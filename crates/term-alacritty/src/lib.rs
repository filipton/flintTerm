//! [`term_core::Emulator`] implementation backed by `alacritty_terminal`.

use std::sync::{Arc, Mutex};

use alacritty_terminal::event::{Event, EventListener};
use alacritty_terminal::grid::{Dimensions, Scroll};
use alacritty_terminal::index::{Column, Line, Point, Side};
use alacritty_terminal::selection::{Selection, SelectionType};
use alacritty_terminal::term::cell::Flags;
use alacritty_terminal::term::{point_to_viewport, viewport_to_point, Config, Term, TermMode};
use alacritty_terminal::vte::ansi::{Color, CursorShape, NamedColor, Processor, Rgb};
use term_core::images::{self, Draw, ImageStore, Mark, Outcome, PlacedImage};
use term_core::marks::{self, PromptMarkAt};
use term_core::{
    CellFlags, Emulator, EmulatorEvent, InterceptEvent, InterceptOptions, Interceptor, Palette, PromptMark,
    SelectionKind, SnapshotHeader, SnapshotWriter, TermModes, ViewPoint,
};

/// Collects events emitted by the terminal so the owner can drain them after
/// each `feed`.
#[derive(Clone, Default)]
struct Listener {
    events: Arc<Mutex<Vec<EmulatorEvent>>>,
}

impl EventListener for Listener {
    fn send_event(&self, event: Event) {
        let mapped = match event {
            Event::PtyWrite(s) => EmulatorEvent::PtyWrite(s.into_bytes()),
            Event::Title(t) => EmulatorEvent::Title(Some(t)),
            Event::ResetTitle => EmulatorEvent::Title(None),
            Event::Bell => EmulatorEvent::Bell,
            Event::ClipboardStore(_, s) => EmulatorEvent::ClipboardStore(s),
            // Answer clipboard reads with an empty string rather than leaking the
            // Android clipboard to the remote host.
            Event::ClipboardLoad(_, f) => EmulatorEvent::PtyWrite(f("").into_bytes()),
            Event::ColorRequest(idx, f) => {
                let rgb = default_rgb_for_index(idx);
                EmulatorEvent::PtyWrite(f(rgb).into_bytes())
            }
            _ => return,
        };
        self.events.lock().unwrap().push(mapped);
    }
}

/// The kitty keyboard flags the program has pushed, as the protocol numbers
/// them. `alacritty_terminal` keeps the mode stack for us; only the reading is
/// ours.
fn kitty_flags(m: &TermMode) -> u8 {
    let mut f = 0u8;
    if m.contains(TermMode::DISAMBIGUATE_ESC_CODES) {
        f |= 1;
    }
    if m.contains(TermMode::REPORT_EVENT_TYPES) {
        f |= 2;
    }
    if m.contains(TermMode::REPORT_ALTERNATE_KEYS) {
        f |= 4;
    }
    if m.contains(TermMode::REPORT_ALL_KEYS_AS_ESC) {
        f |= 8;
    }
    if m.contains(TermMode::REPORT_ASSOCIATED_TEXT) {
        f |= 16;
    }
    f
}

/// The bright twin of one of the eight ANSI colors, for bold-is-bright. A
/// color that was never one of those eight — a 256-color index, a true color,
/// the default foreground — has no brighter version and is left alone.
fn brighten(c: Color) -> Color {
    match c {
        Color::Indexed(i) if i < 8 => Color::Indexed(i + 8),
        Color::Named(n) if (n as usize) < 8 => {
            Color::Named(match n {
                NamedColor::Black => NamedColor::BrightBlack,
                NamedColor::Red => NamedColor::BrightRed,
                NamedColor::Green => NamedColor::BrightGreen,
                NamedColor::Yellow => NamedColor::BrightYellow,
                NamedColor::Blue => NamedColor::BrightBlue,
                NamedColor::Magenta => NamedColor::BrightMagenta,
                NamedColor::Cyan => NamedColor::BrightCyan,
                _ => NamedColor::BrightWhite,
            })
        }
        other => other,
    }
}

/// Whether a hyperlink is one the app wrote into the grid to remember where
/// something is — an image placement or a prompt mark — rather than one the
/// remote side wrote for a person to follow.
fn is_private_uri(uri: &str) -> bool {
    images::parse_mark(uri).is_some() || marks::parse_marks(uri).is_some()
}

/// Whether these bytes are a primary device-attributes reply (`CSI ? … c`).
fn is_primary_da(b: &[u8]) -> bool {
    b.starts_with(b"\x1b[?") && b.ends_with(b"c")
}

fn default_rgb_for_index(_idx: usize) -> Rgb {
    Rgb { r: 0, g: 0, b: 0 }
}

struct Size {
    cols: usize,
    rows: usize,
}

impl Dimensions for Size {
    fn total_lines(&self) -> usize {
        self.rows
    }
    fn screen_lines(&self) -> usize {
        self.rows
    }
    fn columns(&self) -> usize {
        self.cols
    }
}

pub struct AlacrittyEmulator {
    term: Term<Listener>,
    parser: Processor,
    listener: Listener,
    size: Size,
    config: Config,
    bold_is_bright: bool,
    intercept: Interceptor,
    /// Scratch buffer for the filtered stream, kept so a busy session is not
    /// allocating a vector per chunk of output.
    filtered: Vec<u8>,
    images: ImageStore,
    /// Events a sequence produced part-way through a chunk, flushed before the
    /// text that followed it in the same chunk reaches the grid.
    piece_events: Vec<EmulatorEvent>,
    /// The prompt mark waiting for a cell to live on; see [`marks`].
    pending_mark: Option<PendingMark>,
    /// Tells one pending mark's cells from an older mark's. It only has to be
    /// unique among the marks in the buffer, so wrapping is not a worry.
    mark_serial: u32,
}

/// A mark that is on the pen, waiting for the shell to print something.
struct PendingMark {
    serial: u32,
    /// Everything the shell said with nothing printed in between, in order.
    marks: Vec<PromptMark>,
    /// Which screen it was announced on. A mark belongs to the shell's own
    /// screen, so one still waiting when a program takes the alt screen is
    /// dropped rather than pinned to the first cell that program draws.
    alt_screen: bool,
}

impl AlacrittyEmulator {
    pub fn new(cols: u16, rows: u16, scrollback: usize) -> Self {
        let listener = Listener::default();
        let config = Config { scrolling_history: scrollback, ..Default::default() };
        let size = Size { cols: cols.max(1) as usize, rows: rows.max(1) as usize };
        let term = Term::new(config.clone(), &size, listener.clone());
        Self {
            term,
            parser: Processor::new(),
            listener,
            size,
            config,
            bold_is_bright: false,
            intercept: Interceptor::new(InterceptOptions::default()),
            filtered: Vec::new(),
            images: ImageStore::new(),
            piece_events: Vec::new(),
            pending_mark: None,
            mark_serial: 0,
        }
    }

    fn to_point(&self, at: ViewPoint) -> Point {
        let rows = self.size.rows.saturating_sub(1);
        let cols = self.size.cols.saturating_sub(1);
        let vp = Point::new((at.row as usize).min(rows), Column((at.col as usize).min(cols)));
        viewport_to_point(self.term.grid().display_offset(), vp)
    }

    fn resolve(&self, palette: &Palette, color: Color) -> u32 {
        match color {
            Color::Spec(rgb) => pack(rgb),
            Color::Indexed(i) => self.term.colors()[i as usize].map(pack).unwrap_or(palette.colors[i as usize]),
            Color::Named(n) => {
                if let Some(rgb) = self.term.colors()[n] {
                    return pack(rgb);
                }
                match n {
                    NamedColor::Foreground | NamedColor::BrightForeground => palette.foreground,
                    NamedColor::DimForeground => Palette::dim(palette.foreground),
                    NamedColor::Background => palette.background,
                    NamedColor::Cursor => palette.cursor,
                    NamedColor::DimBlack
                    | NamedColor::DimRed
                    | NamedColor::DimGreen
                    | NamedColor::DimYellow
                    | NamedColor::DimBlue
                    | NamedColor::DimMagenta
                    | NamedColor::DimCyan
                    | NamedColor::DimWhite => Palette::dim(palette.colors[n as usize - NamedColor::DimBlack as usize]),
                    other => palette.colors[(other as usize).min(15)],
                }
            }
        }
    }
}

#[inline]
fn pack(rgb: Rgb) -> u32 {
    ((rgb.r as u32) << 16) | ((rgb.g as u32) << 8) | rgb.b as u32
}

impl AlacrittyEmulator {
    /// Feed one run of bytes that contains at most one escape sequence at its
    /// head, acting on any image command it carries before the text after it
    /// is drawn.
    fn feed_piece(&mut self, piece: &[u8]) {
        let mut filtered = std::mem::take(&mut self.filtered);
        filtered.clear();
        let events = self.intercept.feed(piece, &mut filtered);
        for e in events {
            match e {
                // These two put something on the screen at the cursor, so they
                // are acted on here, where the cursor is still where the stream
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
                    self.piece_events.push(EmulatorEvent::Mark(m));
                }
                other => self.piece_events.push(map_intercept(other)),
            }
        }
        // Flushed before the piece's own text is drawn, so a reply keeps its
        // place in the order the program will read it back in.
        if !self.piece_events.is_empty() {
            self.listener.events.lock().unwrap().append(&mut self.piece_events);
        }
        self.parser.advance(&mut self.term, &filtered);
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
            self.piece_events.push(EmulatorEvent::PtyWrite(reply));
        }
        if out.changed {
            self.piece_events.push(EmulatorEvent::ImagesChanged);
        }
    }

    /// Mark the cells an image covers, by writing spaces under a private OSC 8
    /// hyperlink. The grid then owns the placement's position: it scrolls,
    /// reflows and erases it along with the text around it, and the spaces are
    /// what a selection over the image copies.
    fn draw_placement(&mut self, d: Draw) {
        let screen_cols = self.size.cols as u16;
        let screen_rows = self.size.rows as u16;
        let cursor = self.term.grid().cursor.point;
        let col0 = (cursor.column.0 as u16).min(screen_cols.saturating_sub(1));
        let row0 = cursor.line.0.clamp(0, screen_rows as i32 - 1) as u16;
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
        self.parser.advance(&mut self.term, &seq);
    }

    /// Put a prompt mark on the pen, so the next thing the shell prints
    /// carries it.
    ///
    /// The cell under the cursor is not marked directly because it is the very
    /// cell the shell is about to write over — a prompt draws itself where the
    /// mark that announced it just was. Whatever is printed next is a cell with
    /// content on it, which nothing is going to blank behind us.
    fn note_mark(&mut self, mark: PromptMark) {
        // Anything the previous mark was owed, it is owed now: after this the
        // pen belongs to the new one.
        self.settle_marks();
        let mut marks = self.pending_mark.take().map(|p| p.marks).unwrap_or_default();
        marks.push(mark);
        self.mark_serial = self.mark_serial.wrapping_add(1);
        let seq = format!("\x1b]8;;{}\x1b\\", marks::mark_uri(self.mark_serial, &marks));
        self.parser.advance(&mut self.term, seq.as_bytes());
        let alt_screen = self.term.mode().contains(TermMode::ALT_SCREEN);
        self.pending_mark = Some(PendingMark { serial: self.mark_serial, marks, alt_screen });
    }

    /// Give the pending mark the first cell the shell printed after it, and
    /// take the mark off the pen again.
    ///
    /// Everything written while the pen was open carries the mark, so the run
    /// is pruned back to its first cell here: one mark is one place, and a
    /// screenful of output has no business holding a hyperlink on every cell of
    /// itself.
    fn settle_marks(&mut self) {
        let Some(pending) = self.pending_mark.as_ref() else { return };
        let serial = pending.serial;
        if pending.alt_screen != self.term.mode().contains(TermMode::ALT_SCREEN) {
            self.pending_mark = None;
            return;
        }
        let rows = self.size.rows as i32;
        let cols = self.size.cols;
        let is_ours = |cell: &alacritty_terminal::term::cell::Cell| {
            cell.hyperlink().is_some_and(|l| marks::parse_marks(l.uri()).is_some_and(|(s, _)| s == serial))
        };

        let grid = self.term.grid();
        let mut first = None;
        'find: for line in -(grid.history_size() as i32)..rows {
            for col in 0..cols {
                if is_ours(&grid[Line(line)][Column(col)]) {
                    first = Some(Point::new(Line(line), Column(col)));
                    break 'find;
                }
            }
        }
        let Some(first) = first else {
            // Nothing has been printed since the mark arrived, so it waits on
            // the pen for the next chunk — unless the shell has set a link of
            // its own since, in which case no cell of ours is ever coming.
            if !self.pen_carries(serial) {
                self.pending_mark = None;
            }
            return;
        };

        // Everything else of ours is at or after the cell we kept, in reading
        // order, which is as far as this has to look.
        let grid = self.term.grid_mut();
        for line in first.line.0..rows {
            for col in 0..cols {
                let at = Point::new(Line(line), Column(col));
                if at != first && is_ours(&grid[at]) {
                    grid[at].set_hyperlink(None);
                }
            }
        }
        self.pending_mark = None;
        if self.pen_carries(serial) {
            self.parser.advance(&mut self.term, b"\x1b]8;;\x1b\\");
        }
    }

    /// Whether the pen still holds the mark `serial` was written for. It does
    /// not once the shell has written a hyperlink of its own, or swapped in the
    /// alt screen with its own cursor.
    fn pen_carries(&self, serial: u32) -> bool {
        self.term
            .grid()
            .cursor
            .template
            .hyperlink()
            .is_some_and(|l| marks::parse_marks(l.uri()).is_some_and(|(s, _)| s == serial))
    }

    /// The placement marks in the visible grid, left to right and top to
    /// bottom.
    fn marks(&self) -> Vec<Mark> {
        if self.images.is_empty() {
            return Vec::new();
        }
        let grid = self.term.grid();
        let offset = grid.display_offset();
        let mut out = Vec::new();
        for row in 0..self.size.rows {
            let grid_row = &grid[Line(row as i32) - offset];
            let mut col = 0;
            while col < self.size.cols {
                let found = grid_row[Column(col)]
                    .hyperlink()
                    .and_then(|link| images::parse_mark(link.uri()));
                match found {
                    Some((key, index)) => {
                        out.push(Mark { key, index, col: col as u16, row: row as u16 });
                        // The rest of this placement's row says nothing new;
                        // skipping it also lets two images share a row.
                        let width = self.images.placement(key).map(|p| p.cols as usize).unwrap_or(1);
                        col += width.max(1);
                    }
                    None => col += 1,
                }
            }
        }
        out
    }

    /// Which placements a position-based delete command hits.
    fn delete_hits(&self, target: images::DeleteTarget) -> Vec<u32> {
        use images::DeleteTarget as T;
        let cursor = self.term.grid().cursor.point;
        let at = |p: &PlacedImage, col: i64, row: i64| {
            col >= p.col as i64
                && col < p.col as i64 + p.cols as i64
                && row >= p.row as i64
                && row < p.row as i64 + p.rows as i64
        };
        self.images
            .resolve(&self.marks())
            .iter()
            .filter(|p| match target {
                T::Cursor => at(p, cursor.column.0 as i64, cursor.line.0 as i64),
                T::Cell { col, row } => at(p, col as i64, row as i64),
                T::Column(x) => x as i64 >= p.col as i64 && (x as i64) < p.col as i64 + p.cols as i64,
                T::Row(y) => y as i64 >= p.row as i64 && (y as i64) < p.row as i64 + p.rows as i64,
                _ => false,
            })
            .map(|p| p.key)
            .collect()
    }
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

impl Emulator for AlacrittyEmulator {
    fn feed(&mut self, bytes: &[u8]) {
        if self.intercept.passthrough() {
            self.parser.advance(&mut self.term, bytes);
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
            self.settle_marks();
            return;
        }
        self.filtered.clear();
        let mut filtered = std::mem::take(&mut self.filtered);
        let events = self.intercept.feed(bytes, &mut filtered);
        self.parser.advance(&mut self.term, &filtered);
        self.filtered = filtered;
        if !events.is_empty() {
            let mut sink = self.listener.events.lock().unwrap();
            for e in events {
                sink.push(map_intercept(e));
            }
        }
    }

    fn set_cell_size(&mut self, width: u32, height: u32) {
        self.images.set_cell_size(width, height);
    }

    fn images(&self) -> Vec<PlacedImage> {
        self.images.resolve(&self.marks())
    }

    fn prompt_marks(&self) -> Vec<PromptMarkAt> {
        let grid = self.term.grid();
        let offset = grid.display_offset() as i32;
        let mut out = Vec::new();
        for line in -(grid.history_size() as i32)..self.size.rows as i32 {
            let row = &grid[Line(line)];
            for col in 0..self.size.cols {
                let Some(link) = row[Column(col)].hyperlink() else { continue };
                let Some((_, marks)) = marks::parse_marks(link.uri()) else { continue };
                out.extend(marks.into_iter().map(|mark| PromptMarkAt { mark, row: line + offset }));
            }
        }
        out
    }

    fn image_bytes(&self, id: u32, generation: u32) -> Option<&[u8]> {
        self.images
            .image(id)
            .filter(|i| i.generation == generation)
            .map(|i| i.rgba.as_slice())
    }

    fn set_bold_is_bright(&mut self, on: bool) {
        self.bold_is_bright = on;
    }

    fn set_intercept(&mut self, opts: InterceptOptions) {
        self.intercept.set_options(opts);
        if !opts.kitty_images && !opts.sixel_images {
            // Switching images off should give the memory back, not leave a
            // session holding pictures it will never draw again.
            self.images.clear();
        }
        // The backend keeps the kitty mode stack, so switching the protocol off
        // has to reach it too — otherwise a program could turn the modes on
        // behind the filter's back and be answered in a protocol we said we do
        // not speak.
        if self.config.kitty_keyboard != opts.keyboard_protocol {
            self.config.kitty_keyboard = opts.keyboard_protocol;
            self.term.set_options(self.config.clone());
        }
    }

    fn resize(&mut self, cols: u16, rows: u16) {
        let cols = cols.max(2) as usize;
        let rows = rows.max(1) as usize;
        if cols == self.size.cols && rows == self.size.rows {
            return;
        }
        self.size = Size { cols, rows };
        self.term.resize(Size { cols, rows });
    }

    fn size(&self) -> (u16, u16) {
        (self.size.cols as u16, self.size.rows as u16)
    }

    fn snapshot(&self, palette: &Palette, out: &mut Vec<u8>) {
        let content = self.term.renderable_content();
        let display_offset = content.display_offset;
        let mode = content.mode;
        let grid = self.term.grid();
        let cols = self.size.cols;
        let rows = self.size.rows;

        let (cursor_col, cursor_row, cursor_shape) = match point_to_viewport(display_offset, content.cursor.point) {
            Some(p) if content.cursor.shape != CursorShape::Hidden && p.line < rows => (
                p.column.0 as i16,
                p.line as i16,
                match content.cursor.shape {
                    CursorShape::Block => 0,
                    CursorShape::Underline => 1,
                    CursorShape::Beam => 2,
                    _ => 0,
                },
            ),
            _ => (-1, -1, 3),
        };

        let selection = content.selection.map(|r| {
            let s = r.start.line.0 + display_offset as i32;
            let e = r.end.line.0 + display_offset as i32;
            ((r.start.column.0 as i16, s.clamp(i16::MIN as i32, i16::MAX as i32) as i16),
             (r.end.column.0 as i16, e.clamp(i16::MIN as i32, i16::MAX as i32) as i16),
             r.is_block)
        });

        let header = SnapshotHeader {
            cols: cols as u16,
            rows: rows as u16,
            cursor_col,
            cursor_row,
            cursor_shape,
            alt_screen: mode.contains(TermMode::ALT_SCREEN),
            mouse_reporting: mode.intersects(TermMode::MOUSE_MODE),
            bracketed_paste: mode.contains(TermMode::BRACKETED_PASTE),
            display_offset: display_offset as u32,
            history_size: grid.history_size() as u32,
            selection,
        };
        let mut w = SnapshotWriter::new(out, &header);

        for row in 0..rows {
            let line = Line(row as i32) - display_offset;
            let grid_row = &grid[line];
            for col in 0..cols {
                let cell = &grid_row[Column(col)];
                let flags = cell.flags;
                let cell_fg = if self.bold_is_bright && flags.contains(Flags::BOLD) {
                    brighten(cell.fg)
                } else {
                    cell.fg
                };
                let mut fg = self.resolve(palette, cell_fg);
                let mut bg = self.resolve(palette, cell.bg);
                let mut out_flags: u16 = 0;

                if flags.contains(Flags::DIM) || flags.contains(Flags::DIM_BOLD) && !flags.contains(Flags::BOLD) {
                    fg = Palette::dim(fg);
                    out_flags |= CellFlags::DIM;
                }
                if flags.contains(Flags::INVERSE) {
                    std::mem::swap(&mut fg, &mut bg);
                }
                if flags.contains(Flags::BOLD) {
                    out_flags |= CellFlags::BOLD;
                }
                if flags.contains(Flags::ITALIC) {
                    out_flags |= CellFlags::ITALIC;
                }
                if flags.contains(Flags::UNDERLINE) {
                    out_flags |= CellFlags::UNDERLINE;
                }
                if flags.contains(Flags::DOUBLE_UNDERLINE) {
                    out_flags |= CellFlags::DOUBLE_UNDERLINE;
                }
                if flags.intersects(Flags::UNDERCURL | Flags::DOTTED_UNDERLINE | Flags::DASHED_UNDERLINE) {
                    out_flags |= CellFlags::UNDERCURL;
                }
                if flags.contains(Flags::STRIKEOUT) {
                    out_flags |= CellFlags::STRIKEOUT;
                }
                if flags.contains(Flags::HIDDEN) {
                    out_flags |= CellFlags::HIDDEN;
                }
                if flags.contains(Flags::WIDE_CHAR) {
                    out_flags |= CellFlags::WIDE;
                }
                if flags.contains(Flags::WIDE_CHAR_SPACER) || flags.contains(Flags::LEADING_WIDE_CHAR_SPACER) {
                    out_flags |= CellFlags::WIDE_SPACER;
                }
                // An image placement and a prompt mark are both hyperlinks to
                // the app itself, not somewhere a person could go: the renderer
                // must never offer one as a link.
                if cell.hyperlink().is_some_and(|l| !is_private_uri(l.uri())) {
                    out_flags |= CellFlags::HYPERLINK;
                }
                let point = Point::new(line, Column(col));
                if let Some(sel) = &content.selection {
                    if sel.contains(point) {
                        out_flags |= CellFlags::SELECTED;
                        fg = palette.foreground;
                        bg = palette.selection_bg;
                    }
                }
                let cp = if out_flags & CellFlags::WIDE_SPACER != 0 { 0 } else { cell.c as u32 };
                w.cell(cp, fg, bg, out_flags);
            }
        }
    }

    fn cursor_row_col(&self) -> Option<(u16, u16)> {
        // Exactly what the snapshot reports, so a predicted character lands
        // where the caret is actually drawn.
        let content = self.term.renderable_content();
        match point_to_viewport(content.display_offset, content.cursor.point) {
            Some(p) if content.cursor.shape != CursorShape::Hidden && p.line < self.size.rows => {
                Some((p.line as u16, p.column.0 as u16))
            }
            _ => None,
        }
    }

    fn modes(&self) -> TermModes {
        let m = *self.term.mode();
        TermModes {
            app_cursor: m.contains(TermMode::APP_CURSOR),
            app_keypad: m.contains(TermMode::APP_KEYPAD),
            bracketed_paste: m.contains(TermMode::BRACKETED_PASTE),
            alt_screen: m.contains(TermMode::ALT_SCREEN),
            mouse_reporting: m.intersects(TermMode::MOUSE_MODE),
            mouse_motion: m.contains(TermMode::MOUSE_MOTION),
            sgr_mouse: m.contains(TermMode::SGR_MOUSE),
            utf8_mouse: m.contains(TermMode::UTF8_MOUSE),
            alternate_scroll: m.contains(TermMode::ALTERNATE_SCROLL),
            kitty_flags: kitty_flags(&m),
            modify_other_keys: self.intercept.modify_other_keys(),
            fixterms_ctrl_keys: self.intercept.options().fixterms_ctrl_keys,
        }
    }

    fn scroll_display(&mut self, delta: i32) {
        self.term.scroll_display(Scroll::Delta(delta));
    }

    fn scroll_to_bottom(&mut self) {
        self.term.scroll_display(Scroll::Bottom);
    }

    fn display_offset(&self) -> usize {
        self.term.grid().display_offset()
    }

    fn history_size(&self) -> usize {
        self.term.grid().history_size()
    }

    fn selection_start(&mut self, at: ViewPoint, kind: SelectionKind) {
        let point = self.to_point(at);
        let ty = match kind {
            SelectionKind::Simple => SelectionType::Simple,
            SelectionKind::Word => SelectionType::Semantic,
            SelectionKind::Lines => SelectionType::Lines,
            SelectionKind::Block => SelectionType::Block,
        };
        let mut sel = Selection::new(ty, point, Side::Left);
        if kind == SelectionKind::Simple || kind == SelectionKind::Block {
            sel.update(point, Side::Right);
        }
        // Semantic / line selections are converted to a simple range immediately
        // so both ends can be dragged afterwards.
        if matches!(kind, SelectionKind::Word | SelectionKind::Lines) {
            if let Some(range) = sel.to_range(&self.term) {
                let mut simple = Selection::new(SelectionType::Simple, range.start, Side::Left);
                simple.update(range.end, Side::Right);
                sel = simple;
            }
        }
        self.term.selection = Some(sel);
    }

    fn selection_update(&mut self, at: ViewPoint, move_start: bool) {
        let point = self.to_point(at);
        let Some(range) = self.term.selection.as_ref().and_then(|s| s.to_range(&self.term)) else {
            return;
        };
        let is_block = range.is_block;
        let ty = if is_block { SelectionType::Block } else { SelectionType::Simple };
        let (anchor, moving) = if move_start { (range.end, point) } else { (range.start, point) };
        // Keep the anchor on its outer side so dragging past it flips cleanly.
        let anchor_side = if move_start { Side::Right } else { Side::Left };
        let moving_side = if moving > anchor { Side::Right } else { Side::Left };
        let mut sel = Selection::new(ty, anchor, anchor_side);
        sel.update(moving, moving_side);
        self.term.selection = Some(sel);
    }

    fn selection_all(&mut self) {
        // Anchored at the oldest scrollback line and dragged to the bottom
        // right. `Selection::include_all` only widens the sides of whatever
        // range is already there, so an anchor with no update left select-all
        // covering the single line it started on.
        let history = self.term.grid().history_size() as i32;
        let last_line = Line(self.size.rows as i32 - 1);
        let last_column = Column(self.size.cols.saturating_sub(1));
        let mut sel =
            Selection::new(SelectionType::Lines, Point::new(Line(-history), Column(0)), Side::Left);
        sel.update(Point::new(last_line, last_column), Side::Right);
        self.term.selection = Some(sel);
    }

    fn selection_clear(&mut self) {
        self.term.selection = None;
    }

    fn selection_text(&self) -> Option<String> {
        self.term.selection_to_string().filter(|s| !s.is_empty())
    }

    fn has_selection(&self) -> bool {
        self.term.selection.as_ref().map(|s| !s.is_empty()).unwrap_or(false)
    }

    fn row_text(&self, row: u16) -> String {
        let line = Line(row as i32) - self.term.grid().display_offset();
        let cols = self.size.cols;
        let start = Point::new(line, Column(0));
        let end = Point::new(line, Column(cols.saturating_sub(1)));
        self.term.bounds_to_string(start, end)
    }

    fn all_lines(&self) -> Vec<String> {
        let grid = self.term.grid();
        let history = grid.history_size() as i32;
        let cols = self.size.cols;
        let mut out = Vec::with_capacity((history as usize) + self.size.rows);
        for l in -history..(self.size.rows as i32) {
            let start = Point::new(Line(l), Column(0));
            let end = Point::new(Line(l), Column(cols.saturating_sub(1)));
            out.push(self.term.bounds_to_string(start, end).trim_end().to_string());
        }
        out
    }

    fn scroll_to(&mut self, offset: usize) {
        let cur = self.term.grid().display_offset() as i32;
        let target = offset.min(self.term.grid().history_size()) as i32;
        let delta = target - cur;
        if delta != 0 {
            self.term.scroll_display(alacritty_terminal::grid::Scroll::Delta(delta));
        }
    }

    fn take_events(&mut self) -> Vec<EmulatorEvent> {
        let mut events = std::mem::take(&mut *self.listener.events.lock().unwrap());
        if self.intercept.options().sixel_images {
            // The backend answers a device-attributes query for itself and
            // knows nothing about the sixel decoder sitting in front of it, so
            // the claim is added on the way out rather than the reply being
            // reimplemented here.
            for e in &mut events {
                if let EmulatorEvent::PtyWrite(bytes) = e {
                    if is_primary_da(bytes) {
                        *bytes = b"\x1b[?62;4;22c".to_vec();
                    }
                }
            }
        }
        events
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
        let mut e = AlacrittyEmulator::new(10, 3, 100);
        e.feed(b"hi \x1b[31mred\x1b[0m");
        let mut buf = Vec::new();
        e.snapshot(&Palette::default(), &mut buf);
        assert_eq!(buf.len(), HEADER_BYTES + 10 * 3 * CELL_BYTES);
        assert_eq!(cell_at(&buf, 10, 0, 0).0, 'h' as u32);
        assert_eq!(cell_at(&buf, 10, 3, 0).0, 'r' as u32);
        assert_eq!(cell_at(&buf, 10, 3, 0).1, Palette::default().colors[1]);
        // Cursor after "hi red" => column 6.
        assert_eq!(i16::from_le_bytes(buf[4..6].try_into().unwrap()), 6);
    }

    #[test]
    fn wide_chars_and_selection() {
        let mut e = AlacrittyEmulator::new(10, 2, 100);
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
        let mut e = AlacrittyEmulator::new(5, 2, 100);
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
    fn kitty_keyboard_flags_are_read_from_the_mode_stack() {
        let mut e = AlacrittyEmulator::new(5, 2, 10);
        assert_eq!(e.modes().kitty_flags, 0);
        // Nothing happens until the protocol is switched on.
        e.feed(b"\x1b[>3u");
        assert_eq!(e.modes().kitty_flags, 0);
        e.set_intercept(InterceptOptions { keyboard_protocol: true, ..Default::default() });
        e.feed(b"\x1b[>3u");
        assert_eq!(e.modes().kitty_flags, 3);
        e.feed(b"\x1b[=1;1u");
        assert_eq!(e.modes().kitty_flags & 1, 1);
        e.feed(b"\x1b[<u");
        assert_eq!(e.modes().kitty_flags, 0);
    }

    #[test]
    fn the_attributes_reply_claims_sixel_only_when_it_is_on() {
        let mut e = AlacrittyEmulator::new(5, 2, 10);
        e.set_intercept(InterceptOptions { sixel_images: true, ..Default::default() });
        e.feed(b"\x1b[c");
        let evs = e.take_events();
        assert!(matches!(&evs[0], EmulatorEvent::PtyWrite(v) if v == b"\x1b[?62;4;22c"));

        let mut e = AlacrittyEmulator::new(5, 2, 10);
        e.feed(b"\x1b[c");
        let evs = e.take_events();
        assert!(matches!(&evs[0], EmulatorEvent::PtyWrite(v) if v != b"\x1b[?62;4;22c"));
    }

    #[test]
    fn a_notification_becomes_an_event_and_leaves_no_text() {
        let mut e = AlacrittyEmulator::new(20, 2, 10);
        e.set_intercept(InterceptOptions { notifications: true, ..Default::default() });
        e.feed(b"a\x1b]777;notify;Done;ok\x07b");
        assert_eq!(e.row_text(0).trim_end(), "ab");
        let evs = e.take_events();
        assert!(evs.iter().any(|ev| matches!(ev, EmulatorEvent::Notify { title, body } if title == "Done" && body == "ok")));
    }

    #[test]
    fn bold_can_be_drawn_bright() {
        let mut e = AlacrittyEmulator::new(10, 1, 10);
        e.feed(b"\x1b[1;31mR");
        let mut buf = Vec::new();
        e.snapshot(&Palette::default(), &mut buf);
        assert_eq!(cell_at(&buf, 10, 0, 0).1, Palette::default().colors[1]);

        e.set_bold_is_bright(true);
        buf.clear();
        e.snapshot(&Palette::default(), &mut buf);
        assert_eq!(cell_at(&buf, 10, 0, 0).1, Palette::default().colors[9]);
    }

    #[test]
    fn select_all_takes_the_whole_buffer() {
        let mut e = AlacrittyEmulator::new(20, 4, 100);
        e.feed(b"alpha\r\nbeta\r\ngamma\r\ndelta\r\nepsilon\r\nzeta");
        assert!(e.history_size() >= 2, "some lines must have gone into scrollback");

        e.selection_all();
        let text = e.selection_text().expect("select all must select something");
        for word in ["alpha", "beta", "gamma", "delta", "epsilon", "zeta"] {
            assert!(text.contains(word), "select all dropped {word:?}: {text:?}");
        }
    }

    #[test]
    fn responds_to_device_attributes() {
        let mut e = AlacrittyEmulator::new(5, 2, 10);
        e.feed(b"\x1b[c");
        let evs = e.take_events();
        assert!(matches!(&evs[0], EmulatorEvent::PtyWrite(v) if v.starts_with(b"\x1b[?")));
    }

    // ---- images -----------------------------------------------------------

    fn with_images(cols: u16, rows: u16, scrollback: usize) -> AlacrittyEmulator {
        let mut e = AlacrittyEmulator::new(cols, rows, scrollback);
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
        apc(
            &format!("a=T,f=32,s={w},v={h},i=1"),
            &vec![255u8; (w * h * 4) as usize],
        )
    }

    #[test]
    fn the_scrollback_depth_saturates_so_it_cannot_anchor_anything() {
        // The reason placements are marked in the grid rather than counted
        // against an absolute line: once the history is full this number stops
        // moving, and any line counter derived from it stops with it.
        let mut e = AlacrittyEmulator::new(10, 2, 5);
        for i in 0..50 {
            e.feed(format!("line {i}\r\n").as_bytes());
        }
        assert_eq!(e.history_size(), 5);
        for i in 0..50 {
            e.feed(format!("more {i}\r\n").as_bytes());
        }
        assert_eq!(e.history_size(), 5);
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
        assert!(evs
            .iter()
            .any(|ev| matches!(ev, EmulatorEvent::PtyWrite(b) if b == b"\x1b_Gi=1;OK\x1b\\")));
    }

    #[test]
    fn an_image_command_survives_any_chunking() {
        // A network hands the same bytes over in whatever pieces it likes, and
        // an image that lands a column to the left because of it would be a
        // bug nobody could reproduce.
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

        // Four more lines push the last of the six rows off the top.
        for _ in 0..4 {
            e.feed(b"\r\n");
        }
        assert_eq!(e.images()[0].row, 0);
        e.feed(b"\r\n");
        // Half of it is above the viewport now, and it is still drawn.
        assert_eq!(e.images()[0].row, -1);
        e.feed(b"\r\n\r\n");
        assert!(e.images().is_empty(), "an image scrolled out of view is not drawn");

        // Scrolling back finds it again, without the placement having been
        // told anything about the scroll.
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

        // More rows: the content moves down with the viewport, the image with it.
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

        // Erasing one of its rows leaves the rest of the image where it is;
        // erasing the last of them is what makes it gone.
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

        // An image drawn on the alt screen lives and dies there.
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
        // The covered cells are spaces, which is what a selection copies, and
        // they carry no hyperlink the app could offer to open.
        assert_eq!(cell_at(&buf, 20, 0, 0).0, ' ' as u32);
        assert_eq!(cell_at(&buf, 20, 0, 0).3 & CellFlags::HYPERLINK, 0);
        assert_eq!(cell_at(&buf, 20, 1, 0).3 & CellFlags::HYPERLINK, 0);

        // A hyperlink the remote side actually wrote is still reported.
        e.feed(b"\x1b[2;1H\x1b]8;;https://example.com\x1b\\hi\x1b]8;;\x1b\\");
        buf.clear();
        e.snapshot(&Palette::default(), &mut buf);
        assert!(cell_at(&buf, 20, 0, 1).3 & CellFlags::HYPERLINK != 0);
    }

    #[test]
    fn a_sixel_image_advances_the_cursor_by_its_height() {
        let mut e = with_images(20, 6, 100);
        // Twenty columns of six pixels: two cells wide, one cell tall.
        e.feed(b"\x1bP0;1;0q#1;2;0;100;0!20~\x1b\\");
        let imgs = e.images();
        assert_eq!(imgs.len(), 1);
        assert_eq!((imgs[0].col, imgs[0].row), (0, 0));
        assert_eq!((imgs[0].cols, imgs[0].rows), (2, 1));
        // Sixel leaves the cursor at the left margin of the line below.
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
        // kitty leaves the cursor just past the image, so a delete "at the
        // cursor" from there is not about this one.
        e.feed(&apc("a=d,d=c,i=1", b""));
        assert_eq!(e.images().len(), 1);
        // From inside it, it is.
        e.feed(b"\x1b[1;2H");
        e.feed(&apc("a=d,d=c,i=1", b""));
        assert!(e.images().is_empty());
    }

    // ---- prompt marks -----------------------------------------------------

    fn with_marks(cols: u16, rows: u16, scrollback: usize) -> AlacrittyEmulator {
        let mut e = AlacrittyEmulator::new(cols, rows, scrollback);
        e.set_intercept(InterceptOptions { prompt_marks: true, ..Default::default() });
        e
    }

    /// One command the way a shell that marks its prompts sends it: the prompt
    /// drawn, the line typed into it, the output, and the status at the end.
    fn command(prompt: &str, typed: &str, output: &str, status: i32) -> Vec<u8> {
        format!("\x1b]133;A\x07{prompt}\x1b]133;B\x07{typed}\r\n\x1b]133;C\x07{output}\x1b]133;D;{status}\x07")
            .into_bytes()
    }

    fn marks_of(e: &AlacrittyEmulator) -> Vec<(PromptMark, i32)> {
        e.prompt_marks().into_iter().map(|m| (m.mark, m.row)).collect()
    }

    #[test]
    fn a_mark_lands_on_the_first_cell_the_shell_prints_after_it() {
        let mut e = with_marks(20, 6, 100);
        e.feed(&command("$ ", "ls", "one\r\ntwo\r\n", 0));
        // The next prompt is what gives the finished command a cell: the shell
        // prints nothing at all between `D` and the `A` that follows it.
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
        // The text is the shell's own, untouched: the marks are attributes on
        // cells that were going to be written anyway.
        assert_eq!(e.row_text(0).trim_end(), "$ ls");
        assert_eq!(e.row_text(1).trim_end(), "one");
        assert_eq!(e.row_text(3).trim_end(), "$");
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

        // Enough blank lines to push all of it off the top.
        for _ in 0..6 {
            e.feed(b"\r\n");
        }
        let above = marks_of(&e);
        assert!(above.iter().all(|(_, row)| *row < 0), "a mark in the history reports a positive row: {above:?}");

        // Scrolling back finds them again, without the marks having been told
        // anything about the scroll.
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
    fn clear_takes_the_marks_into_the_scrollback_with_the_text() {
        let mut e = with_marks(20, 6, 100);
        e.feed(&command("$ ", "ls", "one\r\n", 0));
        let before = marks_of(&e);

        e.feed(b"\x1b[H\x1b[2J");
        let after = marks_of(&e);
        assert_eq!(after.len(), before.len(), "clear lost the marks the scrollback still holds");
        assert!(after.iter().all(|(_, row)| *row < 0), "clear left the marks on the screen: {after:?}");
        assert_eq!(
            after.iter().map(|(m, _)| *m).collect::<Vec<_>>(),
            before.iter().map(|(m, _)| *m).collect::<Vec<_>>(),
        );

        // Wiping the scrollback as well is what actually ends them.
        e.feed(b"\x1b[3J");
        assert!(e.prompt_marks().is_empty());
    }

    #[test]
    fn the_alt_screen_hides_the_marks_and_gives_them_back() {
        let mut e = with_marks(20, 6, 100);
        e.feed(&command("$ ", "ls", "one\r\n", 0));
        let before = marks_of(&e);

        // As a full-screen program starts: the alt screen, then the cursor home.
        e.feed(b"\x1b[?1049h\x1b[H");
        assert!(e.prompt_marks().is_empty(), "the primary screen's marks showed through the alt screen");
        // A shell run inside the alt screen marks it, and only it.
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

        // A hyperlink the remote side actually wrote is still reported, and
        // taking the pen from us does not leave our mark hunting for a cell.
        e.feed(b"\x1b]8;;https://example.com\x1b\\hi\x1b]8;;\x1b\\");
        buf.clear();
        e.snapshot(&Palette::default(), &mut buf);
        assert!(cell_at(&buf, 20, 2, 0).3 & CellFlags::HYPERLINK != 0);
    }

    #[test]
    fn a_shell_that_sends_no_marks_has_none() {
        let mut e = with_marks(20, 3, 10);
        e.feed(b"$ ls\r\none\r\n");
        assert!(e.prompt_marks().is_empty());
    }

    #[test]
    fn switching_images_off_gives_the_memory_back() {
        let mut e = with_images(20, 6, 100);
        e.feed(&image(20, 20));
        assert_eq!(e.images().len(), 1);
        e.set_intercept(InterceptOptions::default());
        assert!(e.images().is_empty());
    }
}
