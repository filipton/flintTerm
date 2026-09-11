//! Packed, allocation-free grid snapshot format shared with the renderer.
//!
//! Layout (little endian):
//!
//! ```text
//! header (HEADER_BYTES):
//!   u16 cols, u16 rows
//!   i16 cursor_col, i16 cursor_row        (-1 / -1 when hidden)
//!   u8  cursor_shape (0 block, 1 underline, 2 beam, 3 hidden)
//!   u8  mode_flags (bit0 alt_screen, bit1 mouse_reporting, bit2 bracketed_paste)
//!   u32 display_offset, u32 history_size
//!   i16 sel_start_col, i16 sel_start_row, i16 sel_end_col, i16 sel_end_row (i16::MIN when none)
//!   u8  sel_is_block, u32 generation, u8 links_changed
//! cells (rows * cols * CELL_BYTES):
//!   u32 codepoint, u32 fg (0x00RRGGBB), u32 bg (0x00RRGGBB), u16 flags
//! ```
//!
//! Inverse video and dim are already applied to `fg`/`bg`; the renderer only
//! paints what it is told. Wide characters occupy two cells: the first carries
//! the glyph with `WIDE`, the second is a `WIDE_SPACER` with codepoint 0.

pub const HEADER_BYTES: usize = 32;
/// Where the content counter sits in the header.
///
/// The writer leaves it zero; the session fills it in on the way out, because
/// it is the session and not the emulator that knows what has changed. A
/// renderer compares it with the last frame's to find out whether anything on
/// the grid actually moved.
pub const GENERATION_OFFSET: usize = 27;
/// A counter that moves only when the set of links on the grid changed.
///
/// Also filled in by the session. Output with no links in it streams past
/// without this ever moving, so the renderer only asks for the links when it
/// does. It wraps, which is fine: it is compared frame to frame, never kept.
pub const LINKS_OFFSET: usize = 31;
pub const CELL_BYTES: usize = 14;

pub mod CellFlags {
    #![allow(non_snake_case)]
    pub const BOLD: u16 = 1 << 0;
    pub const ITALIC: u16 = 1 << 1;
    pub const UNDERLINE: u16 = 1 << 2;
    pub const STRIKEOUT: u16 = 1 << 3;
    pub const DIM: u16 = 1 << 4;
    pub const WIDE: u16 = 1 << 5;
    pub const WIDE_SPACER: u16 = 1 << 6;
    pub const SELECTED: u16 = 1 << 7;
    pub const HIDDEN: u16 = 1 << 8;
    pub const DOUBLE_UNDERLINE: u16 = 1 << 9;
    pub const UNDERCURL: u16 = 1 << 10;
    pub const HYPERLINK: u16 = 1 << 11;
}

/// Writes one cell into the first [`CELL_BYTES`] of `dst`.
///
/// The single place the cell layout is spelled out. A backend that rebuilds
/// the whole grid every frame appends through [`SnapshotWriter::cell`]; one
/// that keeps a per-row cache overwrites rows in place through this. Both go
/// through here so the two can never disagree.
#[inline]
pub fn encode_cell(dst: &mut [u8], codepoint: u32, fg: u32, bg: u32, flags: u16) {
    dst[0..4].copy_from_slice(&codepoint.to_le_bytes());
    dst[4..8].copy_from_slice(&fg.to_le_bytes());
    dst[8..12].copy_from_slice(&bg.to_le_bytes());
    dst[12..14].copy_from_slice(&flags.to_le_bytes());
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SnapshotHeader {
    pub cols: u16,
    pub rows: u16,
    pub cursor_col: i16,
    pub cursor_row: i16,
    pub cursor_shape: u8,
    pub alt_screen: bool,
    pub mouse_reporting: bool,
    pub bracketed_paste: bool,
    pub display_offset: u32,
    pub history_size: u32,
    pub selection: Option<((i16, i16), (i16, i16), bool)>,
}

pub struct SnapshotWriter<'a> {
    out: &'a mut Vec<u8>,
}

impl<'a> SnapshotWriter<'a> {
    pub fn new(out: &'a mut Vec<u8>, header: &SnapshotHeader) -> Self {
        out.clear();
        out.reserve(HEADER_BYTES + header.cols as usize * header.rows as usize * CELL_BYTES);
        let mut w = Self { out };
        w.u16(header.cols);
        w.u16(header.rows);
        w.i16(header.cursor_col);
        w.i16(header.cursor_row);
        w.u8(header.cursor_shape);
        let mut mode = 0u8;
        if header.alt_screen {
            mode |= 1;
        }
        if header.mouse_reporting {
            mode |= 2;
        }
        if header.bracketed_paste {
            mode |= 4;
        }
        w.u8(mode);
        w.u32(header.display_offset);
        w.u32(header.history_size);
        match header.selection {
            Some(((sc, sr), (ec, er), block)) => {
                w.i16(sc);
                w.i16(sr);
                w.i16(ec);
                w.i16(er);
                w.u8(block as u8);
            }
            None => {
                for _ in 0..4 {
                    w.i16(i16::MIN);
                }
                w.u8(0);
            }
        }
        for _ in 0..5 {
            w.u8(0);
        }
        debug_assert_eq!(w.out.len(), HEADER_BYTES);
        w
    }

    #[inline]
    pub fn cell(&mut self, codepoint: u32, fg: u32, bg: u32, flags: u16) {
        let mut buf = [0u8; CELL_BYTES];
        encode_cell(&mut buf, codepoint, fg, bg, flags);
        self.out.extend_from_slice(&buf);
    }

    #[inline]
    fn u8(&mut self, v: u8) {
        self.out.push(v);
    }
    #[inline]
    fn u16(&mut self, v: u16) {
        self.out.extend_from_slice(&v.to_le_bytes());
    }
    #[inline]
    fn i16(&mut self, v: i16) {
        self.out.extend_from_slice(&v.to_le_bytes());
    }
    #[inline]
    fn u32(&mut self, v: u32) {
        self.out.extend_from_slice(&v.to_le_bytes());
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn appending_and_overwriting_agree() {
        let header = SnapshotHeader {
            cols: 2,
            rows: 1,
            cursor_col: 0,
            cursor_row: 0,
            cursor_shape: 0,
            alt_screen: false,
            mouse_reporting: false,
            bracketed_paste: false,
            display_offset: 0,
            history_size: 0,
            selection: None,
        };
        let mut appended = Vec::new();
        let mut w = SnapshotWriter::new(&mut appended, &header);
        w.cell('a' as u32, 0x00ff00, 0x101010, CellFlags::BOLD);
        w.cell(0x1f600, 0xffffff, 0, CellFlags::WIDE | CellFlags::UNDERCURL);

        let mut overwritten = vec![0u8; 2 * CELL_BYTES];
        encode_cell(&mut overwritten[0..], 'a' as u32, 0x00ff00, 0x101010, CellFlags::BOLD);
        encode_cell(
            &mut overwritten[CELL_BYTES..],
            0x1f600,
            0xffffff,
            0,
            CellFlags::WIDE | CellFlags::UNDERCURL,
        );

        assert_eq!(&appended[HEADER_BYTES..], &overwritten[..]);
    }
}
