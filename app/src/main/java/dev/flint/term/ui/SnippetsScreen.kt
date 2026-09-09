package dev.flint.term.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.flint.term.App
import dev.flint.term.data.Snippet

@Composable
fun SnippetsScreen(nav: NavController) {
    val app = LocalContext.current.applicationContext as App
    val snippets by app.store.snippets.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Snippet?>(null) }
    var query by remember { mutableStateOf("") }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppHeader(title = "Snippets", onBack = null, actions = { CommandPaletteButton() }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = Snippet() },
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text("New snippet") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(20.dp),
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 110.dp)) {
            // Past a handful, scrolling for the one you want is the slow part.
            if (snippets.size > 8) {
                item {
                    Field(
                        query, { query = it }, "Search snippets",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        leading = { Icon(Icons.Rounded.Search, null) },
                    )
                }
            }
            if (snippets.isEmpty()) {
                item {
                    EmptyState(
                        Icons.Rounded.AutoAwesome, "No snippets yet",
                        "Save the commands you type all the time. In the terminal, the ✦ key lists them; {{placeholders}} are asked for before typing.",
                        "Add a snippet",
                    ) { editing = Snippet() }
                }
            } else {
                item {
                    Group {
                        val shown = snippets
                            .filter { query.isBlank() || it.name.contains(query, true) || it.command.contains(query, true) }
                            .sortedBy { it.name.lowercase() }
                        shown.forEachIndexed { i, sn ->
                            GroupRow(
                                title = sn.name.ifBlank { sn.command },
                                subtitle = sn.command + if (sn.hostIds.isNotEmpty()) "   ·   ${sn.hostIds.size} host${if (sn.hostIds.size > 1) "s" else ""}" else "",
                                subtitleMono = true,
                                icon = Icons.Rounded.AutoAwesome,
                                iconTint = HostAccents[Math.floorMod(sn.id.hashCode(), HostAccents.size)],
                                onClick = { editing = sn },
                            )
                            if (i < shown.lastIndex) RowDivider()
                        }
                    }
                }
            }
        }
    }

    editing?.let { sn -> SnippetEditor(sn, onDismiss = { editing = null }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnippetEditor(initial: Snippet, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as App
    val hosts by app.store.hosts.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf(initial.name) }
    var command by remember { mutableStateOf(initial.command) }
    var run by remember { mutableStateOf(initial.run) }
    var hostIds by remember { mutableStateOf(initial.hostIds) }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isNew = initial.command.isBlank()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (isNew) "New snippet" else "Edit snippet", style = MaterialTheme.typography.titleLarge)
            Field(name, { name = it }, "Name", placeholder = "Restart nginx", keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
            Field(
                command, { command = it }, "Command", mono = true, singleLine = false, minLines = 2,
                placeholder = "sudo systemctl restart {{service}}",
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            )
            val ph = Snippet(command = command).placeholders
            Text(
                if (ph.isEmpty()) "Tip: {{name}} becomes a field you fill in when using the snippet."
                else "Asks for: ${ph.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Press Enter after typing it", Modifier.weight(1f))
                AppSwitch(run, { run = it })
            }
            Text("Show for", style = MaterialTheme.typography.titleSmall)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = hostIds.isEmpty(), onClick = { hostIds = emptyList() }, label = { Text("All hosts") })
                hosts.forEach { h ->
                    FilterChip(
                        selected = h.id in hostIds,
                        onClick = { hostIds = if (h.id in hostIds) hostIds - h.id else hostIds + h.id },
                        label = { Text(h.displayName) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    enabled = command.isNotBlank(),
                    onClick = {
                        app.store.upsertSnippet(initial.copy(name = name.trim(), command = command.trimEnd('\n'), run = run, hostIds = hostIds))
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Save") }
                if (!isNew) {
                    TextButton(onClick = { app.store.deleteSnippet(initial.id); onDismiss() }) {
                        Icon(Icons.Rounded.Delete, null, Modifier.width(18.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(6.dp)); Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
