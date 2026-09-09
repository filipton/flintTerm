package dev.flint.term.session

import dev.flint.term.core.Endpoint

/**
 * The order a host's addresses are worth trying in.
 *
 * A private address means a different machine on every network, and one that
 * belongs to a network this phone is not on has nowhere to go: the connection
 * sits on it until it times out, which is exactly the wait a second address was
 * added to avoid. So those go last and everything else keeps its place — the
 * same reasoning the tunnel already uses to decide whether it is needed, applied
 * to a host that has no tunnel at all.
 *
 * Last rather than dropped: this reads the phone's own interfaces, and a route
 * it cannot see is still a route. A host whose only address is off-network is
 * dialled the way it always was.
 *
 * An address with a VPN of its own is left where it is. The tunnel is what makes
 * it reachable and it is not up yet, so what this phone's interfaces say about
 * it now means nothing.
 */
object Endpoints {
    fun order(candidates: List<Endpoint>, onNetwork: (String) -> Boolean = ::onThisNetwork): List<Endpoint> {
        val (here, elsewhere) = candidates.partition { !offNetwork(it, onNetwork) }
        return here + elsewhere
    }

    /** Whether this endpoint is a private address on a network the phone is not on. */
    fun offNetwork(e: Endpoint, onNetwork: (String) -> Boolean = ::onThisNetwork): Boolean {
        if (e.tunnelId != null || e.tailscaleId != null) return false
        val ip = e.host.trim()
        return Wol.isPrivateIpv4(ip) && !onNetwork(ip)
    }

    private fun onThisNetwork(ip: String): Boolean = Wol.localNetworkContaining(ip, ip) != null
}
