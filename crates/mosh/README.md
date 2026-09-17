# mosh

An independent client implementation of mosh's State Synchronization Protocol,
speaking to the stock `mosh-server` (`MOSH_PROTOCOL_VERSION = 2`).

Written against the wire format rather than ported from mosh's C++, so it
carries this workspace's MIT/Apache license instead of mosh's GPL-3.

## Layers

| module      | what it does                                                     |
|-------------|------------------------------------------------------------------|
| `crypto`    | base64 session key, AES-128-OCB3, the direction/sequence nonce     |
| `packet`    | the two 16-bit timestamps every payload carries                    |
| `fragment`  | zlib, and cutting an instruction to fit the MTU                    |
| `proto`     | the protobuf messages, hand-coded (no `protoc` build step)         |
| `transport` | the state machine: what to send, what to ack, what to apply        |
| `predict`   | predictive local echo: guesses drawn over the screen, never fed in |

## The shortcut that makes this small

The host's terminal diff is **an escape-sequence stream, not a screen dump** —
`hoststring` is what mosh's own display layer emitted to repaint. So the bytes
go straight to whatever emulator the caller already has, and there is no
framebuffer, no screen diffing and no state to roll back in here.

That is also why the client keeps a single received state where mosh keeps a
list. An instruction is applied only when its `old_num` matches the state we are
on, and only applied states are acknowledged, so a server — which always diffs
from the last state it saw acknowledged — converges on its own. Loss costs a
retransmit, never a wrong screen.

## Testing

Unit tests cover each layer. The interop harness drives this client against a
real `mosh-server`, which is the only test that proves the wire format:

```sh
cargo test -p mosh                                    # unit tests
MOSH_SERVER=/usr/bin/mosh-server cargo test -p mosh   # + interop
```

Without a `mosh-server` the interop tests skip; with `MOSH_SERVER` set to
something that is not a file they fail rather than quietly pass.
