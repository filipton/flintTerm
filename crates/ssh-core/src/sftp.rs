//! SFTP convenience layer.
//!
//! This sits on russh-sftp's raw session rather than its `SftpSession`. The
//! high-level `File` keeps a single read request in flight, so a download ran
//! at one packet per round trip — about 5 MB/s at 50 ms however fast the link
//! was. The raw session lets a download keep many reads outstanding, and
//! `SftpSession` does not hand its raw session out, so the few high-level calls
//! used here are done by hand instead.

use std::collections::VecDeque;
use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;

use russh_sftp::client::error::Error as SftpError;
use russh_sftp::client::rawsession::Limits;
use russh_sftp::client::RawSftpSession;
use russh_sftp::extensions;
use russh_sftp::protocol::{FileAttributes, FileType, OpenFlags, StatusCode};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::task::JoinHandle;
use tokio::time::Instant;

use crate::SshError;

/// Read requests kept outstanding during a download. Throughput is bounded by
/// the bytes in flight over the round trip; sixteen full-size reads ask for
/// more than the SSH channel window (2 MiB), so it is the window that limits a
/// download, not the request count.
const READ_AHEAD: usize = 16;

/// Write requests outstanding during an upload, as russh-sftp's `File` did.
const WRITE_BEHIND: usize = 8;

/// The largest read asked for. OpenSSH answers at most 255 KiB per read and
/// says so through the limits extension; a server that advertises more is still
/// held to this, so sixteen of them stay a few MiB of memory rather than
/// whatever the server offers.
const MAX_READ: u32 = 255 * 1024;

/// russh-sftp's packet ceiling, for a server that does not publish limits.
const MAX_PACKET: u32 = 256 * 1024;

/// Header bytes around a write's data: type, id, handle length, offset and
/// data length, plus the handle itself.
const WRITE_OVERHEAD: u32 = 1 + 4 + 4 + 8 + 4;

/// Progress is reported at most this often. Each report is a JNA upcall into
/// Kotlin, and one per packet costs more than the packet at LAN speeds.
const PROGRESS_EVERY: Duration = Duration::from_millis(100);

#[derive(Debug, Clone)]
pub struct Entry {
    pub name: String,
    pub path: String,
    pub is_dir: bool,
    pub is_symlink: bool,
    pub size: u64,
    pub mtime: Option<u32>,
    pub permissions: Option<u32>,
}

/// Progress callback: (bytes done, total bytes if known). Return false to cancel.
pub type Progress = Arc<dyn Fn(u64, Option<u64>) -> bool + Send + Sync>;

pub struct Sftp {
    raw: Arc<RawSftpSession>,
    limits: Limits,
    fsync: bool,
    cancelled: AtomicBool,
}

impl Sftp {
    /// Speak SFTP over `stream`: version exchange, then the server's limits if
    /// it publishes them, which cap every read and write that follows.
    pub(crate) async fn start<S>(stream: S) -> Result<Self, SshError>
    where
        S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
    {
        let mut raw = RawSftpSession::new(stream);
        raw.set_timeout(60);
        let version = raw.init().await?;
        let has = |name: &str| version.extensions.get(name).is_some_and(|v| v == "1");
        let mut limits = Limits::default();
        if has(extensions::LIMITS) {
            limits = Limits::from(raw.limits().await?);
            raw.set_limits(limits);
        }
        let fsync = has(extensions::FSYNC);
        Ok(Self { raw: Arc::new(raw), limits, fsync, cancelled: AtomicBool::new(false) })
    }

    pub fn cancel(&self) {
        self.cancelled.store(true, Ordering::Relaxed);
    }

    pub async fn home(&self) -> Result<String, SshError> {
        self.canonicalize(".").await
    }

    pub async fn canonicalize(&self, path: &str) -> Result<String, SshError> {
        let name = self.raw.realpath(path).await?;
        match name.files.into_iter().next() {
            Some(file) => Ok(file.filename),
            None => Err(SshError::Sftp("no file".into())),
        }
    }

    pub async fn list(&self, path: &str) -> Result<Vec<Entry>, SshError> {
        let handle = self.raw.opendir(path).await?.handle;
        let mut files = Vec::new();
        let read = loop {
            match self.raw.readdir(handle.as_str()).await {
                Ok(name) => files.extend(name.files),
                Err(SftpError::Status(s)) if s.status_code == StatusCode::Eof => break Ok(()),
                Err(e) => break Err(e),
            }
        };
        let closed = self.raw.close(handle).await;
        read?;
        closed?;
        let mut out = Vec::new();
        for f in files {
            let name = f.filename;
            if name == "." || name == ".." {
                continue;
            }
            let meta = f.attrs;
            let ft = meta.file_type();
            let mut is_dir = ft == FileType::Dir;
            let is_symlink = ft == FileType::Symlink;
            let full = join(path, &name);
            if is_symlink {
                // Resolve symlinks so directories behave like directories in the browser.
                if let Ok(target) = self.raw.stat(full.clone()).await {
                    is_dir = target.attrs.file_type() == FileType::Dir;
                }
            }
            out.push(Entry {
                name,
                path: full,
                is_dir,
                is_symlink,
                size: meta.size.unwrap_or(0),
                mtime: meta.mtime,
                permissions: meta.permissions,
            });
        }
        out.sort_by(|a, b| b.is_dir.cmp(&a.is_dir).then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase())));
        Ok(out)
    }

    pub async fn mkdir(&self, path: &str) -> Result<(), SshError> {
        self.raw.mkdir(path, FileAttributes::empty()).await?;
        Ok(())
    }

    pub async fn remove(&self, path: &str, is_dir: bool) -> Result<(), SshError> {
        if is_dir {
            // Recursive delete, depth first.
            let entries = self.list(path).await?;
            for e in entries {
                Box::pin(self.remove(&e.path, e.is_dir && !e.is_symlink)).await?;
            }
            self.raw.rmdir(path).await?;
        } else {
            self.raw.remove(path).await?;
        }
        Ok(())
    }

    pub async fn rename(&self, from: &str, to: &str) -> Result<(), SshError> {
        self.raw.rename(from, to).await?;
        Ok(())
    }

    pub async fn download(&self, remote: &str, local: &Path, progress: Progress) -> Result<u64, SshError> {
        let out = tokio::fs::File::create(local).await?;
        self.download_to(remote, out, progress).await
    }

    /// Stream a remote file into an already-open local file (e.g. a content:// fd).
    pub async fn download_to(&self, remote: &str, mut out: tokio::fs::File, progress: Progress) -> Result<u64, SshError> {
        self.cancelled.store(false, Ordering::Relaxed);
        let total = self.raw.stat(remote).await.ok().and_then(|a| a.attrs.size);
        let done = self.read_into(remote, &mut out, total, Some(&progress)).await?;
        out.flush().await?;
        progress(done, total);
        Ok(done)
    }

    pub async fn upload(&self, local: &Path, remote: &str, progress: Progress) -> Result<u64, SshError> {
        let input = tokio::fs::File::open(local).await?;
        self.upload_from(input, remote, progress).await
    }

    /// Stream an already-open local file to the remote path.
    pub async fn upload_from(&self, mut input: tokio::fs::File, remote: &str, progress: Progress) -> Result<u64, SshError> {
        self.cancelled.store(false, Ordering::Relaxed);
        let total = input.metadata().await.ok().map(|m| m.len()).filter(|l| *l > 0);
        let done = self.write_from(&mut input, remote, total, Some(&progress), true).await?;
        progress(done, total);
        Ok(done)
    }

    pub async fn stat(&self, path: &str) -> Result<Entry, SshError> {
        let meta = self.raw.stat(path).await?.attrs;
        let name = path.trim_end_matches('/').rsplit('/').next().unwrap_or(path).to_string();
        Ok(Entry {
            name,
            path: path.to_string(),
            is_dir: meta.file_type() == FileType::Dir,
            is_symlink: false,
            size: meta.size.unwrap_or(0),
            mtime: meta.mtime,
            permissions: meta.permissions,
        })
    }

    pub async fn read_to_string(&self, remote: &str, max_bytes: u64) -> Result<String, SshError> {
        let size = self.raw.stat(remote).await?.attrs.size;
        if size.unwrap_or(0) > max_bytes {
            return Err(SshError::Other("file too large".into()));
        }
        let mut data = Vec::new();
        self.read_into(remote, &mut data, size, None).await?;
        Ok(String::from_utf8_lossy(&data).into_owned())
    }

    /// Write a whole file, creating it if it is not there yet.
    pub async fn write_string(&self, remote: &str, contents: &str) -> Result<(), SshError> {
        self.write_from(&mut contents.as_bytes(), remote, None, None, false).await?;
        Ok(())
    }

    pub async fn close(&self) {
        let _ = self.raw.close_session();
    }

    /// Copy a remote file into `out`, returning the bytes written.
    async fn read_into<W>(&self, remote: &str, out: &mut W, total: Option<u64>, progress: Option<&Progress>) -> Result<u64, SshError>
    where
        W: AsyncWrite + Unpin,
    {
        let handle = self.raw.open(remote, OpenFlags::READ, FileAttributes::empty()).await?.handle;
        let result = self.pump_reads(&handle, out, total, progress).await;
        // Reads still outstanding were aborted on the way out, and the server
        // answers them before it gets to the close, so the handle does not leak.
        let _ = self.raw.close(handle).await;
        result
    }

    async fn pump_reads<W>(&self, handle: &str, out: &mut W, total: Option<u64>, progress: Option<&Progress>) -> Result<u64, SshError>
    where
        W: AsyncWrite + Unpin,
    {
        let mut len = self.limits.read_len.map_or(MAX_READ, |l| l.min(MAX_READ as u64) as u32).max(1);
        let mut inflight = Tasks::default();
        let mut next = 0u64;
        let mut done = 0u64;
        let mut ticker = Ticker::default();
        loop {
            // Past the size the stat reported there is only the read that finds
            // the end, so do not queue fifteen more behind it. If the file grew,
            // the queue drains and the next pass asks again.
            while inflight.len() < READ_AHEAD && (inflight.is_empty() || total.is_none_or(|t| next <= t)) {
                inflight.push(tokio::spawn(read_chunk(self.raw.clone(), handle.to_owned(), next, len)));
                next += len as u64;
            }
            let chunk = settle(inflight.pop().expect("queue was just filled")).await?;
            if self.cancelled.load(Ordering::Relaxed) {
                return Err(SshError::Other("cancelled".into()));
            }
            out.write_all(&chunk.data).await?;
            done += chunk.data.len() as u64;
            if (chunk.data.len() as u32) < chunk.asked {
                return Ok(done);
            }
            // A full chunk whose first answer came up short means the server
            // caps reads below what we ask; asking for its size from here on
            // saves a follow-up request per chunk.
            len = len.min(chunk.first);
            if let Some(p) = progress {
                if ticker.due() && !p(done, total) {
                    return Err(SshError::Other("cancelled".into()));
                }
            }
        }
    }

    /// Copy `input` into a new or truncated remote file, returning the bytes sent.
    async fn write_from<R>(&self, input: &mut R, remote: &str, total: Option<u64>, progress: Option<&Progress>, sync: bool) -> Result<u64, SshError>
    where
        R: AsyncRead + Unpin,
    {
        let flags = OpenFlags::CREATE | OpenFlags::TRUNCATE | OpenFlags::WRITE;
        let handle = self.raw.open(remote, flags, FileAttributes::empty()).await?.handle;
        let result = self.pump_writes(&handle, input, total, progress, sync).await;
        let closed = self.raw.close(handle).await;
        let done = result?;
        closed?;
        Ok(done)
    }

    async fn pump_writes<R>(&self, handle: &str, input: &mut R, total: Option<u64>, progress: Option<&Progress>, sync: bool) -> Result<u64, SshError>
    where
        R: AsyncRead + Unpin,
    {
        let len = self.limits.write_len.map_or_else(
            || MAX_PACKET.saturating_sub(WRITE_OVERHEAD + handle.len() as u32),
            |l| l.min(MAX_PACKET as u64) as u32,
        );
        let mut buf = vec![0u8; len.max(1) as usize];
        let mut pending = Tasks::default();
        let mut done = 0u64;
        let mut ticker = Ticker::default();
        loop {
            if self.cancelled.load(Ordering::Relaxed) {
                return Err(SshError::Other("cancelled".into()));
            }
            let n = input.read(&mut buf).await?;
            if n == 0 {
                break;
            }
            if pending.len() >= WRITE_BEHIND {
                settle(pending.pop().expect("queue is full")).await?;
            }
            let raw = self.raw.clone();
            let (handle, data) = (handle.to_owned(), buf[..n].to_vec());
            pending.push(tokio::spawn(async move { raw.write(handle, done, data).await.map(|_| ()) }));
            done += n as u64;
            if let Some(p) = progress {
                if ticker.due() && !p(done, total) {
                    return Err(SshError::Other("cancelled".into()));
                }
            }
        }
        while let Some(write) = pending.pop() {
            settle(write).await?;
        }
        if sync && self.fsync {
            self.raw.fsync(handle).await?;
        }
        Ok(done)
    }
}

/// One read's worth of a file, reassembled.
struct Chunk {
    data: Vec<u8>,
    asked: u32,
    /// The length of the server's first answer, which tells us its read cap.
    first: u32,
}

/// Read `len` bytes at `offset`, asking again for the rest when the server
/// answers with less. Shorter than `len` only at the end of the file.
async fn read_chunk(raw: Arc<RawSftpSession>, handle: String, offset: u64, len: u32) -> Result<Chunk, SftpError> {
    let mut data: Vec<u8> = Vec::new();
    let mut first = None;
    while (data.len() as u32) < len {
        let want = len - data.len() as u32;
        match raw.read(handle.as_str(), offset + data.len() as u64, want).await {
            // An empty answer is not allowed, but looping on one would never end.
            Ok(reply) if reply.data.is_empty() => break,
            Ok(mut reply) => {
                reply.data.truncate(want as usize);
                first.get_or_insert(reply.data.len() as u32);
                if data.is_empty() {
                    data = reply.data;
                } else {
                    data.extend_from_slice(&reply.data);
                }
            }
            Err(SftpError::Status(s)) if s.status_code == StatusCode::Eof => break,
            Err(e) => return Err(e),
        }
    }
    Ok(Chunk { data, asked: len, first: first.unwrap_or(len) })
}

/// Requests in flight, oldest first. Dropping the queue aborts them, so a
/// failed or cancelled transfer does not keep them running.
struct Tasks<T>(VecDeque<JoinHandle<Result<T, SftpError>>>);

impl<T> Default for Tasks<T> {
    fn default() -> Self {
        Self(VecDeque::new())
    }
}

impl<T> Tasks<T> {
    fn len(&self) -> usize {
        self.0.len()
    }

    fn is_empty(&self) -> bool {
        self.0.is_empty()
    }

    fn push(&mut self, task: JoinHandle<Result<T, SftpError>>) {
        self.0.push_back(task);
    }

    fn pop(&mut self) -> Option<JoinHandle<Result<T, SftpError>>> {
        self.0.pop_front()
    }
}

impl<T> Drop for Tasks<T> {
    fn drop(&mut self) {
        for task in &self.0 {
            task.abort();
        }
    }
}

async fn settle<T>(task: JoinHandle<Result<T, SftpError>>) -> Result<T, SshError> {
    Ok(task.await.map_err(|e| SshError::Other(e.to_string()))??)
}

/// Rate limit for progress reports. The first is due at once; the caller makes
/// a last one itself when the transfer ends.
#[derive(Default)]
struct Ticker {
    last: Option<Instant>,
}

impl Ticker {
    fn due(&mut self) -> bool {
        let now = Instant::now();
        if self.last.is_some_and(|l| now.duration_since(l) < PROGRESS_EVERY) {
            return false;
        }
        self.last = Some(now);
        true
    }
}

fn join(dir: &str, name: &str) -> String {
    if dir.ends_with('/') {
        format!("{dir}{name}")
    } else {
        format!("{dir}/{name}")
    }
}

#[cfg(test)]
mod tests {
    use std::collections::HashMap;
    use std::sync::Mutex;

    use russh_sftp::protocol::{Attrs, Data, ExtendedReply, File, Handle, Name, Packet, Status, Version};
    use tokio::io::DuplexStream;
    use tokio::sync::mpsc;

    use super::*;

    /// An in-memory SFTP server whose answers the tests can shape.
    #[derive(Default)]
    struct State {
        files: HashMap<String, Vec<u8>>,
        /// Answer reads with at most this many bytes, as a server with a small
        /// buffer does.
        read_cap: Option<usize>,
        /// Publish this read limit through `limits@openssh.com`.
        read_limit: Option<u64>,
        /// Fail every read at or past this offset.
        fail_from: Option<u64>,
        /// (offset, length) of every read asked for, in arrival order.
        reads: Vec<(u64, u32)>,
        open_handles: i64,
        listed: bool,
    }

    struct Server(Arc<Mutex<State>>);

    fn ok(id: u32) -> Status {
        Status { id, status_code: StatusCode::Ok, error_message: "Ok".into(), language_tag: "en-US".into() }
    }

    impl russh_sftp::server::Handler for Server {
        type Error = StatusCode;

        fn unimplemented(&self) -> StatusCode {
            StatusCode::OpUnsupported
        }

        async fn init(&mut self, _: u32, _: HashMap<String, String>) -> Result<Version, StatusCode> {
            let mut v = Version::new();
            if self.0.lock().unwrap().read_limit.is_some() {
                v.extensions.insert(extensions::LIMITS.into(), "1".into());
            }
            Ok(v)
        }

        async fn extended(&mut self, id: u32, request: String, _: Vec<u8>) -> Result<Packet, StatusCode> {
            let limit = self.0.lock().unwrap().read_limit;
            match (request.as_str(), limit) {
                (extensions::LIMITS, Some(read)) => {
                    let limits = extensions::LimitsExtension {
                        max_packet_len: 256 * 1024,
                        max_read_len: read,
                        max_write_len: 0,
                        max_open_handles: 0,
                    };
                    let data = russh_sftp::ser::to_bytes(&limits).unwrap().to_vec();
                    Ok(Packet::ExtendedReply(ExtendedReply { id, data }))
                }
                _ => Err(StatusCode::OpUnsupported),
            }
        }

        async fn open(&mut self, id: u32, filename: String, flags: OpenFlags, _: FileAttributes) -> Result<Handle, StatusCode> {
            let mut s = self.0.lock().unwrap();
            if flags.contains(OpenFlags::TRUNCATE) {
                s.files.insert(filename.clone(), Vec::new());
            } else if !s.files.contains_key(&filename) {
                return Err(StatusCode::NoSuchFile);
            }
            s.open_handles += 1;
            Ok(Handle { id, handle: filename })
        }

        async fn close(&mut self, id: u32, _: String) -> Result<Status, StatusCode> {
            self.0.lock().unwrap().open_handles -= 1;
            Ok(ok(id))
        }

        async fn read(&mut self, id: u32, handle: String, offset: u64, len: u32) -> Result<Data, StatusCode> {
            let mut s = self.0.lock().unwrap();
            s.reads.push((offset, len));
            if s.fail_from.is_some_and(|f| offset >= f) {
                return Err(StatusCode::Failure);
            }
            if s.read_limit.is_some_and(|l| len as u64 > l) {
                return Err(StatusCode::BadMessage);
            }
            let cap = s.read_cap.unwrap_or(usize::MAX);
            let file = &s.files[&handle];
            let start = offset as usize;
            if start >= file.len() {
                return Err(StatusCode::Eof);
            }
            let end = file.len().min(start + (len as usize).min(cap));
            Ok(Data { id, data: file[start..end].to_vec() })
        }

        async fn write(&mut self, id: u32, handle: String, offset: u64, data: Vec<u8>) -> Result<Status, StatusCode> {
            let mut s = self.0.lock().unwrap();
            let file = s.files.get_mut(&handle).ok_or(StatusCode::NoSuchFile)?;
            let end = offset as usize + data.len();
            if file.len() < end {
                file.resize(end, 0);
            }
            file[offset as usize..end].copy_from_slice(&data);
            Ok(ok(id))
        }

        async fn stat(&mut self, id: u32, path: String) -> Result<Attrs, StatusCode> {
            let s = self.0.lock().unwrap();
            let mut attrs = FileAttributes::empty();
            if path == "/home" {
                attrs.permissions = Some(0o040755);
            } else {
                attrs.size = Some(s.files.get(&path).ok_or(StatusCode::NoSuchFile)?.len() as u64);
                attrs.permissions = Some(0o100644);
            }
            Ok(Attrs { id, attrs })
        }

        async fn realpath(&mut self, id: u32, path: String) -> Result<Name, StatusCode> {
            let path = if path == "." { "/home".to_string() } else { path };
            Ok(Name { id, files: vec![File::dummy(path)] })
        }

        async fn opendir(&mut self, id: u32, path: String) -> Result<Handle, StatusCode> {
            let mut s = self.0.lock().unwrap();
            s.listed = false;
            s.open_handles += 1;
            Ok(Handle { id, handle: path })
        }

        async fn readdir(&mut self, id: u32, handle: String) -> Result<Name, StatusCode> {
            let mut s = self.0.lock().unwrap();
            if s.listed {
                return Err(StatusCode::Eof);
            }
            s.listed = true;
            let prefix = format!("{handle}/");
            let files = s
                .files
                .iter()
                .filter_map(|(path, data)| path.strip_prefix(&prefix).map(|name| (name, data.len())))
                .map(|(name, len)| {
                    let mut attrs = FileAttributes::empty();
                    attrs.size = Some(len as u64);
                    attrs.permissions = Some(0o100644);
                    File::new(name, attrs)
                })
                .chain([File::dummy(".")])
                .collect();
            Ok(Name { id, files })
        }

        async fn rename(&mut self, id: u32, from: String, to: String) -> Result<Status, StatusCode> {
            let mut s = self.0.lock().unwrap();
            let data = s.files.remove(&from).ok_or(StatusCode::NoSuchFile)?;
            s.files.insert(to, data);
            Ok(ok(id))
        }

        async fn remove(&mut self, id: u32, path: String) -> Result<Status, StatusCode> {
            self.0.lock().unwrap().files.remove(&path).ok_or(StatusCode::NoSuchFile)?;
            Ok(ok(id))
        }
    }

    /// Forward bytes from `from` to `to`, each arriving `by` after it was sent.
    async fn delay_line<R, W>(mut from: R, mut to: W, by: Duration)
    where
        R: AsyncRead + Unpin + Send + 'static,
        W: AsyncWrite + Unpin + Send + 'static,
    {
        let (tx, mut rx) = mpsc::unbounded_channel::<(Instant, Vec<u8>)>();
        tokio::spawn(async move {
            while let Some((at, bytes)) = rx.recv().await {
                tokio::time::sleep_until(at).await;
                if to.write_all(&bytes).await.is_err() {
                    break;
                }
            }
        });
        let mut buf = vec![0u8; 1 << 16];
        while let Ok(n @ 1..) = from.read(&mut buf).await {
            let _ = tx.send((Instant::now() + by, buf[..n].to_vec()));
        }
    }

    /// A link with `one_way` latency in each direction and no bandwidth limit,
    /// so what a transfer takes is round trips and nothing else.
    fn link(one_way: Duration) -> (DuplexStream, DuplexStream) {
        let (client, a) = tokio::io::duplex(1 << 22);
        let (b, server) = tokio::io::duplex(1 << 22);
        let (ar, aw) = tokio::io::split(a);
        let (br, bw) = tokio::io::split(b);
        tokio::spawn(delay_line(ar, bw, one_way));
        tokio::spawn(delay_line(br, aw, one_way));
        (client, server)
    }

    async fn connect(state: &Arc<Mutex<State>>, one_way: Duration) -> Sftp {
        let (client, server) = link(one_way);
        russh_sftp::server::run(server, Server(state.clone())).await;
        Sftp::start(client).await.unwrap()
    }

    fn state_with(path: &str, data: Vec<u8>) -> Arc<Mutex<State>> {
        let mut s = State::default();
        s.files.insert(path.into(), data);
        Arc::new(Mutex::new(s))
    }

    fn payload(len: usize) -> Vec<u8> {
        (0..len).map(|i| (i % 251) as u8).collect()
    }

    const RTT: Duration = Duration::from_millis(50);

    #[tokio::test(start_paused = true)]
    async fn a_download_keeps_reads_in_flight_instead_of_one_per_round_trip() {
        let data = payload(2 * 1024 * 1024);
        let state = state_with("/f", data.clone());
        state.lock().unwrap().read_limit = Some(32 * 1024);
        let sftp = connect(&state, RTT / 2).await;

        let reports = Arc::new(Mutex::new(Vec::new()));
        let r = reports.clone();
        let progress: Progress = Arc::new(move |done, _| {
            r.lock().unwrap().push(done);
            true
        });
        let started = Instant::now();
        let mut out = Vec::new();
        let n = sftp.read_into("/f", &mut out, Some(data.len() as u64), Some(&progress)).await.unwrap();
        let took = started.elapsed();

        assert_eq!(n, data.len() as u64);
        assert!(out == data);
        // 64 reads of 32 KiB: one at a time that is 64 round trips. Sixteen
        // deep it is four, plus the open and the close.
        assert!(took < RTT * 10, "took {took:?}");
        let reports = reports.lock().unwrap().len();
        assert!(reports as u128 <= took.as_millis() / 100 + 1, "{reports} reports in {took:?}");
        assert_eq!(state.lock().unwrap().open_handles, 0);
    }

    #[tokio::test(start_paused = true)]
    async fn reads_stay_within_the_published_limit() {
        let data = payload(100_000);
        let state = state_with("/f", data.clone());
        state.lock().unwrap().read_limit = Some(4096);
        let sftp = connect(&state, RTT / 2).await;
        let mut out = Vec::new();
        sftp.read_into("/f", &mut out, None, None).await.unwrap();
        assert!(out == data);
        assert!(state.lock().unwrap().reads.iter().all(|&(_, len)| len <= 4096));
    }

    #[tokio::test(start_paused = true)]
    async fn short_reads_are_asked_for_again_and_teach_the_read_size() {
        let data = payload(6 * 1024 * 1024 + 3);
        let state = state_with("/f", data.clone());
        state.lock().unwrap().read_cap = Some(50_000);
        let sftp = connect(&state, RTT / 2).await;
        let mut out = Vec::new();
        sftp.read_into("/f", &mut out, Some(data.len() as u64), None).await.unwrap();
        assert!(out == data);
        // Once a full chunk has come back in pieces, later chunks are asked for
        // at the size the server actually answers.
        let reads = state.lock().unwrap().reads.clone();
        let late: Vec<_> = reads.iter().filter(|&&(off, _)| off >= READ_AHEAD as u64 * MAX_READ as u64).collect();
        assert!(!late.is_empty());
        assert!(late.iter().all(|&&(_, len)| len <= 50_000), "{late:?}");
    }

    #[tokio::test(start_paused = true)]
    async fn a_stale_size_does_not_stop_the_download_short() {
        let data = payload(300_000);
        let state = state_with("/f", data.clone());
        let sftp = connect(&state, RTT / 2).await;
        let mut out = Vec::new();
        sftp.read_into("/f", &mut out, Some(1000), None).await.unwrap();
        assert!(out == data);
    }

    #[tokio::test(start_paused = true)]
    async fn empty_and_chunk_sized_files_end_cleanly() {
        for len in [0, MAX_READ as usize, 2 * MAX_READ as usize] {
            let data = payload(len);
            let state = state_with("/f", data.clone());
            let sftp = connect(&state, RTT / 2).await;
            let mut out = Vec::new();
            assert_eq!(sftp.read_into("/f", &mut out, Some(len as u64), None).await.unwrap(), len as u64);
            assert!(out == data);
        }
    }

    #[tokio::test(start_paused = true)]
    async fn a_failed_read_fails_the_download_and_closes_the_handle() {
        let state = state_with("/f", payload(2_000_000));
        state.lock().unwrap().fail_from = Some(3 * MAX_READ as u64);
        let sftp = connect(&state, RTT / 2).await;
        let mut out = Vec::new();
        assert!(sftp.read_into("/f", &mut out, None, None).await.is_err());
        // Everything before the failed read arrived, and nothing after it.
        assert_eq!(out.len(), 3 * MAX_READ as usize);
        assert_eq!(state.lock().unwrap().open_handles, 0);
    }

    #[tokio::test(start_paused = true)]
    async fn declining_progress_cancels_and_closes_the_handle() {
        let state = state_with("/f", payload(8_000_000));
        let sftp = connect(&state, RTT / 2).await;
        let progress: Progress = Arc::new(|_, _| false);
        let mut out = Vec::new();
        let err = sftp.read_into("/f", &mut out, None, Some(&progress)).await.unwrap_err();
        assert!(err.to_string().contains("cancelled"), "{err}");
        assert_eq!(out.len(), MAX_READ as usize);
        assert_eq!(state.lock().unwrap().open_handles, 0);
    }

    #[tokio::test]
    async fn files_round_trip_through_upload_download_and_the_rest() {
        let state = Arc::new(Mutex::new(State::default()));
        let sftp = connect(&state, Duration::ZERO).await;
        assert_eq!(sftp.home().await.unwrap(), "/home");

        sftp.write_string("/home/a.txt", "hello").await.unwrap();
        assert_eq!(sftp.read_to_string("/home/a.txt", 100).await.unwrap(), "hello");
        assert!(sftp.read_to_string("/home/a.txt", 4).await.is_err());

        let dir = std::env::temp_dir().join(format!("flint-sftp-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let data = payload(3 * 1024 * 1024 + 17);
        std::fs::write(dir.join("up.bin"), &data).unwrap();
        let n = sftp.upload(&dir.join("up.bin"), "/home/b.bin", Arc::new(|_, _| true)).await.unwrap();
        assert_eq!(n, data.len() as u64);
        assert!(state.lock().unwrap().files["/home/b.bin"] == data);

        let last = Arc::new(Mutex::new((0, None)));
        let l = last.clone();
        let progress: Progress = Arc::new(move |done, total| {
            *l.lock().unwrap() = (done, total);
            true
        });
        sftp.download("/home/b.bin", &dir.join("down.bin"), progress).await.unwrap();
        assert!(std::fs::read(dir.join("down.bin")).unwrap() == data);
        assert_eq!(*last.lock().unwrap(), (data.len() as u64, Some(data.len() as u64)));

        let names: Vec<_> = sftp.list("/home").await.unwrap().into_iter().map(|e| e.name).collect();
        assert_eq!(names, ["a.txt", "b.bin"]);
        assert!(sftp.stat("/home").await.unwrap().is_dir);
        sftp.rename("/home/a.txt", "/home/c.txt").await.unwrap();
        sftp.remove("/home/b.bin", false).await.unwrap();
        let names: Vec<_> = sftp.list("/home").await.unwrap().into_iter().map(|e| e.name).collect();
        assert_eq!(names, ["c.txt"]);
        assert_eq!(state.lock().unwrap().open_handles, 0);
        let _ = std::fs::remove_dir_all(dir);
    }
}
