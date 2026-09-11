//! Notices when a long command has finished, so the phone can say so.
//!
//! A build, a backup or a package upgrade is exactly when the phone goes back
//! into a pocket, and coming back to look is the only way to find out whether
//! it worked. There are two ways to know, and this takes whichever it is given.
//!
//! A shell that emits OSC 133 marks says it outright: the command started here,
//! it ended there, and this is the status it ended with. That is the truth, so
//! the moment one mark arrives the guesswork below is switched off for good — a
//! shell either draws its prompt with the marks or it does not, and a heuristic
//! second-guessing a fact can only make it worse.
//!
//! Without marks — which is nearly every shell as it ships — the only signal
//! left is the prompt coming back. That is read off the output alone, because
//! that is the one stream every session has: a prompt, then the newline the
//! shell echoes when Enter is pressed, starts the clock, and the next prompt
//! stops it. The rule is deliberately shy: only a command slow enough that
//! somebody might have walked away is worth a notification, and a wrong guess
//! costs one nobody wanted.
//!
//! This lives on the Rust side of the bridge because the alternative was
//! shipping every chunk of output across it. A session watching a program that
//! redraws itself produces those by the dozen a second, and each one became an
//! array, a string, and a stripped copy of that string on the Java heap — for a
//! result that is thrown away between commands. Here the bytes are already in
//! hand, and the app hears about it once, when a command has actually ended.

use term_core::PromptMark;

/// How long the command that just ended took, and — only when the shell marked
/// it — the status it ended with.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Finished {
    pub elapsed_millis: u64,
    pub exit_status: Option<i32>,
}

/// The tail of a line worth keeping; a prompt is never longer than this.
const LINE_CAP: usize = 400;

pub struct CommandWatch {
    /// Below this, the command was over before anyone could look away.
    min_millis: u64,
    /// A prompt is on screen and nothing has been sent yet.
    at_prompt: bool,
    /// When Enter was pressed, or None while no command is running.
    started_at: Option<u64>,
    /// The current output line, escape sequences already dropped.
    ///
    /// Bytes rather than a string: a multi-byte character can be split across
    /// two chunks, and the prompt glyphs this hunts for — `❯`, `➜` — are all
    /// multi-byte. Held as they arrive and decoded only to look at, the halves
    /// meet up on their own.
    line: Vec<u8>,
    /// This shell marks its prompts, so nothing has to be guessed any more.
    marked: bool,
    /// Inside an escape sequence: 0 = no, 1 = after ESC, 2 = CSI, 3 = OSC.
    esc: u8,
}

impl CommandWatch {
    pub fn new(min_millis: u64) -> Self {
        Self { min_millis, at_prompt: false, started_at: None, line: Vec::new(), marked: false, esc: 0 }
    }

    /// A shell prompt mark, in the order the shell sent it.
    ///
    /// `OutputStart` is where the command really begins and `Finished` is where
    /// it really ends, so those two are the clock. The prompt being drawn or
    /// waiting means, with a command still timing, that it never ended on its
    /// own — Ctrl-C, or a shell that skipped the mark — and there is nothing
    /// honest left to report about it.
    pub fn on_mark(&mut self, mark: PromptMark, now: u64) -> Option<Finished> {
        if !self.marked {
            // Whatever the heuristic had going was a guess about this same
            // shell; it is worth less than the marks and cannot outlive them.
            self.marked = true;
            self.reset();
        }
        match mark {
            PromptMark::OutputStart => {
                self.started_at = Some(now);
                None
            }
            PromptMark::Finished(exit) => {
                let start = self.started_at.take()?;
                let elapsed = now.saturating_sub(start);
                (elapsed >= self.min_millis).then_some(Finished { elapsed_millis: elapsed, exit_status: exit })
            }
            _ => {
                self.started_at = None;
                None
            }
        }
    }

    /// Output as it arrives, escape sequences and all. Returns non-null once
    /// per command, when the prompt comes back after long enough — unless this
    /// shell has shown a mark, in which case [`Self::on_mark`] answers instead.
    pub fn on_output(&mut self, data: &[u8], now: u64) -> Option<Finished> {
        if self.marked {
            return None;
        }
        for &b in data {
            match self.esc {
                1 => {
                    self.esc = match b {
                        b'[' => 2,
                        b']' => 3,
                        _ => 0,
                    }
                }
                2 => {
                    if (0x40..=0x7e).contains(&b) {
                        self.esc = 0;
                    }
                }
                3 => {
                    if b == 0x07 {
                        self.esc = 0;
                    } else if b == 0x1b {
                        self.esc = 1; // ESC \ terminator
                    }
                }
                _ => match b {
                    0x1b => self.esc = 1,
                    b'\n' => {
                        // The shell echoes this when Enter is pressed, which is
                        // the only moment we can be sure a command was sent.
                        if self.at_prompt {
                            self.at_prompt = false;
                            self.started_at = Some(now);
                        }
                        self.line.clear();
                    }
                    b'\r' => self.line.clear(),
                    // Carriage returns, bells and backspaces are how a terminal
                    // draws, not what it printed.
                    0x07 | 0x08 | 0x7f => {}
                    b if b < 0x20 => {}
                    _ => {
                        self.line.push(b);
                        // Trimmed in one go when it has run well past the cap,
                        // not on every byte after it: a line that never ends
                        // was moving the whole buffer down by one per byte.
                        if self.line.len() > LINE_CAP * 2 {
                            let cut = self.line.len() - LINE_CAP;
                            self.line.drain(..cut);
                        }
                    }
                },
            }
        }
        let mut finished = None;
        // Only the tail, so that trimming lazily above cannot change what this
        // sees: the buffer now runs past the cap before it is cut back.
        let tail = &self.line[self.line.len().saturating_sub(LINE_CAP)..];
        if looks_like_prompt(&String::from_utf8_lossy(tail)) {
            if let Some(start) = self.started_at.take() {
                let elapsed = now.saturating_sub(start);
                if elapsed >= self.min_millis {
                    finished = Some(Finished { elapsed_millis: elapsed, exit_status: None });
                }
            }
            self.at_prompt = true;
        }
        finished
    }

    /// Nothing is running any more — a disconnect, or a session being replaced.
    /// A shell that marks its prompts is still that shell afterwards, so that
    /// much is remembered.
    pub fn reset(&mut self) {
        self.at_prompt = false;
        self.started_at = None;
        self.line.clear();
        self.esc = 0;
    }
}

/// `$` and `#` for the classics; the rest are what themed shells end with.
const PROMPT_MARKERS: [char; 9] = ['$', '#', '>', '%', '❯', '→', '➜', '»', 'λ'];

/// Glyphs no program prints at the start of a line, but prompts do.
const LEADING_MARKERS: [char; 5] = ['❯', '→', '➜', '»', 'λ'];

/// Does this line look like a shell waiting for the next command?
///
/// Two shapes cover nearly everything. The classic one ends in `$` or `#` and a
/// space, and so does most of what themed shells draw. The other puts its glyph
/// first and the path last — `→  ~` — where the only thing to go on is the
/// glyph and the fact that a prompt is short.
///
/// The trailing space matters in both: it is what separates a prompt waiting
/// for input from a line of output that happens to end in a bracket. And a long
/// line is output however it ends.
pub fn looks_like_prompt(line: &str) -> bool {
    if line.is_empty() || line.chars().count() > 200 || !line.ends_with(' ') {
        return false;
    }
    let text = line.trim_end();
    if text.chars().next_back().is_some_and(|c| PROMPT_MARKERS.contains(&c)) {
        return true;
    }
    // Glyph-first prompts only: `$` or `#` at the start of a line is as likely
    // to be output — a comment, a quoted shell snippet — as a prompt.
    text.chars().count() <= 80 && text.chars().next().is_some_and(|c| LEADING_MARKERS.contains(&c))
}

#[cfg(test)]
mod tests {
    use super::*;

    const MIN: u64 = 30_000;

    /// The prompt, then the newline the shell echoes when Enter is pressed.
    fn start_command(w: &mut CommandWatch, at: u64) {
        w.on_output(b"pilif@box:~$ ", at);
        w.on_output(b"make -j8\n", at);
    }

    #[test]
    fn a_prompt_is_recognized_by_its_last_character_and_a_space() {
        assert!(looks_like_prompt("pilif@box:~$ "));
        assert!(looks_like_prompt("root@box:/# "));
        assert!(looks_like_prompt("~ ❯ "));
        assert!(looks_like_prompt("→ ~ "));
        assert!(!looks_like_prompt("pilif@box:~$"));
        assert!(!looks_like_prompt("total 48 "));
        assert!(!looks_like_prompt(
            "compiling 50% of the crates in this workspace, hold on a moment please "
        ));
        assert!(!looks_like_prompt(""));
    }

    #[test]
    fn a_slow_command_reports_when_the_prompt_returns() {
        let mut w = CommandWatch::new(MIN);
        start_command(&mut w, 0);
        assert!(w.on_output(b"cc main.c\n", 45_000).is_none());
        let done = w.on_output(b"pilif@box:~$ ", 45_000).expect("reported");
        assert_eq!(done.elapsed_millis, 45_000);
        assert_eq!(done.exit_status, None);
    }

    #[test]
    fn a_quick_command_says_nothing() {
        let mut w = CommandWatch::new(MIN);
        start_command(&mut w, 0);
        assert!(w.on_output(b"pilif@box:~$ ", 2_000).is_none());
    }

    #[test]
    fn a_prompt_with_no_command_before_it_reports_nothing() {
        let mut w = CommandWatch::new(MIN);
        assert!(w.on_output(b"pilif@box:~$ ", 0).is_none());
        assert!(w.on_output(b"pilif@box:~$ ", 90_000).is_none());
    }

    #[test]
    fn only_one_report_per_command() {
        let mut w = CommandWatch::new(MIN);
        start_command(&mut w, 0);
        assert!(w.on_output(b"pilif@box:~$ ", 45_000).is_some());
        assert!(w.on_output(b"pilif@box:~$ ", 90_000).is_none());
    }

    #[test]
    fn escape_sequences_do_not_hide_the_prompt() {
        let mut w = CommandWatch::new(MIN);
        start_command(&mut w, 0);
        // A colored prompt is still a prompt.
        let done = w.on_output(b"\x1b[1;32mpilif@box\x1b[0m:~$ ", 45_000);
        assert!(done.is_some(), "a colored prompt should still be recognized");
    }

    #[test]
    fn a_multibyte_glyph_split_across_chunks_still_reads_as_a_prompt() {
        let mut w = CommandWatch::new(MIN);
        start_command(&mut w, 0);
        // "❯ " arriving as two halves of the same character.
        let glyph = "❯".as_bytes();
        assert!(w.on_output(&glyph[..1], 45_000).is_none());
        let done = w.on_output(&[&glyph[1..], b" "].concat(), 45_000);
        assert!(done.is_some(), "the halves should meet up");
    }

    #[test]
    fn marks_take_over_from_the_guessing_for_good() {
        let mut w = CommandWatch::new(MIN);
        start_command(&mut w, 0);
        // One mark and the heuristic is retired, including what it had going.
        assert!(w.on_mark(PromptMark::PromptStart, 10_000).is_none());
        assert!(w.on_output(b"pilif@box:~$ ", 90_000).is_none());
    }

    #[test]
    fn marks_report_the_elapsed_time_and_the_status() {
        let mut w = CommandWatch::new(MIN);
        assert!(w.on_mark(PromptMark::OutputStart, 1_000).is_none());
        let done = w.on_mark(PromptMark::Finished(Some(2)), 61_000).expect("reported");
        assert_eq!(done.elapsed_millis, 60_000);
        assert_eq!(done.exit_status, Some(2));
    }

    #[test]
    fn a_marked_command_that_was_quick_says_nothing() {
        let mut w = CommandWatch::new(MIN);
        w.on_mark(PromptMark::OutputStart, 1_000);
        assert!(w.on_mark(PromptMark::Finished(Some(0)), 2_000).is_none());
    }

    #[test]
    fn a_marked_command_interrupted_before_it_ended_says_nothing() {
        let mut w = CommandWatch::new(MIN);
        w.on_mark(PromptMark::OutputStart, 0);
        // The prompt comes back without a Finished: Ctrl-C, or a skipped mark.
        assert!(w.on_mark(PromptMark::PromptStart, 90_000).is_none());
        assert!(w.on_mark(PromptMark::Finished(None), 95_000).is_none());
    }

    #[test]
    fn a_reset_forgets_the_running_command_but_not_the_marks() {
        let mut w = CommandWatch::new(MIN);
        w.on_mark(PromptMark::OutputStart, 0);
        w.reset();
        assert!(w.on_mark(PromptMark::Finished(None), 90_000).is_none());
    }

    #[test]
    fn carriage_returns_restart_the_line_rather_than_extending_it() {
        let mut w = CommandWatch::new(MIN);
        start_command(&mut w, 0);
        // A progress bar redrawing itself must not look like a prompt.
        assert!(w.on_output(b"50%\r75%\r100%\r", 45_000).is_none());
    }

    #[test]
    fn a_very_long_line_keeps_only_its_tail() {
        let mut w = CommandWatch::new(MIN);
        start_command(&mut w, 0);
        let long = vec![b'x'; LINE_CAP * 3];
        assert!(w.on_output(&long, 45_000).is_none());
        // The tail is still read: a prompt after all that output is found.
        assert!(w.on_output(b"\npilif@box:~$ ", 45_000).is_some());
    }
}
