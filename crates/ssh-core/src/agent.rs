//! A tiny SSH agent that lives inside the app: it answers `auth-agent@openssh.com`
//! channels the server opens when agent forwarding is on, so `ssh`/`git` on the
//! remote machine can use the phone's keys without the keys ever leaving it.

use std::sync::Arc;

use russh::keys::ssh_key::{self, PrivateKey};
use russh::keys::signature::Signer;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

const SSH_AGENT_FAILURE: u8 = 5;
const SSH_AGENTC_REQUEST_IDENTITIES: u8 = 11;
const SSH_AGENT_IDENTITIES_ANSWER: u8 = 12;
const SSH_AGENTC_SIGN_REQUEST: u8 = 13;
const SSH_AGENT_SIGN_RESPONSE: u8 = 14;
const SSH_AGENTC_EXTENSION: u8 = 27;

const SSH_AGENT_RSA_SHA2_256: u32 = 2;

/// Called for every signature the remote side asks for; return false to refuse.
///
/// Forwarding an agent hands the machine at the other end the ability to sign
/// as you for as long as you are connected — anyone with root there can use it
/// to reach every other host that key opens, without ever touching the key
/// itself. That is what this is for, so it is allowed to take its time and ask
/// somebody; it is called on a blocking thread for exactly that reason.
pub trait AgentPolicy: Send + Sync + 'static {
    fn allow_sign(&self, key_comment: &str, fingerprint: &str) -> bool;
}

pub struct AllowAll;
impl AgentPolicy for AllowAll {
    fn allow_sign(&self, _: &str, _: &str) -> bool {
        true
    }
}

#[derive(Clone)]
pub struct AgentKeys {
    pub keys: Vec<Arc<PrivateKey>>,
    pub policy: Arc<dyn AgentPolicy>,
}

impl std::fmt::Debug for AgentKeys {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "AgentKeys({} keys)", self.keys.len())
    }
}

fn put_u32(out: &mut Vec<u8>, v: u32) {
    out.extend_from_slice(&v.to_be_bytes());
}

fn put_string(out: &mut Vec<u8>, s: &[u8]) {
    put_u32(out, s.len() as u32);
    out.extend_from_slice(s);
}

fn get_u32(buf: &[u8], pos: &mut usize) -> Option<u32> {
    let b = buf.get(*pos..*pos + 4)?;
    *pos += 4;
    Some(u32::from_be_bytes([b[0], b[1], b[2], b[3]]))
}

fn get_string<'a>(buf: &'a [u8], pos: &mut usize) -> Option<&'a [u8]> {
    let len = get_u32(buf, pos)? as usize;
    let s = buf.get(*pos..*pos + len)?;
    *pos += len;
    Some(s)
}

/// Serve the agent protocol on one forwarded channel until it closes.
pub async fn serve<S: AsyncRead + AsyncWrite + Unpin>(mut stream: S, agent: AgentKeys) {
    let mut len = [0u8; 4];
    loop {
        if stream.read_exact(&mut len).await.is_err() {
            return;
        }
        let n = u32::from_be_bytes(len) as usize;
        if n == 0 || n > 256 * 1024 {
            return;
        }
        let mut msg = vec![0u8; n];
        if stream.read_exact(&mut msg).await.is_err() {
            return;
        }
        // Off the async runtime: a policy that asks a person can take as long
        // as the person does, and a signature the remote side is waiting on must
        // not hold up every other session sharing these worker threads.
        let keys = agent.clone();
        let Ok(reply) = tokio::task::spawn_blocking(move || handle(&msg, &keys)).await else {
            return;
        };
        let mut framed = Vec::with_capacity(reply.len() + 4);
        put_u32(&mut framed, reply.len() as u32);
        framed.extend_from_slice(&reply);
        if stream.write_all(&framed).await.is_err() {
            return;
        }
    }
}

fn handle(msg: &[u8], agent: &AgentKeys) -> Vec<u8> {
    let mut out = Vec::new();
    match msg.first().copied() {
        Some(SSH_AGENTC_REQUEST_IDENTITIES) => {
            out.push(SSH_AGENT_IDENTITIES_ANSWER);
            put_u32(&mut out, agent.keys.len() as u32);
            for k in &agent.keys {
                let blob = match k.public_key().to_bytes() {
                    Ok(b) => b,
                    Err(_) => continue,
                };
                put_string(&mut out, &blob);
                put_string(&mut out, k.comment().to_string().as_bytes());
            }
        }
        Some(SSH_AGENTC_SIGN_REQUEST) => {
            let mut pos = 1;
            let (Some(blob), Some(data), Some(flags)) = (get_string(msg, &mut pos), get_string(msg, &mut pos), get_u32(msg, &mut pos)) else {
                return vec![SSH_AGENT_FAILURE];
            };
            let key = agent.keys.iter().find(|k| k.public_key().to_bytes().map(|b| b == blob).unwrap_or(false));
            let Some(key) = key else { return vec![SSH_AGENT_FAILURE] };
            let fp = key.public_key().fingerprint(ssh_key::HashAlg::Sha256).to_string();
            let comment = key.comment().to_string();
            if !agent.policy.allow_sign(&comment, &fp) {
                log::info!("agent: signature with {comment} refused by policy");
                return vec![SSH_AGENT_FAILURE];
            }
            match sign(key, data, flags) {
                Some((alg, sig)) => {
                    let mut sigblob = Vec::new();
                    put_string(&mut sigblob, alg.as_bytes());
                    put_string(&mut sigblob, &sig);
                    out.push(SSH_AGENT_SIGN_RESPONSE);
                    put_string(&mut out, &sigblob);
                    log::info!("agent: signed a request with {comment}");
                }
                None => return vec![SSH_AGENT_FAILURE],
            }
        }
        Some(SSH_AGENTC_EXTENSION) | _ => return vec![SSH_AGENT_FAILURE],
    }
    out
}

/// Returns (signature algorithm name, raw signature bytes).
fn sign(key: &PrivateKey, data: &[u8], flags: u32) -> Option<(String, Vec<u8>)> {
    match key.key_data() {
        ssh_key::private::KeypairData::Rsa(rsa) => {
            // OpenSSH clients ask for SHA-2; plain ssh-rsa (SHA-1) is no longer accepted by servers.
            use rsa::pkcs1v15::SigningKey;
            let big = |m: &ssh_key::Mpint| rsa::BigUint::from_bytes_be(m.as_positive_bytes()?).into();
            let n: Option<rsa::BigUint> = big(&rsa.public().n());
            let e: Option<rsa::BigUint> = big(&rsa.public().e());
            let d: Option<rsa::BigUint> = big(&rsa.private().d());
            let p: Option<rsa::BigUint> = big(&rsa.private().p());
            let q: Option<rsa::BigUint> = big(&rsa.private().q());
            let private = rsa::RsaPrivateKey::from_components(n?, e?, d?, vec![p?, q?]).ok()?;
            // The rsa crate speaks an older `signature` than ssh-key does; use its own traits here.
            use rsa::signature::{SignatureEncoding as RsaEnc, Signer as RsaSigner};
            if flags & SSH_AGENT_RSA_SHA2_256 != 0 {
                let sk = SigningKey::<sha2::Sha256>::new(private);
                let sig: rsa::pkcs1v15::Signature = RsaSigner::sign(&sk, data);
                Some(("rsa-sha2-256".into(), RsaEnc::to_vec(&sig)))
            } else {
                // SHA-512 by default; servers that only take SHA-1 are not worth supporting.
                let sk = SigningKey::<sha2::Sha512>::new(private);
                let sig: rsa::pkcs1v15::Signature = RsaSigner::sign(&sk, data);
                Some(("rsa-sha2-512".into(), RsaEnc::to_vec(&sig)))
            }
        }
        _ => {
            let sig: ssh_key::Signature = key.try_sign(data).ok()?;
            Some((sig.algorithm().to_string(), sig.as_bytes().to_vec()))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lists_and_signs_ed25519() {
        // A throwaway key generated for this test only.
        const TEST_KEY: &str = "-----BEGIN OPENSSH PRIVATE KEY-----
b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
QyNTUxOQAAACCCINN7iIdfte59AC6pVkLino33A4m/vfHdRbC2RSCRUwAAAJA59iGkOfYh
pAAAAAtzc2gtZWQyNTUxOQAAACCCINN7iIdfte59AC6pVkLino33A4m/vfHdRbC2RSCRUw
AAAEDy95fZNsrOVa5VPorm7PtpYlS0wlr+Vv3+pAsEf8g84oIg03uIh1+17n0ALqlWQuKe
jfcDib+98d1FsLZFIJFTAAAACmFnZW50LXRlc3QBAgM=
-----END OPENSSH PRIVATE KEY-----
";
        let key = russh::keys::decode_secret_key(TEST_KEY, None).unwrap();
        let agent = AgentKeys { keys: vec![Arc::new(key.clone())], policy: Arc::new(AllowAll) };
        let ids = handle(&[SSH_AGENTC_REQUEST_IDENTITIES], &agent);
        assert_eq!(ids[0], SSH_AGENT_IDENTITIES_ANSWER);
        let mut req = vec![SSH_AGENTC_SIGN_REQUEST];
        put_string(&mut req, &key.public_key().to_bytes().unwrap());
        put_string(&mut req, b"hello");
        put_u32(&mut req, 0);
        let resp = handle(&req, &agent);
        assert_eq!(resp[0], SSH_AGENT_SIGN_RESPONSE);
        let mut pos = 1;
        let blob = get_string(&resp, &mut pos).unwrap();
        let mut p2 = 0;
        assert_eq!(get_string(blob, &mut p2).unwrap(), b"ssh-ed25519");
        let sig = ssh_key::Signature::new(ssh_key::Algorithm::Ed25519, get_string(blob, &mut p2).unwrap()).unwrap();
        russh::keys::signature::Verifier::verify(key.public_key(), b"hello", &sig).unwrap();
    }
}
