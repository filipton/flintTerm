fn main() {
    println!("cargo:rerun-if-env-changed=TAILSCALE_JNILIBS");
    if std::env::var("CARGO_FEATURE_TAILSCALE").is_ok() {
        let root = std::env::var("TAILSCALE_JNILIBS").unwrap_or_else(|_| "../../app/src/main/jniLibs".into());
        let abi = match std::env::var("CARGO_CFG_TARGET_ARCH").as_deref() {
            Ok("aarch64") => "arm64-v8a",
            Ok("x86_64") => "x86_64",
            Ok("arm") => "armeabi-v7a",
            _ => "host",
        };
        println!("cargo:rustc-link-search=native={root}/{abi}");
        println!("cargo:rustc-link-lib=dylib=tailscale");
    }
}
