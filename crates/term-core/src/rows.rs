//! The row table appended after the cells of a packed snapshot, and the draw
//! runs each row is made of.
//!
//! A renderer that keeps each row it drew as a recorded display list needs to
//! know which rows are still the rows it recorded, and a row that scrolled up
//! by one is the same row at another index. So every row gets a hash of its
//! packed cells, and the renderer keys its recordings on that rather than on
//! where the row sits.
//!
//! A row that does have to be recorded again used to be recorded by walking
//! its cells in Kotlin: every cell read through a bounds-checked buffer, runs
//! cut by colour and style, glyphs looked up one by one. The same walk is done
//! here instead, where it is cheap, and what comes out is what the renderer
//! would have decided: background rectangles already merged, glyphs already
//! numbered and placed and gathered by colour and font, decorations, and the
//! few cells that still need the platform to shape them. The glyph numbers
//! are the renderer's own, handed over once per font through [`Layout`].
//!
//! Layout, starting at the offset the header holds at [`ROWS_OFFSET`], which
//! is 8-aligned (0 when there is no table). Every field is 4 bytes, little
//! endian, so the whole thing can be read through int and float views of the
//! buffer:
//!
//! ```text
//! rows * ROW_ENTRY_BYTES:
//!   u64 hash, u32 bg_at, u32 text_at        (both 0 when no runs were asked for)
//! at bg_at:
//!   u32 count, then count * { u32 col | end_col << 16, u32 rgb }
//! at text_at:
//!   u32 fallbacks, u32 decorations, u32 batches
//!   fallbacks   * { u32 col | n << 16, u32 rgb, u32 flags | slot << 16 | FALLBACK_LEARN, n * u32 codepoint }
//!   decorations * { u32 col | cols << 16, u32 rgb, u32 flags }
//!   batches     * { u32 argb, u32 font, u32 n, n * i32 glyph, n * (f32 x, f32 y) }
//! ```
//!
//! In the order they are drawn: the batched glyphs went down last when the
//! renderer gathered them itself, over any decoration. Positions are relative
//! to the row's top left corner. `flags` are the cell style flags
//! ([`CellFlags`]) the decoration or the text was drawn with.
//!
//! [`ROWS_OFFSET`]: crate::snapshot::ROWS_OFFSET

use std::collections::HashMap;
use std::hash::{BuildHasherDefault, Hasher};

use crate::snapshot::{CellFlags, CELL_BYTES, HEADER_BYTES, ROWS_OFFSET};

pub const ROW_ENTRY_BYTES: usize = 16;

/// The faces a piece of text is drawn in. The renderer registers a glyph
/// table per slot and maps each to its typeface.
pub const SLOT_REGULAR: usize = 0;
pub const SLOT_BOLD: usize = 1;
pub const SLOT_ITALIC: usize = 2;
pub const SLOT_BOLD_ITALIC: usize = 3;
/// The bundled icon font, for the private-use codepoints Nerd Fonts fill.
pub const SLOT_SYMBOL: usize = 4;
pub const SLOTS: usize = 5;

/// The glyphs a slot's table holds: printable ASCII after the space.
pub const ASCII_GLYPHS: usize = 0x7f - 0x21;

/// Set on a fallback the renderer could teach us about, so it draws that
/// text by number next time: the slot has never been registered, or the
/// codepoint has never been asked about.
pub const FALLBACK_LEARN: u32 = 1 << 24;

const STYLE_MASK: u16 = CellFlags::BOLD
    | CellFlags::ITALIC
    | CellFlags::UNDERLINE
    | CellFlags::STRIKEOUT
    | CellFlags::DOUBLE_UNDERLINE
    | CellFlags::UNDERCURL;
const DECORATIONS: u16 =
    CellFlags::UNDERLINE | CellFlags::STRIKEOUT | CellFlags::DOUBLE_UNDERLINE | CellFlags::UNDERCURL;

/// The longest piece of text handed over in one go: what the renderer's
/// character buffer has room for, and where it always cut a run.
const RUN_MAX: usize = 1022;

/// A hash of one packed row: codepoints, colours and flags.
///
/// Everything a row's pixels are made of is in those bytes. Selection and
/// keyword colours are painted into the cells before this runs, and inverse
/// and dim are already applied to the colours, so a change to any of them is
/// a change to the bytes. What is drawn over the grid rather than from it —
/// the cursor, the search hit, link underlines, images, predictions — is the
/// renderer's to account for. So are the font and the metrics: the runs
/// depend on them, and the renderer throws its recordings away when they
/// change.
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

/// Whether `cp` is drawn from the icon font: the private-use areas Nerd Fonts
/// patch into. The renderer's `RunSplitter.isSymbol` is the same rule.
#[inline]
pub fn is_symbol(cp: u32) -> bool {
    (0xE000..=0xF8FF).contains(&cp) || (0xF0000..=0xFFFFF).contains(&cp)
}

/// What the runs are laid out with: the renderer's glyph numbers and its cell
/// metrics. One per snapshot buffer, which is one per view.
#[derive(Default)]
pub struct Layout {
    runs: bool,
    nerd: bool,
    cell_w: f32,
    baseline: f32,
    slots: [Slot; SLOTS],
    generation: u64,
    scratch: Scratch,
    /// The longest a packing with runs has been; see [`append_rows`].
    high_water: usize,
}

#[derive(Default)]
struct Slot {
    ascii: Ascii,
    /// Glyphs beyond ASCII as the renderer shapes them: the font and glyph,
    /// or None for a character with no single glyph, which stays text.
    extra: HashMap<u32, Option<(u32, i32)>, BuildHasherDefault<CpHasher>>,
}

#[derive(Default)]
enum Ascii {
    /// The renderer has not said; its text goes out as fallback until it does.
    #[default]
    Unasked,
    /// This face cannot be drawn by number; its text is always fallback.
    Missing,
    Table { font: u32, ids: [i32; ASCII_GLYPHS] },
}

impl Layout {
    /// Starts over for new metrics or a new font: every glyph forgotten.
    ///
    /// `runs` off leaves only the hashes, for a renderer that walks the cells
    /// itself. `nerd` sends private-use codepoints to [`SLOT_SYMBOL`].
    pub fn reset(&mut self, runs: bool, nerd: bool, cell_w: f32, baseline: f32) {
        self.runs = runs;
        self.nerd = nerd;
        self.cell_w = cell_w;
        self.baseline = baseline;
        self.high_water = 0;
        for slot in &mut self.slots {
            slot.ascii = Ascii::Unasked;
            slot.extra.clear();
        }
        self.generation += 1;
    }

    /// The ASCII glyphs of one slot, all out of `font`; None when that face
    /// cannot be drawn by number.
    pub fn set_ascii(&mut self, slot: usize, table: Option<(u32, &[i32; ASCII_GLYPHS])>) {
        let Some(s) = self.slots.get_mut(slot) else {
            return;
        };
        s.ascii = match table {
            Some((font, ids)) => Ascii::Table { font, ids: *ids },
            None => Ascii::Missing,
        };
        s.extra.clear();
        self.generation += 1;
    }

    /// What the renderer found `cp` to be in `slot`: a glyph of a font, or
    /// None when it is not a single glyph and has to stay text.
    pub fn learn(&mut self, slot: usize, cp: u32, glyph: Option<(u32, i32)>) {
        let Some(s) = self.slots.get_mut(slot) else {
            return;
        };
        s.extra.insert(cp, glyph);
        self.generation += 1;
    }

    /// Moves whenever anything above changes, so a packed buffer made with
    /// what came before is packed again.
    pub fn generation(&self) -> u64 {
        self.generation
    }
}

/// Appends the row table to a packed snapshot and points the header at it,
/// with each row's runs when `layout` asks for them.
///
/// Leaves the buffer alone when it is shorter than its header says, which is
/// a backend that packed nothing; the offset stays zero and the renderer takes
/// the path that walks the cells.
pub fn append_rows(buf: &mut Vec<u8>, layout: &mut Layout) {
    let Some((cols, rows)) = grid_size(buf) else {
        return;
    };
    let cells_end = HEADER_BYTES + cols * rows * CELL_BYTES;
    buf.truncate(cells_end);
    // Aligned so the renderer can read everything from here on through int
    // and float views of the whole buffer.
    let table = (cells_end + 7) & !7;
    buf.resize(table + rows * ROW_ENTRY_BYTES, 0);
    if layout.runs {
        // About what a busy screen comes to, so the appends below rarely grow
        // the buffer, and each growth copies everything packed before it.
        buf.reserve(cols * rows * 12 + rows * 32);
    }
    let row_bytes = cols * CELL_BYTES;
    for row in 0..rows {
        let start = HEADER_BYTES + row * row_bytes;
        let cells = &buf[start..start + row_bytes];
        let hash = row_hash(cells);
        let (bg_at, text_at) = if layout.runs {
            layout.gather(cells);
            layout.scratch.write(buf)
        } else {
            (0, 0)
        };
        let e = table + row * ROW_ENTRY_BYTES;
        buf[e..e + 8].copy_from_slice(&hash.to_le_bytes());
        buf[e + 8..e + 12].copy_from_slice(&bg_at.to_le_bytes());
        buf[e + 12..e + 16].copy_from_slice(&text_at.to_le_bytes());
    }
    buf[ROWS_OFFSET..ROWS_OFFSET + 4].copy_from_slice(&(table as u32).to_le_bytes());
    if layout.runs {
        // Padded to the longest packing yet. The renderer makes a new view of
        // the buffer whenever its length changes, which is how it learns that
        // the memory may have moved; runs are as long as the text on screen,
        // and without this every frame of new output was a new length.
        layout.high_water = layout.high_water.max(buf.len().next_multiple_of(PAD));
        buf.resize(layout.high_water, 0);
    }
}

/// What the packed length is rounded up to while it grows.
const PAD: usize = 16 * 1024;

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

#[inline]
fn u32_at(b: &[u8], at: usize) -> u32 {
    u32::from_le_bytes([b[at], b[at + 1], b[at + 2], b[at + 3]])
}

#[inline]
fn style_slot(style: u16) -> usize {
    match (style & CellFlags::BOLD != 0, style & CellFlags::ITALIC != 0) {
        (true, true) => SLOT_BOLD_ITALIC,
        (true, false) => SLOT_BOLD,
        (false, true) => SLOT_ITALIC,
        (false, false) => SLOT_REGULAR,
    }
}

impl Layout {
    /// One row's runs into the scratch, decided exactly as the renderer's own
    /// cell walk decides them.
    fn gather(&mut self, cells: &[u8]) {
        let Layout { nerd, cell_w, baseline, slots, scratch: sc, .. } = self;
        let (nerd, cell_w, baseline) = (*nerd, *cell_w, *baseline);
        sc.clear();

        // Backgrounds: one rectangle per stretch of one colour. The renderer
        // leaves out the ones in its own background colour.
        let mut run_start = 0usize;
        let mut run_bg = 0u32;
        for (c, cell) in cells.chunks_exact(CELL_BYTES).enumerate() {
            let bg = u32_at(cell, 8);
            if c > 0 && bg != run_bg {
                sc.bg.push(run_start as u32 | (c as u32) << 16);
                sc.bg.push(run_bg);
                run_start = c;
            }
            run_bg = bg;
        }
        let cols = cells.len() / CELL_BYTES;
        if cols > 0 {
            sc.bg.push(run_start as u32 | (cols as u32) << 16);
            sc.bg.push(run_bg);
        }

        // Text: printable ASCII gathered into runs of one colour and style,
        // anything else a run of its own, which is where the icon font gets
        // its chance to take over.
        let mut start = 0usize;
        let mut fg_run = 0u32;
        let mut style_run = 0u16;
        for (c, cell) in cells.chunks_exact(CELL_BYTES).enumerate() {
            let cp = u32_at(cell, 0);
            let fg = u32_at(cell, 4);
            let flags = u16::from_le_bytes([cell[12], cell[13]]);
            let style = flags & STYLE_MASK;
            let ascii = (0x20..=0x7e).contains(&cp);
            let hidden = flags & CellFlags::HIDDEN != 0;
            // A hidden cell ends the run too. Skipped inside one, the text
            // after it would be drawn a column early.
            if !sc.run.is_empty() && (fg != fg_run || style != style_run || !ascii || hidden || sc.run.len() >= RUN_MAX) {
                sc.flush_run(slots, start, fg_run, style_run, cell_w, baseline);
            }
            if flags & CellFlags::WIDE_SPACER != 0 || hidden {
                continue;
            }
            if ascii {
                if sc.run.is_empty() {
                    start = c;
                    fg_run = fg;
                    style_run = style;
                }
                sc.run.push(cp);
            } else if cp != 0 {
                let slot = if nerd && is_symbol(cp) { SLOT_SYMBOL } else { style_slot(style) };
                let s = &slots[slot];
                match &s.ascii {
                    Ascii::Table { .. } => match s.extra.get(&cp) {
                        Some(Some((font, id))) => {
                            sc.batch(0xff00_0000 | fg, *font).push(*id, c as f32 * cell_w, baseline)
                        }
                        Some(None) => sc.fallback(c, fg, style, slot, false, &[cp]),
                        None => sc.fallback(c, fg, style, slot, true, &[cp]),
                    },
                    Ascii::Unasked => sc.fallback(c, fg, style, slot, true, &[cp]),
                    Ascii::Missing => sc.fallback(c, fg, style, slot, false, &[cp]),
                }
                if style & DECORATIONS != 0 {
                    let wide = if flags & CellFlags::WIDE != 0 { 2 } else { 1 };
                    sc.decoration(c, wide, fg, style);
                }
            }
        }
        if !sc.run.is_empty() {
            sc.flush_run(slots, start, fg_run, style_run, cell_w, baseline);
        }
    }
}

/// Where one row's runs are put together before they are appended, kept
/// between rows and frames so packing allocates nothing once it has warmed up.
#[derive(Default)]
struct Scratch {
    /// The ASCII run being gathered.
    run: Vec<u32>,
    bg: Vec<u32>,
    batches: Vec<Batch>,
    /// How many of [`Self::batches`] this row uses; the rest wait for reuse.
    live: usize,
    /// The batch the last glyph went in: a run's glyphs share one.
    last: usize,
    decorations: Vec<u32>,
    fallbacks: Vec<u32>,
    decoration_count: u32,
    fallback_count: u32,
}

#[derive(Default)]
struct Batch {
    color: u32,
    font: u32,
    ids: Vec<i32>,
    pos: Vec<f32>,
}

impl Batch {
    #[inline]
    fn push(&mut self, id: i32, x: f32, y: f32) {
        self.ids.push(id);
        self.pos.push(x);
        self.pos.push(y);
    }
}

impl Scratch {
    fn clear(&mut self) {
        self.run.clear();
        self.bg.clear();
        self.live = 0;
        self.last = 0;
        self.decorations.clear();
        self.fallbacks.clear();
        self.decoration_count = 0;
        self.fallback_count = 0;
    }

    /// The batch for one colour out of one font, opened if the row has none.
    fn batch(&mut self, color: u32, font: u32) -> &mut Batch {
        let hit = |b: &Batch| b.color == color && b.font == font;
        if self.last < self.live && hit(&self.batches[self.last]) {
            return &mut self.batches[self.last];
        }
        let i = match self.batches[..self.live].iter().position(hit) {
            Some(i) => i,
            None => {
                if self.live == self.batches.len() {
                    self.batches.push(Batch::default());
                }
                let b = &mut self.batches[self.live];
                b.color = color;
                b.font = font;
                b.ids.clear();
                b.pos.clear();
                self.live += 1;
                self.live - 1
            }
        };
        self.last = i;
        &mut self.batches[i]
    }

    fn decoration(&mut self, col: usize, cols: usize, fg: u32, style: u16) {
        self.decorations.push(col as u32 | (cols as u32) << 16);
        self.decorations.push(fg);
        self.decorations.push(style as u32);
        self.decoration_count += 1;
    }

    fn fallback(&mut self, col: usize, fg: u32, style: u16, slot: usize, learn: bool, cps: &[u32]) {
        self.fallbacks.push(col as u32 | (cps.len() as u32) << 16);
        self.fallbacks.push(fg);
        self.fallbacks.push(style as u32 | (slot as u32) << 16 | if learn { FALLBACK_LEARN } else { 0 });
        self.fallbacks.extend_from_slice(cps);
        self.fallback_count += 1;
    }

    /// The ASCII run gathered so far, drawn by number where its face has a
    /// table, and as text where it has not.
    fn flush_run(&mut self, slots: &[Slot; SLOTS], start: usize, fg: u32, style: u16, cell_w: f32, baseline: f32) {
        let len = self.run.len();
        // A run of spaces is nothing but its background, which is already
        // drawn, unless it is underlined or struck through.
        let blank = self.run.iter().all(|&cp| cp == 0x20);
        if !blank {
            let slot = style_slot(style);
            match &slots[slot].ascii {
                Ascii::Table { font, ids } => {
                    let font = *font;
                    for i in 0..len {
                        let cp = self.run[i];
                        if cp == 0x20 {
                            continue;
                        }
                        let id = ids[(cp - 0x21) as usize];
                        self.batch(0xff00_0000 | fg, font).push(id, (start + i) as f32 * cell_w, baseline);
                    }
                }
                ascii => {
                    let learn = matches!(ascii, Ascii::Unasked);
                    let run = std::mem::take(&mut self.run);
                    self.fallback(start, fg, style, slot, learn, &run);
                    self.run = run;
                }
            }
        }
        if style & DECORATIONS != 0 {
            self.decoration(start, len, fg, style);
        }
        self.run.clear();
    }

    /// Appends the row's runs to `buf`; returns where its backgrounds and its
    /// text start.
    fn write(&self, buf: &mut Vec<u8>) -> (u32, u32) {
        fn put(buf: &mut Vec<u8>, v: u32) {
            buf.extend_from_slice(&v.to_le_bytes());
        }
        let bg_at = buf.len() as u32;
        put(buf, (self.bg.len() / 2) as u32);
        for &w in &self.bg {
            put(buf, w);
        }
        let text_at = buf.len() as u32;
        put(buf, self.fallback_count);
        put(buf, self.decoration_count);
        put(buf, self.live as u32);
        for &w in &self.fallbacks {
            put(buf, w);
        }
        for &w in &self.decorations {
            put(buf, w);
        }
        for b in &self.batches[..self.live] {
            put(buf, b.color);
            put(buf, b.font);
            put(buf, b.ids.len() as u32);
            for &id in &b.ids {
                put(buf, id as u32);
            }
            for &p in &b.pos {
                put(buf, p.to_bits());
            }
        }
        (bg_at, text_at)
    }
}

/// Hashes a codepoint with one multiply. The standard hasher is built to
/// resist chosen keys, which a map of the glyphs on screen has no use for,
/// and it is looked up for every cell outside ASCII on every packing.
#[derive(Default)]
struct CpHasher(u64);

impl Hasher for CpHasher {
    fn finish(&self) -> u64 {
        self.0
    }
    fn write(&mut self, bytes: &[u8]) {
        for &b in bytes {
            self.0 = (self.0.rotate_left(8) ^ b as u64).wrapping_mul(0x9e37_79b9_7f4a_7c15);
        }
    }
    fn write_u32(&mut self, v: u32) {
        self.0 = (v as u64).wrapping_mul(0x9e37_79b9_7f4a_7c15);
    }
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
        append_rows(&mut buf, &mut Layout::default());
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
        append_rows(&mut buf, &mut Layout::default());
        let at = u32::from_le_bytes(buf[ROWS_OFFSET..ROWS_OFFSET + 4].try_into().unwrap()) as usize;
        assert_eq!(at % 8, 0);
        assert_eq!(buf.len(), at + 3 * ROW_ENTRY_BYTES);
        let first = buf.clone();
        append_rows(&mut buf, &mut Layout::default());
        assert_eq!(buf, first);
    }

    #[test]
    fn a_short_buffer_gets_no_table() {
        let mut buf = grid(4, &["ab"]);
        buf.truncate(HEADER_BYTES + 2);
        append_rows(&mut buf, &mut Layout::default());
        assert_eq!(table_hash(&buf, 0), None);
    }

    // ---- runs --------------------------------------------------------------

    const CELL_W: f32 = 10.0;
    const BASELINE: f32 = 12.0;

    #[derive(Debug, Default, PartialEq)]
    struct Runs {
        bg: Vec<(u32, u32, u32)>,
        /// argb, font, (glyph, x, y)
        batches: Vec<(u32, u32, Vec<(i32, f32, f32)>)>,
        /// col, cols, rgb, flags
        decorations: Vec<(u32, u32, u32, u32)>,
        /// col, rgb, flags word, codepoints
        fallbacks: Vec<(u32, u32, u32, Vec<u32>)>,
    }

    /// Reads a row's runs back the way the renderer does.
    fn runs(buf: &[u8], row: usize) -> Runs {
        let table = u32_at(buf, ROWS_OFFSET) as usize;
        let e = table + row * ROW_ENTRY_BYTES;
        let at = std::cell::Cell::new(u32_at(buf, e + 8) as usize);
        let next = || {
            let v = u32_at(buf, at.get());
            at.set(at.get() + 4);
            v
        };
        let mut r = Runs::default();
        for _ in 0..next() {
            let span = next();
            r.bg.push((span & 0xffff, span >> 16, next()));
        }
        assert_eq!(at.get(), u32_at(buf, e + 12) as usize, "text follows the backgrounds");
        let (fallbacks, decorations, batches) = (next(), next(), next());
        for _ in 0..fallbacks {
            let span = next();
            let (rgb, flags) = (next(), next());
            let cps = (0..span >> 16).map(|_| next()).collect();
            r.fallbacks.push((span & 0xffff, rgb, flags, cps));
        }
        for _ in 0..decorations {
            let span = next();
            r.decorations.push((span & 0xffff, span >> 16, next(), next()));
        }
        for _ in 0..batches {
            let (color, font, n) = (next(), next(), next() as usize);
            let ids: Vec<i32> = (0..n).map(|_| next() as i32).collect();
            let glyphs = ids.into_iter().map(|id| (id, f32::from_bits(next()), f32::from_bits(next()))).collect();
            r.batches.push((color, font, glyphs));
        }
        r
    }

    /// Glyph ids that say which character they are: `a` is 1000 + 'a'.
    fn ascii_ids(base: i32) -> [i32; ASCII_GLYPHS] {
        std::array::from_fn(|i| base + 0x21 + i as i32)
    }

    fn layout() -> Layout {
        let mut l = Layout::default();
        l.reset(true, true, CELL_W, BASELINE);
        l.set_ascii(SLOT_REGULAR, Some((0, &ascii_ids(1000))));
        l.set_ascii(SLOT_BOLD, Some((1, &ascii_ids(2000))));
        l
    }

    fn packed(cols: u16, cells: &[(u32, u32, u32, u16)], layout: &mut Layout) -> Vec<u8> {
        let mut buf = Vec::new();
        let mut w = SnapshotWriter::new(&mut buf, &header(cols, 1));
        for i in 0..cols as usize {
            let (cp, fg, bg, flags) = cells.get(i).copied().unwrap_or((' ' as u32, FG, BG, 0));
            w.cell(cp, fg, bg, flags);
        }
        append_rows(&mut buf, layout);
        buf
    }

    fn text(s: &str, fg: u32, flags: u16) -> Vec<(u32, u32, u32, u16)> {
        s.chars().map(|c| (c as u32, fg, BG, flags)).collect()
    }

    #[test]
    fn backgrounds_come_merged_into_one_rectangle_per_stretch() {
        let mut cells = text("abcdef", FG, 0);
        cells[2].2 = 0x223344;
        cells[3].2 = 0x223344;
        let r = runs(&packed(8, &cells, &mut layout()), 0);
        assert_eq!(r.bg, vec![(0, 2, BG), (2, 4, 0x223344), (4, 8, BG)]);
    }

    #[test]
    fn ascii_is_numbered_placed_and_batched_by_colour_and_face() {
        let mut cells = text("ab cd", FG, 0);
        cells.extend(text("X", 0xff0000, 0));
        cells.extend(text("e", FG, 0));
        cells.extend(text("B", FG, CellFlags::BOLD));
        let r = runs(&packed(10, &cells, &mut layout()), 0);
        let at = |col: usize| col as f32 * CELL_W;
        assert_eq!(
            r.batches,
            vec![
                // Split by the red X, and joined again: one call per colour.
                (
                    0xff00_0000 | FG,
                    0,
                    vec![
                        (1000 + 'a' as i32, at(0), BASELINE),
                        (1000 + 'b' as i32, at(1), BASELINE),
                        (1000 + 'c' as i32, at(3), BASELINE),
                        (1000 + 'd' as i32, at(4), BASELINE),
                        (1000 + 'e' as i32, at(6), BASELINE),
                    ]
                ),
                (0xffff_0000, 0, vec![(1000 + 'X' as i32, at(5), BASELINE)]),
                (0xff00_0000 | FG, 1, vec![(2000 + 'B' as i32, at(7), BASELINE)]),
            ]
        );
        assert!(r.decorations.is_empty() && r.fallbacks.is_empty());
    }

    #[test]
    fn a_face_without_a_table_is_handed_over_as_text_and_asked_about() {
        let cells = text("hi there", FG, CellFlags::ITALIC);
        let r = runs(&packed(10, &cells, &mut layout()), 0);
        assert!(r.batches.is_empty());
        let flags = CellFlags::ITALIC as u32 | (SLOT_ITALIC as u32) << 16 | FALLBACK_LEARN;
        let cps = "hi there".chars().map(|c| c as u32).collect();
        assert_eq!(r.fallbacks, vec![(0, FG, flags, cps)]);

        // Once the face is known to have no table, it is not asked about again.
        let mut l = layout();
        l.set_ascii(SLOT_ITALIC, None);
        let r = runs(&packed(10, &cells, &mut l), 0);
        assert_eq!(r.fallbacks[0].2 & FALLBACK_LEARN, 0);
    }

    #[test]
    fn a_character_outside_ascii_is_asked_about_once_and_then_numbered() {
        let cells = vec![('─' as u32, FG, BG, 0), ('─' as u32, FG, BG, 0)];
        let mut l = layout();
        let r = runs(&packed(4, &cells, &mut l), 0);
        let learn = SLOT_REGULAR as u32 | FALLBACK_LEARN;
        assert_eq!(r.fallbacks, vec![(0, FG, learn, vec!['─' as u32]), (1, FG, learn, vec!['─' as u32])]);

        let before = l.generation();
        l.learn(SLOT_REGULAR, '─' as u32, Some((3, 77)));
        assert_ne!(l.generation(), before, "a packing made before this has to be made again");
        let r = runs(&packed(4, &cells, &mut l), 0);
        assert!(r.fallbacks.is_empty());
        assert_eq!(r.batches, vec![(0xff00_0000 | FG, 3, vec![(77, 0.0, BASELINE), (77, CELL_W, BASELINE)])]);

        // No single glyph: text for good, and never asked about again.
        l.learn(SLOT_REGULAR, '─' as u32, None);
        let r = runs(&packed(4, &cells, &mut l), 0);
        assert_eq!(r.fallbacks[0].2, SLOT_REGULAR as u32);
    }

    #[test]
    fn a_wide_character_covers_two_cells_and_the_next_one_lands_after_it() {
        let mut l = layout();
        l.learn(SLOT_REGULAR, 0x4e2d, Some((5, 9)));
        let cells = vec![
            (0x4e2d, FG, BG, CellFlags::WIDE | CellFlags::UNDERLINE),
            (0, FG, BG, CellFlags::WIDE_SPACER | CellFlags::UNDERLINE),
            ('a' as u32, FG, BG, 0),
        ];
        let r = runs(&packed(4, &cells, &mut l), 0);
        assert_eq!(
            r.batches,
            vec![(0xff00_0000 | FG, 5, vec![(9, 0.0, BASELINE)]), (0xff00_0000 | FG, 0, vec![(1000 + 'a' as i32, 2.0 * CELL_W, BASELINE)])]
        );
        // The underline spans both cells, once.
        assert_eq!(r.decorations, vec![(0, 2, FG, CellFlags::UNDERLINE as u32)]);
    }

    #[test]
    fn icons_go_to_the_symbol_face_only_when_asked_to() {
        let cells = vec![(0xe0b0, FG, BG, CellFlags::BOLD)];
        let r = runs(&packed(2, &cells, &mut layout()), 0);
        assert_eq!(r.fallbacks[0].2 >> 16 & 0xff, SLOT_SYMBOL as u32);
        let mut l = layout();
        l.reset(true, false, CELL_W, BASELINE);
        let r = runs(&packed(2, &cells, &mut l), 0);
        assert_eq!(r.fallbacks[0].2 >> 16 & 0xff, SLOT_BOLD as u32);
    }

    #[test]
    fn decorations_cover_their_run_spaces_and_all() {
        let mut cells = text("ab  ", FG, CellFlags::UNDERLINE);
        cells.extend(text("  ", FG, CellFlags::STRIKEOUT));
        let r = runs(&packed(8, &cells, &mut layout()), 0);
        assert_eq!(
            r.decorations,
            vec![(0, 4, FG, CellFlags::UNDERLINE as u32), (4, 2, FG, CellFlags::STRIKEOUT as u32)]
        );
        // Only the two letters are glyphs; the spaces are their background.
        assert_eq!(r.batches[0].2.len(), 2);
    }

    #[test]
    fn a_hidden_cell_draws_nothing_and_moves_nothing_after_it() {
        let mut cells = text("abc", FG, 0);
        cells[1].3 = CellFlags::HIDDEN;
        let r = runs(&packed(4, &cells, &mut layout()), 0);
        let glyphs: Vec<(i32, f32)> = r.batches[0].2.iter().map(|g| (g.0, g.1)).collect();
        assert_eq!(glyphs, vec![(1000 + 'a' as i32, 0.0), (1000 + 'c' as i32, 2.0 * CELL_W)]);
    }

    #[test]
    fn nothing_but_hashes_unless_runs_are_asked_for() {
        let buf = packed(4, &text("ab", FG, 0), &mut Layout::default());
        let e = u32_at(&buf, ROWS_OFFSET) as usize;
        assert_eq!((u32_at(&buf, e + 8), u32_at(&buf, e + 12)), (0, 0));
        assert_eq!(buf.len(), e + ROW_ENTRY_BYTES);
    }

    #[test]
    fn the_length_only_moves_when_a_packing_outgrows_the_last() {
        let mut l = layout();
        let short = packed(40, &text("a", FG, 0), &mut l).len();
        let long = packed(40, &text("the quick brown fox jumps over the lazy dog", FG, 0), &mut l).len();
        assert_eq!(short, long);
        assert_eq!(packed(40, &[], &mut l).len(), long);
    }

    #[test]
    fn a_blank_row_has_no_text() {
        let r = runs(&packed(6, &[], &mut layout()), 0);
        assert_eq!(r.bg, vec![(0, 6, BG)]);
        assert!(r.batches.is_empty() && r.decorations.is_empty() && r.fallbacks.is_empty());
    }
}
