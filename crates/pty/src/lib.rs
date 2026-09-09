//! Local pseudo-terminal: spawn a shell on the device attached to a pty.
//!
//! Tokio-free on purpose; the owner wraps the master fd in whatever reactor it
//! uses. Everything between `fork` and `exec` is async-signal-safe libc only,
//! because the parent is a multithreaded process.

use std::ffi::CString;
use std::os::fd::{AsRawFd, BorrowedFd, OwnedFd, RawFd};

use nix::pty::{openpty, Winsize};
use nix::sys::signal::{kill, Signal};
use nix::sys::wait::{waitpid, WaitPidFlag, WaitStatus};
use nix::unistd::{fork, ForkResult, Pid};

#[derive(Debug, thiserror::Error)]
pub enum PtyError {
    #[error("pty: {0}")]
    Nix(#[from] nix::Error),
    #[error("invalid argument: {0}")]
    Invalid(String),
}

pub struct Pty {
    master: OwnedFd,
    child: Pid,
}

pub struct SpawnOptions<'a> {
    pub program: &'a str,
    pub args: &'a [String],
    pub env: &'a [(String, String)],
    pub cwd: Option<&'a str>,
    pub cols: u16,
    pub rows: u16,
}

impl Pty {
    pub fn spawn(opts: SpawnOptions<'_>) -> Result<Pty, PtyError> {
        let ws = Winsize { ws_row: opts.rows.max(1), ws_col: opts.cols.max(1), ws_xpixel: 0, ws_ypixel: 0 };
        let pair = openpty(Some(&ws), None)?;

        let cstr = |s: &str| CString::new(s).map_err(|_| PtyError::Invalid(s.to_owned()));
        let program = cstr(opts.program)?;
        let mut argv: Vec<CString> = Vec::with_capacity(opts.args.len() + 1);
        argv.push(program.clone());
        for a in opts.args {
            argv.push(cstr(a)?);
        }
        let envp: Vec<CString> = opts.env.iter().map(|(k, v)| cstr(&format!("{k}={v}"))).collect::<Result<_, _>>()?;
        let cwd = opts.cwd.map(cstr).transpose()?;

        let argv_ptrs: Vec<*const libc::c_char> =
            argv.iter().map(|s| s.as_ptr()).chain(std::iter::once(std::ptr::null())).collect();
        let envp_ptrs: Vec<*const libc::c_char> =
            envp.iter().map(|s| s.as_ptr()).chain(std::iter::once(std::ptr::null())).collect();

        // SAFETY: the child only calls async-signal-safe functions before exec.
        match unsafe { fork() }? {
            ForkResult::Child => unsafe {
                let slave = pair.slave.as_raw_fd();
                libc::setsid();
                libc::ioctl(slave, libc::TIOCSCTTY as _, 0);
                libc::dup2(slave, 0);
                libc::dup2(slave, 1);
                libc::dup2(slave, 2);
                if slave > 2 {
                    libc::close(slave);
                }
                libc::close(pair.master.as_raw_fd());
                if let Some(cwd) = &cwd {
                    libc::chdir(cwd.as_ptr());
                }
                // Reset signal dispositions the JVM/tokio parent may have altered.
                for sig in [libc::SIGINT, libc::SIGQUIT, libc::SIGTERM, libc::SIGPIPE, libc::SIGCHLD, libc::SIGHUP] {
                    libc::signal(sig, libc::SIG_DFL);
                }
                libc::execve(program.as_ptr(), argv_ptrs.as_ptr(), envp_ptrs.as_ptr());
                libc::_exit(127);
            },
            ForkResult::Parent { child } => {
                drop(pair.slave);
                Ok(Pty { master: pair.master, child })
            }
        }
    }

    pub fn master(&self) -> BorrowedFd<'_> {
        self.master_fd_borrowed()
    }

    fn master_fd_borrowed(&self) -> BorrowedFd<'_> {
        // SAFETY: `master` outlives the borrow.
        unsafe { BorrowedFd::borrow_raw(self.master.as_raw_fd()) }
    }

    pub fn raw_fd(&self) -> RawFd {
        self.master.as_raw_fd()
    }

    /// Duplicate the master fd so it can be moved into an async reactor.
    pub fn dup_master(&self) -> Result<OwnedFd, PtyError> {
        Ok(nix::unistd::dup(self.master_fd_borrowed())?)
    }

    pub fn set_nonblocking(&self) -> Result<(), PtyError> {
        use nix::fcntl::{fcntl, FcntlArg, OFlag};
        let fd = self.master_fd_borrowed();
        let flags = OFlag::from_bits_truncate(fcntl(fd, FcntlArg::F_GETFL)?);
        fcntl(fd, FcntlArg::F_SETFL(flags | OFlag::O_NONBLOCK))?;
        Ok(())
    }

    pub fn resize(&self, cols: u16, rows: u16) -> Result<(), PtyError> {
        let ws = Winsize { ws_row: rows.max(1), ws_col: cols.max(1), ws_xpixel: 0, ws_ypixel: 0 };
        // SAFETY: TIOCSWINSZ with a valid winsize pointer on an open tty fd.
        let r = unsafe { libc::ioctl(self.master.as_raw_fd(), libc::TIOCSWINSZ as _, &ws as *const Winsize) };
        if r < 0 {
            return Err(nix::Error::last().into());
        }
        Ok(())
    }

    pub fn pid(&self) -> i32 {
        self.child.as_raw()
    }

    /// Non-blocking poll for child exit. `Some(code)` once it has exited.
    pub fn try_wait(&self) -> Option<i32> {
        match waitpid(self.child, Some(WaitPidFlag::WNOHANG)) {
            Ok(WaitStatus::Exited(_, code)) => Some(code),
            Ok(WaitStatus::Signaled(_, sig, _)) => Some(128 + sig as i32),
            Ok(_) => None,
            Err(_) => Some(-1),
        }
    }

    pub fn kill(&self) {
        let _ = kill(self.child, Signal::SIGHUP);
    }
}

impl Drop for Pty {
    fn drop(&mut self) {
        let _ = kill(self.child, Signal::SIGHUP);
        // Reap without blocking forever; a stuck child is left to init.
        let _ = waitpid(self.child, Some(WaitPidFlag::WNOHANG));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use nix::unistd::{read, write};

    #[test]
    fn spawns_and_echoes() {
        let args = vec!["-c".to_string(), "echo hello-$(stty size); exit 3".to_string()];
        let env = vec![("PATH".to_string(), "/usr/bin:/bin".to_string())];
        let pty = Pty::spawn(SpawnOptions { program: "/bin/sh", args: &args, env: &env, cwd: None, cols: 42, rows: 10 })
            .unwrap();
        let mut out = Vec::new();
        let mut buf = [0u8; 256];
        loop {
            match read(pty.master(), &mut buf) {
                Ok(0) => break,
                Ok(n) => out.extend_from_slice(&buf[..n]),
                Err(nix::Error::EIO) => break,
                Err(e) => panic!("{e}"),
            }
        }
        assert!(String::from_utf8_lossy(&out).contains("hello-10 42"), "{out:?}");
        std::thread::sleep(std::time::Duration::from_millis(50));
        assert_eq!(pty.try_wait(), Some(3));
        let _ = write(pty.master(), b"");
    }
}
