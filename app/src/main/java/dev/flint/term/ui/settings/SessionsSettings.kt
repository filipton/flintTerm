package dev.flint.term.ui.settings

import dev.flint.term.R
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Tab
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.flint.term.App
import dev.flint.term.ui.Group
import dev.flint.term.ui.GroupRow
import dev.flint.term.ui.RowDivider

/** The sessions themselves, and the ways one asks for attention while you are elsewhere. */
@Composable
fun SessionsSettings(nav: NavController) {
    val app = LocalContext.current.applicationContext as App
    val settings by app.store.settings.collectAsStateWithLifecycle()

    SettingsSection(nav, stringResource(R.string.sessionssettings_sessions_alerts)) {
        Group("Sessions") {
            GroupRow(
                title = stringResource(R.string.sessionssettings_session_tabs),
                subtitle = stringResource(R.string.sessionssettings_a_strip_of_names_under_the_terminal_s_bar),
                icon = Icons.Rounded.Tab,
                iconTint = MaterialTheme.colorScheme.secondary,
                checked = settings.showSessionTabs,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(showSessionTabs = v) } },
            )
            RowDivider()
            GroupRow(
                title = stringResource(R.string.sessionssettings_reopen_sessions),
                subtitle = stringResource(R.string.sessionssettings_open_again_the_sessions_that_were_running_when_t),
                icon = Icons.Rounded.Restore,
                iconTint = MaterialTheme.colorScheme.secondary,
                checked = settings.restoreSessions,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(restoreSessions = v) } },
            )
            RowDivider()
            GroupRow(
                title = stringResource(R.string.sessionssettings_keep_floating_when_you_leave),
                subtitle = stringResource(R.string.sessionssettings_leave_the_terminal_in_a_small_window_over_other),
                icon = Icons.Rounded.PictureInPictureAlt,
                iconTint = MaterialTheme.colorScheme.secondary,
                checked = settings.pipOnLeave,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(pipOnLeave = v) } },
            )
            RowDivider()
            GroupRow(
                title = stringResource(R.string.sessionssettings_tmux_controls),
                subtitle = stringResource(R.string.sessionssettings_chords_the_window_list_and_a_swipe_that_changes),
                icon = Icons.Rounded.Dashboard,
                iconTint = MaterialTheme.colorScheme.secondary,
                checked = settings.tmuxControls,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(tmuxControls = v) } },
            )
            RowDivider()
            GroupRow(
                title = stringResource(R.string.sessionssettings_keep_screen_on), subtitle = stringResource(R.string.sessionssettings_while_a_terminal_is_open_uses_the_most_battery_o),
                icon = Icons.Rounded.Visibility, iconTint = MaterialTheme.colorScheme.secondary,
                checked = settings.keepScreenOn, onCheckedChange = { v -> app.store.updateSettings { it.copy(keepScreenOn = v) } },
            )
        }

        Group("Alerts") {
            GroupRow(
                title = stringResource(R.string.sessionssettings_notify_on_bell), subtitle = stringResource(R.string.sessionssettings_when_the_app_is_in_the_background),
                icon = Icons.Rounded.NotificationsActive, iconTint = MaterialTheme.colorScheme.tertiary,
                checked = settings.notifyOnBell, onCheckedChange = { v -> app.store.updateSettings { it.copy(notifyOnBell = v) } },
            )
            RowDivider()
            GroupRow(
                title = stringResource(R.string.sessionssettings_vibrate_on_bell), icon = Icons.Rounded.Vibration, iconTint = MaterialTheme.colorScheme.secondary,
                checked = settings.vibrateOnBell, onCheckedChange = { v -> app.store.updateSettings { it.copy(vibrateOnBell = v) } },
            )
            RowDivider()
            GroupRow(
                title = stringResource(R.string.sessionssettings_long_command_finished),
                subtitle = stringResource(R.string.sessionssettings_a_notification_when_a_command_that_took_over_30),
                icon = Icons.Rounded.Timer, iconTint = MaterialTheme.colorScheme.tertiary,
                checked = settings.notifyOnCommandFinish,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(notifyOnCommandFinish = v) } },
            )
            RowDivider()
            GroupRow(
                title = stringResource(R.string.sessionssettings_let_programs_raise_a_notification),
                subtitle = stringResource(R.string.sessionssettings_a_script_can_ask_for_one_with_osc_9_99_or_777),
                icon = Icons.Rounded.Campaign, iconTint = MaterialTheme.colorScheme.tertiary,
                checked = settings.notifyFromEscapes,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(notifyFromEscapes = v) } },
            )
        }
    }
}
