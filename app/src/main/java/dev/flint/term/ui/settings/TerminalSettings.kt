package dev.flint.term.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardTab
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.flint.term.App
import dev.flint.term.data.DEFAULT_TERM
import dev.flint.term.data.PredictiveEcho
import dev.flint.term.data.RecordingFormat
import dev.flint.term.data.TerminalImages
import dev.flint.term.ui.ActionSheet
import dev.flint.term.ui.AppSlider
import dev.flint.term.ui.Group
import dev.flint.term.ui.GroupRow
import dev.flint.term.ui.IconTile
import dev.flint.term.ui.RowDivider
import dev.flint.term.ui.Routes
import dev.flint.term.ui.Segmented
import dev.flint.term.ui.SheetAction
import kotlin.math.roundToInt

/**
 * The terminal names worth offering, and why anyone would pick them.
 *
 * A name only works where the host has a terminfo entry for it, which is why
 * the list is short and the rest is typed in.
 */
private val TERM_PRESETS = listOf(
    DEFAULT_TERM to "Suits almost everything",
    "screen-256color" to "What tmux and screen usually want",
    "tmux-256color" to "Newer tmux, where the host knows the name",
    "xterm-kitty" to "Programs that look for the kitty protocols by name",
)

/** How the terminal itself behaves: how much it remembers, what it guesses, and what it writes down. */
@Composable
fun TerminalSettings(nav: NavController) {
    val app = LocalContext.current.applicationContext as App
    val settings by app.store.settings.collectAsStateWithLifecycle()

    var termSheet by remember { mutableStateOf(false) }
    var termCustom by remember { mutableStateOf<String?>(null) }
    if (termSheet) {
        val current = settings.termName.trim().ifEmpty { DEFAULT_TERM }
        ActionSheet(
            onDismiss = { termSheet = false },
            title = "Terminal type",
            subtitle = "What a session tells the server it is. Applies to new sessions.",
            actions = TERM_PRESETS.map { (name, help) ->
                SheetAction(name + if (name == current) "   ✓" else "", subtitle = help) {
                    app.store.updateSettings { it.copy(termName = name) }
                    termSheet = false
                }
            } + SheetAction(
                "Custom…",
                subtitle = if (TERM_PRESETS.none { it.first == current }) current else "A name the host has terminfo for",
            ) {
                termCustom = current
                termSheet = false
            },
        )
    }
    termCustom?.let { typed ->
        AlertDialog(
            onDismissRequest = { termCustom = null },
            title = { Text("Terminal type") },
            text = {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { termCustom = it },
                    singleLine = true,
                    placeholder = { Text(DEFAULT_TERM) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    app.store.updateSettings { it.copy(termName = typed.trim().ifEmpty { DEFAULT_TERM }) }
                    termCustom = null
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { termCustom = null }) { Text("Cancel") } },
        )
    }

    var predictSheet by remember { mutableStateOf(false) }
    if (predictSheet) {
        ActionSheet(
            onDismiss = { predictSheet = false },
            title = "Predictive echo",
            subtitle = "Mosh draws a keystroke before the server confirms it",
            actions = PredictiveEcho.entries.map { mode ->
                SheetAction(
                    mode.label + if (mode == settings.predictiveEcho) "   ✓" else "",
                    subtitle = mode.help,
                ) {
                    app.store.updateSettings { it.copy(predictiveEcho = mode) }
                    predictSheet = false
                }
            },
        )
    }

    SettingsSection(nav, "Terminal") {
        Group("Terminal") {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Scrollback", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text("${settings.scrollback / 1000}k lines", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                AppSlider(
                    value = settings.scrollback.toFloat(),
                    onValueChange = { v -> app.store.updateSettings { it.copy(scrollback = (v / 1000).roundToInt().coerceAtLeast(1) * 1000) } },
                    valueRange = 1000f..50000f,
                )
                Text("Applies to new sessions.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RowDivider()
            GroupRow(
                title = "Terminal type",
                subtitle = settings.termName.trim().ifEmpty { DEFAULT_TERM },
                subtitleMono = true,
                icon = Icons.Rounded.Terminal,
                iconTint = MaterialTheme.colorScheme.primary,
                onClick = { termSheet = true },
            )
            RowDivider()
            GroupRow(
                title = "Redraw limit",
                subtitle = "Saves battery when output arrives very fast. Off is as smooth as the screen allows",
                icon = Icons.Rounded.Speed,
                iconTint = MaterialTheme.colorScheme.primary,
                checked = settings.maxFps > 0,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(maxFps = if (v) 30 else 0) } },
            )
            if (settings.maxFps > 0) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("At most", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text("${settings.maxFps} per second", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    AppSlider(
                        value = settings.maxFps.toFloat(),
                        onValueChange = { v -> app.store.updateSettings { it.copy(maxFps = (v / 15).roundToInt().coerceAtLeast(1) * 15) } },
                        valueRange = 15f..120f,
                    )
                    Text(
                        "Without a limit the terminal redraws only when something changes, and not at " +
                            "all while the screen is still. A limit only matters when output arrives very " +
                            "fast, and there it saves about a fifth of the battery.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            RowDivider()
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Icons.Rounded.Image, MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Inline images", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Pictures drawn in the terminal by chafa, timg or kitty's icat",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Segmented(TerminalImages.entries.map { it.label }, settings.terminalImages.ordinal) { i ->
                    app.store.updateSettings { it.copy(terminalImages = TerminalImages.entries[i]) }
                }
                Text(
                    "Off means the app answers neither protocol, so a program that asks will use text instead. A host can turn images off on its own.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RowDivider()
            GroupRow(
                title = "Predictive echo",
                subtitle = "Mosh only  ·  ${settings.predictiveEcho.help}",
                icon = Icons.Rounded.Bolt,
                iconTint = MaterialTheme.colorScheme.secondary,
                onClick = { predictSheet = true },
                trailing = { Text(settings.predictiveEcho.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) },
            )
        }

        Group("Completion") {
            GroupRow(
                title = "Complete from history",
                subtitle = "The rest of a command you have run here appears in gray after the cursor",
                icon = Icons.Rounded.History,
                iconTint = MaterialTheme.colorScheme.primary,
                checked = settings.completeFromHistory,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(completeFromHistory = v) } },
            )
            RowDivider()
            GroupRow(
                title = "Tab takes the suggestion",
                subtitle = "Only while one is showing. Otherwise Tab is the shell's own completion",
                icon = Icons.Rounded.KeyboardTab,
                iconTint = MaterialTheme.colorScheme.primary,
                checked = settings.tabAcceptsSuggestion,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(tabAcceptsSuggestion = v) } },
                enabled = settings.completeFromHistory,
            )
        }

        Group("Recording") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Icons.Rounded.FiberManualRecord, MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Session recordings", style = MaterialTheme.typography.bodyLarge)
                        Text(settings.recordingFormat.help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Segmented(RecordingFormat.entries.map { it.label }, settings.recordingFormat.ordinal) { i ->
                    app.store.updateSettings { it.copy(recordingFormat = RecordingFormat.entries[i]) }
                }
                Text(
                    "Start a recording from the terminal's ⋮ menu, or set a host to record every session.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RowDivider()
            GroupRow(
                title = "Recordings",
                subtitle = "Play back a recording, read a log, share or delete one",
                icon = Icons.Rounded.Movie,
                iconTint = MaterialTheme.colorScheme.primary,
                onClick = { nav.navigate(Routes.RECORDINGS) },
            )
        }
    }
}
