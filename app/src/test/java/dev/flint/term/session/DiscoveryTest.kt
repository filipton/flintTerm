package dev.flint.term.session

import dev.flint.term.data.Host
import dev.flint.term.data.HostAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryTest {

    private fun service(name: String, address: String, port: Int = 22) = NearbyService(name, address, port)

    @Test
    fun `a resolved service opens the editor as a host`() {
        val host = Discovery.host(service("rig", "192.168.1.10", 2222))
        assertEquals("rig", host.label)
        assertEquals("192.168.1.10", host.hostname)
        assertEquals(2222, host.port)
        // Nothing is known about the login, so the editor asks as it would for
        // any new host.
        assertEquals("", host.username)
    }

    @Test
    fun `a host already saved at that address is not offered again`() {
        val saved = listOf(Host(label = "rig", hostname = "192.168.1.10", port = 22))
        val offered = Discovery.offer(listOf(service("rig", "192.168.1.10"), service("pi", "192.168.1.11")), saved)
        assertEquals(listOf("pi"), offered.map { it.name })
    }

    @Test
    fun `the same address on another port is another server`() {
        val saved = listOf(Host(hostname = "192.168.1.10", port = 22))
        val offered = Discovery.offer(listOf(service("rig-alt", "192.168.1.10", 2222)), saved)
        assertEquals(listOf(2222), offered.map { it.port })
    }

    @Test
    fun `an alternate address of a saved host counts as saved`() {
        // The host is reached by name from outside and by address at home; mDNS
        // only ever hears the second, and it is still the same machine.
        val saved = listOf(
            Host(
                hostname = "rig.example.com",
                port = 22,
                addresses = listOf(HostAddress(label = "Home LAN", hostname = "192.168.1.10")),
            ),
        )
        assertTrue(Discovery.offer(listOf(service("rig", "192.168.1.10")), saved).isEmpty())
    }

    @Test
    fun `an alternate address with a port of its own is matched on that port`() {
        val saved = listOf(
            Host(hostname = "rig.example.com", port = 22, addresses = listOf(HostAddress(hostname = "192.168.1.10", port = 2222))),
        )
        assertTrue(Discovery.offer(listOf(service("rig", "192.168.1.10", 2222)), saved).isEmpty())
        assertEquals(1, Discovery.offer(listOf(service("rig", "192.168.1.10", 22)), saved).size)
    }

    @Test
    fun `an address is compared without regard to case`() {
        val saved = listOf(Host(hostname = "RIG.local", port = 22))
        assertTrue(Discovery.offer(listOf(service("rig", "rig.local")), saved).isEmpty())
    }

    @Test
    fun `a box announcing both ssh and sftp-ssh is one row`() {
        val found = listOf(service("rig", "192.168.1.10"), service("rig", "192.168.1.10"))
        assertEquals(1, Discovery.offer(found, emptyList()).size)
    }

    @Test
    fun `an announcement with nothing to dial is dropped`() {
        val found = listOf(service("no address", ""), service("no port", "192.168.1.12", 0))
        assertTrue(Discovery.offer(found, emptyList()).isEmpty())
    }

    @Test
    fun `what is offered is in a stable order`() {
        val found = listOf(service("rig", "192.168.1.10"), service("attic", "192.168.1.30"), service("Pi", "192.168.1.20"))
        assertEquals(listOf("attic", "Pi", "rig"), Discovery.offer(found, emptyList()).map { it.name })
    }

    @Test
    fun `nothing saved means everything is offered`() {
        val found = listOf(service("rig", "192.168.1.10"), service("pi", "192.168.1.11"))
        assertEquals(2, Discovery.offer(found, emptyList()).size)
    }
}
