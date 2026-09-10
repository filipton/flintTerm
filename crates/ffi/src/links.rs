//! Tappable things on the visible grid: URLs and paths.
//!
//! This used to run in the app, which meant pulling every visible row back
//! across the bridge as a string and matching it there, once per frame, for a
//! screen that mostly had not changed. The rows are already here, so the
//! scanning is too — the app asks once a frame and gets back only what it
//! draws, and a frontend that is not the Android one gets links for nothing.
//!
//! Results are cached against the text of the row they came from, so a still
//! screen — or a busy one where a line or two moves — runs no patterns at all.

use std::sync::LazyLock;

use regex::Regex;

/// A link found on one row, in **character** columns rather than bytes: that is
/// what the grid is indexed by, and a row can hold anything.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct LinkSpan {
    pub row: u16,
    pub start: u16,
    pub end: u16,
    /// What tapping it should open. Rows that wrap share one text.
    pub text: String,
    pub is_url: bool,
}

static URL_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r#"(?:https?|ftp|ssh|sftp|file)://[^\s'"<>()\[\]{}`]+"#).unwrap());

/// The same shape the app used, minus its leading `(?<![\w/~.\-:])`: this crate's
/// regex engine has no lookbehind, so [`starts_a_word`] does that job after the
/// match instead.
static PATH_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?:~|\.{1,2})?(?:/[\w.@+%~,\-]+)+/?(?::\d+)?").unwrap());

/// Trailing punctuation is nearly always the sentence, not the link.
const TRAILING: [char; 8] = ['.', ',', ';', ':', '!', '?', '\'', '"'];

/// Whether the character before a match would have made it part of a word.
///
/// This is the lookbehind the pattern cannot carry: `/etc/passwd` is a path,
/// the `/foo` inside `a/b/foo` already belongs to something else.
fn starts_a_word(text: &str, at: usize) -> bool {
    text[..at]
        .chars()
        .next_back()
        .is_some_and(|c| c.is_alphanumeric() || c == '_' || matches!(c, '/' | '~' | '.' | '-' | ':'))
}

/// Byte offset to character column.
fn col_of(text: &str, byte: usize) -> usize {
    text[..byte].chars().count()
}

/// The links on one row of text, left to right.
pub fn scan_row(row: u16, text: &str) -> Vec<LinkSpan> {
    let mut found: Vec<LinkSpan> = Vec::new();
    if !text.contains("://") && !text.contains('/') {
        return found;
    }
    for m in URL_RE.find_iter(text) {
        let mut end = m.end();
        while end > m.start() && TRAILING.contains(&text[..end].chars().next_back().unwrap()) {
            end -= text[..end].chars().next_back().unwrap().len_utf8();
        }
        if end > m.start() {
            found.push(LinkSpan {
                row,
                start: col_of(text, m.start()) as u16,
                end: col_of(text, end) as u16,
                text: text[m.start()..end].to_string(),
                is_url: true,
            });
        }
    }
    for m in PATH_RE.find_iter(text) {
        if starts_a_word(text, m.start()) {
            continue;
        }
        let (s, e) = (col_of(text, m.start()), col_of(text, m.end()));
        // A path inside a URL is part of the URL, not a link of its own.
        if found.iter().any(|l| l.row == row && s < l.end as usize && e > l.start as usize) {
            continue;
        }
        let mut end = m.end();
        while end > m.start() && TRAILING.contains(&text[..end].chars().next_back().unwrap()) {
            end -= text[..end].chars().next_back().unwrap().len_utf8();
        }
        let t = &text[m.start()..end];
        // Too short to mean anything, or a bare root with one segment.
        if t.chars().count() < 3 || t == "~/" || (t.matches('/').count() == 1 && t.starts_with('/') && t.chars().count() < 4) {
            continue;
        }
        found.push(LinkSpan {
            row,
            start: col_of(text, m.start()) as u16,
            end: col_of(text, end) as u16,
            text: t.to_string(),
            is_url: false,
        });
    }
    found.sort_by_key(|l| l.start);
    found
}

/// A link that runs into the right edge continues on the next row; give both
/// halves the joined text so tapping either opens the whole thing.
pub fn join_wrapped(links: &mut [LinkSpan], cols: u16) {
    for i in 0..links.len() {
        if links[i].end < cols {
            continue;
        }
        let (row, joined) = {
            let a = &links[i];
            let Some(b) = links.iter().find(|b| b.row == a.row + 1 && b.start == 0) else {
                continue;
            };
            (b.row, format!("{}{}", a.text, b.text))
        };
        links[i].text = joined.clone();
        if let Some(b) = links.iter_mut().find(|b| b.row == row && b.start == 0) {
            b.text = joined;
        }
    }
}

/// Remembers what each row said and what was found in it.
#[derive(Default)]
pub struct LinkCache {
    rows: Vec<(String, Vec<LinkSpan>)>,
}

impl LinkCache {
    /// Links for the visible grid, rescanning only the rows that changed.
    pub fn scan(&mut self, rows: &[String], cols: u16) -> Vec<LinkSpan> {
        if self.rows.len() != rows.len() {
            self.rows = vec![(String::new(), Vec::new()); rows.len()];
            // A fresh cache has nothing to say about any row, so make sure the
            // comparison below misses rather than matching an empty row.
            for (i, slot) in self.rows.iter_mut().enumerate() {
                slot.0.push('\u{0}');
                let _ = i;
            }
        }
        let mut all = Vec::new();
        for (i, text) in rows.iter().enumerate() {
            if &self.rows[i].0 != text {
                self.rows[i] = (text.clone(), scan_row(i as u16, text));
            }
            all.extend(self.rows[i].1.iter().cloned());
        }
        join_wrapped(&mut all, cols);
        all
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn texts(links: &[LinkSpan]) -> Vec<&str> {
        links.iter().map(|l| l.text.as_str()).collect()
    }

    #[test]
    fn a_url_is_found_and_its_trailing_punctuation_left_out() {
        let l = scan_row(0, "see https://example.com/a/b, then stop");
        assert_eq!(texts(&l), vec!["https://example.com/a/b"]);
        assert!(l[0].is_url);
    }

    #[test]
    fn an_absolute_path_is_found() {
        let l = scan_row(0, "open /etc/nginx/nginx.conf now");
        assert_eq!(texts(&l), vec!["/etc/nginx/nginx.conf"]);
        assert!(!l[0].is_url);
    }

    #[test]
    fn a_path_that_continues_a_word_is_not_a_link() {
        // The lookbehind the pattern cannot carry.
        assert!(scan_row(0, "one/two/three").is_empty());
    }

    #[test]
    fn the_path_inside_a_url_is_not_a_second_link() {
        let l = scan_row(0, "https://example.com/a/b/c");
        assert_eq!(l.len(), 1, "got {:?}", texts(&l));
    }

    #[test]
    fn a_row_with_no_slash_is_skipped_entirely() {
        assert!(scan_row(0, "nothing interesting here at all").is_empty());
    }

    #[test]
    fn a_bare_root_is_too_short_to_be_worth_offering() {
        assert!(scan_row(0, "cd /a").is_empty());
    }

    #[test]
    fn columns_are_characters_not_bytes() {
        // The box drawing characters ahead of the path are three bytes each.
        let l = scan_row(0, "├── /etc/hosts");
        assert_eq!(l.len(), 1);
        assert_eq!(l[0].start, 4, "column should count characters");
    }

    #[test]
    fn a_link_running_off_the_edge_is_joined_with_the_next_row() {
        let mut l = vec![
            LinkSpan { row: 0, start: 60, end: 80, text: "https://example.com/".into(), is_url: true },
            LinkSpan { row: 1, start: 0, end: 8, text: "verylong".into(), is_url: false },
        ];
        join_wrapped(&mut l, 80);
        assert_eq!(l[0].text, "https://example.com/verylong");
        assert_eq!(l[1].text, "https://example.com/verylong", "either half opens the whole");
    }

    #[test]
    fn an_unchanged_row_is_not_rescanned() {
        let mut cache = LinkCache::default();
        let rows = vec!["see /etc/hosts".to_string(), "nothing".to_string()];
        let first = cache.scan(&rows, 80);
        assert_eq!(texts(&first), vec!["/etc/hosts"]);
        // Same text again: same answer, and the cache is what served it.
        let again = cache.scan(&rows, 80);
        assert_eq!(first, again);
    }

    #[test]
    fn a_changed_row_is_rescanned() {
        let mut cache = LinkCache::default();
        let mut rows = vec!["nothing".to_string()];
        assert!(cache.scan(&rows, 80).is_empty());
        rows[0] = "now /var/log/syslog".to_string();
        assert_eq!(texts(&cache.scan(&rows, 80)), vec!["/var/log/syslog"]);
    }

    #[test]
    fn an_empty_row_reads_as_empty_on_a_fresh_cache() {
        let mut cache = LinkCache::default();
        assert!(cache.scan(&[String::new()], 80).is_empty());
    }
}
