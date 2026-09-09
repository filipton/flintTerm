//! Predictive local echo: draw a keystroke before the server confirms it.
//!
//! On a link with real latency, the gap between pressing a key and seeing it is
//! the whole reason typing over SSH feels bad. Mosh's answer is to guess — show
//! the character at once, then check the guess against what the server actually
//! says and correct if it was wrong.
//!
//! This engine holds only the guesses. It never touches a terminal: the caller
//! tells it where the cursor is and, later, what the server put on that row, and
//! it says which cells to overlay. Nothing here is ever fed to the emulator, so
//! the authoritative screen cannot be corrupted by a bad prediction.
//!
//! Deliberately narrow: it predicts a plain character landing at the cursor and
//! a backspace taking one back, and gives up on anything else — a newline, an
//! escape sequence, a line that would wrap. It does keep predicting on the
//! alternate screen, because a shell inside tmux lives there and that is exactly
//! where the guess pays off; a wrong one is caught by [`Predictor::reconcile`],
//! which drops it and stops guessing for a moment.

use std::collections::VecDeque;

/// A cell we are drawing before the server has confirmed it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Prediction {
    pub row: u16,
    pub col: u16,
    pub ch: char,
}

/// When to predict.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum PredictMode {
    /// Only once the link is slow enough for the delay to be visible.
    #[default]
    Adaptive,
    Always,
    Never,
}

/// Guesses time out if the server never confirms them, so a stuck prediction
/// cannot linger on screen.
const PREDICTION_TIMEOUT_MS: u64 = 1500;
/// Below this round-trip time the delay is not worth guessing about.
const ADAPTIVE_SRTT_TRIGGER_MS: f64 = 35.0;
/// After a wrong guess, stop predicting briefly rather than fight the server.
const BACKOFF_MS: u64 = 800;
/// A runaway prediction list means we are guessing far more than we confirm.
const MAX_PREDICTIONS: usize = 256;

#[derive(Debug, Clone, Copy)]
struct Pending {
    cell: Prediction,
    made_at: u64,
}

pub struct Predictor {
    mode: PredictMode,
    pending: VecDeque<Pending>,
    /// Where the next predicted character would land.
    cursor: Option<(u16, u16)>,
    /// Smoothed round-trip time, fed from the transport.
    srtt_ms: f64,
    /// Predicting is suspended until this time after a wrong guess.
    paused_until: u64,
    /// The terminal is doing something we cannot reason about.
    suspended: bool,
    cols: u16,
}

impl Predictor {
    pub fn new(cols: u16) -> Self {
        Self {
            mode: PredictMode::default(),
            pending: VecDeque::new(),
            cursor: None,
            srtt_ms: 0.0,
            paused_until: 0,
            suspended: false,
            cols: cols.max(1),
        }
    }

    pub fn set_mode(&mut self, mode: PredictMode) {
        self.mode = mode;
        if mode == PredictMode::Never {
            self.pending.clear();
        }
    }

    pub fn set_cols(&mut self, cols: u16) {
        self.cols = cols.max(1);
        // A reflow moves everything; nothing we guessed is trustworthy.
        self.pending.clear();
        self.cursor = None;
    }

    /// The transport's latency estimate decides whether guessing is worth it.
    pub fn set_srtt(&mut self, srtt_ms: f64) {
        self.srtt_ms = srtt_ms;
    }

    /// Stop guessing entirely — for a screen where keystrokes are not text at
    /// all, such as one with mouse reporting on. The caller decides; a wrong
    /// guess anywhere else is caught by [`Self::reconcile`].
    pub fn set_suspended(&mut self, suspended: bool) {
        if suspended {
            self.pending.clear();
        }
        self.suspended = suspended;
    }

    /// Whether a keystroke would be predicted right now.
    pub fn enabled(&self, now: u64) -> bool {
        if self.suspended || now < self.paused_until {
            return false;
        }
        match self.mode {
            PredictMode::Never => false,
            PredictMode::Always => true,
            PredictMode::Adaptive => self.srtt_ms >= ADAPTIVE_SRTT_TRIGGER_MS,
        }
    }

    /// The cells to draw over the authoritative screen.
    pub fn overlay(&self) -> impl Iterator<Item = Prediction> + '_ {
        self.pending.iter().map(|p| p.cell)
    }

    pub fn is_empty(&self) -> bool {
        self.pending.is_empty()
    }

    /// Where the cursor should appear, if we have moved it ahead of the server.
    pub fn cursor(&self) -> Option<(u16, u16)> {
        self.pending.is_empty().then_some(None).unwrap_or(self.cursor)
    }

    /// Drop every guess — after a resize, a reconnect, or anything unexpected.
    pub fn reset(&mut self) {
        self.pending.clear();
        self.cursor = None;
    }

    /// Tell the engine where the server's cursor is. Called before predicting,
    /// but ignored while guesses are outstanding: our own cursor is ahead of the
    /// server's, and taking the server's would make the next character land on
    /// top of the previous one.
    pub fn observe_cursor(&mut self, row: u16, col: u16) {
        if self.pending.is_empty() {
            self.cursor = Some((row, col));
        }
    }

    /// Feed keystrokes on their way to the server. Returns true if the overlay
    /// changed and the screen needs repainting.
    pub fn on_input(&mut self, bytes: &[u8], now: u64) -> bool {
        if !self.enabled(now) {
            return false;
        }
        let Some((mut row, mut col)) = self.cursor else { return false };
        let before = self.pending.len();
        let mut changed = false;

        // Only plain text is predictable; anything else means stop.
        let Ok(text) = std::str::from_utf8(bytes) else {
            self.reset();
            return before != 0;
        };
        for ch in text.chars() {
            match ch {
                // Enter: the shell's reply is anyone's guess.
                '\r' | '\n' => {
                    self.pending.clear();
                    self.cursor = None;
                    return true;
                }
                // Backspace: take back the last guess if it is ours to take.
                '\u{8}' | '\u{7f}' => {
                    if let Some(last) = self.pending.pop_back() {
                        row = last.cell.row;
                        col = last.cell.col;
                        changed = true;
                    } else {
                        // Deleting something the server drew: let the server do it.
                        self.cursor = None;
                        return before != 0;
                    }
                }
                // Tab, escape, other control characters: unpredictable.
                c if (c as u32) < 0x20 => {
                    self.pending.clear();
                    self.cursor = None;
                    return true;
                }
                c => {
                    // Wrapping to the next line is a guess too far.
                    if col >= self.cols {
                        self.pending.clear();
                        self.cursor = None;
                        return true;
                    }
                    self.pending.push_back(Pending { cell: Prediction { row, col, ch: c }, made_at: now });
                    col += 1;
                    changed = true;
                }
            }
        }
        self.cursor = Some((row, col));
        if self.pending.len() > MAX_PREDICTIONS {
            self.reset();
        }
        changed
    }

    /// Check outstanding guesses against what the server actually put on a row.
    ///
    /// `row_text` is the authoritative content of `row`. A guess whose character
    /// is already there has come true and is dropped; one contradicted by a
    /// different character was wrong, so everything is dropped and predicting
    /// pauses. Cells the server has not reached yet are left alone.
    pub fn reconcile(&mut self, row: u16, row_text: &str, now: u64) -> bool {
        if self.pending.is_empty() {
            return false;
        }
        let cells: Vec<char> = row_text.chars().collect();
        let mut wrong = false;
        let before = self.pending.len();

        self.pending.retain(|p| {
            if p.cell.row != row {
                return true;
            }
            match cells.get(p.cell.col as usize) {
                // The server agrees: the guess is now real.
                Some(&actual) if actual == p.cell.ch => false,
                // The server put something else there.
                Some(&actual) if actual != ' ' && actual != '\0' => {
                    wrong = true;
                    false
                }
                // Still blank: the server has not caught up.
                _ => true,
            }
        });

        if wrong {
            self.pending.clear();
            self.cursor = None;
            self.paused_until = now + BACKOFF_MS;
        }
        self.expire(now) || self.pending.len() != before
    }

    /// Drop guesses the server never confirmed, so nothing lingers.
    pub fn expire(&mut self, now: u64) -> bool {
        let before = self.pending.len();
        self.pending.retain(|p| now.saturating_sub(p.made_at) < PREDICTION_TIMEOUT_MS);
        if self.pending.len() != before {
            self.cursor = None;
            return true;
        }
        false
    }

    /// Rows that currently carry guesses, so the caller knows what to reconcile.
    pub fn rows(&self) -> Vec<u16> {
        let mut rows: Vec<u16> = self.pending.iter().map(|p| p.cell.row).collect();
        rows.sort_unstable();
        rows.dedup();
        rows
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn predictor() -> Predictor {
        let mut p = Predictor::new(80);
        p.set_mode(PredictMode::Always);
        p.observe_cursor(3, 10);
        p
    }

    fn cells(p: &Predictor) -> Vec<Prediction> {
        p.overlay().collect()
    }

    #[test]
    fn a_typed_character_is_predicted_at_the_cursor() {
        let mut p = predictor();
        assert!(p.on_input(b"a", 0));
        assert_eq!(cells(&p), vec![Prediction { row: 3, col: 10, ch: 'a' }]);
        assert_eq!(p.cursor(), Some((3, 11)));
    }

    #[test]
    fn a_run_of_characters_advances_across_the_row() {
        let mut p = predictor();
        p.on_input(b"abc", 0);
        assert_eq!(
            cells(&p),
            vec![
                Prediction { row: 3, col: 10, ch: 'a' },
                Prediction { row: 3, col: 11, ch: 'b' },
                Prediction { row: 3, col: 12, ch: 'c' },
            ],
        );
    }

    #[test]
    fn backspace_takes_back_our_own_guess() {
        let mut p = predictor();
        p.on_input(b"ab", 0);
        assert!(p.on_input(b"\x7f", 1));
        assert_eq!(cells(&p), vec![Prediction { row: 3, col: 10, ch: 'a' }]);
        assert_eq!(p.cursor(), Some((3, 11)));
    }

    #[test]
    fn backspace_with_nothing_of_ours_to_delete_stops_guessing() {
        let mut p = predictor();
        assert!(!p.on_input(b"\x7f", 0), "nothing was on screen from us");
        assert!(p.is_empty());
        // And we do not resume until the server tells us where the cursor is.
        assert!(!p.on_input(b"a", 1));
        assert!(p.is_empty());
    }

    #[test]
    fn enter_abandons_every_guess() {
        let mut p = predictor();
        p.on_input(b"ls", 0);
        assert!(p.on_input(b"\r", 1));
        assert!(p.is_empty(), "the shell's reply is not predictable");
    }

    #[test]
    fn control_characters_and_escapes_abandon_guessing() {
        for seq in [&b"\x1b"[..], &b"\t"[..], &b"\x03"[..]] {
            let mut p = predictor();
            p.on_input(b"ab", 0);
            p.on_input(seq, 1);
            assert!(p.is_empty(), "{seq:?} should have stopped prediction");
        }
    }

    #[test]
    fn the_server_confirming_a_guess_retires_it() {
        let mut p = predictor();
        p.on_input(b"hi", 0);
        // The row now genuinely reads "…hi".
        let row = format!("{}hi", " ".repeat(10));
        assert!(p.reconcile(3, &row, 10));
        assert!(p.is_empty(), "both guesses came true");
    }

    #[test]
    fn a_partly_arrived_row_keeps_the_rest_of_the_guesses() {
        let mut p = predictor();
        p.on_input(b"hi", 0);
        let row = format!("{}h", " ".repeat(10));
        p.reconcile(3, &row, 10);
        assert_eq!(cells(&p), vec![Prediction { row: 3, col: 11, ch: 'i' }], "'i' has not arrived yet");
    }

    #[test]
    fn a_wrong_guess_is_dropped_and_prediction_backs_off() {
        let mut p = predictor();
        p.on_input(b"a", 0);
        // The server put something else at that cell.
        let row = format!("{}X", " ".repeat(10));
        assert!(p.reconcile(3, &row, 100));
        assert!(p.is_empty());
        assert!(!p.enabled(100), "should pause after being wrong");
        assert!(p.enabled(100 + BACKOFF_MS), "and resume later");
    }

    #[test]
    fn guesses_the_server_never_confirms_expire() {
        let mut p = predictor();
        p.on_input(b"a", 0);
        assert!(!p.expire(PREDICTION_TIMEOUT_MS - 1));
        assert!(!p.is_empty());
        assert!(p.expire(PREDICTION_TIMEOUT_MS + 1));
        assert!(p.is_empty(), "a guess should not linger for ever");
    }

    #[test]
    fn adaptive_mode_only_predicts_on_a_slow_link() {
        let mut p = Predictor::new(80);
        p.set_mode(PredictMode::Adaptive);
        p.observe_cursor(0, 0);
        p.set_srtt(5.0);
        assert!(!p.enabled(0), "no point guessing on a fast link");
        assert!(!p.on_input(b"a", 0));
        p.set_srtt(120.0);
        assert!(p.enabled(0));
        assert!(p.on_input(b"a", 0));
    }

    #[test]
    fn never_mode_predicts_nothing() {
        let mut p = predictor();
        p.on_input(b"a", 0);
        p.set_mode(PredictMode::Never);
        assert!(p.is_empty());
        assert!(!p.on_input(b"b", 1));
    }

    #[test]
    fn suspending_clears_and_blocks_prediction() {
        let mut p = predictor();
        p.on_input(b"a", 0);
        p.set_suspended(true);
        assert!(p.is_empty(), "suspending should drop what we had");
        assert!(!p.on_input(b"b", 1));
        p.set_suspended(false);
        p.observe_cursor(3, 10);
        assert!(p.on_input(b"c", 2));
    }

    #[test]
    fn the_servers_cursor_is_ignored_while_guesses_are_outstanding() {
        let mut p = predictor();
        p.on_input(b"ab", 0);
        // A stale cursor report from before our keystrokes arrived.
        p.observe_cursor(3, 10);
        p.on_input(b"c", 1);
        assert_eq!(
            cells(&p).last().copied(),
            Some(Prediction { row: 3, col: 12, ch: 'c' }),
            "must not stack characters on the same cell",
        );
    }

    #[test]
    fn reaching_the_end_of_the_row_stops_guessing() {
        let mut p = Predictor::new(5);
        p.set_mode(PredictMode::Always);
        p.observe_cursor(0, 3);
        p.on_input(b"ab", 0);
        assert_eq!(cells(&p).len(), 2);
        p.on_input(b"c", 1);
        assert!(p.is_empty(), "wrapping is not predictable");
    }

    #[test]
    fn a_resize_abandons_everything() {
        let mut p = predictor();
        p.on_input(b"abc", 0);
        p.set_cols(100);
        assert!(p.is_empty());
        assert_eq!(p.cursor(), None);
    }

    #[test]
    fn rows_reports_where_guesses_live() {
        let mut p = predictor();
        p.on_input(b"ab", 0);
        assert_eq!(p.rows(), vec![3]);
        p.reset();
        assert!(p.rows().is_empty());
    }

    #[test]
    fn a_flood_of_unconfirmed_guesses_resets_rather_than_growing() {
        let mut p = Predictor::new(u16::MAX);
        p.set_mode(PredictMode::Always);
        p.observe_cursor(0, 0);
        let many = vec![b'x'; MAX_PREDICTIONS + 10];
        p.on_input(&many, 0);
        assert!(p.is_empty(), "should give up rather than accumulate for ever");
    }
}
