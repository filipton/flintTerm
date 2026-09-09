//! Where a shell's prompt marks are, once they are on the grid.
//!
//! [`crate::intercept`] lifts OSC 133 out of the byte stream and says what the
//! shell announced. What it cannot say is where on the screen it happened, and
//! without that nothing can scroll to a prompt or select the output between
//! two of them.
//!
//! Position is the grid's business, and the answer is the one the inline
//! images already use, for the reason spelled out in [`crate::images`]: an
//! absolute grid line cannot be anchored to, because `alacritty_terminal`
//! reports a scrollback depth that saturates once the history is full. A cell
//! marked with a private OSC 8 hyperlink, on the other hand, scrolls, reflows
//! and is erased along with the text around it, at no cost to us.
//!
//! What differs from an image is *which* cell gets marked. An image writes its
//! own cells and can mark them as it draws them; a prompt mark arrives just
//! before the shell prints something, and the cell under the cursor is
//! precisely the one about to be overwritten. So the mark goes on the pen
//! instead, and the first character the shell prints after it is what carries
//! it — a cell with real content on it, which nothing is going to blank. The
//! emulator prunes the rest of the run away again (see
//! `AlacrittyEmulator::settle_marks`).

use crate::intercept::PromptMark;

/// The URI scheme the prompt marks use.
///
/// Deliberately not a real scheme, for the same reason the image placements'
/// is not: the app's link detection must never offer one of these to be
/// tapped, and a name no browser knows is the simplest way to say so.
pub const MARK_SCHEME: &str = "flintterm-prompt";

/// A prompt mark and where it currently sits, in viewport rows.
///
/// Row 0 is the top visible line; a mark that has scrolled into the history
/// reports a negative row, as an image placement does, and one below the
/// viewport reports a row past its last line.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PromptMarkAt {
    pub mark: PromptMark,
    pub row: i32,
}

/// The URI that marks a cell as `serial`'s, naming the marks that landed on it.
///
/// Several marks share one cell whenever the shell sends them with nothing
/// printed in between, which `D` immediately followed by `A` — the end of one
/// command and the prompt of the next — does on every command there is.
pub fn mark_uri(serial: u32, marks: &[PromptMark]) -> String {
    let mut uri = format!("{MARK_SCHEME}:{serial}");
    for m in marks {
        uri.push('/');
        match m {
            PromptMark::PromptStart => uri.push('a'),
            PromptMark::CommandStart => uri.push('b'),
            PromptMark::OutputStart => uri.push('c'),
            PromptMark::Finished(None) => uri.push('d'),
            PromptMark::Finished(Some(status)) => uri.push_str(&format!("d{status}")),
        }
    }
    uri
}

/// The serial and the marks a URI names, if it is one of ours.
pub fn parse_marks(uri: &str) -> Option<(u32, Vec<PromptMark>)> {
    let rest = uri.strip_prefix(MARK_SCHEME)?.strip_prefix(':')?;
    let mut parts = rest.split('/');
    let serial: u32 = parts.next()?.parse().ok()?;
    let mut marks = Vec::new();
    for part in parts {
        let mut chars = part.chars();
        marks.push(match chars.next()? {
            'a' => PromptMark::PromptStart,
            'b' => PromptMark::CommandStart,
            'c' => PromptMark::OutputStart,
            'd' => match chars.as_str() {
                "" => PromptMark::Finished(None),
                status => PromptMark::Finished(Some(status.parse().ok()?)),
            },
            _ => return None,
        });
    }
    if marks.is_empty() {
        return None;
    }
    Some((serial, marks))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_mark_uri_survives_a_round_trip() {
        let marks = vec![
            PromptMark::PromptStart,
            PromptMark::CommandStart,
            PromptMark::OutputStart,
            PromptMark::Finished(None),
            PromptMark::Finished(Some(0)),
            // A shell reporting a signal writes it as a negative number.
            PromptMark::Finished(Some(-11)),
        ];
        assert_eq!(parse_marks(&mark_uri(9, &marks)), Some((9, marks)));
    }

    #[test]
    fn somebody_elses_uri_is_not_ours() {
        for uri in ["https://example.com", "flintterm-image:1/0", "flintterm-prompt:1", "flintterm-prompt:x/a"] {
            assert_eq!(parse_marks(uri), None, "{uri} was claimed");
        }
    }
}
