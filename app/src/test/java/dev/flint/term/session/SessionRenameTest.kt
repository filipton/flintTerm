package dev.flint.term.session

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionRenameTest {
    @Test
    fun `a name that was typed is the name it gets`() {
        assertEquals("build box", renamedTo("build box", "web01"))
    }

    @Test
    fun `an empty name goes back to the default`() {
        assertEquals("web01", renamedTo("", "web01"))
        // Spaces are how a cleared field usually comes back, and mean the same.
        assertEquals("web01", renamedTo("   ", "web01"))
    }

    @Test
    fun `the space around a name is not part of it`() {
        assertEquals("db", renamedTo("  db  ", "web01"))
    }

    @Test
    fun `a renamed session comes back from the file with its name`() {
        val entries = listOf(
            OpenSession("web01", name = "build box"),
            OpenSession("db02"),
        )
        val back = OpenSessions.decode(OpenSessions.encode(entries))
        assertEquals(entries, back)
        assertEquals(listOf("build box", null), back.map { it.name })
    }

    @Test
    fun `only a renamed session carries a name into the file`() {
        val live = listOf(LiveSession("a", "web01", "build box"), LiveSession("b", "db02"))
        assertEquals(listOf("build box", null), OpenSessions.snapshot(live, emptySet(), null).map { it.name })
    }
}
