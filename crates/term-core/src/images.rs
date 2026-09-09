//! Inline images: the kitty graphics protocol, sixel, and the store they land in.
//!
//! Two protocols arrive here, both already lifted out of the byte stream by
//! [`crate::intercept`]. What they have in common is the end product: a block
//! of RGBA pixels, a number of character cells it covers, and a position on
//! the screen that has to keep up with text scrolling underneath it.
//!
//! Where an image *is* deliberately does not live in this module. An absolute
//! grid line would be the obvious anchor and it does not survive contact with
//! the backend: `alacritty_terminal` reports a scrollback depth that saturates
//! once the history is full, so a line counter derived from it stops advancing
//! and every later image is anchored to the wrong row. Instead the emulator
//! marks the cells an image covers with an OSC 8 hyperlink naming the
//! placement ([`mark_uri`]) and reads the position back out of the grid each
//! frame. Scrolling, reflow, erasure and the alt-screen swap then need no code
//! at all: they are what the grid already does to its own cells.
//!
//! The one hard promise made here is the memory cap. Images are the only part
//! of the app where a remote host can ask for unbounded memory, so the store
//! is a fixed [`CAP`] bytes with least-recently-used eviction rather than a
//! hope that nobody sends a poster.

use std::collections::HashMap;

use base64::engine::general_purpose::{GeneralPurpose, GeneralPurposeConfig, STANDARD};
use base64::engine::{DecodePaddingMode, Engine};

/// How much decoded pixel data one session may hold.
pub const CAP: usize = 64 << 20;

/// An image bigger than the whole store can never be kept, so it is refused
/// before it is decoded rather than after.
const MAX_PIXELS: usize = CAP / 4;

/// A chunked transfer that never sends its last chunk must not grow forever.
/// Base64 is a third larger than what it carries, so the room is a third more
/// than the store: an image that would just fit is not refused on the way in.
const MAX_TRANSFER: usize = CAP + CAP / 2;

/// Placements outlive their marks (an image scrolled into history has none on
/// screen), so they are kept until this many newer ones exist.
const MAX_PLACEMENTS: usize = 1024;

/// The URI scheme the placement marks use.
///
/// It is deliberately not a real scheme: the app's link detection must never
/// offer one of these to be tapped, and a name no browser knows is the
/// simplest way to say so.
pub const MARK_SCHEME: &str = "flintterm-image";

/// The hyperlink URI that marks row `index` of placement `key`.
pub fn mark_uri(key: u32, index: u16) -> String {
    format!("{MARK_SCHEME}:{key}/{index}")
}

/// The placement and row a mark URI names, if it is one of ours.
pub fn parse_mark(uri: &str) -> Option<(u32, u16)> {
    let rest = uri.strip_prefix(MARK_SCHEME)?.strip_prefix(':')?;
    let (key, index) = rest.split_once('/')?;
    Some((key.parse().ok()?, index.parse().ok()?))
}

/// Decoded pixels, kept until the cap evicts them.
#[derive(Debug, Clone)]
pub struct Image {
    pub id: u32,
    /// Bumped whenever this id is given new pixels, so a cached bitmap on the
    /// other side of the FFI can tell that it is stale.
    pub generation: u32,
    pub width: u32,
    pub height: u32,
    /// Row-major RGBA, 4 bytes per pixel.
    pub rgba: Vec<u8>,
    /// Monotonic stamp for the eviction order.
    used: u64,
}

/// One appearance of an image on the screen.
#[derive(Debug, Clone, Copy)]
pub struct Placement {
    pub key: u32,
    pub image: u32,
    /// Client-chosen placement id (kitty `p=`), for the delete commands.
    pub client_id: u32,
    pub cols: u16,
    pub rows: u16,
    pub z: i32,
    /// Source rectangle inside the image, in pixels.
    pub src: (u32, u32, u32, u32),
}

/// A marked cell found in the grid: placement `key`, its row `index`, and
/// where that cell currently sits in the viewport.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Mark {
    pub key: u32,
    pub index: u16,
    pub col: u16,
    pub row: u16,
}

/// A placement as the renderer should draw it, in viewport coordinates.
///
/// `row` is negative when the top of the image has scrolled above the
/// viewport; the renderer clips.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct PlacedImage {
    pub key: u32,
    pub id: u32,
    pub generation: u32,
    pub col: i32,
    pub row: i32,
    pub cols: u16,
    pub rows: u16,
    pub z: i32,
    pub width: u32,
    pub height: u32,
    pub src: (u32, u32, u32, u32),
}

/// What the emulator must draw into the grid for a placement to exist.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Draw {
    pub key: u32,
    pub cols: u16,
    pub rows: u16,
    /// kitty `C=1`: leave the cursor where it was.
    pub keep_cursor: bool,
    /// Sixel leaves the cursor at the start of the line below the image;
    /// kitty leaves it just past the bottom-right cell.
    pub sixel: bool,
}

/// Which placements a `a=d` command is about. The ones that need to know where
/// an image currently *is* are resolved by the emulator, which can see the
/// grid; the rest are answered here.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DeleteTarget {
    All,
    /// By image id, and by placement id when the client gave one.
    Image { id: u32, placement: Option<u32> },
    Number(u32),
    Z(i32),
    Cursor,
    Cell { col: u32, row: u32 },
    Column(u32),
    Row(u32),
}

/// A delete command, once parsed.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Delete {
    pub target: DeleteTarget,
    /// The uppercase forms also throw the pixels away.
    pub free_data: bool,
}

/// What a graphics command asks the caller to do next.
#[derive(Debug, Default)]
pub struct Outcome {
    /// The protocol's answer, ready for the pty.
    pub reply: Option<Vec<u8>>,
    pub draw: Option<Draw>,
    pub delete: Option<Delete>,
    /// The store changed, so the view should ask for the placements again.
    pub changed: bool,
}

/// Everything one session knows about its images.
pub struct ImageStore {
    images: HashMap<u32, Image>,
    placements: Vec<Placement>,
    bytes: usize,
    clock: u64,
    next_key: u32,
    /// Ids handed out for images the client did not name. Kept well above what
    /// a client is likely to choose so the two never collide.
    next_auto_id: u32,
    /// Kitty image numbers (`I=`) mapped to the id we gave them.
    numbers: HashMap<u32, u32>,
    /// A chunked transfer in progress (`m=1`).
    pending: Option<Pending>,
    cell: (u32, u32),
}

struct Pending {
    control: Control,
    data: Vec<u8>,
}

impl Default for ImageStore {
    fn default() -> Self {
        Self::new()
    }
}

impl ImageStore {
    pub fn new() -> Self {
        Self {
            images: HashMap::new(),
            placements: Vec::new(),
            bytes: 0,
            clock: 0,
            next_key: 1,
            next_auto_id: 0x8000_0000,
            numbers: HashMap::new(),
            pending: None,
            cell: (8, 16),
        }
    }

    /// The size of one character cell in pixels; images are measured in cells.
    pub fn set_cell_size(&mut self, width: u32, height: u32) {
        self.cell = (width.max(1), height.max(1));
    }

    pub fn cell_size(&self) -> (u32, u32) {
        self.cell
    }

    pub fn is_empty(&self) -> bool {
        self.images.is_empty() && self.placements.is_empty()
    }

    /// Bytes of pixel data currently held.
    pub fn bytes(&self) -> usize {
        self.bytes
    }

    pub fn image(&self, id: u32) -> Option<&Image> {
        self.images.get(&id)
    }

    pub fn placement(&self, key: u32) -> Option<&Placement> {
        self.placements.iter().find(|p| p.key == key)
    }

    /// Every placement the store still knows about, whether or not its marks
    /// are anywhere on screen.
    pub fn placements(&self) -> &[Placement] {
        &self.placements
    }

    /// Everything goes: the session's images are irrelevant once the feature
    /// is switched off or the screen they lived on is gone.
    pub fn clear(&mut self) {
        self.images.clear();
        self.placements.clear();
        self.numbers.clear();
        self.pending = None;
        self.bytes = 0;
    }

    /// Turn the marks found in the grid into placements to draw, back to
    /// front. A mark whose placement has been deleted or whose image was
    /// evicted resolves to nothing, which is how an image stops being drawn.
    pub fn resolve(&self, marks: &[Mark]) -> Vec<PlacedImage> {
        let mut out: Vec<PlacedImage> = Vec::new();
        for m in marks {
            let Some(p) = self.placement(m.key) else { continue };
            let Some(img) = self.images.get(&p.image) else { continue };
            let top = m.row as i32 - m.index as i32;
            match out.iter_mut().find(|o| o.key == m.key) {
                // The topmost surviving mark decides where the image starts;
                // a lower one only agrees with it.
                Some(o) if top < o.row || (top == o.row && (m.col as i32) < o.col) => {
                    o.row = top;
                    o.col = m.col as i32;
                }
                Some(_) => {}
                None => out.push(PlacedImage {
                    key: p.key,
                    id: img.id,
                    generation: img.generation,
                    col: m.col as i32,
                    row: top,
                    cols: p.cols,
                    rows: p.rows,
                    z: p.z,
                    width: img.width,
                    height: img.height,
                    src: p.src,
                }),
            }
        }
        out.sort_by_key(|p| p.z);
        out
    }

    /// Drop the placements a delete command names. `hits` are the keys the
    /// emulator worked out for the position-based forms.
    pub fn apply_delete(&mut self, del: Delete, hits: &[u32]) {
        // A number is only ever a stand-in for the id it was given.
        let target = match del.target {
            DeleteTarget::Number(n) => match self.numbers.get(&n) {
                Some(&id) => DeleteTarget::Image { id, placement: None },
                None => return,
            },
            other => other,
        };
        let mut freed: Vec<u32> = Vec::new();
        let mut kept = Vec::with_capacity(self.placements.len());
        for p in std::mem::take(&mut self.placements) {
            let gone = match target {
                DeleteTarget::All => true,
                DeleteTarget::Image { id, placement } => {
                    p.image == id && placement.is_none_or(|c| c == p.client_id)
                }
                DeleteTarget::Number(_) => false,
                DeleteTarget::Z(z) => p.z == z,
                _ => hits.contains(&p.key),
            };
            if gone {
                freed.push(p.image);
            } else {
                kept.push(p);
            }
        }
        self.placements = kept;
        if del.free_data {
            if target == DeleteTarget::All {
                self.images.clear();
                self.numbers.clear();
                self.bytes = 0;
            } else {
                for id in freed {
                    self.remove_image(id);
                }
            }
        }
    }

    /// A sixel image, placed at the cursor.
    pub fn sixel(&mut self, params: &[u16], data: &[u8]) -> Outcome {
        // P2 = 1 asks for untouched pixels to stay transparent. The decoder
        // leaves them transparent either way: composited over the terminal
        // background that is what both readings look like, and it is the only
        // one that is right when the image sits on colored text.
        let Some(decoded) = decode_sixel(params, data, MAX_PIXELS) else {
            return Outcome::default();
        };
        let id = self.alloc_id();
        let (cols, rows) = self.cells_for(decoded.width, decoded.height);
        self.insert(id, decoded.width, decoded.height, decoded.rgba);
        if self.images.contains_key(&id) {
            let key = self.add_placement(id, 0, cols, rows, 0, (0, 0, decoded.width, decoded.height));
            Outcome {
                draw: Some(Draw { key, cols, rows, keep_cursor: false, sixel: true }),
                changed: true,
                ..Default::default()
            }
        } else {
            Outcome::default()
        }
    }

    /// One kitty graphics command: the APC payload with the leading `G` gone.
    pub fn kitty(&mut self, payload: &[u8]) -> Outcome {
        let (control, data) = match payload.iter().position(|&b| b == b';') {
            Some(i) => (&payload[..i], &payload[i + 1..]),
            None => (payload, &[][..]),
        };

        // A continuation chunk carries only `m` and `q`; everything else about
        // the transfer was settled by the first one.
        if let Some(mut pending) = self.pending.take() {
            let more = match parse_chunk(control) {
                Ok(more) => more,
                Err(e) => return self.error(&pending.control, e),
            };
            if pending.data.len() + data.len() > MAX_TRANSFER {
                return self.error(&pending.control, "transfer too large");
            }
            pending.data.extend_from_slice(data);
            if more {
                self.pending = Some(pending);
                return Outcome::default();
            }
            let control = pending.control.clone();
            return self.run(control, &pending.data);
        }

        let (control, bad) = Control::parse(control);
        if let Some(e) = bad {
            return self.error(&control, e);
        }
        if control.more {
            self.pending = Some(Pending { control, data: data.to_vec() });
            return Outcome::default();
        }
        self.run(control, data)
    }

    fn run(&mut self, c: Control, data: &[u8]) -> Outcome {
        match c.action {
            b'q' => match self.decode(&c, data) {
                Ok(_) => self.ok(&c),
                Err(e) => self.error(&c, e),
            },
            b't' | b'T' => self.transmit(c, data),
            b'p' => self.put(c),
            b'd' => self.delete(c),
            // Animation, and anything else a newer kitty grew.
            _ => self.error(&c, "unsupported action"),
        }
    }

    fn transmit(&mut self, c: Control, data: &[u8]) -> Outcome {
        let pixels = match self.decode(&c, data) {
            Ok(p) => p,
            Err(e) => return self.error(&c, e),
        };
        let id = match (c.id, c.number) {
            (Some(id), _) => id,
            (None, Some(n)) => match self.numbers.get(&n) {
                Some(&id) => id,
                None => {
                    let id = self.alloc_id();
                    self.numbers.insert(n, id);
                    id
                }
            },
            (None, None) => self.alloc_id(),
        };
        self.insert(id, pixels.width, pixels.height, pixels.rgba);
        if !self.images.contains_key(&id) {
            return self.error(&c, "image does not fit in the image store");
        }
        let mut out = self.ok(&c);
        out.changed = true;
        if c.action == b'T' {
            out.draw = Some(self.display(&c, id));
        }
        out
    }

    fn put(&mut self, c: Control) -> Outcome {
        let id = match (c.id, c.number.and_then(|n| self.numbers.get(&n).copied())) {
            (Some(id), _) => id,
            (None, Some(id)) => id,
            (None, None) => return self.enoent(&c),
        };
        if !self.images.contains_key(&id) {
            return self.enoent(&c);
        }
        let draw = self.display(&c, id);
        let mut out = self.ok(&c);
        out.draw = Some(draw);
        out.changed = true;
        out
    }

    fn delete(&mut self, c: Control) -> Outcome {
        let what = c.delete.unwrap_or(b'a');
        let free_data = what.is_ascii_uppercase();
        let target = match what.to_ascii_lowercase() {
            b'a' => DeleteTarget::All,
            b'i' => match c.id {
                Some(id) => DeleteTarget::Image { id, placement: c.placement },
                None => return self.error(&c, "delete by id needs i="),
            },
            b'n' => match c.number {
                Some(n) => DeleteTarget::Number(n),
                None => return self.error(&c, "delete by number needs I="),
            },
            b'z' => DeleteTarget::Z(c.z),
            b'c' => DeleteTarget::Cursor,
            b'p' => DeleteTarget::Cell { col: c.x, row: c.y },
            b'x' => DeleteTarget::Column(c.x),
            b'y' => DeleteTarget::Row(c.y),
            b'q' => DeleteTarget::All,
            _ => return self.error(&c, "unknown delete target"),
        };
        let mut out = self.ok(&c);
        out.delete = Some(Delete { target, free_data });
        out.changed = true;
        out
    }

    /// Where an image goes and how big it is on the grid.
    fn display(&mut self, c: &Control, id: u32) -> Draw {
        // Showing an image is using it, so the cap evicts something else next.
        self.touch(id);
        let (w, h) = self.images.get(&id).map(|i| (i.width, i.height)).unwrap_or((1, 1));
        let sx = c.src_x.min(w.saturating_sub(1));
        let sy = c.src_y.min(h.saturating_sub(1));
        let sw = if c.src_w == 0 { w - sx } else { c.src_w.min(w - sx) };
        let sh = if c.src_h == 0 { h - sy } else { c.src_h.min(h - sy) };
        let (fit_cols, fit_rows) = self.cells_for(sw, sh);
        let cols = if c.cols > 0 { c.cols } else { fit_cols };
        let rows = if c.rows > 0 { c.rows } else { fit_rows };
        let key = self.add_placement(id, c.placement.unwrap_or(0), cols, rows, c.z, (sx, sy, sw, sh));
        Draw { key, cols, rows, keep_cursor: c.no_cursor, sixel: false }
    }

    fn cells_for(&self, width: u32, height: u32) -> (u16, u16) {
        let (cw, ch) = self.cell;
        let cols = width.div_ceil(cw).clamp(1, u16::MAX as u32) as u16;
        let rows = height.div_ceil(ch).clamp(1, u16::MAX as u32) as u16;
        (cols, rows)
    }

    fn add_placement(&mut self, image: u32, client_id: u32, cols: u16, rows: u16, z: i32, src: (u32, u32, u32, u32)) -> u32 {
        let key = self.next_key;
        self.next_key = self.next_key.wrapping_add(1).max(1);
        self.placements.push(Placement { key, image, client_id, cols, rows, z, src });
        if self.placements.len() > MAX_PLACEMENTS {
            self.placements.remove(0);
        }
        key
    }

    fn alloc_id(&mut self) -> u32 {
        let id = self.next_auto_id;
        self.next_auto_id = self.next_auto_id.wrapping_add(1).max(0x8000_0000);
        id
    }

    /// Store the pixels under `id`, evicting the least recently used images
    /// until the total fits in [`CAP`]. An image that cannot fit even alone is
    /// simply not stored, and the caller reports that as an error.
    fn insert(&mut self, id: u32, width: u32, height: u32, rgba: Vec<u8>) {
        if rgba.len() > CAP {
            return;
        }
        self.clock += 1;
        let generation = self.images.get(&id).map(|i| i.generation + 1).unwrap_or(0);
        // Replacing an id keeps its placements: they now show the new pixels,
        // which is what a client re-transmitting under the same id means.
        self.drop_pixels(id);
        self.bytes += rgba.len();
        self.images.insert(id, Image { id, generation, width, height, rgba, used: self.clock });
        while self.bytes > CAP {
            let victim = self.images.values().filter(|i| i.id != id).min_by_key(|i| i.used).map(|i| i.id);
            let Some(victim) = victim else { break };
            self.remove_image(victim);
        }
    }

    fn drop_pixels(&mut self, id: u32) {
        if let Some(old) = self.images.remove(&id) {
            self.bytes -= old.rgba.len();
        }
    }

    fn remove_image(&mut self, id: u32) {
        self.drop_pixels(id);
        self.placements.retain(|p| p.image != id);
        self.numbers.retain(|_, v| *v != id);
    }

    /// Mark an image as freshly used so the cap evicts something else first.
    pub fn touch(&mut self, id: u32) {
        self.clock += 1;
        let clock = self.clock;
        if let Some(img) = self.images.get_mut(&id) {
            img.used = clock;
        }
    }

    fn decode(&self, c: &Control, data: &[u8]) -> Result<Pixels, &'static str> {
        if c.medium != b'd' {
            // Files, temp files and shared memory are a desktop terminal's
            // idea of cheap; there is no shared filesystem with the host at
            // the other end of an ssh connection, so saying so is better than
            // pretending the image arrived.
            return Err("only direct transmission is supported");
        }
        let mut cleaned: Vec<u8> = Vec::with_capacity(data.len());
        cleaned.extend(data.iter().copied().filter(|b| !b.is_ascii_whitespace()));
        let raw = base64_engine()
            .decode(&cleaned)
            .map_err(|_| "payload is not valid base64")?;
        let raw = if c.compression == b'z' { inflate(&raw)? } else { raw };
        match c.format {
            100 => decode_png(&raw),
            32 => Pixels::from_raw(c.width, c.height, &raw, 4),
            24 => Pixels::from_raw(c.width, c.height, &raw, 3),
            _ => Err("unsupported format"),
        }
    }

    fn ok(&self, c: &Control) -> Outcome {
        self.reply(c, "OK", false)
    }

    fn enoent(&self, c: &Control) -> Outcome {
        self.reply(c, "ENOENT:no such image", true)
    }

    fn error(&mut self, c: &Control, message: &str) -> Outcome {
        self.pending = None;
        self.reply(c, &format!("EINVAL:{message}"), true)
    }

    /// kitty answers only when the client named the image, and `q` says how
    /// much of that answer it wants to hear.
    fn reply(&self, c: &Control, message: &str, is_error: bool) -> Outcome {
        let named = c.id.is_some() || c.number.is_some();
        let wanted = if is_error { c.quiet < 2 } else { c.quiet < 1 };
        if !named || !wanted {
            return Outcome::default();
        }
        let mut head = String::new();
        if let Some(id) = c.id {
            head.push_str(&format!("i={id}"));
        }
        if let Some(n) = c.number {
            if !head.is_empty() {
                head.push(',');
            }
            head.push_str(&format!("I={n}"));
        }
        Outcome {
            reply: Some(format!("\x1b_G{head};{message}\x1b\\").into_bytes()),
            ..Default::default()
        }
    }
}

// ---------------------------------------------------------------------------
// Control data
// ---------------------------------------------------------------------------

/// The `k=v` pairs in front of a kitty payload.
#[derive(Debug, Clone)]
struct Control {
    action: u8,
    quiet: u8,
    format: u32,
    medium: u8,
    compression: u8,
    more: bool,
    width: u32,
    height: u32,
    id: Option<u32>,
    number: Option<u32>,
    placement: Option<u32>,
    src_x: u32,
    src_y: u32,
    src_w: u32,
    src_h: u32,
    cols: u16,
    rows: u16,
    z: i32,
    no_cursor: bool,
    delete: Option<u8>,
    /// `x=` / `y=` again, this time as the cell a delete talks about.
    x: u32,
    y: u32,
}

impl Default for Control {
    fn default() -> Self {
        Self {
            action: b't',
            quiet: 0,
            format: 32,
            medium: b'd',
            compression: 0,
            more: false,
            width: 0,
            height: 0,
            id: None,
            number: None,
            placement: None,
            src_x: 0,
            src_y: 0,
            src_w: 0,
            src_h: 0,
            cols: 0,
            rows: 0,
            z: 0,
            no_cursor: false,
            delete: None,
            x: 0,
            y: 0,
        }
    }
}

impl Control {
    /// Read every pair, remembering the first complaint rather than stopping
    /// at it: the answer to a bad command still has to carry the image id the
    /// client used, and that id may come after the mistake.
    fn parse(bytes: &[u8]) -> (Self, Option<&'static str>) {
        let mut c = Control::default();
        let mut bad: Option<&'static str> = None;
        macro_rules! num {
            ($v:expr) => {
                match std::str::from_utf8($v).ok().and_then(|s| s.parse::<u32>().ok()) {
                    Some(n) => n,
                    None => {
                        bad.get_or_insert("bad number");
                        continue;
                    }
                }
            };
        }
        macro_rules! chr {
            ($v:expr) => {
                match $v {
                    [b] => *b,
                    _ => {
                        bad.get_or_insert("bad value");
                        continue;
                    }
                }
            };
        }
        for pair in bytes.split(|&b| b == b',') {
            let pair = trim(pair);
            if pair.is_empty() {
                continue;
            }
            let Some(eq) = pair.iter().position(|&b| b == b'=') else {
                bad.get_or_insert("control data is not key=value");
                continue;
            };
            let (k, v) = (&pair[..eq], trim(&pair[eq + 1..]));
            if k.len() != 1 {
                bad.get_or_insert("unknown key");
                continue;
            }
            match k[0] {
                b'a' => c.action = chr!(v),
                b'q' => c.quiet = num!(v).min(255) as u8,
                b'f' => c.format = num!(v),
                b't' => c.medium = chr!(v),
                b'o' => c.compression = chr!(v),
                b'm' => c.more = num!(v) != 0,
                b's' => c.width = num!(v),
                b'v' => c.height = num!(v),
                b'i' => c.id = Some(num!(v)),
                b'I' => c.number = Some(num!(v)),
                b'p' => c.placement = Some(num!(v)),
                b'x' => {
                    c.src_x = num!(v);
                    c.x = c.src_x;
                }
                b'y' => {
                    c.src_y = num!(v);
                    c.y = c.src_y;
                }
                b'w' => c.src_w = num!(v),
                b'h' => c.src_h = num!(v),
                b'c' => c.cols = num!(v).min(u16::MAX as u32) as u16,
                b'r' => c.rows = num!(v).min(u16::MAX as u32) as u16,
                b'z' => match std::str::from_utf8(v).ok().and_then(|s| s.parse::<i32>().ok()) {
                    Some(z) => c.z = z,
                    None => {
                        bad.get_or_insert("bad number");
                    }
                },
                b'C' => c.no_cursor = num!(v) != 0,
                b'd' => c.delete = Some(chr!(v)),
                // Read and ignored: cell-relative offsets and the sizes that
                // only mean something to a transmission medium we refuse.
                b'X' | b'Y' | b'S' | b'O' | b'U' | b'P' | b'Q' => {}
                _ => {
                    bad.get_or_insert("unknown key");
                }
            }
        }
        (c, bad)
    }
}

/// A continuation chunk: only `m` matters, and only `q` is allowed beside it.
fn parse_chunk(bytes: &[u8]) -> Result<bool, &'static str> {
    let mut more = false;
    for pair in bytes.split(|&b| b == b',') {
        let pair = trim(pair);
        if pair.is_empty() {
            continue;
        }
        let Some(i) = pair.iter().position(|&b| b == b'=') else {
            return Err("control data is not key=value");
        };
        let (k, v) = (&pair[..i], trim(&pair[i + 1..]));
        // Everything else was settled by the first chunk; kitty allows those
        // keys to be repeated, so they are read and thrown away.
        if k == b"m" {
            more = std::str::from_utf8(v).ok().and_then(|s| s.parse::<u32>().ok()).ok_or("bad number")? != 0;
        }
    }
    Ok(more)
}

fn trim(mut b: &[u8]) -> &[u8] {
    while b.first().is_some_and(|c| c.is_ascii_whitespace()) {
        b = &b[1..];
    }
    while b.last().is_some_and(|c| c.is_ascii_whitespace()) {
        b = &b[..b.len() - 1];
    }
    b
}

/// kitty's payloads are standard base64, but not every client pads them.
fn base64_engine() -> GeneralPurpose {
    GeneralPurpose::new(
        &base64::alphabet::STANDARD,
        GeneralPurposeConfig::new()
            .with_decode_padding_mode(DecodePaddingMode::Indifferent)
            .with_decode_allow_trailing_bits(true),
    )
}

/// Base64 as the protocol writes it, for tests and for anything that has to
/// speak back.
pub fn base64_encode(bytes: &[u8]) -> String {
    STANDARD.encode(bytes)
}

// ---------------------------------------------------------------------------
// Pixels
// ---------------------------------------------------------------------------

/// Decoded RGBA and its size.
pub struct Pixels {
    pub width: u32,
    pub height: u32,
    pub rgba: Vec<u8>,
}

impl Pixels {
    fn from_raw(width: u32, height: u32, raw: &[u8], channels: usize) -> Result<Pixels, &'static str> {
        if width == 0 || height == 0 {
            return Err("raw pixels need s= and v=");
        }
        let count = (width as usize)
            .checked_mul(height as usize)
            .ok_or("image is too large")?;
        if count > MAX_PIXELS {
            return Err("image is too large");
        }
        if raw.len() < count * channels {
            return Err("payload is shorter than s= by v= says");
        }
        let rgba = if channels == 4 {
            raw[..count * 4].to_vec()
        } else {
            let mut rgba = vec![0xffu8; count * 4];
            for (out, src) in rgba.chunks_exact_mut(4).zip(raw.chunks_exact(3)) {
                out[..3].copy_from_slice(src);
            }
            rgba
        };
        Ok(Pixels { width, height, rgba })
    }
}

fn inflate(data: &[u8]) -> Result<Vec<u8>, &'static str> {
    use std::io::Read;
    let mut out = Vec::new();
    flate2::read::ZlibDecoder::new(data)
        .take((MAX_PIXELS * 4) as u64)
        .read_to_end(&mut out)
        .map_err(|_| "payload is not valid zlib")?;
    Ok(out)
}

fn decode_png(data: &[u8]) -> Result<Pixels, &'static str> {
    let mut decoder = png::Decoder::new(std::io::Cursor::new(data));
    decoder.set_transformations(png::Transformations::normalize_to_color8() | png::Transformations::ALPHA);
    let mut reader = decoder.read_info().map_err(|_| "payload is not a PNG")?;
    let size = reader.output_buffer_size().ok_or("PNG is too large")?;
    let info = reader.info();
    let (w, h) = (info.width, info.height);
    if (w as usize).saturating_mul(h as usize) > MAX_PIXELS {
        return Err("image is too large");
    }
    let mut buf = vec![0u8; size];
    let out = reader.next_frame(&mut buf).map_err(|_| "PNG could not be decoded")?;
    let count = (out.width as usize) * (out.height as usize);
    let rgba = match out.color_type {
        png::ColorType::Rgba => {
            buf.truncate(count * 4);
            buf
        }
        png::ColorType::Rgb => expand(&buf, count, 3, |px, s| [s[0], s[1], s[2], 0xff][px]),
        png::ColorType::GrayscaleAlpha => expand(&buf, count, 2, |px, s| [s[0], s[0], s[0], s[1]][px]),
        png::ColorType::Grayscale => expand(&buf, count, 1, |px, s| [s[0], s[0], s[0], 0xff][px]),
        _ => return Err("unsupported PNG color type"),
    };
    Ok(Pixels { width: out.width, height: out.height, rgba })
}

fn expand(src: &[u8], count: usize, channels: usize, pick: fn(usize, &[u8]) -> u8) -> Vec<u8> {
    let mut out = vec![0u8; count * 4];
    for i in 0..count {
        let s = &src[i * channels..i * channels + channels];
        for c in 0..4 {
            out[i * 4 + c] = pick(c, s);
        }
    }
    out
}

// ---------------------------------------------------------------------------
// Sixel
// ---------------------------------------------------------------------------

/// The VT340's sixteen colors, as percentages of full intensity — the palette
/// a sixel image starts with when it does not define one of its own.
const SIXEL_DEFAULTS: [(u8, u8, u8); 16] = [
    (0, 0, 0),
    (20, 20, 80),
    (80, 13, 13),
    (20, 80, 20),
    (80, 20, 80),
    (20, 80, 80),
    (80, 80, 20),
    (53, 53, 53),
    (26, 26, 26),
    (33, 33, 60),
    (60, 26, 26),
    (33, 60, 33),
    (60, 33, 60),
    (33, 60, 60),
    (60, 60, 33),
    (80, 80, 80),
];

fn percent(v: u32) -> u8 {
    ((v.min(100) * 255 + 50) / 100) as u8
}

/// Decode a sixel image to RGBA. Pixels the image never touches stay
/// transparent, which is what makes it composite correctly over whatever the
/// terminal had drawn there.
pub fn decode_sixel(params: &[u16], data: &[u8], max_pixels: usize) -> Option<Pixels> {
    let mut palette = [0u32; 256];
    for (i, (r, g, b)) in SIXEL_DEFAULTS.iter().enumerate() {
        palette[i] = pack_rgba(percent(*r as u32), percent(*g as u32), percent(*b as u32));
    }
    let _ = params;

    let mut rows: Vec<Vec<u32>> = Vec::new();
    let mut color = 0usize;
    let mut x = 0usize;
    let mut band = 0usize;
    let mut fixed: Option<(usize, usize)> = None;
    let mut i = 0usize;
    let mut painted = false;

    while i < data.len() {
        let b = data[i];
        match b {
            b'"' => {
                let (vals, next) = numbers(data, i + 1);
                i = next;
                if let (Some(&ph), Some(&pv)) = (vals.get(2), vals.get(3)) {
                    if ph > 0 && pv > 0 && (ph as usize) * (pv as usize) <= max_pixels {
                        fixed = Some((ph as usize, pv as usize));
                    }
                }
            }
            b'#' => {
                let (vals, next) = numbers(data, i + 1);
                i = next;
                let Some(&n) = vals.first() else { continue };
                let slot = (n as usize) % 256;
                color = slot;
                if vals.len() >= 5 {
                    let (space, a, b2, c) = (vals[1], vals[2], vals[3], vals[4]);
                    palette[slot] = match space {
                        1 => hls_to_rgba(a, b2, c),
                        _ => pack_rgba(percent(a), percent(b2), percent(c)),
                    };
                }
            }
            b'!' => {
                let (vals, next) = numbers(data, i + 1);
                let repeat = (vals.first().copied().unwrap_or(1).max(1) as usize).min(max_pixels);
                i = next;
                if i < data.len() && (0x3f..=0x7e).contains(&data[i]) {
                    let bits = data[i] - 0x3f;
                    i += 1;
                    if bits == 0 {
                        // Nothing to draw, and a run of blanks is allowed to
                        // be enormous; skipping it beats a million no-ops.
                        x += repeat;
                    } else {
                        for _ in 0..repeat {
                            paint(&mut rows, x, band, bits, palette[color], max_pixels)?;
                            x += 1;
                        }
                        painted = true;
                    }
                }
            }
            b'$' => {
                x = 0;
                i += 1;
            }
            b'-' => {
                x = 0;
                band += 6;
                i += 1;
            }
            0x3f..=0x7e => {
                let bits = b - 0x3f;
                paint(&mut rows, x, band, bits, palette[color], max_pixels)?;
                x += 1;
                painted |= bits != 0;
                i += 1;
            }
            _ => i += 1,
        }
    }
    if !painted && fixed.is_none() {
        return None;
    }

    let (width, height) = match fixed {
        Some(wh) => wh,
        None => (rows.iter().map(|r| r.len()).max().unwrap_or(0), rows.len()),
    };
    if width == 0 || height == 0 || width * height > max_pixels {
        return None;
    }
    let mut rgba = vec![0u8; width * height * 4];
    for (y, row) in rows.iter().enumerate().take(height) {
        for (x, &px) in row.iter().enumerate().take(width) {
            let o = (y * width + x) * 4;
            rgba[o..o + 4].copy_from_slice(&px.to_le_bytes());
        }
    }
    Some(Pixels { width: width as u32, height: height as u32, rgba })
}

/// Paint one sixel: six vertical pixels, the set bits in the current color.
fn paint(rows: &mut Vec<Vec<u32>>, x: usize, band: usize, bits: u8, color: u32, max_pixels: usize) -> Option<()> {
    for bit in 0..6 {
        if bits & (1 << bit) == 0 {
            continue;
        }
        let y = band + bit;
        // Bounds the canvas before it is allocated: a sixel stream is allowed
        // to name a coordinate, not to make us reserve memory for it.
        if (y + 1).saturating_mul(x + 1) > max_pixels {
            return None;
        }
        if rows.len() <= y {
            rows.resize(y + 1, Vec::new());
        }
        let row = &mut rows[y];
        if row.len() <= x {
            row.resize(x + 1, 0);
        }
        row[x] = color;
    }
    Some(())
}

/// Read the `;`-separated numbers a sixel command carries, and say where they
/// ended.
fn numbers(data: &[u8], mut i: usize) -> (Vec<u32>, usize) {
    let mut out = Vec::new();
    let mut cur: Option<u32> = None;
    while i < data.len() {
        match data[i] {
            b'0'..=b'9' => {
                cur = Some(cur.unwrap_or(0).saturating_mul(10) + (data[i] - b'0') as u32);
                i += 1;
            }
            b';' => {
                out.push(cur.take().unwrap_or(0));
                i += 1;
            }
            _ => break,
        }
    }
    if let Some(v) = cur {
        out.push(v);
    }
    (out, i)
}

#[inline]
fn pack_rgba(r: u8, g: u8, b: u8) -> u32 {
    u32::from_le_bytes([r, g, b, 0xff])
}

/// Sixel's other color space: hue in degrees, lightness and saturation in
/// percent, with hue measured from blue rather than red.
fn hls_to_rgba(h: u32, l: u32, s: u32) -> u32 {
    let l = (l.min(100) as f32) / 100.0;
    let s = (s.min(100) as f32) / 100.0;
    let h = ((h % 360) as f32 + 240.0) % 360.0;
    if s == 0.0 {
        let v = (l * 255.0).round() as u8;
        return pack_rgba(v, v, v);
    }
    let c = (1.0 - (2.0 * l - 1.0).abs()) * s;
    let hp = h / 60.0;
    let x = c * (1.0 - (hp % 2.0 - 1.0).abs());
    let (r, g, b) = match hp as u32 {
        0 => (c, x, 0.0),
        1 => (x, c, 0.0),
        2 => (0.0, c, x),
        3 => (0.0, x, c),
        4 => (x, 0.0, c),
        _ => (c, 0.0, x),
    };
    let m = l - c / 2.0;
    pack_rgba(
        ((r + m).clamp(0.0, 1.0) * 255.0).round() as u8,
        ((g + m).clamp(0.0, 1.0) * 255.0).round() as u8,
        ((b + m).clamp(0.0, 1.0) * 255.0).round() as u8,
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    /// One graphics command, with the payload base64-encoded the way a client
    /// would send it.
    fn kitty(store: &mut ImageStore, control: &str, payload: &[u8]) -> Outcome {
        let mut cmd = control.as_bytes().to_vec();
        cmd.push(b';');
        cmd.extend_from_slice(base64_encode(payload).as_bytes());
        store.kitty(&cmd)
    }

    fn reply(out: &Outcome) -> String {
        String::from_utf8_lossy(out.reply.as_deref().unwrap_or(b"")).into_owned()
    }

    fn zlib(data: &[u8]) -> Vec<u8> {
        use std::io::Write;
        let mut e = flate2::write::ZlibEncoder::new(Vec::new(), flate2::Compression::fast());
        e.write_all(data).unwrap();
        e.finish().unwrap()
    }

    fn png_bytes(width: u32, height: u32, rgba: &[u8]) -> Vec<u8> {
        let mut out = Vec::new();
        {
            let mut enc = png::Encoder::new(&mut out, width, height);
            enc.set_color(png::ColorType::Rgba);
            enc.set_depth(png::BitDepth::Eight);
            let mut writer = enc.write_header().unwrap();
            writer.write_image_data(rgba).unwrap();
        }
        out
    }

    #[test]
    fn a_mark_uri_survives_a_round_trip() {
        assert_eq!(parse_mark(&mark_uri(7, 3)), Some((7, 3)));
        assert_eq!(parse_mark("https://example.com/1/2"), None);
        assert_eq!(parse_mark("flintterm-image:7"), None);
    }

    #[test]
    fn rgb_and_rgba_are_both_understood() {
        let mut s = ImageStore::new();
        let out = kitty(&mut s, "a=T,f=24,s=2,v=1,i=1", &[1, 2, 3, 4, 5, 6]);
        assert_eq!(reply(&out), "\x1b_Gi=1;OK\x1b\\");
        assert!(out.draw.is_some());
        let img = s.image(1).unwrap();
        assert_eq!((img.width, img.height), (2, 1));
        assert_eq!(img.rgba, vec![1, 2, 3, 255, 4, 5, 6, 255]);

        let out = kitty(&mut s, "a=t,f=32,s=1,v=2,i=2", &[9, 9, 9, 128, 8, 8, 8, 0]);
        assert_eq!(reply(&out), "\x1b_Gi=2;OK\x1b\\");
        // a=t transmits without displaying.
        assert!(out.draw.is_none());
        assert_eq!(s.image(2).unwrap().rgba, vec![9, 9, 9, 128, 8, 8, 8, 0]);
    }

    #[test]
    fn png_is_decoded_to_rgba() {
        let mut s = ImageStore::new();
        let rgba: Vec<u8> = vec![255, 0, 0, 255, 0, 255, 0, 255, 0, 0, 255, 128, 1, 2, 3, 4];
        let out = kitty(&mut s, "a=T,f=100,i=5", &png_bytes(2, 2, &rgba));
        assert_eq!(reply(&out), "\x1b_Gi=5;OK\x1b\\");
        let img = s.image(5).unwrap();
        assert_eq!((img.width, img.height), (2, 2));
        assert_eq!(img.rgba, rgba);
    }

    #[test]
    fn a_zlib_payload_is_inflated() {
        let mut s = ImageStore::new();
        let raw: Vec<u8> = (0..48u8).collect();
        let mut cmd = b"a=t,f=32,s=4,v=3,o=z,i=9;".to_vec();
        cmd.extend_from_slice(base64_encode(&zlib(&raw)).as_bytes());
        let out = s.kitty(&cmd);
        assert_eq!(reply(&out), "\x1b_Gi=9;OK\x1b\\");
        assert_eq!(s.image(9).unwrap().rgba, raw);

        // Something that only claims to be zlib is an error, not a panic.
        let mut s = ImageStore::new();
        let out = kitty(&mut s, "a=t,f=32,s=1,v=1,o=z,i=9", b"not compressed");
        assert!(reply(&out).contains("EINVAL"));
        assert!(s.image(9).is_none());
    }

    #[test]
    fn a_transfer_may_arrive_in_chunks() {
        let mut s = ImageStore::new();
        let raw: Vec<u8> = (0..64u8).collect();
        let b64 = base64_encode(&raw);
        let (a, rest) = b64.split_at(8);
        let (b, c) = rest.split_at(8);

        let out = s.kitty(format!("a=T,f=32,s=4,v=4,i=3,m=1;{a}").as_bytes());
        assert!(out.reply.is_none() && out.draw.is_none());
        let out = s.kitty(format!("m=1;{b}").as_bytes());
        assert!(out.reply.is_none());
        let out = s.kitty(format!("m=0;{c}").as_bytes());
        assert_eq!(reply(&out), "\x1b_Gi=3;OK\x1b\\");
        assert!(out.draw.is_some());
        assert_eq!(s.image(3).unwrap().rgba, raw);
    }

    #[test]
    fn bad_keys_and_bad_values_are_refused() {
        let mut s = ImageStore::new();
        let out = kitty(&mut s, "a=T,f=32,s=1,v=1,i=1,Z=4", &[1, 2, 3, 4]);
        assert_eq!(reply(&out), "\x1b_Gi=1;EINVAL:unknown key\x1b\\");
        assert!(s.image(1).is_none());

        let out = kitty(&mut s, "a=T,f=32,s=x,v=1,i=1", &[1, 2, 3, 4]);
        assert_eq!(reply(&out), "\x1b_Gi=1;EINVAL:bad number\x1b\\");

        let out = kitty(&mut s, "a=T,f=7,s=1,v=1,i=1", &[1, 2, 3, 4]);
        assert_eq!(reply(&out), "\x1b_Gi=1;EINVAL:unsupported format\x1b\\");

        // Not base64 at all.
        let out = s.kitty(b"a=T,f=32,s=1,v=1,i=1;****");
        assert!(reply(&out).contains("EINVAL"));
    }

    #[test]
    fn only_direct_transmission_is_accepted() {
        let mut s = ImageStore::new();
        for medium in ["f", "t", "s"] {
            let out = kitty(&mut s, &format!("a=T,f=32,s=1,v=1,i=1,t={medium}"), b"/tmp/x");
            assert_eq!(
                reply(&out),
                "\x1b_Gi=1;EINVAL:only direct transmission is supported\x1b\\",
                "medium {medium} was not refused",
            );
        }
        assert!(s.is_empty());
    }

    #[test]
    fn the_query_action_answers_without_storing() {
        let mut s = ImageStore::new();
        let out = kitty(&mut s, "a=q,f=32,s=1,v=1,i=31", &[1, 2, 3, 4]);
        assert_eq!(reply(&out), "\x1b_Gi=31;OK\x1b\\");
        assert!(s.is_empty());

        let out = kitty(&mut s, "a=q,f=32,s=8,v=8,i=31", &[1, 2, 3, 4]);
        assert!(reply(&out).contains("EINVAL"));
    }

    #[test]
    fn quiet_decides_how_much_of_the_answer_is_heard() {
        let mut s = ImageStore::new();
        assert!(kitty(&mut s, "a=t,f=32,s=1,v=1,i=1,q=1", &[1, 2, 3, 4]).reply.is_none());
        assert!(kitty(&mut s, "a=t,f=9,s=1,v=1,i=1,q=1", &[1, 2, 3, 4]).reply.is_some());
        assert!(kitty(&mut s, "a=t,f=9,s=1,v=1,i=1,q=2", &[1, 2, 3, 4]).reply.is_none());
        // An unnamed image is never answered at all.
        assert!(kitty(&mut s, "a=t,f=32,s=1,v=1", &[1, 2, 3, 4]).reply.is_none());
    }

    #[test]
    fn a_placement_can_be_put_and_deleted() {
        let mut s = ImageStore::new();
        s.set_cell_size(10, 20);
        kitty(&mut s, "a=t,f=32,s=20,v=40,i=1", &vec![7u8; 20 * 40 * 4]);
        // 20 by 40 pixels in 10 by 20 cells is two columns by two rows.
        let draw = s.kitty(b"a=p,i=1,p=77").draw.unwrap();
        assert_eq!((draw.cols, draw.rows), (2, 2));
        assert_eq!(s.placements().len(), 1);

        // An id nobody transmitted.
        let out = s.kitty(b"a=p,i=42");
        assert_eq!(reply(&out), "\x1b_Gi=42;ENOENT:no such image\x1b\\");

        // Deleting the placement leaves the pixels behind.
        let out = s.kitty(b"a=d,d=i,i=1,p=77");
        s.apply_delete(out.delete.unwrap(), &[]);
        assert!(s.placements().is_empty());
        assert!(s.image(1).is_some());

        // The uppercase form frees them.
        s.kitty(b"a=p,i=1");
        let out = s.kitty(b"a=d,d=I,i=1");
        s.apply_delete(out.delete.unwrap(), &[]);
        assert!(s.image(1).is_none());
        assert!(s.is_empty());
    }

    #[test]
    fn delete_all_and_delete_by_z() {
        let mut s = ImageStore::new();
        kitty(&mut s, "a=t,f=32,s=1,v=1,i=1", &[1, 2, 3, 4]);
        s.kitty(b"a=p,i=1,z=5");
        s.kitty(b"a=p,i=1,z=-1");
        assert_eq!(s.placements().len(), 2);
        let out = s.kitty(b"a=d,d=z,z=5,i=1");
        s.apply_delete(out.delete.unwrap(), &[]);
        assert_eq!(s.placements().len(), 1);
        let out = s.kitty(b"a=d,i=1");
        s.apply_delete(out.delete.unwrap(), &[]);
        assert!(s.placements().is_empty());
        assert!(s.image(1).is_some());
    }

    #[test]
    fn an_image_larger_than_the_store_is_refused() {
        let mut s = ImageStore::new();
        // 25 megapixels is 100 MiB of RGBA, which no eviction can make room for.
        let out = kitty(&mut s, "a=T,f=32,s=5000,v=5000,i=1", &[0; 16]);
        assert_eq!(reply(&out), "\x1b_Gi=1;EINVAL:image is too large\x1b\\");
        assert!(s.is_empty());
    }

    #[test]
    fn the_cap_evicts_the_least_recently_used_image() {
        // Four 2048 by 2048 images are exactly the 64 MiB cap; the fifth has
        // to push the oldest out. The payload is a compressed run of zeroes so
        // the test moves kilobytes rather than megabytes through the parser.
        let mut s = ImageStore::new();
        let side = 2048usize;
        let payload = base64_encode(&zlib(&vec![0u8; side * side * 4]));
        for id in 1..=5u32 {
            let cmd = format!("a=t,f=32,s={side},v={side},o=z,i={id};{payload}");
            let out = s.kitty(cmd.as_bytes());
            assert_eq!(reply(&out), format!("\x1b_Gi={id};OK\x1b\\"));
            assert!(s.bytes() <= CAP, "store grew past the cap at image {id}");
        }
        assert_eq!(s.bytes(), CAP);
        assert!(s.image(1).is_none(), "the oldest image should have been evicted");
        for id in 2..=5 {
            assert!(s.image(id).is_some(), "image {id} should still be held");
        }
    }

    #[test]
    fn placements_resolve_to_where_their_marks_are() {
        let mut s = ImageStore::new();
        s.set_cell_size(10, 10);
        kitty(&mut s, "a=t,f=32,s=30,v=20,i=1", &vec![0u8; 30 * 20 * 4]);
        let key = s.kitty(b"a=p,i=1").draw.unwrap().key;

        // Both rows on screen, the image starting at column 4 of row 2.
        let marks = [Mark { key, index: 0, col: 4, row: 2 }, Mark { key, index: 1, col: 4, row: 3 }];
        let placed = s.resolve(&marks);
        assert_eq!(placed.len(), 1);
        assert_eq!((placed[0].col, placed[0].row), (4, 2));
        assert_eq!((placed[0].cols, placed[0].rows), (3, 2));

        // Only the second row is left on screen: the image starts above it.
        let placed = s.resolve(&[Mark { key, index: 1, col: 4, row: 0 }]);
        assert_eq!((placed[0].col, placed[0].row), (4, -1));

        // A mark whose placement is gone resolves to nothing.
        s.apply_delete(Delete { target: DeleteTarget::All, free_data: false }, &[]);
        assert!(s.resolve(&marks).is_empty());
    }

    // ---- sixel ------------------------------------------------------------
    //
    // `chafa` is not installed on this machine (`which chafa` finds nothing),
    // so these fixtures are written by hand and the expected pixels are worked
    // out from the protocol rather than captured from an encoder.

    fn pixel(p: &Pixels, x: u32, y: u32) -> [u8; 4] {
        let o = ((y * p.width + x) * 4) as usize;
        p.rgba[o..o + 4].try_into().unwrap()
    }

    const RED: [u8; 4] = [255, 0, 0, 255];
    const GREEN: [u8; 4] = [0, 255, 0, 255];
    const BLUE: [u8; 4] = [0, 0, 255, 255];
    const CLEAR: [u8; 4] = [0, 0, 0, 0];

    #[test]
    fn sixel_colors_repeats_and_line_breaks() {
        // `!3~` paints three columns with every one of the six bits set;
        // `$` goes back to the left margin, where `?A` leaves the second pixel
        // of the second column blue; `-` starts the next band six rows down.
        let data = b"#0;2;100;0;0!3~$#1;2;0;0;100?A-#2;2;0;100;0~";
        let img = decode_sixel(&[0, 1, 0], data, MAX_PIXELS).unwrap();
        assert_eq!((img.width, img.height), (3, 12));
        assert_eq!(pixel(&img, 0, 0), RED);
        assert_eq!(pixel(&img, 2, 5), RED);
        assert_eq!(pixel(&img, 1, 1), BLUE);
        assert_eq!(pixel(&img, 1, 0), RED);
        assert_eq!(pixel(&img, 0, 6), GREEN);
        assert_eq!(pixel(&img, 0, 11), GREEN);
        assert_eq!(pixel(&img, 1, 6), CLEAR);
    }

    #[test]
    fn sixel_raster_attributes_fix_the_canvas() {
        // Pan;Pad;Ph;Pv says the image is 4 by 8 even though the data only
        // paints one column of six.
        let img = decode_sixel(&[], b"\"1;1;4;8#0;2;0;0;100~", MAX_PIXELS).unwrap();
        assert_eq!((img.width, img.height), (4, 8));
        assert_eq!(pixel(&img, 0, 0), BLUE);
        assert_eq!(pixel(&img, 0, 5), BLUE);
        assert_eq!(pixel(&img, 0, 6), CLEAR);
        assert_eq!(pixel(&img, 3, 0), CLEAR);
    }

    #[test]
    fn sixel_starts_from_the_vt340_palette() {
        // No color is defined, so `#2` is the default red: 80 percent of full.
        let img = decode_sixel(&[], b"#2~", MAX_PIXELS).unwrap();
        assert_eq!(pixel(&img, 0, 0), [204, 33, 33, 255]);
    }

    #[test]
    fn sixel_understands_hls() {
        // Hue is measured from blue in this color space, so 0 degrees at half
        // lightness and full saturation is pure blue.
        let img = decode_sixel(&[], b"#3;1;0;50;100~", MAX_PIXELS).unwrap();
        assert_eq!(pixel(&img, 0, 0), BLUE);
    }

    #[test]
    fn sixel_ignores_the_line_breaks_encoders_add() {
        let plain = decode_sixel(&[], b"#1;2;100;0;0~~~", MAX_PIXELS).unwrap();
        let wrapped = decode_sixel(&[], b"#1;2;100;0;0~\n~\r\n~", MAX_PIXELS).unwrap();
        assert_eq!(plain.rgba, wrapped.rgba);
        assert_eq!(plain.width, 3);
    }

    #[test]
    fn an_empty_sixel_is_not_an_image() {
        assert!(decode_sixel(&[], b"", MAX_PIXELS).is_none());
        assert!(decode_sixel(&[], b"#1;2;100;0;0", MAX_PIXELS).is_none());
        // A repeat count that would fill a screen wall is not honored.
        assert!(decode_sixel(&[], b"#1;2;100;0;0!999999999~", 64).is_none());
    }

    #[test]
    fn a_sixel_image_is_placed_at_the_cursor() {
        let mut s = ImageStore::new();
        s.set_cell_size(4, 8);
        let out = s.sixel(&[0, 1, 0], b"#1;2;100;0;0!8~");
        let draw = out.draw.unwrap();
        // 8 by 6 pixels in 4 by 8 cells: two columns, one row.
        assert_eq!((draw.cols, draw.rows), (2, 1));
        assert!(draw.sixel && !draw.keep_cursor);
        assert!(out.changed);
        assert_eq!(s.placements().len(), 1);
    }
}
