//! Prints libghostty-vt's ABI type manifest.
//!
//!     cargo run -p ghostty-vt-sys --example manifest
//!
//! The packed bit layouts in it are not frozen by the C ABI, so this is how to
//! check what a newly pinned revision actually says before trusting the fast
//! cell decoder in `term-ghostty`.

fn main() {
    let json = unsafe { std::ffi::CStr::from_ptr(ghostty_vt_sys::ghostty_type_json()) };
    println!("{}", json.to_string_lossy());
}
