# Features

Everything the app does, at length. The short list is in the [README](../README.md).

### Connecting

- Password and key auth (Ed25519 / ECDSA / RSA generated on-device or imported), host-key
  verification with fingerprint prompt and known-hosts store, keepalives. **Accounts** hold a login
  (username plus key or password) that any number of hosts share, so a rotated key is edited once.
- **Groups** are folders that carry settings: a group can hand its members an account, a jump host, a
  VPN, a proxy, a color scheme and an accent. A host's own value always wins, so nothing it has set
  can change underneath it. Tap a heading on the host list to open the group. "Install SSH key" puts a
  key into the server's `authorized_keys` like `ssh-copy-id` and switches the host to it.
- Hosts have groups, icons and accent colors; search; long-press for actions; pin a host to the home
  screen; the four most recent hosts appear on the app icon's long-press menu.
- **The host editor is pages, not one long scroll**: name, address and credentials up front, then
  Route & VPN, On connect, Wake on LAN and Port forwarding as pages of their own, each summarized on
  the row that opens it. They are pages inside the editor rather than screens, so an unsaved draft
  survives moving between them.
- **Jump hosts** (`ssh -J` / ProxyJump): route a host through another saved host, chains allowed.
  Optionally run a command on the jump host first (e.g. a wake-on-LAN script) — its output shows in
  the connection log — and keep retrying the tunnel while the target boots.
- SOCKS5 / HTTP CONNECT proxies for the first hop; a command to run after login (`tmux attach`…).
- **Proxies are saved objects** kept with the tunnels and tailnets: set one up once on the VPN screen
  and pick it per host, instead of retyping the same corporate SOCKS5 into every host. Existing
  per-host proxies are converted on first load, one entry per distinct address.
- A host picks its route from one **VPN** list — direct, a WireGuard tunnel, or a Tailscale account —
  rather than a switch per kind, since it only ever uses one.
- **Several addresses per host, each with its own route**: a LAN address dialled directly, a tailnet
  name through Tailscale, a remote address through WireGuard. They are tried in order and the first
  that answers wins, so the same host works from the sofa and from a train without editing anything.
  Being turned away (bad password, rejected host key) stops the search — only silence moves on. Mosh
  and file access follow the address that answered, not the first one in the list. The order is the
  one you wrote, with one exception: a private address belonging to a network the phone is not on has
  nowhere to go and would only hold the connection up until it timed out, so it goes to the back of
  the queue. It is still dialled if nothing else answers, since a route this phone cannot see is
  still a route.
- **WireGuard without a system VPN**: paste a `wg-quick` config and route individual hosts through a
  userspace tunnel (boringtun + smoltcp inside the app). No VPN permission, no root, works next to a
  system VPN; DNS names resolve through the tunnel when the config lists a DNS server. By default the
  tunnel is used only when needed: a host on the phone's current network is dialled directly, other
  private addresses go through the tunnel, names and public addresses try direct first ("Always"
  forces the tunnel).
- **Tailscale** (optional build): embedded tsnet nodes — add as many accounts as you like on the
  VPN & tunnels screen (each is its own node, machine name and state directory, so a work tailnet and
  a home one sit side by side), join with a browser login or a pasted pre-auth key, then dial hosts by
  MagicDNS name; tailnet devices can be added as hosts with one tap, and "Leave tailnet" forgets both
  the node identity and the stored key. Needs Android 12 or newer (below that Android's seccomp policy
  blocks a syscall the Go runtime makes at startup); see `build-tailscale.sh` (adds ~22 MB per ABI).
- **Tunnels start on demand**: a tunnel or tailnet on the list means "may be used", not "stay up".
  It comes up for the connection that needs it and goes down again about 20 seconds after the last
  session using it ends, so nothing runs in the background between sessions. Connecting shows a
  spinner with what it is waiting for, and each row has a **Test** button — it brings the tunnel or
  node up, waits for a real handshake (or a real tailnet connection), reports how long that took, and
  leaves it as it found it. Several can be up at once: each session holds up the one it uses.
- **Diagnostics inside a VPN**: ping, a port check and a name lookup that run in the tunnel's own
  stack — which is the only place they mean anything, since a userspace tunnel is invisible to `ping`
  in a shell. A tailnet gets the port check, because its node speaks TCP and UDP rather than ICMP.
- **Use a host as a VPN**: pick "Use as VPN" on any SSH host and the whole phone's traffic leaves from
  that server — every TCP connection becomes a `direct-tcpip` channel and DNS is asked over TCP from
  the server, so internal names resolve to internal addresses and nothing has to be installed on the
  host (`crates/tun2ssh` is a userspace IP stack over Android's tun descriptor; it is sshuttle in your
  pocket). SSH carries no datagrams, so other UDP and ICMP are dropped: `ping` will not answer and
  QUIC falls back to TCP. The app itself stays outside the tunnel, since its own connection is what
  carries it.
- **Wake-on-LAN**: per host MAC + broadcast, aware of every address the host has — a magic packet is
  a link-local broadcast, so the LAN address decides where it goes even when the host is listed under
  a tailnet name first. With no address on any of the phone's networks, one packet goes out of every
  interface, because the limited 255.255.255.255 only leaves on the default route. "Auto" sends from the phone when it is on the machine's
  network and from the jump host otherwise (`wakeonlan`, python3, etherwake or bash fallback);
  automatically before connecting with retries while the machine boots, or by hand from the host menu.
  The magnifier in the MAC field reads the address off the machine over SSH. Leave the broadcast empty
  and it is worked out at send time — the directed broadcast of whichever interface reaches the target
  (192.168.1.255 rather than the blunt 255.255.255.255 some access points drop), on the phone or on the
  jump host.
- **What runs on connect in one place**: chosen snippets in order, then a command just for this host
  (no need to save a snippet for a one-off), then tmux. Environment variables are asked for on the
  same page, with an honest note that sshd only passes on what its `AcceptEnv` allows.
- **tmux that remembers where you were**: with "Back to the last session", reconnecting attaches to
  whichever tmux session was last used rather than always the configured one, so switching sessions on
  the host (`switch-client -p/-n`) is where you come back to.
- **Persistent sessions**: attach to tmux on login and reconnect + re-attach automatically when the
  link drops (with backoff), so running programs survive network changes.
- **Environment variables work better under Mosh than SSH**: `mosh-server` takes them as arguments and
  sets them itself, so there is no `AcceptEnv` to get past — the same variables an sshd would silently
  drop arrive intact. Names have to look like variable names; anything else is left out.
- **Hosts in Android's file picker**, per host and off by default (a switch in the host editor, so the
  picker does not fill up with servers you never browse). Turn it on and that host becomes a storage
  location: any app can open and save files on it through the system picker or the Files app — attach
  a remote file to a mail, open one in an editor, save a download straight to the server. Connections
  are made on demand and dropped when idle; a host whose key has never been confirmed in the app is
  refused rather than prompting where no one can answer.
- **The server's own login questions**, whichever they are: a one-time code after the password, a
  two-factor challenge after a key, an expired password to change. Each prompt appears as the server
  worded it, with the answer hidden unless the server said to show it, and it works over a headless
  connection too — so a snippet or a widget refresh that walks into a code has somewhere to ask.
- **What the server prints before login** is shown rather than swallowed: the policy notice, or the
  sign-in URL a Tailscale SSH check mode prints, with the links tappable. It sits above the connection
  steps while they run and stays reachable afterwards under ⋮ → "Server message". Switchable in
  Settings → Connections.
- **Quick connect**: type `user@host:port` or an `ssh://` URL into the host search field and a
  "Connect to …" row appears. It opens a session on a host that was never saved; ⋮ → "Save host" turns
  it into one, with the editor already filled in.
- **Hosts on this network**: servers announcing `_ssh._tcp` over mDNS appear under "Nearby" at the
  bottom of the host list, unless something saved already has that address. Tapping one opens the
  editor prefilled. Switchable in Settings → Connections.
- **Command palette**: the magnifier in the top bar — or Ctrl+Shift+P on a hardware keyboard, from
  anywhere including a terminal — opens one field that searches the whole app: hosts (connect, or
  browse their files from the same row), snippets (typed into the session you were last in),
  every settings row (jumping straight to the section that holds it) and the actions that are not
  settings — a new host, keys, groups, tunnels, backup, recordings, running one command on many
  hosts. Results are ranked with a heading per kind: a title that begins with what you typed first,
  then a subsequence of it, so `nrdgl` finds "Nerd Font glyphs", then the description underneath.
  The characters that matched are drawn bold, so it is obvious why a row is in the list. An empty
  field offers the hosts you connected to most recently. The settings index is a declared list in
  `ui/PaletteSearch.kt` — there is no reflection over Compose, so a new setting has to be added
  there to be findable.
- **Reachability dots**: beside a host with no session on it, a dim dot says something answered a
  plain TCP connect on its port, and a hollow ring says nothing did. Only the rows on screen are
  checked, with a two-second timeout, and an answer is kept for a couple of minutes; pulling the
  list down asks again. A host that is reached through a tunnel, a tailnet, a jump host, a proxy or
  a port knock is **not** probed — a direct connect from the phone would report "unreachable" for a
  machine that is perfectly fine — and neither is anything while the data saver is holding back on
  a metered connection. Those rows stay unknown, which is drawn as nothing at all.

### Beyond plain SSH

- **Mosh**: flip the switch on a host and the session runs over Mosh — `mosh-server` is started
  through the usual SSH connection (including your key, so nothing extra to set up), then SSH is
  dropped and the session continues over UDP, surviving roaming, sleep and address changes. The
  client is ours (`crates/mosh`), so output goes into the same emulator as SSH: scrollback, search,
  selection, per-host themes and link tapping all behave identically. Needs `mosh-server` on the
  host — found on `PATH` or in the usual off-`PATH` places — and UDP 60000–61000 open. It works
  **through a WireGuard tunnel** (which carries the UDP session as real datagrams, not wrapped in a
  stream), **through a jump host** (a small UDP relay is started on the hop, which needs python3
  there), **through a SOCKS5 proxy** that implements UDP ASSOCIATE, and **over Tailscale** — the
  embedded node's own loopback SOCKS5 proxy carries the datagrams, since `dial` would hand back a
  byte stream and lose datagram boundaries.
- **Mosh falls back to SSH**: the UDP path is proved before the SSH connection is let go, so a blocked
  60000-61000 range means a plain SSH session with a note, not a dead terminal.
- **Predictive echo** on Mosh: a keystroke is drawn immediately, underlined until the server confirms
  it, then it quietly becomes real text. A guess the server contradicts is dropped and predicting
  pauses for a moment, so a wrong one never sticks. On by default only once the round trip is long
  enough to notice; Settings → Terminal → Predictive echo can force it on or off.
- **Agent forwarding even under Mosh**: the forwarded agent belongs to an SSH channel, so one is held
  open beside the Mosh session and its socket handed to `mosh-server`; `ssh-add -l` on the host lists
  the phone's key, with signing still done on the phone. That held connection ends if the network
  changes, which the host editor says plainly.
- **Telnet** for the consoles that still speak it (switches, PDUs, BMCs): pick Telnet in the host
  editor and the auth fields step aside. Option negotiation covers remote echo, suppress-go-ahead,
  terminal type and window size, so full-screen tools and resizing work; jump hosts and proxies do
  not apply, but a WireGuard tunnel or Tailscale still does.
- **USB serial console** over OTG (CP210x, CH340/341, FTDI, PL2303, CDC-ACM): attached adapters show
  up under "This device" on the host list; pick a speed and line format (9600–921600, 8N1 by default)
  and you get a terminal on it. Unplugging ends the session.

### Keys and security

- **Post-quantum key exchange, first in the list**: every connection offers `mlkem768x25519-sha256`
  ahead of everything else, and the connection details say which one was agreed
  ("Connected · mlkem768x25519-sha256"). It matters because the attack is already happening: encrypted
  traffic recorded today can be kept until a quantum computer can open it, and only the key exchange
  decides whether that recording is worth keeping. The hybrid runs ML-KEM and x25519 together, so a
  weakness in either one leaves the other holding the session. Servers that do not have it — OpenSSH
  before 9.9 — fall back to curve25519 as before; nothing needs configuring at either end.
- **Keys that cannot be copied**: generate one inside the device's keystore (StrongBox where the
  hardware has it) and this app can sign with it but never read it — not into a backup, not into a
  sync. Such a key belongs to that phone, so each device gets its own and the server authorizes them
  all. Where the platform allows it, the key also refuses to work while the device is locked.
- **Hardware security keys**, over USB or NFC: an `sk-ssh-ed25519@openssh.com` or
  `sk-ecdsa-sha2-nistp256@openssh.com` key made on a FIDO2 token and used from it. The app speaks CTAP2
  to the token itself rather than going through WebAuthn, which would wrap the challenge in a
  clientDataJSON no SSH server can verify. The private key never leaves the token and every login asks
  for a touch. **A key with a PIN works**: the token is asked for its PIN before the touch, the prompt
  names the key and counts the attempts left after a wrong one, and a locked key is told plainly that
  only a factory reset will recover it. The PIN is never logged, never saved, and never held in a
  String, it lives in a char array that is wiped as soon as the token has been satisfied. It has not
  been tried against real hardware.
- **OpenSSH certificates**: paste the `…-cert-v01@openssh.com` line a CA issued and the key carries it
  from then on, for servers that trust a CA rather than a list of keys. It is read before anything
  relies on it — who it was issued to, the accounts it is good for, its serial, when it starts and
  stops being valid, and the CA's own fingerprint to check against the one you were told to expect.
  An expired certificate, one that certifies a different key, or a host certificate attached to an
  identity is refused here with the reason, rather than at the server with none. The two things that
  arrive by mistake, the plain public key and the private key beside it, are named as such.
- **SSH agent forwarding**: the phone keeps the keys and signs for the server, so a jump host can use
  your identity without a copy of it ever landing there.
- **A forwarded key asks before it signs**, which is `ssh-add -c` rather than plain `ssh -A`. The
  prompt names the host that asked, the key it wants and that key's fingerprint; nobody at the
  keyboard is a refusal after 45 seconds, so an unattended `git push` fails instead of hanging.
  Forwarding hands the far side your identity for as long as you are connected, and anyone with root
  there can use it — this is the switch that makes that visible. On by default, in
  Settings → Connections.
- Password managers fill the app's login fields: every username, password and passphrase field says
  what it is, so Bitwarden, 1Password or Google's own manager offers the right entry. Nothing filled
  is read or kept by the app beyond the field it landed in.

### The terminal

- Full xterm-256color emulation: colors, bold/italic/underline, alt screen, mouse reporting
  (tap/scroll in htop, btop, vim), bracketed paste, OSC title, OSC 52 clipboard.
- **Inline images**: the kitty graphics protocol and sixel, so `chafa`, `timg` and `kitty +kitten
  icat` draw real pictures. They scroll with the text, go when the line they sit on is cleared, and
  a session holds at most 64 MiB of them. Only direct transmission is spoken — a file path or a
  shared-memory handle on the host means nothing to a phone, so it is answered with an error rather
  than ignored. Through tmux it needs tmux 3.3 or newer with `allow-passthrough on`. Settings →
  Terminal → Inline images picks kitty, sixel, both or off, and a host can turn them off on its own.
- Long-press to select a word, drag the handles to adjust, floating Copy / Paste / Select all / Share.
- **Column select**: "Column select" on that same floating toolbar turns the selection into a
  rectangle — the same rows, cut at the same two columns on every one of them. That is how one column
  comes out of `docker ps` or `ls -l` with its alignment intact: the names without the ids, the sizes
  without the dates. The handles keep adjusting it afterwards. It sits on the toolbar rather than
  under a two-finger long press because a second finger on the terminal already means a pinch or the
  arrow-key drag, and a gesture nothing tells you about is a gesture nobody finds.
- **Paste a picture into the terminal.** A keyboard cannot type a PNG, so a screenshot or a GIF from
  Gboard's clipboard — and anything pasted or dropped as a file rather than as text — goes up to the
  host's upload folder instead and its path is typed at the cursor, quoted, once the bytes are there.
  Same for the **📎** cap and for a file dropped on the terminal: three ways in, one road.
- **An offer to paste what you just copied**: a strip above the keys, for a while after something is
  copied in another app, so the command in a browser or a chat is one tap away instead of a hunt for
  the paste cap. Only the clipboard's description is ever read, never its contents, so nothing is
  looked at until the offer is taken. It goes away by itself, and the whole thing can be turned off
  in Settings → Keyboard & input.
- Pinch to zoom the font; the grid re-flows live (more rows/cols for `btop`-style layouts).
- **A font size per host**, on the host editor's Terminal page, where the step below the smallest size
  is "Follow settings" — so the size and whether this host has an opinion at all are one control. Set
  it and the pinch, Ctrl+Shift+± and Ctrl+0 all write that host's size rather than the app's: a switch
  console with an 80x24 firmware terminal can be zoomed until it fits without the big server's `btop`
  going with it. Left on "Follow settings", every one of them changes the app setting as before.
- Scrollback with fling, scroll indicator, scroll-to-bottom on input.
- **Tap URLs and paths** in the terminal: they get a dotted underline, and a tap offers Open / Copy
  for a URL, or jumps straight to the file's folder in the SFTP browser for a path.
- Search in scrollback with highlighted hits; bell and per-host regex pattern notifications while the
  app is in the background; app lock with biometrics or the screen lock.
- **Share the scrollback**: ⋮ → View → "Share the scrollback" takes the whole buffer — history and
  screen — as plain text and offers it to another app, or saves it wherever the system file picker
  points. Colors and escape sequences are gone and the blank screen under the last command's output is
  trimmed, so what lands in an issue is what was actually printed. The text travels as a file through
  the same provider the recordings use, because a real scrollback is far too big for an intent extra.
- **Notifications a program asks for**: a script on the server raises one by printing an escape
  sequence (OSC 9, 99 or 777), and OSC 133 prompt marks make the "long command finished" notice carry
  the real duration and exit status. With `ssh://…?tmux=session:window` links, one tap lands in the
  pane that called (see [automation](automation.md)).
- **Completion from history**: the rest of the command you are typing appears in gray after the
  cursor (ranked by how often you run it, then how recently), Tab takes it, and pausing brings up the
  other matches under the cursor. Double-tapping the terminal sends Tab. The completion switches are
  in Settings → Terminal, the double tap in Settings → Keyboard & input.
- **Completion from what you have actually run**: commands are remembered per host and offered as
  chips above the keyboard as you type; tapping one finishes the line. A host's own
  `~/.zsh_history` / `~/.bash_history` can be imported from its menu, so the suggestions are useful
  from the first session. Which part of the line is "typed" is worked out by matching the line's
  suffixes against the history rather than guessing what the prompt looks like.

### Keyboard and input

- Hardware-keyboard chords the app answers instead of the shell: Ctrl+Shift+C/V copy and paste,
  Ctrl+Tab and Ctrl+Shift+[ / ] move along the tab strip, Ctrl+Shift+N a new session, Ctrl+Shift+F
  search, Ctrl+Shift+W close, Ctrl+Shift+± and Ctrl+0 zoom. Plain Ctrl+C, Ctrl+D and Ctrl+L still
  reach the remote.
- **Every one of those chords is editable**, in Settings → Keyboard & input → Shortcuts: tap an
  action, press the keys you want, and the row shows what it caught before anything is saved. A
  shortcut can also be cleared, and the whole set put back. What the editor will not do is take a
  chord the terminal needs — Ctrl and a letter on its own, or a combination without Ctrl at all — and
  it says which of the two it is rather than quietly refusing, because the alternative is a terminal
  that can no longer be interrupted and no obvious way to work out why. The refusal is enforced where
  the key is resolved as well as in the editor, so a binding restored from an older backup cannot
  smuggle Ctrl+C past it.
- **Kitty keyboard protocol and `modifyOtherKeys`**: when a program asks for them, Ctrl+[ arrives as
  itself instead of as Escape, and Shift+Enter, Ctrl+Shift+letter, auto-repeat and key releases
  become things a program can see at all. Editors (neovim, helix) and agent CLIs ask for it; through
  tmux it needs 3.3+ with `set -g extended-keys on`. Settings → Keyboard & input has the switch, and
  a host can override it. Switched off, the queries are swallowed, so a program never learns the
  protocol is there and keeps to legacy keys.
- **Ctrl+[, Ctrl+I and Ctrl+M are keys**, whether or not anybody asked. A program only gets the
  protocol by asking, and tmux decides whether it can ask from a list of terminal names neither this
  app nor ghostty is on — so `bind -n C-[` would never fire. These three are the ones
  [fixterms](https://www.leonerd.org.uk/hacks/fixterms/) says to send as keys rather than as the
  Escape, Tab and Enter bytes, and ghostty sends them that way unprompted, which is why such a
  binding works there. This does the same, so it works here with nothing added to a tmux config. The
  Escape, Tab and Enter keys still send their bytes, and Ctrl+H and Ctrl+J stay Backspace and line
  feed, because no key of their own sends those. Off in Settings → Keyboard & input, and a host can
  override it on the host editor's Terminal page.
- **Caps Lock as Escape or Control**, since it is the best-placed useless key on any keyboard:
  Settings → Keyboard & input → Hardware keyboard. As Escape it fires as the key goes down, as
  Control it is held for as long as the key is, and letters stay lowercase either way.
- Extra-keys bar above the soft keyboard: sticky/locked CTRL, ALT, SHIFT, ESC, TAB, arrows
  (hold to repeat), HOME/END/PGUP/PGDN, symbols, F1–F12. Hardware keyboards work too. A modifier cap
  holds for one key on a tap, locks on a long press or on a second tap inside 400 ms, and clears on
  the tap after that — the first tap still fires at once, so nothing feels delayed. Two more caps to add from
  the editor: **⇧⇥** sends Shift+Tab, and **📎** opens the file picker, sending what is picked to the
  host and typing the path where the cursor is.
- Configurable extra-keys bar, hidden when a hardware keyboard is attached; volume buttons bindable
  to any key, modifier (sticky, hold or toggle), snippet picker or text; Ctrl+Shift+C/V on hardware
  keyboards. Five presets — the default, one row, tmux, agents, vim — and from there: **drag a cap to
  move it**, with the four arrows moving as one block unless they are ungrouped; a second row that
  can be switched off; and a fixed **…** at the right of the first row that opens the rest as a pad
  over the keyboard, F keys and all, so two rows are rarely worth the screen.
- **Hold Ctrl and slide** onto a letter to send that chord in one gesture, rather than tapping Ctrl
  and then hunting for the letter. The strip that appears under your finger carries the ten a
  terminal actually uses — c, d, z, l, a, e, r, w, u, k — and lifting anywhere else sends nothing.
  It works the same on the key bar and on the app's own keyboard.
- **A keyboard of the app's own**, off by default, in Settings → Keyboard & input. Not an input
  method: it is drawn inside the app, types into the terminal alone, and asks for nothing in system
  settings. The layout is the one every Android keyboard already uses, down to the half-key indent
  on the home row, because a keyboard that moves the letters is one nobody can type on; the
  difference is the bottom row, where Ctrl sits where the emoji key was. Three pages — letters, then
  the symbols and, behind the 1/2 key a phone puts in the same place, the arrows and F1–F12. Every
  page opens with a strip of its own, so switching page never changes the keyboard's height. The
  system keyboard comes back on its own for the compose line, which is a real text field.
- **Compose a line**: **✎** on the key bar, or ⋮ → "Compose a line", opens a plain text field above the
  keys for the input that is a paragraph rather than a command — a prompt for an agent, a commit
  message, a block of YAML. It is an ordinary Android field with nothing switched off, so autocorrect,
  suggestions and voice typing all work and the third line can be fixed without retyping the first
  two. Send pastes the text (bracketed, when the program asked for it) and presses Enter; holding Send
  sends it without the Enter, for something still being thought about. The last 20 lines sent are kept
  per session and ↑ walks back through them, and the field keeps focus afterwards, so a second prompt
  does not need a second tap. Settings → Keyboard & input can leave it open, with what was in it,
  across visits to the same host.
- **Arrow keys by dragging**, so moving the cursor is not forty taps on the same cap: slide two
  fingers across the terminal and each cell they cross sends an arrow key, faster the further out you
  go (twice per cell past eight cells, four times past sixteen). Holding the fingers apart or
  together instead is still a pinch, decided the moment the gesture starts, and the drag works even
  where a program has asked for the mouse (htop, vim) — which is where it is wanted most. Settings →
  Keyboard & input → Gestures. The same thing on one thumb: add the **✥** cap from the extra-keys
  editor and drag it like a joystick.

### Sessions

- Multiple concurrent sessions kept alive by a foreground service; reconnect banner on disconnect.
- **Tabs**: with more than one session open, a thin strip of tabs sits under the header — status dot,
  name, close button — and a single session keeps the screen entirely to itself. A decisive sideways
  swipe moves along the strip; the bar is set high and the swipe has to be clearly sideways, because
  scrolling the scrollback is the gesture that matters. The sessions sheet still works for everything
  else.
- **Rename a session**: a long press on its tab (or ⋮ → Sessions → Rename) gives a session a name of
  its own, because four tabs called `web01` tell you nothing about which one has the build in it. The
  name belongs to the session, not to the host — the host list is untouched — and it lasts as long as
  the session does, including through a restore after Android has killed the app. Clearing the field
  puts the host's own name back.
- **Type in both panes**: with two terminals sharing the screen, ⋮ → "Type in both panes" sends every
  key, every line of text and every paste to the other one as well — the same thing run on a pair of
  servers, watched side by side instead of typed out twice. Both tabs wear a small **both** badge for
  as long as it is on, so nothing arrives anywhere unannounced, and closing the second pane turns it
  off again.
- **Connection details as a drawer, not console noise**: the steps live in a sheet that opens while
  connecting and gets out of the way once there is a shell — one row per address or hop, updating in
  place from "trying" to "connected", green for what worked, red for where it stopped. The terminal
  itself stays clean; the header and the "Session ended" banner reopen the drawer.
- **tmux-aware controls**: on a host that lives in tmux, a long press on the Ctrl key opens a sheet of
  chords — new window, next, previous, last, detach, the two splits, zoom, kill pane, copy mode and
  the ten window keys, a Ctrl tab for the letters a shell wants, and an agent tab with Claude Code's
  `/clear`, `/compact`, `/resume`, `/help`, Shift+Tab and Esc Esc. A sideways swipe sends the prefix
  and `n` or `p`, so the fling that changes session changes window instead. ⋮ → "tmux windows" lists
  what is running over there and switches to the one you tap, asked over the session's own connection
  so it costs no second login; a Mosh session has no channel to ask over, so the list is not offered
  there, though the chords still work — they are only keystrokes. Chords are written the way tmux,
  Emacs and the man pages write them (`C-b c`, `M-x`, `S-Tab`, `Esc Esc`), with the word `prefix`
  standing for the host's own tmux prefix, so a chord copied out of documentation works without
  translation; the whole set is editable in Settings → Keyboard & input → Chords.
- **The sessions that were open come back**: killed in the background, or restarted by the system,
  the app dials the same hosts again in the same tab order on the next cold start — through the normal
  path, so tmux reattaches and tunnels come up with them. A host that fails is left closed rather than
  retried, and a snackbar offers Undo. Switchable in Settings → Sessions & alerts.
- **Float the terminal**: ⋮ → "Float this terminal" puts the session in a small always-on-top window
  that keeps scrolling while you read documentation in the browser; tapping it comes back to the same
  session. It can do that by itself whenever you leave the app, which is off by default because
  otherwise every trip to another app leaves a window to dismiss.

### Files and transfers

- SFTP browser: list, download files or whole folders (via the system file picker), upload, mkdir,
  rename, delete, view text files, jump to an arbitrary path.
- **Copy straight to another host**: pick a file or folder in the SFTP browser, choose a destination
  host and a folder there, and the bytes stream host → phone → host through a pipe. Nothing is staged
  on the phone, the transfer joins the same queue (progress, speed, cancel, waiting for Wi-Fi), and a
  folder keeps going when one file will not copy.
- **Share to a host**: share files from any app into flintTerm, pick a host and a folder, done.
  **"Open in…" / "Edit in…"** hands a remote file to any app on the phone and uploads it back every
  time that app saves — whether it writes in place or saves atomically over the original.
- Transfers run in a foreground service, so they keep going when the app is in the background;
  the notification shows a progress bar, speed and ETA with a Cancel action, and the in-app panel
  mirrors it.
- Local, remote and **dynamic (`-D`) port forwards** per host, auto-start with the session, toggle at
  runtime. The SOCKS proxy speaks SOCKS5 (CONNECT to IPv4/IPv6/hostname) and SOCKS4/4a.
- **Edit a file on the host without leaving the app**: tapping a text file in the SFTP browser opens
  a built-in editor with syntax highlighting for a dozen languages, optional line numbers, a tab that
  inserts what the file type wants, an optional `.bak` before saving, and a guard on the way out if
  there are unsaved changes. Anything too large or not text still goes to another app, and Settings →
  Files decides which is the default.

### The server behind the session

- **Run one command on many hosts**: pick the servers, type it once, and each answer comes back next
  to its host — one refusing does not stop the rest. Each host gets its own connection, and it runs
  without a terminal, so nothing interactive.
- **Containers**, where the machine has them: the server pane finds whichever of `docker` or `podman`
  this login can actually list — asked once per session, not on every refresh — and shows what is on
  the host, grouped by compose project, each row carrying what it is doing, its image and ports, and
  its live CPU and memory. A tap offers start, restart, pause, unpause and stop; stopping asks first,
  because a container is somebody's service, and nothing here removes one. "Logs" opens that
  container's output as a tab of its own, read-only, keeping up as it is written. A machine with no
  runtime this login can talk to has no such section at all — there is no switch to find, because
  there would be nothing behind it.
- **The server behind the session**, as a pane of its own beside the terminal: load and per-core
  CPU, memory and swap, every mounted disk, the busiest interface, uptime and the heaviest processes,
  refreshed every five seconds while it is on screen and not at all when it is not. It runs one script
  over the connection the session already has, with a `sysctl`/`vm_stat` fallback for macOS and BSD.
  A home-screen widget shows load, memory and the fullest disk for one host on a 15, 30 or 60 minute
  refresh, holding off entirely on metered data when data saver says so.

### Appearance

- Dark-first UI (system / dark / light, optional Material You tint), a terminal color scheme **per
  host** or app-wide, adjustable scrollback and font size, vibrate on bell, keep screen on.
- **Six hundred color schemes**, bundled rather than fetched, each drawn as a small terminal running
  in it: Featured first, then every dark one and every light one alphabetically, behind a search box
  that collapses the lot into one ranked list as you type. A host or a group can override the app's
  choice, and the host editor shows the same picker in a sheet.
- **A scheme from another terminal**: import a Ghostty theme, an Alacritty `.toml` or `.yml`, a
  fragment of Windows Terminal's `settings.json`, an iTerm2 `.itermcolors` plist or an Xresources
  file, from a file or pasted as text. Which format it is comes from the content rather than the
  name, because these files have every extension and none, and one that is recognisably a scheme but
  short of colors is told what it lacks rather than refused as bad base64. Imported schemes live
  under Custom, where they can be renamed, shared back out as a Ghostty theme, or deleted.
- **Fonts**: JetBrains Mono, Fira Code or Hack, or import your own TTF/OTF; **ligatures** toggle for
  the families that have them.
- **Nerd Font glyphs**: the icons a starship prompt, `eza` or a powerline theme print come out as
  icons rather than tofu. Cells in the private-use planes (U+E000–F8FF, U+F0000–FFFFF) become runs of
  their own and are drawn with a bundled symbols-only font, which is why it works on every Android
  version and alongside a TTF you imported yourself — neither is true of a font fallback chain. One
  switch in **Settings → Appearance**.
- **Cursor and bold**: pick the cursor shape (block, underline or bar) and whether it blinks, and turn
  on bold-as-bright for palettes that expect it. A program that asks for a shape itself (DECSCUSR, the
  way vim marks insert mode) keeps the one it asked for — the preference is for the rest of the time.
  Blinking stops while keys are arriving, because a cursor that winks out from under your fingers is
  worse than one that never blinks, and stops again whenever the terminal is off screen, so an idle
  session is not waking the display twice a second.
- **Keyword highlighting**: rules that recolor what they match as the terminal draws it — the presets
  are the three every log has, errors red, warnings yellow, ok green — with a rule editor under
  **Settings → Appearance → Highlighting** where each rule has a pattern, a color, a whole-line switch
  and a test line showing what it would do. Rules are tried from the top and the first to claim a piece
  of a line keeps it, so reordering is how ties are settled. Nothing is written to the buffer: the
  scrollback keeps exactly what the server sent, so turning a rule off puts the original colors back.
  A pattern that will not compile is said so in the editor and skipped while drawing, never a crash.
  Off by default.
- **Session recording** to a plain text log and to an asciinema cast, with a **player built in**:
  Settings → Terminal → Recordings replays a cast into the app's own terminal with its colors and its
  timing, at 1×, 2× or 4×, with a scrub bar. A recording can be shared or deleted from the same list.

### Importing and moving data

- **Import from OpenSSH**: read `~/.ssh/config` into hosts (ProxyJump becomes a jump host, identity
  files match your stored keys) and `known_hosts` into trusted keys — hashed entries included, as
  Debian and Ubuntu write them; a hashed name cannot be read back out, so such a key is listed by its
  fingerprint and recognized when you connect to the server it was made for. `ssh://` and `sftp://`
  links connect to a matching host or open a prefilled editor.
- **Import from another app**: a **ConnectBot** export (its Export hosts JSON — nickname, address,
  port, user, color and the post-login command), a **PuTTY** `.ppk` private key (format 2 and 3,
  Argon2-encrypted or not, Ed25519 / ECDSA P-256 / RSA — converted to OpenSSH on the way in, with a
  wrong passphrase told apart from a damaged file), and a **CSV**: Termius's import template is read
  as it is, and any other spreadsheet gets a step for saying which column is the hostname, the user,
  the port and the label. All of it merges — a host you already have is counted ("12 hosts, 3 already
  here") and left exactly as it was.
- **Export as an OpenSSH config**: Settings → Backup writes the saved hosts as a `~/.ssh/config` another
  machine can use — `HostName`, `Port`, `User`, `IdentityFile`, `ProxyJump` from the jump host chain, the
  port forwards, `SetEnv`, `ForwardAgent`, a startup command as `RemoteCommand`, and `ServerAliveInterval`.
  What a host inherits from its group or its account is resolved first, so every block says what that host
  really connects with rather than what it happens to store. No password is written, ever; a key is named
  only by the path it would have there (`~/.ssh/<name>`), and a header at the top says which hosts want a
  key, which will ask for a password, and which sign with a keystore or security-key identity that cannot
  leave the device at all. Anything ssh has no word for — a WireGuard tunnel, a tailnet, Mosh, telnet,
  wake-on-LAN, a knock sequence, a saved proxy — becomes a comment above the host instead of a silent
  omission. Optionally, off by default and behind a confirmation that names them, the private keys this
  app can actually read are written into a folder you pick beside the config, so the pair works on arrival.
- **Data saver** for a spotty or metered link: file transfers wait for Wi-Fi (shown as such, and they
  start by themselves when it arrives) while typing is never held up, SSH keepalives are spaced out,
  a dropped session waits for connectivity instead of burning reconnect attempts against a dead
  network, and WireGuard stops re-initiating handshakes into a link that is not there. On mobile data
  by default.

### Automation and shortcuts

- **Home-screen widget**: the saved hosts, the one you were on last at the top, each row in that host's
  accent color and one tap from a shell. A host that already has a session is marked *open* and goes
  back to it instead of dialing a second one. Resizable, scrollable, readable in light and dark, and it
  redraws itself when a host is added, renamed or connected.
- **Automation**: Tasker, Automate, shortcut apps and `adb` can drive the client with ordered
  broadcasts, so a caller reads the answer off the result (see [automation](automation.md)).
- **Quick settings tile**: one pull-down to the host you were on last, which the tile names, without
  opening the app first. A connection needs the keystore, so on a locked phone it asks for the lock
  screen before it dials rather than failing quietly behind it.
- **Launcher shortcuts**: long-pressing the app icon offers the four hosts used most recently, each
  as a rounded tile in that host's accent color, straight into a shell. Any host can also be pinned
  to the home screen on its own from its menu.
- **Snippets** with `{{placeholders}}`, global or per host, one tap from the ✦ key in the terminal.


[← back to the README](../README.md)
