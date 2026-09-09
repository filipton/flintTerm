# Backup and automation

How to get your data in and out, and how to drive a session from another app or a script.

## Backup

**Settings → Backup** writes everything — hosts, groups, accounts, keys, snippets, tunnels, proxies,
server keys, command history and settings — into one file sealed with a passphrase you choose, and
reads such a file back. Keep the file wherever you like: Drive, Nextcloud, Quick Share to the new
phone, an email to yourself. Nothing about it depends on this app's servers, because there are none.

A restore is a merge. What the file has is added; an entry this phone already has under the same id
takes the file's version; nothing here is deleted. A file made without secrets (the checkbox when
backing up) leaves the passwords and keys already on the phone alone. Keys held in the Android
keystore, or on a FIDO2 token, cannot be exported and are not in the file; a host that used one falls
back to a password after a restore on another phone, and says so in its editor.

The format is small enough to open without this app, which is the point of a backup:

```text
"ATVAULT1"   8 bytes   magic and format version
m_cost       u32 LE    Argon2id memory in KiB   (64 MiB when written by this app)
t_cost       u32 LE    Argon2id passes          (3)
p_cost       u32 LE    Argon2id lanes           (1)
salt         16 bytes
nonce        24 bytes
ciphertext   the rest  XChaCha20-Poly1305, tag last, with the whole header as associated data
```

The key is the 32-byte Argon2id output for the passphrase and salt; the plaintext is the app's own
JSON document with a `vault` block naming the device and date it was made on. A reader refuses a
header asking for more than 256 MiB, so a damaged or hostile file cannot become an out-of-memory
crash.

## Automation

Off until you turn it on: **Settings → Automation**. An app Android names then has to be allowed as
well: its first call is turned away and listed under the switch, where one tap allows it and another
withdraws it later. Not every caller is named — `adb` and some system senders arrive anonymous, and
Android below 14 names nobody at all — and those get through while the switch is on, which is what
Settings says on those versions. A call arriving while the switch is off is answered saying so rather
than ignored, and the last ten calls are listed with the app that made them.

| Action | Extras | What it does |
| --- | --- | --- |
| `dev.flint.term.action.CONNECT` | `host` | Opens a session and brings the app forward |
| `dev.flint.term.action.RUN` | `host`, `command` | Runs it headlessly and hands back the output |
| `dev.flint.term.action.SNIPPET` | `snippet`, optional `host` | Types the snippet into that host's session |
| `dev.flint.term.action.DISCONNECT` | optional `host` | Closes that host's session, or all of them |

They are ordered broadcasts, so the caller gets an answer. `host` is read forgivingly: the host's id,
its label, `user@hostname` as the app writes it, or a bare hostname — and a name that matches nothing,
or several hosts, comes back saying which. `snippet` is a snippet's name or its id; one carrying
`{{placeholders}}` is refused, since a broadcast has nobody standing by to answer them.

The result code is the command's exit status for `RUN`, and otherwise 0 for a call that did what it
says and 1 for one that did not; the result string is the command's output, or the sentence explaining
why there is none. Both also arrive as the `output` and `status` extras.

```sh
adb shell am broadcast -n dev.flint.term/.session.AutomationReceiver \
    -a dev.flint.term.action.RUN --es host web1 --es command "uptime"
```

`adb` is one of the callers Android does not name, so that line works as soon as the switch is on.

### Escape sequences the terminal answers to

The other direction: a program already running in the session speaks to the phone by printing bytes,
with no broadcast and no permission to arrange. The terminal lifts these out of the stream, so they
never reach the screen as gibberish.

| Sequence | What it does |
| --- | --- |
| `ESC ] 9 ; text BEL` | A notification with `text` as the body — the shortest one there is. `OSC 9;4`, ConEmu's progress report, is left alone |
| `ESC ] 777 ; notify ; title ; body BEL` | A notification with both halves, the way urxvt and its imitators write it |
| `ESC ] 99 ; i=1:p=title ; text ESC \` | kitty's, including `d=0` for a title or body sent in several chunks |
| `ESC ] 133 ; A BEL` | A new prompt is being drawn |
| `ESC ] 133 ; B BEL` | The prompt is drawn; what follows was typed |
| `ESC ] 133 ; C BEL` | The command is running and what follows is its output |
| `ESC ] 133 ; D ; status BEL` | The command finished, with its exit status when the shell reports one |
| `ESC ] 7 ; file://host/path BEL` | The directory the shell is now in; a bare `path` without the URL works too |

Either terminator works throughout: `BEL` (`\a`) or `ESC \` (the string terminator).

The three notification forms raise an Android notification on the alerts channel, and tapping it opens
that session. Like the bell, it is shown only while you are looking at something else — a notification
for the terminal in front of you is noise — and **Settings → Sessions & alerts → Let programs raise a
notification** turns the whole thing off. The text comes from the far end of an SSH connection, so it
is treated as text and nothing else: no markup, control characters flattened, and a body long enough
to be a document is cut short rather than pushed at the shade.

```sh
printf '\033]777;notify;Backup;12 GB in 41 minutes\a'   # title and body
printf '\033]9;Backup done\a'                            # body only
```

The `133` marks are what turn "a long command finished" from a guess into a fact. Without them the
notice is worked out by watching for the prompt coming back, which is a good guess and no more; with
them the shell says where the command started and ended, so the notice carries the real elapsed time
and the real exit status, and the guesswork is switched off for that session. Most shells can be asked
to emit them — `starship`, `oh-my-posh` and fish do it out of the box, bash and zsh with a few lines in
the prompt hooks — and nothing else has to change here.

They also say *where*, which is what makes a long session navigable. Two chevrons appear beside the
scroll indicator, and walk the view from one prompt to the previous or the next one however far up the
scrollback it is (**⋮ → View → Previous prompt** and **Next prompt** do the same thing); **⋮ → Type →
Copy the last output** selects everything between the last `C` and the `D` that ended it — or the
bottom of the output when the command is still running — and copies that, whether or not it fits on
the screen. A shell that sends no marks shows neither, because neither could do anything. Where a mark
is has to survive scrolling, a resize, `clear` and reflow, so it is not remembered as a line number:
the terminal puts a private hyperlink on the first cell the shell prints after the mark and reads the
position back out of the grid, exactly as an inline image is placed. Those hyperlinks are the app's
own and are never offered as a link to tap.

The `7` mark says where the shell is, and the app uses it in three places: the title and the tab show
the directory beside the host name (`~/projects/foo`, shortened against the home directory), the Files
tab opens in that directory instead of at the top of the tree, and **⋮ → New session here** opens a
second terminal on the same host that starts with a `cd` into it. Nothing to switch on here, but the
shell has to be asked — no shell sends it unprompted, which is why a terminal that never shows a
directory is a shell that was never told to, not a bug:

```sh
# bash, in ~/.bashrc
PROMPT_COMMAND='printf "\033]7;file://%s%s\a" "$HOSTNAME" "$PWD"'
# zsh, in ~/.zshrc
precmd() { printf '\033]7;file://%s%s\a' "$HOST" "$PWD" }
# fish, in ~/.config/fish/config.fish (fish ≥ 3.1 does this itself)
function osc7 --on-variable PWD; printf '\033]7;file://%s%s\a' (hostname) "$PWD"; end
```

Debian and Ubuntu already ship the bash line in `/etc/profile.d/vte.sh`, and starship, oh-my-posh and
fish send it out of the box — so on a good many machines this is already switched on. The path arrives
from the far end, so it is treated as text: control characters are dropped before it is drawn, a path
too long for a tab is cut from the left rather than allowed to push the strip off screen, and it is
quoted before it reaches the `cd` of a new session.

### Links into a session

`ssh://user@host/` connects to the matching saved host, or opens a prefilled editor when there is no
match. Adding `?tmux=session:window` attaches to that tmux window on the way in, and `?tmux=session`
to the session's current window — so a link can point at one pane among many.

```
ssh://pilif@box.example/?tmux=agents:2
```

### A worked example: an agent that can call you

Put the two together and a program on the server can reach the phone and land you in the pane it is
waiting in. Claude Code fires a `Notification` hook when it needs an answer; the hook posts to
[ntfy](https://ntfy.sh) with a `Click:` header holding the link, so the notification's one tap opens
the tmux window the agent is sitting in.

`~/.claude/settings.json` on the server:

```json
{
  "hooks": {
    "Notification": [
      { "hooks": [{ "type": "command", "command": "~/bin/notify-phone" }] }
    ]
  }
}
```

`~/bin/notify-phone`, which reads the hook's JSON on stdin:

```sh
#!/bin/sh
message=$(jq -r '.message // "Claude Code is waiting"')
pane=$(tmux display-message -p '#S:#I' 2>/dev/null || echo agents:0)
curl -s https://ntfy.sh/pilif-agents-8f2c \
     -H "Title: Claude Code on $(hostname -s)" \
     -H "Click: ssh://pilif@box.example/?tmux=$pane" \
     -d "$message"
```

ntfy delivers it whether or not the app is running or the session still exists, which is the point:
the phone can be anywhere. When the session *is* open and merely in the background, the same hook can
skip the round trip through the internet — `printf '\033]777;notify;Claude Code;%s\a' "$message" >
/dev/tty` reaches the same shade over the SSH connection itself. Pick a topic name nobody will guess
either way: anyone who knows an ntfy topic can read it.

[← back to the README](../README.md)
