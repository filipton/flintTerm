//! [`term_core::Emulator`] implementation backed by libghostty-vt.
//!
//! The alternative to `term-alacritty`, selected with the `ghostty` feature of
//! the `flintterm` crate. It answers exactly the same trait, so the FFI, the
//! snapshot format and the Kotlin renderer are untouched by the swap.
//!
//! Colours are resolved here rather than by libghostty so the app's own
//! [`Palette`] (its colour schemes) keeps deciding what the default foreground,
//! background and the 256 indexed colours look like — the same division of
//! labour the alacritty backend uses.

mod cell;

use std::ffi::{c_int, c_void};

use ghostty_vt_sys as sys;
use term_core::images::{self, Draw, ImageStore, Mark, Outcome, PlacedImage};
use term_core::marks::{self, PromptMarkAt};
use term_core::{
    encode_cell, CellFlags, Emulator, EmulatorEvent, InterceptEvent, InterceptOptions, Interceptor, Palette,
    PromptMark, SelectionKind, SnapshotHeader, SnapshotWriter, TermModes, ViewPoint, CELL_BYTES, HEADER_BYTES,
};

/// Fills one row of the cache left to right. Mirrors `SnapshotWriter::cell`,
/// but overwrites a row in place instead of appending a whole grid.
struct RowWriter<'a> {
    buf: &'a mut [u8],
    at: usize,
}

impl RowWriter<'_> {
    #[inline]
    fn cell(&mut self, codepoint: u32, fg: u32, bg: u32, flags: u16) {
        if let Some(dst) = self.buf.get_mut(self.at..self.at + CELL_BYTES) {
            encode_cell(dst, codepoint, fg, bg, flags);
            self.at += CELL_BYTES;
        }
    }
}

/// Side table the C callbacks write into. Owned as a raw pointer so a callback
/// firing inside `ghostty_terminal_vt_write` does not alias the `&mut self` the
/// caller is holding.
#[derive(Default)]
struct Callbacks {
    events: Vec<EmulatorEvent>,
    /// Set by the title callback; the title itself is read once the VT write
    /// has returned, which is when libghostty says the new value is readable.
    title_changed: bool,
    /// Whether the sixel decoder sitting in front of libghostty is switched
    /// on, so the device-attributes answer can claim it.
    sixel: bool,
}

/// A prompt mark on the pen, waiting for the shell to print something; see
/// [`marks`] for why the mark rides the pen rather than the cell.
struct PendingMark {
    serial: u32,
    /// Everything the shell said with nothing printed in between, in order.
    marks: Vec<PromptMark>,
    /// Which screen it was announced on. A mark belongs to the shell's own
    /// screen, so one still waiting when a program takes the alt screen is
    /// dropped rather than pinned to the first cell that program draws.
    alt_screen: bool,
    /// How many bytes have been fed one at a time on its behalf.
    probed: usize,
}

/// How far a mark is followed byte by byte before it is given up on. A shell
/// prints its prompt within a few dozen bytes of announcing it; this far past
/// that, what is coming is not the prompt.
const MARK_PROBE_LIMIT: usize = 4096;

/// Last frame's cell bytes, so a snapshot only rebuilds the rows that changed.
///
/// libghostty tracks which rows are dirty and accumulates that across
/// `render_state_update` calls until `render_state_clean`, so the snapshot can
/// update as often as it likes and clean once per frame it actually serialised.
/// A full rebuild is forced by anything the row flags do not cover — a resize,
/// a palette change, or libghostty itself reporting the frame fully dirty,
/// which it does for a scroll, a screen switch, and a selection change.
#[derive(Default)]
struct RowCache {
    /// `rows * cols * CELL_BYTES` of cells, without the header.
    cells: Vec<u8>,
    cols: u16,
    rows: u16,
    palette: Option<Palette>,
    /// False until a frame has filled `cells`, and again whenever a frame
    /// bypassed the cache.
    valid: bool,
}

pub struct GhosttyEmulator {
    term: sys::GhosttyTerminal,
    render: sys::GhosttyRenderState,
    rows_iter: sys::GhosttyRenderStateRowIterator,
    cells_iter: sys::GhosttyRenderStateRowCells,
    cb: *mut Callbacks,
    cols: u16,
    rows: u16,
    /// Borrowed only inside `snapshot`, which the session serialises.
    cache: std::cell::RefCell<RowCache>,
    /// Set by tests to make every snapshot a full rebuild, so the cached and
    /// uncached results can be compared.
    always_full: bool,
    intercept: Interceptor,
    /// Scratch buffer for the filtered stream, kept so a busy session is not
    /// allocating a vector per chunk of output.
    filtered: Vec<u8>,
    images: ImageStore,
    /// The prompt mark waiting for a cell to live on.
    pending_mark: Option<PendingMark>,
    /// Tells one pending mark's cells from an older mark's. It only has to be
    /// unique among the marks in the buffer, so wrapping is not a worry.
    mark_serial: u32,
    /// Whether a private hyperlink — an image placement or a prompt mark — was
    /// ever written into the grid. Until one was, no linked cell needs its URI
    /// read to know it is somebody else's.
    private_links_written: bool,
    bold_is_bright: bool,
}

// The handles are only ever touched through `&mut self` (or `&self` for reads,
// which libghostty allows), and the session that owns the emulator keeps it
// behind a mutex.
unsafe impl Send for GhosttyEmulator {}

impl GhosttyEmulator {
    pub fn new(cols: u16, rows: u16, scrollback: usize) -> Self {
        let cols = cols.max(2);
        let rows = rows.max(1);
        unsafe {
            let mut term: sys::GhosttyTerminal = std::ptr::null_mut();
            let rc = sys::ghostty_terminal_new(std::ptr::null(), &mut term, cols, rows);
            assert_eq!(rc, sys::GHOSTTY_SUCCESS, "ghostty_terminal_new failed ({rc})");

            let mut render: sys::GhosttyRenderState = std::ptr::null_mut();
            let rc = sys::ghostty_render_state_new(std::ptr::null(), &mut render);
            assert_eq!(rc, sys::GHOSTTY_SUCCESS, "ghostty_render_state_new failed ({rc})");

            let mut rows_iter: sys::GhosttyRenderStateRowIterator = std::ptr::null_mut();
            let rc = sys::ghostty_render_state_row_iterator_new(std::ptr::null(), &mut rows_iter);
            assert_eq!(rc, sys::GHOSTTY_SUCCESS, "row iterator alloc failed ({rc})");

            let mut cells_iter: sys::GhosttyRenderStateRowCells = std::ptr::null_mut();
            let rc = sys::ghostty_render_state_row_cells_new(std::ptr::null(), &mut cells_iter);
            assert_eq!(rc, sys::GHOSTTY_SUCCESS, "row cells alloc failed ({rc})");

            let cb = Box::into_raw(Box::new(Callbacks::default()));
            let mut intercept = Interceptor::new(InterceptOptions::default());
            // libghostty has no switch for the kitty keyboard protocol and
            // would answer a program's query by itself, so the filter is the
            // switch: see `Interceptor::set_keyboard_gate`.
            intercept.set_keyboard_gate(true);
            let me = Self {
                term,
                render,
                rows_iter,
                cells_iter,
                cb,
                cols,
                rows,
                cache: Default::default(),
                always_full: false,
                intercept,
                filtered: Vec::new(),
                images: ImageStore::new(),
                pending_mark: None,
                mark_serial: 0,
                private_links_written: false,
                bold_is_bright: false,
            };

            let max_lines = scrollback;
            me.set(sys::GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_LINES, &max_lines);
            // libghostty also caps scrollback by bytes, and that limit defaults
            // to 10 KB against a page size of ~400 KB — so out of the box it
            // keeps about one page of history whatever the line limit says, and
            // a request for ten thousand lines yields a few hundred. A null
            // value removes it, leaving the line count the only governor, which
            // is what the app's setting means and what alacritty does.
            me.set_raw(sys::GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_BYTES, std::ptr::null());
            // Userdata and the callbacks are passed *as* the value, not behind
            // a pointer to it, which is what the "Input type" lines in
            // terminal.h mean for those options.
            me.set_raw(sys::GHOSTTY_TERMINAL_OPT_USERDATA, cb as *const c_void);
            me.set_raw(sys::GHOSTTY_TERMINAL_OPT_WRITE_PTY, on_write_pty as *const c_void);
            me.set_raw(sys::GHOSTTY_TERMINAL_OPT_BELL, on_bell as *const c_void);
            me.set_raw(sys::GHOSTTY_TERMINAL_OPT_TITLE_CHANGED, on_title as *const c_void);
            me.set_raw(
                sys::GHOSTTY_TERMINAL_OPT_DEVICE_ATTRIBUTES,
                on_device_attributes as *const c_void,
            );
            me.set_raw(
                sys::GHOSTTY_TERMINAL_OPT_CLIPBOARD_WRITE,
                on_clipboard_write as *const c_void,
            );
            // No clipboard *read* callback is installed: without one libghostty
            // ignores OSC 52 reads, which is what we want — the Android
            // clipboard is never handed to the remote host.
            me
        }
    }

    /// Sets an option whose value is passed behind a pointer (`size_t*`, ...).
    fn set<T>(&self, option: c_int, value: &T) {
        self.set_raw(option, value as *const T as *const c_void)
    }

    /// Sets an option whose value *is* the pointer: userdata and callbacks.
    fn set_raw(&self, option: c_int, value: *const c_void) {
        let rc = unsafe { sys::ghostty_terminal_set(self.term, option, value) };
        if rc != sys::GHOSTTY_SUCCESS {
            log::warn!("ghostty_terminal_set({option}) failed: {rc}");
        }
    }

    fn get<T>(&self, data: c_int, out: &mut T) -> bool {
        unsafe {
            sys::ghostty_terminal_get(self.term, data, out as *mut T as *mut c_void)
                == sys::GHOSTTY_SUCCESS
        }
    }

    fn get_or<T: Default>(&self, data: c_int) -> T {
        let mut v = T::default();
        self.get(data, &mut v);
        v
    }

    fn mode(&self, mode: sys::GhosttyMode) -> bool {
        let mut cfg = sys::GhosttyTerminalModeConfig { mode, value: false };
        self.get(sys::GHOSTTY_TERMINAL_DATA_MODE, &mut cfg) && cfg.value
    }

    fn scrollbar(&self) -> sys::GhosttyTerminalScrollbar {
        self.get_or(sys::GHOSTTY_TERMINAL_DATA_SCROLLBAR)
    }

    /// A grid reference for a viewport cell, clamped into the grid.
    fn grid_ref(&self, at: ViewPoint) -> Option<sys::GhosttyGridRef> {
        let col = at.col.min(self.cols.saturating_sub(1));
        let row = at.row.min(self.rows.saturating_sub(1));
        self.grid_ref_at(sys::GHOSTTY_POINT_TAG_VIEWPORT, col, row as u32)
    }

    fn grid_ref_at(&self, tag: c_int, x: u16, y: u32) -> Option<sys::GhosttyGridRef> {
        let mut r = unsafe { sys::sized::<sys::GhosttyGridRef>() };
        let rc = unsafe { sys::ghostty_terminal_grid_ref(self.term, sys::GhosttyPoint::new(tag, x, y), &mut r) };
        (rc == sys::GHOSTTY_SUCCESS).then_some(r)
    }

    fn selection(&self) -> Option<sys::GhosttySelection> {
        let mut sel = unsafe { sys::sized::<sys::GhosttySelection>() };
        let rc = unsafe {
            sys::ghostty_terminal_get(
                self.term,
                sys::GHOSTTY_TERMINAL_DATA_SELECTION,
                &mut sel as *mut _ as *mut c_void,
            )
        };
        (rc == sys::GHOSTTY_SUCCESS).then_some(sel)
    }

    fn set_selection(&mut self, sel: Option<sys::GhosttySelection>) {
        let rc = unsafe {
            sys::ghostty_terminal_set(
                self.term,
                sys::GHOSTTY_TERMINAL_OPT_SELECTION,
                match &sel {
                    Some(s) => s as *const _ as *const c_void,
                    None => std::ptr::null(),
                },
            )
        };
        if rc != sys::GHOSTTY_SUCCESS {
            log::warn!("setting the ghostty selection failed: {rc}");
        }
    }

    /// Renders a selection to plain text. `None` selects the terminal's own.
    fn format(&self, sel: Option<&sys::GhosttySelection>, unwrap: bool, trim: bool) -> Option<String> {
        let mut opts = unsafe { sys::sized::<sys::GhosttyTerminalSelectionFormatOptions>() };
        opts.emit = sys::GHOSTTY_FORMATTER_FORMAT_PLAIN;
        opts.unwrap = unwrap;
        opts.trim = trim;
        opts.selection = sel.map_or(std::ptr::null(), |s| s as *const _);

        let mut need = 0usize;
        let rc = unsafe {
            sys::ghostty_terminal_selection_format_buf(self.term, opts, std::ptr::null_mut(), 0, &mut need)
        };
        if rc == sys::GHOSTTY_SUCCESS && need == 0 {
            return Some(String::new());
        }
        if rc != sys::GHOSTTY_OUT_OF_SPACE {
            return None;
        }
        let mut buf = vec![0u8; need];
        let mut written = 0usize;
        let rc = unsafe {
            sys::ghostty_terminal_selection_format_buf(
                self.term,
                opts,
                buf.as_mut_ptr(),
                buf.len(),
                &mut written,
            )
        };
        if rc != sys::GHOSTTY_SUCCESS {
            return None;
        }
        buf.truncate(written);
        Some(String::from_utf8_lossy(&buf).into_owned())
    }

    /// The text of one whole grid row, in the coordinate space of `tag`.
    fn line_text(&self, tag: c_int, y: u32) -> String {
        let (Some(start), Some(end)) =
            (self.grid_ref_at(tag, 0, y), self.grid_ref_at(tag, self.cols.saturating_sub(1), y))
        else {
            return String::new();
        };
        let mut sel = unsafe { sys::sized::<sys::GhosttySelection>() };
        sel.start = start;
        sel.end = end;
        // Neither unwrapped nor trimmed: callers index into the result by
        // column to find the word or URL under a tap.
        self.format(Some(&sel), false, false).unwrap_or_default()
    }

    fn events(&mut self) -> &mut Vec<EmulatorEvent> {
        &mut self.events_raw().events
    }

    fn events_raw(&mut self) -> &mut Callbacks {
        unsafe { &mut *self.cb }
    }

    /// The title set by OSC 0/2, or `None` when it was cleared.
    fn title(&self) -> Option<String> {
        let mut s = sys::GhosttyString::default();
        if !self.get(sys::GHOSTTY_TERMINAL_DATA_TITLE, &mut s) || s.len == 0 {
            return None;
        }
        let bytes = unsafe { std::slice::from_raw_parts(s.ptr, s.len) };
        Some(String::from_utf8_lossy(bytes).into_owned())
    }

    /// Refreshes the render state from the terminal. Both are ours alone, so
    /// this is safe from `&self`.
    fn update_render(&self) {
        let rc = unsafe { sys::ghostty_render_state_update(self.render, self.term) };
        if rc != sys::GHOSTTY_SUCCESS {
            log::warn!("ghostty_render_state_update failed: {rc}");
        }
    }

    fn render_cursor(&self) -> sys::GhosttyRenderStateCursor {
        let mut cursor = unsafe { sys::sized::<sys::GhosttyRenderStateCursor>() };
        unsafe {
            sys::ghostty_render_state_get(
                self.render,
                sys::GHOSTTY_RENDER_STATE_DATA_CURSOR,
                &mut cursor as *mut _ as *mut c_void,
            );
        }
        cursor
    }

    /// Hands bytes to libghostty and picks up what its callbacks noted.
    fn write_vt(&mut self, bytes: &[u8]) {
        if bytes.is_empty() {
            return;
        }
        unsafe { sys::ghostty_terminal_vt_write(self.term, bytes.as_ptr(), bytes.len()) };
        if std::mem::take(&mut self.events_raw().title_changed) {
            let title = self.title();
            self.events().push(EmulatorEvent::Title(title));
        }
    }

    /// The cursor in the active area, as `(col, row)`.
    fn cursor_xy(&self) -> (u16, u16) {
        (
            self.get_or::<u16>(sys::GHOSTTY_TERMINAL_DATA_CURSOR_X),
            self.get_or::<u16>(sys::GHOSTTY_TERMINAL_DATA_CURSOR_Y),
        )
    }

    fn alt_screen(&self) -> bool {
        self.get_or::<c_int>(sys::GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN) == sys::GHOSTTY_TERMINAL_SCREEN_ALTERNATE
    }

    /// The URI of the hyperlink on the cell at `r`, if it carries one.
    fn hyperlink_uri(&self, r: &sys::GhosttyGridRef) -> Option<String> {
        // Our own URIs fit here with room to spare, and so does nearly every
        // link a program writes; the retry is for the odd long one.
        let mut buf = [0u8; 256];
        let mut len = 0usize;
        let rc = unsafe { sys::ghostty_grid_ref_hyperlink_uri(r, buf.as_mut_ptr(), buf.len(), &mut len) };
        let bytes: Vec<u8> = match rc {
            sys::GHOSTTY_SUCCESS if len == 0 => return None,
            sys::GHOSTTY_SUCCESS => buf[..len].to_vec(),
            sys::GHOSTTY_OUT_OF_SPACE => {
                let mut long = vec![0u8; len];
                let mut written = 0usize;
                let rc = unsafe {
                    sys::ghostty_grid_ref_hyperlink_uri(r, long.as_mut_ptr(), long.len(), &mut written)
                };
                if rc != sys::GHOSTTY_SUCCESS {
                    return None;
                }
                long.truncate(written);
                long
            }
            _ => return None,
        };
        Some(String::from_utf8_lossy(&bytes).into_owned())
    }

    /// The packed cell at `r`, decoded.
    fn cell_at(&self, r: &sys::GhosttyGridRef) -> Option<cell::Cell> {
        let mut raw: sys::GhosttyCell = 0;
        (unsafe { sys::ghostty_grid_ref_cell(r, &mut raw) } == sys::GHOSTTY_SUCCESS).then(|| cell::decode(raw))
    }

    /// Whether the row at `r` may hold a hyperlink at all. False positives
    /// cost a scan of the row; there are no false negatives, which is what
    /// lets a buffer be searched for marks a row at a time.
    fn row_may_link(&self, r: &sys::GhosttyGridRef) -> bool {
        let mut row: sys::GhosttyRow = 0;
        if unsafe { sys::ghostty_grid_ref_row(r, &mut row) } != sys::GHOSTTY_SUCCESS {
            return true;
        }
        let mut has = true;
        unsafe { sys::ghostty_row_get(row, sys::GHOSTTY_ROW_DATA_HYPERLINK, &mut has as *mut _ as *mut c_void) };
        has
    }

    /// The private hyperlinks — ours — on row `y` of the `tag` coordinate
    /// space, as `(col, uri)`, left to right.
    fn private_links_on(&self, tag: c_int, y: u32) -> Vec<(u16, String)> {
        let mut out = Vec::new();
        let Some(first) = self.grid_ref_at(tag, 0, y) else { return out };
        if !self.row_may_link(&first) {
            return out;
        }
        for col in 0..self.cols {
            // A grid reference is a page node and a position in it, so the
            // rest of the row is the same reference moved along.
            let mut r = first;
            r.x = col;
            let Some(c) = self.cell_at(&r) else { continue };
            if !c.hyperlink {
                continue;
            }
            if let Some(uri) = self.hyperlink_uri(&r).filter(|u| is_private_uri(u)) {
                out.push((col, uri));
            }
        }
        out
    }

    /// Whether the viewport cell carries a hyperlink of ours, which the
    /// renderer must never offer as a link.
    fn is_private_link_at(&self, col: u16, row: u32) -> bool {
        self.grid_ref_at(sys::GHOSTTY_POINT_TAG_VIEWPORT, col, row)
            .and_then(|r| self.hyperlink_uri(&r))
            .is_some_and(|u| is_private_uri(&u))
    }

    /// Whether the active-area cell carries the pending mark `serial`.
    fn cell_carries(&self, x: u16, y: u16, serial: u32) -> bool {
        let Some(r) = self.grid_ref_at(sys::GHOSTTY_POINT_TAG_ACTIVE, x, y as u32) else { return false };
        let Some(c) = self.cell_at(&r) else { return false };
        c.hyperlink
            && self.hyperlink_uri(&r).and_then(|u| marks::parse_marks(&u)).is_some_and(|(s, _)| s == serial)
    }

    /// Feed one run of bytes that contains at most one escape sequence at its
    /// head, acting on any image command or prompt mark it carries before the
    /// text after it is drawn.
    fn feed_piece(&mut self, piece: &[u8]) {
        let mut filtered = std::mem::take(&mut self.filtered);
        filtered.clear();
        let events = self.intercept.feed(piece, &mut filtered);
        for e in events {
            match e {
                // These put something on the screen at the cursor, so they are
                // acted on here, where the cursor is still where the stream
                // said it was, rather than at the end of the chunk.
                InterceptEvent::KittyGraphics(payload) => {
                    let out = self.images.kitty(&payload);
                    self.apply(out);
                }
                InterceptEvent::Sixel { params, data } => {
                    let out = self.images.sixel(&params, &data);
                    self.apply(out);
                }
                // A prompt mark is about the cell the shell is on right now,
                // so it is claimed here too, before the piece's text moves it.
                InterceptEvent::Mark(m) => {
                    self.note_mark(m);
                    self.events().push(EmulatorEvent::Mark(m));
                }
                other => self.events().push(map_intercept(other)),
            }
        }
        self.write_text(&filtered);
        self.filtered = filtered;
    }

    /// Carry out what a graphics command asked for.
    fn apply(&mut self, out: Outcome) {
        if let Some(del) = out.delete {
            let hits = self.delete_hits(del.target);
            self.images.apply_delete(del, &hits);
        }
        if let Some(draw) = out.draw {
            self.draw_placement(draw);
        }
        if let Some(reply) = out.reply {
            self.events().push(EmulatorEvent::PtyWrite(reply));
        }
        if out.changed {
            self.events().push(EmulatorEvent::ImagesChanged);
        }
    }

    /// Mark the cells an image covers, by writing spaces under a private OSC 8
    /// hyperlink. The grid then owns the placement's position: it scrolls,
    /// reflows and erases it along with the text around it, and the spaces are
    /// what a selection over the image copies. The same bytes the alacritty
    /// backend writes, so the two agree on where the cursor ends up.
    fn draw_placement(&mut self, d: Draw) {
        let screen_cols = self.cols;
        let screen_rows = self.rows;
        let (cx, cy) = self.cursor_xy();
        let col0 = cx.min(screen_cols.saturating_sub(1));
        let row0 = cy.min(screen_rows.saturating_sub(1));
        let cols = d.cols.min(screen_cols - col0).max(1);
        let rows = d.rows.max(1);

        let mut seq = Vec::with_capacity(rows as usize * (cols as usize + 48));
        for index in 0..rows {
            seq.extend_from_slice(format!("\x1b]8;;{}\x1b\\", images::mark_uri(d.key, index)).as_bytes());
            seq.extend(std::iter::repeat_n(b' ', cols as usize));
            seq.extend_from_slice(b"\x1b]8;;\x1b\\");
            if index + 1 < rows {
                seq.extend_from_slice(b"\r\n");
                if col0 > 0 {
                    seq.extend_from_slice(format!("\x1b[{col0}C").as_bytes());
                }
            }
        }
        // Writing the rows may have scrolled the screen under the cursor, so
        // "where it started" is a different line now.
        let scrolled = (row0 + rows).saturating_sub(screen_rows);
        if d.keep_cursor {
            seq.extend_from_slice(format!("\x1b[{};{}H", row0 - scrolled + 1, col0 + 1).as_bytes());
        } else if d.sixel {
            // Sixel ends at the left margin of the line below the image;
            // kitty leaves the cursor just past its bottom-right cell.
            seq.extend_from_slice(b"\r\n");
        }
        self.private_links_written = true;
        self.write_vt(&seq);
    }

    /// Put a prompt mark on the pen, so the next thing the shell prints
    /// carries it.
    ///
    /// The cell under the cursor is not marked directly because it is the very
    /// cell the shell is about to write over — a prompt draws itself where the
    /// mark that announced it just was. Whatever is printed next is a cell with
    /// content on it, which nothing is going to blank behind us.
    fn note_mark(&mut self, mark: PromptMark) {
        // A mark still waiting has had every byte since it arrived looked at,
        // so nothing of it is on a cell: it joins this one on the pen.
        let mut marks = self.pending_mark.take().map(|p| p.marks).unwrap_or_default();
        marks.push(mark);
        self.mark_serial = self.mark_serial.wrapping_add(1);
        let seq = format!("\x1b]8;;{}\x1b\\", marks::mark_uri(self.mark_serial, &marks));
        self.private_links_written = true;
        self.write_vt(seq.as_bytes());
        let alt_screen = self.alt_screen();
        self.pending_mark = Some(PendingMark { serial: self.mark_serial, marks, alt_screen, probed: 0 });
    }

    /// Feeds the text of a piece, following a pending mark byte by byte until
    /// it has a cell.
    ///
    /// The alacritty backend lets a whole run of cells take the mark and then
    /// prunes the grid back to the first of them. libghostty's grid cannot be
    /// edited from outside, so the pen is closed the moment the first cell has
    /// it instead — which means knowing when that is, and a byte at a time is
    /// the only way to know. It costs a few calls per byte for the few bytes
    /// between a mark and the prompt it announces, and nothing at any other
    /// time.
    fn write_text(&mut self, text: &[u8]) {
        let mut at = 0;
        if self.pending_mark.is_some() {
            // The shell taking the pen back for a link of its own ends the
            // wait: from there on nothing printed can be ours, and the pen is
            // not ours to close.
            let end = find_osc8(text).unwrap_or(text.len());
            while at < end && self.pending_mark.is_some() {
                self.write_vt(&text[at..at + 1]);
                at += 1;
                self.settle_mark();
            }
            if end < text.len() {
                self.pending_mark = None;
            }
        }
        self.write_vt(&text[at..]);
    }

    /// Looks for the pending mark's cell after one more byte has been fed.
    fn settle_mark(&mut self) {
        let Some(pending) = self.pending_mark.as_mut() else { return };
        pending.probed += 1;
        let serial = pending.serial;
        let given_up = pending.probed > MARK_PROBE_LIMIT;
        if pending.alt_screen != self.alt_screen() {
            self.pending_mark = None;
            return;
        }
        let (x, y) = self.cursor_xy();
        // The cell just printed is left of the cursor — or under it, when it
        // was the last of the row and the cursor is waiting to wrap — and a
        // wide character leaves its spacer between the two.
        let found = (x.saturating_sub(2)..=x).rev().any(|cx| self.cell_carries(cx, y, serial));
        if found || given_up {
            // Off the pen, so the cells after it are not ours too.
            self.write_vt(b"\x1b]8;;\x1b\\");
            self.pending_mark = None;
        }
    }

    /// The placement marks in the visible grid, left to right and top to
    /// bottom.
    fn image_marks(&self) -> Vec<Mark> {
        if self.images.is_empty() {
            return Vec::new();
        }
        let mut out = Vec::new();
        for row in 0..self.rows {
            let mut skip_until = 0u16;
            for (col, uri) in self.private_links_on(sys::GHOSTTY_POINT_TAG_VIEWPORT, row as u32) {
                if col < skip_until {
                    continue;
                }
                let Some((key, index)) = images::parse_mark(&uri) else { continue };
                out.push(Mark { key, index, col, row });
                // The rest of this placement's row says nothing new; skipping
                // it also lets two images share a row.
                let width = self.images.placement(key).map(|p| p.cols).unwrap_or(1);
                skip_until = col + width.max(1);
            }
        }
        out
    }

    /// Which placements a position-based delete command hits.
    fn delete_hits(&self, target: images::DeleteTarget) -> Vec<u32> {
        use images::DeleteTarget as T;
        let (cx, cy) = self.cursor_xy();
        let at = |p: &PlacedImage, col: i64, row: i64| {
            col >= p.col as i64
                && col < p.col as i64 + p.cols as i64
                && row >= p.row as i64
                && row < p.row as i64 + p.rows as i64
        };
        self.images
            .resolve(&self.image_marks())
            .iter()
            .filter(|p| match target {
                T::Cursor => at(p, cx as i64, cy as i64),
                T::Cell { col, row } => at(p, col as i64, row as i64),
                T::Column(x) => x as i64 >= p.col as i64 && (x as i64) < p.col as i64 + p.cols as i64,
                T::Row(y) => y as i64 >= p.row as i64 && (y as i64) < p.row as i64 + p.rows as i64,
                _ => false,
            })
            .map(|p| p.key)
            .collect()
    }
}

/// Whether a hyperlink is one the app wrote into the grid to remember where
/// something is — an image placement or a prompt mark — rather than one the
/// remote side wrote for a person to follow.
fn is_private_uri(uri: &str) -> bool {
    images::parse_mark(uri).is_some() || marks::parse_marks(uri).is_some()
}

/// Where a hyperlink sequence of the program's own (`OSC 8`) begins in
/// `text`, if one does. The filter hands OSC strings on whole, so one is never
/// split across two pieces.
fn find_osc8(text: &[u8]) -> Option<usize> {
    text.windows(4).position(|w| w == b"\x1b]8;")
}

fn map_intercept(e: InterceptEvent) -> EmulatorEvent {
    match e {
        InterceptEvent::Notify { title, body } => EmulatorEvent::Notify { title, body },
        InterceptEvent::Mark(m) => EmulatorEvent::Mark(m),
        InterceptEvent::Cwd(p) => EmulatorEvent::Cwd(p),
        InterceptEvent::KittyGraphics(p) => EmulatorEvent::KittyGraphics(p),
        InterceptEvent::Sixel { params, data } => EmulatorEvent::Sixel { params, data },
        InterceptEvent::Reply(b) => EmulatorEvent::PtyWrite(b),
    }
}

// Bit positions the branchless flag build below relies on.
const BOLD_BIT: u32 = 0;
const ITALIC_BIT: u32 = 1;
const STRIKEOUT_BIT: u32 = 3;
const DIM_BIT: u32 = 4;
const HIDDEN_BIT: u32 = 8;
const _: () = assert!(
    CellFlags::BOLD == 1 << BOLD_BIT
        && CellFlags::ITALIC == 1 << ITALIC_BIT
        && CellFlags::STRIKEOUT == 1 << STRIKEOUT_BIT
        && CellFlags::DIM == 1 << DIM_BIT
        && CellFlags::HIDDEN == 1 << HIDDEN_BIT,
    "term-ghostty builds these flags by shifting; term_core::CellFlags moved"
);

/// `GHOSTTY_SGR_UNDERLINE_*` to the flag it renders as, so the style path needs
/// no branch for it either. Dotted and dashed both draw as an undercurl, which
/// is the only wavy underline the renderer has.
const UNDERLINE_FLAGS: [u16; 6] = [
    0,
    CellFlags::UNDERLINE,
    CellFlags::DOUBLE_UNDERLINE,
    CellFlags::UNDERCURL,
    CellFlags::UNDERCURL,
    CellFlags::UNDERCURL,
];

/// What one style means for the cells wearing it, resolved against the app's
/// palette. Everything here depends on the style alone, so the snapshot works
/// it out once per style per row instead of once per cell.
#[derive(Clone, Copy)]
struct Styling {
    fg: u32,
    bg: u32,
    flags: u16,
    /// Applied per cell, because a background-colour-only cell brings its own
    /// background for inverse to swap against.
    inverse: bool,
}

/// Row-local memo of [`Styling`] by style id.
///
/// Direct-mapped rather than a list to walk: a row of `ls --color` output wears
/// a couple of dozen distinct styles, and a scan of those per cell costs more
/// than the C call it was meant to save. A generation counter retires the whole
/// table between rows without touching it — which matters because style ids are
/// per page, so an entry must never outlive the row it came from.
struct StyleCache {
    slots: Box<[(u32, u16, Styling)]>,
    generation: u32,
}

impl StyleCache {
    const SLOTS: usize = 128;

    fn new(palette: &Palette) -> Self {
        let empty = Styling::new(&unsafe { sys::sized::<sys::GhosttyStyle>() }, palette, false);
        Self { slots: vec![(0, 0, empty); Self::SLOTS].into_boxed_slice(), generation: 0 }
    }

    /// Drops everything remembered for the previous row.
    fn next_row(&mut self) {
        self.generation = self.generation.wrapping_add(1);
        // Generation 0 is what an untouched slot holds, so skip it on wrap.
        if self.generation == 0 {
            self.generation = 1;
        }
    }

    #[inline]
    fn get(&self, id: u16) -> Option<Styling> {
        let slot = &self.slots[id as usize % Self::SLOTS];
        (slot.0 == self.generation && slot.1 == id).then_some(slot.2)
    }

    #[inline]
    fn put(&mut self, id: u16, styling: Styling) {
        self.slots[id as usize % Self::SLOTS] = (self.generation, id, styling);
    }
}

impl Styling {
    fn new(style: &sys::GhosttyStyle, palette: &Palette, bold_is_bright: bool) -> Self {
        let resolve = |color: &sys::GhosttyStyleColor, default: u32| -> u32 {
            unsafe {
                match color.tag {
                    sys::GHOSTTY_STYLE_COLOR_PALETTE => palette.colors[color.value.palette as usize],
                    sys::GHOSTTY_STYLE_COLOR_RGB => pack(color.value.rgb),
                    _ => default,
                }
            }
        };

        let mut fg = resolve(&style.fg_color, palette.foreground);
        // Bold-is-bright moves one of the eight ANSI colors to its bright
        // twin. A 256-color index, a true color or the default foreground has
        // no brighter version and is left alone, as the alacritty backend
        // leaves it.
        if bold_is_bright && style.bold && style.fg_color.tag == sys::GHOSTTY_STYLE_COLOR_PALETTE {
            let index = unsafe { style.fg_color.value.palette };
            if index < 8 {
                fg = palette.colors[index as usize + 8];
            }
        }
        let bg = resolve(&style.bg_color, palette.background);

        // Built without branches: this runs on the cache-miss path, which is
        // already an unpredictable branch, and a run of eight more behind it
        // showed up in the profile. The asserts above keep it honest.
        let flags = (style.bold as u16) << BOLD_BIT
            | (style.italic as u16) << ITALIC_BIT
            | (style.strikethrough as u16) << STRIKEOUT_BIT
            | (style.faint as u16) << DIM_BIT
            | (style.invisible as u16) << HIDDEN_BIT
            | UNDERLINE_FLAGS[(style.underline as usize).min(UNDERLINE_FLAGS.len() - 1)];

        Self {
            fg: if style.faint { Palette::dim(fg) } else { fg },
            bg,
            flags,
            inverse: style.inverse,
        }
    }
}

/// An empty cell reaches the renderer as a space.
///
/// libghostty leaves an untouched cell's codepoint at zero, where
/// `alacritty_terminal` stores a space. The renderer draws neither, but the two
/// backends are meant to be interchangeable down to the bytes of the snapshot —
/// that is what `term-diff` checks — so this is the side that moves.
#[inline]
fn blank_as_space(codepoint: u32) -> u32 {
    if codepoint == 0 {
        ' ' as u32
    } else {
        codepoint
    }
}

#[inline]
fn pack(rgb: sys::GhosttyColorRgb) -> u32 {
    ((rgb.r as u32) << 16) | ((rgb.g as u32) << 8) | rgb.b as u32
}

impl GhosttyEmulator {
    /// Serialises the viewport into `cells`, which must be
    /// `rows * cols * CELL_BYTES` long.
    ///
    /// With `only_dirty`, rows libghostty reports as unchanged are left exactly
    /// as they were, which is what makes the cache worth keeping.
    ///
    /// # Safety
    /// The render state must have been updated for the current terminal.
    unsafe fn write_rows(&self, palette: &Palette, cells: &mut [u8], only_dirty: bool) {
        let cols = self.cols;
        let rows = self.rows;
        let stride = cols as usize * CELL_BYTES;

        {
            // Both handle getters take a pointer *to* the handle; they fill in
            // the object it names and leave the handle itself alone, so a local
            // copy is enough to satisfy `&self`.
            let mut rows_iter = self.rows_iter;
            let mut cells_iter = self.cells_iter;
            // Everything a style implies, worked out once per style per row
            // rather than once per cell.
            let default_style = sys::sized::<sys::GhosttyStyle>();
            let plain_style = Styling::new(&default_style, palette, self.bold_is_bright);
            // Only once a placement or a prompt mark has gone into the grid
            // can a linked cell be one of ours; before that every link is the
            // program's and needs no reading.
            let private_possible = self.private_links_written;
            let mut fetched = default_style;
            let mut styles = StyleCache::new(palette);
            let fast_cells = cell::fast_path();
            let rc = sys::ghostty_render_state_get(
                self.render,
                sys::GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR,
                &mut rows_iter as *mut _ as *mut c_void,
            );
            if rc != sys::GHOSTTY_SUCCESS {
                log::warn!("ghostty row iterator failed: {rc}");
                for byte in cells.iter_mut() {
                    *byte = 0;
                }
                self.cache.borrow_mut().valid = false;
                return;
            }

            for row in 0..rows as usize {
                let mut w = RowWriter { buf: &mut cells[row * stride..][..stride], at: 0 };

                if !sys::ghostty_render_state_row_iterator_next(self.rows_iter) {
                    for _ in 0..cols {
                        w.cell(0, palette.foreground, palette.background, 0);
                    }
                    continue;
                }

                // A clean row still holds last frame's bytes, which are still
                // right. This is the whole point of the cache.
                if only_dirty {
                    let mut row_dirty = true;
                    sys::ghostty_render_state_row_get(
                        self.rows_iter,
                        sys::GHOSTTY_RENDER_STATE_ROW_DATA_DIRTY,
                        &mut row_dirty as *mut _ as *mut c_void,
                    );
                    if !row_dirty {
                        continue;
                    }
                }

                let mut row_sel = sys::sized::<sys::GhosttyRenderStateRowSelection>();
                let has_sel = sys::ghostty_render_state_row_get(
                    self.rows_iter,
                    sys::GHOSTTY_RENDER_STATE_ROW_DATA_SELECTION,
                    &mut row_sel as *mut _ as *mut c_void,
                ) == sys::GHOSTTY_SUCCESS;

                // A whole row of raw cells in one call, decoded in Rust from
                // there. Only a style still costs a trip into libghostty, and
                // only the first time each distinct style shows up in the row.
                let mut view = sys::GhosttyCellsView::default();
                sys::ghostty_render_state_row_get(
                    self.rows_iter,
                    sys::GHOSTTY_RENDER_STATE_ROW_DATA_CELLS_RAW,
                    &mut view as *mut _ as *mut c_void,
                );
                let raw: &[sys::GhosttyCell] = if view.ptr.is_null() {
                    Default::default()
                } else {
                    std::slice::from_raw_parts(view.ptr, view.len)
                };
                styles.next_row();
                let mut cells_ready = false;
                // The shortcut below reads the packed cell directly and knows
                // nothing about selection highlighting.
                let plain_row = fast_cells && !has_sel;

                for col in 0..cols {
                    let Some(&raw_cell) = raw.get(col as usize) else {
                        w.cell(0, palette.foreground, palette.background, 0);
                        continue;
                    };
                    // Most cells on a screen of text are a bare character in
                    // the default style, and those need none of the work below.
                    if plain_row && cell::is_plain(raw_cell) {
                        w.cell(
                            blank_as_space(cell::plain_codepoint(raw_cell)),
                            palette.foreground,
                            palette.background,
                            0,
                        );
                        continue;
                    }
                    let c = cell::decode_with(fast_cells, raw_cell);

                    let s = if !c.styled() {
                        plain_style
                    } else if let Some(cached) = styles.get(c.style_id) {
                        cached
                    } else {
                        if !cells_ready {
                            cells_ready = sys::ghostty_render_state_row_get(
                                self.rows_iter,
                                sys::GHOSTTY_RENDER_STATE_ROW_DATA_CELLS,
                                &mut cells_iter as *mut _ as *mut c_void,
                            ) == sys::GHOSTTY_SUCCESS;
                        }
                        // `fetched` is one buffer for the whole frame. Giving
                        // each lookup a fresh one meant zeroing 72 bytes per
                        // miss, which cost more than the calls that fill it;
                        // libghostty overwrites the struct, so only `size`
                        // needs restating, and a failed read falls back rather
                        // than leaving the previous style behind.
                        fetched.size = std::mem::size_of::<sys::GhosttyStyle>();
                        let read = cells_ready
                            && sys::ghostty_render_state_row_cells_select(self.cells_iter, col)
                                == sys::GHOSTTY_SUCCESS
                            && sys::ghostty_render_state_row_cells_get(
                                self.cells_iter,
                                sys::GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE,
                                &mut fetched as *mut _ as *mut c_void,
                            ) == sys::GHOSTTY_SUCCESS;
                        let styling =
                            if read { Styling::new(&fetched, palette, self.bold_is_bright) } else { plain_style };
                        styles.put(c.style_id, styling);
                        styling
                    };

                    let mut fg = s.fg;
                    let mut bg = match c.content_tag {
                        sys::GHOSTTY_CELL_CONTENT_BG_COLOR_PALETTE => {
                            palette.colors[c.bg_palette() as usize]
                        }
                        sys::GHOSTTY_CELL_CONTENT_BG_COLOR_RGB => c.bg_rgb(),
                        _ => s.bg,
                    };
                    // Inverse comes after the cell's own background, which is
                    // the one a bg-colour-only cell carries in its content.
                    if s.inverse {
                        std::mem::swap(&mut fg, &mut bg);
                    }

                    let mut flags = s.flags;
                    match c.wide {
                        sys::GHOSTTY_CELL_WIDE_WIDE => flags |= CellFlags::WIDE,
                        sys::GHOSTTY_CELL_WIDE_SPACER_TAIL | sys::GHOSTTY_CELL_WIDE_SPACER_HEAD => {
                            flags |= CellFlags::WIDE_SPACER
                        }
                        _ => {}
                    }
                    // An image placement and a prompt mark are both hyperlinks
                    // to the app itself, not somewhere a person could go: the
                    // renderer must never offer one as a link.
                    if c.hyperlink && !(private_possible && self.is_private_link_at(col, row as u32)) {
                        flags |= CellFlags::HYPERLINK;
                    }
                    if has_sel && col >= row_sel.start_x && col <= row_sel.end_x {
                        flags |= CellFlags::SELECTED;
                        fg = palette.foreground;
                        bg = palette.selection_bg;
                    }

                    let cp = if flags & CellFlags::WIDE_SPACER != 0 {
                        0
                    } else {
                        blank_as_space(c.codepoint())
                    };
                    w.cell(cp, fg, bg, flags);
                }
            }

            // The frame was drawn, so the rows that fed it are no longer dirty.
            sys::ghostty_render_state_clean(self.render);
        }
    }
}

impl Drop for GhosttyEmulator {
    fn drop(&mut self) {
        unsafe {
            sys::ghostty_render_state_row_cells_free(self.cells_iter);
            sys::ghostty_render_state_row_iterator_free(self.rows_iter);
            sys::ghostty_render_state_free(self.render);
            sys::ghostty_terminal_free(self.term);
            drop(Box::from_raw(self.cb));
        }
    }
}

// ---- callbacks -------------------------------------------------------------

unsafe fn callbacks<'a>(userdata: *mut c_void) -> &'a mut Callbacks {
    &mut *(userdata as *mut Callbacks)
}

unsafe extern "C" fn on_write_pty(
    _term: sys::GhosttyTerminal,
    userdata: *mut c_void,
    data: *const u8,
    len: usize,
) {
    let bytes = std::slice::from_raw_parts(data, len).to_vec();
    callbacks(userdata).events.push(EmulatorEvent::PtyWrite(bytes));
}

unsafe extern "C" fn on_bell(_term: sys::GhosttyTerminal, userdata: *mut c_void) {
    callbacks(userdata).events.push(EmulatorEvent::Bell);
}

unsafe extern "C" fn on_title(_term: sys::GhosttyTerminal, userdata: *mut c_void) {
    callbacks(userdata).title_changed = true;
}

/// Answers DA1/DA2/DA3 the way Ghostty itself does: a VT220 that speaks ANSI
/// colour — and sixel, when the decoder in front of libghostty is switched
/// on, since libghostty knows nothing about it. Without this callback
/// libghostty stays silent and programs that probe the terminal before drawing
/// hang.
unsafe extern "C" fn on_device_attributes(
    _term: sys::GhosttyTerminal,
    userdata: *mut c_void,
    out: *mut sys::GhosttyDeviceAttributes,
) -> bool {
    let attrs = &mut *out;
    attrs.primary.conformance_level = 62; // VT220
    let mut n = 0;
    if callbacks(userdata).sixel {
        attrs.primary.features[n] = 4; // sixel graphics
        n += 1;
    }
    attrs.primary.features[n] = 22; // ANSI colour
    n += 1;
    attrs.primary.num_features = n;
    attrs.secondary.device_type = 1; // VT220
    attrs.secondary.firmware_version = 10;
    attrs.secondary.rom_cartridge = 0;
    attrs.tertiary.unit_id = 0;
    true
}

/// OSC 52 write. The app decides what to do with it; we accept the request so
/// the sequence is not left half-answered.
unsafe extern "C" fn on_clipboard_write(
    _term: sys::GhosttyTerminal,
    userdata: *mut c_void,
    write: *const sys::GhosttyClipboardWrite,
) {
    let req = &*write;
    let contents = std::slice::from_raw_parts(req.contents, req.contents_len);
    // Prefer a text/plain representation, else take whatever came first.
    let pick = contents
        .iter()
        .find(|c| {
            let mime = std::slice::from_raw_parts(c.mime.ptr, c.mime.len);
            mime.starts_with(b"text/plain")
        })
        .or_else(|| contents.first());
    if let Some(c) = pick {
        let data = std::slice::from_raw_parts(c.data.ptr, c.data.len);
        let text = String::from_utf8_lossy(data).into_owned();
        callbacks(userdata).events.push(EmulatorEvent::ClipboardStore(text));
    }
    if let Some(reply) = req.reply {
        let mut r = sys::sized::<sys::GhosttyClipboardWriteReply>();
        r.result = sys::GHOSTTY_CLIPBOARD_WRITE_RESULT_SUCCESS;
        reply(write, &r);
    }
}

// ---- the trait -------------------------------------------------------------

impl Emulator for GhosttyEmulator {
    fn feed(&mut self, bytes: &[u8]) {
        if self.intercept.passthrough() {
            self.write_vt(bytes);
            return;
        }
        let opts = self.intercept.options();
        if opts.kitty_images || opts.sixel_images || opts.prompt_marks {
            // An image, and a prompt mark, land at the cursor, so the emulator
            // has to be exactly where the stream said it was when the sequence
            // arrived — and the filter reports a whole chunk at once, which
            // loses that. The chunk is therefore cut where such a sequence can
            // begin (ESC _ for kitty, ESC P for sixel, ESC ] for OSC 133) and
            // each piece is fed on its own: inside a piece nothing is printed
            // before the sequence, so acting on it before the piece's text
            // reaches the grid puts it exactly where the program meant it.
            // Output with none of them in it is not cut at all, which is what
            // keeps this off the hot path.
            let cuts_here = |b: u8| match b {
                b'_' => opts.kitty_images,
                b'P' => opts.sixel_images,
                b']' => opts.prompt_marks,
                _ => false,
            };
            let mut start = 0;
            while start < bytes.len() {
                let mut end = start + 1;
                while end + 1 < bytes.len() && !(bytes[end] == 0x1b && cuts_here(bytes[end + 1])) {
                    end += 1;
                }
                if end + 1 >= bytes.len() {
                    end = bytes.len();
                }
                self.feed_piece(&bytes[start..end]);
                start = end;
            }
            return;
        }
        let mut filtered = std::mem::take(&mut self.filtered);
        filtered.clear();
        let events = self.intercept.feed(bytes, &mut filtered);
        self.write_vt(&filtered);
        self.filtered = filtered;
        for e in events {
            self.events().push(map_intercept(e));
        }
    }

    fn set_intercept(&mut self, opts: InterceptOptions) {
        self.intercept.set_options(opts);
        if !opts.kitty_images && !opts.sixel_images {
            // Switching images off should give the memory back, not leave a
            // session holding pictures it will never draw again.
            self.images.clear();
        }
        self.events_raw().sixel = opts.sixel_images;
    }

    fn set_bold_is_bright(&mut self, on: bool) {
        if self.bold_is_bright != on {
            self.bold_is_bright = on;
            // Every bold cell changes color without its row being touched.
            self.cache.get_mut().valid = false;
        }
    }

    fn set_cell_size(&mut self, width: u32, height: u32) {
        self.images.set_cell_size(width, height);
    }

    fn images(&self) -> Vec<PlacedImage> {
        self.images.resolve(&self.image_marks())
    }

    fn prompt_marks(&self) -> Vec<PromptMarkAt> {
        if !self.private_links_written {
            return Vec::new();
        }
        let total = self.get_or::<usize>(sys::GHOSTTY_TERMINAL_DATA_TOTAL_ROWS);
        let top = self.scrollbar().offset as i64;
        let mut out = Vec::new();
        let mut last_serial = None;
        for y in 0..total {
            for (_, uri) in self.private_links_on(sys::GHOSTTY_POINT_TAG_SCREEN, y as u32) {
                let Some((serial, marks)) = marks::parse_marks(&uri) else { continue };
                // One mark is one place. A wide character's spacer, or a run
                // the pen was closed a byte late on, repeats it and says
                // nothing new.
                if last_serial == Some(serial) {
                    continue;
                }
                last_serial = Some(serial);
                let row = (y as i64 - top).clamp(i32::MIN as i64, i32::MAX as i64) as i32;
                out.extend(marks.into_iter().map(|mark| PromptMarkAt { mark, row }));
            }
        }
        out
    }

    fn image_bytes(&self, id: u32, generation: u32) -> Option<&[u8]> {
        self.images.image(id).filter(|i| i.generation == generation).map(|i| i.rgba.as_slice())
    }

    fn resize(&mut self, cols: u16, rows: u16) {
        let cols = cols.max(2);
        let rows = rows.max(1);
        if cols == self.cols && rows == self.rows {
            return;
        }
        self.cols = cols;
        self.rows = rows;
        unsafe { sys::ghostty_terminal_resize(self.term, cols, rows, 0, 0) };
    }

    fn size(&self) -> (u16, u16) {
        (self.cols, self.rows)
    }

    fn snapshot(&self, palette: &Palette, out: &mut Vec<u8>) {
        self.update_render();

        let cols = self.cols;
        let rows = self.rows;
        let bar = self.scrollbar();
        let history = bar.total.saturating_sub(bar.len);
        let display_offset = history.saturating_sub(bar.offset);

        let cursor = self.render_cursor();
        let (cursor_col, cursor_row, cursor_shape) =
            if cursor.visible && cursor.viewport_has_value && cursor.viewport_y < rows {
                let shape = match cursor.visual_style {
                    sys::GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_UNDERLINE => 1,
                    sys::GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BAR => 2,
                    _ => 0,
                };
                (cursor.viewport_x as i16, cursor.viewport_y as i16, shape)
            } else {
                (-1, -1, 3)
            };

        // The header wants viewport rows, which may be negative when the
        // selection starts above the visible area.
        let selection = self.selection().and_then(|sel| {
            let mut ordered = unsafe { sys::sized::<sys::GhosttySelection>() };
            let rc = unsafe {
                sys::ghostty_terminal_selection_ordered(
                    self.term,
                    &sel,
                    sys::GHOSTTY_SELECTION_ORDER_FORWARD,
                    &mut ordered,
                )
            };
            let sel = if rc == sys::GHOSTTY_SUCCESS { ordered } else { sel };
            let top = bar.offset as i64;
            let to_view = |r: &sys::GhosttyGridRef| {
                let mut p = sys::GhosttyPointCoordinate::default();
                let rc = unsafe {
                    sys::ghostty_terminal_point_from_grid_ref(
                        self.term,
                        r,
                        sys::GHOSTTY_POINT_TAG_SCREEN,
                        &mut p,
                    )
                };
                (rc == sys::GHOSTTY_SUCCESS).then(|| {
                    (p.x as i16, (p.y as i64 - top).clamp(i16::MIN as i64, i16::MAX as i64) as i16)
                })
            };
            Some((to_view(&sel.start)?, to_view(&sel.end)?, sel.rectangle))
        });

        let header = SnapshotHeader {
            cols,
            rows,
            cursor_col,
            cursor_row,
            cursor_shape,
            alt_screen: self.get_or::<c_int>(sys::GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN)
                == sys::GHOSTTY_TERMINAL_SCREEN_ALTERNATE,
            mouse_reporting: self.get_or::<bool>(sys::GHOSTTY_TERMINAL_DATA_MOUSE_TRACKING),
            bracketed_paste: self.mode(sys::GHOSTTY_MODE_BRACKETED_PASTE),
            display_offset: display_offset as u32,
            history_size: history as u32,
            selection,
        };
        // The header always goes straight into the caller's buffer: the cursor
        // and the scroll position move without any row changing.
        SnapshotWriter::new(out, &header);

        let stride = cols as usize * CELL_BYTES;
        let total = rows as usize * stride;
        let mut borrowed = self.cache.borrow_mut();
        let cache = &mut *borrowed;

        let mut state_dirty: c_int = sys::GHOSTTY_RENDER_STATE_DIRTY_FULL;
        unsafe {
            sys::ghostty_render_state_get(
                self.render,
                sys::GHOSTTY_RENDER_STATE_DATA_DIRTY,
                &mut state_dirty as *mut _ as *mut c_void,
            );
        }

        // The row flags cover row contents. A resize or a palette change is
        // ours to notice; everything else that invalidates the whole grid — a
        // scroll, a switch to the alternate screen, a selection change —
        // libghostty already reports as fully dirty.
        let usable = cache.valid
            && !self.always_full
            && cache.cols == cols
            && cache.rows == rows
            && cache.cells.len() == total
            && cache.palette.as_ref() == Some(palette)
            && state_dirty != sys::GHOSTTY_RENDER_STATE_DIRTY_FULL;

        if usable && state_dirty == sys::GHOSTTY_RENDER_STATE_DIRTY_FALSE {
            out.extend_from_slice(&cache.cells);
            return;
        }

        if !usable {
            // Every row has to be drawn, so the cache saves nothing and would
            // only cost a copy into it: write straight into the caller's
            // buffer and let the cache go. Scrolling output is made entirely
            // of these frames.
            //
            // The exception is a frame that could have used a cache but has
            // none — the first frame, or the one after a resize. That one
            // fills the cache so the frames after it can ride it, which is
            // what a TUI redrawing in place does.
            let fill_cache = state_dirty != sys::GHOSTTY_RENDER_STATE_DIRTY_FULL
                && !self.always_full;
            if fill_cache {
                cache.cells.clear();
                cache.cells.resize(total, 0);
                cache.cols = cols;
                cache.rows = rows;
                cache.palette = Some(palette.clone());
                cache.valid = true;
                unsafe { self.write_rows(palette, &mut cache.cells, false) };
                out.extend_from_slice(&cache.cells);
            } else {
                cache.valid = false;
                out.resize(HEADER_BYTES + total, 0);
                unsafe { self.write_rows(palette, &mut out[HEADER_BYTES..], false) };
            }
            return;
        }

        unsafe { self.write_rows(palette, &mut cache.cells, true) };
        out.extend_from_slice(&cache.cells);
    }

    fn cursor_row_col(&self) -> Option<(u16, u16)> {
        self.update_render();
        let c = self.render_cursor();
        (c.visible && c.viewport_has_value && c.viewport_y < self.rows)
            .then_some((c.viewport_y, c.viewport_x))
    }

    fn modes(&self) -> TermModes {
        TermModes {
            app_cursor: self.mode(sys::GHOSTTY_MODE_DECCKM),
            app_keypad: self.mode(sys::GHOSTTY_MODE_KEYPAD_KEYS),
            bracketed_paste: self.mode(sys::GHOSTTY_MODE_BRACKETED_PASTE),
            alt_screen: self.get_or::<c_int>(sys::GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN)
                == sys::GHOSTTY_TERMINAL_SCREEN_ALTERNATE,
            mouse_reporting: self.get_or::<bool>(sys::GHOSTTY_TERMINAL_DATA_MOUSE_TRACKING),
            mouse_motion: self.mode(sys::GHOSTTY_MODE_ANY_MOUSE),
            sgr_mouse: self.mode(sys::GHOSTTY_MODE_SGR_MOUSE),
            utf8_mouse: self.mode(sys::GHOSTTY_MODE_UTF8_MOUSE),
            alternate_scroll: self.mode(sys::GHOSTTY_MODE_ALT_SCROLL),
            // libghostty keeps the kitty mode stack and numbers the flags as
            // the protocol does; modifyOtherKeys never reaches it, the filter
            // answers that one.
            kitty_flags: self.get_or::<u8>(sys::GHOSTTY_TERMINAL_DATA_KITTY_KEYBOARD_FLAGS),
            modify_other_keys: self.intercept.modify_other_keys(),
            fixterms_ctrl_keys: self.intercept.options().fixterms_ctrl_keys,
        }
    }

    fn scroll_display(&mut self, delta: i32) {
        // term-core counts positive towards history; libghostty counts up as
        // negative.
        let behavior = sys::GhosttyTerminalScrollViewport {
            tag: sys::GHOSTTY_SCROLL_VIEWPORT_DELTA,
            value: sys::GhosttyTerminalScrollViewportValue { delta: -(delta as isize) },
        };
        unsafe { sys::ghostty_terminal_scroll_viewport(self.term, behavior) };
    }

    fn scroll_to_bottom(&mut self) {
        let behavior = sys::GhosttyTerminalScrollViewport {
            tag: sys::GHOSTTY_SCROLL_VIEWPORT_BOTTOM,
            value: sys::GhosttyTerminalScrollViewportValue { _padding: [0; 2] },
        };
        unsafe { sys::ghostty_terminal_scroll_viewport(self.term, behavior) };
    }

    fn display_offset(&self) -> usize {
        let bar = self.scrollbar();
        bar.total.saturating_sub(bar.len).saturating_sub(bar.offset) as usize
    }

    fn history_size(&self) -> usize {
        let bar = self.scrollbar();
        bar.total.saturating_sub(bar.len) as usize
    }

    fn scroll_to(&mut self, offset: usize) {
        let bar = self.scrollbar();
        let history = bar.total.saturating_sub(bar.len);
        let row = history.saturating_sub(offset.min(history as usize) as u64);
        let behavior = sys::GhosttyTerminalScrollViewport {
            tag: sys::GHOSTTY_SCROLL_VIEWPORT_ROW,
            value: sys::GhosttyTerminalScrollViewportValue { row: row as usize },
        };
        unsafe { sys::ghostty_terminal_scroll_viewport(self.term, behavior) };
    }

    fn selection_start(&mut self, at: ViewPoint, kind: SelectionKind) {
        let Some(r) = self.grid_ref(at) else { return };
        let mut sel = unsafe { sys::sized::<sys::GhosttySelection>() };
        let ok = unsafe {
            match kind {
                SelectionKind::Word => {
                    let mut opts = sys::sized::<sys::GhosttyTerminalSelectWordOptions>();
                    opts.r#ref = r;
                    sys::ghostty_terminal_select_word(self.term, &opts, &mut sel)
                        == sys::GHOSTTY_SUCCESS
                }
                SelectionKind::Lines => {
                    let mut opts = sys::sized::<sys::GhosttyTerminalSelectLineOptions>();
                    opts.r#ref = r;
                    sys::ghostty_terminal_select_line(self.term, &opts, &mut sel)
                        == sys::GHOSTTY_SUCCESS
                }
                SelectionKind::Simple | SelectionKind::Block => false,
            }
        };
        if !ok {
            sel.start = r;
            sel.end = r;
        }
        // Word and line selections become plain ranges straight away so both
        // ends can be dragged afterwards, matching the alacritty backend.
        sel.rectangle = kind == SelectionKind::Block;
        self.set_selection(Some(sel));
    }

    fn selection_update(&mut self, at: ViewPoint, move_start: bool) {
        let Some(current) = self.selection() else { return };
        let mut ordered = unsafe { sys::sized::<sys::GhosttySelection>() };
        let rc = unsafe {
            sys::ghostty_terminal_selection_ordered(
                self.term,
                &current,
                sys::GHOSTTY_SELECTION_ORDER_FORWARD,
                &mut ordered,
            )
        };
        let ordered = if rc == sys::GHOSTTY_SUCCESS { ordered } else { current };
        let Some(moving) = self.grid_ref(at) else { return };

        let mut sel = unsafe { sys::sized::<sys::GhosttySelection>() };
        sel.rectangle = ordered.rectangle;
        if move_start {
            sel.start = moving;
            sel.end = ordered.end;
        } else {
            sel.start = ordered.start;
            sel.end = moving;
        }
        self.set_selection(Some(sel));
    }

    fn selection_all(&mut self) {
        let mut sel = unsafe { sys::sized::<sys::GhosttySelection>() };
        if unsafe { sys::ghostty_terminal_select_all(self.term, &mut sel) } == sys::GHOSTTY_SUCCESS {
            self.set_selection(Some(sel));
        }
    }

    fn selection_clear(&mut self) {
        self.set_selection(None);
    }

    fn selection_text(&self) -> Option<String> {
        self.format(None, true, true).filter(|s| !s.is_empty())
    }

    fn has_selection(&self) -> bool {
        self.selection().is_some()
    }

    fn row_text(&self, row: u16) -> String {
        self.line_text(sys::GHOSTTY_POINT_TAG_VIEWPORT, row as u32)
    }

    fn all_lines(&self) -> Vec<String> {
        let total = self.get_or::<usize>(sys::GHOSTTY_TERMINAL_DATA_TOTAL_ROWS);
        (0..total)
            .map(|y| self.line_text(sys::GHOSTTY_POINT_TAG_SCREEN, y as u32).trim_end().to_string())
            .collect()
    }

    fn take_events(&mut self) -> Vec<EmulatorEvent> {
        std::mem::take(self.events())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use term_core::{CELL_BYTES, HEADER_BYTES};

    fn cell_at(buf: &[u8], cols: usize, col: usize, row: usize) -> (u32, u32, u32, u16) {
        let off = HEADER_BYTES + (row * cols + col) * CELL_BYTES;
        let u32at = |i: usize| u32::from_le_bytes(buf[off + i..off + i + 4].try_into().unwrap());
        let u16at = |i: usize| u16::from_le_bytes(buf[off + i..off + i + 2].try_into().unwrap());
        (u32at(0), u32at(4), u32at(8), u16at(12))
    }

    #[test]
    fn renders_text_and_colors() {
        let mut e = GhosttyEmulator::new(10, 3, 100);
        e.feed(b"hi \x1b[31mred\x1b[0m");
        let mut buf = Vec::new();
        e.snapshot(&Palette::default(), &mut buf);
        assert_eq!(buf.len(), HEADER_BYTES + 10 * 3 * CELL_BYTES);
        assert_eq!(cell_at(&buf, 10, 0, 0).0, 'h' as u32);
        assert_eq!(cell_at(&buf, 10, 3, 0).0, 'r' as u32);
        assert_eq!(cell_at(&buf, 10, 3, 0).1, Palette::default().colors[1]);
        assert_eq!(i16::from_le_bytes(buf[4..6].try_into().unwrap()), 6);
    }

    #[test]
    fn wide_chars_and_selection() {
        let mut e = GhosttyEmulator::new(10, 2, 100);
        e.feed("日本 word".as_bytes());
        e.selection_start(ViewPoint { col: 5, row: 0 }, SelectionKind::Word);
        assert_eq!(e.selection_text().as_deref(), Some("word"));
        let mut buf = Vec::new();
        e.snapshot(&Palette::default(), &mut buf);
        let (cp, _, _, flags) = cell_at(&buf, 10, 0, 0);
        assert_eq!(cp, '日' as u32);
        assert!(flags & CellFlags::WIDE != 0);
        assert!(cell_at(&buf, 10, 1, 0).3 & CellFlags::WIDE_SPACER != 0);
        assert!(cell_at(&buf, 10, 6, 0).3 & CellFlags::SELECTED != 0);
        e.selection_update(ViewPoint { col: 0, row: 0 }, true);
        assert_eq!(e.selection_text().as_deref(), Some("日本 word"));
    }

    #[test]
    fn scrollback_and_modes() {
        let mut e = GhosttyEmulator::new(5, 2, 100);
        for i in 0..5 {
            e.feed(format!("l{i}\r\n").as_bytes());
        }
        assert!(e.history_size() >= 3);
        e.scroll_display(2);
        assert_eq!(e.display_offset(), 2);
        assert_eq!(e.row_text(0).trim_end(), "l2");
        e.scroll_to_bottom();
        assert_eq!(e.display_offset(), 0);
        e.feed(b"\x1b[?1049h\x1b[?1000h\x1b[?1006h\x1b[?2004h\x1b[?1h");
        let m = e.modes();
        assert!(m.alt_screen && m.mouse_reporting && m.sgr_mouse && m.bracketed_paste && m.app_cursor);
    }

    #[test]
    fn simple_selection_and_select_all() {
        let mut e = GhosttyEmulator::new(12, 3, 100);
        e.feed(b"alpha beta\r\ngamma");
        assert!(!e.has_selection());

        e.selection_start(ViewPoint { col: 0, row: 0 }, SelectionKind::Simple);
        e.selection_update(ViewPoint { col: 4, row: 0 }, false);
        assert_eq!(e.selection_text().as_deref(), Some("alpha"));
        assert!(e.has_selection());

        e.selection_clear();
        assert!(!e.has_selection());
        assert_eq!(e.selection_text(), None);

        e.selection_all();
        let all = e.selection_text().unwrap();
        assert!(all.contains("alpha beta") && all.contains("gamma"), "{all:?}");

        e.selection_start(ViewPoint { col: 2, row: 1 }, SelectionKind::Lines);
        assert_eq!(e.selection_text().as_deref(), Some("gamma"));
    }

    #[test]
    fn resize_keeps_the_screen() {
        let mut e = GhosttyEmulator::new(20, 4, 100);
        e.feed(b"hello\r\nworld");
        e.resize(40, 10);
        assert_eq!(e.size(), (40, 10));
        let mut buf = Vec::new();
        e.snapshot(&Palette::default(), &mut buf);
        assert_eq!(buf.len(), HEADER_BYTES + 40 * 10 * CELL_BYTES);
        assert_eq!(u16::from_le_bytes(buf[0..2].try_into().unwrap()), 40);
        assert!(e.all_lines().iter().any(|l| l == "world"));
        // Down to the minimum the trait allows, and back.
        e.resize(0, 0);
        assert_eq!(e.size(), (2, 1));
    }

    /// Deterministic xorshift, so a failure is reproducible.
    struct Rng(u64);
    impl Rng {
        fn next(&mut self) -> u64 {
            self.0 ^= self.0 << 13;
            self.0 ^= self.0 >> 7;
            self.0 ^= self.0 << 17;
            self.0
        }
        fn below(&mut self, n: u64) -> u64 {
            self.next() % n
        }
    }

    /// The row cache must never show anything a full rebuild would not.
    ///
    /// Two emulators are driven through the same long, arbitrary sequence:
    /// one snapshots incrementally, the other rebuilds every row every time.
    /// They are compared after every step, so a row left stale by a missed
    /// invalidation shows up on the step after the one that caused it rather
    /// than being papered over by the next full frame.
    #[test]
    fn cached_snapshots_match_full_rebuilds() {
        let mut cached = GhosttyEmulator::new(24, 6, 200);
        let mut reference = GhosttyEmulator::new(24, 6, 200);
        reference.always_full = true;

        let palettes = [
            Palette::default(),
            Palette { background: 0x102030, selection_bg: 0x445566, ..Palette::default() },
        ];

        let mut rng = Rng(0x9E3779B97F4A7C15);
        let mut a = Vec::new();
        let mut b = Vec::new();

        for step in 0..400 {
            match rng.below(11) {
                0 => {
                    let text = format!("line {step} of output\r\n");
                    cached.feed(text.as_bytes());
                    reference.feed(text.as_bytes());
                }
                1 => {
                    let text = format!("\x1b[3{}m{}\x1b[0m ", step % 8, "styled");
                    cached.feed(text.as_bytes());
                    reference.feed(text.as_bytes());
                }
                2 => {
                    // Redraw in place: moves the cursor without scrolling.
                    let text = format!("\x1b[{};{}H{}", rng.below(6) + 1, rng.below(24) + 1, step % 10);
                    cached.feed(text.as_bytes());
                    reference.feed(text.as_bytes());
                }
                3 => {
                    let text = "日本 wide\r\n";
                    cached.feed(text.as_bytes());
                    reference.feed(text.as_bytes());
                }
                4 => {
                    let delta = rng.below(7) as i32 - 3;
                    cached.scroll_display(delta);
                    reference.scroll_display(delta);
                }
                5 => {
                    cached.scroll_to_bottom();
                    reference.scroll_to_bottom();
                }
                6 => {
                    let at = ViewPoint { col: rng.below(24) as u16, row: rng.below(6) as u16 };
                    let kind = match rng.below(4) {
                        0 => SelectionKind::Simple,
                        1 => SelectionKind::Word,
                        2 => SelectionKind::Lines,
                        _ => SelectionKind::Block,
                    };
                    cached.selection_start(at, kind);
                    reference.selection_start(at, kind);
                }
                7 => {
                    let at = ViewPoint { col: rng.below(24) as u16, row: rng.below(6) as u16 };
                    let move_start = rng.below(2) == 0;
                    cached.selection_update(at, move_start);
                    reference.selection_update(at, move_start);
                }
                8 => {
                    if rng.below(2) == 0 {
                        cached.selection_clear();
                        reference.selection_clear();
                    } else {
                        cached.selection_all();
                        reference.selection_all();
                    }
                }
                9 => {
                    // Alt screen in and out, which swaps the whole grid.
                    let seq: &[u8] =
                        if rng.below(2) == 0 { b"\x1b[?1049h" } else { b"\x1b[?1049l" };
                    cached.feed(seq);
                    reference.feed(seq);
                }
                _ => {
                    let cols = 10 + rng.below(30) as u16;
                    let rows = 2 + rng.below(10) as u16;
                    cached.resize(cols, rows);
                    reference.resize(cols, rows);
                }
            }
            cached.take_events();
            reference.take_events();

            // Alternate the palette so a stale cache across a colour-scheme
            // change is caught too.
            let palette = &palettes[(step as usize / 7) % palettes.len()];
            cached.snapshot(palette, &mut a);
            reference.snapshot(palette, &mut b);
            assert_eq!(a, b, "cached snapshot diverged at step {step}");
        }
    }

    /// The narrow case the cache is most likely to get wrong: a selection
    /// appearing, moving and going away changes cells without the text
    /// changing at all.
    #[test]
    fn selection_changes_invalidate_the_cache() {
        let mut e = GhosttyEmulator::new(20, 3, 100);
        e.feed(b"alpha beta gamma");
        let palette = Palette::default();

        let mut before = Vec::new();
        e.snapshot(&palette, &mut before);
        assert!(!has_selected_cell(&before), "nothing is selected yet");

        e.selection_start(ViewPoint { col: 0, row: 0 }, SelectionKind::Word);
        let mut during = Vec::new();
        e.snapshot(&palette, &mut during);
        assert!(has_selected_cell(&during), "the selection must reach the cells");

        e.selection_clear();
        let mut after = Vec::new();
        e.snapshot(&palette, &mut after);
        assert!(!has_selected_cell(&after), "clearing must take the highlight away");
        assert_eq!(before, after, "clearing must land back exactly where it started");
    }

    fn has_selected_cell(buf: &[u8]) -> bool {
        buf[HEADER_BYTES..]
            .as_chunks::<CELL_BYTES>()
            .0
            .iter()
            .any(|c| u16::from_le_bytes([c[12], c[13]]) & CellFlags::SELECTED != 0)
    }

    #[test]
    fn responds_to_device_attributes() {
        let mut e = GhosttyEmulator::new(5, 2, 10);
        e.feed(b"\x1b[c");
        let evs = e.take_events();
        assert!(matches!(&evs[0], EmulatorEvent::PtyWrite(v) if v.starts_with(b"\x1b[?")));
    }

    #[test]
    fn reports_title_and_scrollback_text() {
        let mut e = GhosttyEmulator::new(20, 2, 100);
        e.feed(b"\x1b]0;hello\x07one\r\ntwo\r\nthree");
        let evs = e.take_events();
        assert!(evs.iter().any(|ev| matches!(ev, EmulatorEvent::Title(Some(t)) if t == "hello")));
        let lines = e.all_lines();
        assert_eq!(lines.last().map(String::as_str), Some("three"));
        assert!(lines.iter().any(|l| l == "one"));
    }

    #[test]
    fn kitty_keyboard_flags_are_read_from_libghostty() {
        let mut e = GhosttyEmulator::new(5, 2, 10);
        assert_eq!(e.modes().kitty_flags, 0);
        // Nothing happens until the protocol is switched on: the filter is
        // libghostty's only switch, and it keeps the push from it.
        e.feed(b"\x1b[>3u\x1b[?u");
        assert_eq!(e.modes().kitty_flags, 0);
        assert!(e.take_events().is_empty(), "a query was answered with the protocol off");
        e.set_intercept(InterceptOptions { keyboard_protocol: true, ..Default::default() });
        e.feed(b"\x1b[>3u");
        assert_eq!(e.modes().kitty_flags, 3);
        e.feed(b"\x1b[=1;1u");
        assert_eq!(e.modes().kitty_flags & 1, 1);
        e.feed(b"\x1b[<u");
        assert_eq!(e.modes().kitty_flags, 0);
        e.feed(b"\x1b[>4;2m");
        assert_eq!(e.modes().modify_other_keys, 2);
    }

    #[test]
    fn the_attributes_reply_claims_sixel_only_when_it_is_on() {
        let mut e = GhosttyEmulator::new(5, 2, 10);
        e.set_intercept(InterceptOptions { sixel_images: true, ..Default::default() });
        e.feed(b"\x1b[c");
        let evs = e.take_events();
        assert!(matches!(&evs[0], EmulatorEvent::PtyWrite(v) if v == b"\x1b[?62;4;22c"), "{evs:?}");

        let mut e = GhosttyEmulator::new(5, 2, 10);
        e.feed(b"\x1b[c");
        let evs = e.take_events();
        assert!(matches!(&evs[0], EmulatorEvent::PtyWrite(v) if v == b"\x1b[?62;22c"), "{evs:?}");
    }

    #[test]
    fn a_notification_becomes_an_event_and_leaves_no_text() {
        let mut e = GhosttyEmulator::new(20, 2, 10);
        e.set_intercept(InterceptOptions { notifications: true, ..Default::default() });
        e.feed(b"a\x1b]777;notify;Done;ok\x07b");
        assert_eq!(e.row_text(0).trim_end(), "ab");
        let evs = e.take_events();
        assert!(evs.iter().any(|ev| matches!(ev, EmulatorEvent::Notify { title, body } if title == "Done" && body == "ok")));
    }

    #[test]
    fn bold_can_be_drawn_bright() {
        let mut e = GhosttyEmulator::new(10, 1, 10);
        e.feed(b"\x1b[1;31mR");
        let mut buf = Vec::new();
        e.snapshot(&Palette::default(), &mut buf);
        assert_eq!(cell_at(&buf, 10, 0, 0).1, Palette::default().colors[1]);

        // The row did not change, so this is also the cache being told.
        e.set_bold_is_bright(true);
        buf.clear();
        e.snapshot(&Palette::default(), &mut buf);
        assert_eq!(cell_at(&buf, 10, 0, 0).1, Palette::default().colors[9]);
        e.set_bold_is_bright(false);
        buf.clear();
        e.snapshot(&Palette::default(), &mut buf);
        assert_eq!(cell_at(&buf, 10, 0, 0).1, Palette::default().colors[1]);
    }

    // ---- images -----------------------------------------------------------

    fn with_images(cols: u16, rows: u16, scrollback: usize) -> GhosttyEmulator {
        let mut e = GhosttyEmulator::new(cols, rows, scrollback);
        e.set_intercept(InterceptOptions { kitty_images: true, sixel_images: true, ..Default::default() });
        // Ten pixels square makes the arithmetic in these tests visible.
        e.set_cell_size(10, 10);
        e
    }

    /// A kitty graphics command, ready to feed.
    fn apc(control: &str, payload: &[u8]) -> Vec<u8> {
        format!("\x1b_G{control};{}\x1b\\", images::base64_encode(payload)).into_bytes()
    }

    /// A white image `w` by `h` pixels, transmitted and displayed as id 1.
    fn image(w: u32, h: u32) -> Vec<u8> {
        apc(&format!("a=T,f=32,s={w},v={h},i=1"), &vec![255u8; (w * h * 4) as usize])
    }

    #[test]
    fn an_image_lands_at_the_cursor_with_the_text_around_it_intact() {
        let mut e = with_images(20, 6, 100);
        let mut input = b"top\r\n".to_vec();
        input.extend(image(20, 20));
        input.extend_from_slice(b"after");
        e.feed(&input);

        let imgs = e.images();
        assert_eq!(imgs.len(), 1);
        assert_eq!((imgs[0].col, imgs[0].row), (0, 1));
        assert_eq!((imgs[0].cols, imgs[0].rows), (2, 2));
        assert_eq!(e.row_text(0).trim_end(), "top");
        // kitty leaves the cursor just past the bottom right cell, so the text
        // that followed the command in the same chunk starts there.
        assert_eq!(e.row_text(2).trim_end(), "  after");

        let evs = e.take_events();
        assert!(evs.iter().any(|ev| matches!(ev, EmulatorEvent::ImagesChanged)));
        assert!(evs.iter().any(|ev| matches!(ev, EmulatorEvent::PtyWrite(b) if b == b"\x1b_Gi=1;OK\x1b\\")));
    }

    #[test]
    fn an_image_command_survives_any_chunking() {
        let run = |split: bool| {
            let mut e = with_images(20, 6, 100);
            let mut input = b"a\r\n".to_vec();
            input.extend(image(20, 20));
            input.extend_from_slice(b"tail");
            if split {
                for b in &input {
                    e.feed(&[*b]);
                }
            } else {
                e.feed(&input);
            }
            (e.images(), e.row_text(0), e.row_text(2), e.cursor_row_col())
        };
        assert_eq!(run(false), run(true), "chunking moved the image");
    }

    #[test]
    fn a_placement_scrolls_with_the_text_and_comes_back() {
        let mut e = with_images(20, 6, 100);
        e.feed(b"top\r\n");
        e.feed(&image(20, 20));
        assert_eq!(e.images()[0].row, 1);

        for _ in 0..4 {
            e.feed(b"\r\n");
        }
        assert_eq!(e.images()[0].row, 0);
        e.feed(b"\r\n");
        assert_eq!(e.images()[0].row, -1);
        e.feed(b"\r\n\r\n");
        assert!(e.images().is_empty(), "an image scrolled out of view is not drawn");

        e.scroll_display(3);
        let imgs = e.images();
        assert_eq!(imgs.len(), 1);
        assert_eq!(imgs[0].row, 0);
        e.scroll_to_bottom();
        assert!(e.images().is_empty());
    }

    #[test]
    fn a_placement_survives_a_resize() {
        let mut e = with_images(20, 6, 100);
        e.feed(b"top\r\n");
        e.feed(&image(20, 20));
        assert_eq!((e.images()[0].col, e.images()[0].row), (0, 1));

        e.resize(40, 6);
        let imgs = e.images();
        assert_eq!(imgs.len(), 1, "the placement was lost when the screen grew");
        assert_eq!((imgs[0].col, imgs[0].row), (0, 1));
        assert_eq!((imgs[0].cols, imgs[0].rows), (2, 2));

        e.resize(8, 6);
        let imgs = e.images();
        assert_eq!(imgs.len(), 1, "the placement was lost when the screen shrank");
        assert_eq!((imgs[0].col, imgs[0].row), (0, 1));

        e.resize(20, 10);
        assert_eq!(e.images().len(), 1);
    }

    #[test]
    fn clearing_the_screen_takes_the_image_with_it() {
        let mut e = with_images(20, 6, 100);
        e.feed(b"top\r\n");
        e.feed(&image(20, 20));
        assert_eq!(e.images().len(), 1);
        e.feed(b"\x1b[H\x1b[2J");
        assert!(e.images().is_empty());

        e.feed(&image(20, 20));
        assert_eq!(e.images().len(), 1);
        e.feed(b"\x1b[1;1H\x1b[2K");
        assert_eq!(e.images().len(), 1);
        e.feed(b"\x1b[2;1H\x1b[2K");
        assert!(e.images().is_empty());
    }

    #[test]
    fn the_alt_screen_hides_the_image_and_gives_it_back() {
        let mut e = with_images(20, 6, 100);
        e.feed(b"top\r\n");
        e.feed(&image(20, 20));
        assert_eq!(e.images().len(), 1);

        e.feed(b"\x1b[?1049h");
        assert!(e.images().is_empty(), "the primary screen's image showed through the alt screen");

        e.feed(&apc("a=T,f=32,s=10,v=10,i=2", &vec![128u8; 400]));
        assert_eq!(e.images().len(), 1);
        assert_eq!(e.images()[0].id, 2);

        e.feed(b"\x1b[?1049l");
        let imgs = e.images();
        assert_eq!(imgs.len(), 1);
        assert_eq!(imgs[0].id, 1);
    }

    #[test]
    fn the_marks_are_never_offered_as_links() {
        let mut e = with_images(20, 3, 10);
        e.feed(&image(20, 10));
        let mut buf = Vec::new();
        e.snapshot(&Palette::default(), &mut buf);
        assert_eq!(cell_at(&buf, 20, 0, 0).0, ' ' as u32);
        assert_eq!(cell_at(&buf, 20, 0, 0).3 & CellFlags::HYPERLINK, 0);
        assert_eq!(cell_at(&buf, 20, 1, 0).3 & CellFlags::HYPERLINK, 0);

        e.feed(b"\x1b[2;1H\x1b]8;;https://example.com\x1b\\hi\x1b]8;;\x1b\\");
        buf.clear();
        e.snapshot(&Palette::default(), &mut buf);
        assert!(cell_at(&buf, 20, 0, 1).3 & CellFlags::HYPERLINK != 0);
    }

    #[test]
    fn a_sixel_image_advances_the_cursor_by_its_height() {
        let mut e = with_images(20, 6, 100);
        e.feed(b"\x1bP0;1;0q#1;2;0;100;0!20~\x1b\\");
        let imgs = e.images();
        assert_eq!(imgs.len(), 1);
        assert_eq!((imgs[0].col, imgs[0].row), (0, 0));
        assert_eq!((imgs[0].cols, imgs[0].rows), (2, 1));
        assert_eq!(e.cursor_row_col(), Some((1, 0)));

        let bytes = e.image_bytes(imgs[0].id, imgs[0].generation).unwrap();
        assert_eq!(bytes.len(), (imgs[0].width * imgs[0].height * 4) as usize);
        assert_eq!(&bytes[..4], &[0, 255, 0, 255]);
    }

    #[test]
    fn kitty_can_be_told_to_leave_the_cursor_alone() {
        let mut e = with_images(20, 6, 100);
        e.feed(b"ab");
        e.feed(&apc("a=T,f=32,s=20,v=20,i=1,C=1", &vec![255u8; 1600]));
        assert_eq!(e.cursor_row_col(), Some((0, 2)));
        assert_eq!(e.images()[0].col, 2);
    }

    #[test]
    fn deleting_at_the_cursor_uses_where_the_image_ended_up() {
        let mut e = with_images(20, 6, 100);
        e.feed(&image(20, 20));
        assert_eq!(e.images().len(), 1);
        e.feed(&apc("a=d,d=c,i=1", b""));
        assert_eq!(e.images().len(), 1);
        e.feed(b"\x1b[1;2H");
        e.feed(&apc("a=d,d=c,i=1", b""));
        assert!(e.images().is_empty());
    }

    #[test]
    fn switching_images_off_gives_the_memory_back() {
        let mut e = with_images(20, 6, 100);
        e.feed(&image(20, 20));
        assert_eq!(e.images().len(), 1);
        e.set_intercept(InterceptOptions::default());
        assert!(e.images().is_empty());
    }

    // ---- prompt marks -----------------------------------------------------

    fn with_marks(cols: u16, rows: u16, scrollback: usize) -> GhosttyEmulator {
        let mut e = GhosttyEmulator::new(cols, rows, scrollback);
        e.set_intercept(InterceptOptions { prompt_marks: true, ..Default::default() });
        e
    }

    /// One command the way a shell that marks its prompts sends it.
    fn command(prompt: &str, typed: &str, output: &str, status: i32) -> Vec<u8> {
        format!("\x1b]133;A\x07{prompt}\x1b]133;B\x07{typed}\r\n\x1b]133;C\x07{output}\x1b]133;D;{status}\x07")
            .into_bytes()
    }

    fn marks_of(e: &GhosttyEmulator) -> Vec<(PromptMark, i32)> {
        e.prompt_marks().into_iter().map(|m| (m.mark, m.row)).collect()
    }

    #[test]
    fn a_mark_lands_on_the_first_cell_the_shell_prints_after_it() {
        let mut e = with_marks(20, 6, 100);
        e.feed(&command("$ ", "ls", "one\r\ntwo\r\n", 0));
        e.feed(b"\x1b]133;A\x07$ ");

        assert_eq!(
            marks_of(&e),
            vec![
                (PromptMark::PromptStart, 0),
                (PromptMark::CommandStart, 0),
                (PromptMark::OutputStart, 1),
                (PromptMark::Finished(Some(0)), 3),
                (PromptMark::PromptStart, 3),
            ],
        );
        assert_eq!(e.row_text(0).trim_end(), "$ ls");
        assert_eq!(e.row_text(1).trim_end(), "one");
        assert_eq!(e.row_text(3).trim_end(), "$");
    }

    #[test]
    fn a_mark_takes_one_cell_and_the_pen_is_handed_back() {
        let mut e = with_marks(20, 3, 10);
        e.feed(b"\x1b]133;A\x07$ user@host ");
        assert_eq!(marks_of(&e), vec![(PromptMark::PromptStart, 0)]);
        // Only the first cell is ours; the rest of the prompt carries no link
        // at all, so the renderer has nothing to hide there.
        let mut buf = Vec::new();
        e.snapshot(&Palette::default(), &mut buf);
        for col in 0..12 {
            assert_eq!(cell_at(&buf, 20, col, 0).3 & CellFlags::HYPERLINK, 0, "column {col}");
        }
        let first = e.grid_ref_at(sys::GHOSTTY_POINT_TAG_VIEWPORT, 0, 0).unwrap();
        assert!(e.hyperlink_uri(&first).is_some());
        let second = e.grid_ref_at(sys::GHOSTTY_POINT_TAG_VIEWPORT, 1, 0).unwrap();
        assert!(e.hyperlink_uri(&second).is_none(), "the mark ran on past its cell");
    }

    #[test]
    fn a_mark_survives_any_chunking() {
        let run = |split: bool| {
            let mut e = with_marks(20, 6, 100);
            let input = command("$ ", "ls", "one\r\n", 0);
            if split {
                for b in &input {
                    e.feed(&[*b]);
                }
            } else {
                e.feed(&input);
            }
            (marks_of(&e), e.row_text(0), e.row_text(1))
        };
        assert_eq!(run(false), run(true), "chunking moved a mark");
    }

    #[test]
    fn a_mark_scrolls_out_of_the_viewport_and_comes_back() {
        let mut e = with_marks(20, 4, 100);
        e.feed(&command("$ ", "ls", "one\r\n", 0));
        assert_eq!(marks_of(&e)[0], (PromptMark::PromptStart, 0));

        for _ in 0..6 {
            e.feed(b"\r\n");
        }
        let above = marks_of(&e);
        assert!(above.iter().all(|(_, row)| *row < 0), "a mark in the history reports a positive row: {above:?}");

        e.scroll_to(e.history_size());
        assert_eq!(marks_of(&e)[0], (PromptMark::PromptStart, 0));
        e.scroll_to_bottom();
        assert_eq!(marks_of(&e), above);
    }

    #[test]
    fn a_mark_survives_a_resize() {
        let mut e = with_marks(20, 6, 100);
        e.feed(&command("$ ", "ls", "one\r\n", 0));
        let before = marks_of(&e);
        assert_eq!(before.len(), 3);

        e.resize(40, 6);
        assert_eq!(marks_of(&e), before, "the marks were lost when the screen grew");
        e.resize(8, 6);
        assert_eq!(marks_of(&e), before, "the marks were lost when the screen shrank");
        e.resize(20, 10);
        assert_eq!(marks_of(&e).len(), before.len());
    }

    #[test]
    fn clearing_the_screen_erases_the_marks_with_their_text() {
        let mut e = with_marks(20, 6, 100);
        e.feed(&command("$ ", "ls", "one\r\n", 0));
        assert_eq!(marks_of(&e).len(), 3);

        // Where alacritty's ED 2 pushes the displaced screen into the
        // scrollback, marks and all, libghostty erases it in place — one of
        // the differences term-diff records — so here the marks go the way
        // of the text they were on. What is in the history stays.
        for _ in 0..6 {
            e.feed(b"\r\n");
        }
        let above = marks_of(&e);
        assert_eq!(above.len(), 3);
        e.feed(b"\x1b]133;A\x07$ \x1b[H\x1b[2J");
        assert_eq!(marks_of(&e), above, "clearing the screen reached into the history");

        // Wiping the scrollback as well is what ends those.
        e.feed(b"\x1b[3J");
        assert!(e.prompt_marks().is_empty());
    }

    #[test]
    fn the_alt_screen_hides_the_marks_and_gives_them_back() {
        let mut e = with_marks(20, 6, 100);
        e.feed(&command("$ ", "ls", "one\r\n", 0));
        let before = marks_of(&e);

        e.feed(b"\x1b[?1049h\x1b[H");
        assert!(e.prompt_marks().is_empty(), "the primary screen's marks showed through the alt screen");
        e.feed(b"\x1b]133;A\x07> ");
        assert_eq!(marks_of(&e), vec![(PromptMark::PromptStart, 0)]);

        e.feed(b"\x1b[?1049l");
        assert_eq!(marks_of(&e), before);
    }

    #[test]
    fn a_mark_is_never_offered_as_a_link() {
        let mut e = with_marks(20, 3, 10);
        e.feed(b"\x1b]133;A\x07$ ");
        assert_eq!(marks_of(&e), vec![(PromptMark::PromptStart, 0)]);
        let mut buf = Vec::new();
        e.snapshot(&Palette::default(), &mut buf);
        assert_eq!(cell_at(&buf, 20, 0, 0).0, '$' as u32);
        assert_eq!(cell_at(&buf, 20, 0, 0).3 & CellFlags::HYPERLINK, 0);

        e.feed(b"\x1b]8;;https://example.com\x1b\\hi\x1b]8;;\x1b\\");
        buf.clear();
        e.snapshot(&Palette::default(), &mut buf);
        assert!(cell_at(&buf, 20, 2, 0).3 & CellFlags::HYPERLINK != 0);
    }

    #[test]
    fn a_link_the_shell_opens_before_printing_takes_the_pen_from_the_mark() {
        // `ls --hyperlink` right after OSC 133;C: the first cell printed is
        // the program's link, so the mark has no cell and must not close the
        // program's link behind it.
        let mut e = with_marks(20, 3, 10);
        e.feed(b"\x1b]133;C\x07\x1b]8;;file:///a\x1b\\ab\x1b]8;;\x1b\\c");
        assert!(marks_of(&e).is_empty());
        let mut buf = Vec::new();
        e.snapshot(&Palette::default(), &mut buf);
        assert!(cell_at(&buf, 20, 0, 0).3 & CellFlags::HYPERLINK != 0);
        assert!(cell_at(&buf, 20, 1, 0).3 & CellFlags::HYPERLINK != 0);
        assert_eq!(cell_at(&buf, 20, 2, 0).3 & CellFlags::HYPERLINK, 0);
    }

    #[test]
    fn a_shell_that_sends_no_marks_has_none() {
        let mut e = with_marks(20, 3, 10);
        e.feed(b"$ ls\r\none\r\n");
        assert!(e.prompt_marks().is_empty());
    }
}
