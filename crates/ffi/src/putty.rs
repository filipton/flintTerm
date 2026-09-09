//! PuTTY private keys (`.ppk`), turned into the OpenSSH text the app already reads.
//!
//! A `.ppk` is a short RFC822-ish header block followed by two base64 bodies:
//!
//! ```text
//! PuTTY-User-Key-File-3: ssh-ed25519    format version, and the key's algorithm
//! Encryption: aes256-cbc                or "none"
//! Comment: me@laptop
//! Public-Lines: 2                       …then that many lines of base64
//! Key-Derivation: Argon2id              v3 only, with the three Argon2-* lines
//! Argon2-Memory: 8192
//! Argon2-Passes: 13
//! Argon2-Parallelism: 1
//! Argon2-Salt: 86473f99854a71169ddd1db937c9047f
//! Private-Lines: 1
//! Private-MAC: 0201d325…
//! ```
//!
//! The two versions differ only in how the passphrase becomes keys. v2 hashes
//! it with SHA-1 — twice for the AES key, once more with a fixed prefix for the
//! MAC key — and uses an all-zero IV. v3 replaced all of that with a single
//! Argon2 call whose 80-byte output is cut into the AES key, the IV and the MAC
//! key, with the parameters written into the file so a reader never has to
//! guess them.
//!
//! The MAC is what tells a wrong passphrase from a damaged file: it covers the
//! *decrypted* private blob, so the only way to check a passphrase is to spend
//! it. That is why [`import_ppk`] reports [`CoreError::WrongPassphrase`] rather
//! than letting a garbage key blob fail much later, at a login.

use aes::Aes256;
use argon2::{Algorithm, Argon2, Params, Version};
use cbc::cipher::block_padding::NoPadding;
use cbc::cipher::{BlockModeDecrypt, KeyIvInit};
use hmac::{Hmac, KeyInit, Mac};
use russh::keys::ssh_key::encoding::base64::{Base64, Encoding};
use russh::keys::ssh_key::encoding::Decode;
use russh::keys::ssh_key::private::{KeypairData, PrivateKey};
use russh::keys::ssh_key::LineEnding;
use sha1::{Digest, Sha1};
use sha2::Sha256;

use crate::CoreError;

/// The most Argon2 memory a file may ask for, so a damaged or hostile header
/// cannot turn "import a key" into an out-of-memory kill. PuTTY's own default
/// is 8 MiB; anything near this ceiling was already unusual.
const MAX_ARGON2_MEMORY_KIB: u32 = 256 * 1024;

/// Read a PuTTY key and return it as an unencrypted OpenSSH private key.
///
/// The passphrase is spent here and not kept: what comes back is the plain
/// OpenSSH text, which the caller stores the same way it stores any imported
/// key, under whatever protection the app applies to all of them.
#[uniffi::export]
pub fn import_ppk(bytes: Vec<u8>, passphrase: String) -> Result<String, CoreError> {
    let text = String::from_utf8(bytes).map_err(|_| not_a_ppk())?;
    let file = PpkFile::parse(&text)?;
    let private = file.decrypt(&passphrase)?;
    file.check_mac(&passphrase, &private)?;
    file.to_openssh(&private)
}

fn not_a_ppk() -> CoreError {
    CoreError::InvalidKey("that file is not a PuTTY private key".into())
}

struct PpkFile {
    version: u8,
    algorithm: String,
    encryption: String,
    comment: String,
    public: Vec<u8>,
    private: Vec<u8>,
    mac: String,
    argon2: Option<Argon2Params>,
}

struct Argon2Params {
    algorithm: Algorithm,
    memory_kib: u32,
    passes: u32,
    parallelism: u32,
    salt: Vec<u8>,
}

impl PpkFile {
    fn parse(text: &str) -> Result<Self, CoreError> {
        // Lines rather than a streaming reader: a key file is a few kilobytes,
        // and the base64 bodies are addressed by a count in the header above
        // them, which is far easier to honour with random access.
        let lines: Vec<&str> = text.lines().map(|l| l.trim_end_matches('\r')).collect();
        let mut headers: Vec<(String, String)> = Vec::new();
        let mut public = Vec::new();
        let mut private = Vec::new();
        let mut i = 0;
        while i < lines.len() {
            let line = lines[i];
            i += 1;
            if line.trim().is_empty() {
                continue;
            }
            let (key, value) = line.split_once(':').ok_or_else(not_a_ppk)?;
            let (key, value) = (key.trim(), value.trim());
            if key == "Public-Lines" || key == "Private-Lines" {
                let count: usize = value.parse().map_err(|_| not_a_ppk())?;
                let end = i.checked_add(count).filter(|e| *e <= lines.len()).ok_or_else(|| {
                    CoreError::InvalidKey("the key file ends in the middle of a key".into())
                })?;
                let body: String = lines[i..end].concat();
                let decoded = Base64::decode_vec(&body)
                    .map_err(|_| CoreError::InvalidKey("the key file's base64 is damaged".into()))?;
                if key == "Public-Lines" { public = decoded } else { private = decoded }
                i = end;
                continue;
            }
            headers.push((key.to_string(), value.to_string()));
        }

        let get = |name: &str| headers.iter().find(|(k, _)| k == name).map(|(_, v)| v.clone());
        let (version, algorithm) = headers
            .first()
            .and_then(|(k, v)| k.strip_prefix("PuTTY-User-Key-File-").map(|n| (n.to_string(), v.clone())))
            .ok_or_else(not_a_ppk)?;
        let version: u8 = version.parse().map_err(|_| not_a_ppk())?;
        if version < 2 || version > 3 {
            return Err(CoreError::InvalidKey(format!(
                "this is a version {version} PuTTY key; open it in PuTTYgen and save it again to get a version 2 or 3 file"
            )));
        }
        let encryption = get("Encryption").unwrap_or_else(|| "none".into());
        if encryption != "none" && encryption != "aes256-cbc" {
            return Err(CoreError::InvalidKey(format!("this key is encrypted with {encryption}, which this app cannot read")));
        }
        let mac = get("Private-MAC").ok_or_else(not_a_ppk)?;

        let argon2 = if version >= 3 && encryption != "none" {
            Some(Argon2Params::parse(&get)?)
        } else {
            None
        };

        Ok(PpkFile {
            version,
            algorithm,
            encryption,
            comment: get("Comment").unwrap_or_default(),
            public,
            private,
            mac,
            argon2,
        })
    }

    fn encrypted(&self) -> bool {
        self.encryption != "none"
    }

    /// The private blob in the clear, still carrying whatever padding PuTTY
    /// added before encrypting — the MAC covers that padding, so it must not be
    /// trimmed before [`Self::check_mac`] has run.
    fn decrypt(&self, passphrase: &str) -> Result<Vec<u8>, CoreError> {
        if !self.encrypted() {
            return Ok(self.private.clone());
        }
        if passphrase.is_empty() {
            return Err(CoreError::WrongPassphrase);
        }
        if self.private.is_empty() || self.private.len() % 16 != 0 {
            return Err(CoreError::InvalidKey("the encrypted part of the key is the wrong length".into()));
        }
        let (key, iv) = match &self.argon2 {
            Some(params) => {
                let out = params.derive(passphrase)?;
                let mut key = [0u8; 32];
                let mut iv = [0u8; 16];
                key.copy_from_slice(&out[..32]);
                iv.copy_from_slice(&out[32..48]);
                (key, iv)
            }
            // v2: two SHA-1 rounds over a counter and the passphrase, truncated
            // to 32 bytes, with an all-zero IV.
            None => {
                let mut key = [0u8; 32];
                let mut made = Vec::with_capacity(40);
                for round in 0u32..2 {
                    let mut h = Sha1::new();
                    h.update(round.to_be_bytes());
                    h.update(passphrase.as_bytes());
                    made.extend_from_slice(&h.finalize());
                }
                key.copy_from_slice(&made[..32]);
                (key, [0u8; 16])
            }
        };
        let mut buf = self.private.clone();
        cbc::Decryptor::<Aes256>::new(&key.into(), &iv.into())
            .decrypt_padded::<NoPadding>(&mut buf)
            .map_err(|_| CoreError::InvalidKey("the encrypted part of the key is the wrong length".into()))?;
        Ok(buf)
    }

    fn check_mac(&self, passphrase: &str, private: &[u8]) -> Result<(), CoreError> {
        let mut data = Vec::new();
        for field in [self.algorithm.as_bytes(), self.encryption.as_bytes(), self.comment.as_bytes()] {
            put_string(&mut data, field);
        }
        put_string(&mut data, &self.public);
        put_string(&mut data, private);

        let expected = self.mac.to_ascii_lowercase();
        let got = if self.version >= 3 {
            // v3 keeps the MAC key in the last 32 bytes of the Argon2 output;
            // an unencrypted key has no Argon2 output and so no key at all.
            let key = match &self.argon2 {
                Some(params) => params.derive(passphrase)?[48..80].to_vec(),
                None => Vec::new(),
            };
            let mut mac = Hmac::<Sha256>::new_from_slice(&key).expect("HMAC takes a key of any length");
            mac.update(&data);
            hex(&mac.finalize().into_bytes())
        } else {
            let key = Sha1::new()
                .chain_update(b"putty-private-key-file-mac-key")
                .chain_update(passphrase.as_bytes())
                .finalize();
            let mut mac = Hmac::<Sha1>::new_from_slice(&key).expect("HMAC takes a key of any length");
            mac.update(&data);
            hex(&mac.finalize().into_bytes())
        };

        if got == expected {
            Ok(())
        } else if self.encrypted() {
            // A wrong passphrase and a corrupted file are indistinguishable
            // here, and of the two only one is worth suggesting to somebody who
            // has just typed something.
            Err(CoreError::WrongPassphrase)
        } else {
            Err(CoreError::InvalidKey("this key file has been damaged: its checksum does not match".into()))
        }
    }

    /// Re-lay the key's numbers into the order OpenSSH writes them and let the
    /// SSH key crate do the framing, so the result is a file OpenSSH itself
    /// would have written rather than one that merely looks like it.
    fn to_openssh(&self, private: &[u8]) -> Result<String, CoreError> {
        let unsupported = || {
            CoreError::InvalidKey(format!(
                "this app cannot import {} keys from PuTTY — only ssh-ed25519, ecdsa-sha2-nistp256 and ssh-rsa",
                self.algorithm
            ))
        };
        let damaged = || CoreError::InvalidKey("this key file has been damaged: its contents do not match its type".into());

        let mut pub_fields = Reader::new(&self.public);
        let mut priv_fields = Reader::new(private);
        if pub_fields.string().ok_or_else(damaged)? != self.algorithm.as_bytes() {
            return Err(damaged());
        }

        let mut blob = Vec::new();
        put_string(&mut blob, self.algorithm.as_bytes());
        match self.algorithm.as_str() {
            "ssh-ed25519" => {
                let public = pub_fields.string().ok_or_else(damaged)?;
                let seed = priv_fields.string().ok_or_else(damaged)?;
                if public.len() != 32 || seed.len() != 32 {
                    return Err(damaged());
                }
                put_string(&mut blob, public);
                // OpenSSH stores the seed and the public key together, in that
                // order, and PuTTY stores only the seed.
                put_string(&mut blob, &[seed, public].concat());
            }
            "ssh-rsa" => {
                let (e, n) = (pub_fields.string().ok_or_else(damaged)?, pub_fields.string().ok_or_else(damaged)?);
                let d = priv_fields.string().ok_or_else(damaged)?;
                let p = priv_fields.string().ok_or_else(damaged)?;
                let q = priv_fields.string().ok_or_else(damaged)?;
                let iqmp = priv_fields.string().ok_or_else(damaged)?;
                // PuTTY writes e before n and the CRT values last; OpenSSH puts
                // the modulus first and iqmp before the primes.
                for field in [n, e, d, iqmp, p, q] {
                    put_string(&mut blob, field);
                }
            }
            "ecdsa-sha2-nistp256" => {
                let curve = pub_fields.string().ok_or_else(damaged)?;
                let point = pub_fields.string().ok_or_else(damaged)?;
                if curve != b"nistp256" {
                    return Err(unsupported());
                }
                let d = priv_fields.string().ok_or_else(damaged)?;
                put_string(&mut blob, curve);
                put_string(&mut blob, point);
                // PuTTY writes the scalar as an mpint, which drops leading zero
                // bytes and adds one when the top bit is set; OpenSSH wants
                // exactly the curve's 32.
                put_string(&mut blob, &fixed_width(d, 32).ok_or_else(damaged)?);
            }
            _ => return Err(unsupported()),
        }

        let keypair = KeypairData::decode(&mut blob.as_slice()).map_err(|_| damaged())?;
        let key = PrivateKey::new(keypair, self.comment.as_str()).map_err(|e| CoreError::InvalidKey(e.to_string()))?;
        Ok(key.to_openssh(LineEnding::LF).map_err(|e| CoreError::InvalidKey(e.to_string()))?.to_string())
    }
}

impl Argon2Params {
    fn parse(get: &impl Fn(&str) -> Option<String>) -> Result<Self, CoreError> {
        let missing = |what: &str| CoreError::InvalidKey(format!("this key file is missing its {what}"));
        let algorithm = match get("Key-Derivation").unwrap_or_default().as_str() {
            "Argon2id" => Algorithm::Argon2id,
            "Argon2i" => Algorithm::Argon2i,
            "Argon2d" => Algorithm::Argon2d,
            other => {
                return Err(CoreError::InvalidKey(format!(
                    "this key uses the {other} key derivation, which this app cannot read"
                )))
            }
        };
        let number = |name: &str| -> Result<u32, CoreError> {
            get(name).and_then(|v| v.parse().ok()).ok_or_else(|| missing(name))
        };
        let memory_kib = number("Argon2-Memory")?;
        if memory_kib > MAX_ARGON2_MEMORY_KIB {
            return Err(CoreError::InvalidKey(format!(
                "this key asks for {} MiB of memory to open, more than this app will spend",
                memory_kib / 1024
            )));
        }
        let salt = get("Argon2-Salt").ok_or_else(|| missing("Argon2-Salt"))?;
        Ok(Argon2Params {
            algorithm,
            memory_kib,
            passes: number("Argon2-Passes")?,
            parallelism: number("Argon2-Parallelism")?,
            salt: unhex(&salt).ok_or_else(|| CoreError::InvalidKey("this key file's salt is damaged".into()))?,
        })
    }

    /// 80 bytes: the AES key, then the IV, then the MAC key.
    fn derive(&self, passphrase: &str) -> Result<[u8; 80], CoreError> {
        let params = Params::new(self.memory_kib, self.passes, self.parallelism, Some(80))
            .map_err(|e| CoreError::InvalidKey(format!("this key file's Argon2 settings are out of range: {e}")))?;
        let mut out = [0u8; 80];
        Argon2::new(self.algorithm, Version::V0x13, params)
            .hash_password_into(passphrase.as_bytes(), &self.salt, &mut out)
            .map_err(|e| CoreError::InvalidKey(format!("could not derive a key from the passphrase: {e}")))?;
        Ok(out)
    }
}

/// Walks the length-prefixed fields SSH packs its keys into.
struct Reader<'a> {
    rest: &'a [u8],
}

impl<'a> Reader<'a> {
    fn new(bytes: &'a [u8]) -> Self {
        Reader { rest: bytes }
    }

    fn string(&mut self) -> Option<&'a [u8]> {
        let (len, rest) = self.rest.split_first_chunk::<4>()?;
        let len = u32::from_be_bytes(*len) as usize;
        let (value, rest) = rest.split_at_checked(len)?;
        self.rest = rest;
        Some(value)
    }
}

fn put_string(out: &mut Vec<u8>, value: &[u8]) {
    out.extend_from_slice(&(value.len() as u32).to_be_bytes());
    out.extend_from_slice(value);
}

/// `value` as exactly `width` bytes, big-endian, or `None` if it does not fit.
fn fixed_width(value: &[u8], width: usize) -> Option<Vec<u8>> {
    let trimmed = value.iter().position(|b| *b != 0).map_or(&value[..0], |at| &value[at..]);
    if trimmed.len() > width {
        return None;
    }
    let mut out = vec![0u8; width - trimmed.len()];
    out.extend_from_slice(trimmed);
    Some(out)
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn unhex(text: &str) -> Option<Vec<u8>> {
    if text.len() % 2 != 0 {
        return None;
    }
    text.as_bytes()
        .chunks(2)
        .map(|pair| u8::from_str_radix(std::str::from_utf8(pair).ok()?, 16).ok())
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Made by PuTTYgen 0.85, one key per algorithm saved four ways: v2 and v3,
    /// each unencrypted and each under the passphrase "hunter2". The v3
    /// encrypted files were written with `--ppk-param passes=8,memory=4096` so
    /// the tests do not spend PuTTY's default 8 MiB thirteen times over.
    macro_rules! fixture {
        ($name:literal) => {
            include_str!(concat!("../tests/fixtures/ppk/", $name)).as_bytes().to_vec()
        };
    }

    const PASSPHRASE: &str = "hunter2";

    /// The `ssh-ed25519 AAAA…` line PuTTYgen exported for the same key, so the
    /// check runs all the way from the `.ppk` to a public half that a server
    /// would recognise, rather than stopping at "it parsed".
    fn assert_public_half(openssh: &str, expected: &str) {
        let key = PrivateKey::from_openssh(openssh).expect("the OpenSSH text we wrote parses");
        let public = key.public_key().to_openssh().expect("the public half re-encodes");
        assert_eq!(public.trim(), expected.trim());
    }

    #[test]
    fn every_form_of_the_same_ed25519_key_gives_the_same_key() {
        let expected = include_str!("../tests/fixtures/ppk/ed25519.pub");
        for (bytes, pass) in [
            (fixture!("ed25519.v2.ppk"), ""),
            (fixture!("ed25519.v3.ppk"), ""),
            (fixture!("ed25519.v2.enc.ppk"), PASSPHRASE),
            (fixture!("ed25519.v3.enc.ppk"), PASSPHRASE),
        ] {
            assert_public_half(&import_ppk(bytes, pass.into()).unwrap(), expected);
        }
    }

    #[test]
    fn every_form_of_the_same_rsa_key_gives_the_same_key() {
        let expected = include_str!("../tests/fixtures/ppk/rsa.pub");
        for (bytes, pass) in [
            (fixture!("rsa.v2.ppk"), ""),
            (fixture!("rsa.v3.ppk"), ""),
            (fixture!("rsa.v2.enc.ppk"), PASSPHRASE),
            (fixture!("rsa.v3.enc.ppk"), PASSPHRASE),
        ] {
            assert_public_half(&import_ppk(bytes, pass.into()).unwrap(), expected);
        }
    }

    #[test]
    fn every_form_of_the_same_ecdsa_key_gives_the_same_key() {
        let expected = include_str!("../tests/fixtures/ppk/ecdsa.pub");
        for (bytes, pass) in [
            (fixture!("ecdsa.v2.ppk"), ""),
            (fixture!("ecdsa.v3.ppk"), ""),
            (fixture!("ecdsa.v2.enc.ppk"), PASSPHRASE),
            (fixture!("ecdsa.v3.enc.ppk"), PASSPHRASE),
        ] {
            assert_public_half(&import_ppk(bytes, pass.into()).unwrap(), expected);
        }
    }

    #[test]
    fn the_comment_survives_the_trip() {
        let openssh = import_ppk(fixture!("ed25519.v2.ppk"), String::new()).unwrap();
        let key = PrivateKey::from_openssh(&openssh).unwrap();
        assert_eq!(key.comment().to_string(), "test@androidterm");
    }

    #[test]
    fn a_wrong_passphrase_is_named_as_such_in_both_versions() {
        for bytes in [fixture!("ed25519.v2.enc.ppk"), fixture!("ed25519.v3.enc.ppk"), fixture!("rsa.v3.enc.ppk")] {
            assert!(matches!(import_ppk(bytes, "hunter3".into()), Err(CoreError::WrongPassphrase)));
        }
    }

    #[test]
    fn an_encrypted_key_with_no_passphrase_asks_for_one_rather_than_failing_obscurely() {
        assert!(matches!(import_ppk(fixture!("ecdsa.v3.enc.ppk"), String::new()), Err(CoreError::WrongPassphrase)));
    }

    #[test]
    fn a_damaged_unencrypted_key_says_so_instead_of_blaming_the_passphrase() {
        let text = String::from_utf8(fixture!("ed25519.v2.ppk")).unwrap();
        let broken = text.replace("Comment: test@androidterm", "Comment: tampered");
        let err = import_ppk(broken.into_bytes(), String::new()).unwrap_err();
        assert!(err.to_string().contains("damaged"), "{err}");
    }

    #[test]
    fn a_file_that_is_not_a_ppk_is_refused_before_anything_else() {
        for bytes in [b"hello".to_vec(), b"-----BEGIN OPENSSH PRIVATE KEY-----\nnope\n".to_vec(), Vec::new()] {
            let err = import_ppk(bytes, String::new()).unwrap_err();
            assert!(err.to_string().contains("not a PuTTY private key"), "{err}");
        }
    }

    #[test]
    fn an_unsupported_key_type_is_named() {
        // A real DSA key, not a relabelled one: the algorithm is part of what
        // the MAC covers, so an edited header would fail as damage first and
        // never reach the question this test is about.
        let err = import_ppk(fixture!("dsa.v2.ppk"), String::new()).unwrap_err();
        assert!(err.to_string().contains("ssh-dss"), "{err}");
    }

    #[test]
    fn a_version_one_key_is_told_how_to_be_converted() {
        let text = String::from_utf8(fixture!("ed25519.v2.ppk")).unwrap();
        let old = text.replace("PuTTY-User-Key-File-2:", "PuTTY-User-Key-File-1:");
        let err = import_ppk(old.into_bytes(), String::new()).unwrap_err();
        assert!(err.to_string().contains("PuTTYgen"), "{err}");
    }

    #[test]
    fn a_key_demanding_absurd_memory_is_refused_rather_than_obeyed() {
        let text = String::from_utf8(fixture!("ed25519.v3.enc.ppk")).unwrap();
        let greedy = text.replace("Argon2-Memory: 4096", "Argon2-Memory: 8388608");
        let err = import_ppk(greedy.into_bytes(), PASSPHRASE.into()).unwrap_err();
        assert!(err.to_string().contains("8192 MiB"), "{err}");
    }

    #[test]
    fn carriage_returns_from_a_windows_file_do_not_change_the_reading() {
        let unix = String::from_utf8(fixture!("ecdsa.v3.ppk")).unwrap();
        let dos = unix.replace('\n', "\r\n");
        assert_eq!(
            import_ppk(dos.into_bytes(), String::new()).unwrap(),
            import_ppk(unix.into_bytes(), String::new()).unwrap(),
        );
    }

    #[test]
    fn an_mpint_is_widened_to_the_curve_and_never_silently_truncated() {
        assert_eq!(fixed_width(&[0x00, 0xff], 4), Some(vec![0, 0, 0, 0xff]));
        assert_eq!(fixed_width(&[0xff; 4], 4), Some(vec![0xff; 4]));
        assert_eq!(fixed_width(&[], 2), Some(vec![0, 0]));
        assert_eq!(fixed_width(&[0xff; 5], 4), None);
    }
}
