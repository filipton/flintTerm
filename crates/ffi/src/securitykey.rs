//! The app's half of an `sk-*` key: a token Kotlin can reach, over USB or NFC.
//!
//! Only the transport crosses this boundary. Kotlin speaks CTAP2 to whatever is
//! plugged in or held against the phone and hands back the two fields the token
//! answered with; assembling those into an SSH public key or signature stays in
//! [`ssh_core::sk`], so there is exactly one implementation of the wire formats
//! and it is the one the integration test proves against a real sshd.

use std::sync::Arc;

use ssh_core::{SkAlgorithm, SkAssertion, SkAuthenticator, SkCredential};

use crate::CoreError;

/// What a FIDO2 token can sign an SSH key with.
#[derive(Debug, Clone, Copy, uniffi::Enum)]
pub enum SecurityKeyAlgorithm {
    Ed25519,
    EcdsaP256,
}

impl From<SecurityKeyAlgorithm> for SkAlgorithm {
    fn from(a: SecurityKeyAlgorithm) -> Self {
        match a {
            SecurityKeyAlgorithm::Ed25519 => SkAlgorithm::Ed25519,
            SecurityKeyAlgorithm::EcdsaP256 => SkAlgorithm::EcdsaP256,
        }
    }
}

/// Everything enrolling a security key leaves on the phone.
///
/// The private half is not here and cannot be: it stayed on the token. Keep all
/// four fields together — a credential id without its application is a handle
/// the token will refuse to open.
#[derive(Debug, Clone, uniffi::Record)]
pub struct SecurityKeyCredential {
    pub algorithm: SecurityKeyAlgorithm,
    /// Ed25519: the 32-byte public key. ECDSA: `0x04 ‖ X ‖ Y`.
    pub public_key: Vec<u8>,
    /// The relying party id the credential was made under, "ssh:" by default.
    pub application: String,
    pub credential_id: Vec<u8>,
}

impl From<&SecurityKeyCredential> for SkCredential {
    fn from(c: &SecurityKeyCredential) -> Self {
        SkCredential {
            algorithm: c.algorithm.into(),
            public_key: c.public_key.clone(),
            application: c.application.clone(),
            credential_id: c.credential_id.clone(),
        }
    }
}

/// One `authenticatorGetAssertion`, exactly as the token answered it.
#[derive(Debug, Clone, uniffi::Record)]
pub struct SecurityKeyAssertion {
    /// `rpIdHash ‖ flags ‖ counter`, unaltered — the flags and the counter are
    /// hashed into what the server verifies, so nothing here may be tidied up.
    pub authenticator_data: Vec<u8>,
    /// 64 raw bytes for Ed25519, X9.62 DER for ECDSA.
    pub signature: Vec<u8>,
}

/// Implemented on the Kotlin side; whatever can reach the token.
///
/// Called from a blocking thread and allowed to take a long time: somebody has
/// to touch the key, and over NFC hold it against the phone for the whole
/// exchange. An error message is shown to them as it is, so it should say what
/// to do about it.
#[uniffi::export(with_foreign)]
pub trait SecurityKeyToken: Send + Sync + std::fmt::Debug {
    fn assert(
        &self,
        application: String,
        credential_id: Vec<u8>,
        client_data_hash: Vec<u8>,
    ) -> Result<SecurityKeyAssertion, CoreError>;
}

/// Bridges the app's token onto the core's authenticator trait.
pub struct ForeignToken(pub Arc<dyn SecurityKeyToken>);

impl std::fmt::Debug for ForeignToken {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "ForeignToken")
    }
}

impl SkAuthenticator for ForeignToken {
    fn assert(
        &self,
        application: &str,
        credential_id: &[u8],
        client_data_hash: &[u8],
    ) -> Result<SkAssertion, String> {
        let answer = self
            .0
            .assert(application.to_string(), credential_id.to_vec(), client_data_hash.to_vec())
            .map_err(|e| e.to_string())?;
        Ok(SkAssertion { authenticator_data: answer.authenticator_data, signature: answer.signature })
    }
}

/// The `authorized_keys` line for a freshly enrolled credential.
///
/// Enrollment calls this while the person is still looking at the screen, so a
/// token that answered with something unusable is caught there rather than on a
/// connection weeks later.
#[uniffi::export]
pub fn security_key_public_key(credential: SecurityKeyCredential, comment: String) -> Result<String, CoreError> {
    Ok(SkCredential::from(&credential).openssh_public_key(&comment)?)
}

#[uniffi::export]
pub fn security_key_fingerprint(credential: SecurityKeyCredential) -> Result<String, CoreError> {
    Ok(SkCredential::from(&credential).fingerprint()?)
}
