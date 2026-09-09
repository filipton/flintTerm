//! Which terminal emulator the core is built with.
//!
//! `alacritty` (the default) uses `alacritty_terminal`; `ghostty` uses
//! libghostty-vt, Ghostty's own VT library, through `term-ghostty`. Both answer
//! [`term_core::Emulator`], so nothing above this module changes with the swap.
//!
//! Cargo features are additive, so when a build asks for both, `ghostty` wins —
//! that way `--features ghostty` selects it without having to also pass
//! `--no-default-features`.

use term_core::Emulator;

#[cfg(not(any(feature = "alacritty", feature = "ghostty")))]
compile_error!("flintterm needs a terminal backend: enable the `alacritty` or the `ghostty` feature");

/// The name of the backend this build was compiled with, for the About screen
/// and the logs.
pub const NAME: &str = if cfg!(feature = "ghostty") { "libghostty-vt" } else { "alacritty" };

pub fn new(cols: u16, rows: u16, scrollback: usize) -> Box<dyn Emulator> {
    #[cfg(feature = "ghostty")]
    {
        Box::new(term_ghostty::GhosttyEmulator::new(cols, rows, scrollback))
    }
    #[cfg(not(feature = "ghostty"))]
    {
        Box::new(term_alacritty::AlacrittyEmulator::new(cols, rows, scrollback))
    }
}

/// Which VT engine this build carries. Shown in Settings → About so a
/// libghostty build can be told apart from a stock one.
#[uniffi::export]
pub fn terminal_backend() -> String {
    NAME.to_string()
}
