//! Raw bindings to libghostty-vt.
//!
//! Hand-written rather than generated: only the terminal, render-state and
//! selection surfaces are needed here, and hand-writing keeps `libclang` out of
//! the Android build. Every declaration mirrors a header under
//! `include/ghostty/vt/` of the revision pinned in `build.rs`; the header name
//! is noted above each block so the two can be diffed when the pin moves.
//!
//! All enums are `int` in the C API (`GHOSTTY_ENUM_MAX_VALUE = INT_MAX` forces
//! the width), so they are `c_int` here. Sized structs carry a leading `size`
//! field that the caller must set to the struct's own size; use [`sized`].

#![allow(non_camel_case_types)]

use std::ffi::{c_int, c_void};

// ---- types.h ---------------------------------------------------------------

pub type GhosttyResult = c_int;
pub const GHOSTTY_SUCCESS: GhosttyResult = 0;
pub const GHOSTTY_OUT_OF_MEMORY: GhosttyResult = -1;
pub const GHOSTTY_INVALID_VALUE: GhosttyResult = -2;
pub const GHOSTTY_OUT_OF_SPACE: GhosttyResult = -3;
pub const GHOSTTY_NO_VALUE: GhosttyResult = -4;

pub type GhosttyTerminal = *mut c_void;
pub type GhosttyRenderState = *mut c_void;
pub type GhosttyRenderStateRowIterator = *mut c_void;
pub type GhosttyRenderStateRowCells = *mut c_void;

#[repr(C)]
#[derive(Clone, Copy)]
pub struct GhosttyString {
    pub ptr: *const u8,
    pub len: usize,
}

impl Default for GhosttyString {
    fn default() -> Self {
        Self { ptr: std::ptr::null(), len: 0 }
    }
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct GhosttyBuffer {
    pub ptr: *mut u8,
    pub cap: usize,
    pub len: usize,
}

pub const GHOSTTY_FORMATTER_FORMAT_PLAIN: c_int = 0;

/// Fills a sized struct with zeroes and stamps its `size` field, the way
/// `GHOSTTY_INIT_SIZED` does in C. Every sized struct in this module starts
/// with a `usize` size field, which is what makes this sound.
///
/// # Safety
/// `T` must be a `repr(C)` struct whose first field is a `usize` size.
pub unsafe fn sized<T>() -> T {
    let mut v: T = std::mem::zeroed();
    *(&mut v as *mut T as *mut usize) = std::mem::size_of::<T>();
    v
}

// ---- color.h ---------------------------------------------------------------

#[repr(C)]
#[derive(Clone, Copy, Default, Debug, PartialEq, Eq)]
pub struct GhosttyColorRgb {
    pub r: u8,
    pub g: u8,
    pub b: u8,
}

// ---- point.h ---------------------------------------------------------------

pub const GHOSTTY_POINT_TAG_ACTIVE: c_int = 0;
pub const GHOSTTY_POINT_TAG_VIEWPORT: c_int = 1;
pub const GHOSTTY_POINT_TAG_SCREEN: c_int = 2;
pub const GHOSTTY_POINT_TAG_HISTORY: c_int = 3;

#[repr(C)]
#[derive(Clone, Copy, Default, Debug)]
pub struct GhosttyPointCoordinate {
    pub x: u16,
    pub y: u32,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub union GhosttyPointValue {
    pub coordinate: GhosttyPointCoordinate,
    pub _padding: [u64; 2],
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct GhosttyPoint {
    pub tag: c_int,
    pub value: GhosttyPointValue,
}

impl GhosttyPoint {
    pub fn new(tag: c_int, x: u16, y: u32) -> Self {
        Self { tag, value: GhosttyPointValue { coordinate: GhosttyPointCoordinate { x, y } } }
    }
}

// ---- grid_ref.h ------------------------------------------------------------

#[repr(C)]
#[derive(Clone, Copy)]
pub struct GhosttyGridRef {
    pub size: usize,
    pub node: *mut c_void,
    pub x: u16,
    pub y: u16,
}

// ---- style.h ---------------------------------------------------------------

pub const GHOSTTY_STYLE_COLOR_NONE: c_int = 0;
pub const GHOSTTY_STYLE_COLOR_PALETTE: c_int = 1;
pub const GHOSTTY_STYLE_COLOR_RGB: c_int = 2;

#[repr(C)]
#[derive(Clone, Copy)]
pub union GhosttyStyleColorValue {
    pub palette: u8,
    pub rgb: GhosttyColorRgb,
    pub _padding: u64,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct GhosttyStyleColor {
    pub tag: c_int,
    pub value: GhosttyStyleColorValue,
}

/// `underline` holds a `GHOSTTY_SGR_UNDERLINE_*` value; see `sgr.h`.
#[repr(C)]
#[derive(Clone, Copy)]
pub struct GhosttyStyle {
    pub size: usize,
    pub fg_color: GhosttyStyleColor,
    pub bg_color: GhosttyStyleColor,
    pub underline_color: GhosttyStyleColor,
    pub bold: bool,
    pub italic: bool,
    pub faint: bool,
    pub blink: bool,
    pub inverse: bool,
    pub invisible: bool,
    pub strikethrough: bool,
    pub overline: bool,
    pub underline: c_int,
}

// ---- sgr.h (underline styles) ----------------------------------------------

pub const GHOSTTY_SGR_UNDERLINE_NONE: c_int = 0;
pub const GHOSTTY_SGR_UNDERLINE_SINGLE: c_int = 1;
pub const GHOSTTY_SGR_UNDERLINE_DOUBLE: c_int = 2;
pub const GHOSTTY_SGR_UNDERLINE_CURLY: c_int = 3;
pub const GHOSTTY_SGR_UNDERLINE_DOTTED: c_int = 4;
pub const GHOSTTY_SGR_UNDERLINE_DASHED: c_int = 5;

// ---- screen.h --------------------------------------------------------------

pub type GhosttyCell = u64;
pub type GhosttyRow = u64;

/// A borrowed run of raw cell values, one per column of a row.
#[repr(C)]
#[derive(Clone, Copy)]
pub struct GhosttyCellsView {
    pub ptr: *const GhosttyCell,
    pub len: usize,
}

impl Default for GhosttyCellsView {
    fn default() -> Self {
        Self { ptr: std::ptr::null(), len: 0 }
    }
}

pub const GHOSTTY_CELL_CONTENT_CODEPOINT: c_int = 0;
pub const GHOSTTY_CELL_CONTENT_CODEPOINT_GRAPHEME: c_int = 1;
pub const GHOSTTY_CELL_CONTENT_BG_COLOR_PALETTE: c_int = 2;
pub const GHOSTTY_CELL_CONTENT_BG_COLOR_RGB: c_int = 3;

pub const GHOSTTY_CELL_WIDE_NARROW: c_int = 0;
pub const GHOSTTY_CELL_WIDE_WIDE: c_int = 1;
pub const GHOSTTY_CELL_WIDE_SPACER_TAIL: c_int = 2;
pub const GHOSTTY_CELL_WIDE_SPACER_HEAD: c_int = 3;

/// `GhosttyRowData`, for `ghostty_row_get`. May report a false positive, which
/// only costs a wasted style lookup.
pub const GHOSTTY_ROW_DATA_STYLED: c_int = 4;
/// Whether any cell of the row carries a hyperlink; false positives possible,
/// false negatives not, which is what makes it a safe way to skip a row.
pub const GHOSTTY_ROW_DATA_HYPERLINK: c_int = 5;

pub const GHOSTTY_CELL_DATA_CODEPOINT: c_int = 1;
pub const GHOSTTY_CELL_DATA_CONTENT_TAG: c_int = 2;
pub const GHOSTTY_CELL_DATA_WIDE: c_int = 3;
pub const GHOSTTY_CELL_DATA_HAS_TEXT: c_int = 4;
pub const GHOSTTY_CELL_DATA_HAS_STYLING: c_int = 5;
pub const GHOSTTY_CELL_DATA_STYLE_ID: c_int = 6;
pub const GHOSTTY_CELL_DATA_HAS_HYPERLINK: c_int = 7;
pub const GHOSTTY_CELL_DATA_COLOR_PALETTE: c_int = 10;
pub const GHOSTTY_CELL_DATA_COLOR_RGB: c_int = 11;

// ---- terminal.h ------------------------------------------------------------

pub const GHOSTTY_TERMINAL_OPT_USERDATA: c_int = 0;
pub const GHOSTTY_TERMINAL_OPT_WRITE_PTY: c_int = 1;
pub const GHOSTTY_TERMINAL_OPT_BELL: c_int = 2;
pub const GHOSTTY_TERMINAL_OPT_TITLE_CHANGED: c_int = 5;
pub const GHOSTTY_TERMINAL_OPT_DEVICE_ATTRIBUTES: c_int = 8;
pub const GHOSTTY_TERMINAL_OPT_SELECTION: c_int = 21;
pub const GHOSTTY_TERMINAL_OPT_CLIPBOARD_WRITE: c_int = 26;
pub const GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_BYTES: c_int = 27;
pub const GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_LINES: c_int = 28;
pub const GHOSTTY_TERMINAL_OPT_CLIPBOARD_READ: c_int = 38;

pub const GHOSTTY_TERMINAL_DATA_CURSOR_X: c_int = 3;
pub const GHOSTTY_TERMINAL_DATA_CURSOR_Y: c_int = 4;
pub const GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN: c_int = 6;
pub const GHOSTTY_TERMINAL_DATA_CURSOR_VISIBLE: c_int = 7;
/// `GhosttyKittyKeyFlags`, a `uint8_t` laid out as the protocol numbers its
/// flags: 1 disambiguate, 2 event types, 4 alternate keys, 8 all keys as
/// escapes, 16 associated text.
pub const GHOSTTY_TERMINAL_DATA_KITTY_KEYBOARD_FLAGS: c_int = 8;
pub const GHOSTTY_TERMINAL_DATA_SCROLLBAR: c_int = 9;
pub const GHOSTTY_TERMINAL_DATA_CURSOR_STYLE: c_int = 10;
pub const GHOSTTY_TERMINAL_DATA_MOUSE_TRACKING: c_int = 11;
pub const GHOSTTY_TERMINAL_DATA_TITLE: c_int = 12;
pub const GHOSTTY_TERMINAL_DATA_TOTAL_ROWS: c_int = 14;
pub const GHOSTTY_TERMINAL_DATA_SCROLLBACK_ROWS: c_int = 15;
pub const GHOSTTY_TERMINAL_DATA_SELECTION: c_int = 31;
pub const GHOSTTY_TERMINAL_DATA_MODE: c_int = 37;

pub const GHOSTTY_SCROLL_VIEWPORT_TOP: c_int = 0;
pub const GHOSTTY_SCROLL_VIEWPORT_BOTTOM: c_int = 1;
pub const GHOSTTY_SCROLL_VIEWPORT_DELTA: c_int = 2;
pub const GHOSTTY_SCROLL_VIEWPORT_ROW: c_int = 3;

#[repr(C)]
#[derive(Clone, Copy)]
pub union GhosttyTerminalScrollViewportValue {
    pub delta: isize,
    pub row: usize,
    pub _padding: [u64; 2],
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct GhosttyTerminalScrollViewport {
    pub tag: c_int,
    pub value: GhosttyTerminalScrollViewportValue,
}

#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct GhosttyTerminalScrollbar {
    pub total: u64,
    pub offset: u64,
    pub len: u64,
}

pub const GHOSTTY_TERMINAL_SCREEN_PRIMARY: c_int = 0;
pub const GHOSTTY_TERMINAL_SCREEN_ALTERNATE: c_int = 1;

pub const GHOSTTY_TERMINAL_CURSOR_STYLE_BAR: c_int = 0;
pub const GHOSTTY_TERMINAL_CURSOR_STYLE_BLOCK: c_int = 1;
pub const GHOSTTY_TERMINAL_CURSOR_STYLE_UNDERLINE: c_int = 2;
pub const GHOSTTY_TERMINAL_CURSOR_STYLE_BLOCK_HOLLOW: c_int = 3;

/// `GhosttyMode`, from `modes.h`: the mode number in the low 15 bits, bit 15
/// set for an ANSI (rather than DEC private) mode.
pub type GhosttyMode = u16;

pub const fn ghostty_mode_new(value: u16, ansi: bool) -> GhosttyMode {
    (value & 0x7FFF) | ((ansi as u16) << 15)
}

pub const GHOSTTY_MODE_DECCKM: GhosttyMode = ghostty_mode_new(1, false);
pub const GHOSTTY_MODE_KEYPAD_KEYS: GhosttyMode = ghostty_mode_new(66, false);
pub const GHOSTTY_MODE_X10_MOUSE: GhosttyMode = ghostty_mode_new(9, false);
pub const GHOSTTY_MODE_NORMAL_MOUSE: GhosttyMode = ghostty_mode_new(1000, false);
pub const GHOSTTY_MODE_BUTTON_MOUSE: GhosttyMode = ghostty_mode_new(1002, false);
pub const GHOSTTY_MODE_ANY_MOUSE: GhosttyMode = ghostty_mode_new(1003, false);
pub const GHOSTTY_MODE_UTF8_MOUSE: GhosttyMode = ghostty_mode_new(1005, false);
pub const GHOSTTY_MODE_SGR_MOUSE: GhosttyMode = ghostty_mode_new(1006, false);
pub const GHOSTTY_MODE_ALT_SCROLL: GhosttyMode = ghostty_mode_new(1007, false);
pub const GHOSTTY_MODE_ALT_SCREEN_LEGACY: GhosttyMode = ghostty_mode_new(47, false);
pub const GHOSTTY_MODE_ALT_SCREEN: GhosttyMode = ghostty_mode_new(1047, false);
pub const GHOSTTY_MODE_ALT_SCREEN_SAVE: GhosttyMode = ghostty_mode_new(1049, false);
pub const GHOSTTY_MODE_BRACKETED_PASTE: GhosttyMode = ghostty_mode_new(2004, false);

#[repr(C)]
#[derive(Clone, Copy)]
pub struct GhosttyTerminalModeConfig {
    pub mode: GhosttyMode,
    pub value: bool,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct GhosttyDeviceAttributesPrimary {
    pub conformance_level: u16,
    pub features: [u16; 64],
    pub num_features: usize,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct GhosttyDeviceAttributesSecondary {
    pub device_type: u16,
    pub firmware_version: u16,
    pub rom_cartridge: u16,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct GhosttyDeviceAttributesTertiary {
    pub unit_id: u32,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct GhosttyDeviceAttributes {
    pub primary: GhosttyDeviceAttributesPrimary,
    pub secondary: GhosttyDeviceAttributesSecondary,
    pub tertiary: GhosttyDeviceAttributesTertiary,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct GhosttyClipboardContent {
    pub mime: GhosttyString,
    pub data: GhosttyString,
}

pub const GHOSTTY_CLIPBOARD_WRITE_RESULT_SUCCESS: c_int = 0;
pub const GHOSTTY_CLIPBOARD_WRITE_RESULT_DENIED: c_int = 1;
pub const GHOSTTY_CLIPBOARD_READ_RESULT_DENIED: c_int = 1;

#[repr(C)]
#[derive(Clone, Copy)]
pub struct GhosttyClipboardWriteReply {
    pub size: usize,
    pub result: c_int,
    pub remember: bool,
}

#[repr(C)]
pub struct GhosttyClipboardWrite {
    pub size: usize,
    pub location: c_int,
    pub contents: *const GhosttyClipboardContent,
    pub contents_len: usize,
    pub name: GhosttyString,
    pub granted: bool,
    pub can_remember: bool,
    pub ctx: *const c_void,
    pub reply: Option<
        unsafe extern "C" fn(*const GhosttyClipboardWrite, *const GhosttyClipboardWriteReply),
    >,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct GhosttyClipboardReadReply {
    pub size: usize,
    pub result: c_int,
    pub contents: *const GhosttyClipboardContent,
    pub contents_len: usize,
    pub available: *const GhosttyString,
    pub available_len: usize,
    pub remember: bool,
}

#[repr(C)]
pub struct GhosttyClipboardRead {
    _opaque: [u8; 0],
}

pub type GhosttyTerminalWritePtyFn =
    unsafe extern "C" fn(GhosttyTerminal, *mut c_void, *const u8, usize);
pub type GhosttyTerminalBellFn = unsafe extern "C" fn(GhosttyTerminal, *mut c_void);
pub type GhosttyTerminalTitleChangedFn = unsafe extern "C" fn(GhosttyTerminal, *mut c_void);
pub type GhosttyTerminalDeviceAttributesFn =
    unsafe extern "C" fn(GhosttyTerminal, *mut c_void, *mut GhosttyDeviceAttributes) -> bool;
pub type GhosttyTerminalClipboardWriteFn =
    unsafe extern "C" fn(GhosttyTerminal, *mut c_void, *const GhosttyClipboardWrite);
pub type GhosttyTerminalClipboardReadFn =
    unsafe extern "C" fn(GhosttyTerminal, *mut c_void, *const GhosttyClipboardRead);

// ---- selection.h -----------------------------------------------------------

#[repr(C)]
#[derive(Clone, Copy)]
pub struct GhosttySelection {
    pub size: usize,
    pub start: GhosttyGridRef,
    pub end: GhosttyGridRef,
    pub rectangle: bool,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct GhosttyTerminalSelectWordOptions {
    pub size: usize,
    pub r#ref: GhosttyGridRef,
    pub boundary_codepoints: *const u32,
    pub boundary_codepoints_len: usize,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct GhosttyTerminalSelectLineOptions {
    pub size: usize,
    pub r#ref: GhosttyGridRef,
    pub whitespace: *const u32,
    pub whitespace_len: usize,
    pub semantic_prompt_boundary: bool,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct GhosttyTerminalSelectionFormatOptions {
    pub size: usize,
    pub emit: c_int,
    pub unwrap: bool,
    pub trim: bool,
    pub selection: *const GhosttySelection,
}

pub const GHOSTTY_SELECTION_ORDER_FORWARD: c_int = 0;

// ---- render.h --------------------------------------------------------------

pub const GHOSTTY_RENDER_STATE_DATA_COLS: c_int = 1;
pub const GHOSTTY_RENDER_STATE_DATA_ROWS: c_int = 2;
pub const GHOSTTY_RENDER_STATE_DATA_DIRTY: c_int = 3;
pub const GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR: c_int = 4;

pub const GHOSTTY_RENDER_STATE_DIRTY_FALSE: c_int = 0;
pub const GHOSTTY_RENDER_STATE_DIRTY_PARTIAL: c_int = 1;
pub const GHOSTTY_RENDER_STATE_DIRTY_FULL: c_int = 2;
pub const GHOSTTY_RENDER_STATE_DATA_CURSOR: c_int = 18;

pub const GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BAR: c_int = 0;
pub const GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BLOCK: c_int = 1;
pub const GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_UNDERLINE: c_int = 2;
pub const GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BLOCK_HOLLOW: c_int = 3;

pub const GHOSTTY_RENDER_STATE_ROW_DATA_CELLS: c_int = 3;
pub const GHOSTTY_RENDER_STATE_ROW_DATA_SELECTION: c_int = 4;
pub const GHOSTTY_RENDER_STATE_ROW_DATA_CELLS_RAW: c_int = 5;
pub const GHOSTTY_RENDER_STATE_ROW_DATA_DIRTY: c_int = 1;
pub const GHOSTTY_RENDER_STATE_ROW_DATA_RAW: c_int = 2;

pub const GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_RAW: c_int = 1;
pub const GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE: c_int = 2;

#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct GhosttyRenderStateRowSelection {
    pub size: usize,
    pub start_x: u16,
    pub end_x: u16,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct GhosttyRenderStateCursor {
    pub size: usize,
    pub viewport_has_value: bool,
    pub viewport_x: u16,
    pub viewport_y: u16,
    pub wide_tail: bool,
    pub visible: bool,
    pub blinking: bool,
    pub password_input: bool,
    pub visual_style: c_int,
}

// ---- functions -------------------------------------------------------------

extern "C" {
    // terminal.h
    pub fn ghostty_terminal_new(
        allocator: *const c_void,
        terminal: *mut GhosttyTerminal,
        cols: u16,
        rows: u16,
    ) -> GhosttyResult;
    pub fn ghostty_terminal_free(terminal: GhosttyTerminal);
    pub fn ghostty_terminal_resize(
        terminal: GhosttyTerminal,
        cols: u16,
        rows: u16,
        cell_width_px: u32,
        cell_height_px: u32,
    ) -> GhosttyResult;
    pub fn ghostty_terminal_set(
        terminal: GhosttyTerminal,
        option: c_int,
        value: *const c_void,
    ) -> GhosttyResult;
    pub fn ghostty_terminal_vt_write(terminal: GhosttyTerminal, data: *const u8, len: usize);
    pub fn ghostty_terminal_scroll_viewport(
        terminal: GhosttyTerminal,
        behavior: GhosttyTerminalScrollViewport,
    );
    pub fn ghostty_terminal_get(
        terminal: GhosttyTerminal,
        data: c_int,
        out: *mut c_void,
    ) -> GhosttyResult;
    pub fn ghostty_terminal_grid_ref(
        terminal: GhosttyTerminal,
        point: GhosttyPoint,
        out_ref: *mut GhosttyGridRef,
    ) -> GhosttyResult;

    // selection.h
    pub fn ghostty_terminal_select_word(
        terminal: GhosttyTerminal,
        options: *const GhosttyTerminalSelectWordOptions,
        out_selection: *mut GhosttySelection,
    ) -> GhosttyResult;
    pub fn ghostty_terminal_select_line(
        terminal: GhosttyTerminal,
        options: *const GhosttyTerminalSelectLineOptions,
        out_selection: *mut GhosttySelection,
    ) -> GhosttyResult;
    pub fn ghostty_terminal_select_all(
        terminal: GhosttyTerminal,
        out_selection: *mut GhosttySelection,
    ) -> GhosttyResult;
    pub fn ghostty_terminal_selection_format_buf(
        terminal: GhosttyTerminal,
        options: GhosttyTerminalSelectionFormatOptions,
        buf: *mut u8,
        buf_len: usize,
        out_written: *mut usize,
    ) -> GhosttyResult;
    pub fn ghostty_terminal_selection_ordered(
        terminal: GhosttyTerminal,
        selection: *const GhosttySelection,
        desired: c_int,
        out_selection: *mut GhosttySelection,
    ) -> GhosttyResult;
    pub fn ghostty_terminal_point_from_grid_ref(
        terminal: GhosttyTerminal,
        r#ref: *const GhosttyGridRef,
        tag: c_int,
        out: *mut GhosttyPointCoordinate,
    ) -> GhosttyResult;

    // grid_ref.h
    pub fn ghostty_grid_ref_style(
        r#ref: *const GhosttyGridRef,
        out_style: *mut GhosttyStyle,
    ) -> GhosttyResult;
    pub fn ghostty_grid_ref_cell(
        r#ref: *const GhosttyGridRef,
        out_cell: *mut GhosttyCell,
    ) -> GhosttyResult;
    pub fn ghostty_grid_ref_row(r#ref: *const GhosttyGridRef, out_row: *mut GhosttyRow) -> GhosttyResult;
    /// The URI of the cell's hyperlink. `out_len` is 0 when there is none;
    /// `GHOSTTY_OUT_OF_SPACE` says how many bytes a retry needs.
    pub fn ghostty_grid_ref_hyperlink_uri(
        r#ref: *const GhosttyGridRef,
        buf: *mut u8,
        buf_len: usize,
        out_len: *mut usize,
    ) -> GhosttyResult;

    // screen.h
    pub fn ghostty_cell_get(cell: GhosttyCell, data: c_int, out: *mut c_void) -> GhosttyResult;
    pub fn ghostty_row_get(row: GhosttyRow, data: c_int, out: *mut c_void) -> GhosttyResult;
    /// The ABI type manifest: sizes, offsets and the packed bit layouts that
    /// the headers deliberately do not freeze. Valid for the process lifetime.
    pub fn ghostty_type_json() -> *const std::ffi::c_char;
    pub fn ghostty_cell_get_multi(
        cell: GhosttyCell,
        count: usize,
        keys: *const c_int,
        values: *mut *mut c_void,
        out_written: *mut usize,
    ) -> GhosttyResult;

    // render.h
    pub fn ghostty_render_state_new(
        allocator: *const c_void,
        out_state: *mut GhosttyRenderState,
    ) -> GhosttyResult;
    pub fn ghostty_render_state_free(state: GhosttyRenderState);
    pub fn ghostty_render_state_update(
        state: GhosttyRenderState,
        terminal: GhosttyTerminal,
    ) -> GhosttyResult;
    /// Clears the global and per-row dirty state. Both accumulate across
    /// `update` calls until this is called, so a caller that only redraws dirty
    /// rows may update as often as it likes and clean once per frame it drew.
    pub fn ghostty_render_state_clean(state: GhosttyRenderState) -> GhosttyResult;
    pub fn ghostty_render_state_get(
        state: GhosttyRenderState,
        data: c_int,
        out: *mut c_void,
    ) -> GhosttyResult;
    pub fn ghostty_render_state_row_iterator_new(
        allocator: *const c_void,
        out_iterator: *mut GhosttyRenderStateRowIterator,
    ) -> GhosttyResult;
    pub fn ghostty_render_state_row_iterator_free(iterator: GhosttyRenderStateRowIterator);
    pub fn ghostty_render_state_row_iterator_next(iterator: GhosttyRenderStateRowIterator) -> bool;
    pub fn ghostty_render_state_row_get(
        iterator: GhosttyRenderStateRowIterator,
        data: c_int,
        out: *mut c_void,
    ) -> GhosttyResult;
    pub fn ghostty_render_state_row_cells_new(
        allocator: *const c_void,
        out_cells: *mut GhosttyRenderStateRowCells,
    ) -> GhosttyResult;
    pub fn ghostty_render_state_row_cells_free(cells: GhosttyRenderStateRowCells);
    pub fn ghostty_render_state_row_cells_next(cells: GhosttyRenderStateRowCells) -> bool;
    pub fn ghostty_render_state_row_cells_select(
        cells: GhosttyRenderStateRowCells,
        x: u16,
    ) -> GhosttyResult;
    pub fn ghostty_render_state_row_cells_get(
        cells: GhosttyRenderStateRowCells,
        data: c_int,
        out: *mut c_void,
    ) -> GhosttyResult;
}
