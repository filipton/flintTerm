package dev.flint.term.session

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import dev.flint.term.core.TailscaleStatus
import dev.flint.term.core.tailscaleAvailable
import dev.flint.term.core.tailscaleConfigure
import dev.flint.term.core.tailscaleDown
import dev.flint.term.core.tailscaleForget
import dev.flint.term.core.tailscaleSetInterfaces
import dev.flint.term.core.tailscaleStatus
import dev.flint.term.core.tailscaleUp
import dev.flint.term.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.NetworkInterface

/**
 * The embedded Tailscale nodes, one per profile in the store.
 *
 * Each profile is a separate tsnet node with its own state directory, so a work
 * tailnet and a home one can be used side by side. A node starts when a host
 * that uses it connects and stops once nothing needs it, so having several
 * configured costs nothing while they are idle.
 */
class TailscaleManager(private val context: Context, private val store: Store) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Live status per profile id; a profile that never started reads as "stopped". */
    private val _statuses = MutableStateFlow<Map<String, TailscaleStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, TailscaleStatus>> = _statuses

    /**
     * Android's seccomp policy bans the pidfd syscalls before Android 12, and the
     * Go runtime inside libtailscale probes for them on startup. Go guards that
     * probe with its own SIGSYS handling, but in a `c-shared` library loaded into
     * an ART process it does not own the handler, so the probe kills the process
     * (SIGSYS, "disallowed system call 424"). Ignoring SIGSYS from our side does
     * not help — it is re-armed before the probe runs — so nodes are simply not
     * offered here, with an explanation, rather than crashing the app.
     */
    val supportedHere: Boolean = Build.VERSION.SDK_INT >= 31

    val available: Boolean = tailscaleAvailable() && supportedHere

    /** Why Tailscale is unavailable, for the UI to show. */
    val unavailableReason: String? = when {
        !tailscaleAvailable() -> "Run build-tailscale.sh before building the app to embed Tailscale."
        !supportedHere -> "Needs Android 12 or newer: below that, Android blocks a system call the " +
            "embedded node makes at startup, which would take the whole app down."
        else -> null
    }

    /** Profiles the user asked to keep up, rather than ones a session started. */
    private val byHand = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Profiles brought up only to get through a first login. */
    private val joining = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    init {
        if (available) {
            migrateSingleNodeLayout()
            pushInterfaces()
            runCatching {
                val cm = context.getSystemService(ConnectivityManager::class.java)
                cm.registerNetworkCallback(NetworkRequest.Builder().build(), object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = pushInterfaces()
                    override fun onLost(network: Network) = pushInterfaces()
                    override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) = pushInterfaces()
                })
            }
        }
        // Nothing is started here: a profile means "may be used", not "keep a
        // node up". Cheap poll; the screens show live state and sessions wait
        // for "running".
        scope.launch {
            while (true) {
                refresh()
                val busy = _statuses.value.values.any { it.state == "starting" || it.state == "needs-login" }
                delay(if (busy) 1500 else 5000)
            }
        }
    }

    fun status(id: String): TailscaleStatus =
        _statuses.value[id] ?: TailscaleStatus("stopped", null, emptyList(), null, null, emptyList())

    fun refresh() {
        if (!available) return
        val next = store.tailscaleProfiles.value.associate { it.id to tailscaleStatus(it.id) }
        _statuses.value = next
        for ((id, st) in next) {
            if (st.state != "running") continue
            // Remember that the login went through: a stopped node's status says
            // nothing about whether it ever joined a tailnet.
            val p = store.tailscaleProfile(id) ?: continue
            if (!p.joined) store.upsertTailscaleProfile(p.copy(joined = true))
            if (joining.remove(id)) byHand.remove(id) // done joining; back to on demand
        }
    }

    /** Java can list interfaces where netlink cannot; tsnet reads this list through our Go glue. */
    fun pushInterfaces() {
        if (!available) return
        val spec = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().joinToString("\n") { ni ->
                val addrs = ni.interfaceAddresses.joinToString(" ") { ia ->
                    val host = ia.address.hostAddress?.substringBefore('%') ?: return@joinToString ""
                    "$host/${ia.networkPrefixLength}"
                }
                val flags = listOf(ni.isUp, !ni.isLoopback && !ni.isPointToPoint, ni.isLoopback, ni.isPointToPoint, ni.supportsMulticast())
                    .joinToString(",") { if (it) "1" else "0" }
                "${ni.name}|${ni.index}|${runCatching { ni.mtu }.getOrDefault(1500)}|$flags|$addrs"
            }
        }.getOrElse { Log.w("Tailscale", "interfaces: ${it.message}"); return }
        runCatching { tailscaleSetInterfaces(spec) }
    }

    /** True while this profile's node is up because the user asked, not because a session did. */
    fun startedByHand(id: String): Boolean = id in byHand

    /** Bring a node up and keep it up until the user says otherwise. */
    fun upByHand(id: String) {
        byHand += id
        ensureUp(id)
    }

    /**
     * Bring a node up only long enough to join a tailnet. Once it is running the
     * hand-hold is released, so it goes back to coming up on demand.
     */
    fun upToJoin(id: String) {
        joining += id
        byHand += id
        ensureUp(id)
    }

    /**
     * Join with a pre-auth key.
     *
     * tsnet only honours an auth key when it starts from an empty state
     * directory: with anything already there it logs "Ignoring authkey" and
     * waits for an interactive login instead. So a key that was mistyped, or one
     * for the wrong tailnet, cannot be corrected by pasting a new one — the
     * half-written state has to go first. Nothing is lost, because the node has
     * not joined anything yet.
     */
    fun joinWithKey(id: String, key: String) {
        scope.launch {
            runCatching { tailscaleDown(id) }
            val p = store.tailscaleProfile(id) ?: return@launch
            if (!p.joined) stateDir(id).deleteRecursively()
            store.upsertTailscaleProfile(p.copy(authKey = key.trim()))
            delay(200)
            upToJoin(id)
        }
    }

    /**
     * Bring a node down if it is only up to serve sessions and none need it any
     * more. One the user started by hand is left alone.
     */
    fun downIfIdle(id: String) {
        if (!available || id in byHand) return
        if (status(id).state == "stopped") return
        down(id)
    }

    fun ensureUp(id: String) {
        if (!available) return
        scope.launch {
            pushInterfaces()
            // A node that failed to start cannot be restarted in place; rebuild it.
            if (status(id).state == "error") runCatching { tailscaleDown(id) }
            val p = store.tailscaleProfile(id) ?: return@launch
            val hostname = p.hostname.ifBlank { defaultHostname() }
            val dir = stateDir(id).apply { mkdirs() }
            runCatching { tailscaleConfigure(id, dir.absolutePath, hostname, p.authKey.ifBlank { null }, p.controlUrl.ifBlank { null }) }
                .onFailure { Log.w("Tailscale", "configure ${p.name}: ${it.message}") }
            runCatching { tailscaleUp(id) }.onFailure { Log.w("Tailscale", "up ${p.name}: ${it.message}") }
            refresh()
        }
    }

    /**
     * Start the node if needed and wait for it to be usable.
     *
     * The node is only brought up for the connection that needs it, and starting
     * it takes a moment (key exchange with the control plane, then a DERP or
     * direct path). Without this wait a session would race it and fail with
     * "tailscale is not connected yet". Returns false on a timeout or when nodes
     * cannot run here, leaving the caller to try anyway and report the real error.
     */
    suspend fun awaitUp(id: String, timeoutMs: Long = 45_000): Boolean {
        if (!available) return false
        if (status(id).state == "running") return true
        ensureUp(id)
        return withTimeoutOrNull(timeoutMs) {
            statuses.first { m -> (m[id]?.state ?: "stopped").let { it == "running" || it == "error" } }[id]?.state == "running"
        } == true
    }

    /**
     * Check whether this account actually works: bring its node up if it is
     * down, wait for it to reach the tailnet, and leave it as it was found —
     * the same question the WireGuard "Test connection" answers, which is what
     * a VPN screen should be able to say without connecting to a host.
     */
    suspend fun test(id: String): Result<String> {
        if (!available) return Result.failure(Exception(unavailableReason ?: "Tailscale is not available"))
        val p = store.tailscaleProfile(id) ?: return Result.failure(Exception("unknown account"))
        if (!p.joined) return Result.failure(Exception("Not joined to a tailnet yet — log in or paste an auth key first."))
        val wasRunning = status(id).state == "running"
        val started = System.currentTimeMillis()
        try {
            if (!awaitUp(id, TEST_TIMEOUT_MS)) {
                val st = status(id)
                return Result.failure(
                    Exception(
                        st.error?.let { "Could not connect: $it" }
                            ?: "No tailnet connection within ${TEST_TIMEOUT_MS / 1000}s. The device may be offline, or the node may need logging in again.",
                    ),
                )
            }
            val st = status(id)
            val took = System.currentTimeMillis() - started
            val online = st.peers.count { it.online }
            return Result.success(
                buildString {
                    append("Connected in $took ms")
                    st.selfName?.let { append("  ·  ").append(it.substringBefore('.')) }
                    st.ips.firstOrNull()?.let { append("  ·  ").append(it) }
                    append("  ·  ").append(online).append(" of ").append(st.peers.size).append(" devices online")
                },
            )
        } finally {
            // A test should not silently leave a VPN running.
            if (!wasRunning) down(id)
        }
    }

    fun down(id: String) {
        byHand -= id
        joining -= id
        scope.launch {
            runCatching { tailscaleDown(id) }
            refresh()
        }
    }

    /** Stop and start again with the current settings (machine name / control server changed). */
    fun restart(id: String) {
        scope.launch {
            runCatching { tailscaleDown(id) }
            delay(300)
            ensureUp(id)
        }
    }

    /**
     * Leave the tailnet: forget the node identity and the stored auth key, so the
     * next start begins from scratch. Keeping the key would silently rejoin the
     * tailnet the user just left.
     */
    fun logout(id: String) {
        scope.launch {
            runCatching { tailscaleDown(id) }
            stateDir(id).deleteRecursively()
            byHand -= id
            joining -= id
            store.tailscaleProfile(id)?.let { store.upsertTailscaleProfile(it.copy(authKey = "", joined = false)) }
            refresh()
        }
    }

    /** Drop a profile's node entirely; the profile itself is deleted by the caller. */
    fun forget(id: String) {
        scope.launch {
            runCatching { tailscaleForget(id) }
            stateDir(id).deleteRecursively()
            byHand -= id
            joining -= id
            refresh()
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 25_000L
    }

    private fun defaultHostname(): String =
        "androidterm-" + Build.MODEL.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    private fun stateDir(id: String): File = File(File(context.filesDir, "tailscale"), id)

    /**
     * Older builds kept the single node's state directly in `files/tailscale`.
     * Move it under the migrated profile's id so that node keeps its identity
     * instead of having to join the tailnet all over again.
     */
    private fun migrateSingleNodeLayout() {
        val root = File(context.filesDir, "tailscale")
        if (!File(root, "tailscaled.state").isFile) return
        val dest = File(root, Store.LEGACY_TAILSCALE_ID).apply { mkdirs() }
        root.listFiles()?.forEach { f ->
            if (f.name != Store.LEGACY_TAILSCALE_ID) runCatching { f.renameTo(File(dest, f.name)) }
        }
    }
}
