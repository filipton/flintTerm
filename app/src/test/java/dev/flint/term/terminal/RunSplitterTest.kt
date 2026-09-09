package dev.flint.term.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which cells go to the symbols font. The boundaries are the interesting part:
 * one codepoint either side of a private-use range decides whether a prompt
 * loses its icons or a line of ordinary text is drawn in a font that has no
 * letters in it.
 */
class RunSplitterTest {

    @Test
    fun `the private-use ranges are symbols and their neighbours are not`() {
        assertFalse(RunSplitter.isSymbol(0xDFFF))
        assertTrue(RunSplitter.isSymbol(0xE000))
        assertTrue(RunSplitter.isSymbol(0xF8FF))
        assertFalse(RunSplitter.isSymbol(0xF900))
        assertFalse(RunSplitter.isSymbol(0xEFFFF))
        assertTrue(RunSplitter.isSymbol(0xF0000))
        assertTrue(RunSplitter.isSymbol(0xFFFFF))
        // Plane 16 is private use too, but nothing draws there; a codepoint
        // that high is far more likely to be a decoding accident.
        assertFalse(RunSplitter.isSymbol(0x100000))
    }

    @Test
    fun `ordinary text is one run`() {
        assertEquals(
            listOf(RunSplitter.Run(0, 5, symbol = false)),
            RunSplitter.split("~ $ l"),
        )
    }

    @Test
    fun `an icon in the middle of a prompt breaks the row into three`() {
        assertEquals(
            listOf(
                RunSplitter.Run(0, 2, symbol = false),
                RunSplitter.Run(2, 3, symbol = true),
                RunSplitter.Run(3, 6, symbol = false),
            ),
            RunSplitter.split("~ \uE0B0 on"),
        )
    }

    @Test
    fun `adjacent icons stay in one run`() {
        assertEquals(
            listOf(RunSplitter.Run(0, 3, symbol = true)),
            RunSplitter.split(intArrayOf(0xE0B0, 0xF0000, 0xE5FF)),
        )
    }

    @Test
    fun `a surrogate pair counts as the one codepoint it spells`() {
        // U+F0001 arrives as two chars; splitting by char would cut it in half.
        val runs = RunSplitter.split(String(Character.toChars(0xF0001)) + "x")
        assertEquals(listOf(RunSplitter.Run(0, 1, symbol = true), RunSplitter.Run(1, 2, symbol = false)), runs)
    }

    @Test
    fun `an empty row has no runs`() {
        assertEquals(emptyList<RunSplitter.Run>(), RunSplitter.split(IntArray(0)))
    }
}
