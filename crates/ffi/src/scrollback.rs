//! Scrollback search and export, run against the session's emulator.
//!
//! The matching and the text itself are [`term_core::scrollback`]; what lives
//! here is how long the emulator is held while they run.

use std::sync::atomic::{AtomicU64, Ordering};

use parking_lot::Mutex;
use term_core::scrollback::Needle;
use term_core::Emulator;

/// How many lines a search reads before letting go of the emulator.
///
/// Output keeps arriving while a search runs, and every chunk of it waits for
/// the lock the search is holding. A slice this size is well under a
/// millisecond of work, so a search through the whole scrollback is a string
/// of short waits rather than one long one.
const SLICE: usize = 500;

/// Hands out search tickets; only the newest one is still wanted.
///
/// The app starts a search after each pause in the typing, and a slow one can
/// still be running when the next starts. The older result would be thrown
/// away on arrival, so it stops at its next slice instead of finishing.
#[derive(Default)]
pub(crate) struct Searches(AtomicU64);

impl Searches {
    /// A ticket for a new search, which retires every earlier one.
    pub(crate) fn begin(&self) -> u64 {
        self.0.fetch_add(1, Ordering::AcqRel) + 1
    }

    fn wanted(&self, ticket: u64) -> bool {
        self.0.load(Ordering::Acquire) == ticket
    }
}

/// Every case-insensitive hit of `query`, as `line, column` pairs one after
/// the other: the line as [`Emulator::line_text_into`] numbers it, the column
/// in UTF-16 units. Empty when there is nothing, and when a newer search took
/// over before this one finished.
///
/// The emulator is let go between slices, so the lines can move under a
/// search while output streams in. Nothing is lost that was not already
/// lost before: the hits were always a picture of the buffer at one moment,
/// and the view scrolls to them after that moment has passed.
pub(crate) fn search(emu: &Mutex<Box<dyn Emulator>>, searches: &Searches, ticket: u64, query: &str) -> Vec<u32> {
    let Some(needle) = Needle::new(query) else { return Vec::new() };
    let mut found = Vec::new();
    let mut line = String::new();
    let mut hay = Vec::new();
    let mut start = 0;
    loop {
        if !searches.wanted(ticket) {
            return Vec::new();
        }
        let emu = emu.lock();
        let end = emu.line_count().min(start + SLICE);
        if start >= end {
            return found;
        }
        for i in start..end {
            line.clear();
            emu.line_text_into(i, &mut line);
            needle.find(&line, &mut hay, |col| found.extend([i as u32, col]));
        }
        drop(emu);
        start = end;
    }
}

/// The buffer as the text a person would paste into an issue.
///
/// Read in one go, unlike a search: a saved file with a line in it twice, or
/// one missing, because output scrolled the buffer halfway through would be
/// wrong in a way nobody would notice. Building one string is still much
/// less time under the lock than the list of lines it replaced.
pub(crate) fn text(emu: &Mutex<Box<dyn Emulator>>) -> String {
    let mut out = String::new();
    term_core::scrollback::text(&**emu.lock(), &mut out);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn emulator(rows: u16, feed: &str) -> Mutex<Box<dyn Emulator>> {
        let mut emu = crate::emulator::new(20, rows, 10_000);
        emu.feed(feed.replace('\n', "\r\n").as_bytes());
        Mutex::new(emu)
    }

    fn pairs(flat: &[u32]) -> Vec<(u32, u32)> {
        flat.chunks(2).map(|p| (p[0], p[1])).collect()
    }

    fn run(emu: &Mutex<Box<dyn Emulator>>, query: &str) -> Vec<(u32, u32)> {
        let searches = Searches::default();
        let ticket = searches.begin();
        pairs(&search(emu, &searches, ticket, query))
    }

    #[test]
    fn hits_are_numbered_as_all_lines_numbers_them() {
        let emu = emulator(4, "one\nTwo two\nthree\nfour TWO\nfive\nsix");
        let lines = emu.lock().all_lines();
        assert!(emu.lock().history_size() > 0, "the test wants history: {lines:?}");
        let hits = run(&emu, "two");
        assert_eq!(hits.len(), 3, "{lines:?}");
        for (line, col) in hits {
            let text = &lines[line as usize];
            assert!(text[col as usize..].to_lowercase().starts_with("two"), "{text:?} at {col}");
        }
        assert_eq!(run(&emu, "two")[..2], [(1, 0), (1, 4)]);
    }

    #[test]
    fn a_search_reaches_every_slice_of_a_long_history() {
        let feed: String = (0..SLICE * 3).map(|i| format!("line-{i}\n")).collect();
        let emu = emulator(10, &feed);
        let hits = run(&emu, "LINE-");
        assert_eq!(hits.len(), SLICE * 3);
        let last = format!("line-{}", SLICE * 3 - 1);
        let (line, _) = run(&emu, &last)[0];
        assert_eq!(emu.lock().all_lines()[line as usize], last);
    }

    #[test]
    fn a_newer_search_stops_an_older_one() {
        let emu = emulator(4, "needle\nhay\nneedle");
        let searches = Searches::default();
        let older = searches.begin();
        let newer = searches.begin();
        assert!(search(&emu, &searches, older, "needle").is_empty());
        assert_eq!(pairs(&search(&emu, &searches, newer, "needle")).len(), 2);
    }

    #[test]
    fn a_blank_query_finds_nothing() {
        let emu = emulator(4, "   \n");
        assert!(run(&emu, " ").is_empty());
    }

    #[test]
    fn the_text_is_the_lines_without_the_empty_screen_under_them() {
        let emu = emulator(6, "$ ls   \n\nsrc\n$ ");
        assert_eq!(text(&emu), "$ ls\n\nsrc\n$");
        assert_eq!(text(&emulator(6, "")), "");
    }
}
