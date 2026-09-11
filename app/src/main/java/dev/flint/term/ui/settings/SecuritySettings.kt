package dev.flint.term.ui.settings

import dev.flint.term.R
import androidx.compose.ui.res.stringResource
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Visibility
import dev.flint.term.App
import dev.flint.term.ui.AppLock
import dev.flint.term.ui.AppSwitch
import dev.flint.term.ui.Group
import dev.flint.term.ui.Routes
import dev.flint.term.ui.GroupRow
import dev.flint.term.ui.Segmented

/** The lock in front of the hosts and the keys they open. */
@Composable
fun SecuritySettings(nav: NavController) {
    val app = LocalContext.current.applicationContext as App
    val settings by app.store.settings.collectAsStateWithLifecycle()

    SettingsSection(nav, "Security") {
        Group(stringResource(R.string.securitysettings_app_lock)) {
            GroupRow(
                title = stringResource(R.string.securitysettings_app_lock), subtitle = stringResource(R.string.securitysettings_fingerprint_face_or_screen_lock_before_hosts_and),
                icon = Icons.Rounded.Lock, iconTint = MaterialTheme.colorScheme.error,
                trailing = {
                    val activity = LocalContext.current as? FragmentActivity
                    AppSwitch(settings.appLock, { v ->
                        if (v && activity != null && !AppLock.canAuthenticate(activity)) {
                            Toast.makeText(activity, activity.getString(R.string.securitysettings_set_up_a_screen_lock_or_fingerprint_in_android_s), Toast.LENGTH_LONG).show()
                        } else {
                            app.store.updateSettings { it.copy(appLock = v) }; if (v) AppLock.unlockedAt = System.currentTimeMillis()
                        }
                    })
                },
            )
            if (settings.appLock) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.securitysettings_lock_again_after), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val choices = listOf(0, 60, 300, 1800)
                    Segmented(listOf(stringResource(R.string.securitysettings_right_away), stringResource(R.string.securitysettings_1_min), stringResource(R.string.securitysettings_5_min), stringResource(R.string.securitysettings_30_min)), choices.indexOf(settings.appLockGraceSeconds).coerceAtLeast(0)) { i ->
                        app.store.updateSettings { it.copy(appLockGraceSeconds = choices[i]) }
                    }
                }
            }
        }

        Group("Privacy") {
            GroupRow(
                title = stringResource(R.string.securitysettings_block_screenshots),
                subtitle = stringResource(R.string.securitysettings_stops_android_taking_screenshots_of_the_app_incl),
                icon = Icons.Rounded.Visibility,
                iconTint = MaterialTheme.colorScheme.primary,
                checked = settings.blockScreenshots,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(blockScreenshots = v) } },
            )
        }

        Group("Servers") {
            GroupRow(
                title = stringResource(R.string.securitysettings_trusted_host_keys),
                subtitle = stringResource(R.string.securitysettings_the_server_keys_this_phone_has_accepted_you_can),
                icon = Icons.Rounded.VerifiedUser,
                iconTint = MaterialTheme.colorScheme.primary,
                onClick = { nav.navigate(Routes.KNOWN_HOSTS) },
            )
        }
    }
}
