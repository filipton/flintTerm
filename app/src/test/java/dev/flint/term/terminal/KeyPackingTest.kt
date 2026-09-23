package dev.flint.term.terminal

import dev.flint.term.core.KeyCode
import dev.flint.term.core.KeyEventKind
import dev.flint.term.core.KeyPress
import dev.flint.term.core.ModifierKey
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The numbers a key crosses to the core as. The core unpacks the same numbers
 * in `frame.rs`, and its test there uses the values asserted here, so the two
 * sides cannot drift apart without one of them failing.
 */
class KeyPackingTest {

    @Test
    fun `every key has the number the core expects`() {
        val expected = listOf(
            KeyCode.Char('a'.code.toUInt()) to 0,
            KeyCode.Enter to 1,
            KeyCode.Tab to 2,
            KeyCode.Backspace to 3,
            KeyCode.Escape to 4,
            KeyCode.Up to 5,
            KeyCode.Down to 6,
            KeyCode.Left to 7,
            KeyCode.Right to 8,
            KeyCode.Home to 9,
            KeyCode.End to 10,
            KeyCode.PageUp to 11,
            KeyCode.PageDown to 12,
            KeyCode.Insert to 13,
            KeyCode.Delete to 14,
            KeyCode.Function(12u) to 15,
            KeyCode.Modifier(ModifierKey.RIGHT_SUPER) to 16,
        )
        for ((code, number) in expected) assertEquals("$code", number, KeyPacking.key(code))
    }

    @Test
    fun `the argument carries the codepoint, the F number or the modifier`() {
        assertEquals('c'.code, KeyPacking.arg(KeyCode.Char('c'.code.toUInt())))
        assertEquals(12, KeyPacking.arg(KeyCode.Function(12u)))
        assertEquals(7, KeyPacking.arg(KeyCode.Modifier(ModifierKey.RIGHT_SUPER)))
        assertEquals(0, KeyPacking.arg(KeyCode.Enter))
    }

    @Test
    fun `modifiers and the kind pack as the core reads them`() {
        val press = KeyPress(KeyCode.Enter, ctrl = true, alt = false, shift = true, kind = KeyEventKind.RELEASE)
        assertEquals(1 or 4, KeyPacking.mods(press))
        assertEquals(2, KeyEventKind.RELEASE.ordinal)
        assertEquals(1, KeyEventKind.REPEAT.ordinal)
        assertEquals(0, KeyEventKind.PRESS.ordinal)
    }
}
