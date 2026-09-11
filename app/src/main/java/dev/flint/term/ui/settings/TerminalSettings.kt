package dev.flint.term.ui.settings

import dev.flint.term.R
import androidx.compose.ui.res.stringResource
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
            title = stringResource(R.string.terminalsettings_terminal_type),
            subtitle = stringResource(R.string.terminalsettings_what_a_session_tells_the_server_it_is_applies_to),
            actions = TERM_PRESETS.map { (name, help) ->
                SheetAction(name + if (name == current) "   ✓" else "", subtitle = help) {
                    app.store.updateSettings { it.copy(termName = name) }
                    termSheet = false
                }
            } + SheetAction(
                stringResource(R.string.terminalsettings_custom),
                subtitle = if (TERM_PRESETS.none { it.first == current }) current else stringResource(R.string.terminalsettings_a_name_the_host_has_terminfo_for),
            ) {
                termCustom = current
                termSheet = false
            },
        )
    }
    termCustom?.let { typed ->
        AlertDialog(
            onDismissRequest = { termCustom = null },
            title = { Text(stringResource(R.string.terminalsettings_terminal_type)) },
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
                }) { Text(stringResource(R.string.terminalsettings_set)) }
            },
            dismissButton = { TextButton(onClick = { termCustom = null }) { Text(stringResource(R.string.terminalsettings_cancel)) } },
        )
    }

    var predictSheet by remember { mutableStateOf(false) }
    if (predictSheet) {
        ActionSheet(
            onDismiss = { predictSheet = false },
            title = stringResource(R.string.terminalsettings_predictive_echo),
            subtitle = stringResource(R.string.terminalsettings_mosh_draws_a_keystroke_before_the_server_confirm),
            actions = PredictiveEcho.entries.map { mode ->
                SheetAction(
                    stringResource(mode.label) + if (mode == settings.predictiveEcho) "   ✓" else "",
                    subtitle = stringResource(mode.help),
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
                    Text(stringResource(R.string.terminalsettings_scrollback), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.terminalsettings_k_lines, settings.scrollback / 1000), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                AppSlider(
                    value = settings.scrollback.toFloat(),
                    onValueChange = { v -> app.store.updateSettings { it.copy(scrollback = (v / 1000).roundToInt().coerceAtLeast(1) * 1000) } },
                    valueRange = 1000f..50000f,
                )
                Text(stringResource(R.string.terminalsettings_applies_to_new_sessions), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RowDivider()
            GroupRow(
                title = stringResource(R.string.terminalsettings_terminal_type),
                subtitle = settings.termName.trim().ifEmpty { DEFAULT_TERM },
                subtitleMono = true,
                icon = Icons.Rounded.Terminal,
                iconTint = MaterialTheme.colorScheme.primary,
                onClick = { termSheet = true },
            )
            RowDivider()
            GroupRow(
                title = stringResource(R.string.terminalsettings_redraw_limit),
                subtitle = stringResource(R.string.terminalsettings_saves_battery_when_output_arrives_very_fast_off),
                icon = Icons.Rounded.Speed,
                iconTint = MaterialTheme.colorScheme.primary,
                checked = settings.maxFps > 0,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(maxFps = if (v) 30 else 0) } },
            )
            if (settings.maxFps > 0) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.terminalsettings_at_most), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(stringResource(R.string.terminalsettings_per_second, settings.maxFps), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    AppSlider(
                        value = settings.maxFps.toFloat(),
                        onValueChange = { v -> app.store.updateSettings { it.copy(maxFps = (v / 15).roundToInt().coerceAtLeast(1) * 15) } },
                        valueRange = 15f..120f,
                    )
                    Text(
                        stringResource(R.string.terminalsettings_without_a_limit_the_terminal_redraws_only_when_s) +
                            stringResource(R.string.terminalsettings_all_while_the_screen_is_still_a_limit_only_matte) +
                            stringResource(R.string.terminalsettings_fast_and_there_it_saves_about_a_fifth_of_the_bat),
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
                        Text(stringResource(R.string.terminalsettings_inline_images), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.terminalsettings_pictures_drawn_in_the_terminal_by_chafa_timg_or),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Segmented(TerminalImages.entries.map { stringResource(it.label) }, settings.terminalImages.ordinal) { i ->
                    app.store.updateSettings { it.copy(terminalImages = TerminalImages.entries[i]) }
                }
                Text(
                    stringResource(R.string.terminalsettings_off_means_the_app_answers_neither_protocol_so_a),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RowDivider()
            GroupRow(
                title = stringResource(R.string.terminalsettings_predictive_echo),
                subtitle = stringResource(R.string.terminalsettings_mosh_only, settings.predictiveEcho.help),
                icon = Icons.Rounded.Bolt,
                iconTint = MaterialTheme.colorScheme.secondary,
                onClick = { predictSheet = true },
                trailing = { Text(stringResource(settings.predictiveEcho.label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) },
            )
        }

        Group("Completion") {
            GroupRow(
                title = stringResource(R.string.terminalsettings_complete_from_history),
                subtitle = stringResource(R.string.terminalsettings_the_rest_of_a_command_you_have_run_here_appears),
                icon = Icons.Rounded.History,
                iconTint = MaterialTheme.colorScheme.primary,
                checked = settings.completeFromHistory,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(completeFromHistory = v) } },
            )
            RowDivider()
            GroupRow(
                title = stringResource(R.string.terminalsettings_tab_takes_the_suggestion),
                subtitle = stringResource(R.string.terminalsettings_only_while_one_is_showing_otherwise_tab_is_the_s),
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
                        Text(stringResource(R.string.terminalsettings_session_recordings), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(settings.recordingFormat.help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Segmented(RecordingFormat.entries.map { stringResource(it.label) }, settings.recordingFormat.ordinal) { i ->
                    app.store.updateSettings { it.copy(recordingFormat = RecordingFormat.entries[i]) }
                }
                Text(
                    stringResource(R.string.terminalsettings_start_a_recording_from_the_terminal_s_menu_or_se),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RowDivider()
            GroupRow(
                title = stringResource(R.string.terminalsettings_recordings),
                subtitle = stringResource(R.string.terminalsettings_play_back_a_recording_read_a_log_share_or_delete),
                icon = Icons.Rounded.Movie,
                iconTint = MaterialTheme.colorScheme.primary,
                onClick = { nav.navigate(Routes.RECORDINGS) },
            )
        }
    }
}
