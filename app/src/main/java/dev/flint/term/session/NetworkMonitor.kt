package dev.flint.term.session

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/** What the phone's connection looks like right now. */
data class NetworkState(
    /** There is a usable network. False on a train through a tunnel. */
    val online: Boolean = true,
    /** The connection is metered — mobile data, or a hotspot marked as such. */
    val metered: Boolean = false,
    /** Roughly Wi-Fi or Ethernet, as opposed to cellular. */
    val unmeteredKind: Boolean = true,
) {
    val describe: String
        get() = when {
            !online -> "offline"
            metered -> "metered"
            else -> "unmetered"
        }
}

/**
 * Tracks connectivity so the rest of the app can stop wasting data and battery
 * on a link that is not there.
 *
 * Android only tells you about *changes*, so the initial value is read once at
 * construction; after that the callback keeps it current.
 */
class NetworkMonitor(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _state = MutableStateFlow(NetworkState())
    val state: StateFlow<NetworkState> = _state

    val online: Boolean get() = _state.value.online
    val metered: Boolean get() = _state.value.metered

    init {
        refresh()
        runCatching {
            cm?.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = refresh()
                    override fun onLost(network: Network) = refresh()
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh()
                },
            )
        }
    }

    /** Re-read the current network. Cheap; called from the callback. */
    fun refresh() {
        val active = cm?.activeNetwork
        val caps = active?.let { runCatching { cm.getNetworkCapabilities(it) }.getOrNull() }
        val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        // `NOT_METERED` is the authoritative answer; the transport is a fallback
        // for the case where capabilities are not published yet.
        val notMetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        val wifiLike = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        _state.value = NetworkState(
            // With no capabilities at all, assume we are online rather than
            // blocking everything: a wrong "offline" is worse than a wasted try.
            online = if (caps == null) active != null else online,
            metered = caps != null && !notMetered,
            unmeteredKind = wifiLike,
        )
    }

    /** Suspend until there is a network again. Returns at once when online. */
    suspend fun awaitOnline() {
        if (online) return
        state.first { it.online }
    }
}
