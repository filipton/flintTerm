package dev.flint.term.ui.settings

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

    SettingsSection(nav, "Sessions & alerts") {
        Group("Sessions") {
            GroupRow(
                title = "Session tabs",
                subtitle = "A strip of names under the terminal's bar",
                icon = Icons.Rounded.Tab,
                iconTint = MaterialTheme.colorScheme.secondary,
                checked = settings.showSessionTabs,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(showSessionTabs = v) } },
            )
            RowDivider()
            GroupRow(
                title = "Reopen sessions",
                subtitle = "Dial again what was open when the app was killed",
                icon = Icons.Rounded.Restore,
                iconTint = MaterialTheme.colorScheme.secondary,
                checked = settings.restoreSessions,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(restoreSessions = v) } },
            )
            RowDivider()
            GroupRow(
                title = "Float when leaving",
                subtitle = "Leave the terminal in a small window over other apps",
                icon = Icons.Rounded.PictureInPictureAlt,
                iconTint = MaterialTheme.colorScheme.secondary,
                checked = settings.pipOnLeave,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(pipOnLeave = v) } },
            )
            RowDivider()
            GroupRow(
                title = "tmux controls",
                subtitle = "Chords, the window list, and a swipe that changes window",
                icon = Icons.Rounded.Dashboard,
                iconTint = MaterialTheme.colorScheme.secondary,
                checked = settings.tmuxControls,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(tmuxControls = v) } },
            )
            RowDivider()
            GroupRow(
                title = "Keep screen on", subtitle = "While a terminal is open · uses the most battery of any setting here",
                icon = Icons.Rounded.Visibility, iconTint = MaterialTheme.colorScheme.secondary,
                checked = settings.keepScreenOn, onCheckedChange = { v -> app.store.updateSettings { it.copy(keepScreenOn = v) } },
            )
        }

        Group("Alerts") {
            GroupRow(
                title = "Notify on bell", subtitle = "When the app is in the background",
                icon = Icons.Rounded.NotificationsActive, iconTint = MaterialTheme.colorScheme.tertiary,
                checked = settings.notifyOnBell, onCheckedChange = { v -> app.store.updateSettings { it.copy(notifyOnBell = v) } },
            )
            RowDivider()
            GroupRow(
                title = "Vibrate on bell", icon = Icons.Rounded.Vibration, iconTint = MaterialTheme.colorScheme.secondary,
                checked = settings.vibrateOnBell, onCheckedChange = { v -> app.store.updateSettings { it.copy(vibrateOnBell = v) } },
            )
            RowDivider()
            GroupRow(
                title = "Long command finished",
                subtitle = "A notification when a command over 30 seconds ends while the app is in the background",
                icon = Icons.Rounded.Timer, iconTint = MaterialTheme.colorScheme.tertiary,
                checked = settings.notifyOnCommandFinish,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(notifyOnCommandFinish = v) } },
            )
            RowDivider()
            GroupRow(
                title = "Let programs raise a notification",
                subtitle = "A script can ask for one with OSC 9, 99 or 777",
                icon = Icons.Rounded.Campaign, iconTint = MaterialTheme.colorScheme.tertiary,
                checked = settings.notifyFromEscapes,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(notifyFromEscapes = v) } },
            )
        }
    }
}
