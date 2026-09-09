package dev.flint.term.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostGroupTest {
    private val group = HostGroup(
        id = "g1",
        name = "Work",
        accountId = "a1",
        jumpHostId = "bastion",
        tunnelId = "t1",
        proxyId = "p1",
        theme = "NORD",
        color = 0xFF00FF00.toInt(),
    )

    @Test
    fun `a member inherits what it has not set`() {
        val out = Host(id = "h1", groupId = "g1").withGroup(group)
        assertEquals("a1", out.accountId)
        assertEquals("bastion", out.jumpHostId)
        assertEquals("t1", out.tunnelId)
        assertEquals("p1", out.proxyId)
        assertEquals("NORD", out.theme)
        assertEquals(0xFF00FF00.toInt(), out.color)
    }

    @Test
    fun `the host's own values win`() {
        val host = Host(
            id = "h1", groupId = "g1", accountId = "mine", jumpHostId = "other",
            tunnelId = "t9", proxyId = "p9", theme = "DRACULA", color = 0xFFFF0000.toInt(),
        )
        val out = host.withGroup(group)
        assertEquals("mine", out.accountId)
        assertEquals("other", out.jumpHostId)
        assertEquals("t9", out.tunnelId)
        assertEquals("p9", out.proxyId)
        assertEquals("DRACULA", out.theme)
        assertEquals(0xFFFF0000.toInt(), out.color)
    }

    @Test
    fun `a host is never sent through itself`() {
        val out = Host(id = "bastion", groupId = "g1").withGroup(group)
        assertNull(out.jumpHostId)
        assertEquals("t1", out.tunnelId)
    }

    @Test
    fun `a host outside the group inherits nothing`() {
        val out = Host(id = "h1", groupId = null).withGroup(group)
        assertNull(out.accountId)
        assertNull(out.tunnelId)
    }

    @Test
    fun `a tailnet in the group reaches its hosts`() {
        val out = Host(id = "h1", groupId = "g1").withGroup(group.copy(tunnelId = null, tailscaleId = "ts1"))
        assertEquals("ts1", out.tailscaleId)
    }
}
