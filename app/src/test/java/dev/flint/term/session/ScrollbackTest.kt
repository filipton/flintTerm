package dev.flint.term.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollbackTest {
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
