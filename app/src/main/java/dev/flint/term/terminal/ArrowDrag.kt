package dev.flint.term.terminal

import dev.flint.term.core.KeyCode
import kotlin.math.abs

/**
 * A finger sliding across the screen, turned into arrow keys.
 *
 * Moving the cursor on a phone means poking the same little cap forty times,
 * and the arrows are the one thing a terminal person does constantly. So a
 * drag walks the cursor instead: every cell width the finger crosses is one
 * Left or Right, every cell height one Up or Down, which makes the cursor
 * follow the finger at the same speed the text scrolls under it.
 *
 * Far from where the drag started that gets slow — a phone is only so wide —
 * so the drag has gears: past [GEAR_2_CELLS] cells out each crossing sends the
 * key twice, past [GEAR_3_CELLS] four times. The gear is read per axis from
 * how far out the finger is on that axis, so a long sideways drag speeds up
 * without a stray wobble up or down running away with it.
 *
 * It holds no Android types and allocates nothing once started: this runs on
 * every finger movement, and it has to be testable without a screen.
 */
class ArrowDrag {

    /** Where the keys go. A `fun interface` so the caller can hold one and reuse it. */
    fun interface Emit {
        fun key(code: KeyCode, times: Int)
    }

    private var originX = 0f
    private var originY = 0f
    private var cellW = 1f
    private var cellH = 1f

    /** Cells already paid out, signed, one count per axis. */
    private var sentX = 0
    private var sentY = 0

    /** Begin at ([x], [y]), stepping one key per [cellW] across and [cellH] down. */
    fun start(x: Float, y: Float, cellW: Float, cellH: Float) {
        originX = x
        originY = y
        // A degenerate cell would divide by zero and send the whole scrollback.
        this.cellW = if (cellW > 0.5f) cellW else 1f
        this.cellH = if (cellH > 0.5f) cellH else 1f
        sentX = 0
        sentY = 0
    }

    /**
     * The finger is now at ([x], [y]); send whatever that crossed to [out].
     *
     * Returns whether anything went out, which is what the caller ticks a
     * haptic on — one tick per crossing, not one per key, or a drag in fourth
     * gear would buzz without stopping.
     */
    fun move(x: Float, y: Float, out: Emit): Boolean {
        val nx = ((x - originX) / cellW).toInt()
        val ny = ((y - originY) / cellH).toInt()
        var sent = false
        if (nx != sentX) {
            val steps = nx - sentX
            sentX = nx
            out.key(if (steps > 0) KeyCode.Right else KeyCode.Left, abs(steps) * gear(nx))
            sent = true
        }
        if (ny != sentY) {
            val steps = ny - sentY
            sentY = ny
            out.key(if (steps > 0) KeyCode.Down else KeyCode.Up, abs(steps) * gear(ny))
            sent = true
        }
        return sent
    }

    /** How many keys one crossing is worth [cells] out from the start. */
    private fun gear(cells: Int): Int = when {
        abs(cells) >= GEAR_3_CELLS -> 4
        abs(cells) >= GEAR_2_CELLS -> 2
        else -> 1
    }

    companion object {
        /** Cells out from the start where the second and third gears engage. */
        const val GEAR_2_CELLS = 8
        const val GEAR_3_CELLS = 16
    }
}
