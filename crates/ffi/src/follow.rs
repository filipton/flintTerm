//! A container's log, followed straight onto another session's screen.
//!
//! The log tab used to be fed from Kotlin: every two seconds it opened an exec
//! channel, fetched the last lines again, worked out which of them were new and
//! pushed those across the bridge. Here one channel runs `logs -f` for as long as
//! the tab is watched, and what it prints goes into the tab's emulator from the
//! runtime thread that read it — nothing on the Kotlin side runs per chunk, and
//! nothing runs at all while the container is quiet.
//!
//! The command is asked for `--timestamps` so that a follow paused while the tab
//! was out of sight can pick up at the line it stopped at rather than at a time
//! read off the phone's clock, which need not agree with the server's. The
//! stamps are for this bookkeeping only and are taken off before the text is
//! shown.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

use parking_lot::Mutex;
use russh::{ChannelMsg, Sig};
use tokio::sync::Notify;

use crate::runtime::{block_on, RUNTIME};
use crate::session::Session;
use crate::CoreError;

/// Implemented on the Kotlin side: hears that a follow ended without being asked to.
#[uniffi::export(foreign)]
pub trait FollowListener: Send + Sync {
    /// The command finished, or the connection under it went. `error` is None
    /// for a command that exited cleanly — `logs -f` does when the container
    /// stops — and says what happened otherwise.
    fn on_end(&self, error: Option<String>);
}

/// One running follow. Stopping it, or dropping it, closes the channel.
#[derive(uniffi::Object)]
pub struct Follow {
    stop: Arc<Notify>,
    stopped: Arc<AtomicBool>,
    last: Arc<Mutex<Vec<u8>>>,
}

#[uniffi::export]
impl Follow {
    /// Close the channel. The listener is not told: the caller already knows.
    pub fn stop(&self) {
        self.stopped.store(true, Ordering::Release);
        // A permit is kept if the task is between two waits, so this is never lost.
        self.stop.notify_one();
    }

    /// The timestamp of the last whole line shown, to resume after; None before the first.
    pub fn last_timestamp(&self) -> Option<String> {
        let last = self.last.lock();
        (!last.is_empty()).then(|| String::from_utf8_lossy(&last).into_owned())
    }
}

impl Drop for Follow {
    fn drop(&mut self) {
        self.stop();
    }
}

#[uniffi::export]
impl Session {
    /// Run `command` on this session's connection and write what it prints,
    /// stdout and stderr alike, into `into` — a session on the external backend,
    /// such as a playback tab — until [`Follow::stop`] or the command ends.
    ///
    /// Each line is expected to start with a `--timestamps` stamp, which is
    /// removed; a line that does not start with one is shown as it is. Lines
    /// stamped exactly `resume_after` are dropped, because `--since` includes the
    /// line it names and that line was already on screen.
    ///
    /// Blocks until the command has been started, and fails the way
    /// [`Session::exec_live`] does when there is no connection to borrow.
    pub fn follow_logs(
        &self,
        command: String,
        into: Arc<Session>,
        resume_after: Option<String>,
        listener: Arc<dyn FollowListener>,
    ) -> Result<Arc<Follow>, CoreError> {
        let mut channel = block_on(async {
            let client = self.live_ssh().await.ok_or(CoreError::NotConnected)?;
            client.open_exec(&command).await.map_err(CoreError::from)
        })?;
        let follow = Arc::new(Follow {
            stop: Arc::new(Notify::new()),
            stopped: Arc::new(AtomicBool::new(false)),
            last: Arc::new(Mutex::new(Vec::new())),
        });
        let stop = follow.stop.clone();
        let stopped = follow.stopped.clone();
        let last = follow.last.clone();
        RUNTIME.spawn(async move {
            let mut lines = LogLines::new(resume_after.map(String::into_bytes));
            let mut status = None;
            loop {
                let msg = tokio::select! {
                    _ = stop.notified() => break,
                    msg = channel.wait() => msg,
                };
                match msg {
                    Some(ChannelMsg::Data { data }) | Some(ChannelMsg::ExtendedData { data, .. }) => {
                        let mut out = Vec::with_capacity(data.len() + data.len() / 16);
                        lines.feed(&data, &mut out);
                        if !out.is_empty() {
                            into.push_output(out);
                        }
                        if lines.take_changed() {
                            let mut last = last.lock();
                            last.clear();
                            last.extend_from_slice(lines.last());
                        }
                    }
                    Some(ChannelMsg::ExitStatus { exit_status }) => status = Some(exit_status),
                    Some(ChannelMsg::Close) | None => break,
                    _ => {}
                }
            }
            if stopped.load(Ordering::Acquire) {
                // Closing the channel only closes the command's pipes, and a
                // quiet container gives `logs -f` nothing to write that would
                // fail. The signal ends it now, on servers that honour one.
                let _ = channel.signal(Sig::TERM).await;
                let _ = channel.close().await;
                return;
            }
            let _ = channel.close().await;
            let error = match status {
                Some(0) => None,
                Some(n) => Some(format!("the log stopped: the command exited with {n}")),
                None => Some("the log stopped: the connection closed".to_string()),
            };
            listener.on_end(error);
        });
        Ok(follow)
    }
}

/// Stamps longer than this are not stamps; the line is shown as it came.
const MAX_STAMP: usize = 48;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum State {
    /// At the start of a line, reading what may be its stamp.
    Stamp,
    Body,
    /// In a line that was already shown before a pause.
    Skip,
}

/// Takes the stamps off `--timestamps` output and ends every line with CR LF.
///
/// A channel with no terminal on the far end sends bare newlines, which on a
/// screen would walk each line off to the right of the one before it.
pub(crate) struct LogLines {
    state: State,
    stamp: Vec<u8>,
    /// The stamp of the line being shown, and of the last one that finished.
    current: Vec<u8>,
    last: Vec<u8>,
    changed: bool,
    skip: Option<Vec<u8>>,
    prev: u8,
}

impl LogLines {
    pub(crate) fn new(skip: Option<Vec<u8>>) -> Self {
        Self {
            state: State::Stamp,
            stamp: Vec::with_capacity(MAX_STAMP),
            current: Vec::with_capacity(MAX_STAMP),
            last: Vec::with_capacity(MAX_STAMP),
            changed: false,
            skip: skip.filter(|s| !s.is_empty()),
            prev: 0,
        }
    }

    pub(crate) fn feed(&mut self, data: &[u8], out: &mut Vec<u8>) {
        for &b in data {
            match self.state {
                State::Stamp => {
                    if b == b' ' {
                        self.end_stamp(out);
                    } else if b == b'\n' || b == b'\r' || self.stamp.len() >= MAX_STAMP {
                        self.flush_stamp(out);
                        self.state = State::Body;
                        self.body(b, out);
                    } else {
                        self.stamp.push(b);
                    }
                }
                State::Body => self.body(b, out),
                State::Skip => {
                    if b == b'\n' {
                        self.state = State::Stamp;
                    }
                }
            }
        }
    }

    /// The stamp of the last whole line shown.
    pub(crate) fn last(&self) -> &[u8] {
        &self.last
    }

    /// Whether [`Self::last`] moved since this was last asked.
    pub(crate) fn take_changed(&mut self) -> bool {
        std::mem::take(&mut self.changed)
    }

    fn end_stamp(&mut self, out: &mut Vec<u8>) {
        if !is_stamp(&self.stamp) {
            self.flush_stamp(out);
            self.state = State::Body;
            self.body(b' ', out);
            return;
        }
        if self.skip.as_deref() == Some(self.stamp.as_slice()) {
            self.state = State::Skip;
        } else {
            // Past the lines already shown; a later one with the same stamp is new.
            self.skip = None;
            std::mem::swap(&mut self.current, &mut self.stamp);
            self.state = State::Body;
        }
        self.stamp.clear();
    }

    fn flush_stamp(&mut self, out: &mut Vec<u8>) {
        let stamp = std::mem::take(&mut self.stamp);
        for &b in &stamp {
            self.body(b, out);
        }
        self.stamp = stamp;
        self.stamp.clear();
        self.current.clear();
    }

    fn body(&mut self, b: u8, out: &mut Vec<u8>) {
        if b == b'\n' {
            if self.prev != b'\r' {
                out.push(b'\r');
            }
            self.state = State::Stamp;
            // A line counts as shown once it is whole, so a pause in the middle
            // of one shows it again in full rather than losing its end.
            if !self.current.is_empty() {
                std::mem::swap(&mut self.last, &mut self.current);
                self.current.clear();
                self.changed = true;
            }
        }
        out.push(b);
        self.prev = b;
    }
}

/// RFC 3339 as docker and podman print it: `2026-09-23T10:11:12.123456789Z`,
/// or with an offset in place of the `Z`.
fn is_stamp(s: &[u8]) -> bool {
    s.len() >= 20
        && s[..4].iter().all(u8::is_ascii_digit)
        && s[4] == b'-'
        && s[10] == b'T'
        && s.iter().all(|&c| c.is_ascii_digit() || matches!(c, b'-' | b':' | b'.' | b'+' | b'T' | b'Z'))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn run(lines: &mut LogLines, chunks: &[&str]) -> String {
        let mut out = Vec::new();
        for c in chunks {
            lines.feed(c.as_bytes(), &mut out);
        }
        String::from_utf8(out).unwrap()
    }

    #[test]
    fn stamps_come_off_and_lines_end_in_crlf() {
        let mut l = LogLines::new(None);
        let out = run(&mut l, &["2026-09-23T10:00:00.1Z hello\n2026-09-23T10:00:01Z world\n"]);
        assert_eq!(out, "hello\r\nworld\r\n");
        assert_eq!(l.last(), b"2026-09-23T10:00:01Z");
    }

    #[test]
    fn a_stamp_split_across_chunks_still_comes_off() {
        let mut l = LogLines::new(None);
        let out = run(&mut l, &["2026-09-23T10:0", "0:00.123456789+02:00", " a b\n", "2026-09-23T10:00:01Z c", "\n"]);
        assert_eq!(out, "a b\r\nc\r\n");
        assert_eq!(l.last(), b"2026-09-23T10:00:01Z");
    }

    #[test]
    fn a_line_without_a_stamp_is_shown_as_it_came() {
        let mut l = LogLines::new(None);
        let out = run(&mut l, &["Error response from daemon: No such container: x\n\nplain\n"]);
        assert_eq!(out, "Error response from daemon: No such container: x\r\n\r\nplain\r\n");
        assert!(l.last().is_empty());
    }

    #[test]
    fn a_terminal_containers_crlf_is_not_doubled() {
        let mut l = LogLines::new(None);
        let out = run(&mut l, &["2026-09-23T10:00:00Z tty line\r", "\n"]);
        assert_eq!(out, "tty line\r\n");
    }

    #[test]
    fn a_resume_drops_the_line_it_resumed_after_and_nothing_else() {
        let mut l = LogLines::new(Some(b"2026-09-23T10:00:01Z".to_vec()));
        let out = run(
            &mut l,
            &["2026-09-23T10:00:01Z seen\n2026-09-23T10:00:01Z seen too\n2026-09-23T10:00:02Z new\n2026-09-23T10:00:01Z late\n"],
        );
        assert_eq!(out, "new\r\nlate\r\n");
    }

    #[test]
    fn the_last_stamp_waits_for_the_line_to_finish() {
        let mut l = LogLines::new(None);
        run(&mut l, &["2026-09-23T10:00:00Z one\n2026-09-23T10:00:05Z tw"]);
        assert_eq!(l.last(), b"2026-09-23T10:00:00Z");
        assert!(l.take_changed());
        assert!(!l.take_changed());
    }

    #[test]
    fn a_long_first_word_is_not_held_back() {
        let mut l = LogLines::new(None);
        let word = "x".repeat(100);
        let out = run(&mut l, &[&format!("{word} tail\n")]);
        assert_eq!(out, format!("{word} tail\r\n"));
    }
}
