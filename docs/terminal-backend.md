# The terminal backend

The two VT engines, how they compare, and how they're checked against each other.

- [The two engines](#the-two-engines)
- [Performance](#performance)
- [Checking the two against each other](#checking-the-two-against-each-other)

## The two engines

The engine is chosen at compile time:

| | alacritty (default) | ghostty |
| --- | --- | --- |
| Library | `alacritty_terminal` | libghostty-vt, from [Ghostty](https://github.com/ghostty-org/ghostty) |
| `build-apk.sh` | (nothing) | `--term ghostty` |
| Gradle | (nothing) | `-PtermBackend=ghostty` |
| cargo | (nothing) | `--features ghostty` |
| Needs | nothing extra | Zig 0.16+ on `PATH` |

Both implement `term_core::Emulator`, so the snapshot format, FFI, Kotlin renderer and color schemes
are the same. A ghostty build leaves alacritty out.

### Building libghostty-vt

`crates/ghostty-vt-sys/build.rs` fetches Ghostty at a pinned revision into `target/ghostty-vt/` and
runs `zig build` once per ABI. Later builds reuse both.

- **Only what's used is built.** Key encoding, search, image decoding and state serialisation are
  done on our side, so they're left out (`FEATURES` in the build script, or `GHOSTTY_VT_FEATURES`).
  Calling something that was left out fails at link time, not at runtime.
- **`GHOSTTY_VT_LIB_DIR`** links a prebuilt `libghostty-vt.a`; **`GHOSTTY_SRC`** builds from an
  existing checkout.
- **Size on arm64:** +808 KB installed (`libflintterm.so` 7.06 → 7.89 MB) and +279 KB in the APK
  (7.43 → 7.72 MB). All of libghostty-vt plus both backends would have been +1.50 MB and +650 KB.

### What works the same on both

Notifications (OSC 9/99/777), the working directory (OSC 7), prompt marks (OSC 133), kitty and sixel
images and the kitty keyboard protocol are all handled by `term_core::intercept` before the engine
sees the bytes. Images and marks are placed by writing a private OSC 8 hyperlink into the grid and
reading it back, so they scroll, reflow and get erased with the text on both engines.

Three differences underneath:

- **Prompt marks.** libghostty's grid can't be edited from outside, so the mark's hyperlink is
  closed as soon as the first cell after it is printed. That means feeding the bytes between a mark
  and its prompt one at a time.
- **Kitty keyboard protocol.** libghostty can't switch it off, so when it's off the interceptor
  stays in the stream to swallow the queries (`Interceptor::set_keyboard_gate`). The flags a program
  pushed are read from `GHOSTTY_TERMINAL_DATA_KITTY_KEYBOARD_FLAGS`.
- **`clear`.** `ESC [ 2 J` pushes the screen into scrollback on alacritty but erases it in place on
  libghostty, so on ghostty a `clear` also removes the prompt marks that were on screen.

## Performance

### Benchmark

`cargo run -p term-ghostty --release --example vtbench`, best of five, x86_64, 120x40:

| Parsing | alacritty | ghostty |
| --- | --- | --- |
| 1.2 MB plain text | 26.5 ms | **2.4 ms** |
| 1.1 MB CJK | 23.5 ms | **3.6 ms** |
| A build log (6 recurring styles) | 26.9 ms | **5.9 ms** |
| A different style per word | 10.7 ms | **9.4 ms** |
| 300 alt-screen repaints | **28.9 ms** | 31.9 ms |

| Parse + snapshot per frame (what the app does) | alacritty | ghostty |
| --- | --- | --- |
| 1000 frames of scrolling output | 29.8 ms | **8.3 ms** |
| 1000 frames of a TUI status line redrawing | 28.5 ms | **2.8 ms** |
| 200 frames of a TUI repainting the whole screen | **18.3 ms** | 19.9 ms |
| 500 scroll + snapshot (a fling) | 16.3 ms | **4.2 ms** |
| 1000 snapshots of an unchanged screen | 28.2 ms | **1.6 ms** |

### On a device

`btop --update 100` full screen for 30 seconds on the emulator, same server, frame times from
`dumpsys gfxinfo`:

| Frame time | 50th | 90th | 99th |
| --- | --- | --- | --- |
| alacritty | 48 ms | 117 ms | 150 ms |
| ghostty | 32 ms | 48 ms | 65 ms |

The slow frames are what feels like lag, and that's where the difference is. Nothing on an emulator
hits 16 ms, so compare the percentiles; the jank count is 97% on both and means nothing here.

### Where the speed comes from

- **Parsing** is libghostty's own, and it's the part that grows with how much the server prints.
- **Cells are decoded in Rust.** A cell is a packed `u64`, which is much cheaper to read directly
  than one C call per field. The bit layout isn't a stable ABI, but `ghostty_type_json()` publishes
  it. At startup the backend writes test cells covering every field, checks they read back exactly,
  and falls back to the C calls if anything moved (`cargo run -p ghostty-vt-sys --example manifest`
  prints the layout). Cells in the default style skip color resolution, and each style is resolved
  once per row, not per cell.
- **Only changed rows are rebuilt.** libghostty tracks which rows changed since the last frame, so a
  TUI redrawing one status line rebuilds one row out of forty. The cache is dropped on a resize, a
  palette change, or when libghostty marks the whole frame dirty (scrolls, screen switches,
  selection changes). `cached_snapshots_match_full_rebuilds` runs 400 random steps on two emulators,
  one cached and one not, and compares them after every step.

### Where ghostty is slower

A screen repainted entirely with SGR (color and style) changes. Fed only cursor movement, libghostty
is 1.8x faster; fed only SGR, it's 1.4x slower. Ghostty stores a 16-bit style id per cell and
updates a shared style table on each change, where alacritty stores attributes in every cell: cheap
cells and text, more expensive style changes.

That cost is inside `ghostty_terminal_vt_write`, so we can't fix it. It's softened upstream: an SGR
that sets the style a cell already has skips the table, which is the common case. A sequence with
several parameters (`ESC [ 1;31 m`) pays once per parameter that changes the style.

## Checking the two against each other

`term-diff` feeds both engines the same input and reports every difference: cells, cursor, modes,
scrollback, and the text a selection would copy.

```sh
cargo run -p term-diff                  # the built-in scripts
cargo run -p term-diff -- capture.bin   # a captured stream of real terminal output
cargo run -p term-diff -- --known       # also list the known differences
cargo test -p term-diff                 # the same, as a regression test
```

**Seventeen scripts agree exactly**: text and wrapping, SGR, colors, cursor movement, erase and
edit, scroll regions, the alternate screen, wide characters, modes, OSC, scrollback, selection, a
repainting TUI, resize, and (with the interceptor on) prompt marks and kitty and sixel images.

**Six known differences**, each with its own script and reason:

| Behaviour | alacritty | ghostty |
| --- | --- | --- |
| Tabs | keeps U+0009 in the skipped cells | fills them with spaces |
| SGR 21 | ignored | double underline, as ECMA-48 says |
| ED 2 | pushes lines into scrollback | doesn't |
| IL | pushes lines into scrollback | doesn't |
| Mouse modes 1005 and 1006 both set | setting one clears the other | keeps both, as xterm does |
| Selection trimming | runs to the end of the row | stops at the end of the text |

**Real captures** of `ls --color` (632 KB), `top`, `htop`, `less`, `vim` and `man` agree on every
cell, the cursor and the modes. Replay one before trusting a change.

**Bugs found so far**, both now covered by tests:

- **alacritty backend:** Select all copied one line instead of the whole buffer, because
  `selection_all` never extended the selection.
- **ghostty backend:** scrollback kept only about a page of history. libghostty also limits it by
  bytes, defaulting to 10 KB against ~400 KB pages. The byte limit is now removed, so the line count
  setting is the only limit, as on alacritty.

[← back to the README](../README.md)
