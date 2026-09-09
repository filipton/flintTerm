# The terminal backend

What the two engines are, how they compare, and how they are kept honest against each other.

The VT engine is chosen at compile time. `alacritty_terminal` is the default; passing `--term
ghostty` to `build-apk.sh` (or `-PtermBackend=ghostty` to Gradle, or `--features ghostty` to cargo)
swaps in **libghostty-vt**, the VT library out of [Ghostty](https://github.com/ghostty-org/ghostty).
Both implement `term_core::Emulator`, so the snapshot format, the FFI and the Kotlin renderer are the
same either way, and so are the colour schemes — the palette stays on our side and only the grid
comes from the backend.

libghostty-vt is Zig, so that build needs **Zig 0.16+** on `PATH`. `crates/ghostty-vt-sys/build.rs`
fetches Ghostty at a pinned revision into `target/ghostty-vt/`, runs `zig build` once per ABI and
links the static library; later builds reuse both.

Only the parts of libghostty-vt this crate calls are built: it encodes keys and mice with
`term_core::keys`, searches the scrollback through `Emulator::all_lines`, decodes images itself (see
below), and has no use for libghostty's own state serialisation, so those come out (`FEATURES` in
the build script, overridable with `GHOSTTY_VT_FEATURES`). Referencing something that was left out
fails to link rather than going quiet at runtime. A ghostty build also compiles alacritty out rather
than carrying both.

Everything the app layers on top of the VT engine works the same on either backend, because it is
not the engine's: notifications (OSC 9/99/777), the working directory (OSC 7), shell prompt marks
(OSC 133), kitty and sixel images and the kitty keyboard protocol are all lifted out of the byte
stream by `term_core::intercept` before the engine sees them, and where an image or a mark sits on
the screen is remembered by writing a private OSC 8 hyperlink into the grid and reading it back
through `ghostty_grid_ref_hyperlink_uri` — the same trick the alacritty backend uses, so a placement
scrolls, reflows and gets erased with the text around it on both. Two things are done differently
underneath. libghostty's grid cannot be edited from outside, so a prompt mark is not pruned back to
one cell after the fact; the pen is closed the moment the first cell printed after the mark carries
it, which means feeding the handful of bytes between a mark and its prompt one at a time. And
libghostty has no setting to switch the kitty keyboard protocol off, so with the protocol off the
filter stays in the stream to swallow the queries (`Interceptor::set_keyboard_gate`) instead of
stepping aside as it can for alacritty. The kitty flags a program pushed are read from
`GHOSTTY_TERMINAL_DATA_KITTY_KEYBOARD_FLAGS`. One observable difference remains, and it is the VT
engines' own: `ESC [ 2 J` pushes the cleared screen into the scrollback in alacritty and erases it
in place in libghostty, so a `clear` takes the marks on screen with it on the ghostty build where
the alacritty build keeps them in the history.

What is left costs, on arm64, +808 KB in the installed `libflintterm.so` (7.06 → 7.89 MB) and
+279 KB in the APK (7.43 → 7.72 MB), the emulator being the only difference between the two builds.
Carrying the whole of libghostty-vt and both backends would have been +1.50 MB and +650 KB.
Set `GHOSTTY_VT_LIB_DIR` to link a prebuilt `libghostty-vt.a` instead, or `GHOSTTY_SRC` to build
from a checkout you already have.

`cargo run -p term-ghostty --release --example vtbench` times the two against each other, best of
five runs. On an x86_64 dev box, 120x40:

| parsing | alacritty | ghostty |
| --- | --- | --- |
| feed 1.2 MB plain text | 26.5 ms | **2.4 ms** |
| feed 1.1 MB CJK | 23.5 ms | **3.6 ms** |
| feed a build log (6 recurring styles) | 26.9 ms | **5.9 ms** |
| feed a distinct style per word | 10.7 ms | **9.4 ms** |
| feed 300 alt-screen repaints | **28.9 ms** | 31.9 ms |

| feed + snapshot per frame, the loop the app runs | alacritty | ghostty |
| --- | --- | --- |
| 1000 frames of scrolling output | 29.8 ms | **8.3 ms** |
| 1000 frames of a TUI status line redrawing in place | 28.5 ms | **2.8 ms** |
| 200 frames of a TUI repainting the whole screen | **18.3 ms** | 19.9 ms |
| 500 scroll + snapshot (a fling) | 16.3 ms | **4.2 ms** |
| 1000 snapshots of a screen that did not change | 28.2 ms | **1.6 ms** |

That is a bench. On a device it shows up as the frame times of a program that redraws constantly:
the same emulator, the same server, `btop --update 100` full screen for 30 seconds, read out of
`dumpsys gfxinfo` with a reset either side.

| frame time | 50th | 90th | 99th |
| --- | --- | --- | --- |
| alacritty | 48 ms | 117 ms | 150 ms |
| ghostty | 32 ms | 48 ms | 65 ms |

The tail is what a person calls lag, and it is the tail that moves. Nothing on an emulator meets
16 ms, so the percentiles are the comparison; the jank count says 97% on both and means nothing here.

Three things get it there.

**Parsing** is libghostty's own, and it is the part that scales with how much the remote prints.

**Cells are decoded in Rust.** Reading one through the C API costs a call per field; a cell is really
a packed `u64`, and while the C ABI does not freeze the bit positions, `ghostty_type_json()`
publishes them. This is not taken on trust: at startup the backend builds synthetic cells covering
every content tag and field, checks that `ghostty_cell_get` reads back exactly what was written, and
falls back to the per-field C path if a revision has moved anything (`cargo run -p ghostty-vt-sys
--example manifest` prints what a build actually says). A cell in the default style then skips colour
resolution entirely, and what a style *means* is worked out once per style per row rather than once
per cell.

**Only rows that changed are re-serialised.** libghostty tracks per-row dirty state and accumulates
it until told the frame was drawn, so a snapshot rebuilds just the rows that moved — a TUI redrawing
one status line touches one row out of forty. A frame that has to draw everything anyway skips the
cache and writes straight into the caller's buffer, so scrolling output pays nothing for it. The
cache is dropped whenever anything outside the row flags could have changed the picture: a resize, a
palette change, or libghostty reporting the frame fully dirty, which it does for a scroll, a screen
switch and a selection change. `cached_snapshots_match_full_rebuilds` drives two emulators through
400 arbitrary steps — feeding, scrolling, selecting, resizing, switching screens, changing the
palette — one snapshotting incrementally and one rebuilding every row, and compares after every
step.

The one case libghostty loses is a screen being repainted entirely in SGR, and the cost is in
parsing rather than drawing: fed nothing but cursor positioning it is 1.8x *faster*, and fed nothing
but SGR changes 1.4x slower. That is the two designs showing through. Ghostty interns styles — a
cell is eight bytes holding a 16-bit style id, and every attribute change releases one entry in the
page's style set and adds another — where alacritty stores the attributes in the cell. Cheap cells
and cheap bulk text, dearer SGR.

Nothing here can undo that; it is behind `ghostty_terminal_vt_write`. Two things soften it, both
already upstream: a sequence that re-asserts the style a cell already has returns before touching
the set, which is the common case in the wild, and the interning is what makes a cell eight bytes
and the render state's per-row style array cheap to read. A sequence carrying several parameters
(`ESC [ 1;31 m`) does pay once per parameter that changes the style.

## Checking the two against each other

`cargo run -p term-diff` feeds both backends the same input and reports everywhere they disagree —
cell by cell, plus the cursor, the modes, the scrollback and the text a selection would copy. It
runs as a test too (`cargo test -p term-diff`), which is the regression net for the newer backend.

    cargo run -p term-diff                  # the built-in scripts
    cargo run -p term-diff -- capture.bin   # a captured stream of real terminal output
    cargo run -p term-diff -- --known       # also list the differences we know about

Seventeen scripts — text and wrapping, SGR, colours, cursor movement, erase and edit, scroll
regions, the alternate screen, wide characters, modes, OSC, scrollback, selection, a repainting TUI,
resize, and with the output filter on, prompt marks and kitty and sixel images, which compares the
marks and placements each backend reads back out of its own grid — agree exactly. Six behaviours
differ, and each has a script of its own carrying the reason: tabs
(alacritty keeps U+0009 in the cells a tab skipped, ghostty fills them with spaces), SGR 21 (ghostty
draws the double underline ECMA-48 asks for, alacritty ignores it), ED 2 and IL (alacritty pushes the
displaced lines into scrollback, ghostty does not), the two mouse encodings being set at once, and
selection trimming (ghostty stops at the end of the text, alacritty runs to the end of the row).
Two emulators are not obliged to agree; these are written down rather than papered over.

Replaying captured output from real programs is the point of the file argument, and it is worth
doing before trusting a change: `ls --color` (632 KB), `top`, `htop`, `less`, `vim` and `man` all
come through with the two backends agreeing on every cell, the cursor and the modes.

The harness has found two real bugs so far, one on each side. In the *older* backend, `selection_all`
anchored a line selection and never extended it, so "Select all" copied one line instead of the
buffer. In the newer one, libghostty caps scrollback by bytes as well as by lines and that limit
defaults to 10 KB against a page size of around 400 KB — so it kept roughly one page of history
whatever the line limit said, and a request for ten thousand lines yielded a few hundred. The byte
limit is now removed, leaving the line count the only governor, which is what the app's setting means
and what alacritty does. Both are pinned by tests.

[← back to the README](../README.md)
