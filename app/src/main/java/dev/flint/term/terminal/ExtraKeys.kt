package dev.flint.term.terminal

import dev.flint.term.core.KeyCode

/**
 * Tokens for the configurable extra-keys bar. Known tokens map to keys or
 * actions; anything else is typed literally (so "ls -la" or "|" work).
 */
object ExtraKeys {
    sealed class Action {
        /** A key, optionally with modifiers baked into the cap (Shift+Tab). */
        data class Key(val code: KeyCode, val shift: Boolean = false, val ctrl: Boolean = false, val alt: Boolean = false) : Action()
        data class Modifier(val which: Char) : Action()
        data class Text(val text: String) : Action()
        data object Snippets : Action()
        data object Search : Action()
        data object ToggleKeyboard : Action()
        data object Paste : Action()
        data object InsertFile : Action()
        data object Compose : Action()
        /** A cap that is dragged rather than tapped; see [ArrowDrag]. */
        data object Nav : Action()
    }

    data class Def(val token: String, val label: String, val action: Action, val repeat: Boolean = false, val mono: Boolean = false)

    /** The four that move together unless somebody asks them not to. */
    val ARROWS = setOf("UP", "DOWN", "LEFT", "RIGHT")

    // Ctrl sits second because it is the modifier a shell needs constantly and
    // the one no soft keyboard has. Shift is not here: the keyboard already has
    // one, and a cap that duplicates it costs a column the arrows want.
    val defaultRow1 = listOf("ESC", "CTRL", "ALT", "TAB", "UP", "DOWN", "LEFT", "RIGHT")

    // Ordered by how hard the character is to reach otherwise: the punctuation a
    // phone keyboard hides behind two taps comes before the keys that are merely
    // convenient, and the function keys last of all.
    val defaultRow2 = listOf(
        "SNIPPETS", "PASTE", "STAB", "SHIFT", "SEARCH",
        "-", "_", "/", "|", "~", ":", ";", "'", "\"", "`", "\\",
        "HOME", "END", "PGUP", "PGDN", "DEL", "INS",
        "{", "}", "[", "]", "<", ">", "(", ")", "^", "#", "@", "$", "!", "&", "*", "+", "=", "%", "?",
    ) + (1..12).map { "F$it" }

    /**
     * A ready-made pair of rows.
     *
     * Building a bar a token at a time is fine once; most people want the set
     * that suits the thing they are about to run, so the editor offers a few
     * whole arrangements instead.
     */
    data class Preset(
        val name: String,
        val subtitle: String,
        val row1: List<String>,
        val row2: List<String>,
        /** How many rows this arrangement wants on screen; 1 leaves row 2 stored but hidden. */
        val rows: Int = 2,
    )

    val presets: List<Preset> = listOf(
        Preset("Default", "Modifiers and arrows, then the punctuation a phone buries", defaultRow1, defaultRow2),
        Preset(
            "One row", "The same first row, and half the height back",
            defaultRow1, defaultRow2, rows = 1,
        ),
        // tmux takes the prefix chord itself, and this app puts the rest of them
        // behind a long press on ctrl, so the bar carries what is left: paging
        // through copy mode, and the two characters that split a pane.
        Preset(
            "tmux", "Ctrl to hand, the paging keys, and the split characters",
            listOf("ESC", "CTRL", "TAB", "UP", "DOWN", "LEFT", "RIGHT"),
            listOf(
                "SNIPPETS", "PASTE", "SEARCH", "PGUP", "PGDN", "HOME", "END", "STAB", "DEL",
                "%", "\"", "-", "|", "/", ":", "~", "[", "]", "{", "}", "$", "!", "&", "*", "<", ">",
            ) + (1..12).map { "F$it" },
        ),
        // Coding agents are driven by prose, so composing a line off-screen and
        // handing over a file matter more here than punctuation does.
        Preset(
            "Agents", "Esc, compose and shift+tab for Claude Code and friends",
            listOf("ESC", "COMPOSE", "STAB", "CTRL", "UP", "DOWN", "LEFT", "RIGHT"),
            listOf("SNIPPETS", "FILE", "PASTE", "SEARCH", "TAB", "ENTER", "HOME", "END", "/", "@", "-", "_", "~", ":", "#", "\"", "'", "`", "*"),
        ),
        Preset(
            "vim", "Colon, slash and the paging keys",
            listOf("ESC", ":", "/", "CTRL", "UP", "DOWN", "LEFT", "RIGHT"),
            listOf("HOME", "END", "PGUP", "PGDN", "TAB", "DEL", "INS", "PASTE", "SEARCH", "$", "^", "%", "*", "~", "\"", "'", "-", "_", "{", "}", "[", "]"),
        ),
    )

    /** Everything selectable in the editor, in display order. */
    val catalog: List<Def> = listOf(
        Def("ESC", "esc", Action.Key(KeyCode.Escape)),
        Def("TAB", "tab", Action.Key(KeyCode.Tab)),
        Def("STAB", "⇧⇥", Action.Key(KeyCode.Tab, shift = true)),
        Def("CTRL", "ctrl", Action.Modifier('c')),
        Def("ALT", "alt", Action.Modifier('a')),
        Def("SHIFT", "shift", Action.Modifier('s')),
        Def("UP", "↑", Action.Key(KeyCode.Up), repeat = true),
        Def("DOWN", "↓", Action.Key(KeyCode.Down), repeat = true),
        Def("LEFT", "←", Action.Key(KeyCode.Left), repeat = true),
        Def("RIGHT", "→", Action.Key(KeyCode.Right), repeat = true),
        Def("HOME", "home", Action.Key(KeyCode.Home)),
        Def("END", "end", Action.Key(KeyCode.End)),
        Def("PGUP", "pgup", Action.Key(KeyCode.PageUp)),
        Def("PGDN", "pgdn", Action.Key(KeyCode.PageDown)),
        Def("DEL", "del", Action.Key(KeyCode.Delete)),
        Def("INS", "ins", Action.Key(KeyCode.Insert)),
        Def("BKSP", "⌫", Action.Key(KeyCode.Backspace), repeat = true),
        Def("ENTER", "⏎", Action.Key(KeyCode.Enter)),
        Def("SNIPPETS", "✦", Action.Snippets),
        Def("SEARCH", "🔍", Action.Search),
        Def("KEYBOARD", "⌨", Action.ToggleKeyboard),
        Def("PASTE", "paste", Action.Paste),
        Def("FILE", "📎", Action.InsertFile),
        Def("COMPOSE", "✎", Action.Compose),
        Def("NAV", "✥", Action.Nav, mono = true),
    ) + (1..12).map { Def("F$it", "F$it", Action.Key(KeyCode.Function(it.toUByte()))) }

    /**
     * The run of caps that move together when the one at [index] is moved.
     *
     * Only the arrows form a run, and only while [grouped]. Everything else is
     * itself alone, which is what makes one function serve both settings.
     */
    fun groupAt(tokens: List<String>, index: Int, grouped: Boolean): IntRange {
        if (!grouped || tokens.getOrNull(index) !in ARROWS) return index..index
        var start = index
        while (start > 0 && tokens[start - 1] in ARROWS) start--
        var end = index
        while (end < tokens.lastIndex && tokens[end + 1] in ARROWS) end++
        return start..end
    }

    /**
     * The row with the group holding [index] moved one place, and where the
     * selected cap ended up. Null when it is already at that end.
     *
     * A group steps over the whole of its neighbour rather than into the middle
     * of it, so moving the arrows past a group of arrows cannot interleave them.
     */
    fun moveGroup(tokens: List<String>, index: Int, right: Boolean, grouped: Boolean): Pair<List<String>, Int>? {
        val group = groupAt(tokens, index, grouped)
        val block = tokens.subList(group.first, group.last + 1).toList()
        val out = tokens.toMutableList()
        if (right) {
            if (group.last == tokens.lastIndex) return null
            val next = groupAt(tokens, group.last + 1, grouped)
            val width = next.last - next.first + 1
            repeat(block.size) { out.removeAt(group.first) }
            out.addAll(group.first + width, block)
            return out to index + width
        }
        if (group.first == 0) return null
        val previous = groupAt(tokens, group.first - 1, grouped)
        val width = previous.last - previous.first + 1
        repeat(block.size) { out.removeAt(group.first) }
        out.addAll(previous.first, block)
        return out to index - width
    }

    private val byToken = catalog.associateBy { it.token }

    fun resolve(token: String): Def = byToken[token] ?: Def(token, token, Action.Text(token), mono = token.length <= 2)
}
