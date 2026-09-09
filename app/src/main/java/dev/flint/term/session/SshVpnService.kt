package dev.flint.term.session

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import dev.flint.term.App
import dev.flint.term.R
import dev.flint.term.core.VpnStats
import dev.flint.term.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The whole phone's traffic, sent out from one of your servers.
 *
 * Android hands a VPN raw IP packets; SSH only knows streams. The Rust core
 * bridges the two (see `crates/tun2ssh`), so every TCP connection the phone
 * makes is re-opened as a channel on the far side and arrives from the server's
 * address — reaching whatever that server can reach, with nothing installed on
 * it. It is sshuttle, run from a phone.
 *
 * Two things are worth knowing before switching it on, and the UI says both:
 * only TCP and DNS cross (SSH cannot carry datagrams, so `ping` and QUIC do
 * not), and this app is left outside the tunnel — its own SSH connection has to
 * go out the ordinary way, or it would be carrying itself.
 */
class SshVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tun: ParcelFileDescriptor? = null
    private var session: TerminalSession? = null
    /**
     * Which run of the tunnel is current.
     *
     * Switching hosts leaves the previous run's watcher in flight; without
     * something to check, it would notice its own session ending and tear down
     * the tunnel that replaced it.
     */
    private var generation = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        val hostId = intent?.getStringExtra(EXTRA_HOST) ?: return START_NOT_STICKY.also { stopSelf() }
        val app = application as App
        val host = app.store.host(hostId) ?: return START_NOT_STICKY.also { stopSelf() }
        // Starting a second tunnel while one is up: Android replaces the tun
        // underneath us, so the old router and its SSH connection have to be
        // let go here or they would sit there holding a dead descriptor.
        release()
        val generation = ++this.generation
        val label = host.displayName
        _state.value = VpnState(hostId = hostId, label = label, connecting = true)
        promote(notification(label, "Connecting…"))

        scope.launch {
            try {
                // The connection that carries the tunnel is made before the tun
                // exists: if the server cannot be reached there is no point
                // capturing the phone's traffic, and doing it in this order
                // means a failure never leaves the phone with a black-hole route.
                val connected = app.sessions.connectHeadless(app.store.effective(host))
                session = connected
                val resolver = app.store.settings.value.vpnResolver.trim().ifBlank { DEFAULT_RESOLVER }
                val builder = Builder()
                    .setSession(label)
                    .setMtu(MTU)
                    .addAddress(LOCAL_ADDRESS, 32)
                    .addRoute("0.0.0.0", 0)
                    // The phone is told to ask an address inside the tunnel, not
                    // the real resolver: Android refuses a loopback DNS server,
                    // and a server's own resolver very often *is* on loopback.
                    // Every query lands on the router either way, which then
                    // asks the configured address from the far side.
                    .addDnsServer(DNS_ADDRESS)
                // Everything this app does must stay outside, or the SSH
                // connection carrying the tunnel would be routed into itself.
                runCatching { builder.addDisallowedApplication(packageName) }
                val pfd = builder.establish() ?: throw IllegalStateException("Android refused to start the VPN")
                tun = pfd
                // The core owns the descriptor from here and closes it, so this
                // hands it over rather than lending it.
                connected.core.startVpn(pfd.detachFd(), MTU.toUInt(), resolver, DNS_ADDRESS)
                _state.value = VpnState(hostId = hostId, label = label, connecting = false)
                watch(connected, label, generation)
            } catch (e: Throwable) {
                Log.w(TAG, "vpn failed", e)
                // A failure only speaks for its own run; a newer one may already
                // be up, and tearing that down would be the wrong answer.
                if (generation == this@SshVpnService.generation) {
                    _state.value = VpnState(error = e.message ?: "could not start the VPN")
                    shutdown()
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Keep the notification honest about what is going through, and notice a dropped session. */
    private suspend fun watch(session: TerminalSession, label: String, generation: Int) {
        while (generation == this.generation) {
            delay(2000)
            if (generation != this.generation) return
            val stats = runCatching { session.core.vpnStats() }.getOrNull()
            if (stats == null || session.isFinished) {
                _state.value = VpnState(error = "the connection to $label ended")
                shutdown()
                return
            }
            _state.value = _state.value.copy(stats = stats)
            promote(notification(label, summary(stats)))
        }
    }

    private fun summary(stats: VpnStats): String {
        val active = if (stats.active == 1uL) "1 connection" else "${stats.active} connections"
        return "$active  ·  ↑ ${bytes(stats.sent)}  ↓ ${bytes(stats.received)}"
    }

    private fun bytes(n: ULong): String {
        val v = n.toDouble()
        return when {
            v < 1024 -> "$n B"
            v < 1024 * 1024 -> String.format("%.0f kB", v / 1024)
            v < 1024 * 1024 * 1024 -> String.format("%.1f MB", v / (1024 * 1024))
            else -> String.format("%.1f GB", v / (1024.0 * 1024 * 1024))
        }
    }

    private fun promote(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notification(label: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, SshVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, App.CHANNEL_SESSIONS)
            .setSmallIcon(R.drawable.ic_terminal)
            .setContentTitle("VPN through $label")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    /** Let go of the tunnel and the connection carrying it, keeping the service. */
    private fun release() {
        runCatching { session?.core?.stopVpn() }
        session?.destroy()
        session = null
        // The core closed the descriptor it was given; this only lets go of the
        // wrapper, which no longer owns anything.
        runCatching { tun?.close() }
        tun = null
    }

    private fun shutdown() {
        release()
        if (_state.value.error == null) _state.value = VpnState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        // Another VPN took over, or the user revoked consent.
        _state.value = VpnState()
        shutdown()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** What the UI shows: which host, whether it is up yet, and what has gone through. */
    data class VpnState(
        val hostId: String? = null,
        val label: String = "",
        val connecting: Boolean = false,
        val stats: VpnStats? = null,
        /** Why the last attempt ended, shown once and then cleared. */
        val error: String? = null,
    ) {
        val running: Boolean get() = hostId != null
    }

    companion object {
        private const val TAG = "SshVpn"
        private const val NOTIFICATION_ID = 3
        private const val ACTION_STOP = "dev.flint.term.VPN_STOP"
        private const val EXTRA_HOST = "host"
        /** Small enough to survive the extra headers of anything the packets cross. */
        private const val MTU = 1400
        /** The phone's address inside the tunnel; nothing else lives on it. */
        private const val LOCAL_ADDRESS = "10.60.0.2"
        /** Where the phone is told to send DNS. Nothing listens there — the router picks it up. */
        private const val DNS_ADDRESS = "10.60.0.1"
        /** Asked over TCP from the server, so internal names resolve internally. */
        const val DEFAULT_RESOLVER = "1.1.1.1"

        private val _state = MutableStateFlow(VpnState())
        val state: StateFlow<VpnState> = _state

        /**
         * Ask Android for consent, if it has not been given. A non-null result
         * is an intent the caller must launch for a result and then start the
         * VPN once it comes back allowed.
         */
        fun consent(context: Context): Intent? = prepare(context)

        fun start(context: Context, hostId: String) {
            val intent = Intent(context, SshVpnService::class.java).putExtra(EXTRA_HOST, hostId)
            runCatching { context.startForegroundService(intent) }
        }

        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, SshVpnService::class.java).setAction(ACTION_STOP)) }
        }

        /** Drop a failure once it has been shown, so it does not haunt the screen. */
        fun clearError() {
            if (_state.value.error != null) _state.value = VpnState()
        }
    }
}
