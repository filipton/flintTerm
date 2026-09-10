package dev.flint.term.session

/**
 * A command slow enough to have been walked away from has ended.
 *
 * How long it took, and — only when the shell marked its prompts with OSC 133 —
 * the status it ended with. The noticing happens in the core, next to the byte
 * stream; see `crates/ffi/src/command_watch.rs` for why it lives there.
 */
data class CommandFinished(val elapsedMillis: Long, val exitStatus: Int? = null)
