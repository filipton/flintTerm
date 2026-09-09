#!/usr/bin/env bash
# Build libtailscale (tsnet as a C shared library) for Android and drop it into
# app/src/main/jniLibs/<abi>/. Needs Go 1.22+ and the NDK. Optional: without it
# the app builds without Tailscale support.
#
#   ./build-tailscale.sh                 # arm64-v8a + x86_64
#   ./build-tailscale.sh arm64-v8a       # one ABI
#   ./build-tailscale.sh armeabi-v7a     # 32-bit ARM, for the secondary APK
set -euo pipefail
cd "$(dirname "$0")"
command -v go >/dev/null || { echo "go not found" >&2; exit 1; }
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  ANDROID_NDK_HOME=$(ls -d "$ANDROID_HOME"/ndk/*/ 2>/dev/null | sort -V | tail -1); ANDROID_NDK_HOME=${ANDROID_NDK_HOME%/}
fi
TOOLS="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
SRC=build/libtailscale
if [ ! -d "$SRC" ]; then
  git clone --depth 1 https://github.com/tailscale/libtailscale "$SRC"
fi
# Our Android glue (interface list from Java) goes into the clone.
cp tailscale/android_ifaces.go "$SRC/"
ABIS=${*:-"arm64-v8a x86_64"}
for abi in $ABIS; do
  case $abi in
    arm64-v8a) goarch=arm64; goarm=""; cc=aarch64-linux-android26-clang ;;
    x86_64) goarch=amd64; goarm=""; cc=x86_64-linux-android26-clang ;;
    # 32-bit ARM. Go calls it arm and wants the ARM version spelled out; the
    # NDK spells the compiler armv7a-linux-androideabi, not arm-linux-android.
    armeabi-v7a) goarch=arm; goarm=7; cc=armv7a-linux-androideabi26-clang ;;
    *) echo "unsupported abi $abi" >&2; exit 2 ;;
  esac
  out="app/src/main/jniLibs/$abi"
  mkdir -p "$out"
  echo "building libtailscale for $abi"
  (cd "$SRC" && CGO_ENABLED=1 GOOS=android GOARCH=$goarch GOARM=$goarm CC="$TOOLS/$cc" \
     go build -trimpath -ldflags="-s -w" -buildmode=c-shared -o "../../$out/libtailscale.so" .)
  # The generated header carries the word size it was generated for, so only one
  # ABI gets to be the copy kept in the tree; otherwise whichever was built last
  # would decide whether GoInt is 32 or 64 bits. Nothing compiles it — the Rust
  # side declares the few functions it calls by hand — so it is reference only,
  # and reference is clearer when it does not move.
  [ "$abi" = arm64-v8a ] && cp "$out/libtailscale.h" crates/ffi/tailscale.h 2>/dev/null || true
  rm -f "$out/libtailscale.h"
  ls -la "$out/libtailscale.so"
done
echo "done — Gradle enables the 'tailscale' Rust feature when app/src/main/jniLibs/<abi>/libtailscale.so exists"
