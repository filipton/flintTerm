package dev.flint.term.ui

import dev.flint.term.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.flint.term.App
import dev.flint.term.data.Snippet

/**
 * Snippet picker shown from the terminal: search, tap to insert. Placeholders
 * are collected in a small dialog first. `onInsert(text, run)` types the text
 * (and presses Enter when `run`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnippetSheet(hostId: String?, onDismiss: () -> Unit, onManage: () -> Unit, onInsert: (String, Boolean) -> Unit) {
    val app = LocalContext.current.applicationContext as App
    val all by app.store.snippets.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var filling by remember { mutableStateOf<Snippet?>(null) }
    var creating by remember { mutableStateOf(false) }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val visible = all
        .filter { it.hostIds.isEmpty() || hostId in it.hostIds }
        .filter { query.isBlank() || it.name.contains(query, true) || it.command.contains(query, true) }
        .sortedBy { it.name.lowercase() }

    fun use(sn: Snippet) {
        if (sn.placeholders.isEmpty()) {
            onInsert(sn.command, sn.run); onDismiss()
        } else {
            filling = sn
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(bottom = 20.dp)) {
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(stringResource(R.string.snippetsheet_snippets), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { creating = true }) { Icon(Icons.Rounded.Add, null, Modifier.width(18.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.snippetsheet_new)) }
                TextButton(onClick = { onDismiss(); onManage() }) { Text(stringResource(R.string.snippetsheet_manage)) }
            }
            if (all.size > 5) {
                Field(query, { query = it }, "Search", Modifier.padding(horizontal = 24.dp), leading = { Icon(Icons.Rounded.Search, null) })
                Spacer(Modifier.height(6.dp))
            }
            if (visible.isEmpty()) {
                Text(
                    if (all.isEmpty()) stringResource(R.string.snippetsheet_no_snippets_yet_tap_new_to_save_a_command_you_us) else stringResource(R.string.snippetsheet_nothing_matches),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(visible, key = { it.id }) { sn ->
                    GroupRow(
                        title = sn.name.ifBlank { sn.command },
                        subtitle = if (sn.name.isBlank()) null else sn.command,
                        subtitleMono = true,
                        icon = Icons.Rounded.AutoAwesome,
                        iconTint = HostAccents[Math.floorMod(sn.id.hashCode(), HostAccents.size)],
                        onClick = { use(sn) },
                        trailing = if (!sn.run) ({ Text(stringResource(R.string.snippetsheet_insert), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }) else null,
                    )
                }
            }
        }
    }

    filling?.let { sn ->
        val values = remember(sn.id) { mutableStateOf(sn.placeholders.associateWith { "" }) }
        AlertDialog(
            onDismissRequest = { filling = null },
            title = { Text(sn.name.ifBlank { stringResource(R.string.snippetsheet_fill_in) }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(sn.command, style = CodeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    sn.placeholders.forEach { ph ->
                        Field(values.value[ph].orEmpty(), { v -> values.value = values.value + (ph to v) }, ph, mono = true)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    var text = sn.command
                    values.value.forEach { (k, v) -> text = text.replace(Regex("\\{\\{\\s*" + Regex.escape(k) + "\\s*\\}\\}"), Regex.escapeReplacement(v)) }
                    filling = null
                    onInsert(text, sn.run)
                    onDismiss()
                }) { Text(if (sn.run) "Run" else "Insert") }
            },
            dismissButton = { TextButton(onClick = { filling = null }) { Text(stringResource(R.string.snippetsheet_cancel)) } },
        )
    }

    if (creating) SnippetEditor(Snippet(), onDismiss = { creating = false })
    Spacer(Modifier.fillMaxWidth())
}
