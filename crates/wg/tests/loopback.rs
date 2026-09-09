//! Two userspace peers talking over UDP loopback: no kernel module, no root.

use std::net::IpAddr;
use std::sync::Arc;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use wg_tunnel::config::encode_key;
use wg_tunnel::{Tunnel, WgConfig};

fn keypair() -> ([u8; 32], [u8; 32]) {
    let secret = boringtun::x25519::StaticSecret::random_from_rng(rand::rngs::OsRng);
    let public = boringtun::x25519::PublicKey::from(&secret);
    (secret.to_bytes(), public.to_bytes())
}

/// Returns (client, server). The server first learns the client's address from the handshake.
async fn pair() -> (Arc<Tunnel>, Arc<Tunnel>) {
    let (a_priv, a_pub) = keypair();
    let (b_priv, b_pub) = keypair();
    let server_cfg = format!(
        "[Interface]\nPrivateKey = {}\nAddress = 10.99.0.1/24\n[Peer]\nPublicKey = {}\nAllowedIPs = 10.99.0.0/24\nEndpoint = 127.0.0.1:9\n",
        encode_key(&b_priv), encode_key(&a_pub)
    );
    let server = Tunnel::start(WgConfig::parse(&server_cfg).unwrap()).await.unwrap();
    let client_cfg = format!(
        "[Interface]\nPrivateKey = {}\nAddress = 10.99.0.2/24\nDNS = 10.99.0.1\n[Peer]\nPublicKey = {}\nAllowedIPs = 10.99.0.0/24\nEndpoint = {}\nPersistentKeepalive = 5\n",
        encode_key(&a_priv), encode_key(&b_pub), server.local_addr()
    );
    let client = Tunnel::start(WgConfig::parse(&client_cfg).unwrap()).await.unwrap();
    (client, server)
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn tcp_echo_through_tunnel() {
    let (client, server) = pair().await;
    let mut listener = server.listen(7).await.unwrap();
    tokio::spawn(async move {
        while let Some(mut s) = listener.accept().await {
            tokio::spawn(async move {
                let mut buf = vec![0u8; 8192];
                loop {
                    let n = match s.read(&mut buf).await {
                        Ok(0) | Err(_) => break,
                        Ok(n) => n,
                    };
                    if s.write_all(&buf[..n]).await.is_err() {
                        break;
                    }
                }
            });
        }
    });

    let mut stream = tokio::time::timeout(std::time::Duration::from_secs(10), client.connect("10.99.0.1".parse().unwrap(), 7))
        .await
        .expect("connect timed out")
        .unwrap();
    // A payload larger than one MTU, sent in one go.
    let payload: Vec<u8> = (0..200_000u32).map(|i| (i % 251) as u8).collect();
    let writer_payload = payload.clone();
    let (mut r, mut w) = tokio::io::split(stream);
    let write = tokio::spawn(async move { w.write_all(&writer_payload).await.unwrap(); w });
    let mut got = vec![0u8; payload.len()];
    tokio::time::timeout(std::time::Duration::from_secs(20), r.read_exact(&mut got)).await.expect("echo timed out").unwrap();
    assert!(got == payload);
    let w = write.await.unwrap();
    stream = r.unsplit(w);
    stream.shutdown().await.unwrap();
    tokio::time::sleep(std::time::Duration::from_millis(600)).await; // stats refresh on the timer tick
    assert!(client.stats().last_handshake_secs.is_some());
    assert!(client.stats().tx_bytes > 200_000);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn refused_and_unroutable() {
    let (client, _server) = pair().await;
    let err = client.connect("10.99.0.1".parse().unwrap(), 9).await.err().expect("nothing listens on 9");
    assert!(err.to_string().contains("refused"), "{err}");
    let err = client.connect("192.168.50.1".parse::<IpAddr>().unwrap(), 22).await.err().unwrap();
    assert!(err.to_string().contains("AllowedIPs"), "{err}");
}

/// SSH over the tunnel: the "server" peer bridges accepted connections to the
/// real test sshd, and the client authenticates through the virtual stream.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn ssh_over_tunnel() {
    let Ok(port) = std::env::var("SSH_TEST_PORT") else { return };
    let port: u16 = port.parse().unwrap();
    let key = std::fs::read_to_string(std::env::var("SSH_TEST_KEY").unwrap()).unwrap();
    let user = std::env::var("USER").unwrap();

    let (client, server) = pair().await;
    let mut listener = server.listen(22).await.unwrap();
    tokio::spawn(async move {
        while let Some(mut s) = listener.accept().await {
            tokio::spawn(async move {
                let mut tcp = tokio::net::TcpStream::connect(("127.0.0.1", port)).await.unwrap();
                let _ = tokio::io::copy_bidirectional(&mut s, &mut tcp).await;
            });
        }
    });

    let stream = client.connect("10.99.0.1".parse().unwrap(), 22).await.unwrap();
    let opts = ssh_core::ConnectOptions {
        host: "10.99.0.1".into(),
        port: 22,
        username: user,
        auth: vec![ssh_core::Auth::Key { private_key: key, passphrase: None, certificate: None }],
        keepalive_interval: None,
        connect_timeout: std::time::Duration::from_secs(10),
        proxy: None,
        agent: None,
        prompter: None,
        on_banner: None,
    };
    let ssh = ssh_core::SshClient::connect_over(opts, stream, Arc::new(ssh_core::AcceptAll)).await.unwrap();
    let (out, status) = ssh.exec("echo tunnelled-$((6*7))").await.unwrap();
    assert_eq!(status, 0);
    assert!(String::from_utf8_lossy(&out).contains("tunnelled-42"));
    ssh.disconnect().await;
}

/// Mosh needs datagram boundaries preserved end to end, not a stream.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn udp_datagrams_through_tunnel() {
    let (client, server) = pair().await;
    let mut echo = server.udp_listen(7).await.unwrap();
    tokio::spawn(async move {
        while let Some((data, from)) = echo.recv_from().await {
            let mut reply = b"echo:".to_vec();
            reply.extend_from_slice(&data);
            if echo.send_to(reply, from).is_err() {
                break;
            }
        }
    });

    let mut sock = client.udp_connect("10.99.0.1".parse::<IpAddr>().unwrap(), 7).await.unwrap();
    for i in 0..5u8 {
        sock.send(vec![b'a' + i; 10]).unwrap();
    }

    // Each send must come back as its own datagram, not merged into a stream.
    let mut seen = Vec::new();
    for _ in 0..5 {
        let got = tokio::time::timeout(std::time::Duration::from_secs(10), sock.recv())
            .await
            .expect("timed out waiting for a datagram")
            .expect("tunnel closed");
        seen.push(got);
    }
    seen.sort();
    for (i, datagram) in seen.iter().enumerate() {
        let mut want = b"echo:".to_vec();
        want.extend_from_slice(&[b'a' + i as u8; 10]);
        assert_eq!(datagram, &want, "datagram {i} came back wrong");
    }
}

/// The stack inside the tunnel does not fragment IP, so an oversized datagram
/// is refused up front instead of vanishing. Anything up to the limit crosses.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn an_oversized_datagram_is_refused_and_a_full_one_is_not() {
    let (client, server) = pair().await;
    let mut echo = server.udp_listen(7).await.unwrap();
    tokio::spawn(async move {
        while let Some((data, from)) = echo.recv_from().await {
            if echo.send_to(data, from).is_err() {
                break;
            }
        }
    });

    let mut sock = client.udp_connect("10.99.0.1".parse::<IpAddr>().unwrap(), 7).await.unwrap();
    let limit = sock.max_payload();
    assert!(limit > 1000, "a usable tunnel should carry over 1000 bytes, got {limit}");

    let err = sock.send(vec![0u8; limit + 1]).unwrap_err().to_string();
    assert!(err.contains("exceeds the tunnel"), "{err}");

    // Right at the limit still goes through in one piece.
    let full: Vec<u8> = (0..limit).map(|i| (i % 251) as u8).collect();
    sock.send(full.clone()).unwrap();
    let got = tokio::time::timeout(std::time::Duration::from_secs(10), sock.recv())
        .await
        .expect("timed out")
        .expect("tunnel closed");
    assert_eq!(got.len(), full.len(), "a datagram at the limit changed size");
    assert_eq!(got, full);
}

/// A connected socket must ignore traffic from anywhere but its peer.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_connected_socket_ignores_other_senders() {
    let (client, server) = pair().await;
    // Nothing is listening on port 9, so this peer never answers.
    let mut sock = client.udp_connect("10.99.0.1".parse::<IpAddr>().unwrap(), 9).await.unwrap();
    sock.send(b"hello".to_vec()).unwrap();

    // Meanwhile the server sends from a different port.
    let other = server.udp_listen(11).await.unwrap();
    other.send_to(b"not from your peer".to_vec(), "10.99.0.2:20000".parse().unwrap()).unwrap();

    let quiet = tokio::time::timeout(std::time::Duration::from_millis(600), sock.recv()).await;
    assert!(quiet.is_err(), "should have heard nothing, got {quiet:?}");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn udp_outside_allowed_ips_is_refused() {
    let (client, _server) = pair().await;
    let err = client.udp_connect("8.8.8.8".parse::<IpAddr>().unwrap(), 53).await.unwrap_err();
    assert!(err.to_string().contains("AllowedIPs"), "{err}");
}

/// With no network, the timers must stop spending handshakes on nothing — but
/// traffic the caller actually sends still has to go out.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn no_network_stops_the_periodic_work_but_not_real_traffic() {
    let (client, server) = pair().await;
    let mut echo = server.udp_listen(7).await.unwrap();
    tokio::spawn(async move {
        while let Some((data, from)) = echo.recv_from().await {
            if echo.send_to(data, from).is_err() {
                break;
            }
        }
    });
    let mut sock = client.udp_connect("10.99.0.1".parse::<IpAddr>().unwrap(), 7).await.unwrap();
    // Warm the tunnel up first so the handshake is done.
    sock.send(b"warm".to_vec()).unwrap();
    tokio::time::timeout(std::time::Duration::from_secs(10), sock.recv()).await.unwrap().unwrap();

    assert!(client.network_up(), "a tunnel starts assuming there is a network");
    client.set_network_up(false);
    assert!(!client.network_up());

    // Keepalives stop, but a datagram we ask for is still delivered.
    sock.send(b"still works".to_vec()).unwrap();
    let got = tokio::time::timeout(std::time::Duration::from_secs(10), sock.recv())
        .await
        .expect("timed out with the network flag down")
        .expect("tunnel closed");
    assert_eq!(got, b"still works");

    client.set_network_up(true);
    assert!(client.network_up());
}
