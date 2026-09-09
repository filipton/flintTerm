//! Minimal Telnet (RFC 854) client — enough of the protocol to drive the
//! consoles that still speak it: switches, PDUs, serial-over-LAN boxes, BMCs.
//!
//! We negotiate the four options that matter for an interactive terminal
//! (remote ECHO and SUPPRESS-GO-AHEAD from the server, TERMINAL-TYPE and NAWS
//! from us) and refuse everything else. Option state is tracked per side so a
//! refusal is only ever sent once — answering every DONT with a WONT is how
//! telnet implementations end up in a negotiation loop.

use std::collections::HashSet;
use std::sync::Arc;

use bytes::Bytes;
use ssh_core::BoxedStream;
use tokio::io::{AsyncReadExt, AsyncWriteExt, ReadHalf, WriteHalf};
use tokio::sync::Mutex;

use crate::transport::Event;

// Commands.
const SE: u8 = 240;
const SB: u8 = 250;
const WILL: u8 = 251;
const WONT: u8 = 252;
const DO: u8 = 253;
const DONT: u8 = 254;
const IAC: u8 = 255;

// Options.
const OPT_BINARY: u8 = 0;
const OPT_ECHO: u8 = 1;
const OPT_SGA: u8 = 3;
const OPT_TTYPE: u8 = 24;
const OPT_NAWS: u8 = 31;

const TTYPE_IS: u8 = 0;
const TTYPE_SEND: u8 = 1;

/// Options we are willing to perform when the server asks (IAC DO x).
const WE_SUPPORT: [u8; 3] = [OPT_TTYPE, OPT_NAWS, OPT_BINARY];
/// Options we want the server to perform when it offers (IAC WILL x).
const WE_WANT: [u8; 3] = [OPT_ECHO, OPT_SGA, OPT_BINARY];

const TERM: &[u8] = b"xterm-256color";

pub struct TelnetIo;

impl TelnetIo {
    /// Split a freshly connected stream into the session's reader and writer halves.
    pub fn new(stream: BoxedStream, cols: u16, rows: u16) -> (TelnetReader, TelnetWriter) {
        let (r, w) = tokio::io::split(stream);
        let writer = TelnetWriter {
            inner: Arc::new(Mutex::new(w)),
            state: Arc::new(Mutex::new(Negotiated { size: (cols, rows), naws: false })),
        };
        let reader = TelnetReader { inner: r, writer: writer.clone(), parser: Parser::default(), buf: vec![0u8; 32 * 1024] };
        (reader, writer)
    }
}

struct Negotiated {
    size: (u16, u16),
    /// The server accepted our offer to report the window size.
    naws: bool,
}

#[derive(Default)]
struct Parser {
    state: State,
    /// Options we have agreed to perform, and ones we have already refused.
    will: HashSet<u8>,
    refused_will: HashSet<u8>,
    /// Options we have asked the server to perform, and ones we have already declined.
    r#do: HashSet<u8>,
    refused_do: HashSet<u8>,
    sub: Vec<u8>,
}

#[derive(Default, PartialEq)]
enum State {
    #[default]
    Data,
    Iac,
    Will,
    Wont,
    Do,
    Dont,
    Sub,
    SubIac,
}

/// What the parser wants done after a chunk: clean payload, and bytes to send back.
struct Parsed {
    data: Vec<u8>,
    reply: Vec<u8>,
    /// The server accepted NAWS, so the current size should be reported.
    naws_agreed: bool,
}

impl Parser {
    fn feed(&mut self, input: &[u8]) -> Parsed {
        let mut out = Parsed { data: Vec::with_capacity(input.len()), reply: Vec::new(), naws_agreed: false };
        for &b in input {
            match self.state {
                State::Data => {
                    if b == IAC {
                        self.state = State::Iac;
                    } else {
                        out.data.push(b);
                    }
                }
                State::Iac => match b {
                    IAC => {
                        // Escaped 0xFF is literal data.
                        out.data.push(IAC);
                        self.state = State::Data;
                    }
                    WILL => self.state = State::Will,
                    WONT => self.state = State::Wont,
                    DO => self.state = State::Do,
                    DONT => self.state = State::Dont,
                    SB => {
                        self.sub.clear();
                        self.state = State::Sub;
                    }
                    // Everything else (NOP, GA, data-mark…) needs no answer.
                    _ => self.state = State::Data,
                },
                State::Will => {
                    self.on_will(b, &mut out);
                    self.state = State::Data;
                }
                State::Wont => {
                    if self.r#do.remove(&b) {
                        out.reply.extend_from_slice(&[IAC, DONT, b]);
                    }
                    self.state = State::Data;
                }
                State::Do => {
                    self.on_do(b, &mut out);
                    self.state = State::Data;
                }
                State::Dont => {
                    if self.will.remove(&b) {
                        out.reply.extend_from_slice(&[IAC, WONT, b]);
                    }
                    self.state = State::Data;
                }
                State::Sub => {
                    if b == IAC {
                        self.state = State::SubIac;
                    } else {
                        self.sub.push(b);
                    }
                }
                State::SubIac => {
                    if b == SE {
                        self.on_subnegotiation(&mut out);
                        self.state = State::Data;
                    } else {
                        // IAC IAC inside a subnegotiation is a literal 0xFF.
                        self.sub.push(b);
                        self.state = State::Sub;
                    }
                }
            }
        }
        out
    }

    /// The server offers to perform an option.
    fn on_will(&mut self, opt: u8, out: &mut Parsed) {
        if WE_WANT.contains(&opt) {
            if self.r#do.insert(opt) {
                out.reply.extend_from_slice(&[IAC, DO, opt]);
            }
        } else if self.refused_do.insert(opt) {
            out.reply.extend_from_slice(&[IAC, DONT, opt]);
        }
    }

    /// The server asks us to perform an option.
    fn on_do(&mut self, opt: u8, out: &mut Parsed) {
        if WE_SUPPORT.contains(&opt) {
            if self.will.insert(opt) {
                out.reply.extend_from_slice(&[IAC, WILL, opt]);
                if opt == OPT_NAWS {
                    out.naws_agreed = true;
                }
            }
        } else if self.refused_will.insert(opt) {
            out.reply.extend_from_slice(&[IAC, WONT, opt]);
        }
    }

    fn on_subnegotiation(&mut self, out: &mut Parsed) {
        // Only TERMINAL-TYPE SEND needs an answer; the rest we happily ignore.
        if self.sub.len() >= 2 && self.sub[0] == OPT_TTYPE && self.sub[1] == TTYPE_SEND {
            out.reply.extend_from_slice(&[IAC, SB, OPT_TTYPE, TTYPE_IS]);
            out.reply.extend_from_slice(TERM);
            out.reply.extend_from_slice(&[IAC, SE]);
        }
        self.sub.clear();
    }
}

pub struct TelnetReader {
    inner: ReadHalf<BoxedStream>,
    writer: TelnetWriter,
    parser: Parser,
    buf: Vec<u8>,
}

impl TelnetReader {
    pub async fn next(&mut self) -> Event {
        loop {
            let n = match self.inner.read(&mut self.buf).await {
                Ok(0) | Err(_) => return Event::Closed,
                Ok(n) => n,
            };
            let parsed = self.parser.feed(&self.buf[..n]);
            if !parsed.reply.is_empty() && !self.writer.raw(&parsed.reply).await {
                return Event::Closed;
            }
            if parsed.naws_agreed {
                self.writer.state.lock().await.naws = true;
                self.writer.send_naws().await;
            }
            if !parsed.data.is_empty() {
                return Event::Data(Bytes::from(parsed.data));
            }
            // Pure negotiation chunk — keep reading rather than reporting an empty frame.
        }
    }
}

#[derive(Clone)]
pub struct TelnetWriter {
    inner: Arc<Mutex<WriteHalf<BoxedStream>>>,
    state: Arc<Mutex<Negotiated>>,
}

impl TelnetWriter {
    /// Write bytes verbatim (negotiation); returns false once the socket is gone.
    async fn raw(&self, bytes: &[u8]) -> bool {
        let mut w = self.inner.lock().await;
        w.write_all(bytes).await.is_ok() && w.flush().await.is_ok()
    }

    pub async fn write(&self, bytes: Vec<u8>) -> bool {
        self.raw(&escape(&bytes)).await
    }

    pub async fn resize(&self, cols: u16, rows: u16) {
        {
            let mut s = self.state.lock().await;
            if s.size == (cols, rows) {
                return;
            }
            s.size = (cols, rows);
            if !s.naws {
                return;
            }
        }
        self.send_naws().await;
    }

    async fn send_naws(&self) {
        let (cols, rows) = self.state.lock().await.size;
        let mut msg = vec![IAC, SB, OPT_NAWS];
        // Width and height are 16-bit big-endian, and 0xFF still has to be escaped.
        for b in [(cols >> 8) as u8, cols as u8, (rows >> 8) as u8, rows as u8] {
            msg.push(b);
            if b == IAC {
                msg.push(IAC);
            }
        }
        msg.extend_from_slice(&[IAC, SE]);
        self.raw(&msg).await;
    }

    pub async fn close(&self) {
        let mut w = self.inner.lock().await;
        let _ = w.shutdown().await;
    }
}

/// Outbound data escaping: 0xFF is doubled, and a bare CR gets the NUL that
/// RFC 854 requires so servers do not read it as the start of a CR LF.
fn escape(bytes: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        let b = bytes[i];
        match b {
            IAC => out.extend_from_slice(&[IAC, IAC]),
            b'\r' => {
                out.push(b'\r');
                if bytes.get(i + 1) != Some(&b'\n') {
                    out.push(0);
                }
            }
            _ => out.push(b),
        }
        i += 1;
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn parse(p: &mut Parser, input: &[u8]) -> (Vec<u8>, Vec<u8>) {
        let r = p.feed(input);
        (r.data, r.reply)
    }

    #[test]
    fn passes_data_through_and_unescapes_iac() {
        let mut p = Parser::default();
        let (data, reply) = parse(&mut p, b"hi\xff\xffthere");
        assert_eq!(data, b"hi\xffthere");
        assert!(reply.is_empty());
    }

    #[test]
    fn agrees_to_the_options_we_support_and_refuses_the_rest() {
        let mut p = Parser::default();
        let (data, reply) = parse(&mut p, &[IAC, DO, OPT_NAWS, IAC, DO, 99, IAC, WILL, OPT_ECHO, IAC, WILL, 77]);
        assert!(data.is_empty());
        assert_eq!(
            reply,
            vec![IAC, WILL, OPT_NAWS, IAC, WONT, 99, IAC, DO, OPT_ECHO, IAC, DONT, 77],
        );
    }

    #[test]
    fn does_not_loop_on_repeated_negotiation() {
        let mut p = Parser::default();
        let (_, first) = parse(&mut p, &[IAC, DO, OPT_NAWS, IAC, DO, 99]);
        assert!(!first.is_empty());
        // The same requests again are already-settled state: stay quiet.
        let (_, second) = parse(&mut p, &[IAC, DO, OPT_NAWS, IAC, DO, 99]);
        assert!(second.is_empty(), "answered twice: {second:?}");
    }

    #[test]
    fn answers_terminal_type_requests() {
        let mut p = Parser::default();
        let (_, reply) = parse(&mut p, &[IAC, SB, OPT_TTYPE, TTYPE_SEND, IAC, SE]);
        let mut want = vec![IAC, SB, OPT_TTYPE, TTYPE_IS];
        want.extend_from_slice(TERM);
        want.extend_from_slice(&[IAC, SE]);
        assert_eq!(reply, want);
    }

    #[test]
    fn splits_sequences_across_chunk_boundaries() {
        let mut p = Parser::default();
        let (d1, r1) = parse(&mut p, &[b'a', IAC]);
        assert_eq!(d1, b"a");
        assert!(r1.is_empty());
        let (d2, r2) = parse(&mut p, &[DO, OPT_NAWS]);
        assert!(d2.is_empty());
        assert_eq!(r2, vec![IAC, WILL, OPT_NAWS]);
    }

    #[test]
    fn withdrawing_an_option_is_acknowledged_once() {
        let mut p = Parser::default();
        parse(&mut p, &[IAC, DO, OPT_NAWS]);
        let (_, reply) = parse(&mut p, &[IAC, DONT, OPT_NAWS]);
        assert_eq!(reply, vec![IAC, WONT, OPT_NAWS]);
        let (_, again) = parse(&mut p, &[IAC, DONT, OPT_NAWS]);
        assert!(again.is_empty());
    }

    #[test]
    fn escapes_outbound_data() {
        assert_eq!(escape(b"a\xffb"), b"a\xff\xffb");
        assert_eq!(escape(b"x\r"), b"x\r\0");
        assert_eq!(escape(b"x\r\n"), b"x\r\n");
    }
}
