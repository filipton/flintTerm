package dev.flint.term.session

import dev.flint.term.data.Host
import dev.flint.term.data.WolSettings
import dev.flint.term.data.WolSource
import java.net.Inet4Address
import java.net.NetworkInterface

/** Wake-on-LAN helpers: a magic packet from this device, or a script for a remote shell. */
object Wol {
    private val macRegex = Regex("^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$")

    fun isValidMac(mac: String) = macRegex.matches(mac.trim())

    /**
     * Prints one "iface mac broadcast" line per interface with a real MAC, the
     * default-route interface first. Linux via /sys and iproute2, with an
     * ifconfig fallback for BSD/macOS.
     */
    const val DETECT_COMMAND = """D=${'$'}(ip route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if(${'$'}i=="dev") {print ${'$'}(i+1); exit}}'); for i in ${'$'}D ${'$'}(ls /sys/class/net 2>/dev/null); do [ "${'$'}i" = lo ] && continue; a=${'$'}(cat /sys/class/net/${'$'}i/address 2>/dev/null); case "${'$'}a" in ""|00:00:00:00:00:00) continue;; esac; b=${'$'}(ip -o -4 addr show dev ${'$'}i 2>/dev/null | awk '{for(j=1;j<=NF;j++) if(${'$'}j=="brd") {print ${'$'}(j+1); exit}}'); echo "${'$'}i ${'$'}a ${'$'}b"; done | awk '!seen[${'$'}2]++'; [ -d /sys/class/net ] || ifconfig -a 2>/dev/null | awk '/^[a-z]/{i=${'$'}1} /ether/{print i, ${'$'}2, ""}'"""

    /** Parse [DETECT_COMMAND] output into (iface, mac, broadcast). */
    fun parseDetected(out: String): List<Triple<String, String, String>> =
        out.lines().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 2 && isValidMac(parts[1])) Triple(parts[0].trimEnd(':'), normalizeMac(parts[1]), parts.getOrNull(2) ?: "") else null
        }

    fun normalizeMac(mac: String) = mac.trim().replace('-', ':').lowercase()

    /** Where the magic packet should come from, with the reason (for the connection log). */
    data class Decision(val source: WolSource, val reason: String)

    /**
     * Every address the host might be reached at, primary first.
     *
     * A magic packet is a link-local broadcast: it can only wake a machine on a
     * network this phone is actually on. With several addresses configured, the
     * LAN one is usually not the first — so all of them are considered, and the
     * one that matches a local network decides where to broadcast.
     */
    /**
     * The address a remote waker should aim at: the first private one, since a
     * public name or a tailnet address tells the jump host nothing about which
     * of its interfaces the machine is on.
     */
    fun wakeTarget(host: Host): String =
        candidates(host).firstOrNull { isPrivateIpv4(it) } ?: host.hostname.trim()

    fun candidates(host: Host): List<String> =
        (listOf(host.hostname) + host.addresses.map { it.hostname }).map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Resolve [WolSource.AUTO]: the phone broadcasts when it sits on the
     * target's network, otherwise the jump host (if there is one) does it.
     */
    fun decide(host: Host, hasJump: Boolean): Decision {
        return when (host.wol.sendFrom) {
            WolSource.PHONE -> Decision(WolSource.PHONE, "configured to send from this phone")
            WolSource.JUMP_HOST -> if (hasJump) Decision(WolSource.JUMP_HOST, "configured to send from the jump host") else Decision(WolSource.PHONE, "no jump host set, sending from this phone")
            WolSource.AUTO -> {
                val lan = candidates(host).firstNotNullOfOrNull { localNetworkContaining(host.wol.broadcast, it) }
                when {
                    lan != null -> Decision(WolSource.PHONE, "this phone is on $lan, broadcasting from here")
                    hasJump -> Decision(WolSource.JUMP_HOST, "this phone is not on the target's network, sending from the jump host")
                    else -> Decision(WolSource.PHONE, "no jump host and not on the target's network, so trying a broadcast from this phone anyway")
                }
            }
        }
    }

    /** The phone's IPv4 network (as "a.b.c.d/n") that contains the broadcast or the target address, if any. */
    fun localNetworkContaining(broadcast: String, hostname: String): String? {
        val targets = listOf(broadcast.trim(), hostname.trim()).mapNotNull { parseIpv4(it) }.filter { it != 0xFFFFFFFFL }
        if (targets.isEmpty()) return null
        val ifaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }.getOrDefault(emptyList())
        for (ni in ifaces) {
            if (!ni.isUp || ni.isLoopback) continue
            for (ia in ni.interfaceAddresses) {
                val addr = ia.address as? Inet4Address ?: continue
                val prefix = ia.networkPrefixLength.toInt()
                if (prefix !in 1..31) continue
                val ip = parseIpv4(addr.hostAddress ?: continue) ?: continue
                val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
                if (targets.any { (it and mask) == (ip and mask) }) {
                    val net = ip and mask
                    return "${net shr 24}.${(net shr 16) and 255}.${(net shr 8) and 255}.${net and 255}/$prefix"
                }
            }
        }
        return null
    }

    /**
     * The broadcast address to actually send to. An empty setting means "auto":
     * if the target is an IPv4 address on one of this phone's networks, use that
     * interface's own directed broadcast (192.168.1.255 rather than the blunt
     * 255.255.255.255, which some routers and APs drop).
     */
    fun resolveBroadcast(broadcast: String, hostname: String): String {
        broadcast.trim().takeIf { it.isNotEmpty() }?.let { return it }
        return autoBroadcast(hostname) ?: LIMITED
    }

    /** The directed broadcast of the phone's interface that shares a subnet with [hostname]. */
    fun autoBroadcast(hostname: String): String? {
        val target = parseIpv4(hostname.trim()) ?: return null
        val ifaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }.getOrDefault(emptyList())
        for (ni in ifaces) {
            if (!runCatching { ni.isUp && !ni.isLoopback }.getOrDefault(false)) continue
            for (ia in ni.interfaceAddresses) {
                val addr = ia.address as? Inet4Address ?: continue
                val prefix = ia.networkPrefixLength.toInt()
                if (prefix !in 1..31) continue
                val ip = parseIpv4(addr.hostAddress ?: continue) ?: continue
                val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
                if ((target and mask) != (ip and mask)) continue
                // Prefer what the OS reports; otherwise derive it from the prefix.
                ia.broadcast?.hostAddress?.let { return it }
                val bc = (ip and mask) or (mask.inv() and 0xFFFFFFFFL)
                return "${bc shr 24}.${(bc shr 16) and 255}.${(bc shr 8) and 255}.${bc and 255}"
            }
        }
        return null
    }

    const val LIMITED = "255.255.255.255"

    /**
     * Where to actually send, given the setting and the host's addresses.
     *
     * An explicit setting is used as given. "Auto" prefers the directed
     * broadcast of whichever interface shares a subnet with one of the host's
     * addresses. When none does — a phone on Wi-Fi and a wired dock, say, with
     * the target only reachable by name — every interface gets its own directed
     * broadcast instead: 255.255.255.255 leaves on the default route alone, so
     * on a multi-homed device it would quietly miss the network the machine is
     * on. Duplicates are dropped; several packets cost nothing.
     */
    fun broadcastTargets(w: WolSettings, candidates: List<String>): List<String> {
        w.broadcast.trim().takeIf { it.isNotEmpty() }?.let { return listOf(it) }
        candidates.firstNotNullOfOrNull { autoBroadcast(it) }?.let { return listOf(it) }
        return allDirectedBroadcasts().ifEmpty { listOf(LIMITED) }
    }

    /** The directed broadcast of every ordinary IPv4 interface this phone has. */
    fun allDirectedBroadcasts(): List<String> {
        val out = LinkedHashSet<String>()
        val ifaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }.getOrDefault(emptyList())
        for (ni in ifaces) {
            // Point-to-point links (VPN tunnels) have no broadcast domain to speak of.
            if (!runCatching { ni.isUp && !ni.isLoopback && !ni.isPointToPoint }.getOrDefault(false)) continue
            for (ia in ni.interfaceAddresses) {
                val addr = ia.address as? Inet4Address ?: continue
                val prefix = ia.networkPrefixLength.toInt()
                if (prefix !in 1..30) continue
                ia.broadcast?.hostAddress?.let { out += it; continue }
                val ip = parseIpv4(addr.hostAddress ?: continue) ?: continue
                val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
                val bc = (ip and mask) or (mask.inv() and 0xFFFFFFFFL)
                out += "${bc shr 24}.${(bc shr 16) and 255}.${(bc shr 8) and 255}.${bc and 255}"
            }
        }
        return out.toList()
    }

    /** RFC 1918 / link-local / loopback addresses — the ones that mean something different per network. */
    fun isPrivateIpv4(s: String): Boolean {
        val v = parseIpv4(s) ?: return false
        val a = (v shr 24).toInt(); val b = ((v shr 16) and 255).toInt()
        return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254) || a == 127
    }

    private fun parseIpv4(s: String): Long? {
        val parts = s.split('.')
        if (parts.size != 4) return null
        var v = 0L
        for (p in parts) {
            val n = p.toIntOrNull() ?: return null
            if (n !in 0..255) return null
            v = (v shl 8) or n.toLong()
        }
        return v
    }

    /**
     * Send from this phone (blocking; call from IO), to every broadcast address
     * that could plausibly reach the machine. Returns what it sent to.
     */
    fun sendFromPhone(w: WolSettings, candidates: List<String> = emptyList()): List<String> {
        val targets = broadcastTargets(w, candidates)
        var failure: Throwable? = null
        for (t in targets) {
            runCatching { dev.flint.term.core.wakeOnLan(normalizeMac(w.mac), t, w.port.toUShort()) }
                .onFailure { failure = it }
        }
        // Only a complete failure is worth reporting: one dead interface out of
        // several is normal on a phone.
        failure?.takeIf { targets.size == 1 }?.let { throw it }
        return targets
    }

    /**
     * A POSIX-ish shell snippet that sends the magic packet from another
     * machine, trying `wakeonlan`, then `etherwake`, then python3, then
     * bash's /dev/udp so it works on most boxes without extra packages.
     */
    fun remoteScript(w: WolSettings, targetHost: String = ""): String {
        val mac = normalizeMac(w.mac)
        val port = w.port.coerceIn(1, 65535)
        val hex = mac.replace(":", "")
        val payloadEsc = "\\xff".repeat(6) + hex.chunked(2).joinToString("") { "\\x$it" }.repeat(16)
        // "Auto": let the sending machine work out which of its interfaces reaches the
        // target and use that interface's directed broadcast, falling back to the limited one.
        val bcAssign = when (val fixed = w.broadcast.trim()) {
            "" -> {
                val probe = targetHost.trim().ifEmpty { "1.1.1.1" }.replace("'", "")
                """I=${'$'}(ip route get '$probe' 2>/dev/null | awk '{for(i=1;i<=NF;i++) if(${'$'}i=="dev"){print ${'$'}(i+1);exit}}'); """ +
                    """B=${'$'}(ip -o -4 addr show dev "${'$'}I" 2>/dev/null | awk '{for(j=1;j<=NF;j++) if(${'$'}j=="brd"){print ${'$'}(j+1);exit}}'); """ +
                    """[ -n "${'$'}B" ] || B='$LIMITED'"""
            }
            else -> "B='$fixed'"
        }
        return """M='$mac'; $bcAssign; P='$port'
if command -v wakeonlan >/dev/null 2>&1; then wakeonlan -i "${'$'}B" -p "${'$'}P" "${'$'}M" && echo "wol: sent with wakeonlan to ${'$'}M via ${'$'}B"
elif command -v python3 >/dev/null 2>&1; then python3 -c 'import socket,sys;m=bytes.fromhex(sys.argv[1].replace(":",""));s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);s.setsockopt(socket.SOL_SOCKET,socket.SO_BROADCAST,1);[s.sendto(b"\xff"*6+m*16,(sys.argv[2],int(sys.argv[3]))) for _ in range(3)];print("wol: sent with python to",sys.argv[1],"via",sys.argv[2])' "${'$'}M" "${'$'}B" "${'$'}P"
elif command -v etherwake >/dev/null 2>&1; then etherwake "${'$'}M" && echo "wol: sent with etherwake to ${'$'}M"
elif [ -n "${'$'}BASH_VERSION" ] || command -v bash >/dev/null 2>&1; then bash -c 'for i in 1 2 3; do printf "%b" "$payloadEsc" > /dev/udp/'"${'$'}B"'/'"${'$'}P"'; done' && echo "wol: sent with bash to ${'$'}M via ${'$'}B"
else echo "wol: no wakeonlan/python3/etherwake/bash on this host" >&2; fi"""
    }
}
