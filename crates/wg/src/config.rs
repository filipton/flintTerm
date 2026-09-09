//! Parser for `wg-quick` style configuration files.

use std::net::IpAddr;

use base64::Engine;

use crate::WgError;

#[derive(Debug, Clone)]
pub struct IpNet {
    pub addr: IpAddr,
    pub prefix: u8,
}

impl IpNet {
    pub fn contains(&self, ip: &IpAddr) -> bool {
        match (self.addr, ip) {
            (IpAddr::V4(a), IpAddr::V4(b)) => {
                let mask = if self.prefix == 0 { 0 } else { u32::MAX << (32 - self.prefix.min(32)) };
                (u32::from(a) & mask) == (u32::from(*b) & mask)
            }
            (IpAddr::V6(a), IpAddr::V6(b)) => {
                let mask = if self.prefix == 0 { 0 } else { u128::MAX << (128 - self.prefix.min(128)) };
                (u128::from(a) & mask) == (u128::from(*b) & mask)
            }
            _ => false,
        }
    }
}

#[derive(Debug, Clone)]
pub struct Peer {
    pub public_key: [u8; 32],
    pub preshared_key: Option<[u8; 32]>,
    /// `host:port`; resolved when the tunnel starts.
    pub endpoint: String,
    pub allowed_ips: Vec<IpNet>,
    pub persistent_keepalive: Option<u16>,
}

#[derive(Debug, Clone)]
pub struct WgConfig {
    pub private_key: [u8; 32],
    /// Addresses of this side of the tunnel (`Address =`).
    pub addresses: Vec<IpNet>,
    pub dns: Vec<IpAddr>,
    pub mtu: usize,
    pub peer: Peer,
}

impl WgConfig {
    /// Parse the INI-like `wg-quick` format. Only the first `[Peer]` is used.
    pub fn parse(text: &str) -> Result<Self, WgError> {
        let mut section = String::new();
        let mut private_key = None;
        let mut addresses = Vec::new();
        let mut dns = Vec::new();
        let mut mtu = 1280usize;
        let mut peers: Vec<Peer> = Vec::new();

        for raw in text.lines() {
            let line = raw.split('#').next().unwrap_or("").trim();
            if line.is_empty() {
                continue;
            }
            if line.starts_with('[') && line.ends_with(']') {
                section = line[1..line.len() - 1].trim().to_ascii_lowercase();
                if section == "peer" {
                    peers.push(Peer {
                        public_key: [0; 32],
                        preshared_key: None,
                        endpoint: String::new(),
                        allowed_ips: Vec::new(),
                        persistent_keepalive: None,
                    });
                }
                continue;
            }
            let (key, value) = match line.split_once('=') {
                Some((k, v)) => (k.trim().to_ascii_lowercase(), v.trim()),
                None => return Err(WgError::Config(format!("cannot parse line: {line}"))),
            };
            match (section.as_str(), key.as_str()) {
                ("interface", "privatekey") => private_key = Some(decode_key(value)?),
                ("interface", "address") => {
                    for a in value.split(',') {
                        addresses.push(parse_net(a.trim())?);
                    }
                }
                ("interface", "dns") => {
                    for d in value.split(',') {
                        let d = d.trim();
                        // wg-quick also allows search domains here; ignore anything that is not an IP.
                        if let Ok(ip) = d.parse::<IpAddr>() {
                            dns.push(ip);
                        }
                    }
                }
                ("interface", "mtu") => mtu = value.parse().map_err(|_| WgError::Config("bad MTU".into()))?,
                ("interface", _) => {} // ListenPort, PreUp, PostUp, Table, FwMark… not applicable in userspace
                ("peer", k) => {
                    let peer = peers.last_mut().ok_or_else(|| WgError::Config("key outside a section".into()))?;
                    match k {
                        "publickey" => peer.public_key = decode_key(value)?,
                        "presharedkey" => peer.preshared_key = Some(decode_key(value)?),
                        "endpoint" => peer.endpoint = value.to_string(),
                        "allowedips" => {
                            for a in value.split(',') {
                                peer.allowed_ips.push(parse_net(a.trim())?);
                            }
                        }
                        "persistentkeepalive" => {
                            let secs: u16 = value.parse().map_err(|_| WgError::Config("bad PersistentKeepalive".into()))?;
                            peer.persistent_keepalive = (secs > 0).then_some(secs);
                        }
                        _ => {}
                    }
                }
                _ => return Err(WgError::Config(format!("unexpected key '{key}' outside a section"))),
            }
        }

        let private_key = private_key.ok_or_else(|| WgError::Config("missing [Interface] PrivateKey".into()))?;
        if addresses.is_empty() {
            return Err(WgError::Config("missing [Interface] Address".into()));
        }
        let peer = peers.into_iter().next().ok_or_else(|| WgError::Config("missing [Peer] section".into()))?;
        if peer.public_key == [0; 32] {
            return Err(WgError::Config("missing [Peer] PublicKey".into()));
        }
        if peer.endpoint.is_empty() {
            return Err(WgError::Config("missing [Peer] Endpoint (a userspace tunnel must know where to connect)".into()));
        }
        if !(576..=1500).contains(&mtu) {
            return Err(WgError::Config("MTU must be between 576 and 1500".into()));
        }
        Ok(WgConfig { private_key, addresses, dns, mtu, peer })
    }

    /// True when `ip` is routed through this tunnel according to the peer's AllowedIPs.
    pub fn routes(&self, ip: &IpAddr) -> bool {
        self.peer.allowed_ips.iter().any(|n| n.contains(ip))
    }

    /// Our address of the same family as `ip`, used as the source of virtual sockets.
    pub fn source_for(&self, ip: &IpAddr) -> Option<IpAddr> {
        self.addresses.iter().map(|n| n.addr).find(|a| a.is_ipv4() == ip.is_ipv4())
    }
}

pub fn decode_key(b64: &str) -> Result<[u8; 32], WgError> {
    let bytes = base64::engine::general_purpose::STANDARD
        .decode(b64.trim())
        .map_err(|e| WgError::Config(format!("invalid key: {e}")))?;
    bytes.try_into().map_err(|_| WgError::Config("key must be 32 bytes".into()))
}

pub fn encode_key(key: &[u8; 32]) -> String {
    base64::engine::general_purpose::STANDARD.encode(key)
}

fn parse_net(s: &str) -> Result<IpNet, WgError> {
    let (ip, prefix) = match s.split_once('/') {
        Some((ip, p)) => (ip, Some(p)),
        None => (s, None),
    };
    let addr: IpAddr = ip.trim().parse().map_err(|_| WgError::Config(format!("invalid address: {s}")))?;
    let prefix = match prefix {
        Some(p) => p.trim().parse::<u8>().map_err(|_| WgError::Config(format!("invalid prefix: {s}")))?,
        None => if addr.is_ipv4() { 32 } else { 128 },
    };
    Ok(IpNet { addr, prefix })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_wg_quick() {
        let text = "\
[Interface]
PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=
Address = 10.8.0.2/24, fd00:8::2/64
DNS = 10.8.0.1, example.lan
MTU = 1380
# comment
[Peer]
PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
PresharedKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=
AllowedIPs = 0.0.0.0/0, ::/0
Endpoint = vpn.example.com:51820
PersistentKeepalive = 25
";
        let c = WgConfig::parse(text).unwrap();
        assert_eq!(c.addresses.len(), 2);
        assert_eq!(c.dns, vec!["10.8.0.1".parse::<IpAddr>().unwrap()]);
        assert_eq!(c.mtu, 1380);
        assert_eq!(c.peer.endpoint, "vpn.example.com:51820");
        assert_eq!(c.peer.persistent_keepalive, Some(25));
        assert!(c.routes(&"192.168.1.5".parse().unwrap()));
        assert_eq!(c.source_for(&"1.2.3.4".parse().unwrap()), Some("10.8.0.2".parse().unwrap()));
    }

    #[test]
    fn rejects_missing_bits() {
        assert!(WgConfig::parse("[Interface]\nAddress = 10.0.0.1/24\n").is_err());
        assert!(WgConfig::parse("[Interface]\nPrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=\nAddress=10.0.0.1\n[Peer]\nPublicKey=xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=\n").is_err());
    }
}
