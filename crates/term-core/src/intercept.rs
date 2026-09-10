//! A filter between the pty and the emulator for sequences the backend drops.
//!
//! `alacritty_terminal`'s parser hands unknown OSC, DCS and APC strings to
//! handler methods that do nothing, and there are seventy-one of those methods
//! to wrap if we want at one of them. Reading the bytes before they get there
//! is both smaller and backend-independent: notifications, shell prompt marks
//! and inline images are lifted out here, and the emulator sees a stream with
//! them removed.
//!
//! Two properties matter more than the features:
//!
//! * When nothing is switched on the filter is a `memcpy` — see
//!   [`Interceptor::passthrough`].
//! * When something is switched on, every byte that is not part of a sequence
//!   we claim comes out exactly as it went in, whatever the chunk boundaries.
//!   A terminal that garbles one byte in a million is worse than one that
//!   never learned about notifications.

/// What the filter is asked to claim. Everything off means it does nothing.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct InterceptOptions {
    /// OSC 9, OSC 99 and OSC 777 raise a notification.
    pub notifications: bool,
    /// OSC 133 marks where a prompt, a command and its output begin and end.
    pub prompt_marks: bool,
    /// OSC 7 says which directory the shell is in.
    pub working_directory: bool,
    /// APC `G` (kitty graphics) is lifted out for the image store.
    pub kitty_images: bool,
    /// DCS `q` (sixel) is lifted out for the image store.
    pub sixel_images: bool,
    /// The kitty keyboard protocol and xterm's modifyOtherKeys are spoken.
    ///
    /// When this is off the queries are swallowed rather than answered, so a
    /// program asking "do you support this?" hears nothing and stays legacy.
    /// Answering "no" is not the same thing: some programs read a reply of any
    /// shape as a yes.
    pub keyboard_protocol: bool,
    /// Ctrl+[, Ctrl+I and Ctrl+M leave as keys of their own rather than as the
    /// Escape, Tab and Enter bytes, whether or not a program asked for a
    /// protocol that separates them.
    pub fixterms_ctrl_keys: bool,
}

impl InterceptOptions {
    /// Nothing to claim: the filter can be skipped entirely.
    pub fn idle(&self) -> bool {
        !self.notifications && !self.prompt_marks && !self.working_directory && !self.kitty_images
            && !self.sixel_images && !self.keyboard_protocol
    }
}

/// Where a shell said it was, in the OSC 133 sense.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PromptMark {
    /// `A`: a new prompt is being drawn.
    PromptStart,
    /// `B`: the prompt is drawn, what follows is typed by the person.
    CommandStart,
    /// `C`: the command is running and what follows is its output.
    OutputStart,
    /// `D`: the command finished, with its exit status when the shell said one.
    Finished(Option<i32>),
}

/// What the filter pulled out of the stream.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum InterceptEvent {
    /// A desktop notification the program asked for.
    Notify { title: String, body: String },
    Mark(PromptMark),
    /// The directory the shell says it is in (OSC 7), percent-decoded.
    Cwd(String),
    /// A kitty graphics command: the APC payload with the leading `G` removed.
    KittyGraphics(Vec<u8>),
    /// A sixel image: the DCS parameters and everything after the `q`.
    Sixel { params: Vec<u16>, data: Vec<u8> },
    /// Bytes to write back to the pty, as an answer to a query.
    Reply(Vec<u8>),
}

/// An OSC longer than this is not a notification, it is a runaway program.
const MAX_STRING: usize = 1 << 20;
/// Image payloads are legitimately large; a full-screen sixel clears a megabyte.
const MAX_IMAGE: usize = 16 << 20;
/// No real CSI comes near this; the limit is only there to bound the buffer.
const MAX_CSI: usize = 64;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum State {
    Ground,
    /// ESC seen, waiting for the byte that says what kind of sequence it is.
    Escape,
    Csi,
    /// An OSC, DCS or APC string; `esc` records a pending ESC inside it.
    String { kind: StringKind, esc: bool },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum StringKind {
    Osc,
    Dcs,
    Apc,
}

/// The filter. One per session; `feed` may be called with any chunking.
pub struct Interceptor {
    opts: InterceptOptions,
    state: State,
    /// The sequence being collected, introducer included, so it can be handed
    /// back untouched if we decide not to claim it.
    buf: Vec<u8>,
    /// modifyOtherKeys level the program last asked for (0, 1 or 2).
    modify_other_keys: u8,
    /// Set by a backend that has no switch of its own for the kitty keyboard
    /// protocol. `keyboard_protocol` off means the queries are swallowed here,
    /// and when this is the only thing keeping them from the backend the
    /// filter cannot take the memcpy shortcut in that state.
    keyboard_gate: bool,
    /// Partial OSC 99 notification, by id, while `d=0` says more is coming.
    pending: Vec<(String, String, String)>,
}

impl Interceptor {
    pub fn new(opts: InterceptOptions) -> Self {
        Self {
            opts,
            state: State::Ground,
            buf: Vec::new(),
            modify_other_keys: 0,
            keyboard_gate: false,
            pending: Vec::new(),
        }
    }

    /// Make the filter the backend's kitty keyboard switch.
    ///
    /// `alacritty_terminal` can be told to ignore the protocol, so with every
    /// option off the filter can step aside and let the backend stay silent by
    /// itself. libghostty-vt has no such setting and would answer a program's
    /// query, and then its keys would be encoded in a protocol the app said it
    /// does not speak. With the gate on, the filter stays in the stream while
    /// `keyboard_protocol` is off, whatever the other options say.
    pub fn set_keyboard_gate(&mut self, on: bool) {
        self.keyboard_gate = on;
    }

    pub fn set_options(&mut self, opts: InterceptOptions) {
        self.opts = opts;
    }

    pub fn options(&self) -> InterceptOptions {
        self.opts
    }

    /// The modifyOtherKeys level in force, for the key encoder.
    pub fn modify_other_keys(&self) -> u8 {
        self.modify_other_keys
    }

    /// True when the filter has nothing to claim and the caller may hand the
    /// bytes straight to the emulator.
    pub fn passthrough(&self) -> bool {
        self.opts.idle() && !(self.keyboard_gate && !self.opts.keyboard_protocol) && self.state == State::Ground
    }

    /// Filter `bytes` into `out`, returning what was lifted out of them.
    pub fn feed(&mut self, bytes: &[u8], out: &mut Vec<u8>) -> Vec<InterceptEvent> {
        let mut events = Vec::new();
        if self.passthrough() {
            out.extend_from_slice(bytes);
            return events;
        }
        // Nothing here ever emits more than it was given, so one reservation
        // covers the whole chunk and the buffer stops growing a byte at a time.
        out.reserve(bytes.len());
        let mut i = 0;
        while i < bytes.len() {
            // On the ground everything up to the next ESC is ordinary text, and
            // [`Self::byte`] would do nothing with it but push it one at a
            // time. A terminal stream is mostly ordinary text, so copying the
            // whole run at once is most of what this loop does.
            if matches!(self.state, State::Ground) {
                let rest = &bytes[i..];
                match rest.iter().position(|&b| b == 0x1b) {
                    None => {
                        out.extend_from_slice(rest);
                        break;
                    }
                    Some(0) => {}
                    Some(n) => {
                        out.extend_from_slice(&rest[..n]);
                        i += n;
                        continue;
                    }
                }
            }
            self.byte(bytes[i], out, &mut events);
            i += 1;
        }
        events
    }

    fn byte(&mut self, b: u8, out: &mut Vec<u8>, events: &mut Vec<InterceptEvent>) {
        match self.state {
            State::Ground => {
                if b == 0x1b {
                    self.state = State::Escape;
                    self.buf.clear();
                    self.buf.push(b);
                } else {
                    out.push(b);
                }
            }
            State::Escape => {
                self.buf.push(b);
                match b {
                    b'[' => self.state = State::Csi,
                    b']' if self.wants_osc() => self.state = State::String { kind: StringKind::Osc, esc: false },
                    b'P' if self.opts.sixel_images => self.state = State::String { kind: StringKind::Dcs, esc: false },
                    b'_' if self.opts.kitty_images => self.state = State::String { kind: StringKind::Apc, esc: false },
                    // Another ESC restarts the escape; anything else is a
                    // two-byte sequence we have no interest in.
                    0x1b => {
                        out.extend_from_slice(&self.buf[..self.buf.len() - 1]);
                        self.buf.clear();
                        self.buf.push(b);
                    }
                    _ => self.release(out),
                }
            }
            State::Csi => {
                self.buf.push(b);
                // A CSI ends at its first byte in 0x40..=0x7e; everything
                // before that is parameters and intermediates.
                if (0x40..=0x7e).contains(&b) {
                    self.finish_csi(out, events);
                } else if self.buf.len() > MAX_CSI {
                    self.release(out);
                }
            }
            State::String { kind, esc } => {
                if esc {
                    self.buf.push(b);
                    if b == b'\\' {
                        self.finish_string(kind, out, events);
                    } else {
                        // An ESC that is not ST aborts the string, as xterm
                        // does: hand back what we have and start again at the
                        // ESC we swallowed.
                        self.buf.pop();
                        self.buf.pop();
                        self.release(out);
                        self.state = State::Escape;
                        self.buf.clear();
                        self.buf.push(0x1b);
                        self.byte(b, out, events);
                    }
                    return;
                }
                match b {
                    0x07 => {
                        self.buf.push(b);
                        self.finish_string(kind, out, events);
                    }
                    0x1b => {
                        self.buf.push(b);
                        self.state = State::String { kind, esc: true };
                    }
                    _ => {
                        self.buf.push(b);
                        let cap = if kind == StringKind::Osc { MAX_STRING } else { MAX_IMAGE };
                        if self.buf.len() > cap {
                            self.release(out);
                        }
                    }
                }
            }
        }
    }

    fn wants_osc(&self) -> bool {
        self.opts.notifications || self.opts.prompt_marks || self.opts.working_directory
    }

    /// Give up on the sequence in the buffer and pass it through unchanged.
    fn release(&mut self, out: &mut Vec<u8>) {
        out.extend_from_slice(&self.buf);
        self.buf.clear();
        self.state = State::Ground;
    }

    fn finish_csi(&mut self, out: &mut Vec<u8>, events: &mut Vec<InterceptEvent>) {
        let seq = std::mem::take(&mut self.buf);
        self.state = State::Ground;
        let body = &seq[2..];
        let final_byte = *body.last().unwrap();
        let params = &body[..body.len() - 1];

        if self.opts.keyboard_protocol {
            // modifyOtherKeys: xterm's older answer to the same problem, and
            // still what tmux and a good deal of software asks for first.
            if final_byte == b'm' {
                if let Some(rest) = params.strip_prefix(b">") {
                    let mut it = rest.split(|&c| c == b';');
                    if it.next().map(|p| p == b"4").unwrap_or(false) {
                        self.modify_other_keys = it
                            .next()
                            .and_then(|v| std::str::from_utf8(v).ok())
                            .and_then(|v| v.parse::<u8>().ok())
                            .unwrap_or(0)
                            .min(2);
                        return;
                    }
                } else if params == b"?4" {
                    events.push(InterceptEvent::Reply(
                        format!("\x1b[>4;{}m", self.modify_other_keys).into_bytes(),
                    ));
                    return;
                }
            }
        } else if final_byte == b'u' && matches!(params.first(), Some(b'?') | Some(b'>') | Some(b'<') | Some(b'=')) {
            // Swallowed rather than answered: see InterceptOptions.
            return;
        }
        out.extend_from_slice(&seq);
    }

    fn finish_string(&mut self, kind: StringKind, out: &mut Vec<u8>, events: &mut Vec<InterceptEvent>) {
        let seq = std::mem::take(&mut self.buf);
        self.state = State::Ground;
        // Strip the introducer (ESC + one byte) and the terminator (BEL, or
        // ESC \).
        let end = if seq.ends_with(b"\x1b\\") { seq.len() - 2 } else { seq.len() - 1 };
        let body = &seq[2..end];
        let claimed = match kind {
            StringKind::Osc => self.osc(body, events),
            StringKind::Apc => match body.split_first() {
                Some((b'G', rest)) => {
                    events.push(InterceptEvent::KittyGraphics(rest.to_vec()));
                    true
                }
                _ => false,
            },
            StringKind::Dcs => {
                // ESC P <params> q <data>: only sixel is ours; anything else
                // (DECRQSS, tmux passthrough) goes on to the emulator.
                match body.iter().position(|&c| c == b'q') {
                    Some(i) if body[..i].iter().all(|c| c.is_ascii_digit() || *c == b';') => {
                        let params = body[..i]
                            .split(|&c| c == b';')
                            .map(|p| std::str::from_utf8(p).ok().and_then(|s| s.parse().ok()).unwrap_or(0))
                            .collect();
                        events.push(InterceptEvent::Sixel { params, data: body[i + 1..].to_vec() });
                        true
                    }
                    _ => false,
                }
            }
        };
        if !claimed {
            out.extend_from_slice(&seq);
        }
    }

    /// Returns whether the OSC was ours.
    fn osc(&mut self, body: &[u8], events: &mut Vec<InterceptEvent>) -> bool {
        let mut parts = body.splitn(2, |&c| c == b';');
        let num = parts.next().unwrap_or(b"");
        let rest = parts.next().unwrap_or(b"");
        match num {
            b"9" if self.opts.notifications => {
                // OSC 9;4 is ConEmu's progress report, which shares the number
                // and means something else entirely; leaving it to the
                // emulator is the only safe reading.
                if rest.starts_with(b"4;") || rest == b"4" {
                    return false;
                }
                events.push(InterceptEvent::Notify {
                    title: String::new(),
                    body: String::from_utf8_lossy(rest).into_owned(),
                });
                true
            }
            b"99" if self.opts.notifications => {
                self.kitty_notification(rest, events);
                true
            }
            b"777" if self.opts.notifications => {
                let mut it = rest.splitn(3, |&c| c == b';');
                if it.next() != Some(b"notify") {
                    return false;
                }
                let title = String::from_utf8_lossy(it.next().unwrap_or(b"")).into_owned();
                let body = String::from_utf8_lossy(it.next().unwrap_or(b"")).into_owned();
                events.push(InterceptEvent::Notify { title, body });
                true
            }
            b"7" if self.opts.working_directory => match cwd_path(rest) {
                Some(path) => {
                    events.push(InterceptEvent::Cwd(path));
                    true
                }
                None => false,
            },
            b"133" if self.opts.prompt_marks => {
                let mut it = rest.splitn(2, |&c| c == b';');
                let mark = match it.next() {
                    Some(b"A") => PromptMark::PromptStart,
                    Some(b"B") => PromptMark::CommandStart,
                    Some(b"C") => PromptMark::OutputStart,
                    Some(b"D") => {
                        let exit = it
                            .next()
                            .and_then(|v| std::str::from_utf8(v).ok())
                            // The status may carry `;aid=…` after it.
                            .and_then(|v| v.split(';').next().unwrap_or("").parse().ok());
                        PromptMark::Finished(exit)
                    }
                    _ => return false,
                };
                events.push(InterceptEvent::Mark(mark));
                true
            }
            _ => false,
        }
    }

    /// kitty's OSC 99: `metadata ; payload`, where the metadata is `k=v`
    /// pairs joined by colons. Only the parts that decide what a person sees
    /// are read — the identifier, which half of the notification this is, and
    /// whether more chunks follow.
    fn kitty_notification(&mut self, rest: &[u8], events: &mut Vec<InterceptEvent>) {
        let mut parts = rest.splitn(2, |&c| c == b';');
        let meta = parts.next().unwrap_or(b"");
        let payload = String::from_utf8_lossy(parts.next().unwrap_or(b"")).into_owned();
        let (mut id, mut which, mut done) = (String::new(), "title".to_string(), true);
        for kv in meta.split(|&c| c == b':') {
            let mut it = kv.splitn(2, |&c| c == b'=');
            let k = it.next().unwrap_or(b"");
            let v = String::from_utf8_lossy(it.next().unwrap_or(b"")).into_owned();
            match k {
                b"i" => id = v,
                b"p" => which = v,
                b"d" => done = v != "0",
                _ => {}
            }
        }
        let slot = match self.pending.iter().position(|(k, _, _)| *k == id) {
            Some(i) => i,
            None => {
                self.pending.push((id.clone(), String::new(), String::new()));
                if self.pending.len() > 8 {
                    self.pending.remove(0);
                }
                self.pending.len() - 1
            }
        };
        if which == "body" {
            self.pending[slot].2.push_str(&payload);
        } else {
            self.pending[slot].1.push_str(&payload);
        }
        if done {
            let (_, title, body) = self.pending.remove(slot);
            if !title.is_empty() || !body.is_empty() {
                events.push(InterceptEvent::Notify { title, body });
            }
        }
    }
}

/// The directory out of an OSC 7 payload, or `None` if it does not name one.
///
/// The sequence is written up as a `file://` URL, and the shells that follow
/// the write-up put a host name in it. That name is whatever `hostname` says at
/// the far end, which is neither something we can check nor something we have a
/// use for: the directory is on the machine this session is talking to whatever
/// the URL claims, so the host is read past rather than compared. Shells that
/// send the bare path instead — and there are several — are read the same way.
fn cwd_path(rest: &[u8]) -> Option<String> {
    let path = match rest.strip_prefix(b"file://") {
        Some(after) => &after[after.iter().position(|&c| c == b'/')?..],
        None => rest,
    };
    // A relative path would be relative to somewhere we cannot know, so an
    // absolute one is the only kind worth claiming.
    if !path.starts_with(b"/") {
        return None;
    }
    // Lossy on purpose: a path that is not UTF-8 is still better shown with a
    // replacement character in it than dropped for being unpronounceable.
    Some(String::from_utf8_lossy(&percent_decode(path)).into_owned())
}

fn percent_decode(s: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(s.len());
    let mut i = 0;
    while i < s.len() {
        if s[i] == b'%' && i + 2 < s.len() {
            if let (Some(hi), Some(lo)) = (hex(s[i + 1]), hex(s[i + 2])) {
                out.push(hi << 4 | lo);
                i += 3;
                continue;
            }
        }
        // A stray `%` is a literal one: no shell escapes it, and a path is
        // better off with it than a byte short.
        out.push(s[i]);
        i += 1;
    }
    out
}

fn hex(b: u8) -> Option<u8> {
    match b {
        b'0'..=b'9' => Some(b - b'0'),
        b'a'..=b'f' => Some(b - b'a' + 10),
        b'A'..=b'F' => Some(b - b'A' + 10),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn all_on() -> InterceptOptions {
        InterceptOptions {
            notifications: true,
            prompt_marks: true,
            working_directory: true,
            kitty_images: true,
            sixel_images: true,
            keyboard_protocol: true,
            fixterms_ctrl_keys: true,
        }
    }

    /// Feed the whole input in one go.
    fn run(opts: InterceptOptions, input: &[u8]) -> (Vec<u8>, Vec<InterceptEvent>) {
        let mut i = Interceptor::new(opts);
        let mut out = Vec::new();
        let ev = i.feed(input, &mut out);
        (out, ev)
    }

    /// Feed one byte at a time, which is the worst chunking a network offers.
    fn run_split(opts: InterceptOptions, input: &[u8]) -> (Vec<u8>, Vec<InterceptEvent>) {
        let mut i = Interceptor::new(opts);
        let mut out = Vec::new();
        let mut ev = Vec::new();
        for b in input {
            ev.extend(i.feed(&[*b], &mut out));
        }
        (out, ev)
    }

    fn both(opts: InterceptOptions, input: &[u8]) -> (Vec<u8>, Vec<InterceptEvent>) {
        let whole = run(opts, input);
        let split = run_split(opts, input);
        assert_eq!(whole, split, "chunking changed the result");
        whole
    }

    #[test]
    fn idle_is_a_memcpy() {
        let opts = InterceptOptions::default();
        assert!(Interceptor::new(opts).passthrough());
        let input = b"\x1b]9;hi\x07\x1b[31mred\x1b_Gf=100\x1b\\";
        let (out, ev) = both(opts, input);
        assert_eq!(out, input);
        assert!(ev.is_empty());
    }

    #[test]
    fn text_around_a_claimed_sequence_survives() {
        let (out, ev) = both(all_on(), b"before\x1b]9;done\x07after");
        assert_eq!(out, b"beforeafter");
        assert_eq!(ev, vec![InterceptEvent::Notify { title: String::new(), body: "done".into() }]);
    }

    #[test]
    fn both_terminators_work() {
        for input in [&b"\x1b]777;notify;Build;ok\x07"[..], &b"\x1b]777;notify;Build;ok\x1b\\"[..]] {
            let (out, ev) = both(all_on(), input);
            assert!(out.is_empty());
            assert_eq!(ev, vec![InterceptEvent::Notify { title: "Build".into(), body: "ok".into() }]);
        }
    }

    #[test]
    fn conemu_progress_is_left_alone() {
        let input = b"\x1b]9;4;1;40\x07";
        let (out, ev) = both(all_on(), input);
        assert_eq!(out, input);
        assert!(ev.is_empty());
    }

    #[test]
    fn kitty_notification_arrives_in_chunks() {
        let input = b"\x1b]99;i=1:d=0;Build \x1b\\\x1b]99;i=1:p=body;finished\x1b\\";
        let (out, ev) = both(all_on(), input);
        assert!(out.is_empty());
        assert_eq!(ev, vec![InterceptEvent::Notify { title: "Build ".into(), body: "finished".into() }]);
    }

    #[test]
    fn prompt_marks_carry_the_exit_status() {
        let (_, ev) = both(all_on(), b"\x1b]133;A\x07\x1b]133;C\x07\x1b]133;D;7\x07\x1b]133;D\x07");
        assert_eq!(
            ev,
            vec![
                InterceptEvent::Mark(PromptMark::PromptStart),
                InterceptEvent::Mark(PromptMark::OutputStart),
                InterceptEvent::Mark(PromptMark::Finished(Some(7))),
                InterceptEvent::Mark(PromptMark::Finished(None)),
            ]
        );
    }

    #[test]
    fn a_working_directory_arrives_as_a_url_or_as_a_path() {
        for input in [
            &b"\x1b]7;file://boxy/home/ada/src\x07"[..],
            &b"\x1b]7;file://boxy/home/ada/src\x1b\\"[..],
            &b"\x1b]7;/home/ada/src\x07"[..],
            &b"\x1b]7;/home/ada/src\x1b\\"[..],
            // The host in the URL is the far end's idea of its own name; the
            // path is ours to use whatever it says there.
            &b"\x1b]7;file://somewhere.else/home/ada/src\x07"[..],
            // An empty host is what a shell sends when it has none to give.
            &b"\x1b]7;file:///home/ada/src\x07"[..],
        ] {
            let (out, ev) = both(all_on(), input);
            assert!(out.is_empty(), "{input:?} was not claimed");
            assert_eq!(ev, vec![InterceptEvent::Cwd("/home/ada/src".into())]);
        }
    }

    #[test]
    fn a_working_directory_is_percent_decoded() {
        let (_, ev) = both(all_on(), b"\x1b]7;file://box/home/ada/my%20notes/%C3%A4%25\x07");
        assert_eq!(ev, vec![InterceptEvent::Cwd("/home/ada/my notes/ä%".into())]);
    }

    #[test]
    fn an_osc_7_that_names_no_directory_is_left_alone() {
        // Neither a path nor a URL we can read a path out of: the emulator can
        // have them, in case it makes more of them than we do.
        for input in [&b"\x1b]7;\x07"[..], &b"\x1b]7;relative/path\x07"[..], &b"\x1b]7;file://boxy\x07"[..]] {
            let (out, ev) = both(all_on(), input);
            assert_eq!(out, input);
            assert!(ev.is_empty());
        }
    }

    #[test]
    fn the_working_directory_is_left_in_the_stream_when_it_is_switched_off() {
        let opts = InterceptOptions { notifications: true, ..Default::default() };
        let input = b"\x1b]7;file://boxy/tmp\x07";
        let (out, ev) = both(opts, input);
        assert_eq!(out, input);
        assert!(ev.is_empty());
    }

    #[test]
    fn images_are_lifted_out() {
        let (out, ev) = both(all_on(), b"\x1b_Ga=T,f=100;AAAA\x1b\\x\x1bP0;1;0qdata\x1b\\");
        assert_eq!(out, b"x");
        assert_eq!(
            ev,
            vec![
                InterceptEvent::KittyGraphics(b"a=T,f=100;AAAA".to_vec()),
                InterceptEvent::Sixel { params: vec![0, 1, 0], data: b"data".to_vec() },
            ]
        );
    }

    #[test]
    fn other_dcs_strings_go_on_to_the_emulator() {
        // DECRQSS, which shares the introducer and is none of our business.
        let input = b"\x1bP$qm\x1b\\";
        let (out, ev) = both(all_on(), input);
        assert_eq!(out, input);
        assert!(ev.is_empty());
    }

    #[test]
    fn modify_other_keys_is_remembered_and_answered() {
        let mut i = Interceptor::new(all_on());
        let mut out = Vec::new();
        let ev = i.feed(b"\x1b[>4;2m", &mut out);
        assert!(out.is_empty() && ev.is_empty());
        assert_eq!(i.modify_other_keys(), 2);
        let ev = i.feed(b"\x1b[?4m", &mut out);
        assert_eq!(ev, vec![InterceptEvent::Reply(b"\x1b[>4;2m".to_vec())]);
        i.feed(b"\x1b[>4m", &mut out);
        assert_eq!(i.modify_other_keys(), 0);
        assert!(out.is_empty());
    }

    #[test]
    fn keyboard_queries_are_swallowed_when_the_protocol_is_off() {
        let opts = InterceptOptions { keyboard_protocol: false, notifications: true, ..Default::default() };
        let (out, ev) = both(opts, b"\x1b[?u\x1b[>1u\x1b[<u\x1b[=5;1uleft");
        assert_eq!(out, b"left");
        assert!(ev.is_empty());
        // A CSI that merely ends in `u` without a private marker is somebody
        // else's (a cursor restore, for one) and must go through.
        let (out, _) = both(opts, b"\x1b[u");
        assert_eq!(out, b"\x1b[u");
    }

    #[test]
    fn the_keyboard_gate_keeps_the_filter_in_the_stream_when_everything_is_off() {
        let mut i = Interceptor::new(InterceptOptions::default());
        i.set_keyboard_gate(true);
        assert!(!i.passthrough());
        let mut out = Vec::new();
        let ev = i.feed(b"\x1b[?u\x1b[>1u\x1b[31mred\x1b]9;hi\x07", &mut out);
        // The keyboard queries are gone; everything else, notifications
        // included, is left exactly as it came.
        assert_eq!(out, b"\x1b[31mred\x1b]9;hi\x07");
        assert!(ev.is_empty());
        // Speaking the protocol lifts the gate's cost: nothing is left to swallow.
        i.set_options(InterceptOptions { keyboard_protocol: true, ..Default::default() });
        assert!(!i.passthrough(), "the protocol itself needs the filter for modifyOtherKeys");
        i.set_keyboard_gate(false);
        i.set_options(InterceptOptions::default());
        assert!(i.passthrough());
    }

    #[test]
    fn an_escape_inside_a_string_aborts_it() {
        // ESC without a backslash ends the string and starts a new sequence.
        let (out, ev) = both(all_on(), b"\x1b]9;half\x1b[31mred");
        assert_eq!(out, b"\x1b]9;half\x1b[31mred");
        assert!(ev.is_empty());
    }

    #[test]
    fn a_runaway_string_is_handed_back_rather_than_hoarded() {
        let mut input = b"\x1b]9;".to_vec();
        input.extend(std::iter::repeat(b'x').take(MAX_STRING + 8));
        let (out, ev) = run(all_on(), &input);
        assert!(ev.is_empty());
        assert_eq!(out.len(), input.len());
        assert_eq!(&out[..4], b"\x1b]9;");
    }

    #[test]
    fn an_unfinished_sequence_holds_only_itself() {
        let mut i = Interceptor::new(all_on());
        let mut out = Vec::new();
        i.feed(b"text\x1b]9;par", &mut out);
        assert_eq!(out, b"text");
        let ev = i.feed(b"tial\x07more", &mut out);
        assert_eq!(out, b"textmore");
        assert_eq!(ev, vec![InterceptEvent::Notify { title: String::new(), body: "partial".into() }]);
    }

    #[test]
    fn everything_that_is_not_ours_comes_out_byte_for_byte() {
        // A deterministic stand-in for random input: every byte value except
        // ESC, in a long enough run to cross every branch.
        let mut input = Vec::new();
        for round in 0..40u32 {
            for b in 0u8..=255 {
                if b != 0x1b {
                    input.push(b.wrapping_add(round as u8));
                }
            }
        }
        input.retain(|&b| b != 0x1b);
        let (out, ev) = both(all_on(), &input);
        assert_eq!(out, input);
        assert!(ev.is_empty());
    }
}
