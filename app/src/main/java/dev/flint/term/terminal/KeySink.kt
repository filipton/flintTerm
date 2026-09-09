package dev.flint.term.terminal

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
        core?.sendKey(press)
    }

    override fun text(text: String) {
        core?.sendText(text)
    }

    override fun paste(text: String) {
        core?.paste(text)
    }
}
