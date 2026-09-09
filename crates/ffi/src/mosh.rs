//! Driving a [`mosh::Transport`] as a session transport.
//!
//! The socket and the state machine live in one task; the session talks to it
//! through channels, so the rest of the app sees an ordinary byte pipe. Mosh
//! carries the terminal as an escape-sequence stream, so what comes out of here
//! goes to the emulator exactly like SSH output does.

use std::sync::Arc;
use std::time::{Duration, Instant};

use bytes::Bytes;
use mosh::transport::{Transport, TransportEvent};
use mosh::{Base64Key, Session as MoshSession, UserEvent};
use tokio::net::UdpSocket;
use tokio::sync::mpsc::{unbounded_channel, UnboundedReceiver, UnboundedSender};

use crate::runtime::RUNTIME;
use crate::transport::Event;
use crate::CoreError;

/// What `mosh-server new` printed, once we found the line that matters.
#[derive(Debug)]
pub struct Bootstrap {
    pub port: u16,
    pub key: String,
}

/// Pull `MOSH CONNECT <port> <key>` out of the server's output.
pub fn parse_connect(output: &str) -> Result<Bootstrap, CoreError> {
    for line in output.lines() {
        if let Some(rest) = line.trim().strip_prefix("MOSH CONNECT ") {
            let mut parts = rest.split_whitespace();
            if let (Some(port), Some(key)) = (parts.next(), parts.next()) {
                if let Ok(port) = port.parse::<u16>() {
                    return Ok(Bootstrap { port, key: key.to_string() });
                }
            }
        }
    }
    // Most failures are the server missing or refusing; pass its words along.
    let detail = output.trim().lines().next_back().unwrap_or("no output").trim();
    Err(CoreError::Other(if detail.is_empty() {
        "mosh-server did not start".into()
    } else {
        format!("mosh-server did not start: {detail}")
    }))
}

/// The shell snippet that starts the server and prints its connect line.
///
/// `mosh-server` daemonises but the daemon keeps stdout open, so reading the
/// exec channel to EOF would block until the session ended. Sending its output
/// to a file instead lets the parent exit at once and we simply read the file.
pub fn start_command(server: &str, locale: &str, env: &[(String, String)]) -> String {
    // Everything goes inside single quotes, so a stray quote would end the string.
    let locale = locale.replace('\'', "");
    let server = server.trim().replace('\'', "");
    // mosh-server sets these in the session it spawns, so unlike SSH there is no
    // AcceptEnv to get past: what is asked for is what the shell gets. Names are
    // restricted to what a variable name can be, which also keeps them safe to
    // interpolate.
    let extra: String = env
        .iter()
        .filter(|(name, _)| !name.is_empty() && name.chars().all(|c| c.is_ascii_alphanumeric() || c == '_') && !name.chars().next().unwrap().is_ascii_digit())
        .map(|(name, value)| format!(" -l {name}='{}'", value.replace('\'', "")))
        .collect();
    // An explicit path is used as given; otherwise look on PATH and then in the
    // places that keep mosh off it — Homebrew, Termux, a local prefix.
    let pick = if server.is_empty() {
        "S=mosh-server; command -v \"$S\" >/dev/null 2>&1 || \
         for c in /usr/bin/mosh-server /usr/local/bin/mosh-server /opt/homebrew/bin/mosh-server \
                  /opt/local/bin/mosh-server \"$HOME/.local/bin/mosh-server\" \"$HOME/bin/mosh-server\" \
                  /data/data/com.termux/files/usr/bin/mosh-server; do \
             [ -x \"$c\" ] && S=\"$c\" && break; \
         done"
            .to_string()
    } else {
        format!("S='{server}'")
    };
    format!(
        "{pick}; f=/tmp/.flintterm-mosh.$$; \
         \"$S\" new -s -c 256 -l LANG='{locale}'{extra} >\"$f\" 2>&1 </dev/null; \
         cat \"$f\"; rm -f \"$f\""
    )
}

/// Where the datagrams actually go: straight out of the phone, or inside a
/// WireGuard tunnel. Mosh needs datagram semantics either way, which is why the
/// tunnel case uses the tunnel's UDP socket rather than a TCP forward.
enum Link {
    Direct(UdpSocket),
    Tunnel(wg_tunnel::VirtualDatagram),
    /// Through a SOCKS5 proxy's UDP relay. Every datagram carries a SOCKS5
    /// header naming the real destination, and the association only lives as
    /// long as the TCP control connection is held, so it is kept here.
    Socks5 {
        socket: UdpSocket,
        host: String,
        port: u16,
        _control: tokio::net::TcpStream,
    },
}

impl Link {
    async fn send(&self, data: &[u8]) -> Result<(), String> {
        match self {
            Link::Direct(s) => s.send(data).await.map(|_| ()).map_err(|e| e.to_string()),
            Link::Tunnel(t) => t.send(data.to_vec()).map_err(|e| e.to_string()),
            Link::Socks5 { socket, host, port, .. } => {
                let wrapped = ssh_core::proxy::udp_encapsulate(host, *port, data).map_err(|e| e.to_string())?;
                socket.send(&wrapped).await.map(|_| ()).map_err(|e| e.to_string())
            }
        }
    }

    async fn recv(&mut self, buf: &mut [u8]) -> Result<Vec<u8>, String> {
        match self {
            Link::Direct(s) => {
                let n = s.recv(buf).await.map_err(|e| e.to_string())?;
                Ok(buf[..n].to_vec())
            }
            Link::Tunnel(t) => t.recv().await.ok_or_else(|| "tunnel closed".to_string()),
            Link::Socks5 { socket, .. } => {
                let n = socket.recv(buf).await.map_err(|e| e.to_string())?;
                ssh_core::proxy::udp_decapsulate(&buf[..n]).map(|p| p.to_vec()).map_err(|e| e.to_string())
            }
        }
    }
}

/// The shell snippet that starts a UDP relay on a jump host.
///
/// Mosh is UDP and SSH forwards only TCP, so a jump host cannot carry a session
/// on its own. What it can do is relay datagrams: this listens on a port of its
/// own, sends whatever arrives to the target's mosh port, and sends the replies
/// back — datagram in, datagram out, so nothing about Mosh's loss behaviour
/// changes. It prints `RELAY <port>`, detaches, and exits by itself once the
/// session has been quiet for a while, so nothing is left running.
/// How long a relay waits with no traffic before exiting on its own.
pub const RELAY_IDLE_SECS: u32 = 120;

pub fn relay_command(target: &str, target_port: u16, idle_secs: u32) -> String {
    let target = target.replace('\'', "");
    // Written as one python -c line: python3 is far more universally present
    // than socat, and this way there is no dependency to install on the hop.
    let script = "import socket,select,sys\n\
        t=(sys.argv[1],int(sys.argv[2]));idle=int(sys.argv[3])\n\
        s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);s.bind(('0.0.0.0',0))\n\
        u=socket.socket(socket.AF_INET,socket.SOCK_DGRAM)\n\
        print('RELAY %d'%s.getsockname()[1],flush=True)\n\
        c=None\n\
        while True:\n\
        \x20r=select.select([s,u],[],[],idle)[0]\n\
        \x20if not r: break\n\
        \x20for k in r:\n\
        \x20\x20d,a=k.recvfrom(65535)\n\
        \x20\x20if k is s:\n\
        \x20\x20\x20c=a;u.sendto(d,t)\n\
        \x20\x20elif c: s.sendto(d,c)\n";
    format!(
        "f=/tmp/.flintterm-relay.$$; \
         command -v python3 >/dev/null 2>&1 || {{ echo 'no python3 on this jump host'; exit 1; }}; \
         nohup python3 -c {script} '{target}' '{target_port}' '{idle_secs}' >\"$f\" 2>&1 </dev/null & \
         for i in 1 2 3 4 5 6 7 8 9 10; do grep -q RELAY \"$f\" 2>/dev/null && break; sleep 0.2; done; \
         cat \"$f\"; rm -f \"$f\"",
        script = shell_quote(script),
    )
}

/// Single-quote a string for a POSIX shell, the safe way: end the quote, insert
/// an escaped quote, start again.
fn shell_quote(s: &str) -> String {
    format!("'{}'", s.replace('\'', "'\\''"))
}

/// Pull `RELAY <port>` out of [`relay_command`]'s output.
pub fn parse_relay(output: &str) -> Result<u16, CoreError> {
    for line in output.lines() {
        if let Some(rest) = line.trim().strip_prefix("RELAY ") {
            if let Ok(port) = rest.trim().parse::<u16>() {
                return Ok(port);
            }
        }
    }
    let detail = output.trim().lines().next_back().unwrap_or("no output").trim();
    Err(CoreError::Other(format!("could not start a UDP relay on the jump host: {detail}")))
}

pub struct MoshReader {
    rx: UnboundedReceiver<Event>,
}

impl MoshReader {
    pub async fn next(&mut self) -> Event {
        self.rx.recv().await.unwrap_or(Event::Closed)
    }
}

#[derive(Clone)]
pub struct MoshWriter {
    tx: UnboundedSender<UserEvent>,
}

impl MoshWriter {
    pub fn write(&self, bytes: Vec<u8>) -> bool {
        self.tx.send(UserEvent::Keystroke(bytes)).is_ok()
    }

    pub fn resize(&self, cols: u16, rows: u16) {
        let _ = self.tx.send(UserEvent::Resize { width: cols as i32, height: rows as i32 });
    }

    pub fn close(&self) {
        // Dropping the sender ends the task, which closes the socket.
        let _ = self.tx.send(UserEvent::Keystroke(Vec::new()));
    }
}

/// Connect to a running `mosh-server` and start pumping it.
pub async fn connect(
    host: &str,
    port: u16,
    boot: Bootstrap,
    cols: u16,
    rows: u16,
    tunnel: Option<Arc<wg_tunnel::Tunnel>>,
    proxy: Option<ssh_core::ProxyConfig>,
    progress: Arc<dyn Fn(String) + Send + Sync>,
    // on_srtt reports the smoothed round-trip time; predictive echo uses it to
    // decide whether guessing is worth it.
    on_srtt: Arc<dyn Fn(f64) + Send + Sync>,
) -> Result<(MoshReader, MoshWriter), CoreError> {
    let key = Base64Key::parse(&boot.key).map_err(|e| CoreError::Other(format!("mosh key: {e}")))?;
    let mut link = match tunnel {
        Some(t) => {
            let ip = t.resolve(host).await.map_err(|e| CoreError::Other(format!("mosh: {host} in the tunnel: {e}")))?;
            progress(format!("Mosh over the tunnel to {ip}:{port}"));
            Link::Tunnel(
                t.udp_connect(ip, port)
                    .await
                    .map_err(|e| CoreError::Other(format!("mosh through the tunnel: {e}")))?,
            )
        }
        None => match proxy {
            // A proxy can only carry datagrams through SOCKS5 UDP ASSOCIATE.
            Some(p) => {
                progress(format!("Asking the SOCKS5 proxy {}:{} to relay UDP", p.host, p.port));
                let assoc = ssh_core::proxy::udp_associate(&p, Duration::from_secs(15))
                    .await
                    .map_err(|e| CoreError::Other(e.to_string()))?;
                let socket = UdpSocket::bind("0.0.0.0:0").await.map_err(|e| CoreError::Other(format!("mosh socket: {e}")))?;
                socket
                    .connect((assoc.relay_host.as_str(), assoc.relay_port))
                    .await
                    .map_err(|e| CoreError::Other(format!("mosh: proxy relay {}:{}: {e}", assoc.relay_host, assoc.relay_port)))?;
                progress(format!("Mosh through the proxy relay {}:{}", assoc.relay_host, assoc.relay_port));
                Link::Socks5 { socket, host: host.to_string(), port, _control: assoc.control }
            }
            None => {
                let socket = UdpSocket::bind("0.0.0.0:0").await.map_err(|e| CoreError::Other(format!("mosh socket: {e}")))?;
                socket
                    .connect((host, port))
                    .await
                    .map_err(|e| CoreError::Other(format!("mosh connect to {host}:{port}: {e}")))?;
                Link::Direct(socket)
            }
        },
    };

    let (out_tx, out_rx) = unbounded_channel();
    let (in_tx, in_rx) = unbounded_channel();
    let mut transport = Transport::new(MoshSession::new(&key), cols, rows);

    // Prove the datagrams actually get there before anyone relies on them.
    //
    // UDP 60000-61000 is blocked more often than people expect — hotel Wi-Fi,
    // corporate egress filters, a NAT that only forwards TCP. mosh-server answers
    // the first packet straight away, so a couple of seconds of silence means the
    // path is not going to work, and the caller can fall back to plain SSH while
    // it still has an SSH connection in hand.
    let start = Instant::now();
    let now = |()| start.elapsed().as_millis() as u64;
    let mut buf = vec![0u8; 4096];
    let mut first: Option<Vec<TransportEvent>> = None;
    let deadline = tokio::time::Instant::now() + Duration::from_millis(PROBE_MS);
    while tokio::time::Instant::now() < deadline && first.is_none() {
        match transport.tick(now(())) {
            Ok(datagrams) => {
                for d in datagrams {
                    link.send(&d).await.map_err(|e| CoreError::Other(format!("mosh: {e}")))?;
                }
            }
            Err(e) => return Err(CoreError::Other(format!("mosh: {e}"))),
        }
        if let Ok(Ok(datagram)) = tokio::time::timeout(Duration::from_millis(PROBE_RETRY_MS), link.recv(&mut buf)).await {
            if let Ok(events) = transport.receive(&datagram, now(())) {
                first = Some(events);
            }
        }
    }
    let Some(events) = first else {
        return Err(CoreError::Other(format!(
            "no reply from mosh-server on UDP {port} after {}s — the port is probably blocked",
            PROBE_MS / 1000
        )));
    };
    // Whatever came back with the probe is the first screen; don't lose it.
    for e in events {
        if let TransportEvent::Output(bytes) = e {
            let _ = out_tx.send(Event::Data(Bytes::from(bytes)));
        }
    }
    RUNTIME.spawn(pump(link, transport, in_rx, out_tx, progress, on_srtt, start));
    Ok((MoshReader { rx: out_rx }, MoshWriter { tx: in_tx }))
}

/// How long to wait for the first datagram back before giving up on UDP.
const PROBE_MS: u64 = 4000;
const PROBE_RETRY_MS: u64 = 400;

async fn pump(
    mut link: Link,
    mut transport: Transport,
    mut input: UnboundedReceiver<UserEvent>,
    output: UnboundedSender<Event>,
    progress: Arc<dyn Fn(String) + Send + Sync>,
    on_srtt: Arc<dyn Fn(f64) + Send + Sync>,
    // The clock the probe already started on: timestamps have to keep running
    // from the same zero, or the server's echo timings make no sense.
    start: Instant,
) {
    let now = |()| start.elapsed().as_millis() as u64;
    let mut buf = vec![0u8; 4096];
    let mut warned_quiet = false;

    loop {
        // Send whatever the state machine says is due.
        match transport.tick(now(())) {
            Ok(datagrams) => {
                for d in datagrams {
                    if let Err(e) = link.send(&d).await {
                        log::warn!("mosh: send: {e}");
                        let _ = output.send(Event::Closed);
                        return;
                    }
                }
            }
            Err(e) => {
                log::warn!("mosh: {e}");
                let _ = output.send(Event::Closed);
                return;
            }
        }

        if transport.timed_out(now(())) {
            progress("Mosh: no reply from the server, giving up".into());
            let _ = output.send(Event::Closed);
            return;
        }
        // Tell the user once when the link goes quiet, the way mosh does.
        match transport.silent_for(now(())) {
            Some(ms) if ms > 3000 && !warned_quiet => {
                warned_quiet = true;
                progress(format!("Mosh: no reply for {}s", ms / 1000));
            }
            Some(ms) if ms < 1000 => warned_quiet = false,
            _ => {}
        }

        let wait = Duration::from_millis(transport.wait_time(now(())).clamp(1, 250));
        tokio::select! {
            received = input.recv() => match received {
                Some(event) => {
                    transport.push(event, now(()));
                    // Take anything else already queued so a burst is one state.
                    while let Ok(more) = input.try_recv() {
                        transport.push(more, now(()));
                    }
                }
                None => {
                    let _ = output.send(Event::Closed);
                    return;
                }
            },
            read = link.recv(&mut buf) => match read {
                Ok(datagram) => match transport.receive(&datagram, now(())) {
                    Ok(events) => {
                        if let Some(srtt) = transport.srtt_ms() {
                            on_srtt(srtt);
                        }
                        let mut ending = false;
                        for e in events {
                            match e {
                                TransportEvent::Output(bytes) => {
                                    if output.send(Event::Data(Bytes::from(bytes))).is_err() {
                                        return;
                                    }
                                }
                                TransportEvent::Shutdown => ending = true,
                                _ => {}
                            }
                        }
                        if ending {
                            // The shell exited. Acknowledge the shutdown so the
                            // server stops retrying and exits now rather than
                            // after its retry window, then end the session — the
                            // alternative is sitting here until the link times
                            // out, long after there is anything to wait for.
                            if let Ok(datagrams) = transport.tick(now(())) {
                                for d in datagrams {
                                    let _ = link.send(&d).await;
                                }
                            }
                            let _ = output.send(Event::Closed);
                            return;
                        }
                    }
                    // A stray datagram is not a reason to end the session.
                    Err(e) => log::debug!("mosh: ignoring datagram: {e}"),
                },
                Err(e) => {
                    log::warn!("mosh: link: {e}");
                    let _ = output.send(Event::Closed);
                    return;
                }
            },
            _ = tokio::time::sleep(wait) => {}
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn finds_the_connect_line() {
        let b = parse_connect("\nMOSH CONNECT 60001 fYh+gZV2PI6C6iMIrsv5rQ\n\nmosh-server (mosh 1.4.0)\n").unwrap();
        assert_eq!(b.port, 60001);
        assert_eq!(b.key, "fYh+gZV2PI6C6iMIrsv5rQ");
    }

    #[test]
    fn reports_what_the_server_said_when_there_is_no_connect_line() {
        let err = parse_connect("sh: mosh-server: command not found").unwrap_err().to_string();
        assert!(err.contains("command not found"), "{err}");
    }

    #[test]
    fn empty_output_is_still_a_readable_error() {
        assert!(parse_connect("").unwrap_err().to_string().contains("did not start"));
    }

    #[test]
    fn the_start_command_cannot_be_broken_by_the_locale() {
        let cmd = start_command("mosh-server", "en_US.UTF-8'; rm -rf /", &[]);
        assert!(!cmd.contains("'; rm"), "{cmd}");
        assert!(cmd.contains("LANG='en_US.UTF-8; rm -rf /'"), "{cmd}");
    }

    #[test]
    fn environment_variables_are_passed_to_mosh_server() {
        let env = vec![
            ("LC_TERMINAL".to_string(), "flintTerm".to_string()),
            ("EDITOR".to_string(), "vim".to_string()),
        ];
        let cmd = start_command("mosh-server", "C", &env);
        assert!(cmd.contains("-l LC_TERMINAL='flintTerm'"), "{cmd}");
        // Unlike SSH, mosh-server sets these itself, so a name sshd would have
        // refused still arrives.
        assert!(cmd.contains("-l EDITOR='vim'"), "{cmd}");
    }

    #[test]
    fn a_variable_cannot_smuggle_shell_into_the_start_command() {
        let env = vec![
            ("BAD NAME".to_string(), "x".to_string()),
            ("ALSO;BAD".to_string(), "x".to_string()),
            ("9LEADING".to_string(), "x".to_string()),
            ("GOOD".to_string(), "it's fine; rm -rf /".to_string()),
        ];
        let cmd = start_command("mosh-server", "C", &env);
        assert!(!cmd.contains("BAD"), "a name that is not a variable name must be dropped: {cmd}");
        assert!(!cmd.contains("9LEADING"), "{cmd}");
        // The value keeps its text but loses the quote that would end the string.
        assert!(cmd.contains("-l GOOD='its fine; rm -rf /'"), "{cmd}");
    }

    #[test]
    fn a_custom_server_path_is_quoted_and_used() {
        let cmd = start_command("/opt/my mosh/bin/mosh-server", "C", &[]);
        assert!(cmd.contains("S='/opt/my mosh/bin/mosh-server'"), "{cmd}");
        assert!(!cmd.contains("command -v"), "an explicit path should not be second-guessed: {cmd}");
        // The danger is closing our quote, not the characters themselves: with the
        // quotes stripped the whole thing stays a single (nonexistent) command.
        let sneaky = start_command("x' ; rm -rf / ; '", "C", &[]);
        assert!(sneaky.contains("S='x ; rm -rf / ; '"), "{sneaky}");
        assert_eq!(sneaky.matches('\'').count(), 4, "only our own two pairs of quotes: {sneaky}");
    }

    #[test]
    fn an_empty_server_searches_for_one() {
        let cmd = start_command("  ", "C", &[]);
        assert!(cmd.contains("S=mosh-server"), "{cmd}");
        assert!(cmd.contains("command -v"), "{cmd}");
        assert!(cmd.contains("/opt/homebrew/bin/mosh-server"), "should cover the usual off-PATH installs: {cmd}");
    }

    #[test]
    fn the_relay_command_reports_its_port_and_detaches() {
        let cmd = relay_command("10.0.0.5", 60001, 120);
        assert!(cmd.contains("nohup python3 -c"), "must outlive the ssh channel: {cmd}");
        assert!(cmd.contains("'10.0.0.5' '60001' '120'"), "{cmd}");
        assert!(cmd.contains("grep -q RELAY"), "must wait for the port before returning: {cmd}");
        assert!(cmd.contains("no python3"), "should explain itself when python3 is missing");
    }

    #[test]
    fn the_relay_command_cannot_be_broken_by_the_target() {
        let cmd = relay_command("h' ; rm -rf / ; '", 1, 1);
        assert!(cmd.contains("'h ; rm -rf / ; '"), "quotes should be stripped, leaving one argument: {cmd}");
    }

    #[test]
    fn the_relay_port_is_parsed() {
        assert_eq!(parse_relay("RELAY 54321\n").unwrap(), 54321);
        assert_eq!(parse_relay("noise\nRELAY 60007\nmore\n").unwrap(), 60007);
    }

    #[test]
    fn a_missing_relay_reports_what_the_jump_host_said() {
        let err = parse_relay("no python3 on this jump host").unwrap_err().to_string();
        assert!(err.contains("no python3"), "{err}");
        assert!(parse_relay("").unwrap_err().to_string().contains("could not start a UDP relay"));
    }

    #[test]
    fn the_start_command_does_not_wait_on_the_daemon() {
        // Redirecting to a file is what keeps the exec channel from blocking.
        let cmd = start_command("mosh-server", "C", &[]);
        assert!(cmd.contains(">\"$f\""), "{cmd}");
        assert!(cmd.contains("cat \"$f\""), "{cmd}");
    }
}
