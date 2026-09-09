#!/usr/bin/env bash
# Build the APKs for a release: one per terminal engine, signed, ready to upload.
#
#   ./release.sh                    # one APK per engine, runs on anything -> dist/release-<version>/
#   ./release.sh --with-32bit       # and a 32-bit ARM APK per engine, for old phones
#   ./release.sh --abi arm64-v8a    # one ABI only: a third of the size, phones only
#   ./release.sh --alacritty-only   # skip the engine that needs Zig
#   ./release.sh --no-test          # don't run the unit tests first
#   ./release.sh --publish          # tag, push and put it on GitHub as a draft release
#   ./release.sh --publish --live   # ... published rather than a draft
#
# By default each APK carries the machine code for both 64-bit ABIs, so there is
# nothing for anyone to choose: Android installs the one slice that matches and
# ignores the other. arm64-v8a is every phone worth speaking of — 64-bit ARM has
# been the only thing shipping for a decade, and Play has refused 32-bit-only
# apps since 2019 — and x86_64 covers emulators and Intel Chromebooks.
#
# armeabi-v7a (32-bit ARM) is a file of its own rather than a third slice in the
# same APK, because it would add its own copy of everything — libtailscale alone
# is 20 MB — to every download, for the few old phones that still need it.
#
# It is a complete build, Tailscale included. What decides that is whether
# app/src/main/jniLibs/<abi>/libtailscale.so exists for every ABI in the build:
# Gradle compiles Tailscale out when any of them is missing, so a build mixing an
# ABI that has one with an ABI that does not silently loses it for both. Run
# ./build-tailscale.sh <abi> for anything new; this script says so if a build is
# about to go out without it.
#
# Leaves one directory holding everything a release page needs:
#
#   flintTerm-<version>.apk                 the default engine (alacritty)
#   flintTerm-<version>-ghostty.apk         libghostty-vt: faster, needs Zig to build
#   flintTerm-<version>-armeabi-v7a*.apk    with --with-32bit
#   SHA256SUMS                                one line per file, as `sha256sum -c` wants it
#   RELEASE.txt                               version, commit, ABIs, sizes, signing certificate
#
# --publish is the only part that leaves this machine. It refuses to run from a
# dirty tree, takes the release notes from the CHANGELOG section for this version,
# tags the commit, pushes it, and attaches every file to a GitHub release — as a
# draft, so nothing is public until you say so. --live skips the draft.
#
# Every APK is signed with the same key as every other build here (androidterm.jks).
# That key is what lets a phone update rather than reinstall, so it is checked and
# printed rather than assumed: compare it against the last release before uploading.
set -euo pipefail
cd "$(dirname "$0")"

# Both 64-bit ABIs in one file: see the note above on why 32-bit ARM is separate.
ABI=arm64-v8a,x86_64
ENGINES=(alacritty ghostty)
RUN_TESTS=1
WITH_32BIT=0
PUBLISH=0
DRAFT=--draft
while [ $# -gt 0 ]; do
  case "$1" in
    --abi) ABI="$2"; shift ;;
    --with-32bit) WITH_32BIT=1 ;;
    --alacritty-only) ENGINES=(alacritty) ;;
    --no-test) RUN_TESTS=0 ;;
    --publish) PUBLISH=1 ;;
    --live) DRAFT="" ;;
    -h|--help) sed -n 2,44p "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done
[ "$ABI" = all ] && ABI=arm64-v8a,x86_64

# Losing a feature to a missing library is exactly the kind of thing that ships
# unnoticed, so it is said out loud rather than left to whoever reads the Gradle
# file later.
check_tailscale() {
  local missing=""
  for abi in ${1//,/ }; do
    [ -f "app/src/main/jniLibs/$abi/libtailscale.so" ] || missing="$missing $abi"
  done
  [ -n "$missing" ] || return 0
  echo "warning: no libtailscale.so for$missing, so the $1 APK HAS NO EMBEDDED TAILSCALE." >&2
  echo "         every ABI in a build needs one. fix with: ./build-tailscale.sh$missing" >&2
}
check_tailscale "$ABI"
if [ "$WITH_32BIT" = 1 ]; then check_tailscale armeabi-v7a; fi

# Fail before a long build rather than half way through it.
for e in "${ENGINES[@]}"; do
  [ "$e" = ghostty ] && ! command -v zig >/dev/null && {
    echo "the ghostty engine is built from source and needs Zig 0.16+ on PATH." >&2
    echo "install it, or run: ./release.sh --alacritty-only" >&2
    exit 1
  }
done

die() { echo "$@" >&2; exit 1; }

version=$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts)

# What the build was cut from, for RELEASE.txt and for the publish check below.
# An `if` rather than `&&`, because a false test is a non-zero status and this
# script stops on those.
commit=$(git rev-parse --short=10 HEAD 2>/dev/null || echo unknown)
if [ -n "$(git status --porcelain 2>/dev/null)" ]; then dirty=" (dirty)"; else dirty=""; fi

# Everything that would stop a publish is checked before the build rather than
# after it: finding out that there is no remote at the end of twenty minutes of
# compiling is the same as not checking at all.
if [ "$PUBLISH" = 1 ]; then
  command -v gh >/dev/null || die "publishing needs the GitHub CLI (gh) on PATH"
  gh auth status >/dev/null 2>&1 || die "gh is not logged in: run 'gh auth login'"
  git remote get-url origin >/dev/null 2>&1 ||
    die "this repository has no 'origin' remote, so there is nothing to publish to.
create one and push first, e.g.:
  gh repo create android-term --private --source=. --remote=origin --push"
  [ -z "$dirty" ] || die "refusing to publish from a dirty tree: commit or stash first"
  ./tools/changelog.py --notes "$version" >/dev/null ||
    die "CHANGELOG.md has no section for $version.
write one, or generate it:  ./tools/changelog.py --update && ./tools/changelog.py --release $version"
  ! gh release view "v$version" >/dev/null 2>&1 ||
    die "a release v$version already exists. bump versionName in app/build.gradle.kts first"
fi

if [ "$RUN_TESTS" = 1 ]; then
  echo "==> unit tests"
  ./gradlew testDebugUnitTest --console=plain -q
fi

out="dist/release-$version"
rm -rf "$out"
mkdir -p "$out"

# build_one <abi-list> <engine>
build_one() {
  local abis="$1" engine="$2" suffix abiname
  echo "==> $engine, $abis"
  ./build-apk.sh --abi "$abis" --term "$engine"
  suffix=$([ "$engine" = alacritty ] && echo "" || echo "-$engine")
  # build-apk.sh names its output for a working tree; a release page wants the
  # version and what is inside, and nothing about how it was made. An APK holding
  # more than one ABI runs anywhere, so naming it after one would be a lie and
  # naming it after all of them is noise.
  case "$abis" in
    *,*) abiname="" ;;
    *) abiname="-$abis" ;;
  esac
  mv "dist/flintTerm-$version-${abis//,/+}$suffix-release.apk" \
     "$out/flintTerm-$version$abiname$suffix.apk"
}

for engine in "${ENGINES[@]}"; do
  build_one "$ABI" "$engine"
  if [ "$WITH_32BIT" = 1 ]; then build_one armeabi-v7a "$engine"; fi
done

# --- what is in the directory ---------------------------------------------------
apksigner=$(ls -d "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)
signer() {
  [ -x "${apksigner:-}" ] || { echo "unknown (apksigner not found)"; return; }
  "$apksigner" verify --print-certs "$1" 2>/dev/null |
    sed -n 's/.*certificate SHA-256 digest: \(.*\)/\1/p' | head -1
}

( cd "$out" && sha256sum *.apk > SHA256SUMS )

{
  echo "flintTerm $version"
  echo "commit $commit$dirty"
  echo "built $(date -u '+%Y-%m-%d %H:%M UTC')"
  echo "minSdk 26 (Android 8.0)"
  echo
  for f in "$out"/*.apk; do
    name=$(basename "$f")
    case "$name" in
      *-ghostty.apk) what="libghostty-vt — the VT engine out of Ghostty" ;;
      *) what="alacritty_terminal — the default engine" ;;
    esac
    case "$name" in
      *armeabi-v7a*) where="32-bit ARM only: for old phones that cannot run the other one" ;;
      *) where="any phone, and x86_64 emulators — carries ${ABI//,/ + }" ;;
    esac
    echo "$name"
    echo "  $what"
    echo "  $where"
    echo "  $(du -h "$f" | cut -f1)  ·  sha256 $(sha256sum "$f" | cut -c1-16)…"
    echo "  signing certificate SHA-256: $(signer "$f")"
  done
} > "$out/RELEASE.txt"

echo
cat "$out/RELEASE.txt"
echo
echo "everything to upload is in $out/"
echo "the signing certificate must match the last release, or phones cannot update in place."

if [ "$PUBLISH" != 1 ]; then
  echo
  echo "to publish it:  ./release.sh --publish"
  exit 0
fi

# --- publish --------------------------------------------------------------------
notes=$(mktemp)
trap 'rm -f "$notes"' EXIT
./tools/changelog.py --notes "$version" > "$notes"

if git rev-parse "v$version" >/dev/null 2>&1; then
  echo "==> tag v$version already exists, reusing it"
else
  echo "==> tagging v$version"
  git tag -a "v$version" -m "flintTerm $version"
fi
# The branch goes first: a tag whose commit is on no branch is a commit nobody
# can find their way to from the repository page.
git push origin "$(git branch --show-current)"
git push origin "v$version"

echo "==> creating the release${DRAFT:+ (draft)}"
# shellcheck disable=SC2086
gh release create "v$version" "$out"/*.apk "$out/SHA256SUMS" \
  --title "flintTerm $version" --notes-file "$notes" $DRAFT

echo
echo "done: $(gh release view "v$version" --json url --jq .url 2>/dev/null || echo "v$version")"
if [ -n "$DRAFT" ]; then
  echo "it is a draft — nothing is public until you press publish."
fi
