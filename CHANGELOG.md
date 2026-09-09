# Changelog

What changed in each release, newest first. Later versions are grouped from the commit log by
`tools/changelog.py` — `feat:` becomes Added, `fix:` becomes Fixed, and so on — so this file and
the history cannot drift apart. Versions follow [semantic versioning](https://semver.org).

## [Unreleased]

## [0.1.1] - 2026-09-09

### Added

- The app's keyboard pages the way a phone's does, every page the same height
- A strip offers to paste what was just copied in another app
- Ctrl+[, Ctrl+I and Ctrl+M go out as keys of their own even when no program asked
- The paste offer covers a picture copied elsewhere, not only text
- An extra address says for itself whether its VPN is used always or only when needed
- The extra addresses fold to a line each, reorder where they are listed, and start with the port above

### Fixed

- The key bar sits against the app's keyboard instead of a navigation bar's worth of nothing
- Back puts the app's keyboard away instead of leaving the session, and the system one stops coming back on top of it
- Dismissing the paste offer keeps it dismissed after leaving the session
- The history completion stays out of full-screen programs
- Both pages away from the letters have the same layout, so paging does not move the keys
- The system keyboard stays down while the app draws one of its own
- An address on a network the phone is not on is tried after the ones that can answer

### Documentation

- The feature list covers the keyboard pages, the paste offer and the three keys
- The address list says which address goes last and why
- The address list covers the per-address VPN mode, folding and reordering

## [0.1.0] - 2026-09-09

First release. Written by hand rather than generated, for two reasons: a first release is a
description of an app rather than a diff, and most of this one arrived in a single `wip: initial
project` commit of 142 files that no generator would have anything to say about.

### Added

#### Connecting

- **SSH** with key, password, keyboard-interactive and OpenSSH certificate authentication, answering
  the server's login questions a code at a time and showing what it prints before login.
- **Post-quantum key exchange** offered first, with the app naming the one that was agreed.
- **Mosh**, written for this app rather than wrapped: roaming, predictive echo, and bootstrapping
  over SSH, through a jump host, over WireGuard or through a SOCKS5 proxy.
- **Jump-host chains**, SOCKS5 and HTTP proxies, port knocking before a connection is dialed, and
  Wake-on-LAN from the phone or from a jump host.
- **WireGuard** in userspace and **Tailscale** embedded, both without root, with ping and port
  checks inside the tunnel.
- Any SSH host usable as a **VPN for the whole phone**, and its DNS asked over that host.
- **Telnet**, **USB serial** adapters, and a local shell on the phone itself.
- **Groups and accounts**: a folder of hosts sharing a jump host, a VPN, a proxy, a login or a theme.
- Hosts found on the network over **mDNS**, a quiet dot saying whether a host answers at all, and
  connecting to a host you never saved straight from the search field.
- **Import** from ConnectBot, PuTTY and CSV; **export** the saved hosts as an OpenSSH config.
- A **data saver** that holds transfers for Wi-Fi and eases off keepalives on a metered connection.

#### Keys

- Generated on the phone or inside the **Android Keystore**, imported from OpenSSH and PEM.
- **FIDO2 security keys** over USB and NFC, including PIN-protected ones, by speaking CTAP2.
- A fingerprint demanded before a signature, and a forwarded agent that asks first, naming the host
  and the key.
- **Replace a key everywhere it is used**, proving the new one works before removing the old.
- Host keys checked against `known_hosts`, hashed entries included, and a trusted-keys screen.
- Password managers can fill the login fields, because the fields say what they are.

#### Terminal

- A custom canvas renderer with no per-cell allocation on the draw path.
- The **kitty keyboard protocol**, so a program can tell `Ctrl+[` from Escape.
- **Inline images**: kitty graphics and sixel, placed by marking the cells they cover so they scroll
  and reflow with the text.
- **OSC 8** hyperlinks, **OSC 52** clipboard, **OSC 7** working directory, and **OSC 133** prompt
  marks — jump between prompts, copy the last command's output in one action.
- Scrollback search, block selection for one column of `ls -l`, and getting the whole buffer out to
  the share sheet or a file.
- **Predictive echo** over Mosh, drawn underlined until the server confirms it.
- Completion from that host's own shell history, ranked by how often and how recently a command was
  run, suggested where the cursor is.
- Keyword **highlighting rules**, Nerd Font glyphs, cursor shape and blink, and bold-as-bright.
- **604 color schemes** with search, and import from Ghostty, Alacritty, Windows Terminal, iTerm2 and
  Xresources files.

#### Sessions

- A tab strip, **two panes on one screen**, typing broadcast into both, and a session renamed from a
  long press on its tab.
- **tmux control mode**: the window list, a chords sheet, and a swipe that changes window.
- Sessions that survive being backgrounded, reopen after the app is killed, and **float in a small
  window** over other apps.
- **Recording** as a plain log or an asciinema cast, played back in the app.
- Notifications for the bell, for a long command finishing, and for anything a script raises with an
  escape sequence.
- **One command run across many hosts** at once, each answer beside its host.

#### Files

- **SFTP** as a tab of its own session, with background transfers, folder downloads and uploads.
- A file dropped on the terminal is sent to the host and its path typed; a picture from the keyboard
  goes up as a file.
- An **editor** in the app with highlighting, or the file handed to another app and watched for a
  save, optionally keeping a `.bak`.
- Copying files **straight from one host to another**, and hosts offered to the system Files app.

#### Input

- An **extra-key bar** above the keyboard: five presets, one row or two, caps you reorder by
  dragging, arrows that move as a block, and a fixed key that opens the rest as a pad.
- A **keyboard of the app's own**, off by default, laid out the way every phone keyboard is but with
  Ctrl where the emoji key was, and layers for symbols, function keys and arrows.
- **Ctrl by holding and sliding** onto a letter, which sends that chord in one gesture, from the bar
  and from the app's keyboard alike.
- **Chords rebound by pressing the keys**, hardware-keyboard shortcuts, Caps Lock as Escape or
  Control, a double tap that locks a modifier, and two fingers that walk the cursor.
- A **compose line** for the prompts that are a paragraph rather than a command.

#### Elsewhere

- A **command palette** over every host, snippet, setting and action, and a search over the settings.
- **Snippets** with placeholders that are asked for before typing.
- A **status pane** reading a host's load, memory, disks and processes, and a container list it can
  work.
- A **home-screen widget** for the host list and another watching one host's load, a quick-settings
  tile, shortcuts, and an interface other apps can drive over broadcasts.
- **App lock** behind a fingerprint, and a backup of everything sealed with a passphrase that
  restores as a merge.

### Build

- A Rust core reached through UniFFI: SSH, Mosh, WireGuard, the terminal emulator and the local pty.
- The terminal emulator is chosen at build time — `alacritty_terminal` by default, or libghostty-vt
  with `--term ghostty`, which measures faster on a device.
- `release.sh` builds the APKs a release page needs, one per engine, signed and checksummed, and can
  tag, push and upload them.
