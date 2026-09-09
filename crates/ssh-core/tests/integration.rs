//! Runs against a real sshd. Set `SSH_TEST_PORT` and `SSH_TEST_KEY` (path to
//! an authorized private key) to enable; otherwise the tests are skipped.

use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use ssh_core::{AcceptAll, Auth, ConnectOptions, ShellEvent, SshClient};
use tokio::io::{AsyncReadExt, AsyncWriteExt};

fn options() -> Option<ConnectOptions> {
    let port: u16 = std::env::var("SSH_TEST_PORT").ok()?.parse().ok()?;
    let key = std::fs::read_to_string(std::env::var("SSH_TEST_KEY").ok()?).ok()?;
    let user = std::env::var("USER").unwrap_or_else(|_| "root".into());
    Some(ConnectOptions {
        host: "127.0.0.1".into(),
        port,
        username: user,
        auth: vec![Auth::Key { private_key: key, passphrase: None, certificate: None }],
        keepalive_interval: Some(Duration::from_secs(15)),
        connect_timeout: Duration::from_secs(10),
        proxy: None,
        agent: None,
        prompter: None,
        on_banner: None,
    })
}

async fn client() -> Option<SshClient> {
    let opts = options()?;
    Some(SshClient::connect(opts, Arc::new(AcceptAll)).await.expect("connect"))
}

/// Against a real OpenSSH, the hybrid is what gets negotiated — from 9.9 on,
/// where `mlkem768x25519-sha256` is both offered and preferred. An older sshd
/// falls back to curve25519, which is the fallback working rather than a
/// failure, so that is allowed here and only anything *below* it is not.
#[tokio::test]
async fn the_key_exchange_is_the_hybrid_where_the_server_has_it() {
    let Some(c) = client().await else { return };
    let kex = c.kex_algorithm().expect("the connection negotiated something");
    assert!(
        kex == "mlkem768x25519-sha256" || kex == "curve25519-sha256",
        "negotiated {kex}, which is neither the hybrid nor the curve25519 fallback",
    );
    c.disconnect().await;
}

#[tokio::test]
async fn exec_and_pty_shell() {
    let Some(c) = client().await else { return };
    let (out, status) = c.exec("echo hello-exec").await.unwrap();
    assert_eq!(status, 0);
    assert!(String::from_utf8_lossy(&out).contains("hello-exec"));

    let mut shell = c.open_shell("xterm-256color", 91, 33).await.unwrap();
    let w = shell.writer();
    w.write(&b"stty size; echo TERM=$TERM; exit\n"[..]).await.unwrap();
    let mut collected = String::new();
    loop {
        match shell.next().await {
            ShellEvent::Data(d) => collected.push_str(&String::from_utf8_lossy(&d)),
            ShellEvent::Exit(_) | ShellEvent::Closed => break,
        }
    }
    assert!(collected.contains("33 91"), "{collected}");
    assert!(collected.contains("TERM=xterm-256color"), "{collected}");
    c.disconnect().await;
}

#[tokio::test]
async fn bad_key_is_rejected() {
    let Some(mut opts) = options() else { return };
    opts.auth = vec![Auth::Password("nope".into())];
    let err = SshClient::connect(opts, Arc::new(AcceptAll)).await.err().expect("should fail");
    assert!(matches!(err, ssh_core::SshError::AuthFailed(_)), "{err}");
}

/// A certificate the server will not take must not cost us the key underneath
/// it.
///
/// This signs the very key the server already accepts with a CA it has never
/// heard of, so the certificate is guaranteed to be refused. OpenSSH would then
/// offer the plain key and get in; anything less locks a person out of every
/// host their CA does not cover, which is most of them.
#[tokio::test]
async fn a_refused_certificate_falls_back_to_the_key() {
    use russh::keys::ssh_key::certificate::{Builder, CertType};
    use russh::keys::ssh_key::private::Ed25519Keypair;

    let Some(mut opts) = options() else { return };
    let text = std::fs::read_to_string(std::env::var("SSH_TEST_KEY").unwrap()).unwrap();
    let key = russh::keys::decode_secret_key(&text, None).unwrap();
    // A CA of our own, from a fixed seed so the test carries no key material.
    let ca = russh::keys::PrivateKey::from(Ed25519Keypair::from_seed(&[7u8; 32]));
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_secs();
    let mut builder = Builder::new([0u8; 32], key.public_key().key_data().clone(), now - 60, now + 3600).unwrap();
    builder.cert_type(CertType::User).unwrap();
    builder.key_id("certificate-fallback-test").unwrap();
    builder.valid_principal(opts.username.clone()).unwrap();
    let cert = builder.sign(&ca).unwrap();

    opts.auth = vec![Auth::Key {
        private_key: text,
        passphrase: None,
        certificate: Some(cert.to_openssh().unwrap()),
    }];
    let c = SshClient::connect(opts, Arc::new(AcceptAll)).await.expect("the key should still get in");
    let (out, status) = c.exec("echo fell-back").await.unwrap();
    assert_eq!(status, 0);
    assert_eq!(String::from_utf8_lossy(&out).trim(), "fell-back");
}

/// The exit status has to be the command's own, whichever way it left.
///
/// `false` and a command that does not exist were reported correctly while the
/// shell's own `exit` was not, which is the shape of a status read from the
/// wrong message — so all three are pinned here.
#[tokio::test]
async fn exec_reports_the_exit_status() {
    let Some(c) = client().await else { return };
    for (command, want) in [("true", 0u32), ("false", 1), ("exit 7", 7), ("echo hi; exit 3", 3)] {
        let (out, status) = c.exec(command).await.unwrap();
        assert_eq!(status, want, "`{command}` printed {:?}", String::from_utf8_lossy(&out));
    }
}

async fn echo_server() -> SocketAddr {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(async move {
        loop {
            let (mut s, _) = listener.accept().await.unwrap();
            tokio::spawn(async move {
                let mut buf = [0u8; 1024];
                while let Ok(n) = s.read(&mut buf).await {
                    if n == 0 || s.write_all(&buf[..n]).await.is_err() {
                        break;
                    }
                }
            });
        }
    });
    addr
}

#[tokio::test]
async fn local_and_remote_forwarding() {
    let Some(c) = client().await else { return };
    let echo = echo_server().await;

    // -L: local listener -> ssh -> echo server on the "remote" side.
    let lf = c.local_forward("127.0.0.1:0".parse().unwrap(), "127.0.0.1".into(), echo.port()).await.unwrap();
    let mut s = tokio::net::TcpStream::connect(lf.bound).await.unwrap();
    s.write_all(b"ping-local").await.unwrap();
    let mut buf = [0u8; 32];
    let n = s.read(&mut buf).await.unwrap();
    assert_eq!(&buf[..n], b"ping-local");
    drop(s);
    lf.stop();

    // -R: server listens, connections come back to the echo server here.
    let rf = c.remote_forward("127.0.0.1".into(), 0, echo).await.unwrap();
    assert!(rf.remote_port > 0);
    let mut s = tokio::net::TcpStream::connect(("127.0.0.1", rf.remote_port as u16)).await.unwrap();
    s.write_all(b"ping-remote").await.unwrap();
    let n = s.read(&mut buf).await.unwrap();
    assert_eq!(&buf[..n], b"ping-remote");
    rf.stop().await;
    c.disconnect().await;
}

#[tokio::test]
async fn sftp_roundtrip() {
    let Some(c) = client().await else { return };
    let sftp = c.open_sftp().await.unwrap();
    let home = sftp.home().await.unwrap();
    assert!(home.starts_with('/'));

    let dir = tempfile_dir();
    let remote_dir = format!("{dir}/sftp-test");
    sftp.mkdir(&remote_dir).await.unwrap();

    let local = std::path::PathBuf::from(format!("{dir}/upload.bin"));
    let payload: Vec<u8> = (0..(3 * 1024 * 1024)).map(|i| (i % 251) as u8).collect();
    std::fs::write(&local, &payload).unwrap();
    let remote_file = format!("{remote_dir}/copy.bin");
    let n = sftp.upload(&local, &remote_file, Arc::new(|_, _| true)).await.unwrap();
    assert_eq!(n, payload.len() as u64);

    let entries = sftp.list(&remote_dir).await.unwrap();
    assert_eq!(entries.len(), 1);
    assert_eq!(entries[0].name, "copy.bin");
    assert_eq!(entries[0].size, payload.len() as u64);

    let back = std::path::PathBuf::from(format!("{dir}/download.bin"));
    let progressed = Arc::new(std::sync::atomic::AtomicU64::new(0));
    let p2 = progressed.clone();
    sftp.download(
        &remote_file,
        &back,
        Arc::new(move |done, total| {
            p2.store(done, std::sync::atomic::Ordering::Relaxed);
            assert_eq!(total, Some(3 * 1024 * 1024));
            true
        }),
    )
    .await
    .unwrap();
    assert_eq!(std::fs::read(&back).unwrap(), payload);
    assert_eq!(progressed.load(std::sync::atomic::Ordering::Relaxed), payload.len() as u64);

    sftp.rename(&remote_file, &format!("{remote_dir}/renamed.bin")).await.unwrap();
    sftp.remove(&remote_dir, true).await.unwrap();
    assert!(sftp.list(&remote_dir).await.is_err());
    sftp.close().await;
    c.disconnect().await;
    let _ = std::fs::remove_dir_all(dir);
}

fn tempfile_dir() -> String {
    let base = std::env::var("SSH_TEST_TMP").unwrap_or_else(|_| std::env::temp_dir().to_string_lossy().into_owned());
    let dir = format!("{base}/ssh-core-test-{}", std::process::id());
    std::fs::create_dir_all(&dir).unwrap();
    dir
}
