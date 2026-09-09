//! The sequences both backends are driven through.
//!
//! Each is split into chunks so a divergence is pinned to the sequence that
//! caused it rather than to the end of a long stream. They aim at the parts of
//! a terminal the app actually leans on: what the renderer draws, where the
//! cursor is, what scrollback holds, and what a selection copies.

use term_core::{images, InterceptOptions, SelectionKind, ViewPoint};

use crate::Script;

fn chunks(parts: &[&str]) -> Vec<Vec<u8>> {
    parts.iter().map(|p| p.as_bytes().to_vec()).collect()
}

/// A kitty graphics command carrying a white `w` by `h` image as id `id`.
fn kitty_image(id: u32, w: u32, h: u32) -> Vec<u8> {
    let pixels = vec![255u8; (w * h * 4) as usize];
    format!("\x1b_Ga=T,f=32,s={w},v={h},i={id};{}\x1b\\", images::base64_encode(&pixels)).into_bytes()
}

/// One command the way a shell that marks its prompts sends it.
fn marked_command(prompt: &str, typed: &str, output: &str, status: i32) -> String {
    format!("\x1b]133;A\x07{prompt}\x1b]133;B\x07{typed}\r\n\x1b]133;C\x07{output}\x1b]133;D;{status}\x07")
}

pub fn all() -> Vec<Script> {
    vec![
        Script::new(
            "plain text and wrapping",
            chunks(&[
                "hello world\r\n",
                "a line that is long enough to wrap past the right margin of the grid\r\n",
                "back\x08space and \rcarriage return\r\n",
            ]),
        ),
        Script::new(
            "sgr attributes",
            chunks(&[
                "\x1b[1mbold\x1b[0m ",
                "\x1b[3mitalic\x1b[0m ",
                "\x1b[4munderline\x1b[0m ",
                "\x1b[9mstrike\x1b[0m ",
                "\x1b[7minverse\x1b[0m ",
                "\x1b[2mfaint\x1b[0m ",
                "\x1b[8mhidden\x1b[0m ",
                "\x1b[4:3mundercurl\x1b[0m ",
                "\x1b[1;3;4;7mcombined\x1b[0m ",
            ]),
        ),
        Script::new(
            "colours",
            chunks(&[
                "\x1b[31mred\x1b[0m \x1b[92mbright\x1b[0m ",
                "\x1b[38;5;200m256fg\x1b[0m \x1b[48;5;22m256bg\x1b[0m ",
                "\x1b[38;2;10;20;30mtruecolor\x1b[0m ",
                "\x1b[48;2;200;100;50mtruebg\x1b[0m ",
                "\x1b[39;49mdefaults\x1b[0m ",
                // Faint plus a colour, which the two resolve through different
                // code paths on the way to the same dimmed value.
                "\x1b[2;31mfaint red\x1b[0m ",
            ]),
        ),
        Script::new(
            "cursor movement",
            chunks(&[
                "\x1b[5;10Hpositioned",
                "\x1b[2A up \x1b[3B down",
                "\x1b[4C forward \x1b[2D back",
                "\x1b[H home \x1b[3;3f hvp",
                "\x1b[G column one",
                "\x1b[?25l hidden cursor",
                "\x1b[?25h shown again",
                "\x1b[3 q underline cursor",
                "\x1b[5 q bar cursor",
                "\x1b[1 q block cursor",
            ]),
        ),
        Script::new(
            "erase and edit",
            chunks(&[
                "0123456789\r\nabcdefghij\r\nABCDEFGHIJ\r\n",
                "\x1b[2;5H\x1b[K erase to end of line",
                "\x1b[3;5H\x1b[1K erase to start",
                "\x1b[1;1H\x1b[4X erase 4 characters",
                "\x1b[2;2H\x1b[3@ insert blanks",
                "\x1b[2;2H\x1b[2P delete characters",
                "\x1b[J erase below",
            ]),
        ),
        Script::new(
            "scroll regions",
            chunks(&[
                "one\r\ntwo\r\nthree\r\nfour\r\nfive\r\nsix\r\n",
                "\x1b[2;5r",
                "\x1b[5;1H\r\nscrolled in region\r\n",
                "\x1b[2;1H\x1b[2Sscroll up two",
                "\x1b[2;1H\x1b[1Tscroll down one",
                "\x1b[r\x1b[8;1Hregion reset",
            ]),
        ),
        Script::new(
            "alternate screen",
            chunks(&[
                "primary content\r\nsecond line\r\n",
                "\x1b[?1049h",
                "\x1b[Halt screen \x1b[32mcontent\x1b[0m\r\n",
                "more alt lines\r\n",
                "\x1b[?1049l",
                "back on the primary\r\n",
            ]),
        ),
        Script::new(
            "wide characters and graphemes",
            chunks(&[
                "日本語テスト\r\n",
                "mixed 漢字 and latin\r\n",
                // A wide character landing exactly on the last column has to
                // wrap rather than split.
                "\x1b[1;39Hab日\r\n",
                "combining e\u{0301} and a\u{0308}\r\n",
                "\u{1f600} emoji\r\n",
            ]),
        )
        .size(40, 8),
        Script::new(
            "modes the input encoder reads",
            chunks(&[
                "\x1b[?1h app cursor",
                "\x1b[?1l normal cursor",
                "\x1b[?2004h bracketed paste",
                "\x1b[?1000h mouse click",
                "\x1b[?1002h mouse drag",
                "\x1b[?1003h mouse motion",
                "\x1b[?1006h sgr mouse",
                "\x1b[?1007h alternate scroll",
                "\x1b[?1000l\x1b[?1002l\x1b[?1003l mouse off",
                "\x1b=application keypad",
                "\x1b>normal keypad",
            ]),
        ),
        Script::new(
            "osc",
            chunks(&[
                "\x1b]0;a title\x07",
                "\x1b]2;another title\x1b\\",
                "\x1b]8;;https://example.com\x07link text\x1b]8;;\x07",
                "\x1b]0;\x07",
            ]),
        ),
        // Scrollback, driven: fill history, then scroll through it.
        Script::new(
            "scrollback and scrolling",
            (0..24).map(|i| format!("line {i:02}\r\n").into_bytes()).collect(),
        )
        .size(20, 6)
        .driving(|step, e| {
            match step % 4 {
                0 => e.scroll_display(2),
                1 => e.scroll_display(-1),
                2 => e.scroll_to_bottom(),
                _ => e.scroll_to(3),
            };
        }),
        // Selections over known content.
        Script::new(
            "selection",
            chunks(&[
                "alpha beta gamma\r\n",
                "delta epsilon zeta\r\n",
                "eta theta iota\r\n",
                "kappa lambda mu\r\n",
            ]),
        )
        .size(30, 6)
        .driving(|step, e| match step {
            0 => e.selection_start(ViewPoint { col: 2, row: 0 }, SelectionKind::Word),
            1 => e.selection_update(ViewPoint { col: 8, row: 1 }, false),
            2 => e.selection_update(ViewPoint { col: 1, row: 0 }, true),
            _ => e.selection_clear(),
        }),
        // A repaint-in-place TUI, the shape the snapshot cache is built for.
        Script::new(
            "tui repaint",
            (0..12)
                .map(|frame: u32| {
                    let mut s = String::from("\x1b[H");
                    for row in 0..6 {
                        s.push_str(&format!("\x1b[{};1H", row + 1));
                        s.push_str(&format!(
                            "\x1b[3{}m{:<20}\x1b[0m",
                            (frame + row) % 8,
                            format!("row {row} frame {frame}")
                        ));
                    }
                    s.into_bytes()
                })
                .collect(),
        )
        .size(30, 6),
        // Long lines that wrap, filling scrollback: how each counts a wrapped
        // line as history is the thing under test.
        Script::new(
            "wrapped scrollback",
            (0..40)
                .map(|i| format!("{i:02} {}\r\n", "x".repeat(70)).into_bytes())
                .collect(),
        )
        .size(20, 4)
        .scrollback(5000),

        // --- differences the two are allowed to have ----------------------
        //
        // Each of these is one behaviour, isolated, so the reason attached to
        // it is the whole story.
        Script::new("tabs", chunks(&["tab\tseparated\tcolumns\r\n", "\x1b[1;1H\tone tab\r\n"]))
            .known(
                "alacritty leaves U+0009 in the cells a tab skipped over and keeps it in the \
                 line text; ghostty fills them with spaces. Neither draws a glyph, but text \
                 pulled out of the scrollback differs.",
            ),
        Script::new(
            "sgr 21",
            chunks(&["\x1b[21mdouble underline\x1b[0m ", "plain after"]),
        )
        .known(
            "ghostty renders SGR 21 as a double underline, which is what it means in ECMA-48; \
             alacritty ignores it. Ghostty is the more conformant of the two here.",
        ),
        Script::new(
            "mouse encodings set together",
            chunks(&["\x1b[?1006h", "\x1b[?1005h", "\x1b[?1006l"]),
        )
        .known(
            "1005 and 1006 are separate DEC modes and xterm lets both be set, resolving the \
             encoding by precedence, which is what ghostty reports and term-core's encoder \
             then applies. Alacritty models the encodings as one enum, so setting 1005 \
             clears 1006. No program sets both.",
        ),
        Script::new(
            "erase all",
            chunks(&["one\r\ntwo\r\nthree\r\n", "\x1b[2J"]),
        )
        .known(
            "alacritty saves the screen into scrollback when a program erases it with ED 2, \
             the way xterm does; ghostty drops it. Shows up as a differing history_size and \
             as those lines being present in all_lines.",
        ),
        Script::new(
            "insert and delete lines",
            chunks(&[
                "one\r\ntwo\r\nthree\r\nfour\r\n",
                "\x1b[1;1H\x1b[2L insert lines",
                "\x1b[1;1H\x1b[1M delete a line",
            ]),
        )
        .known(
            "alacritty pushes the lines an IL displaces into scrollback; ghostty does not, \
             which is what the spec asks for — IL scrolls within the region and must not \
             add to history. Shows up as a differing history_size and an extra line at the \
             top of all_lines.",
        ),
        Script::new(
            "selection trimming",
            chunks(&["alpha beta gamma\r\n", "delta epsilon\r\n"]),
        )
        .size(30, 4)
        .driving(|step, e| {
            if step == 0 {
                e.selection_start(ViewPoint { col: 4, row: 0 }, SelectionKind::Lines)
            } else {
                e.selection_all()
            }
        })
        .known(
            "selecting a line, or everything, covers the full width of every row in alacritty \
             and stops at the end of the written text in ghostty. The highlight is shorter and \
             a copy carries no trailing spaces. Ghostty trims by default and its C API has no \
             way to ask it not to.",
        ),
        Script::new(
            "resize",
            chunks(&["one\r\ntwo\r\nthree\r\nfour\r\nfive\r\n", "six\r\n", "seven\r\n", "eight\r\n"]),
        )
        .size(24, 6)
        .driving(|step, e| {
            let (cols, rows) = match step % 4 {
                0 => (24, 6),
                1 => (12, 4),
                2 => (40, 10),
                _ => (24, 6),
            };
            e.resize(cols, rows);
        }),
        // The output filter and the grid marks are the two backends' own code
        // rather than their VT engines', so this is where they are most
        // likely to drift apart: where a mark lands, what a placement covers,
        // and that neither shows up as a link the renderer would offer.
        Script::new(
            "prompt marks",
            chunks(&[
                &marked_command("$ ", "ls", "one\r\ntwo\r\n", 0),
                // The next prompt is what gives the finished command a cell.
                "\x1b]133;A\x07\x1b[1;32m$\x1b[0m ",
                // A mark split across chunks, with SGR in between.
                "\x1b]133;B\x07\x1b[",
                "4m",
                "cat x\r\n",
                "\x1b]133;C\x07no such file\r\n\x1b]133;D;1\x07",
                "\x1b]133;A\x07$ ",
                // A link the program opens before printing takes the pen.
                "\x1b]133;C\x07\x1b]8;;file:///a\x1b\\a\x1b]8;;\x1b\\b\r\n",
                "\x1b]133;D;0\x07\x1b]133;A\x07$ \r\n\r\n\r\n\r\n\r\n\r\n",
            ]),
        )
        .intercept(InterceptOptions { prompt_marks: true, ..Default::default() })
        .driving(|step, e| {
            // Marks in the history are what jumping back is for.
            if step == 8 {
                e.scroll_display(4);
            }
        }),
        Script::new(
            "kitty and sixel images",
            vec![
                b"top\r\n".to_vec(),
                kitty_image(1, 20, 20),
                b"after\r\n".to_vec(),
                // Two columns wide, one cell tall, then the cursor on the line below.
                b"\x1bP0;1;0q#1;2;0;100;0!20~\x1b\\".to_vec(),
                b"\x1b[3Cx".to_vec(),
                kitty_image(2, 10, 10),
                b"\r\n\r\n\r\n".to_vec(),
                // Delete by cursor position, then everything.
                b"\x1b[2;1H\x1b_Ga=d,d=c\x1b\\".to_vec(),
                b"\x1b_Ga=d,d=A\x1b\\".to_vec(),
            ],
        )
        .intercept(InterceptOptions { kitty_images: true, sixel_images: true, ..Default::default() })
        .driving(|step, e| {
            if step == 6 {
                e.scroll_display(2);
            }
            if step == 7 {
                e.scroll_to_bottom();
            }
        }),
    ]
}
