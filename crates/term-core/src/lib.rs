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
pub use snapshot::{encode_cell, CellFlags, SnapshotHeader, SnapshotWriter, CELL_BYTES, HEADER_BYTES};

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
