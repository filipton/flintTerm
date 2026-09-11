package dev.flint.term.ui

import dev.flint.term.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.flint.term.App
import dev.flint.term.data.Chord
import dev.flint.term.terminal.ChordParser
import dev.flint.term.terminal.Chords

/**
 * The chords sheet's contents, in the order it shows them.
 *
 * An untouched setting is empty and means "the built-in set", so the first
 * edit of any kind writes that set out and changes it from there — otherwise
 * deleting one chord would look like deleting all of them.
 */
@Composable
fun ChordsScreen(nav: NavController) {
    val app = LocalContext.current.applicationContext as App
    val settings by app.store.settings.collectAsStateWithLifecycle()
    val chords = settings.chords.ifEmpty { Chords.defaults }
    var editing by remember { mutableStateOf<Chord?>(null) }

    fun save(list: List<Chord>) = app.store.updateSettings { it.copy(chords = list) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppHeader(
                title = stringResource(R.string.chordsscreen_chords), onBack = { nav.popBackStack() },
                actions = {
                    if (settings.chords.isNotEmpty()) {
                        IconButton(onClick = { save(emptyList()) }) { Icon(Icons.Rounded.RestartAlt, stringResource(R.string.chordsscreen_reset_to_the_built_in_chords)) }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = Chord() },
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text(stringResource(R.string.chordsscreen_new_chord)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(20.dp),
            )
        },
    ) { padding ->
        LazyColumn(state = rememberScreenListState("chords"), modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 110.dp)) {
            item {
                Text(
                    stringResource(R.string.chordsscreen_hold_the_ctrl_key_above_the_keyboard_to_open_the) +
                        stringResource(R.string.chordsscreen_write_them_c_b_c_m_x_s_tab_the_word_prefix_stand),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
            val tabs = Chords.tabs.map { it.first } + chords.map { it.tab }.filterNot { t -> Chords.tabs.any { it.first == t } }
            tabs.distinct().forEach { tab ->
                val rows = chords.filter { it.tab == tab }
                if (rows.isEmpty()) return@forEach
                item(key = tab) {
                    Group(Chords.tabLabel(tab)) {
                        rows.forEachIndexed { i, c ->
                            GroupRow(
                                title = c.label.ifBlank { c.keys },
                                subtitle = c.keys,
                                subtitleMono = true,
                                icon = Icons.Rounded.Keyboard,
                                iconTint = MaterialTheme.colorScheme.secondary,
                                onClick = { editing = c },
                            )
                            if (i < rows.lastIndex) RowDivider()
                        }
                    }
                }
            }
        }
    }

    editing?.let { chord ->
        ChordEditor(
            initial = chord,
            onDismiss = { editing = null },
            onSave = { edited ->
                val at = chords.indexOfFirst { it.id == edited.id }
                save(if (at >= 0) chords.toMutableList().also { it[at] = edited } else chords + edited)
                editing = null
            },
            onDelete = { save(chords.filterNot { it.id == chord.id }); editing = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChordEditor(initial: Chord, onDismiss: () -> Unit, onSave: (Chord) -> Unit, onDelete: () -> Unit) {
    var label by remember { mutableStateOf(initial.label) }
    var keys by remember { mutableStateOf(initial.keys) }
    var tab by remember { mutableStateOf(initial.tab) }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isNew = initial.keys.isBlank()
    val tabs = Chords.tabs.map { it.first }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (isNew) stringResource(R.string.chordsscreen_new_chord) else stringResource(R.string.chordsscreen_edit_chord), style = MaterialTheme.typography.titleLarge)
            Field(label, { label = it }, "Label", placeholder = stringResource(R.string.chordsscreen_new_window), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
            Field(keys, { keys = it }, "Keys", mono = true, placeholder = stringResource(R.string.chordsscreen_prefix_c), keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
            // Said before the chord is saved, because a chord that sends
            // nothing looks exactly like one that works until it is tapped.
            val presses = ChordParser.parse(keys)
            Text(
                when {
                    keys.isBlank() -> stringResource(R.string.chordsscreen_one_or_more_keys_separated_by_spaces)
                    presses == null -> stringResource(R.string.chordsscreen_not_a_chord_check_the_modifier_and_the_key_name)
                    else -> stringResource(R.string.chordsscreen_sends_key_press, presses.size, if (presses.size == 1) "" else "es")
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Segmented(tabs.map { Chords.tabLabel(it) }, tabs.indexOf(tab).coerceAtLeast(0)) { i -> tab = tabs[i] }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onSave(initial.copy(label = label.trim(), keys = keys.trim(), tab = tab)) },
                    enabled = presses != null,
                ) { Text(stringResource(R.string.chordsscreen_save)) }
                if (!isNew) {
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Rounded.Delete, null, Modifier.width(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.chordsscreen_delete))
                    }
                }
            }
        }
    }
}
