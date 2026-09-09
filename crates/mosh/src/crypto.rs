//! The datagram layer: AES-128-OCB3 under a base64 session key.
//!
//! A datagram on the wire is the 8-byte nonce value followed by the OCB3
//! ciphertext (plaintext plus a 16-byte tag), so the smallest legal datagram is
//! 24 bytes. The nonce fed to OCB3 is 12 bytes: four zero bytes then that same
//! 8-byte value, big-endian. The top bit of the value is the direction flag and
//! the remaining 63 bits are the sequence number.

use aes::Aes128;
use ocb3::aead::{AeadInPlace, KeyInit};
use ocb3::Ocb3;

use crate::MoshError;

/// OCB3 with the 12-byte nonce and 16-byte tag mosh uses.
type Aes128Ocb3 = Ocb3<Aes128, ocb3::consts::U12, ocb3::consts::U16>;

/// Bytes added to the plaintext by the AEAD tag.
pub const TAG_LEN: usize = 16;
/// The nonce value carried on the wire, ahead of the ciphertext.
pub const WIRE_NONCE_LEN: usize = 8;
/// Smallest datagram that could possibly be valid.
pub const MIN_DATAGRAM: usize = WIRE_NONCE_LEN + TAG_LEN;
/// Largest datagram mosh will read.
pub const RECEIVE_MTU: usize = 2048;

/// Which way a datagram travels; it is the top bit of the nonce.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Direction {
    ToServer,
    ToClient,
}

const DIRECTION_MASK: u64 = 1 << 63;
const SEQUENCE_MASK: u64 = !DIRECTION_MASK;

/// The 16-byte session key, carried as 22 base64 characters in `MOSH_KEY`.
#[derive(Clone)]
pub struct Base64Key([u8; 16]);

impl Base64Key {
    /// Parse the `MOSH_KEY` form: 22 base64 characters, no padding, 128 bits.
    pub fn parse(printable: &str) -> Result<Self, MoshError> {
        let printable = printable.trim();
        if printable.len() != 22 {
            return Err(MoshError::Key(format!("key must be 22 characters, got {}", printable.len())));
        }
        // mosh appends the padding that its own encoder leaves off.
        let decoded = base64_decode(&format!("{printable}=="))?;
        let bytes: [u8; 16] = decoded
            .try_into()
            .map_err(|_| MoshError::Key("key must decode to 16 bytes".into()))?;
        Ok(Self(bytes))
    }

    pub fn as_bytes(&self) -> &[u8; 16] {
        &self.0
    }
}

impl std::fmt::Debug for Base64Key {
    /// Never print the key; it is the whole security of the session.
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("Base64Key(<redacted>)")
    }
}

/// Encrypts and decrypts datagrams under one session key.
pub struct Session {
    cipher: Aes128Ocb3,
}

impl Session {
    pub fn new(key: &Base64Key) -> Self {
        Self { cipher: Aes128Ocb3::new(key.as_bytes().into()) }
    }

    /// Wrap `plaintext` as a datagram: 8-byte nonce value, then ciphertext+tag.
    pub fn encrypt(&self, direction: Direction, seq: u64, plaintext: &[u8]) -> Result<Vec<u8>, MoshError> {
        let value = nonce_value(direction, seq);
        let mut buf = plaintext.to_vec();
        self.cipher
            .encrypt_in_place(&nonce_bytes(value).into(), &[], &mut buf)
            .map_err(|_| MoshError::Crypto("encrypt failed".into()))?;
        let mut out = Vec::with_capacity(WIRE_NONCE_LEN + buf.len());
        out.extend_from_slice(&value.to_be_bytes());
        out.extend_from_slice(&buf);
        Ok(out)
    }

    /// Unwrap a datagram, returning its direction, sequence and plaintext.
    pub fn decrypt(&self, datagram: &[u8]) -> Result<(Direction, u64, Vec<u8>), MoshError> {
        if datagram.len() < MIN_DATAGRAM {
            return Err(MoshError::Crypto(format!("datagram of {} bytes is too short", datagram.len())));
        }
        let value = u64::from_be_bytes(datagram[..WIRE_NONCE_LEN].try_into().expect("checked length"));
        let mut buf = datagram[WIRE_NONCE_LEN..].to_vec();
        self.cipher
            .decrypt_in_place(&nonce_bytes(value).into(), &[], &mut buf)
            .map_err(|_| MoshError::Crypto("authentication failed".into()))?;
        let direction = if value & DIRECTION_MASK != 0 { Direction::ToClient } else { Direction::ToServer };
        Ok((direction, value & SEQUENCE_MASK, buf))
    }
}

fn nonce_value(direction: Direction, seq: u64) -> u64 {
    let flag = if matches!(direction, Direction::ToClient) { DIRECTION_MASK } else { 0 };
    flag | (seq & SEQUENCE_MASK)
}

/// The 12-byte OCB nonce: four zero bytes, then the value big-endian.
fn nonce_bytes(value: u64) -> [u8; 12] {
    let mut n = [0u8; 12];
    n[4..].copy_from_slice(&value.to_be_bytes());
    n
}

/// Standard base64 decode; the alphabet mosh uses is the ordinary one.
fn base64_decode(s: &str) -> Result<Vec<u8>, MoshError> {
    const ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let bytes = s.as_bytes();
    if bytes.len() % 4 != 0 {
        return Err(MoshError::Key("base64 length must be a multiple of 4".into()));
    }
    let mut out = Vec::with_capacity(bytes.len() / 4 * 3);
    for chunk in bytes.chunks(4) {
        let mut acc: u32 = 0;
        let mut pad = 0;
        for &c in chunk {
            acc <<= 6;
            if c == b'=' {
                pad += 1;
            } else {
                let v = ALPHABET
                    .iter()
                    .position(|&a| a == c)
                    .ok_or_else(|| MoshError::Key(format!("invalid base64 character {:?}", c as char)))?;
                acc |= v as u32;
            }
        }
        let full = acc.to_be_bytes();
        out.push(full[1]);
        if pad < 2 {
            out.push(full[2]);
        }
        if pad < 1 {
            out.push(full[3]);
        }
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn key() -> Base64Key {
        // 16 zero bytes is "AAAAAAAAAAAAAAAAAAAAAA" in mosh's 22-character form.
        Base64Key::parse("AAAAAAAAAAAAAAAAAAAAAA").unwrap()
    }

    #[test]
    fn parses_a_22_character_key() {
        assert_eq!(key().as_bytes(), &[0u8; 16]);
        let k = Base64Key::parse("/////////////////////w").unwrap();
        assert_eq!(k.as_bytes(), &[0xffu8; 16]);
    }

    #[test]
    fn rejects_a_key_of_the_wrong_length() {
        assert!(Base64Key::parse("tooshort").is_err());
        assert!(Base64Key::parse("AAAAAAAAAAAAAAAAAAAAAAAA").is_err());
    }

    #[test]
    fn the_key_never_prints_itself() {
        assert_eq!(format!("{:?}", key()), "Base64Key(<redacted>)");
    }

    #[test]
    fn nonce_puts_the_direction_in_the_top_bit() {
        assert_eq!(nonce_value(Direction::ToServer, 1), 1);
        assert_eq!(nonce_value(Direction::ToClient, 1), (1 << 63) | 1);
        assert_eq!(nonce_bytes(1), [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1]);
    }

    #[test]
    fn round_trips_a_datagram() {
        let s = Session::new(&key());
        let dg = s.encrypt(Direction::ToServer, 42, b"hello mosh").unwrap();
        assert_eq!(dg.len(), WIRE_NONCE_LEN + b"hello mosh".len() + TAG_LEN);
        let (dir, seq, pt) = s.decrypt(&dg).unwrap();
        assert_eq!(dir, Direction::ToServer);
        assert_eq!(seq, 42);
        assert_eq!(pt, b"hello mosh");
    }

    #[test]
    fn round_trips_the_other_direction_and_an_empty_payload() {
        let s = Session::new(&key());
        let dg = s.encrypt(Direction::ToClient, 0, b"").unwrap();
        assert_eq!(dg.len(), MIN_DATAGRAM);
        let (dir, seq, pt) = s.decrypt(&dg).unwrap();
        assert_eq!((dir, seq, pt.len()), (Direction::ToClient, 0, 0));
    }

    #[test]
    fn a_tampered_datagram_does_not_authenticate() {
        let s = Session::new(&key());
        let mut dg = s.encrypt(Direction::ToServer, 7, b"payload").unwrap();
        let last = dg.len() - 1;
        dg[last] ^= 0x01;
        assert!(s.decrypt(&dg).is_err());
    }

    #[test]
    fn a_different_key_does_not_authenticate() {
        let a = Session::new(&key());
        let b = Session::new(&Base64Key::parse("/////////////////////w").unwrap());
        let dg = a.encrypt(Direction::ToServer, 7, b"payload").unwrap();
        assert!(b.decrypt(&dg).is_err());
    }

    #[test]
    fn short_datagrams_are_rejected_before_the_cipher() {
        let s = Session::new(&key());
        assert!(s.decrypt(&[0u8; MIN_DATAGRAM - 1]).is_err());
    }
}
