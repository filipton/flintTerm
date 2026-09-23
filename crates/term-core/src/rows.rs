//! The row table appended after the cells of a packed snapshot.
//!
//! A renderer that keeps each row it drew as a recorded display list needs to
//! know which rows are still the rows it recorded, and a row that scrolled up
//! by one is the same row at another index. So every row gets a hash of its
//! packed cells, and the renderer keys its recordings on that rather than on
//! where the row sits.
//!
//! Layout, starting at the offset the header holds at [`ROWS_OFFSET`], which
//! is 8-aligned (0 when there is no table):
//!
//! ```text
//! rows * ROW_ENTRY_BYTES:
//!   u64 hash, u32 reserved, u32 reserved
//! ```
//!
//! [`ROWS_OFFSET`]: crate::snapshot::ROWS_OFFSET

use crate::snapshot::{CELL_BYTES, HEADER_BYTES, ROWS_OFFSET};

pub const ROW_ENTRY_BYTES: usize = 16;

/// A hash of one packed row: codepoints, colours and flags.
///
/// Everything a row's pixels are made of is in those bytes. Selection and
/// keyword colours are painted into the cells before this runs, and inverse
/// and dim are already applied to the colours, so a change to any of them is
/// a change to the bytes. What is drawn over the grid rather than from it —
/// the cursor, the search hit, link underlines, images, predictions — is the
/// renderer's to account for.
///
/// Fx-style: a rotate, an xor and a multiply by an odd constant per 8 bytes.
/// Each step is a bijection of the state for a fixed input word, so two rows
/// that differ in a single word can never collide, which is the common case
/// of one cell changing. Nothing here needs to resist anyone choosing
/// collisions; a row that did collide would be wrong until it next changed.
#[inline]
pub fn row_hash(row: &[u8]) -> u64 {
    const K: u64 = 0x517c_c1b7_2722_0a95;
    let mut h = row.len() as u64;
    let (words, tail) = row.as_chunks::<8>();
    for w in words {
        h = (h.rotate_left(5) ^ u64::from_le_bytes(*w)).wrapping_mul(K);
    }
    if !tail.is_empty() {
        let mut last = [0u8; 8];
        last[..tail.len()].copy_from_slice(tail);
        h = (h.rotate_left(5) ^ u64::from_le_bytes(last)).wrapping_mul(K);
    }
    // The multiply only carries upwards; fold the top back down so the low
    // bits a hash table would use depend on the whole row too.
    h ^ (h >> 32)
}

/// Appends the row table to a packed snapshot and points the header at it.
///
/// Leaves the buffer alone when it is shorter than its header says, which is
/// a backend that packed nothing; the offset stays zero and the renderer takes
/// the path that walks the cells.
pub fn append_rows(buf: &mut Vec<u8>) {
    let Some((cols, rows)) = grid_size(buf) else {
        return;
    };
    let cells_end = HEADER_BYTES + cols * rows * CELL_BYTES;
    buf.truncate(cells_end);
    // Aligned so the renderer can read the table, and later everything after
    // it, through int and float views of the whole buffer.
    let table = (cells_end + 7) & !7;
    buf.resize(table + rows * ROW_ENTRY_BYTES, 0);
    let (cells, entries) = buf.split_at_mut(table);
    let cells = &cells[HEADER_BYTES..cells_end];
    let row_bytes = cols * CELL_BYTES;
    for (row, entry) in entries.chunks_exact_mut(ROW_ENTRY_BYTES).enumerate() {
        let hash = row_hash(&cells[row * row_bytes..(row + 1) * row_bytes]);
        entry[0..8].copy_from_slice(&hash.to_le_bytes());
    }
    buf[ROWS_OFFSET..ROWS_OFFSET + 4].copy_from_slice(&(table as u32).to_le_bytes());
}

/// Columns and rows from the header, when the cells they promise are there.
fn grid_size(buf: &[u8]) -> Option<(usize, usize)> {
    if buf.len() < HEADER_BYTES {
        return None;
    }
    let cols = u16::from_le_bytes([buf[0], buf[1]]) as usize;
    let rows = u16::from_le_bytes([buf[2], buf[3]]) as usize;
    (buf.len() >= HEADER_BYTES + cols * rows * CELL_BYTES).then_some((cols, rows))
}

/// The hash the table holds for `row`; for tests and the session's own checks.
pub fn table_hash(buf: &[u8], row: usize) -> Option<u64> {
    let at = u32::from_le_bytes(buf.get(ROWS_OFFSET..ROWS_OFFSET + 4)?.try_into().ok()?) as usize;
    if at == 0 {
        return None;
    }
    let e = at + row * ROW_ENTRY_BYTES;
    Some(u64::from_le_bytes(buf.get(e..e + 8)?.try_into().ok()?))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::snapshot::{encode_cell, CellFlags, SnapshotHeader, SnapshotWriter};

    const FG: u32 = 0xabb2bf;
    const BG: u32 = 0x1e2127;

    fn header(cols: u16, rows: u16) -> SnapshotHeader {
        SnapshotHeader {
            cols,
            rows,
            cursor_col: 0,
            cursor_row: 0,
            cursor_shape: 0,
            alt_screen: false,
            mouse_reporting: false,
            bracketed_paste: false,
            display_offset: 0,
            history_size: 0,
            selection: None,
        }
    }

    /// A grid of `lines`, one string per row, padded with spaces.
    fn grid(cols: u16, lines: &[&str]) -> Vec<u8> {
        let mut buf = Vec::new();
        let mut w = SnapshotWriter::new(&mut buf, &header(cols, lines.len() as u16));
        for line in lines {
            let mut chars = line.chars();
            for _ in 0..cols {
                w.cell(chars.next().unwrap_or(' ') as u32, FG, BG, 0);
            }
        }
        buf
    }

    fn hashes(mut buf: Vec<u8>) -> Vec<u64> {
        append_rows(&mut buf);
        let rows = u16::from_le_bytes([buf[2], buf[3]]) as usize;
        (0..rows).map(|r| table_hash(&buf, r).unwrap()).collect()
    }

    fn set_cell(buf: &mut [u8], cols: usize, row: usize, col: usize, cp: u32, fg: u32, bg: u32, flags: u16) {
        encode_cell(&mut buf[HEADER_BYTES + (row * cols + col) * CELL_BYTES..], cp, fg, bg, flags);
    }

    #[test]
    fn the_same_row_hashes_the_same_wherever_it_is() {
        let h = hashes(grid(10, &["hello", "world", "hello", ""]));
        assert_eq!(h[0], h[2]);
        assert_ne!(h[0], h[1]);
        assert_ne!(h[0], h[3]);
        // And again, from a separate packing: nothing but the bytes goes in.
        assert_eq!(h, hashes(grid(10, &["hello", "world", "hello", ""])));
    }

    #[test]
    fn every_visible_attribute_moves_the_hash() {
        let base = grid(8, &["abc"]);
        let before = hashes(base.clone())[0];
        let changes: [(u32, u32, u32, u16); 12] = [
            ('x' as u32, FG, BG, 0),
            ('a' as u32, 0xff0000, BG, 0),
            ('a' as u32, FG, 0x00ff00, 0),
            ('a' as u32, FG, BG, CellFlags::BOLD),
            ('a' as u32, FG, BG, CellFlags::ITALIC),
            ('a' as u32, FG, BG, CellFlags::UNDERLINE),
            ('a' as u32, FG, BG, CellFlags::DOUBLE_UNDERLINE),
            ('a' as u32, FG, BG, CellFlags::UNDERCURL),
            ('a' as u32, FG, BG, CellFlags::STRIKEOUT),
            ('a' as u32, FG, BG, CellFlags::HIDDEN),
            ('a' as u32, FG, BG, CellFlags::SELECTED),
            ('a' as u32, FG, BG, CellFlags::WIDE),
        ];
        for (cp, fg, bg, flags) in changes {
            let mut b = base.clone();
            set_cell(&mut b, 8, 0, 0, cp, fg, bg, flags);
            assert_ne!(hashes(b)[0], before, "{cp:x} {fg:x} {bg:x} {flags:x}");
        }
        // The last cell too, which is where the tail of a row's bytes lands.
        let mut b = base.clone();
        set_cell(&mut b, 8, 0, 7, ' ' as u32, FG, 0x010101, 0);
        assert_ne!(hashes(b)[0], before);
    }

    #[test]
    fn rows_of_different_widths_differ_even_when_blank() {
        assert_ne!(hashes(grid(8, &[""]))[0], hashes(grid(9, &[""]))[0]);
    }

    #[test]
    fn the_table_is_aligned_and_repacking_replaces_it() {
        let mut buf = grid(3, &["a", "b", "c"]);
        append_rows(&mut buf);
        let at = u32::from_le_bytes(buf[ROWS_OFFSET..ROWS_OFFSET + 4].try_into().unwrap()) as usize;
        assert_eq!(at % 8, 0);
        assert_eq!(buf.len(), at + 3 * ROW_ENTRY_BYTES);
        let first = buf.clone();
        append_rows(&mut buf);
        assert_eq!(buf, first);
    }

    #[test]
    fn a_short_buffer_gets_no_table() {
        let mut buf = grid(4, &["ab"]);
        buf.truncate(HEADER_BYTES + 2);
        append_rows(&mut buf);
        assert_eq!(table_hash(&buf, 0), None);
    }
}
