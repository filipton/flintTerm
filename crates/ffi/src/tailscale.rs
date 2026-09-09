//! Tailscale (tsnet) embedded through libtailscale. Nodes are kept in a
//! registry keyed by profile id, so several tailnets (or several accounts on
//! one) can be configured side by side; a host names the one it is dialled
//! through. Each node is a separate tsnet server with its own state directory,
//! and only the ones a session needs are ever started. Compiled in only with
//! the `tailscale` feature (needs `libtailscale.so`, see build-tailscale.sh);
//! otherwise the same API reports that support is missing.

use crate::CoreError;

#[derive(Debug, Clone, uniffi::Record)]
pub struct TailscalePeer {
    pub name: String,
    pub ips: Vec<String>,
    pub online: bool,
    pub os: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct TailscaleStatus {
    /// "unavailable" (not built in), "stopped", "starting", "needs-login", "running", "error"
    pub state: String,
    pub login_url: Option<String>,
    pub ips: Vec<String>,
    pub self_name: Option<String>,
    pub error: Option<String>,
    pub peers: Vec<TailscalePeer>,
}

#[cfg(feature = "tailscale")]
mod imp {
    use super::*;
    use std::ffi::{CStr, CString};
    use std::io::{BufRead, BufReader};
    use std::os::raw::{c_char, c_int};
    use std::os::unix::io::FromRawFd;
    use std::collections::HashMap;
    use std::sync::Arc;

    use parking_lot::Mutex;

    #[link(name = "tailscale")]
    extern "C" {
        fn TsnetNewServer() -> c_int;
        fn TsnetStart(sd: c_int) -> c_int;
        fn TsnetUp(sd: c_int) -> c_int;
        fn TsnetClose(sd: c_int) -> c_int;
        fn TsnetGetIps(sd: c_int, buf: *mut c_char, buflen: usize) -> c_int;
        fn TsnetErrmsg(sd: c_int, buf: *mut c_char, buflen: usize) -> c_int;
        fn TsnetDial(sd: c_int, network: *mut c_char, addr: *mut c_char, conn_out: *mut c_int) -> c_int;
        fn TsnetLoopback(sd: c_int, addr_out: *mut c_char, addr_len: usize, proxy_cred_out: *mut c_char, local_api_cred_out: *mut c_char) -> c_int;
        fn TsnetSetDir(sd: c_int, s: *mut c_char) -> c_int;
        fn TsnetSetHostname(sd: c_int, s: *mut c_char) -> c_int;
        fn TsnetSetAuthKey(sd: c_int, s: *mut c_char) -> c_int;
        fn TsnetSetControlURL(sd: c_int, s: *mut c_char) -> c_int;
        fn TsnetSetLogFD(sd: c_int, fd: c_int) -> c_int;
        fn TsnetStatusJSON(sd: c_int, json_out: *mut *mut c_char) -> c_int;
        fn TsnetSetInterfaces(spec: *mut c_char) -> c_int;
        fn TsnetSetLogsDir(dir: *mut c_char) -> c_int;
    }

    #[derive(Default)]
    struct Node {
        sd: Option<c_int>,
        state: String,
        login_url: Option<String>,
        error: Option<String>,
        logs: Vec<String>,
    }

    static NODES: Mutex<Option<HashMap<String, Arc<Mutex<Node>>>>> = Mutex::new(None);

    /// The node for `id`, created (stopped) on first mention.
    fn node(id: &str) -> Arc<Mutex<Node>> {
        let mut guard = NODES.lock();
        let map = guard.get_or_insert_with(HashMap::new);
        map.entry(id.to_string())
            .or_insert_with(|| Arc::new(Mutex::new(Node { state: "stopped".into(), ..Default::default() })))
            .clone()
    }

    /// Every node that has been configured, for log capture and shutdown.
    fn all_nodes() -> Vec<Arc<Mutex<Node>>> {
        NODES.lock().as_ref().map(|m| m.values().cloned().collect()).unwrap_or_default()
    }

    fn sd_of(id: &str) -> Option<c_int> {
        node(id).lock().sd
    }

    fn errmsg(sd: c_int) -> String {
        let mut buf = vec![0u8; 1024];
        unsafe {
            if TsnetErrmsg(sd, buf.as_mut_ptr() as *mut c_char, buf.len()) == 0 {
                return CStr::from_ptr(buf.as_ptr() as *const c_char).to_string_lossy().into_owned();
            }
        }
        "unknown tailscale error".into()
    }

    fn cstr(s: &str) -> CString {
        CString::new(s.replace('\0', "")).unwrap()
    }

    /// Record a log line and pick up the interactive login URL when it shows up.
    fn ingest_line(n: &Arc<Mutex<Node>>, line: String) {
        let mut g = n.lock();
        if let Some(pos) = line.find("https://login.tailscale.com/") {
            let url: String = line[pos..].split_whitespace().next().unwrap_or("").trim_end_matches(|c| c == '"' || c == ',' || c == ')').to_string();
            g.login_url = Some(url);
            if g.state != "running" {
                g.state = "needs-login".into();
            }
        }
        if g.logs.len() >= 200 {
            g.logs.remove(0);
        }
        g.logs.push(line);
    }

    /// Go prints panics to fd 2, which Android throws away: mirror stderr into the log.
    ///
    /// This is the whole process's stderr, not only Tailscale's — anything else
    /// in the app that writes to fd 2 lands here too, so the lines are labelled
    /// as what they are rather than blamed on the node.
    fn capture_stderr() {
        static ONCE: std::sync::Once = std::sync::Once::new();
        ONCE.call_once(|| {
            let mut fds = [0 as c_int; 2];
            if unsafe { libc::pipe(fds.as_mut_ptr()) } != 0 {
                return;
            }
            unsafe {
                libc::dup2(fds[1], 2);
                libc::close(fds[1]);
            }
            let reader = unsafe { std::fs::File::from_raw_fd(fds[0]) };
            std::thread::Builder::new()
                .name("stderr-log".into())
                .spawn(move || {
                    for line in BufReader::new(reader).lines().map_while(Result::ok) {
                        log::info!("stderr: {line}");
                        // With several nodes there is no way to tell whose line
                        // this is, so a login URL is offered to whichever node is
                        // waiting for one.
                        for n in all_nodes() {
                            if n.lock().state == "starting" || n.lock().state == "needs-login" {
                                ingest_line(&n, line.clone());
                            }
                        }
                    }
                })
                .ok();
        });
    }

    pub fn configure(id: String, state_dir: String, hostname: String, auth_key: Option<String>, control_url: Option<String>) -> Result<(), CoreError> {
        capture_stderr();
        let n = node(&id);
        let mut g = n.lock();
        if g.sd.is_some() {
            return Ok(()); // already configured; call down() first to change settings
        }
        let sd = unsafe { TsnetNewServer() };
        if sd < 0 {
            return Err(CoreError::Other("tailscale: cannot create node".into()));
        }
        unsafe {
            TsnetSetLogsDir(cstr(&format!("{state_dir}/logs")).into_raw());
            if TsnetSetDir(sd, cstr(&state_dir).into_raw()) != 0 {
                return Err(CoreError::Other(format!("tailscale: {}", errmsg(sd))));
            }
            TsnetSetHostname(sd, cstr(&hostname).into_raw());
            if let Some(k) = auth_key.filter(|k| !k.trim().is_empty()) {
                TsnetSetAuthKey(sd, cstr(k.trim()).into_raw());
            }
            if let Some(u) = control_url.filter(|u| !u.trim().is_empty()) {
                TsnetSetControlURL(sd, cstr(u.trim()).into_raw());
            }
        }
        // Capture tsnet's log output: it is where the login URL shows up.
        let mut fds = [0 as c_int; 2];
        if unsafe { libc::pipe(fds.as_mut_ptr()) } == 0 {
            unsafe { TsnetSetLogFD(sd, fds[1]) };
            let reader = unsafe { std::fs::File::from_raw_fd(fds[0]) };
            let n2 = n.clone();
            std::thread::Builder::new()
                .name("tailscale-log".into())
                .spawn(move || {
                    for line in BufReader::new(reader).lines().map_while(Result::ok) {
                        ingest_line(&n2, line);
                    }
                })
                .ok();
        }
        g.sd = Some(sd);
        g.state = "stopped".into();
        Ok(())
    }

    /// Start the node in the background; `status()` reports progress.
    pub fn up(id: String) -> Result<(), CoreError> {
        let n = node(&id);
        let sd = {
            let mut g = n.lock();
            let sd = g.sd.ok_or_else(|| CoreError::Other("tailscale: not configured".into()))?;
            if g.state == "starting" || g.state == "running" || g.state == "needs-login" {
                return Ok(());
            }
            g.state = "starting".into();
            g.error = None;
            g.login_url = None;
            sd
        };
        std::thread::Builder::new()
            .name("tailscale-up".into())
            .spawn(move || {
                let rc = unsafe { TsnetStart(sd) };
                if rc != 0 {
                    let mut g = n.lock();
                    g.state = "error".into();
                    g.error = Some(errmsg(sd));
                    return;
                }
                let rc = unsafe { TsnetUp(sd) };
                let mut g = n.lock();
                if rc == 0 {
                    g.state = "running".into();
                    g.login_url = None;
                } else {
                    g.state = "error".into();
                    g.error = Some(errmsg(sd));
                }
            })
            .map_err(|e| CoreError::Other(e.to_string()))?;
        Ok(())
    }

    pub fn down(id: String) {
        let n = node(&id);
        let mut g = n.lock();
        if let Some(sd) = g.sd.take() {
            unsafe { TsnetClose(sd) };
        }
        g.state = "stopped".into();
        g.login_url = None;
    }

    /// Stop a node and forget it entirely (the profile was deleted).
    pub fn forget(id: String) {
        down(id.clone());
        if let Some(m) = NODES.lock().as_mut() {
            m.remove(&id);
        }
    }

    pub fn status(id: String) -> TailscaleStatus {
        let n = node(&id);
        let g = n.lock();
        let mut st = TailscaleStatus {
            state: g.state.clone(),
            login_url: g.login_url.clone(),
            ips: vec![],
            self_name: None,
            error: g.error.clone(),
            peers: vec![],
        };
        if let (Some(sd), "running") = (g.sd, g.state.as_str()) {
            drop(g);
            let mut buf = vec![0u8; 512];
            unsafe {
                if TsnetGetIps(sd, buf.as_mut_ptr() as *mut c_char, buf.len()) == 0 {
                    let s = CStr::from_ptr(buf.as_ptr() as *const c_char).to_string_lossy();
                    st.ips = s.split(',').map(|x| x.trim().to_string()).filter(|x| !x.is_empty()).collect();
                }
                let mut json: *mut c_char = std::ptr::null_mut();
                if TsnetStatusJSON(sd, &mut json) == 0 && !json.is_null() {
                    let text = CStr::from_ptr(json).to_string_lossy().into_owned();
                    libc::free(json as *mut libc::c_void);
                    parse_status(&text, &mut st);
                }
            }
        }
        st
    }

    /// Minimal extraction from ipnstate.Status JSON: Self.DNSName and Peer[*].
    fn parse_status(text: &str, st: &mut TailscaleStatus) {
        let v: serde_json::Value = match serde_json::from_str(text) {
            Ok(v) => v,
            Err(_) => return,
        };
        st.self_name = v["Self"]["DNSName"].as_str().map(|s| s.trim_end_matches('.').to_string());
        if let Some(peers) = v["Peer"].as_object() {
            for (_, p) in peers {
                st.peers.push(TailscalePeer {
                    name: p["DNSName"].as_str().map(|s| s.trim_end_matches('.').to_string()).or_else(|| p["HostName"].as_str().map(String::from)).unwrap_or_default(),
                    ips: p["TailscaleIPs"].as_array().map(|a| a.iter().filter_map(|x| x.as_str().map(String::from)).collect()).unwrap_or_default(),
                    online: p["Online"].as_bool().unwrap_or(false),
                    os: p["OS"].as_str().unwrap_or("").to_string(),
                });
            }
            st.peers.sort_by(|a, b| b.online.cmp(&a.online).then(a.name.cmp(&b.name)));
        }
    }

    pub fn logs(id: String) -> Vec<String> {
        node(&id).lock().logs.clone()
    }

    pub fn set_interfaces(spec: String) {
        let c = cstr(&spec);
        unsafe { TsnetSetInterfaces(c.as_ptr() as *mut c_char) };
    }

    /// Start (or reuse) the node's loopback server and return its SOCKS5 proxy.
    ///
    /// This is how a datagram protocol gets onto the tailnet: `dial` hands back
    /// a byte stream, which would destroy datagram boundaries, but the loopback
    /// server is a real SOCKS5 proxy and tailscale's SOCKS5 implements UDP
    /// ASSOCIATE. Returns (host, port, password); the username is always "tsnet".
    pub async fn loopback_proxy(id: String) -> Result<(String, u16, String), CoreError> {
        let sd = sd_of(&id).ok_or_else(|| CoreError::Other("tailscale is not running".into()))?;
        if node(&id).lock().state != "running" {
            return Err(CoreError::Other("tailscale is not connected yet (open VPN & tunnels to log in)".into()));
        }
        tokio::task::spawn_blocking(move || {
            // The credentials are exactly 32 bytes plus a NUL, per libtailscale.
            let mut addr = vec![0u8; 128];
            let mut proxy = vec![0u8; 33];
            let mut local = vec![0u8; 33];
            let rc = unsafe {
                TsnetLoopback(
                    sd,
                    addr.as_mut_ptr() as *mut c_char,
                    addr.len(),
                    proxy.as_mut_ptr() as *mut c_char,
                    local.as_mut_ptr() as *mut c_char,
                )
            };
            if rc != 0 {
                return Err(CoreError::Other(format!("tailscale loopback proxy: {}", errmsg(sd))));
            }
            let take = |v: &[u8]| String::from_utf8_lossy(&v[..v.iter().position(|&b| b == 0).unwrap_or(v.len())]).into_owned();
            let addr = take(&addr);
            let cred = take(&proxy);
            let (host, port) = addr
                .rsplit_once(':')
                .and_then(|(h, p)| p.parse::<u16>().ok().map(|p| (h.trim_matches(['[', ']']).to_string(), p)))
                .ok_or_else(|| CoreError::Other(format!("tailscale loopback address {addr:?} is not host:port")))?;
            Ok((host, port, cred))
        })
        .await
        .map_err(|e| CoreError::Other(e.to_string()))?
    }

    /// Dial `host:port` inside the tailnet. Returns a Unix socket connected to the remote TCP endpoint.
    pub async fn dial(id: String, addr: String) -> Result<tokio::net::UnixStream, CoreError> {
        let sd = sd_of(&id).ok_or_else(|| CoreError::Other("tailscale is not running".into()))?;
        if node(&id).lock().state != "running" {
            return Err(CoreError::Other("tailscale is not connected yet (open VPN & tunnels to log in)".into()));
        }
        let fd = tokio::task::spawn_blocking(move || {
            let mut out: c_int = -1;
            let rc = unsafe { TsnetDial(sd, cstr("tcp").into_raw(), cstr(&addr).into_raw(), &mut out) };
            if rc != 0 {
                Err(CoreError::Other(format!("tailscale dial {addr}: {}", errmsg(sd))))
            } else {
                Ok(out)
            }
        })
        .await
        .map_err(|e| CoreError::Other(e.to_string()))??;
        // SAFETY: the fd is a fresh socketpair end owned by us from here on.
        let std_stream = unsafe { std::os::unix::net::UnixStream::from_raw_fd(fd) };
        std_stream.set_nonblocking(true).map_err(|e| CoreError::Other(e.to_string()))?;
        tokio::net::UnixStream::from_std(std_stream).map_err(|e| CoreError::Other(e.to_string()))
    }

    pub const AVAILABLE: bool = true;
}

#[cfg(not(feature = "tailscale"))]
mod imp {
    use super::*;

    fn missing<T>() -> Result<T, CoreError> {
        Err(CoreError::Other("Tailscale support is not included in this build".into()))
    }

    pub fn configure(_: String, _: String, _: String, _: Option<String>, _: Option<String>) -> Result<(), CoreError> {
        missing()
    }
    pub fn up(_: String) -> Result<(), CoreError> {
        missing()
    }
    pub fn down(_: String) {}
    pub fn forget(_: String) {}
    pub fn status(_: String) -> TailscaleStatus {
        TailscaleStatus { state: "unavailable".into(), login_url: None, ips: vec![], self_name: None, error: None, peers: vec![] }
    }
    pub fn logs(_: String) -> Vec<String> {
        vec![]
    }
    pub fn set_interfaces(_: String) {}
    pub async fn dial(_: String, _: String) -> Result<tokio::net::UnixStream, CoreError> {
        missing()
    }
    pub async fn loopback_proxy(_: String) -> Result<(String, u16, String), CoreError> {
        missing()
    }
    pub const AVAILABLE: bool = false;
}

pub(crate) use imp::{dial, loopback_proxy};

#[uniffi::export]
pub fn tailscale_available() -> bool {
    imp::AVAILABLE
}

/// Set up the node for profile `id`. `state_dir` must be writable and persists
/// that profile's identity; give each profile its own directory.
#[uniffi::export]
pub fn tailscale_configure(id: String, state_dir: String, hostname: String, auth_key: Option<String>, control_url: Option<String>) -> Result<(), CoreError> {
    imp::configure(id, state_dir, hostname, auth_key, control_url)
}

#[uniffi::export]
pub fn tailscale_up(id: String) -> Result<(), CoreError> {
    imp::up(id)
}

#[uniffi::export]
pub fn tailscale_down(id: String) {
    imp::down(id)
}

/// Stop the node and drop it from the registry (its profile was deleted).
#[uniffi::export]
pub fn tailscale_forget(id: String) {
    imp::forget(id)
}

#[uniffi::export]
pub fn tailscale_status(id: String) -> TailscaleStatus {
    imp::status(id)
}

/// Open a TCP connection to something on the tailnet and close it again.
///
/// A tailnet carries no ICMP we can reach from here — the node speaks TCP and
/// UDP through its own stack — so "can I reach it?" is asked with a connection
/// rather than a ping.
#[uniffi::export]
pub fn tailscale_port_check(id: String, host: String, port: u16, timeout_secs: u32) -> Result<crate::tunnel::ProbeResult, CoreError> {
    crate::runtime::block_on(async move {
        let target = format!("{host}:{port}");
        let started = std::time::Instant::now();
        let timeout = std::time::Duration::from_secs(timeout_secs.max(1) as u64);
        match tokio::time::timeout(timeout, imp::dial(id, target.clone())).await {
            Ok(Ok(_stream)) => {
                let ms = started.elapsed().as_millis() as u32;
                Ok(crate::tunnel::ProbeResult { ok: true, detail: format!("{target} is open ({ms} ms)"), millis: Some(ms) })
            }
            Ok(Err(e)) => Ok(crate::tunnel::ProbeResult { ok: false, detail: format!("{target}: {e}"), millis: None }),
            Err(_) => Ok(crate::tunnel::ProbeResult { ok: false, detail: format!("{target} did not answer in {timeout_secs}s"), millis: None }),
        }
    })
}

#[uniffi::export]
pub fn tailscale_logs(id: String) -> Vec<String> {
    imp::logs(id)
}

/// Hand the current network interfaces (Java's NetworkInterface list) to the node;
/// Android does not let apps enumerate them over netlink.
#[uniffi::export]
pub fn tailscale_set_interfaces(spec: String) {
    imp::set_interfaces(spec)
}

