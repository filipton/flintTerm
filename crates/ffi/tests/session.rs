use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use flintterm::*;

struct Listener {
    damage: AtomicUsize,
    states: Mutex<Vec<SessionState>>,
    progress: Mutex<Vec<String>>,
    images: AtomicUsize,
}
impl Listener {
    fn new() -> Arc<Self> {
        Arc::new(Self {
            damage: AtomicUsize::new(0),
            states: Mutex::new(vec![]),
            progress: Mutex::new(vec![]),
            images: AtomicUsize::new(0),
        })
    }
}
impl SessionListener for Listener {
    fn on_damage(&self) {
        self.damage.fetch_add(1, Ordering::Relaxed);
    }
    fn on_state(&self, state: SessionState) {
        self.states.lock().unwrap().push(state);
    }
    fn on_title(&self, _: Option<String>) {}
    fn on_bell(&self) {}
    fn on_clipboard(&self, _: String) {}
    fn on_progress(&self, _key: String, m: String, _status: flintterm::StepStatus) {
        self.progress.lock().unwrap().push(m);
    }
    fn on_pattern(&self, p: String, line: String) {
        self.progress.lock().unwrap().push(format!("PATTERN {p}: {line}"));
    }
    fn on_notify(&self, title: String, body: String) {
        self.progress.lock().unwrap().push(format!("NOTIFY {title}: {body}"));
    }
    fn on_prompt_mark(&self, kind: flintterm::PromptKind, exit: Option<i32>) {
        self.progress.lock().unwrap().push(format!("MARK {kind:?} {exit:?}"));
    }
    fn on_cwd(&self, path: String) {
        self.progress.lock().unwrap().push(format!("CWD {path}"));
    }
    fn on_banner(&self, text: String) {
        self.progress.lock().unwrap().push(format!("BANNER {text}"));
    }
    fn on_images_changed(&self) {
        self.images.fetch_add(1, Ordering::Relaxed);
    }
}
struct Accept;
impl HostKeyVerifier for Accept {
    fn verify(&self, _: HostKey) -> bool {
        true
    }
}

/// Nothing here is in front of a person, so a server that starts asking
/// questions is a session that ends rather than one that waits for ever.
#[derive(Debug)]
struct NoQuestions;
impl AuthPrompter for NoQuestions {
    fn ask(&self, _name: String, _instruction: String, _prompts: Vec<Prompt>) -> Option<Vec<String>> {
        None
    }
}

fn screen_text(session: &Session) -> String {
    let snap = session.snapshot();
    let header = snapshot_header_bytes() as usize;
    let cell = snapshot_cell_bytes() as usize;
    let cols = u16::from_le_bytes([snap[0], snap[1]]) as usize;
    let rows = u16::from_le_bytes([snap[2], snap[3]]) as usize;
    let mut s = String::new();
    for r in 0..rows {
        for c in 0..cols {
            let off = header + (r * cols + c) * cell;
            let cp = u32::from_le_bytes(snap[off..off + 4].try_into().unwrap());
            s.push(char::from_u32(cp).filter(|c| *c != '\0').unwrap_or(' '));
        }
        s.push('\n');
    }
    s
}

fn wait_for(session: &Session, needle: &str) -> String {
    let start = Instant::now();
    loop {
        let text = screen_text(session);
        if text.contains(needle) {
            return text;
        }
        assert!(start.elapsed() < Duration::from_secs(10), "timed out waiting for {needle:?}; screen:\n{text}");
        std::thread::sleep(Duration::from_millis(30));
    }
}

#[test]
fn local_shell_roundtrip() {
    let listener = Listener::new();
    let backend = Backend::Local {
        config: LocalShellConfig {
            program: "/bin/sh".into(),
            args: vec![],
            env: vec![EnvVar { name: "PATH".into(), value: "/usr/bin:/bin".into() }, EnvVar { name: "PS1".into(), value: "$ ".into() }],
            cwd: None,
        },
    };
    let session = Session::new(backend, 40, 10, 1000, listener.clone(), Arc::new(Accept), Arc::new(NoQuestions), Options::default());
    session.start();
    session.send_text("echo hello-$(stty size)\n".into());
    wait_for(&session, "hello-10 40");
    assert!(listener.damage.load(Ordering::Relaxed) > 0);

    session.resize(60, 20);
    session.send_text("stty size\n".into());
    wait_for(&session, "20 60");

    // Selection of a word and copy.
    session.send_text("echo selectme-please\n".into());
    let text = wait_for(&session, "selectme-please");
    let row = text.lines().position(|l| l.trim_start().starts_with("selectme-please")).unwrap();
    session.select_start(2, row as u16, SelectKind::Word);
    assert_eq!(session.selected_text().as_deref(), Some("selectme-please"));
    session.select_clear();
    assert!(!session.has_selection());

    // Scrollback text + search helpers, and an output watch.
    session.set_watch_patterns(vec!["build (finished|failed)".into()]).unwrap();
    session.send_text("echo the build finished ok\n".into());
    wait_for(&session, "finished ok");
    let lines = session.all_lines();
    assert!(lines.iter().any(|l| l.contains("selectme-please")), "{lines:?}");
    let start = Instant::now();
    while !listener.progress.lock().unwrap().iter().any(|m| m.starts_with("PATTERN")) {
        assert!(start.elapsed() < Duration::from_secs(3), "no pattern hit: {:?}", listener.progress.lock().unwrap());
        std::thread::sleep(Duration::from_millis(30));
    }
    // Fill the screen so there is history, then scroll to the top and back.
    session.send_text("for i in $(seq 1 60); do echo line-$i; done\n".into());
    wait_for(&session, "line-60");
    assert!(session.history_size() > 0);
    session.scroll_to_offset(session.history_size());
    assert_eq!(session.display_offset(), session.history_size());
    session.scroll_to_offset(0);
    assert_eq!(session.display_offset(), 0);

    // Ctrl-C then exit.
    session.send_key(KeyPress { key: KeyCode::Char { codepoint: 'c' as u32 }, ctrl: true, alt: false, shift: false, kind: KeyEventKind::Press });
    wait_for(&session, "^C");
    session.send_text("exit 7\n".into());
    let start = Instant::now();
    loop {
        if let SessionState::Disconnected { exit_code, .. } = session.state() {
            assert_eq!(exit_code, Some(7));
            break;
        }
        assert!(start.elapsed() < Duration::from_secs(5), "state {:?} screen:\n{}", session.state(), screen_text(&session));
        std::thread::sleep(Duration::from_millis(30));
    }
}

/// A person in front of the host-key prompt is a connection that waits, and the
/// view finishes measuring while they read the fingerprint. That resize has no
/// writer to reach yet, so the size the pty is asked for has to be read when it
/// is asked for — not before dialing. Getting this wrong left the first session
/// to every new host at 80x24, with nothing afterwards to correct it.
#[test]
fn a_resize_while_the_host_key_is_being_checked_reaches_the_pty() {
    let Ok(port) = std::env::var("SSH_TEST_PORT") else { return };
    let key = std::fs::read_to_string(std::env::var("SSH_TEST_KEY").unwrap()).unwrap();

    /// Somebody reading a fingerprint before they tap Trust.
    struct Slow;
    impl HostKeyVerifier for Slow {
        fn verify(&self, _: HostKey) -> bool {
            std::thread::sleep(Duration::from_millis(600));
            true
        }
    }

    let listener = Listener::new();
    let backend = Backend::Ssh {
        config: SshConfig {
            host: "127.0.0.1".into(),
            port: port.parse().unwrap(),
            username: std::env::var("USER").unwrap(),
            auth: vec![AuthMethod::Key { private_key: key, passphrase: None, certificate: String::new() }],
            keepalive_secs: 10,
            connect_timeout_secs: 10,
            proxy: None,
            jumps: vec![],
            tunnel_id: None,
            wait_for_host_secs: 0,
            tailscale_id: None,
            tunnel_fallback: false,
            forward_agent: false,
            agent_keys: vec![],
            agent_approval: None,
            env: vec![],
            alternates: vec![],
            vpn_name: String::new(),
        },
    };
    let session = Session::new(backend, 80, 24, 1000, listener.clone(), Arc::new(Slow), Arc::new(NoQuestions), Options::default());
    session.start();
    // The view settles while the prompt is still up.
    std::thread::sleep(Duration::from_millis(200));
    session.resize(100, 40);

    let start = Instant::now();
    while !matches!(session.state(), SessionState::Connected) {
        assert!(start.elapsed() < Duration::from_secs(10), "state: {:?}", session.state());
        std::thread::sleep(Duration::from_millis(30));
    }
    session.send_text("stty size\n".into());
    wait_for(&session, "40 100");
    session.disconnect();
}

#[test]
fn ssh_session_roundtrip() {
    let Ok(port) = std::env::var("SSH_TEST_PORT") else { return };
    let key = std::fs::read_to_string(std::env::var("SSH_TEST_KEY").unwrap()).unwrap();
    let listener = Listener::new();
    let backend = Backend::Ssh {
        config: SshConfig {
            host: "127.0.0.1".into(),
            port: port.parse().unwrap(),
            username: std::env::var("USER").unwrap(),
            auth: vec![AuthMethod::Key { private_key: key, passphrase: None, certificate: String::new() }],
            keepalive_secs: 10,
            connect_timeout_secs: 10,
            proxy: None,
            jumps: vec![],
            tunnel_id: None,
            wait_for_host_secs: 0,
            tailscale_id: None,
            tunnel_fallback: false,
            forward_agent: false,
            agent_keys: vec![],
            agent_approval: None,
            // Whether these arrive depends on the test sshd's AcceptEnv, so the
            // shell is only expected to come up regardless.
            env: vec![
                EnvVar { name: "LC_ANDROIDTERM".into(), value: "yes".into() },
                EnvVar { name: "TOTALLY_NOT_ACCEPTED".into(), value: "1".into() },
            ],
        alternates: vec![],
        vpn_name: String::new(),
    },
    };
    let session = Session::new(backend, 80, 24, 1000, listener.clone(), Arc::new(Accept), Arc::new(NoQuestions), Options::default());
    session.start();
    let start = Instant::now();
    while !matches!(session.state(), SessionState::Connected) {
        assert!(start.elapsed() < Duration::from_secs(10), "state: {:?}", session.state());
        std::thread::sleep(Duration::from_millis(30));
    }
    session.send_text("echo over-ssh-$(stty size)\n".into());
    wait_for(&session, "over-ssh-24 80");
    assert!(
        listener.progress.lock().unwrap().iter().any(|m| m.contains("2 environment variables")),
        "{:?}",
        listener.progress.lock().unwrap(),
    );

    // A question asked of the live connection: no second login, and the answer
    // comes back over the session that is already up.
    let uname = session.exec_live("uname -s".into()).unwrap();
    assert!(!uname.trim().is_empty(), "uname said nothing");

    let fwd = session.add_local_forward("127.0.0.1".into(), 0, "127.0.0.1".into(), port.parse().unwrap()).unwrap();
    assert!(fwd.port > 0);
    let mut s = std::net::TcpStream::connect(("127.0.0.1", fwd.port)).unwrap();
    use std::io::Read;
    let mut banner = [0u8; 8];
    s.read_exact(&mut banner).unwrap();
    assert_eq!(&banner[..4], b"SSH-");
    session.remove_forward(fwd.id);

    // Dynamic (SOCKS5) forward: connect through it to the sshd itself and read the banner.
    let dyn_fwd = session.add_dynamic_forward("127.0.0.1".into(), 0).unwrap();
    {
        use std::io::{Read, Write};
        let mut s = std::net::TcpStream::connect(("127.0.0.1", dyn_fwd.port)).unwrap();
        s.write_all(&[5, 1, 0]).unwrap();
        let mut r = [0u8; 2];
        s.read_exact(&mut r).unwrap();
        assert_eq!(r, [5, 0]);
        let p: u16 = port.parse().unwrap();
        let mut req = vec![5, 1, 0, 1, 127, 0, 0, 1];
        req.extend_from_slice(&p.to_be_bytes());
        s.write_all(&req).unwrap();
        let mut rep = [0u8; 10];
        s.read_exact(&mut rep).unwrap();
        assert_eq!(rep[1], 0, "socks reply {rep:?}");
        let mut banner = [0u8; 4];
        s.read_exact(&mut banner).unwrap();
        assert_eq!(&banner, b"SSH-");
    }
    session.remove_forward(dyn_fwd.id);

    let sftp = session.open_sftp().unwrap();
    assert!(sftp.home().unwrap().starts_with('/'));
    sftp.shutdown();

    session.disconnect();
    let start = Instant::now();
    while !matches!(session.state(), SessionState::Disconnected { .. }) {
        assert!(start.elapsed() < Duration::from_secs(5));
        std::thread::sleep(Duration::from_millis(30));
    }
}

/// Reach the test server *through itself*: hop = server, target = server via a
/// direct-tcpip tunnel, with a pre-command whose output must show up as progress.
#[test]
fn ssh_via_jump_host_with_pre_command() {
    let Ok(port) = std::env::var("SSH_TEST_PORT") else { return };
    let port: u16 = port.parse().unwrap();
    let key = std::fs::read_to_string(std::env::var("SSH_TEST_KEY").unwrap()).unwrap();
    let user = std::env::var("USER").unwrap();
    let listener = Listener::new();
    let backend = Backend::Ssh {
        config: SshConfig {
            host: "127.0.0.1".into(),
            port,
            username: user.clone(),
            auth: vec![AuthMethod::Key { private_key: key.clone(), passphrase: None, certificate: String::new() }],
            keepalive_secs: 10,
            connect_timeout_secs: 10,
            proxy: None,
            jumps: vec![JumpHop {
                host: "127.0.0.1".into(),
                port,
                username: user,
                auth: vec![AuthMethod::Key { private_key: key, passphrase: None, certificate: String::new() }],
                pre_command: Some("echo waking-up; echo second-line 1>&2; sleep 1".into()),
                wait_for_next_secs: 30,
                proxy: None,
                tunnel_id: None,
                tailscale_id: None,
                forward_agent: false,
            }],
            tunnel_id: None,
            wait_for_host_secs: 0,
            tailscale_id: None,
            tunnel_fallback: false,
            forward_agent: false,
            agent_keys: vec![],
            agent_approval: None,
            env: vec![],
        alternates: vec![],
        vpn_name: String::new(),
    },
    };
    let session = Session::new(backend, 80, 24, 1000, listener.clone(), Arc::new(Accept), Arc::new(NoQuestions), Options::default());
    session.start();
    let start = Instant::now();
    while !matches!(session.state(), SessionState::Connected) {
        assert!(start.elapsed() < Duration::from_secs(20), "state: {:?}\n{}", session.state(), screen_text(&session));
        std::thread::sleep(Duration::from_millis(30));
    }
    session.send_text("echo via-jump-$(stty size)\n".into());
    let text = wait_for(&session, "via-jump-24 80");
    let progress = listener.progress.lock().unwrap().clone();
    assert!(progress.iter().any(|m| m.contains("jump host")), "{progress:?}");
    assert!(progress.iter().any(|m| m.contains("waking-up")), "{progress:?}");
    assert!(progress.iter().any(|m| m.contains("second-line")), "{progress:?}");
    // The terminal belongs to the remote host: connection steps go to the step
    // list, never onto the screen.
    assert!(!text.contains("Running on"), "connection steps must stay out of the terminal:\n{text}");

    session.disconnect();
    let start = Instant::now();
    while !matches!(session.state(), SessionState::Disconnected { .. }) {
        assert!(start.elapsed() < Duration::from_secs(5));
        std::thread::sleep(Duration::from_millis(30));
    }
}

/// A target that never answers: the jump retries until the wait budget runs out.
#[test]
fn ssh_via_jump_host_unreachable_target_times_out() {
    let Ok(port) = std::env::var("SSH_TEST_PORT") else { return };
    let port: u16 = port.parse().unwrap();
    let key = std::fs::read_to_string(std::env::var("SSH_TEST_KEY").unwrap()).unwrap();
    let user = std::env::var("USER").unwrap();
    let listener = Listener::new();
    let backend = Backend::Ssh {
        config: SshConfig {
            host: "127.0.0.1".into(),
            port: 1, // nothing listens here
            username: user.clone(),
            auth: vec![],
            keepalive_secs: 0,
            connect_timeout_secs: 10,
            proxy: None,
            jumps: vec![JumpHop {
                host: "127.0.0.1".into(),
                port,
                username: user,
                auth: vec![AuthMethod::Key { private_key: key, passphrase: None, certificate: String::new() }],
                pre_command: None,
                wait_for_next_secs: 4,
                proxy: None,
                tunnel_id: None,
                tailscale_id: None,
                forward_agent: false,
            }],
            tunnel_id: None,
            wait_for_host_secs: 0,
            tailscale_id: None,
            tunnel_fallback: false,
            forward_agent: false,
            agent_keys: vec![],
            agent_approval: None,
            env: vec![],
        alternates: vec![],
        vpn_name: String::new(),
    },
    };
    let session = Session::new(backend, 80, 24, 1000, listener.clone(), Arc::new(Accept), Arc::new(NoQuestions), Options::default());
    session.start();
    let start = Instant::now();
    loop {
        if let SessionState::Disconnected { error, .. } = session.state() {
            let e = error.unwrap_or_default();
            assert!(e.contains("cannot reach") || e.contains("timed out"), "{e}");
            break;
        }
        assert!(start.elapsed() < Duration::from_secs(30), "still {:?}", session.state());
        std::thread::sleep(Duration::from_millis(50));
    }
    let progress = listener.progress.lock().unwrap().clone();
    assert!(progress.iter().any(|m| m.contains("retrying")), "{progress:?}");
}

/// A session over a registered userspace WireGuard tunnel. The far peer is a
/// second in-process tunnel that bridges port 22 to the real test sshd.
#[test]
fn ssh_session_through_wireguard_tunnel() {
    let Ok(port) = std::env::var("SSH_TEST_PORT") else { return };
    let port: u16 = port.parse().unwrap();
    let key = std::fs::read_to_string(std::env::var("SSH_TEST_KEY").unwrap()).unwrap();
    let a = wg_generate_keypair();
    let b = wg_generate_keypair();

    // Far side, on its own runtime so it behaves like a separate machine.
    let rt = tokio::runtime::Runtime::new().unwrap();
    let server_cfg = format!(
        "[Interface]\nPrivateKey = {}\nAddress = 10.77.0.1/24\n[Peer]\nPublicKey = {}\nAllowedIPs = 10.77.0.0/24\nEndpoint = 127.0.0.1:9\n",
        b.private_key, a.public_key
    );
    let server_addr = rt.block_on(async {
        let server = wg_tunnel::Tunnel::start(wg_tunnel::WgConfig::parse(&server_cfg).unwrap()).await.unwrap();
        let addr = server.local_addr();
        let mut listener = server.listen(22).await.unwrap();
        tokio::spawn(async move {
            let _keep = server;
            while let Some(mut s) = listener.accept().await {
                tokio::spawn(async move {
                    let mut tcp = tokio::net::TcpStream::connect(("127.0.0.1", port)).await.unwrap();
                    let _ = tokio::io::copy_bidirectional(&mut s, &mut tcp).await;
                });
            }
        });
        addr
    });

    let client_cfg = format!(
        "[Interface]\nPrivateKey = {}\nAddress = 10.77.0.2/24\n[Peer]\nPublicKey = {}\nAllowedIPs = 10.77.0.0/24\nEndpoint = {}\nPersistentKeepalive = 10\n",
        a.private_key, b.public_key, server_addr
    );
    let info = register_tunnel("t1".into(), client_cfg).unwrap();
    assert_eq!(info.addresses, vec!["10.77.0.2/24".to_string()]);

    let listener = Listener::new();
    let backend = Backend::Ssh {
        config: SshConfig {
            host: "10.77.0.1".into(),
            port: 22,
            username: std::env::var("USER").unwrap(),
            auth: vec![AuthMethod::Key { private_key: key, passphrase: None, certificate: String::new() }],
            keepalive_secs: 0,
            connect_timeout_secs: 10,
            proxy: None,
            jumps: vec![],
            tunnel_id: Some("t1".into()),
            wait_for_host_secs: 0,
            tailscale_id: None,
            tunnel_fallback: false,
            forward_agent: false,
            agent_keys: vec![],
            agent_approval: None,
            env: vec![],
        alternates: vec![],
        vpn_name: String::new(),
    },
    };
    let session = Session::new(backend, 80, 24, 1000, listener.clone(), Arc::new(Accept), Arc::new(NoQuestions), Options::default());
    session.start();
    let start = Instant::now();
    while !matches!(session.state(), SessionState::Connected) {
        assert!(start.elapsed() < Duration::from_secs(20), "state: {:?}\n{}", session.state(), screen_text(&session));
        std::thread::sleep(Duration::from_millis(30));
    }
    session.send_text("echo via-wg-$(stty size)\n".into());
    wait_for(&session, "via-wg-24 80");
    let stats = tunnel_stats("t1".into());
    assert!(stats.running && stats.tx_bytes > 0, "{stats:?}");
    let progress = listener.progress.lock().unwrap().clone();
    assert!(progress.iter().any(|m| m.contains("through the tunnel")), "{progress:?}");
    session.disconnect();
    stop_tunnel("t1".into());
    assert!(!tunnel_stats("t1".into()).running);
    rt.shutdown_background();
}

/// Agent forwarding: inside the session, `ssh-add -l` must list the phone's key.
#[test]
fn agent_forwarding_lists_our_key() {
    let Ok(port) = std::env::var("SSH_TEST_PORT") else { return };
    let key = std::fs::read_to_string(std::env::var("SSH_TEST_KEY").unwrap()).unwrap();
    let listener = Listener::new();
    let backend = Backend::Ssh {
        config: SshConfig {
            host: "127.0.0.1".into(),
            port: port.parse().unwrap(),
            username: std::env::var("USER").unwrap(),
            auth: vec![AuthMethod::Key { private_key: key.clone(), passphrase: None, certificate: String::new() }],
            keepalive_secs: 0,
            connect_timeout_secs: 10,
            proxy: None,
            jumps: vec![],
            tunnel_id: None,
            wait_for_host_secs: 0,
            tailscale_id: None,
            tunnel_fallback: false,
            forward_agent: true,
            agent_keys: vec![AuthMethod::Key { private_key: key, passphrase: None, certificate: String::new() }],
            agent_approval: None,
            env: vec![],
        alternates: vec![],
        vpn_name: String::new(),
    },
    };
    let session = Session::new(backend, 80, 24, 1000, listener.clone(), Arc::new(Accept), Arc::new(NoQuestions), Options::default());
    let out = session.exec_once("ssh-add -l 2>&1; echo sock=${SSH_AUTH_SOCK:+set}".into()).unwrap();
    assert!(out.contains("sock=set"), "no SSH_AUTH_SOCK: {out}");
    assert!(out.contains("ED25519") || out.contains("RSA"), "agent did not list keys: {out}");
}

// ---------------------------------------------------------------------------
// Telnet
// ---------------------------------------------------------------------------

fn contains_seq(haystack: &[u8], needle: &[u8]) -> bool {
    haystack.windows(needle.len()).any(|w| w == needle)
}

/// A device-ish telnet server: offers the usual options, then echoes payload.
fn fake_telnet_server(seen: Arc<Mutex<Vec<u8>>>) -> u16 {
    use std::io::{Read, Write};
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    std::thread::spawn(move || {
        let (mut sock, _) = listener.accept().unwrap();
        let mut w = sock.try_clone().unwrap();
        // IAC DO NAWS, IAC WILL ECHO, IAC WILL SGA, IAC SB TTYPE SEND IAC SE
        w.write_all(&[255, 253, 31, 255, 251, 1, 255, 251, 3, 255, 250, 24, 1, 255, 240]).unwrap();
        w.write_all(b"device> ").unwrap();
        let mut buf = [0u8; 1024];
        loop {
            let n = match sock.read(&mut buf) {
                Ok(0) | Err(_) => break,
                Ok(n) => n,
            };
            seen.lock().unwrap().extend_from_slice(&buf[..n]);
            // Echo back everything that is not a telnet command.
            let mut out = Vec::new();
            let mut i = 0;
            while i < n {
                if buf[i] != 255 {
                    out.push(buf[i]);
                    i += 1;
                } else if i + 1 < n && buf[i + 1] == 250 {
                    i += 2;
                    while i + 1 < n && !(buf[i] == 255 && buf[i + 1] == 240) {
                        i += 1;
                    }
                    i += 2;
                } else {
                    i += 3;
                }
            }
            if !out.is_empty() && w.write_all(&out).is_err() {
                break;
            }
        }
    });
    port
}

#[test]
fn telnet_session_negotiates_and_echoes() {
    let seen = Arc::new(Mutex::new(Vec::<u8>::new()));
    let port = fake_telnet_server(seen.clone());

    let listener = Listener::new();
    let session = Session::new(
        Backend::Telnet {
            config: TelnetConfig {
                host: "127.0.0.1".into(),
                port,
                connect_timeout_secs: 5,
                wait_for_host_secs: 0,
                tunnel_id: None,
                tailscale_id: None,
            },
        },
        80,
        24,
        1000,
        listener.clone(),
        Arc::new(Accept),
        Arc::new(NoQuestions),
        Options::default(),
    );
    session.start();

    // The banner proves negotiation was stripped out of the data stream.
    wait_for(&session, "device>");
    assert!(matches!(session.state(), SessionState::Connected), "state: {:?}", session.state());

    session.send_text("hello\n".into());
    wait_for(&session, "hello");

    let sent = seen.lock().unwrap().clone();
    assert!(contains_seq(&sent, &[255, 251, 31]), "did not offer NAWS: {sent:?}");
    assert!(contains_seq(&sent, &[255, 253, 1]), "did not accept remote ECHO: {sent:?}");
    assert!(contains_seq(&sent, &[255, 253, 3]), "did not accept SGA: {sent:?}");
    assert!(contains_seq(&sent, b"xterm-256color"), "did not report the terminal type");
    // NAWS subnegotiation carrying 80x24.
    assert!(contains_seq(&sent, &[255, 250, 31, 0, 80, 0, 24, 255, 240]), "no window size: {sent:?}");

    session.disconnect();
}

#[test]
fn telnet_reports_an_unreachable_device() {
    // Port 1 on loopback: nothing listens there.
    let listener = Listener::new();
    let session = Session::new(
        Backend::Telnet {
            config: TelnetConfig {
                host: "127.0.0.1".into(),
                port: 1,
                connect_timeout_secs: 2,
                wait_for_host_secs: 0,
                tunnel_id: None,
                tailscale_id: None,
            },
        },
        80, 24, 200, listener.clone(), Arc::new(Accept), Arc::new(NoQuestions), Options::default(),
    );
    session.start();
    let start = Instant::now();
    loop {
        if let SessionState::Disconnected { error, .. } = session.state() {
            assert!(error.is_some(), "expected an error message");
            break;
        }
        assert!(start.elapsed() < Duration::from_secs(15), "never reported failure");
        std::thread::sleep(Duration::from_millis(30));
    }
}

// ---------------------------------------------------------------------------
// External transport (USB serial): the app owns the device
// ---------------------------------------------------------------------------

struct Device {
    written: Mutex<Vec<u8>>,
    sizes: Mutex<Vec<(u16, u16)>>,
    closed: AtomicUsize,
}
impl ExternalSink for Device {
    fn on_input(&self, data: Vec<u8>) {
        self.written.lock().unwrap().extend_from_slice(&data);
    }
    fn on_resize(&self, cols: u16, rows: u16) {
        self.sizes.lock().unwrap().push((cols, rows));
    }
    fn on_close(&self) {
        self.closed.fetch_add(1, Ordering::Relaxed);
    }
}

#[test]
fn external_session_carries_bytes_both_ways() {
    let device = Arc::new(Device { written: Mutex::new(vec![]), sizes: Mutex::new(vec![]), closed: AtomicUsize::new(0) });
    let listener = Listener::new();
    let session = Session::new(
        Backend::External { config: ExternalConfig { label: "USB serial · 115200 8N1".into() } },
        80, 24, 500, listener.clone(), Arc::new(Accept), Arc::new(NoQuestions), Options::default(),
    );
    session.set_external_sink(device.clone());
    session.start();

    // Device -> screen.
    session.push_output(b"login: ".to_vec());
    wait_for(&session, "login:");
    assert!(matches!(session.state(), SessionState::Connected));

    // Screen -> device.
    session.send_text("root\r".into());
    let start = Instant::now();
    while device.written.lock().unwrap().is_empty() {
        assert!(start.elapsed() < Duration::from_secs(5), "device never received input");
        std::thread::sleep(Duration::from_millis(20));
    }
    assert_eq!(device.written.lock().unwrap().clone(), b"root\r".to_vec());

    // Resize is dispatched onto the runtime, so give it a moment to arrive.
    session.resize(100, 30);
    let start = Instant::now();
    while !device.sizes.lock().unwrap().contains(&(100, 30)) {
        assert!(start.elapsed() < Duration::from_secs(5), "resize not passed on: {:?}", device.sizes.lock().unwrap());
        std::thread::sleep(Duration::from_millis(20));
    }

    // Unplugging the device ends the session.
    session.external_closed();
    let start = Instant::now();
    loop {
        if matches!(session.state(), SessionState::Disconnected { .. }) {
            break;
        }
        assert!(start.elapsed() < Duration::from_secs(5), "session stayed open after the device left");
        std::thread::sleep(Duration::from_millis(20));
    }
}

// ---------------------------------------------------------------------------
// Mosh: bootstrap over SSH, then run the session over UDP
// ---------------------------------------------------------------------------

/// Needs both the test sshd (SSH_TEST_PORT / SSH_TEST_KEY) and a mosh-server
/// binary (MOSH_SERVER); skips when either is missing.
#[test]
fn mosh_session_bootstraps_over_ssh_and_runs_over_udp() {
    let (Ok(port), Ok(key_path), Ok(server)) = (
        std::env::var("SSH_TEST_PORT"),
        std::env::var("SSH_TEST_KEY"),
        std::env::var("MOSH_SERVER"),
    ) else {
        eprintln!("skipping: needs SSH_TEST_PORT, SSH_TEST_KEY and MOSH_SERVER");
        return;
    };
    assert!(std::path::Path::new(&server).is_file(), "MOSH_SERVER={server} is not a file");
    let key = std::fs::read_to_string(&key_path).expect("read test key");

    let listener = Listener::new();
    let session = Session::new(
        Backend::Mosh {
            config: MoshConfig {
                ssh: SshConfig {
                    host: "127.0.0.1".into(),
                    port: port.parse().unwrap(),
                    username: std::env::var("USER").unwrap(),
                    auth: vec![AuthMethod::Key { private_key: key, passphrase: None, certificate: String::new() }],
                    keepalive_secs: 10,
                    connect_timeout_secs: 10,
                    proxy: None,
                    jumps: vec![],
                    tunnel_id: None,
                    wait_for_host_secs: 0,
                    tailscale_id: None,
                    tunnel_fallback: false,
                    forward_agent: false,
                    agent_keys: vec![],
                    agent_approval: None,
                    env: vec![],
        alternates: vec![],
        vpn_name: String::new(),
    },
                locale: "en_US.UTF-8".into(),
                server: server.clone(),
            },
        },
        80,
        24,
        1000,
        listener.clone(),
        Arc::new(Accept),
        Arc::new(NoQuestions),
        Options::default(),
    );
    session.start();

    let start = Instant::now();
    while !matches!(session.state(), SessionState::Connected) {
        assert!(
            start.elapsed() < Duration::from_secs(30),
            "state: {:?}\nprogress: {:?}",
            session.state(),
            listener.progress.lock().unwrap()
        );
        std::thread::sleep(Duration::from_millis(30));
    }

    // The shell that mosh-server started is a real pty on the other side.
    session.send_text("echo mosh-$((3+4))\n".into());
    wait_for(&session, "mosh-7");

    let progress = listener.progress.lock().unwrap().clone();
    assert!(progress.iter().any(|p| p.contains("Starting mosh-server")), "{progress:?}");
    assert!(progress.iter().any(|p| p.contains("leaving SSH behind")), "{progress:?}");
    session.disconnect();
}

#[test]
fn mosh_refuses_tailscale_with_a_clear_reason() {
    // A jump host is fine now (a UDP relay runs on the hop), but the embedded
    // Tailscale node only forwards TCP, so that one still has to be refused.
    let listener = Listener::new();
    let session = Session::new(
        Backend::Mosh {
            config: MoshConfig {
                ssh: SshConfig {
                    host: "host.example".into(),
                    port: 22,
                    username: "nobody".into(),
                    auth: vec![],
                    keepalive_secs: 10,
                    connect_timeout_secs: 2,
                    proxy: None,
                    jumps: vec![],
                    tunnel_id: None,
                    wait_for_host_secs: 0,
                    tailscale_id: Some("ts".into()),
                    tunnel_fallback: false,
                    forward_agent: false,
                    agent_keys: vec![],
                    agent_approval: None,
                    env: vec![],
        alternates: vec![],
        vpn_name: String::new(),
    },
                locale: "C".into(),
                server: String::new(),
            },
        },
        80, 24, 200, listener.clone(), Arc::new(Accept), Arc::new(NoQuestions), Options::default(),
    );
    session.start();
    let start = Instant::now();
    loop {
        if let SessionState::Disconnected { error: Some(e), .. } = session.state() {
            assert!(e.contains("Tailscale"), "expected a Tailscale explanation, got: {e}");
            return;
        }
        assert!(start.elapsed() < Duration::from_secs(10), "state: {:?}", session.state());
        std::thread::sleep(Duration::from_millis(20));
    }
}

/// The whole point of teaching the tunnel UDP: a host reached over WireGuard
/// can still run Mosh. The far side of the tunnel is an in-process peer that
/// relays TCP 22 to the test sshd and the mosh UDP ports to localhost, so this
/// exercises the real path — SSH bootstrap and UDP session both inside the tunnel.
#[test]
fn mosh_session_through_a_wireguard_tunnel() {
    let (Ok(ssh_port), Ok(key_path), Ok(server)) =
        (std::env::var("SSH_TEST_PORT"), std::env::var("SSH_TEST_KEY"), std::env::var("MOSH_SERVER"))
    else {
        eprintln!("skipping: needs SSH_TEST_PORT, SSH_TEST_KEY and MOSH_SERVER");
        return;
    };
    let ssh_port: u16 = ssh_port.parse().unwrap();
    let key = std::fs::read_to_string(&key_path).expect("read test key");
    let a = wg_generate_keypair();
    let b = wg_generate_keypair();

    // The far side, on its own runtime so it behaves like another machine.
    let rt = tokio::runtime::Runtime::new().unwrap();
    let server_cfg = format!(
        "[Interface]\nPrivateKey = {}\nAddress = 10.77.0.1/24\n[Peer]\nPublicKey = {}\nAllowedIPs = 10.77.0.0/24\nEndpoint = 127.0.0.1:9\n",
        b.private_key, a.public_key
    );
    let server_addr = rt.block_on(async move {
        let server = wg_tunnel::Tunnel::start(wg_tunnel::WgConfig::parse(&server_cfg).unwrap()).await.unwrap();
        let addr = server.local_addr();
        let mut tcp_listener = server.listen(22).await.unwrap();

        // mosh-server takes the lowest free port at or above 60000. Servers left
        // over from earlier runs hold the low ones, so cover a wide range rather
        // than assuming this run gets 60000.
        for port in 60000u16..=60049 {
            let mut inside = server.udp_listen(port).await.unwrap();
            tokio::spawn(async move {
                let outside = match tokio::net::UdpSocket::bind("127.0.0.1:0").await {
                    Ok(s) => s,
                    Err(_) => return,
                };
                if outside.connect(("127.0.0.1", port)).await.is_err() {
                    return;
                }
                let mut client: Option<std::net::SocketAddr> = None;
                let mut buf = vec![0u8; 4096];
                loop {
                    tokio::select! {
                        from_tunnel = inside.recv_from() => match from_tunnel {
                            Some((data, from)) => {
                                client = Some(from);
                                if outside.send(&data).await.is_err() { return; }
                            }
                            None => return,
                        },
                        from_host = outside.recv(&mut buf) => match from_host {
                            Ok(n) => {
                                if let Some(to) = client {
                                    if inside.send_to(buf[..n].to_vec(), to).is_err() { return; }
                                }
                            }
                            Err(_) => return,
                        },
                    }
                }
            });
        }

        tokio::spawn(async move {
            let _keep = server;
            while let Some(mut s) = tcp_listener.accept().await {
                tokio::spawn(async move {
                    if let Ok(mut tcp) = tokio::net::TcpStream::connect(("127.0.0.1", ssh_port)).await {
                        let _ = tokio::io::copy_bidirectional(&mut s, &mut tcp).await;
                    }
                });
            }
        });
        addr
    });

    let client_cfg = format!(
        "[Interface]\nPrivateKey = {}\nAddress = 10.77.0.2/24\n[Peer]\nPublicKey = {}\nAllowedIPs = 10.77.0.0/24\nEndpoint = {}\nPersistentKeepalive = 10\n",
        a.private_key, b.public_key, server_addr
    );
    register_tunnel("mosh-wg".into(), client_cfg).expect("register tunnel");

    let listener = Listener::new();
    let session = Session::new(
        Backend::Mosh {
            config: MoshConfig {
                ssh: SshConfig {
                    host: "10.77.0.1".into(),
                    port: 22,
                    username: std::env::var("USER").unwrap(),
                    auth: vec![AuthMethod::Key { private_key: key, passphrase: None, certificate: String::new() }],
                    keepalive_secs: 10,
                    connect_timeout_secs: 15,
                    proxy: None,
                    jumps: vec![],
                    tunnel_id: Some("mosh-wg".into()),
                    wait_for_host_secs: 0,
                    tailscale_id: None,
                    tunnel_fallback: false,
                    forward_agent: false,
                    agent_keys: vec![],
                    agent_approval: None,
                    env: vec![],
        alternates: vec![],
        vpn_name: String::new(),
    },
                locale: "en_US.UTF-8".into(),
                server,
            },
        },
        80, 24, 1000, listener.clone(), Arc::new(Accept), Arc::new(NoQuestions), Options::default(),
    );
    session.start();

    let start = Instant::now();
    while !matches!(session.state(), SessionState::Connected) {
        assert!(
            start.elapsed() < Duration::from_secs(45),
            "state: {:?}\nprogress: {:?}",
            session.state(),
            listener.progress.lock().unwrap()
        );
        std::thread::sleep(Duration::from_millis(30));
    }

    session.send_text("echo wg-mosh-$((8*8))\n".into());
    wait_for(&session, "wg-mosh-64");

    let progress = listener.progress.lock().unwrap().clone();
    assert!(progress.iter().any(|p| p.contains("Mosh over the tunnel")), "{progress:?}");
    let stats = tunnel_stats("mosh-wg".into());
    assert!(stats.running && stats.tx_bytes > 0, "{stats:?}");
    session.disconnect();
    unregister_tunnel("mosh-wg".into());
}

/// Predictive echo has to show a keystroke before the server has echoed it, and
/// then get out of the way once the real thing lands.
#[test]
fn predictive_echo_shows_a_keystroke_before_the_server_does() {
    let (Ok(port), Ok(key_path), Ok(server)) =
        (std::env::var("SSH_TEST_PORT"), std::env::var("SSH_TEST_KEY"), std::env::var("MOSH_SERVER"))
    else {
        eprintln!("skipping: needs SSH_TEST_PORT, SSH_TEST_KEY and MOSH_SERVER");
        return;
    };
    let key = std::fs::read_to_string(&key_path).expect("read test key");

    let listener = Listener::new();
    let session = Session::new(
        Backend::Mosh {
            config: MoshConfig {
                ssh: SshConfig {
                    host: "127.0.0.1".into(),
                    port: port.parse().unwrap(),
                    username: std::env::var("USER").unwrap(),
                    auth: vec![AuthMethod::Key { private_key: key, passphrase: None, certificate: String::new() }],
                    keepalive_secs: 10,
                    connect_timeout_secs: 10,
                    proxy: None,
                    jumps: vec![],
                    tunnel_id: None,
                    wait_for_host_secs: 0,
                    tailscale_id: None,
                    tunnel_fallback: false,
                    forward_agent: false,
                    agent_keys: vec![],
                    agent_approval: None,
                    env: vec![],
        alternates: vec![],
        vpn_name: String::new(),
    },
                locale: "en_US.UTF-8".into(),
                server,
            },
        },
        80, 24, 1000, listener.clone(), Arc::new(Accept), Arc::new(NoQuestions), Options::default(),
    );
    // Loopback is far too quick for adaptive mode to bother, so force it on.
    session.set_prediction(PredictionMode::Always);
    session.start();

    let start = Instant::now();
    while !matches!(session.state(), SessionState::Connected) {
        assert!(start.elapsed() < Duration::from_secs(30), "state: {:?}", session.state());
        std::thread::sleep(Duration::from_millis(30));
    }
    // Let the shell settle so the cursor is somewhere sensible.
    session.send_text("\n".into());
    std::thread::sleep(Duration::from_millis(800));

    session.send_text("zqx".into());
    let predicted = session.predictions();
    assert_eq!(predicted.len(), 3, "expected three predicted cells, got {predicted:?}");
    assert_eq!(
        predicted.iter().map(|c| char::from_u32(c.codepoint).unwrap()).collect::<String>(),
        "zqx",
    );
    // They should sit on one row, at consecutive columns.
    assert!(predicted.windows(2).all(|w| w[0].row == w[1].row && w[1].col == w[0].col + 1), "{predicted:?}");

    // Once the server's echo arrives the guesses retire, and the characters are
    // on the real screen rather than in the overlay.
    wait_for(&session, "zqx");
    let start = Instant::now();
    while !session.predictions().is_empty() {
        assert!(start.elapsed() < Duration::from_secs(5), "predictions never retired: {:?}", session.predictions());
        std::thread::sleep(Duration::from_millis(30));
    }
    session.disconnect();
}

#[test]
fn prediction_is_off_for_an_ordinary_ssh_session() {
    let Ok(port) = std::env::var("SSH_TEST_PORT") else { return };
    let key = std::fs::read_to_string(std::env::var("SSH_TEST_KEY").unwrap()).unwrap();
    let listener = Listener::new();
    let session = Session::new(
        Backend::Ssh {
            config: SshConfig {
                host: "127.0.0.1".into(),
                port: port.parse().unwrap(),
                username: std::env::var("USER").unwrap(),
                auth: vec![AuthMethod::Key { private_key: key, passphrase: None, certificate: String::new() }],
                keepalive_secs: 10,
                connect_timeout_secs: 10,
                proxy: None,
                jumps: vec![],
                tunnel_id: None,
                wait_for_host_secs: 0,
                tailscale_id: None,
                tunnel_fallback: false,
                forward_agent: false,
                agent_keys: vec![],
                agent_approval: None,
                env: vec![],
        alternates: vec![],
        vpn_name: String::new(),
    },
        },
        80, 24, 1000, listener.clone(), Arc::new(Accept), Arc::new(NoQuestions), Options::default(),
    );
    session.set_prediction(PredictionMode::Always);
    session.start();
    let start = Instant::now();
    while !matches!(session.state(), SessionState::Connected) {
        assert!(start.elapsed() < Duration::from_secs(10), "state: {:?}", session.state());
        std::thread::sleep(Duration::from_millis(30));
    }
    session.send_text("abc".into());
    std::thread::sleep(Duration::from_millis(200));
    assert!(session.predictions().is_empty(), "SSH echoes for itself; nothing to guess");
    session.disconnect();
}

/// Mosh through a jump host: SSH cannot forward UDP, so the last hop runs a
/// datagram relay. Both hops here are the same test sshd, which is enough to
/// prove the chain, the relay and the session all fit together.
#[test]
fn mosh_session_through_a_jump_host_relay() {
    let (Ok(port), Ok(key_path), Ok(server)) =
        (std::env::var("SSH_TEST_PORT"), std::env::var("SSH_TEST_KEY"), std::env::var("MOSH_SERVER"))
    else {
        eprintln!("skipping: needs SSH_TEST_PORT, SSH_TEST_KEY and MOSH_SERVER");
        return;
    };
    let port: u16 = port.parse().unwrap();
    let key = std::fs::read_to_string(&key_path).expect("read test key");
    let user = std::env::var("USER").unwrap();
    let auth = vec![AuthMethod::Key { private_key: key, passphrase: None, certificate: String::new() }];

    let listener = Listener::new();
    let session = Session::new(
        Backend::Mosh {
            config: MoshConfig {
                ssh: SshConfig {
                    host: "127.0.0.1".into(),
                    port,
                    username: user.clone(),
                    auth: auth.clone(),
                    keepalive_secs: 10,
                    connect_timeout_secs: 15,
                    proxy: None,
                    jumps: vec![JumpHop {
                        host: "127.0.0.1".into(),
                        port,
                        username: user,
                        auth,
                        pre_command: None,
                        wait_for_next_secs: 0,
                        proxy: None,
                        tunnel_id: None,
                        tailscale_id: None,
                        forward_agent: false,
                    }],
                    tunnel_id: None,
                    wait_for_host_secs: 0,
                    tailscale_id: None,
                    tunnel_fallback: false,
                    forward_agent: false,
                    agent_keys: vec![],
                    agent_approval: None,
                    env: vec![],
        alternates: vec![],
        vpn_name: String::new(),
    },
                locale: "en_US.UTF-8".into(),
                server,
            },
        },
        80, 24, 1000, listener.clone(), Arc::new(Accept), Arc::new(NoQuestions), Options::default(),
    );
    session.start();

    let start = Instant::now();
    while !matches!(session.state(), SessionState::Connected) {
        assert!(
            start.elapsed() < Duration::from_secs(45),
            "state: {:?}\nprogress: {:?}",
            session.state(),
            listener.progress.lock().unwrap()
        );
        std::thread::sleep(Duration::from_millis(30));
    }

    session.send_text("echo relayed-$((5*5))\n".into());
    wait_for(&session, "relayed-25");

    let progress = listener.progress.lock().unwrap().clone();
    assert!(progress.iter().any(|p| p.contains("Starting a UDP relay")), "{progress:?}");
    assert!(progress.iter().any(|p| p.contains("Mosh through the relay")), "{progress:?}");
    session.disconnect();
}

/// A SOCKS5 proxy that speaks UDP ASSOCIATE, just enough of RFC 1928 to relay
/// datagrams. Returns the port to point a client at.
fn fake_socks5_udp_proxy() -> u16 {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let (tx, rx) = std::sync::mpsc::channel();
    std::thread::spawn(move || {
        let rt = tokio::runtime::Runtime::new().unwrap();
        rt.block_on(async move {
            let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            tx.send(listener.local_addr().unwrap().port()).unwrap();
            loop {
                let Ok((mut tcp, _)) = listener.accept().await else { return };
                tokio::spawn(async move {
                    // Greeting: accept "no authentication".
                    let mut head = [0u8; 2];
                    if tcp.read_exact(&mut head).await.is_err() {
                        return;
                    }
                    let mut methods = vec![0u8; head[1] as usize];
                    let _ = tcp.read_exact(&mut methods).await;
                    let _ = tcp.write_all(&[0x05, 0x00]).await;

                    // Request: only UDP ASSOCIATE is answered.
                    let mut req = [0u8; 4];
                    if tcp.read_exact(&mut req).await.is_err() {
                        return;
                    }
                    let skip = match req[3] {
                        0x01 => 6,
                        0x04 => 18,
                        _ => {
                            let mut l = [0u8; 1];
                            let _ = tcp.read_exact(&mut l).await;
                            l[0] as usize + 2
                        }
                    };
                    let mut rest = vec![0u8; skip];
                    let _ = tcp.read_exact(&mut rest).await;
                    // CONNECT: the SSH hop uses it, so a proxy has to do both.
                    if req[1] == 0x01 {
                        let target = match req[3] {
                            0x01 => format!(
                                "{}.{}.{}.{}:{}",
                                rest[0], rest[1], rest[2], rest[3],
                                u16::from_be_bytes([rest[4], rest[5]])
                            ),
                            0x03 => {
                                let n = rest.len();
                                format!(
                                    "{}:{}",
                                    String::from_utf8_lossy(&rest[..n - 2]),
                                    u16::from_be_bytes([rest[n - 2], rest[n - 1]])
                                )
                            }
                            _ => return,
                        };
                        match tokio::net::TcpStream::connect(&target).await {
                            Ok(mut upstream) => {
                                let _ = tcp.write_all(&[0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await;
                                let _ = tokio::io::copy_bidirectional(&mut tcp, &mut upstream).await;
                            }
                            Err(_) => {
                                let _ = tcp.write_all(&[0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await;
                            }
                        }
                        return;
                    }
                    if req[1] != 0x03 {
                        let _ = tcp.write_all(&[0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await;
                        return;
                    }

                    let relay = tokio::net::UdpSocket::bind("127.0.0.1:0").await.unwrap();
                    let relay_port = relay.local_addr().unwrap().port();
                    let mut reply = vec![0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1];
                    reply.extend_from_slice(&relay_port.to_be_bytes());
                    let _ = tcp.write_all(&reply).await;

                    // Relay until the control connection goes away.
                    let out = tokio::net::UdpSocket::bind("127.0.0.1:0").await.unwrap();
                    let mut client: Option<std::net::SocketAddr> = None;
                    let mut target: Option<std::net::SocketAddr> = None;
                    let mut a = vec![0u8; 65535];
                    let mut b = vec![0u8; 65535];
                    let mut sink = [0u8; 1];
                    loop {
                        tokio::select! {
                            // The client's association ends with its control connection.
                            r = tcp.read(&mut sink) => if matches!(r, Ok(0) | Err(_)) { return; },
                            r = relay.recv_from(&mut a) => {
                                let Ok((n, from)) = r else { return };
                                client = Some(from);
                                // Header: RSV RSV FRAG ATYP ADDR PORT, then payload.
                                if n < 10 || a[2] != 0 { continue; }
                                let (dst, off) = match a[3] {
                                    0x01 => (
                                        std::net::SocketAddr::from((
                                            [a[4], a[5], a[6], a[7]],
                                            u16::from_be_bytes([a[8], a[9]]),
                                        )),
                                        10,
                                    ),
                                    _ => continue,
                                };
                                target = Some(dst);
                                let _ = out.send_to(&a[off..n], dst).await;
                            }
                            r = out.recv_from(&mut b) => {
                                let Ok((n, _)) = r else { return };
                                let (Some(c), Some(t)) = (client, target) else { continue };
                                let mut wrapped = vec![0x00, 0x00, 0x00, 0x01];
                                match t.ip() {
                                    std::net::IpAddr::V4(v4) => wrapped.extend_from_slice(&v4.octets()),
                                    std::net::IpAddr::V6(_) => continue,
                                }
                                wrapped.extend_from_slice(&t.port().to_be_bytes());
                                wrapped.extend_from_slice(&b[..n]);
                                let _ = relay.send_to(&wrapped, c).await;
                            }
                        }
                    }
                });
            }
        });
    });
    rx.recv().unwrap()
}

/// Mosh over a SOCKS5 proxy that relays UDP. HTTP CONNECT cannot do this at all,
/// and plenty of SOCKS5 proxies decline it, so the path is worth pinning down.
#[test]
fn mosh_session_through_a_socks5_udp_proxy() {
    let (Ok(port), Ok(key_path), Ok(server)) =
        (std::env::var("SSH_TEST_PORT"), std::env::var("SSH_TEST_KEY"), std::env::var("MOSH_SERVER"))
    else {
        eprintln!("skipping: needs SSH_TEST_PORT, SSH_TEST_KEY and MOSH_SERVER");
        return;
    };
    let key = std::fs::read_to_string(&key_path).expect("read test key");
    let proxy_port = fake_socks5_udp_proxy();

    let listener = Listener::new();
    let session = Session::new(
        Backend::Mosh {
            config: MoshConfig {
                ssh: SshConfig {
                    host: "127.0.0.1".into(),
                    port: port.parse().unwrap(),
                    username: std::env::var("USER").unwrap(),
                    auth: vec![AuthMethod::Key { private_key: key, passphrase: None, certificate: String::new() }],
                    keepalive_secs: 10,
                    connect_timeout_secs: 15,
                    // The SSH hop goes direct here; the proxy is exercised for the
                    // datagrams, which is the part that needs UDP ASSOCIATE.
                    proxy: Some(ProxyConfig {
                        kind: ProxyKind::Socks5,
                        host: "127.0.0.1".into(),
                        port: proxy_port,
                        username: None,
                        password: None,
                    }),
                    jumps: vec![],
                    tunnel_id: None,
                    wait_for_host_secs: 0,
                    tailscale_id: None,
                    tunnel_fallback: false,
                    forward_agent: false,
                    agent_keys: vec![],
                    agent_approval: None,
                    env: vec![],
        alternates: vec![],
        vpn_name: String::new(),
    },
                locale: "en_US.UTF-8".into(),
                server,
            },
        },
        80, 24, 1000, listener.clone(), Arc::new(Accept), Arc::new(NoQuestions), Options::default(),
    );
    session.start();

    let start = Instant::now();
    while !matches!(session.state(), SessionState::Connected) {
        assert!(
            start.elapsed() < Duration::from_secs(45),
            "state: {:?}\nprogress: {:?}",
            session.state(),
            listener.progress.lock().unwrap()
        );
        std::thread::sleep(Duration::from_millis(30));
    }

    session.send_text("echo proxied-$((9*9))\n".into());
    wait_for(&session, "proxied-81");

    let progress = listener.progress.lock().unwrap().clone();
    assert!(progress.iter().any(|p| p.contains("relay UDP")), "{progress:?}");
    assert!(progress.iter().any(|p| p.contains("through the proxy relay")), "{progress:?}");
    session.disconnect();
}

/// Mosh over Tailscale rides on tsnet's loopback SOCKS5 proxy, so what actually
/// has to interoperate is *tailscale's own* SOCKS5 implementation, not our test
/// double. `TSOCKS_BIN` points at a helper that runs `tailscale.com/net/socks5`
/// with the same username/password the loopback proxy uses.
#[test]
fn mosh_session_through_tailscales_own_socks5_server() {
    let (Ok(port), Ok(key_path), Ok(server), Ok(tsocks)) = (
        std::env::var("SSH_TEST_PORT"),
        std::env::var("SSH_TEST_KEY"),
        std::env::var("MOSH_SERVER"),
        std::env::var("TSOCKS_BIN"),
    ) else {
        eprintln!("skipping: needs SSH_TEST_PORT, SSH_TEST_KEY, MOSH_SERVER and TSOCKS_BIN");
        return;
    };
    assert!(std::path::Path::new(&tsocks).is_file(), "TSOCKS_BIN={tsocks} is not a file");
    let key = std::fs::read_to_string(&key_path).expect("read test key");

    // The proxy credential tsnet generates is 32 characters; mimic that.
    let cred = "0123456789abcdef0123456789abcdef".to_string();
    let mut child = std::process::Command::new(&tsocks)
        .arg(&cred)
        .stdout(std::process::Stdio::piped())
        .spawn()
        .expect("spawn tailscale socks5 helper");
    let stdout = child.stdout.take().expect("piped");
    let proxy_port = {
        use std::io::{BufRead, BufReader};
        let mut port = None;
        for line in BufReader::new(stdout).lines().map_while(Result::ok) {
            if let Some(rest) = line.strip_prefix("SOCKS5 ") {
                port = rest.trim().parse::<u16>().ok();
                break;
            }
        }
        port.expect("helper did not report its port")
    };
    struct Kill(std::process::Child);
    impl Drop for Kill {
        fn drop(&mut self) {
            let _ = self.0.kill();
            let _ = self.0.wait();
        }
    }
    let _guard = Kill(child);

    let listener = Listener::new();
    let session = Session::new(
        Backend::Mosh {
            config: MoshConfig {
                ssh: SshConfig {
                    host: "127.0.0.1".into(),
                    port: port.parse().unwrap(),
                    username: std::env::var("USER").unwrap(),
                    auth: vec![AuthMethod::Key { private_key: key, passphrase: None, certificate: String::new() }],
                    keepalive_secs: 10,
                    connect_timeout_secs: 15,
                    proxy: Some(ProxyConfig {
                        kind: ProxyKind::Socks5,
                        host: "127.0.0.1".into(),
                        port: proxy_port,
                        // Exactly what tsnet's loopback proxy expects.
                        username: Some("tsnet".into()),
                        password: Some(cred),
                    }),
                    jumps: vec![],
                    tunnel_id: None,
                    wait_for_host_secs: 0,
                    tailscale_id: None,
                    tunnel_fallback: false,
                    forward_agent: false,
                    agent_keys: vec![],
                    agent_approval: None,
                    env: vec![],
        alternates: vec![],
        vpn_name: String::new(),
    },
                locale: "en_US.UTF-8".into(),
                server,
            },
        },
        80, 24, 1000, listener.clone(), Arc::new(Accept), Arc::new(NoQuestions), Options::default(),
    );
    session.start();

    let start = Instant::now();
    while !matches!(session.state(), SessionState::Connected) {
        assert!(
            start.elapsed() < Duration::from_secs(45),
            "state: {:?}\nprogress: {:?}",
            session.state(),
            listener.progress.lock().unwrap()
        );
        std::thread::sleep(Duration::from_millis(30));
    }
    session.send_text("echo tailscale-socks-$((7*7))\n".into());
    wait_for(&session, "tailscale-socks-49");
    session.disconnect();
}

/// A certificate is checked when it is attached, not when a connection fails,
/// so this is the layer the app leans on for that.
#[test]
fn inspect_certificate_reports_and_refuses() {
    use russh::keys::ssh_key::certificate::{Builder, CertType};
    use russh::keys::ssh_key::{Algorithm, PrivateKey};

    let mut rng = russh::keys::key::safe_rng();
    let ca = PrivateKey::random(&mut rng, Algorithm::Ed25519).unwrap();
    let mine = PrivateKey::random(&mut rng, Algorithm::Ed25519).unwrap();
    let someone_else = PrivateKey::random(&mut rng, Algorithm::Ed25519).unwrap();
    let mut builder = Builder::new_with_random_nonce(&mut rng, mine.public_key(), 0, u64::MAX).unwrap();
    builder.cert_type(CertType::User).unwrap();
    builder.key_id("phone").unwrap();
    builder.valid_principal("deploy").unwrap();
    let text = builder.sign(&ca).unwrap().to_openssh().unwrap();
    let public = mine.public_key().to_openssh().unwrap();

    let info = inspect_certificate(text.clone(), Some(public)).unwrap();
    assert_eq!(info.principals, vec!["deploy".to_string()]);
    assert_eq!(info.key_id, "phone");
    assert!(matches!(info.validity, CertValidity::Current));

    let wrong = inspect_certificate(text, Some(someone_else.public_key().to_openssh().unwrap()));
    assert!(matches!(wrong, Err(CoreError::InvalidKey(_))), "{wrong:?}");
    assert!(inspect_certificate("not a certificate".into(), None).is_err());
}

/// The image path end to end, without needing a host: a device that "prints" a
/// kitty graphics command, and a view that asks where the picture went.
#[test]
fn an_image_reaches_the_view_through_the_session() {
    let device = Arc::new(Device { written: Mutex::new(vec![]), sizes: Mutex::new(vec![]), closed: AtomicUsize::new(0) });
    let listener = Listener::new();
    let session = Session::new(
        Backend::External { config: ExternalConfig { label: "test".into() } },
        40, 10, 200, listener.clone(), Arc::new(Accept), Arc::new(NoQuestions), Options::default(),
    );
    session.set_external_sink(device.clone());
    session.set_cell_size(10, 20);
    session.start();

    // A 20x40 pixel image of solid magenta, which is two cells by two.
    let pixels: Vec<u8> = std::iter::repeat([255u8, 0, 255, 255]).take(20 * 40).flatten().collect();
    let mut out = b"before\r\n".to_vec();
    out.extend_from_slice(
        format!("\x1b_Ga=T,f=32,s=20,v=40,i=7;{}\x1b\\", base64_for_test(&pixels)).as_bytes(),
    );
    session.push_output(out);

    let start = Instant::now();
    while listener.images.load(Ordering::Relaxed) == 0 {
        assert!(start.elapsed() < Duration::from_secs(5), "the view was never told about the image");
        std::thread::sleep(Duration::from_millis(20));
    }

    let images = session.images();
    assert_eq!(images.len(), 1);
    let p = images[0];
    assert_eq!((p.id, p.generation), (7, 0));
    assert_eq!((p.col, p.row), (0, 1));
    assert_eq!((p.cols, p.rows), (2, 2));
    assert_eq!((p.width, p.height), (20, 40));

    let bytes = session.image_bytes(p.id, p.generation);
    assert_eq!(bytes.len(), 20 * 40 * 4);
    assert_eq!(&bytes[..4], &[255, 0, 255, 255]);
    // A generation nobody has is not an image.
    assert!(session.image_bytes(7, 1).is_empty());

    // The terminal answered the program, and the covered cells read as spaces.
    let start = Instant::now();
    while device.written.lock().unwrap().is_empty() {
        assert!(start.elapsed() < Duration::from_secs(5), "no reply was sent");
        std::thread::sleep(Duration::from_millis(20));
    }
    assert_eq!(device.written.lock().unwrap().clone(), b"\x1b_Gi=7;OK\x1b\\".to_vec());
    assert!(screen_text(&session).starts_with("before"));
}

/// Standard base64, spelled out here so the test does not need a dependency.
fn base64_for_test(data: &[u8]) -> String {
    const ALPHABET: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::new();
    for chunk in data.chunks(3) {
        let b = [chunk[0], *chunk.get(1).unwrap_or(&0), *chunk.get(2).unwrap_or(&0)];
        let n = ((b[0] as u32) << 16) | ((b[1] as u32) << 8) | b[2] as u32;
        for i in 0..4 {
            if i <= chunk.len() {
                out.push(ALPHABET[((n >> (18 - 6 * i)) & 0x3f) as usize] as char);
            } else {
                out.push('=');
            }
        }
    }
    out
}
