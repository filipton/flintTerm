//! Outbound proxies for the first TCP hop: SOCKS5 (RFC 1928/1929) and HTTP CONNECT.

use std::time::Duration;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;

use crate::SshError;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProxyKind {
    Socks5,
    Http,
}

#[derive(Debug, Clone)]
pub struct ProxyConfig {
    pub kind: ProxyKind,
    pub host: String,
    pub port: u16,
    pub username: Option<String>,
    pub password: Option<String>,
}

/// Open a TCP connection to `host:port` through `proxy`. `timeout` bounds the
/// whole handshake.
pub async fn connect(proxy: &ProxyConfig, host: &str, port: u16, timeout: Duration) -> Result<TcpStream, SshError> {
    let fut = async {
        let mut tcp = TcpStream::connect((proxy.host.as_str(), proxy.port)).await?;
        let _ = tcp.set_nodelay(true);
        match proxy.kind {
            ProxyKind::Socks5 => socks5(&mut tcp, proxy, host, port).await?,
            ProxyKind::Http => http_connect(&mut tcp, proxy, host, port).await?,
        }
        Ok::<_, SshError>(tcp)
    };
    match tokio::time::timeout(timeout, fut).await {
        Ok(r) => r,
        Err(_) => Err(SshError::Timeout),
    }
}

/// Greeting and, if offered, username/password authentication. Shared by
/// CONNECT and UDP ASSOCIATE, which differ only in the request that follows.
async fn socks5_handshake(tcp: &mut TcpStream, proxy: &ProxyConfig) -> Result<(), SshError> {
    let with_auth = proxy.username.is_some();
    // Greeting: offer no-auth and, when we have credentials, user/pass.
    let methods: &[u8] = if with_auth { &[0x00, 0x02] } else { &[0x00] };
    let mut greeting = vec![0x05, methods.len() as u8];
    greeting.extend_from_slice(methods);
    tcp.write_all(&greeting).await?;
    let mut reply = [0u8; 2];
    tcp.read_exact(&mut reply).await?;
    if reply[0] != 0x05 {
        return Err(SshError::Other("SOCKS5 proxy: bad version".into()));
    }
    match reply[1] {
        0x00 => {}
        0x02 => {
            let user = proxy.username.clone().unwrap_or_default();
            let pass = proxy.password.clone().unwrap_or_default();
            if user.len() > 255 || pass.len() > 255 {
                return Err(SshError::Other("SOCKS5 proxy: credentials too long".into()));
            }
            let mut msg = vec![0x01, user.len() as u8];
            msg.extend_from_slice(user.as_bytes());
            msg.push(pass.len() as u8);
            msg.extend_from_slice(pass.as_bytes());
            tcp.write_all(&msg).await?;
            let mut r = [0u8; 2];
            tcp.read_exact(&mut r).await?;
            if r[1] != 0x00 {
                return Err(SshError::Other("SOCKS5 proxy: authentication rejected".into()));
            }
        }
        0xFF => return Err(SshError::Other("SOCKS5 proxy: no acceptable authentication method".into())),
        m => return Err(SshError::Other(format!("SOCKS5 proxy: unsupported auth method {m}"))),
    }
    Ok(())
}

async fn socks5(tcp: &mut TcpStream, proxy: &ProxyConfig, host: &str, port: u16) -> Result<(), SshError> {
    socks5_handshake(tcp, proxy).await?;
    // CONNECT with a domain-name address; the proxy resolves it.
    if host.len() > 255 {
        return Err(SshError::Other("SOCKS5 proxy: host name too long".into()));
    }
    let mut req = vec![0x05, 0x01, 0x00, 0x03, host.len() as u8];
    req.extend_from_slice(host.as_bytes());
    req.extend_from_slice(&port.to_be_bytes());
    tcp.write_all(&req).await?;
    let mut head = [0u8; 4];
    tcp.read_exact(&mut head).await?;
    if head[1] != 0x00 {
        let reason = match head[1] {
            0x01 => "general failure",
            0x02 => "connection not allowed",
            0x03 => "network unreachable",
            0x04 => "host unreachable",
            0x05 => "connection refused",
            0x06 => "TTL expired",
            0x07 => "command not supported",
            0x08 => "address type not supported",
            _ => "unknown error",
        };
        return Err(SshError::Other(format!("SOCKS5 proxy: {reason}")));
    }
    read_bound_address(tcp, head[3]).await?;
    Ok(())
}

/// Read the address in a SOCKS5 reply, given its type byte.
async fn read_bound_address(tcp: &mut TcpStream, atyp: u8) -> Result<(String, u16), SshError> {
    let (addr, port) = match atyp {
        0x01 => {
            let mut b = [0u8; 6];
            tcp.read_exact(&mut b).await?;
            (format!("{}.{}.{}.{}", b[0], b[1], b[2], b[3]), u16::from_be_bytes([b[4], b[5]]))
        }
        0x04 => {
            let mut b = [0u8; 18];
            tcp.read_exact(&mut b).await?;
            let mut segs = [0u16; 8];
            for (i, seg) in segs.iter_mut().enumerate() {
                *seg = u16::from_be_bytes([b[i * 2], b[i * 2 + 1]]);
            }
            (std::net::Ipv6Addr::from(segs).to_string(), u16::from_be_bytes([b[16], b[17]]))
        }
        0x03 => {
            let mut l = [0u8; 1];
            tcp.read_exact(&mut l).await?;
            let mut name = vec![0u8; l[0] as usize + 2];
            tcp.read_exact(&mut name).await?;
            let port = u16::from_be_bytes([name[name.len() - 2], name[name.len() - 1]]);
            (String::from_utf8_lossy(&name[..name.len() - 2]).into_owned(), port)
        }
        _ => return Err(SshError::Other("SOCKS5 proxy: bad reply".into())),
    };
    Ok((addr, port))
}

/// Where a SOCKS5 proxy will relay our datagrams.
pub struct UdpAssociation {
    /// The proxy's relay address, where datagrams must be sent.
    pub relay_host: String,
    pub relay_port: u16,
    /// The control connection. The association lives only as long as this is
    /// held open, so the caller must keep it for the life of the session.
    pub control: TcpStream,
}

/// Ask a SOCKS5 proxy to relay UDP for us (RFC 1928 UDP ASSOCIATE).
///
/// This is the only way a proxy can carry a datagram protocol like Mosh; plenty
/// of proxies decline it, which is reported as-is rather than guessed at.
pub async fn udp_associate(proxy: &ProxyConfig, timeout: Duration) -> Result<UdpAssociation, SshError> {
    if proxy.kind != ProxyKind::Socks5 {
        return Err(SshError::Other("only a SOCKS5 proxy can relay UDP; HTTP CONNECT is TCP only".into()));
    }
    let mut tcp = tokio::time::timeout(timeout, TcpStream::connect((proxy.host.as_str(), proxy.port)))
        .await
        .map_err(|_| SshError::Timeout)??;
    tcp.set_nodelay(true)?;
    socks5_handshake(&mut tcp, proxy).await?;

    // We do not know our own source address yet, so ask for a wildcard
    // association: the proxy then accepts our datagrams from any port.
    let req = [0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0];
    tcp.write_all(&req).await?;
    let mut head = [0u8; 4];
    tcp.read_exact(&mut head).await?;
    if head[1] != 0x00 {
        let reason = if head[1] == 0x07 { "the proxy does not support UDP relaying" } else { "UDP ASSOCIATE refused" };
        return Err(SshError::Other(format!("SOCKS5 proxy: {reason}")));
    }
    let (mut relay_host, relay_port) = read_bound_address(&mut tcp, head[3]).await?;
    // A proxy may answer with an all-zero address meaning "same host as me".
    if relay_host == "0.0.0.0" || relay_host == "::" {
        relay_host = proxy.host.clone();
    }
    Ok(UdpAssociation { relay_host, relay_port, control: tcp })
}

/// Wrap a datagram in the SOCKS5 UDP request header for `host:port`.
pub fn udp_encapsulate(host: &str, port: u16, payload: &[u8]) -> Result<Vec<u8>, SshError> {
    let mut out = Vec::with_capacity(payload.len() + 22);
    out.extend_from_slice(&[0x00, 0x00, 0x00]); // RSV, RSV, FRAG=0
    match host.parse::<std::net::IpAddr>() {
        Ok(std::net::IpAddr::V4(v4)) => {
            out.push(0x01);
            out.extend_from_slice(&v4.octets());
        }
        Ok(std::net::IpAddr::V6(v6)) => {
            out.push(0x04);
            out.extend_from_slice(&v6.octets());
        }
        Err(_) => {
            if host.len() > 255 {
                return Err(SshError::Other("SOCKS5 proxy: host name too long".into()));
            }
            out.push(0x03);
            out.push(host.len() as u8);
            out.extend_from_slice(host.as_bytes());
        }
    }
    out.extend_from_slice(&port.to_be_bytes());
    out.extend_from_slice(payload);
    Ok(out)
}

/// Strip the SOCKS5 UDP header from a relayed datagram, returning the payload.
/// Fragmented datagrams are refused: nothing we send needs them, and accepting
/// a fragment would mean handing a partial message to the protocol above.
pub fn udp_decapsulate(datagram: &[u8]) -> Result<&[u8], SshError> {
    if datagram.len() < 10 {
        return Err(SshError::Other("SOCKS5 proxy: short UDP reply".into()));
    }
    if datagram[2] != 0x00 {
        return Err(SshError::Other("SOCKS5 proxy: fragmented UDP is not supported".into()));
    }
    let offset = match datagram[3] {
        0x01 => 4 + 4 + 2,
        0x04 => 4 + 16 + 2,
        0x03 => 4 + 1 + datagram[4] as usize + 2,
        _ => return Err(SshError::Other("SOCKS5 proxy: bad UDP reply address".into())),
    };
    datagram.get(offset..).ok_or_else(|| SshError::Other("SOCKS5 proxy: truncated UDP reply".into()))
}

async fn http_connect(tcp: &mut TcpStream, proxy: &ProxyConfig, host: &str, port: u16) -> Result<(), SshError> {
    let mut req = format!("CONNECT {host}:{port} HTTP/1.1\r\nHost: {host}:{port}\r\n");
    if let Some(user) = &proxy.username {
        let creds = format!("{user}:{}", proxy.password.clone().unwrap_or_default());
        req.push_str(&format!("Proxy-Authorization: Basic {}\r\n", base64(creds.as_bytes())));
    }
    req.push_str("\r\n");
    tcp.write_all(req.as_bytes()).await?;
    // Read the response head (up to the blank line).
    let mut head = Vec::new();
    let mut byte = [0u8; 1];
    while !head.ends_with(b"\r\n\r\n") {
        if head.len() > 16 * 1024 {
            return Err(SshError::Other("HTTP proxy: response too large".into()));
        }
        tcp.read_exact(&mut byte).await?;
        head.push(byte[0]);
    }
    let status = String::from_utf8_lossy(&head);
    let line = status.lines().next().unwrap_or_default();
    let code = line.split_whitespace().nth(1).and_then(|c| c.parse::<u16>().ok()).unwrap_or(0);
    if (200..300).contains(&code) {
        Ok(())
    } else {
        Err(SshError::Other(format!("HTTP proxy: {}", line.trim())))
    }
}

fn base64(input: &[u8]) -> String {
    const T: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity((input.len() + 2) / 3 * 4);
    for chunk in input.chunks(3) {
        let b = [chunk[0], *chunk.get(1).unwrap_or(&0), *chunk.get(2).unwrap_or(&0)];
        let n = (b[0] as u32) << 16 | (b[1] as u32) << 8 | b[2] as u32;
        out.push(T[(n >> 18) as usize & 63] as char);
        out.push(T[(n >> 12) as usize & 63] as char);
        out.push(if chunk.len() > 1 { T[(n >> 6) as usize & 63] as char } else { '=' });
        out.push(if chunk.len() > 2 { T[n as usize & 63] as char } else { '=' });
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn socks5_udp_header_round_trips_for_each_address_type() {
        for (host, port) in [("10.1.2.3", 60001u16), ("2001:db8::1", 1), ("host.example", 65535)] {
            let wrapped = udp_encapsulate(host, port, b"payload").unwrap();
            assert_eq!(&wrapped[..3], &[0, 0, 0], "RSV RSV FRAG");
            assert_eq!(udp_decapsulate(&wrapped).unwrap(), b"payload", "host {host}");
        }
    }

    #[test]
    fn an_ipv4_udp_header_is_the_expected_bytes() {
        let w = udp_encapsulate("1.2.3.4", 0x1234, b"hi").unwrap();
        assert_eq!(w, vec![0, 0, 0, 0x01, 1, 2, 3, 4, 0x12, 0x34, b'h', b'i']);
    }

    #[test]
    fn a_fragmented_udp_datagram_is_refused() {
        let mut w = udp_encapsulate("1.2.3.4", 1, b"x").unwrap();
        w[2] = 1; // FRAG
        assert!(udp_decapsulate(&w).unwrap_err().to_string().contains("fragmented"));
    }

    #[test]
    fn a_short_or_malformed_udp_datagram_is_refused_not_panicked_on() {
        assert!(udp_decapsulate(&[0, 0, 0]).is_err());
        assert!(udp_decapsulate(&[0, 0, 0, 0x09, 1, 2, 3, 4, 5, 6]).is_err(), "unknown address type");
        // A truncated domain-name header must not index past the end.
        assert!(udp_decapsulate(&[0, 0, 0, 0x03, 200, 1, 2, 3, 4, 5]).is_err());
    }

    #[test]
    fn a_long_host_name_is_refused() {
        let long = "a".repeat(256);
        assert!(udp_encapsulate(&long, 1, b"x").is_err());
    }

    #[test]
    fn base64_matches_reference() {
        assert_eq!(super::base64(b"user:pass"), "dXNlcjpwYXNz");
        assert_eq!(super::base64(b"a"), "YQ==");
        assert_eq!(super::base64(b"ab"), "YWI=");
    }
}
