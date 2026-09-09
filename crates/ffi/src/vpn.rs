//! The phone's traffic, carried by one SSH session.
//!
//! Android's `VpnService` hands over a tun descriptor and expects somebody to
//! deal with the packets. [`tun2ssh`] does the work; this is the part that
//! joins it to a live [`Session`] — the session's SSH client becomes the thing
//! that opens a channel per connection, and the tunnel dies with the session
//! it rides on.

use std::net::Ipv4Addr;
use std::sync::Arc;

use ssh_core::SshClient;
use tun2ssh::{Config, Dialer, Router, Stream};

use crate::CoreError;

/// What the tunnel is doing, for the notification and the VPN screen.
#[derive(uniffi::Record, Clone, Debug)]
pub struct VpnStats {
    pub sent: u64,
    pub received: u64,
    /// Connections opened since the tunnel came up.
    pub opened: u64,
    /// Connections carrying traffic right now.
    pub active: u64,
    /// Names looked up through the server.
    pub queries: u64,
}

/// The session's SSH client, as something the router can dial through.
pub(crate) struct SshDialer(pub Arc<SshClient>);

impl Dialer for SshDialer {
    fn dial(
        &self,
        host: String,
        port: u16,
    ) -> std::pin::Pin<Box<dyn std::future::Future<Output = std::io::Result<Box<dyn Stream>>> + Send>> {
        let client = self.0.clone();
        Box::pin(async move {
            let stream = client
                .open_direct_tcpip(&host, port)
                .await
                .map_err(|e| std::io::Error::other(e.to_string()))?;
            Ok(Box::new(stream) as Box<dyn Stream>)
        })
    }
}

/// A running tunnel, kept by the session that owns it.
pub(crate) struct Vpn {
    router: Router,
}

impl Vpn {
    pub(crate) fn start(fd: i32, mtu: u32, resolver: &str, dns_address: &str, client: Arc<SshClient>) -> Result<Vpn, CoreError> {
        let resolver: Ipv4Addr = resolver
            .parse()
            .map_err(|_| CoreError::Other(format!("{resolver} is not an IPv4 address")))?;
        let dns_address: Ipv4Addr = dns_address
            .parse()
            .map_err(|_| CoreError::Other(format!("{dns_address} is not an IPv4 address")))?;
        let config = Config { mtu: mtu as usize, resolver, dns_address };
        let router = Router::start(fd, config, Arc::new(SshDialer(client)))
            .map_err(|e| CoreError::Other(e.to_string()))?;
        Ok(Vpn { router })
    }

    pub(crate) fn stats(&self) -> VpnStats {
        let s = self.router.stats();
        VpnStats { sent: s.sent, received: s.received, opened: s.opened, active: s.active, queries: s.queries }
    }
}
