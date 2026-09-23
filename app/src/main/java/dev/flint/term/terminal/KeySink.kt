package dev.flint.term.terminal

import dev.flint.term.core.KeyCode
import dev.flint.term.core.KeyPress
import dev.flint.term.session.TerminalSession

/** Somewhere typing can land: one session, or a stand-in for one in a test. */
interface KeyTarget {
    fun key(press: KeyPress)
    fun text(text: String)
    fun paste(text: String)
}

/**
 * The far end of the send path: the session being typed into, and a second one
 * getting the same keys when typing is broadcast across a split.
 *
 * It sits outside the view because "does what I type reach both panes?" is the
 * whole of that feature, and a test that has to inflate a view to answer it is
 * a test nobody runs. Either end may be missing — a session is briefly gone
 * while a reconnect replaces it, and most of the time there is no mirror at all
 * — so both are written to as far as they exist and neither can stop the other.
 */
class KeySink(var primary: KeyTarget? = null, var mirror: KeyTarget? = null) {

    fun key(press: KeyPress) {
        primary?.key(press)
        mirror?.key(press)
    }

    fun text(text: String) {
        primary?.text(text)
        mirror?.text(text)
    }

    fun paste(text: String) {
        primary?.paste(text)
        mirror?.paste(text)
    }
}

/**
 * A live session as somewhere to type.
 *
 * A destroyed session quietly takes nothing: the core behind it is gone, and
 * its handle stays reachable only for as long as it takes the UI to notice.
 */
class SessionTarget(private val session: TerminalSession) : KeyTarget {
    private val core get() = session.takeIf { !it.destroyed }?.core

    override fun key(press: KeyPress) {
        if (!session.destroyed) session.sendKey(press)
    }

    override fun text(text: String) {
        core?.sendText(text)
    }

    override fun paste(text: String) {
        core?.paste(text)
    }
}

/**
 * A [KeyPress] as the numbers [FrameBridge.sendKey] takes.
 *
 * The key is numbered in the order [KeyCode]'s variants are declared on the
 * Rust side, which is where it is unpacked again; the modifier keys and the
 * event kind go by their own declaration order, which the bindings keep.
 */
internal object KeyPacking {
    fun key(code: KeyCode): Int = when (code) {
        is KeyCode.Char -> 0
        KeyCode.Enter -> 1
        KeyCode.Tab -> 2
        KeyCode.Backspace -> 3
        KeyCode.Escape -> 4
        KeyCode.Up -> 5
        KeyCode.Down -> 6
        KeyCode.Left -> 7
        KeyCode.Right -> 8
        KeyCode.Home -> 9
        KeyCode.End -> 10
        KeyCode.PageUp -> 11
        KeyCode.PageDown -> 12
        KeyCode.Insert -> 13
        KeyCode.Delete -> 14
        is KeyCode.Function -> 15
        is KeyCode.Modifier -> 16
    }

    fun arg(code: KeyCode): Int = when (code) {
        is KeyCode.Char -> code.codepoint.toInt()
        is KeyCode.Function -> code.n.toInt()
        is KeyCode.Modifier -> code.which.ordinal
        else -> 0
    }

    fun mods(press: KeyPress): Int =
        (if (press.ctrl) 1 else 0) or (if (press.alt) 2 else 0) or (if (press.shift) 4 else 0)
}
