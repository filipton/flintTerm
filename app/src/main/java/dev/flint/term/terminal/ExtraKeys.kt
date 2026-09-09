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

    val defaultRow1 = listOf("ESC", "TAB", "CTRL", "ALT", "SHIFT", "UP", "DOWN", "LEFT", "RIGHT")
    val defaultRow2 = listOf("SNIPPETS", "STAB", "HOME", "END", "PGUP", "PGDN", "DEL", "INS", "-", "/", "|", "~", ":", ";", "`", "'", "\"", "\\", "&", "*", "$", "!", "{", "}", "[", "]", "<", ">", "^", "#", "@", "=", "+") + (1..12).map { "F$it" }

    /**
     * A ready-made pair of rows.
     *
     * Building a bar a token at a time is fine once; most people want the set
     * that suits the thing they are about to run, so the editor offers a few
     * whole arrangements instead.
     */
    data class Preset(val name: String, val subtitle: String, val row1: List<String>, val row2: List<String>)

    val presets: List<Preset> = listOf(
        Preset("Default", "What a fresh install has", defaultRow1, defaultRow2),
        // The tmux prefix chords live behind a held ctrl, so the bar only has
        // to carry the keys tmux itself does not intercept.
        Preset(
            "tmux", "Hold ctrl for the prefix chords",
            listOf("ESC", "TAB", "CTRL", "ALT", "UP", "DOWN", "LEFT", "RIGHT"),
            listOf("SNIPPETS", "STAB", "SEARCH", "PASTE", "HOME", "END", "PGUP", "PGDN", "DEL", "|", "-", "~", ":", "/"),
        ),
        // Coding agents are driven by prose, so composing a line off-screen and
        // handing over a file matter more here than punctuation does.
        Preset(
            "Agents", "Esc, compose and shift+tab for Claude Code and friends",
            listOf("ESC", "COMPOSE", "STAB", "CTRL", "UP", "DOWN", "LEFT", "RIGHT"),
            listOf("SNIPPETS", "FILE", "PASTE", "SEARCH", "TAB", "ENTER", "HOME", "END", "/", "@", "-", "~"),
        ),
        Preset(
            "vim", "Colon, slash and the paging keys",
            listOf("ESC", ":", "/", "CTRL", "UP", "DOWN", "LEFT", "RIGHT"),
            listOf("HOME", "END", "PGUP", "PGDN", "TAB", "DEL", "INS", "PASTE", "SEARCH", "$", "^", "%", "*", "~", "\"", "'", "-"),
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

    private val byToken = catalog.associateBy { it.token }

    fun resolve(token: String): Def = byToken[token] ?: Def(token, token, Action.Text(token), mono = token.length <= 2)
}
