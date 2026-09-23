package dev.flint.term

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dev.flint.term.core.initLogging
import dev.flint.term.data.Store
import dev.flint.term.session.SessionManager
import dev.flint.term.session.TailscaleManager
import dev.flint.term.session.TunnelManager
import dev.flint.term.transfer.TransferManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class App : Application() {
    private val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    lateinit var store: Store
        private set
    lateinit var sessions: SessionManager
        private set
    /**
     * Built on first use rather than in [onCreate]: the process is also started
     * for a quick settings tile, a widget or a reconnect in the background, and
     * none of those move a file.
     */
    val transfers: TransferManager by lazy {
        TransferManager(this).apply {
            // Transfers are the one thing worth holding back on mobile data.
            holdForWifi = { sessions.holdTransfers() }
            awaitUnmetered = { network.state.first { !it.metered && it.online } }
        }
    }
    lateinit var tunnels: TunnelManager
        private set
    /**
     * Built when a tailnet exists or a screen asks, not at every start: a
     * running manager watches the network and polls the embedded node once a
     * minute, which is battery spent on nothing for anybody without a tailnet.
     * Sessions only look for it through [SessionManager.tailscale], so building
     * it is what hands it to them.
     */
    val tailscale: TailscaleManager by lazy {
        TailscaleManager(this, store).also { sessions.tailscale = it }
    }
    /** Only a USB device, or the hosts screen looking for one, needs this. */
    val serial: dev.flint.term.session.SerialManager by lazy { dev.flint.term.session.SerialManager(this) }
    lateinit var network: dev.flint.term.session.NetworkMonitor
        private set
    /** What was open last time, and the record of what is open now. */
    lateinit var open: dev.flint.term.session.OpenSessions
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        initLogging(BuildConfig.DEBUG)
        store = Store(this)
        tunnels = TunnelManager(store)
        network = dev.flint.term.session.NetworkMonitor(this)
        sessions = SessionManager(this, store, network)
        open = dev.flint.term.session.OpenSessions(java.io.File(filesDir, "open.json"), appScope)
        sessions.tunnels = tunnels
        // A host can only point at a tailnet that exists, so the manager has to
        // be there before any session starts once there is one — now, if the
        // store already has it, or the moment one arrives (added, or restored
        // from a backup) otherwise.
        if (store.tailscaleProfiles.value.isNotEmpty()) {
            tailscale
        } else {
            appScope.launch {
                store.tailscaleProfiles.first { it.isNotEmpty() }
                tailscale
            }
        }
        sessions.openSessions = open
        // Six hundred palettes in an asset: parsed once, here, off the main
        // thread, so no session or screen ever waits on the file. A session
        // restored before it landed was drawn in the default and is repainted.
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val text = runCatching { assets.open("schemes.txt").bufferedReader().use { it.readText() } }.getOrDefault("")
            dev.flint.term.data.Schemes.install(dev.flint.term.data.Schemes.parseCatalog(text))
            sessions.applyTheme()
        }
        registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: android.app.Activity) { foregroundActivities++ }
            override fun onActivityStopped(a: android.app.Activity) {
                foregroundActivities--
                if (foregroundActivities <= 0) {
                    lastBackgroundedAt = System.currentTimeMillis()
                    // Leaving the screen is the last notice Android gives before
                    // it may kill the process, so a change still waiting out the
                    // store's quiet period is written now rather than lost.
                    store.flush()
                }
            }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            // A biometric prompt needs an activity to attach to, and the only
            // one it may attach to is the one the person is looking at.
            override fun onActivityResumed(a: android.app.Activity) {
                resumed = java.lang.ref.WeakReference(a as? androidx.fragment.app.FragmentActivity)
            }
            override fun onActivityPaused(a: android.app.Activity) {
                if (resumed?.get() === a) resumed = null
            }
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
        // WireGuard's timers would otherwise keep retrying handshakes with no
        // link at all, which is pure battery and data on a spotty connection.
        appScope.launch {
            network.state.collect { st ->
                runCatching { dev.flint.term.core.setTunnelsNetworkUp(st.online) }
            }
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SESSIONS, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TRANSFERS, getString(R.string.notification_channel_transfers), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "Terminal alerts", NotificationManager.IMPORTANCE_DEFAULT))
    }

    companion object {
        const val CHANNEL_SESSIONS = "sessions"
        const val CHANNEL_TRANSFERS = "transfers"
        const val CHANNEL_ALERTS = "alerts"
        @Volatile var foregroundActivities: Int = 0
        @Volatile var lastBackgroundedAt: Long = 0
        val inForeground: Boolean get() = foregroundActivities > 0
        /** Weakly held: the activity may be destroyed while a session runs on. */
        @Volatile private var resumed: java.lang.ref.WeakReference<androidx.fragment.app.FragmentActivity>? = null
        /**
         * The activity a dialog can be shown on, or null when nothing of ours is
         * on screen — a background reconnect, or the Files app browsing over
         * SFTP with the app itself closed.
         */
        val foregroundActivity: androidx.fragment.app.FragmentActivity?
            get() = resumed?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
        /** Prefilled values for the next "new host" editor, e.g. from an ssh:// link. */
        @Volatile var hostDraft: dev.flint.term.data.Host? = null
        /** Files handed to us through the share sheet, waiting for a host and a folder. */
        val pendingShare = androidx.compose.runtime.mutableStateListOf<android.net.Uri>()
        lateinit var instance: App
            private set
    }
}
