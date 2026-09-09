package dev.flint.term.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollbackTest {
    @Test
    fun `the lines come out in the order they were printed`() {
        val lines = listOf("$ ls", "Cargo.toml", "src", "$ ")
        assertEquals("$ ls\nCargo.toml\nsrc\n$", Scrollback.text(lines))
    }

    @Test
    fun `the empty screen under the last output is dropped`() {
        val lines = listOf("$ uptime", " 11:04:22 up 9 days", "$ ", "", "", "", "")
        assertEquals("$ uptime\n 11:04:22 up 9 days\n$", Scrollback.text(lines))
    }

    @Test
    fun `a blank line inside the output is part of it`() {
        val lines = listOf("first", "", "second", "")
        assertEquals("first\n\nsecond", Scrollback.text(lines))
    }

    @Test
    fun `the padding a terminal writes to the end of a row is not text`() {
        assertEquals("web01", Scrollback.text(listOf("web01" + " ".repeat(75))))
    }

    @Test
    fun `an empty buffer is an empty string, not a line`() {
        assertEquals("", Scrollback.text(emptyList()))
        assertEquals("", Scrollback.text(listOf("", "   ", "")))
    }

    @Test
    fun `the file is named after the session and when it was taken`() {
        val at = 1_757_000_000_000L
        val name = Scrollback.fileName("web01", at)
        assertTrue(name, name.startsWith("scrollback-web01-"))
        assertTrue(name, name.endsWith(".txt"))
        // Nothing a file system would argue with, whatever the session is called.
        assertEquals(name, Scrollback.fileName("web01", at).replace(Regex("[^A-Za-z0-9._-]"), "?"))
    }

    @Test
    fun `a name full of punctuation still makes a file name`() {
        val name = Scrollback.fileName("root@10.0.0.4: ~/src", 1_757_000_000_000L)
        assertEquals(name, name.replace(Regex("[^A-Za-z0-9._-]"), "?"))
        assertTrue(name, name.startsWith("scrollback-root-10.0.0.4"))
    }
}
