package dev.flint.term.session

import dev.flint.term.data.Host
import dev.flint.term.data.HostGroup
import dev.flint.term.data.KnockSettings
import dev.flint.term.data.ProxySettings
import dev.flint.term.data.ProxyType
import dev.flint.term.data.withGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which hosts may be probed at all.
 *
 * This is the part with the judgment in it. A TCP connect from the phone is
 * only evidence about a host the phone dials itself; run it against one that
 * lives behind a tunnel, a tailnet, a jump host or a proxy and it reports
 * "unreachable" for a machine that is perfectly well — a dot that lies is worse
 * than no dot, so those are left alone.
 */
class ReachabilityTest {

    private fun host(label: String = "web01") = Host(label = label, hostname = "$label.example.com", port = 22)

    private fun names(hosts: List<Host>) = hosts.map { it.label }

    private fun probeable(vararg hosts: Host) =
        Reachability.probeable(hosts.toList(), saving = false, online = true)

    @Test
    fun `a host dialled straight from the phone is probed`() {
        assertEquals(listOf("web01"), names(probeable(host())))
    }

    @Test
    fun `a host behind a jump host is left alone`() {
        assertFalse(Reachability.direct(host().copy(jumpHostId = "other")))
    }

    @Test
    fun `a host behind a tunnel or a tailnet is left alone`() {
        assertFalse(Reachability.direct(host().copy(tunnelId = "wg0")))
        assertFalse(Reachability.direct(host().copy(tailscaleId = "work")))
    }

    @Test
    fun `a host behind a proxy is left alone, named or written out`() {
        assertFalse(Reachability.direct(host().copy(proxyId = "corp")))
        assertFalse(
            Reachability.direct(
                host().copy(proxy = ProxySettings(type = ProxyType.SOCKS5, host = "10.0.0.1", port = 1080)),
            ),
        )
        // A proxy record with nothing in it is not a proxy.
        assertTrue(Reachability.direct(host().copy(proxy = ProxySettings(type = ProxyType.SOCKS5, host = ""))))
    }

    @Test
    fun `a host behind a port knock is left alone`() {
        // Its port is shut until the knock opens it, so a probe would find a
        // closed door and call the machine down.
        assertFalse(Reachability.direct(host().copy(knock = KnockSettings(enabled = true, sequence = "7000, 8000"))))
        assertTrue(Reachability.direct(host().copy(knock = KnockSettings(enabled = false, sequence = "7000"))))
    }

    @Test
    fun `a route inherited from a group disqualifies its members too`() {
        val group = HostGroup(name = "Office", jumpHostId = "bastion")
        val member = host("db01").copy(groupId = group.id)
        assertTrue("on its own it would be probed", Reachability.direct(member))
        assertFalse("but it is reached through the group's bastion", Reachability.direct(member.withGroup(group)))
    }

    @Test
    fun `a host with no address to dial is skipped`() {
        assertFalse(Reachability.direct(host().copy(hostname = "   ")))
        assertFalse(Reachability.direct(host().copy(port = 70000)))
    }

    @Test
    fun `a telnet console is probed like anything else that answers TCP`() {
        val console = Host(label = "switch", hostname = "switch.lan", port = 0, protocol = dev.flint.term.data.Protocol.TELNET)
        assertTrue(Reachability.direct(console))
        assertEquals(23, Reachability.port(console))
    }

    @Test
    fun `nothing is probed while the data saver says to hold off`() {
        assertTrue(Reachability.probeable(listOf(host()), saving = true, online = true).isEmpty())
    }

    @Test
    fun `nothing is probed with no network at all`() {
        assertTrue(Reachability.probeable(listOf(host()), saving = false, online = false).isEmpty())
    }

    @Test
    fun `only the direct ones come back out of a mixed list`() {
        val direct = host("web01")
        val tunnelled = host("nas").copy(tunnelId = "wg0")
        val jumped = host("db01").copy(jumpHostId = "bastion")
        assertEquals(listOf("web01"), names(probeable(direct, tunnelled, jumped)))
    }

    @Test
    fun `an answer is remembered until it goes stale`() {
        var clock = 0L
        var probes = 0
        val cache = ReachabilityCache(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            ttlMs = 1000,
            now = { clock },
            connect = { _, _, _ -> probes++; true },
        )
        val h = host()
        cache.probe(listOf(h), saving = false, online = true)
        assertEquals(Reach.REACHABLE, cache.state.value[h.id])
        cache.probe(listOf(h), saving = false, online = true)
        assertEquals("still fresh", 1, probes)
        clock += 1000
        cache.probe(listOf(h), saving = false, online = true)
        assertEquals("asked again once it aged out", 2, probes)
    }

    @Test
    fun `a pull on the list asks everything again`() {
        var probes = 0
        val cache = ReachabilityCache(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            now = { 0 },
            connect = { _, _, _ -> probes++; false },
        )
        val h = host()
        cache.probe(listOf(h), saving = false, online = true)
        assertEquals(Reach.UNREACHABLE, cache.state.value[h.id])
        cache.invalidate()
        assertTrue("the dot goes with it", cache.state.value.isEmpty())
        cache.probe(listOf(h), saving = false, online = true)
        assertEquals(2, probes)
    }

    @Test
    fun `an edited address is not answered from the old one's result`() {
        var probes = 0
        val cache = ReachabilityCache(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            now = { 0 },
            connect = { _, _, _ -> probes++; true },
        )
        val h = host()
        cache.probe(listOf(h), saving = false, online = true)
        cache.probe(listOf(h.copy(port = 2222)), saving = false, online = true)
        assertEquals(2, probes)
    }
}
