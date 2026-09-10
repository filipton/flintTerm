//! SSH client built on `russh`: sessions, interactive shells with a pty,
//! SFTP, and local/remote port forwarding.

pub mod agent;
pub mod cert;
pub mod forward;
pub mod keys;
pub mod proxy;
pub mod sftp;
pub mod sk;

use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use bytes::Bytes;
use russh::client::{self, Handle, KeyboardInteractiveAuthResponse, Msg};
use russh::keys::{HashAlg, PrivateKeyWithHashAlg, PublicKeyOrCertificate};
use russh::{Channel, ChannelMsg, ChannelReadHalf, ChannelWriteHalf, Disconnect};
use tokio::io::{AsyncRead, AsyncWrite};
use tokio::sync::Mutex;

pub use forward::{LocalForward, RemoteForward};
pub use agent::{AgentKeys, AgentPolicy, AllowAll};
pub use cert::{CertificateInfo, Validity};
pub use proxy::{ProxyConfig, ProxyKind};
pub use russh::ChannelStream;
/// A `direct-tcpip` tunnel opened through a session, usable as the transport of another session.
pub type TunnelStream = ChannelStream<Msg>;
pub use sftp::Sftp;
pub use sk::{SkAlgorithm, SkAssertion, SkAuthenticator, SkCredential, SkSigner, DEFAULT_APPLICATION};

/// Byte stream an SSH session can run over (a TCP socket, or a channel of another session).
pub type BoxedStream = Box<dyn AsyncStream>;
pub trait AsyncStream: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T: AsyncRead + AsyncWrite + Unpin + Send> AsyncStream for T {}

#[derive(Debug, thiserror::Error)]
pub enum SshError {
    #[error("{0}")]
    Ssh(#[from] russh::Error),
    #[error("connection timed out")]
    Timeout,
    #[error("io: {0}")]
    Io(#[from] std::io::Error),
    #[error("host key rejected")]
    HostKeyRejected,
    #[error("authentication failed ({0})")]
    AuthFailed(String),
    /// Nobody answered the server's login question. Kept apart from
    /// [`SshError::AuthFailed`] because it is not a wrong answer: trying the
    /// next key, or the next address, would only put the same dialog up again.
    #[error("{0}")]
    AuthCancelled(String),
    #[error("invalid private key: {0}")]
    InvalidKey(String),
    #[error("sftp: {0}")]
    Sftp(String),
    #[error("{0}")]
    Other(String),
}

impl From<russh::SendError> for SshError {
    fn from(e: russh::SendError) -> Self {
        // The channel to the connection task is gone, which from a caller's
        // point of view is the connection being gone.
        SshError::Other(e.to_string())
    }
}

impl From<russh::keys::Error> for SshError {
    fn from(e: russh::keys::Error) -> Self {
        SshError::InvalidKey(e.to_string())
    }
}

impl From<russh_sftp::client::error::Error> for SshError {
    fn from(e: russh_sftp::client::error::Error) -> Self {
        SshError::Sftp(e.to_string())
    }
}

/// Signs on behalf of a key this process cannot read.
///
/// A key in Android's keystore never leaves it, and a key on a FIDO2 security
/// key was never on the phone at all: both will sign but neither will be handed
/// over. So authentication asks the holder for a signature over the bytes the
/// server wants signed, and gets back a finished SSH signature blob.
pub trait ExternalSigner: Send + Sync + std::fmt::Debug {
    /// The public key in OpenSSH one-line form ("ecdsa-sha2-nistp256 AAAA…").
    fn public_key(&self) -> String;
    /// The SSH signature blob for `data`, or an error the user should see.
    fn sign(&self, data: &[u8]) -> Result<Vec<u8>, String>;
}

#[derive(Debug, Clone)]
pub enum Auth {
    Password(String),
    Key {
        private_key: String,
        passphrase: Option<String>,
        /// The CA's certificate for this key, as one OpenSSH line. Offered in
        /// place of the bare public key, which a server that only trusts the CA
        /// would not recognise.
        certificate: Option<String>,
    },
    /// A key held somewhere that signs for us — the phone's keystore, or a
    /// FIDO2 security key over USB or NFC (see [`sk::SkSigner`]).
    External {
        signer: Arc<dyn ExternalSigner>,
        certificate: Option<String>,
    },
}

/// One question the server asked while logging in.
#[derive(Debug, Clone)]
pub struct Prompt {
    /// The server's own wording, "Verification code: " and all.
    pub text: String,
    /// Whether what is typed may be shown. False for a password or a code.
    pub echo: bool,
}

/// Answers the server's login questions.
///
/// Keyboard-interactive is a conversation rather than a credential: the server
/// asks whatever it likes — a verification code, a password that has expired,
/// a challenge to type into a token — and only the person holding the phone
/// can answer. Called off the async runtime, so it may block for as long as
/// they take; `None` means they walked away from it.
pub trait AuthPrompter: Send + Sync + std::fmt::Debug {
    fn ask(&self, name: &str, instruction: &str, prompts: &[Prompt]) -> Option<Vec<String>>;
}

/// Told what the server printed before login.
///
/// Pushed as it arrives rather than collected and handed over at the end,
/// because a banner is sometimes an instruction rather than a notice:
/// Tailscale's SSH check mode prints a login URL there and then waits for
/// somebody to open it. A URL delivered once the connection is up is a URL
/// nobody needed.
pub trait BannerSink: Send + Sync + std::fmt::Debug {
    fn banner(&self, text: &str);
}

/// The key exchanges this client will do, best first.
///
/// This is russh's own order today, written down so that it stays that way. An
/// upgrade that reordered the list — or dropped the hybrid, as any of these
/// lists eventually drops something — would quietly take post-quantum
/// protection off every connection, and nothing on screen would look different.
///
/// The hybrid is first because "harvest now, decrypt later" is the threat that
/// is already happening: traffic recorded today is decrypted whenever a quantum
/// computer arrives. ML-KEM is what makes that recording useless, and x25519 is
/// still underneath it in the same exchange, so a weakness found in ML-KEM
/// costs nothing that was not already there.
///
/// The four `ext-info` / `kex-strict` entries are not key exchanges at all but
/// the signals that turn on RFC 8308 extension negotiation and OpenSSH's strict
/// KEX; leaving them out of an explicit list would turn both off.
/// Ciphers in the order this client asks for them.
///
/// AES-GCM ahead of ChaCha20-Poly1305, which is the other way round from the
/// library's default. ChaCha20 is the right default for a CPU with no AES
/// instructions, because a software AES is both slower and harder to keep
/// constant-time — but every ARMv8 phone has the AES and PMULL instructions,
/// and with them AES-GCM decrypts a stream several times more cheaply. On a
/// session watching something that redraws itself, that is battery.
const CIPHER_ORDER: &[russh::cipher::Name] = &[
    russh::cipher::AES_256_GCM,
    russh::cipher::CHACHA20_POLY1305,
    russh::cipher::AES_256_CTR,
    russh::cipher::AES_192_CTR,
    russh::cipher::AES_128_CTR,
];

const KEX_ORDER: &[russh::kex::Name] = &[
    russh::kex::MLKEM768X25519_SHA256,
    russh::kex::CURVE25519,
    russh::kex::CURVE25519_PRE_RFC_8731,
    russh::kex::DH_GEX_SHA256,
    russh::kex::DH_G18_SHA512,
    russh::kex::DH_G17_SHA512,
    russh::kex::DH_G16_SHA512,
    russh::kex::DH_G15_SHA512,
    russh::kex::DH_G14_SHA256,
    russh::kex::EXTENSION_SUPPORT_AS_CLIENT,
    russh::kex::EXTENSION_SUPPORT_AS_SERVER,
    russh::kex::EXTENSION_OPENSSH_STRICT_KEX_AS_CLIENT,
    russh::kex::EXTENSION_OPENSSH_STRICT_KEX_AS_SERVER,
];

/// A login is a conversation, but a server that never stops asking is a loop.
const MAX_AUTH_ROUNDS: usize = 8;

/// How long one question waits for its answer. Reading a code off another
/// device takes a while; two minutes of silence is nobody there.
const ANSWER_TIMEOUT: Duration = Duration::from_secs(120);

#[derive(Debug, Clone)]
pub struct ConnectOptions {
    pub host: String,
    pub port: u16,
    pub username: String,
    /// Tried in order until one succeeds.
    pub auth: Vec<Auth>,
    pub keepalive_interval: Option<Duration>,
    pub connect_timeout: Duration,
    /// Route the TCP connection through a SOCKS5/HTTP proxy.
    pub proxy: Option<ProxyConfig>,
    /// Keys offered through agent forwarding (`ssh -A`); None disables forwarding.
    pub agent: Option<AgentKeys>,
    /// Answers whatever the server asks over keyboard-interactive. Without one,
    /// a login that needs more than the stored password fails.
    pub prompter: Option<Arc<dyn AuthPrompter>>,
    /// Given the pre-login banner, if the server sends one and anyone wants it.
    pub on_banner: Option<Arc<dyn BannerSink>>,
}

#[derive(Debug, Clone)]
pub struct HostKeyInfo {
    pub host: String,
    pub port: u16,
    pub key_type: String,
    /// Base64 key blob, as found in `known_hosts`.
    pub key_base64: String,
    /// `SHA256:...` fingerprint.
    pub fingerprint: String,
}

/// Decides whether to trust a server key. Called off the async runtime, so it
/// may block (for instance on a confirmation dialog).
pub trait HostKeyVerifier: Send + Sync + 'static {
    fn verify(&self, info: &HostKeyInfo) -> bool;
}

pub struct AcceptAll;
impl HostKeyVerifier for AcceptAll {
    fn verify(&self, _: &HostKeyInfo) -> bool {
        true
    }
}

type RemoteTargets = Arc<std::sync::Mutex<HashMap<(String, u32), SocketAddr>>>;

struct ClientHandler {
    host: String,
    port: u16,
    verifier: Arc<dyn HostKeyVerifier>,
    remote_targets: RemoteTargets,
    agent: Option<AgentKeys>,
    on_banner: Option<Arc<dyn BannerSink>>,
    kex: KexAlgorithm,
}

/// What the two sides settled on, once they have; shared with the client so it
/// can be reported after the connection is up.
type KexAlgorithm = Arc<std::sync::Mutex<Option<String>>>;

impl client::Handler for ClientHandler {
    type Error = SshError;

    async fn auth_banner(&mut self, banner: &str, _session: &mut client::Session) -> Result<(), Self::Error> {
        if let Some(sink) = &self.on_banner {
            sink.banner(banner);
        }
        Ok(())
    }

    /// The shared secret is deliberately ignored: nothing here needs it, and a
    /// copy of it is a copy of the session.
    async fn kex_done(
        &mut self,
        _shared_secret: Option<&[u8]>,
        names: &russh::Names,
        _session: &mut client::Session,
    ) -> Result<(), Self::Error> {
        *self.kex.lock().unwrap() = Some(names.kex.as_ref().to_string());
        Ok(())
    }

    async fn check_server_key(&mut self, key: &PublicKeyOrCertificate) -> Result<bool, Self::Error> {
        let public = match key {
            PublicKeyOrCertificate::PublicKey { key, .. } => key.clone(),
            PublicKeyOrCertificate::Certificate(cert) => cert.public_key().clone().into(),
        };
        let openssh = public.to_openssh().map_err(|e| SshError::Other(e.to_string()))?;
        let mut parts = openssh.split_whitespace();
        let info = HostKeyInfo {
            host: self.host.clone(),
            port: self.port,
            key_type: parts.next().unwrap_or_default().to_string(),
            key_base64: parts.next().unwrap_or_default().to_string(),
            fingerprint: public.fingerprint(HashAlg::Sha256).to_string(),
        };
        let verifier = self.verifier.clone();
        let ok = tokio::task::spawn_blocking(move || verifier.verify(&info))
            .await
            .map_err(|e| SshError::Other(e.to_string()))?;
        Ok(ok)
    }

    async fn server_channel_open_agent_forward(
        &mut self,
        channel: Channel<Msg>,
        reply: client::ChannelOpenHandle,
        _session: &mut client::Session,
    ) -> Result<(), Self::Error> {
        match self.agent.clone() {
            Some(keys) => {
                reply.accept().await;
                tokio::spawn(async move { agent::serve(channel.into_stream(), keys).await });
            }
            None => reply.reject(russh::ChannelOpenFailure::AdministrativelyProhibited).await,
        }
        Ok(())
    }

    async fn server_channel_open_forwarded_tcpip(
        &mut self,
        channel: Channel<Msg>,
        connected_address: &str,
        connected_port: u32,
        _originator_address: &str,
        _originator_port: u32,
        reply: client::ChannelOpenHandle,
        _session: &mut client::Session,
    ) -> Result<(), Self::Error> {
        let target = self
            .remote_targets
            .lock()
            .unwrap()
            .get(&(connected_address.to_string(), connected_port))
            .copied()
            .or_else(|| {
                // Servers may report a different bind address (e.g. "" vs "localhost").
                let map = self.remote_targets.lock().unwrap();
                map.iter().find(|((_, p), _)| *p == connected_port).map(|(_, t)| *t)
            });
        match target {
            Some(addr) => {
                reply.accept().await;
                tokio::spawn(async move {
                    match tokio::net::TcpStream::connect(addr).await {
                        Ok(mut tcp) => {
                            let mut stream = channel.into_stream();
                            let _ = tokio::io::copy_bidirectional(&mut stream, &mut tcp).await;
                        }
                        Err(e) => {
                            log::warn!("remote forward: connect {addr} failed: {e}");
                            let _ = channel.close().await;
                        }
                    }
                });
            }
            None => {
                reply.reject(russh::ChannelOpenFailure::AdministrativelyProhibited).await;
            }
        }
        Ok(())
    }
}

/// A connected, authenticated SSH session.
pub struct SshClient {
    /// Kept without a lock on purpose: every post-authentication call takes
    /// `&self` and replies through a channel of its own, so a slow one — a
    /// forward to an address that never answers — must not hold up the rest.
    /// A VPN over this connection opens a channel per TCP connection and would
    /// serialize behind any one of them.
    handle: Arc<Handle<ClientHandler>>,
    remote_targets: RemoteTargets,
    forward_agent: bool,
    kex: KexAlgorithm,
}

impl SshClient {
    pub async fn connect(opts: ConnectOptions, verifier: Arc<dyn HostKeyVerifier>) -> Result<SshClient, SshError> {
        // Only the TCP connect is subject to the timeout: the key exchange may
        // legitimately block on the user answering the host-key prompt.
        let tcp = match &opts.proxy {
            Some(p) => proxy::connect(p, &opts.host, opts.port, opts.connect_timeout).await?,
            None => match tokio::time::timeout(
                opts.connect_timeout,
                tokio::net::TcpStream::connect((opts.host.as_str(), opts.port)),
            )
            .await
            {
                Ok(r) => r?,
                Err(_) => return Err(SshError::Timeout),
            },
        };
        let _ = tcp.set_nodelay(true);
        Self::connect_over(opts, tcp, verifier).await
    }

    /// Run the SSH handshake and authentication over an already-open byte
    /// stream, e.g. a `direct-tcpip` channel of a jump host.
    pub async fn connect_over<R>(opts: ConnectOptions, stream: R, verifier: Arc<dyn HostKeyVerifier>) -> Result<SshClient, SshError>
    where
        R: AsyncRead + AsyncWrite + Unpin + Send + 'static,
    {
        let config = client::Config {
            keepalive_interval: opts.keepalive_interval,
            keepalive_max: 3,
            nodelay: true,
            preferred: russh::Preferred {
                kex: std::borrow::Cow::Borrowed(KEX_ORDER),
                cipher: std::borrow::Cow::Borrowed(CIPHER_ORDER),
                ..Default::default()
            },
            ..Default::default()
        };
        let remote_targets: RemoteTargets = Default::default();
        let forward_agent = opts.agent.is_some();
        let kex: KexAlgorithm = Default::default();
        let handler = ClientHandler {
            host: opts.host.clone(),
            port: opts.port,
            verifier,
            remote_targets: remote_targets.clone(),
            agent: opts.agent.clone(),
            on_banner: opts.on_banner.clone(),
            kex: kex.clone(),
        };
        let mut handle = client::connect_stream(Arc::new(config), stream, handler).await.map_err(|e| match e {
            SshError::Ssh(russh::Error::UnknownKey) => SshError::HostKeyRejected,
            other => other,
        })?;

        let mut last_reason = String::from("no authentication methods configured");
        for auth in &opts.auth {
            match Self::try_auth(&mut handle, &opts.username, auth, opts.prompter.as_ref()).await {
                Ok(()) => {
                    return Ok(SshClient { handle: Arc::new(handle), remote_targets, forward_agent, kex });
                }
                Err(SshError::AuthFailed(reason)) => last_reason = reason,
                Err(e) => return Err(e),
            }
        }
        let _ = handle.disconnect(Disconnect::ByApplication, "auth failed", "en").await;
        Err(SshError::AuthFailed(last_reason))
    }

    /// The key exchange both sides settled on ("mlkem768x25519-sha256"), once
    /// they have. Worth saying out loud: it is the difference between a session
    /// a recording can give up later and one it cannot.
    pub fn kex_algorithm(&self) -> Option<String> {
        self.kex.lock().unwrap().clone()
    }

    /// Open a TCP connection from the server to `host:port` and hand it back
    /// as a byte stream (what `ssh -J` / `ProxyJump` does under the hood).
    pub async fn open_direct_tcpip(&self, host: &str, port: u16) -> Result<TunnelStream, SshError> {
        let channel = self.handle.channel_open_direct_tcpip(host, port as u32, "127.0.0.1", 0).await?;
        Ok(channel.into_stream())
    }

    /// Run a command, streaming its output (stdout and stderr) to `on_output`
    /// as it arrives. Returns the exit status.
    pub async fn exec_streaming(&self, command: &str, mut on_output: impl FnMut(&[u8])) -> Result<u32, SshError> {
        let mut channel = self.handle.channel_open_session().await?;
        if self.forward_agent {
            let _ = channel.agent_forward(true).await;
        }
        channel.exec(true, command).await?;
        let mut status = 0;
        while let Some(msg) = channel.wait().await {
            match msg {
                ChannelMsg::Data { data } | ChannelMsg::ExtendedData { data, .. } => on_output(&data),
                ChannelMsg::ExitStatus { exit_status } => status = exit_status,
                ChannelMsg::Close => break,
                _ => {}
            }
        }
        Ok(status)
    }

    async fn try_auth(
        handle: &mut Handle<ClientHandler>,
        user: &str,
        auth: &Auth,
        prompter: Option<&Arc<dyn AuthPrompter>>,
    ) -> Result<(), SshError> {
        // russh asks a Signer for the finished blob, which is exactly what the
        // keystore side produces, so this only carries the call across.
        struct SignerAdaptor {
            signer: Arc<dyn ExternalSigner>,
        }
        impl russh::Signer for SignerAdaptor {
            type Error = SshError;

            async fn auth_sign(
                &mut self,
                _key: &russh::keys::agent::AgentIdentity,
                _hash_alg: Option<russh::keys::HashAlg>,
                to_sign: Vec<u8>,
            ) -> Result<Vec<u8>, Self::Error> {
                let signer = self.signer.clone();
                let data = to_sign.clone();
                let signature = tokio::task::spawn_blocking(move || signer.sign(&data))
                    .await
                    .map_err(|e| SshError::Other(e.to_string()))?
                    .map_err(SshError::AuthFailed)?;
                // russh hands over the whole request-to-be-signed and expects it
                // back with the signature appended as an SSH string — the same
                // contract its ssh-agent client works to. Returning the bare
                // signature writes a truncated packet the server cannot parse.
                let mut out = to_sign;
                out.extend_from_slice(&(signature.len() as u32).to_be_bytes());
                out.extend_from_slice(&signature);
                Ok(out)
            }
        }

        match auth {
            Auth::Password(password) => {
                let res = handle.authenticate_password(user, password.clone()).await?;
                if res.success() {
                    return Ok(());
                }
                // A server that took the password and still says no is asking for
                // a second factor; one that never offered `password` at all wants
                // everything through keyboard-interactive. Both continue into the
                // same conversation — what differs is whether the password has
                // already been spent on the first factor.
                let spent = matches!(res, client::AuthResult::Failure { partial_success: true, .. });
                Self::keyboard_interactive(handle, user, (!spent).then_some(password.as_str()), prompter).await
            }
            Auth::External { signer, certificate } => {
                let mut adaptor = SignerAdaptor { signer: signer.clone() };
                if let Some(text) = certificate.as_deref() {
                    let cert = cert::parse(text)?;
                    note_certificate(&cert);
                    // The keystore only does ECDSA P-256, but an external
                    // signer is not promised to, and an RSA certificate is
                    // signed under whichever SHA-2 the server admits to.
                    let hash = match cert.algorithm() {
                        russh::keys::Algorithm::Rsa { .. } => handle.best_supported_rsa_hash().await?.flatten(),
                        _ => None,
                    };
                    if handle.authenticate_certificate_with(user, cert, hash, &mut adaptor).await?.success() {
                        return Ok(());
                    }
                    note_certificate_refused();
                }
                let line = signer.public_key();
                let public = russh::keys::PublicKey::from_openssh(&line)
                    .map_err(|e| SshError::Other(format!("the signing key is not a usable SSH key: {e}")))?;
                let res = handle.authenticate_publickey_with(user, public, None, &mut adaptor).await?;
                // The keystore and a security key both arrive here, so a refusal
                // is only worth reading if it names which of them was refused.
                Self::after_key(handle, user, res, line.split_whitespace().next().unwrap_or("key"), prompter).await
            }
            Auth::Key { private_key, passphrase, certificate } => {
                let key = Arc::new(russh::keys::decode_secret_key(private_key, passphrase.as_deref())?);
                if let Some(text) = certificate.as_deref() {
                    let cert = cert::parse(text)?;
                    note_certificate(&cert);
                    // No hash to choose: the certificate's own type says how
                    // it is signed, so russh works it out from the key.
                    if handle.authenticate_openssh_cert(user, key.clone(), cert).await?.success() {
                        return Ok(());
                    }
                    note_certificate_refused();
                }
                let hash = handle.best_supported_rsa_hash().await?.flatten();
                let res = handle.authenticate_publickey(user, PrivateKeyWithHashAlg::new(key, hash)).await?;
                Self::after_key(handle, user, res, "public key", prompter).await
            }
        }
    }

    /// What follows a key the server did not simply accept.
    ///
    /// `partial_success` is the interesting case: the key *was* taken and the
    /// server wants another factor, which is how `AuthenticationMethods
    /// publickey,keyboard-interactive` asks for a code. Anything else is a
    /// refusal, and reads as one.
    async fn after_key(
        handle: &mut Handle<ClientHandler>,
        user: &str,
        res: client::AuthResult,
        offered: &str,
        prompter: Option<&Arc<dyn AuthPrompter>>,
    ) -> Result<(), SshError> {
        if prompter.is_some() && wants_another_factor(&res) {
            log::info!("the {offered} was accepted; the server is asking for another factor");
            return Self::keyboard_interactive(handle, user, None, prompter).await;
        }
        rejected(res, offered)
    }

    /// The keyboard-interactive conversation, round by round.
    ///
    /// `password` is the stored password when it has not been spent yet: a
    /// server whose only method is keyboard-interactive asks for the password
    /// through it, and making somebody retype what the app is already holding
    /// would be a ceremony rather than a security measure. It answers at most
    /// one request, and only a first round that asks for a password on its own
    /// — a second round, or anything else asked, is a question only the person
    /// can answer.
    async fn keyboard_interactive(
        handle: &mut Handle<ClientHandler>,
        user: &str,
        password: Option<&str>,
        prompter: Option<&Arc<dyn AuthPrompter>>,
    ) -> Result<(), SshError> {
        let mut sent_password = false;
        let mut reply = handle.authenticate_keyboard_interactive_start(user, None).await?;
        for round in 0..MAX_AUTH_ROUNDS {
            let (name, instruction, prompts) = match reply {
                KeyboardInteractiveAuthResponse::Success => return Ok(()),
                KeyboardInteractiveAuthResponse::Failure { remaining_methods, .. } => {
                    let what = if sent_password { "password" } else { "answer" };
                    return Err(SshError::AuthFailed(format!(
                        "{what} rejected; server accepts {}",
                        describe_methods(&remaining_methods)
                    )));
                }
                KeyboardInteractiveAuthResponse::InfoRequest { name, instructions, prompts } => (name, instructions, prompts),
            };
            let answers = if prompts.is_empty() {
                // Servers use an empty request to say something rather than ask
                // it ("your password expires in three days"). It wants an empty
                // answer, not a person.
                log::info!("the server said something without asking anything");
                Vec::new()
            } else if let (Some(stored), 0, true) = (password, round, asks_for_a_password(&prompts)) {
                sent_password = true;
                vec![stored.to_string()]
            } else {
                let Some(prompter) = prompter else {
                    return Err(SshError::AuthFailed(format!(
                        "the server asked \"{}\" and there is nothing here to answer with",
                        prompts[0].prompt.trim()
                    )));
                };
                ask(prompter, name, instruction, prompts).await?
            };
            reply = handle.authenticate_keyboard_interactive_respond(answers).await?;
        }
        Err(SshError::AuthFailed(format!("the server was still asking after {MAX_AUTH_ROUNDS} rounds")))
    }

    /// Open an interactive shell with a pty.
    pub async fn open_shell(&self, term: &str, cols: u16, rows: u16) -> Result<Shell, SshError> {
        self.open_shell_env(term, cols, rows, &[]).await
    }

    /// As [`SshClient::open_shell`], but asking the server for each variable in
    /// `env` first — the only point in a channel's life at which it will listen.
    ///
    /// What arrives on the other side is the server's decision: `AcceptEnv`
    /// governs it and a stock sshd lists little beyond `LANG` and `LC_*`, so
    /// most variables are quietly dropped. That is not worth losing a shell
    /// over, so no reply is waited for and a request that cannot be sent is
    /// logged and stepped over.
    pub async fn open_shell_env(
        &self,
        term: &str,
        cols: u16,
        rows: u16,
        env: &[(String, String)],
    ) -> Result<Shell, SshError> {
        let channel = self.handle.channel_open_session().await?;
        let (read, write) = channel.split();
        if self.forward_agent {
            if let Err(e) = write.agent_forward(true).await {
                log::warn!("agent forwarding request failed: {e}");
            }
        }
        for (name, value) in env.iter().filter(|(n, _)| !n.is_empty()) {
            if let Err(e) = write.set_env(false, name.as_str(), value.as_str()).await {
                log::warn!("could not ask for environment variable {name}: {e}");
            }
        }
        write.request_pty(true, term, cols as u32, rows as u32, 0, 0, &[]).await?;
        write.request_shell(true).await?;
        Ok(Shell { read, write: Arc::new(Mutex::new(write)) })
    }

    /// Run a single command and collect its output.
    /// Open a channel with agent forwarding and keep it, returning the socket
    /// path sshd made for it.
    ///
    /// The forwarded agent lives and dies with the channel that asked for it, so
    /// a Mosh session — which has no SSH channel at all once it starts — can only
    /// have an agent if something holds one open on its behalf. The returned
    /// channel is that something: drop it and the socket goes away.
    pub async fn hold_agent_socket(&self) -> Result<(String, russh::Channel<russh::client::Msg>), SshError> {
        let mut channel = self.handle.channel_open_session().await?;
        channel.agent_forward(true).await?;
        // Print the path, then sit still. `read` blocks without spinning and ends
        // the moment the channel closes.
        channel.exec(true, "echo \"$SSH_AUTH_SOCK\"; read _ 2>/dev/null || sleep 86400").await?;
        let deadline = tokio::time::Instant::now() + Duration::from_secs(10);
        let mut line = String::new();
        while tokio::time::Instant::now() < deadline {
            match tokio::time::timeout(Duration::from_secs(10), channel.wait()).await {
                Ok(Some(russh::ChannelMsg::Data { ref data })) => {
                    line.push_str(&String::from_utf8_lossy(data));
                    if let Some(nl) = line.find('\n') {
                        let path = line[..nl].trim().to_string();
                        return if path.is_empty() {
                            Err(SshError::Other("the server did not set SSH_AUTH_SOCK".into()))
                        } else {
                            Ok((path, channel))
                        };
                    }
                }
                Ok(Some(_)) => {}
                Ok(None) => break,
                Err(_) => break,
            }
        }
        Err(SshError::Other("no agent socket came back from the server".into()))
    }

    pub async fn exec(&self, command: &str) -> Result<(Vec<u8>, u32), SshError> {
        let mut channel = self.handle.channel_open_session().await?;
        if self.forward_agent {
            let _ = channel.agent_forward(true).await;
        }
        channel.exec(true, command).await?;
        let mut out = Vec::new();
        let mut status = 0;
        while let Some(msg) = channel.wait().await {
            match msg {
                ChannelMsg::Data { data } | ChannelMsg::ExtendedData { data, .. } => out.extend_from_slice(&data),
                ChannelMsg::ExitStatus { exit_status } => status = exit_status,
                ChannelMsg::Close => break,
                _ => {}
            }
        }
        Ok((out, status))
    }

    pub async fn open_sftp(&self) -> Result<Sftp, SshError> {
        let channel = self.handle.channel_open_session().await?;
        channel.request_subsystem(true, "sftp").await?;
        let session = russh_sftp::client::SftpSession::new(channel.into_stream()).await?;
        Ok(Sftp::new(session))
    }

    /// Listen on `bind` locally and tunnel each connection to `target_host:target_port`
    /// through the server.
    pub async fn local_forward(
        &self,
        bind: SocketAddr,
        target_host: String,
        target_port: u16,
    ) -> Result<LocalForward, SshError> {
        forward::start_local(self.handle.clone(), bind, target_host, target_port).await
    }

    /// `-D`: a local SOCKS5 (and SOCKS4a) proxy whose connections leave from the server.
    pub async fn dynamic_forward(&self, bind: SocketAddr) -> Result<LocalForward, SshError> {
        forward::start_dynamic(self.handle.clone(), bind).await
    }

    /// Ask the server to listen on `remote_host:remote_port` and deliver
    /// connections to `target` from this device.
    pub async fn remote_forward(
        &self,
        remote_host: String,
        remote_port: u16,
        target: SocketAddr,
    ) -> Result<RemoteForward, SshError> {
        let port = self.handle.tcpip_forward(remote_host.clone(), remote_port as u32).await?;
        let port = if port == 0 { remote_port as u32 } else { port };
        self.remote_targets.lock().unwrap().insert((remote_host.clone(), port), target);
        Ok(RemoteForward::new(self.handle.clone(), self.remote_targets.clone(), remote_host, port))
    }

    pub async fn disconnect(&self) {
        let _ = self.handle.disconnect(Disconnect::ByApplication, "bye", "en").await;
    }

    pub async fn is_closed(&self) -> bool {
        self.handle.is_closed()
    }
}

/// Whether a refusal is really the server asking for the next factor.
///
/// `partial_success` is the server saying it took what was offered and wants
/// more before it will let anyone in — `AuthenticationMethods
/// publickey,keyboard-interactive`. Without it the same packet is a plain no,
/// and continuing into a conversation would put a code prompt in front of
/// somebody whose key was simply not in `authorized_keys`.
fn wants_another_factor(res: &client::AuthResult) -> bool {
    matches!(
        res,
        client::AuthResult::Failure { remaining_methods, partial_success: true }
            if remaining_methods.iter().any(|m| matches!(m, russh::MethodKind::KeyboardInteractive))
    )
}

/// Whether the server is asking for the password this connection already holds:
/// one prompt, and it says so. Two fields, or anything else asked for, is a
/// question for the person — a code from an app, an answer to a challenge.
fn asks_for_a_password(prompts: &[client::Prompt]) -> bool {
    prompts.len() == 1 && prompts[0].prompt.to_lowercase().contains("password")
}

/// Put the server's questions to whoever is holding the phone, off the runtime.
///
/// What comes back is a one-time code or a password: it goes to the caller, on
/// to the wire, and nowhere else — not into a log line, not into an error
/// message, not even in a count of characters.
async fn ask(
    prompter: &Arc<dyn AuthPrompter>,
    name: String,
    instruction: String,
    prompts: Vec<client::Prompt>,
) -> Result<Vec<String>, SshError> {
    let wanted = prompts.len();
    let prompts: Vec<Prompt> = prompts.into_iter().map(|p| Prompt { text: p.prompt, echo: p.echo }).collect();
    log::info!("the server is asking {wanted} question(s) to log in");
    let prompter = prompter.clone();
    let asked = tokio::task::spawn_blocking(move || prompter.ask(&name, &instruction, &prompts));
    match tokio::time::timeout(ANSWER_TIMEOUT, asked).await {
        Ok(Ok(Some(answers))) if answers.len() == wanted => Ok(answers),
        // A prompter that answers a different number of questions than it was
        // asked would send the server nonsense in the right order.
        Ok(Ok(Some(_))) => Err(SshError::Other("the login prompt answered the wrong number of questions".into())),
        Ok(Ok(None)) => Err(SshError::AuthCancelled("the login prompt was cancelled".into())),
        Ok(Err(e)) => Err(SshError::Other(e.to_string())),
        Err(_) => Err(SshError::AuthCancelled("nobody answered the server's question".into())),
    }
}

/// Turn an authentication result into `Ok(())` or a reason naming what was
/// offered — "certificate rejected" and "public key rejected" send the reader
/// looking in very different places.
fn rejected(res: russh::client::AuthResult, offered: &str) -> Result<(), SshError> {
    if res.success() {
        return Ok(());
    }
    let remaining = match res {
        russh::client::AuthResult::Failure { remaining_methods, .. } => describe_methods(&remaining_methods),
        _ => String::new(),
    };
    Err(SshError::AuthFailed(format!("{offered} rejected; server accepts {remaining}")))
}

/// Say what is being offered, and warn when the certificate is outside its
/// window: the server will refuse it and say nothing useful about why.
fn note_certificate(certificate: &russh::keys::Certificate) {
    let info = cert::describe(certificate);
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs().min(i64::MAX as u64) as i64)
        .unwrap_or(0);
    let who = if info.principals.is_empty() { "any principal".to_string() } else { info.principals.join(", ") };
    match cert::validity(&info, now) {
        cert::Validity::Current => log::info!("offering certificate for {who} (key id {})", info.key_id),
        cert::Validity::Expired => log::warn!("certificate for {who} expired; the server will refuse it"),
        cert::Validity::NotYet => log::warn!("certificate for {who} is not valid yet — check this device's clock"),
    }
}

/// A certificate the server would not take is not the end of the attempt.
///
/// OpenSSH offers the certificate first and the bare key after it, and so do
/// we: a server that has never been told about the CA — or one whose trust in
/// it has been taken away since — refuses the certificate and will still take
/// the key underneath it. Falling straight through to a failure would lock the
/// person out of every host but the ones the CA covers.
fn note_certificate_refused() {
    log::info!("the server refused the certificate; offering the key on its own");
}

fn describe_methods(set: &russh::MethodSet) -> String {
    let names: Vec<String> = set.iter().map(|m| format!("{m:?}").to_lowercase()).collect();
    if names.is_empty() {
        "nothing".into()
    } else {
        names.join(", ")
    }
}

#[derive(Debug)]
pub enum ShellEvent {
    Data(Bytes),
    Exit(u32),
    Closed,
}

pub struct Shell {
    read: ChannelReadHalf,
    write: Arc<Mutex<ChannelWriteHalf<Msg>>>,
}

impl Shell {
    pub async fn next(&mut self) -> ShellEvent {
        loop {
            match self.read.wait().await {
                Some(ChannelMsg::Data { data }) => return ShellEvent::Data(data),
                Some(ChannelMsg::ExtendedData { data, .. }) => return ShellEvent::Data(data),
                Some(ChannelMsg::ExitStatus { exit_status }) => return ShellEvent::Exit(exit_status),
                Some(ChannelMsg::Eof) | Some(ChannelMsg::Close) | None => return ShellEvent::Closed,
                Some(_) => continue,
            }
        }
    }

    pub fn writer(&self) -> ShellWriter {
        ShellWriter { write: self.write.clone() }
    }
}

#[derive(Clone)]
pub struct ShellWriter {
    write: Arc<Mutex<ChannelWriteHalf<Msg>>>,
}

impl ShellWriter {
    pub async fn write(&self, bytes: impl Into<Bytes>) -> Result<(), SshError> {
        self.write.lock().await.data_bytes(bytes.into()).await?;
        Ok(())
    }

    pub async fn resize(&self, cols: u16, rows: u16) -> Result<(), SshError> {
        self.write.lock().await.window_change(cols as u32, rows as u32, 0, 0).await?;
        Ok(())
    }

    pub async fn close(&self) {
        let w = self.write.lock().await;
        let _ = w.eof().await;
        let _ = w.close().await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use russh::MethodKind;

    fn methods(kinds: &[MethodKind]) -> russh::MethodSet {
        let mut set = russh::MethodSet::empty();
        for k in kinds {
            set.push(*k);
        }
        set
    }

    fn failure(kinds: &[MethodKind], partial_success: bool) -> client::AuthResult {
        client::AuthResult::Failure { remaining_methods: methods(kinds), partial_success }
    }

    /// The packet a server sends after `AuthenticationMethods
    /// publickey,keyboard-interactive` has taken the key.
    #[test]
    fn a_partial_success_that_offers_questions_is_a_second_factor() {
        assert!(wants_another_factor(&failure(&[MethodKind::KeyboardInteractive], true)));
    }

    #[test]
    fn a_plain_refusal_is_not_a_second_factor() {
        // The same methods, without the flag: the key was simply not wanted, and
        // asking for a code here would be asking for a code that does not exist.
        assert!(!wants_another_factor(&failure(&[MethodKind::KeyboardInteractive], false)));
        // Taken, but what it wants next is not a conversation we can have.
        assert!(!wants_another_factor(&failure(&[MethodKind::Password], true)));
    }

    #[test]
    fn only_a_lone_password_prompt_is_answered_from_storage() {
        let prompt = |text: &str| client::Prompt { prompt: text.into(), echo: false };
        assert!(asks_for_a_password(&[prompt("Password: ")]));
        assert!(asks_for_a_password(&[prompt("LDAP password for someone:")]));
        // A code is not a password, and two fields are a form nobody stored.
        assert!(!asks_for_a_password(&[prompt("Verification code: ")]));
        assert!(!asks_for_a_password(&[prompt("Password: "), prompt("Code: ")]));
        assert!(!asks_for_a_password(&[]));
    }
}
