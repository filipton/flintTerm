//! Builds (or locates) `libghostty-vt.a` and tells cargo to link it.
//!
//! libghostty-vt is written in Zig, so this needs a `zig` on PATH. The source
//! is fetched once from GitHub at a pinned revision into a cache directory and
//! then built per Rust target; both steps are skipped when their output is
//! already there, so only the first build of an ABI pays for them.
//!
//! Escape hatches, in the order they are checked:
//!
//! * `GHOSTTY_VT_LIB_DIR` — a directory holding a prebuilt `libghostty-vt.a`
//!   for this target. Nothing is fetched or compiled.
//! * `GHOSTTY_SRC` — a Ghostty checkout to build from instead of fetching.
//! * `GHOSTTY_VT_REV` — a different revision to fetch.
//! * `GHOSTTY_VT_CACHE_DIR` — where the source and the per-target builds live.
//! * `GHOSTTY_VT_OPTIMIZE` — the Zig optimize mode (default `ReleaseFast`).
//! * `GHOSTTY_VT_FEATURES` — which parts of libghostty-vt to build, in the
//!   `-Dvt-features` syntax. Defaults to `FEATURES`.

use std::env;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;

/// Pinned libghostty-vt revision. Bump together with the FFI in `src/lib.rs`.
const REV: &str = "448062571c5edf010b7490d06869b88b5ebf8f80";

/// The parts of libghostty-vt this crate actually calls.
///
/// Everything is on by default, and the ones turned off here are whole
/// subsystems the app never reaches: it encodes keys and mice with
/// `term_core::keys`, searches the scrollback through `Emulator::all_lines`,
/// draws no images, and has no use for libghostty's own state serialisation.
/// Carrying them cost about a third of the static library.
///
/// Anything referenced but disabled fails to link, loudly, rather than going
/// quiet at runtime.
const FEATURES: &str = "-search,-snapshot,-input-encode,-kitty-graphics,-glyph-protocol";

fn main() {
    println!("cargo:rerun-if-changed=build.rs");
    for var in [
        "GHOSTTY_VT_LIB_DIR",
        "GHOSTTY_SRC",
        "GHOSTTY_VT_REV",
        "GHOSTTY_VT_CACHE_DIR",
        "GHOSTTY_VT_OPTIMIZE",
        "GHOSTTY_VT_FEATURES",
    ] {
        println!("cargo:rerun-if-env-changed={var}");
    }

    if let Ok(dir) = env::var("GHOSTTY_VT_LIB_DIR") {
        link(Path::new(&dir));
        return;
    }

    let rev = env::var("GHOSTTY_VT_REV").unwrap_or_else(|_| REV.to_string());
    let cache = cache_dir();
    let src = match env::var("GHOSTTY_SRC") {
        Ok(p) => PathBuf::from(p),
        Err(_) => fetch(&cache, &rev),
    };

    let target = env::var("TARGET").unwrap();
    let optimize = env::var("GHOSTTY_VT_OPTIMIZE").unwrap_or_else(|_| "ReleaseFast".to_string());
    let features = env::var("GHOSTTY_VT_FEATURES").unwrap_or_else(|_| FEATURES.to_string());
    let zig_target = zig_target(&target);
    // The feature set is part of a build's identity, or changing it would
    // silently reuse a library built from a different one.
    let prefix = cache.join(format!(
        "build-{}-{}-{:08x}",
        zig_target.as_deref().unwrap_or("native"),
        optimize,
        fnv1a(&features)
    ));

    if !prefix.join("lib/libghostty-vt.a").is_file() {
        build(&src, &prefix, zig_target.as_deref(), &optimize, &features);
    }
    link(&prefix.join("lib"));
    println!("cargo:include={}", prefix.join("include").display());
}

fn link(dir: &Path) {
    let lib = dir.join("libghostty-vt.a");
    if !lib.is_file() {
        panic!("no libghostty-vt.a in {}", dir.display());
    }
    println!("cargo:rustc-link-search=native={}", dir.display());
    println!("cargo:rustc-link-lib=static=ghostty-vt");
    println!("cargo:lib={}", lib.display());
}

fn cache_dir() -> PathBuf {
    if let Ok(dir) = env::var("GHOSTTY_VT_CACHE_DIR") {
        return PathBuf::from(dir);
    }
    let base = match env::var("CARGO_TARGET_DIR") {
        Ok(dir) => PathBuf::from(dir),
        // <workspace>/crates/ghostty-vt-sys -> <workspace>/target
        Err(_) => PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap())
            .join("../../target")
            .canonicalize()
            .unwrap_or_else(|_| PathBuf::from(env::var("OUT_DIR").unwrap())),
    };
    base.join("ghostty-vt")
}

/// The Zig triple for a Rust target triple. `None` means "build natively".
fn zig_target(rust: &str) -> Option<String> {
    let zig = match rust {
        "aarch64-linux-android" => "aarch64-linux-android",
        "armv7-linux-androideabi" | "arm-linux-androideabi" | "thumbv7neon-linux-androideabi" => {
            "arm-linux-androideabi"
        }
        "x86_64-linux-android" => "x86_64-linux-android",
        "i686-linux-android" => "x86-linux-android",
        _ => return None,
    };
    Some(zig.to_string())
}

/// Downloads the pinned Ghostty tree into the cache, once.
fn fetch(cache: &Path, rev: &str) -> PathBuf {
    let dir = cache.join(format!("src-{rev}"));
    let stamp = dir.join(".flintterm-fetched");
    if stamp.is_file() {
        return dir;
    }

    fs::create_dir_all(cache).expect("create ghostty-vt cache dir");
    let tmp = cache.join(format!(".src-{rev}.tmp"));
    let _ = fs::remove_dir_all(&tmp);
    fs::create_dir_all(&tmp).expect("create ghostty-vt staging dir");

    let url = format!("https://codeload.github.com/ghostty-org/ghostty/tar.gz/{rev}");
    eprintln!("ghostty-vt: fetching {url}");
    let mut curl = Command::new("curl")
        .args(["-fL", "--retry", "3", "-o", "-", &url])
        .stdout(std::process::Stdio::piped())
        .spawn()
        .expect("curl is needed to fetch libghostty-vt; install it or set GHOSTTY_SRC");
    let status = Command::new("tar")
        .args(["xz", "--strip-components=1", "-C"])
        .arg(&tmp)
        .stdin(curl.stdout.take().unwrap())
        .status()
        .expect("tar is needed to unpack libghostty-vt");
    // A download cut short can still unpack cleanly up to where it stopped,
    // so curl's own verdict is the one that says the tree is whole.
    let fetched = curl.wait().expect("wait for curl");
    assert!(fetched.success(), "failed to fetch {url}");
    assert!(status.success(), "failed to unpack {url}");

    fs::write(tmp.join(".flintterm-fetched"), rev).unwrap();
    let _ = fs::remove_dir_all(&dir);
    fs::rename(&tmp, &dir).expect("move the fetched ghostty tree into place");
    dir
}

/// Enough of a hash to tell one feature set from another in a directory name.
fn fnv1a(s: &str) -> u32 {
    s.bytes().fold(0x811c_9dc5u32, |h, b| (h ^ b as u32).wrapping_mul(0x0100_0193))
}

fn build(src: &Path, prefix: &Path, zig_target: Option<&str>, optimize: &str, features: &str) {
    let mut cmd = Command::new("zig");
    cmd.current_dir(src)
        .arg("build")
        .arg("-Demit-lib-vt=true")
        .arg(format!("-Doptimize={optimize}"))
        .arg("--prefix")
        .arg(prefix);
    if let Some(t) = zig_target {
        cmd.arg(format!("-Dtarget={t}"));
    }
    if !features.is_empty() {
        cmd.arg(format!("-Dvt-features={features}"));
    }
    // Zig picks its own C toolchain; a cross-compiling cargo run (cargo-ndk)
    // exports CC/AR/CFLAGS for the NDK, which would only confuse it.
    for var in ["CC", "CXX", "AR", "CFLAGS", "CXXFLAGS", "LDFLAGS", "RANLIB"] {
        cmd.env_remove(var);
    }
    eprintln!("ghostty-vt: {cmd:?}");
    let status = cmd
        .status()
        .expect("zig is needed to build libghostty-vt; install Zig 0.16+ or set GHOSTTY_VT_LIB_DIR");
    assert!(status.success(), "zig build of libghostty-vt failed");
}
