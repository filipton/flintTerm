package dev.flint.term.terminal

import dev.flint.term.core.KeyCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrowDragTest {

    private val drag = ArrowDrag()
    private val sent = mutableListOf<Pair<KeyCode, Int>>()
    private val out = ArrowDrag.Emit { code, times -> sent += code to times }

    /** A 10 × 20 pixel cell, so a cell across and a cell down are told apart. */
    private fun start(x: Float = 0f, y: Float = 0f) = drag.start(x, y, 10f, 20f)

    /** Every key that came out, one entry per press. */
    private fun keys(): List<KeyCode> = sent.flatMap { (code, times) -> List(times) { code } }

    @Test
    fun `a drag sideways sends one key per cell crossed`() {
        start()
        drag.move(35f, 0f, out)
        assertEquals(List(3) { KeyCode.Right }, keys())
    }

    @Test
    fun `the other way sends the other key`() {
        start(x = 100f)
        drag.move(75f, 0f, out)
        assertEquals(List(2) { KeyCode.Left }, keys())
    }

    @Test
    fun `up and down go by the cell height`() {
        start(y = 200f)
        drag.move(0f, 141f, out)
        assertEquals(List(2) { KeyCode.Up }, keys())
        sent.clear()
        drag.move(0f, 260f, out)
        assertEquals(List(5) { KeyCode.Down }, keys())
    }

    @Test
    fun `part of a cell sends nothing`() {
        start()
        assertFalse(drag.move(9f, 19f, out))
        assertTrue(sent.isEmpty())
        // The part already travelled still counts towards the next cell.
        assertTrue(drag.move(11f, 0f, out))
        assertEquals(listOf(KeyCode.Right), keys())
    }

    @Test
    fun `a cell is only paid for once`() {
        start()
        drag.move(15f, 0f, out)
        drag.move(17f, 0f, out)
        drag.move(19f, 0f, out)
        assertEquals(listOf(KeyCode.Right), keys())
    }

    @Test
    fun `coming back sends the opposite key`() {
        start()
        drag.move(30f, 0f, out)
        sent.clear()
        drag.move(5f, 0f, out)
        assertEquals(List(3) { KeyCode.Left }, keys())
    }

    @Test
    fun `a diagonal drag sends both axes`() {
        start()
        drag.move(20f, 40f, out)
        assertEquals(listOf(KeyCode.Right, KeyCode.Right, KeyCode.Down, KeyCode.Down), keys())
    }

    @Test
    fun `the gears come in as the finger gets further out`() {
        start()
        // One cell at a time, the way a finger actually arrives.
        val perCrossing = (1..20).map { cell ->
            sent.clear()
            drag.move(cell * 10f + 1f, 0f, out)
            sent.single().second
        }
        assertEquals(1, perCrossing[0])
        assertEquals(1, perCrossing[ArrowDrag.GEAR_2_CELLS - 2])
        assertEquals(2, perCrossing[ArrowDrag.GEAR_2_CELLS - 1])
        assertEquals(2, perCrossing[ArrowDrag.GEAR_3_CELLS - 2])
        assertEquals(4, perCrossing[ArrowDrag.GEAR_3_CELLS - 1])
    }

    @Test
    fun `the gear belongs to the axis it was earned on`() {
        start()
        drag.move(200f, 0f, out)
        sent.clear()
        // One more cell across, in third gear, and the first cell down, which
        // has earned nothing yet.
        drag.move(210f, 21f, out)
        assertEquals(4, sent.first { it.first == KeyCode.Right }.second)
        assertEquals(1, sent.first { it.first == KeyCode.Down }.second)
    }

    @Test
    fun `starting again forgets the last drag`() {
        start()
        drag.move(50f, 0f, out)
        sent.clear()
        start(x = 50f)
        drag.move(60f, 0f, out)
        assertEquals(listOf(KeyCode.Right), keys())
    }
}
