package dev.flint.term.terminal

import android.view.KeyEvent
import dev.flint.term.data.KeyBinding

/**
 * Chords a hardware keyboard gives to the app instead of to the shell.
 *
 * A terminal is worth nothing if Ctrl+C, Ctrl+D or Ctrl+L stop arriving, so the
 * app only claims combinations no shell binds: Ctrl with Shift and a letter,
 * and Ctrl with Tab, a bracket or a digit. Everything else resolves to null and
 * travels on untouched.
 *
 * Which chord runs which action is the person's to change — [defaults] is only
 * where everyone starts — but the rule above is not, so [claimable] holds an
 * edited binding to exactly the ground the built-in table stands on, and holds
 * it in [resolve] rather than only in the editor: a chord that arrived from a
 * restored backup or an older version gets the same answer.
 */
object KeyShortcuts {

    enum class Shortcut(val label: String) {
        COPY("Copy"),
        PASTE("Paste"),
        NEW_SESSION("New session"),
        NEXT_TAB("Next session"),
        PREV_TAB("Previous session"),
        SEARCH("Search"),
        CLOSE_SESSION("Close session"),
        FONT_BIGGER("Bigger text"),
        FONT_SMALLER("Smaller text"),
        FONT_RESET("Reset text size"),
    }

    /**
     * The built-in table written out as bindings, for the editor to show and to
     * hand back edited.
     *
     * Several rows may carry one action: the aliases are what make Ctrl+Tab and
     * Ctrl+Shift+] the same move, and what lets zoom answer to every shape of
     * plus a keyboard has.
     */
    val defaults: List<KeyBinding> = listOf(
        KeyBinding(Shortcut.COPY.name, KeyEvent.KEYCODE_C, shift = true),
        KeyBinding(Shortcut.PASTE.name, KeyEvent.KEYCODE_V, shift = true),
        KeyBinding(Shortcut.NEW_SESSION.name, KeyEvent.KEYCODE_N, shift = true),
        KeyBinding(Shortcut.SEARCH.name, KeyEvent.KEYCODE_F, shift = true),
        KeyBinding(Shortcut.CLOSE_SESSION.name, KeyEvent.KEYCODE_W, shift = true),
        KeyBinding(Shortcut.NEXT_TAB.name, KeyEvent.KEYCODE_TAB),
        KeyBinding(Shortcut.PREV_TAB.name, KeyEvent.KEYCODE_TAB, shift = true),
        // The bracket pair is what a browser trains people to reach for.
        KeyBinding(Shortcut.NEXT_TAB.name, KeyEvent.KEYCODE_RIGHT_BRACKET, shift = true),
        KeyBinding(Shortcut.PREV_TAB.name, KeyEvent.KEYCODE_LEFT_BRACKET, shift = true),
        // "+" is Shift+= on most layouts, its own key on a few, and on the numpad.
        KeyBinding(Shortcut.FONT_BIGGER.name, KeyEvent.KEYCODE_EQUALS, shift = true),
        KeyBinding(Shortcut.FONT_BIGGER.name, KeyEvent.KEYCODE_PLUS, shift = true),
        KeyBinding(Shortcut.FONT_BIGGER.name, KeyEvent.KEYCODE_NUMPAD_ADD, shift = true),
        KeyBinding(Shortcut.FONT_SMALLER.name, KeyEvent.KEYCODE_MINUS, shift = true),
        KeyBinding(Shortcut.FONT_SMALLER.name, KeyEvent.KEYCODE_NUMPAD_SUBTRACT, shift = true),
        KeyBinding(Shortcut.FONT_RESET.name, KeyEvent.KEYCODE_0),
        KeyBinding(Shortcut.FONT_RESET.name, KeyEvent.KEYCODE_NUMPAD_0),
    )

    /** Built once, because [resolve] reads it on every key a hardware keyboard sends. */
    private val byName: Map<String, Shortcut> = Shortcut.entries.associateBy { it.name }

    /**
     * The chord's action, or null when the keys belong to the remote.
     *
     * [bindings] is `Settings.shortcuts`; an empty list means the built-in
     * table. Nothing here allocates — it runs once per key press, for an answer
     * that is nearly always "not ours".
     */
    fun resolve(
        keyCode: Int,
        ctrl: Boolean,
        shift: Boolean,
        alt: Boolean,
        bindings: List<KeyBinding> = emptyList(),
    ): Shortcut? {
        // Alt is the meta prefix a shell reads as ESC; a chord carrying it is not ours.
        if (!ctrl || alt) return null
        if (!claimable(keyCode, shift)) return null
        if (bindings.isEmpty()) return builtIn(keyCode, shift)
        for (i in bindings.indices) {
            val b = bindings[i]
            if (b.keyCode == keyCode && b.ctrl && b.shift == shift && !b.alt) {
                val hit = byName[b.action]
                if (hit != null) return hit
            }
        }
        return null
    }

    /**
     * Whether the app may take this chord at all.
     *
     * Ctrl with Shift is free: no shell binds it, and a terminal sends it only
     * when a program has asked for the kitty protocol. Ctrl on its own is the
     * shell's — that is Ctrl+C, Ctrl+D, Ctrl+L — apart from Tab and zero, which
     * carry no control character and which the built-in table has always
     * claimed.
     */
    fun claimable(keyCode: Int, shift: Boolean): Boolean = when {
        keyCode == KeyEvent.KEYCODE_UNKNOWN -> false
        shift -> true
        else -> keyCode == KeyEvent.KEYCODE_TAB || keyCode == KeyEvent.KEYCODE_0 || keyCode == KeyEvent.KEYCODE_NUMPAD_0
    }

    /**
     * Why this chord cannot be bound, said the way it would be said out loud,
     * or null when it is fine.
     *
     * The editor refuses on this rather than warning, because a chord that eats
     * Ctrl+C leaves a terminal no one can interrupt and no obvious way back.
     */
    fun problem(binding: KeyBinding): String? = when {
        binding.keyCode == KeyEvent.KEYCODE_UNKNOWN ->
            "Press a key as well as the modifiers."
        !binding.ctrl ->
            "A shortcut has to include Ctrl. Without it these keys are ordinary typing, and the terminal needs to receive them."
        binding.alt ->
            "Alt is the prefix a shell reads as Escape, so a chord holding it belongs to the remote."
        !claimable(binding.keyCode, binding.shift) ->
            "Ctrl and this key on their own is what the shell already uses, the way Ctrl+C stops a program. Hold Shift as well."
        else -> null
    }

    /** The chord shown for [action]: its first binding, or null when it has none. */
    fun binding(bindings: List<KeyBinding>, action: Shortcut): KeyBinding? =
        bindings.firstOrNull { it.action == action.name && it.keyCode != KeyEvent.KEYCODE_UNKNOWN }

    /** A chord written the way a keyboard has it printed: "Ctrl+Shift+C". */
    fun describe(binding: KeyBinding): String = buildString {
        if (binding.ctrl) append("Ctrl+")
        if (binding.alt) append("Alt+")
        if (binding.shift) append("Shift+")
        append(keyName(binding.keyCode))
    }

    private fun builtIn(keyCode: Int, shift: Boolean): Shortcut? {
        if (!shift) {
            return when (keyCode) {
                KeyEvent.KEYCODE_TAB -> Shortcut.NEXT_TAB
                KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> Shortcut.FONT_RESET
                else -> null
            }
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_C -> Shortcut.COPY
            KeyEvent.KEYCODE_V -> Shortcut.PASTE
            KeyEvent.KEYCODE_N -> Shortcut.NEW_SESSION
            KeyEvent.KEYCODE_F -> Shortcut.SEARCH
            KeyEvent.KEYCODE_W -> Shortcut.CLOSE_SESSION
            KeyEvent.KEYCODE_TAB -> Shortcut.PREV_TAB
            KeyEvent.KEYCODE_RIGHT_BRACKET -> Shortcut.NEXT_TAB
            KeyEvent.KEYCODE_LEFT_BRACKET -> Shortcut.PREV_TAB
            KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_NUMPAD_ADD -> Shortcut.FONT_BIGGER
            KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> Shortcut.FONT_SMALLER
            else -> null
        }
    }

    /**
     * The keys anyone is likely to bind are spelled out here; the platform's own
     * name, tidied, answers for the rest, since it is better to show
     * "MEDIA_PLAY" than a number.
     */
    private fun keyName(keyCode: Int): String = when (keyCode) {
        KeyEvent.KEYCODE_TAB -> "Tab"
        KeyEvent.KEYCODE_SPACE -> "Space"
        KeyEvent.KEYCODE_ENTER -> "Enter"
        KeyEvent.KEYCODE_ESCAPE -> "Esc"
        KeyEvent.KEYCODE_DEL -> "Backspace"
        KeyEvent.KEYCODE_FORWARD_DEL -> "Delete"
        KeyEvent.KEYCODE_LEFT_BRACKET -> "["
        KeyEvent.KEYCODE_RIGHT_BRACKET -> "]"
        KeyEvent.KEYCODE_BACKSLASH -> "\\"
        KeyEvent.KEYCODE_SEMICOLON -> ";"
        KeyEvent.KEYCODE_APOSTROPHE -> "'"
        KeyEvent.KEYCODE_GRAVE -> "`"
        KeyEvent.KEYCODE_COMMA -> ","
        KeyEvent.KEYCODE_PERIOD -> "."
        KeyEvent.KEYCODE_SLASH -> "/"
        KeyEvent.KEYCODE_EQUALS -> "="
        KeyEvent.KEYCODE_PLUS -> "+"
        KeyEvent.KEYCODE_MINUS -> "-"
        KeyEvent.KEYCODE_NUMPAD_ADD -> "Numpad +"
        KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> "Numpad -"
        KeyEvent.KEYCODE_NUMPAD_0 -> "Numpad 0"
        KeyEvent.KEYCODE_DPAD_UP -> "Up"
        KeyEvent.KEYCODE_DPAD_DOWN -> "Down"
        KeyEvent.KEYCODE_DPAD_LEFT -> "Left"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "Right"
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> ('0' + (keyCode - KeyEvent.KEYCODE_0)).toString()
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('A' + (keyCode - KeyEvent.KEYCODE_A)).toString()
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> "F" + (keyCode - KeyEvent.KEYCODE_F1 + 1)
        else -> KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
    }
}
