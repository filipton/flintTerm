package dev.flint.term.session

import dev.flint.term.data.Host
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket

/**
 * What a plain TCP connect had to say about a host.
 *
 * [UNKNOWN] is the resting state and means exactly that — never asked, or a
 * question this cannot answer. It is drawn as nothing at all: a dot that says
 * "unreachable" because nobody looked would be worse than no dot anywhere.
 */
enum class Reach { UNKNOWN, REACHABLE, UNREACHABLE }

object Reachability {

    /**
     * Which of [hosts] may honestly be probed from this phone, right now.
     *
     * The judgment, and the whole point of the feature: a direct connect only
     * means something for a host the phone can dial directly. A host that is
     * reached through a tunnel, a tailnet, a jump host or a proxy would refuse
     * or time out from here while being perfectly healthy behind whatever
     * carries it — so it is skipped and left [Reach.UNKNOWN] rather than
     * accused. A host behind a port knock is the same lie in a different shape:
     * its port is shut until the knock opens it, and the knock belongs to the
     * connection, not to a status dot.
     *
     * Give it hosts as they will be connected — `Store.effective` folds in what
     * a group hands down, and a group's jump host or VPN disqualifies its
     * members just as much as their own would.
     */
    fun probeable(hosts: List<Host>, saving: Boolean, online: Boolean): List<Host> {
        // Nothing to reach, or the data saver has asked for quiet: a status dot
        // is never worth waking the radio for.
        if (!online || saving) return emptyList()
        return hosts.filter { direct(it) }
    }

    /**
     * Whether this one host is dialled straight from the phone, with nothing in
     * the way. Only its own address is asked about: the extra addresses each
     * carry their own route, and choosing between them belongs to the
     * connection rather than to a dot on a list.
     */
    fun direct(host: Host): Boolean =
        host.hostname.isNotBlank() &&
            port(host) in 1..65535 &&
            host.jumpHostId == null &&
            host.tunnelId == null &&
            host.tailscaleId == null &&
            host.proxyId == null &&
            !host.proxy.enabled &&
            !host.knock.enabled

    /** The port a probe would knock on; 0 in the record means the protocol's own. */
    fun port(host: Host): Int = if (host.port == 0) host.defaultPort else host.port

    /** Where a probe went, so an edited address is not answered from the old one's result. */
    fun target(host: Host): String = "${host.hostname.trim()}:${port(host)}"

    /**
     * The one the app uses. Its own scope, because a check outlives the row
     * that asked for it — scrolling away mid-probe should still leave the
     * answer in the cache for the next time the row comes back.
     */
    val shared: ReachabilityCache by lazy {
        ReachabilityCache(CoroutineScope(SupervisorJob() + Dispatchers.IO))
    }
}

/**
 * Remembers which hosts answered, for a couple of minutes.
 *
 * A probe is a TCP connect and nothing more: no banner is read and no login is
 * attempted, so a host learns only that something opened a socket, which is
 * what any port scan on the network is doing anyway.
 */
class ReachabilityCache(
    private val scope: CoroutineScope,
    /** Long enough that scrolling does not re-probe, short enough to follow a laptop waking up. */
    private val ttlMs: Long = 2 * 60 * 1000L,
    /** A server either answers a syn quickly or is not there; waiting longer only delays the dot. */
    private val timeoutMs: Int = 2_000,
    private val now: () -> Long = System::currentTimeMillis,
    private val connect: (String, Int, Int) -> Boolean = ::tcpAnswers,
) {
    private class Seen(val target: String, val at: Long, val reach: Reach)

    private val seen = HashMap<String, Seen>()
    private val running = HashSet<String>()
    private val _state = MutableStateFlow<Map<String, Reach>>(emptyMap())

    /** Host id to what was found, for the rows to read. */
    val state: StateFlow<Map<String, Reach>> = _state

    /**
     * Check the ones that need it among [hosts] — the rows on screen, since a
     * list of two hundred servers is not something to open sockets to because
     * it exists.
     */
    fun probe(hosts: List<Host>, saving: Boolean, online: Boolean) {
        val due = Reachability.probeable(hosts, saving, online).filter { stale(it) }
        if (due.isEmpty()) return
        synchronized(seen) { due.forEach { running += it.id } }
        due.forEach { host ->
            val target = Reachability.target(host)
            scope.launch {
                val reach = if (connect(host.hostname.trim(), Reachability.port(host), timeoutMs)) {
                    Reach.REACHABLE
                } else {
                    Reach.UNREACHABLE
                }
                synchronized(seen) {
                    seen[host.id] = Seen(target, now(), reach)
                    running -= host.id
                    _state.value = seen.mapValues { it.value.reach }
                }
            }
        }
    }

    /** Everything is asked again: what a pull on the list means. */
    fun invalidate() {
        synchronized(seen) {
            seen.clear()
            _state.value = emptyMap()
        }
    }

    private fun stale(host: Host): Boolean = synchronized(seen) {
        if (host.id in running) return@synchronized false
        val last = seen[host.id] ?: return@synchronized true
        last.target != Reachability.target(host) || now() - last.at >= ttlMs
    }
}

/**
 * True when something accepted a connection on that port.
 *
 * Everything else is one answer: a refusal, a timeout and a name that does not
 * resolve all mean "not from here, not now", and splitting them would only put
 * a distinction on screen that nobody can act on.
 */
private fun tcpAnswers(hostname: String, port: Int, timeoutMs: Int): Boolean = runCatching {
    Socket().use { socket ->
        socket.connect(InetSocketAddress(hostname, port), timeoutMs)
        true
    }
}.getOrDefault(false)
