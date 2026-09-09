//! SSH keys whose private half lives on a FIDO2 security key.
//!
//! OpenSSH's `sk-*` key types exist because a token will not sign what SSH
//! wants signed: it signs its own attestation blob, and it insists on adding
//! the "user present" flags byte and a use counter that the verifier has to see
//! too. `PROTOCOL.u2f` describes the two resulting wire formats, and this
//! module is those formats and nothing else — everything that has to reach a
//! physical token is behind [`SkAuthenticator`], so USB, NFC and a test's
//! software token all arrive here the same way.
//!
//! The blob a token signs is `sha256(application) ‖ flags ‖ counter ‖
//! sha256(message)`, which is precisely what CTAP2 produces for an
//! `authenticatorGetAssertion` with `rpId = application` and `clientDataHash =
//! sha256(message)`. So there is no SSH-specific request to build: hash the
//! bytes the server asked for and hand the digest over as the client data hash.
//!
//! An `sk-*` key reaches russh through [`crate::Auth::External`]: the signer
//! hands over a finished signature blob and russh writes it into the packet as
//! an SSH string without looking inside. That is what makes this possible at
//! all — the flags byte and counter sit *after* the inner signature, and there
//! is no private key on this side to hand a signing library instead.

use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::Arc;

use russh::keys::signature::Signer;
use russh::keys::ssh_key::{Algorithm, EcdsaCurve, PrivateKey};
use russh::keys::{HashAlg, PublicKey};
use sha2::{Digest, Sha256};

use crate::{ExternalSigner, SshError};

/// The application string OpenSSH uses unless `ssh-keygen -O application=` says
/// otherwise. A token scopes a credential to it the way a browser scopes one to
/// a web origin, so it is part of the identity and not a label.
pub const DEFAULT_APPLICATION: &str = "ssh:";

/// The flag a token sets when somebody actually touched it.
const FLAG_USER_PRESENT: u8 = 0x01;
/// The flag that says authenticator data carries extensions after the counter.
const FLAG_EXTENSION_DATA: u8 = 0x80;
/// `rpIdHash ‖ flags ‖ counter`, with nothing optional in it.
const AUTHENTICATOR_DATA_LEN: usize = 32 + 1 + 4;

/// The signature primitives a FIDO2 token may offer for an SSH key.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SkAlgorithm {
    Ed25519,
    EcdsaP256,
}

impl SkAlgorithm {
    pub fn key_type(self) -> &'static str {
        match self {
            SkAlgorithm::Ed25519 => "sk-ssh-ed25519@openssh.com",
            SkAlgorithm::EcdsaP256 => "sk-ecdsa-sha2-nistp256@openssh.com",
        }
    }

    /// The COSE algorithm identifier the token knows this by, in
    /// `pubKeyCredParams` at enrollment and in the public key it hands back
    /// (RFC 8152: -8 is Ed25519, -7 is ES256).
    pub fn cose_id(self) -> i64 {
        match self {
            SkAlgorithm::Ed25519 => -8,
            SkAlgorithm::EcdsaP256 => -7,
        }
    }

    pub fn from_cose_id(id: i64) -> Option<Self> {
        match id {
            -8 => Some(SkAlgorithm::Ed25519),
            -7 => Some(SkAlgorithm::EcdsaP256),
            _ => None,
        }
    }
}

/// Everything enrollment leaves behind on this side.
///
/// There is no private key here and never will be: the token keeps it, and the
/// credential id is the only handle we have on it. Losing this record loses the
/// key just as thoroughly as losing the token would.
#[derive(Debug, Clone)]
pub struct SkCredential {
    pub algorithm: SkAlgorithm,
    /// Ed25519: the 32-byte public key. ECDSA: the point as `0x04 ‖ X ‖ Y`.
    pub public_key: Vec<u8>,
    /// Passed to the token as the relying party id, so a wrong value here means
    /// the token declines to sign rather than signing something useless.
    pub application: String,
    pub credential_id: Vec<u8>,
}

impl SkCredential {
    /// The public key blob as `PROTOCOL.u2f` lays it out.
    pub fn public_key_blob(&self) -> Result<Vec<u8>, SshError> {
        let mut out = Vec::new();
        write_string(&mut out, self.algorithm.key_type().as_bytes());
        match self.algorithm {
            SkAlgorithm::Ed25519 => {
                if self.public_key.len() != 32 {
                    return Err(SshError::InvalidKey(format!(
                        "an Ed25519 security key is 32 bytes, this one is {}",
                        self.public_key.len()
                    )));
                }
                write_string(&mut out, &self.public_key);
            }
            SkAlgorithm::EcdsaP256 => {
                if self.public_key.len() != 65 || self.public_key[0] != 0x04 {
                    return Err(SshError::InvalidKey(
                        "an ECDSA security key is an uncompressed P-256 point of 65 bytes".into(),
                    ));
                }
                write_string(&mut out, b"nistp256");
                write_string(&mut out, &self.public_key);
            }
        }
        write_string(&mut out, self.application.as_bytes());
        Ok(out)
    }

    /// The key as it goes into `authorized_keys`.
    pub fn openssh_public_key(&self, comment: &str) -> Result<String, SshError> {
        let mut key = self.parsed()?;
        key.set_comment(comment);
        key.to_openssh().map_err(|e| SshError::InvalidKey(e.to_string()))
    }

    pub fn fingerprint(&self) -> Result<String, SshError> {
        Ok(self.parsed()?.fingerprint(HashAlg::Sha256).to_string())
    }

    /// Round-tripping the blob through the key library is also the check that
    /// what enrollment produced is a key at all, so a token that returns
    /// nonsense is caught while somebody is still looking at the enrollment
    /// screen rather than a year later on a connection.
    fn parsed(&self) -> Result<PublicKey, SshError> {
        PublicKey::from_bytes(&self.public_key_blob()?)
            .map_err(|e| SshError::InvalidKey(format!("the security key returned an unusable public key: {e}")))
    }
}

/// One `authenticatorGetAssertion`, as the token answered it.
#[derive(Debug, Clone)]
pub struct SkAssertion {
    /// `rpIdHash ‖ flags ‖ counter`, straight from the token.
    pub authenticator_data: Vec<u8>,
    /// Raw 64 bytes for Ed25519, X9.62 DER for ECDSA — whichever the token uses.
    pub signature: Vec<u8>,
}

/// Whatever can reach a token and get an assertion out of it.
///
/// Called from a blocking thread, and expected to take its time: somebody has
/// to touch the key, and on NFC to hold it against the phone throughout.
pub trait SkAuthenticator: Send + Sync + std::fmt::Debug {
    fn assert(
        &self,
        application: &str,
        credential_id: &[u8],
        client_data_hash: &[u8],
    ) -> Result<SkAssertion, String>;
}

/// Authenticates with a credential the token holds.
#[derive(Debug)]
pub struct SkSigner {
    credential: SkCredential,
    public_key: String,
    token: Arc<dyn SkAuthenticator>,
}

impl SkSigner {
    pub fn new(
        credential: SkCredential,
        comment: &str,
        token: Arc<dyn SkAuthenticator>,
    ) -> Result<Self, SshError> {
        let public_key = credential.openssh_public_key(comment)?;
        Ok(Self { credential, public_key, token })
    }
}

impl ExternalSigner for SkSigner {
    fn public_key(&self) -> String {
        self.public_key.clone()
    }

    fn sign(&self, data: &[u8]) -> Result<Vec<u8>, String> {
        let client_data_hash = Sha256::digest(data);
        let assertion = self.token.assert(
            &self.credential.application,
            &self.credential.credential_id,
            &client_data_hash,
        )?;
        // A token asked for the wrong credential answers for a different key,
        // and the server would reject a signature it cannot attribute. Saying
        // so here names the real problem instead.
        let expected = Sha256::digest(self.credential.application.as_bytes());
        if assertion.authenticator_data.len() >= 32 && assertion.authenticator_data[..32] != expected[..] {
            return Err("the security key signed for a different application than this key was enrolled with".into());
        }
        signature_blob(self.credential.algorithm, &assertion.authenticator_data, &assertion.signature)
            .map_err(|e| e.to_string())
    }
}

/// Assemble the SSH signature blob from what the token returned.
///
/// The flags and counter the server needs are already inside the authenticator
/// data — it hashes them back into the blob it verifies — so this pulls them out
/// rather than asking the caller for them again.
pub fn signature_blob(
    algorithm: SkAlgorithm,
    authenticator_data: &[u8],
    signature: &[u8],
) -> Result<Vec<u8>, SshError> {
    if authenticator_data.len() < AUTHENTICATOR_DATA_LEN {
        return Err(SshError::Other(format!(
            "the security key returned {} bytes of authenticator data, too few to sign with",
            authenticator_data.len()
        )));
    }
    let flags = authenticator_data[32];
    let counter = u32::from_be_bytes([
        authenticator_data[33],
        authenticator_data[34],
        authenticator_data[35],
        authenticator_data[36],
    ]);
    if flags & FLAG_USER_PRESENT == 0 {
        return Err(SshError::AuthFailed(
            "the security key signed without anybody touching it, which no server will accept".into(),
        ));
    }
    // The wire format has no field for extension data, so the server would hash
    // 37 bytes where the token hashed more and the signature would simply not
    // verify. Better to say why than to let it fail as "wrong password".
    if flags & FLAG_EXTENSION_DATA != 0 || authenticator_data.len() > AUTHENTICATOR_DATA_LEN {
        return Err(SshError::Other(
            "the security key added extension data to its signature, which SSH has no way to carry".into(),
        ));
    }

    let inner = match algorithm {
        SkAlgorithm::Ed25519 => {
            if signature.len() != 64 {
                return Err(SshError::Other(format!(
                    "an Ed25519 signature is 64 bytes, the security key returned {}",
                    signature.len()
                )));
            }
            signature.to_vec()
        }
        // The server refuses to parse ASN.1 before authentication, so the two
        // integers are dug out here and re-encoded the way RFC 5656 wants them.
        SkAlgorithm::EcdsaP256 => {
            let (r, s) = der_to_rs(signature)?;
            let mut inner = Vec::new();
            write_string(&mut inner, &mpint(&r));
            write_string(&mut inner, &mpint(&s));
            inner
        }
    };

    let mut out = Vec::new();
    write_string(&mut out, algorithm.key_type().as_bytes());
    write_string(&mut out, &inner);
    out.push(flags);
    out.extend_from_slice(&counter.to_be_bytes());
    Ok(out)
}

/// A FIDO2 token with no hardware under it.
///
/// It exists because the wire formats are the part of `sk-*` that can be got
/// wrong, and they can be proven without a physical key: a token that holds its
/// own key, counts its own uses and signs the same blob a real one would is
/// enough to put an `sk-ssh-ed25519@openssh.com` line in `authorized_keys` and
/// have OpenSSH let us in. What it cannot prove is USB and NFC, which is the
/// whole reason it is separate from them.
#[derive(Debug)]
pub struct SoftwareToken {
    algorithm: SkAlgorithm,
    key: PrivateKey,
    credential_id: Vec<u8>,
    application: String,
    /// A real token never repeats a counter value, and a server that watches it
    /// will disconnect a key that goes backwards, so this one counts too.
    counter: AtomicU32,
    touched: AtomicBool,
}

impl SoftwareToken {
    /// Enroll a fresh credential, the way `authenticatorMakeCredential` would.
    pub fn enroll(algorithm: SkAlgorithm, application: &str) -> Result<Self, SshError> {
        let mut rng = russh::keys::key::safe_rng();
        let inner = match algorithm {
            SkAlgorithm::Ed25519 => Algorithm::Ed25519,
            SkAlgorithm::EcdsaP256 => Algorithm::Ecdsa { curve: EcdsaCurve::NistP256 },
        };
        let key = PrivateKey::random(&mut rng, inner).map_err(|e| SshError::InvalidKey(e.to_string()))?;
        // A real credential id is an opaque handle, often the wrapped private
        // key itself. Nothing here reads it, so what matters is only that it is
        // the length a token's would be and is carried around unchanged.
        let credential_id = Sha256::digest(key.public_key().to_bytes().unwrap_or_default()).to_vec();
        Ok(Self {
            algorithm,
            key,
            credential_id,
            application: application.to_string(),
            counter: AtomicU32::new(0),
            touched: AtomicBool::new(true),
        })
    }

    /// What enrollment would have handed the app to keep.
    pub fn credential(&self) -> Result<SkCredential, SshError> {
        let data = self.key.public_key().key_data();
        let public_key = match self.algorithm {
            SkAlgorithm::Ed25519 => data
                .ed25519()
                .ok_or_else(|| SshError::InvalidKey("not an Ed25519 key".into()))?
                .0
                .to_vec(),
            SkAlgorithm::EcdsaP256 => data
                .ecdsa()
                .ok_or_else(|| SshError::InvalidKey("not an ECDSA key".into()))?
                .as_sec1_bytes()
                .to_vec(),
        };
        Ok(SkCredential {
            algorithm: self.algorithm,
            public_key,
            application: self.application.clone(),
            credential_id: self.credential_id.clone(),
        })
    }

    /// Pretend nobody touches the key, so the caller can see what that produces.
    pub fn set_touched(&self, touched: bool) {
        self.touched.store(touched, Ordering::Relaxed);
    }
}

impl SkAuthenticator for SoftwareToken {
    fn assert(
        &self,
        application: &str,
        credential_id: &[u8],
        client_data_hash: &[u8],
    ) -> Result<SkAssertion, String> {
        if credential_id != self.credential_id {
            // What a real token says when it holds no credential for the request.
            return Err("this security key does not hold that credential".into());
        }
        let flags = if self.touched.load(Ordering::Relaxed) { FLAG_USER_PRESENT } else { 0 };
        let counter = self.counter.fetch_add(1, Ordering::Relaxed) + 1;
        let mut authenticator_data = Sha256::digest(application.as_bytes()).to_vec();
        authenticator_data.push(flags);
        authenticator_data.extend_from_slice(&counter.to_be_bytes());

        let mut signed = authenticator_data.clone();
        signed.extend_from_slice(client_data_hash);
        let signature = self.key.try_sign(&signed).map_err(|e| e.to_string())?;
        let signature = match self.algorithm {
            SkAlgorithm::Ed25519 => signature.as_bytes().to_vec(),
            // A token answers in DER, which is what `signature_blob` unpicks;
            // the key library hands back the two integers in SSH form instead,
            // so they are put back into the shape the token would have used.
            SkAlgorithm::EcdsaP256 => rs_to_der(signature.as_bytes())?,
        };
        Ok(SkAssertion { authenticator_data, signature })
    }
}

/// Turn `mpint r ‖ mpint s` into a DER `SEQUENCE { INTEGER r, INTEGER s }`.
fn rs_to_der(ssh: &[u8]) -> Result<Vec<u8>, String> {
    let mut at = 0usize;
    let string = |at: &mut usize| -> Result<Vec<u8>, String> {
        let head = ssh.get(*at..*at + 4).ok_or("truncated ECDSA signature")?;
        let len = u32::from_be_bytes([head[0], head[1], head[2], head[3]]) as usize;
        *at += 4;
        let end = at.checked_add(len).ok_or("truncated ECDSA signature")?;
        let value = ssh.get(*at..end).ok_or("truncated ECDSA signature")?.to_vec();
        *at = end;
        Ok(value)
    };
    let r = string(&mut at)?;
    let s = string(&mut at)?;
    let mut body = Vec::new();
    for value in [r, s] {
        body.push(0x02);
        body.push(value.len() as u8);
        body.extend_from_slice(&value);
    }
    let mut out = vec![0x30, body.len() as u8];
    out.extend_from_slice(&body);
    Ok(out)
}

fn write_string(out: &mut Vec<u8>, bytes: &[u8]) {
    out.extend_from_slice(&(bytes.len() as u32).to_be_bytes());
    out.extend_from_slice(bytes);
}

/// An SSH mpint: big-endian two's complement, no leading zero unless the top
/// bit would otherwise read as a negative number.
fn mpint(value: &[u8]) -> Vec<u8> {
    let start = value.iter().position(|b| *b != 0).unwrap_or(value.len());
    let trimmed = &value[start..];
    if trimmed.first().is_some_and(|b| b & 0x80 != 0) {
        let mut out = vec![0];
        out.extend_from_slice(trimmed);
        out
    } else {
        trimmed.to_vec()
    }
}

/// Pull r and s out of a DER `SEQUENCE { INTEGER r, INTEGER s }`.
fn der_to_rs(der: &[u8]) -> Result<(Vec<u8>, Vec<u8>), SshError> {
    let bad = || SshError::Other("the security key returned a signature that is not valid DER".into());
    let mut at = 0usize;
    let byte = |at: &mut usize| -> Result<u8, SshError> {
        let b = *der.get(*at).ok_or_else(bad)?;
        *at += 1;
        Ok(b)
    };
    let length = |at: &mut usize| -> Result<usize, SshError> {
        let first = byte(at)?;
        if first & 0x80 == 0 {
            return Ok(first as usize);
        }
        let mut len = 0usize;
        for _ in 0..(first & 0x7F) {
            len = (len << 8) | byte(at)? as usize;
        }
        Ok(len)
    };
    let integer = |at: &mut usize| -> Result<Vec<u8>, SshError> {
        if byte(at)? != 0x02 {
            return Err(bad());
        }
        let len = length(at)?;
        let end = at.checked_add(len).ok_or_else(bad)?;
        let value = der.get(*at..end).ok_or_else(bad)?.to_vec();
        *at = end;
        Ok(value)
    };
    if byte(&mut at)? != 0x30 {
        return Err(bad());
    }
    length(&mut at)?;
    let r = integer(&mut at)?;
    let s = integer(&mut at)?;
    Ok((r, s))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ed25519_credential() -> SkCredential {
        SkCredential {
            algorithm: SkAlgorithm::Ed25519,
            public_key: vec![7u8; 32],
            application: DEFAULT_APPLICATION.into(),
            credential_id: vec![1, 2, 3, 4],
        }
    }

    #[test]
    fn ed25519_public_key_is_what_openssh_reads_back() {
        let line = ed25519_credential().openssh_public_key("token@android").unwrap();
        assert!(line.starts_with("sk-ssh-ed25519@openssh.com AAAA"), "{line}");
        assert!(line.ends_with(" token@android"), "{line}");
        let parsed = PublicKey::from_openssh(&line).unwrap();
        let sk = parsed.key_data().sk_ed25519().expect("an sk-ed25519 key");
        assert_eq!(sk.public_key().as_ref(), &[7u8; 32]);
        assert_eq!(sk.application(), "ssh:");
    }

    #[test]
    fn ecdsa_public_key_carries_the_curve_name() {
        let mut point = vec![0x04];
        point.extend_from_slice(&[9u8; 64]);
        let credential = SkCredential {
            algorithm: SkAlgorithm::EcdsaP256,
            public_key: point,
            application: "ssh:".into(),
            credential_id: vec![],
        };
        let line = credential.openssh_public_key("k").unwrap();
        assert!(line.starts_with("sk-ecdsa-sha2-nistp256@openssh.com "), "{line}");
        let parsed = PublicKey::from_openssh(&line).unwrap();
        assert!(parsed.key_data().is_sk_ecdsa_p256());
    }

    #[test]
    fn a_short_public_key_is_refused_at_enrollment() {
        let mut credential = ed25519_credential();
        credential.public_key = vec![1, 2, 3];
        assert!(credential.openssh_public_key("k").is_err());
    }

    #[test]
    fn ed25519_signature_blob_matches_the_protocol() {
        let mut authenticator_data = vec![0u8; 32];
        authenticator_data.push(FLAG_USER_PRESENT);
        authenticator_data.extend_from_slice(&7u32.to_be_bytes());
        let blob = signature_blob(SkAlgorithm::Ed25519, &authenticator_data, &[3u8; 64]).unwrap();

        let mut expected = Vec::new();
        write_string(&mut expected, b"sk-ssh-ed25519@openssh.com");
        write_string(&mut expected, &[3u8; 64]);
        expected.push(FLAG_USER_PRESENT);
        expected.extend_from_slice(&7u32.to_be_bytes());
        assert_eq!(blob, expected);
    }

    #[test]
    fn ecdsa_signature_blob_reencodes_der_as_mpints() {
        // r = 0x00ff…, s = 0x01: the first needs a leading zero so its top bit
        // does not read as negative, the second must not gain one.
        let der = [0x30, 0x08, 0x02, 0x02, 0x00, 0xff, 0x02, 0x01, 0x01];
        let mut authenticator_data = vec![0u8; 32];
        authenticator_data.push(FLAG_USER_PRESENT);
        authenticator_data.extend_from_slice(&1u32.to_be_bytes());
        let blob = signature_blob(SkAlgorithm::EcdsaP256, &authenticator_data, &der).unwrap();

        let mut inner = Vec::new();
        write_string(&mut inner, &[0x00, 0xff]);
        write_string(&mut inner, &[0x01]);
        let mut expected = Vec::new();
        write_string(&mut expected, b"sk-ecdsa-sha2-nistp256@openssh.com");
        write_string(&mut expected, &inner);
        expected.push(FLAG_USER_PRESENT);
        expected.extend_from_slice(&1u32.to_be_bytes());
        assert_eq!(blob, expected);
    }

    #[test]
    fn an_untouched_key_does_not_produce_a_signature() {
        let mut authenticator_data = vec![0u8; 32];
        authenticator_data.push(0);
        authenticator_data.extend_from_slice(&[0, 0, 0, 1]);
        let err = signature_blob(SkAlgorithm::Ed25519, &authenticator_data, &[0u8; 64]).unwrap_err();
        assert!(err.to_string().contains("touching"), "{err}");
    }

    /// The point of the whole module: what the token signed is what the server
    /// re-hashes, so the key library's own verifier is the honest judge of it.
    #[test]
    fn the_software_token_signs_something_openssh_can_verify() {
        for algorithm in [SkAlgorithm::Ed25519, SkAlgorithm::EcdsaP256] {
            let token = SoftwareToken::enroll(algorithm, DEFAULT_APPLICATION).unwrap();
            let credential = token.credential().unwrap();
            let signer =
                SkSigner::new(credential.clone(), "token@test", Arc::new(token)).unwrap();
            let message = b"the bytes a server asked to have signed";
            let blob = ExternalSigner::sign(&signer, message).unwrap();

            let public = PublicKey::from_openssh(&signer.public_key()).unwrap();
            let signature = russh::keys::ssh_key::Signature::try_from(&blob[..]).unwrap();
            russh::keys::signature::Verifier::verify(public.key_data(), message, &signature).unwrap();
            assert_eq!(signature.algorithm().as_str(), algorithm.key_type());
        }
    }

    /// A token that answers with somebody else's credential has answered for a
    /// different key, and the person needs to hear that rather than "rejected".
    #[test]
    fn a_credential_the_token_does_not_hold_is_refused_by_name() {
        let token = SoftwareToken::enroll(SkAlgorithm::Ed25519, DEFAULT_APPLICATION).unwrap();
        let mut credential = token.credential().unwrap();
        credential.credential_id = vec![0xAA; 32];
        let signer = SkSigner::new(credential, "k", Arc::new(token)).unwrap();
        let err = ExternalSigner::sign(&signer, b"x").unwrap_err();
        assert!(err.contains("does not hold that credential"), "{err}");
    }

    #[test]
    fn the_use_counter_only_goes_up() {
        let token = SoftwareToken::enroll(SkAlgorithm::Ed25519, DEFAULT_APPLICATION).unwrap();
        let id = token.credential().unwrap().credential_id;
        let counter = |n: usize| {
            let mut last = 0u32;
            for _ in 0..n {
                let a = token.assert(DEFAULT_APPLICATION, &id, &[0u8; 32]).unwrap();
                last = u32::from_be_bytes(a.authenticator_data[33..37].try_into().unwrap());
            }
            last
        };
        assert_eq!(counter(1), 1);
        assert_eq!(counter(3), 4);
    }

    #[test]
    fn extension_data_is_reported_rather_than_silently_dropped() {
        let mut authenticator_data = vec![0u8; 32];
        authenticator_data.push(FLAG_USER_PRESENT | FLAG_EXTENSION_DATA);
        authenticator_data.extend_from_slice(&[0, 0, 0, 1]);
        authenticator_data.extend_from_slice(&[0xa0]);
        let err = signature_blob(SkAlgorithm::Ed25519, &authenticator_data, &[0u8; 64]).unwrap_err();
        assert!(err.to_string().contains("extension data"), "{err}");
    }
}
