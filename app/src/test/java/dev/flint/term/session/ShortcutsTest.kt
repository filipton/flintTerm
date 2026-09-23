package dev.flint.term.session

import dev.flint.term.data.Host
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ShortcutsTest {

    private val hosts = listOf(
        Host(id = "a", label = "alpha", lastConnected = 10),
        Host(id = "b", label = "bravo", lastConnected = 30),
        Host(id = "c", label = "charlie", lastConnected = 0),
        Host(id = "d", label = "delta", lastConnected = 20),
        Host(id = "e", label = "echo", lastConnected = 5),
        Host(id = "f", label = "foxtrot", lastConnected = 1),
    )

    @Test
    fun `the four most recent hosts are offered, newest first`() {
        assertEquals(listOf("b", "d", "a", "e"), Shortcuts.recentFaces(hosts).map { it.id })
    }

    @Test
    fun `connecting again to the host at the top changes nothing the launcher shows`() {
        val again = hosts.map { if (it.id == "b") it.copy(lastConnected = 99) else it }
        assertEquals(Shortcuts.recentFaces(hosts), Shortcuts.recentFaces(again))
    }

    @Test
    fun `a new order, a rename or a new colour is a change`() {
        val before = Shortcuts.recentFaces(hosts)
        val reordered = hosts.map { if (it.id == "a") it.copy(lastConnected = 99) else it }
        val renamed = hosts.map { if (it.id == "d") it.copy(label = "delta two") else it }
        val recoloured = hosts.map { if (it.id == "e") it.copy(color = 0xFF00FF00.toInt()) else it }
        assertNotEquals(before, Shortcuts.recentFaces(reordered))
        assertNotEquals(before, Shortcuts.recentFaces(renamed))
        assertNotEquals(before, Shortcuts.recentFaces(recoloured))
    }
}
