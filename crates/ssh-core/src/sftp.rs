//! SFTP convenience layer.

use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

use russh_sftp::client::SftpSession;
use russh_sftp::protocol::{FileType, OpenFlags};
use tokio::io::{AsyncReadExt, AsyncWriteExt};

use crate::SshError;

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
    session: SftpSession,
    cancelled: AtomicBool,
}

impl Sftp {
    pub(crate) fn new(session: SftpSession) -> Self {
        session.set_timeout(60);
        Self { session, cancelled: AtomicBool::new(false) }
    }

    pub fn cancel(&self) {
        self.cancelled.store(true, Ordering::Relaxed);
    }

    pub async fn home(&self) -> Result<String, SshError> {
        Ok(self.session.canonicalize(".").await?)
    }

    pub async fn canonicalize(&self, path: &str) -> Result<String, SshError> {
        Ok(self.session.canonicalize(path).await?)
    }

    pub async fn list(&self, path: &str) -> Result<Vec<Entry>, SshError> {
        let dir = self.session.read_dir(path).await?;
        let mut out = Vec::new();
        for e in dir {
            let name = e.file_name();
            if name == "." || name == ".." {
                continue;
            }
            let meta = e.metadata();
            let ft = e.file_type();
            let mut is_dir = ft == FileType::Dir;
            let is_symlink = ft == FileType::Symlink;
            let full = join(path, &name);
            if is_symlink {
                // Resolve symlinks so directories behave like directories in the browser.
                if let Ok(target) = self.session.metadata(full.clone()).await {
                    is_dir = target.file_type() == FileType::Dir;
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
        Ok(self.session.create_dir(path).await?)
    }

    pub async fn remove(&self, path: &str, is_dir: bool) -> Result<(), SshError> {
        if is_dir {
            // Recursive delete, depth first.
            let entries = self.list(path).await?;
            for e in entries {
                Box::pin(self.remove(&e.path, e.is_dir && !e.is_symlink)).await?;
            }
            Ok(self.session.remove_dir(path).await?)
        } else {
            Ok(self.session.remove_file(path).await?)
        }
    }

    pub async fn rename(&self, from: &str, to: &str) -> Result<(), SshError> {
        Ok(self.session.rename(from, to).await?)
    }

    pub async fn download(&self, remote: &str, local: &Path, progress: Progress) -> Result<u64, SshError> {
        let out = tokio::fs::File::create(local).await?;
        self.download_to(remote, out, progress).await
    }

    /// Stream a remote file into an already-open local file (e.g. a content:// fd).
    pub async fn download_to(&self, remote: &str, mut out: tokio::fs::File, progress: Progress) -> Result<u64, SshError> {
        self.cancelled.store(false, Ordering::Relaxed);
        let total = self.session.metadata(remote).await.ok().and_then(|m| m.size);
        let mut file = self.session.open_with_flags(remote, OpenFlags::READ).await?;
        let mut buf = vec![0u8; 256 * 1024];
        let mut done = 0u64;
        loop {
            if self.cancelled.load(Ordering::Relaxed) {
                return Err(SshError::Other("cancelled".into()));
            }
            let n = file.read(&mut buf).await?;
            if n == 0 {
                break;
            }
            out.write_all(&buf[..n]).await?;
            done += n as u64;
            if !progress(done, total) {
                return Err(SshError::Other("cancelled".into()));
            }
        }
        out.flush().await?;
        let _ = file.shutdown().await;
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
        let mut file = self
            .session
            .open_with_flags(remote, OpenFlags::CREATE | OpenFlags::TRUNCATE | OpenFlags::WRITE)
            .await?;
        let mut buf = vec![0u8; 256 * 1024];
        let mut done = 0u64;
        loop {
            if self.cancelled.load(Ordering::Relaxed) {
                return Err(SshError::Other("cancelled".into()));
            }
            let n = input.read(&mut buf).await?;
            if n == 0 {
                break;
            }
            file.write_all(&buf[..n]).await?;
            done += n as u64;
            if !progress(done, total) {
                return Err(SshError::Other("cancelled".into()));
            }
        }
        file.flush().await?;
        file.shutdown().await?;
        Ok(done)
    }

    pub async fn stat(&self, path: &str) -> Result<Entry, SshError> {
        let meta = self.session.metadata(path).await?;
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
        let meta = self.session.metadata(remote).await?;
        if meta.size.unwrap_or(0) > max_bytes {
            return Err(SshError::Other("file too large".into()));
        }
        let data = self.session.read(remote).await?;
        Ok(String::from_utf8_lossy(&data).into_owned())
    }

    /// Write a whole file, creating it if it is not there yet.
    ///
    /// `write` on its own opens for writing without CREATE, which fails with
    /// "no such file" on anything new — not what a caller writing a file means.
    pub async fn write_string(&self, remote: &str, contents: &str) -> Result<(), SshError> {
        use tokio::io::AsyncWriteExt;
        let mut file = self
            .session
            .open_with_flags(remote, OpenFlags::CREATE | OpenFlags::TRUNCATE | OpenFlags::WRITE)
            .await?;
        file.write_all(contents.as_bytes()).await?;
        file.shutdown().await?;
        Ok(())
    }

    pub async fn close(&self) {
        let _ = self.session.close().await;
    }
}

fn join(dir: &str, name: &str) -> String {
    if dir.ends_with('/') {
        format!("{dir}{name}")
    } else {
        format!("{dir}/{name}")
    }
}
