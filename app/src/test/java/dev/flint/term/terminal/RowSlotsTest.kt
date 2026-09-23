package dev.flint.term.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bookkeeping behind recorded rows. What matters is that a row which only
 * moved is found again, and that recording a new row never takes the slot of
 * one already drawn this frame — that would change a row that is already on
 * the screen.
 */
class RowSlotsTest {

    /** One frame: the slot each row is drawn from, claiming where nothing is found. */
    private fun RowSlots.frame(keys: LongArray): IntArray =
        IntArray(keys.size) { r -> find(r, keys[r]).takeIf { it >= 0 } ?: claim(r, keys[r]) }

    @Test
    fun `a row that scrolled up is found where it was recorded`() {
        val slots = RowSlots()
        slots.fit(4)
        val first = slots.frame(longArrayOf(10, 11, 12, 13))
        // Everything moved up by one and a new line came in at the bottom.
        for (r in 0 until 3) assertEquals(first[r + 1], slots.find(r, 11L + r))
        assertEquals(-1, slots.find(3, 14))
    }

    @Test
    fun `a new row never takes a slot this frame has drawn`() {
        val slots = RowSlots()
        slots.fit(5)
        var key = 0L
        // Screen after screen of rows nobody has seen before, which is output
        // streaming past: every frame fills the slots up and evicts.
        repeat(20) {
            val drawn = slots.frame(LongArray(5) { key++ })
            assertEquals("five rows, five slots", 5, drawn.toSet().size)
        }
    }

    @Test
    fun `identical rows share one recording`() {
        val slots = RowSlots()
        slots.fit(3)
        val drawn = slots.frame(longArrayOf(7, 7, 7))
        assertEquals(1, drawn.toSet().size)
    }

    @Test
    fun `clearing forgets every row, and a new size starts empty`() {
        val slots = RowSlots()
        assertTrue(slots.fit(2))
        slots.frame(longArrayOf(1, 2))
        slots.clear()
        assertEquals(-1, slots.find(0, 1))
        slots.frame(longArrayOf(1, 2))
        assertFalse("the same size keeps what it has", slots.fit(2))
        assertNotEquals(-1, slots.find(1, 2))
        assertTrue(slots.fit(3))
        assertEquals(-1, slots.find(1, 2))
    }
}
