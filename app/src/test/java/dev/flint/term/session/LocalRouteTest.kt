package dev.flint.term.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalRouteTest {
    private fun ip(s: String): Long = Wol.parseIpv4(s)!!
    private fun route(s: String): Pair<Long, Int> = s.split('/').let { (n, p) -> ip(n) to p.toInt() }

    @Test
    fun `a VPN that routes the home network reaches it`() {
        val routes = listOf(route("10.8.0.0/24"), route("192.168.1.0/24"))
        assertEquals(LocalRoute.ThroughVpn("192.168.1.0/24"), LocalRoute.classify(ip("192.168.1.20"), routes))
    }

    @Test
    fun `an address the VPN does not route is not reached through it`() {
        assertNull(LocalRoute.classify(ip("192.168.2.20"), listOf(route("192.168.1.0/24"))))
    }

    @Test
    fun `a VPN that carries everything is only a maybe`() {
        assertEquals(LocalRoute.EverythingThroughVpn, LocalRoute.classify(ip("192.168.1.20"), listOf(route("0.0.0.0/0"))))
        val halves = listOf(route("0.0.0.0/1"), route("128.0.0.0/1"))
        assertEquals(LocalRoute.EverythingThroughVpn, LocalRoute.classify(ip("10.0.0.5"), halves))
    }

    @Test
    fun `the narrowest route decides`() {
        val routes = listOf(route("0.0.0.0/0"), route("10.0.0.0/8"), route("10.1.2.0/24"))
        assertEquals(LocalRoute.ThroughVpn("10.1.2.0/24"), LocalRoute.classify(ip("10.1.2.3"), routes))
        assertEquals(LocalRoute.ThroughVpn("10.0.0.0/8"), LocalRoute.classify(ip("10.9.9.9"), routes))
    }

    @Test
    fun `a full tunnel that leaves private networks out does not claim them`() {
        // What the WireGuard app writes for 0.0.0.0/0 with "exclude private IPs".
        val routes = listOf(route("0.0.0.0/5"), route("8.0.0.0/7"), route("11.0.0.0/8"), route("192.0.0.0/9"), route("192.169.0.0/16"))
        assertNull(LocalRoute.classify(ip("192.168.1.20"), routes))
    }
}
