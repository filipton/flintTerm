package dev.flint.term.terminal

import dev.flint.term.core.KeyCode

/** One press of a chord: the key, and the modifiers held down with it. */
data class ChordPress(
    val code: KeyCode,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
)

/**
 * Chords written the way tmux, Emacs and the man pages write them.
 *
 * `C-b c`, `M-x`, `S-Tab`, `Esc Esc`: steps separated by spaces, each a key
 * with `C-`, `M-`, `S-` or `A-` in front of it (the spelled-out `Ctrl+`,
 * `Alt-`, `Shift+` work too). Nothing here needs translating from the
 * documentation it was copied out of, which is the whole point — the label on
 * a chord is ours, but the keys are the program's own notation.
 *
 * Two conveniences beyond that notation. The word `prefix` stands for the
 * host's tmux prefix, so the tmux tab reads like the tmux man page and a host
 * that rebound the prefix to `C-a` gets its own key without editing anything.
 * And a bare word that is not a key name is typed literally, which is how
 * `/clear Enter` sends a slash command; a word carrying a modifier gets no
 * such benefit of the doubt, because `C-Nope` is a mistake, not a sentence.
 */
object ChordParser {

    /** The presses [keys] means, or null when it means nothing we can send. */
    fun parse(keys: String, prefix: String = "C-b"): List<ChordPress>? =
        parse(keys, prefix, expandPrefix = true)

    private fun parse(keys: String, prefix: String, expandPrefix: Boolean): List<ChordPress>? {
        val steps = keys.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (steps.isEmpty()) return null
        val out = ArrayList<ChordPress>()
        for (step in steps) {
            if (expandPrefix && step.equals("prefix", ignoreCase = true)) {
                // A prefix that is itself rubbish would otherwise send half a
                // chord; the whole thing fails instead.
                out += parse(prefix, prefix, expandPrefix = false) ?: return null
                continue
            }
            out += press(step) ?: return null
        }
        return out
    }

    private fun press(step: String): List<ChordPress>? {
        var rest = step
        var ctrl = false
        var alt = false
        var shift = false
        var modified = false
        while (true) {
            val next = modifier(rest) ?: break
            when (next.first) {
                'c' -> ctrl = true
                'a' -> alt = true
                else -> shift = true
            }
            rest = next.second
            modified = true
        }
        if (rest.isEmpty()) return null
        named(rest)?.let { return listOf(ChordPress(it, ctrl, alt, shift)) }
        if (rest.length == 1) return listOf(ChordPress(char(rest[0]), ctrl, alt, shift))
        // Only an unmodified word can be text: `M-hello` is a typo for a key.
        if (modified) return null
        return rest.map { ChordPress(char(it)) }
    }

    /** Which modifier [token] starts with, and what is left after it. */
    private fun modifier(token: String): Pair<Char, String>? {
        // The separator alone is enough to call it a modifier: `C-` with
        // nothing after it is a broken chord, not the letter C and a dash.
        if (token.length > 1 && token[1] in SEPARATORS) {
            val which = when (token[0]) {
                'C' -> 'c'
                'M', 'A' -> 'a'
                'S' -> 's'
                else -> null
            }
            if (which != null) return which to token.substring(2)
        }
        for ((name, which) in LONG_MODIFIERS) {
            if (token.length > name.length && token[name.length] in SEPARATORS &&
                token.regionMatches(0, name, 0, name.length, ignoreCase = true)
            ) {
                return which to token.substring(name.length + 1)
            }
        }
        return null
    }

    private fun char(c: Char) = KeyCode.Char(c.code.toUInt())

    private fun named(name: String): KeyCode? {
        val lower = name.lowercase()
        NAMED[lower]?.let { return it }
        // F1 through F12, and nothing beyond: no terminal sends F13 without
        // being asked to, and a stray "f20" is more likely a typo.
        if (lower.length in 2..3 && lower[0] == 'f') {
            val n = lower.drop(1).toIntOrNull() ?: return null
            if (n in 1..12) return KeyCode.Function(n.toUByte())
        }
        return null
    }

    /** The alternatives tmux, Emacs and terminfo each prefer for the same key. */
    private val NAMED: Map<String, KeyCode> = buildMap {
        put("esc", KeyCode.Escape); put("escape", KeyCode.Escape)
        put("enter", KeyCode.Enter); put("return", KeyCode.Enter); put("cr", KeyCode.Enter)
        put("tab", KeyCode.Tab)
        put("space", KeyCode.Char(' '.code.toUInt()))
        put("bspace", KeyCode.Backspace); put("backspace", KeyCode.Backspace); put("bs", KeyCode.Backspace)
        put("up", KeyCode.Up); put("down", KeyCode.Down); put("left", KeyCode.Left); put("right", KeyCode.Right)
        put("home", KeyCode.Home); put("end", KeyCode.End)
        put("pgup", KeyCode.PageUp); put("pageup", KeyCode.PageUp); put("ppage", KeyCode.PageUp)
        put("pgdn", KeyCode.PageDown); put("pagedown", KeyCode.PageDown); put("npage", KeyCode.PageDown)
        put("ins", KeyCode.Insert); put("insert", KeyCode.Insert); put("ic", KeyCode.Insert)
        put("del", KeyCode.Delete); put("delete", KeyCode.Delete); put("dc", KeyCode.Delete)
    }

    private val LONG_MODIFIERS = listOf(
        "ctrl" to 'c', "control" to 'c', "shift" to 's', "alt" to 'a', "meta" to 'a',
    )

    private val SEPARATORS = charArrayOf('-', '+')

    private val WHITESPACE = Regex("\\s+")
}
