package dev.flint.term.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardCapslock
import androidx.compose.material.icons.rounded.KeyboardCommandKey
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.SwipeLeft
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.flint.term.App
import dev.flint.term.data.CapsLockAction
import dev.flint.term.ui.Group
import dev.flint.term.ui.GroupRow
import dev.flint.term.ui.RowDivider
import dev.flint.term.ui.Routes
import dev.flint.term.ui.Segmented
import dev.flint.term.ui.ShortcutsScreen

/** What the keys and the fingers do: the extra-key bar, and the gestures on the terminal. */
@Composable
fun KeyboardSettings(nav: NavController) {
    val app = LocalContext.current.applicationContext as App
    val settings by app.store.settings.collectAsStateWithLifecycle()
    // A screen rather than a row, but it hangs off this one instead of the
    // navigation graph: nothing outside this section ever opens it.
    var shortcuts by remember { mutableStateOf(false) }
    if (shortcuts) {
        ShortcutsScreen(onBack = { shortcuts = false })
        return
    }

    SettingsSection(nav, "Keyboard & input") {
        Group("Keys") {
            GroupRow(
                title = "Extra keys",
                subtitle = "The bar above the keyboard, and what the volume keys do",
                icon = Icons.Rounded.Keyboard,
                onClick = { nav.navigate(Routes.EXTRA_KEYS) },
            )
            RowDivider()
            GroupRow(
                title = "Chords",
                subtitle = "The tmux, Ctrl and agent keys on the sheet you get by holding Ctrl",
                icon = Icons.Rounded.Bolt,
                onClick = { nav.navigate(Routes.CHORDS) },
            )
            RowDivider()
            GroupRow(
                title = "Shortcuts",
                subtitle = "What Ctrl+Shift+C and the other app shortcuts do",
                icon = Icons.Rounded.KeyboardCommandKey,
                onClick = { shortcuts = true },
            )
            RowDivider()
            GroupRow(
                title = "Keyboard protocol",
                subtitle = "Lets a program see the difference between Ctrl+[ and Escape, and see Shift+Enter",
                icon = Icons.Rounded.Terminal,
                iconTint = MaterialTheme.colorScheme.secondary,
                checked = settings.keyboardProtocol,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(keyboardProtocol = v) } },
            )
            RowDivider()
            GroupRow(
                title = "Send Ctrl+[, Ctrl+I and Ctrl+M as their own keys",
                subtitle = "Normally these three send the same bytes as Escape, Tab and Enter. On, they are sent as separate keys, so a tmux binding on Ctrl+[ works. The Escape, Tab and Enter keys do not change.",
                icon = Icons.Rounded.Terminal,
                iconTint = MaterialTheme.colorScheme.secondary,
                checked = settings.fixtermsCtrlKeys,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(fixtermsCtrlKeys = v) } },
                enabled = settings.keyboardProtocol,
            )
            RowDivider()
            GroupRow(
                title = "Type with the app's keyboard",
                subtitle = "A simple keyboard drawn by the app, with Ctrl and Alt on the bottom row. It has no dictionary, no autocorrect and no swiping. Use it if you mainly need a Ctrl key.",
                icon = Icons.Rounded.Keyboard,
                iconTint = MaterialTheme.colorScheme.secondary,
                checked = settings.builtInKeyboard,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(builtInKeyboard = v) } },
            )
            RowDivider()
            GroupRow(
                title = "Offer to paste what you copied",
                subtitle = "A strip above the keys, for about a minute after you copy something in another app. Only the clipboard's description is read, never its contents.",
                icon = Icons.Rounded.ContentPaste,
                iconTint = MaterialTheme.colorScheme.secondary,
                checked = settings.clipboardSuggestion,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(clipboardSuggestion = v) } },
            )
            RowDivider()
            GroupRow(
                title = "Ctrl keys always send control bytes",
                subtitle = "Ctrl+C from the key bar or the menu sends the byte itself, so it still stops a program that has taken over the keyboard. Tab, Enter, Backspace and Escape never change.",
                icon = Icons.Rounded.Bolt,
                iconTint = MaterialTheme.colorScheme.secondary,
                checked = settings.rawControlKeys,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(rawControlKeys = v) } },
            )
            RowDivider()
            GroupRow(
                title = "Keep the compose line open",
                subtitle = "The ✎ field and what you typed in it stay when you leave the session",
                icon = Icons.Rounded.EditNote,
                iconTint = MaterialTheme.colorScheme.secondary,
                checked = settings.composeRemembersState,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(composeRemembersState = v) } },
            )
        }

        Group("Hardware keyboard") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Caps Lock acts as", style = MaterialTheme.typography.bodyLarge)
                Segmented(CapsLockAction.entries.map { it.label }, settings.capsLockAs.ordinal) { i ->
                    app.store.updateSettings { it.copy(capsLockAs = CapsLockAction.entries[i]) }
                }
                Text(
                    "Escape sends it on the way down. Control is held for as long as the key is.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Group("Gestures") {
            GroupRow(
                title = "Double tap locks a modifier",
                subtitle = "Tap Ctrl twice quickly and it stays down until you tap it again",
                icon = Icons.Rounded.KeyboardCapslock,
                iconTint = MaterialTheme.colorScheme.secondary,
                checked = settings.doubleTapLocksModifier,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(doubleTapLocksModifier = v) } },
            )
            RowDivider()
            GroupRow(
                title = "Double tap sends Tab",
                subtitle = "Phone keyboards have no Tab key, so two taps send it. This works inside programs that read the mouse too.",
                icon = Icons.Rounded.TouchApp,
                iconTint = MaterialTheme.colorScheme.secondary,
                checked = settings.doubleTapSendsTab,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(doubleTapSendsTab = v) } },
            )
            RowDivider()
            GroupRow(
                title = "Two-finger drag sends arrows",
                subtitle = "Slide two fingers to walk the cursor. Pinch still zooms",
                icon = Icons.Rounded.Swipe,
                iconTint = MaterialTheme.colorScheme.secondary,
                checked = settings.twoFingerDragArrows,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(twoFingerDragArrows = v) } },
            )
            RowDivider()
            GroupRow(
                title = "Hold Ctrl for chords",
                subtitle = "A long press on Ctrl opens the chords sheet",
                icon = Icons.Rounded.Bolt,
                iconTint = MaterialTheme.colorScheme.secondary,
                checked = settings.ctrlLongPressOpensChords,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(ctrlLongPressOpensChords = v) } },
            )
            RowDivider()
            GroupRow(
                title = "Swipe between sessions",
                subtitle = "Drag sideways in the terminal to move along the tab strip",
                icon = Icons.Rounded.SwipeLeft,
                iconTint = MaterialTheme.colorScheme.secondary,
                checked = settings.swipeBetweenSessions,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(swipeBetweenSessions = v) } },
            )
        }
    }
}
