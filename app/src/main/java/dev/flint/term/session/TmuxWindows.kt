package dev.flint.term.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One window of the tmux session on the other end. */
data class TmuxWindow(val index: Int, val name: String, val active: Boolean) {
    /**
     * Whether `prefix <index>` reaches this window.
     *
     * tmux binds the ten digits and nothing more, so a window numbered above
     * nine — which `base-index 1` and a busy day both produce — has to be
     * asked for by name instead.
     */
    val hasPrefixKey: Boolean get() = index in 0..9
}

/**
 * The window list of the tmux session a host is attached to.
 *
 * It is asked over the session's own connection, which costs one channel and
 * no second login; a Mosh session has no channel to lend, which is why the
 * list is not offered there at all. The chords still are — they are only
 * keystrokes, and they travel over the terminal like everything else.
 */
object TmuxWindows {

    /** Three fields, in the order the rest of this file reads them back. */
    const val COMMAND = "tmux list-windows -F '#{window_index}:#{window_name}:#{window_active}'"

    fun selectCommand(index: Int): String = "tmux select-window -t $index"

    suspend fun list(session: TerminalSession): List<TmuxWindow> =
        withContext(Dispatchers.IO) { parse(session.core.execLive(COMMAND)) }

    /** Ask tmux to switch, for the windows no prefix key can reach. */
    suspend fun select(session: TerminalSession, index: Int) {
        withContext(Dispatchers.IO) { session.core.execLive(selectCommand(index)) }
    }

    /**
     * `0:zsh:1` per window — read from both ends, because the middle field is
     * a window name and a window name may contain colons.
     *
     * Anything that does not have that shape is dropped rather than guessed
     * at: a login profile that greets the exec channel would otherwise arrive
     * as a window nobody can switch to.
     */
    fun parse(output: String): List<TmuxWindow> = output.lineSequence().mapNotNull { line ->
        val text = line.trim()
        if (text.count { it == ':' } < 2) return@mapNotNull null
        val index = text.substringBefore(':').toIntOrNull() ?: return@mapNotNull null
        val active = when (text.substringAfterLast(':')) {
            "1" -> true
            "0" -> false
            else -> return@mapNotNull null
        }
        val name = text.substringAfter(':').substringBeforeLast(':')
        TmuxWindow(index, name, active)
    }.toList()
}
