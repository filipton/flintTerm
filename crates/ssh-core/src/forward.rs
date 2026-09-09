//! Port forwarding.

use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::Arc;

use russh::client::{Handle, Msg};
use tokio::net::TcpListener;
use tokio::task::JoinHandle;

use crate::{ClientHandler, SshError};

/// `-L`: a local listener whose connections are tunneled to the remote target.
pub struct LocalForward {
    pub bound: SocketAddr,
    task: JoinHandle<()>,
}

impl LocalForward {
    pub fn stop(&self) {
        self.task.abort();
    }
}

impl Drop for LocalForward {
    fn drop(&mut self) {
        self.task.abort();
    }
}

pub(crate) async fn start_local(
    handle: Arc<Handle<ClientHandler>>,
    bind: SocketAddr,
    target_host: String,
    target_port: u16,
) -> Result<LocalForward, SshError> {
    let listener = TcpListener::bind(bind).await?;
    let bound = listener.local_addr()?;
    let task = tokio::spawn(async move {
        loop {
            let (mut tcp, peer) = match listener.accept().await {
                Ok(v) => v,
                Err(e) => {
                    log::warn!("local forward accept: {e}");
                    break;
                }
            };
            let handle = handle.clone();
            let target_host = target_host.clone();
            tokio::spawn(async move {
                let _ = tcp.set_nodelay(true);
                let open = handle
                    .channel_open_direct_tcpip(target_host.clone(), target_port as u32, peer.ip().to_string(), peer.port() as u32)
                    .await;
                match open {
                    Ok(channel) => {
                        let mut stream = channel.into_stream();
                        if let Err(e) = tokio::io::copy_bidirectional(&mut tcp, &mut stream).await {
                            log::debug!("local forward stream ended: {e}");
                        }
                    }
                    Err(e) => log::warn!("local forward to {target_host}:{target_port} failed: {e}"),
                }
            });
        }
    });
    Ok(LocalForward { bound, task })
}

/// `-D`: a SOCKS5/SOCKS4a proxy on `bind`; each connection is tunneled to the
/// destination the client asks for through a `direct-tcpip` channel.
pub(crate) async fn start_dynamic(handle: Arc<Handle<ClientHandler>>, bind: SocketAddr) -> Result<LocalForward, SshError> {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let listener = TcpListener::bind(bind).await?;
    let bound = listener.local_addr()?;
    let task = tokio::spawn(async move {
        loop {
            let (mut tcp, peer) = match listener.accept().await {
                Ok(v) => v,
                Err(e) => {
                    log::warn!("socks accept: {e}");
                    break;
                }
            };
            let handle = handle.clone();
            tokio::spawn(async move {
                let _ = tcp.set_nodelay(true);
                // ---- handshake: figure out where the client wants to go ----
                let mut first = [0u8; 1];
                if tcp.read_exact(&mut first).await.is_err() {
                    return;
                }
                let (host, port, v5) = match first[0] {
                    5 => {
                        let mut n = [0u8; 1];
                        if tcp.read_exact(&mut n).await.is_err() { return; }
                        let mut methods = vec![0u8; n[0] as usize];
                        if tcp.read_exact(&mut methods).await.is_err() { return; }
                        if tcp.write_all(&[5, 0]).await.is_err() { return; } // no auth
                        let mut head = [0u8; 4];
                        if tcp.read_exact(&mut head).await.is_err() { return; }
                        if head[1] != 1 {
                            let _ = tcp.write_all(&[5, 7, 0, 1, 0, 0, 0, 0, 0, 0]).await; // command not supported
                            return;
                        }
                        let host = match head[3] {
                            1 => { let mut a = [0u8; 4]; if tcp.read_exact(&mut a).await.is_err() { return; } std::net::Ipv4Addr::from(a).to_string() }
                            4 => { let mut a = [0u8; 16]; if tcp.read_exact(&mut a).await.is_err() { return; } std::net::Ipv6Addr::from(a).to_string() }
                            3 => {
                                let mut l = [0u8; 1];
                                if tcp.read_exact(&mut l).await.is_err() { return; }
                                let mut name = vec![0u8; l[0] as usize];
                                if tcp.read_exact(&mut name).await.is_err() { return; }
                                String::from_utf8_lossy(&name).into_owned()
                            }
                            _ => { let _ = tcp.write_all(&[5, 8, 0, 1, 0, 0, 0, 0, 0, 0]).await; return; }
                        };
                        let mut p = [0u8; 2];
                        if tcp.read_exact(&mut p).await.is_err() { return; }
                        (host, u16::from_be_bytes(p), true)
                    }
                    4 => {
                        // SOCKS4 / 4a: CD, DSTPORT, DSTIP, USERID\0 [, DOMAIN\0]
                        let mut rest = [0u8; 7];
                        if tcp.read_exact(&mut rest).await.is_err() { return; }
                        let port = u16::from_be_bytes([rest[1], rest[2]]);
                        let ip = [rest[3], rest[4], rest[5], rest[6]];
                        async fn read_cstr(tcp: &mut tokio::net::TcpStream) -> Option<Vec<u8>> {
                            let mut v = Vec::new();
                            let mut b = [0u8; 1];
                            loop {
                                if tcp.read_exact(&mut b).await.is_err() || v.len() > 512 { return None; }
                                if b[0] == 0 { return Some(v); }
                                v.push(b[0]);
                            }
                        }
                        if read_cstr(&mut tcp).await.is_none() { return; } // user id
                        let host = if ip[0] == 0 && ip[1] == 0 && ip[2] == 0 && ip[3] != 0 {
                            match read_cstr(&mut tcp).await { Some(d) => String::from_utf8_lossy(&d).into_owned(), None => return }
                        } else {
                            std::net::Ipv4Addr::from(ip).to_string()
                        };
                        if rest[0] != 1 { let _ = tcp.write_all(&[0, 91, 0, 0, 0, 0, 0, 0]).await; return; }
                        (host, port, false)
                    }
                    _ => return,
                };
                // ---- open the tunnel ----
                let open = handle.channel_open_direct_tcpip(host.clone(), port as u32, peer.ip().to_string(), peer.port() as u32).await;
                match open {
                    Ok(channel) => {
                        let ok = if v5 { tcp.write_all(&[5, 0, 0, 1, 0, 0, 0, 0, 0, 0]).await } else { tcp.write_all(&[0, 90, 0, 0, 0, 0, 0, 0]).await };
                        if ok.is_err() { return; }
                        let mut stream = channel.into_stream();
                        let _ = tokio::io::copy_bidirectional(&mut tcp, &mut stream).await;
                    }
                    Err(e) => {
                        log::debug!("socks: {host}:{port} failed: {e}");
                        let _ = if v5 { tcp.write_all(&[5, 5, 0, 1, 0, 0, 0, 0, 0, 0]).await } else { tcp.write_all(&[0, 91, 0, 0, 0, 0, 0, 0]).await };
                    }
                }
            });
        }
    });
    Ok(LocalForward { bound, task })
}

/// `-R`: the server listens and connections arrive over the session.
pub struct RemoteForward {
    handle: Arc<Handle<ClientHandler>>,
    targets: Arc<std::sync::Mutex<HashMap<(String, u32), SocketAddr>>>,
    pub remote_host: String,
    pub remote_port: u32,
}

impl RemoteForward {
    pub(crate) fn new(
        handle: Arc<Handle<ClientHandler>>,
        targets: Arc<std::sync::Mutex<HashMap<(String, u32), SocketAddr>>>,
        remote_host: String,
        remote_port: u32,
    ) -> Self {
        Self { handle, targets, remote_host, remote_port }
    }

    pub async fn stop(&self) {
        self.targets.lock().unwrap().remove(&(self.remote_host.clone(), self.remote_port));
        let _ = self.handle.cancel_tcpip_forward(self.remote_host.clone(), self.remote_port).await;
    }
}

// Unused-import guard: Msg is referenced through Handle's generic in other modules.
#[allow(dead_code)]
fn _msg_marker(_: Option<Msg>) {}
