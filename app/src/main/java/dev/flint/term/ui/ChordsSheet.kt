package dev.flint.term.ui

import dev.flint.term.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.flint.term.data.Chord
import dev.flint.term.data.Settings
import dev.flint.term.data.usesTmuxControls
import dev.flint.term.session.TerminalSession
import dev.flint.term.session.TmuxWindow
import dev.flint.term.session.TmuxWindows
import dev.flint.term.terminal.Chords
import dev.flint.term.terminal.TerminalView
import dev.flint.term.terminal.sendChord
import kotlinx.coroutines.launch

/**
 * The two things a session driven from a phone borrows from tmux: a sheet of
 * chords to tap instead of typing them, and the list of windows on the other
 * end.
 *
 * Both are shown from the terminal and both are one call, so the screen that
 * owns them carries two booleans and nothing else.
 */
@Composable
fun TmuxControls(
    view: TerminalView,
    session: TerminalSession,
    settings: Settings,
    chords: Boolean,
    windows: Boolean,
    onDismiss: () -> Unit,
    onManage: () -> Unit,
) {
    val host = session.host
    val prefix = host?.tmuxPrefix?.ifBlank { "C-b" } ?: "C-b"
    val scope = rememberCoroutineScope()
    if (chords) {
        ChordsSheet(
            chords = settings.chords.ifEmpty { Chords.defaults },
            // Prefix chords are noise on a host with no tmux to catch them.
            tmux = host?.usesTmuxControls(settings) == true,
            onDismiss = onDismiss,
            onManage = onManage,
            onSend = { chord -> view.sendChord(chord.keys, prefix); onDismiss() },
        )
    }
    if (windows) {
        TmuxWindowsSheet(
            session = session,
            onDismiss = onDismiss,
            onSelect = { window ->
                // The keystroke is instant and works whatever the exec channel
                // is doing; only a window tmux has no key for is asked for.
                if (window.hasPrefixKey) {
                    view.sendChord("prefix ${window.index}", prefix)
                } else {
                    scope.launch { runCatching { TmuxWindows.select(session, window.index) } }
                }
                onDismiss()
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ChordsSheet(
    chords: List<Chord>,
    tmux: Boolean,
    onDismiss: () -> Unit,
    onManage: () -> Unit,
    onSend: (Chord) -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // The known tabs first, in their own order, then anything the user invented.
    val tabs = remember(chords, tmux) {
        val present = chords.map { it.tab }.distinct()
        (Chords.tabs.map { it.first }.filter { it in present } + present.filterNot { t -> Chords.tabs.any { it.first == t } })
            .filter { tmux || it != Chords.TMUX }
    }
    var tab by remember(tabs) { mutableStateOf(tabs.firstOrNull().orEmpty()) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.chordssheet_chords), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onManage) { Text(stringResource(R.string.chordssheet_edit)) }
            }
            if (tabs.size > 1) {
                Spacer(Modifier.height(8.dp))
                Segmented(tabs.map { Chords.tabLabel(it) }, tabs.indexOf(tab).coerceAtLeast(0)) { i -> tab = tabs[i] }
            }
            Spacer(Modifier.height(12.dp))
            val shown = chords.filter { it.tab == tab && it.keys.isNotBlank() }
            if (shown.isEmpty()) {
                Text(
                    stringResource(R.string.chordssheet_nothing_on_this_tab_yet_edit_adds_a_chord),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(
                Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                shown.forEach { chord ->
                    SuggestionChip(onClick = { onSend(chord) }, label = { Text(chord.label.ifBlank { chord.keys }) })
                }
            }
        }
    }
}

/**
 * What tmux is running over there, asked once when the sheet opens.
 *
 * There is no polling: a window list is read and acted on in a second, and a
 * sheet that refetched would spend somebody's data on a screen they have
 * already tapped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TmuxWindowsSheet(session: TerminalSession, onDismiss: () -> Unit, onSelect: (TmuxWindow) -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var windows by remember { mutableStateOf<List<TmuxWindow>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(session) {
        runCatching { TmuxWindows.list(session) }
            .onSuccess { windows = it }
            .onFailure { error = it.message ?: "tmux did not answer" }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        // The groups below sit on the sheet, not the page: their gaps must match it.
        CompositionLocalProvider(LocalBackdrop provides MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(stringResource(R.string.chordssheet_tmux_windows), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(8.dp))
            val list = windows
            when {
                error != null -> Message(error!!)
                list == null -> Row(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(12.dp))
                    Text(stringResource(R.string.chordssheet_asking_tmux), style = MaterialTheme.typography.bodyMedium)
                }
                list.isEmpty() -> Message(stringResource(R.string.chordssheet_no_windows_this_session_is_not_inside_tmux))
                else -> Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Group {
                        list.forEachIndexed { i, w ->
                            GroupRow(
                                title = "${w.index}: ${w.name}",
                                subtitle = when {
                                    w.active -> stringResource(R.string.chordssheet_current_window)
                                    !w.hasPrefixKey -> stringResource(R.string.chordssheet_past_9_so_tmux_is_asked_to_switch)
                                    else -> null
                                },
                                onClick = { onSelect(w) },
                            )
                            if (i < list.lastIndex) RowDivider()
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
    )
}
