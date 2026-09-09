package dev.flint.term.session

import android.util.Log
import dev.flint.term.core.TunnelInfo
import dev.flint.term.core.TunnelStats
import dev.flint.term.core.parseTunnelConfig
import dev.flint.term.core.registerTunnel
import dev.flint.term.core.startTunnel
import dev.flint.term.core.stopTunnel
import dev.flint.term.core.tunnelStats
import dev.flint.term.core.unregisterTunnel
import dev.flint.term.data.Store
import dev.flint.term.data.Tunnel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps the Rust tunnel registry in sync with the store and exposes live
 * stats. Tunnels start lazily when a session needs them; the Tunnels screen
 * can also start/stop them by hand.
 */
class TunnelManager(private val store: Store) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _stats = MutableStateFlow<Map<String, TunnelStats>>(emptyMap())
    val stats: StateFlow<Map<String, TunnelStats>> = _stats
    /** Parsed details per tunnel id (addresses, endpoint, public key…). */
    private val _info = MutableStateFlow<Map<String, TunnelInfo>>(emptyMap())
    val info: StateFlow<Map<String, TunnelInfo>> = _info

    init {
        scope.launch { store.tunnels.collect { list -> sync(list) } }
    }

    private fun sync(list: List<Tunnel>) {
        val infos = HashMap<String, TunnelInfo>()
        for (t in list) {
            runCatching { registerTunnel(t.id, t.config) }
                .onSuccess { infos[t.id] = it }
                .onFailure { Log.w("TunnelManager", "tunnel ${t.name}: ${it.message}") }
        }
        val known = list.map { it.id }.toSet()
        _info.value.keys.filterNot { it in known }.forEach { runCatching { unregisterTunnel(it) } }
        _info.value = infos
        refreshStats()
    }

    fun refreshStats() {
        _stats.value = store.tunnels.value.associate { it.id to tunnelStats(it.id) }
    }

    /** Validate a config without saving it. Returns the error message on failure. */
    fun validate(config: String): Result<TunnelInfo> = runCatching { parseTunnelConfig(config) }

    suspend fun start(id: String): Result<TunnelStats> = withContext(Dispatchers.IO) {
        runCatching { startTunnel(id) }.also { refreshStats() }
    }

    suspend fun stop(id: String) = withContext(Dispatchers.IO) {
        runCatching { stopTunnel(id) }
        refreshStats()
    }

    /**
     * Check whether a tunnel actually works: bring it up if it is down, wait for
     * a WireGuard handshake, and leave it as it was found. A handshake is the
     * real test — it proves the endpoint answered and the keys match, which is
     * what almost every broken config gets wrong.
     */
    suspend fun test(id: String): Result<String> = withContext(Dispatchers.IO) {
        val wasRunning = tunnelStats(id).running
        val started = runCatching { startTunnel(id) }
        if (started.isFailure) {
            refreshStats()
            return@withContext Result.failure(started.exceptionOrNull() ?: Exception("could not start"))
        }
        try {
            val started = System.currentTimeMillis()
            val deadline = started + HANDSHAKE_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val s = tunnelStats(id)
                val handshake = s.lastHandshakeSecs
                if (handshake != null && handshake < 60u) {
                    // Not the byte counters: a handshake moves no payload, so those
                    // read zero here and would make a working tunnel look broken.
                    val took = System.currentTimeMillis() - started
                    return@withContext Result.success(
                        "Handshake completed in ${took} ms  ·  ${s.endpoint ?: "peer"}",
                    )
                }
                kotlinx.coroutines.delay(300)
            }
            Result.failure(
                Exception(
                    "No handshake within ${HANDSHAKE_TIMEOUT_MS / 1000}s. The endpoint may be unreachable, " +
                        "the port blocked, or the keys may not match.",
                ),
            )
        } finally {
            // Leave things as they were: a test should not silently start a VPN.
            if (!wasRunning) runCatching { stopTunnel(id) }
            refreshStats()
        }
    }

    private companion object {
        const val HANDSHAKE_TIMEOUT_MS = 8000L
    }
}
