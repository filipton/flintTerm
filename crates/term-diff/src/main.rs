//! Runs the two emulator backends on the same input and reports where they differ.
//!
//!     cargo run -p term-diff                  # the built-in scripts
//!     cargo run -p term-diff -- capture.bin   # a captured stream of terminal output
//!     cargo run -p term-diff -- --known       # also list the differences we know about
//!
//! A capture is fed in 512-byte chunks with a comparison after each, so a
//! difference is pinned near the sequence that caused it. Capture one with
//! `script -q -c 'your command' /dev/null -f capture.bin`, or by teeing a pty.
//!
//! Exits non-zero when anything differs that is not already accounted for, so
//! it works as a check as well as a tool.

use std::process::ExitCode;

use term_diff::{report, run, scripts, Divergence, Script};

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let show_known = args.iter().any(|a| a == "--known");
    let path = args.iter().find(|a| !a.starts_with("--"));

    let scripts: Vec<Script> = match path {
        Some(p) => {
            let bytes = match std::fs::read(p) {
                Ok(b) => b,
                Err(e) => {
                    eprintln!("cannot read {p}: {e}");
                    return ExitCode::FAILURE;
                }
            };
            println!("{p}: {} bytes\n", bytes.len());
            // A realistic scrollback: libghostty prunes by the page, so a tiny
            // limit makes its history sawtooth in a way no real setting does.
            vec![Script::new("capture", bytes.chunks(512).map(<[u8]>::to_vec).collect())
                .size(80, 24)
                .scrollback(5000)]
        }
        None => scripts::all(),
    };

    let mut unexplained: Vec<Divergence> = Vec::new();
    let mut known: Vec<Divergence> = Vec::new();

    for script in &scripts {
        let found = run(script);
        let total = found.len();
        for d in found {
            if script.known.is_some() || d.known().is_some() {
                known.push(d);
            } else {
                unexplained.push(d);
            }
        }
        let note = match script.known {
            Some(_) => "known",
            None => "",
        };
        println!("{:<34} {:>3} chunks {:>4} differences  {note}", script.name, script.chunks.len(), total);
    }

    if show_known {
        println!("\n== differences we know about ==");
        for script in scripts.iter().filter(|s| s.known.is_some()) {
            println!("\n{}\n  {}", script.name, script.known.unwrap());
        }
        println!("\n{}", report(&known));
    }

    println!("\n== unexplained ==\n{}", report(&unexplained));
    if unexplained.is_empty() {
        ExitCode::SUCCESS
    } else {
        ExitCode::FAILURE
    }
}
