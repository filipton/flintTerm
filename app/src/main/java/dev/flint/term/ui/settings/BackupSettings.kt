package dev.flint.term.ui.settings

import dev.flint.term.R
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavController
import dev.flint.term.ui.BackupDialog
import dev.flint.term.ui.Group
import dev.flint.term.ui.GroupRow
import dev.flint.term.ui.RestoreFlow
import dev.flint.term.ui.RowDivider
import dev.flint.term.ui.SshConfigExportDialog

/** Everything the app knows, out to a file and back in again — and one way out that is not a way back. */
@Composable
fun BackupSettings(nav: NavController) {
    var backup by remember { mutableStateOf(false) }
    if (backup) BackupDialog(onDismiss = { backup = false })
    var restore by remember { mutableStateOf(false) }
    if (restore) RestoreFlow(onDismiss = { restore = false })
    var sshConfig by remember { mutableStateOf(false) }
    if (sshConfig) SshConfigExportDialog(onDismiss = { sshConfig = false })

    SettingsSection(nav, "Backup") {
        Group("Backup") {
            GroupRow(
                title = stringResource(R.string.backupsettings_back_up_to_a_file),
                subtitle = stringResource(R.string.backupsettings_hosts_keys_snippets_tunnels_and_settings_protect),
                icon = Icons.Rounded.Archive, iconTint = MaterialTheme.colorScheme.primary,
                onClick = { backup = true },
            )
            RowDivider()
            GroupRow(
                title = stringResource(R.string.backupsettings_restore_from_a_file),
                subtitle = stringResource(R.string.backupsettings_adds_what_the_file_has_nothing_here_is_deleted),
                icon = Icons.Rounded.Unarchive, iconTint = MaterialTheme.colorScheme.primary,
                onClick = { restore = true },
            )
        }
        Group("Export") {
            GroupRow(
                title = stringResource(R.string.backupsettings_export_as_an_ssh_config),
                subtitle = stringResource(R.string.backupsettings_a_plain_ssh_config_another_machine_can_use),
                icon = Icons.Rounded.Description, iconTint = MaterialTheme.colorScheme.primary,
                onClick = { sshConfig = true },
            )
        }
    }
}
