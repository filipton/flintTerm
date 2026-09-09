package dev.flint.term.session

import dev.flint.term.core.Endpoint
import org.junit.Assert.assertEquals
import org.junit.Test

class EndpointsTest {
    private fun ep(host: String, label: String = "", tunnel: String? = null, tailscale: String? = null) =
        Endpoint(
            label = label, host = host, port = 22u, tunnelId = tunnel, tailscaleId = tailscale,
            vpnName = "", tunnelFallback = false,
        )

    /** As if the phone were on 192.168.1.0/24 and nothing else. */
    private val here: (String) -> Boolean = { it.startsWith("192.168.1.") }

    @Test
    fun `a private address on another network is tried last`() {
        val out = Endpoints.order(listOf(ep("10.0.0.5"), ep("vpn.example.com")), here)
        assertEquals(listOf("vpn.example.com", "10.0.0.5"), out.map { it.host })
    }

    @Test
    fun `a private address on this network keeps its place at the front`() {
        val out = Endpoints.order(listOf(ep("192.168.1.5"), ep("vpn.example.com")), here)
        assertEquals(listOf("192.168.1.5", "vpn.example.com"), out.map { it.host })
    }

    @Test
    fun `names and public addresses are left alone`() {
        val given = listOf(ep("example.com"), ep("203.0.113.9"), ep("box.local"))
        assertEquals(given.map { it.host }, Endpoints.order(given, here).map { it.host })
    }

    @Test
    fun `an address reached through a tunnel is not judged by this phone's networks`() {
        val given = listOf(ep("10.0.0.5", tunnel = "wg1"), ep("example.com"))
        assertEquals(given.map { it.host }, Endpoints.order(given, here).map { it.host })
        val tailnet = listOf(ep("100.64.0.7", tailscale = "ts1"), ep("example.com"))
        assertEquals(tailnet.map { it.host }, Endpoints.order(tailnet, here).map { it.host })
    }

    @Test
    fun `the ones that stay and the ones that move each keep their own order`() {
        val given = listOf(ep("10.0.0.5"), ep("a.example.com"), ep("172.16.4.4"), ep("b.example.com"))
        assertEquals(
            listOf("a.example.com", "b.example.com", "10.0.0.5", "172.16.4.4"),
            Endpoints.order(given, here).map { it.host },
        )
    }

    @Test
    fun `a host whose only address is elsewhere is still dialled`() {
        val given = listOf(ep("10.0.0.5"))
        assertEquals(given.map { it.host }, Endpoints.order(given, here).map { it.host })
    }
}
