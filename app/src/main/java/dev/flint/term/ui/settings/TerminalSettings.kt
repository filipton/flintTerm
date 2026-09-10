package dev.flint.term.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardTab
import androidx.compose.material.icons.rounded.Movie
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.flint.term.App
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

/** How the terminal itself behaves: how much it remembers, what it guesses, and what it writes down. */
@Composable
fun TerminalSettings(nav: NavController) {
    val app = LocalContext.current.applicationContext as App
    val settings by app.store.settings.collectAsStateWithLifecycle()

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
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Redraw limit", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text("${settings.maxFps} per second", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                AppSlider(
                    value = settings.maxFps.toFloat(),
                    onValueChange = { v -> app.store.updateSettings { it.copy(maxFps = (v / 15).roundToInt().coerceAtLeast(1) * 15) } },
                    valueRange = 15f..120f,
                )
                Text(
                    "How often a terminal repaints while something on it is changing. Higher is smoother " +
                        "and costs battery: every redraw repacks and repaints the whole grid, so 120 costs " +
                        "four times what 30 does. Above 30 a terminal does not look any different.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                    "Off leaves both protocols unanswered, so a program that asks falls back to text. A host can turn images off on its own.",
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
                subtitle = "Only while one is showing; otherwise Tab is the shell's own completion",
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
                subtitle = "Watch a cast play back, read a log, share or delete one",
                icon = Icons.Rounded.Movie,
                iconTint = MaterialTheme.colorScheme.primary,
                onClick = { nav.navigate(Routes.RECORDINGS) },
            )
        }
    }
}
