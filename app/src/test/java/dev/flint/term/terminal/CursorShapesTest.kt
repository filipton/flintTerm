package dev.flint.term.terminal

import dev.flint.term.data.CursorStyle
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Who decides what the cursor looks like. The preference is for the shell
 * prompt, which never says; the program is for vim, which says with DECSCUSR
 * and means it.
 */
class CursorShapesTest {

    @Test
    fun `the preference applies while the program has said nothing`() {
        assertEquals(CursorShapes.BAR, CursorShapes.effective(CursorShapes.BLOCK, CursorStyle.BAR))
        assertEquals(CursorShapes.UNDERLINE, CursorShapes.effective(CursorShapes.BLOCK, CursorStyle.UNDERLINE))
        assertEquals(CursorShapes.BLOCK, CursorShapes.effective(CursorShapes.BLOCK, CursorStyle.BLOCK))
    }

    @Test
    fun `a shape the program asked for wins over the preference`() {
        assertEquals(CursorShapes.BAR, CursorShapes.effective(CursorShapes.BAR, CursorStyle.BLOCK))
        assertEquals(CursorShapes.UNDERLINE, CursorShapes.effective(CursorShapes.UNDERLINE, CursorStyle.BAR))
    }

    @Test
    fun `a hidden cursor stays hidden whatever the preference is`() {
        for (style in CursorStyle.entries) {
            assertEquals(CursorShapes.HIDDEN, CursorShapes.effective(CursorShapes.HIDDEN, style))
        }
    }
}
