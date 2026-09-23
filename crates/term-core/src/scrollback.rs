//! Searching the whole buffer, and turning it into text to save or share.
//!
//! Both used to happen in the app, over a copy of every line that crossed the
//! bridge as a list of strings: fifty thousand of them for each search, taken
//! again after every pause in the typing. Here they read one line at a time
//! through [`Emulator::line_text_into`] into a buffer that is reused.

use crate::Emulator;

/// A search query, case-folded once rather than once per line.
pub struct Needle {
    chars: Vec<char>,
}

impl Needle {
    /// `None` for a query with nothing to look for.
    pub fn new(query: &str) -> Option<Self> {
        if query.trim().is_empty() {
            return None;
        }
        Some(Self { chars: query.chars().map(fold).collect() })
    }

    /// Every occurrence of the query in `line`, ignoring case, passed to `hit`
    /// by the column it starts at. `hay` is scratch space the caller keeps.
    ///
    /// Hits do not overlap: the next one is looked for after the end of the
    /// last. Columns count UTF-16 units, which is what the search counted when
    /// it ran in Kotlin and what the highlight is drawn from.
    pub fn find(&self, line: &str, hay: &mut Vec<char>, mut hit: impl FnMut(u32)) {
        let n = self.chars.len();
        if line.len() < n {
            return;
        }
        hay.clear();
        hay.extend(line.chars().map(fold));
        let (mut i, mut col) = (0, 0u32);
        while i + n <= hay.len() {
            if hay[i..i + n] == self.chars[..] {
                hit(col);
                col += hay[i..i + n].iter().map(|c| c.len_utf16() as u32).sum::<u32>();
                i += n;
            } else {
                col += hay[i].len_utf16() as u32;
                i += 1;
            }
        }
    }
}

/// The form two characters share when they differ only in case.
///
/// The same comparison Kotlin's `ignoreCase` makes: upper case, then lower
/// case, each by the one-character mapping. A letter whose upper case is two
/// letters (`ß`) is left as it is, as it was there.
pub fn fold(c: char) -> char {
    if c.is_ascii() {
        return c.to_ascii_lowercase();
    }
    let upper = single(c.to_uppercase(), c);
    single(upper.to_lowercase(), upper)
}

fn single(mut mapped: impl Iterator<Item = char>, c: char) -> char {
    match (mapped.next(), mapped.next()) {
        (Some(one), None) => one,
        _ => c,
    }
}

/// The whole buffer as plain text, appended to `out`: every line oldest first,
/// trailing blanks trimmed, joined with `\n`.
///
/// Blank lines between output are the shape of what ran and are kept; the
/// ones after the last of it are only the rest of the screen the cursor never
/// reached, and are dropped.
pub fn text(emu: &dyn Emulator, out: &mut String) {
    text_from(emu.line_count(), |i, s| emu.line_text_into(i, s), out);
}

/// [`text`] over any source of lines.
pub fn text_from(count: usize, mut line: impl FnMut(usize, &mut String), out: &mut String) {
    let mut probe = String::new();
    let last = (0..count).rev().find(|&i| {
        probe.clear();
        line(i, &mut probe);
        !probe.trim_end().is_empty()
    });
    let Some(last) = last else { return };
    for i in 0..=last {
        if i > 0 {
            out.push('\n');
        }
        let start = out.len();
        line(i, out);
        let kept = start + out[start..].trim_end().len();
        out.truncate(kept);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn text_of(lines: &[&str]) -> String {
        let mut out = String::new();
        text_from(lines.len(), |i, s| s.push_str(lines[i]), &mut out);
        out
    }

    fn hits(query: &str, line: &str) -> Vec<u32> {
        let mut found = Vec::new();
        Needle::new(query).unwrap().find(line, &mut Vec::new(), |c| found.push(c));
        found
    }

    #[test]
    fn the_lines_come_out_in_the_order_they_were_printed() {
        assert_eq!(text_of(&["$ ls", "Cargo.toml", "src", "$ "]), "$ ls\nCargo.toml\nsrc\n$");
    }

    #[test]
    fn the_empty_screen_under_the_last_output_is_dropped() {
        let lines = ["$ uptime", " 11:04:22 up 9 days", "$ ", "", "", "", ""];
        assert_eq!(text_of(&lines), "$ uptime\n 11:04:22 up 9 days\n$");
    }

    #[test]
    fn a_blank_line_inside_the_output_is_part_of_it() {
        assert_eq!(text_of(&["first", "", "second", ""]), "first\n\nsecond");
    }

    #[test]
    fn the_padding_a_terminal_writes_to_the_end_of_a_row_is_not_text() {
        let padded = format!("web01{}", " ".repeat(75));
        assert_eq!(text_of(&[&padded]), "web01");
    }

    #[test]
    fn an_empty_buffer_is_an_empty_string_not_a_line() {
        assert_eq!(text_of(&[]), "");
        assert_eq!(text_of(&["", "   ", ""]), "");
    }

    #[test]
    fn search_ignores_case_both_ways() {
        assert_eq!(hits("error", "ERROR: disk Error"), [0, 12]);
        assert_eq!(hits("ErRoR", "an error"), [3]);
        assert_eq!(hits("straße", "STRAßE"), [0]);
        assert_eq!(hits("é", "CAFÉ"), [3]);
    }

    #[test]
    fn every_hit_on_a_line_is_found_and_none_overlap() {
        assert_eq!(hits("ab", "ab ab xab"), [0, 3, 7]);
        assert_eq!(hits("aa", "aaaa"), [0, 2]);
        assert_eq!(hits("aa", "aaa"), [0]);
    }

    #[test]
    fn columns_count_utf16_units_as_the_app_does() {
        // The emoji is two UTF-16 units, so what follows it starts at 3.
        assert_eq!(hits("x", "🙂 x"), [3]);
    }

    #[test]
    fn a_blank_query_finds_nothing() {
        assert!(Needle::new("").is_none());
        assert!(Needle::new("   ").is_none());
        assert!(hits("longer than the line", "short").is_empty());
    }
}
