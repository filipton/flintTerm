//! The backup file: everything the app knows, sealed under a passphrase.
//!
//! The format is deliberately small enough to describe in a paragraph, so a
//! backup made today can be opened in ten years with a few lines of any
//! language that has Argon2 and XChaCha20-Poly1305:
//!
//! ```text
//! "ATVAULT1"      8 bytes   magic and format version
//! m_cost          u32 LE    Argon2id memory, in KiB
//! t_cost          u32 LE    Argon2id passes
//! p_cost          u32 LE    Argon2id lanes
//! salt            16 bytes
//! nonce           24 bytes
//! ciphertext      the rest  XChaCha20-Poly1305 over the JSON, tag last
//! ```
//!
//! Everything before the ciphertext is the associated data, so a file whose
//! header has been edited fails the same way as one whose body has.
//!
//! The parameters are written into the file rather than assumed, so a future
//! build can raise them without stranding old backups — and so a file can say
//! what it needs, within limits, rather than the reader guessing.

use argon2::{Algorithm, Argon2, Params, Version};
use chacha20poly1305::aead::{Aead, KeyInit, Payload};
use chacha20poly1305::{XChaCha20Poly1305, XNonce};
use rand::RngCore;

use crate::CoreError;

const MAGIC: &[u8; 8] = b"ATVAULT1";
const SALT_LEN: usize = 16;
const NONCE_LEN: usize = 24;
const HEADER_LEN: usize = MAGIC.len() + 12 + SALT_LEN + NONCE_LEN;

/// What a new file is sealed with: 64 MiB, three passes, one lane.
///
/// Heavier than the OWASP floor, light enough that a phone finishes in about a
/// second — a backup is made rarely and opened even more rarely, so the wait is
/// paid where it buys the most.
const M_COST_KIB: u32 = 64 * 1024;
const T_COST: u32 = 3;
const P_COST: u32 = 1;

/// The most a file may ask a reader for, so a hostile or damaged header cannot
/// turn "open backup" into an out-of-memory crash.
const MAX_M_COST_KIB: u32 = 256 * 1024;
const MAX_T_COST: u32 = 32;
const MAX_P_COST: u32 = 8;

/// Seal `plaintext` under `passphrase` into a file in the format above.
#[uniffi::export]
pub fn seal_vault(passphrase: String, plaintext: Vec<u8>) -> Result<Vec<u8>, CoreError> {
    if passphrase.is_empty() {
        return Err(CoreError::Other("a backup needs a passphrase".into()));
    }
    let mut salt = [0u8; SALT_LEN];
    let mut nonce = [0u8; NONCE_LEN];
    rand::thread_rng().fill_bytes(&mut salt);
    rand::thread_rng().fill_bytes(&mut nonce);

    let mut header = Vec::with_capacity(HEADER_LEN);
    header.extend_from_slice(MAGIC);
    header.extend_from_slice(&M_COST_KIB.to_le_bytes());
    header.extend_from_slice(&T_COST.to_le_bytes());
    header.extend_from_slice(&P_COST.to_le_bytes());
    header.extend_from_slice(&salt);
    header.extend_from_slice(&nonce);

    let key = derive(passphrase.as_bytes(), &salt, M_COST_KIB, T_COST, P_COST)?;
    let sealed = XChaCha20Poly1305::new((&key).into())
        .encrypt(XNonce::from_slice(&nonce), Payload { msg: &plaintext, aad: &header })
        .map_err(|_| CoreError::Other("could not encrypt the backup".into()))?;

    let mut out = header;
    out.extend_from_slice(&sealed);
    Ok(out)
}

/// Open a file made by [`seal_vault`], telling a wrong passphrase apart from a
/// file that was never a backup.
#[uniffi::export]
pub fn open_vault(passphrase: String, sealed: Vec<u8>) -> Result<Vec<u8>, CoreError> {
    if !looks_sealed(&sealed) || sealed.len() < HEADER_LEN + 16 {
        return Err(CoreError::NotAVault);
    }
    let (header, body) = sealed.split_at(HEADER_LEN);
    let word = |at: usize| u32::from_le_bytes(header[at..at + 4].try_into().unwrap());
    let (m_cost, t_cost, p_cost) = (word(8), word(12), word(16));
    if m_cost > MAX_M_COST_KIB || t_cost > MAX_T_COST || p_cost > MAX_P_COST {
        return Err(CoreError::Other(format!(
            "this backup asks for {} MiB of memory to open, more than this app will use",
            m_cost / 1024
        )));
    }
    let salt = &header[20..20 + SALT_LEN];
    let nonce = &header[20 + SALT_LEN..HEADER_LEN];

    let key = derive(passphrase.as_bytes(), salt, m_cost, t_cost, p_cost)?;
    XChaCha20Poly1305::new((&key).into())
        .decrypt(XNonce::from_slice(nonce), Payload { msg: body, aad: header })
        .map_err(|_| CoreError::WrongPassphrase)
}

/// Whether `bytes` begin the way a backup does — enough to pick a file
/// apart from a stray download before asking anybody for a passphrase.
#[uniffi::export]
pub fn is_vault(bytes: Vec<u8>) -> bool {
    looks_sealed(&bytes)
}

fn looks_sealed(bytes: &[u8]) -> bool {
    bytes.len() >= MAGIC.len() && &bytes[..MAGIC.len()] == MAGIC
}

fn derive(passphrase: &[u8], salt: &[u8], m_cost: u32, t_cost: u32, p_cost: u32) -> Result<[u8; 32], CoreError> {
    let params = Params::new(m_cost, t_cost, p_cost, Some(32))
        .map_err(|e| CoreError::Other(format!("bad Argon2 parameters: {e}")))?;
    let mut key = [0u8; 32];
    Argon2::new(Algorithm::Argon2id, Version::V0x13, params)
        .hash_password_into(passphrase, salt, &mut key)
        .map_err(|e| CoreError::Other(format!("key derivation failed: {e}")))?;
    Ok(key)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trips_under_the_same_passphrase() {
        let sealed = seal_vault("correct horse".into(), b"{\"hosts\":[]}".to_vec()).unwrap();
        assert!(is_vault(sealed.clone()));
        assert_eq!(open_vault("correct horse".into(), sealed).unwrap(), b"{\"hosts\":[]}");
    }

    #[test]
    fn a_wrong_passphrase_is_named_as_such() {
        let sealed = seal_vault("right".into(), b"secret".to_vec()).unwrap();
        assert!(matches!(open_vault("wrong".into(), sealed), Err(CoreError::WrongPassphrase)));
    }

    #[test]
    fn a_flipped_byte_anywhere_is_refused() {
        let sealed = seal_vault("pw".into(), b"payload that is long enough".to_vec()).unwrap();
        // The header (parameters, salt, nonce) is covered as associated data,
        // the body by the tag: every position must fail, not just the body.
        for at in [9, 21, 40, HEADER_LEN + 3, sealed.len() - 1] {
            let mut bad = sealed.clone();
            bad[at] ^= 0x01;
            assert!(matches!(open_vault("pw".into(), bad), Err(CoreError::WrongPassphrase)), "byte {at}");
        }
    }

    #[test]
    fn something_that_is_not_a_backup_says_so_before_any_key_derivation() {
        assert!(matches!(open_vault("pw".into(), b"just a text file".to_vec()), Err(CoreError::NotAVault)));
        assert!(matches!(open_vault("pw".into(), b"ATVAULT1".to_vec()), Err(CoreError::NotAVault)));
        assert!(!is_vault(b"ATVAULT2....".to_vec()));
    }

    #[test]
    fn a_header_demanding_absurd_memory_is_refused_rather_than_obeyed() {
        let mut sealed = seal_vault("pw".into(), b"x".to_vec()).unwrap();
        sealed[8..12].copy_from_slice(&(8 * 1024 * 1024u32).to_le_bytes());
        let err = open_vault("pw".into(), sealed).unwrap_err();
        assert!(err.to_string().contains("8192 MiB"), "{err}");
    }

    #[test]
    fn every_file_gets_its_own_salt_and_nonce() {
        let a = seal_vault("pw".into(), b"same".to_vec()).unwrap();
        let b = seal_vault("pw".into(), b"same".to_vec()).unwrap();
        assert_ne!(a[20..HEADER_LEN], b[20..HEADER_LEN]);
        assert_ne!(a[HEADER_LEN..], b[HEADER_LEN..]);
    }

    #[test]
    fn an_empty_passphrase_is_not_accepted() {
        assert!(seal_vault(String::new(), b"x".to_vec()).is_err());
    }
}
