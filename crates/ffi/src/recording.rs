//! Writes a session to disk as it happens: a text log to read, an asciinema
//! cast to replay, or both.
//!
//! This used to be done in the app, which meant every chunk of output crossed
//! the bridge as its own array while a recording ran — and recordings are
//! switched on per host, so for some people that was every session, all day.
//! Here the bytes are already in hand: the pump hands the recorder the same
//! reference-counted buffer the emulator reads, and a thread of the
//! recorder's own decodes, strips and writes it, so neither the disk nor the
//! formatting ever runs on the runtime's one worker.
//!
//! The files are the ones the app has always written, byte for byte — the
//! Recordings screen reads recordings made before this moved, and after.

use std::fs::{File, OpenOptions};
use std::io::{self, BufWriter, Write};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use bytes::Bytes;
use parking_lot::Mutex;
use tokio::sync::mpsc;
use tokio::sync::mpsc::error::TryRecvError;

use crate::CoreError;

/// Flushed at most this often, and within this long of the output stopping: a
/// flush per chunk was a pair of syscalls for every redraw, and a second is all
/// a killed app can lose.
const FLUSH: Duration = Duration::from_secs(1);

/// Bounded, and full means the pump waits rather than anything being dropped:
/// a gap in a log is a lie about the session, and a gap in a cast breaks the
/// replay from there on. Nothing but a stalled disk fills this many chunks, and
/// then holding the one session back is the lesser harm than memory growing
/// until the app is killed.
const QUEUE_CHUNKS: usize = 256;

/// When a chunk arrived, in wall-clock milliseconds, and the chunk itself.
type Chunk = (u64, Bytes);

/// A session's recorder: idle until [`Recorder::start`], and idle again after
/// [`Recorder::stop`].
#[derive(Default)]
pub struct Recorder {
    tap: Mutex<Option<Arc<Tap>>>,
}

/// The pump's end of a running recording.
struct Tap {
    /// Only ever `None` inside `drop`; see there.
    tx: Option<mpsc::Sender<Chunk>>,
    /// The writer, parked whenever it has nothing to do.
    thread: std::thread::Thread,
    /// What it writes to, for [`Recorder::stop`] to hand back.
    paths: Vec<String>,
}

impl Drop for Tap {
    fn drop(&mut self) {
        // The sender goes first, then the writer is woken to find the channel
        // closed. The other way round, it could wake, find the channel still
        // open and empty, and park with nobody left to wake it.
        self.tx = None;
        self.thread.unpark();
    }
}

impl Recorder {
    /// Start writing to `text` and `cast`, whichever are given; a recording
    /// already running is left alone.
    ///
    /// `started_at` is the wall-clock millisecond the caller named the files
    /// after, so the cast's timestamp and its file name agree.
    pub fn start(
        &self,
        text: Option<String>,
        cast: Option<String>,
        cols: u16,
        rows: u16,
        started_at: u64,
    ) -> Result<(), CoreError> {
        let mut slot = self.tap.lock();
        if slot.is_some() {
            return Ok(());
        }
        let paths: Vec<String> = text.iter().chain(cast.iter()).cloned().collect();
        // Opened here, on the caller, so a file that cannot be written is an
        // error the app can show, rather than "Recording to …" over nothing.
        let header = cast.is_some().then(|| cast_header(cols, rows, started_at, ""));
        let text = text.map(|p| Sink::open(p.into())).transpose()?;
        let cast = cast.map(|p| Sink::open(p.into())).transpose()?;
        let (tx, rx) = mpsc::channel(QUEUE_CHUNKS);
        // A thread rather than a task: writing files blocks, and the runtime
        // has one worker every session shares.
        let thread = std::thread::Builder::new()
            .name("flintterm-rec".into())
            .spawn(move || {
                let mut writer = Writer::new(text, cast, started_at);
                if let Some(h) = header {
                    writer.header(&h);
                }
                run(rx, writer);
            })
            .map_err(|e| CoreError::Other(format!("cannot start recording: {e}")))?;
        *slot = Some(Arc::new(Tap { tx: Some(tx), thread: thread.thread().clone(), paths }));
        Ok(())
    }

    /// Stop recording, and return the files it was writing; empty when none was
    /// running. What is still queued is written and the files closed on the
    /// recorder's thread, after this has returned.
    pub fn stop(&self) -> Vec<String> {
        self.tap.lock().take().map(|t| t.paths.clone()).unwrap_or_default()
    }

    /// Hand `data` to the recording, if one is running.
    ///
    /// Awaited by the pump: when the queue is full this session's output waits
    /// for the disk, and nothing else on the runtime does.
    pub async fn tap(&self, data: &Bytes) {
        let Some(tap) = self.tap.lock().clone() else { return };
        let Some(tx) = tap.tx.as_ref() else { return };
        if tx.send((now_millis(), data.clone())).await.is_ok() {
            tap.thread.unpark();
        }
    }
}

fn now_millis() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

/// The recorder's thread: write what arrives, flush a second after the first
/// write since the last flush, and park in between.
fn run<W: Write>(mut rx: mpsc::Receiver<Chunk>, mut writer: Writer<W>) {
    let mut dirty: Option<Instant> = None;
    loop {
        match rx.try_recv() {
            Ok((at, bytes)) => {
                writer.chunk(at, &bytes);
                let since = *dirty.get_or_insert_with(Instant::now);
                if since.elapsed() >= FLUSH {
                    writer.flush();
                    dirty = None;
                }
            }
            Err(TryRecvError::Empty) => match dirty {
                // Nothing unwritten: sleep until a chunk or the stop arrives,
                // with no timer waking the phone in the meantime.
                None => std::thread::park(),
                Some(since) => {
                    let left = FLUSH.saturating_sub(since.elapsed());
                    if left.is_zero() {
                        writer.flush();
                        dirty = None;
                    } else {
                        std::thread::park_timeout(left);
                    }
                }
            },
            Err(TryRecvError::Disconnected) => break,
        }
    }
    writer.flush();
}

/// The formatting half of a recording, over whatever it writes to.
struct Writer<W: Write> {
    text: Option<Sink<W>>,
    cast: Option<Sink<W>>,
    started_at: u64,
    utf8: Utf8Stream,
    strip: Stripper,
    /// Reused for every chunk, so a recording allocates only while its chunks
    /// are still growing.
    decoded: String,
    stripped: String,
}

impl<W: Write> Writer<W> {
    fn new(text: Option<Sink<W>>, cast: Option<Sink<W>>, started_at: u64) -> Self {
        Self {
            text,
            cast,
            started_at,
            utf8: Utf8Stream::default(),
            strip: Stripper::default(),
            decoded: String::new(),
            stripped: String::new(),
        }
    }

    fn header(&mut self, header: &str) {
        if let Some(cast) = &mut self.cast {
            cast.write(|w| {
                w.write_all(header.as_bytes())?;
                w.write_all(b"\n")
            });
        }
    }

    fn chunk(&mut self, at: u64, bytes: &[u8]) {
        self.decoded.clear();
        self.utf8.decode(bytes, &mut self.decoded);
        if self.decoded.is_empty() {
            return;
        }
        if let Some(text) = &mut self.text {
            self.stripped.clear();
            self.strip.push(&self.decoded, &mut self.stripped);
            if !self.stripped.is_empty() {
                text.write(|w| w.write_all(self.stripped.as_bytes()));
            }
        }
        // The cast keeps the stream as it came, escape sequences included:
        // that is what makes it replay as the session looked rather than as a
        // transcript of it.
        if let Some(cast) = &mut self.cast {
            let elapsed = at.saturating_sub(self.started_at);
            let s = &self.decoded;
            cast.write(|w| {
                write_event(w, elapsed, s)?;
                w.write_all(b"\n")
            });
        }
    }

    fn flush(&mut self) {
        for sink in [&mut self.text, &mut self.cast].into_iter().flatten() {
            sink.write(|w| w.flush());
        }
    }
}

/// An append-only output that stops trying once it has failed: the terminal
/// must not notice that anyone is recording, least of all a full disk.
struct Sink<W: Write> {
    out: Option<W>,
    name: String,
}

impl Sink<BufWriter<File>> {
    fn open(path: PathBuf) -> Result<Self, CoreError> {
        let name = path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
        let opened = path
            .parent()
            .map_or(Ok(()), std::fs::create_dir_all)
            .and_then(|_| OpenOptions::new().create(true).append(true).open(&path));
        match opened {
            Ok(f) => Ok(Sink { out: Some(BufWriter::with_capacity(64 * 1024, f)), name }),
            Err(e) => Err(CoreError::Other(format!("cannot record to {name}: {e}"))),
        }
    }
}

impl<W: Write> Sink<W> {
    fn write(&mut self, f: impl FnOnce(&mut W) -> io::Result<()>) {
        let Some(out) = &mut self.out else { return };
        if let Err(e) = f(out) {
            log::warn!("cannot record to {}: {e}", self.name);
            self.out = None;
        }
    }
}

/// The first line of an asciinema v2 cast: the size the recording was made
/// at, when it started, and the terminal it claims to be.
fn cast_header(cols: u16, rows: u16, started_at: u64, shell: &str) -> String {
    let mut out = Vec::with_capacity(128);
    // Writing into a Vec cannot fail.
    let _ = write!(out, "{{\"version\":2,\"width\":{cols},\"height\":{rows},\"timestamp\":{},\"env\":{{\"TERM\":", started_at / 1000);
    let _ = write_json_str(&mut out, crate::session::DEFAULT_TERM);
    if !shell.trim().is_empty() {
        out.extend_from_slice(b",\"SHELL\":");
        let _ = write_json_str(&mut out, shell);
    }
    out.extend_from_slice(b"}}");
    String::from_utf8(out).unwrap_or_default()
}

/// One cast event: `[elapsed,"o","…"]`, the seconds counted from the start.
///
/// Six decimals, as the app's `%.6f` wrote them: from whole milliseconds that
/// is always the three digits and three zeros, with no float involved.
fn write_event(out: &mut impl Write, elapsed_millis: u64, s: &str) -> io::Result<()> {
    write!(out, "[{}.{:03}000,\"o\",", elapsed_millis / 1000, elapsed_millis % 1000)?;
    write_json_str(out, s)?;
    out.write_all(b"]")
}

/// `s` as a JSON string, quotes included.
///
/// One unescaped control character makes a whole cast unplayable. Everything
/// escaped is ASCII, so the bytes in between are copied as runs, and a
/// multi-byte character is never looked inside.
fn write_json_str(out: &mut impl Write, s: &str) -> io::Result<()> {
    out.write_all(b"\"")?;
    let bytes = s.as_bytes();
    let mut run = 0;
    for (i, &b) in bytes.iter().enumerate() {
        let esc: &[u8] = match b {
            b'"' => b"\\\"",
            b'\\' => b"\\\\",
            b'\n' => b"\\n",
            b'\r' => b"\\r",
            b'\t' => b"\\t",
            0x08 => b"\\b",
            0x0c => b"\\f",
            b if b < 0x20 || b == 0x7f => b"",
            _ => continue,
        };
        out.write_all(&bytes[run..i])?;
        if esc.is_empty() {
            write!(out, "\\u{:04x}", b)?;
        } else {
            out.write_all(esc)?;
        }
        run = i + 1;
    }
    out.write_all(&bytes[run..])?;
    out.write_all(b"\"")
}

/// UTF-8 decoding of a stream that arrives in pieces.
///
/// Output comes in whatever chunks the socket produced, and those cut through
/// the middle of a character as happily as anywhere else. Decoding each chunk
/// on its own turns both halves of an é or a box-drawing line into
/// replacement characters; this holds the unfinished tail back for the next.
#[derive(Default)]
struct Utf8Stream {
    /// The start of a character the last chunk ended in the middle of.
    carry: [u8; 4],
    len: usize,
}

impl Utf8Stream {
    fn decode(&mut self, mut bytes: &[u8], out: &mut String) {
        if self.len > 0 {
            // Finish the broken character on its own, in a few bytes on the
            // stack, so the rest of the chunk decodes where it lies.
            let take = (4 - self.len).min(bytes.len());
            let mut buf = self.carry;
            buf[self.len..self.len + take].copy_from_slice(&bytes[..take]);
            let n = self.len + take;
            let carried = self.len;
            self.len = 0;
            match std::str::from_utf8(&buf[..n]) {
                Ok(s) => {
                    out.push_str(s);
                    bytes = &bytes[take..];
                }
                Err(e) if e.valid_up_to() > 0 => {
                    // The character is whole; whatever came after it is read
                    // again from the chunk itself.
                    let valid = e.valid_up_to();
                    out.push_str(std::str::from_utf8(&buf[..valid]).unwrap_or_default());
                    bytes = &bytes[valid - carried..];
                }
                Err(e) => match e.error_len() {
                    // Still short: the chunk was smaller than the rest of it.
                    None => {
                        self.carry = buf;
                        self.len = n;
                        return;
                    }
                    Some(bad) => {
                        out.push(char::REPLACEMENT_CHARACTER);
                        bytes = &bytes[bad.saturating_sub(carried).min(bytes.len())..];
                    }
                },
            }
        }
        loop {
            match std::str::from_utf8(bytes) {
                Ok(s) => {
                    out.push_str(s);
                    return;
                }
                Err(e) => {
                    let valid = e.valid_up_to();
                    out.push_str(std::str::from_utf8(&bytes[..valid]).unwrap_or_default());
                    match e.error_len() {
                        // A character the chunk ends inside: at most three
                        // bytes, kept for the next one.
                        None => {
                            let rest = &bytes[valid..];
                            self.carry[..rest.len()].copy_from_slice(rest);
                            self.len = rest.len();
                            return;
                        }
                        // A byte that can never start a character is replaced,
                        // not held.
                        Some(bad) => {
                            out.push(char::REPLACEMENT_CHARACTER);
                            bytes = &bytes[valid + bad..];
                        }
                    }
                }
            }
        }
    }
}

/// Where [`Stripper`] is inside an escape sequence.
#[derive(Default, Clone, Copy, PartialEq, Eq, Debug)]
enum Seq {
    #[default]
    Ground,
    /// Just after ESC.
    Esc,
    /// After ESC and an intermediate: one more character belongs to it ("ESC ( B").
    Intermediate,
    /// Inside CSI, until its final byte.
    Csi,
    /// Inside OSC, DCS, SOS, PM or APC, until BEL or ST (ESC \).
    Str,
    /// An ESC inside one of those, which may be the start of ST.
    StrEsc,
}

/// The stream as a person reads it: escape sequences and the control
/// characters a terminal consumes are dropped, newlines and tabs survive.
///
/// The same shape as the output watcher in the session: CSI runs to its final
/// byte, OSC and the other string sequences run to BEL or ST, and a
/// two-character sequence takes its second character with it. The state lives
/// across chunks, so a sequence the socket cut in two is still dropped whole.
#[derive(Default)]
struct Stripper {
    state: Seq,
}

impl Stripper {
    fn push(&mut self, s: &str, out: &mut String) {
        for c in s.chars() {
            self.state = match self.state {
                Seq::Ground => match c {
                    '\x1b' => Seq::Esc,
                    '\n' | '\t' => {
                        out.push(c);
                        Seq::Ground
                    }
                    // Carriage returns, bells and backspaces are how a
                    // terminal draws, not what it printed; a file keeps only
                    // the text.
                    c if c < ' ' || c == '\x7f' => Seq::Ground,
                    c => {
                        out.push(c);
                        Seq::Ground
                    }
                },
                Seq::Esc => match c {
                    '[' => Seq::Csi,
                    ']' | 'P' | 'X' | '^' | '_' => Seq::Str,
                    ' '..='/' => Seq::Intermediate,
                    _ => Seq::Ground,
                },
                Seq::Intermediate => Seq::Ground,
                Seq::Csi => {
                    if ('\x40'..='\x7e').contains(&c) {
                        Seq::Ground
                    } else {
                        Seq::Csi
                    }
                }
                Seq::StrEsc if c == '\\' => Seq::Ground,
                Seq::Str | Seq::StrEsc => match c {
                    '\x07' => Seq::Ground,
                    '\x1b' => Seq::StrEsc,
                    _ => Seq::Str,
                },
            };
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const ESC: &str = "\x1b";
    const BEL: &str = "\x07";

    fn strip(s: &str) -> String {
        let mut out = String::new();
        Stripper::default().push(s, &mut out);
        out
    }

    fn event(ms: u64, s: &str) -> String {
        let mut out = Vec::new();
        write_event(&mut out, ms, s).unwrap();
        String::from_utf8(out).unwrap()
    }

    fn decode_all(parts: &[&[u8]]) -> String {
        let mut utf8 = Utf8Stream::default();
        let mut out = String::new();
        for p in parts {
            utf8.decode(p, &mut out);
        }
        out
    }

    /// What a recording of `chunks`, each arriving `ms` after the start,
    /// writes to its text log and to its cast.
    fn record(chunks: &[(u64, &[u8])]) -> (String, String) {
        let mut w = Writer::new(
            Some(Sink { out: Some(Vec::new()), name: "log".into() }),
            Some(Sink { out: Some(Vec::new()), name: "cast".into() }),
            1_000_000,
        );
        for (ms, c) in chunks {
            w.chunk(1_000_000 + ms, c);
        }
        let text = String::from_utf8(w.text.unwrap().out.unwrap()).unwrap();
        let cast = String::from_utf8(w.cast.unwrap().out.unwrap()).unwrap();
        (text, cast)
    }

    #[test]
    fn a_recording_that_cannot_open_its_file_fails_to_start() {
        let rec = Recorder::default();
        let nowhere = "/proc/flintterm-no-such-dir/x.log".to_string();
        assert!(rec.start(Some(nowhere), None, 80, 24, 0).is_err());
        assert!(rec.stop().is_empty());
    }

    #[test]
    fn plain_text_comes_out_as_it_went_in() {
        let text = "total 12\ndrwxr-xr-x  3 pi pi 4096 Jan  1 10:00 .\n\tindented";
        assert_eq!(strip(text), text);
    }

    #[test]
    fn color_and_cursor_sequences_are_dropped() {
        let colored = format!("{ESC}[1;32mpi@raspberrypi{ESC}[0m:{ESC}[01;34m~{ESC}[00m$ ");
        assert_eq!(strip(&colored), "pi@raspberrypi:~$ ");
        assert_eq!(strip(&format!("{ESC}[2J{ESC}[Hdone")), "done");
    }

    #[test]
    fn an_osc_title_goes_with_either_of_its_terminators() {
        assert_eq!(strip(&format!("{ESC}]0;pi@raspberrypi: ~{BEL}ls")), "ls");
        assert_eq!(strip(&format!("{ESC}]0;pi@raspberrypi: ~{ESC}\\ls")), "ls");
        // An ESC that is not the start of ST leaves the string running.
        assert_eq!(strip(&format!("{ESC}]0;a{ESC}b{BEL}ls")), "ls");
    }

    #[test]
    fn a_charset_selection_takes_its_final_character_with_it() {
        assert_eq!(strip(&format!("{ESC}(Bplain")), "plain");
        assert_eq!(strip(&format!("{ESC}=keypad")), "keypad");
    }

    #[test]
    fn carriage_returns_and_bells_leave_the_text_alone() {
        assert_eq!(strip(&format!("50%\r75%{BEL}\r\n")), "50%75%\n");
    }

    #[test]
    fn an_escape_sequence_cut_between_chunks_is_still_dropped_whole() {
        let cases: &[&[&str]] = &[
            &["\x1b", "[31mred"],
            &["\x1b[3", "1mred"],
            &["\x1b]0;ti", "tle\x07red"],
            &["\x1b]0;title\x1b", "\\red"],
            &["\x1b(", "Bred"],
            &["\x1b", "(", "B", "red"],
        ];
        for parts in cases {
            let mut s = Stripper::default();
            let mut out = String::new();
            for p in *parts {
                s.push(p, &mut out);
            }
            assert_eq!(out, "red", "{parts:?}");
        }
    }

    #[test]
    fn a_cast_line_quotes_what_would_otherwise_break_the_json() {
        assert_eq!(event(1_500, "say \"hi\"\\\r\n\u{1}"), "[1.500000,\"o\",\"say \\\"hi\\\"\\\\\\r\\n\\u0001\"]");
        assert_eq!(event(0, "\x08\x0c\x7f\x1b[0m"), "[0.000000,\"o\",\"\\b\\f\\u007f\\u001b[0m\"]");
        // Nothing outside ASCII is escaped, and nothing inside a character is looked at.
        assert_eq!(event(0, "é─😀"), "[0.000000,\"o\",\"é─😀\"]");
    }

    #[test]
    fn the_elapsed_time_is_the_seconds_since_the_recording_started() {
        assert!(event(0, "x").starts_with("[0.000000,"));
        assert!(event(90_061, "x").starts_with("[90.061000,"));
        assert!(event(7, "x").starts_with("[0.007000,"));
    }

    #[test]
    fn the_header_carries_the_size_the_recording_was_made_at() {
        assert_eq!(
            cast_header(132, 43, 1_700_000_123_456, "/bin/zsh"),
            "{\"version\":2,\"width\":132,\"height\":43,\"timestamp\":1700000123,\
             \"env\":{\"TERM\":\"xterm-256color\",\"SHELL\":\"/bin/zsh\"}}",
        );
    }

    #[test]
    fn a_header_without_a_known_shell_claims_none() {
        assert_eq!(
            cast_header(80, 24, 0, ""),
            "{\"version\":2,\"width\":80,\"height\":24,\"timestamp\":0,\"env\":{\"TERM\":\"xterm-256color\"}}",
        );
    }

    #[test]
    fn a_character_split_between_chunks_comes_out_whole() {
        let bytes = "é─ok😀".as_bytes();
        // Every cut, including through the four bytes of the emoji.
        for cut in 1..bytes.len() {
            assert_eq!(decode_all(&[&bytes[..cut], &bytes[cut..]]), "é─ok😀", "cut at {cut}");
        }
        // And every byte on its own.
        let singles: Vec<&[u8]> = bytes.chunks(1).collect();
        assert_eq!(decode_all(&singles), "é─ok😀");

        let mut utf8 = Utf8Stream::default();
        let mut out = String::new();
        utf8.decode(&[0xe2], &mut out);
        assert_eq!(out, "");
        utf8.decode(&[0x94, 0x80], &mut out);
        assert_eq!(out, "─");
    }

    #[test]
    fn a_byte_that_can_never_start_a_character_is_replaced_not_held() {
        assert_eq!(decode_all(&[&[0x61, 0xff, 0x62]]), "a\u{fffd}b");
        // A character broken off by something that cannot continue it.
        assert_eq!(decode_all(&[&[0xe2], b"ab"]), "\u{fffd}ab");
        assert_eq!(decode_all(&[&[0xf0, 0x9f], &[0x98], b"x"]), "\u{fffd}x");
    }

    #[test]
    fn a_recording_fed_bytes_writes_them_whole() {
        let stream = format!("{ESC}[1mé{ESC}[0m\n");
        let bytes = stream.as_bytes();
        // Cut through the middle of the é.
        let (text, cast) = record(&[(0, &bytes[..5]), (1_500, &bytes[5..])]);
        assert_eq!(text, "é\n");
        assert_eq!(cast, "[0.000000,\"o\",\"\\u001b[1m\"]\n[1.500000,\"o\",\"é\\u001b[0m\\n\"]\n");
    }

    #[test]
    fn a_chunk_that_decodes_to_nothing_writes_no_frame() {
        let (text, cast) = record(&[(0, &[0xc3]), (10, &[0xa9])]);
        assert_eq!(text, "é");
        assert_eq!(cast, "[0.010000,\"o\",\"é\"]\n");
    }

    #[test]
    fn a_recording_writes_its_files_and_closes_them_on_stop() {
        let dir = std::env::temp_dir().join(format!("flintterm-rec-{}-{}", std::process::id(), now_millis()));
        let log = dir.join("host.log");
        let cast = dir.join("host.cast");
        let rec = Recorder::default();
        let started = now_millis();
        rec.start(Some(log.to_string_lossy().into()), Some(cast.to_string_lossy().into()), 80, 24, started).unwrap();
        let stream = format!("{ESC}[1mé{ESC}[0m\n");
        let bytes = Bytes::from(stream.clone().into_bytes());
        crate::runtime::block_on(async {
            rec.tap(&bytes.slice(..5)).await;
            rec.tap(&bytes.slice(5..)).await;
        });
        let paths = rec.stop();
        assert_eq!(paths, vec![log.to_string_lossy().to_string(), cast.to_string_lossy().to_string()]);
        assert!(rec.stop().is_empty());

        // Closing happens on the recorder's thread once it has the last chunk.
        let deadline = Instant::now() + Duration::from_secs(5);
        let done = || std::fs::read_to_string(&cast).is_ok_and(|c| c.lines().count() == 3);
        while !done() && Instant::now() < deadline {
            std::thread::sleep(Duration::from_millis(20));
        }
        assert_eq!(std::fs::read_to_string(&log).unwrap(), "é\n");
        let cast = std::fs::read_to_string(&cast).unwrap();
        let mut lines = cast.lines();
        assert_eq!(lines.next().unwrap(), cast_header(80, 24, started, ""));
        assert!(lines.next().unwrap().ends_with(",\"o\",\"\\u001b[1m\"]"));
        assert!(lines.next().unwrap().ends_with(",\"o\",\"é\\u001b[0m\\n\"]"));
        let _ = std::fs::remove_dir_all(&dir);
    }
}
