//! OpenSSH certificates: what a CA hands out instead of a line in
//! `authorized_keys`.
//!
//! A certificate is the CA's signature over a public key plus the terms it is
//! good for — which accounts, and until when. The server trusts the CA and has
//! never heard of the key itself, so the key alone would be turned away; what
//! goes on the wire is the certificate, signed by the key it certifies.
//!
//! Nothing here writes any of that format: `ssh_key` parses it and `russh`
//! sends it. This is the reading side — enough to refuse a bad paste with a
//! reason, and to say what a certificate is for before anyone relies on it.

use russh::keys::ssh_key::certificate::CertType;
use russh::keys::{Certificate, HashAlg, PublicKey};

use crate::SshError;

/// The type name every user/host certificate ends in.
const CERT_SUFFIX: &str = "-cert-v01@openssh.com";

/// What a certificate says about itself.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CertificateInfo {
    /// `ssh-ed25519-cert-v01@openssh.com` and friends.
    pub key_type: String,
    /// The CA's name for whoever this was issued to; it is what ends up in the
    /// server's logs.
    pub key_id: String,
    /// Accounts (or, for a host certificate, host names) it is good for. Empty
    /// means every one of them, which OpenSSH treats as a wildcard.
    pub principals: Vec<String>,
    /// Unix seconds. A certificate with no expiry carries `u64::MAX`, which
    /// would not survive the trip to a signed integer, so both ends are clamped.
    pub valid_after: i64,
    pub valid_before: i64,
    pub serial: u64,
    /// Host certificates identify servers, not people: one attached to an
    /// identity here would never authenticate anything.
    pub host: bool,
    /// `SHA256:…` of the signing CA, so a user can check it against the CA they
    /// were told to expect.
    pub ca_fingerprint: String,
    pub comment: String,
}

/// Whether a certificate's validity window contains `now` (Unix seconds).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Validity {
    /// Issued for later — usually a clock that disagrees with the CA's.
    NotYet,
    Current,
    Expired,
}

/// Parse an OpenSSH certificate line ("ssh-ed25519-cert-v01@openssh.com AAAA… comment").
///
/// The errors matter more than usual here: a certificate is pasted by hand or
/// picked out of a downloads folder, and the two things that arrive by mistake —
/// the plain public key, or the private key next to it — are worth naming rather
/// than reporting as a base64 problem.
pub fn parse(text: &str) -> Result<Certificate, SshError> {
    let text = text.trim();
    let first = text.split_whitespace().next().unwrap_or_default();
    if text.is_empty() {
        return Err(SshError::InvalidKey("there is no certificate here".into()));
    }
    if text.contains("PRIVATE KEY") {
        return Err(SshError::InvalidKey(
            "that is a private key, not a certificate — a certificate is the one-line file the CA signed, usually ending in -cert.pub".into(),
        ));
    }
    if !first.contains(CERT_SUFFIX) {
        return Err(SshError::InvalidKey(format!(
            "that is a {first} key, not a certificate — a certificate's type ends in {CERT_SUFFIX}"
        )));
    }
    Certificate::from_openssh(text).map_err(|e| SshError::InvalidKey(format!("this certificate does not parse: {e}")))
}

/// Read a certificate without keeping it, for a UI that wants to show what it is.
pub fn inspect(text: &str) -> Result<CertificateInfo, SshError> {
    Ok(describe(&parse(text)?))
}

pub fn describe(cert: &Certificate) -> CertificateInfo {
    CertificateInfo {
        key_type: cert.algorithm().to_certificate_type().to_string(),
        key_id: cert.key_id().to_string(),
        principals: cert.valid_principals().to_vec(),
        valid_after: clamp_time(cert.valid_after()),
        valid_before: clamp_time(cert.valid_before()),
        serial: cert.serial(),
        host: cert.cert_type() == CertType::Host,
        ca_fingerprint: PublicKey::from(cert.signature_key().clone()).fingerprint(HashAlg::Sha256).to_string(),
        comment: cert.comment().to_string(),
    }
}

/// True when this certificate is the one for `public_key` (an OpenSSH one-line
/// public key).
///
/// Worth asking before storing one: a certificate for a different key is
/// accepted by every parser and then rejected by every server, which is a long
/// way to travel for a paste error.
pub fn certifies(cert: &Certificate, public_key: &str) -> Result<bool, SshError> {
    let key = PublicKey::from_openssh(public_key.trim())
        .map_err(|e| SshError::InvalidKey(format!("this key is not a usable SSH public key: {e}")))?;
    Ok(cert.public_key() == key.key_data())
}

pub fn validity(info: &CertificateInfo, now: i64) -> Validity {
    if now < info.valid_after {
        Validity::NotYet
    } else if now > info.valid_before {
        Validity::Expired
    } else {
        Validity::Current
    }
}

/// Unix seconds an `i64` can hold. "Forever" is `u64::MAX` on the wire, and the
/// far end of an i64 is just as far away as anyone needs.
fn clamp_time(t: u64) -> i64 {
    t.min(i64::MAX as u64) as i64
}

#[cfg(test)]
mod tests {
    use super::*;
    use russh::keys::ssh_key::certificate::Builder;
    use russh::keys::ssh_key::{Algorithm, PrivateKey};

    fn issue(principals: &[&str], valid_after: u64, valid_before: u64, host: bool) -> (String, String) {
        let mut rng = russh::keys::key::safe_rng();
        let ca = PrivateKey::random(&mut rng, Algorithm::Ed25519).unwrap();
        let user = PrivateKey::random(&mut rng, Algorithm::Ed25519).unwrap();
        let mut builder = Builder::new_with_random_nonce(&mut rng, user.public_key(), valid_after, valid_before).unwrap();
        builder.serial(7).unwrap();
        builder.key_id("someone@example.com").unwrap();
        builder.cert_type(if host { CertType::Host } else { CertType::User }).unwrap();
        for p in principals {
            builder.valid_principal(*p).unwrap();
        }
        let cert = builder.sign(&ca).unwrap();
        (cert.to_openssh().unwrap(), user.public_key().to_openssh().unwrap())
    }

    #[test]
    fn reads_what_the_ca_wrote() {
        let (text, _) = issue(&["deploy", "root"], 1_000, 2_000, false);
        let info = inspect(&text).unwrap();
        assert_eq!(info.key_type, "ssh-ed25519-cert-v01@openssh.com");
        assert_eq!(info.key_id, "someone@example.com");
        assert_eq!(info.principals, vec!["deploy".to_string(), "root".to_string()]);
        assert_eq!(info.valid_after, 1_000);
        assert_eq!(info.valid_before, 2_000);
        assert_eq!(info.serial, 7);
        assert!(!info.host);
        assert!(info.ca_fingerprint.starts_with("SHA256:"));
    }

    #[test]
    fn a_host_certificate_says_so() {
        let (text, _) = issue(&["files.example.com"], 0, u64::MAX, true);
        let info = inspect(&text).unwrap();
        assert!(info.host);
        // No expiry has to survive the trip out of u64 rather than wrapping negative.
        assert_eq!(info.valid_before, i64::MAX);
    }

    #[test]
    fn only_the_key_it_was_issued_for() {
        let (text, public) = issue(&["deploy"], 0, u64::MAX, false);
        let cert = parse(&text).unwrap();
        assert!(certifies(&cert, &public).unwrap());
        let other = PrivateKey::random(&mut russh::keys::key::safe_rng(), Algorithm::Ed25519).unwrap();
        assert!(!certifies(&cert, &other.public_key().to_openssh().unwrap()).unwrap());
    }

    #[test]
    fn the_window_is_checked_against_the_clock() {
        let (text, _) = issue(&["deploy"], 1_000, 2_000, false);
        let info = inspect(&text).unwrap();
        assert_eq!(validity(&info, 500), Validity::NotYet);
        assert_eq!(validity(&info, 1_500), Validity::Current);
        assert_eq!(validity(&info, 5_000), Validity::Expired);
    }

    #[test]
    fn a_public_key_is_not_a_certificate() {
        let key = PrivateKey::random(&mut russh::keys::key::safe_rng(), Algorithm::Ed25519).unwrap();
        let e = parse(&key.public_key().to_openssh().unwrap()).unwrap_err().to_string();
        assert!(e.contains("not a certificate"), "{e}");
    }

    #[test]
    fn a_private_key_is_named_as_such() {
        let key = PrivateKey::random(&mut russh::keys::key::safe_rng(), Algorithm::Ed25519).unwrap();
        let pem = key.to_openssh(russh::keys::ssh_key::LineEnding::LF).unwrap();
        let e = parse(&pem).unwrap_err().to_string();
        assert!(e.contains("private key"), "{e}");
    }

    #[test]
    fn nonsense_is_refused_rather_than_guessed_at() {
        assert!(parse("").is_err());
        assert!(parse("ssh-ed25519-cert-v01@openssh.com not-base64").is_err());
    }
}
