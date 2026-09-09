//! Reading a `GhosttyCell` without crossing the C boundary.
//!
//! A cell is a packed `u64`. The bit positions are deliberately *not* frozen by
//! the C ABI — `render.h` says so and points callers at the manifest from
//! `ghostty_type_json()` instead — so decoding one normally means a call into
//! libghostty per field. At 4800 cells a frame that is the single largest cost
//! in [`snapshot`](crate::GhosttyEmulator::snapshot).
//!
//! So this module mirrors the layout of the pinned revision in Rust and then
//! *proves* it: [`fast_path`] builds synthetic cells covering every content tag
//! and field, hands each to `ghostty_cell_get`, and only enables the fast path
//! when libghostty reads back exactly what was written. If a future revision
//! moves a field, the check fails and every cell goes through the C getters
//! again — slower, never wrong.
//!
//! `cargo run -p ghostty-vt-sys --example manifest` prints the layout a build
//! of libghostty actually has, which is where these constants come from.

use std::ffi::{c_int, c_void};
use std::sync::OnceLock;

use ghostty_vt_sys as sys;

// Field positions within the packed u64, from the manifest's "GhosttyCell".
const CONTENT_TAG_LSB: u32 = 0;
const CONTENT_TAG_WIDTH: u32 = 2;
const CONTENT_LSB: u32 = 2;
const CONTENT_WIDTH: u32 = 24;
const CODEPOINT_WIDTH: u32 = 21;
const STYLE_ID_LSB: u32 = 26;
const STYLE_ID_WIDTH: u32 = 16;
const WIDE_LSB: u32 = 42;
const WIDE_WIDTH: u32 = 2;
const HYPERLINK_LSB: u32 = 45;

#[inline]
const fn mask(width: u32) -> u64 {
    (1u64 << width) - 1
}

#[inline]
const fn field(cell: u64, lsb: u32, width: u32) -> u64 {
    (cell >> lsb) & mask(width)
}

/// Everything the snapshot needs out of one cell.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Cell {
    pub content_tag: c_int,
    /// The cell's 24 bits of content, meaning whatever `content_tag` says.
    pub content: u32,
    pub style_id: u16,
    pub wide: c_int,
    pub hyperlink: bool,
}

impl Cell {
    /// The character in the cell, or 0 when it holds only a background colour.
    #[inline]
    pub fn codepoint(&self) -> u32 {
        match self.content_tag {
            sys::GHOSTTY_CELL_CONTENT_CODEPOINT | sys::GHOSTTY_CELL_CONTENT_CODEPOINT_GRAPHEME => {
                self.content & mask(CODEPOINT_WIDTH) as u32
            }
            _ => 0,
        }
    }

    /// Palette index of a background-only cell.
    #[inline]
    pub fn bg_palette(&self) -> u8 {
        self.content as u8
    }

    /// Packed `0x00RRGGBB` of a background-only cell.
    #[inline]
    pub fn bg_rgb(&self) -> u32 {
        let r = self.content & 0xff;
        let g = (self.content >> 8) & 0xff;
        let b = (self.content >> 16) & 0xff;
        (r << 16) | (g << 8) | b
    }

    /// Cells carry the default style under id 0; anything else has SGR on it.
    #[inline]
    pub fn styled(&self) -> bool {
        self.style_id != 0
    }
}

/// Reassembles a cell, used only to check the layout against libghostty.
fn pack(c: &Cell) -> u64 {
    ((c.content_tag as u64) & mask(CONTENT_TAG_WIDTH)) << CONTENT_TAG_LSB
        | ((c.content as u64) & mask(CONTENT_WIDTH)) << CONTENT_LSB
        | ((c.style_id as u64) & mask(STYLE_ID_WIDTH)) << STYLE_ID_LSB
        | ((c.wide as u64) & mask(WIDE_WIDTH)) << WIDE_LSB
        | (c.hyperlink as u64) << HYPERLINK_LSB
}

#[inline]
fn decode_fast(cell: u64) -> Cell {
    Cell {
        content_tag: field(cell, CONTENT_TAG_LSB, CONTENT_TAG_WIDTH) as c_int,
        content: field(cell, CONTENT_LSB, CONTENT_WIDTH) as u32,
        style_id: field(cell, STYLE_ID_LSB, STYLE_ID_WIDTH) as u16,
        wide: field(cell, WIDE_LSB, WIDE_WIDTH) as c_int,
        hyperlink: field(cell, HYPERLINK_LSB, 1) != 0,
    }
}

/// The same answer, asked of libghostty one field at a time.
fn decode_slow(cell: u64) -> Cell {
    let mut codepoint: u32 = 0;
    let mut content_tag: c_int = sys::GHOSTTY_CELL_CONTENT_CODEPOINT;
    let mut wide: c_int = sys::GHOSTTY_CELL_WIDE_NARROW;
    let mut style_id: u16 = 0;
    let mut hyperlink = false;
    let keys = [
        sys::GHOSTTY_CELL_DATA_CODEPOINT,
        sys::GHOSTTY_CELL_DATA_CONTENT_TAG,
        sys::GHOSTTY_CELL_DATA_WIDE,
        sys::GHOSTTY_CELL_DATA_STYLE_ID,
        sys::GHOSTTY_CELL_DATA_HAS_HYPERLINK,
    ];
    let mut values: [*mut c_void; 5] = [
        &mut codepoint as *mut _ as *mut c_void,
        &mut content_tag as *mut _ as *mut c_void,
        &mut wide as *mut _ as *mut c_void,
        &mut style_id as *mut _ as *mut c_void,
        &mut hyperlink as *mut _ as *mut c_void,
    ];
    let mut written = 0usize;
    unsafe {
        sys::ghostty_cell_get_multi(cell, keys.len(), keys.as_ptr(), values.as_mut_ptr(), &mut written);
    }

    // The C API hands back the decoded content, so put it back in the packed
    // form the fast path yields to keep both sides comparable.
    let content = match content_tag {
        sys::GHOSTTY_CELL_CONTENT_BG_COLOR_PALETTE => {
            let mut idx: u8 = 0;
            unsafe {
                sys::ghostty_cell_get(
                    cell,
                    sys::GHOSTTY_CELL_DATA_COLOR_PALETTE,
                    &mut idx as *mut _ as *mut c_void,
                );
            }
            idx as u32
        }
        sys::GHOSTTY_CELL_CONTENT_BG_COLOR_RGB => {
            let mut rgb = sys::GhosttyColorRgb::default();
            unsafe {
                sys::ghostty_cell_get(
                    cell,
                    sys::GHOSTTY_CELL_DATA_COLOR_RGB,
                    &mut rgb as *mut _ as *mut c_void,
                );
            }
            rgb.r as u32 | ((rgb.g as u32) << 8) | ((rgb.b as u32) << 16)
        }
        _ => codepoint,
    };

    Cell { content_tag, content, style_id, wide, hyperlink }
}

/// Whether the packed layout above matches the libghostty we linked against.
fn layout_matches() -> bool {
    let tags = [
        sys::GHOSTTY_CELL_CONTENT_CODEPOINT,
        sys::GHOSTTY_CELL_CONTENT_CODEPOINT_GRAPHEME,
        sys::GHOSTTY_CELL_CONTENT_BG_COLOR_PALETTE,
        sys::GHOSTTY_CELL_CONTENT_BG_COLOR_RGB,
    ];
    // Content values that exercise every bit of the 24-bit field: the widest
    // codepoint, a palette index, and an RGB triple with three distinct bytes.
    let contents: [u32; 6] = [0, 0x41, 0x65e5, 0x10_ffff, 0xff, 0x123456];
    let style_ids: [u16; 4] = [0, 1, 0x00ff, 0xffff];
    let wides = [
        sys::GHOSTTY_CELL_WIDE_NARROW,
        sys::GHOSTTY_CELL_WIDE_WIDE,
        sys::GHOSTTY_CELL_WIDE_SPACER_TAIL,
        sys::GHOSTTY_CELL_WIDE_SPACER_HEAD,
    ];

    for &content_tag in &tags {
        for &content in &contents {
            // A codepoint arm only owns 21 of the 24 bits; the rest belong to
            // no field and must not be set, or the two sides disagree over
            // bits libghostty never reads.
            let content = match content_tag {
                sys::GHOSTTY_CELL_CONTENT_BG_COLOR_PALETTE => content & 0xff,
                sys::GHOSTTY_CELL_CONTENT_BG_COLOR_RGB => content & 0xff_ffff,
                _ => content & mask(CODEPOINT_WIDTH) as u32,
            };
            for &style_id in &style_ids {
                for &wide in &wides {
                    for hyperlink in [false, true] {
                        let want = Cell { content_tag, content, style_id, wide, hyperlink };
                        let raw = pack(&want);
                        if decode_fast(raw) != want || decode_slow(raw) != want {
                            log::warn!(
                                "libghostty cell layout moved (tag={content_tag} content={content:#x} \
                                 style={style_id} wide={wide}); using the per-field C decoder"
                            );
                            return false;
                        }
                    }
                }
            }
        }
    }
    true
}

/// Whether the packed layout can be trusted, decided once per process.
///
/// Read it once per frame rather than once per cell: the answer never changes,
/// and an atomic load inside the cell loop costs more than the decode does.
pub fn fast_path() -> bool {
    static FAST: OnceLock<bool> = OnceLock::new();
    *FAST.get_or_init(layout_matches)
}

/// Decodes a cell, `fast` being an earlier answer from [`fast_path`].
#[inline]
pub fn decode_with(fast: bool, cell: u64) -> Cell {
    if fast {
        decode_fast(cell)
    } else {
        decode_slow(cell)
    }
}

/// Decodes a cell, checking the layout verdict itself. For callers outside a
/// hot loop.
#[inline]
pub fn decode(cell: u64) -> Cell {
    decode_with(fast_path(), cell)
}

/// Everything outside the content tag and the codepoint: no style, not wide,
/// no hyperlink, no semantic marking.
const NOT_PLAIN: u64 = !(mask(CODEPOINT_WIDTH) << CONTENT_LSB);

/// Whether a cell is an unadorned character in the default style — which most
/// cells on a screen of text are. Such a cell needs no colour resolution and no
/// flag work at all, so the snapshot can write it straight out.
///
/// Only meaningful when [`fast_path`] said yes.
#[inline]
pub fn is_plain(raw: u64) -> bool {
    raw & NOT_PLAIN == 0
}

/// The codepoint of a cell [`is_plain`] accepted.
#[inline]
pub fn plain_codepoint(raw: u64) -> u32 {
    (raw >> CONTENT_LSB) as u32 & mask(CODEPOINT_WIDTH) as u32
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn layout_agrees_with_libghostty() {
        assert!(layout_matches(), "the pinned libghostty-vt no longer has this cell layout");
    }

    #[test]
    fn plain_cells_are_recognised() {
        let plain = pack(&Cell {
            content_tag: sys::GHOSTTY_CELL_CONTENT_CODEPOINT,
            content: 'q' as u32,
            style_id: 0,
            wide: sys::GHOSTTY_CELL_WIDE_NARROW,
            hyperlink: false,
        });
        assert!(is_plain(plain));
        assert_eq!(plain_codepoint(plain), 'q' as u32);
        assert!(is_plain(0), "an empty cell is plain, with codepoint 0");
        assert_eq!(plain_codepoint(0), 0);

        // Anything else about a cell disqualifies it from the shortcut, and
        // whatever the shortcut does accept must decode to a bare character.
        for cell in [
            Cell {
                content_tag: sys::GHOSTTY_CELL_CONTENT_CODEPOINT,
                content: 'q' as u32,
                style_id: 3,
                wide: sys::GHOSTTY_CELL_WIDE_NARROW,
                hyperlink: false,
            },
            Cell {
                content_tag: sys::GHOSTTY_CELL_CONTENT_CODEPOINT,
                content: 'q' as u32,
                style_id: 0,
                wide: sys::GHOSTTY_CELL_WIDE_WIDE,
                hyperlink: false,
            },
            Cell {
                content_tag: sys::GHOSTTY_CELL_CONTENT_CODEPOINT,
                content: 'q' as u32,
                style_id: 0,
                wide: sys::GHOSTTY_CELL_WIDE_NARROW,
                hyperlink: true,
            },
            Cell {
                content_tag: sys::GHOSTTY_CELL_CONTENT_BG_COLOR_PALETTE,
                content: 4,
                style_id: 0,
                wide: sys::GHOSTTY_CELL_WIDE_NARROW,
                hyperlink: false,
            },
        ] {
            assert!(!is_plain(pack(&cell)), "{cell:?} is not a plain character");
        }
    }

    #[test]
    fn decodes_the_content_arms() {
        let ascii = decode(pack(&Cell {
            content_tag: sys::GHOSTTY_CELL_CONTENT_CODEPOINT,
            content: 'x' as u32,
            style_id: 7,
            wide: sys::GHOSTTY_CELL_WIDE_NARROW,
            hyperlink: false,
        }));
        assert_eq!(ascii.codepoint(), 'x' as u32);
        assert!(ascii.styled());

        let rgb = decode(pack(&Cell {
            content_tag: sys::GHOSTTY_CELL_CONTENT_BG_COLOR_RGB,
            // r=0x12, g=0x34, b=0x56 in the manifest's little-endian order.
            content: 0x12 | (0x34 << 8) | (0x56 << 16),
            style_id: 0,
            wide: sys::GHOSTTY_CELL_WIDE_NARROW,
            hyperlink: false,
        }));
        assert_eq!(rgb.bg_rgb(), 0x123456);
        assert_eq!(rgb.codepoint(), 0);
        assert!(!rgb.styled());

        let pal = decode(pack(&Cell {
            content_tag: sys::GHOSTTY_CELL_CONTENT_BG_COLOR_PALETTE,
            content: 33,
            style_id: 0,
            wide: sys::GHOSTTY_CELL_WIDE_SPACER_TAIL,
            hyperlink: true,
        }));
        assert_eq!(pal.bg_palette(), 33);
        assert_eq!(pal.wide, sys::GHOSTTY_CELL_WIDE_SPACER_TAIL);
        assert!(pal.hyperlink);
    }
}
