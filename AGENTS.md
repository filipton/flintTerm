# Working in this repo

flintTerm is an Android SSH terminal: a Kotlin/Compose app over a Rust core, bridged by uniffi.
This file is the house style for anyone working here, human or agent. `CLAUDE.md` points at it, so
there is one copy rather than one per tool.

## Where things are

```
app/            the Android app: Compose screens in ui/, the Canvas grid in terminal/
crates/         the Rust core: ssh-core, term-core and the two VT backends, wg, pty, ffi
tools/          changelog.py, gen-schemes.py
docs/           the long-form documentation the README links to
```

`docs/building.md` has the full build, test and release story. The short version:

```sh
./gradlew :app:assembleDebug -PrustTargets=x86_64   # fast build for an emulator
./gradlew :app:testDebugUnitTest                    # the Kotlin tests
cargo test                                          # the Rust tests
./build-apk.sh --install                            # a signed build on a phone
```

Run both test suites before committing. They are fast and they are the only thing standing between
a refactor and a broken session.

## Commit messages

One line. That line is the whole message unless something genuinely cannot be said in it.

```
<type>: <what is different now>
```

`type` is one of `feat`, `fix`, `perf`, `refactor`, `docs`, `build`, `test`, `chore`. It is not
decoration: `tools/changelog.py` groups the log into `CHANGELOG.md` by it, so a `feat` that should
have been a `fix` lands in the wrong section of the release notes.

Write the subject as plain engineering English, describing the app as it behaves after the change.
Lowercase after the colon, no full stop, and keep it short — aim for well under 60 characters. What
the reader wants is what changed for them, not which function was edited.

One commit per piece of work, not per file. A change that touches the Rust core and the Kotlin that
calls it is one commit; so is a set of fixes that share a reason. Splitting them makes the log
longer without making it say more.

```
fix: Ctrl+C interrupts from the key bar too, not only from the menu
feat: hold Ctrl and slide onto a letter to send that chord in one gesture
fix: every screen comes back where you left it, and a subpage opens at its top
perf: the grid is packed once per frame instead of once per cell
```

Not this:

```
fix: bug fixes            # which bug, and what does it do now
feat: add SoftKeys.kt     # a file is not a feature
fix: fixed the thing in TerminalScreen.kt where the keyboard was wrong because
                          # the reader cannot skim it, and the file name will move
```

A body is for the rare commit that needs a reason nobody can infer: why an obvious approach was
rejected, or what a subtle change protects against. Two or three sentences, wrapped at 100 columns.
Never a bullet list of the diff.

No attribution trailers. No `Co-Authored-By`, no session links, no "generated with". The commit log
says what changed, not who or what typed it.

## Code

Comments explain why, in full sentences, and are worth writing when the reason is not on the screen:
a protocol quirk, a platform bug, a decision that looks wrong until you know what it avoids. A
comment restating the line under it is noise.

Match the file you are in. The Kotlin is Compose-idiomatic and the Rust is plain and allocation-shy
on hot paths, and neither is the place for a new set of conventions.

Cover new behaviour with a test where behaviour can be tested off a device. Anything the terminal
encodes, parses or stores has a test next to it already.

## Do not

- Commit `dist/`, `*.jks`, `keystore.properties` or anything else in `.gitignore`. The signing key
  is the app's identity; a release signed with a different one cannot install over the old app.
- Hand-edit the generated part of `CHANGELOG.md`. Run `tools/changelog.py --update` and edit the
  draft it writes.
- Bump `versionName` outside cutting a release. `release.sh` and the tag have to agree. The commit
  that does it is a `chore:`, so the version number does not turn up as a line in its own release
  notes.
- Add a dependency without a reason that survives the question "what does this do that we cannot".
  The app ships the licence of everything it links, listed in `NOTICE` and in the app itself.
