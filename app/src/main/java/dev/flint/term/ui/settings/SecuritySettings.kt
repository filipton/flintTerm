package dev.flint.term.ui.settings

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
        Group("App lock") {
            GroupRow(
                title = "App lock", subtitle = "Fingerprint, face or screen lock before hosts and keys open",
                icon = Icons.Rounded.Lock, iconTint = MaterialTheme.colorScheme.error,
                trailing = {
                    val activity = LocalContext.current as? FragmentActivity
                    AppSwitch(settings.appLock, { v ->
                        if (v && activity != null && !AppLock.canAuthenticate(activity)) {
                            Toast.makeText(activity, "Set up a screen lock or fingerprint in Android settings first", Toast.LENGTH_LONG).show()
                        } else {
                            app.store.updateSettings { it.copy(appLock = v) }; if (v) AppLock.unlockedAt = System.currentTimeMillis()
                        }
                    })
                },
            )
            if (settings.appLock) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Lock again after", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val choices = listOf(0, 60, 300, 1800)
                    Segmented(listOf("Right away", "1 min", "5 min", "30 min"), choices.indexOf(settings.appLockGraceSeconds).coerceAtLeast(0)) { i ->
                        app.store.updateSettings { it.copy(appLockGraceSeconds = choices[i]) }
                    }
                }
            }
        }

        Group("Servers") {
            GroupRow(
                title = "Trusted host keys",
                subtitle = "The server keys this phone has accepted, and forgetting one",
                icon = Icons.Rounded.VerifiedUser,
                iconTint = MaterialTheme.colorScheme.primary,
                onClick = { nav.navigate(Routes.KNOWN_HOSTS) },
            )
        }
    }
}
