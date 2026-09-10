//! uniffi surface consumed by the Android app.
//!
//! All functions are synchronous: long operations (connect, SFTP transfers)
//! block the calling thread on the shared tokio runtime and report progress
//! through foreign callbacks, so the Kotlin side just calls them from
//! `Dispatchers.IO`. Terminal output flows the other way via
//! [`SessionListener::on_damage`] and a packed [`Session::snapshot`].

uniffi::setup_scaffolding!();

mod command_watch;
mod links;
mod emulator;
mod external;
mod mosh;
mod putty;
mod runtime;
mod securitykey;
mod session;
mod sftp;
mod tunnel;
mod tailscale;
mod vault;
mod telnet;
mod transport;
mod vpn;

pub use external::ExternalSink;
pub use putty::*;
pub use securitykey::*;
pub use session::*;
pub use sftp::*;
pub use tunnel::*;
pub use vault::*;
pub use tailscale::*;
pub use vpn::VpnStats;

#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum CoreError {
    #[error("{0}")]
    Ssh(String),
    #[error("connection timed out")]
    Timeout,
    #[error("host key rejected")]
    HostKeyRejected,
    #[error("authentication failed: {0}")]
    Auth(String),
    #[error("invalid key: {0}")]
    InvalidKey(String),
    #[error("sftp: {0}")]
    Sftp(String),
    #[error("not connected")]
    NotConnected,
    #[error("wrong passphrase, or the file has been damaged")]
    WrongPassphrase,
    #[error("that file is not a backup made by this app")]
    NotAVault,
    #[error("{0}")]
    Other(String),
}

impl From<ssh_core::SshError> for CoreError {
    fn from(e: ssh_core::SshError) -> Self {
        use ssh_core::SshError as E;
        match e {
            E::Timeout => CoreError::Timeout,
            E::HostKeyRejected => CoreError::HostKeyRejected,
            E::AuthFailed(s) => CoreError::Auth(s),
            // Auth, so that nothing tries the next key or the next address and
            // puts the same question up again.
            E::AuthCancelled(s) => CoreError::Auth(s),
            E::InvalidKey(s) => CoreError::InvalidKey(s),
            E::Sftp(s) => CoreError::Sftp(s),
            E::Ssh(inner) => CoreError::Ssh(inner.to_string()),
            E::Io(inner) => CoreError::Ssh(inner.to_string()),
            E::Other(s) => CoreError::Other(s),
        }
    }
}

#[uniffi::export]
pub fn init_logging(verbose: bool) {
    #[cfg(target_os = "android")]
    {
        let level = if verbose { log::LevelFilter::Debug } else { log::LevelFilter::Info };
        android_logger::init_once(android_logger::Config::default().with_max_level(level).with_tag("flintterm"));
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = verbose;
    }
    std::panic::set_hook(Box::new(|info| {
        log::error!("rust panic: {info}");
    }));
    log::info!("flintterm core initialised");
}

#[derive(Debug, Clone, Copy, uniffi::Enum)]
pub enum KeyAlgorithm {
    Ed25519,
    EcdsaP256,
    Rsa4096,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct KeyInfo {
    pub private_key: String,
    pub public_key: String,
    pub fingerprint: String,
}

#[uniffi::export]
pub fn generate_key(algorithm: KeyAlgorithm, comment: String, passphrase: Option<String>) -> Result<KeyInfo, CoreError> {
    let alg = match algorithm {
        KeyAlgorithm::Ed25519 => ssh_core::keys::KeyAlgorithm::Ed25519,
        KeyAlgorithm::EcdsaP256 => ssh_core::keys::KeyAlgorithm::EcdsaP256,
        KeyAlgorithm::Rsa4096 => ssh_core::keys::KeyAlgorithm::Rsa4096,
    };
    let k = ssh_core::keys::generate(alg, &comment, passphrase.as_deref())?;
    Ok(KeyInfo { private_key: k.private_key, public_key: k.public_key, fingerprint: k.fingerprint })
}

#[uniffi::export]
pub fn inspect_key(private_key: String, passphrase: Option<String>) -> Result<KeyInfo, CoreError> {
    let k = ssh_core::keys::inspect(&private_key, passphrase.as_deref())?;
    Ok(KeyInfo { private_key: k.private_key, public_key: k.public_key, fingerprint: k.fingerprint })
}

/// Whether a certificate's window contains the moment it was read.
#[derive(Debug, Clone, Copy, uniffi::Enum)]
pub enum CertValidity {
    /// Issued for later, which on a phone usually means the clock is wrong.
    NotYetValid,
    Current,
    Expired,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct CertificateInfo {
    pub key_type: String,
    /// The CA's name for who this was issued to; it is what the server logs.
    pub key_id: String,
    /// Accounts it may log in as. Empty means any of them.
    pub principals: Vec<String>,
    /// Unix seconds, clamped: a certificate with no expiry reports `i64::MAX`.
    pub valid_after: i64,
    pub valid_before: i64,
    pub serial: u64,
    /// Host certificates identify servers, so one attached to a key here would
    /// never log anyone in.
    pub host: bool,
    pub ca_fingerprint: String,
    pub comment: String,
    pub validity: CertValidity,
}

/// Read an OpenSSH certificate line, failing with a reason a person can act on.
///
/// Called the moment a certificate is attached to a key rather than when a
/// connection is attempted: a bad paste should be a message next to the field,
/// not an authentication failure an hour later. When `public_key` is given, the
/// certificate must also be the one issued for that key.
#[uniffi::export]
pub fn inspect_certificate(certificate: String, public_key: Option<String>) -> Result<CertificateInfo, CoreError> {
    let cert = ssh_core::cert::parse(&certificate)?;
    if let Some(key) = public_key.as_deref().map(str::trim).filter(|k| !k.is_empty()) {
        if !ssh_core::cert::certifies(&cert, key)? {
            return Err(CoreError::InvalidKey(
                "this certificate was issued for a different key — ask the CA for one for this key's public half".into(),
            ));
        }
    }
    let info = ssh_core::cert::describe(&cert);
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs().min(i64::MAX as u64) as i64)
        .unwrap_or(0);
    let validity = match ssh_core::cert::validity(&info, now) {
        ssh_core::Validity::NotYet => CertValidity::NotYetValid,
        ssh_core::Validity::Current => CertValidity::Current,
        ssh_core::Validity::Expired => CertValidity::Expired,
    };
    Ok(CertificateInfo {
        key_type: info.key_type,
        key_id: info.key_id,
        principals: info.principals,
        valid_after: info.valid_after,
        valid_before: info.valid_before,
        serial: info.serial,
        host: info.host,
        ca_fingerprint: info.ca_fingerprint,
        comment: info.comment,
        validity,
    })
}

/// Snapshot layout constants, exposed so the renderer never hardcodes them.
#[uniffi::export]
pub fn snapshot_header_bytes() -> u32 {
    term_core::HEADER_BYTES as u32
}

#[uniffi::export]
pub fn snapshot_cell_bytes() -> u32 {
    term_core::CELL_BYTES as u32
}
