//! Interop harness: drive this client against the stock `mosh-server`.
//!
//! Unit tests can only prove we agree with ourselves, so this runs the real
//! thing. It needs a `mosh-server` binary; set `MOSH_SERVER` to one, or have it
//! on `PATH`. Without it every test here skips rather than fails, so the suite
//! stays green on a machine that has no mosh installed.
//!
//! ```text
//! MOSH_SERVER=/usr/bin/mosh-server cargo test -p mosh --test server
//! ```

use std::io::{BufRead, BufReader};
use std::net::UdpSocket;
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

use mosh::transport::{Transport, TransportEvent};
use mosh::{Base64Key, Session, UserEvent};

/// A `mosh-server` running a command, and the details needed to talk to it.
struct Server {
    port: u16,
    key: String,
    /// The daemon's pid. `mosh-server` forks, so the process we spawned has
    /// already exited by the time we connect and killing it achieves nothing.
    daemon: Option<u32>,
}

impl Drop for Server {
    fn drop(&mut self) {
        if let Some(pid) = self.daemon {
            let _ = Command::new("kill").arg(pid.to_string()).status();
        }
    }
}

fn server_binary() -> Option<String> {
    if let Ok(p) = std::env::var("MOSH_SERVER") {
        // Asking for a specific binary and not getting it is a broken setup,
        // not a reason to quietly skip and report green.
        assert!(std::path::Path::new(&p).is_file(), "MOSH_SERVER={p} is not a file");
        return Some(p);
    }
    let out = Command::new("sh").arg("-c").arg("command -v mosh-server").output().ok()?;
    let path = String::from_utf8_lossy(&out.stdout).trim().to_string();
    (!path.is_empty()).then_some(path)
}

/// Start `mosh-server` running `command` and parse its `MOSH CONNECT` line.
///
/// Both pipes are drained on background threads for the life of the test: the
/// server writes its banner *after* the connect line, and if our end of the
/// pipe has gone away it takes a SIGPIPE and dies before we ever reach it.
fn start(command: &[&str]) -> Option<Server> {
    let binary = server_binary()?;
    let mut child = Command::new(binary)
        .arg("new")
        .args(["-i", "127.0.0.1"])
        .args(["-c", "256"])
        .arg("-v")
        .args(["-l", "LANG=en_US.UTF-8"])
        .arg("--")
        .args(command)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .env("TERM", "xterm-256color")
        .spawn()
        .expect("failed to spawn mosh-server");

    let stdout = child.stdout.take().expect("piped");
    let stderr = child.stderr.take().expect("piped");
    let (tx, rx) = std::sync::mpsc::channel();
    let connect_tx = tx.clone();
    std::thread::spawn(move || {
        for line in BufReader::new(stdout).lines().map_while(Result::ok) {
            if let Some(rest) = line.strip_prefix("MOSH CONNECT ") {
                let mut parts = rest.split_whitespace();
                let port = parts.next().and_then(|p| p.parse::<u16>().ok());
                let key = parts.next().map(str::to_string);
                if let (Some(port), Some(key)) = (port, key) {
                    let _ = connect_tx.send(Line::Connect(port, key));
                }
            }
        }
    });
    std::thread::spawn(move || {
        for line in BufReader::new(stderr).lines().map_while(Result::ok) {
            // "[mosh-server detached, pid = 12345]"
            if let Some(rest) = line.split("pid = ").nth(1) {
                if let Ok(pid) = rest.trim_end_matches(']').trim().parse::<u32>() {
                    let _ = tx.send(Line::Daemon(pid));
                }
            }
        }
    });

    // The parent exits as soon as it has forked; reap it so it is not a zombie.
    let _ = child.wait();

    let (mut port, mut key, mut daemon) = (None, None, None);
    let deadline = Instant::now() + Duration::from_secs(10);
    while (port.is_none() || daemon.is_none()) && Instant::now() < deadline {
        match rx.recv_timeout(Duration::from_millis(200)) {
            Ok(Line::Connect(p, k)) => {
                port = Some(p);
                key = Some(k);
            }
            Ok(Line::Daemon(pid)) => daemon = Some(pid),
            Err(_) => {}
        }
    }
    match (port, key) {
        (Some(port), Some(key)) => Some(Server { port, key, daemon }),
        _ => panic!("mosh-server did not print a MOSH CONNECT line"),
    }
}

enum Line {
    Connect(u16, String),
    Daemon(u32),
}

/// A client bound to a real UDP socket, driven by wall-clock time.
struct Client {
    socket: UdpSocket,
    transport: Transport,
    start: Instant,
}

impl Client {
    fn connect(server: &Server) -> Self {
        let socket = UdpSocket::bind("127.0.0.1:0").expect("bind");
        socket.connect(("127.0.0.1", server.port)).expect("connect");
        socket.set_read_timeout(Some(Duration::from_millis(20))).expect("timeout");
        let key = Base64Key::parse(&server.key).expect("server key should parse");
        Self { socket, transport: Transport::new(Session::new(&key), 80, 24), start: Instant::now() }
    }

    fn now(&self) -> u64 {
        self.start.elapsed().as_millis() as u64
    }

    /// Send anything due, then drain whatever has arrived.
    fn pump(&mut self) -> Vec<TransportEvent> {
        for datagram in self.transport.tick(self.now()).expect("tick") {
            let _ = self.socket.send(&datagram);
        }
        let mut events = Vec::new();
        let mut buf = [0u8; 4096];
        while let Ok(n) = self.socket.recv(&mut buf) {
            match self.transport.receive(&buf[..n], self.now()) {
                Ok(mut e) => events.append(&mut e),
                // A stray or replayed datagram must not kill the session.
                Err(e) => panic!("server sent something we could not read: {e}"),
            }
        }
        events
    }

    /// Pump until `want` is satisfied by the accumulated output, or time runs out.
    fn wait_for_output(&mut self, needle: &str, timeout: Duration) -> String {
        let deadline = Instant::now() + timeout;
        let mut screen = String::new();
        while Instant::now() < deadline {
            for event in self.pump() {
                if let TransportEvent::Output(bytes) = event {
                    screen.push_str(&String::from_utf8_lossy(&bytes));
                }
            }
            if screen.contains(needle) {
                return screen;
            }
            std::thread::sleep(Duration::from_millis(5));
        }
        panic!("timed out waiting for {needle:?}; got:\n{screen}");
    }
}

macro_rules! require_server {
    ($server:expr) => {
        match $server {
            Some(s) => s,
            None => {
                eprintln!("skipping: no mosh-server (set MOSH_SERVER=/path/to/mosh-server)");
                return;
            }
        }
    };
}

#[test]
fn connects_and_receives_the_first_screen() {
    let server = require_server!(start(&["sh", "-c", "echo MOSH_HARNESS_READY; sleep 30"]));
    let mut client = Client::connect(&server);
    let screen = client.wait_for_output("MOSH_HARNESS_READY", Duration::from_secs(10));
    assert!(screen.contains("MOSH_HARNESS_READY"), "screen was:\n{screen}");
    assert!(client.transport.received_state() > 0, "we should have applied at least one state");
}

#[test]
fn keystrokes_reach_the_command_and_come_back() {
    let server = require_server!(start(&["sh", "-c", "echo READY; cat"]));
    let mut client = Client::connect(&server);
    client.wait_for_output("READY", Duration::from_secs(10));

    // `cat` echoes the line back, and the pty echoes the typing itself.
    let now = client.now();
    client.transport.push(UserEvent::Keystroke(b"round-trip\r".to_vec()), now);
    let screen = client.wait_for_output("round-trip", Duration::from_secs(10));
    assert!(screen.contains("round-trip"), "screen was:\n{screen}");
}

#[test]
fn the_server_acknowledges_our_input() {
    let server = require_server!(start(&["sh", "-c", "echo READY; cat"]));
    let mut client = Client::connect(&server);
    client.wait_for_output("READY", Duration::from_secs(10));

    let now = client.now();
    client.transport.push(UserEvent::Keystroke(b"x".to_vec()), now);
    let deadline = Instant::now() + Duration::from_secs(10);
    while Instant::now() < deadline {
        client.pump();
        if client.transport.all_acked() {
            return;
        }
        std::thread::sleep(Duration::from_millis(5));
    }
    panic!("the server never acknowledged our input");
}

#[test]
fn a_resize_is_accepted() {
    // `tput cols` reports what the server thinks the window is.
    let server = require_server!(start(&["sh", "-c", "echo READY; sleep 1; stty size; sleep 30"]));
    let mut client = Client::connect(&server);
    client.wait_for_output("READY", Duration::from_secs(10));

    let now = client.now();
    client.transport.push(UserEvent::Resize { width: 100, height: 40 }, now);
    // 40 rows and 100 columns, as `stty size` prints it.
    let screen = client.wait_for_output("40 100", Duration::from_secs(10));
    assert!(screen.contains("40 100"), "resize did not take effect; screen:\n{screen}");
}

#[test]
fn a_burst_of_output_survives_fragmentation() {
    // 2000 numbered lines is far more than one datagram can carry.
    let server = require_server!(start(&["sh", "-c", "echo READY; sleep 1; seq 1 2000; sleep 30"]));
    let mut client = Client::connect(&server);
    client.wait_for_output("READY", Duration::from_secs(10));
    // The screen only holds the tail, so look for the last line.
    let screen = client.wait_for_output("2000", Duration::from_secs(15));
    assert!(screen.contains("2000"), "screen was:\n{screen}");
}

#[test]
fn the_session_survives_a_source_port_change() {
    let server = require_server!(start(&["sh", "-c", "echo READY; cat"]));
    let mut client = Client::connect(&server);
    client.wait_for_output("READY", Duration::from_secs(10));

    // Roaming is exactly this: the same session seen from a new address.
    let moved = UdpSocket::bind("127.0.0.1:0").expect("rebind");
    moved.connect(("127.0.0.1", server.port)).expect("connect");
    moved.set_read_timeout(Some(Duration::from_millis(20))).expect("timeout");
    client.socket = moved;

    let now = client.now();
    client.transport.push(UserEvent::Keystroke(b"after-roam\r".to_vec()), now);
    let screen = client.wait_for_output("after-roam", Duration::from_secs(10));
    assert!(screen.contains("after-roam"), "screen was:\n{screen}");
}
