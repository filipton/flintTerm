//! The login conversation, against an SSH server running in this process.
//!
//! Keyboard-interactive is the one part of authentication that cannot be tested
//! against the rig: what it does depends entirely on how the far side is
//! configured, and the sshd here is somebody's actual machine. So the far side
//! is written here instead — one server that wants a verification code after
//! the password, one that offers nothing but keyboard-interactive, and one that
//! takes a key and then asks for a second factor. They are the three shapes
//! two-factor SSH comes in.

use std::collections::VecDeque;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use russh::server::{self, Auth as ServerAuth, Response, Server as _};
use russh::{MethodKind, MethodSet};
use ssh_core::keys::KeyAlgorithm;
use ssh_core::{AcceptAll, Auth, AuthPrompter, BannerSink, ConnectOptions, Prompt, SshClient, SshError};

/// What the server wants before it lets anyone in.
#[derive(Debug, Clone, Copy, PartialEq)]
enum Demands {
    /// The password, then a verification code — `AuthenticationMethods
    /// password,keyboard-interactive`.
    CodeAfterPassword,
    /// Nothing but keyboard-interactive: it asks for the password itself, and
    /// then for a code.
    InteractiveOnly,
    /// A key, then a verification code — `publickey,keyboard-interactive`.
    CodeAfterKey,
}

const PASSWORD: &str = "hunter2";
const CODE: &str = "424242";

/// What the server was told, so a test can say what actually went over the wire.
#[derive(Default, Debug)]
struct Wire {
    passwords: Vec<String>,
    interactive: Vec<Vec<String>>,
}

#[derive(Clone)]
struct TestServer {
    demands: Demands,
    banner: Option<String>,
    wire: Arc<Mutex<Wire>>,
    /// How many keyboard-interactive requests this client has answered.
    round: usize,
}

impl server::Server for TestServer {
    type Handler = Self;

    fn new_client(&mut self, _: Option<std::net::SocketAddr>) -> Self {
        self.clone()
    }
}

fn only(method: MethodKind) -> MethodSet {
    let mut set = MethodSet::empty();
    set.push(method);
    set
}

impl server::Handler for TestServer {
    type Error = russh::Error;

    async fn authentication_banner(&mut self) -> Result<Option<String>, Self::Error> {
        Ok(self.banner.clone())
    }

    async fn auth_password(&mut self, _user: &str, password: &str) -> Result<ServerAuth, Self::Error> {
        self.wire.lock().unwrap().passwords.push(password.to_string());
        Ok(match self.demands {
            // The password was right, and it was only the first half.
            Demands::CodeAfterPassword if password == PASSWORD => ServerAuth::Reject {
                proceed_with_methods: Some(only(MethodKind::KeyboardInteractive)),
                partial_success: true,
            },
            _ => ServerAuth::Reject {
                proceed_with_methods: Some(only(MethodKind::KeyboardInteractive)),
                partial_success: false,
            },
        })
    }

    async fn auth_publickey(&mut self, _user: &str, _key: &russh::keys::ssh_key::PublicKey) -> Result<ServerAuth, Self::Error> {
        Ok(match self.demands {
            Demands::CodeAfterKey => ServerAuth::Reject {
                proceed_with_methods: Some(only(MethodKind::KeyboardInteractive)),
                partial_success: true,
            },
            _ => ServerAuth::reject(),
        })
    }

    async fn auth_keyboard_interactive<'a>(
        &'a mut self,
        _user: &str,
        _submethods: &str,
        response: Option<Response<'a>>,
    ) -> Result<ServerAuth, Self::Error> {
        if let Some(response) = response {
            let answers: Vec<String> = response.map(|a| String::from_utf8_lossy(&a).into_owned()).collect();
            self.wire.lock().unwrap().interactive.push(answers.clone());
            self.round += 1;
            // The password-first server asks two questions; the others ask one.
            let expected = if self.demands == Demands::InteractiveOnly && self.round == 1 { PASSWORD } else { CODE };
            if answers.first().map(String::as_str) != Some(expected) {
                return Ok(ServerAuth::reject());
            }
            if self.demands == Demands::InteractiveOnly && self.round == 1 {
                return Ok(ask("Two-factor", "Enter the code from your phone", "Verification code: "));
            }
            return Ok(ServerAuth::Accept);
        }
        Ok(match self.demands {
            Demands::InteractiveOnly => ask("Login", "", "Password: "),
            _ => ask("Two-factor", "Enter the code from your phone", "Verification code: "),
        })
    }
}

fn ask(name: &'static str, instructions: &'static str, prompt: &'static str) -> ServerAuth {
    ServerAuth::Partial {
        name: name.into(),
        instructions: instructions.into(),
        prompts: vec![(prompt.into(), false)].into(),
    }
}

/// Start the server on a port of the kernel's choosing.
async fn serve(demands: Demands, banner: Option<&str>) -> (u16, Arc<Mutex<Wire>>) {
    let generated = ssh_core::keys::generate(KeyAlgorithm::Ed25519, "test-server", None).expect("host key");
    let key = russh::keys::decode_secret_key(&generated.private_key, None).expect("host key");
    let config = Arc::new(server::Config {
        keys: vec![key],
        // Nothing here is guessing at a password, and the wait would only make
        // the tests slow.
        auth_rejection_time: Duration::ZERO,
        auth_rejection_time_initial: Some(Duration::ZERO),
        ..Default::default()
    });
    let socket = tokio::net::TcpListener::bind(("127.0.0.1", 0)).await.expect("listen");
    let port = socket.local_addr().expect("port").port();
    let wire: Arc<Mutex<Wire>> = Default::default();
    let mut server = TestServer { demands, banner: banner.map(str::to_string), wire: wire.clone(), round: 0 };
    tokio::spawn(async move { server.run_on_socket(config, &socket).await });
    (port, wire)
}

fn options(port: u16, auth: Auth, prompter: Option<Arc<dyn AuthPrompter>>) -> ConnectOptions {
    ConnectOptions {
        host: "127.0.0.1".into(),
        port,
        username: "someone".into(),
        auth: vec![auth],
        keepalive_interval: None,
        connect_timeout: Duration::from_secs(10),
        proxy: None,
        agent: None,
        prompter,
        on_banner: None,
    }
}

/// A prompter with its answers written down in advance, which remembers what it
/// was asked. Running out of answers is a person walking away from the dialog.
///
/// It is the banner sink as well, so a test can say not just that the banner
/// arrived but that it had arrived *before* the question was put — which is the
/// whole point of one that carries a URL to go and open.
#[derive(Debug, Default)]
struct Scripted {
    answers: Mutex<VecDeque<Vec<String>>>,
    asked: Mutex<Vec<(String, String, Vec<Prompt>)>>,
    banners: Mutex<Vec<String>>,
    banner_came_first: Mutex<bool>,
}

impl Scripted {
    fn with(answers: &[&str]) -> Arc<Self> {
        Arc::new(Scripted {
            answers: Mutex::new(answers.iter().map(|a| vec![a.to_string()]).collect()),
            ..Default::default()
        })
    }

    fn asked(&self) -> Vec<(String, String, Vec<Prompt>)> {
        self.asked.lock().unwrap().clone()
    }

    fn banners(&self) -> Vec<String> {
        self.banners.lock().unwrap().clone()
    }
}

impl AuthPrompter for Scripted {
    fn ask(&self, name: &str, instruction: &str, prompts: &[Prompt]) -> Option<Vec<String>> {
        *self.banner_came_first.lock().unwrap() = !self.banners.lock().unwrap().is_empty();
        self.asked.lock().unwrap().push((name.to_string(), instruction.to_string(), prompts.to_vec()));
        self.answers.lock().unwrap().pop_front()
    }
}

impl BannerSink for Scripted {
    fn banner(&self, text: &str) {
        self.banners.lock().unwrap().push(text.to_string());
    }
}

#[tokio::test]
async fn a_code_is_asked_for_after_the_password() {
    let (port, wire) = serve(Demands::CodeAfterPassword, None).await;
    let prompter = Scripted::with(&[CODE]);
    let opts = options(port, Auth::Password(PASSWORD.into()), Some(prompter.clone()));
    let client = SshClient::connect(opts, Arc::new(AcceptAll)).await.expect("connect");
    client.disconnect().await;

    let asked = prompter.asked();
    assert_eq!(asked.len(), 1, "asked more than once: {asked:?}");
    let (name, instruction, prompts) = &asked[0];
    assert_eq!(name, "Two-factor");
    assert_eq!(instruction, "Enter the code from your phone");
    assert_eq!(prompts.len(), 1);
    assert!(prompts[0].text.to_lowercase().contains("code"), "{:?}", prompts[0]);
    assert!(!prompts[0].echo, "a code must not be echoed");
    // The password was spent on the first factor: the code is what went into
    // the second, not the password again.
    assert_eq!(wire.lock().unwrap().interactive, vec![vec![CODE.to_string()]]);
}

#[tokio::test]
async fn the_stored_password_answers_a_password_prompt_once() {
    let (port, wire) = serve(Demands::InteractiveOnly, None).await;
    let prompter = Scripted::with(&[CODE]);
    let opts = options(port, Auth::Password(PASSWORD.into()), Some(prompter.clone()));
    let client = SshClient::connect(opts, Arc::new(AcceptAll)).await.expect("connect");
    client.disconnect().await;

    // Nobody was asked to type a password the app was already holding — and the
    // second question, which the app cannot answer, went straight to them.
    let asked = prompter.asked();
    assert_eq!(asked.len(), 1, "{asked:?}");
    assert!(asked[0].2[0].text.to_lowercase().contains("code"), "{asked:?}");
    let wire = wire.lock().unwrap();
    assert_eq!(wire.interactive, vec![vec![PASSWORD.to_string()], vec![CODE.to_string()]]);
}

/// The one shape that cannot be proved end to end from here.
///
/// A server that wants a code *after* a key says so by refusing the key with
/// `partial_success` set, and russh's own server cannot send that packet: it
/// assigns the flag and then clears it again before replying to a public key
/// (0.63.2, `server/encrypted.rs`, the `auth_request.partial_success = false`
/// after each publickey rejection). Only the reply to a keyboard-interactive
/// response keeps it, which is the wrong half of the exchange.
///
/// So this pins the halves either side of that packet: the key really is
/// offered and refused with keyboard-interactive named as what to do next, and
/// nobody is asked for a code on the strength of a plain refusal. The decision
/// on the flag itself is `wants_another_factor`, unit-tested in the crate.
#[tokio::test]
async fn a_refused_key_alone_asks_nobody_for_a_code() {
    let (port, _wire) = serve(Demands::CodeAfterKey, None).await;
    let key = ssh_core::keys::generate(KeyAlgorithm::Ed25519, "someone@phone", None).expect("key");
    let prompter = Scripted::with(&[CODE]);
    let auth = Auth::Key { private_key: key.private_key, passphrase: None, certificate: None };
    let err = SshClient::connect(options(port, auth, Some(prompter.clone())), Arc::new(AcceptAll))
        .await
        .err()
        .expect("should fail");
    match err {
        SshError::AuthFailed(reason) => assert!(reason.contains("keyboardinteractive"), "{reason}"),
        other => panic!("{other}"),
    }
    assert!(prompter.asked().is_empty(), "a refused key must not put a code prompt on screen");
}

#[tokio::test]
async fn without_a_prompter_the_password_is_never_sent_twice() {
    let (port, wire) = serve(Demands::InteractiveOnly, None).await;
    let opts = options(port, Auth::Password(PASSWORD.into()), None);
    let err = SshClient::connect(opts, Arc::new(AcceptAll)).await.err().expect("should fail");
    assert!(matches!(err, SshError::AuthFailed(_)), "{err}");
    // It answered the password question, and stopped at the one it could not
    // answer rather than sending the password into it.
    assert_eq!(wire.lock().unwrap().interactive, vec![vec![PASSWORD.to_string()]]);
}

#[tokio::test]
async fn the_banner_arrives_while_the_login_is_still_going_on() {
    const BANNER: &str = "To log in, visit https://login.example/a/CDEF-1234\n";
    let (port, _wire) = serve(Demands::CodeAfterPassword, Some(BANNER)).await;
    let watcher = Scripted::with(&[CODE]);
    let mut opts = options(port, Auth::Password(PASSWORD.into()), Some(watcher.clone()));
    opts.on_banner = Some(watcher.clone());
    let client = SshClient::connect(opts, Arc::new(AcceptAll)).await.expect("connect");
    client.disconnect().await;

    assert_eq!(watcher.banners(), vec![BANNER.to_string()]);
    // A login URL is only any use before the login it is for.
    assert!(*watcher.banner_came_first.lock().unwrap(), "the banner arrived after the question");
}

/// The key exchange is the hybrid, and the client can say so.
///
/// russh puts ML-KEM first today and this pins that it stays first: a version
/// bump that reordered the list would leave every connection looking exactly
/// the same while a recording of it stopped being safe.
#[tokio::test]
async fn the_key_exchange_is_post_quantum() {
    let (port, _wire) = serve(Demands::CodeAfterPassword, None).await;
    let opts = options(port, Auth::Password(PASSWORD.into()), Some(Scripted::with(&[CODE])));
    let client = SshClient::connect(opts, Arc::new(AcceptAll)).await.expect("connect");
    assert_eq!(client.kex_algorithm().as_deref(), Some("mlkem768x25519-sha256"));
    client.disconnect().await;
}

#[tokio::test]
async fn a_cancelled_prompt_ends_the_attempt() {
    let (port, _wire) = serve(Demands::CodeAfterPassword, None).await;
    // No answers scripted at all: the dialog was dismissed.
    let prompter = Scripted::with(&[]);
    let opts = options(port, Auth::Password(PASSWORD.into()), Some(prompter));
    let err = SshClient::connect(opts, Arc::new(AcceptAll)).await.err().expect("should fail");
    assert!(matches!(err, SshError::AuthCancelled(_)), "{err}");
}
