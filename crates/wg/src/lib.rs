//! Userspace WireGuard tunnel: boringtun does the crypto, smoltcp provides a
//! TCP/IP stack on top, and the result is exposed as ordinary async byte
//! streams. No TUN device, no VPN permission — only sockets opened through
//! [`Tunnel::connect`] go through the tunnel.

pub mod config;
mod dns;

use std::collections::{HashMap, VecDeque};
use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::pin::Pin;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::task::{Context, Poll};
use std::time::Duration;

use boringtun::noise::{Tunn, TunnResult};
use smoltcp::iface::{Config as IfaceConfig, Interface, SocketHandle, SocketSet};
use smoltcp::phy::ChecksumCapabilities;
use smoltcp::wire::{Icmpv4Packet, Icmpv4Repr};
use smoltcp::phy::{Device, DeviceCapabilities, Medium};
use smoltcp::socket::{icmp, tcp, udp};
use smoltcp::time::Instant as SmolInstant;
use smoltcp::wire::{HardwareAddress, IpAddress, IpCidr};
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio::net::UdpSocket;
use tokio::sync::{mpsc, oneshot};

pub use config::{IpNet, Peer, WgConfig};

#[derive(Debug, thiserror::Error)]
pub enum WgError {
    #[error("config: {0}")]
    Config(String),
    #[error("io: {0}")]
    Io(#[from] io::Error),
    #[error("tunnel is not running")]
    Stopped,
    #[error("{0}")]
    Other(String),
}

const MAX_PACKET: usize = 65536;
/// Room left for the WireGuard header and its authentication tag, so an inner
/// packet at the device MTU still fits one outer datagram.
const WG_OVERHEAD: usize = 80;

/// The MTU seen by the stack inside the tunnel.
fn device_mtu(config: &WgConfig) -> usize {
    config.mtu.saturating_sub(WG_OVERHEAD).max(576)
}
const TCP_BUFFER: usize = 256 * 1024;
const TIMER_TICK: Duration = Duration::from_millis(250);
const CONNECT_TIMEOUT: Duration = Duration::from_secs(20);
const DNS_TIMEOUT: Duration = Duration::from_secs(6);

/// Snapshot of the tunnel's health for the UI.
#[derive(Debug, Clone, Default)]
pub struct TunnelStats {
    /// Seconds since the last completed handshake, if any.
    pub last_handshake_secs: Option<u64>,
    pub tx_bytes: u64,
    pub rx_bytes: u64,
    pub endpoint: Option<SocketAddr>,
    pub running: bool,
}

// ---------------------------------------------------------------------------
// Public handle
// ---------------------------------------------------------------------------

pub struct Tunnel {
    config: WgConfig,
    cmd: mpsc::UnboundedSender<Cmd>,
    stats: Arc<Mutex<TunnelStats>>,
    local_addr: SocketAddr,
    running: Arc<AtomicBool>,
    /// False while the phone has no network; see [`Tunnel::set_network_up`].
    network_up: Arc<AtomicBool>,
}

impl Tunnel {
    /// Bind a UDP socket, resolve the peer endpoint, send the first handshake
    /// and start the packet loop on the current tokio runtime.
    pub async fn start(config: WgConfig) -> Result<Arc<Tunnel>, WgError> {
        let endpoint = resolve_endpoint(&config.peer.endpoint).await?;
        let bind: SocketAddr = if endpoint.is_ipv4() { "0.0.0.0:0".parse().unwrap() } else { "[::]:0".parse().unwrap() };
        Self::start_bound(config, bind).await
    }

    /// Like [`start`] but with an explicit local UDP address (servers, tests).
    pub async fn start_bound(config: WgConfig, bind: SocketAddr) -> Result<Arc<Tunnel>, WgError> {
        let endpoint = resolve_endpoint(&config.peer.endpoint).await?;
        let udp = UdpSocket::bind(bind).await?;
        let local_addr = udp.local_addr()?;

        let private = boringtun::x25519::StaticSecret::from(config.private_key);
        let public = boringtun::x25519::PublicKey::from(config.peer.public_key);
        let tunn = Tunn::new(private, public, config.peer.preshared_key, config.peer.persistent_keepalive, rand::random(), None);

        let (cmd_tx, cmd_rx) = mpsc::unbounded_channel();
        let stats = Arc::new(Mutex::new(TunnelStats { endpoint: Some(endpoint), running: true, ..Default::default() }));
        let running = Arc::new(AtomicBool::new(true));
        let network_up = Arc::new(AtomicBool::new(true));

        let worker = Worker::new(
            config.clone(), tunn, udp, endpoint, cmd_rx, cmd_tx.clone(), stats.clone(), running.clone(), network_up.clone(),
        );
        tokio::spawn(worker.run());

        Ok(Arc::new(Tunnel { config, cmd: cmd_tx, stats, local_addr, running, network_up }))
    }

    pub fn config(&self) -> &WgConfig {
        &self.config
    }

    /// The UDP port this end uses (mostly useful for tests).
    pub fn local_addr(&self) -> SocketAddr {
        self.local_addr
    }

    pub fn stats(&self) -> TunnelStats {
        self.stats.lock().unwrap().clone()
    }

    pub fn is_running(&self) -> bool {
        self.running.load(Ordering::Relaxed)
    }

    /// Open a TCP connection to `addr:port` from inside the tunnel.
    pub async fn connect(&self, addr: IpAddr, port: u16) -> Result<VirtualStream, WgError> {
        let (reply, rx) = oneshot::channel();
        self.cmd.send(Cmd::Connect { addr, port, reply }).map_err(|_| WgError::Stopped)?;
        rx.await.map_err(|_| WgError::Stopped)?
    }

    /// Resolve a host name using the DNS servers from the config, through the tunnel.
    /// Open a datagram socket inside the tunnel, fixed to one peer.
    ///
    /// Datagram boundaries are preserved end to end, which is the point: it is
    /// what lets a UDP protocol like Mosh run over the tunnel without being
    /// wrapped in a stream that would reintroduce head-of-line blocking.
    pub async fn udp_connect(&self, addr: IpAddr, port: u16) -> Result<VirtualDatagram, WgError> {
        let (reply, rx) = oneshot::channel();
        self.cmd.send(Cmd::UdpBind { addr, port, reply }).map_err(|_| WgError::Stopped)?;
        rx.await.map_err(|_| WgError::Stopped)?
    }

    /// Tell the tunnel whether the phone has a network at all.
    ///
    /// With no network, WireGuard's timers keep re-initiating handshakes into
    /// the void — a steady drip of radio wake-ups and data for nothing. While
    /// this is false the periodic work is skipped; traffic the caller actually
    /// sends still goes out, so nothing is broken if the guess is wrong.
    pub fn set_network_up(&self, up: bool) {
        self.network_up.store(up, Ordering::Relaxed);
    }

    pub fn network_up(&self) -> bool {
        self.network_up.load(Ordering::Relaxed)
    }

    /// Bind a datagram socket on `port` inside the tunnel, taking traffic from
    /// any peer.
    pub async fn udp_listen(&self, port: u16) -> Result<UdpListener, WgError> {
        let (reply, rx) = oneshot::channel();
        self.cmd.send(Cmd::UdpListen { port, reply }).map_err(|_| WgError::Stopped)?;
        rx.await.map_err(|_| WgError::Stopped)?
    }

    /// Send an ICMP echo inside the tunnel and wait for the reply.
    ///
    /// The question every VPN raises is "can it actually reach the box?", and
    /// ping is how everyone asks it. Returns the round trip in milliseconds, or
    /// nothing if the timeout passed — a host that ignores pings looks the same
    /// as one that is not there, which is true of ping everywhere.
    pub async fn ping(&self, addr: IpAddr, timeout: Duration) -> Result<Option<u32>, WgError> {
        let (reply, rx) = oneshot::channel();
        self.cmd.send(Cmd::Ping { addr, timeout, reply }).map_err(|_| WgError::Stopped)?;
        rx.await.map_err(|_| WgError::Stopped)?
    }

    pub async fn resolve(&self, name: &str) -> Result<IpAddr, WgError> {
        if let Ok(ip) = name.parse::<IpAddr>() {
            return Ok(ip);
        }
        if self.config.dns.is_empty() {
            return Err(WgError::Other(format!("cannot resolve '{name}': the tunnel config has no DNS server; use an IP address")));
        }
        let (reply, rx) = oneshot::channel();
        self.cmd.send(Cmd::Resolve { name: name.to_string(), reply }).map_err(|_| WgError::Stopped)?;
        rx.await.map_err(|_| WgError::Stopped)?
    }

    /// Accept TCP connections arriving through the tunnel on `port`.
    pub async fn listen(&self, port: u16) -> Result<Listener, WgError> {
        let (reply, rx) = oneshot::channel();
        self.cmd.send(Cmd::Listen { port, reply }).map_err(|_| WgError::Stopped)?;
        rx.await.map_err(|_| WgError::Stopped)?
    }

    pub fn stop(&self) {
        let _ = self.cmd.send(Cmd::Stop);
    }
}

impl Drop for Tunnel {
    fn drop(&mut self) {
        let _ = self.cmd.send(Cmd::Stop);
    }
}

async fn resolve_endpoint(endpoint: &str) -> Result<SocketAddr, WgError> {
    if let Ok(sa) = endpoint.parse::<SocketAddr>() {
        return Ok(sa);
    }
    tokio::net::lookup_host(endpoint)
        .await
        .map_err(|e| WgError::Other(format!("cannot resolve endpoint {endpoint}: {e}")))?
        .next()
        .ok_or_else(|| WgError::Other(format!("cannot resolve endpoint {endpoint}")))
}

// ---------------------------------------------------------------------------
// Virtual stream / listener
// ---------------------------------------------------------------------------

/// A TCP connection living inside the tunnel. Implements tokio's async I/O traits.
pub struct VirtualStream {
    id: u32,
    cmd: mpsc::UnboundedSender<Cmd>,
    incoming: mpsc::UnboundedReceiver<Vec<u8>>,
    leftover: Vec<u8>,
    leftover_pos: usize,
}

impl VirtualStream {
    pub fn peer_id(&self) -> u32 {
        self.id
    }
}

/// A datagram socket inside the tunnel, fixed to one peer. Dropping it frees
/// the socket.
#[derive(Debug)]
pub struct VirtualDatagram {
    id: u32,
    peer: SocketAddr,
    max_payload: usize,
    cmd: mpsc::UnboundedSender<Cmd>,
    incoming: mpsc::UnboundedReceiver<(Vec<u8>, SocketAddr)>,
}

impl VirtualDatagram {
    /// Queue one datagram to the peer. Like UDP anywhere, delivery is not promised.
    ///
    /// The datagram has to fit [`Self::max_payload`]: the stack inside the
    /// tunnel does not fragment IP, so an oversized one is refused here rather
    /// than disappearing silently. Protocols that fragment themselves — Mosh
    /// does — never come close.
    pub fn send(&self, data: Vec<u8>) -> Result<(), WgError> {
        if data.len() > self.max_payload {
            return Err(WgError::Other(format!(
                "datagram of {} bytes exceeds the tunnel's {} byte limit",
                data.len(),
                self.max_payload
            )));
        }
        self.cmd.send(Cmd::UdpSend { id: self.id, data, to: self.peer }).map_err(|_| WgError::Stopped)
    }

    /// Largest datagram that can cross this tunnel in one piece.
    pub fn max_payload(&self) -> usize {
        self.max_payload
    }

    /// The next datagram from the peer, or `None` once the tunnel is gone.
    pub async fn recv(&mut self) -> Option<Vec<u8>> {
        self.incoming.recv().await.map(|(data, _)| data)
    }

    pub fn peer(&self) -> SocketAddr {
        self.peer
    }
}

impl Drop for VirtualDatagram {
    fn drop(&mut self) {
        let _ = self.cmd.send(Cmd::UdpClose { id: self.id });
    }
}

/// A datagram socket inside the tunnel bound to a known port, taking traffic
/// from any peer — the UDP counterpart of [`Listener`].
#[derive(Debug)]
pub struct UdpListener {
    id: u32,
    max_payload: usize,
    cmd: mpsc::UnboundedSender<Cmd>,
    incoming: mpsc::UnboundedReceiver<(Vec<u8>, SocketAddr)>,
}

impl UdpListener {
    pub async fn recv_from(&mut self) -> Option<(Vec<u8>, SocketAddr)> {
        self.incoming.recv().await
    }

    pub fn send_to(&self, data: Vec<u8>, to: SocketAddr) -> Result<(), WgError> {
        if data.len() > self.max_payload {
            return Err(WgError::Other(format!(
                "datagram of {} bytes exceeds the tunnel's {} byte limit",
                data.len(),
                self.max_payload
            )));
        }
        self.cmd.send(Cmd::UdpSend { id: self.id, data, to }).map_err(|_| WgError::Stopped)
    }

    pub fn max_payload(&self) -> usize {
        self.max_payload
    }
}

impl Drop for UdpListener {
    fn drop(&mut self) {
        let _ = self.cmd.send(Cmd::UdpClose { id: self.id });
    }
}

impl AsyncRead for VirtualStream {
    fn poll_read(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        loop {
            if self.leftover_pos < self.leftover.len() {
                let n = (self.leftover.len() - self.leftover_pos).min(buf.remaining());
                buf.put_slice(&self.leftover[self.leftover_pos..self.leftover_pos + n]);
                self.leftover_pos += n;
                return Poll::Ready(Ok(()));
            }
            match self.incoming.poll_recv(cx) {
                Poll::Ready(Some(data)) => {
                    self.leftover = data;
                    self.leftover_pos = 0;
                }
                Poll::Ready(None) => return Poll::Ready(Ok(())), // EOF
                Poll::Pending => return Poll::Pending,
            }
        }
    }
}

impl AsyncWrite for VirtualStream {
    fn poll_write(self: Pin<&mut Self>, _cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
        match self.cmd.send(Cmd::Send { id: self.id, data: buf.to_vec() }) {
            Ok(()) => Poll::Ready(Ok(buf.len())),
            Err(_) => Poll::Ready(Err(io::Error::new(io::ErrorKind::BrokenPipe, "tunnel stopped"))),
        }
    }

    fn poll_flush(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Poll::Ready(Ok(()))
    }

    fn poll_shutdown(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        let _ = self.cmd.send(Cmd::Shutdown { id: self.id });
        Poll::Ready(Ok(()))
    }
}

impl Drop for VirtualStream {
    fn drop(&mut self) {
        let _ = self.cmd.send(Cmd::Close { id: self.id });
    }
}

pub struct Listener {
    accepted: mpsc::UnboundedReceiver<VirtualStream>,
}

impl Listener {
    pub async fn accept(&mut self) -> Option<VirtualStream> {
        self.accepted.recv().await
    }
}

// ---------------------------------------------------------------------------
// Worker: owns the crypto state, the UDP socket and the smoltcp stack.
// ---------------------------------------------------------------------------

/// Identifier put in every echo request, so replies meant for us are obvious.
const PING_IDENT: u16 = 0x4154;

enum Cmd {
    Connect { addr: IpAddr, port: u16, reply: oneshot::Sender<Result<VirtualStream, WgError>> },
    Listen { port: u16, reply: oneshot::Sender<Result<Listener, WgError>> },
    Resolve { name: String, reply: oneshot::Sender<Result<IpAddr, WgError>> },
    Ping { addr: IpAddr, timeout: Duration, reply: oneshot::Sender<Result<Option<u32>, WgError>> },
    Send { id: u32, data: Vec<u8> },
    UdpBind { addr: IpAddr, port: u16, reply: oneshot::Sender<Result<VirtualDatagram, WgError>> },
    UdpListen { port: u16, reply: oneshot::Sender<Result<UdpListener, WgError>> },
    UdpSend { id: u32, data: Vec<u8>, to: SocketAddr },
    UdpClose { id: u32 },
    Shutdown { id: u32 },
    Close { id: u32 },
    Stop,
}

struct Conn {
    handle: SocketHandle,
    tx_queue: VecDeque<Vec<u8>>,
    /// Data towards the reader; `None` once EOF was delivered.
    to_reader: Option<mpsc::UnboundedSender<Vec<u8>>>,
    /// Pending connect: the stream to hand out once established, plus the deadline.
    connecting: Option<(VirtualStream, oneshot::Sender<Result<VirtualStream, WgError>>, tokio::time::Instant)>,
    /// The local side asked to close after the queue drains.
    shutdown: bool,
    closed_by_local: bool,
}

/// A datagram socket the caller holds.
struct UdpConn {
    handle: SocketHandle,
    /// Pinned peer for a connected socket; `None` accepts from anyone.
    peer: Option<(IpAddress, u16)>,
    to_reader: mpsc::UnboundedSender<(Vec<u8>, SocketAddr)>,
    tx_queue: VecDeque<(Vec<u8>, (IpAddress, u16))>,
}

/// An echo request waiting for its reply.
struct Ping {
    sent: std::time::Instant,
    deadline: std::time::Instant,
    reply: oneshot::Sender<Result<Option<u32>, WgError>>,
}

struct ListenSlot {
    port: u16,
    handle: SocketHandle,
    accepted: mpsc::UnboundedSender<VirtualStream>,
}

struct DnsQuery {
    handle: SocketHandle,
    id: u16,
    name: String,
    qtype: u16,
    reply: oneshot::Sender<Result<IpAddr, WgError>>,
    deadline: tokio::time::Instant,
}

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

/// smoltcp addresses back to std ones, so callers never see smoltcp types.
fn to_ip_addr(addr: IpAddress) -> IpAddr {
    match addr {
        IpAddress::Ipv4(v4) => IpAddr::V4(Ipv4Addr::from(v4)),
        IpAddress::Ipv6(v6) => IpAddr::V6(Ipv6Addr::from(v6)),
    }
}

struct Worker {
    config: WgConfig,
    tunn: Tunn,
    udp: UdpSocket,
    endpoint: SocketAddr,
    cmd_rx: mpsc::UnboundedReceiver<Cmd>,
    cmd_tx: mpsc::UnboundedSender<Cmd>,
    stats: Arc<Mutex<TunnelStats>>,
    running: Arc<AtomicBool>,
    network_up: Arc<AtomicBool>,
    device: VirtDevice,
    iface: Interface,
    sockets: SocketSet<'static>,
    conns: HashMap<u32, Conn>,
    listeners: Vec<ListenSlot>,
    dns: Vec<DnsQuery>,
    udps: HashMap<u32, UdpConn>,
    /// Echo requests waiting for their reply, by sequence number.
    pings: HashMap<u16, Ping>,
    ping_socket: Option<SocketHandle>,
    next_ping_seq: u16,
    next_id: u32,
    next_port: u16,
}

impl Worker {
    fn new(
        config: WgConfig,
        tunn: Tunn,
        udp: UdpSocket,
        endpoint: SocketAddr,
        cmd_rx: mpsc::UnboundedReceiver<Cmd>,
        cmd_tx: mpsc::UnboundedSender<Cmd>,
        stats: Arc<Mutex<TunnelStats>>,
        running: Arc<AtomicBool>,
        network_up: Arc<AtomicBool>,
    ) -> Self {
        let mut device = VirtDevice { rx: VecDeque::new(), tx: VecDeque::new(), mtu: device_mtu(&config) };
        let mut iface = Interface::new(IfaceConfig::new(HardwareAddress::Ip), &mut device, SmolInstant::now());
        iface.update_ip_addrs(|addrs| {
            for a in &config.addresses {
                let _ = addrs.push(IpCidr::new(IpAddress::from(a.addr), a.prefix));
            }
        });
        // Everything not on-link goes to the peer; with an IP medium the gateway is nominal.
        let _ = iface.routes_mut().add_default_ipv4_route(Ipv4Addr::new(0, 0, 0, 1));
        let _ = iface.routes_mut().add_default_ipv6_route(Ipv6Addr::new(0, 0, 0, 0, 0, 0, 0, 1));
        Self {
            config,
            tunn,
            udp,
            endpoint,
            cmd_rx,
            cmd_tx,
            stats,
            running,
            network_up,
            device,
            iface,
            sockets: SocketSet::new(vec![]),
            conns: HashMap::new(),
            listeners: Vec::new(),
            dns: Vec::new(),
            udps: HashMap::new(),
            pings: HashMap::new(),
            ping_socket: None,
            next_ping_seq: 1,
            next_id: 1,
            next_port: 20000 + (rand::random::<u16>() % 20000),
        }
    }

    async fn run(mut self) {
        let mut buf = vec![0u8; MAX_PACKET];
        let mut out = vec![0u8; MAX_PACKET];

        // Kick off the handshake right away.
        if let TunnResult::WriteToNetwork(p) = self.tunn.format_handshake_initiation(&mut out, false) {
            let _ = self.udp.send_to(p, self.endpoint).await;
        }
        let mut next_tick = tokio::time::Instant::now() + TIMER_TICK;

        loop {
            // --- drive the stack ------------------------------------------
            self.poll_stack().await;

            let now = tokio::time::Instant::now();
            let poll_wait = self
                .iface
                .poll_delay(SmolInstant::now(), &self.sockets)
                .map(|d| Duration::from_micros(d.total_micros()))
                .unwrap_or(Duration::from_secs(3600));
            let wait = poll_wait.min(next_tick.saturating_duration_since(now));

            tokio::select! {
                r = self.udp.recv_from(&mut buf) => {
                    match r {
                        Ok((n, from)) => self.handle_datagram(&buf[..n], from, &mut out).await,
                        Err(e) => {
                            log::warn!("wg: udp recv failed: {e}");
                            tokio::time::sleep(Duration::from_millis(50)).await;
                        }
                    }
                }
                cmd = self.cmd_rx.recv() => {
                    match cmd {
                        Some(Cmd::Stop) | None => break,
                        Some(c) => self.handle_cmd(c),
                    }
                }
                _ = tokio::time::sleep(wait) => {}
            }

            if tokio::time::Instant::now() >= next_tick {
                next_tick = tokio::time::Instant::now() + TIMER_TICK;
                self.tick(&mut out).await;
            }
        }

        self.running.store(false, Ordering::Relaxed);
        self.stats.lock().unwrap().running = false;
        // Wake everyone waiting on us.
        for (_, c) in self.conns.drain() {
            if let Some((_, reply, _)) = c.connecting {
                let _ = reply.send(Err(WgError::Stopped));
            }
        }
        for q in self.dns.drain(..) {
            let _ = q.reply.send(Err(WgError::Stopped));
        }
        log::info!("wg: tunnel to {} stopped", self.endpoint);
    }

    async fn tick(&mut self, out: &mut [u8]) {
        // No network: do not spend handshakes on a link that is not there. The
        // stats are still refreshed so the UI keeps telling the truth.
        if !self.network_up.load(Ordering::Relaxed) {
            let (since, tx, rx, _, _) = self.tunn.stats();
            let mut s = self.stats.lock().unwrap();
            s.last_handshake_secs = since.map(|d| d.as_secs());
            s.tx_bytes = tx as u64;
            s.rx_bytes = rx as u64;
            return;
        }
        match self.tunn.update_timers(out) {
            TunnResult::WriteToNetwork(p) => {
                let _ = self.udp.send_to(p, self.endpoint).await;
            }
            TunnResult::Err(boringtun::noise::errors::WireGuardError::ConnectionExpired) => {
                log::warn!("wg: handshake expired, re-initiating");
                if let TunnResult::WriteToNetwork(p) = self.tunn.format_handshake_initiation(out, true) {
                    let _ = self.udp.send_to(p, self.endpoint).await;
                }
            }
            TunnResult::Err(e) => log::debug!("wg: timer: {e:?}"),
            _ => {}
        }
        let (since, tx, rx, _, _) = self.tunn.stats();
        let mut s = self.stats.lock().unwrap();
        s.last_handshake_secs = since.map(|d| d.as_secs());
        s.tx_bytes = tx as u64;
        s.rx_bytes = rx as u64;
        s.endpoint = Some(self.endpoint);

        // Expire connects and DNS queries.
        let now = tokio::time::Instant::now();
        let expired: Vec<u32> = self
            .conns
            .iter()
            .filter(|(_, c)| c.connecting.as_ref().map(|(_, _, d)| now >= *d).unwrap_or(false))
            .map(|(id, _)| *id)
            .collect();
        for id in expired {
            if let Some(mut c) = self.conns.remove(&id) {
                if let Some((_, reply, _)) = c.connecting.take() {
                    let _ = reply.send(Err(WgError::Other("connection through the tunnel timed out (no handshake or host unreachable)".into())));
                }
                self.sockets.get_mut::<tcp::Socket>(c.handle).abort();
                self.sockets.remove(c.handle);
            }
        }
        let mut i = 0;
        while i < self.dns.len() {
            if now >= self.dns[i].deadline {
                let q = self.dns.remove(i);
                self.sockets.remove(q.handle);
                let _ = q.reply.send(Err(WgError::Other(format!("DNS lookup of {} timed out", q.name))));
            } else {
                i += 1;
            }
        }
    }

    async fn handle_datagram(&mut self, data: &[u8], from: SocketAddr, out: &mut [u8]) {
        let mut res = self.tunn.decapsulate(Some(from.ip()), data, out);
        loop {
            match res {
                TunnResult::WriteToNetwork(p) => {
                    // A valid handshake/cookie packet from a new address: follow it (endpoint roaming).
                    self.roam(from);
                    let _ = self.udp.send_to(p, self.endpoint).await;
                    // The peer may have more queued (e.g. after a handshake completes).
                    res = self.tunn.decapsulate(None, &[], out);
                    continue;
                }
                TunnResult::WriteToTunnelV4(p, _) | TunnResult::WriteToTunnelV6(p, _) => {
                    self.roam(from);
                    self.device.rx.push_back(p.to_vec());
                }
                TunnResult::Err(e) => log::debug!("wg: decapsulate: {e:?}"),
                TunnResult::Done => self.roam(from), // e.g. a keepalive that authenticated fine
            }
            break;
        }
    }

    fn roam(&mut self, from: SocketAddr) {
        if from != self.endpoint {
            log::info!("wg: endpoint moved to {from}");
            self.endpoint = from;
        }
    }

    /// Poll smoltcp, move socket data, and flush outgoing IP packets through WireGuard.
    async fn poll_stack(&mut self) {
        let _ = self.iface.poll(SmolInstant::now(), &mut self.device, &mut self.sockets);

        // Established / failed connects, data in both directions, closes.
        let mut finished = Vec::new();
        let mut established = Vec::new();
        for (id, conn) in self.conns.iter_mut() {
            let sock = self.sockets.get_mut::<tcp::Socket>(conn.handle);
            if conn.connecting.is_some() {
                match sock.state() {
                    tcp::State::Established => established.push(*id),
                    tcp::State::Closed | tcp::State::TimeWait => {
                        if let Some((_, reply, _)) = conn.connecting.take() {
                            let _ = reply.send(Err(WgError::Other("connection refused inside the tunnel".into())));
                        }
                        finished.push(*id);
                    }
                    _ => {}
                }
                continue;
            }
            // Outgoing data.
            while sock.can_send() {
                let Some(front) = conn.tx_queue.front_mut() else { break };
                match sock.send_slice(front) {
                    Ok(n) if n == front.len() => {
                        conn.tx_queue.pop_front();
                    }
                    Ok(n) => {
                        front.drain(..n);
                        break;
                    }
                    Err(_) => {
                        conn.tx_queue.clear();
                        break;
                    }
                }
            }
            if conn.shutdown && conn.tx_queue.is_empty() && sock.state() != tcp::State::Closed {
                sock.close();
                conn.shutdown = false;
            }
            // Incoming data.
            if let Some(tx) = &conn.to_reader {
                while sock.can_recv() {
                    let chunk = sock.recv(|b| (b.len(), b.to_vec())).unwrap_or_default();
                    if chunk.is_empty() || tx.send(chunk).is_err() {
                        break;
                    }
                }
                if !sock.may_recv() {
                    conn.to_reader = None; // EOF for the reader
                }
            }
            if sock.state() == tcp::State::Closed || (conn.closed_by_local && !sock.is_open()) {
                finished.push(*id);
            }
        }
        for id in established {
            if let Some(conn) = self.conns.get_mut(&id) {
                if let Some((stream, reply, _)) = conn.connecting.take() {
                    if reply.send(Ok(stream)).is_err() {
                        finished.push(id);
                    }
                }
            }
        }
        for id in finished {
            if let Some(conn) = self.conns.remove(&id) {
                self.sockets.remove(conn.handle);
            }
        }

        // Listeners: a listening socket that got a connection becomes a normal one.
        let mut i = 0;
        while i < self.listeners.len() {
            let slot = &self.listeners[i];
            let sock = self.sockets.get_mut::<tcp::Socket>(slot.handle);
            if sock.is_active() {
                let port = slot.port;
                let accepted = slot.accepted.clone();
                let handle = slot.handle;
                self.listeners.remove(i);
                let id = self.alloc_id();
                let (to_reader, incoming) = mpsc::unbounded_channel();
                let stream = VirtualStream { id, cmd: self.cmd_tx.clone(), incoming, leftover: Vec::new(), leftover_pos: 0 };
                self.conns.insert(id, Conn { handle, tx_queue: VecDeque::new(), to_reader: Some(to_reader), connecting: None, shutdown: false, closed_by_local: false });
                if accepted.send(stream).is_ok() {
                    // Keep listening.
                    if let Ok(h) = self.new_listen_socket(port) {
                        self.listeners.push(ListenSlot { port, handle: h, accepted });
                    }
                }
            } else {
                i += 1;
            }
        }

        // Datagram sockets the caller holds.
        let mut dead: Vec<u32> = Vec::new();
        // Echo replies, and pings that ran out of time.
        if let Some(handle) = self.ping_socket {
            let sock = self.sockets.get_mut::<icmp::Socket>(handle);
            while let Ok((payload, _from)) = sock.recv() {
                let packet = Icmpv4Packet::new_unchecked(payload);
                if let Ok(Icmpv4Repr::EchoReply { ident, seq_no, .. }) =
                    Icmpv4Repr::parse(&packet, &ChecksumCapabilities::default())
                {
                    if ident != PING_IDENT {
                        continue;
                    }
                    if let Some(p) = self.pings.remove(&seq_no) {
                        let _ = p.reply.send(Ok(Some(p.sent.elapsed().as_millis() as u32)));
                    }
                }
            }
        }
        if !self.pings.is_empty() {
            let now = std::time::Instant::now();
            let expired: Vec<u16> = self.pings.iter().filter(|(_, p)| now >= p.deadline).map(|(seq, _)| *seq).collect();
            for seq in expired {
                if let Some(p) = self.pings.remove(&seq) {
                    let _ = p.reply.send(Ok(None));
                }
            }
        }

        for (id, u) in self.udps.iter_mut() {
            let sock = self.sockets.get_mut::<udp::Socket>(u.handle);
            while let Some((data, to)) = u.tx_queue.front() {
                match sock.send_slice(data, *to) {
                    Ok(()) => {
                        u.tx_queue.pop_front();
                    }
                    // Buffer full: keep it queued and try again on the next poll.
                    Err(_) => break,
                }
            }
            while let Ok((payload, meta)) = sock.recv() {
                // A connected socket ignores anything from elsewhere.
                if let Some(peer) = u.peer {
                    if meta.endpoint.addr != peer.0 || meta.endpoint.port != peer.1 {
                        continue;
                    }
                }
                let from = SocketAddr::new(to_ip_addr(meta.endpoint.addr), meta.endpoint.port);
                if u.to_reader.send((payload.to_vec(), from)).is_err() {
                    dead.push(*id);
                    break;
                }
            }
        }
        for id in dead {
            if let Some(u) = self.udps.remove(&id) {
                self.sockets.remove(u.handle);
            }
        }

        // DNS answers.
        let mut j = 0;
        while j < self.dns.len() {
            let q = &self.dns[j];
            let sock = self.sockets.get_mut::<udp::Socket>(q.handle);
            let mut done: Option<Result<IpAddr, WgError>> = None;
            while let Ok((payload, _)) = sock.recv() {
                if let Some(answers) = dns::parse_answers(payload, q.id) {
                    done = Some(match answers.into_iter().next() {
                        Some(ip) => Ok(ip),
                        None => Err(WgError::Other(format!("no address for {}", q.name))),
                    });
                    break;
                }
            }
            if let Some(result) = done {
                let q = self.dns.remove(j);
                self.sockets.remove(q.handle);
                // No A record: try AAAA once before giving up.
                if result.is_err() && q.qtype == dns::TYPE_A {
                    self.start_dns(q.name, dns::TYPE_AAAA, q.reply);
                } else {
                    let _ = q.reply.send(result);
                }
            } else {
                j += 1;
            }
        }

        // Ship whatever smoltcp produced.
        let mut out = vec![0u8; MAX_PACKET];
        while let Some(packet) = self.device.tx.pop_front() {
            match self.tunn.encapsulate(&packet, &mut out) {
                TunnResult::WriteToNetwork(p) => {
                    let _ = self.udp.send_to(p, self.endpoint).await;
                }
                TunnResult::Err(e) => log::debug!("wg: encapsulate: {e:?}"),
                _ => {}
            }
        }
    }

    fn handle_cmd(&mut self, cmd: Cmd) {
        match cmd {
            Cmd::Connect { addr, port, reply } => {
                let Some(source) = self.config.source_for(&addr) else {
                    let _ = reply.send(Err(WgError::Other(format!("tunnel has no {} address", if addr.is_ipv4() { "IPv4" } else { "IPv6" }))));
                    return;
                };
                if !self.config.routes(&addr) {
                    let _ = reply.send(Err(WgError::Other(format!("{addr} is not within the peer's AllowedIPs"))));
                    return;
                }
                let mut sock = tcp::Socket::new(tcp::SocketBuffer::new(vec![0; TCP_BUFFER]), tcp::SocketBuffer::new(vec![0; TCP_BUFFER]));
                sock.set_nagle_enabled(false);
                let local_port = self.alloc_port();
                if let Err(e) = sock.connect(self.iface.context(), (IpAddress::from(addr), port), (IpAddress::from(source), local_port)) {
                    let _ = reply.send(Err(WgError::Other(format!("connect: {e:?}"))));
                    return;
                }
                let handle = self.sockets.add(sock);
                let id = self.alloc_id();
                let (to_reader, incoming) = mpsc::unbounded_channel();
                let stream = VirtualStream { id, cmd: self.cmd_tx.clone(), incoming, leftover: Vec::new(), leftover_pos: 0 };
                self.conns.insert(
                    id,
                    Conn {
                        handle,
                        tx_queue: VecDeque::new(),
                        to_reader: Some(to_reader),
                        connecting: Some((stream, reply, tokio::time::Instant::now() + CONNECT_TIMEOUT)),
                        shutdown: false,
                        closed_by_local: false,
                    },
                );
            }
            Cmd::Listen { port, reply } => match self.new_listen_socket(port) {
                Ok(handle) => {
                    let (tx, rx) = mpsc::unbounded_channel();
                    self.listeners.push(ListenSlot { port, handle, accepted: tx });
                    let _ = reply.send(Ok(Listener { accepted: rx }));
                }
                Err(e) => {
                    let _ = reply.send(Err(e));
                }
            },
            Cmd::UdpBind { addr, port, reply } => {
                let Some(source) = self.config.source_for(&addr) else {
                    let _ = reply.send(Err(WgError::Other(format!("tunnel has no {} address", if addr.is_ipv4() { "IPv4" } else { "IPv6" }))));
                    return;
                };
                if !self.config.routes(&addr) {
                    let _ = reply.send(Err(WgError::Other(format!("{addr} is not within the peer's AllowedIPs"))));
                    return;
                }
                let local_port = self.alloc_port();
                match self.new_udp_socket(IpAddress::from(source), local_port) {
                    Ok(handle) => {
                        let id = self.alloc_id();
                        let (to_reader, incoming) = mpsc::unbounded_channel();
                        let peer = (IpAddress::from(addr), port);
                        self.udps.insert(id, UdpConn { handle, peer: Some(peer), to_reader, tx_queue: VecDeque::new() });
                        let _ = reply.send(Ok(VirtualDatagram {
                            id,
                            peer: SocketAddr::new(addr, port),
                            max_payload: self.max_udp_payload(addr.is_ipv4()),
                            cmd: self.cmd_tx.clone(),
                            incoming,
                        }));
                    }
                    Err(e) => {
                        let _ = reply.send(Err(e));
                    }
                }
            }
            Cmd::Ping { addr, timeout, reply } => {
                if !self.config.routes(&addr) {
                    let _ = reply.send(Err(WgError::Other(format!("{addr} is not within the peer's AllowedIPs"))));
                    return;
                }
                // One ICMP socket serves every ping; replies are matched by the
                // sequence number, the way ping itself does it.
                if self.ping_socket.is_none() {
                    let rx = icmp::PacketBuffer::new(vec![icmp::PacketMetadata::EMPTY; 8], vec![0; 2048]);
                    let tx = icmp::PacketBuffer::new(vec![icmp::PacketMetadata::EMPTY; 8], vec![0; 2048]);
                    let mut sock = icmp::Socket::new(rx, tx);
                    if let Err(e) = sock.bind(icmp::Endpoint::Ident(PING_IDENT)) {
                        let _ = reply.send(Err(WgError::Other(format!("icmp: {e}"))));
                        return;
                    }
                    self.ping_socket = Some(self.sockets.add(sock));
                }
                let handle = self.ping_socket.expect("just created");
                let seq = self.next_ping_seq;
                self.next_ping_seq = self.next_ping_seq.wrapping_add(1).max(1);
                let sock = self.sockets.get_mut::<icmp::Socket>(handle);
                // A fixed payload: nothing here needs to carry data, and 16 bytes
                // is enough for the reply to be recognizable.
                let payload = [0x61u8; 16];
                let repr = Icmpv4Repr::EchoRequest { ident: PING_IDENT, seq_no: seq, data: &payload };
                let dest = IpAddress::from(addr);
                let result = sock.send_with(repr.buffer_len(), dest, |buf| {
                    let mut packet = Icmpv4Packet::new_unchecked(buf);
                    repr.emit(&mut packet, &ChecksumCapabilities::default());
                    repr.buffer_len()
                });
                match result {
                    Ok(_) => {
                        let now = std::time::Instant::now();
                        self.pings.insert(seq, Ping { sent: now, deadline: now + timeout, reply });
                    }
                    Err(e) => {
                        let _ = reply.send(Err(WgError::Other(format!("icmp send: {e}"))));
                    }
                }
            }
            Cmd::UdpListen { port, reply } => {
                // Any of our addresses will do for an unconnected socket.
                let Some(source) = self.config.addresses.first().map(|a| a.addr) else {
                    let _ = reply.send(Err(WgError::Other("tunnel has no address".into())));
                    return;
                };
                match self.new_udp_socket(IpAddress::from(source), port) {
                    Ok(handle) => {
                        let id = self.alloc_id();
                        let (to_reader, incoming) = mpsc::unbounded_channel();
                        self.udps.insert(id, UdpConn { handle, peer: None, to_reader, tx_queue: VecDeque::new() });
                        let _ = reply.send(Ok(UdpListener {
                            id,
                            max_payload: self.max_udp_payload(source.is_ipv4()),
                            cmd: self.cmd_tx.clone(),
                            incoming,
                        }));
                    }
                    Err(e) => {
                        let _ = reply.send(Err(e));
                    }
                }
            }
            Cmd::UdpSend { id, data, to } => {
                if let Some(u) = self.udps.get_mut(&id) {
                    u.tx_queue.push_back((data, (IpAddress::from(to.ip()), to.port())));
                }
            }
            Cmd::UdpClose { id } => {
                if let Some(u) = self.udps.remove(&id) {
                    self.sockets.remove(u.handle);
                }
            }
            Cmd::Resolve { name, reply } => self.start_dns(name, dns::TYPE_A, reply),
            Cmd::Send { id, data } => {
                if let Some(c) = self.conns.get_mut(&id) {
                    c.tx_queue.push_back(data);
                }
            }
            Cmd::Shutdown { id } => {
                if let Some(c) = self.conns.get_mut(&id) {
                    c.shutdown = true;
                }
            }
            Cmd::Close { id } => {
                if let Some(c) = self.conns.get_mut(&id) {
                    c.closed_by_local = true;
                    c.to_reader = None;
                    let sock = self.sockets.get_mut::<tcp::Socket>(c.handle);
                    if c.tx_queue.is_empty() {
                        sock.close();
                    } else {
                        c.shutdown = true;
                    }
                }
            }
            Cmd::Stop => {}
        }
    }

    fn start_dns(&mut self, name: String, qtype: u16, reply: oneshot::Sender<Result<IpAddr, WgError>>) {
        let Some(server) = self.config.dns.first().copied() else {
            let _ = reply.send(Err(WgError::Other("no DNS server in tunnel config".into())));
            return;
        };
        let Some(source) = self.config.source_for(&server) else {
            let _ = reply.send(Err(WgError::Other("tunnel has no address matching the DNS server family".into())));
            return;
        };
        let rx = udp::PacketBuffer::new(vec![udp::PacketMetadata::EMPTY; 4], vec![0; 4096]);
        let tx = udp::PacketBuffer::new(vec![udp::PacketMetadata::EMPTY; 4], vec![0; 1024]);
        let mut sock = udp::Socket::new(rx, tx);
        let local_port = self.alloc_port();
        if sock.bind((IpAddress::from(source), local_port)).is_err() {
            let _ = reply.send(Err(WgError::Other("cannot bind DNS socket".into())));
            return;
        }
        let id: u16 = rand::random();
        let query = dns::build_query(id, &name, qtype);
        if sock.send_slice(&query, (IpAddress::from(server), 53)).is_err() {
            let _ = reply.send(Err(WgError::Other("cannot send DNS query".into())));
            return;
        }
        let handle = self.sockets.add(sock);
        self.dns.push(DnsQuery { handle, id, name, qtype, reply, deadline: tokio::time::Instant::now() + DNS_TIMEOUT });
    }

    /// The tunnel MTU less the IP and UDP headers. smoltcp does not fragment,
    /// so this is a hard ceiling on one datagram.
    fn max_udp_payload(&self, ipv4: bool) -> usize {
        let headers = if ipv4 { 20 + 8 } else { 40 + 8 };
        device_mtu(&self.config).saturating_sub(headers)
    }

    fn new_udp_socket(&mut self, source: IpAddress, port: u16) -> Result<SocketHandle, WgError> {
        // Room for a burst: one screen update can arrive as several datagrams.
        let rx = udp::PacketBuffer::new(vec![udp::PacketMetadata::EMPTY; 16], vec![0; 64 * 1024]);
        let tx = udp::PacketBuffer::new(vec![udp::PacketMetadata::EMPTY; 16], vec![0; 64 * 1024]);
        let mut sock = udp::Socket::new(rx, tx);
        sock.bind((source, port)).map_err(|e| WgError::Other(format!("udp bind: {e:?}")))?;
        Ok(self.sockets.add(sock))
    }

    fn new_listen_socket(&mut self, port: u16) -> Result<SocketHandle, WgError> {
        let mut sock = tcp::Socket::new(tcp::SocketBuffer::new(vec![0; TCP_BUFFER]), tcp::SocketBuffer::new(vec![0; TCP_BUFFER]));
        sock.listen(port).map_err(|e| WgError::Other(format!("listen: {e:?}")))?;
        Ok(self.sockets.add(sock))
    }

    fn alloc_id(&mut self) -> u32 {
        let id = self.next_id;
        self.next_id = self.next_id.wrapping_add(1).max(1);
        id
    }

    fn alloc_port(&mut self) -> u16 {
        let p = self.next_port;
        self.next_port = if p >= 60000 { 20000 } else { p + 1 };
        p
    }
}
