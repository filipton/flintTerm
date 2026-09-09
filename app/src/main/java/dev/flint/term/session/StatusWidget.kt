package dev.flint.term.session

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.flint.term.App
import dev.flint.term.R
import dev.flint.term.ui.MainActivity
import dev.flint.term.ui.accentFor
import dev.flint.term.ui.humanBytes
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * One host's vitals on the home screen: load, memory, and the disk nearest to
 * full.
 *
 * The status pane exists for the minute you spend looking; this exists for the
 * glance that decides whether to look at all. It is deliberately its own
 * opt-in, because unlike the host-list widget it costs a connection every time
 * it refreshes — so a widget is added for a host on purpose, at an interval the
 * person chose, and it holds off entirely when data saving says the connection
 * is somebody's mobile data.
 *
 * The last reading is kept and redrawn, so a widget that could not connect
 * shows the numbers it has with the time they were taken, rather than blanking
 * out and leaving the screen looking broken.
 */
class StatusWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            manager.updateAppWidget(id, views(context, id))
            // The process is restarted far more often than a widget is placed,
            // and WorkManager keeps its own schedule across that — so this is a
            // re-assertion, not a second job.
            StatusWidgetWork.schedule(context, id)
        }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { id ->
            StatusWidgetWork.cancel(context, id)
            StatusWidgetPrefs.clear(context, id)
        }
    }

    companion object {
        /** Redraw one placed widget from whatever is stored for it. */
        fun refresh(context: Context, widgetId: Int) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            manager.updateAppWidget(widgetId, views(context, widgetId))
        }

        /** Every status widget currently on a home screen. */
        fun placed(context: Context): IntArray =
            AppWidgetManager.getInstance(context)
                ?.getAppWidgetIds(ComponentName(context, StatusWidget::class.java))
                ?: IntArray(0)

        internal fun views(context: Context, widgetId: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_status)
            val app = context.applicationContext as? App
            val host = StatusWidgetPrefs.hostId(context, widgetId)?.let { app?.store?.host(it) }
            views.setTextViewText(R.id.status_name, host?.displayName ?: context.getString(R.string.widget_status_label))
            if (host != null) {
                views.setInt(R.id.status_accent, "setBackgroundColor", accentFor(host.id, host.color).toArgb())
            }

            val reading = StatusWidgetPrefs.reading(context, widgetId)
            val have = reading?.hasNumbers == true
            views.setViewVisibility(R.id.status_body, if (have) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.status_empty, if (have) View.GONE else View.VISIBLE)
            if (reading != null) {
                views.setTextViewText(R.id.status_load, reading.load ?: EMPTY)
                views.setTextViewText(R.id.status_memory, reading.memory ?: EMPTY)
                views.setTextViewText(R.id.status_disk, reading.disk ?: EMPTY)
                views.setTextViewText(R.id.status_disk_label, reading.diskMount ?: context.getString(R.string.widget_status_disk))
                views.setProgressBar(R.id.status_memory_bar, 100, reading.memoryPercent, false)
                views.setProgressBar(R.id.status_disk_bar, 100, reading.diskPercent, false)
            }
            views.setTextViewText(R.id.status_taken, reading?.let { takenText(context, it) } ?: "")

            // Tapping it opens the app rather than the pane: a widget that
            // dialled a host from the home screen would be a connection nobody
            // asked for.
            val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            views.setOnClickPendingIntent(
                R.id.status_root,
                PendingIntent.getActivity(context, widgetId, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )
            return views
        }

        /** What a field looks like when the machine did not report it. */
        private const val EMPTY = "—"

        private fun takenText(context: Context, reading: StatusWidgetPrefs.Reading): String {
            val minutes = ((System.currentTimeMillis() - reading.at) / 60_000).coerceAtLeast(0)
            val when_ = when {
                minutes < 1 -> context.getString(R.string.widget_status_just_now)
                minutes < 60 -> "$minutes min ago"
                else -> "${minutes / 60} h ago"
            }
            return if (reading.error != null) "$when_ · ${reading.error}" else when_
        }
    }
}

/**
 * The stored half of a placed widget: which host, how often, and the last
 * numbers it managed to read.
 *
 * These belong to the widget rather than to the app's settings — a widget is a
 * thing on a home screen, and its host and interval mean nothing to a phone
 * that has not placed one.
 */
object StatusWidgetPrefs {
    private const val FILE = "status_widget"

    /** The intervals offered; anything shorter is below what WorkManager will honor. */
    val INTERVALS = listOf(15, 30, 60)

    data class Reading(
        val load: String?,
        val memory: String?,
        val memoryPercent: Int,
        val disk: String?,
        val diskMount: String?,
        val diskPercent: Int,
        val at: Long,
        /** Why the last attempt failed, when the numbers above are older than they should be. */
        val error: String? = null,
    ) {
        /** False for a widget that has only ever failed: there is nothing to draw yet. */
        val hasNumbers: Boolean get() = load != null || memory != null || disk != null
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun hostId(context: Context, widgetId: Int): String? =
        prefs(context).getString("host_$widgetId", null)

    fun minutes(context: Context, widgetId: Int): Int =
        prefs(context).getInt("minutes_$widgetId", INTERVALS.first())

    fun configure(context: Context, widgetId: Int, hostId: String, minutes: Int) {
        prefs(context).edit()
            .putString("host_$widgetId", hostId)
            .putInt("minutes_$widgetId", minutes)
            .apply()
    }

    fun reading(context: Context, widgetId: Int): Reading? {
        val p = prefs(context)
        val at = p.getLong("at_$widgetId", 0)
        if (at <= 0) return null
        return Reading(
            load = p.getString("load_$widgetId", null),
            memory = p.getString("memory_$widgetId", null),
            memoryPercent = p.getInt("memoryPercent_$widgetId", 0),
            disk = p.getString("disk_$widgetId", null),
            diskMount = p.getString("diskMount_$widgetId", null),
            diskPercent = p.getInt("diskPercent_$widgetId", 0),
            at = at,
            error = p.getString("error_$widgetId", null),
        )
    }

    fun remember(context: Context, widgetId: Int, reading: Reading) {
        prefs(context).edit()
            .putString("load_$widgetId", reading.load)
            .putString("memory_$widgetId", reading.memory)
            .putInt("memoryPercent_$widgetId", reading.memoryPercent)
            .putString("disk_$widgetId", reading.disk)
            .putString("diskMount_$widgetId", reading.diskMount)
            .putInt("diskPercent_$widgetId", reading.diskPercent)
            .putLong("at_$widgetId", reading.at)
            .putString("error_$widgetId", reading.error)
            .apply()
    }

    /** Note that the last attempt failed without losing the numbers it failed to replace. */
    fun rememberFailure(context: Context, widgetId: Int, why: String) {
        val kept = reading(context, widgetId)
        if (kept == null) {
            remember(context, widgetId, Reading(null, null, 0, null, null, 0, System.currentTimeMillis(), why))
        } else {
            prefs(context).edit().putString("error_$widgetId", why).apply()
        }
    }

    fun clear(context: Context, widgetId: Int) {
        val p = prefs(context).edit()
        listOf("host", "minutes", "load", "memory", "memoryPercent", "disk", "diskMount", "diskPercent", "at", "error")
            .forEach { p.remove("${it}_$widgetId") }
        p.apply()
    }
}

/** Scheduling, kept apart from the worker so the config screen can reach it. */
object StatusWidgetWork {
    internal const val WIDGET_ID = "widgetId"

    private fun name(widgetId: Int) = "status-widget-$widgetId"

    /**
     * Put this widget on its interval, replacing whatever it was on.
     *
     * There is no point running with no link at all, so the job waits for one;
     * whether the link is one worth using is a separate question, and the
     * worker answers it.
     */
    fun schedule(context: Context, widgetId: Int) {
        if (StatusWidgetPrefs.hostId(context, widgetId) == null) return
        val minutes = StatusWidgetPrefs.minutes(context, widgetId).toLong()
        val data = workDataOf(WIDGET_ID to widgetId)
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            name(widgetId),
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<StatusWidgetWorker>(minutes, TimeUnit.MINUTES)
                .setInputData(data)
                .setConstraints(constraints)
                .build(),
        )
    }

    /** A reading now, so a widget just placed is not blank until the interval comes round. */
    fun readNow(context: Context, widgetId: Int) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "${name(widgetId)}-now",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<StatusWidgetWorker>()
                .setInputData(workDataOf(WIDGET_ID to widgetId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }

    fun cancel(context: Context, widgetId: Int) {
        WorkManager.getInstance(context).cancelUniqueWork(name(widgetId))
        WorkManager.getInstance(context).cancelUniqueWork("${name(widgetId)}-now")
    }
}

/**
 * One connection, one script, three numbers, and back to sleep.
 *
 * A headless connection rather than a session: nothing about a widget belongs
 * in the session list, and the terminal the person left open should not be
 * disturbed to answer a question the home screen asked.
 */
class StatusWidgetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val widgetId = inputData.getInt(StatusWidgetWork.WIDGET_ID, -1)
        val context = applicationContext
        val app = context as? App ?: return Result.success()
        // A widget dragged off the home screen takes its job with it, but a job
        // already queued can still arrive; there is nothing left to draw on.
        if (!StatusWidget.placed(context).contains(widgetId)) return Result.success()
        val host = StatusWidgetPrefs.hostId(context, widgetId)?.let { app.store.host(it) }
            ?: return Result.success()

        // The one rule that outranks the interval. Not a retry: the answer
        // would be the same in ten minutes, and the widget already has numbers
        // to show. It picks itself up on the next run over a cheaper link.
        if (app.sessions.holdTransfers()) return Result.success()

        val session = runCatching { app.sessions.connectHeadless(host) }.getOrElse { e ->
            StatusWidgetPrefs.rememberFailure(context, widgetId, e.message ?: "could not connect")
            StatusWidget.refresh(context, widgetId)
            return Result.retry()
        }
        return try {
            val status = ServerProbe.parse(session.core.execLive(ServerProbe.SCRIPT))
            StatusWidgetPrefs.remember(context, widgetId, reading(status))
            StatusWidget.refresh(context, widgetId)
            Result.success()
        } catch (e: Throwable) {
            StatusWidgetPrefs.rememberFailure(context, widgetId, e.message ?: "could not read the status")
            StatusWidget.refresh(context, widgetId)
            Result.retry()
        } finally {
            session.destroy()
        }
    }

    /** Whatever the machine actually reported; a field it did not answer stays null. */
    private fun reading(status: ServerStatus): StatusWidgetPrefs.Reading {
        val memory = status.memory
        val disk = status.fullestDisk
        return StatusWidgetPrefs.Reading(
            load = status.load?.let { String.format(Locale.US, "%.2f", it.one) },
            memory = memory?.let { "${humanBytes(it.usedBytes)} / ${humanBytes(it.totalBytes)}" },
            memoryPercent = memory?.let { (it.fraction * 100).roundToInt() } ?: 0,
            disk = disk?.let { "${humanBytes(it.usedBytes)} / ${humanBytes(it.totalBytes)}" },
            diskMount = disk?.mount,
            diskPercent = disk?.let { (it.fraction * 100).roundToInt() } ?: 0,
            at = System.currentTimeMillis(),
        )
    }
}
