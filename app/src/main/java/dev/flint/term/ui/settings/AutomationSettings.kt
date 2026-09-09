package dev.flint.term.ui.settings

import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.flint.term.App
import dev.flint.term.ui.Group
import dev.flint.term.ui.GroupRow
import dev.flint.term.ui.RowDivider
import dev.flint.term.ui.relativeTime

/** Whether anything else on the phone may drive sessions, and what has tried. */
@Composable
fun AutomationSettings(nav: NavController) {
    val app = LocalContext.current.applicationContext as App
    val settings by app.store.settings.collectAsStateWithLifecycle()

    SettingsSection(nav, "Automation") {
        Group("Automation") {
            GroupRow(
                title = "Let other apps drive sessions",
                subtitle = "Tasker, Automate, adb and shortcuts can connect, run a command or disconnect",
                icon = Icons.Rounded.Bolt, iconTint = MaterialTheme.colorScheme.error,
                checked = settings.automation, onCheckedChange = { v -> app.store.updateSettings { it.copy(automation = v) } },
            )
            if (settings.automation) {
                Text(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        "An app that Android names is turned away until you allow it here. Not every caller is " +
                            "named — adb and some system senders arrive anonymous, and those get through while " +
                            "this is on. Anything that gets through reaches every saved host with the keys kept " +
                            "here, so leave it off unless something is using it."
                    } else {
                        // Below Android 14 the platform will not say which
                        // app sent a broadcast, so there is nobody to allow
                        // or refuse and the switch is the whole gate.
                        "This version of Android does not say which app sent a call, so anything on the phone can " +
                            "use this while it is on — and reach every saved host with the keys kept here."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
                settings.automationAllowed.forEach { pkg ->
                    RowDivider()
                    GroupRow(
                        title = pkg,
                        subtitle = "Allowed",
                        icon = Icons.Rounded.Bolt, iconTint = MaterialTheme.colorScheme.primary,
                        trailing = {
                            TextButton(onClick = {
                                app.store.updateSettings { it.copy(automationAllowed = it.automationAllowed - pkg) }
                            }) { Text("Withdraw") }
                        },
                    )
                }
                RowDivider()
                if (settings.automationLog.isEmpty()) {
                    GroupRow(
                        title = "Nothing has called yet",
                        subtitle = "The last ten calls appear here, with the app that made them",
                        icon = Icons.Rounded.History, iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    settings.automationLog.forEach { call ->
                        val unknown = call.caller.isNotBlank() && call.caller !in settings.automationAllowed
                        GroupRow(
                            title = call.what,
                            subtitle = listOfNotNull(
                                call.caller.ifBlank { "an app that did not identify itself" },
                                relativeTime(call.at),
                                call.result.ifBlank { null },
                            ).joinToString("  ·  "),
                            icon = Icons.Rounded.History,
                            iconTint = if (unknown) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                            // The app that was turned away is right here, so
                            // allowing it is one tap rather than a hunt
                            // through a package name typed from memory.
                            trailing = if (!unknown) null else ({
                                TextButton(onClick = {
                                    app.store.updateSettings { it.copy(automationAllowed = it.automationAllowed + call.caller) }
                                }) { Text("Allow") }
                            }),
                        )
                        RowDivider()
                    }
                    GroupRow(
                        title = "Forget these calls",
                        icon = Icons.Rounded.Delete, iconTint = MaterialTheme.colorScheme.error,
                        onClick = { app.store.updateSettings { it.copy(automationLog = emptyList()) } },
                    )
                }
            }
        }
    }
}
