package dev.flint.term.ui

import dev.flint.term.R
import androidx.compose.ui.res.stringResource
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.flint.term.App
import dev.flint.term.session.StatusWidget
import dev.flint.term.session.StatusWidgetPrefs
import dev.flint.term.session.StatusWidgetWork

/**
 * The screen the launcher shows while a status widget is being placed.
 *
 * It asks the only two questions the widget cannot answer for itself: which
 * machine, and how often. Picking the host is what confirms the widget — there
 * is no separate "done", because a widget with no host would be a blank square
 * nobody could fix from the home screen.
 */
class StatusWidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Backing out has to leave no widget behind, so the refusal is set
        // first and only replaced once a host has actually been chosen.
        setResult(Activity.RESULT_CANCELED)
        val widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        setContent {
            FlintTermTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    StatusWidgetConfig(widgetId) { hostId, minutes ->
                        StatusWidgetPrefs.configure(this, widgetId, hostId, minutes)
                        StatusWidgetWork.schedule(this, widgetId)
                        StatusWidgetWork.readNow(this, widgetId)
                        StatusWidget.refresh(this, widgetId)
                        setResult(
                            Activity.RESULT_OK,
                            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
                        )
                        finish()
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusWidgetConfig(widgetId: Int, onPick: (hostId: String, minutes: Int) -> Unit) {
    val app = LocalContext.current.applicationContext as App
    val hosts by app.store.hosts.collectAsStateWithLifecycle()
    var minutes by remember { mutableIntStateOf(StatusWidgetPrefs.minutes(app, widgetId)) }
    val sorted = remember(hosts) {
        hosts.sortedWith(compareByDescending<dev.flint.term.data.Host> { it.lastConnected }.thenBy { it.displayName.lowercase() })
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        AppHeader(stringResource(R.string.statuswidgetconfigactivi_server_status), subtitle = stringResource(R.string.statuswidgetconfigactivi_load_memory_and_the_fullest_disk_on_your_home_sc))

        Group(title = stringResource(R.string.statuswidgetconfigactivi_check_every)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                StatusWidgetPrefs.INTERVALS.forEachIndexed { index, value ->
                    SegmentedButton(
                        selected = minutes == value,
                        onClick = { minutes = value },
                        shape = SegmentedButtonDefaults.itemShape(index, StatusWidgetPrefs.INTERVALS.size),
                    ) { Text(stringResource(R.string.statuswidgetconfigactivi_min, value)) }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        // The interval is the smaller of the two decisions, so it comes first
        // and the host row is the tap that finishes the job.
        if (sorted.isEmpty()) {
            EmptyState(
                Icons.Rounded.Dns,
                stringResource(R.string.statuswidgetconfigactivi_no_hosts_yet),
                stringResource(R.string.statuswidgetconfigactivi_add_a_host_in_flintterm_and_this_widget_can_watc),
            )
        } else {
            Group(title = stringResource(R.string.statuswidgetconfigactivi_watch)) {
                sorted.forEachIndexed { index, host ->
                    if (index > 0) RowDivider()
                    GroupRow(
                        title = host.displayName,
                        subtitle = host.target,
                        subtitleMono = true,
                        icon = iconFor(host.icon),
                        iconTint = accentFor(host.id, host.color),
                        onClick = { onPick(host.id, minutes) },
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
