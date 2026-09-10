//! Colouring rows by the user's own patterns.
//!
//! This lives beside the grid rather than in the app for the same reason the
//! link scanning does: the rows are already here, and a frontend that is not
//! the Android one gets the feature rather than reimplementing it.
//!
//! There is a second reason here though. These are the *user's* patterns, run
//! against every visible row of a terminal that may be printing megabytes. A
//! backtracking engine — which is what the platform offers — can be made to
//! take exponential time by a pattern as ordinary-looking as `(a+)+b`, and the
//! row it is chewing on is on the drawing thread. This engine matches in time
//! linear in the length of the row, always, so a bad pattern can be a wrong
//! colour but never a frozen terminal.
//!
//! The price is that it has no backreferences and no lookaround: both need the
//! backtracking this deliberately does not do. A pattern using them is reported
//! as bad rather than quietly ignored.

use std::sync::LazyLock;

use regex::{Regex, RegexBuilder};

/// One rule as the app stores it.
#[derive(Debug, Clone, uniffi::Record)]
pub struct HighlightRule {
    pub id: String,
    pub pattern: String,
    /// ARGB; 0 means "leave the colour alone" and only `whole_line` applies.
    pub color: i32,
    pub whole_line: bool,
    pub enabled: bool,
}

/// A stretch of a row one rule claimed, in character columns.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HighlightSpan {
    pub row: u16,
    pub start: u16,
    pub end: u16,
    pub color: i32,
}

/// Why a rule's pattern was refused, for the editor to show.
#[derive(Debug, Clone, uniffi::Record)]
pub struct HighlightError {
    pub id: String,
    pub reason: String,
}

/// A row is one line of a terminal; past this many hits a rule is repeating
/// itself, and the budget is what keeps a pattern like `.?` from costing more
/// than the frame it is drawn in.
const MAX_MATCHES: usize = 128;

/// Patterns are the user's, so hold them to a size that cannot eat the heap.
const PATTERN_SIZE_LIMIT: usize = 1 << 20;

struct Compiled {
    re: Regex,
    color: i32,
    whole_line: bool,
}

#[derive(Default)]
pub struct Highlighter {
    rules: Vec<Compiled>,
    /// The text each row was last matched against, and what was found in it.
    rows: Vec<(String, Vec<HighlightSpan>)>,
}

/// Compile one pattern, or say why it will not.
pub fn compile(pattern: &str) -> Result<Regex, String> {
    RegexBuilder::new(pattern)
        .size_limit(PATTERN_SIZE_LIMIT)
        .build()
        .map_err(|e| first_line(&e.to_string()))
}

/// Regex errors are several lines with the pattern drawn out underneath; the
/// first line is the part a person needs.
fn first_line(s: &str) -> String {
    s.lines()
        .map(str::trim)
        .find(|l| !l.is_empty())
        .unwrap_or("Not a valid pattern")
        .to_string()
}

/// Byte offset to character column, which is what the grid is indexed by.
fn col_of(text: &str, byte: usize) -> u16 {
    text[..byte].chars().count() as u16
}

impl Highlighter {
    /// Replace the rules, returning the ones that would not compile.
    ///
    /// A rule that is switched off is still checked: a broken pattern has to be
    /// visible in the editor whether or not it is in use.
    pub fn set_rules(&mut self, rules: &[HighlightRule]) -> Vec<HighlightError> {
        let mut errors = Vec::new();
        self.rules.clear();
        self.rows.clear();
        for rule in rules {
            if rule.pattern.is_empty() {
                continue;
            }
            match compile(&rule.pattern) {
                Err(reason) => errors.push(HighlightError { id: rule.id.clone(), reason }),
                Ok(re) => {
                    // Alpha is forced on: a rule is stored as ARGB, and a
                    // colour with no alpha would be drawn as invisible black.
                    if rule.enabled && rule.color != 0 {
                        self.rules.push(Compiled {
                            re,
                            color: rule.color | 0xff00_0000u32 as i32,
                            whole_line: rule.whole_line,
                        });
                    }
                }
            }
        }
        errors
    }

    pub fn is_empty(&self) -> bool {
        self.rules.is_empty()
    }

    /// The spans on one row, in the order the rules are in.
    fn scan_row(&self, row: u16, text: &str) -> Vec<HighlightSpan> {
        let mut out = Vec::new();
        if text.is_empty() {
            return out;
        }
        let cols = text.chars().count() as u16;
        for rule in &self.rules {
            let mut found = 0;
            let mut at = 0;
            while at <= text.len() && found < MAX_MATCHES {
                let Some(m) = rule.re.find_at(text, at) else { break };
                let (s, e) = (m.start(), m.end());
                if e > s {
                    out.push(if rule.whole_line {
                        HighlightSpan { row, start: 0, end: cols, color: rule.color }
                    } else {
                        HighlightSpan { row, start: col_of(text, s), end: col_of(text, e), color: rule.color }
                    });
                    found += 1;
                }
                // A whole-line rule has said everything it has to say the
                // moment it matches once.
                if rule.whole_line {
                    break;
                }
                // An empty match would otherwise sit on the same spot forever.
                at = if e > s { e } else { next_char(text, s) };
                if at > text.len() {
                    break;
                }
            }
        }
        out
    }

    /// Spans for the visible grid, rescanning only the rows that changed.
    pub fn scan(&mut self, rows: &[String]) -> Vec<HighlightSpan> {
        if self.rules.is_empty() {
            return Vec::new();
        }
        if self.rows.len() != rows.len() {
            // A row that has never been looked at must not match an empty one.
            self.rows = vec![("\u{0}".to_string(), Vec::new()); rows.len()];
        }
        let mut all = Vec::new();
        for (i, text) in rows.iter().enumerate() {
            if &self.rows[i].0 != text {
                let spans = self.scan_row(i as u16, text);
                self.rows[i] = (text.clone(), spans);
            }
            all.extend(self.rows[i].1.iter().cloned());
        }
        all
    }
}

/// The next character boundary after `at`, so an empty match still advances.
fn next_char(text: &str, at: usize) -> usize {
    text[at..].chars().next().map_or(at + 1, |c| at + c.len_utf8())
}

/// One of the rules offered ready-made, without the id the app will give it.
#[derive(Debug, Clone, uniffi::Record)]
pub struct HighlightPreset {
    pub pattern: String,
    pub color: i32,
}

/// The three rules every log already asks for: errors red, warnings yellow,
/// ok green. Kept here so there is one copy of them, in the dialect that has
/// to accept them.
static PRESETS: LazyLock<Vec<HighlightPreset>> = LazyLock::new(|| {
    vec![
        HighlightPreset {
            pattern: r"(?i)\b(error|errors|failed|failure|fatal|panic|denied)\b".into(),
            color: 0xFFF7768Eu32 as i32,
        },
        HighlightPreset {
            pattern: r"(?i)\b(warn|warning|warnings|deprecated|timeout)\b".into(),
            color: 0xFFE0AF68u32 as i32,
        },
        HighlightPreset {
            pattern: r"(?i)\b(ok|success|succeeded|done|passed|active|running)\b".into(),
            color: 0xFF9ECE6Au32 as i32,
        },
    ]
});

#[uniffi::export]
pub fn highlight_presets() -> Vec<HighlightPreset> {
    PRESETS.clone()
}

/// Why [`pattern`] would be refused, or None when it is fine. For the editor,
/// which has to say so as the pattern is typed.
#[uniffi::export]
pub fn highlight_error(pattern: String) -> Option<String> {
    if pattern.is_empty() {
        return None;
    }
    compile(&pattern).err()
}

/// The same for a whole set of rules, keyed by rule id.
#[uniffi::export]
pub fn highlight_errors(rules: Vec<HighlightRule>) -> Vec<HighlightError> {
    Highlighter::default().set_rules(&rules)
}

/// What these rules would claim on one line, for the editor's preview.
#[uniffi::export]
pub fn highlight_preview(rules: Vec<HighlightRule>, text: String) -> Vec<HighlightSpan> {
    let mut h = Highlighter::default();
    h.set_rules(&rules);
    h.scan_row(0, &text)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rule(pattern: &str, color: i32) -> HighlightRule {
        HighlightRule { id: pattern.into(), pattern: pattern.into(), color, whole_line: false, enabled: true }
    }

    fn with(rules: Vec<HighlightRule>) -> Highlighter {
        let mut h = Highlighter::default();
        let errs = h.set_rules(&rules);
        assert!(errs.is_empty(), "unexpected errors: {errs:?}");
        h
    }

    #[test]
    fn a_rule_colours_what_it_matches() {
        let h = with(vec![rule("error", 0x00ff0000)]);
        let s = h.scan_row(0, "an error happened");
        assert_eq!(s.len(), 1);
        assert_eq!((s[0].start, s[0].end), (3, 8));
        assert_eq!(s[0].color, 0xffff0000u32 as i32, "alpha is forced on");
    }

    #[test]
    fn a_whole_line_rule_claims_the_row() {
        let mut r = rule("fatal", 0x0000ff00);
        r.whole_line = true;
        let h = with(vec![r]);
        let s = h.scan_row(0, "a fatal thing");
        assert_eq!(s.len(), 1);
        assert_eq!((s[0].start, s[0].end), (0, 13));
    }

    #[test]
    fn every_occurrence_is_claimed() {
        let h = with(vec![rule("ab", 0x00ff0000)]);
        assert_eq!(h.scan_row(0, "ab-ab-ab").len(), 3);
    }

    #[test]
    fn a_rule_that_is_off_or_colourless_claims_nothing() {
        let mut off = rule("error", 0x00ff0000);
        off.enabled = false;
        let mut colourless = rule("warn", 0);
        colourless.color = 0;
        let h = with(vec![off, colourless]);
        assert!(h.is_empty());
        assert!(h.scan_row(0, "error and warn").is_empty());
    }

    #[test]
    fn a_pattern_that_will_not_compile_is_reported_against_its_rule() {
        let mut h = Highlighter::default();
        let errs = h.set_rules(&[rule("(unclosed", 0x00ff0000)]);
        assert_eq!(errs.len(), 1);
        assert_eq!(errs[0].id, "(unclosed");
        assert!(!errs[0].reason.is_empty());
    }

    #[test]
    fn a_pattern_needing_backtracking_is_refused_rather_than_silently_dropped() {
        // Lookahead and backreferences cannot be matched in linear time, so
        // this engine has neither; the user is told rather than left guessing.
        let mut h = Highlighter::default();
        let errs = h.set_rules(&[rule(r"foo(?=bar)", 0x00ff0000), rule(r"(a+)\1", 0x00ff0000)]);
        assert_eq!(errs.len(), 2, "got {errs:?}");
    }

    #[test]
    fn an_empty_match_does_not_spin() {
        // `x?` matches nothing at every position; it must still terminate.
        let h = with(vec![rule("x?", 0x00ff0000)]);
        let s = h.scan_row(0, "abcxdef");
        assert!(s.len() <= MAX_MATCHES);
    }

    #[test]
    fn a_runaway_rule_is_held_to_a_budget() {
        let h = with(vec![rule("a", 0x00ff0000)]);
        let text = "a".repeat(500);
        assert_eq!(h.scan_row(0, &text).len(), MAX_MATCHES);
    }

    #[test]
    fn columns_are_characters_not_bytes() {
        let h = with(vec![rule("error", 0x00ff0000)]);
        // The arrows ahead of it are three bytes each.
        let s = h.scan_row(0, "→→→ error");
        assert_eq!(s[0].start, 4);
    }

    #[test]
    fn an_unchanged_row_keeps_the_spans_it_had() {
        let mut h = with(vec![rule("error", 0x00ff0000)]);
        let rows = vec!["an error".to_string(), "fine".to_string()];
        let first = h.scan(&rows);
        assert_eq!(first.len(), 1);
        assert_eq!(h.scan(&rows), first);
    }

    #[test]
    fn a_changed_row_is_rescanned() {
        let mut h = with(vec![rule("error", 0x00ff0000)]);
        let mut rows = vec!["fine".to_string()];
        assert!(h.scan(&rows).is_empty());
        rows[0] = "an error".to_string();
        assert_eq!(h.scan(&rows).len(), 1);
    }

    #[test]
    fn an_empty_row_reads_as_empty_on_a_fresh_matcher() {
        let mut h = with(vec![rule("error", 0x00ff0000)]);
        assert!(h.scan(&[String::new()]).is_empty());
    }

    #[test]
    fn the_presets_all_compile() {
        for p in highlight_presets() {
            assert!(compile(&p.pattern).is_ok(), "preset {:?} did not compile", p.pattern);
        }
    }
}
