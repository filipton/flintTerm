package dev.flint.term.session

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.RouteInfo
import android.os.Build
import dev.flint.term.App
import java.net.Inet4Address

/**
 * How this phone reaches a private address on its own, before any tunnel of
 * the app's is involved.
 *
 * Being on the home Wi-Fi is one way. A system-wide VPN that leads home (the
 * WireGuard app, say) is another, and it cannot be told from the interface
 * list: its interface usually holds a lone /32, and what it carries is said by
 * its routes. The app's own tunnels never show up here — WireGuard and
 * Tailscale run inside the process, and the SSH VPN leaves this app out.
 */
object LocalRoute {
    sealed interface Found

    /** One of the phone's own networks, as "a.b.c.d/n". */
    data class OnNetwork(val net: String) : Found

    /** A route of a system VPN that names this network, as "a.b.c.d/n". */
    data class ThroughVpn(val net: String) : Found

    /**
     * A system VPN that carries everything. It may lead home or to a VPN
     * provider, and only a connection can tell which.
     */
    data object EverythingThroughVpn : Found

    fun find(ip: String): Found? {
        Wol.localNetworkContaining(ip, ip)?.let { return OnNetwork(it) }
        val target = Wol.parseIpv4(ip.trim()) ?: return null
        return classify(target, systemVpnRoutes() ?: return null)
    }

    /**
     * What a VPN's routes, as (network, prefix), say about [target].
     *
     * Anything broader than a /8 is a VPN that takes all traffic, whether it
     * says so with 0.0.0.0/0 or with the two /1 halves some clients use:
     * nobody routes a whole /4 to a home network on purpose.
     */
    internal fun classify(target: Long, routes: List<Pair<Long, Int>>): Found? {
        val best = routes
            .filter { (net, prefix) -> prefix in 0..32 && (target and mask(prefix)) == (net and mask(prefix)) }
            .maxByOrNull { it.second }
            ?: return null
        val (net, prefix) = best
        if (prefix < 8) return EverythingThroughVpn
        val n = net and mask(prefix)
        return ThroughVpn("${n shr 24}.${(n shr 16) and 255}.${(n shr 8) and 255}.${n and 255}/$prefix")
    }

    private fun mask(prefix: Int): Long = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL

    /**
     * The IPv4 routes of the VPN this app's traffic goes through, or null when
     * it goes through none. The active network is asked rather than every
     * network, because it is the one that applies to this app: a VPN that
     * leaves the app out is not a way home for it.
     */
    private fun systemVpnRoutes(): List<Pair<Long, Int>>? = runCatching {
        val cm = App.instance.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null
        val lp = cm.getLinkProperties(network) ?: return null
        lp.routes.mapNotNull { r ->
            // An excluded route is the VPN saying "not through me".
            if (Build.VERSION.SDK_INT >= 33 && r.type != RouteInfo.RTN_UNICAST) return@mapNotNull null
            val addr = r.destination.address as? Inet4Address ?: return@mapNotNull null
            val net = Wol.parseIpv4(addr.hostAddress ?: return@mapNotNull null) ?: return@mapNotNull null
            net to r.destination.prefixLength
        }
    }.getOrNull()
}
