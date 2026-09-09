//! Userspace WireGuard tunnels, kept in a process-wide registry so sessions can
//! refer to them by id, plus Wake-on-LAN and key helpers.

use std::collections::HashMap;
use std::sync::Arc;

use parking_lot::Mutex;
use wg_tunnel::{Tunnel, WgConfig};

use crate::runtime::{block_on, RUNTIME};
use crate::CoreError;

struct Entry {
    config: WgConfig,
    config_text: String,
    running: Option<Arc<Tunnel>>,
}

static TUNNELS: Mutex<Option<HashMap<String, Entry>>> = Mutex::new(None);

fn with_registry<R>(f: impl FnOnce(&mut HashMap<String, Entry>) -> R) -> R {
    let mut guard = TUNNELS.lock();
    f(guard.get_or_insert_with(HashMap::new))
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct TunnelInfo {
    /// Our addresses inside the tunnel, e.g. "10.8.0.2/24".
    pub addresses: Vec<String>,
    pub endpoint: String,
    pub dns: Vec<String>,
    pub allowed_ips: Vec<String>,
    pub public_key: String,
    pub mtu: u32,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct TunnelStats {
    pub running: bool,
    pub last_handshake_secs: Option<u64>,
    pub tx_bytes: u64,
    pub rx_bytes: u64,
    pub endpoint: Option<String>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct WgKeyPair {
    pub private_key: String,
    pub public_key: String,
}

/// Validate a wg-quick config and return what the UI wants to show.
#[uniffi::export]
pub fn parse_tunnel_config(config_text: String) -> Result<TunnelInfo, CoreError> {
    let c = WgConfig::parse(&config_text).map_err(|e| CoreError::Other(e.to_string()))?;
    Ok(info(&c))
}

fn info(c: &WgConfig) -> TunnelInfo {
    let public = boringtun::x25519::PublicKey::from(&boringtun::x25519::StaticSecret::from(c.private_key));
    TunnelInfo {
        addresses: c.addresses.iter().map(|a| format!("{}/{}", a.addr, a.prefix)).collect(),
        endpoint: c.peer.endpoint.clone(),
        dns: c.dns.iter().map(|d| d.to_string()).collect(),
        allowed_ips: c.peer.allowed_ips.iter().map(|a| format!("{}/{}", a.addr, a.prefix)).collect(),
        public_key: wg_tunnel::config::encode_key(public.as_bytes()),
        mtu: c.mtu as u32,
    }
}

/// Make a tunnel known under `id`. Re-registering with a different config
/// stops any running instance so the next use picks up the new settings.
#[uniffi::export]
pub fn register_tunnel(id: String, config_text: String) -> Result<TunnelInfo, CoreError> {
    let config = WgConfig::parse(&config_text).map_err(|e| CoreError::Other(e.to_string()))?;
    let i = info(&config);
    let stale = with_registry(|reg| {
        let stale = match reg.get(&id) {
            Some(e) if e.config_text != config_text => e.running.clone(),
            _ => None,
        };
        let running = if stale.is_some() { None } else { reg.get(&id).and_then(|e| e.running.clone()) };
        reg.insert(id, Entry { config, config_text, running });
        stale
    });
    if let Some(t) = stale {
        t.stop();
    }
    Ok(i)
}

#[uniffi::export]
pub fn unregister_tunnel(id: String) {
    let t = with_registry(|reg| reg.remove(&id).and_then(|e| e.running));
    if let Some(t) = t {
        t.stop();
    }
}

/// Bring the tunnel up (handshake happens in the background). No-op when already running.
#[uniffi::export]
pub fn start_tunnel(id: String) -> Result<TunnelStats, CoreError> {
    let t = block_on(tunnel_for(&id))?;
    Ok(stats_of(&t))
}

/// Tell every running tunnel whether the phone has a network.
///
/// With no link, WireGuard's timers would keep re-initiating handshakes into
/// nothing, which costs data and battery for no benefit. Called from the app's
/// connectivity monitor.
#[uniffi::export]
pub fn set_tunnels_network_up(up: bool) {
    with_registry(|reg| {
        for entry in reg.values() {
            if let Some(running) = &entry.running {
                running.set_network_up(up);
            }
        }
    });
}

#[uniffi::export]
pub fn stop_tunnel(id: String) {
    let t = with_registry(|reg| reg.get_mut(&id).and_then(|e| e.running.take()));
    if let Some(t) = t {
        t.stop();
    }
}

#[uniffi::export]
pub fn tunnel_stats(id: String) -> TunnelStats {
    match with_registry(|reg| reg.get(&id).and_then(|e| e.running.clone())) {
        Some(t) if t.is_running() => stats_of(&t),
        _ => TunnelStats { running: false, last_handshake_secs: None, tx_bytes: 0, rx_bytes: 0, endpoint: None },
    }
}

fn stats_of(t: &Tunnel) -> TunnelStats {
    let s = t.stats();
    TunnelStats {
        running: s.running,
        last_handshake_secs: s.last_handshake_secs,
        tx_bytes: s.tx_bytes,
        rx_bytes: s.rx_bytes,
        endpoint: s.endpoint.map(|e| e.to_string()),
    }
}

/// What a diagnostic came back with, ready to show.
#[derive(Debug, Clone, uniffi::Record)]
pub struct ProbeResult {
    pub ok: bool,
    /// One line for the screen: the answer, or why there was not one.
    pub detail: String,
    pub millis: Option<u32>,
}

/// Ping an address inside a tunnel.
///
/// The tunnel is brought up if it is not already, because a diagnostic that
/// needs the thing being diagnosed to be running first is not much of one.
#[uniffi::export]
pub fn tunnel_ping(id: String, host: String, timeout_secs: u32) -> Result<ProbeResult, CoreError> {
    block_on(async move {
        let t = tunnel_for(&id).await?;
        let addr = t.resolve(&host).await.map_err(|e| CoreError::Other(e.to_string()))?;
        let timeout = std::time::Duration::from_secs(timeout_secs.max(1) as u64);
        match t.ping(addr, timeout).await.map_err(|e| CoreError::Other(e.to_string()))? {
            Some(ms) => Ok(ProbeResult { ok: true, detail: format!("{addr} answered in {ms} ms"), millis: Some(ms) }),
            None => Ok(ProbeResult {
                ok: false,
                // Worth stating: plenty of hosts drop pings on purpose.
                detail: format!("no reply from {addr} in {timeout_secs}s — it may be down, or set not to answer pings"),
                millis: None,
            }),
        }
    })
}

/// Open a TCP connection inside a tunnel and close it again.
#[uniffi::export]
pub fn tunnel_port_check(id: String, host: String, port: u16, timeout_secs: u32) -> Result<ProbeResult, CoreError> {
    block_on(async move {
        let t = tunnel_for(&id).await?;
        let addr = t.resolve(&host).await.map_err(|e| CoreError::Other(e.to_string()))?;
        let started = std::time::Instant::now();
        let timeout = std::time::Duration::from_secs(timeout_secs.max(1) as u64);
        match tokio::time::timeout(timeout, t.connect(addr, port)).await {
            Ok(Ok(_stream)) => {
                let ms = started.elapsed().as_millis() as u32;
                Ok(ProbeResult { ok: true, detail: format!("{addr}:{port} is open ({ms} ms)"), millis: Some(ms) })
            }
            Ok(Err(e)) => Ok(ProbeResult { ok: false, detail: format!("{addr}:{port} refused: {e}"), millis: None }),
            Err(_) => Ok(ProbeResult { ok: false, detail: format!("{addr}:{port} did not answer in {timeout_secs}s"), millis: None }),
        }
    })
}

/// Resolve a name using the tunnel's own DNS server.
#[uniffi::export]
pub fn tunnel_resolve(id: String, name: String) -> Result<ProbeResult, CoreError> {
    block_on(async move {
        let t = tunnel_for(&id).await?;
        let started = std::time::Instant::now();
        match t.resolve(&name).await {
            Ok(addr) => Ok(ProbeResult {
                ok: true,
                detail: format!("{name} is {addr} ({} ms)", started.elapsed().as_millis()),
                millis: Some(started.elapsed().as_millis() as u32),
            }),
            Err(e) => Ok(ProbeResult { ok: false, detail: e.to_string(), millis: None }),
        }
    })
}

/// Fresh Curve25519 key pair for building a config in-app.
#[uniffi::export]
pub fn wg_generate_keypair() -> WgKeyPair {
    let secret = boringtun::x25519::StaticSecret::random_from_rng(rand::rngs::OsRng);
    let public = boringtun::x25519::PublicKey::from(&secret);
    WgKeyPair {
        private_key: wg_tunnel::config::encode_key(&secret.to_bytes()),
        public_key: wg_tunnel::config::encode_key(public.as_bytes()),
    }
}

/// Send a Wake-on-LAN magic packet (three times, like `wakeonlan` does) from this device.
#[uniffi::export]
pub fn wake_on_lan(mac: String, broadcast: String, port: u16) -> Result<(), CoreError> {
    let mac = parse_mac(&mac)?;
    let mut packet = vec![0xFFu8; 6];
    for _ in 0..16 {
        packet.extend_from_slice(&mac);
    }
    let target = format!("{}:{}", broadcast.trim(), if port == 0 { 9 } else { port });
    block_on(async move {
        let sock = tokio::net::UdpSocket::bind("0.0.0.0:0").await.map_err(|e| CoreError::Other(e.to_string()))?;
        sock.set_broadcast(true).map_err(|e| CoreError::Other(e.to_string()))?;
        let addr = tokio::net::lookup_host(target.as_str())
            .await
            .map_err(|e| CoreError::Other(format!("bad broadcast address {target}: {e}")))?
            .next()
            .ok_or_else(|| CoreError::Other(format!("bad broadcast address {target}")))?;
        for i in 0..3 {
            sock.send_to(&packet, addr).await.map_err(|e| CoreError::Other(format!("send failed: {e}")))?;
            if i < 2 {
                tokio::time::sleep(std::time::Duration::from_millis(100)).await;
            }
        }
        Ok(())
    })
}

pub fn parse_mac(s: &str) -> Result<[u8; 6], CoreError> {
    let clean: String = s.chars().filter(|c| c.is_ascii_hexdigit()).collect();
    if clean.len() != 12 {
        return Err(CoreError::Other(format!("invalid MAC address: {s}")));
    }
    let mut out = [0u8; 6];
    for i in 0..6 {
        out[i] = u8::from_str_radix(&clean[i * 2..i * 2 + 2], 16).map_err(|_| CoreError::Other(format!("invalid MAC address: {s}")))?;
    }
    Ok(out)
}

/// Running tunnel for `id`, starting it when needed.
pub(crate) async fn tunnel_for(id: &str) -> Result<Arc<Tunnel>, CoreError> {
    let (config, running) = with_registry(|reg| {
        reg.get(id).map(|e| (e.config.clone(), e.running.clone())).ok_or_else(|| CoreError::Other(format!("unknown tunnel {id}")))
    })?;
    if let Some(t) = running {
        if t.is_running() {
            return Ok(t);
        }
    }
    let t = Tunnel::start(config).await.map_err(|e| CoreError::Other(format!("tunnel: {e}")))?;
    // Wait for the first handshake before calling the tunnel usable. Starting it
    // only arms the state machine; a TCP connection sent into a tunnel whose peer
    // has not answered yet dies with an unhelpful "early eof".
    let deadline = tokio::time::Instant::now() + std::time::Duration::from_secs(6);
    while tokio::time::Instant::now() < deadline {
        if t.stats().last_handshake_secs.is_some() {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(100)).await;
    }
    let id = id.to_string();
    let t2 = t.clone();
    with_registry(move |reg| {
        if let Some(e) = reg.get_mut(&id) {
            e.running = Some(t2);
        }
    });
    Ok(t)
}

#[allow(dead_code)]
pub(crate) fn spawn_detached<F: std::future::Future<Output = ()> + Send + 'static>(f: F) {
    RUNTIME.spawn(f);
}

#[cfg(test)]
mod tests {
    #[test]
    fn mac_parsing() {
        assert_eq!(super::parse_mac("a8:a1:59:23:8e:88").unwrap(), [0xa8, 0xa1, 0x59, 0x23, 0x8e, 0x88]);
        assert_eq!(super::parse_mac("A8-A1-59-23-8E-88").unwrap(), [0xa8, 0xa1, 0x59, 0x23, 0x8e, 0x88]);
        assert!(super::parse_mac("a8:a1:59").is_err());
    }
}
