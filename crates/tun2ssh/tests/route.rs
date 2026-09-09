//! The router, driven the way Android drives it: raw IP packets over a
//! descriptor, with a stack at the other end standing in for the phone's apps.

use std::collections::VecDeque;
use std::io;
use std::os::fd::RawFd;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use smoltcp::iface::{Config as IfaceConfig, Interface, SocketSet};
use smoltcp::phy::{Device, DeviceCapabilities, Medium};
use smoltcp::socket::tcp;
use smoltcp::time::Instant as SmolInstant;
use smoltcp::wire::{HardwareAddress, IpAddress, IpCidr};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tun2ssh::{Config, Dialer, Router, Stream};

/// A dialer that hands back one end of an in-memory pipe and records what was asked for.
struct FakeDialer {
    asked: Arc<Mutex<Vec<(String, u16)>>>,
    /// The far end of every pipe handed out, for the test to talk on.
    given: Arc<Mutex<Vec<tokio::io::DuplexStream>>>,
    refuse: AtomicBool,
}

impl Dialer for FakeDialer {
    fn dial(
        &self,
        host: String,
        port: u16,
    ) -> std::pin::Pin<Box<dyn std::future::Future<Output = io::Result<Box<dyn Stream>>> + Send>> {
        self.asked.lock().unwrap().push((host, port));
        if self.refuse.load(Ordering::Relaxed) {
            return Box::pin(async { Err(io::Error::new(io::ErrorKind::ConnectionRefused, "refused")) });
        }
        let (mine, theirs) = tokio::io::duplex(64 * 1024);
        self.given.lock().unwrap().push(theirs);
        Box::pin(async move { Ok(Box::new(mine) as Box<dyn Stream>) })
    }
}

/// The phone's side of the tun: a second stack, on the other end of a socket pair.
struct PairDevice {
    fd: RawFd,
    tx: VecDeque<Vec<u8>>,
}

struct PRx(Vec<u8>);
struct PTx<'a>(&'a mut VecDeque<Vec<u8>>);

impl smoltcp::phy::RxToken for PRx {
    fn consume<R, F: FnOnce(&[u8]) -> R>(self, f: F) -> R {
        f(&self.0)
    }
}

impl<'a> smoltcp::phy::TxToken for PTx<'a> {
    fn consume<R, F: FnOnce(&mut [u8]) -> R>(self, len: usize, f: F) -> R {
        let mut buf = vec![0u8; len];
        let r = f(&mut buf);
        self.0.push_back(buf);
        r
    }
}

impl Device for PairDevice {
    type RxToken<'a> = PRx where Self: 'a;
    type TxToken<'a> = PTx<'a> where Self: 'a;

    fn receive(&mut self, _ts: SmolInstant) -> Option<(Self::RxToken<'_>, Self::TxToken<'_>)> {
        let mut buf = vec![0u8; 4096];
        let n = unsafe { libc::recv(self.fd, buf.as_mut_ptr() as *mut libc::c_void, buf.len(), libc::MSG_DONTWAIT) };
        if n <= 0 {
            return None;
        }
        buf.truncate(n as usize);
        Some((PRx(buf), PTx(&mut self.tx)))
    }

    fn transmit(&mut self, _ts: SmolInstant) -> Option<Self::TxToken<'_>> {
        Some(PTx(&mut self.tx))
    }

    fn capabilities(&self) -> DeviceCapabilities {
        let mut c = DeviceCapabilities::default();
        c.medium = Medium::Ip;
        c.max_transmission_unit = 1400;
        c
    }
}

impl PairDevice {
    fn flush(&mut self) {
        while let Some(p) = self.tx.pop_front() {
            unsafe { libc::send(self.fd, p.as_ptr() as *const libc::c_void, p.len(), libc::MSG_DONTWAIT) };
        }
    }
}

fn socket_pair() -> (RawFd, RawFd) {
    let mut fds = [0i32; 2];
    // Datagrams, so one write is one packet — exactly how a tun behaves.
    let r = unsafe { libc::socketpair(libc::AF_UNIX, libc::SOCK_DGRAM, 0, fds.as_mut_ptr()) };
    assert_eq!(r, 0, "socketpair failed");
    (fds[0], fds[1])
}

struct Phone {
    device: PairDevice,
    iface: Interface,
    sockets: SocketSet<'static>,
}

impl Phone {
    fn new(fd: RawFd) -> Phone {
        let mut device = PairDevice { fd, tx: VecDeque::new() };
        let mut iface = Interface::new(IfaceConfig::new(HardwareAddress::Ip), &mut device, SmolInstant::now());
        iface.update_ip_addrs(|a| {
            let _ = a.push(IpCidr::new(IpAddress::v4(10, 60, 0, 9), 24));
        });
        let _ = iface.routes_mut().add_default_ipv4_route(std::net::Ipv4Addr::new(10, 60, 0, 1));
        Phone { device, iface, sockets: SocketSet::new(vec![]) }
    }

    fn poll(&mut self) {
        self.iface.poll(SmolInstant::now(), &mut self.device, &mut self.sockets);
        self.device.flush();
    }
}

/// Turn both stacks until [done] says so, or give up.
async fn spin(phone: &mut Phone, done: impl Fn(&mut Phone) -> bool) -> bool {
    let deadline = Instant::now() + Duration::from_secs(5);
    while Instant::now() < deadline {
        phone.poll();
        if done(phone) {
            return true;
        }
        tokio::time::sleep(Duration::from_millis(5)).await;
    }
    false
}

#[tokio::test]
async fn a_connection_from_the_phone_becomes_a_channel_on_the_far_side() {
    let _ = env_logger::builder().is_test(true).try_init();
    let (router_fd, phone_fd) = socket_pair();
    let dialer = Arc::new(FakeDialer {
        asked: Arc::new(Mutex::new(Vec::new())),
        given: Arc::new(Mutex::new(Vec::new())),
        refuse: AtomicBool::new(false),
    });
    let router = Router::start(
        router_fd,
        Config {
            mtu: 1400,
            resolver: std::net::Ipv4Addr::new(1, 1, 1, 1),
            dns_address: std::net::Ipv4Addr::new(10, 60, 0, 1),
        },
        dialer.clone(),
    ).unwrap();

    let mut phone = Phone::new(phone_fd);
    let handle = {
        let sock = tcp::Socket::new(
            tcp::SocketBuffer::new(vec![0u8; 8192]),
            tcp::SocketBuffer::new(vec![0u8; 8192]),
        );
        phone.sockets.add(sock)
    };
    // An ordinary outbound connection to somewhere on the internet.
    {
        let sock = phone.sockets.get_mut::<tcp::Socket>(handle);
        sock.connect(phone.iface.context(), (IpAddress::v4(93, 184, 216, 34), 80), 40000).unwrap();
    }

    let connected = spin(&mut phone, |p| p.sockets.get_mut::<tcp::Socket>(handle).may_send()).await;
    assert!(connected, "the router never completed the handshake");
    assert_eq!(dialer.asked.lock().unwrap().as_slice(), &[("93.184.216.34".to_string(), 80)]);

    // What the phone writes must come out of the channel.
    phone.sockets.get_mut::<tcp::Socket>(handle).send_slice(b"GET / HTTP/1.0\r\n\r\n").unwrap();
    let mut far = dialer.given.lock().unwrap().pop().unwrap();
    let mut buf = vec![0u8; 64];
    let read = tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            tokio::select! {
                r = far.read(&mut buf) => return r,
                _ = tokio::time::sleep(Duration::from_millis(5)) => phone.poll(),
            }
        }
    })
    .await
    .expect("timed out waiting for the request")
    .unwrap();
    assert_eq!(&buf[..read], b"GET / HTTP/1.0\r\n\r\n");

    // And what the channel answers must reach the phone.
    far.write_all(b"HTTP/1.0 204 No Content\r\n\r\n").await.unwrap();
    let got = spin(&mut phone, |p| p.sockets.get_mut::<tcp::Socket>(handle).can_recv()).await;
    assert!(got, "the answer never came back");
    let answer = phone
        .sockets
        .get_mut::<tcp::Socket>(handle)
        .recv(|b| (b.len(), b.to_vec()))
        .unwrap();
    assert_eq!(answer, b"HTTP/1.0 204 No Content\r\n\r\n");

    let stats = router.stats();
    assert_eq!(stats.opened, 1);
    assert!(stats.sent >= 18 && stats.received >= 27, "stats not counted: {stats:?}");
}

#[tokio::test]
async fn a_refused_connection_is_refused_on_the_phone_too() {
    let _ = env_logger::builder().is_test(true).try_init();
    let (router_fd, phone_fd) = socket_pair();
    let dialer = Arc::new(FakeDialer {
        asked: Arc::new(Mutex::new(Vec::new())),
        given: Arc::new(Mutex::new(Vec::new())),
        refuse: AtomicBool::new(true),
    });
    let _router = Router::start(
        router_fd,
        Config {
            mtu: 1400,
            resolver: std::net::Ipv4Addr::new(1, 1, 1, 1),
            dns_address: std::net::Ipv4Addr::new(10, 60, 0, 1),
        },
        dialer.clone(),
    ).unwrap();

    let mut phone = Phone::new(phone_fd);
    let handle = phone.sockets.add(tcp::Socket::new(
        tcp::SocketBuffer::new(vec![0u8; 4096]),
        tcp::SocketBuffer::new(vec![0u8; 4096]),
    ));
    {
        let sock = phone.sockets.get_mut::<tcp::Socket>(handle);
        sock.connect(phone.iface.context(), (IpAddress::v4(10, 1, 2, 3), 22), 40001).unwrap();
    }
    // A reset, not a hang: the application should hear "no" straight away.
    let closed = spin(&mut phone, |p| !p.sockets.get_mut::<tcp::Socket>(handle).is_active()).await;
    assert!(closed, "a refused dial left the connection hanging");
    assert_eq!(dialer.asked.lock().unwrap().len(), 1);
}

#[tokio::test]
async fn a_name_lookup_goes_to_the_servers_resolver_over_tcp() {
    let _ = env_logger::builder().is_test(true).try_init();
    let (router_fd, phone_fd) = socket_pair();
    let dialer = Arc::new(FakeDialer {
        asked: Arc::new(Mutex::new(Vec::new())),
        given: Arc::new(Mutex::new(Vec::new())),
        refuse: AtomicBool::new(false),
    });
    let _router = Router::start(
        router_fd,
        Config {
            mtu: 1400,
            resolver: std::net::Ipv4Addr::new(9, 9, 9, 9),
            dns_address: std::net::Ipv4Addr::new(10, 60, 0, 1),
        },
        dialer.clone(),
    ).unwrap();

    let mut phone = Phone::new(phone_fd);
    let udp_handle = phone.sockets.add(smoltcp::socket::udp::Socket::new(
        smoltcp::socket::udp::PacketBuffer::new(
            vec![smoltcp::socket::udp::PacketMetadata::EMPTY; 4],
            vec![0u8; 4096],
        ),
        smoltcp::socket::udp::PacketBuffer::new(
            vec![smoltcp::socket::udp::PacketMetadata::EMPTY; 4],
            vec![0u8; 4096],
        ),
    ));
    {
        let sock = phone.sockets.get_mut::<smoltcp::socket::udp::Socket>(udp_handle);
        sock.bind(40002).unwrap();
        // A query for "example.com", as a resolver would send it.
        let query = [
            0x12u8, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x07, b'e', b'x', b'a', b'm',
            b'p', b'l', b'e', 0x03, b'c', b'o', b'm', 0x00, 0x00, 0x01, 0x00, 0x01,
        ];
        sock.send_slice(&query, (IpAddress::v4(1, 1, 1, 1), 53)).unwrap();
    }

    let dialed = spin(&mut phone, |_| !dialer.asked.lock().unwrap().is_empty()).await;
    assert!(dialed, "the query never left for the resolver");
    // The phone asked 1.1.1.1; the tunnel uses the resolver it was given, on TCP.
    assert_eq!(dialer.asked.lock().unwrap()[0], ("9.9.9.9".to_string(), 53));

    let mut far = dialer.given.lock().unwrap().pop().unwrap();
    let mut framed = vec![0u8; 2];
    tokio::time::timeout(Duration::from_secs(5), far.read_exact(&mut framed)).await.unwrap().unwrap();
    let len = u16::from_be_bytes([framed[0], framed[1]]) as usize;
    let mut body = vec![0u8; len];
    far.read_exact(&mut body).await.unwrap();
    assert_eq!(&body[..2], &[0x12, 0x34], "the query was mangled on the way");

    // Answer it, and the phone should get its datagram back.
    let mut answer = body.clone();
    answer[2] = 0x81;
    answer[3] = 0x80;
    far.write_all(&(answer.len() as u16).to_be_bytes()).await.unwrap();
    far.write_all(&answer).await.unwrap();
    let got = spin(&mut phone, |p| p.sockets.get_mut::<smoltcp::socket::udp::Socket>(udp_handle).can_recv()).await;
    assert!(got, "the answer never came back to the phone");
}
