package dev.flint.term.terminal

import dev.flint.term.core.KeyCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChordParserTest {

    private fun char(c: Char) = KeyCode.Char(c.code.toUInt())

    @Test
    fun `the tmux prefix and a letter`() {
        assertEquals(
            listOf(ChordPress(char('b'), ctrl = true), ChordPress(char('c'))),
            ChordParser.parse("C-b c"),
        )
    }

    @Test
    fun `meta is alt`() {
        assertEquals(listOf(ChordPress(char('x'), alt = true)), ChordParser.parse("M-x"))
    }

    @Test
    fun `a named key carries its modifier`() {
        assertEquals(listOf(ChordPress(KeyCode.Tab, shift = true)), ChordParser.parse("S-Tab"))
    }

    @Test
    fun `the same step twice is two presses`() {
        assertEquals(listOf(ChordPress(KeyCode.Escape), ChordPress(KeyCode.Escape)), ChordParser.parse("Esc Esc"))
    }

    @Test
    fun `modifiers stack, in any spelling`() {
        val expected = listOf(ChordPress(char('k'), ctrl = true, alt = true, shift = true))
        assertEquals(expected, ChordParser.parse("C-M-S-k"))
        assertEquals(expected, ChordParser.parse("Ctrl+Alt+Shift+k"))
        assertEquals(expected, ChordParser.parse("control-meta-shift-k"))
    }

    @Test
    fun `key names are spelled several ways and mean the same key`() {
        for (name in listOf("Enter", "return", "CR")) {
            assertEquals(listOf(ChordPress(KeyCode.Enter)), ChordParser.parse(name))
        }
        for (name in listOf("PgUp", "pageup", "PPage")) {
            assertEquals(listOf(ChordPress(KeyCode.PageUp)), ChordParser.parse(name))
        }
        assertEquals(listOf(ChordPress(KeyCode.Function(7.toUByte()))), ChordParser.parse("F7"))
        assertEquals(listOf(ChordPress(char(' '))), ChordParser.parse("Space"))
    }

    @Test
    fun `the word prefix is the host's own prefix`() {
        assertEquals(
            listOf(ChordPress(char('a'), ctrl = true), ChordPress(char('n'))),
            ChordParser.parse("prefix n", prefix = "C-a"),
        )
        // Unset, it is what tmux ships with.
        assertEquals(ChordParser.parse("C-b c"), ChordParser.parse("prefix c"))
    }

    @Test
    fun `a slash command is typed out and run`() {
        val presses = ChordParser.parse("/clear Enter")
        assertEquals("/clear".map { ChordPress(char(it)) } + ChordPress(KeyCode.Enter), presses)
    }

    @Test
    fun `punctuation is a key like any other`() {
        assertEquals(
            listOf(ChordPress(char('b'), ctrl = true), ChordPress(char('%'))),
            ChordParser.parse("prefix %"),
        )
        assertEquals(listOf(ChordPress(char('-'), alt = true)), ChordParser.parse("M--"))
    }

    @Test
    fun `spacing around the steps does not matter`() {
        assertEquals(ChordParser.parse("C-b c"), ChordParser.parse("  C-b   c  "))
    }

    @Test
    fun `rubbish is rejected rather than half sent`() {
        assertNull(ChordParser.parse(""))
        assertNull(ChordParser.parse("   "))
        assertNull(ChordParser.parse("C-"))
        assertNull(ChordParser.parse("C-M-"))
        assertNull(ChordParser.parse("Ctrl+"))
        // A modifier means the rest is a key name, and there is no such key.
        assertNull(ChordParser.parse("M-Nope"))
        assertNull(ChordParser.parse("S-F13"))
        // One bad step spoils the chord: a half-sent chord is worse than none.
        assertNull(ChordParser.parse("C-b C-"))
    }

    @Test
    fun `a prefix that is itself rubbish sends nothing`() {
        assertNull(ChordParser.parse("prefix c", prefix = "C-"))
    }
}
