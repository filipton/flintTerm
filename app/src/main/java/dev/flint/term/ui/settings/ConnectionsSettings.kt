package dev.flint.term.ui.settings

import dev.flint.term.R
import androidx.compose.ui.res.stringResource
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
            title = stringResource(R.string.connectionssettings_data_saver),
            subtitle = stringResource(R.string.connectionssettings_waits_for_wi_fi_before_transferring_files_and_se),
            actions = DataSaver.entries.map { mode ->
                SheetAction(stringResource(mode.label) + if (mode == settings.dataSaver) "   ✓" else "", subtitle = stringResource(mode.help)) {
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
                    Text(stringResource(R.string.connectionssettings_keepalive), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(if (settings.keepaliveSeconds == 0) "off" else stringResource(R.string.connectionssettings_every_s, settings.keepaliveSeconds), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                AppSlider(
                    value = settings.keepaliveSeconds.toFloat(),
                    onValueChange = { v -> app.store.updateSettings { it.copy(keepaliveSeconds = (v / 5).roundToInt() * 5) } },
                    valueRange = 0f..120f,
                )
                Text(stringResource(R.string.connectionssettings_keeps_idle_connections_alive_through_nat_and_mob), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RowDivider()
            GroupRow(
                title = stringResource(R.string.connectionssettings_data_saver),
                subtitle = stringResource(R.string.connectionssettings_waits_for_wi_fi_to_transfer_files_and_sends_keep),
                icon = Icons.Rounded.DataSaverOn,
                iconTint = MaterialTheme.colorScheme.tertiary,
                onClick = { dataSheet = true },
                trailing = { Text(stringResource(settings.dataSaver.label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) },
            )
            RowDivider()
            GroupRow(
                title = stringResource(R.string.connectionssettings_ask_before_agent_signing),
                subtitle = stringResource(R.string.connectionssettings_a_forwarded_key_asks_first_off_signs_whenever_as),
                icon = Icons.Rounded.Key,
                iconTint = MaterialTheme.colorScheme.tertiary,
                checked = settings.confirmAgentSignatures,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(confirmAgentSignatures = v) } },
            )
            RowDivider()
            GroupRow(
                title = stringResource(R.string.connectionssettings_find_hosts_on_this_network),
                subtitle = stringResource(R.string.connectionssettings_servers_that_announce_ssh_on_this_network_appear),
                icon = Icons.Rounded.Wifi,
                iconTint = MaterialTheme.colorScheme.primary,
                checked = settings.discoverNearbyHosts,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(discoverNearbyHosts = v) } },
            )
            RowDivider()
            GroupRow(
                title = stringResource(R.string.connectionssettings_show_the_server_s_message),
                subtitle = stringResource(R.string.connectionssettings_what_a_server_prints_before_login_a_notice_or_a),
                icon = Icons.Rounded.Campaign,
                iconTint = MaterialTheme.colorScheme.primary,
                checked = settings.showAuthBanners,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(showAuthBanners = v) } },
            )
            RowDivider()
            GroupRow(
                title = stringResource(R.string.connectionssettings_learn_the_host_s_shell_history),
                subtitle = stringResource(R.string.connectionssettings_reads_the_host_s_shell_history_once_a_week_so_co),
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
                    stringResource(R.string.connectionssettings_used_while_a_host_acts_as_a_vpn_lookups_are_sent) +
                        stringResource(R.string.connectionssettings_putting_an_internal_dns_server_here_is_what_make) +
                        stringResource(R.string.connectionssettings_127_0_0_1_means_the_server_s_own_resolver),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
