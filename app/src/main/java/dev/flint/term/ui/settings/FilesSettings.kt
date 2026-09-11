package dev.flint.term.ui.settings

import dev.flint.term.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.flint.term.App
import dev.flint.term.ui.Field
import dev.flint.term.ui.Group
import dev.flint.term.ui.GroupRow
import dev.flint.term.ui.RowDivider
import dev.flint.term.ui.Segmented
import dev.flint.term.data.EditorChoice

/** The file browser, and where a file handed to the terminal ends up. */
@Composable
fun FilesSettings(nav: NavController) {
    val app = LocalContext.current.applicationContext as App
    val settings by app.store.settings.collectAsStateWithLifecycle()

    SettingsSection(nav, "Files") {
        Group("Editing") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.filessettings_open_a_text_file_with), style = MaterialTheme.typography.bodyLarge)
                Segmented(EditorChoice.entries.map { it.label }, settings.editor.ordinal) { i ->
                    app.store.updateSettings { it.copy(editor = EditorChoice.entries[i]) }
                }
                Text(
                    stringResource(R.string.filessettings_anything_the_built_in_editor_will_not_open_becau),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RowDivider()
            GroupRow(
                title = stringResource(R.string.filessettings_keep_a_bak), subtitle = stringResource(R.string.filessettings_copy_the_file_on_the_host_before_saving_over_it),
                icon = Icons.Rounded.Backup, iconTint = MaterialTheme.colorScheme.secondary,
                checked = settings.editorBackup, onCheckedChange = { v -> app.store.updateSettings { it.copy(editorBackup = v) } },
                enabled = settings.editor == EditorChoice.BUILT_IN,
            )
        }

        Group("Files") {
            GroupRow(
                title = stringResource(R.string.filessettings_show_hidden_files), subtitle = stringResource(R.string.filessettings_dotfiles_in_the_sftp_browser), icon = Icons.Rounded.Visibility, iconTint = MaterialTheme.colorScheme.tertiary,
                checked = settings.showHiddenFiles, onCheckedChange = { v -> app.store.updateSettings { it.copy(showHiddenFiles = v) } },
            )
            RowDivider()
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 14.dp)) {
                Field(
                    settings.terminalUploadDir,
                    { v -> app.store.updateSettings { it.copy(terminalUploadDir = v) } },
                    stringResource(R.string.filessettings_where_files_dropped_on_the_terminal_go),
                    mono = true,
                    placeholder = stringResource(R.string.filessettings_tmp),
                    hint = stringResource(R.string.filessettings_dropping_a_file_on_the_terminal_or_inserting_one),
                )
            }
        }
    }
}
