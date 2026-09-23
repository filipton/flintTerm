//! The calls made on every frame, and on every key and finger movement, taken
//! the short way across.
//!
//! Everything else reaches the core through uniffi, which runs over JNA, and
//! JNA builds its call-status and return structures by reflection, backed by
//! native memory, on every call. A snapshot was several trips across a frame
//! (the handles are cloned first, and the span comes back in a buffer that has
//! to be freed again), and profiling put JNA and the garbage it left at a few
//! percent of the app while output streams. This is plain JNI with nothing but
//! numbers going either way.
//!
//! The handles are extra strong references, taken once through uniffi and
//! given back here, so a session torn down on another thread cannot free what
//! a frame is still reading.

use std::ffi::c_void;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::Arc;

use crate::session::{KeyCode, KeyEventKind, KeyPress, ModifierKey, Session, SnapshotBuffer};

#[uniffi::export]
impl Session {
    /// A strong reference for `FrameBridge.snapshotInto`; give it back with
    /// `FrameBridge.releaseSession`.
    pub fn frame_handle(self: Arc<Self>) -> u64 {
        Arc::into_raw(self) as usize as u64
    }

    /// A descriptor that turns readable whenever the grid changes, instead of
    /// the `on_damage` callback; -1 if it cannot be had.
    ///
    /// That callback is the other thing that happens on every frame, and it
    /// came across JNA the other way: a status structure read and written back
    /// by reflection, and the thread attached to the VM, to say one bit. An
    /// eventfd the main looper watches wakes the thread that draws directly.
    pub fn damage_fd(&self) -> i32 {
        self.open_damage_fd()
    }
}

#[uniffi::export]
impl SnapshotBuffer {
    /// A strong reference for `FrameBridge`; give it back with
    /// `FrameBridge.releaseBuffer`.
    pub fn frame_handle(self: Arc<Self>) -> u64 {
        Arc::into_raw(self) as usize as u64
    }
}

/// Bit 0 of `flags`: find the links too, so the header can say if they moved.
const WANT_LINKS: i32 = 1;
/// Bit 1 of `flags`: paint the user's colour rules into the cells.
const WANT_HIGHLIGHTS: i32 = 2;

/// Where the session's extra header fields sit; see `term_core::snapshot`.
#[uniffi::export]
pub fn snapshot_images_offset() -> u32 {
    term_core::snapshot::IMAGES_OFFSET as u32
}

#[uniffi::export]
pub fn snapshot_predictions_offset() -> u32 {
    term_core::snapshot::PREDICTIONS_OFFSET as u32
}

#[uniffi::export]
pub fn snapshot_predictions_count_offset() -> u32 {
    term_core::snapshot::PREDICTIONS_COUNT_OFFSET as u32
}

#[uniffi::export]
pub fn snapshot_kitty_flags_offset() -> u32 {
    term_core::snapshot::KITTY_FLAGS_OFFSET as u32
}

#[uniffi::export]
pub fn snapshot_rows_offset() -> u32 {
    term_core::snapshot::ROWS_OFFSET as u32
}

#[uniffi::export]
pub fn snapshot_row_entry_bytes() -> u32 {
    term_core::rows::ROW_ENTRY_BYTES as u32
}

/// Fills the buffer from the session and returns its length, or -1.
#[no_mangle]
pub extern "system" fn Java_dev_flint_term_terminal_FrameBridge_snapshotInto(
    _env: *mut c_void,
    _class: *mut c_void,
    session: i64,
    buffer: i64,
    flags: i32,
) -> i64 {
    if session == 0 || buffer == 0 {
        return -1;
    }
    catch_unwind(AssertUnwindSafe(|| {
        // SAFETY: both came from `frame_handle` and have not been released;
        // the strong reference each holds keeps it alive through this call.
        let session = unsafe { &*(session as usize as *const Session) };
        let buffer = unsafe { &*(buffer as usize as *const SnapshotBuffer) };
        session.fill_snapshot(buffer, flags & WANT_LINKS != 0, flags & WANT_HIGHLIGHTS != 0).len as i64
    }))
    .unwrap_or(-1)
}

/// Bit 0 of `flags` in `resetGlyphs`: lay out each row's runs, not only its hash.
const LAYOUT_RUNS: i32 = 1;
/// Bit 1: private-use codepoints go to the icon font's slot.
const LAYOUT_NERD: i32 = 2;

/// New metrics or a new font: every glyph the buffer was told about is
/// forgotten, and the next packing lays its runs out again.
#[no_mangle]
pub extern "system" fn Java_dev_flint_term_terminal_FrameBridge_resetGlyphs(
    _env: *mut c_void,
    _class: *mut c_void,
    buffer: i64,
    flags: i32,
    cell_w: f32,
    baseline: f32,
) {
    if buffer == 0 {
        return;
    }
    let _ = catch_unwind(AssertUnwindSafe(|| {
        // SAFETY: as in `snapshotInto`.
        let buffer = unsafe { &*(buffer as usize as *const SnapshotBuffer) };
        buffer.with_layout(|l| l.reset(flags & LAYOUT_RUNS != 0, flags & LAYOUT_NERD != 0, cell_w, baseline));
    }));
}

/// The glyph numbers of printable ASCII after the space in one face, all out
/// of the renderer's font number `font`: `ids` is the address of that many
/// i32s, or 0 when the face cannot be drawn by number at all.
///
/// An address rather than an array, because an array would need the JNI
/// environment to read, and nothing else here does; the renderer keeps the
/// memory alive for the length of the call.
#[no_mangle]
pub extern "system" fn Java_dev_flint_term_terminal_FrameBridge_setGlyphs(
    _env: *mut c_void,
    _class: *mut c_void,
    buffer: i64,
    slot: i32,
    font: i32,
    ids: i64,
) {
    if buffer == 0 || slot < 0 {
        return;
    }
    let _ = catch_unwind(AssertUnwindSafe(|| {
        // SAFETY: as in `snapshotInto`.
        let buffer = unsafe { &*(buffer as usize as *const SnapshotBuffer) };
        let table = if ids != 0 && font >= 0 {
            // SAFETY: the caller hands over ASCII_GLYPHS i32s at `ids`, alive
            // until this returns; read unaligned, as nothing promises more.
            let ids = unsafe { std::ptr::read_unaligned(ids as usize as *const [i32; term_core::rows::ASCII_GLYPHS]) };
            Some((font as u32, ids))
        } else {
            None
        };
        buffer.with_layout(|l| l.set_ascii(slot as usize, table.as_ref().map(|(f, ids)| (*f, ids))));
    }));
}

/// What one codepoint shaped to in one face: glyph `glyph` of font `font`,
/// or a negative glyph when it is not a single glyph and stays text.
#[no_mangle]
pub extern "system" fn Java_dev_flint_term_terminal_FrameBridge_learnGlyph(
    _env: *mut c_void,
    _class: *mut c_void,
    buffer: i64,
    slot: i32,
    cp: i32,
    font: i32,
    glyph: i32,
) {
    if buffer == 0 || slot < 0 || cp < 0 {
        return;
    }
    let _ = catch_unwind(AssertUnwindSafe(|| {
        // SAFETY: as in `snapshotInto`.
        let buffer = unsafe { &*(buffer as usize as *const SnapshotBuffer) };
        let found = (glyph >= 0 && font >= 0).then_some((font as u32, glyph));
        buffer.with_layout(|l| l.learn(slot as usize, cp as u32, found));
    }));
}

/// A scroll gesture; see [`Session::scroll`]. Sent for every line a finger
/// or a fling moves, which is dozens of calls a second.
#[no_mangle]
pub extern "system" fn Java_dev_flint_term_terminal_FrameBridge_scroll(
    _env: *mut c_void,
    _class: *mut c_void,
    session: i64,
    col: i32,
    row: i32,
    lines: i32,
) {
    if session == 0 {
        return;
    }
    let _ = catch_unwind(AssertUnwindSafe(|| {
        // SAFETY: as in `snapshotInto`.
        let session = unsafe { &*(session as usize as *const Session) };
        session.scroll(clamp_u16(col), clamp_u16(row), lines);
    }));
}

/// A selection end dragged to a cell; see [`Session::select_update`].
#[no_mangle]
pub extern "system" fn Java_dev_flint_term_terminal_FrameBridge_selectUpdate(
    _env: *mut c_void,
    _class: *mut c_void,
    session: i64,
    col: i32,
    row: i32,
    move_start: u8,
) {
    if session == 0 {
        return;
    }
    let _ = catch_unwind(AssertUnwindSafe(|| {
        // SAFETY: as in `snapshotInto`.
        let session = unsafe { &*(session as usize as *const Session) };
        session.select_update(clamp_u16(col), clamp_u16(row), move_start != 0);
    }));
}

/// A key, packed into numbers by `FrameBridge.sendKey`; see [`key_press`].
///
/// Typing is the other thing that happens constantly, and each key through
/// uniffi built its record on the far side and read it back on this one.
#[no_mangle]
pub extern "system" fn Java_dev_flint_term_terminal_FrameBridge_sendKey(
    _env: *mut c_void,
    _class: *mut c_void,
    session: i64,
    key: i32,
    arg: i32,
    mods: i32,
    kind: i32,
) {
    if session == 0 {
        return;
    }
    let Some(press) = key_press(key, arg, mods, kind) else {
        return;
    };
    let _ = catch_unwind(AssertUnwindSafe(|| {
        // SAFETY: as in `snapshotInto`.
        let session = unsafe { &*(session as usize as *const Session) };
        session.send_key(press);
    }));
}

/// Typed text, already UTF-8 in `len` bytes at `addr`; see
/// [`Session::send_text`], which this is the short way round to.
///
/// An address rather than a string or an array, for the same reason as in
/// `setGlyphs`: the keyboard's text is encoded once into memory the caller
/// keeps, and nothing about a `jstring` has to be looked at here.
#[no_mangle]
pub extern "system" fn Java_dev_flint_term_terminal_FrameBridge_sendBytes(
    _env: *mut c_void,
    _class: *mut c_void,
    session: i64,
    addr: i64,
    len: i32,
) {
    if session == 0 || addr == 0 || len <= 0 {
        return;
    }
    let _ = catch_unwind(AssertUnwindSafe(|| {
        // SAFETY: as in `snapshotInto`; the caller keeps `len` bytes alive at
        // `addr` until this returns.
        let session = unsafe { &*(session as usize as *const Session) };
        let bytes = unsafe { std::slice::from_raw_parts(addr as usize as *const u8, len as usize) };
        session.send_typed(bytes.to_vec());
    }));
}

fn clamp_u16(v: i32) -> u16 {
    v.clamp(0, u16::MAX as i32) as u16
}

/// Modifier bits of `mods` in [`key_press`].
const MOD_CTRL: i32 = 1;
const MOD_ALT: i32 = 2;
const MOD_SHIFT: i32 = 4;

/// Unpacks a key from `FrameBridge.sendKey`, or None for one it cannot be.
///
/// `key` numbers the [`KeyCode`] variants in declaration order; `arg` is the
/// codepoint of a `Char`, the number of a `Function` key, or the index of a
/// [`ModifierKey`]. `kind` is 0 press, 1 repeat, 2 release. The Kotlin side
/// packs in the same order, and the two are tested against each other there.
fn key_press(key: i32, arg: i32, mods: i32, kind: i32) -> Option<KeyPress> {
    let key = match key {
        0 => KeyCode::Char { codepoint: u32::try_from(arg).ok()? },
        1 => KeyCode::Enter,
        2 => KeyCode::Tab,
        3 => KeyCode::Backspace,
        4 => KeyCode::Escape,
        5 => KeyCode::Up,
        6 => KeyCode::Down,
        7 => KeyCode::Left,
        8 => KeyCode::Right,
        9 => KeyCode::Home,
        10 => KeyCode::End,
        11 => KeyCode::PageUp,
        12 => KeyCode::PageDown,
        13 => KeyCode::Insert,
        14 => KeyCode::Delete,
        15 => KeyCode::Function { n: u8::try_from(arg).ok()? },
        16 => KeyCode::Modifier {
            which: match arg {
                0 => ModifierKey::LeftShift,
                1 => ModifierKey::RightShift,
                2 => ModifierKey::LeftControl,
                3 => ModifierKey::RightControl,
                4 => ModifierKey::LeftAlt,
                5 => ModifierKey::RightAlt,
                6 => ModifierKey::LeftSuper,
                7 => ModifierKey::RightSuper,
                _ => return None,
            },
        },
        _ => return None,
    };
    let kind = match kind {
        0 => KeyEventKind::Press,
        1 => KeyEventKind::Repeat,
        2 => KeyEventKind::Release,
        _ => return None,
    };
    Some(KeyPress {
        key,
        ctrl: mods & MOD_CTRL != 0,
        alt: mods & MOD_ALT != 0,
        shift: mods & MOD_SHIFT != 0,
        kind,
    })
}

/// Where the buffer's bytes start. Asked for every frame, as the buffer can move
/// without its length changing.
#[no_mangle]
pub extern "system" fn Java_dev_flint_term_terminal_FrameBridge_bufferAddress(
    _env: *mut c_void,
    _class: *mut c_void,
    buffer: i64,
) -> i64 {
    if buffer == 0 {
        return 0;
    }
    // SAFETY: as above.
    let buffer = unsafe { &*(buffer as usize as *const SnapshotBuffer) };
    buffer.address() as i64
}

#[no_mangle]
pub extern "system" fn Java_dev_flint_term_terminal_FrameBridge_releaseSession(
    _env: *mut c_void,
    _class: *mut c_void,
    session: i64,
) {
    if session != 0 {
        // SAFETY: from `frame_handle`, released exactly once by the caller.
        drop(unsafe { Arc::from_raw(session as usize as *const Session) });
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_flint_term_terminal_FrameBridge_releaseBuffer(
    _env: *mut c_void,
    _class: *mut c_void,
    buffer: i64,
) {
    if buffer != 0 {
        // SAFETY: as above.
        drop(unsafe { Arc::from_raw(buffer as usize as *const SnapshotBuffer) });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_packed_key_comes_back_as_the_press_it_was() {
        let p = key_press(0, 'c' as i32, MOD_CTRL | MOD_SHIFT, 0).unwrap();
        assert!(matches!(p.key, KeyCode::Char { codepoint } if codepoint == 'c' as u32));
        assert!(p.ctrl && p.shift && !p.alt);
        assert!(matches!(p.kind, KeyEventKind::Press));

        assert!(matches!(key_press(15, 12, 0, 1).unwrap().key, KeyCode::Function { n: 12 }));
        assert!(matches!(
            key_press(16, 7, MOD_ALT, 2).unwrap(),
            KeyPress { key: KeyCode::Modifier { which: ModifierKey::RightSuper }, alt: true, kind: KeyEventKind::Release, .. }
        ));
        assert!(matches!(key_press(14, 0, 0, 0).unwrap().key, KeyCode::Delete));
    }

    #[test]
    fn a_key_that_cannot_be_is_dropped() {
        assert!(key_press(17, 0, 0, 0).is_none());
        assert!(key_press(0, -1, 0, 0).is_none());
        assert!(key_press(16, 8, 0, 0).is_none());
        assert!(key_press(1, 0, 0, 3).is_none());
    }
}
