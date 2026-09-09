package dev.flint.term.session

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.flint.term.App
import dev.flint.term.R
import dev.flint.term.ui.MainActivity

/**
 * Foreground service that exists only to keep the process (and therefore the
 * SSH sockets living in the Rust runtime) alive while sessions are open.
 */
class SessionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    /** Set by a start command that came from `startForegroundService()`. */
    private var awaitingPromotion = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as App
        // Only the plain start comes from startForegroundService(); the stop
        // actions arrive via startService() and carry no promotion obligation.
        if (intent?.action == null) awaitingPromotion = true
        if (intent?.action == ACTION_STOP_ALL) app.sessions.closeAll()
        val active = app.sessions.activeCount
        if (intent?.action == ACTION_STOP || intent?.action == ACTION_STOP_ALL || active == 0) {
            // The last session can end between refresh() and here — a connection
            // that fails immediately does exactly that. Android still demands the
            // startForeground() that startForegroundService() promised and kills
            // the process otherwise, so promote briefly before standing down.
            if (awaitingPromotion) promote(build(0))
            standDown()
            stopSelf()
            return START_NOT_STICKY
        }
        promote(build(active))
        return START_STICKY
    }

    private fun promote(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        awaitingPromotion = false
    }

    private fun standDown() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun build(active: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SessionService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val names = (application as App).sessions.sessions.value.filter { !it.isFinished }.joinToString(", ") { it.label }
        return Notification.Builder(this, App.CHANNEL_SESSIONS)
            .setSmallIcon(R.drawable.ic_terminal)
            .setContentTitle(if (active == 1) "1 session active" else "$active sessions active")
            .setContentText(names)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Disconnect all", stop).build())
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep running: the user may come back to a still-open shell.
        super.onTaskRemoved(rootIntent)
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "dev.flint.term.STOP"
        const val ACTION_STOP_ALL = "dev.flint.term.STOP_ALL"

        fun refresh(context: Context) {
            val app = context.applicationContext as App
            val intent = Intent(context, SessionService::class.java)
            if (app.sessions.activeCount > 0) {
                runCatching { context.startForegroundService(intent) }
            } else {
                runCatching { context.startService(intent.setAction(ACTION_STOP)) }
            }
        }
    }
}
