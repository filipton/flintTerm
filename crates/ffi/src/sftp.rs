use std::path::Path;
use std::sync::Arc;

use ssh_core::Sftp;

use crate::runtime::block_on;
use crate::CoreError;

#[derive(Debug, Clone, uniffi::Record)]
pub struct FileEntry {
    pub name: String,
    pub path: String,
    pub is_dir: bool,
    pub is_symlink: bool,
    pub size: u64,
    /// Unix seconds.
    pub mtime: Option<u32>,
    pub permissions: Option<u32>,
}

#[uniffi::export(foreign)]
pub trait TransferListener: Send + Sync {
    /// Return false to cancel.
    fn on_progress(&self, done: u64, total: Option<u64>) -> bool;
}

#[derive(uniffi::Object)]
pub struct SftpClient {
    sftp: Sftp,
    /// The connection this was opened on, when it belongs to us rather than to
    /// the session (a Mosh session has left SSH behind, so file access dials its
    /// own). Held so it lives exactly as long as the client does.
    _owner: Option<Arc<ssh_core::SshClient>>,
}

impl SftpClient {
    pub(crate) fn new(sftp: Sftp) -> Arc<Self> {
        Arc::new(Self { sftp, _owner: None })
    }

    pub(crate) fn owning(sftp: Sftp, client: Arc<ssh_core::SshClient>) -> Arc<Self> {
        Arc::new(Self { sftp, _owner: Some(client) })
    }
}

#[uniffi::export]
impl SftpClient {
    pub fn home(&self) -> Result<String, CoreError> {
        Ok(block_on(self.sftp.home())?)
    }

    pub fn canonicalize(&self, path: String) -> Result<String, CoreError> {
        Ok(block_on(self.sftp.canonicalize(&path))?)
    }

    pub fn list(&self, path: String) -> Result<Vec<FileEntry>, CoreError> {
        let entries = block_on(self.sftp.list(&path))?;
        Ok(entries
            .into_iter()
            .map(|e| FileEntry {
                name: e.name,
                path: e.path,
                is_dir: e.is_dir,
                is_symlink: e.is_symlink,
                size: e.size,
                mtime: e.mtime,
                permissions: e.permissions,
            })
            .collect())
    }

    pub fn mkdir(&self, path: String) -> Result<(), CoreError> {
        Ok(block_on(self.sftp.mkdir(&path))?)
    }

    pub fn remove(&self, path: String, is_dir: bool) -> Result<(), CoreError> {
        Ok(block_on(self.sftp.remove(&path, is_dir))?)
    }

    pub fn rename(&self, from: String, to: String) -> Result<(), CoreError> {
        Ok(block_on(self.sftp.rename(&from, &to))?)
    }

    pub fn download(&self, remote: String, local_path: String, listener: Arc<dyn TransferListener>) -> Result<u64, CoreError> {
        let progress: ssh_core::sftp::Progress = Arc::new(move |done, total| listener.on_progress(done, total));
        Ok(block_on(self.sftp.download(&remote, Path::new(&local_path), progress))?)
    }

    pub fn upload(&self, local_path: String, remote: String, listener: Arc<dyn TransferListener>) -> Result<u64, CoreError> {
        let progress: ssh_core::sftp::Progress = Arc::new(move |done, total| listener.on_progress(done, total));
        Ok(block_on(self.sftp.upload(Path::new(&local_path), &remote, progress))?)
    }

    /// Download into an open file descriptor (ownership of `fd` is taken).
    pub fn download_fd(&self, remote: String, fd: i32, listener: Arc<dyn TransferListener>) -> Result<u64, CoreError> {
        let file = file_from_fd(fd)?;
        let progress: ssh_core::sftp::Progress = Arc::new(move |done, total| listener.on_progress(done, total));
        Ok(block_on(self.sftp.download_to(&remote, file, progress))?)
    }

    /// Upload from an open file descriptor (ownership of `fd` is taken).
    pub fn upload_fd(&self, fd: i32, remote: String, listener: Arc<dyn TransferListener>) -> Result<u64, CoreError> {
        let file = file_from_fd(fd)?;
        let progress: ssh_core::sftp::Progress = Arc::new(move |done, total| listener.on_progress(done, total));
        Ok(block_on(self.sftp.upload_from(file, &remote, progress))?)
    }

    pub fn stat(&self, path: String) -> Result<FileEntry, CoreError> {
        let e = block_on(self.sftp.stat(&path))?;
        Ok(FileEntry {
            name: e.name,
            path: e.path,
            is_dir: e.is_dir,
            is_symlink: e.is_symlink,
            size: e.size,
            mtime: e.mtime,
            permissions: e.permissions,
        })
    }

    pub fn read_text(&self, remote: String, max_bytes: u64) -> Result<String, CoreError> {
        Ok(block_on(self.sftp.read_to_string(&remote, max_bytes))?)
    }

    pub fn write_text(&self, remote: String, contents: String) -> Result<(), CoreError> {
        Ok(block_on(self.sftp.write_string(&remote, &contents))?)
    }

    pub fn cancel(&self) {
        self.sftp.cancel();
    }

    pub fn shutdown(&self) {
        block_on(self.sftp.close());
    }
}

fn file_from_fd(fd: i32) -> Result<tokio::fs::File, CoreError> {
    use std::os::fd::FromRawFd;
    if fd < 0 {
        return Err(CoreError::Other("invalid fd".into()));
    }
    // SAFETY: the caller detached the descriptor and hands ownership to us.
    let std_file = unsafe { std::fs::File::from_raw_fd(fd) };
    Ok(tokio::fs::File::from_std(std_file))
}
