# Building, testing and releasing


Requirements: Rust (stable or nightly), `cargo-ndk` (`cargo install cargo-ndk`), the Android targets
(`rustup target add aarch64-linux-android x86_64-linux-android`), Android SDK with NDK 28+, JDK 17+
(JDK 26 works with the bundled Gradle 9.7).

Quickest way to an APK for a phone:

```sh
./build-apk.sh                 # signed release build, arm64-v8a  -> dist/flintTerm-<ver>-arm64-v8a-release.apk
./build-apk.sh --abi all       # arm64-v8a + x86_64 (emulators too)
./build-apk.sh --debug         # debug build (verbose Rust logs, noticeably slower SFTP)
./build-apk.sh --install       # and adb install it
./build-apk.sh --term ghostty  # build the VT core on libghostty-vt (needs Zig 0.16+)
```

The first run generates a self-signed key (`androidterm.jks` + `keystore.properties`, both gitignored).
Keep them: Android refuses to update an app whose new APK is signed with a different key.
Alternatively set `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` in the environment.

For a release, `./release.sh` builds one APK per terminal engine and leaves them, their checksums
and a summary in `dist/release-<version>/` — the whole directory is what a release page wants:

```sh
./release.sh                    # one APK per engine, runs on anything
./release.sh --with-32bit       # and a 32-bit ARM APK per engine, for old phones
./release.sh --abi arm64-v8a    # one ABI only: a third of the size, phones only
./release.sh --alacritty-only   # skip the engine that needs Zig
./release.sh --no-test          # don't run the unit tests first
```

Each APK carries the machine code for both 64-bit ABIs, so nobody downloading one has to know
what a phone is made of: Android installs the slice that matches and ignores the rest.
`arm64-v8a` is every phone worth speaking of, and `x86_64` covers emulators and Intel Chromebooks.

`armeabi-v7a` (32-bit ARM) is a file of its own rather than a third slice in the same APK: it
would add its own copy of everything — `libtailscale.so` alone is 20 MB — to every download, for
the few old phones that still need it. It is otherwise a complete build, Tailscale included.

What decides whether Tailscale is in a build is `app/src/main/jniLibs/<abi>/libtailscale.so`
existing for **every** ABI in it. Gradle compiles Tailscale out when any of them is missing, so a
build mixing an ABI that has one with an ABI that does not silently loses it for both. Run
`./build-tailscale.sh <abi>` for anything new; `release.sh` warns when a build is about to go out
without it.

It prints the signing certificate of each APK. Compare it against the last release before
uploading: a release signed with a different key installs only after uninstalling, which takes
every host, key and setting on the phone with it.

### Cutting a release

`CHANGELOG.md` is grouped from the commit log, so the history and the release notes cannot drift:
`feat:` becomes Added, `fix:` becomes Fixed, `perf:` becomes Performance, and `test:`/`chore:` are
left out as being about the work rather than the app.

```sh
tools/changelog.py                  # what has landed since the last tag
tools/changelog.py --update         # write that into CHANGELOG.md's [Unreleased]
# edit it: the generated lines are a first draft, not the last word
tools/changelog.py --release 0.2.0  # close [Unreleased] as a dated version
# bump versionName in app/build.gradle.kts, commit
./release.sh --publish              # build, tag, push, and upload a draft release
```

`--publish` is the only part that leaves the machine. It refuses to run from a dirty tree or
without a `CHANGELOG.md` section for the version, takes the release notes from that section, and
creates the GitHub release as a **draft** with every APK and `SHA256SUMS` attached, so nothing is
public until you press publish. `--live` skips the draft.

Gradle directly:

```sh
export ANDROID_HOME=~/Android/Sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/<version>
./gradlew :app:assembleRelease          # arm64-v8a + x86_64, minified, signed if a keystore is configured
./gradlew :app:assembleDebug -PrustTargets=x86_64   # emulator-only, faster
```

Optional: `./build-tailscale.sh` cross-compiles [libtailscale](https://github.com/tailscale/libtailscale)
(needs Go) into `app/src/main/jniLibs/`; when present for every ABI, Gradle builds the Rust core with
the `tailscale` feature. Without it the app still builds, and the Tailscale card says it is not included.

Gradle runs `cargo ndk` and `uniffi-bindgen` automatically (see `app/build.gradle.kts`); the Kotlin
bindings land in `app/build/generated/uniffi` and the `.so` files in `app/build/rust/jniLibs`.

## Tests

```sh
cargo test                       # emulator, key encoding, pty, key generation
# against a real sshd (see crates/ssh-core/tests/integration.rs):
SSH_TEST_PORT=2222 SSH_TEST_KEY=~/.ssh/id_ed25519 cargo test -p ssh-core -p flintterm
```

The integration tests exercise exec, a pty shell with resize, local + remote forwards, and an SFTP
upload/download round-trip.

`cargo test -p flintterm` covers the transports end to end — SSH, the local shell, Mosh over UDP
and through a jump host, WireGuard, SOCKS5, predictive echo, telnet, agent forwarding — and takes
`--features ghostty` like anything else. It passes identically on both emulator backends against a
real sshd and a real `mosh-server`, which is what says the newer one is safe to run a session on and
not only to draw a grid with.

## Layout

```
crates/term-core       Emulator trait, packed snapshot format, xterm key/mouse encoders
crates/term-alacritty  alacritty_terminal backend (the default)
crates/term-ghostty    libghostty-vt backend, behind the `ghostty` feature
crates/ghostty-vt-sys  raw bindings to libghostty-vt; its build script builds the Zig library
crates/term-diff       runs both backends on the same input and reports where they differ
crates/ssh-core        russh client: auth, shell, exec, forwards, SFTP, key generation
crates/pty             fork/exec on a pty for the local shell
crates/wg              userspace WireGuard: boringtun + smoltcp, virtual TCP streams, tunnel DNS
tailscale/             Go glue compiled into libtailscale (interface list from Java, log dir)
crates/ffi             uniffi surface: Session, SftpClient, listeners
app/                   Android application
```

Bundled color schemes: six hundred from [iTerm2-Color-Schemes](https://github.com/mbadolato/iTerm2-Color-Schemes)
(MIT, Mark Badolato and contributors; each scheme belongs to its author), packed into
`app/src/main/assets/schemes.txt` by `tools/gen-schemes.py` — see `NOTICE`.

Bundled fonts: the Nerd Font symbols under the MIT license (see
`app/FONT_LICENSE_SymbolsNerdFont.txt`), JetBrains Mono under the SIL Open Font License (see
`app/FONT_LICENSE_JetBrainsMono.txt`), Fira Code (SIL OFL 1.1, `app/FONT_LICENSE_FiraCode.txt`) and
Hack (MIT plus the Bitstream Vera license, `app/FONT_LICENSE_Hack.txt`). `NOTICE` lists all four.

[← back to the README](../README.md)
