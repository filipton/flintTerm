//! `sk-*` authentication against a real OpenSSH server.
//!
//! Nothing here needs a physical security key: a [`SoftwareToken`] answers the
//! assertions, so what is under test is the pair of wire formats — the public
//! key OpenSSH has to read out of `authorized_keys`, and the signature blob it
//! has to verify. If sshd lets us in, they are right; a unit test can only ever
//! say that we agree with ourselves.
//!
//! Set `SSH_TEST_PORT` and `SSH_TEST_AUTHORIZED_KEYS` (the server's
//! `AuthorizedKeysFile`) to enable; otherwise the tests are skipped. The file is
//! restored to the byte it started at, whether the test passes, fails or panics.

use std::sync::{Arc, Mutex, MutexGuard, OnceLock};
use std::time::Duration;

use ssh_core::sk::SoftwareToken;
use ssh_core::{
    AcceptAll, Auth, ConnectOptions, ExternalSigner, SkAlgorithm, SkSigner, SshClient, SshError, DEFAULT_APPLICATION,
};

/// The server's `authorized_keys`, borrowed for the length of one test.
///
/// It is a file the machine's owner cares about, so it is read before anything
/// is written to it and put back on the way out — including out through a panic,
/// which is why this is a guard and not a pair of calls.
struct AuthorizedKeys {
    path: String,
    original: String,
    _lock: MutexGuard<'static, ()>,
}

impl AuthorizedKeys {
    /// None when the harness is not configured, which is how these tests skip.
    fn borrow() -> Option<Self> {
        let path = std::env::var("SSH_TEST_AUTHORIZED_KEYS").ok()?;
        // Cargo runs the tests in this file on threads of one process, and they
        // all write the same file; one at a time is the only way that is safe.
        static LOCK: OnceLock<Mutex<()>> = OnceLock::new();
        let lock = LOCK.get_or_init(|| Mutex::new(())).lock().unwrap_or_else(|e| e.into_inner());
        let original = std::fs::read_to_string(&path).ok()?;
        Some(Self { path, original, _lock: lock })
    }

    fn authorize(&self, line: &str) {
        let mut text = self.original.clone();
        if !text.ends_with('\n') {
            text.push('\n');
        }
        text.push_str(line.trim_end());
        text.push('\n');
        std::fs::write(&self.path, text).expect("authorized_keys should be writable");
    }
}

impl Drop for AuthorizedKeys {
    fn drop(&mut self) {
        let _ = std::fs::write(&self.path, &self.original);
    }
}

fn options(auth: Auth) -> Option<ConnectOptions> {
    let port: u16 = std::env::var("SSH_TEST_PORT").ok()?.parse().ok()?;
    Some(ConnectOptions {
        host: "127.0.0.1".into(),
        port,
        username: std::env::var("USER").unwrap_or_else(|_| "root".into()),
        auth: vec![auth],
        keepalive_interval: Some(Duration::from_secs(15)),
        connect_timeout: Duration::from_secs(10),
        proxy: None,
        agent: None,
        prompter: None,
        on_banner: None,
    })
}

fn signer(algorithm: SkAlgorithm, comment: &str) -> SkSigner {
    let token = SoftwareToken::enroll(algorithm, DEFAULT_APPLICATION).expect("enroll");
    let credential = token.credential().expect("credential");
    SkSigner::new(credential, comment, Arc::new(token)).expect("signer")
}

/// The whole point: enroll, write the public key where sshd will read it, and
/// log in with a signature the token produced.
#[tokio::test]
async fn openssh_accepts_a_security_key() {
    let Some(keys) = AuthorizedKeys::borrow() else { return };
    for algorithm in [SkAlgorithm::Ed25519, SkAlgorithm::EcdsaP256] {
        let signer = signer(algorithm, "software-token@test");
        let line = signer.public_key();
        assert!(line.starts_with(algorithm.key_type()), "{line}");
        keys.authorize(&line);

        let auth = Auth::External { signer: Arc::new(signer), certificate: None };
        let Some(opts) = options(auth) else { return };
        let client = SshClient::connect(opts, Arc::new(AcceptAll))
            .await
            .unwrap_or_else(|e| panic!("{} should have been accepted: {e}", algorithm.key_type()));
        let (out, status) = client.exec("echo security-key-in").await.unwrap();
        assert_eq!(status, 0);
        assert_eq!(String::from_utf8_lossy(&out).trim(), "security-key-in");
        client.disconnect().await;
    }
}

/// A second login has to work too, because the token's use counter has moved on
/// and it is hashed into every signature — a server that pinned the first value
/// would lock the key out, and so would a client that forgot to send it.
#[tokio::test]
async fn the_advancing_use_counter_does_not_lock_the_key_out() {
    let Some(keys) = AuthorizedKeys::borrow() else { return };
    let token = SoftwareToken::enroll(SkAlgorithm::Ed25519, DEFAULT_APPLICATION).expect("enroll");
    let token = Arc::new(token);
    let signer = SkSigner::new(token.credential().unwrap(), "counter@test", token).expect("signer");
    keys.authorize(&signer.public_key());

    let signer: Arc<dyn ssh_core::ExternalSigner> = Arc::new(signer);
    for attempt in 1..=3 {
        let auth = Auth::External { signer: signer.clone(), certificate: None };
        let Some(opts) = options(auth) else { return };
        let client = SshClient::connect(opts, Arc::new(AcceptAll))
            .await
            .unwrap_or_else(|e| panic!("login {attempt} failed: {e}"));
        client.disconnect().await;
    }
}

/// A key the server has never been told about must fail as authentication, not
/// as a mangled packet: that is the difference between "add this key" and "this
/// client is broken".
#[tokio::test]
async fn a_security_key_the_server_does_not_know_is_rejected() {
    let Some(_keys) = AuthorizedKeys::borrow() else { return };
    let signer = signer(SkAlgorithm::Ed25519, "unauthorized@test");
    let auth = Auth::External { signer: Arc::new(signer), certificate: None };
    let Some(opts) = options(auth) else { return };
    let err = SshClient::connect(opts, Arc::new(AcceptAll)).await.err().expect("should fail");
    assert!(matches!(err, SshError::AuthFailed(_)), "{err}");
    assert!(err.to_string().contains("sk-ssh-ed25519@openssh.com"), "{err}");
}
