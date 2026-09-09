//! Key generation and inspection.

use russh::keys::ssh_key::{Algorithm, EcdsaCurve, LineEnding, PrivateKey};
use russh::keys::HashAlg;

use crate::SshError;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum KeyAlgorithm {
    Ed25519,
    EcdsaP256,
    Rsa4096,
}

#[derive(Debug, Clone)]
pub struct GeneratedKey {
    /// OpenSSH private key PEM (optionally passphrase-encrypted).
    pub private_key: String,
    /// `ssh-ed25519 AAAA... comment` line.
    pub public_key: String,
    pub fingerprint: String,
}

pub fn generate(alg: KeyAlgorithm, comment: &str, passphrase: Option<&str>) -> Result<GeneratedKey, SshError> {
    let mut rng = russh::keys::key::safe_rng();
    let algorithm = match alg {
        KeyAlgorithm::Ed25519 => Algorithm::Ed25519,
        KeyAlgorithm::EcdsaP256 => Algorithm::Ecdsa { curve: EcdsaCurve::NistP256 },
        KeyAlgorithm::Rsa4096 => Algorithm::Rsa { hash: None },
    };
    let mut key = PrivateKey::random(&mut rng, algorithm).map_err(|e| SshError::InvalidKey(e.to_string()))?;
    if !comment.is_empty() {
        key.set_comment(comment);
    }
    let public_key = key.public_key().to_openssh().map_err(|e| SshError::InvalidKey(e.to_string()))?;
    let fingerprint = key.fingerprint(HashAlg::Sha256).to_string();
    let private = match passphrase.filter(|p| !p.is_empty()) {
        Some(p) => key.encrypt(&mut rng, p).map_err(|e| SshError::InvalidKey(e.to_string()))?,
        None => key,
    };
    let private_key = private.to_openssh(LineEnding::LF).map_err(|e| SshError::InvalidKey(e.to_string()))?;
    Ok(GeneratedKey { private_key: private_key.to_string(), public_key, fingerprint })
}

/// Parse a private key (OpenSSH, PKCS#8 or PEM) and report its public half.
pub fn inspect(private_key: &str, passphrase: Option<&str>) -> Result<GeneratedKey, SshError> {
    let key = russh::keys::decode_secret_key(private_key, passphrase)?;
    let public_key = key.public_key().to_openssh().map_err(|e| SshError::InvalidKey(e.to_string()))?;
    Ok(GeneratedKey {
        private_key: private_key.to_string(),
        public_key,
        fingerprint: key.fingerprint(HashAlg::Sha256).to_string(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_ed25519() {
        let k = generate(KeyAlgorithm::Ed25519, "test@android", None).unwrap();
        assert!(k.public_key.starts_with("ssh-ed25519 "));
        assert!(k.private_key.contains("BEGIN OPENSSH PRIVATE KEY"));
        let i = inspect(&k.private_key, None).unwrap();
        assert_eq!(i.fingerprint, k.fingerprint);
    }

    #[test]
    fn encrypted_key_needs_passphrase() {
        let k = generate(KeyAlgorithm::Ed25519, "", Some("secret")).unwrap();
        assert!(inspect(&k.private_key, None).is_err());
        assert!(inspect(&k.private_key, Some("secret")).is_ok());
    }
}
