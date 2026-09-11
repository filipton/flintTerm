//! Timing for the two per-frame calls, so a change can be judged off-device.
//! `cargo test -p term-alacritty --release --test bench -- --ignored --nocapture`
use std::time::Instant;
use term_alacritty::AlacrittyEmulator;
use term_core::{Emulator, Palette};

fn loaded() -> AlacrittyEmulator {
    let mut e = AlacrittyEmulator::new(62, 30, 1000);
    for i in 0..200 {
        e.feed(
            format!(
                "\x1b[32m{:>5}\x1b[0m /usr/lib/systemd/system-{}.service \x1b[1mloaded\x1b[0m active running Some Daemon\r\n",
                i, i
            )
            .as_bytes(),
        );
    }
    e
}

#[test]
#[ignore]
fn snapshot_cost() {
    let e = loaded();
    let palette = Palette::default();
    let mut buf = Vec::new();
    for _ in 0..200 {
        e.snapshot(&palette, &mut buf);
    }
    let n = 3000;
    let t = Instant::now();
    for _ in 0..n {
        e.snapshot(&palette, &mut buf);
    }
    println!("snapshot: {:>8.1} us/frame", t.elapsed().as_secs_f64() * 1e6 / n as f64);
}

#[test]
#[ignore]
fn row_text_cost() {
    let e = loaded();
    for _ in 0..200 {
        for r in 0..30 {
            std::hint::black_box(e.row_text(r));
        }
    }
    let n = 3000;
    let t = Instant::now();
    for _ in 0..n {
        for r in 0..30 {
            std::hint::black_box(e.row_text(r));
        }
    }
    println!("row_text x30:      {:>8.1} us/frame", t.elapsed().as_secs_f64() * 1e6 / n as f64);
}

#[test]
#[ignore]
fn row_text_into_cost() {
    let e = loaded();
    let mut rows: Vec<String> = (0..30).map(|_| String::new()).collect();
    let mut run = |e: &AlacrittyEmulator, rows: &mut Vec<String>| {
        for (r, slot) in rows.iter_mut().enumerate() {
            slot.clear();
            e.row_text_into(r as u16, slot);
        }
    };
    for _ in 0..200 {
        run(&e, &mut rows);
    }
    let n = 3000;
    let t = Instant::now();
    for _ in 0..n {
        run(&e, &mut rows);
    }
    println!("row_text_into x30: {:>8.1} us/frame", t.elapsed().as_secs_f64() * 1e6 / n as f64);
}
