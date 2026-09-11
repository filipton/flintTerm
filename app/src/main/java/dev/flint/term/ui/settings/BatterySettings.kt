package dev.flint.term.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.flint.term.App
import dev.flint.term.R
import dev.flint.term.ui.Group
import dev.flint.term.ui.GroupRow
import dev.flint.term.ui.RowDivider
import dev.flint.term.ui.Routes
import dev.flint.term.ui.SettingsFocus

/**
 * One thing that can be changed, and whether it is already the cheap way round.
 *
 * [title] is the row's own text on the screen it lives on, which is what the
 * jump matches on, so it has to be the same string resource both places.
 */
private data class Saver(
    val title: String,
    val why: String,
    val frugal: Boolean,
    val route: String,
)

/**
 * What to change to make the app cost less, with the state of each one.
 *
 * Every row says what it costs and jumps to the setting itself rather than
 * making you go and find it.
 */
@Composable
fun BatterySettings(nav: NavController) {
    val app = LocalContext.current.applicationContext as App
    val settings by app.store.settings.collectAsStateWithLifecycle()
    val hosts by app.store.hosts.collectAsStateWithLifecycle()

    val savers = listOf(
        Saver(
            stringResource(R.string.sessionssettings_keep_screen_on),
            stringResource(R.string.battery_why_keep_screen_on),
            frugal = !settings.keepScreenOn,
            route = Routes.SETTINGS_SESSIONS,
        ),
        Saver(
            stringResource(R.string.terminalsettings_redraw_limit),
            stringResource(R.string.battery_why_redraw_limit),
            frugal = settings.maxFps > 0,
            route = Routes.SETTINGS_TERMINAL,
        ),
        Saver(
            stringResource(R.string.connectionssettings_data_saver),
            stringResource(R.string.battery_why_data_saver),
            frugal = settings.dataSaver != dev.flint.term.data.DataSaver.OFF,
            route = Routes.SETTINGS_CONNECTIONS,
        ),
        Saver(
            stringResource(R.string.battery_keepalive),
            stringResource(R.string.battery_why_keepalive, settings.keepaliveSeconds),
            frugal = settings.keepaliveSeconds == 0 || settings.keepaliveSeconds >= 60,
            route = Routes.SETTINGS_CONNECTIONS,
        ),
        Saver(
            stringResource(R.string.appearancesettings_blink),
            stringResource(R.string.battery_why_blink),
            frugal = !settings.cursorBlink,
            route = Routes.SETTINGS_APPEARANCE,
        ),
        Saver(
            stringResource(R.string.connectionssettings_find_hosts_on_this_network),
            stringResource(R.string.battery_why_discovery),
            frugal = !settings.discoverNearbyHosts,
            route = Routes.SETTINGS_CONNECTIONS,
        ),
    )
    val moshHosts = hosts.count { it.mosh }
    val recording = hosts.count { it.recordSessions }
    val todo = savers.count { !it.frugal }

    SettingsSection(nav, stringResource(R.string.battery_title)) {
        Group(stringResource(R.string.battery_group_now)) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    if (todo == 0) {
                        stringResource(R.string.battery_all_set)
                    } else {
                        androidx.compose.ui.res.pluralStringResource(R.plurals.battery_n_to_change, todo, todo)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    stringResource(R.string.battery_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        Group(stringResource(R.string.battery_group_settings)) {
            savers.forEachIndexed { i, s ->
                if (i > 0) RowDivider()
                GroupRow(
                    title = s.title,
                    subtitle = s.why,
                    icon = if (s.frugal) Icons.Rounded.CheckCircle else Icons.Rounded.Bolt,
                    iconTint = if (s.frugal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    onClick = {
                        SettingsFocus.want(s.title)
                        nav.navigate(s.route)
                    },
                )
            }
        }

        Group(stringResource(R.string.battery_group_hosts)) {
            GroupRow(
                title = stringResource(R.string.battery_mosh),
                subtitle = stringResource(R.string.battery_why_mosh, moshHosts, hosts.size),
                icon = if (moshHosts > 0) Icons.Rounded.CheckCircle else Icons.Rounded.Bolt,
                iconTint = if (moshHosts > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                onClick = { nav.navigate(Routes.HOSTS) },
            )
            RowDivider()
            GroupRow(
                title = stringResource(R.string.battery_recording),
                subtitle = if (recording == 0) {
                    stringResource(R.string.battery_recording_none)
                } else {
                    stringResource(R.string.battery_recording_some, recording)
                },
                icon = if (recording == 0) Icons.Rounded.CheckCircle else Icons.Rounded.Bolt,
                iconTint = if (recording == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                onClick = { nav.navigate(Routes.HOSTS) },
            )
        }

        Group(stringResource(R.string.battery_group_phone)) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.battery_phone_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
