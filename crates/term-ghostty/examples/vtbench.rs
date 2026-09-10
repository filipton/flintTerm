//! Side-by-side timing of the two emulator backends on identical input.
//!
//!     cargo run -p term-ghostty --release --example vtbench
//!
//! Every case drives the same bytes through both backends and then snapshots
//! the grid the way the session's frame loop does, so the numbers cover what
//! the app actually pays per frame. Timings are wall clock on this machine —
//! useful for the ratio between the two, not as an absolute figure for a phone.

use std::time::{Duration, Instant};

use term_alacritty::AlacrittyEmulator;
use term_core::{Emulator, Palette};
use term_ghostty::GhosttyEmulator;

const COLS: u16 = 120;
const ROWS: u16 = 40;
const SCROLLBACK: usize = 10_000;

fn make(name: &str, cols: u16, rows: u16) -> Box<dyn Emulator> {
    match name {
        "alacritty" => Box::new(AlacrittyEmulator::new(cols, rows, SCROLLBACK)),
        _ => Box::new(GhosttyEmulator::new(cols, rows, SCROLLBACK)),
    }
}

/// Plain output scrolling past, the `cat a big file` case.
fn plain_text() -> Vec<u8> {
    let mut out = Vec::new();
    for i in 0..20_000 {
        out.extend_from_slice(
            format!("{i:6}  the quick brown fox jumps over the lazy dog 0123456789\r\n").as_bytes(),
        );
    }
    out
}

/// A colour change on every run of characters, the `ls --color` / build-log case.
fn styled_text() -> Vec<u8> {
    let mut out = Vec::new();
    for i in 0..20_000 {
        let fg = 31 + (i % 7);
        out.extend_from_slice(format!("\x1b[{fg};1mword\x1b[0m \x1b[38;5;{}mmore\x1b[0m ", i % 256).as_bytes());
        if i % 8 == 7 {
            out.extend_from_slice(b"\r\n");
        }
    }
    out
}

/// Full-screen repaints on the alternate screen, the `btop` / `vim` case.
fn alt_screen_frames() -> Vec<u8> {
    let mut out = Vec::from(&b"\x1b[?1049h"[..]);
    for frame in 0u32..300 {
        out.extend_from_slice(b"\x1b[H");
        for row in 0u32..ROWS as u32 {
            out.extend_from_slice(format!("\x1b[{};1H", row + 1).as_bytes());
            for col in 0u32..(COLS as u32 / 8) {
                let c = (frame + row + col) % 6;
                out.extend_from_slice(
                    format!("\x1b[4{}m\x1b[3{}m{:>8}", c, (c + 3) % 8, frame + col).as_bytes(),
                );
            }
        }
    }
    out.extend_from_slice(b"\x1b[?1049l");
    out
}

/// What a terminal usually holds: mostly plain text with a handful of styles
/// recurring through it, the shape of `ls --color` or a build log.
fn mixed_text() -> Vec<u8> {
    const STYLES: [&str; 6] = ["\x1b[32m", "\x1b[1;34m", "\x1b[33m", "\x1b[31;1m", "\x1b[36m", "\x1b[2m"];
    let mut out = Vec::new();
    for i in 0..20_000 {
        out.extend_from_slice(
            format!(
                "{}{:>10}\x1b[0m  src/module_{}.rs   compiled in {}ms\r\n",
                STYLES[i % STYLES.len()],
                if i % 5 == 0 { "warning" } else { "ok" },
                i % 97,
                i % 400,
            )
            .as_bytes(),
        );
    }
    out
}

/// Every cell styled, but all with the *same* style: isolates the per-cell
/// work from the cost of looking a new style up.
fn one_style_text() -> Vec<u8> {
    let mut out = Vec::from(&b"\x1b[1;31m"[..]);
    for i in 0..20_000 {
        out.extend_from_slice(format!("{i:6}  the quick brown fox jumps over the lazy dog\r\n").as_bytes());
    }
    out
}

/// Nothing but cursor positioning: CUP and nothing else. A full-screen TUI
/// repaint is mostly this, and it is the case where alacritty comes out ahead.
fn cup_only() -> Vec<u8> {
    let mut out = Vec::new();
    for i in 0..200_000u32 {
        out.extend_from_slice(format!("\x1b[{};{}H", i % 40 + 1, i % 120 + 1).as_bytes());
    }
    out
}

/// Nothing but SGR changes, the other half of a repaint.
fn sgr_only() -> Vec<u8> {
    let mut out = Vec::new();
    for i in 0..200_000u32 {
        out.extend_from_slice(format!("\x1b[3{}m", i % 8).as_bytes());
    }
    out
}

/// UTF-8 with wide characters, which both backends have to measure per cell.
fn wide_text() -> Vec<u8> {
    let mut out = Vec::new();
    for i in 0..20_000 {
        out.extend_from_slice(format!("日本語テスト {i} ひらがな カタカナ\r\n").as_bytes());
    }
    out
}

/// Best of a few runs. A single run of any of these is noisy by 10-20% on a
/// machine doing anything else, and the minimum is the one that is not
/// measuring the interference.
fn best<F: FnMut() -> Duration>(mut run: F) -> Duration {
    (0..5).map(|_| run()).min().unwrap()
}

/// The same, with the filter switched on the way the app runs it.
///
/// Prompt marks and the rest mean every chunk goes through the interceptor
/// instead of straight to the parser, which is the path a real session takes
/// and the one the plain [`bench_feed`] never touches.
fn bench_feed_filtered(backend: &str, input: &[u8]) -> Duration {
    let mut e = make(backend, COLS, ROWS);
    e.set_intercept(term_core::InterceptOptions {
        notifications: true,
        prompt_marks: true,
        working_directory: true,
        ..Default::default()
    });
    let start = Instant::now();
    for chunk in input.chunks(8192) {
        e.feed(chunk);
        e.take_events();
    }
    start.elapsed()
}

fn bench_feed(backend: &str, input: &[u8]) -> Duration {
    let mut e = make(backend, COLS, ROWS);
    let start = Instant::now();
    // Fed in pty-sized chunks rather than one slab, which is how it arrives.
    for chunk in input.chunks(8192) {
        e.feed(chunk);
        e.take_events();
    }
    start.elapsed()
}

fn bench_snapshot(backend: &str, input: &[u8], frames: u32) -> Duration {
    let mut e = make(backend, COLS, ROWS);
    e.feed(input);
    e.take_events();
    let palette = Palette::default();
    let mut buf = Vec::new();
    let start = Instant::now();
    for _ in 0..frames {
        e.snapshot(&palette, &mut buf);
    }
    start.elapsed()
}

/// The loop the app actually runs: output arrives, then the grid is
/// serialised for the renderer. Nothing here snapshots a screen that did not
/// change, because the session only snapshots when its damage flag is set.
fn bench_frames(backend: &str, setup: &[u8], frames: &[Vec<u8>]) -> Duration {
    let mut e = make(backend, COLS, ROWS);
    e.feed(setup);
    e.take_events();
    let palette = Palette::default();
    let mut buf = Vec::new();
    e.snapshot(&palette, &mut buf);

    let start = Instant::now();
    for frame in frames {
        e.feed(frame);
        e.take_events();
        e.snapshot(&palette, &mut buf);
    }
    start.elapsed()
}

/// A line of output at a time, so the viewport scrolls every frame.
fn scrolling_frames() -> Vec<Vec<u8>> {
    (0..1000)
        .map(|i| format!("{i:5}  build step finished, moving on to the next one\r\n").into_bytes())
        .collect()
}

/// A TUI redrawing one status line in place, the shape of a progress bar or a
/// clock: the viewport does not move and one row changes.
fn tui_line_frames() -> Vec<Vec<u8>> {
    (0..1000)
        .map(|i| {
            format!("\x1b[{};1H\x1b[7m {:>3}%  {:<40}\x1b[0m", ROWS, i % 101, "working")
                .into_bytes()
        })
        .collect()
}

/// A TUI repainting the whole screen every frame, the way `btop` does.
fn tui_full_frames() -> Vec<Vec<u8>> {
    (0..200)
        .map(|frame: u32| {
            let mut out = Vec::new();
            for row in 0..ROWS as u32 {
                out.extend_from_slice(format!("\x1b[{};1H", row + 1).as_bytes());
                for col in 0..(COLS as u32 / 10) {
                    let c = (frame + row + col) % 6;
                    out.extend_from_slice(
                        format!("\x1b[3{}m{:>10}", c + 1, frame + col).as_bytes(),
                    );
                }
            }
            out
        })
        .collect()
}

/// Scrolling back through history, which is what a fling does.
fn bench_scroll(backend: &str, input: &[u8]) -> Duration {
    let mut e = make(backend, COLS, ROWS);
    e.feed(input);
    e.take_events();
    let palette = Palette::default();
    let mut buf = Vec::new();
    let history = e.history_size();
    let start = Instant::now();
    for i in 0..500 {
        e.scroll_to((i * history / 500).min(history));
        e.snapshot(&palette, &mut buf);
    }
    start.elapsed()
}

fn row(name: &str, a: Duration, g: Duration) {
    let ratio = g.as_secs_f64() / a.as_secs_f64();
    let verdict = if ratio < 0.98 {
        format!("ghostty {:.2}x faster", 1.0 / ratio)
    } else if ratio > 1.02 {
        format!("alacritty {ratio:.2}x faster")
    } else {
        "even".to_string()
    };
    println!("{name:<30} {:>10.1?} {:>10.1?}   {verdict}", a, g);
}

fn main() {
    let plain = plain_text();
    let styled = styled_text();
    let alt = alt_screen_frames();
    let wide = wide_text();
    let mixed = mixed_text();
    let cup = cup_only();
    let sgr = sgr_only();

    println!("{COLS}x{ROWS}, {SCROLLBACK} lines of scrollback");
    println!("\nparsing\n");
    println!("{:<30} {:>10} {:>10}", "case", "alacritty", "ghostty");

    for (name, input) in [
        ("feed plain", &plain),
        ("feed styled", &styled),
        ("feed alt-screen", &alt),
        ("feed wide (CJK)", &wide),
        ("feed mixed", &mixed),
        ("feed 200k CUP only", &cup),
        ("feed 200k SGR only", &sgr),
    ] {
        row(
            name,
            best(|| bench_feed("alacritty", input)),
            best(|| bench_feed("ghostty", input)),
        );
    }

    println!("\nthe same with the OSC filter on, which is how a session runs\n");
    for (name, input) in [
        ("filtered plain", &plain),
        ("filtered styled", &styled),
        ("filtered mixed", &mixed),
        ("filtered alt-screen", &alt),
    ] {
        row(
            name,
            best(|| bench_feed_filtered("alacritty", input)),
            best(|| bench_feed_filtered("ghostty", input)),
        );
    }

    println!("\nserialising a screen that did not change since the last snapshot\n");
    row(
        "snapshot x1000 (plain)",
        best(|| bench_snapshot("alacritty", &plain, 1000)),
        best(|| bench_snapshot("ghostty", &plain, 1000)),
    );
    row(
        "snapshot x1000 (styled)",
        best(|| bench_snapshot("alacritty", &styled, 1000)),
        best(|| bench_snapshot("ghostty", &styled, 1000)),
    );
    row(
        "snapshot x1000 (mixed)",
        best(|| bench_snapshot("alacritty", &mixed, 1000)),
        best(|| bench_snapshot("ghostty", &mixed, 1000)),
    );
    let one = one_style_text();
    row(
        "snapshot x1000 (1 style)",
        best(|| bench_snapshot("alacritty", &one, 1000)),
        best(|| bench_snapshot("ghostty", &one, 1000)),
    );
    println!("\nscrolling back through history, which is what a fling does\n");
    row(
        "scroll+snapshot x500",
        best(|| bench_scroll("alacritty", &plain)),
        best(|| bench_scroll("ghostty", &plain)),
    );

    println!("\nfeed + snapshot per frame, which is the loop the app runs\n");
    let scrolling = scrolling_frames();
    row(
        "1000 frames, output scrolling",
        best(|| bench_frames("alacritty", b"", &scrolling)),
        best(|| bench_frames("ghostty", b"", &scrolling)),
    );
    let tui_line = tui_line_frames();
    row(
        "1000 frames, TUI status line",
        best(|| bench_frames("alacritty", b"\x1b[?1049h", &tui_line)),
        best(|| bench_frames("ghostty", b"\x1b[?1049h", &tui_line)),
    );
    let tui_full = tui_full_frames();
    row(
        "200 frames, TUI full repaint",
        best(|| bench_frames("alacritty", b"\x1b[?1049h", &tui_full)),
        best(|| bench_frames("ghostty", b"\x1b[?1049h", &tui_full)),
    );
}
