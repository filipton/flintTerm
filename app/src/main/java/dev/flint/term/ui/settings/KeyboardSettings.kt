package dev.flint.term.ui.settings

import dev.flint.term.R
import androidx.compose.ui.res.stringResource
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

    SettingsSection(nav, stringResource(R.string.keyboardsettings_keyboard_input)) {
        Group("Keys") {
            GroupRow(
                title = stringResource(R.string.keyboardsettings_extra_keys),
                subtitle = stringResource(R.string.keyboardsettings_the_bar_above_the_keyboard_and_what_the_volume_k),
                icon = Icons.Rounded.Keyboard,
                onClick = { nav.navigate(Routes.EXTRA_KEYS) },
            )
            RowDivider()
            GroupRow(
                title = stringResource(R.string.keyboardsettings_chords),
                subtitle = stringResource(R.string.keyboardsettings_the_tmux_ctrl_and_agent_keys_on_the_sheet_you_ge),
                icon = Icons.Rounded.Bolt,
                onClick = { nav.navigate(Routes.CHORDS) },
            )
            RowDivider()
            GroupRow(
                title = stringResource(R.string.keyboardsettings_shortcuts),
                subtitle = stringResource(R.string.keyboardsettings_what_ctrl_shift_c_and_the_other_app_shortcuts_do),
                icon = Icons.Rounded.KeyboardCommandKey,
                onClick = { shortcuts = true },
            )
            RowDivider()
            GroupRow(
                title = stringResource(R.string.keyboardsettings_keyboard_protocol),
                subtitle = stringResource(R.string.keyboardsettings_lets_a_program_see_the_difference_between_ctrl_a),
                icon = Icons.Rounded.Terminal,
                iconTint = MaterialTheme.colorScheme.secondary,
                checked = settings.keyboardProtocol,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(keyboardProtocol = v) } },
            )
            RowDivider()
            GroupRow(
                title = stringResource(R.string.keyboardsettings_send_ctrl_ctrl_i_and_ctrl_m_as_their_own_keys),
                subtitle = stringResource(R.string.keyboardsettings_normally_these_three_send_the_same_bytes_as_esca),
                icon = Icons.Rounded.Terminal,
                iconTint = MaterialTheme.colorScheme.secondary,
                checked = settings.fixtermsCtrlKeys,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(fixtermsCtrlKeys = v) } },
                enabled = settings.keyboardProtocol,
            )
            RowDivider()
            GroupRow(
                title = stringResource(R.string.keyboardsettings_type_with_the_app_s_keyboard),
                subtitle = stringResource(R.string.keyboardsettings_a_simple_keyboard_drawn_by_the_app_with_ctrl_and),
                icon = Icons.Rounded.Keyboard,
                iconTint = MaterialTheme.colorScheme.secondary,
                checked = settings.builtInKeyboard,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(builtInKeyboard = v) } },
            )
            RowDivider()
            GroupRow(
                title = stringResource(R.string.keyboardsettings_offer_to_paste_what_you_copied),
                subtitle = stringResource(R.string.keyboardsettings_a_strip_above_the_keys_for_about_a_minute_after),
                icon = Icons.Rounded.ContentPaste,
                iconTint = MaterialTheme.colorScheme.secondary,
                checked = settings.clipboardSuggestion,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(clipboardSuggestion = v) } },
            )
            RowDivider()
            GroupRow(
                title = stringResource(R.string.keyboardsettings_ctrl_keys_always_send_control_bytes),
                subtitle = stringResource(R.string.keyboardsettings_ctrl_c_from_the_key_bar_or_the_menu_sends_the_by),
                icon = Icons.Rounded.Bolt,
                iconTint = MaterialTheme.colorScheme.secondary,
                checked = settings.rawControlKeys,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(rawControlKeys = v) } },
            )
            RowDivider()
            GroupRow(
                title = stringResource(R.string.keyboardsettings_keep_the_compose_line_open),
                subtitle = stringResource(R.string.keyboardsettings_the_field_and_what_you_typed_in_it_stay_when_you),
                icon = Icons.Rounded.EditNote,
                iconTint = MaterialTheme.colorScheme.secondary,
                checked = settings.composeRemembersState,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(composeRemembersState = v) } },
            )
        }

        Group(stringResource(R.string.keyboardsettings_hardware_keyboard)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.keyboardsettings_caps_lock_acts_as), style = MaterialTheme.typography.bodyLarge)
                Segmented(CapsLockAction.entries.map { stringResource(it.label) }, settings.capsLockAs.ordinal) { i ->
                    app.store.updateSettings { it.copy(capsLockAs = CapsLockAction.entries[i]) }
                }
                Text(
                    stringResource(R.string.keyboardsettings_escape_sends_it_on_the_way_down_control_is_held),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Group("Gestures") {
            GroupRow(
                title = stringResource(R.string.keyboardsettings_double_tap_locks_a_modifier),
                subtitle = stringResource(R.string.keyboardsettings_tap_ctrl_twice_quickly_and_it_stays_down_until_y),
                icon = Icons.Rounded.KeyboardCapslock,
                iconTint = MaterialTheme.colorScheme.secondary,
                checked = settings.doubleTapLocksModifier,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(doubleTapLocksModifier = v) } },
            )
            RowDivider()
            GroupRow(
                title = stringResource(R.string.keyboardsettings_double_tap_sends_tab),
                subtitle = stringResource(R.string.keyboardsettings_phone_keyboards_have_no_tab_key_so_two_taps_send),
                icon = Icons.Rounded.TouchApp,
                iconTint = MaterialTheme.colorScheme.secondary,
                checked = settings.doubleTapSendsTab,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(doubleTapSendsTab = v) } },
            )
            RowDivider()
            GroupRow(
                title = stringResource(R.string.keyboardsettings_two_finger_drag_sends_arrows),
                subtitle = stringResource(R.string.keyboardsettings_slide_two_fingers_to_walk_the_cursor_pinch_still),
                icon = Icons.Rounded.Swipe,
                iconTint = MaterialTheme.colorScheme.secondary,
                checked = settings.twoFingerDragArrows,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(twoFingerDragArrows = v) } },
            )
            RowDivider()
            GroupRow(
                title = stringResource(R.string.keyboardsettings_hold_ctrl_for_chords),
                subtitle = stringResource(R.string.keyboardsettings_a_long_press_on_ctrl_opens_the_chords_sheet),
                icon = Icons.Rounded.Bolt,
                iconTint = MaterialTheme.colorScheme.secondary,
                checked = settings.ctrlLongPressOpensChords,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(ctrlLongPressOpensChords = v) } },
            )
            RowDivider()
            GroupRow(
                title = stringResource(R.string.keyboardsettings_swipe_between_sessions),
                subtitle = stringResource(R.string.keyboardsettings_drag_sideways_in_the_terminal_to_move_along_the),
                icon = Icons.Rounded.SwipeLeft,
                iconTint = MaterialTheme.colorScheme.secondary,
                checked = settings.swipeBetweenSessions,
                onCheckedChange = { v -> app.store.updateSettings { it.copy(swipeBetweenSessions = v) } },
            )
        }
    }
}
