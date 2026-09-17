# Backup and automation

Getting your data in and out, and driving a session from another app, a script or the server itself.

- [Backup](#backup)
- [Broadcasts from other apps](#broadcasts-from-other-apps)
- [Escape sequences](#escape-sequences)
- [Links into a session](#links-into-a-session)
- [Example: an agent that can call you](#example-an-agent-that-can-call-you)

## Backup

**Settings → Backup** writes hosts, groups, accounts, keys, snippets, tunnels, proxies, server keys,
command history and settings into one file encrypted with a passphrase you choose. Store it
anywhere; the app has no servers of its own.

- **Restoring merges.** New entries are added, entries with the same id take the file's version, and
  nothing is deleted.
- **A backup without secrets** (the checkbox) leaves the passwords and keys already on the phone
  alone.
- **Keystore and FIDO2 keys** can't be exported. A host using one falls back to a password after a
  restore on another phone, and its editor says so.

The format is simple enough to decrypt without the app:

```text
"ATVAULT1"   8 bytes   magic and format version
m_cost       u32 LE    Argon2id memory in KiB   (64 MiB when written by this app)
t_cost       u32 LE    Argon2id passes          (3)
p_cost       u32 LE    Argon2id lanes           (1)
salt         16 bytes
nonce        24 bytes
ciphertext   the rest  XChaCha20-Poly1305, tag last, with the whole header as associated data
```

The key is the 32-byte Argon2id output. The plaintext is the app's JSON, with a `vault` block naming
the device and date. Headers asking for more than 256 MiB of memory are refused.

## Broadcasts from other apps

Off by default: **Settings → Automation**.

- **Named apps must be allowed.** An app's first call is refused and listed under the switch; one
  tap allows it.
- **Anonymous callers get through** while the switch is on. That's `adb`, some system senders, and
  every caller on Android below 14, which names nobody.
- The last ten calls are listed with the app that made them.

| Action | Extras | What it does |
| --- | --- | --- |
| `dev.flint.term.action.CONNECT` | `host` | Opens a session and brings the app forward |
| `dev.flint.term.action.RUN` | `host`, `command` | Runs it headlessly and returns the output |
| `dev.flint.term.action.SNIPPET` | `snippet`, optional `host` | Types the snippet into that host's session |
| `dev.flint.term.action.DISCONNECT` | optional `host` | Closes that host's session, or all of them |

- **`host`** can be the id, the label, `user@hostname` or a bare hostname. No match or several
  matches comes back as an error saying which.
- **`snippet`** is a name or id. Snippets with `{{placeholders}}` are refused, since nobody is there
  to fill them in.
- **The answer**: for `RUN`, the result code is the exit status and the result string is the output.
  Otherwise 0 means it worked, 1 means it didn't, and the string says why. Both are also in the
  `status` and `output` extras.

```sh
adb shell am broadcast -n dev.flint.term/.session.AutomationReceiver \
    -a dev.flint.term.action.RUN --es host web1 --es command "uptime"
```

## Escape sequences

A program running in the session can talk to the phone by printing these. They're removed from the
output, so they never show up on screen. End each with `BEL` (`\a`) or `ESC \`.

| Sequence | What it does |
| --- | --- |
| `ESC ] 9 ; text BEL` | Notification with `text` as the body. `OSC 9;4` (progress) is ignored |
| `ESC ] 777 ; notify ; title ; body BEL` | Notification with a title, urxvt style |
| `ESC ] 99 ; i=1:p=title ; text ESC \` | kitty's notification, including chunked `d=0` |
| `ESC ] 133 ; A BEL` | A prompt is being drawn |
| `ESC ] 133 ; B BEL` | The prompt is done; what follows is typed |
| `ESC ] 133 ; C BEL` | The command is running; what follows is output |
| `ESC ] 133 ; D ; status BEL` | The command finished, with its exit status |
| `ESC ] 7 ; file://host/path BEL` | The shell's current directory (a bare path works too) |

### Notifications (9, 99, 777)

```sh
printf '\033]777;notify;Backup;12 GB in 41 minutes\a'   # title and body
printf '\033]9;Backup done\a'                            # body only
```

- Shown only when you aren't looking at that session. Tapping one opens it.
- Treated as plain text: control characters are removed and long bodies are cut short.
- Turn them off in **Settings → Sessions & alerts → Let programs raise a notification**.

### Prompt marks (133)

`starship`, `oh-my-posh` and fish send these already; bash and zsh need a few lines in the prompt
hooks. With them:

- **"Command finished"** notifications show the real duration and exit status, instead of guessing
  from the prompt coming back.
- **Jump between prompts** with the chevrons beside the scroll indicator, or ⋮ → View → Previous
  prompt / Next prompt.
- **Copy the last output**: ⋮ → Type → Copy the last output, even if it's longer than the screen.

Marks stay in place through scrolling, resizing, `clear` and reflow.

### Working directory (7)

With it, the tab title shows the directory (`~/projects/foo`), the Files tab opens there, and **⋮ →
New session here** starts a second terminal in the same place.

No shell sends it unless asked. Debian and Ubuntu set it up for bash in `/etc/profile.d/vte.sh`, and
starship, oh-my-posh and fish 3.1+ send it themselves. Otherwise:

```sh
# bash, in ~/.bashrc
PROMPT_COMMAND='printf "\033]7;file://%s%s\a" "$HOSTNAME" "$PWD"'
# zsh, in ~/.zshrc
precmd() { printf '\033]7;file://%s%s\a' "$HOST" "$PWD" }
# fish, in ~/.config/fish/config.fish
function osc7 --on-variable PWD; printf '\033]7;file://%s%s\a' (hostname) "$PWD"; end
```

The path is treated as untrusted text: control characters are removed and it's quoted before being
used in a `cd`.

## Links into a session

`ssh://user@host/` connects to the matching saved host, or opens a prefilled editor if there isn't
one. Add `?tmux=session:window` to attach to that tmux window, or `?tmux=session` for the session's
current window.

```
ssh://pilif@box.example/?tmux=agents:2
```

## Example: an agent that can call you

Claude Code runs a `Notification` hook when it needs an answer. This hook posts to
[ntfy](https://ntfy.sh) with a link to the tmux window the agent is in, so tapping the notification
takes you straight there.

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

ntfy works even when the app isn't running. If the session is open in the background, you can skip
ntfy and print the notification straight to the terminal instead:

```sh
printf '\033]777;notify;Claude Code;%s\a' "$message" > /dev/tty
```

Pick an ntfy topic nobody will guess: anyone who knows it can read it.

[← back to the README](../README.md)
