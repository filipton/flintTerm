package dev.flint.term.session

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.compose.ui.graphics.toArgb
import dev.flint.term.App
import dev.flint.term.R
import dev.flint.term.data.Host
import dev.flint.term.ui.MainActivity
import dev.flint.term.ui.accentFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The saved hosts on the home screen, most recently connected first, each one a
 * tap away from a shell.
 *
 * The rows are the same intents a pinned shortcut sends, so a host reached from
 * here and a host reached from the launcher arrive at the same place — and a
 * host that already has a session reopens it instead of dialing a second one.
 */
class HostsWidget : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        watch(context)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // Also here, not only in onEnabled: the process is restarted far more
        // often than the widget is added, and the collector dies with it.
        watch(context)
        ids.forEach { id -> manager.updateAppWidget(id, views(context, id)) }
    }

    companion object {
        private val watching = AtomicBoolean(false)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** Redraw the rows of every placed widget. */
        @Suppress("DEPRECATION")
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, HostsWidget::class.java))
            if (ids.isEmpty()) return
            manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
        }

        /**
         * Follow the host list and the open sessions, and redraw when either
         * changes.
         *
         * Both are already state the app keeps, so nothing here polls or wakes
         * the device: a renamed host, a new one, a connection that just came up
         * are all one emission away. The first emission is the state the widget
         * was drawn from, which is why it is dropped.
         */
        internal fun watch(context: Context) {
            val app = context.applicationContext as? App ?: return
            if (!watching.compareAndSet(false, true)) return
            scope.launch {
                combine(app.store.hosts, app.sessions.sessions) { hosts, sessions ->
                    val live = sessions.filterNot { it.isFinished }.mapNotNull { it.host?.id }.toSet()
                    hosts.joinToString("|") { "${it.id};${it.displayName};${it.target};${it.color};${it.lastConnected};${it.id in live}" }
                }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { refresh(context) }
            }
        }

        private fun views(context: Context, widgetId: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_hosts)
            val rows = Intent(context, HostsWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                // Each placed widget needs a factory of its own, and the adapter
                // tells two intents apart by their data alone.
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            @Suppress("DEPRECATION")
            views.setRemoteAdapter(R.id.widget_list, rows)
            views.setEmptyView(R.id.widget_list, R.id.widget_empty)
            // The rows fill in their own action and host, so the template holds
            // neither: Intent.fillIn only writes into fields that are still empty.
            val template = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            views.setPendingIntentTemplate(R.id.widget_list, PendingIntent.getActivity(context, 0, template, mutableFlags))
            val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            views.setOnClickPendingIntent(
                R.id.widget_header,
                PendingIntent.getActivity(context, 1, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )
            return views
        }

        /** A template a row can write into has to stay mutable, where the platform asks. */
        private val mutableFlags: Int
            get() = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
    }
}

/** Binds the widget's list to the app's hosts; the launcher owns the other end. */
class HostsWidgetService : RemoteViewsService() {
    override fun onCreate() {
        super.onCreate()
        // The provider's onUpdate only arrives when a widget is placed or the
        // device restarts, so a process started for anything else would have
        // nothing following the host list. Being bound at all means a widget is
        // on a screen somewhere, which is exactly when it is worth watching.
        HostsWidget.watch(applicationContext)
    }

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = HostRows(applicationContext)
}

private class HostRows(private val context: Context) : RemoteViewsService.RemoteViewsFactory {
    /** A host and the session it already has, if it has one. */
    private data class Row(val host: Host, val sessionId: String?)

    private var rows: List<Row> = emptyList()

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        rows = read()
    }

    override fun onDestroy() {
        rows = emptyList()
    }

    override fun getCount(): Int = rows.size

    override fun getViewAt(position: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_host_row)
        val row = rows.getOrNull(position) ?: return views
        views.setTextViewText(R.id.widget_row_name, row.host.displayName)
        views.setTextViewText(R.id.widget_row_target, row.host.target)
        views.setInt(R.id.widget_row_accent, "setBackgroundColor", accentFor(row.host.id, row.host.color).toArgb())
        views.setViewVisibility(R.id.widget_row_open, if (row.sessionId != null) View.VISIBLE else View.GONE)
        // A host that is already up goes back to the session it has; starting a
        // second one to the same machine is never what the tap meant.
        val tap = if (row.sessionId != null) {
            Intent().setAction(Shortcuts.ACTION_OPEN_SESSION).putExtra(Shortcuts.EXTRA_SESSION_ID, row.sessionId)
        } else {
            Shortcuts.connectIntent(context, row.host)
        }
        views.setOnClickFillInIntent(R.id.widget_row, tap)
        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long =
        rows.getOrNull(position)?.host?.id?.hashCode()?.toLong() ?: position.toLong()

    override fun hasStableIds(): Boolean = true

    /**
     * The list as the widget shows it: the machine you were on last at the top,
     * then the rest by name, since a host never connected has no order of its own.
     */
    private fun read(): List<Row> {
        val app = context.applicationContext as? App ?: return emptyList()
        val live = app.sessions.sessions.value.filterNot { it.isFinished }
        return app.store.hosts.value
            .sortedWith(compareByDescending<Host> { it.lastConnected }.thenBy { it.displayName.lowercase() })
            .map { host -> Row(host, live.firstOrNull { it.host?.id == host.id }?.id) }
    }
}
