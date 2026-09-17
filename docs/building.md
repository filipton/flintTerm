# Building, testing and releasing

- [Requirements](#requirements)
- [Building](#building)
- [Releasing](#releasing)
- [Tests](#tests)
- [Layout](#layout)
- [Bundled assets](#bundled-assets)

## Requirements

- Rust (stable or nightly) with `cargo-ndk` (`cargo install cargo-ndk`)
- The Android targets: `rustup target add aarch64-linux-android x86_64-linux-android`
- Android SDK with NDK 28+
- JDK 17+ (JDK 26 works with the bundled Gradle 9.7)
- Optional: Zig 0.16+ for the ghostty engine, Go 1.22+ for Tailscale

## Building

```sh
./build-apk.sh                 # signed release, arm64-v8a -> dist/flintTerm-<ver>-arm64-v8a-release.apk
./build-apk.sh --abi all       # arm64-v8a + x86_64 (emulators too)
./build-apk.sh --debug         # debug build: verbose Rust logs, noticeably slower SFTP
./build-apk.sh --install       # and adb install it
./build-apk.sh --term ghostty  # the libghostty-vt engine
```

**Signing.** The first run creates `androidterm.jks` and `keystore.properties` (both gitignored).
Keep them: Android won't update an app signed with a different key. Or set `KEYSTORE_FILE`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` in the environment.

**Tailscale.** `./build-tailscale.sh [abi]` cross-compiles
[libtailscale](https://github.com/tailscale/libtailscale) into `app/src/main/jniLibs/<abi>/`. It's
only built in when that library exists for **every** ABI in the build; if one is missing, it's left
out for all of them. Without it the app still builds, and the Tailscale card says it isn't included.

**Gradle directly:**

```sh
export ANDROID_HOME=~/Android/Sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/<version>
./gradlew :app:assembleRelease                      # arm64-v8a + x86_64, minified, signed if configured
./gradlew :app:assembleDebug -PrustTargets=x86_64   # emulator only, faster
```

Gradle runs `cargo ndk` and `uniffi-bindgen` itself. The Kotlin bindings land in
`app/build/generated/uniffi` and the `.so` files in `app/build/rust/jniLibs`.

## Releasing

`./release.sh` builds one APK per terminal engine into `dist/release-<version>/`, with checksums and
a summary:

```sh
./release.sh                    # one APK per engine
./release.sh --with-32bit       # plus a 32-bit ARM APK per engine
./release.sh --abi arm64-v8a    # one ABI only: a third of the size, phones only
./release.sh --alacritty-only   # skip the engine that needs Zig
./release.sh --no-test          # skip the unit tests
./release.sh --publish          # tag, push and create a draft GitHub release
./release.sh --publish --live   # ... published instead of a draft
```

- **Each APK holds arm64-v8a and x86_64.** Android installs the one that matches, so nobody has to
  pick.
- **32-bit ARM is a separate file**, because adding it to every APK would add another copy of
  everything (Tailscale alone is 20 MB) for a few old phones.
- **Check the signing certificate** it prints against the last release. An APK signed with a
  different key only installs after uninstalling, which wipes every host, key and setting.

### Cutting a release

The changelog is generated from commit types: `feat:` → Added, `fix:` → Fixed, `perf:` →
Performance. `test:` and `chore:` are left out.

```sh
tools/changelog.py                  # preview what landed since the last tag
tools/changelog.py --update         # write it into CHANGELOG.md's [Unreleased]
# edit the draft if needed
tools/changelog.py --release 0.2.0  # turn [Unreleased] into a dated version
# bump versionName in app/build.gradle.kts, commit as "chore: 0.2.0"
./release.sh --publish              # build, tag, push, upload a draft release
```

`--publish` refuses a dirty tree or a version with no changelog section. It uses that section as the
release notes and uploads every APK plus `SHA256SUMS`.

## Tests

```sh
./gradlew :app:testDebugUnitTest  # Kotlin
cargo test                        # Rust
```

Against a real sshd (see `crates/ssh-core/tests/integration.rs`):

```sh
SSH_TEST_PORT=2222 SSH_TEST_KEY=~/.ssh/id_ed25519 cargo test -p ssh-core -p flintterm
```

- **`ssh-core`** covers exec, a pty shell with resize, local and remote forwards, and an SFTP round
  trip.
- **`flintterm`** covers every transport end to end: SSH, the local shell, Mosh (direct and through
  a jump host), WireGuard, SOCKS5, predictive echo, telnet and agent forwarding. Add `--features
  ghostty` to run it on the other engine; both pass identically.
- **`mosh`** has its own interop tests against a real `mosh-server`: see [its
  README](../crates/mosh/README.md).

## Layout

```
app/                   the Android app
crates/term-core       Emulator trait, packed snapshot format, xterm key/mouse encoders
crates/term-alacritty  alacritty_terminal backend (the default)
crates/term-ghostty    libghostty-vt backend, behind the `ghostty` feature
crates/ghostty-vt-sys  raw bindings to libghostty-vt; its build script builds the Zig library
crates/term-diff       runs both backends on the same input and reports differences
crates/ssh-core        russh client: auth, shell, exec, forwards, SFTP, key generation
crates/mosh            Mosh client: the SSP wire protocol and predictive echo
crates/wg              userspace WireGuard: boringtun + smoltcp, virtual TCP streams, tunnel DNS
crates/tun2ssh         "use a host as a VPN": a userspace IP stack over SSH channels
crates/pty             fork/exec on a pty for the local shell
crates/ffi             uniffi surface: sessions, SFTP, telnet, VPNs, backup, and the rest
crates/uniffi-bindgen  the bindgen binary Gradle calls
tailscale/             Go glue compiled into libtailscale
tools/                 changelog.py, gen-schemes.py
```

## Bundled assets

- **Color schemes**: about six hundred from
  [iTerm2-Color-Schemes](https://github.com/mbadolato/iTerm2-Color-Schemes) (MIT, Mark Badolato and
  contributors; each scheme belongs to its author), packed into `app/src/main/assets/schemes.txt` by
  `tools/gen-schemes.py`.
- **Fonts**: Nerd Font symbols (MIT), JetBrains Mono (SIL OFL), Fira Code (SIL OFL 1.1) and Hack
  (MIT plus the Bitstream Vera license). Each licence is in `app/FONT_LICENSE_*.txt`.

All of them are listed in `NOTICE`.

[← back to the README](../README.md)
