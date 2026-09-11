package dev.flint.term.ui

import dev.flint.term.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.zIndex
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.flint.term.App
import dev.flint.term.terminal.ExtraKeys
import dev.flint.term.data.VolumeModifierMode

/**
 * One editable row of caps: hold one to drag it somewhere else, tap it to pick it.
 *
 * The drag works on whole groups rather than single caps, so the four arrows
 * travel together, and it steps a group at a time rather than hit-testing a
 * position: once the finger has travelled the width of the neighbour, the two
 * change places. That reuses the same move the tests cover instead of a second
 * idea of what order means.
 *
 * The list being dragged is held here and only handed back when the finger
 * lifts. Committing every step would change the caller's state mid-gesture, and
 * the gesture detector is restarted whenever its keys change, which cancels the
 * drag that caused it.
 */
@Composable
private fun KeyRow(
    tokens: List<String>,
    grouped: Boolean,
    selected: Int?,
    dimmed: Boolean,
    onSelect: (Int?) -> Unit,
    onReorder: (List<String>, Int) -> Unit,
    onAdd: () -> Unit,
) {
    val state = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    var live by remember(tokens) { mutableStateOf(tokens) }
    var dragAt by remember { mutableStateOf<Int?>(null) }
    var dragBy by remember { mutableFloatStateOf(0f) }
    val spacing = with(androidx.compose.ui.platform.LocalDensity.current) { 6.dp.toPx() }

    /** How wide a run of caps is on screen, or null while any of it is off it. */
    fun widthOf(range: IntRange): Float? {
        val visible = state.layoutInfo.visibleItemsInfo
        var total = 0f
        for (i in range) {
            val item = visible.firstOrNull { it.index == i } ?: return null
            total += item.size + spacing
        }
        return total.takeIf { it > 0f }
    }

    LazyRow(
        state = state,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.alpha(if (dimmed) 0.4f else 1f),
    ) {
        itemsIndexed(live) { i, t ->
            val def = ExtraKeys.resolve(t)
            val isSel = selected == i
            val slot = rememberUpdatedState(i)
            val group = dragAt?.let { ExtraKeys.groupAt(live, it, grouped) }
            val isDragged = group != null && i in group
            Box(
                Modifier
                    .zIndex(if (isDragged) 1f else 0f)
                    .graphicsLayer {
                        if (isDragged) {
                            translationX = dragBy
                            scaleX = 1.06f
                            scaleY = 1.06f
                        }
                    }
                    .height(38.dp)
                    .widthIn(min = 44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        when {
                            isDragged -> MaterialTheme.colorScheme.primaryContainer
                            isSel -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                    )
                    .then(
                        if (dimmed) {
                            Modifier
                        } else {
                            Modifier
                                .clickable { onSelect(if (isSel) null else i) }
                                // Keyed on nothing, so a reorder mid-gesture cannot
                                // restart the detector and cancel the drag that
                                // caused it. The index is read through a holder
                                // instead, or the closure would keep the one this
                                // slot had when the gesture was first installed.
                                .pointerInput(Unit) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            dragAt = slot.value
                                            dragBy = 0f
                                            onSelect(i)
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                        onDragEnd = {
                                            dragAt?.let { onReorder(live, it) }
                                            dragAt = null
                                            dragBy = 0f
                                        },
                                        onDragCancel = { dragAt = null; dragBy = 0f },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragBy += amount.x
                                            var from = dragAt ?: return@detectDragGesturesAfterLongPress
                                            while (true) {
                                                val g = ExtraKeys.groupAt(live, from, grouped)
                                                val forward = dragBy > 0f
                                                val edge = if (forward) g.last + 1 else g.first - 1
                                                if (edge !in live.indices) break
                                                val width = widthOf(ExtraKeys.groupAt(live, edge, grouped)) ?: break
                                                if (kotlin.math.abs(dragBy) < width) break
                                                val moved = ExtraKeys.moveGroup(live, from, forward, grouped) ?: break
                                                live = moved.first
                                                from = moved.second
                                                dragAt = from
                                                dragBy += if (forward) -width else width
                                            }
                                        },
                                    )
                                }
                        },
                    )
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    def.label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = when {
                        isDragged -> MaterialTheme.colorScheme.onPrimaryContainer
                        isSel -> MaterialTheme.colorScheme.onPrimary
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
        item {
            Box(
                Modifier.height(38.dp).widthIn(min = 44.dp).clip(RoundedCornerShape(10.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                    .then(if (dimmed) Modifier else Modifier.clickable { onAdd() })
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Add, stringResource(R.string.extrakeysscreen_add_key), tint = MaterialTheme.colorScheme.primary) }
        }
    }
}

/** Editor for the two extra-key rows: tap a key to select, then move or remove it; add from the catalog or as literal text. */
@Composable
fun ExtraKeysScreen(nav: NavController) {
    val app = LocalContext.current.applicationContext as App
    val settings by app.store.settings.collectAsStateWithLifecycle()
    val row1 = settings.extraKeysRow1 ?: ExtraKeys.defaultRow1
    val row2 = settings.extraKeysRow2 ?: ExtraKeys.defaultRow2
    var selected by remember { mutableStateOf<Pair<Int, Int>?>(null) } // row, index
    var adding by remember { mutableStateOf<Int?>(null) } // row to add into
    var customText by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf<Int?>(null) } // 0 = volume down, 1 = volume up
    var pickText by remember { mutableStateOf("") }
    var preset by remember { mutableStateOf<ExtraKeys.Preset?>(null) } // waiting to be confirmed

    fun save(r1: List<String>, r2: List<String>) = app.store.updateSettings { it.copy(extraKeysRow1 = r1, extraKeysRow2 = r2) }
    fun rows() = listOf(row1, row2)
    fun update(row: Int, list: List<String>) = if (row == 0) save(list, row2) else save(row1, list)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppHeader(
                title = stringResource(R.string.extrakeysscreen_extra_keys), onBack = { nav.popBackStack() },
                actions = {
                    IconButton(onClick = { app.store.updateSettings { it.copy(extraKeysRow1 = null, extraKeysRow2 = null) }; selected = null }) {
                        Icon(Icons.Rounded.RestartAlt, stringResource(R.string.extrakeysscreen_reset_to_defaults))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScreenScroll("extrakeys")).padding(bottom = 40.dp)) {
            Text(
                stringResource(R.string.extrakeysscreen_this_is_the_bar_above_the_keyboard_the_first_row),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            rows().forEachIndexed { r, tokens ->
                val hidden = r == 1 && settings.extraKeysRows < 2
                Group(if (r == 0) stringResource(R.string.extrakeysscreen_row_1_fixed) else stringResource(R.string.extrakeysscreen_row_2_scrolls)) {
                    KeyRow(
                        tokens = tokens,
                        grouped = settings.groupArrowKeys,
                        selected = selected?.takeIf { it.first == r }?.second,
                        dimmed = hidden,
                        onSelect = { i -> selected = if (i == null) null else r to i },
                        onReorder = { list, at -> update(r, list); selected = r to at },
                        onAdd = { adding = r },
                    )
                    if (r == 1) {
                        RowDivider()
                        // The switch belongs against the row it turns off, not in
                        // a list of unrelated preferences at the foot of the page.
                        GroupRow(
                            title = if (hidden) "Hidden" else "Shown",
                            subtitle = if (hidden) stringResource(R.string.extrakeysscreen_turned_off_and_kept_as_it_is) else stringResource(R.string.extrakeysscreen_on_screen_under_the_first_row),
                            icon = Icons.Rounded.Keyboard,
                            iconTint = MaterialTheme.colorScheme.secondary,
                            checked = !hidden,
                            onCheckedChange = { on -> app.store.updateSettings { it.copy(extraKeysRows = if (on) 2 else 1) } },
                        )
                    }
                    val sel = selected
                    if (sel != null && sel.first == r) {
                        RowDivider()
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(ExtraKeys.resolve(tokens[sel.second]).label, Modifier.padding(start = 12.dp).weight(1f), style = MaterialTheme.typography.titleSmall)
                            IconButton(onClick = {
                                val other = 1 - r
                                update(r, tokens.filterIndexed { i, _ -> i != sel.second })
                                if (other == 1) save(row1.filterIndexed { i, _ -> i != sel.second }, row2 + tokens[sel.second]) else save(row1 + tokens[sel.second], row2.filterIndexed { i, _ -> i != sel.second })
                                selected = null
                            }) { Icon(Icons.Rounded.Keyboard, stringResource(R.string.extrakeysscreen_move_to_the_other_row)) }
                            IconButton(onClick = { update(r, tokens.filterIndexed { i, _ -> i != sel.second }); selected = null }) {
                                Icon(Icons.Rounded.Delete, "Remove", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            // Next to the thing it changes: it is a rule about rearranging, and
            // rearranging is what the two groups above are for.
            Group("Arranging") {
                GroupRow(
                    title = stringResource(R.string.extrakeysscreen_move_the_arrows_together),
                    subtitle = stringResource(R.string.extrakeysscreen_they_are_one_control_drawn_as_four_caps_so_dragg),
                    icon = Icons.Rounded.OpenWith,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    checked = settings.groupArrowKeys,
                    onCheckedChange = { v -> app.store.updateSettings { it.copy(groupArrowKeys = v) } },
                )
                RowDivider()
                GroupRow(
                    title = stringResource(R.string.extrakeysscreen_show_the_bar_at_all),
                    subtitle = if (settings.extraKeysRows == 0) stringResource(R.string.extrakeysscreen_off_the_terminal_has_the_whole_screen) else stringResource(R.string.extrakeysscreen_the_bar_sits_above_the_keyboard),
                    icon = Icons.Rounded.Keyboard,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    checked = settings.extraKeysRows > 0,
                    onCheckedChange = { on -> app.store.updateSettings { it.copy(extraKeysRows = if (on) 2 else 0) } },
                )
            }

            Group("Presets") {
                ExtraKeys.presets.forEachIndexed { i, p ->
                    if (i > 0) RowDivider()
                    GroupRow(
                        title = p.name, subtitle = p.subtitle,
                        icon = Icons.Rounded.AutoAwesome, iconTint = MaterialTheme.colorScheme.secondary,
                        onClick = { preset = p },
                    )
                }
            }

            Group("Behaviour") {
                GroupRow(
                    title = stringResource(R.string.extrakeysscreen_hide_with_a_hardware_keyboard), subtitle = stringResource(R.string.extrakeysscreen_free_the_space_when_a_physical_keyboard_is_conne),
                    icon = Icons.Rounded.Keyboard, iconTint = MaterialTheme.colorScheme.secondary,
                    checked = settings.hideExtraKeysWithHardwareKeyboard, onCheckedChange = { v -> app.store.updateSettings { it.copy(hideExtraKeysWithHardwareKeyboard = v) } },
                )
            }

            Group(stringResource(R.string.extrakeysscreen_volume_buttons)) {
                // Takes the mode's name already resolved: this is a plain
                // function, and a string resource can only be read from a
                // composable one.
                fun describe(t: String, modeName: String): String {
                    if (t == "NONE" || t.isBlank()) return "Nothing (volume as usual)"
                    val d = ExtraKeys.resolve(t)
                    val name = if (d.label == t && ExtraKeys.catalog.none { it.token == t }) "Types \"${t}\"" else (ExtraKeys.catalog.firstOrNull { it.token == t }?.token?.lowercase()?.replaceFirstChar { c -> c.uppercase() } ?: d.label)
                    return if (d.action is ExtraKeys.Action.Modifier) "$name  ·  " + modeName.lowercase() else name
                }
                GroupRow(
                    title = stringResource(R.string.extrakeysscreen_volume_down), subtitle = describe(settings.volumeDownAction, stringResource(settings.volumeDownMode.label)),
                    icon = Icons.Rounded.VolumeUp, iconTint = MaterialTheme.colorScheme.secondary,
                    onClick = { picking = 0 },
                )
                RowDivider()
                GroupRow(
                    title = stringResource(R.string.extrakeysscreen_volume_up), subtitle = describe(settings.volumeUpAction, stringResource(settings.volumeUpMode.label)),
                    icon = Icons.Rounded.VolumeUp, iconTint = MaterialTheme.colorScheme.secondary,
                    onClick = { picking = 1 },
                )
                RowDivider()
                Text(
                    stringResource(R.string.extrakeysscreen_bound_buttons_no_longer_change_the_volume_while),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    picking?.let { which ->
        val current = if (which == 0) settings.volumeDownAction else settings.volumeUpAction
        val currentMode = if (which == 0) settings.volumeDownMode else settings.volumeUpMode
        KeyPickerSheet(
            title = if (which == 0) stringResource(R.string.extrakeysscreen_volume_down_does) else stringResource(R.string.extrakeysscreen_volume_up_does),
            selected = setOf(current),
            allowNone = true,
            modifierMode = currentMode,
            onModifierMode = { m -> app.store.updateSettings { if (which == 0) it.copy(volumeDownMode = m) else it.copy(volumeUpMode = m) } },
            onPick = { token -> app.store.updateSettings { if (which == 0) it.copy(volumeDownAction = token) else it.copy(volumeUpAction = token) } },
            onDismiss = { picking = null },
        )
    }

    adding?.let { r ->
        KeyPickerSheet(
            title = if (r == 0) stringResource(R.string.extrakeysscreen_add_to_row_1) else stringResource(R.string.extrakeysscreen_add_to_row_2),
            selected = rows()[r].toSet(),
            allowNone = false,
            onPick = { token -> val cur = rows()[r]; update(r, if (token in cur) cur - token else cur + token) },
            onDismiss = { adding = null },
        )
    }

    preset?.let { p ->
        AlertDialog(
            onDismissRequest = { preset = null },
            title = { Text(stringResource(R.string.extrakeysscreen_replace_both_rows)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.extrakeysscreen_the_preset_takes_over_both_rows_whatever_is_arra, p.name), style = MaterialTheme.typography.bodyMedium)
                    // Reading the caps first is the difference between choosing
                    // a preset and gambling with the bar already built.
                    listOf(p.row1, p.row2).forEach { row ->
                        Text(
                            row.joinToString(" ") { ExtraKeys.resolve(it).label },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                FilledTonalButton(onClick = {
                    // The arrangement includes how many rows it wants on screen.
                    app.store.updateSettings { it.copy(extraKeysRow1 = p.row1, extraKeysRow2 = p.row2, extraKeysRows = p.rows) }
                    selected = null
                    preset = null
                }) { Text(stringResource(R.string.extrakeysscreen_replace)) }
            },
            dismissButton = { TextButton(onClick = { preset = null }) { Text(stringResource(R.string.extrakeysscreen_cancel)) } },
        )
    }
}

/**
 * Grouped key picker used for the volume buttons and for adding keys to the
 * bar. Stays open until dismissed; `onPick` fires for every tap (a second tap
 * on a row key removes it again — the caller decides).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun KeyPickerSheet(
    title: String,
    selected: Set<String>,
    allowNone: Boolean,
    modifierMode: VolumeModifierMode? = null,
    onModifierMode: ((VolumeModifierMode) -> Unit)? = null,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var customOpen by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf("") }
    var showFn by remember { mutableStateOf(selected.any { it.startsWith("F") && it.drop(1).toIntOrNull() != null }) }
    val byToken = ExtraKeys.catalog.associateBy { it.token }
    val customSelected = selected.filter { it !in byToken && it != "NONE" }

    @Composable
    fun chips(tokens: List<String>) {
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            tokens.forEach { t ->
                val d = byToken[t] ?: ExtraKeys.resolve(t)
                FilterChip(selected = t in selected, onClick = { onPick(t) }, label = { Text(d.label) })
            }
        }
    }

    @Composable
    fun section(label: String, content: @Composable () -> Unit) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
        content()
    }

    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.extrakeysscreen_done)) }
            }
            if (allowNone) {
                Spacer(Modifier.height(6.dp))
                FilterChip(selected = "NONE" in selected, onClick = { onPick("NONE") }, label = { Text(stringResource(R.string.extrakeysscreen_nothing_normal_volume_button)) })
            }
            section("Modifiers") { chips(listOf("CTRL", "ALT", "SHIFT")) }
            if (modifierMode != null && onModifierMode != null && selected.any { byToken[it]?.action is ExtraKeys.Action.Modifier }) {
                Spacer(Modifier.height(8.dp))
                Segmented(VolumeModifierMode.entries.map { stringResource(it.label) }, modifierMode.ordinal) { i -> onModifierMode(VolumeModifierMode.entries[i]) }
                Text(stringResource(modifierMode.help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
            section("Navigation") { chips(listOf("UP", "DOWN", "LEFT", "RIGHT", "NAV", "HOME", "END", "PGUP", "PGDN")) }
            section("Editing") { chips(listOf("ESC", "TAB", "STAB", "ENTER", "BKSP", "DEL", "INS")) }
            section("Actions") { chips(listOf("SNIPPETS", "SEARCH", "KEYBOARD", "PASTE", "FILE", "COMPOSE")) }
            section(stringResource(R.string.extrakeysscreen_function_keys)) {
                if (showFn) chips((1..12).map { "F$it" })
                else TextButton(onClick = { showFn = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text(stringResource(R.string.extrakeysscreen_show_f1_f12)) }
            }
            section(stringResource(R.string.extrakeysscreen_custom_text)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (customSelected.isNotEmpty()) chips(customSelected)
                    androidx.compose.material3.OutlinedButton(onClick = { customOpen = true }) {
                        Icon(Icons.Rounded.Add, null, Modifier.width(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.extrakeysscreen_type_text_to_insert))
                    }
                }
            }
        }
    }

    if (customOpen) {
        AlertDialog(
            onDismissRequest = { customOpen = false },
            title = { Text(stringResource(R.string.extrakeysscreen_custom_text)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.extrakeysscreen_inserted_exactly_as_typed_e_g_sudo_or_grep), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Field(custom, { custom = it }, "Text", mono = true)
                }
            },
            confirmButton = { FilledTonalButton(enabled = custom.isNotEmpty(), onClick = { onPick(custom); custom = ""; customOpen = false }) { Text(stringResource(R.string.extrakeysscreen_use)) } },
            dismissButton = { TextButton(onClick = { customOpen = false }) { Text(stringResource(R.string.extrakeysscreen_cancel)) } },
        )
    }
}
