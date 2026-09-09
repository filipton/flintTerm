package dev.flint.term.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The arrows are one control drawn as four caps, so reordering has to treat
 * them as one. The interesting cases are the edges and a group meeting a group.
 */
class ExtraKeysMoveTest {
    private val arrows = listOf("UP", "DOWN", "LEFT", "RIGHT")

    @Test fun `a group steps over a single cap and takes the selection with it`() {
        val row = listOf("ESC") + arrows + listOf("TAB")
        val (moved, at) = ExtraKeys.moveGroup(row, 2, right = true, grouped = true)!!
        assertEquals(listOf("ESC", "TAB", "UP", "DOWN", "LEFT", "RIGHT"), moved)
        assertEquals("DOWN", moved[at])

        val (back, backAt) = ExtraKeys.moveGroup(row, 2, right = false, grouped = true)!!
        assertEquals(arrows + listOf("ESC", "TAB"), back)
        assertEquals("DOWN", back[backAt])
    }

    @Test fun `a group will not step into the middle of another group`() {
        val row = listOf("ESC") + arrows + listOf("HOME", "END")
        // HOME and END are not arrows, so they are groups of one: the block
        // lands between them rather than swallowing both.
        val (moved, _) = ExtraKeys.moveGroup(row, 1, right = true, grouped = true)!!
        assertEquals(listOf("ESC", "HOME", "UP", "DOWN", "LEFT", "RIGHT", "END"), moved)
    }

    @Test fun `ungrouped, one arrow moves alone`() {
        val row = listOf("ESC") + arrows
        val (moved, at) = ExtraKeys.moveGroup(row, 1, right = true, grouped = false)!!
        assertEquals(listOf("ESC", "DOWN", "UP", "LEFT", "RIGHT"), moved)
        assertEquals("UP", moved[at])
    }

    @Test fun `the ends refuse to move`() {
        val row = arrows + listOf("ESC")
        assertNull(ExtraKeys.moveGroup(row, 0, right = false, grouped = true))
        assertNull(ExtraKeys.moveGroup(row, 4, right = true, grouped = true))
    }

    @Test fun `a cap outside a run is only ever itself`() {
        val row = listOf("ESC") + arrows
        assertEquals(0..0, ExtraKeys.groupAt(row, 0, grouped = true))
        assertEquals(1..4, ExtraKeys.groupAt(row, 3, grouped = true))
        assertEquals(3..3, ExtraKeys.groupAt(row, 3, grouped = false))
    }
}
