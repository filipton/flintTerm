//! The byte pipe behind a session: an SSH shell channel, a local pty, a telnet
//! socket, or an "external" pipe driven from the Kotlin side (USB serial).

use std::os::fd::OwnedFd;
use std::sync::Arc;

use bytes::Bytes;
use tokio::io::unix::AsyncFd;
use tokio::io::Interest;

use ssh_core::{Shell, ShellEvent, ShellWriter};

use crate::external::{ExternalReader, ExternalWriter};
use crate::mosh::{MoshReader, MoshWriter};
use crate::telnet::{TelnetReader, TelnetWriter};

pub enum Event {
    Data(Bytes),
    Exit(Option<i32>),
    Closed,
}

/// Reader side; consumed by the session's pump task.
pub enum Reader {
    Ssh(Shell),
    Pty(PtyIo),
    Telnet(TelnetReader),
    External(ExternalReader),
    Mosh(MoshReader),
}

impl Reader {
    pub async fn next(&mut self) -> Event {
        match self {
            Reader::Ssh(shell) => match shell.next().await {
                ShellEvent::Data(d) => Event::Data(d),
                ShellEvent::Exit(code) => Event::Exit(Some(code as i32)),
                ShellEvent::Closed => Event::Closed,
            },
            Reader::Pty(pty) => pty.read().await,
            Reader::Telnet(t) => t.next().await,
            Reader::External(e) => e.next().await,
            Reader::Mosh(m) => m.next().await,
        }
    }
}

/// Writer side; cheap to clone, shared with the input queue and resize.
#[derive(Clone)]
pub enum Writer {
    Ssh(ShellWriter),
    Pty(PtyIo),
    Telnet(TelnetWriter),
    External(ExternalWriter),
    Mosh(MoshWriter),
}

impl Writer {
    pub async fn write(&self, bytes: Vec<u8>) -> bool {
        match self {
            Writer::Ssh(w) => w.write(bytes).await.is_ok(),
            Writer::Pty(p) => p.write(&bytes).await,
            Writer::Telnet(t) => t.write(bytes).await,
            Writer::External(e) => e.write(bytes),
            Writer::Mosh(m) => m.write(bytes),
        }
    }

    pub async fn resize(&self, cols: u16, rows: u16) {
        match self {
            Writer::Ssh(w) => {
                let _ = w.resize(cols, rows).await;
            }
            Writer::Pty(p) => {
                let _ = p.pty.resize(cols, rows);
            }
            Writer::Telnet(t) => t.resize(cols, rows).await,
            Writer::External(e) => e.resize(cols, rows),
            Writer::Mosh(m) => m.resize(cols, rows),
        }
    }

    pub async fn close(&self) {
        match self {
            Writer::Ssh(w) => w.close().await,
            Writer::Pty(p) => p.pty.kill(),
            Writer::Telnet(t) => t.close().await,
            Writer::External(e) => e.close(),
            Writer::Mosh(m) => m.close(),
        }
    }
}

#[derive(Clone)]
pub struct PtyIo {
    pty: Arc<pty::Pty>,
    fd: Arc<AsyncFd<OwnedFd>>,
    /// Where reads land before each chunk is copied out at its own size.
    ///
    /// Only the reader's copy ever grows it; the writer's clone keeps an empty
    /// one. It used to be a fresh 64 KiB, zeroed, per read, and the whole of it
    /// was handed on as the chunk however few bytes the read returned.
    scratch: Vec<u8>,
}

impl PtyIo {
    pub fn new(pty: pty::Pty) -> Result<Self, pty::PtyError> {
        pty.set_nonblocking()?;
        let dup = pty.dup_master()?;
        let fd = AsyncFd::with_interest(dup, Interest::READABLE | Interest::WRITABLE)
            .map_err(|e| pty::PtyError::Invalid(e.to_string()))?;
        Ok(Self { pty: Arc::new(pty), fd: Arc::new(fd), scratch: Vec::new() })
    }

    async fn read(&mut self) -> Event {
        if self.scratch.is_empty() {
            self.scratch = vec![0u8; 64 * 1024];
        }
        loop {
            let buf = &mut self.scratch;
            let mut guard = match self.fd.readable().await {
                Ok(g) => g,
                Err(_) => return Event::Closed,
            };
            match guard.try_io(|inner| {
                use std::os::fd::AsRawFd;
                // SAFETY: valid fd, valid buffer.
                let n = unsafe { libc::read(inner.as_raw_fd(), buf.as_mut_ptr() as *mut _, buf.len()) };
                if n < 0 {
                    Err(std::io::Error::last_os_error())
                } else {
                    Ok(n as usize)
                }
            }) {
                Ok(Ok(0)) => return Event::Exit(self.exit_code().await),
                Ok(Ok(n)) => return Event::Data(Bytes::copy_from_slice(&buf[..n])),
                // EIO on a pty master means the slave side is gone: the child exited.
                Ok(Err(e)) if e.raw_os_error() == Some(libc::EIO) => return Event::Exit(self.exit_code().await),
                Ok(Err(_)) => return Event::Closed,
                Err(_would_block) => continue,
            }
        }
    }

    async fn exit_code(&self) -> Option<i32> {
        for _ in 0..20 {
            if let Some(code) = self.pty.try_wait() {
                return Some(code);
            }
            tokio::time::sleep(std::time::Duration::from_millis(10)).await;
        }
        None
    }

    async fn write(&self, mut bytes: &[u8]) -> bool {
        while !bytes.is_empty() {
            let mut guard = match self.fd.writable().await {
                Ok(g) => g,
                Err(_) => return false,
            };
            match guard.try_io(|inner| {
                use std::os::fd::AsRawFd;
                // SAFETY: valid fd, valid buffer.
                let n = unsafe { libc::write(inner.as_raw_fd(), bytes.as_ptr() as *const _, bytes.len()) };
                if n < 0 {
                    Err(std::io::Error::last_os_error())
                } else {
                    Ok(n as usize)
                }
            }) {
                Ok(Ok(n)) => bytes = &bytes[n..],
                Ok(Err(_)) => return false,
                Err(_would_block) => continue,
            }
        }
        true
    }
}
