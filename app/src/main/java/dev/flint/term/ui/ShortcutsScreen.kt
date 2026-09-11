package dev.flint.term.ui

import dev.flint.term.R
import androidx.compose.ui.res.stringResource
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backspace
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.flint.term.App
import dev.flint.term.data.KeyBinding
import dev.flint.term.terminal.KeyShortcuts
import dev.flint.term.terminal.KeyShortcuts.Shortcut

/**
 * What a hardware keyboard's chords do, one row per action.
 *
 * These are the app's own chords, not the ones the chords sheet sends: nothing
 * here reaches the host, and every row is a key press the remote will never
 * see. That is the whole cost of the screen, and why [KeyShortcuts.problem] is
 * allowed to refuse — see the sheet in [ChordsScreen] for the other kind.
 *
 * An untouched setting is empty and means "the built-in set", so the first edit
 * of any kind writes that set out and changes it from there — otherwise binding
 * one chord would look like unbinding the other nine.
 */
@Composable
fun ShortcutsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as App
    val settings by app.store.settings.collectAsStateWithLifecycle()
    val bindings = settings.shortcuts.ifEmpty { KeyShortcuts.defaults }
    var recording by remember { mutableStateOf<Shortcut?>(null) }

    BackHandler(onBack = onBack)

    fun save(list: List<KeyBinding>) = app.store.updateSettings { it.copy(shortcuts = list) }

    fun bind(action: Shortcut, chord: KeyBinding) {
        // The action's aliases go with it: having chosen a chord by hand, a
        // second one nobody asked for is a surprise. Whatever else held the
        // chord loses it, because one key press cannot have two answers.
        val kept = bindings.filterNot {
            it.action == action.name ||
                (it.keyCode == chord.keyCode && it.shift == chord.shift && it.alt == chord.alt)
        }
        save(kept + chord.copy(action = action.name))
    }

    fun clear(action: Shortcut) {
        // The keyless placeholder is what keeps the list non-empty: an empty
        // list means "the built-in set", so clearing the last chord left would
        // otherwise hand all ten straight back.
        save(bindings.filterNot { it.action == action.name } + KeyBinding(action.name, KeyEvent.KEYCODE_UNKNOWN))
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppHeader(
                title = stringResource(R.string.shortcutsscreen_shortcuts), onBack = onBack,
                actions = {
                    if (settings.shortcuts.isNotEmpty()) {
                        IconButton(onClick = { save(emptyList()) }) { Icon(Icons.Rounded.RestartAlt, stringResource(R.string.shortcutsscreen_reset_to_the_built_in_shortcuts)) }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(state = rememberScreenListState("shortcuts"), modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 40.dp)) {
            item {
                Text(
                    stringResource(R.string.shortcutsscreen_key_combinations_a_hardware_keyboard_gives_to_th) +
                        stringResource(R.string.shortcutsscreen_sees_them_so_they_all_use_ctrl_and_nearly_all_us) +
                        stringResource(R.string.shortcutsscreen_shell_leaves_free),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
            item {
                Group("Actions") {
                    Shortcut.entries.forEachIndexed { i, action ->
                        val chord = KeyShortcuts.binding(bindings, action)
                        GroupRow(
                            title = action.label,
                            subtitle = chord?.let { KeyShortcuts.describe(it) } ?: stringResource(R.string.shortcutsscreen_not_bound),
                            subtitleMono = chord != null,
                            icon = Icons.Rounded.Keyboard,
                            iconTint = MaterialTheme.colorScheme.secondary,
                            onClick = { recording = action },
                            trailing = if (chord == null) {
                                null
                            } else {
                                { IconButton(onClick = { clear(action) }) { Icon(Icons.Rounded.Backspace, stringResource(R.string.shortcutsscreen_clear, action.label)) } }
                            },
                        )
                        if (i < Shortcut.entries.lastIndex) RowDivider()
                    }
                }
            }
        }
    }

    recording?.let { action ->
        RecordDialog(
            action = action,
            bound = KeyShortcuts.binding(bindings, action) != null,
            onDismiss = { recording = null },
            onBind = { chord -> bind(action, chord); recording = null },
            onClear = { clear(action); recording = null },
        )
    }
}

/**
 * Catches the next key press and says whether it can be kept.
 *
 * The chord is shown before it is saved and the reason a bad one is refused is
 * shown with it, because the mistake this screen makes possible — taking Ctrl+C
 * away from the shell — leaves a terminal that cannot be interrupted and no
 * obvious way to work out why.
 */
@Composable
private fun RecordDialog(
    action: Shortcut,
    bound: Boolean,
    onDismiss: () -> Unit,
    onBind: (KeyBinding) -> Unit,
    onClear: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    var caught by remember { mutableStateOf<KeyBinding?>(null) }
    val problem = caught?.let { KeyShortcuts.problem(it) }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .onPreviewKeyEvent { event ->
                val code = event.key.nativeKeyCode
                // A modifier struck on its own is half of the chord, not the
                // chord; Back is how the dialog is left on a device whose
                // keyboard is the only way in.
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_CAPS_LOCK || KeyEvent.isModifierKey(code)) {
                    return@onPreviewKeyEvent false
                }
                caught = KeyBinding(
                    action = action.name,
                    keyCode = code,
                    ctrl = event.isCtrlPressed,
                    shift = event.isShiftPressed,
                    alt = event.isAltPressed,
                )
                true
            }
            .focusRequester(focus)
            .focusable(),
        title = { Text(stringResource(R.string.shortcutsscreen_press_the_keys_for, action.label)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    caught?.let { KeyShortcuts.describe(it) } ?: stringResource(R.string.shortcutsscreen_waiting_for_a_key),
                    style = if (caught != null) CodeStyle else MaterialTheme.typography.bodyLarge,
                    color = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    problem ?: stringResource(R.string.shortcutsscreen_hold_ctrl_and_shift_then_press_the_key_this_need),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (bound) {
                    TextButton(onClick = onClear, contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.shortcutsscreen_clear_this_shortcut)) }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { caught?.let(onBind) },
                enabled = caught != null && problem == null,
            ) { Text(stringResource(R.string.shortcutsscreen_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.shortcutsscreen_cancel)) } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}
