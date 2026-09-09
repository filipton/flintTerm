//! Everything the phone sends, carried over one SSH connection.
//!
//! Android hands a VPN a tun file descriptor: raw IP packets in, raw IP packets
//! out, with no sockets anywhere. SSH, on the other hand, only knows how to
//! open a stream to a host and a port. This crate is the piece in between — a
//! userspace IP stack (smoltcp) that terminates the phone's TCP connections
//! locally and re-opens each one as a `direct-tcpip` channel on the far side,
//! so the traffic arrives from the server rather than from the phone. It is the
//! same trick sshuttle plays, without needing anything installed on the server.
//!
//! What crosses and what does not is worth being plain about:
//!
//! * **TCP** — carried, every connection its own channel.
//! * **DNS** — carried, but rewritten to TCP, since SSH has no datagram
//!   channel. Queries are answered by a resolver the *server* can reach, which
//!   is the point: internal names resolve to internal addresses.
//! * **Other UDP, ICMP** — dropped. There is nothing to carry them over, so a
//!   `ping` will not answer and QUIC falls back to TCP.

use std::collections::{HashMap, VecDeque};
use std::future::Future;
use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::os::fd::{AsRawFd, RawFd};
use std::pin::Pin;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use smoltcp::iface::{Config as IfaceConfig, Interface, SocketHandle, SocketSet};
use smoltcp::phy::{Device, DeviceCapabilities, Medium};
use smoltcp::socket::{tcp, udp};
use smoltcp::time::Instant as SmolInstant;
use smoltcp::wire::{
    HardwareAddress, IpAddress, IpCidr, IpEndpoint, IpListenEndpoint, Ipv4Packet, Ipv6Packet, TcpPacket, UdpPacket,
};
use tokio::io::unix::AsyncFd;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::sync::mpsc;

/// Anything the router can pump bytes through: an SSH channel, or a pipe in a test.
pub trait Stream: tokio::io::AsyncRead + tokio::io::AsyncWrite + Send + Unpin {}
impl<T: tokio::io::AsyncRead + tokio::io::AsyncWrite + Send + Unpin> Stream for T {}

type Dialed = Pin<Box<dyn Future<Output = io::Result<Box<dyn Stream>>> + Send>>;

/// How the router reaches the far side. The SSH client implements this; a test
/// implements it with an in-memory pipe.
pub trait Dialer: Send + Sync + 'static {
    fn dial(&self, host: String, port: u16) -> Dialed;
}

#[derive(Clone, Copy, Default, Debug)]
pub struct Stats {
    /// Bytes the phone sent through the tunnel.
    pub sent: u64,
    /// Bytes that came back.
    pub received: u64,
    /// Connections opened since the tunnel came up.
    pub opened: u64,
    /// Connections carrying traffic right now.
    pub active: u64,
    /// Names looked up through the server.
    pub queries: u64,
}

/// How the tunnel is set up.
pub struct Config {
    /// The tun's MTU, as given to Android's `VpnService.Builder`.
    pub mtu: usize,
    /// Where name lookups are sent, dialed from the far side over TCP. May be a
    /// loopback address — it is the *server's* loopback that answers.
    pub resolver: Ipv4Addr,
    /// The address the phone was told to send DNS to. Nothing on the far side
    /// owns it, so a TCP connection there is refused at once rather than left
    /// to time out — which is what Android's DNS-over-TLS probe needs to hear
    /// before it falls back to ordinary DNS.
    pub dns_address: Ipv4Addr,
}

/// The tunnel: a task pumping one tun device, stopped by dropping this.
pub struct Router {
    stats: Arc<Mutex<Stats>>,
    running: Arc<AtomicBool>,
    task: tokio::task::JoinHandle<()>,
}

impl Router {
    /// Take over [fd] — the descriptor Android's `VpnService` returned — and
    /// carry what it receives to the far side of [dialer].
    ///
    /// The router owns the descriptor from here and closes it when it stops.
    pub fn start(fd: RawFd, config: Config, dialer: Arc<dyn Dialer>) -> io::Result<Router> {
        let tun = TunFd::adopt(fd)?;
        let stats = Arc::new(Mutex::new(Stats::default()));
        let running = Arc::new(AtomicBool::new(true));
        let worker = Worker::new(tun, config, dialer, stats.clone(), running.clone())?;
        let task = tokio::spawn(worker.run());
        Ok(Router { stats, running, task })
    }

    pub fn stats(&self) -> Stats {
        *self.stats.lock().unwrap()
    }

    pub fn stop(&self) {
        self.running.store(false, Ordering::Relaxed);
        self.task.abort();
    }
}

impl Drop for Router {
    fn drop(&mut self) {
        self.stop();
    }
}

// ---------------------------------------------------------------------------
// The tun device
// ---------------------------------------------------------------------------

/// The VPN descriptor, read and written as whole IP packets.
struct TunFd(RawFd);

impl TunFd {
    fn adopt(fd: RawFd) -> io::Result<TunFd> {
        // Readiness only works on a descriptor that will not block, and the one
        // Android hands over is blocking.
        let flags = unsafe { libc::fcntl(fd, libc::F_GETFL) };
        if flags < 0 {
            return Err(io::Error::last_os_error());
        }
        if unsafe { libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK) } < 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(TunFd(fd))
    }

    fn read(&self, buf: &mut [u8]) -> io::Result<usize> {
        let n = unsafe { libc::read(self.0, buf.as_mut_ptr() as *mut libc::c_void, buf.len()) };
        if n < 0 {
            Err(io::Error::last_os_error())
        } else {
            Ok(n as usize)
        }
    }

    fn write(&self, buf: &[u8]) -> io::Result<usize> {
        let n = unsafe { libc::write(self.0, buf.as_ptr() as *const libc::c_void, buf.len()) };
        if n < 0 {
            Err(io::Error::last_os_error())
        } else {
            Ok(n as usize)
        }
    }
}

impl AsRawFd for TunFd {
    fn as_raw_fd(&self) -> RawFd {
        self.0
    }
}

impl Drop for TunFd {
    fn drop(&mut self) {
        unsafe { libc::close(self.0) };
    }
}

// ---------------------------------------------------------------------------
// smoltcp plumbing
// ---------------------------------------------------------------------------

struct VirtDevice {
    rx: VecDeque<Vec<u8>>,
    tx: VecDeque<Vec<u8>>,
    mtu: usize,
}

struct VRx(Vec<u8>);
struct VTx<'a>(&'a mut VecDeque<Vec<u8>>);

impl smoltcp::phy::RxToken for VRx {
    fn consume<R, F: FnOnce(&[u8]) -> R>(self, f: F) -> R {
        f(&self.0)
    }
}

impl<'a> smoltcp::phy::TxToken for VTx<'a> {
    fn consume<R, F: FnOnce(&mut [u8]) -> R>(self, len: usize, f: F) -> R {
        let mut buf = vec![0u8; len];
        let r = f(&mut buf);
        self.0.push_back(buf);
        r
    }
}

impl Device for VirtDevice {
    type RxToken<'a> = VRx where Self: 'a;
    type TxToken<'a> = VTx<'a> where Self: 'a;

    fn receive(&mut self, _ts: SmolInstant) -> Option<(Self::RxToken<'_>, Self::TxToken<'_>)> {
        let p = self.rx.pop_front()?;
        Some((VRx(p), VTx(&mut self.tx)))
    }

    fn transmit(&mut self, _ts: SmolInstant) -> Option<Self::TxToken<'_>> {
        Some(VTx(&mut self.tx))
    }

    fn capabilities(&self) -> DeviceCapabilities {
        let mut c = DeviceCapabilities::default();
        c.medium = Medium::Ip;
        c.max_transmission_unit = self.mtu;
        c
    }
}

/// One connection the phone made, as seen from both sides.
struct Conn {
    handle: SocketHandle,
    /// Bytes on their way out, once the far side is up.
    to_remote: Option<mpsc::Sender<Vec<u8>>>,
    /// Bytes that arrived before the socket could take them.
    pending: VecDeque<Vec<u8>>,
    /// The far side is gone; close once [pending] has drained.
    remote_closed: bool,
    /// The dial is still in flight — nothing may be read off the socket yet, or
    /// it would be dropped on the floor.
    dialing: bool,
}

/// What the connection tasks tell the worker about.
enum Event {
    /// The far side is open and will take bytes.
    Opened(ConnKey, mpsc::Sender<Vec<u8>>),
    /// The far side refused, or the dial failed.
    Failed(ConnKey),
    /// Bytes back from the far side.
    Data(ConnKey, Vec<u8>),
    /// The far side hung up.
    Closed(ConnKey),
    /// A DNS answer, for whoever asked.
    Dns(udp::UdpMetadata, Vec<u8>),
}

type ConnKey = (IpAddress, u16, IpAddress, u16);

/// Room for a few packets in either direction; more only adds latency.
const CHANNEL_DEPTH: usize = 32;
/// TCP buffers per connection. Enough to keep a fast link busy, small enough
/// that a hundred connections do not add up to anything the phone notices.
const SOCKET_BUFFER: usize = 64 * 1024;
const MAX_PACKET: usize = 9000;
/// A name lookup that has not answered by now is not going to.
const DNS_TIMEOUT: Duration = Duration::from_secs(5);
/// A connection the server cannot make deserves a refusal, not a socket held
/// open until the application gives up on its own.
const DIAL_TIMEOUT: Duration = Duration::from_secs(15);

struct Worker {
    tun: AsyncFd<TunFd>,
    device: VirtDevice,
    iface: Interface,
    sockets: SocketSet<'static>,
    conns: HashMap<ConnKey, Conn>,
    dns_socket: SocketHandle,
    dialer: Arc<dyn Dialer>,
    config: Config,
    events_tx: mpsc::UnboundedSender<Event>,
    events_rx: mpsc::UnboundedReceiver<Event>,
    stats: Arc<Mutex<Stats>>,
    running: Arc<AtomicBool>,
}

impl Worker {
    fn new(
        tun: TunFd,
        config: Config,
        dialer: Arc<dyn Dialer>,
        stats: Arc<Mutex<Stats>>,
        running: Arc<AtomicBool>,
    ) -> io::Result<Worker> {
        let mut device = VirtDevice { rx: VecDeque::new(), tx: VecDeque::new(), mtu: config.mtu.clamp(576, 9000) };
        let mut iface = Interface::new(IfaceConfig::new(HardwareAddress::Ip), &mut device, SmolInstant::now());
        // The stack answers for whatever address the phone was talking to, which
        // is the whole point of sitting in front of a default route.
        iface.set_any_ip(true);
        iface.update_ip_addrs(|addrs| {
            let _ = addrs.push(IpCidr::new(IpAddress::v4(10, 60, 0, 2), 32));
            let _ = addrs.push(IpCidr::new(IpAddress::v6(0xfd00, 0x60, 0, 0, 0, 0, 0, 2), 128));
        });
        // The gateway has to be one of our own addresses: with `any_ip` smoltcp
        // only accepts a packet addressed elsewhere when the route for it leads
        // back to this interface. On an IP medium nothing is ever sent to a
        // next hop, so this costs nothing.
        let _ = iface.routes_mut().add_default_ipv4_route(Ipv4Addr::new(10, 60, 0, 2));
        let _ = iface.routes_mut().add_default_ipv6_route(Ipv6Addr::new(0xfd00, 0x60, 0, 0, 0, 0, 0, 2));

        let mut sockets = SocketSet::new(vec![]);
        // One socket answers every name lookup: they are single datagrams, and
        // the destination is carried on each one.
        let dns_socket = {
            let rx = udp::PacketBuffer::new(vec![udp::PacketMetadata::EMPTY; 32], vec![0u8; 16 * 1024]);
            let tx = udp::PacketBuffer::new(vec![udp::PacketMetadata::EMPTY; 32], vec![0u8; 16 * 1024]);
            let mut sock = udp::Socket::new(rx, tx);
            sock.bind(IpListenEndpoint { addr: None, port: 53 }).map_err(|e| io::Error::other(format!("dns bind: {e:?}")))?;
            sockets.add(sock)
        };
        let (events_tx, events_rx) = mpsc::unbounded_channel();
        Ok(Worker {
            tun: AsyncFd::new(tun)?,
            device,
            iface,
            sockets,
            conns: HashMap::new(),
            dns_socket,
            dialer,
            config,
            events_tx,
            events_rx,
            stats,
            running,
        })
    }

    async fn run(mut self) {
        let mut buf = vec![0u8; MAX_PACKET];
        while self.running.load(Ordering::Relaxed) {
            self.poll_stack();
            self.flush_out();

            let delay = if self.device.tx.is_empty() {
                self.iface
                    .poll_delay(SmolInstant::now(), &self.sockets)
                    .map(|d| Duration::from_micros(d.total_micros()))
                    .unwrap_or(Duration::from_millis(500))
            } else {
                // Something is waiting to go to the phone and the descriptor
                // was not ready for it. Come straight back rather than sitting
                // on the packet until the next timer.
                Duration::from_millis(2)
            };

            tokio::select! {
                r = self.tun.readable() => {
                    match r {
                        Ok(mut guard) => {
                            // Drain what is waiting; one wake-up per packet would
                            // spend more time in the scheduler than in the stack.
                            // Collected first, then handed on: the guard borrows
                            // the descriptor, and looking at a packet needs the
                            // rest of the worker.
                            let mut batch: Vec<Vec<u8>> = Vec::new();
                            loop {
                                match guard.try_io(|inner| inner.get_ref().read(&mut buf)) {
                                    // Nothing more will ever come out of a
                                    // descriptor at end of file — Android has
                                    // taken the VPN away — and retrying would
                                    // spin for as long as the tunnel lived.
                                    Ok(Ok(0)) => {
                                        log::debug!("tun closed");
                                        self.running.store(false, Ordering::Relaxed);
                                        break;
                                    }
                                    Err(_) => break,
                                    Ok(Ok(n)) => {
                                        batch.push(buf[..n].to_vec());
                                        if batch.len() >= 256 { break; }
                                    }
                                    Ok(Err(e)) => {
                                        if e.kind() != io::ErrorKind::WouldBlock {
                                            log::warn!("tun read: {e}");
                                        }
                                        break;
                                    }
                                }
                            }
                            for packet in batch {
                                self.intercept(&packet);
                                self.device.rx.push_back(packet);
                            }
                        }
                        Err(e) => {
                            log::warn!("tun readable: {e}");
                            break;
                        }
                    }
                }
                Some(ev) = self.events_rx.recv() => self.on_event(ev),
                _ = tokio::time::sleep(delay) => {}
            }
        }
    }

    /// Write everything smoltcp produced back to the phone.
    fn flush_out(&mut self) {
        while let Some(p) = self.device.tx.pop_front() {
            match self.tun.get_ref().write(&p) {
                Ok(_) => {}
                Err(e) if e.kind() == io::ErrorKind::WouldBlock => {
                    // Rare on a tun: put it back and let the next turn retry.
                    self.device.tx.push_front(p);
                    break;
                }
                Err(e) => {
                    log::warn!("tun write: {e}");
                    break;
                }
            }
        }
    }

    /// Watch for the start of a connection.
    ///
    /// smoltcp will only answer a SYN when a socket is already listening on the
    /// address it is addressed to, and here that address is "wherever the phone
    /// was going" — not known until the packet arrives. So each packet is
    /// looked at before the stack sees it, and a socket is put in place just in
    /// time for it.
    fn intercept(&mut self, packet: &[u8]) {
        let (src, dst, protocol, payload) = match packet.first().map(|b| b >> 4) {
            Some(4) => {
                let ip = match Ipv4Packet::new_checked(packet) {
                    Ok(v) => v,
                    Err(_) => return,
                };
                (
                    IpAddress::Ipv4(ip.src_addr()),
                    IpAddress::Ipv4(ip.dst_addr()),
                    ip.next_header(),
                    ip.payload().to_vec(),
                )
            }
            Some(6) => {
                let ip = match Ipv6Packet::new_checked(packet) {
                    Ok(v) => v,
                    Err(_) => return,
                };
                (
                    IpAddress::Ipv6(ip.src_addr()),
                    IpAddress::Ipv6(ip.dst_addr()),
                    ip.next_header(),
                    ip.payload().to_vec(),
                )
            }
            _ => return,
        };
        if protocol != smoltcp::wire::IpProtocol::Tcp {
            return;
        }
        let tcp_packet = match TcpPacket::new_checked(&payload[..]) {
            Ok(v) => v,
            Err(_) => return,
        };
        if !tcp_packet.syn() || tcp_packet.ack() {
            return;
        }
        let key = (src, tcp_packet.src_port(), dst, tcp_packet.dst_port());
        if self.conns.contains_key(&key) {
            return;
        }
        let mut sock = tcp::Socket::new(
            tcp::SocketBuffer::new(vec![0u8; SOCKET_BUFFER]),
            tcp::SocketBuffer::new(vec![0u8; SOCKET_BUFFER]),
        );
        // Nagle would add a round trip to every small write, and the phone's
        // stack has already made that decision for the application.
        sock.set_nagle_enabled(false);
        // A connection whose far side has gone quiet is worth giving up on
        // rather than holding a channel open for ever.
        sock.set_timeout(Some(smoltcp::time::Duration::from_secs(600)));
        sock.set_keep_alive(Some(smoltcp::time::Duration::from_secs(60)));
        if sock.listen(IpEndpoint::new(dst, tcp_packet.dst_port())).is_err() {
            return;
        }
        let handle = self.sockets.add(sock);
        let target = SocketAddr::new(to_ip(dst), tcp_packet.dst_port());
        self.conns.insert(
            key,
            Conn { handle, to_remote: None, pending: VecDeque::new(), remote_closed: false, dialing: true },
        );
        // The DNS address is ours, not the server's: refuse rather than dial it.
        if target.ip() == IpAddr::V4(self.config.dns_address) {
            let _ = self.events_tx.send(Event::Failed(key));
            return;
        }
        self.stats.lock().unwrap().opened += 1;
        self.open_channel(key, target);
    }

    /// Ask the far side for a stream, and pump it once it is there.
    fn open_channel(&mut self, key: ConnKey, target: SocketAddr) {
        let dialer = self.dialer.clone();
        let events = self.events_tx.clone();
        tokio::spawn(async move {
            let host = match target.ip() {
                IpAddr::V4(v4) => v4.to_string(),
                IpAddr::V6(v6) => v6.to_string(),
            };
            let stream = match tokio::time::timeout(DIAL_TIMEOUT, dialer.dial(host, target.port())).await {
                Ok(Ok(s)) => s,
                Ok(Err(e)) => {
                    log::debug!("vpn: {target} refused: {e}");
                    let _ = events.send(Event::Failed(key));
                    return;
                }
                Err(_) => {
                    log::debug!("vpn: {target} did not answer in time");
                    let _ = events.send(Event::Failed(key));
                    return;
                }
            };
            let (tx, rx) = mpsc::channel::<Vec<u8>>(CHANNEL_DEPTH);
            if events.send(Event::Opened(key, tx)).is_err() {
                return;
            }
            pump(stream, rx, key, events).await;
        });
    }

    fn on_event(&mut self, ev: Event) {
        match ev {
            Event::Opened(key, tx) => {
                if let Some(conn) = self.conns.get_mut(&key) {
                    conn.to_remote = Some(tx);
                    conn.dialing = false;
                    self.stats.lock().unwrap().active += 1;
                }
            }
            Event::Failed(key) => {
                if let Some(conn) = self.conns.remove(&key) {
                    // Aborting sends a reset, which is what the application
                    // expects from a refused connection.
                    self.sockets.get_mut::<tcp::Socket>(conn.handle).abort();
                    self.sockets.remove(conn.handle);
                }
            }
            Event::Data(key, bytes) => {
                if let Some(conn) = self.conns.get_mut(&key) {
                    self.stats.lock().unwrap().received += bytes.len() as u64;
                    conn.pending.push_back(bytes);
                }
            }
            Event::Closed(key) => {
                if let Some(conn) = self.conns.get_mut(&key) {
                    conn.remote_closed = true;
                }
            }
            Event::Dns(meta, answer) => {
                let sock = self.sockets.get_mut::<udp::Socket>(self.dns_socket);
                if sock.can_send() {
                    let _ = sock.send_slice(&answer, meta);
                }
            }
        }
    }

    /// One turn of the stack: hand it what arrived, then move bytes between the
    /// sockets and the far side.
    fn poll_stack(&mut self) {
        self.iface.poll(SmolInstant::now(), &mut self.device, &mut self.sockets);

        // --- TCP ----------------------------------------------------------
        let keys: Vec<ConnKey> = self.conns.keys().copied().collect();
        let mut finished = Vec::new();
        for key in keys {
            let Some(conn) = self.conns.get_mut(&key) else { continue };
            let sock = self.sockets.get_mut::<tcp::Socket>(conn.handle);

            // Anything that came back goes in first; a full receive buffer just
            // means the phone is slow to read and the rest waits its turn.
            while let Some(front) = conn.pending.front() {
                if !sock.can_send() {
                    break;
                }
                match sock.send_slice(front) {
                    Ok(0) => break,
                    Ok(n) if n < front.len() => {
                        conn.pending[0] = front[n..].to_vec();
                        break;
                    }
                    Ok(_) => {
                        conn.pending.pop_front();
                    }
                    Err(_) => break,
                }
            }

            // Only once the channel exists, or the bytes would have nowhere to go.
            if let Some(tx) = conn.to_remote.clone() {
                while sock.can_recv() {
                    // Backpressure: leave it in the socket if the far side is
                    // behind, and TCP will close the window by itself.
                    let permit = match tx.try_reserve() {
                        Ok(p) => p,
                        Err(_) => break,
                    };
                    let chunk = sock.recv(|buf| {
                        let take = buf.len().min(16 * 1024);
                        (take, buf[..take].to_vec())
                    });
                    match chunk {
                        Ok(bytes) if !bytes.is_empty() => {
                            self.stats.lock().unwrap().sent += bytes.len() as u64;
                            permit.send(bytes);
                        }
                        _ => break,
                    }
                }
            }

            let idle = !sock.is_active() && !conn.dialing;
            let done = conn.remote_closed && conn.pending.is_empty();
            if done && sock.may_send() {
                sock.close();
            }
            if idle || (done && !sock.is_open()) {
                finished.push(key);
            }
        }
        for key in finished {
            if let Some(conn) = self.conns.remove(&key) {
                if conn.to_remote.is_some() {
                    let mut stats = self.stats.lock().unwrap();
                    stats.active = stats.active.saturating_sub(1);
                }
                self.sockets.remove(conn.handle);
            }
        }

        // --- DNS ----------------------------------------------------------
        loop {
            let sock = self.sockets.get_mut::<udp::Socket>(self.dns_socket);
            let Ok((payload, meta)) = sock.recv() else { break };
            let query = payload.to_vec();
            self.stats.lock().unwrap().queries += 1;
            self.resolve(query, meta);
        }
    }

    /// Ask the server's resolver, over TCP because that is all SSH can carry.
    fn resolve(&self, query: Vec<u8>, meta: udp::UdpMetadata) {
        let dialer = self.dialer.clone();
        let events = self.events_tx.clone();
        let resolver = self.config.resolver;
        tokio::spawn(async move {
            let answer = tokio::time::timeout(DNS_TIMEOUT, async {
                let mut stream = dialer.dial(resolver.to_string(), 53).await?;
                // DNS over TCP is the same message behind a two-byte length.
                let mut framed = Vec::with_capacity(query.len() + 2);
                framed.extend_from_slice(&(query.len() as u16).to_be_bytes());
                framed.extend_from_slice(&query);
                stream.write_all(&framed).await?;
                stream.flush().await?;
                let mut len = [0u8; 2];
                stream.read_exact(&mut len).await?;
                let mut body = vec![0u8; u16::from_be_bytes(len) as usize];
                stream.read_exact(&mut body).await?;
                Ok::<_, io::Error>(body)
            })
            .await;
            match answer {
                Ok(Ok(body)) => {
                    let _ = events.send(Event::Dns(meta, body));
                }
                Ok(Err(e)) => log::debug!("vpn dns: {e}"),
                Err(_) => log::debug!("vpn dns: timed out"),
            }
        });
    }
}

/// Move bytes between one SSH channel and the worker until either end stops.
async fn pump(mut stream: Box<dyn Stream>, mut rx: mpsc::Receiver<Vec<u8>>, key: ConnKey, events: mpsc::UnboundedSender<Event>) {
    let mut buf = vec![0u8; 32 * 1024];
    loop {
        tokio::select! {
            out = rx.recv() => match out {
                Some(bytes) => {
                    if stream.write_all(&bytes).await.is_err() {
                        break;
                    }
                }
                // The worker dropped the connection: nothing more will be sent.
                None => break,
            },
            r = stream.read(&mut buf) => match r {
                Ok(0) | Err(_) => break,
                Ok(n) => {
                    if events.send(Event::Data(key, buf[..n].to_vec())).is_err() {
                        break;
                    }
                }
            },
        }
    }
    let _ = stream.shutdown().await;
    let _ = events.send(Event::Closed(key));
}

fn to_ip(addr: IpAddress) -> IpAddr {
    match addr {
        IpAddress::Ipv4(v4) => IpAddr::V4(Ipv4Addr::from(v4)),
        IpAddress::Ipv6(v6) => IpAddr::V6(Ipv6Addr::from(v6)),
    }
}

/// The UDP header a datagram carries, for callers that want to look before the
/// stack does. Kept public for tests.
pub fn udp_ports(payload: &[u8]) -> Option<(u16, u16)> {
    let packet = UdpPacket::new_checked(payload).ok()?;
    Some((packet.src_port(), packet.dst_port()))
}
