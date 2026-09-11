package dev.flint.term.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.DataSaverOn
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.flint.term.App
import dev.flint.term.data.DataSaver
import dev.flint.term.session.SshVpnService
import dev.flint.term.ui.ActionSheet
import dev.flint.term.ui.AppSlider
import dev.flint.term.ui.Field
import dev.flint.term.ui.Group
import dev.flint.term.ui.GroupRow
import dev.flint.term.ui.IconTile
import dev.flint.term.ui.RowDivider
import dev.flint.term.ui.SheetAction
import kotlin.math.roundToInt

/** What every connection does regardless of which host it is to. */
@Composable
fun ConnectionsSettings(nav: NavController) {
    val app = LocalContext.current.applicationContext as App
    val settings by app.store.settings.collectAsStateWithLifecycle()

    var dataSheet by remember { mutableStateOf(false) }
    if (dataSheet) {
        ActionSheet(
            onDismiss = { dataSheet = false },
            title = "Data saver",
            subtitle = "Waits for Wi-Fi before transferring files, and sends keepalives less often. Typing is never delayed.",
            actions = DataSaver.entries.map { mode ->
                SheetAction(mode.label + if (mode == settings.dataSaver) "   ✓" else "", subtitle = mode.help) {
                    app.store.updateSettings { it.copy(dataSaver = mode) }
                    dataSheet = false
                }
            },
        )
    }

    SettingsSection(nav, "Connections") {
        Group("SSH") {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Icons.Rounded.Timer, MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Text("Keepalive", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(if (settings.keepaliveSeconds == 0) "off" else "every ${settings.keepaliveSeconds}s", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                AppSlider(
                    value = settings.keepaliveSeconds.toFloat(),
                    onValueChange = { v -> app.store.updateSettings { it.copy(keepaliveSeconds = (v / 5).roundToInt() * 5) } },
                    valueRange = 0f..120f,
                )
                Text("Keeps idle connections alive through NAT and mobile networks. Applies to new sessions.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RowDivider()
            GroupRow(
                title = "Data saver",
                subtitle = "Waits for Wi-Fi to transfer files, and sends keepalives less often",
                icon = Icons.Rounded.DataSaverOn,
                iconTint = MaterialTheme.colorScheme.tertiary,
                onClick = { dataSheet = true },
                trailing = { Text(settings.dataSaver.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) },
            )
            RowDivider()
            GroupRow(
                title = "Ask before agent signing",
                subtitle = "A forwarded key asks first. Off signs whenever asked, as ssh -A does",
                icon = Icons.Rounded.Key,
                iconTint = MaterialTheme.colorScheme.tertiary,
                checked = settings.confirmAgentSignatures,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(confirmAgentSignatures = v) } },
            )
            RowDivider()
            GroupRow(
                title = "Find hosts on this network",
                subtitle = "Servers that announce SSH on this network appear under Nearby",
                icon = Icons.Rounded.Wifi,
                iconTint = MaterialTheme.colorScheme.primary,
                checked = settings.discoverNearbyHosts,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(discoverNearbyHosts = v) } },
            )
            RowDivider()
            GroupRow(
                title = "Show the server's message",
                subtitle = "What a server prints before login: a notice, or a link to sign in with",
                icon = Icons.Rounded.Campaign,
                iconTint = MaterialTheme.colorScheme.primary,
                checked = settings.showAuthBanners,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(showAuthBanners = v) } },
            )
            RowDivider()
            GroupRow(
                title = "Learn the host's shell history",
                subtitle = "Reads the host's shell history once a week, so commands you ran there can complete what you type",
                icon = Icons.Rounded.History,
                iconTint = MaterialTheme.colorScheme.primary,
                checked = settings.importShellHistory,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(importShellHistory = v) } },
            )
        }

        Group("VPN") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(
                    settings.vpnResolver,
                    { v -> app.store.updateSettings { it.copy(vpnResolver = v.trim()) } },
                    "Resolver",
                    mono = true,
                    placeholder = SshVpnService.DEFAULT_RESOLVER,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Text(
                    "Used while a host acts as a VPN. Lookups are sent over TCP from that host, so " +
                        "putting an internal DNS server here is what makes internal names work. " +
                        "127.0.0.1 means the server's own resolver.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
