package dev.flint.term.session

import android.content.Context
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import dev.flint.term.data.Host
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.Inet4Address
import kotlin.coroutines.resume

/** An SSH server announcing itself on the network this phone is on. */
data class NearbyService(
    /** What it calls itself in its announcement: "rig", "pi-hole". */
    val name: String,
    val address: String,
    val port: Int,
)

/**
 * Servers found by mDNS, and which of them are worth showing.
 *
 * The rules live here rather than in the listener callbacks so that they can be
 * read and tested on their own: what comes back from [NsdManager] cannot be, on
 * any machine without another machine beside it.
 */
object Discovery {
    /** Trailing dot and all, as NsdManager writes service types. */
    const val SSH_TYPE = "_ssh._tcp."
    const val SFTP_TYPE = "_sftp-ssh._tcp."

    /** Android 17 puts everything on the local network behind this. */
    const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

    /** A found service as the host editor should open it. */
    fun host(service: NearbyService): Host = Host(
        label = service.name.trim(),
        hostname = service.address,
        port = service.port,
    )

    /**
     * The announcements worth putting in front of someone.
     *
     * A machine already saved is not news — and a box announcing both `_ssh` and
     * `_sftp-ssh` on the same port is one machine, not two, so the same address
     * is only ever offered once. An alternate address counts as saved too: a
     * host reachable at 192.168.1.10 as its second address is the same server
     * whichever one of them mDNS happened to hear.
     */
    fun offer(found: List<NearbyService>, saved: List<Host>): List<NearbyService> {
        fun known(address: String, port: Int) = saved.any { h ->
            (h.hostname.equals(address, true) && h.port == port) ||
                h.addresses.any { a -> a.hostname.equals(address, true) && (if (a.port == 0) h.port else a.port) == port }
        }
        return found
            .filter { it.address.isNotBlank() && it.port in 1..65535 && !known(it.address, it.port) }
            .distinctBy { it.address.lowercase() to it.port }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.address }))
    }

    /** Whether this device would refuse to browse without being asked first. */
    fun needsPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN &&
            context.checkSelfPermission(LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
}

/**
 * Browses `_ssh._tcp` and `_sftp-ssh._tcp` for a while, and stops.
 *
 * Discovery is a registration with the system rather than a coroutine: it runs,
 * and keeps the radio listening for multicast, until it is told to stop. So
 * there is a timer for the usual case and a [stop] for the screen going away,
 * and exactly one registered listener per service type — registering the same
 * listener twice throws, and registering a second one for the same type would
 * leak the first.
 */
class NearbyScanner(context: Context, private val scope: CoroutineScope) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val _found = MutableStateFlow<List<NearbyService>>(emptyList())
    val found: StateFlow<List<NearbyService>> = _found
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    /** The listener currently registered for each service type. */
    private val listeners = mutableMapOf<String, NsdManager.DiscoveryListener>()
    private var timer: Job? = null

    /**
     * Found services waiting to be resolved. Before API 34 a resolve is a
     * single-slot operation — a second one while the first is in flight comes
     * back as FAILURE_ALREADY_ACTIVE — so they go through one at a time.
     */
    private val toResolve = Channel<NsdServiceInfo>(Channel.UNLIMITED)
    private var resolver: Job? = null

    @Synchronized
    fun scan(seconds: Int = 10) {
        val nsd = nsd ?: return
        timer?.cancel()
        if (resolver == null) resolver = scope.launch { for (info in toResolve) resolve(info) }
        for (type in listOf(Discovery.SSH_TYPE, Discovery.SFTP_TYPE)) {
            if (listeners.containsKey(type)) continue
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) {}
                override fun onStartDiscoveryFailed(type: String, code: Int) = forget(type, this)
                override fun onStopDiscoveryFailed(type: String, code: Int) = forget(type, this)
                override fun onDiscoveryStopped(type: String) = forget(type, this)
                override fun onServiceFound(info: NsdServiceInfo) { toResolve.trySend(info) }
                override fun onServiceLost(info: NsdServiceInfo) {}
            }
            listeners[type] = listener
            runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
                .onFailure { listeners.remove(type) }
        }
        _scanning.value = listeners.isNotEmpty()
        if (_scanning.value) timer = scope.launch { delay(seconds * 1000L); stop() }
    }

    /** Stops every browse in flight. Safe to call when none is. */
    @Synchronized
    fun stop() {
        timer?.cancel()
        timer = null
        val nsd = nsd
        // Taken out of the map first: what is stopped here must never be stopped
        // twice, and a scan that starts before the system confirms the stop
        // registers a listener of its own rather than finding this one in the way.
        val stopping = listeners.values.toList()
        listeners.clear()
        _scanning.value = false
        stopping.forEach { runCatching { nsd?.stopServiceDiscovery(it) } }
    }

    /** Drop a listener the system is done with, unless a later scan replaced it. */
    @Synchronized
    private fun forget(type: String, listener: NsdManager.DiscoveryListener) {
        if (listeners[type] === listener) {
            listeners.remove(type)
            _scanning.value = listeners.isNotEmpty()
        }
    }

    // resolveService gave way to a callback in API 34; the deprecated call is
    // still the one path that works from 26 up.
    @Suppress("DEPRECATION")
    private suspend fun resolve(info: NsdServiceInfo) {
        val nsd = nsd ?: return
        val resolved = suspendCancellableCoroutine<NsdServiceInfo?> { cont ->
            nsd.resolveService(
                info,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        if (cont.isActive) cont.resume(info)
                    }
                },
            )
        } ?: return
        val name = resolved.serviceName.orEmpty().trim()
        val address = addressOf(resolved)
        if (name.isEmpty() || address == null || resolved.port !in 1..65535) return
        val service = NearbyService(name, address, resolved.port)
        _found.value = _found.value.let { list ->
            if (list.any { it.address == service.address && it.port == service.port }) list else list + service
        }
    }

    /** The address to dial, IPv4 first: it is the one a LAN server is really at. */
    @Suppress("DEPRECATION")
    private fun addressOf(info: NsdServiceInfo): String? {
        val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) info.hostAddresses else listOfNotNull(info.host)
        val best = addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull()
        return best?.hostAddress?.takeIf { it.isNotBlank() }
    }
}

/**
 * A scanner that browses while the screen asking for it is on screen.
 *
 * The stop is the whole point of tying it to composition: a browse nobody is
 * looking at is multicast traffic and a radio kept awake for nothing.
 */
@Composable
fun rememberNearbyScanner(enabled: Boolean): NearbyScanner {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scanner = remember { NearbyScanner(context.applicationContext, scope) }
    // Android 17 wants to be asked before an app may talk to the local network.
    // Asked for here, where the answer is about something visible happening,
    // rather than at startup where it would be one more dialog to dismiss.
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanner.scan()
    }
    DisposableEffect(scanner, enabled) {
        if (enabled) {
            if (Discovery.needsPermission(context)) ask.launch(Discovery.LOCAL_NETWORK) else scanner.scan()
        }
        onDispose { scanner.stop() }
    }
    return scanner
}
