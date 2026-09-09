package dev.flint.term.transfer

import android.app.Notification
import android.app.NotificationManager
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Foreground service (type dataSync) that mirrors [TransferManager] state into
 * notifications: one ongoing notification per active transfer with a progress
 * bar, speed and ETA plus a Cancel action, and a plain "done/failed"
 * notification once it finishes. Keeps the process alive so transfers continue
 * when the app is backgrounded.
 */
class TransferService : Service() {
    private val nm by lazy { getSystemService(NotificationManager::class.java) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** Transfer ids we have already posted a final notification for. */
    private val finalized = HashSet<String>()
    private var foregroundId: Int? = null
    private var observing = false
    /** Set by each start command: startForegroundService() must be answered with startForeground(). */
    private var awaitingPromotion = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = (application as App).transfers
        if (intent?.action == ACTION_CANCEL) {
            intent.getStringExtra(EXTRA_ID)?.let { manager.cancel(it) }
        }
        // Only the plain start comes from startForegroundService(); ACTION_CANCEL
        // arrives via startService() and owes Android nothing.
        if (intent?.action == null) awaitingPromotion = true
        render(manager.transfers.value)
        if (!observing) {
            observing = true
            scope.launch { manager.transfers.collect { render(it) } }
        }
        return START_NOT_STICKY
    }

    private fun render(all: List<Transfer>) {
        val active = all.filter { it.isActive }

        for (t in all.filter { !it.isActive && it.id !in finalized }) {
            finalized += t.id
            if (foregroundId == t.id.hashCode()) standDown()
            nm.cancel(t.id.hashCode())
            if (t.status != TransferStatus.CANCELLED) nm.notify(t.id.hashCode() + 1, buildFinal(t))
        }

        if (active.isEmpty()) {
            // A small transfer can finish before this service is even started. Android still
            // demands a startForeground() for the startForegroundService() that brought us
            // here and kills the process otherwise, so promote briefly, then stand down.
            if (awaitingPromotion) promote(IDLE_ID, buildIdle())
            standDown()
            stopSelf()
            return
        }

        // The first active transfer carries the foreground notification; the rest are ordinary ones.
        val first = active.first()
        if (foregroundId != null && foregroundId != first.id.hashCode()) {
            stopForeground(STOP_FOREGROUND_DETACH)
        }
        promote(first.id.hashCode(), buildProgress(first))
        foregroundId = first.id.hashCode()
        for (t in active.drop(1)) nm.notify(t.id.hashCode(), buildProgress(t))
    }

    private fun promote(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(id, notification)
        }
        awaitingPromotion = false
    }

    private fun standDown() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundId = null
    }

    /** Placeholder shown for the instant between promotion and standing down. */
    private fun buildIdle(): Notification =
        Notification.Builder(this, App.CHANNEL_TRANSFERS)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.notification_channel_transfers))
            .setCategory(Notification.CATEGORY_STATUS)
            .build()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildProgress(t: Transfer): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getService(
            this, t.id.hashCode(),
            Intent(this, TransferService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, t.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val verb = when (t.kind) {
            TransferKind.DOWNLOAD -> "Downloading"
            TransferKind.UPLOAD -> "Uploading"
            TransferKind.COPY -> "Copying"
        }
        val title = when {
            t.filesTotal > 1 -> "$verb ${t.name} (${t.filesDone}/${t.filesTotal} files)"
            t.filesTotal == 0 -> "$verb ${t.name} (scanning…)"
            else -> "$verb ${t.name}"
        }
        val queued = t.status == TransferStatus.QUEUED
        val bytes = buildString {
            append(human(t.done))
            t.total?.let { append(" / ").append(human(it)) }
        }
        val rate = buildString {
            if (t.speed > 0) append(human(t.speed)).append("/s")
            t.etaSeconds?.let { if (isNotEmpty()) append("  •  "); append(eta(it)).append(" left") }
        }
        // Collapsed: bytes + rate on one line (may truncate). Expanded: each on its own line.
        val text = if (queued) "starting…" else listOf(bytes, rate).filter { it.isNotEmpty() }.joinToString("  •  ")
        val big = if (queued) "starting…" else listOfNotNull(bytes, rate.takeIf { it.isNotEmpty() }, t.currentFile).joinToString("\n")
        val b = Notification.Builder(this, App.CHANNEL_TRANSFERS)
            .setSmallIcon(if (t.kind == TransferKind.DOWNLOAD) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(t.route)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
        val frac = t.fraction
        if (frac != null) b.setProgress(1000, (frac * 1000).toInt(), false) else b.setProgress(0, 0, true)
        b.setStyle(Notification.BigTextStyle().bigText(big))
        return b.build()
    }

    private fun buildFinal(t: Transfer): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val noun = when (t.kind) {
            TransferKind.DOWNLOAD -> "Download"
            TransferKind.UPLOAD -> "Upload"
            TransferKind.COPY -> "Copy"
        }
        val verb = when (t.kind) {
            TransferKind.DOWNLOAD -> "Downloaded"
            TransferKind.UPLOAD -> "Uploaded"
            TransferKind.COPY -> "Copied"
        }
        val (title, text) = when (t.status) {
            TransferStatus.DONE -> {
                val secs = ((t.finishedAt ?: System.currentTimeMillis()) - t.startedAt) / 1000
                val files = if (t.filesTotal > 1) "${t.filesTotal} files, " else ""
                "$verb ${t.name}" to "$files${human(t.done)} in ${eta(secs)}"
            }
            TransferStatus.FAILED -> "$noun failed: ${t.name}" to (t.error ?: "unknown error")
            else -> "$verb ${t.name}" to ""
        }
        return Notification.Builder(this, App.CHANNEL_TRANSFERS)
            .setSmallIcon(if (t.status == TransferStatus.DONE) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(t.route)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
    }

    companion object {
        private const val ACTION_CANCEL = "dev.flint.term.transfer.CANCEL"
        private const val IDLE_ID = 0x7d000001
        private const val EXTRA_ID = "id"

        /** Start (or poke) the service; it then follows the manager's state on its own. */
        fun refresh(context: Context) {
            val app = context.applicationContext as App
            if (app.transfers.activeCount > 0) {
                runCatching { context.startForegroundService(Intent(context, TransferService::class.java)) }
            }
        }

        fun human(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val units = arrayOf("KB", "MB", "GB", "TB")
            var v = bytes.toDouble()
            var u = -1
            while (v >= 1024 && u < units.size - 1) { v /= 1024; u++ }
            return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[u])
        }

        fun eta(seconds: Long): String = when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
    }
}
