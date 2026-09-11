package dev.flint.term.ui.settings

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
                title = "Back up to a file",
                subtitle = "Hosts, keys, snippets, tunnels and settings, protected with a passphrase",
                icon = Icons.Rounded.Archive, iconTint = MaterialTheme.colorScheme.primary,
                onClick = { backup = true },
            )
            RowDivider()
            GroupRow(
                title = "Restore from a file",
                subtitle = "Adds what the file has. Nothing here is deleted",
                icon = Icons.Rounded.Unarchive, iconTint = MaterialTheme.colorScheme.primary,
                onClick = { restore = true },
            )
        }
        Group("Export") {
            GroupRow(
                title = "Export as an SSH config",
                subtitle = "A plain ~/.ssh/config another machine can use",
                icon = Icons.Rounded.Description, iconTint = MaterialTheme.colorScheme.primary,
                onClick = { sshConfig = true },
            )
        }
    }
}
