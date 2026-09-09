package dev.flint.term.session

import dev.flint.term.data.Host
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** One knock: which port, and whether the packet is TCP or UDP. */
data class KnockStep(val port: Int, val udp: Boolean = false) {
    override fun toString(): String = if (udp) "$port/udp" else port.toString()
}

/** A sequence ready to send: where it goes, what it is, and the two waits around it. */
data class KnockPlan(val hostname: String, val steps: List<KnockStep>, val delayMs: Int, val pauseMs: Int)

/**
 * Port knocking: a sequence of packets at closed ports that tells a firewall to
 * let this address in, sent just before the connection is dialed.
 *
 * It leaves the phone directly, so it can only open a firewall the phone
 * reaches directly — not one behind a jump host, a WireGuard tunnel or a proxy,
 * which the host editor says in as many words.
 */
object PortKnock {
    /**
     * Long enough for a SYN to leave, short enough not to hold the connection
     * up. Nothing waits for an answer: there is nothing listening on a knocked
     * port, which is the point of knocking at it.
     */
    private const val TCP_TIMEOUT_MS = 300

    /**
     * The steps written in [text], in the order they are written.
     *
     * Anything that is not a port is left out rather than guessed at; [problem]
     * is what names it to whoever typed it. Repeats are kept, because a sequence
     * may legitimately knock the same port twice and dropping the second one
     * would send something the server is not listening for.
     */
    fun parse(text: String): List<KnockStep> = tokens(text).mapNotNull(::step)

    /** Back to the stored text form, so a sequence reads the way it is sent. */
    fun format(steps: List<KnockStep>): String = steps.joinToString(", ")

    /**
     * Why [text] is not a usable sequence, or null when it is. One sentence, for
     * the field's error line.
     */
    fun problem(text: String): String? {
        val tokens = tokens(text)
        if (tokens.isEmpty()) return "Add at least one port"
        val bad = tokens.firstOrNull { step(it) == null } ?: return null
        return "“$bad” is not a port between 1 and 65535"
    }

    /** What to send for [host], or null when there is nothing worth sending. */
    fun plan(host: Host): KnockPlan? {
        val knock = host.knock
        if (!knock.enabled) return null
        val steps = parse(knock.sequence)
        val where = host.hostname.trim()
        if (steps.isEmpty() || where.isEmpty()) return null
        return KnockPlan(where, steps, knock.delayMs.coerceIn(0, 10_000), knock.pauseMs.coerceIn(0, 10_000))
    }

    /**
     * Send the sequence and wait out the pause. Blocking; call from IO.
     *
     * Returns what went wrong, or null when the knocks went out. Only a name
     * that will not resolve counts as wrong: a knocked port is closed by
     * definition, so a refusal and a timeout are the two shapes success comes
     * in, and neither says anything about whether the firewall was listening.
     */
    fun send(plan: KnockPlan): String? {
        val address = runCatching { InetAddress.getByName(plan.hostname) }
            .getOrElse { return it.message?.ifBlank { null } ?: "could not resolve ${plan.hostname}" }
        plan.steps.forEachIndexed { i, step ->
            if (i > 0) pause(plan.delayMs)
            knock(address, step)
        }
        // The firewall rule is written after the last packet arrives, so the
        // connection has to give it that moment or it knocks at a closed door.
        pause(plan.pauseMs)
        return null
    }

    private fun knock(address: InetAddress, step: KnockStep) {
        runCatching {
            if (step.udp) {
                DatagramSocket().use { it.send(DatagramPacket(ByteArray(0), 0, address, step.port)) }
            } else {
                // The SYN is the whole message, so the socket is dropped as soon
                // as it has been sent.
                Socket().use { it.connect(InetSocketAddress(address, step.port), TCP_TIMEOUT_MS) }
            }
        }
    }

    private fun pause(ms: Int) {
        if (ms > 0) runCatching { Thread.sleep(ms.toLong()) }
    }

    /** Commas or spaces, either way: the sequence is copied out of a knockd config as often as typed. */
    private fun tokens(text: String): List<String> =
        text.split(',', ' ', '\t', '\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }

    private fun step(token: String): KnockStep? {
        val udp = when {
            token.endsWith("/udp", ignoreCase = true) -> true
            token.endsWith("/tcp", ignoreCase = true) -> false
            // Anything else after a slash is a protocol we cannot send, or a
            // range, which knockd does not take either.
            token.contains('/') -> return null
            else -> false
        }
        val port = token.substringBefore('/').takeIf { it.all(Char::isDigit) }?.toIntOrNull() ?: return null
        return if (port in 1..65535) KnockStep(port, udp) else null
    }
}
