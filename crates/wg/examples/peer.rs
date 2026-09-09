//! Test peer: a userspace WireGuard "server" that bridges TCP port 22 inside
//! the tunnel to a real address. Usage:
//!   peer <listen-udp-port> <our-private-key-b64> <client-public-key-b64> <bridge-to host:port>
//! Prints its own public key; the client config must use Endpoint = <host>:<listen-udp-port>.
use tokio::io::copy_bidirectional;
use wg_tunnel::config::encode_key;
use wg_tunnel::{Tunnel, WgConfig};

#[tokio::main]
async fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 5 {
        eprintln!("usage: peer <udp-port> <private-key> <client-public-key> <bridge host:port>");
        std::process::exit(2);
    }
    let cfg = format!(
        "[Interface]\nPrivateKey = {}\nAddress = 10.77.0.1/24\n[Peer]\nPublicKey = {}\nAllowedIPs = 10.77.0.0/24\nEndpoint = 127.0.0.1:9\n",
        args[2], args[3]
    );
    let config = WgConfig::parse(&cfg).expect("config");
    let public = boringtun::x25519::PublicKey::from(&boringtun::x25519::StaticSecret::from(config.private_key));
    println!("peer public key: {}", encode_key(public.as_bytes()));
    // Bind on the requested port instead of an ephemeral one.
    std::env::set_var("WG_PEER_BIND", &args[1]);
    let tunnel = Tunnel::start_bound(config, format!("0.0.0.0:{}", args[1]).parse().unwrap()).await.expect("start");
    println!("listening on udp {} and tunnel 10.77.0.1:22 -> {}", args[1], args[4]);
    // UDP as well as TCP, so a Mosh session inside the tunnel can be tested:
    // mosh-server picks a port in 60000-61000 and binds it on the real host, so
    // each in-tunnel port is forwarded to the same port on the bridge address.
    let bridge_host = args[4].split(':').next().unwrap_or("127.0.0.1").to_string();
    // PEER_NO_UDP=1 leaves UDP unforwarded, which is what a firewall that only
    // lets TCP through looks like — the case Mosh has to fall back from.
    let udp = std::env::var("PEER_NO_UDP").is_err();
    for port in 60000..60011u16 {
        if !udp {
            break;
        }
        let host = bridge_host.clone();
        match tunnel.udp_listen(port).await {
            Ok(mut inbound) => {
                tokio::spawn(async move {
                    let out = match tokio::net::UdpSocket::bind("0.0.0.0:0").await {
                        Ok(s) => std::sync::Arc::new(s),
                        Err(e) => { eprintln!("udp {port}: {e}"); return; }
                    };
                    let mut client: Option<std::net::SocketAddr> = None;
                    let mut buf = vec![0u8; 2048];
                    loop {
                        tokio::select! {
                            got = inbound.recv_from() => match got {
                                Some((data, from)) => {
                                    client = Some(from);
                                    let _ = out.send_to(&data, format!("{host}:{port}")).await;
                                }
                                None => return,
                            },
                            got = out.recv_from(&mut buf) => if let Ok((n, _)) = got {
                                if let Some(to) = client {
                                    let _ = inbound.send_to(buf[..n].to_vec(), to);
                                }
                            },
                        }
                    }
                });
            }
            Err(e) => eprintln!("udp listen {port}: {e}"),
        }
    }
    println!("{}", if udp { format!("forwarding in-tunnel UDP 60000-60010 to {bridge_host}") } else { "UDP forwarding disabled (PEER_NO_UDP)".into() });

    let mut listener = tunnel.listen(22).await.unwrap();
    let bridge = args[4].clone();
    while let Some(mut s) = listener.accept().await {
        let bridge = bridge.clone();
        tokio::spawn(async move {
            match tokio::net::TcpStream::connect(&bridge).await {
                Ok(mut tcp) => { let _ = copy_bidirectional(&mut s, &mut tcp).await; }
                Err(e) => eprintln!("bridge failed: {e}"),
            }
        });
    }
}
