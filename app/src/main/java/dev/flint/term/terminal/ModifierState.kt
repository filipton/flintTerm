package dev.flint.term.terminal

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ModState { OFF, ONCE, LOCKED }

data class Modifiers(val ctrl: ModState = ModState.OFF, val alt: ModState = ModState.OFF, val shift: ModState = ModState.OFF)

/**
 * The sticky modifiers behind the extra-keys caps, and the timing that turns a
 * quick second tap into a lock.
 *
 * A tap has to act the instant it lands — waiting to see whether another one
 * is coming would put a delay under every Ctrl anyone presses — so the lock is
 * decided afterwards: the tap that would have cleared the modifier locks it
 * instead when it falls inside the window. The clock is a parameter so that
 * window can be tested without living through it.
 */
class ModifierState(private val clock: () -> Long = SystemClock::uptimeMillis) {

    private val _value = MutableStateFlow(Modifiers())
    val flow: StateFlow<Modifiers> = _value
    val value: Modifiers get() = _value.value

    /** Whether the second tap locks; off, it clears as it always did. */
    var doubleTapLocks: Boolean = true

    /** When each modifier was last tapped into [ModState.ONCE]. */
    private val stuckAt = HashMap<Char, Long>()

    fun get(which: Char): ModState = value.let {
        when (which) {
            'c' -> it.ctrl
            'a' -> it.alt
            else -> it.shift
        }
    }

    fun set(which: Char, state: ModState) {
        _value.value = value.let {
            when (which) {
                'c' -> it.copy(ctrl = state)
                'a' -> it.copy(alt = state)
                else -> it.copy(shift = state)
            }
        }
    }

    /** A tap on the cap: off → once → off, or → locked when it was quick. */
    fun tap(which: Char) {
        val now = clock()
        val next = when (get(which)) {
            ModState.OFF -> ModState.ONCE
            ModState.ONCE -> {
                val quick = stuckAt[which]?.let { now - it <= DOUBLE_TAP_MS } == true
                if (doubleTapLocks && quick) ModState.LOCKED else ModState.OFF
            }
            // A locked modifier clears, whether the tap was quick or not.
            ModState.LOCKED -> ModState.OFF
        }
        if (next == ModState.ONCE) stuckAt[which] = now else stuckAt.remove(which)
        set(which, next)
    }

    /** A long press: lock it, or let a locked one go. */
    fun lock(which: Char) {
        stuckAt.remove(which)
        set(which, if (get(which) == ModState.LOCKED) ModState.OFF else ModState.LOCKED)
    }

    /** Spend the one-shot modifiers; a locked one stays. */
    fun consumeOnce() {
        _value.value = value.let {
            it.copy(
                ctrl = if (it.ctrl == ModState.ONCE) ModState.OFF else it.ctrl,
                alt = if (it.alt == ModState.ONCE) ModState.OFF else it.alt,
                shift = if (it.shift == ModState.ONCE) ModState.OFF else it.shift,
            )
        }
    }

    companion object {
        /** How long the second tap has to arrive in to mean "lock this". */
        const val DOUBLE_TAP_MS = 400L
    }
}
