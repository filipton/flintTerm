package dev.flint.term.session

/** The shell snippet that puts a session inside tmux on login. */
object Tmux {
    /**
     * Attach to tmux, optionally to whichever session was last used.
     *
     * With `resumeLast`, the most recently attached session wins instead of the
     * configured name — so switching sessions on the host (`switch-client -p/-n`)
     * is where you come back to, rather than being dropped back into the same
     * one every time. The configured name is still what gets created when the
     * server has no sessions at all.
     */
    fun attachCommand(session: String, resumeLast: Boolean): String {
        val name = session.ifBlank { "main" }.replace("'", "")
        val pick = if (resumeLast) {
            // Sorted by last-attached time; empty when tmux is not running yet.
            "S=$(tmux list-sessions -F '#{session_last_attached} #{session_name}' 2>/dev/null | sort -rn | head -1 | cut -d' ' -f2-); "
        } else {
            "S=''; "
        }
        return "command -v tmux >/dev/null 2>&1 && { " + pick +
            "exec tmux new -A -s \"\${S:-$name}\"; } || " +
            "echo 'tmux is not installed on this host, so the session will not persist'"
    }
}
