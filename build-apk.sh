#!/usr/bin/env bash
# Build a signed, installable flintTerm APK.
#
#   ./build-apk.sh                 # release, arm64-v8a only (phones)  -> dist/
#   ./build-apk.sh --abi all       # arm64-v8a + x86_64 (also emulators)
#   ./build-apk.sh --debug         # debug build (verbose Rust logging, slower SFTP)
#   ./build-apk.sh --install       # adb install the result afterwards
#   ./build-apk.sh --term ghostty  # build the VT core on libghostty-vt instead of
#                                  # alacritty (needs Zig 0.16+ on PATH)
#
# First run creates keystore.properties + androidterm.jks (a self-signed key).
# Keep them: reinstalling over an app signed with a different key fails.
set -euo pipefail
cd "$(dirname "$0")"

ABI=arm64-v8a; TYPE=release; INSTALL=0; TERM_BACKEND=alacritty
while [ $# -gt 0 ]; do
  case "$1" in
    --abi) ABI="$2"; shift ;;
    --debug) TYPE=debug ;;
    --install) INSTALL=1 ;;
    --term) TERM_BACKEND="$2"; shift ;;
    -h|--help) sed -n 2,13p "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done
[ "$ABI" = all ] && ABI=arm64-v8a,x86_64
case "$TERM_BACKEND" in
  alacritty) ;;
  ghostty) command -v zig >/dev/null || { echo "--term ghostty needs Zig 0.16+ on PATH (libghostty-vt is built from source)" >&2; exit 1; } ;;
  *) echo "unknown --term backend: $TERM_BACKEND (alacritty|ghostty)" >&2; exit 2 ;;
esac

# --- SDK / NDK -----------------------------------------------------------------
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  ANDROID_NDK_HOME=$(ls -d "$ANDROID_HOME"/ndk/*/ 2>/dev/null | sort -V | tail -1 || true)
  ANDROID_NDK_HOME=${ANDROID_NDK_HOME%/}
fi
[ -d "${ANDROID_NDK_HOME:-}" ] || { echo "NDK not found under $ANDROID_HOME/ndk; set ANDROID_NDK_HOME" >&2; exit 1; }
export ANDROID_NDK_HOME
command -v cargo >/dev/null || { echo "cargo not found (install Rust)" >&2; exit 1; }
command -v cargo-ndk >/dev/null || { echo "installing cargo-ndk"; cargo install cargo-ndk; }
for abi in ${ABI//,/ }; do
  case $abi in
    arm64-v8a) t=aarch64-linux-android ;;
    x86_64) t=x86_64-linux-android ;;
    armeabi-v7a) t=armv7-linux-androideabi ;;
    *) echo "unknown abi $abi" >&2; exit 2 ;;
  esac
  rustup target list --installed 2>/dev/null | grep -q "^$t\$" || rustup target add "$t"
done

# --- signing key (release only) -------------------------------------------------
if [ "$TYPE" = release ] && [ ! -f keystore.properties ]; then
  KEYTOOL=$(command -v keytool || ls "${JAVA_HOME:-/usr/lib/jvm/default}"/bin/keytool 2>/dev/null | head -1)
  [ -n "$KEYTOOL" ] || { echo "keytool not found; set JAVA_HOME" >&2; exit 1; }
  PASS=$(head -c 24 /dev/urandom | base64 | tr -d '/+=' )
  "$KEYTOOL" -genkeypair -v -keystore androidterm.jks -alias androidterm -keyalg RSA -keysize 4096 \
    -validity 10000 -storepass "$PASS" -keypass "$PASS" -dname "CN=flintTerm" >/dev/null 2>&1
  cat > keystore.properties <<PROPS
storeFile=androidterm.jks
storePassword=$PASS
keyAlias=androidterm
keyPassword=$PASS
PROPS
  chmod 600 keystore.properties
  echo "created androidterm.jks + keystore.properties (gitignored) — keep them to be able to update the app"
fi

# --- build ----------------------------------------------------------------------
task=$([ "$TYPE" = release ] && echo assembleRelease || echo assembleDebug)
./gradlew ":app:$task" -PrustTargets="$ABI" -PtermBackend="$TERM_BACKEND" --console=plain -q

version=$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts)
src="app/build/outputs/apk/$TYPE/app-$TYPE.apk"
mkdir -p dist
suffix=$([ "$TERM_BACKEND" = alacritty ] && echo "" || echo "-$TERM_BACKEND")
out="dist/flintTerm-$version-${ABI//,/+}$suffix-$TYPE.apk"
cp "$src" "$out"
echo "built $out ($(du -h "$out" | cut -f1))"

if [ "$INSTALL" = 1 ]; then
  adb install -r "$out"
fi
