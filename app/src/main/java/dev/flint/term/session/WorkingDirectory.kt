package dev.flint.term.session

/**
 * What the app does with the directory a shell reports over OSC 7.
 *
 * The path comes from the far end of a connection, so nothing here takes its
 * shape on trust: it is cut down before it is shown, quoted before it is typed,
 * and a path carrying what a command line cannot hold is not offered to one.
 */
object WorkingDirectory {
    /**
     * As much of a directory as a tab or a title will ever be asked to lay out.
     *
     * Not about what fits — Compose ellipsizes what does not — but about what
     * the far end can make us measure: a real path is a few hundred bytes, and
     * one that is not is a machine pushing the tab strip off the screen.
     */
    private const val DISPLAY_MAX = 120

    /** Longer than any shell will accept as one word, and further than we will type. */
    private const val COMMAND_MAX = 4096

    /**
     * [cwd] the way a tab or a title should carry it: under [home] it starts at
     * `~`, and it never runs longer than the strip can take.
     *
     * Null when there is nothing worth showing, which is the usual answer.
     */
    fun shorten(cwd: String?, home: String? = null): String? {
        if (cwd.isNullOrBlank()) return null
        // A newline or an escape in a folder name would be laid out as either a
        // second line or nothing at all; neither belongs on a one-line tab.
        val path = cwd.filterNot { it.isISOControl() }.trimEnd('/').ifEmpty { "/" }
        if (path.isBlank()) return null
        val under = home?.trimEnd('/')?.takeIf { it.isNotEmpty() && it != "/" }?.let { h ->
            when {
                path == h -> "~"
                path.startsWith("$h/") -> "~" + path.removePrefix(h)
                else -> null
            }
        }
        val shown = under ?: path
        // Cut from the left: the folder you are in is the part that says where
        // you are, and the root it hangs off is the part that can go.
        return if (shown.length <= DISPLAY_MAX) shown else "…" + shown.takeLast(DISPLAY_MAX - 1)
    }

    /**
     * Whether [path] can be typed at a shell at all.
     *
     * A control character in it would end the line early and leave the rest of
     * a remote machine's choosing to be run as a command of its own, so a path
     * with one is not offered rather than repaired.
     */
    fun isUsable(path: String?): Boolean =
        path != null && path.startsWith("/") && path.length <= COMMAND_MAX && path.none { it.isISOControl() }

    /** `cd` into [path], as a startup command for a session on the same host. */
    fun cdCommand(path: String): String = "cd " + quote(path)

    /**
     * POSIX single-quoting: inside them every character is itself, and the one
     * that cannot be — the quote — ends the string, is escaped, and reopens it.
     */
    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
