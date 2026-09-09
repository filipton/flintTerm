package dev.flint.term.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSessionsTest {
    private fun ssh(id: String) = LiveSession(id, "host-$id")

    @Test
    fun `a list survives the round trip`() {
        val entries = listOf(
            OpenSession("web01", filesOpen = true),
            OpenSession("db02", besidePane = PaneKind.SERVER),
            OpenSession("pi", filesOpen = false, besidePane = null),
        )
        assertEquals(entries, OpenSessions.decode(OpenSessions.encode(entries)))
    }

    @Test
    fun `every kind of pane comes back as itself`() {
        for (kind in PaneKind.entries) {
            val one = listOf(OpenSession("web01", besidePane = kind))
            assertEquals(one, OpenSessions.decode(OpenSessions.encode(one)))
        }
    }

    @Test
    fun `nothing open is an empty list, not a missing one`() {
        assertEquals("[]", OpenSessions.encode(emptyList()))
        assertEquals(emptyList<OpenSession>(), OpenSessions.decode("[]"))
        assertEquals(emptyList<OpenSession>(), OpenSessions.decode(""))
    }

    @Test
    fun `an entry with no host is dropped rather than read back as one`() {
        assertEquals(
            listOf(OpenSession("web01")),
            OpenSessions.decode("""[{"filesOpen":true},{"hostId":"web01"}]"""),
        )
    }

    @Test
    fun `a pane name nobody recognises reads as no split`() {
        assertEquals(
            listOf(OpenSession("web01")),
            OpenSessions.decode("""[{"hostId":"web01","besidePane":"nonsense"}]"""),
        )
    }

    @Test
    fun `the file keeps the tab order`() {
        val live = listOf(ssh("a"), ssh("b"), ssh("c"))
        val entries = OpenSessions.snapshot(live, filesOpen = emptySet(), beside = null)
        assertEquals(listOf("host-a", "host-b", "host-c"), entries.map { it.hostId })
        // And through the file, since that is where the order has to survive.
        assertEquals(entries, OpenSessions.decode(OpenSessions.encode(entries)))
    }

    @Test
    fun `playback, serial and local sessions are not recorded`() {
        // None of the three has a host: there is nothing to reconnect to.
        val live = listOf(LiveSession("play", null), ssh("a"), LiveSession("usb", null), LiveSession("sh", null))
        val entries = OpenSessions.snapshot(live, filesOpen = emptySet(), beside = null)
        assertEquals(listOf("host-a"), entries.map { it.hostId })
    }

    @Test
    fun `open tabs and the split are recorded against the session they belong to`() {
        val live = listOf(ssh("a"), ssh("b"))
        val entries = OpenSessions.snapshot(live, filesOpen = setOf("a"), beside = "b" to PaneKind.FILES)
        assertTrue(entries[0].filesOpen)
        assertEquals(null, entries[0].besidePane)
        assertFalse(entries[1].filesOpen)
        assertEquals(PaneKind.FILES, entries[1].besidePane)
    }

    @Test
    fun `a split belonging to a session that is gone is not written`() {
        val entries = OpenSessions.snapshot(listOf(ssh("a")), filesOpen = emptySet(), beside = "b" to PaneKind.TERM)
        assertEquals(listOf(OpenSession("host-a")), entries)
    }

    @Test
    fun `a host that failed to reconnect leaves the file and is not tried again`() {
        val file = java.io.File.createTempFile("open", ".json").apply { deleteOnExit() }
        file.writeText(OpenSessions.encode(listOf(OpenSession("web01"), OpenSession("db02"))))
        val open = OpenSessions(file, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job()))
        assertEquals(listOf("web01", "db02"), open.saved.map { it.hostId })

        // The restore is handed the list exactly once; whatever happens to the
        // connections afterwards, a second ask gets nothing.
        assertTrue(open.claimRestore())
        assertFalse(open.claimRestore())

        // db02 never came up, so it is not among the live sessions and drops
        // out of what would be written — nothing is left to retry next time.
        val stillUp = OpenSessions.snapshot(listOf(LiveSession("s1", "web01")), emptySet(), null)
        assertEquals(listOf("web01"), stillUp.map { it.hostId })
    }

    @Test
    fun `a file that is not JSON leaves the app with an empty list`() {
        val file = java.io.File.createTempFile("open", ".json").apply { deleteOnExit() }
        file.writeText("half a wri")
        val open = OpenSessions(file, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job()))
        assertEquals(emptyList<OpenSession>(), open.saved)
    }
}
