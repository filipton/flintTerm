//! The one call made on every frame, taken the short way across.
//!
//! Everything else reaches the core through uniffi, which runs over JNA, and
//! JNA builds its call-status and return structures by reflection, backed by
//! native memory, on every call. A snapshot was several trips across a frame
//! (the handles are cloned first, and the span comes back in a buffer that has
//! to be freed again), and profiling put JNA and the garbage it left at a few
//! percent of the app while output streams. This is plain JNI with nothing but
//! numbers going either way.
//!
//! The handles are extra strong references, taken once through uniffi and
//! given back here, so a session torn down on another thread cannot free what
//! a frame is still reading.

use std::ffi::c_void;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::Arc;

use crate::session::{Session, SnapshotBuffer};

#[uniffi::export]
impl Session {
    /// A strong reference for `FrameBridge.snapshotInto`; give it back with
    /// `FrameBridge.releaseSession`.
    pub fn frame_handle(self: Arc<Self>) -> u64 {
        Arc::into_raw(self) as usize as u64
    }

    /// A descriptor that turns readable whenever the grid changes, instead of
    /// the `on_damage` callback; -1 if it cannot be had.
    ///
    /// That callback is the other thing that happens on every frame, and it
    /// came across JNA the other way: a status structure read and written back
    /// by reflection, and the thread attached to the VM, to say one bit. An
    /// eventfd the main looper watches wakes the thread that draws directly.
    pub fn damage_fd(&self) -> i32 {
        self.open_damage_fd()
    }
}

#[uniffi::export]
impl SnapshotBuffer {
    /// A strong reference for `FrameBridge`; give it back with
    /// `FrameBridge.releaseBuffer`.
    pub fn frame_handle(self: Arc<Self>) -> u64 {
        Arc::into_raw(self) as usize as u64
    }
}

/// Bit 0 of `flags`: find the links too, so the header can say if they moved.
const WANT_LINKS: i32 = 1;

/// Fills the buffer from the session and returns its length, or -1.
#[no_mangle]
pub extern "system" fn Java_dev_flint_term_terminal_FrameBridge_snapshotInto(
    _env: *mut c_void,
    _class: *mut c_void,
    session: i64,
    buffer: i64,
    flags: i32,
) -> i64 {
    if session == 0 || buffer == 0 {
        return -1;
    }
    catch_unwind(AssertUnwindSafe(|| {
        // SAFETY: both came from `frame_handle` and have not been released;
        // the strong reference each holds keeps it alive through this call.
        let session = unsafe { &*(session as usize as *const Session) };
        let buffer = unsafe { &*(buffer as usize as *const SnapshotBuffer) };
        session.fill_snapshot(buffer, flags & WANT_LINKS != 0).len as i64
    }))
    .unwrap_or(-1)
}

/// Where the buffer's bytes start. Only asked for when the length changed.
#[no_mangle]
pub extern "system" fn Java_dev_flint_term_terminal_FrameBridge_bufferAddress(
    _env: *mut c_void,
    _class: *mut c_void,
    buffer: i64,
) -> i64 {
    if buffer == 0 {
        return 0;
    }
    // SAFETY: as above.
    let buffer = unsafe { &*(buffer as usize as *const SnapshotBuffer) };
    buffer.address() as i64
}

#[no_mangle]
pub extern "system" fn Java_dev_flint_term_terminal_FrameBridge_releaseSession(
    _env: *mut c_void,
    _class: *mut c_void,
    session: i64,
) {
    if session != 0 {
        // SAFETY: from `frame_handle`, released exactly once by the caller.
        drop(unsafe { Arc::from_raw(session as usize as *const Session) });
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_flint_term_terminal_FrameBridge_releaseBuffer(
    _env: *mut c_void,
    _class: *mut c_void,
    buffer: i64,
) {
    if buffer != 0 {
        // SAFETY: as above.
        drop(unsafe { Arc::from_raw(buffer as usize as *const SnapshotBuffer) });
    }
}
