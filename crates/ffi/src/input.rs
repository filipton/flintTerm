//! Where the command being typed begins, for completing it from history.
//!
//! A suggestion is only as good as the reading of what was typed, and the line
//! the cursor is on also holds the prompt. Guessing where a prompt ends from
//! its text is a losing game: a hostname, a path, a git branch, `$`, `#`, `❯`.
//! Guessing wrong is how `systemctl restart nginx` came to be remembered as
//! `nginx`, and how `Password:` ended up in a host's history.
//!
//! What is known for certain is where the cursor stood when somebody started
//! typing: everything before it on that line was put there by the shell. So
//! that text is kept at the first keystroke after a command, and afterwards the
//! line is read as prompt-plus-input only while it still begins with it. The
//! check is on content, not position, which keeps it right across the things
//! that move a line without changing it — scrolling, a wrapped command, Ctrl+L
//! redrawing the prompt at the top, a completion list pushing it down — and
//! makes it give up rather than guess on anything that does change it.

use term_core::CursorLine;

/// What is known about where the current command starts.
#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub(crate) enum InputStart {
    /// A command was just entered, or nothing has been typed yet: the next
    /// keystroke starts the next command.
    #[default]
    Fresh,
    /// The line as it read when typing began, which is the prompt.
    Prompt(String),
    /// Typing began where no prompt was, and nothing is known until the next
    /// command is entered.
    Unknown,
}

impl InputStart {
    /// Keystrokes on their way to the host, before the host has seen them.
    ///
    /// `line` is asked for only when it is needed, which is once a command.
    pub fn typing(&mut self, bytes: &[u8], line: impl FnOnce() -> Option<CursorLine>) {
        if bytes.is_empty() {
            return;
        }
        // Enter, and the two interrupts that give the shell a new prompt
        // without one. A keyboard protocol that encodes them as escapes never
        // gets here, which costs nothing: the next prompt reads the same.
        if bytes.iter().any(|b| matches!(b, b'\r' | b'\n' | 0x03 | 0x04)) {
            *self = InputStart::Fresh;
            return;
        }
        if *self != InputStart::Fresh {
            return;
        }
        *self = match line() {
            // Nothing before the cursor is a keystroke that beat the prompt to
            // the screen, typed while the last command was still finishing.
            // The prompt will be drawn in front of it, and that is not input.
            Some(line) if !line.before.trim().is_empty() => InputStart::Prompt(line.before),
            _ => InputStart::Unknown,
        };
    }

    /// A paste on its way to the host.
    ///
    /// One with a line break in it has either run as commands already or sits
    /// in the shell's editor as a block of lines; neither leaves the next
    /// keystroke at the start of anything.
    pub fn pasting(&mut self, text: &str, line: impl FnOnce() -> Option<CursorLine>) {
        if text.contains(['\r', '\n']) {
            *self = InputStart::Unknown;
        } else {
            self.typing(text.as_bytes(), line);
        }
    }

    /// What was typed after the prompt, when the prompt is known and the line
    /// still begins with it.
    /// Nothing typed since the last command, or since the session began.
    pub fn is_fresh(&self) -> bool {
        *self == InputStart::Fresh
    }

    pub fn typed(&self, before_cursor: &str) -> Option<String> {
        match self {
            InputStart::Prompt(prompt) => before_cursor.strip_prefix(prompt.as_str()).map(str::to_owned),
            _ => None,
        }
    }
}

#[cfg(all(test, feature = "alacritty", not(feature = "ghostty")))]
mod tests {
    use super::*;
    use term_core::Emulator;

    /// A shell on the other end, as far as the screen goes: `type` is what the
    /// keyboard sends, and the echo is fed back the way the host would.
    struct Shell {
        emu: Box<dyn Emulator>,
        start: InputStart,
    }

    impl Shell {
        fn new() -> Self {
            Shell { emu: crate::emulator::new(80, 24, 1000), start: InputStart::default() }
        }
        fn output(&mut self, s: &str) {
            self.emu.feed(s.as_bytes());
        }
        fn keys(&mut self, s: &str) {
            let emu = &self.emu;
            self.start.typing(s.as_bytes(), || emu.cursor_line());
        }
        /// Keys the host echoes, which is every printable one at a prompt.
        fn type_echoed(&mut self, s: &str) {
            self.keys(s);
            self.output(s);
        }
        fn typed(&self) -> Option<String> {
            self.start.typed(&self.emu.cursor_line().unwrap().before)
        }
    }

    const PROMPT: &str = "868ac99d54c5:~$ ";

    #[test]
    fn the_prompt_is_what_was_on_the_line_when_typing_began() {
        let mut sh = Shell::new();
        sh.output(PROMPT);
        assert_eq!(sh.typed(), None, "nothing is known before the first key");
        sh.type_echoed("upt");
        assert_eq!(sh.typed().as_deref(), Some("upt"));
    }

    #[test]
    fn a_command_of_several_words_is_read_whole() {
        let mut sh = Shell::new();
        sh.output(PROMPT);
        sh.type_echoed("systemctl restart nginx");
        assert_eq!(sh.typed().as_deref(), Some("systemctl restart nginx"));
    }

    #[test]
    fn a_prompt_that_takes_no_echo_gives_nothing_typed() {
        let mut sh = Shell::new();
        sh.output("Password: ");
        sh.keys("hunter2");
        assert_eq!(sh.typed().as_deref(), Some(""));
    }

    #[test]
    fn entering_a_command_starts_the_next_one_afresh() {
        let mut sh = Shell::new();
        sh.output(PROMPT);
        sh.type_echoed("uptime");
        sh.keys("\r");
        sh.output("\r\n 12:00:00 up 1 day\r\n");
        assert_eq!(sh.start, InputStart::Fresh);
        sh.output("~/src $ ");
        sh.type_echoed("ls");
        assert_eq!(sh.typed().as_deref(), Some("ls"));
    }

    #[test]
    fn the_same_prompt_drawn_again_is_still_the_prompt() {
        let mut sh = Shell::new();
        sh.output(PROMPT);
        sh.type_echoed("git st");
        // Ctrl+L: the shell clears and draws the prompt and the line again.
        sh.keys("\x0c");
        sh.output(&format!("\x1b[H\x1b[2J{PROMPT}git st"));
        assert_eq!(sh.typed().as_deref(), Some("git st"));
        // A completion list printed under the line pushes it further down.
        sh.keys("\t");
        sh.output(&format!("\r\nstash  status\r\n{PROMPT}git st"));
        assert_eq!(sh.typed().as_deref(), Some("git st"));
    }

    #[test]
    fn a_key_that_beats_the_prompt_to_the_screen_is_not_trusted() {
        let mut sh = Shell::new();
        sh.output(PROMPT);
        sh.type_echoed("make");
        sh.keys("\r");
        sh.output("\r\n");
        // Typed while `make` was still running: the cursor is at the start of
        // an empty line, and the prompt is about to be drawn in front of it.
        sh.keys("ls");
        sh.output(&format!("{PROMPT}ls"));
        assert_eq!(sh.typed(), None);
        // The next command is a fresh start again.
        sh.keys("\r");
        sh.output(&format!("\r\n{PROMPT}"));
        sh.type_echoed("pwd");
        assert_eq!(sh.typed().as_deref(), Some("pwd"));
    }

    #[test]
    fn a_line_that_no_longer_begins_with_the_prompt_is_not_read() {
        let mut sh = Shell::new();
        sh.output(PROMPT);
        sh.keys("\x12"); // Ctrl+R
        sh.output("\r\x1b[K(reverse-i-search)`': ");
        assert_eq!(sh.typed(), None);
    }

    #[test]
    fn a_paste_of_several_lines_leaves_nothing_known() {
        let mut sh = Shell::new();
        sh.output(PROMPT);
        let emu = &sh.emu;
        sh.start.pasting("echo one\necho two", || emu.cursor_line());
        assert_eq!(sh.start, InputStart::Unknown);
        // A paste within one line is typing like any other.
        let mut sh = Shell::new();
        sh.output(PROMPT);
        let emu = &sh.emu;
        sh.start.pasting("uptime", || emu.cursor_line());
        sh.output("uptime");
        assert_eq!(sh.typed().as_deref(), Some("uptime"));
    }
}
