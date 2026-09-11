use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;

use parking_lot::Mutex;
use tokio::sync::mpsc;

use ssh_core::{LocalForward, RemoteForward, SshClient};
use term_core::{
    encode_key, encode_mouse, encode_paste, Emulator, EmulatorEvent, InterceptOptions, Key, KeyEvent, KeyKind,
    Modifiers, MouseButton, MouseEvent, Palette, PromptMark, SelectionKind, ViewPoint, CELL_BYTES, HEADER_BYTES,
};

use crate::emulator;
use crate::runtime::{block_on, RUNTIME};
use crate::sftp::SftpClient;
use crate::external::{ExternalPipe, ExternalSink, ExternalWriter};
use crate::securitykey::{ForeignToken, SecurityKeyCredential, SecurityKeyToken};
use crate::telnet::TelnetIo;
use crate::transport::{Event, PtyIo, Reader, Writer};
use crate::CoreError;

// ---------------------------------------------------------------------------
// Configuration records
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, uniffi::Enum)]
pub enum AuthMethod {
    Password { password: String },
    /// `certificate` is the CA's line for this key ("ssh-ed25519-cert-v01@openssh.com
    /// AAAA…"), offered instead of the bare public key; empty means there is none.
    Key { private_key: String, passphrase: Option<String>, certificate: String },
    /// A key the app holds but cannot read — Android's keystore signs for it.
    Keystore { signer: Arc<dyn KeystoreSigner>, certificate: String },
    /// A key that is not on the phone at all: a FIDO2 security key signs for it
    /// over USB or NFC, and `comment` is what the `authorized_keys` line ends in.
    SecurityKey {
        credential: SecurityKeyCredential,
        token: Arc<dyn SecurityKeyToken>,
        comment: String,
        certificate: String,
    },
}

/// Decides whether a program on the far side may sign with a forwarded key.
///
/// Implemented on the app side because the answer is a person's: agent
/// forwarding lets whoever controls that machine sign as you while you are
/// connected, so the interesting case is the one where somebody is asked about
/// it. Called on a blocking thread and free to take as long as the person does.
#[uniffi::export(with_foreign)]
pub trait AgentApproval: Send + Sync + std::fmt::Debug {
    /// `key_comment` is what the key's `authorized_keys` line ends in, and
    /// `fingerprint` its `SHA256:…`, so a prompt can say which key is about to
    /// be used.
    fn allow_sign(&self, key_comment: String, fingerprint: String) -> bool;
}

/// Carries [`AgentApproval`] across to the core's own policy trait.
#[derive(Debug)]
struct ForeignAgentPolicy(Arc<dyn AgentApproval>);

impl ssh_core::AgentPolicy for ForeignAgentPolicy {
    fn allow_sign(&self, key_comment: &str, fingerprint: &str) -> bool {
        self.0.allow_sign(key_comment.to_string(), fingerprint.to_string())
    }
}

/// Signs with a key that never leaves the device's keystore.
///
/// Implemented on the app side, because that is where the platform's keystore
/// lives; the core only hands over the bytes to be signed and takes back a
/// finished SSH signature blob.
#[uniffi::export(with_foreign)]
pub trait KeystoreSigner: Send + Sync + std::fmt::Debug {
    /// The public key in OpenSSH one-line form.
    fn public_key(&self) -> String;
    /// The SSH signature blob for `data`; an error message means "refused",
    /// which is what a cancelled biometric prompt amounts to.
    fn sign(&self, data: Vec<u8>) -> Result<Vec<u8>, CoreError>;
}

#[derive(Debug, Clone, Copy, uniffi::Enum)]
pub enum ProxyKind {
    Socks5,
    Http,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ProxyConfig {
    pub kind: ProxyKind,
    pub host: String,
    pub port: u16,
    pub username: Option<String>,
    pub password: Option<String>,
}

/// One intermediate host on the way to the target (`ssh -J`). Hops are
/// connected in order: the first directly (through its proxy, if any), each
/// following one through a `direct-tcpip` channel of the previous.
/// One place a host can be reached at, with the route to use for it.
///
/// A machine often has more than one address — a LAN address at home, a tailnet
/// name from outside — and which one works depends on where the phone is. They
/// are tried in order, each with its own VPN (or none), and the first that
/// answers wins.
/// How a connection step ended.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum StepStatus {
    /// Still happening.
    Running,
    /// Finished, and worked.
    Done,
    /// Did not work, but the connection carried on another way.
    Warning,
    /// Did not work, and this is where that route stopped.
    Failed,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct Endpoint {
    /// Shown while connecting ("Home LAN"); the address is used when blank.
    pub label: String,
    pub host: String,
    pub port: u16,
    pub tunnel_id: Option<String>,
    pub tailscale_id: Option<String>,
    /// What to call the tunnel or tailnet in the connection steps; an id means
    /// nothing to anyone reading them.
    pub vpn_name: String,
    /// Try this address directly first and fall back to its tunnel only when
    /// that fails, rather than going through the tunnel from the start.
    pub tunnel_fallback: bool,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct JumpHop {
    pub host: String,
    pub port: u16,
    pub username: String,
    pub auth: Vec<AuthMethod>,
    /// Run on this hop before connecting onwards (e.g. a wake-on-LAN script).
    pub pre_command: Option<String>,
    /// Keep retrying the onward connection for this long (the next machine may be booting).
    pub wait_for_next_secs: u32,
    pub proxy: Option<ProxyConfig>,
    /// Reach this hop through a registered WireGuard tunnel (only meaningful for the first hop).
    pub tunnel_id: Option<String>,
    /// Dial this hop inside a Tailscale profile's node, by profile id (first hop only).
    pub tailscale_id: Option<String>,
    /// Offer the phone's keys to this hop through agent forwarding.
    pub forward_agent: bool,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SshConfig {
    pub host: String,
    pub port: u16,
    /// What to call the host's own address in the steps, when it has a name of
    /// its own. Empty for the address the host was configured with.
    pub label: String,
    pub username: String,
    pub auth: Vec<AuthMethod>,
    pub keepalive_secs: u32,
    pub connect_timeout_secs: u32,
    pub proxy: Option<ProxyConfig>,
    pub jumps: Vec<JumpHop>,
    /// Reach the host (or the first hop) through a registered WireGuard tunnel.
    pub tunnel_id: Option<String>,
    /// For direct connections: keep retrying for this long (the machine may be waking up).
    pub wait_for_host_secs: u32,
    /// Dial the host (or the first hop) inside a Tailscale profile's node, by profile id.
    pub tailscale_id: Option<String>,
    /// Further addresses to try, in order, when the first does not answer.
    /// Only used for a direct connection: with a jump chain the route is the chain.
    pub alternates: Vec<Endpoint>,
    /// Name of the tunnel or tailnet this host's own address uses, for the steps.
    pub vpn_name: String,
    /// With `tunnel_id`: try a short direct connection first and only fall back to the tunnel
    /// when that fails (host names / public addresses whose reachability is unknown up front).
    pub tunnel_fallback: bool,
    /// Offer the phone's keys to the host through agent forwarding (`ssh -A`).
    pub forward_agent: bool,
    /// Private keys (OpenSSH/PEM text, with optional passphrase) the agent may sign with.
    pub agent_keys: Vec<AuthMethod>,
    /// Asked before each forwarded signature; without one the agent signs
    /// whenever the far side asks, which is what `ssh -A` does by default.
    pub agent_approval: Option<Arc<dyn AgentApproval>>,
    /// Asked for on the shell channel before the pty. Whether they arrive is the
    /// server's decision (`AcceptEnv`), so nothing here is a promise.
    pub env: Vec<EnvVar>,
    /// What to call this terminal in the pty request. Empty for the default,
    /// which is what almost everything wants; `screen-256color` inside tmux and
    /// `xterm-kitty` for programs that look for the kitty protocols by name.
    pub term: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct EnvVar {
    pub name: String,
    pub value: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct LocalShellConfig {
    pub program: String,
    pub args: Vec<String>,
    pub env: Vec<EnvVar>,
    pub cwd: Option<String>,
    /// `TERM` for the shell. Empty for the default; see [`SshConfig::term`].
    pub term: String,
}

/// A plain telnet console (switches, PDUs, BMCs). No auth: the device prompts.
#[derive(Debug, Clone, uniffi::Record)]
pub struct TelnetConfig {
    pub host: String,
    pub port: u16,
    pub connect_timeout_secs: u32,
    /// Keep retrying for this many seconds while the device boots.
    pub wait_for_host_secs: u32,
    pub tunnel_id: Option<String>,
    pub tailscale_id: Option<String>,
}

/// A transport the app owns and drives (USB serial); see [`ExternalSink`].
#[derive(Debug, Clone, uniffi::Record)]
pub struct ExternalConfig {
    /// Shown in the connection log, e.g. "USB serial · CP2102 · 115200 8N1".
    pub label: String,
}

/// Mosh: bootstrap `mosh-server` over SSH, then leave SSH behind and speak UDP.
#[derive(Debug, Clone, uniffi::Record)]
pub struct MoshConfig {
    /// How to reach the host to start the server. The UDP session then goes
    /// straight to `ssh.host`, so a jump host or tunnel cannot carry it.
    pub ssh: SshConfig,
    /// Passed to `mosh-server -l LANG=…`; a UTF-8 locale keeps box drawing intact.
    pub locale: String,
    /// The server command; empty means `mosh-server` from the remote `PATH`.
    pub server: String,
}

#[derive(Debug, Clone, uniffi::Enum)]
pub enum Backend {
    Ssh { config: SshConfig },
    Mosh { config: MoshConfig },
    Local { config: LocalShellConfig },
    Telnet { config: TelnetConfig },
    External { config: ExternalConfig },
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct HostKey {
    pub host: String,
    pub port: u16,
    pub key_type: String,
    pub key_base64: String,
    pub fingerprint: String,
}

/// Implemented on the Kotlin side. May block (e.g. show a dialog).
#[uniffi::export(foreign)]
pub trait HostKeyVerifier: Send + Sync {
    fn verify(&self, key: HostKey) -> bool;
}

/// One field of a login question, as the server worded it.
#[derive(Debug, Clone, uniffi::Record)]
pub struct Prompt {
    pub text: String,
    /// Whether what is typed may be shown. False for a password or a code.
    pub echo: bool,
}

/// Answers what the server asks during login — a verification code, a password
/// that has expired, whatever the far side decided to ask for.
///
/// Implemented on the app side, because the answer is a person's and nothing in
/// the core can invent one. Called on a blocking thread and free to take as long
/// as they do; `None` means they cancelled, and the connection stops there.
#[uniffi::export(with_foreign)]
pub trait AuthPrompter: Send + Sync + std::fmt::Debug {
    fn ask(&self, name: String, instruction: String, prompts: Vec<Prompt>) -> Option<Vec<String>>;
}

#[derive(Debug, Clone, uniffi::Enum)]
pub enum SessionState {
    Connecting,
    Connected,
    Disconnected { exit_code: Option<i32>, error: Option<String> },
}

#[uniffi::export(foreign)]
pub trait SessionListener: Send + Sync {
    /// The grid changed; the view should pull a fresh snapshot on its next frame.
    fn on_damage(&self);

    /// A command slow enough to walk away from has ended. Only sent when the
    /// app asked for it with [`Session::watch_commands`].
    fn on_command_finished(&self, elapsed_millis: u64, exit_status: Option<i32>);
    fn on_state(&self, state: SessionState);
    fn on_title(&self, title: Option<String>);
    fn on_bell(&self);
    /// The remote side wrote to the clipboard (OSC 52).
    fn on_clipboard(&self, text: String);
    /// One step of the connection.
    ///
    /// Steps that belong together share a `key`: the app replaces the step of
    /// that name rather than adding another, so "trying this address" becomes
    /// "connected through the tunnel" in place instead of leaving a trail of
    /// half-truths behind it.
    fn on_progress(&self, key: String, message: String, status: StepStatus);
    /// A line of output matched one of the watch patterns (see `set_watch_patterns`).
    fn on_pattern(&self, pattern: String, line: String);
    /// A program asked for a desktop notification (OSC 9, 99 or 777).
    fn on_notify(&self, title: String, body: String);
    /// A shell said where it is in the prompt/command cycle (OSC 133).
    ///
    /// `exit` is only ever set on [`PromptKind::Finished`], and only when the
    /// shell bothered to report one.
    fn on_prompt_mark(&self, kind: PromptKind, exit: Option<i32>);
    /// A shell said which directory it is in (OSC 7), as an absolute path.
    ///
    /// Only shells that have been asked to send it ever do, so a session that
    /// never calls this is the normal case rather than a broken one.
    fn on_cwd(&self, path: String);
    /// The server's pre-login banner, as it sent it.
    fn on_banner(&self, text: String);
    /// The image store changed; the view should ask for the placements again.
    fn on_images_changed(&self);
}

/// Where a shell said it was, in the OSC 133 sense.
#[derive(Debug, Clone, Copy, uniffi::Enum)]
pub enum PromptKind {
    /// A new prompt is being drawn.
    PromptStart,
    /// The prompt is drawn; what follows was typed by the person.
    CommandStart,
    /// The command is running and what follows is its output.
    OutputStart,
    /// The command finished.
    Finished,
}

/// A prompt mark and where it currently is, for jumping between prompts and
/// for selecting the output of one command.
///
/// `row` is counted from the top visible line, so a mark that has scrolled
/// into the scrollback reports a negative row, as an image placement does.
/// Positions come out of the grid rather than being remembered, so this is
/// where the text a mark was printed against has ended up.
#[derive(Debug, Clone, Copy, uniffi::Record)]
pub struct PromptMarkAt {
    pub kind: PromptKind,
    /// Only ever set on [`PromptKind::Finished`], and only when the shell
    /// reported one.
    pub exit: Option<i32>,
    pub row: i32,
}

/// What a mark says, in the two halves the app is handed.
fn split_mark(m: PromptMark) -> (PromptKind, Option<i32>) {
    match m {
        PromptMark::PromptStart => (PromptKind::PromptStart, None),
        PromptMark::CommandStart => (PromptKind::CommandStart, None),
        PromptMark::OutputStart => (PromptKind::OutputStart, None),
        PromptMark::Finished(code) => (PromptKind::Finished, code),
    }
}

/// A copy of everything the terminal receives, for whoever asked for it.
///
/// The emulator eats the byte stream and keeps only a grid, so anything that
/// needs the stream itself — a recording that must replay with its colors and
/// its timing, a watcher for the prompt coming back — has to be handed the
/// bytes as they arrive. Nothing is copied unless somebody is listening.
#[uniffi::export(with_foreign)]
pub trait OutputSink: Send + Sync {
    fn on_output(&self, bytes: Vec<u8>);
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct PaletteConfig {
    /// Exactly 16 ANSI colors as 0x00RRGGBB.
    pub ansi: Vec<u32>,
    pub foreground: u32,
    pub background: u32,
    pub cursor: u32,
    pub selection: u32,
}

impl PaletteConfig {
    fn to_palette(&self) -> Palette {
        let mut ansi = Palette::default().colors[..16].try_into().unwrap_or([0u32; 16]);
        for (i, c) in self.ansi.iter().take(16).enumerate() {
            ansi[i] = *c;
        }
        Palette::from_ansi(ansi, self.foreground, self.background, self.cursor, self.selection)
    }
}

#[derive(Debug, Clone, Copy, uniffi::Enum)]
pub enum KeyCode {
    Char { codepoint: u32 },
    Enter,
    Tab,
    Backspace,
    Escape,
    Up,
    Down,
    Left,
    Right,
    Home,
    End,
    PageUp,
    PageDown,
    Insert,
    Delete,
    Function { n: u8 },
    /// A modifier struck on its own; only the kitty protocol can report it.
    Modifier { which: ModifierKey },
}

/// The modifier keys the kitty protocol numbers individually.
#[derive(Debug, Clone, Copy, uniffi::Enum)]
pub enum ModifierKey {
    LeftShift,
    RightShift,
    LeftControl,
    RightControl,
    LeftAlt,
    RightAlt,
    LeftSuper,
    RightSuper,
}

/// Press, auto-repeat or release. A program sees the last two only after
/// asking for kitty event types; everywhere else a repeat is another press and
/// a release is nothing.
#[derive(Debug, Clone, Copy, uniffi::Enum)]
pub enum KeyEventKind {
    Press,
    Repeat,
    Release,
}

#[derive(Debug, Clone, Copy, uniffi::Record)]
pub struct KeyPress {
    pub key: KeyCode,
    pub ctrl: bool,
    pub alt: bool,
    pub shift: bool,
    pub kind: KeyEventKind,
}

#[derive(Debug, Clone, Copy, uniffi::Enum)]
pub enum SelectKind {
    Simple,
    Word,
    Lines,
    Block,
}

#[derive(Debug, Clone, Copy, uniffi::Enum)]
pub enum PointerButton {
    Left,
    Middle,
    Right,
}

/// One cell drawn by predictive echo, ahead of the server confirming it.
#[derive(Debug, Clone, Copy, uniffi::Record)]
pub struct PredictedCell {
    pub row: u16,
    pub col: u16,
    pub codepoint: u32,
}

/// One inline image, where it currently is on the visible grid.
///
/// The pixels are fetched separately with [`Session::image_bytes`]: this
/// record is asked for on every frame that has images on it, and an image's
/// bytes are wanted once. `id` and `generation` together name the pixels, so
/// the renderer can key a cached bitmap on them.
///
/// `row` and `col` may be negative when the image has scrolled partly out of
/// the viewport; `src` is the rectangle of the image to draw, in pixels, which
/// is the whole of it unless the program asked for a crop.
#[derive(Debug, Clone, Copy, uniffi::Record)]
pub struct ImagePlacement {
    pub id: u32,
    pub generation: u32,
    pub col: i32,
    pub row: i32,
    pub cols: u16,
    pub rows: u16,
    /// Below the text when negative, above it otherwise.
    pub z: i32,
    pub width: u32,
    pub height: u32,
    pub src_x: u32,
    pub src_y: u32,
    pub src_width: u32,
    pub src_height: u32,
}

#[derive(Debug, Clone, Copy, uniffi::Enum)]
pub enum PredictionMode {
    /// Predict only when the round trip is long enough to notice.
    Adaptive,
    Always,
    Never,
}

#[derive(Debug, Clone, Copy, uniffi::Record)]
pub struct Modes {
    pub alt_screen: bool,
    pub mouse_reporting: bool,
    pub bracketed_paste: bool,
    pub app_cursor: bool,
    /// Kitty keyboard flags the program has asked for: 1 disambiguate,
    /// 2 event types, 4 alternate keys, 8 all keys as escapes, 16 text.
    pub kitty_flags: u8,
    /// xterm modifyOtherKeys level (0, 1 or 2).
    pub modify_other_keys: u8,
}

/// What a session is allowed to do beyond plain terminal behavior.
///
/// The terminal name to announce, with the default filled in.
///
/// Every backend asks the same question, and an empty setting has to mean the
/// default rather than an empty `TERM` — which would leave programs guessing at
/// a dumb terminal.
fn term_name(configured: &str) -> &str {
    let t = configured.trim();
    if t.is_empty() { DEFAULT_TERM } else { t }
}

/// What this terminal calls itself when nothing says otherwise.
pub const DEFAULT_TERM: &str = "xterm-256color";

/// Everything here is a switch in the app, so the record travels with the
/// session rather than being read from a global: two hosts open at once may
/// disagree about every one of these.
#[derive(Debug, Clone, Copy, uniffi::Record)]
pub struct Options {
    /// Speak the kitty keyboard protocol and xterm's modifyOtherKeys.
    pub keyboard_protocol: bool,
    /// Send Ctrl+[, Ctrl+I and Ctrl+M as keys of their own rather than as the
    /// Escape, Tab and Enter bytes, even when nothing has asked for a protocol
    /// that tells them apart.
    pub fixterms_ctrl_keys: bool,
    /// Let a program raise a notification with an escape sequence.
    pub notifications: bool,
    /// Read the OSC 133 shell marks.
    pub prompt_marks: bool,
    /// Read the OSC 7 working directory.
    pub working_directory: bool,
    /// Answer the kitty graphics protocol.
    pub kitty_images: bool,
    /// Answer sixel.
    pub sixel_images: bool,
    /// Draw bold text in the bright half of the palette.
    pub bold_is_bright: bool,
    /// Pass on what the server prints before login. Off means the banner is
    /// never asked for, never kept and never shown.
    pub show_banner: bool,
}

impl Default for Options {
    fn default() -> Self {
        // What a terminal that has been asked for nothing should do: speak the
        // protocols a program has to opt into anyway, and change no colors.
        Self {
            keyboard_protocol: true,
            fixterms_ctrl_keys: true,
            notifications: true,
            prompt_marks: true,
            working_directory: true,
            kitty_images: true,
            sixel_images: true,
            bold_is_bright: false,
            show_banner: true,
        }
    }
}

impl Options {
    fn intercept(&self) -> InterceptOptions {
        InterceptOptions {
            notifications: self.notifications,
            prompt_marks: self.prompt_marks,
            working_directory: self.working_directory,
            kitty_images: self.kitty_images,
            sixel_images: self.sixel_images,
            keyboard_protocol: self.keyboard_protocol,
            // A terminal told not to speak the protocol at all should not be
            // sending these keys' sequences anyway.
            fixterms_ctrl_keys: self.keyboard_protocol && self.fixterms_ctrl_keys,
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ForwardInfo {
    pub id: u64,
    /// Actual bound port (useful when 0 was requested).
    pub port: u16,
}

// ---------------------------------------------------------------------------
// Session
// ---------------------------------------------------------------------------

#[allow(dead_code)]
enum Forward {
    Local(LocalForward),
    Remote(RemoteForward),
}

struct Inner {
    backend: Backend,
    listener: Arc<dyn SessionListener>,
    verifier: Arc<dyn HostKeyVerifier>,
    prompter: Arc<dyn AuthPrompter>,
    emu: Mutex<Box<dyn Emulator>>,
    palette: Mutex<Palette>,
    dirty: AtomicBool,
    state: Mutex<SessionState>,
    title: Mutex<Option<String>>,
    size: Mutex<(u16, u16)>,
    writer: Mutex<Option<Writer>>,
    input_tx: mpsc::UnboundedSender<Vec<u8>>,
    input_rx: Mutex<Option<mpsc::UnboundedReceiver<Vec<u8>>>>,
    ssh: tokio::sync::Mutex<Option<Arc<SshClient>>>,
    /// Jump-host sessions the target connection is tunnelled through; kept alive with it.
    jumps: tokio::sync::Mutex<Vec<Arc<SshClient>>>,
    forwards: Mutex<HashMap<u64, Forward>>,
    next_forward: AtomicU64,
    closed: AtomicBool,
    /// Output watch: compiled regexes and the current partial line (escape sequences stripped).
    /// Whether anything at all wants a look at each chunk of output.
    ///
    /// The three below — pattern watching, the command watch, the recording
    /// sink — are all off in an ordinary session, and taking a mutex for each
    /// of them on every chunk to find that out is most of what this function
    /// used to do. The flag is the fast answer; the mutexes still hold the
    /// truth and are taken once it says yes.
    taps: AtomicBool,
    watch: Mutex<Vec<(String, regex::Regex)>>,
    watch_line: Mutex<WatchLine>,
    /// Set while the app wants to be told about long commands; see
    /// [`crate::command_watch`] for why the watching happens on this side.
    command_watch: Mutex<Option<crate::command_watch::CommandWatch>>,
    /// The user's colour rules, and what they last found on each row.
    highlight: Mutex<crate::highlight::Highlighter>,
    /// Links found on the visible grid, kept against the rows they came from.
    link_cache: Mutex<crate::links::LinkCache>,
    /// Endpoint for `Backend::External`; unused by the other backends.
    external: Arc<ExternalPipe>,
    /// Which tunnel the first hop actually went through, once decided. "Only
    /// when needed" can settle on a direct connection, and anything that dials
    /// again later (Mosh's UDP session) must make the same choice.
    used_tunnel: Mutex<Option<String>>,
    /// The address that answered, out of the host's list. Mosh dials the machine
    /// a second time over UDP, and SFTP on a Mosh session dials it again over
    /// SSH — both have to go where the terminal actually went, not to the first
    /// address in the list.
    used_endpoint: Mutex<Option<Endpoint>>,
    /// Names one-off progress steps apart from each other.
    next_step: AtomicU64,
    /// What the host answered when asked what it runs, if it was asked.
    detected_os: Mutex<Option<String>>,
    /// An SSH connection kept open beside a Mosh session purely so its forwarded
    /// agent socket stays alive; dropped with the session.
    agent_hold: tokio::sync::Mutex<Option<(Arc<SshClient>, russh::Channel<russh::client::Msg>)>>,
    /// Predictive local echo. Only ever populated for a Mosh session, and only
    /// consulted by the renderer — never fed to the emulator.
    predict: Mutex<mosh::Predictor>,
    /// Wall clock for the predictor, in milliseconds since the session started.
    started: std::time::Instant,
    /// The phone's traffic, while this session is being used as a VPN.
    vpn: Mutex<Option<crate::vpn::Vpn>>,
    /// Set while the app wants the raw output stream as well as the grid.
    sink: Mutex<Option<Arc<dyn OutputSink>>>,
    /// What this session is allowed to do beyond plain terminal behavior.
    options: Mutex<Options>,
}

#[derive(Default)]
struct WatchLine {
    text: String,
    /// Inside an escape sequence: 0 = no, 1 = after ESC, 2 = CSI, 3 = OSC.
    esc: u8,
}

/// Where a packed grid sits in memory, for a caller that will read it directly.
#[derive(Debug, Clone, Copy, uniffi::Record)]
pub struct SnapshotSpan {
    /// Valid until the next [`Session::snapshot_into`] on the same buffer, and
    /// only for as long as the buffer itself is alive.
    pub ptr: u64,
    pub len: u64,
}

/// A grid-sized buffer the renderer keeps and the core fills.
///
/// Owned by whoever draws, not by the session, so that letting go of a session
/// cannot pull the memory out from under a frame that is still being painted.
#[derive(uniffi::Object, Default)]
pub struct SnapshotBuffer {
    data: Mutex<Vec<u8>>,
}

#[uniffi::export]
impl SnapshotBuffer {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self::default())
    }
}

#[derive(uniffi::Object)]
pub struct Session {
    inner: Arc<Inner>,
}

/// Hands the server's pre-login banner to the app, the moment it arrives.
///
/// Both halves are worth having: the text itself, which the connection sheet
/// shows with its URLs tappable, and a step saying it happened, so the record
/// of the connection has it in the order it came in.
struct Banner(Arc<dyn SessionListener>);

impl std::fmt::Debug for Banner {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("Banner")
    }
}

impl ssh_core::BannerSink for Banner {
    fn banner(&self, text: &str) {
        if text.trim().is_empty() {
            return;
        }
        self.0.on_banner(text.to_string());
        self.0.on_progress("banner".into(), "The server sent a message".into(), StepStatus::Done);
    }
}

/// Carries [`AuthPrompter`] across to the core's own trait.
#[derive(Debug)]
struct Prompter(Arc<dyn AuthPrompter>);

impl ssh_core::AuthPrompter for Prompter {
    fn ask(&self, name: &str, instruction: &str, prompts: &[ssh_core::Prompt]) -> Option<Vec<String>> {
        let prompts = prompts.iter().map(|p| Prompt { text: p.text.clone(), echo: p.echo }).collect();
        self.0.ask(name.to_string(), instruction.to_string(), prompts)
    }
}

struct Verifier(Arc<dyn HostKeyVerifier>);
impl ssh_core::HostKeyVerifier for Verifier {
    fn verify(&self, info: &ssh_core::HostKeyInfo) -> bool {
        self.0.verify(HostKey {
            host: info.host.clone(),
            port: info.port,
            key_type: info.key_type.clone(),
            key_base64: info.key_base64.clone(),
            fingerprint: info.fingerprint.clone(),
        })
    }
}

#[uniffi::export]
impl Session {
    #[uniffi::constructor]
    pub fn new(
        backend: Backend,
        cols: u16,
        rows: u16,
        scrollback: u32,
        listener: Arc<dyn SessionListener>,
        verifier: Arc<dyn HostKeyVerifier>,
        prompter: Arc<dyn AuthPrompter>,
        options: Options,
    ) -> Arc<Self> {
        let (input_tx, input_rx) = mpsc::unbounded_channel();
        let mut emu = emulator::new(cols.max(2), rows.max(1), scrollback as usize);
        emu.set_intercept(options.intercept());
        emu.set_bold_is_bright(options.bold_is_bright);
        Arc::new(Self {
            inner: Arc::new(Inner {
                backend,
                listener,
                verifier,
                prompter,
                emu: Mutex::new(emu),
                palette: Mutex::new(Palette::default()),
                dirty: AtomicBool::new(true),
                state: Mutex::new(SessionState::Connecting),
                title: Mutex::new(None),
                size: Mutex::new((cols.max(2), rows.max(1))),
                writer: Mutex::new(None),
                input_tx,
                input_rx: Mutex::new(Some(input_rx)),
                ssh: tokio::sync::Mutex::new(None),
                jumps: tokio::sync::Mutex::new(Vec::new()),
                forwards: Mutex::new(HashMap::new()),
                next_forward: AtomicU64::new(1),
                closed: AtomicBool::new(false),
                taps: AtomicBool::new(false),
                watch: Mutex::new(Vec::new()),
                watch_line: Mutex::new(WatchLine::default()),
                command_watch: Mutex::new(None),
                highlight: Mutex::new(Default::default()),
                link_cache: Mutex::new(Default::default()),
                external: Arc::new(ExternalPipe::new()),
                used_tunnel: Mutex::new(None),
                used_endpoint: Mutex::new(None),
                next_step: AtomicU64::new(0),
                detected_os: Mutex::new(None),
                agent_hold: tokio::sync::Mutex::new(None),
                predict: Mutex::new(mosh::Predictor::new(cols.max(2))),
                started: std::time::Instant::now(),
                vpn: Mutex::new(None),
                sink: Mutex::new(None),
                options: Mutex::new(options),
            }),
        })
    }

    /// Change what the session is allowed to do while it is up, so a switch
    /// flipped in settings reaches a terminal already on screen.
    pub fn set_options(&self, options: Options) {
        *self.inner.options.lock() = options;
        let mut emu = self.inner.emu.lock();
        emu.set_intercept(options.intercept());
        emu.set_bold_is_bright(options.bold_is_bright);
        drop(emu);
        self.inner.mark_dirty();
    }

    pub fn options(&self) -> Options {
        *self.inner.options.lock()
    }

    /// For `Backend::External`: where the terminal's keystrokes should go.
    /// Attach this before [`Session::start`].
    pub fn set_external_sink(&self, sink: Arc<dyn ExternalSink>) {
        self.inner.external.set_sink(sink);
    }

    /// For `Backend::External`: bytes read from the device, on their way to the screen.
    pub fn push_output(&self, data: Vec<u8>) {
        self.inner.external.push(data);
    }

    /// For `Backend::External`: the device is gone, so end the session.
    pub fn external_closed(&self) {
        self.inner.external.finish();
    }

    /// Connect (or spawn the local shell) in the background. State changes
    /// arrive through the listener.
    pub fn start(&self) {
        let inner = self.inner.clone();
        RUNTIME.spawn(async move {
            match Inner::connect(inner.clone()).await {
                Ok(reader) => {
                    inner.set_state(SessionState::Connected);
                    inner.pump(reader).await;
                }
                Err(e) => {
                    log::warn!("session connect failed: {e}");
                    inner.set_state(SessionState::Disconnected { exit_code: None, error: Some(e.to_string()) });
                }
            }
        });
    }

    pub fn state(&self) -> SessionState {
        self.inner.state.lock().clone()
    }

    /// What the host said it runs, once it has been asked. Empty until then.
    pub fn detected_os(&self) -> Option<String> {
        self.inner.detected_os.lock().clone()
    }

    /// Add a step to the connection log, shown in the connection details.
    pub fn note(&self, message: String) {
        self.inner.progress(message);
    }

    /// Connect (walking the jump chain), run one command, disconnect. Returns
    /// stdout+stderr; a non-zero exit status is an error. Blocks the caller.
    pub fn exec_once(&self, command: String) -> Result<String, CoreError> {
        let inner = self.inner.clone();
        block_on(async move {
            let config = match &inner.backend {
                Backend::Ssh { config } => config.clone(),
                _ => return Err(CoreError::Other("not an SSH session".into())),
            };
            let client = inner.connect_ssh(&config).await?;
            let result = client.exec(&command).await;
            client.disconnect().await;
            inner.close_jumps().await;
            let (out, status) = result?;
            let text = String::from_utf8_lossy(&out).into_owned();
            if status != 0 {
                Err(CoreError::Other(format!("command exited with {status}: {}", text.trim())))
            } else {
                Ok(text)
            }
        })
    }

    /// Run one command on the connection this session already has and return
    /// stdout+stderr; a non-zero exit status is an error. Blocks the caller.
    ///
    /// Unlike [`Session::exec_once`] it dials nothing — it costs one channel and
    /// no second login, which is what makes it cheap enough to ask a connected
    /// session a question about itself. There is nothing to borrow before the
    /// session is up, on a Mosh session that has left SSH behind, or on any
    /// other backend, and all three answer `NotConnected`.
    pub fn exec_live(&self, command: String) -> Result<String, CoreError> {
        let inner = self.inner.clone();
        block_on(async move {
            let client = inner.ssh.lock().await.clone().ok_or(CoreError::NotConnected)?;
            let (out, status) = client.exec(&command).await?;
            let text = String::from_utf8_lossy(&out).into_owned();
            if status != 0 {
                Err(CoreError::Other(format!("command exited with {status}: {}", text.trim())))
            } else {
                Ok(text)
            }
        })
    }

    pub fn title(&self) -> Option<String> {
        self.inner.title.lock().clone()
    }

    // ---- output ----------------------------------------------------------

    /// Pack the grid into [`SnapshotBuffer`] and say where it landed.
    ///
    /// The frame loop asks for this once per drawn frame, and the grid is tens
    /// of kilobytes: returning it as a `Vec<u8>` meant the bridge minted a new
    /// Java array every frame, large enough to go straight to the large-object
    /// heap and be collected again a moment later. Filling a buffer the caller
    /// already owns and handing back where it is copies nothing and allocates
    /// nothing once the buffer has grown to size.
    ///
    /// The buffer belongs to the caller on purpose: it has to outlive this
    /// session, because a session can be destroyed from another thread while a
    /// frame that is already reading the pointer is still on its way out.
    pub fn snapshot_into(&self, out: Arc<SnapshotBuffer>) -> SnapshotSpan {
        self.inner.dirty.store(false, Ordering::Release);
        let (cols, rows) = *self.inner.size.lock();
        let mut buf = out.data.lock();
        buf.clear();
        buf.reserve(HEADER_BYTES + cols as usize * rows as usize * CELL_BYTES);
        let palette = self.inner.palette.lock();
        self.inner.emu.lock().snapshot(&palette, &mut buf);
        // Read after filling: growing the buffer moves it.
        SnapshotSpan { ptr: buf.as_ptr() as u64, len: buf.len() as u64 }
    }

    /// Packed grid (see `term_core::snapshot`). Clears the damage flag.
    pub fn snapshot(&self) -> Vec<u8> {
        self.inner.dirty.store(false, Ordering::Release);
        // Sized up front and the palette borrowed rather than copied: this runs
        // once per displayed frame, so a grid's worth of reallocation and a
        // kilobyte of palette per call is a steady drain for nothing.
        let (cols, rows) = *self.inner.size.lock();
        let mut out = Vec::with_capacity(HEADER_BYTES + cols as usize * rows as usize * CELL_BYTES);
        let palette = self.inner.palette.lock();
        self.inner.emu.lock().snapshot(&palette, &mut out);
        out
    }

    /// Images on the visible grid, back to front. Cheap enough to ask for on
    /// every frame: it is a scan of the visible cells and carries no pixels.
    pub fn images(&self) -> Vec<ImagePlacement> {
        self.inner
            .emu
            .lock()
            .images()
            .into_iter()
            .map(|p| ImagePlacement {
                id: p.id,
                generation: p.generation,
                col: p.col,
                row: p.row,
                cols: p.cols,
                rows: p.rows,
                z: p.z,
                width: p.width,
                height: p.height,
                src_x: p.src.0,
                src_y: p.src.1,
                src_width: p.src.2,
                src_height: p.src.3,
            })
            .collect()
    }

    /// The shell's prompt marks (OSC 133) still in the buffer, in row order.
    ///
    /// The scrollback is included, because the prompt worth jumping back to is
    /// usually one that has scrolled off the top; a mark up there reports a
    /// negative row. Empty for the whole life of a session whose shell never
    /// sends the marks, which is most of them — everything reading this has to
    /// work without them.
    pub fn marks(&self) -> Vec<PromptMarkAt> {
        self.inner
            .emu
            .lock()
            .prompt_marks()
            .into_iter()
            .map(|m| {
                let (kind, exit) = split_mark(m.mark);
                PromptMarkAt { kind, exit, row: m.row }
            })
            .collect()
    }

    /// The pixels behind an image, as row-major RGBA. Empty once that
    /// generation of the image is gone, which is the renderer's cue to drop
    /// the bitmap it cached.
    pub fn image_bytes(&self, id: u32, generation: u32) -> Vec<u8> {
        self.inner.emu.lock().image_bytes(id, generation).unwrap_or_default().to_vec()
    }

    /// How many pixels one character cell measures, so the core can say how
    /// many rows and columns an image covers. Called when the font changes.
    pub fn set_cell_size(&self, width: u32, height: u32) {
        self.inner.emu.lock().set_cell_size(width, height);
    }

    /// Cells predictive echo is drawing ahead of the server, as a flat list.
    /// Empty unless this is a Mosh session on a slow enough link.
    pub fn predictions(&self) -> Vec<PredictedCell> {
        let mut p = self.inner.predict.lock();
        p.expire(self.inner.millis());
        p.overlay().map(|c| PredictedCell { row: c.row, col: c.col, codepoint: c.ch as u32 }).collect()
    }

    /// How predictive echo should behave for this session.
    pub fn set_prediction(&self, mode: PredictionMode) {
        self.inner.predict.lock().set_mode(match mode {
            PredictionMode::Adaptive => mosh::PredictMode::Adaptive,
            PredictionMode::Always => mosh::PredictMode::Always,
            PredictionMode::Never => mosh::PredictMode::Never,
        });
        self.inner.mark_dirty();
    }

    pub fn set_palette(&self, palette: PaletteConfig) {
        *self.inner.palette.lock() = palette.to_palette();
        self.inner.mark_dirty();
    }

    pub fn resize(&self, cols: u16, rows: u16) {
        let cols = cols.max(2);
        let rows = rows.max(1);
        {
            let mut size = self.inner.size.lock();
            if *size == (cols, rows) {
                return;
            }
            *size = (cols, rows);
        }
        self.inner.emu.lock().resize(cols, rows);
        self.inner.predict.lock().set_cols(cols);
        self.inner.mark_dirty();
        if let Some(w) = self.inner.writer.lock().clone() {
            RUNTIME.spawn(async move { w.resize(cols, rows).await });
        }
    }

    pub fn modes(&self) -> Modes {
        let m = self.inner.emu.lock().modes();
        Modes {
            alt_screen: m.alt_screen,
            mouse_reporting: m.mouse_reporting,
            bracketed_paste: m.bracketed_paste,
            app_cursor: m.app_cursor,
            kitty_flags: m.kitty_flags,
            modify_other_keys: m.modify_other_keys,
        }
    }

    // ---- input -----------------------------------------------------------

    pub fn write(&self, data: Vec<u8>) {
        self.inner.send(data);
    }

    /// Text committed by the IME; sent verbatim.
    pub fn send_text(&self, text: String) {
        self.inner.send(text.into_bytes());
    }

    pub fn send_key(&self, key: KeyPress) {
        let k = match key.key {
            KeyCode::Char { codepoint } => match char::from_u32(codepoint) {
                Some(c) => Key::Char(c),
                None => return,
            },
            KeyCode::Enter => Key::Enter,
            KeyCode::Tab => Key::Tab,
            KeyCode::Backspace => Key::Backspace,
            KeyCode::Escape => Key::Escape,
            KeyCode::Up => Key::Up,
            KeyCode::Down => Key::Down,
            KeyCode::Left => Key::Left,
            KeyCode::Right => Key::Right,
            KeyCode::Home => Key::Home,
            KeyCode::End => Key::End,
            KeyCode::PageUp => Key::PageUp,
            KeyCode::PageDown => Key::PageDown,
            KeyCode::Insert => Key::Insert,
            KeyCode::Delete => Key::Delete,
            KeyCode::Function { n } => Key::F(n),
            KeyCode::Modifier { which } => Key::Modifier(match which {
                ModifierKey::LeftShift => term_core::ModifierKey::LeftShift,
                ModifierKey::RightShift => term_core::ModifierKey::RightShift,
                ModifierKey::LeftControl => term_core::ModifierKey::LeftControl,
                ModifierKey::RightControl => term_core::ModifierKey::RightControl,
                ModifierKey::LeftAlt => term_core::ModifierKey::LeftAlt,
                ModifierKey::RightAlt => term_core::ModifierKey::RightAlt,
                ModifierKey::LeftSuper => term_core::ModifierKey::LeftSuper,
                ModifierKey::RightSuper => term_core::ModifierKey::RightSuper,
            }),
        };
        let kind = match key.kind {
            KeyEventKind::Press => KeyKind::Press,
            KeyEventKind::Repeat => KeyKind::Repeat,
            KeyEventKind::Release => KeyKind::Release,
        };
        let modes = self.inner.emu.lock().modes();
        let bytes = encode_key(
            KeyEvent { key: k, mods: Modifiers { ctrl: key.ctrl, alt: key.alt, shift: key.shift }, kind },
            &modes,
        );
        if !bytes.is_empty() {
            self.inner.scroll_to_bottom_on_input();
            self.inner.send(bytes);
        }
    }

    pub fn paste(&self, text: String) {
        let modes = self.inner.emu.lock().modes();
        self.inner.scroll_to_bottom_on_input();
        self.inner.send(encode_paste(&text, &modes));
    }

    /// Pointer press/release/drag at a cell. Returns true when the application
    /// consumed it (mouse reporting mode); false means the UI should treat it as
    /// a normal touch (selection, focus, ...).
    pub fn pointer(&self, col: u16, row: u16, button: PointerButton, pressed: bool, motion: bool) -> bool {
        let modes = self.inner.emu.lock().modes();
        if !modes.mouse_reporting {
            return false;
        }
        let button = match button {
            PointerButton::Left => MouseButton::Left,
            PointerButton::Middle => MouseButton::Middle,
            PointerButton::Right => MouseButton::Right,
        };
        let bytes = encode_mouse(
            MouseEvent { button, col, row, pressed, motion, mods: Modifiers::NONE },
            &modes,
        );
        if !bytes.is_empty() {
            self.inner.send(bytes);
        }
        true
    }

    /// Scroll gesture: `lines` > 0 scrolls towards history (content moves down).
    /// Handles mouse-mode wheel reporting and alt-screen arrow emulation.
    pub fn scroll(&self, col: u16, row: u16, lines: i32) {
        if lines == 0 {
            return;
        }
        let modes = self.inner.emu.lock().modes();
        if modes.mouse_reporting {
            let button = if lines > 0 { MouseButton::WheelUp } else { MouseButton::WheelDown };
            let mut out = Vec::new();
            for _ in 0..lines.unsigned_abs().min(64) {
                out.extend(encode_mouse(
                    MouseEvent { button, col, row, pressed: true, motion: false, mods: Modifiers::NONE },
                    &modes,
                ));
            }
            self.inner.send(out);
        } else if modes.alt_screen && modes.alternate_scroll {
            let key = if lines > 0 { Key::Up } else { Key::Down };
            let mut out = Vec::new();
            for _ in 0..lines.unsigned_abs().min(64) {
                out.extend(encode_key(KeyEvent::press(key, Modifiers::NONE), &modes));
            }
            self.inner.send(out);
        } else {
            self.inner.emu.lock().scroll_display(lines);
            self.inner.mark_dirty();
        }
    }

    pub fn scroll_to_bottom(&self) {
        self.inner.emu.lock().scroll_to_bottom();
        self.inner.mark_dirty();
    }

    pub fn display_offset(&self) -> u32 {
        self.inner.emu.lock().display_offset() as u32
    }

    pub fn history_size(&self) -> u32 {
        self.inner.emu.lock().history_size() as u32
    }

    // ---- selection -------------------------------------------------------

    pub fn select_start(&self, col: u16, row: u16, kind: SelectKind) {
        let kind = match kind {
            SelectKind::Simple => SelectionKind::Simple,
            SelectKind::Word => SelectionKind::Word,
            SelectKind::Lines => SelectionKind::Lines,
            SelectKind::Block => SelectionKind::Block,
        };
        self.inner.emu.lock().selection_start(ViewPoint { col, row }, kind);
        self.inner.mark_dirty();
    }

    pub fn select_update(&self, col: u16, row: u16, move_start: bool) {
        self.inner.emu.lock().selection_update(ViewPoint { col, row }, move_start);
        self.inner.mark_dirty();
    }

    pub fn select_all(&self) {
        self.inner.emu.lock().selection_all();
        self.inner.mark_dirty();
    }

    pub fn select_clear(&self) {
        let mut emu = self.inner.emu.lock();
        if emu.has_selection() {
            emu.selection_clear();
            drop(emu);
            self.inner.mark_dirty();
        }
    }

    pub fn has_selection(&self) -> bool {
        self.inner.emu.lock().has_selection()
    }

    pub fn selected_text(&self) -> Option<String> {
        self.inner.emu.lock().selection_text()
    }

    /// The line the cursor is on, up to the cursor, or empty when there is none.
    ///
    /// What the user has typed so far, as far as anyone outside the emulator can
    /// tell: enough to offer a completion against, and it costs one lock.
    pub fn current_input(&self) -> String {
        let emu = self.inner.emu.lock();
        match emu.cursor_row_col() {
            Some((row, col)) => emu.row_text(row).chars().take(col as usize).collect(),
            None => String::new(),
        }
    }

    /// Replace the colour rules, and say which patterns were refused.
    ///
    /// A rule that is switched off is still compiled: a broken pattern has to
    /// be visible in the editor whether or not it is in use.
    pub fn set_highlight_rules(&self, rules: Vec<crate::highlight::HighlightRule>) -> Vec<crate::highlight::HighlightError> {
        self.inner.highlight.lock().set_rules(&rules)
    }

    /// Whether a pattern would be accepted, and why not when it would not.
    pub fn highlight_error(&self, pattern: String) -> Option<String> {
        if pattern.is_empty() {
            return None;
        }
        crate::highlight::compile(&pattern).err()
    }

    /// What the colour rules claim on the visible grid.
    ///
    /// Asked for once a frame alongside [`Self::visible_links`], and cached
    /// against the rows the same way, so a still screen runs no patterns.
    pub fn visible_highlights(&self) -> Vec<crate::highlight::HighlightSpan> {
        let mut hl = self.inner.highlight.lock();
        if hl.is_empty() {
            return Vec::new();
        }
        let (_, rows) = *self.inner.size.lock();
        let texts: Vec<String> = {
            let emu = self.inner.emu.lock();
            (0..rows).map(|r| emu.row_text(r)).collect()
        };
        hl.scan(&texts)
    }

    /// Tappable URLs and paths on the visible grid.
    ///
    /// Asked for once a frame. The rows it reads are right here, and the
    /// answers are cached against them, so a screen that is sitting still
    /// costs nothing and only the rows that moved are looked at again.
    pub fn visible_links(&self) -> Vec<crate::links::LinkSpan> {
        let (cols, rows) = *self.inner.size.lock();
        let texts: Vec<String> = {
            let emu = self.inner.emu.lock();
            (0..rows).map(|r| emu.row_text(r)).collect()
        };
        self.inner.link_cache.lock().scan(&texts, cols)
    }

    pub fn row_text(&self, row: u16) -> String {
        self.inner.emu.lock().row_text(row)
    }

    /// Scrollback plus screen, one string per line (for search).
    pub fn all_lines(&self) -> Vec<String> {
        self.inner.emu.lock().all_lines()
    }

    /// Scroll so that `offset` lines of history are above the top of the screen.
    pub fn scroll_to_offset(&self, offset: u32) {
        self.inner.emu.lock().scroll_to(offset as usize);
        self.inner.mark_dirty();
    }

    /// Start telling this session's listener when a slow command ends.
    ///
    /// `min_millis` is the shortest command worth a word: below it, the command
    /// was over before anyone could look away. Calling it again only changes
    /// that threshold, so a session keeps what it has learned about its shell.
    pub fn watch_commands(&self, min_millis: u64) {
        self.inner.taps.store(true, Ordering::Release);
        let mut w = self.inner.command_watch.lock();
        match w.as_mut() {
            Some(_) => {}
            None => *w = Some(crate::command_watch::CommandWatch::new(min_millis)),
        }
    }

    /// Regexes matched against every completed output line; hits arrive via `on_pattern`.
    pub fn set_watch_patterns(&self, patterns: Vec<String>) -> Result<(), CoreError> {
        let mut compiled = Vec::new();
        for p in patterns {
            let re = regex::RegexBuilder::new(&p)
                .case_insensitive(true)
                .size_limit(1 << 20)
                .build()
                .map_err(|e| CoreError::Other(format!("bad pattern '{p}': {e}")))?;
            compiled.push((p, re));
        }
        if !compiled.is_empty() {
            self.inner.taps.store(true, Ordering::Release);
        }
        *self.inner.watch.lock() = compiled;
        // Clearing the list is the one case that can turn the flag back off.
        self.inner.refresh_taps();
        Ok(())
    }

    /// Send every byte the terminal receives to [sink] as well, until
    /// [`Session::stop_output_capture`].
    pub fn capture_output(&self, sink: Arc<dyn OutputSink>) {
        // Armed before it is set, so a chunk already on its way finds the sink
        // rather than skipping the whole look.
        self.inner.taps.store(true, Ordering::Release);
        *self.inner.sink.lock() = Some(sink);
    }

    pub fn stop_output_capture(&self) {
        *self.inner.sink.lock() = None;
        // Only the sink went; the others may still want the stream.
        self.inner.refresh_taps();
    }

    // ---- the phone's traffic ---------------------------------------------

    /// Carry everything arriving on [fd] — the descriptor Android's VpnService
    /// returned — through this session.
    ///
    /// The descriptor belongs to the core from here on and is closed when the
    /// tunnel stops, so the caller must not close it as well.
    pub fn start_vpn(&self, fd: i32, mtu: u32, resolver: String, dns_address: String) -> Result<(), CoreError> {
        let inner = self.inner.clone();
        block_on(async move {
            let client = inner.ssh.lock().await.clone().ok_or(CoreError::NotConnected)?;
            let vpn = crate::vpn::Vpn::start(fd, mtu, &resolver, &dns_address, client)?;
            *inner.vpn.lock() = Some(vpn);
            Ok(())
        })
    }

    pub fn stop_vpn(&self) {
        *self.inner.vpn.lock() = None;
    }

    pub fn vpn_stats(&self) -> Option<crate::VpnStats> {
        self.inner.vpn.lock().as_ref().map(|v| v.stats())
    }

    // ---- forwarding ------------------------------------------------------

    pub fn add_local_forward(
        &self,
        bind_host: String,
        bind_port: u16,
        target_host: String,
        target_port: u16,
    ) -> Result<ForwardInfo, CoreError> {
        let inner = self.inner.clone();
        block_on(async move {
            let client = inner.ssh.lock().await.clone().ok_or(CoreError::NotConnected)?;
            let bind = format!("{bind_host}:{bind_port}")
                .parse()
                .map_err(|e: std::net::AddrParseError| CoreError::Other(e.to_string()))?;
            let fwd = client.local_forward(bind, target_host, target_port).await?;
            let port = fwd.bound.port();
            let id = inner.next_forward.fetch_add(1, Ordering::Relaxed);
            inner.forwards.lock().insert(id, Forward::Local(fwd));
            Ok(ForwardInfo { id, port })
        })
    }

    /// `-D`: SOCKS5/SOCKS4a proxy on `bind_host:bind_port` whose connections leave from the server.
    pub fn add_dynamic_forward(&self, bind_host: String, bind_port: u16) -> Result<ForwardInfo, CoreError> {
        let inner = self.inner.clone();
        block_on(async move {
            let client = inner.ssh.lock().await.clone().ok_or(CoreError::NotConnected)?;
            let bind = format!("{bind_host}:{bind_port}")
                .parse()
                .map_err(|e: std::net::AddrParseError| CoreError::Other(e.to_string()))?;
            let fwd = client.dynamic_forward(bind).await?;
            let port = fwd.bound.port();
            let id = inner.next_forward.fetch_add(1, Ordering::Relaxed);
            inner.forwards.lock().insert(id, Forward::Local(fwd));
            Ok(ForwardInfo { id, port })
        })
    }

    pub fn add_remote_forward(
        &self,
        remote_host: String,
        remote_port: u16,
        target_host: String,
        target_port: u16,
    ) -> Result<ForwardInfo, CoreError> {
        let inner = self.inner.clone();
        block_on(async move {
            let client = inner.ssh.lock().await.clone().ok_or(CoreError::NotConnected)?;
            let target = tokio::net::lookup_host((target_host.as_str(), target_port))
                .await
                .map_err(|e| CoreError::Other(e.to_string()))?
                .next()
                .ok_or_else(|| CoreError::Other(format!("cannot resolve {target_host}")))?;
            let fwd = client.remote_forward(remote_host, remote_port, target).await?;
            let port = fwd.remote_port as u16;
            let id = inner.next_forward.fetch_add(1, Ordering::Relaxed);
            inner.forwards.lock().insert(id, Forward::Remote(fwd));
            Ok(ForwardInfo { id, port })
        })
    }

    pub fn remove_forward(&self, id: u64) {
        let fwd = self.inner.forwards.lock().remove(&id);
        if let Some(Forward::Remote(r)) = fwd {
            block_on(async move { r.stop().await });
        }
    }

    // ---- sftp ------------------------------------------------------------

    pub fn open_sftp(&self) -> Result<Arc<SftpClient>, CoreError> {
        let inner = self.inner.clone();
        block_on(async move {
            if let Some(client) = inner.ssh.lock().await.clone() {
                return Ok(SftpClient::new(client.open_sftp().await?));
            }
            // A Mosh session hands the terminal to UDP and drops SSH, but SFTP
            // is an SSH subsystem — so browsing files opens its own connection
            // over the same route rather than reporting "not connected".
            match &inner.backend {
                Backend::Mosh { config } => {
                    inner.progress("Opening an SSH connection for files");
                    // Go back to the address that answered, not the top of the list.
                    let mut ssh = config.ssh.clone();
                    if let Some(e) = inner.used_endpoint.lock().clone() {
                        ssh.host = e.host;
                        ssh.port = e.port;
                        ssh.tunnel_id = e.tunnel_id;
                        ssh.tailscale_id = e.tailscale_id;
                        ssh.alternates = Vec::new();
                    }
                    let client = Arc::new(inner.connect_ssh(&ssh).await?);
                    let sftp = client.open_sftp().await?;
                    Ok(SftpClient::owning(sftp, client))
                }
                _ => Err(CoreError::NotConnected),
            }
        })
    }

    // ---- lifecycle -------------------------------------------------------

    pub fn disconnect(&self) {
        if self.inner.closed.swap(true, Ordering::AcqRel) {
            return;
        }
        let inner = self.inner.clone();
        RUNTIME.spawn(async move {
            inner.forwards.lock().clear();
            let writer = inner.writer.lock().clone();
            if let Some(w) = writer {
                w.close().await;
            }
            if let Some(c) = inner.ssh.lock().await.take() {
                c.disconnect().await;
            }
            inner.close_jumps().await;
            inner.set_state(SessionState::Disconnected { exit_code: None, error: None });
        });
    }
}

impl Inner {
    fn set_state(&self, state: SessionState) {
        {
            let mut cur = self.state.lock();
            if matches!(*cur, SessionState::Disconnected { .. }) && !matches!(state, SessionState::Disconnected { .. }) {
                return;
            }
            *cur = state.clone();
        }
        self.listener.on_state(state);
    }

    /// Recompute whether anything still wants each chunk of output.
    fn refresh_taps(&self) {
        let any = !self.watch.lock().is_empty()
            || self.command_watch.lock().is_some()
            || self.sink.lock().is_some();
        self.taps.store(any, Ordering::Release);
    }

    fn mark_dirty(&self) {
        if !self.dirty.swap(true, Ordering::AcqRel) {
            self.listener.on_damage();
        }
    }

    fn millis(&self) -> u64 {
        self.started.elapsed().as_millis() as u64
    }

    fn send(&self, bytes: Vec<u8>) {
        if bytes.is_empty() {
            return;
        }
        self.predict_input(&bytes);
        let _ = self.input_tx.send(bytes);
    }

    /// Guess what these keystrokes will look like, so they appear at once.
    fn predict_input(&self, bytes: &[u8]) {
        if !matches!(self.backend, Backend::Mosh { .. }) {
            return;
        }
        let now = self.millis();
        let mut p = self.predict.lock();
        if !p.enabled(now) {
            return;
        }
        // No screen mode is treated as a reason to stop guessing. A shell inside
        // tmux is on the alternate screen, and tmux with the mouse on still
        // reports mouse events at a plain text prompt — so neither mode tells us
        // whether a keystroke is text. Reconciliation is the real safeguard: a
        // contradicted guess is dropped and predicting pauses by itself.
        if let Some((row, col)) = self.emu.lock().cursor_row_col() {
            p.observe_cursor(row, col);
        }
        if p.on_input(bytes, now) {
            drop(p);
            self.mark_dirty();
        }
    }

    /// Check outstanding guesses against what the server actually sent.
    fn reconcile_predictions(&self) {
        // Only a Mosh session ever guesses; everything else can skip the lock.
        if !matches!(self.backend, Backend::Mosh { .. }) {
            return;
        }
        let now = self.millis();
        let mut p = self.predict.lock();
        if p.is_empty() {
            return;
        }
        let rows = p.rows();
        let texts: Vec<(u16, String)> = {
            let emu = self.emu.lock();
            rows.into_iter().map(|r| (r, emu.row_text(r))).collect()
        };
        let mut changed = false;
        for (row, text) in texts {
            changed |= p.reconcile(row, &text, now);
        }
        if changed {
            drop(p);
            self.mark_dirty();
        }
    }

    fn scroll_to_bottom_on_input(&self) {
        let mut emu = self.emu.lock();
        if emu.display_offset() != 0 {
            emu.scroll_to_bottom();
            drop(emu);
            self.mark_dirty();
        }
    }

    /// Report connection progress to the listener and echo it, dimmed, into
    /// the terminal so it stays in the scrollback like `ssh -v` output would.
    /// Report a step of the connection.
    ///
    /// Deliberately not written into the terminal: the screen belongs to the
    /// remote host, and a shell that opens with a dozen dim bullet lines above
    /// the first prompt is noise that never scrolls away. The app shows these
    /// as a list of steps instead, where a failure can be pointed at.
    /// A step of its own, done as soon as it is reported.
    fn progress(&self, msg: impl Into<String>) {
        let msg = msg.into();
        let key = format!("s{}", self.next_step.fetch_add(1, Ordering::Relaxed));
        log::info!("connect: {msg}");
        self.listener.on_progress(key, msg, StepStatus::Done);
    }

    /// A step that went wrong but did not end the connection.
    fn warn(&self, msg: impl Into<String>) {
        let msg = msg.into();
        let key = format!("s{}", self.next_step.fetch_add(1, Ordering::Relaxed));
        log::warn!("connect: {msg}");
        self.listener.on_progress(key, msg, StepStatus::Warning);
    }

    /// Update the step called `key`, or start it if it is new. This is how one
    /// address stays one line while it goes from "trying" to "connected".
    fn step(&self, key: &str, msg: impl Into<String>, status: StepStatus) {
        let msg = msg.into();
        match status {
            StepStatus::Failed | StepStatus::Warning => log::warn!("connect[{key}]: {msg}"),
            _ => log::info!("connect[{key}]: {msg}"),
        }
        self.listener.on_progress(key.to_string(), msg, status);
    }

    /// Name the key exchange the connection ended up with.
    ///
    /// It is a step rather than a footnote because it is the one part of the
    /// handshake whose absence nobody would notice: `mlkem768x25519-sha256`
    /// means a recording of this session made today survives the arrival of a
    /// quantum computer, and anything else means it does not.
    fn note_kex(&self, client: &SshClient) {
        if let Some(algorithm) = client.kex_algorithm() {
            self.step("kex", format!("Connected · {algorithm}"), StepStatus::Done);
        }
    }

    /// Ask the host what it runs, on the SSH connection that is already open.
    ///
    /// Done here rather than from the app because a Mosh session has no SSH
    /// channel left by the time anyone could ask — this is the one moment both
    /// kinds of session have one. Failure is silence: an old sshd, a restricted
    /// shell or a busy host simply leaves the answer unset.
    async fn probe_os(&self, client: &SshClient) {
        const CMD: &str = ". /etc/os-release 2>/dev/null && echo \"$PRETTY_NAME\" || uname -sr";
        let Ok(Ok((out, _))) = tokio::time::timeout(Duration::from_secs(6), client.exec(CMD)).await else {
            return;
        };
        let text = String::from_utf8_lossy(&out);
        let answer: String = text.lines().map(str::trim).find(|l| !l.is_empty()).unwrap_or_default().chars().take(60).collect();
        if !answer.is_empty() {
            *self.detected_os.lock() = Some(answer);
        }
    }

    async fn close_jumps(&self) {
        let jumps = std::mem::take(&mut *self.jumps.lock().await);
        for j in jumps.into_iter().rev() {
            j.disconnect().await;
        }
    }

    fn tunnel_fallback(&self) -> bool {
        matches!(&self.backend, Backend::Ssh { config } if config.tunnel_fallback)
    }

    /// The variables to ask for on the shell channel, with a step recording how
    /// many were asked for.
    ///
    /// "Asked for" is the whole claim: the requests carry no reply worth waiting
    /// on, and `AcceptEnv` on the far side decides which of them survive. Saying
    /// they were applied would be a guess dressed up as a fact.
    fn shell_env(&self, env: &[EnvVar]) -> Vec<(String, String)> {
        let vars: Vec<(String, String)> = env
            .iter()
            .filter(|v| !v.name.trim().is_empty())
            .map(|v| (v.name.trim().to_string(), v.value.clone()))
            .collect();
        if !vars.is_empty() {
            self.progress(format!(
                "Asked the server for {} environment variable{} — it keeps whichever its AcceptEnv allows",
                vars.len(),
                if vars.len() == 1 { "" } else { "s" },
            ));
        }
        vars
    }

    async fn connect(self: Arc<Self>) -> Result<Reader, CoreError> {
        // The size is read at each point it is sent, never once up front. What
        // happens in between can wait on a person — a host key to trust, a
        // password, a touch on a security key — and the view finishes measuring
        // while they decide. A resize arriving in that window has no writer to
        // reach and is dropped, so a size read before dialing would be the
        // 80x24 the session was constructed with, and nothing later would
        // correct it.
        match &self.backend {
            Backend::Ssh { config } => {
                let client = Arc::new(self.connect_ssh(config).await?);
                self.probe_os(&client).await;
                let env = self.shell_env(&config.env);
                let (cols, rows) = *self.size.lock();
                let shell = client.open_shell_env(term_name(&config.term), cols, rows, &env).await?;
                *self.writer.lock() = Some(Writer::Ssh(shell.writer()));
                *self.ssh.lock().await = Some(client);
                Ok(Reader::Ssh(shell))
            }
            Backend::Mosh { config } => {
                // Announced only once SSH is up: the Mosh row belongs after the
                // address it was reached over, not above it.
                // Wrapped early: the agent hold keeps a reference to it, and the
                // Mosh handoff may or may not be the end of this connection.
                let client = Arc::new(self.connect_ssh(&config.ssh).await?);
                self.step("mosh", "Starting mosh-server over SSH", StepStatus::Running);
                // mosh-server takes the variables directly, so a Mosh session gets
                // them even where sshd's AcceptEnv would have dropped them.
                let mut env: Vec<(String, String)> = config.ssh.env.iter().map(|e| (e.name.clone(), e.value.clone())).collect();
                // mosh-server decides TERM for the session it spawns, and takes
                // it the same way as every other variable.
                if !env.iter().any(|(n, _)| n == "TERM") {
                    env.push(("TERM".to_string(), term_name(&config.ssh.term).to_string()));
                }
                self.probe_os(&client).await;
                // Agent forwarding cannot survive on its own here: mosh-server
                // inherits SSH_AUTH_SOCK, but sshd removes that socket when the
                // channel that asked for it closes. So a channel is held open and
                // its socket path handed to mosh-server, which sets it in the
                // session it spawns.
                // (env is already owned and mutable above)
                if config.ssh.forward_agent {
                    match client.hold_agent_socket().await {
                        Ok((path, channel)) => {
                            env.push(("SSH_AUTH_SOCK".to_string(), path));
                            *self.agent_hold.lock().await = Some((client.clone(), channel));
                            self.step("agent", "Agent forwarding kept alive on an SSH connection beside Mosh", StepStatus::Done);
                        }
                        Err(e) => self.warn(format!("Agent forwarding not available under Mosh: {e}")),
                    }
                }
                let started = client.exec(&crate::mosh::start_command(&config.server, &config.locale, &env)).await;
                if !env.is_empty() {
                    self.step("mosh", format!("Asked mosh-server for {} environment variable{}", env.len(), if env.len() == 1 { "" } else { "s" }), StepStatus::Running);
                }
                let boot = match started {
                    Ok((out, _)) => crate::mosh::parse_connect(&String::from_utf8_lossy(&out)),
                    Err(e) => Err(e.into()),
                };

                // Through a jump host, SSH cannot carry the UDP session, so ask
                // the last hop to relay datagrams for us. Done before tearing the
                // chain down, since the relay is started over it.
                let relay = match (&boot, config.ssh.jumps.last()) {
                    (Ok(boot), Some(hop)) => {
                        self.progress(format!("Starting a UDP relay on {}", hop.host));
                        let cmd = crate::mosh::relay_command(&config.ssh.host, boot.port, crate::mosh::RELAY_IDLE_SECS);
                        let jumps = self.jumps.lock().await;
                        match jumps.last() {
                            Some(last) => match last.exec(&cmd).await {
                                Ok((out, _)) => Some(crate::mosh::parse_relay(&String::from_utf8_lossy(&out)).map(|p| (hop.host.clone(), p))),
                                Err(e) => Some(Err(e.into())),
                            },
                            None => Some(Err(CoreError::Other("jump host connection went away".into()))),
                        }
                    }
                    _ => None,
                };

                // The SSH connection stays up until the UDP session has proved
                // itself, so a blocked port can fall back to it instead of
                // leaving the user with a dead terminal.
                let boot = boot?;
                // Where the datagrams actually go: the target, or the relay.
                let (udp_host, udp_port) = match relay {
                    Some(result) => {
                        let (host, port) = result?;
                        self.progress(format!("Mosh through the relay on {host}:{port}"));
                        (host, port)
                    }
                    // The address that answered, which is not necessarily the
                    // first one in the list.
                    None => (self.used_endpoint.lock().as_ref().map(|e| e.host.clone()).unwrap_or_else(|| config.ssh.host.clone()), boot.port),
                };
                self.step("mosh", format!("mosh-server is on UDP {}, leaving SSH behind", boot.port), StepStatus::Running);

                let listener = self.listener.clone();
                // The Mosh client's own notes update the Mosh line rather than
                // stacking up under it.
                let progress = std::sync::Arc::new(move |m: String| listener.on_progress("mosh".into(), m, StepStatus::Running));
                let (cols, rows) = *self.size.lock();
                // The tunnel carries the UDP session too, so a host reached over
                // WireGuard keeps working; smoltcp gives us real datagrams there.
                let chosen = self.used_tunnel.lock().clone();
                let tunnel = match chosen.as_deref() {
                    Some(id) => Some(crate::tunnel::tunnel_for(id).await?),
                    None => None,
                };
                let inner = self.clone();
                let on_srtt = std::sync::Arc::new(move |srtt: f64| inner.predict.lock().set_srtt(srtt));
                // How the datagrams get out. Over Tailscale that is the node's own
                // loopback SOCKS5 proxy: `dial` would give a byte stream and lose
                // datagram boundaries, but tailscale's SOCKS5 speaks UDP ASSOCIATE.
                // Otherwise it is the host's configured proxy, and only when there
                // is no jump chain — with a relay on the hop the SSH already went
                // through the proxy.
                let tailnet = self
                    .used_endpoint
                    .lock()
                    .as_ref()
                    .map(|e| e.tailscale_id.clone())
                    .unwrap_or_else(|| config.ssh.tailscale_id.clone());
                let proxy = if let Some(ts) = tailnet {
                    let (host, port, cred) = crate::tailscale::loopback_proxy(ts).await?;
                    self.progress("Mosh over Tailscale, through the node's SOCKS5 proxy");
                    Some(ssh_core::ProxyConfig {
                        kind: ssh_core::ProxyKind::Socks5,
                        host,
                        port,
                        username: Some("tsnet".into()),
                        password: Some(cred),
                    })
                } else {
                    config.ssh.jumps.is_empty().then(|| config.ssh.proxy.as_ref().map(to_proxy)).flatten()
                };
                match crate::mosh::connect(&udp_host, udp_port, boot, cols, rows, tunnel, proxy, progress, on_srtt).await {
                    Ok((reader, writer)) => {
                        self.step("mosh", format!("Mosh session on UDP {udp_port}"), StepStatus::Done);
                        // The bootstrap connection goes, unless it is the one
                        // holding the agent socket open.
                        if self.agent_hold.lock().await.is_none() {
                            client.disconnect().await;
                            self.close_jumps().await;
                        }
                        *self.writer.lock() = Some(Writer::Mosh(writer));
                        Ok(Reader::Mosh(reader))
                    }
                    Err(e) => {
                        // Mosh is an optimisation, not the point of the session:
                        // if its datagrams cannot get through, carry on over the
                        // SSH connection that is still open and say why.
                        self.step("mosh", format!("Mosh unavailable: {e}"), StepStatus::Failed);
                        self.warn("Continuing over SSH — the session will not survive a network change");
                        self.predict.lock().reset();
                        let env = self.shell_env(&config.ssh.env);
                        let (cols, rows) = *self.size.lock();
                        let shell = client.open_shell_env(term_name(&config.ssh.term), cols, rows, &env).await?;
                        *self.writer.lock() = Some(Writer::Ssh(shell.writer()));
                        *self.ssh.lock().await = Some(client);
                        Ok(Reader::Ssh(shell))
                    }
                }
            }
            Backend::Telnet { config } => {
                let stream = self
                    .dial_plain(&config.host, config.port, config.connect_timeout_secs, config.wait_for_host_secs, config.tunnel_id.as_deref(), config.tailscale_id.as_deref())
                    .await?;
                self.progress("Connected, negotiating telnet options");
                let (cols, rows) = *self.size.lock();
                let (reader, writer) = TelnetIo::new(stream, cols, rows);
                *self.writer.lock() = Some(Writer::Telnet(writer));
                Ok(Reader::Telnet(reader))
            }
            Backend::External { config } => {
                let reader = self
                    .external
                    .take_reader()
                    .ok_or_else(|| CoreError::Other("this session was already started".into()))?;
                self.progress(format!("Attached to {}", config.label));
                *self.writer.lock() = Some(Writer::External(ExternalWriter::new(self.external.clone())));
                Ok(Reader::External(reader))
            }
            Backend::Local { config } => {
                let mut env: Vec<(String, String)> = config.env.iter().map(|e| (e.name.clone(), e.value.clone())).collect();
                if !env.iter().any(|(n, _)| n == "TERM") {
                    env.push(("TERM".to_string(), term_name(&config.term).to_string()));
                }
                let (cols, rows) = *self.size.lock();
                let pty = pty::Pty::spawn(pty::SpawnOptions {
                    program: &config.program,
                    args: &config.args,
                    env: &env,
                    cwd: config.cwd.as_deref(),
                    cols,
                    rows,
                })
                .map_err(|e| CoreError::Other(e.to_string()))?;
                let io = PtyIo::new(pty).map_err(|e| CoreError::Other(e.to_string()))?;
                *self.writer.lock() = Some(Writer::Pty(io.clone()));
                Ok(Reader::Pty(io))
            }
        }
    }

    /// Connect to the target, walking the jump chain first.
    async fn connect_ssh(&self, config: &SshConfig) -> Result<SshClient, CoreError> {
        let verifier: Arc<dyn ssh_core::HostKeyVerifier> = Arc::new(Verifier(self.verifier.clone()));
        let prompter: Arc<dyn ssh_core::AuthPrompter> = Arc::new(Prompter(self.prompter.clone()));
        let on_banner: Option<Arc<dyn ssh_core::BannerSink>> = self
            .options
            .lock()
            .show_banner
            .then(|| Arc::new(Banner(self.listener.clone())) as Arc<dyn ssh_core::BannerSink>);
        let keepalive = (config.keepalive_secs > 0).then(|| Duration::from_secs(config.keepalive_secs as u64));
        let timeout = Duration::from_secs(config.connect_timeout_secs.max(1) as u64);
        let agent_keys: Vec<Arc<russh::keys::PrivateKey>> = config
            .agent_keys
            .iter()
            .filter_map(|a| match a {
                AuthMethod::Key { private_key, passphrase, .. } => russh::keys::decode_secret_key(private_key, passphrase.as_deref()).ok().map(Arc::new),
                _ => None,
            })
            .collect();
        let agent_for = |on: bool| {
            (on && !agent_keys.is_empty()).then(|| ssh_core::AgentKeys {
                keys: agent_keys.clone(),
                policy: match config.agent_approval.clone() {
                    Some(ask) => Arc::new(ForeignAgentPolicy(ask)) as Arc<dyn ssh_core::AgentPolicy>,
                    None => Arc::new(ssh_core::AllowAll),
                },
            })
        };
        // Fallible only because of a security key: a stored credential that no
        // longer forms a key has to be a sentence about that key rather than a
        // shorter list of things to try and a bare "authentication failed".
        let opts_for = |host: &str, port: u16, user: &str, auth: &[AuthMethod], proxy: &Option<ProxyConfig>, agent: bool| {
            Ok::<_, CoreError>(ssh_core::ConnectOptions {
                host: host.to_string(),
                port,
                username: user.to_string(),
                auth: auth.iter().map(to_auth).collect::<Result<_, _>>()?,
                keepalive_interval: keepalive,
                connect_timeout: timeout,
                proxy: proxy.as_ref().map(to_proxy),
                agent: agent_for(agent),
                prompter: Some(prompter.clone()),
                on_banner: on_banner.clone(),
            })
        };

        // A direct connection, over whichever address answers first.
        if config.jumps.is_empty() {
            let mut candidates = vec![Endpoint {
                label: config.label.clone(),
                host: config.host.clone(),
                port: config.port,
                tunnel_id: config.tunnel_id.clone(),
                tailscale_id: config.tailscale_id.clone(),
                vpn_name: config.vpn_name.clone(),
                tunnel_fallback: config.tunnel_fallback,
            }];
            candidates.extend(config.alternates.iter().cloned());
            let deadline = tokio::time::Instant::now() + Duration::from_secs(config.wait_for_host_secs as u64);
            let mut last: Option<CoreError> = None;
            loop {
                for (i, candidate) in candidates.iter().enumerate() {
                    // Everything this attempt says goes on one line, which ends
                    // as either the connection that worked or the one that did not.
                    let key = format!("addr{i}");
                    let name = if candidate.label.is_empty() {
                        format!("{}:{}", candidate.host, candidate.port)
                    } else {
                        format!("{} ({}:{})", candidate.label, candidate.host, candidate.port)
                    };
                    // The VPN row is created first, because that is the order it
                    // happens in: the tunnel comes up, then the address is dialled
                    // through it.
                    if candidate.tunnel_id.is_some() || candidate.tailscale_id.is_some() {
                        let what = if candidate.tailscale_id.is_some() { "tailnet" } else { "tunnel" };
                        let named = if candidate.vpn_name.is_empty() { what.to_string() } else { format!("{what} {}", candidate.vpn_name) };
                        self.step("vpn", format!("Bringing up the {named}"), StepStatus::Running);
                    }
                    self.step(&key, format!("Trying {name}"), StepStatus::Running);
                    let opts = opts_for(&candidate.host, candidate.port, &config.username, &config.auth, &config.proxy, config.forward_agent)?;
                    // No waiting inside a single attempt: with several addresses,
                    // sitting on the first one defeats the point of the list.
                    match self
                        .dial_first_hop(
                            opts,
                            candidate.tunnel_id.as_deref(),
                            candidate.tailscale_id.as_deref(),
                            0,
                            &verifier,
                            &key,
                            &candidate.vpn_name,
                            candidate.tunnel_fallback,
                        )
                        .await
                    {
                        Ok(client) => {
                            *self.used_endpoint.lock() = Some(candidate.clone());
                            self.step(&key, format!("Connected to {name}"), StepStatus::Done);
                            self.note_kex(&client);
                            self.progress("Authenticated");
                            if config.forward_agent {
                                self.progress(format!("Agent forwarding on ({} key{})", agent_keys.len(), if agent_keys.len() == 1 { "" } else { "s" }));
                            }
                            return Ok(client);
                        }
                        // Reaching the machine and being turned away is an answer:
                        // another address would only ask the same question again.
                        Err(e @ (CoreError::Auth(_) | CoreError::HostKeyRejected | CoreError::InvalidKey(_))) => {
                            self.step(&key, format!("{name}: {e}"), StepStatus::Failed);
                            return Err(e);
                        }
                        Err(e) => {
                            self.step(&key, format!("{name}: {e}"), StepStatus::Failed);
                            last = Some(e);
                        }
                    }
                }
                if tokio::time::Instant::now() >= deadline {
                    return Err(last.unwrap_or(CoreError::Timeout));
                }
                self.warn("No address answered yet, trying again…");
                tokio::time::sleep(Duration::from_secs(3)).await;
            }
        }

        // Walk the chain: hop 0 is reached directly, every later host through the previous one.
        let mut previous: Option<Arc<SshClient>> = None;
        for (i, hop) in config.jumps.iter().enumerate() {
            let label = format!("{}@{}:{}", hop.username, hop.host, hop.port);
            let opts = opts_for(&hop.host, hop.port, &hop.username, &hop.auth, &hop.proxy, hop.forward_agent)?;
            // One line per hop, from "connecting" to "authenticated".
            let key = format!("hop{i}");
            let client = match &previous {
                None => {
                    self.step(&key, format!("Connecting to jump host {label}"), StepStatus::Running);
                    let tunnel = hop.tunnel_id.as_deref().or(config.tunnel_id.as_deref());
                    match self.dial_first_hop(opts, tunnel, hop.tailscale_id.as_deref(), 0, &verifier, &key, "", self.tunnel_fallback()).await {
                        Ok(c) => c,
                        Err(e) => {
                            self.step(&key, format!("Jump host {label}: {e}"), StepStatus::Failed);
                            return Err(e);
                        }
                    }
                }
                Some(prev) => {
                    self.step(&key, format!("Connecting to jump host {label} through {}", config.jumps[i - 1].host), StepStatus::Running);
                    let stream = match self.open_onward(prev, &config.jumps[i - 1], &hop.host, hop.port).await {
                        Ok(s) => s,
                        Err(e) => {
                            self.step(&key, format!("Jump host {label}: {e}"), StepStatus::Failed);
                            return Err(e);
                        }
                    };
                    match SshClient::connect_over(opts, stream, verifier.clone()).await {
                        Ok(c) => c,
                        Err(e) => {
                            self.step(&key, format!("Jump host {label}: {e}"), StepStatus::Failed);
                            return Err(e.into());
                        }
                    }
                }
            };
            self.step(&key, format!("Jump host {} — authenticated", hop.host), StepStatus::Done);
            let client = Arc::new(client);
            self.jumps.lock().await.push(client.clone());
            previous = Some(client);
        }

        let last_hop = config.jumps.last().expect("non-empty");
        let last = previous.expect("non-empty");
        self.progress(format!("Connecting to {}:{} through {}", config.host, config.port, last_hop.host));
        let stream = self.open_onward(&last, last_hop, &config.host, config.port).await?;
        let client = SshClient::connect_over(
            opts_for(&config.host, config.port, &config.username, &config.auth, &None, config.forward_agent)?,
            stream,
            verifier,
        )
        .await?;
        self.note_kex(&client);
        self.progress("Authenticated");
        if config.forward_agent {
            self.progress(format!("Agent forwarding on ({} key{})", agent_keys.len(), if agent_keys.len() == 1 { "" } else { "s" }));
        }
        Ok(client)
    }

    /// Connect to the first machine in the chain (or the only one): plain TCP,
    /// through a proxy, or through a userspace WireGuard tunnel. Retries for
    /// `wait_secs` so a freshly woken machine has time to boot.
    /// Open a plain TCP stream to `host:port`, honouring the WireGuard tunnel or
    /// Tailscale when the host is configured for one, and retrying while the
    /// device boots if `wait_secs` allows it.
    async fn dial_plain(
        &self,
        host: &str,
        port: u16,
        timeout_secs: u32,
        wait_secs: u32,
        tunnel_id: Option<&str>,
        tailscale_id: Option<&str>,
    ) -> Result<ssh_core::BoxedStream, CoreError> {
        let target = format!("{host}:{port}");
        let tunnel = match tunnel_id.filter(|_| tailscale_id.is_none()) {
            Some(id) => {
                self.progress("Bringing up WireGuard tunnel");
                Some(crate::tunnel::tunnel_for(id).await?)
            }
            None => None,
        };
        let timeout = Duration::from_secs(timeout_secs.max(1) as u64);
        let deadline = tokio::time::Instant::now() + Duration::from_secs(wait_secs as u64);
        let mut attempt = 0u32;
        loop {
            attempt += 1;
            match tailscale_id {
                Some(_) => self.progress(format!("Connecting to {target} through Tailscale")),
                None if tunnel.is_some() => self.progress(format!("Connecting to {target} through the tunnel")),
                None => self.progress(format!("Connecting to {target}")),
            }
            let result: Result<ssh_core::BoxedStream, String> = if let Some(ts) = tailscale_id {
                crate::tailscale::dial(ts.to_string(), target.clone())
                    .await
                    .map(|s| Box::new(s) as ssh_core::BoxedStream)
                    .map_err(|e| e.to_string())
            } else if let Some(t) = &tunnel {
                match t.resolve(host).await {
                    Ok(ip) => t.connect(ip, port).await.map(|s| Box::new(s) as ssh_core::BoxedStream).map_err(|e| e.to_string()),
                    Err(e) => Err(e.to_string()),
                }
            } else {
                match tokio::time::timeout(timeout, tokio::net::TcpStream::connect(&target)).await {
                    Ok(Ok(s)) => {
                        let _ = s.set_nodelay(true);
                        Ok(Box::new(s) as ssh_core::BoxedStream)
                    }
                    Ok(Err(e)) => Err(e.to_string()),
                    Err(_) => Err("connection timed out".into()),
                }
            };
            match result {
                Ok(s) => return Ok(s),
                Err(e) => {
                    let now = tokio::time::Instant::now();
                    if now >= deadline {
                        return Err(CoreError::Other(e));
                    }
                    let left = (deadline - now).as_secs();
                    self.warn(format!("{target} not reachable yet (attempt {attempt}: {e}), retrying for {left}s…"));
                    tokio::time::sleep(Duration::from_secs(3)).await;
                }
            }
        }
    }

    async fn dial_first_hop(
        &self,
        opts: ssh_core::ConnectOptions,
        tunnel_id: Option<&str>,
        tailscale_id: Option<&str>,
        wait_secs: u32,
        verifier: &Arc<dyn ssh_core::HostKeyVerifier>,
        // Which step line this attempt writes to, so one address stays one line.
        step_key: &str,
        // What the tunnel or tailnet is called, for its own step line.
        vpn_name: &str,
        // Whether this address would rather be tried directly first.
        tunnel_fallback: bool,
    ) -> Result<SshClient, CoreError> {
        let say = |msg: String| self.step(step_key, msg, StepStatus::Running);
        let target = format!("{}:{}", opts.host, opts.port);
        // Smart tunnel use: reachable directly? Then no tunnel. Otherwise go through it.
        let tunnel_id = match tunnel_id {
            Some(id) if tunnel_fallback && tailscale_id.is_none() => {
                say(format!("Trying {target} directly first"));
                let mut quick = opts.clone();
                quick.connect_timeout = Duration::from_secs(4);
                match SshClient::connect(quick, verifier.clone()).await {
                    Ok(c) => {
                        self.step(step_key, format!("Reachable directly, not using the tunnel"), StepStatus::Running);
                        *self.used_tunnel.lock() = None;
                        return Ok(c);
                    }
                    Err(ssh_core::SshError::Io(_)) | Err(ssh_core::SshError::Timeout) | Err(ssh_core::SshError::Other(_)) => {
                        say("Not reachable directly, using the WireGuard tunnel".to_string());
                        Some(id)
                    }
                    Err(e) => return Err(e.into()),
                }
            }
            other => other,
        };
        if tailscale_id.is_some() {
            // The tailnet is a step of its own: when a node is slow to come up,
            // that is what the connection is waiting for.
            let name = if vpn_name.is_empty() { "Tailscale".to_string() } else { format!("Tailscale ({vpn_name})") };
            self.step("vpn", format!("Dialling inside {name}"), StepStatus::Done);
            say(format!("Connecting to {target} through Tailscale"));
        }
        *self.used_tunnel.lock() = tunnel_id.filter(|_| tailscale_id.is_none()).map(str::to_string);
        let tunnel = match tunnel_id.filter(|_| tailscale_id.is_none()) {
            Some(id) => {
                let name = if vpn_name.is_empty() { "WireGuard tunnel".to_string() } else { format!("WireGuard tunnel {vpn_name}") };
                self.step("vpn", format!("Bringing up {name}"), StepStatus::Running);
                let t = match crate::tunnel::tunnel_for(id).await {
                    Ok(t) => t,
                    Err(e) => {
                        self.step("vpn", format!("{name}: {e}"), StepStatus::Failed);
                        return Err(e);
                    }
                };
                self.step("vpn", format!("{name} is up"), StepStatus::Done);
                say(format!("Connecting to {target} through the tunnel"));
                Some(t)
            }
            None => {
                if tailscale_id.is_none() {
                    match &opts.proxy {
                        Some(p) => say(format!("Connecting to {target} via {} proxy {}:{}", match p.kind { ssh_core::ProxyKind::Socks5 => "SOCKS5", ssh_core::ProxyKind::Http => "HTTP" }, p.host, p.port)),
                        None => say(format!("Connecting to {target}")),
                    }
                }
                None
            }
        };
        let deadline = tokio::time::Instant::now() + Duration::from_secs(wait_secs as u64);
        let mut attempt = 0u32;
        loop {
            attempt += 1;
            let result = if let Some(ts) = tailscale_id {
                match crate::tailscale::dial(ts.to_string(), target.clone()).await {
                    Ok(stream) => SshClient::connect_over(opts.clone(), stream, verifier.clone()).await,
                    Err(e) => Err(ssh_core::SshError::Other(e.to_string())),
                }
            } else { match &tunnel {
                Some(t) => {
                    let ip = match t.resolve(&opts.host).await {
                        Ok(ip) => ip,
                        Err(e) => return Err(CoreError::Other(e.to_string())),
                    };
                    match t.connect(ip, opts.port).await {
                        Ok(stream) => SshClient::connect_over(opts.clone(), stream, verifier.clone()).await,
                        Err(e) => Err(ssh_core::SshError::Other(e.to_string())),
                    }
                }
                None => SshClient::connect(opts.clone(), verifier.clone()).await,
            } };
            match result {
                Ok(c) => return Ok(c),
                // Only network-level failures are worth retrying; auth/host-key problems are final.
                Err(e @ (ssh_core::SshError::Io(_) | ssh_core::SshError::Timeout | ssh_core::SshError::Other(_))) => {
                    let now = tokio::time::Instant::now();
                    if now >= deadline {
                        return Err(e.into());
                    }
                    let left = (deadline - now).as_secs();
                    self.step(step_key, format!("{target} not reachable yet (attempt {attempt}: {e}), retrying for {left}s…"), StepStatus::Warning);
                    tokio::time::sleep(Duration::from_secs(3)).await;
                }
                Err(e) => return Err(e.into()),
            }
        }
    }

    /// From `hop`, run its pre-command (if any) and open a tunnel to
    /// `host:port`, retrying while the machine on the other side comes up.
    async fn open_onward(
        &self,
        via: &Arc<SshClient>,
        hop: &JumpHop,
        host: &str,
        port: u16,
    ) -> Result<ssh_core::TunnelStream, CoreError> {
        if let Some(cmd) = hop.pre_command.as_deref().map(str::trim).filter(|c| !c.is_empty()) {
            let shown = match cmd.split_once('\n') {
                Some((first, _)) => format!("{} …", first.trim()),
                None => cmd.to_string(),
            };
            self.progress(format!("Running on {}: {shown}", hop.host));
            let mut partial = String::new();
            let status = via
                .exec_streaming(cmd, |chunk| {
                    partial.push_str(&String::from_utf8_lossy(chunk));
                    while let Some(nl) = partial.find('\n') {
                        let line = partial[..nl].trim_end().to_string();
                        partial = partial[nl + 1..].to_string();
                        if !line.is_empty() {
                            self.progress(format!("  {line}"));
                        }
                    }
                })
                .await?;
            if !partial.trim().is_empty() {
                self.progress(format!("  {}", partial.trim()));
            }
            if status != 0 {
                self.progress(format!("Command exited with status {status}; continuing"));
            }
        }

        let deadline = tokio::time::Instant::now() + Duration::from_secs(hop.wait_for_next_secs as u64);
        let mut attempt = 0u32;
        loop {
            attempt += 1;
            let result = tokio::time::timeout(Duration::from_secs(20), via.open_direct_tcpip(host, port)).await;
            match result {
                Ok(Ok(stream)) => return Ok(stream),
                Ok(Err(e)) => {
                    let now = tokio::time::Instant::now();
                    if now >= deadline {
                        return Err(CoreError::Other(format!("cannot reach {host}:{port} from {}: {e}", hop.host)));
                    }
                    let left = (deadline - now).as_secs();
                    self.progress(format!("{host}:{port} not reachable yet (attempt {attempt}), retrying for {left}s…"));
                    tokio::time::sleep(Duration::from_secs(3)).await;
                }
                Err(_) => {
                    if tokio::time::Instant::now() >= deadline {
                        return Err(CoreError::Other(format!("timed out reaching {host}:{port} from {}", hop.host)));
                    }
                    self.progress(format!("{host}:{port} did not answer (attempt {attempt}), retrying…"));
                }
            }
        }
    }

    /// Move bytes between the transport and the emulator until the session ends.
    async fn pump(self: Arc<Self>, mut reader: Reader) {
        // Writer task: drains queued input in order.
        let writer = self.writer.lock().clone().expect("writer set before pump");
        let mut rx = self.input_rx.lock().take().expect("pump called once");
        let write_task = RUNTIME.spawn(async move {
            while let Some(mut bytes) = rx.recv().await {
                // Coalesce bursts (e.g. key repeat) into one write.
                while let Ok(more) = rx.try_recv() {
                    bytes.extend_from_slice(&more);
                    if bytes.len() > 64 * 1024 {
                        break;
                    }
                }
                if !writer.write(bytes).await {
                    break;
                }
            }
        });

        let result = loop {
            match reader.next().await {
                Event::Data(data) => self.feed(&data),
                Event::Exit(code) => break (code, None),
                Event::Closed => break (None, None),
            }
        };
        write_task.abort();
        *self.writer.lock() = None;
        if let Some(c) = self.ssh.lock().await.take() {
            c.disconnect().await;
        }
        self.close_jumps().await;
        self.forwards.lock().clear();
        self.set_state(SessionState::Disconnected { exit_code: result.0, error: result.1 });
    }

    /// Strip escape sequences, split into lines, match the watch patterns.
    fn watch_output(&self, data: &[u8]) {
        if self.watch.lock().is_empty() {
            return;
        }
        let mut completed: Vec<String> = Vec::new();
        {
            let mut wl = self.watch_line.lock();
            for &b in data {
                match wl.esc {
                    1 => {
                        wl.esc = match b {
                            b'[' => 2,
                            b']' => 3,
                            _ => 0,
                        };
                    }
                    2 => {
                        if (0x40..=0x7e).contains(&b) {
                            wl.esc = 0;
                        }
                    }
                    3 => {
                        if b == 0x07 {
                            wl.esc = 0;
                        } else if b == 0x1b {
                            wl.esc = 1; // ESC \ terminator
                        }
                    }
                    _ => match b {
                        0x1b => wl.esc = 1,
                        b'\n' => {
                            let line = std::mem::take(&mut wl.text);
                            completed.push(line);
                        }
                        b'\r' | 0x07 | 0x08 => {}
                        b if b < 0x20 => {}
                        _ => {
                            if wl.text.len() < 4096 {
                                wl.text.push(b as char);
                            }
                        }
                    },
                }
            }
        }
        if completed.is_empty() {
            return;
        }
        let watch = self.watch.lock();
        for line in completed {
            let line = line.trim();
            if line.is_empty() {
                continue;
            }
            for (pat, re) in watch.iter() {
                if let Some(m) = re.find(line) {
                    // Report a window around the match: terminal redraws (status bars, prompts)
                    // often share the "line" with the interesting text.
                    let start = line[..m.start()].char_indices().rev().nth(40).map(|(i, _)| i).unwrap_or(0);
                    let end = line[m.end()..].char_indices().nth(100).map(|(i, _)| m.end() + i).unwrap_or(line.len());
                    let shown = line[start..end].trim().to_string();
                    self.listener.on_pattern(pat.clone(), shown);
                    break;
                }
            }
        }
    }

    /// Hand the stream to the command watcher, when one is running.
    fn watch_commands_output(&self, data: &[u8]) {
        let now = self.millis();
        let finished = {
            let mut w = self.command_watch.lock();
            match w.as_mut() {
                // Nothing to do, and nothing crosses the bridge: this is the
                // whole reason the watching moved to this side.
                None => return,
                Some(w) => w.on_output(data, now),
            }
        };
        if let Some(f) = finished {
            self.listener.on_command_finished(f.elapsed_millis, f.exit_status);
        }
    }

    fn feed(&self, data: &[u8]) {
        if self.taps.load(Ordering::Acquire) {
            self.watch_output(data);
            self.watch_commands_output(data);
            // Cloned out of the lock first: the sink is foreign code, and
            // holding a mutex across a call into the app makes deadlocks.
            let sink = self.sink.lock().clone();
            if let Some(sink) = sink {
                sink.on_output(data.to_vec());
            }
        }
        let events = {
            let mut emu = self.emu.lock();
            emu.feed(data);
            emu.take_events()
        };
        // The authoritative screen just moved; settle the guesses against it.
        self.reconcile_predictions();
        for ev in events {
            match ev {
                EmulatorEvent::PtyWrite(bytes) => self.send(bytes),
                EmulatorEvent::Title(t) => {
                    *self.title.lock() = t.clone();
                    self.listener.on_title(t);
                }
                EmulatorEvent::Bell => self.listener.on_bell(),
                EmulatorEvent::ClipboardStore(s) => self.listener.on_clipboard(s),
                EmulatorEvent::Notify { title, body } => self.listener.on_notify(title, body),
                EmulatorEvent::Mark(m) => {
                    // The watcher first: a mark is the truth about this shell,
                    // and it retires the guessing the moment one arrives.
                    let now = self.millis();
                    let finished = self.command_watch.lock().as_mut().and_then(|w| w.on_mark(m, now));
                    if let Some(f) = finished {
                        self.listener.on_command_finished(f.elapsed_millis, f.exit_status);
                    }
                    let (kind, exit) = split_mark(m);
                    self.listener.on_prompt_mark(kind, exit);
                }
                EmulatorEvent::Cwd(path) => self.listener.on_cwd(path),
                EmulatorEvent::ImagesChanged => self.listener.on_images_changed(),
                // A backend that places images itself — the one we have —
                // never hands these up; they are here for one that does not.
                EmulatorEvent::KittyGraphics(_) | EmulatorEvent::Sixel { .. } => {}
            }
        }
        self.mark_dirty();
    }
}

impl Drop for Inner {
    fn drop(&mut self) {
        log::debug!("session dropped");
    }
}

fn to_auth(a: &AuthMethod) -> Result<ssh_core::Auth, CoreError> {
    Ok(match a {
        AuthMethod::Password { password } => ssh_core::Auth::Password(password.clone()),
        AuthMethod::Key { private_key, passphrase, certificate } => ssh_core::Auth::Key {
            private_key: private_key.clone(),
            passphrase: passphrase.clone(),
            certificate: some_text(certificate),
        },
        AuthMethod::Keystore { signer, certificate } => ssh_core::Auth::External {
            signer: Arc::new(ForeignSigner(signer.clone())),
            certificate: some_text(certificate),
        },
        AuthMethod::SecurityKey { credential, token, comment, certificate } => {
            let authenticator = Arc::new(ForeignToken(token.clone()));
            let signer = ssh_core::SkSigner::new(credential.into(), comment, authenticator)?;
            ssh_core::Auth::External { signer: Arc::new(signer), certificate: some_text(certificate) }
        }
    })
}

/// uniffi enums have no room for "absent" in a `String`, so the app sends an
/// empty one and it means the same thing here.
fn some_text(s: &str) -> Option<String> {
    let s = s.trim();
    (!s.is_empty()).then(|| s.to_string())
}

/// Bridges the app's keystore signer onto the core's signing trait.
struct ForeignSigner(Arc<dyn KeystoreSigner>);

impl std::fmt::Debug for ForeignSigner {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "ForeignSigner")
    }
}

impl ssh_core::ExternalSigner for ForeignSigner {
    fn public_key(&self) -> String {
        self.0.public_key()
    }

    fn sign(&self, data: &[u8]) -> Result<Vec<u8>, String> {
        self.0.sign(data.to_vec()).map_err(|e| e.to_string())
    }
}

fn to_proxy(p: &ProxyConfig) -> ssh_core::ProxyConfig {
    ssh_core::ProxyConfig {
        kind: match p.kind {
            ProxyKind::Socks5 => ssh_core::ProxyKind::Socks5,
            ProxyKind::Http => ssh_core::ProxyKind::Http,
        },
        host: p.host.clone(),
        port: p.port,
        username: p.username.clone(),
        password: p.password.clone(),
    }
}
