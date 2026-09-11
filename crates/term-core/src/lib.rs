//! Backend-agnostic terminal emulator interface.
//!
//! Everything the UI needs is expressed in plain data owned by this crate so the
//! emulator backend (alacritty_terminal today, libghostty-vt tomorrow) can be
//! swapped behind [`Emulator`] without touching the FFI or the app.

pub mod images;
pub mod intercept;
pub mod keys;
pub mod marks;
pub mod palette;
pub mod snapshot;

pub use images::{ImageStore, Mark as ImageMark, PlacedImage};
pub use intercept::{InterceptEvent, InterceptOptions, Interceptor, PromptMark};
pub use marks::PromptMarkAt;
pub use keys::{
    encode_key, encode_mouse, encode_paste, Key, KeyEvent, KeyKind, ModifierKey, Modifiers, MouseButton, MouseEvent,
};
pub use palette::Palette;
pub use snapshot::{
    encode_cell, CellFlags, SnapshotHeader, SnapshotWriter, CELL_BYTES, GENERATION_OFFSET, HEADER_BYTES,
    LINKS_OFFSET,
};

/// Terminal modes that influence input encoding and gesture handling.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct TermModes {
    pub app_cursor: bool,
    pub app_keypad: bool,
    pub bracketed_paste: bool,
    pub alt_screen: bool,
    /// Any mouse reporting mode (click / drag / motion).
    pub mouse_reporting: bool,
    pub mouse_motion: bool,
    pub sgr_mouse: bool,
    pub utf8_mouse: bool,
    /// Scroll wheel on the alt screen should send arrow keys.
    pub alternate_scroll: bool,
    /// Kitty keyboard protocol flags in force at the top of the program's
    /// stack: 1 disambiguate, 2 event types, 4 alternate keys, 8 all keys as
    /// escapes, 16 associated text.
    pub kitty_flags: u8,
    /// xterm modifyOtherKeys level the program asked for (0, 1 or 2).
    pub modify_other_keys: u8,
    /// Send Ctrl+[, Ctrl+I and Ctrl+M as keys of their own even when nothing
    /// has asked for a protocol. Not a mode a program can set: a setting,
    /// carried here because this is what the key encoder is given.
    pub fixterms_ctrl_keys: bool,
}

/// How a selection is started.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SelectionKind {
    /// Character-precise selection.
    Simple,
    /// Expand to the surrounding word.
    Word,
    /// Whole lines.
    Lines,
    /// Rectangular block.
    Block,
}

/// A point in viewport coordinates: `row` 0 is the top visible line.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ViewPoint {
    pub col: u16,
    pub row: u16,
}

/// The cursor's line as far as the cursor; see [`Emulator::cursor_line`].
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct CursorLine {
    /// Everything before the cursor on its line, the rows it wrapped from
    /// included.
    pub before: String,
    /// Nothing is written at the cursor or in the cell after it.
    ///
    /// False when the cursor has been moved back into what was typed, and when
    /// a shell draws its own suggestion after the cursor (fish, and zsh with
    /// autosuggestions): anything drawn at the cursor would land on top of
    /// that. The second cell is what tells "between two words" from "at the
    /// end", and it still leaves room for a right-hand prompt far off to the
    /// side.
    pub at_end: bool,
    /// No row below the cursor's line has anything on it.
    ///
    /// That is what a shell waiting for a command looks like, at the foot of
    /// what it has printed. A full-screen program has its status line, its
    /// `~` rows or its menus under the cursor, which is what tells the two
    /// apart once the screen mode cannot: a program killed without restoring
    /// the screen leaves the shell on the alt screen. Whether the cursor shows
    /// is no help there, because the same program leaves it hidden too.
    pub nothing_below: bool,
    /// Where the cursor is, in viewport rows (outside `0..rows` when the view
    /// is scrolled away from it), and the column the next character goes in,
    /// which is the width itself once a row is full.
    ///
    /// Reported whether or not the cursor is showing: the snapshot calls a
    /// hidden cursor nowhere, and a shell still takes commands under one.
    pub row: i32,
    pub col: u16,
}

/// Events an emulator produces as a side effect of processing output.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum EmulatorEvent {
    /// Bytes the emulator wants written back to the pty (DA responses, cursor reports, ...).
    PtyWrite(Vec<u8>),
    Title(Option<String>),
    Bell,
    /// OSC 52 clipboard write from the remote side.
    ClipboardStore(String),
    /// A desktop notification the program asked for (OSC 9, 99 or 777).
    Notify { title: String, body: String },
    /// A shell told us where it is in the prompt/command cycle (OSC 133).
    Mark(PromptMark),
    /// A shell told us which directory it is in (OSC 7).
    Cwd(String),
    /// A kitty graphics command, payload only.
    KittyGraphics(Vec<u8>),
    /// A sixel image: the DCS parameters and the data after the `q`.
    Sixel { params: Vec<u16>, data: Vec<u8> },
    /// The image store changed; the view should ask for the placements again.
    ImagesChanged,
}

pub trait Emulator: Send {
    /// Process bytes coming from the pty.
    fn feed(&mut self, bytes: &[u8]);

    fn resize(&mut self, cols: u16, rows: u16);
    fn size(&self) -> (u16, u16);

    /// Serialize the visible grid into `out` using the [`snapshot`] format.
    fn snapshot(&self, palette: &Palette, out: &mut Vec<u8>);

    fn modes(&self) -> TermModes;

    /// Cursor position in the visible grid as `(row, col)`, or `None` when it is
    /// hidden or scrolled out of view. Predictive echo needs it to know where a
    /// typed character would land.
    fn cursor_row_col(&self) -> Option<(u16, u16)>;

    /// Scroll the viewport by `delta` lines (positive = towards history).
    fn scroll_display(&mut self, delta: i32);
    fn scroll_to_bottom(&mut self);
    fn display_offset(&self) -> usize;
    fn history_size(&self) -> usize;

    fn selection_start(&mut self, at: ViewPoint, kind: SelectionKind);
    /// Move the end of the selection (or the start if `move_start`).
    fn selection_update(&mut self, at: ViewPoint, move_start: bool);
    fn selection_all(&mut self);
    fn selection_clear(&mut self);
    fn selection_text(&self) -> Option<String>;
    fn has_selection(&self) -> bool;

    /// Text of a single visible row (used for URL / word detection on tap).
    fn row_text(&self, row: u16) -> String;

    /// The same text, appended to a buffer the caller keeps.
    ///
    /// Every visible row is read on every frame that changes, for link and
    /// keyword matching, and the `String` each one handed back was allocated
    /// and thrown away again straight after. A backend that can write the
    /// characters out directly should override this; the default keeps the
    /// old behaviour for one that cannot.
    fn row_text_into(&self, row: u16, out: &mut String) {
        out.push_str(&self.row_text(row));
    }

    /// The line the cursor is on, from where it starts up to the cursor.
    ///
    /// A line in the shell's sense rather than the grid's: a command too long
    /// for the width carries on into the next row, and reading the cursor's row
    /// alone hands back the tail of it. The blanks between the last character
    /// and the cursor are kept, because a prompt ends in one, and without it a
    /// bare prompt reads exactly like a prompt with a word typed after it.
    ///
    /// This default reads the cursor's row alone, which is as much as a backend
    /// that cannot say where its rows wrap is able to offer.
    fn cursor_line(&self) -> Option<CursorLine> {
        let (row, col) = self.cursor_row_col()?;
        let text = self.row_text(row);
        let col = col as usize;
        let mut before: String = text.chars().take(col).collect();
        let short = col.saturating_sub(before.chars().count());
        before.extend(std::iter::repeat(' ').take(short));
        let at_end = text.chars().skip(col).take(2).all(|c| c == ' ');
        let (_, rows) = self.size();
        let nothing_below = (row + 1..rows).all(|r| self.row_text(r).trim().is_empty());
        Some(CursorLine { before, at_end, nothing_below, row: row as i32, col: col as u16 })
    }

    /// Every line, scrollback first then the visible screen, trailing spaces trimmed.
    fn all_lines(&self) -> Vec<String>;
    /// Set the display offset (0 = bottom, history_size() = top).
    fn scroll_to(&mut self, offset: usize);

    /// Drain events produced since the last call.
    fn take_events(&mut self) -> Vec<EmulatorEvent>;

    /// Choose which sequences the output filter claims before the backend sees
    /// them. Everything off makes the filter a `memcpy`.
    fn set_intercept(&mut self, opts: InterceptOptions);

    /// Draw bold text in the bright half of the palette, as the terminals this
    /// convention comes from did.
    fn set_bold_is_bright(&mut self, on: bool);

    /// How many pixels one character cell measures, so an image's pixels can be
    /// turned into the rows and columns it covers.
    fn set_cell_size(&mut self, width: u32, height: u32);

    /// Images visible in the current viewport, back to front.
    ///
    /// Positions are read out of the grid rather than remembered, so this is
    /// where an image has scrolled to, not where it was placed.
    fn images(&self) -> Vec<PlacedImage>;

    /// The shell's prompt marks (OSC 133) still in the buffer, in row order.
    ///
    /// The whole buffer and not only the visible part of it: jumping to the
    /// previous prompt is asking for one that has scrolled off the top, so the
    /// scrollback is where most of the answers are. Rows are counted from the
    /// top visible line all the same, which makes a mark in the history
    /// negative — see [`PromptMarkAt`].
    ///
    /// Positions are read out of the grid rather than remembered, so a mark is
    /// where the text it was printed against has ended up, and marks the grid
    /// has erased are gone.
    fn prompt_marks(&self) -> Vec<PromptMarkAt>;

    /// The RGBA pixels behind an image, while that generation of it is still
    /// held. The renderer asks for these once and caches the bitmap.
    fn image_bytes(&self, id: u32, generation: u32) -> Option<&[u8]>;
}
