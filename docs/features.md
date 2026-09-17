# Features

Everything the app does. The short list is in the [README](../README.md).

- [Connecting](#connecting)
- [Routes, VPNs and tunnels](#routes-vpns-and-tunnels)
- [Mosh, telnet and serial](#mosh-telnet-and-serial)
- [Keys and security](#keys-and-security)
- [The terminal](#the-terminal)
- [Keyboard and input](#keyboard-and-input)
- [Sessions and tmux](#sessions-and-tmux)
- [Files and transfers](#files-and-transfers)
- [The server behind the session](#the-server-behind-the-session)
- [Appearance](#appearance)
- [Importing and exporting](#importing-and-exporting)
- [Automation and shortcuts](#automation-and-shortcuts)

## Connecting

- **Auth**: password, keys (Ed25519, ECDSA or RSA, made on the phone or imported), and whatever the
  server asks during login: a one-time code, a 2FA challenge, an expired password to change.
- **Host keys** are checked against a known-hosts store, with a fingerprint prompt for new ones.
- **Accounts** are a username plus key or password that many hosts share, so a rotated key is
  changed in one place.
- **Groups** are folders that can hand their hosts an account, jump host, VPN, proxy, color scheme
  and accent. A host's own setting always wins.
- **Install SSH key** copies a key into the server's `authorized_keys`, like `ssh-copy-id`, and
  switches the host to it.
- **The host editor** is split into pages: the basics up front, then Route & VPN, On connect, Wake
  on LAN and Port forwarding. An unsaved draft survives moving between them.
- **On connect** runs chosen snippets, then a one-off command, then tmux. Environment variables go
  on the same page (sshd only passes what its `AcceptEnv` allows).
- **Quick connect**: type `user@host:port` or an `ssh://` URL into the host search. ⋮ → Save host
  keeps it.
- **Nearby**: servers announcing `_ssh._tcp` over mDNS show up at the bottom of the host list.
- **Reachability dots** beside each host: a dot if its port answered, a ring if not. Hosts behind a
  tunnel, tailnet, jump host, proxy or port knock aren't probed, since the answer would be wrong.
- **Server messages** shown before login (a policy notice, a Tailscale SSH sign-in link) stay
  reachable under ⋮ → Server message.
- **Command palette**: the magnifier in the top bar, or Ctrl+Shift+P. Searches hosts, snippets,
  every setting and app actions. Fuzzy, so `nrdgl` finds "Nerd Font glyphs".
- Search, icons, accent colors, long-press actions, and home-screen pins for hosts.

## Routes, VPNs and tunnels

- **Jump hosts** (`ssh -J`), chains allowed. Can run a command on the hop first, such as a wake
  script, and keep retrying while the target boots.
- **Proxies**: SOCKS5 and HTTP CONNECT for the first hop, saved once and picked per host.
- **Several addresses per host**, each with its own VPN, tried in order until one answers: a LAN
  address at home, a tailnet name elsewhere. A wrong password or host key stops the search; only
  silence moves on. A private address on a network the phone isn't on is tried last.
- **WireGuard** with no system VPN, no root and no VPN permission. Paste a `wg-quick` config and
  route individual hosts through it. By default it's only used when the host isn't reachable
  directly.
- **Tailscale** (optional build, Android 12+): as many tailnets as you like, each its own node. Join
  with a browser login or a pre-auth key, dial hosts by MagicDNS name, add tailnet devices as hosts
  in one tap.
- **Tunnels start on demand** and stop about 20 seconds after the last session using them ends. Each
  has a **Test** button that reports how long the handshake took.
- **Diagnostics inside a tunnel**: ping, port check and name lookup run in the tunnel itself, where
  a shell's `ping` can't reach.
- **Use a host as a VPN**: send all of the phone's TCP traffic out through any SSH server, with DNS
  resolved on the server. Nothing to install there. UDP and ICMP (so `ping` and QUIC) don't go
  through.
- **Wake-on-LAN** per host, sent from the phone or from the jump host, by hand or automatically
  before connecting. The broadcast address is worked out if left empty, and the MAC can be read off
  the machine over SSH.
- **Port forwarding**: local, remote and dynamic (`-D`, SOCKS5 and SOCKS4/4a), started with the
  session and switchable while it runs.
- **Data saver** on metered links: transfers wait for Wi-Fi, keepalives are spaced out, reconnects
  wait for a network. Typing is never held up. On for mobile data by default.

## Mosh, telnet and serial

- **Mosh**, our own client rather than a wrapper, so scrollback, search, themes and links behave as
  they do over SSH. Survives roaming and sleep. Needs `mosh-server` on the host and UDP 60000–61000
  open.
- Mosh works through **WireGuard**, a **jump host** (needs python3 there), a **SOCKS5** proxy with
  UDP ASSOCIATE, and **Tailscale**.
- **Falls back to SSH** if the UDP path is blocked, with a note saying so.
- **Predictive echo**: keystrokes appear immediately, underlined until the server confirms them. On
  when latency is high enough to notice; Settings → Terminal → Predictive echo forces it.
- **Agent forwarding and environment variables** work under Mosh too.
- **Telnet** for switches, PDUs and BMCs, with echo, terminal type and window-size negotiation.
  WireGuard and Tailscale still apply; jump hosts and proxies don't.
- **USB serial** over OTG (CP210x, CH340/341, FTDI, PL2303, CDC-ACM), listed under "This device".
  9600–921600 baud, 8N1 by default.

## Keys and security

- **Post-quantum key exchange** (`mlkem768x25519-sha256`) is offered first. Servers older than
  OpenSSH 9.9 fall back to curve25519. The agreed algorithm is shown in the connection details.
- **Keystore keys** live in the phone's hardware (StrongBox where there is one). The app can sign
  with them but never read them, so they can't be backed up or copied, and can refuse to work while
  the phone is locked.
- **FIDO2 security keys** over USB or NFC (`sk-ssh-ed25519` and `sk-ecdsa`), PIN included. Every
  login asks for a touch. Not yet tested against real hardware.
- **OpenSSH certificates**: paste the `-cert-v01` line and the key uses it. The app shows who it's
  for, its validity and the CA fingerprint, and refuses expired or mismatched ones up front.
- **Agent forwarding** that asks before each signature, like `ssh-add -c`, naming the host and key.
  No answer in 45 seconds is a refusal. Settings → Connections.
- **Password managers** (Bitwarden, 1Password, Google) can fill every login field.
- **App lock** with biometrics or the screen lock.
- **Block screenshots** in Settings → Security, which also hides the app in recents.

## The terminal

- **xterm-256color**: colors, bold/italic/underline, alternate screen, mouse reporting, bracketed
  paste, OSC title, OSC 52 clipboard. Settings → Terminal → Terminal type sets a different `TERM`.
- **Inline images** with the kitty graphics protocol and sixel (`chafa`, `timg`, `icat`). Through
  tmux it needs 3.3+ and `allow-passthrough on`. Settings → Terminal → Inline images.
- **Selection**: long-press a word, drag the handles, then Copy, Paste, Select all or Share.
  **Column select** on the same toolbar cuts a rectangle, for one column out of `ls -l`.
- **Pinch to zoom**; the grid reflows live. A **font size per host** on the host editor's Terminal
  page.
- **Scrollback** with fling and search, and a **scroll speed** in Settings → Keyboard & input →
  Gestures.
- **Links and paths** are underlined: tap a URL to open or copy it, a path to open its folder in
  SFTP.
- **Paste a picture**: a screenshot, GIF or file pasted or dropped on the terminal is uploaded to
  the host and its path typed at the cursor.
- **Paste offer**: for a while after you copy something in another app, a strip above the keys
  offers to paste it. Only the clipboard's description is read until you tap.
- **Share the scrollback** as plain text: ⋮ → View → Share the scrollback.
- **Notifications**: bell and per-host regex patterns while the app is in the background, and the
  ones a program raises (OSC 9, 99, 777). With OSC 133 prompt marks, "command finished" includes the
  duration and exit status.
- **History completion**: commands are remembered per host. The rest of the line shows in gray (Tab
  takes it) and other matches appear as chips. Shell history can be imported from the host's menu.
  Nothing is suggested or recorded inside full-screen programs.
- **Session recording** to text and asciinema, with a built-in player (1×, 2×, 4×, scrubbing) under
  Settings → Terminal → Recordings.
- **Redraw limit**, off by default: caps repaints during heavy output to save battery.
- **Battery page** under Settings → Battery lists the settings that cost power and links to each.

## Keyboard and input

- **Key bar** above the soft keyboard: sticky Ctrl, Alt and Shift, Esc, Tab, arrows, Home/End,
  PgUp/PgDn, symbols and F1–F12. A tap holds a modifier for one key; a long press or double tap
  locks it. Hidden when a hardware keyboard is attached.
- **Customize the bar**: five presets (default, one row, tmux, agents, vim), drag caps to move them,
  an optional second row, and a **…** pad for everything else. Extra caps include **⇧⇥**
  (Shift+Tab), **📎** (upload a file and type its path) and **✥** (a joystick for the arrows).
- **Hold Ctrl and slide** onto a letter to send that chord: c, d, z, l, a, e, r, w, u, k.
- **The app's own keyboard**, off by default: the usual Android layout with Ctrl where the emoji key
  is, plus pages of symbols, arrows and F-keys. Types only into the terminal. Settings → Keyboard &
  input.
- **Compose a line**: **✎** on the bar opens a normal text field with autocorrect and voice typing,
  for prompts and commit messages. Send pastes and presses Enter; hold Send to skip the Enter. ↑
  recalls the last 20.
- **Arrow keys by dragging** two fingers across the terminal; faster the further you go. Works
  inside htop and vim too.
- **Double tap** sends Tab. **Volume buttons** can be bound to a key, modifier, snippet or text.
- **Hardware keyboard shortcuts**: Ctrl+Shift+C/V copy and paste, Ctrl+Tab and Ctrl+Shift+[ ] switch
  tabs, Ctrl+Shift+N/F/W new, search and close, Ctrl+Shift+± and Ctrl+0 zoom. All editable in
  Settings → Keyboard & input → Shortcuts, which won't let you take a chord the shell needs, such as
  Ctrl+C.
- **Kitty keyboard protocol and `modifyOtherKeys`** for programs that ask (neovim, helix, agent
  CLIs), so Shift+Enter and Ctrl+Shift+letter reach them. Through tmux: 3.3+ with `extended-keys
  on`.
- **Ctrl+[, Ctrl+I and Ctrl+M** are sent as their own keys rather than Esc, Tab and Enter, as
  ghostty does, so `bind -n C-[` works in tmux. Can be switched off, per host too.
- **Caps Lock as Escape or Control**: Settings → Keyboard & input → Hardware keyboard.

## Sessions and tmux

- **Many sessions at once**, kept alive by a foreground service and reconnected with backoff when
  the link drops.
- **Tabs** appear once there's more than one session. Swipe sideways to switch; long-press a tab to
  rename it.
- **Sessions come back** after Android kills the app, in the same order, with tmux reattached.
  Settings → Sessions & alerts.
- **Type in both panes**: with two terminals on screen, ⋮ → Type in both panes sends every keystroke
  to both.
- **Connection details** open in a drawer while connecting, one row per address or hop, and close
  once there's a shell.
- **Float a terminal** in a small window over other apps: ⋮ → Float this terminal.
- **tmux on connect**, reattaching to the session you last used rather than a fixed one.
- **tmux controls**: long-press Ctrl for a sheet of chords (windows, splits, zoom, copy mode, Claude
  Code commands). A sideways swipe changes window. ⋮ → tmux windows lists and switches windows.
  Chords are editable in Settings → Keyboard & input → Chords.

## Files and transfers

- **SFTP browser**: browse, upload, download files and folders, create, rename, delete, jump to a
  path.
- **Built-in editor** for remote text files, with syntax highlighting, line numbers and an optional
  `.bak`. Settings → Files picks it or another app as the default.
- **Open in another app**: edit a remote file in any app on the phone; every save is uploaded back.
- **Copy between hosts**, streamed through the phone without storing it.
- **Share to a host** from any app: pick a host and a folder.
- **Background transfers** with progress, speed, ETA and cancel in the notification.
- **Hosts in Android's file picker**, switched on per host, so any app can open and save files on
  the server.

## The server behind the session

- **Server pane** beside the terminal: CPU per core, memory, swap, disks, network, uptime and top
  processes, refreshed every five seconds while visible. Linux, macOS and BSD.
- **Containers** (docker or podman): grouped by compose project with live CPU and memory. Start,
  stop, restart, pause, and open logs in a tab. Nothing gets removed.
- **Run one command on many hosts** and see each host's output beside it.
- **Server widget** on the home screen: load, memory and the fullest disk for one host, every 15, 30
  or 60 minutes.

## Appearance

- **Theme**: system, dark or light, with optional Material You colors.
- **604 color schemes** with live previews and search, set app-wide or per host or group.
- **Import schemes** from Ghostty, Alacritty, Windows Terminal, iTerm2 or Xresources, from a file or
  pasted. The format is detected from the contents.
- **Fonts**: JetBrains Mono, Fira Code, Hack or your own TTF/OTF, with ligatures.
- **Nerd Font glyphs** from a bundled symbols font, so starship and `eza` icons render. Settings →
  Appearance.
- **Cursor**: block, underline or bar, blinking or not. Bold-as-bright for palettes that expect it.
- **Keyword highlighting**: color matches (errors red, warnings yellow, ok green by default) without
  changing the scrollback. Settings → Appearance → Highlighting. Off by default.
- **Keep screen awake** and **vibrate on bell**, both optional.

## Importing and exporting

- **From OpenSSH**: `~/.ssh/config` becomes hosts (ProxyJump becomes a jump host) and `known_hosts`
  becomes trusted keys, hashed entries included.
- **From other apps**: ConnectBot's JSON export, PuTTY `.ppk` keys (versions 2 and 3), and CSV
  (Termius's template directly, anything else with column mapping). Existing hosts are left alone.
- **Export as an OpenSSH config** from Settings → Backup, with inherited settings resolved.
  Passwords are never written. Anything ssh can't express (WireGuard, Mosh, Wake-on-LAN…) is noted
  as a comment. Readable private keys can optionally be exported next to it.
- **Encrypted backup** of everything: see [backup and automation](automation.md).

## Automation and shortcuts

- **Host widget**: saved hosts with the last one first; a tap connects or returns to an open
  session.
- **Quick settings tile** that connects to the last host.
- **Launcher shortcuts**: long-press the app icon for the four most recent hosts.
- **Snippets** with `{{placeholders}}`, global or per host, from the ✦ key.
- **`ssh://` and `sftp://` links** connect to a matching host or open a prefilled editor.
  `ssh://…?tmux=session:window` lands in a specific pane.
- **Tasker, Automate and `adb`** can drive the app with broadcasts: see [backup and
  automation](automation.md).

[← back to the README](../README.md)
