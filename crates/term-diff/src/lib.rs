//! Runs the two emulator backends side by side on identical input and reports
//! everywhere they disagree.
//!
//! Both implement [`term_core::Emulator`], and the app is meant to behave the
//! same whichever it was built with. This is how that claim gets checked:
//! feed the same bytes to both, then compare everything the app can actually
//! observe — the packed grid cell by cell, the modes, the cursor, the
//! scrollback, the text of the visible rows.
//!
//! The two are separate emulators, so a few differences are real and expected
//! (they answer device-attribute queries with their own identity, for one).
//! [`Divergence::expected`] names those; everything else is a bug in the
//! `term-ghostty` backend, since `term-alacritty` is the one that shipped.

use std::fmt::Write as _;

use term_alacritty::AlacrittyEmulator;
use term_core::{Emulator, InterceptOptions, Palette, CELL_BYTES, HEADER_BYTES};
use term_ghostty::GhosttyEmulator;

pub mod scripts;

/// One thing the two backends disagreed about.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Divergence {
    pub script: &'static str,
    /// Which chunk of the script had just been fed.
    pub step: usize,
    /// What differed, e.g. `cell (12,3).fg` or `modes.app_cursor`.
    pub what: String,
    pub alacritty: String,
    pub ghostty: String,
}

impl Divergence {
    /// Why this difference is allowed, if it is.
    ///
    /// Two emulators are under no obligation to agree. Where ghostty is simply
    /// doing its own thing and the screen still reads the same — or where it is
    /// the more conformant of the two — the difference is recorded and
    /// explained rather than argued with. `Script::known` carries the reason
    /// for a whole script; this covers the one case that turns up everywhere.
    pub fn known(&self) -> Option<&'static str> {
        // Device attributes, XTVERSION and the like: each emulator answers with
        // its own identity, and the reply goes to the program, never the grid.
        if self.what.starts_with("pty_write") {
            return Some("each backend reports its own identity");
        }
        // libghostty prunes scrollback a page at a time rather than a line at a
        // time, so its history overshoots the limit and then dips under it,
        // where alacritty sits exactly on it. Its own header calls the limit an
        // estimate for this reason.
        //
        // The slack is bounded: a dip of more than a quarter is not page
        // granularity, it is the scrollback being governed by something else.
        // That is not hypothetical — it is how the byte limit defaulting to
        // 10 KB was caught, which had ghostty holding half the lines it should.
        if self.what == "history_size" {
            if let (Ok(a), Ok(g)) = (self.alacritty.parse::<u64>(), self.ghostty.parse::<u64>()) {
                if g >= a || g * 4 >= a * 3 {
                    return Some("libghostty prunes scrollback by the page, not by the line");
                }
            }
        }
        None
    }
}

impl std::fmt::Display for Divergence {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "{}[{}] {}: alacritty={} ghostty={}",
            self.script, self.step, self.what, self.alacritty, self.ghostty
        )
    }
}

/// A decoded snapshot, so differences can be described in terms a person can
/// act on rather than as a byte offset.
struct Grid {
    cols: u16,
    rows: u16,
    cursor: (i16, i16),
    cursor_shape: u8,
    mode_flags: u8,
    display_offset: u32,
    history_size: u32,
    selection: [i16; 4],
    sel_block: u8,
    cells: Vec<Cell>,
}

#[derive(PartialEq, Eq)]
struct Cell {
    codepoint: u32,
    fg: u32,
    bg: u32,
    flags: u16,
}

impl Cell {
    fn describe(&self) -> String {
        let ch = char::from_u32(self.codepoint).filter(|c| !c.is_control()).unwrap_or(' ');
        format!(
            "{:?}(U+{:04X}) fg={:06x} bg={:06x} flags={:012b}",
            ch, self.codepoint, self.fg, self.bg, self.flags
        )
    }
}

impl Grid {
    fn parse(buf: &[u8]) -> Self {
        let u16at = |i: usize| u16::from_le_bytes(buf[i..i + 2].try_into().unwrap());
        let i16at = |i: usize| i16::from_le_bytes(buf[i..i + 2].try_into().unwrap());
        let u32at = |i: usize| u32::from_le_bytes(buf[i..i + 4].try_into().unwrap());

        let cols = u16at(0);
        let rows = u16at(2);
        let cells = buf[HEADER_BYTES..]
            .as_chunks::<CELL_BYTES>()
            .0
            .iter()
            .map(|c| Cell {
                codepoint: u32::from_le_bytes(c[0..4].try_into().unwrap()),
                fg: u32::from_le_bytes(c[4..8].try_into().unwrap()),
                bg: u32::from_le_bytes(c[8..12].try_into().unwrap()),
                flags: u16::from_le_bytes(c[12..14].try_into().unwrap()),
            })
            .collect();

        Self {
            cols,
            rows,
            cursor: (i16at(4), i16at(6)),
            cursor_shape: buf[8],
            mode_flags: buf[9],
            display_offset: u32at(10),
            history_size: u32at(14),
            selection: [i16at(18), i16at(20), i16at(22), i16at(24)],
            sel_block: buf[26],
            cells,
        }
    }
}

/// A named sequence of byte chunks, compared after every chunk so a divergence
/// is attributed to the sequence that caused it.
pub struct Script {
    pub name: &'static str,
    pub cols: u16,
    pub rows: u16,
    pub scrollback: usize,
    pub chunks: Vec<Vec<u8>>,
    /// Run against both after every chunk, for the parts of the trait that are
    /// driven rather than fed — scrolling, selecting, resizing.
    pub after_each: Option<fn(usize, &mut dyn Emulator)>,
    /// Set when the two are *known* to differ here, with the reason.
    ///
    /// Each such script holds one divergent behaviour and nothing else, so
    /// marking the whole script known hides nothing unrelated. They exist to
    /// pin the difference down and say why it is allowed to stand.
    pub known: Option<&'static str>,
    /// What the output filter in front of each backend claims. Off by default,
    /// which is a memcpy on both sides; the image and prompt-mark scripts turn
    /// their parts on, since that is where the two backends have the most of
    /// their own code in the path.
    pub intercept: InterceptOptions,
}

impl Script {
    pub fn new(name: &'static str, chunks: Vec<Vec<u8>>) -> Self {
        Self {
            name,
            cols: 40,
            rows: 8,
            scrollback: 200,
            chunks,
            after_each: None,
            known: None,
            intercept: InterceptOptions::default(),
        }
    }

    pub fn intercept(mut self, opts: InterceptOptions) -> Self {
        self.intercept = opts;
        self
    }

    pub fn scrollback(mut self, lines: usize) -> Self {
        self.scrollback = lines;
        self
    }

    pub fn size(mut self, cols: u16, rows: u16) -> Self {
        self.cols = cols;
        self.rows = rows;
        self
    }

    pub fn driving(mut self, f: fn(usize, &mut dyn Emulator)) -> Self {
        self.after_each = Some(f);
        self
    }

    /// Marks this script as one the two are allowed to disagree about.
    pub fn known(mut self, why: &'static str) -> Self {
        self.known = Some(why);
        self
    }
}

/// Feeds `script` to both backends and collects every difference.
pub fn run(script: &Script) -> Vec<Divergence> {
    let palette = Palette::default();
    let mut a: Box<dyn Emulator> =
        Box::new(AlacrittyEmulator::new(script.cols, script.rows, script.scrollback));
    let mut g: Box<dyn Emulator> =
        Box::new(GhosttyEmulator::new(script.cols, script.rows, script.scrollback));
    for e in [a.as_mut(), g.as_mut()] {
        e.set_intercept(script.intercept);
        // Ten pixels square keeps the image arithmetic legible.
        e.set_cell_size(10, 10);
    }

    let mut found = Vec::new();
    let mut ab = Vec::new();
    let mut gb = Vec::new();

    for (step, chunk) in script.chunks.iter().enumerate() {
        a.feed(chunk);
        g.feed(chunk);

        // Bytes the emulator wants written back to the pty. Compared as a
        // whole so a backend answering a query differently is one divergence
        // rather than one per byte.
        let pty = |e: &mut dyn Emulator| -> String {
            e.take_events()
                .into_iter()
                .filter_map(|ev| match ev {
                    term_core::EmulatorEvent::PtyWrite(b) => {
                        Some(String::from_utf8_lossy(&b).escape_debug().to_string())
                    }
                    _ => None,
                })
                .collect::<Vec<_>>()
                .join("|")
        };
        let (pa, pg) = (pty(a.as_mut()), pty(g.as_mut()));

        if let Some(f) = script.after_each {
            f(step, a.as_mut());
            f(step, g.as_mut());
        }

        let mut report = |what: String, alacritty: String, ghostty: String| {
            if alacritty != ghostty {
                found.push(Divergence { script: script.name, step, what, alacritty, ghostty });
            }
        };

        report("pty_write".into(), pa, pg);

        a.snapshot(&palette, &mut ab);
        g.snapshot(&palette, &mut gb);
        let (ga, gg) = (Grid::parse(&ab), Grid::parse(&gb));

        report("grid.cols".into(), ga.cols.to_string(), gg.cols.to_string());
        report("grid.rows".into(), ga.rows.to_string(), gg.rows.to_string());
        report("cursor".into(), format!("{:?}", ga.cursor), format!("{:?}", gg.cursor));
        report(
            "cursor_shape".into(),
            ga.cursor_shape.to_string(),
            gg.cursor_shape.to_string(),
        );
        report("header.modes".into(), format!("{:03b}", ga.mode_flags), format!("{:03b}", gg.mode_flags));
        report(
            "display_offset".into(),
            ga.display_offset.to_string(),
            gg.display_offset.to_string(),
        );
        report("history_size".into(), ga.history_size.to_string(), gg.history_size.to_string());
        report(
            "selection".into(),
            format!("{:?}b{}", ga.selection, ga.sel_block),
            format!("{:?}b{}", gg.selection, gg.sel_block),
        );

        if ga.cols == gg.cols && ga.rows == gg.rows {
            // At most a few cells per step, or a wholesale difference buries
            // everything else in the report.
            let mut shown = 0;
            for (i, (ca, cg)) in ga.cells.iter().zip(gg.cells.iter()).enumerate() {
                if ca == cg {
                    continue;
                }
                shown += 1;
                if shown > 6 {
                    report(
                        "cells".into(),
                        format!("{} differing cells", ga.cells.iter().zip(&gg.cells).filter(|(x, y)| x != y).count()),
                        "(truncated)".into(),
                    );
                    break;
                }
                let (col, row) = (i % ga.cols as usize, i / ga.cols as usize);
                report(format!("cell ({col},{row})"), ca.describe(), cg.describe());
            }
        }

        let ma = a.modes();
        let mg = g.modes();
        report("modes".into(), format!("{ma:?}"), format!("{mg:?}"));
        report(
            "cursor_row_col".into(),
            format!("{:?}", a.cursor_row_col()),
            format!("{:?}", g.cursor_row_col()),
        );
        // Both read these back out of their grids, so agreeing here means
        // the two grids carry the marks in the same cells.
        report("images".into(), format!("{:?}", a.images()), format!("{:?}", g.images()));
        report("prompt_marks".into(), format!("{:?}", a.prompt_marks()), format!("{:?}", g.prompt_marks()));
        report(
            "selection_text".into(),
            format!("{:?}", a.selection_text()),
            format!("{:?}", g.selection_text()),
        );
        // Only the lines both still hold. How deep the scrollback goes is a
        // pruning-granularity difference (see `Divergence::known`), but every
        // line they both still have must match exactly.
        let (la, lg) = (a.all_lines(), g.all_lines());
        let common = la.len().min(lg.len());
        report(
            "recent_lines".into(),
            format!("{:?}", &la[la.len() - common..]),
            format!("{:?}", &lg[lg.len() - common..]),
        );
    }

    found
}

/// Runs every script and returns only the differences nothing accounts for.
pub fn run_all() -> Vec<Divergence> {
    scripts::all()
        .iter()
        .filter(|s| s.known.is_none())
        .flat_map(run)
        .filter(|d| d.known().is_none())
        .collect()
}

/// A readable report of a run, for the binary.
pub fn report(divergences: &[Divergence]) -> String {
    let mut out = String::new();
    if divergences.is_empty() {
        return "the two backends agree on every script\n".into();
    }
    let mut script = "";
    for d in divergences {
        if d.script != script {
            script = d.script;
            let _ = writeln!(out, "\n{script}");
        }
        let _ = writeln!(out, "  [{}] {}", d.step, d.what);
        let _ = writeln!(out, "        alacritty: {}", d.alacritty);
        let _ = writeln!(out, "          ghostty: {}", d.ghostty);
    }
    let _ = writeln!(out, "\n{} divergences", divergences.len());
    out
}
