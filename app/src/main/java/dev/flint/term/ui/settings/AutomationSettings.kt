package dev.flint.term.ui.settings

import dev.flint.term.R
import androidx.compose.ui.res.stringResource
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
                title = stringResource(R.string.automationsettings_let_other_apps_drive_sessions),
                subtitle = stringResource(R.string.automationsettings_tasker_automate_adb_and_shortcuts_can_connect_ru),
                icon = Icons.Rounded.Bolt, iconTint = MaterialTheme.colorScheme.error,
                checked = settings.automation, onCheckedChange = { v -> app.store.updateSettings { it.copy(automation = v) } },
            )
            if (settings.automation) {
                Text(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        stringResource(R.string.automationsettings_an_app_that_android_can_name_is_refused_until_yo) +
                            stringResource(R.string.automationsettings_named_adb_and_some_system_senders_arrive_without) +
                            stringResource(R.string.automationsettings_while_this_is_on_anything_that_gets_through_can) +
                            stringResource(R.string.automationsettings_keys_kept_here_so_leave_it_off_unless_you_are_us)
                    } else {
                        // Below Android 14 the platform will not say which
                        // app sent a broadcast, so there is nobody to allow
                        // or refuse and the switch is the whole gate.
                        stringResource(R.string.automationsettings_this_version_of_android_does_not_say_which_app_s) +
                            stringResource(R.string.automationsettings_the_phone_can_use_it_and_reach_every_saved_host)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
                settings.automationAllowed.forEach { pkg ->
                    RowDivider()
                    GroupRow(
                        title = pkg,
                        subtitle = stringResource(R.string.automationsettings_allowed),
                        icon = Icons.Rounded.Bolt, iconTint = MaterialTheme.colorScheme.primary,
                        trailing = {
                            TextButton(onClick = {
                                app.store.updateSettings { it.copy(automationAllowed = it.automationAllowed - pkg) }
                            }) { Text(stringResource(R.string.automationsettings_withdraw)) }
                        },
                    )
                }
                RowDivider()
                if (settings.automationLog.isEmpty()) {
                    GroupRow(
                        title = stringResource(R.string.automationsettings_nothing_has_called_yet),
                        subtitle = stringResource(R.string.automationsettings_the_last_ten_calls_appear_here_with_the_app_that),
                        icon = Icons.Rounded.History, iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    settings.automationLog.forEach { call ->
                        val unknown = call.caller.isNotBlank() && call.caller !in settings.automationAllowed
                        GroupRow(
                            title = call.what,
                            subtitle = listOfNotNull(
                                call.caller.ifBlank { stringResource(R.string.automationsettings_an_app_that_did_not_identify_itself) },
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
                                }) { Text(stringResource(R.string.automationsettings_allow)) }
                            }),
                        )
                        RowDivider()
                    }
                    GroupRow(
                        title = stringResource(R.string.automationsettings_forget_these_calls),
                        icon = Icons.Rounded.Delete, iconTint = MaterialTheme.colorScheme.error,
                        onClick = { app.store.updateSettings { it.copy(automationLog = emptyList()) } },
                    )
                }
            }
        }
    }
}
