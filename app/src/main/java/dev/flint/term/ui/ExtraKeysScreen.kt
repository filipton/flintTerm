package dev.flint.term.ui

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Keyboard
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
                title = "Extra keys", onBack = { nav.popBackStack() },
                actions = {
                    IconButton(onClick = { app.store.updateSettings { it.copy(extraKeysRow1 = null, extraKeysRow2 = null) }; selected = null }) {
                        Icon(Icons.Rounded.RestartAlt, "Reset to defaults")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScreenScroll("extrakeys")).padding(bottom = 40.dp)) {
            Text(
                "This is the bar above the keyboard. The first row is fixed and shares the width; the second row scrolls. Tap a key to select it, then move or remove it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            rows().forEachIndexed { r, tokens ->
                Group(if (r == 0) "Row 1 — fixed" else "Row 2 — scrolls") {
                    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(tokens) { i, t ->
                            val def = ExtraKeys.resolve(t)
                            val isSel = selected == r to i
                            Box(
                                Modifier
                                    .height(38.dp)
                                    .widthIn(min = 44.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .clickable { selected = if (isSel) null else r to i }
                                    .padding(horizontal = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(def.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (isSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                        item {
                            Box(
                                Modifier.height(38.dp).widthIn(min = 44.dp).clip(RoundedCornerShape(10.dp))
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                                    .clickable { adding = r }.padding(horizontal = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Rounded.Add, "Add key", tint = MaterialTheme.colorScheme.primary) }
                        }
                    }
                    val sel = selected
                    if (sel != null && sel.first == r) {
                        RowDivider()
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(ExtraKeys.resolve(tokens[sel.second]).label, Modifier.padding(start = 12.dp).weight(1f), style = MaterialTheme.typography.titleSmall)
                            IconButton(enabled = sel.second > 0, onClick = {
                                val l = tokens.toMutableList(); java.util.Collections.swap(l, sel.second, sel.second - 1); update(r, l); selected = r to sel.second - 1
                            }) { Icon(Icons.Rounded.ChevronLeft, "Move left") }
                            IconButton(enabled = sel.second < tokens.lastIndex, onClick = {
                                val l = tokens.toMutableList(); java.util.Collections.swap(l, sel.second, sel.second + 1); update(r, l); selected = r to sel.second + 1
                            }) { Icon(Icons.Rounded.ChevronRight, "Move right") }
                            IconButton(onClick = {
                                val other = 1 - r
                                update(r, tokens.filterIndexed { i, _ -> i != sel.second })
                                if (other == 1) save(row1.filterIndexed { i, _ -> i != sel.second }, row2 + tokens[sel.second]) else save(row1 + tokens[sel.second], row2.filterIndexed { i, _ -> i != sel.second })
                                selected = null
                            }) { Icon(Icons.Rounded.Keyboard, "Move to other row") }
                            IconButton(onClick = { update(r, tokens.filterIndexed { i, _ -> i != sel.second }); selected = null }) {
                                Icon(Icons.Rounded.Delete, "Remove", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
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
                    title = "Hide with a hardware keyboard", subtitle = "Free the space when a physical keyboard is connected",
                    icon = Icons.Rounded.Keyboard, iconTint = MaterialTheme.colorScheme.secondary,
                    checked = settings.hideExtraKeysWithHardwareKeyboard, onCheckedChange = { v -> app.store.updateSettings { it.copy(hideExtraKeysWithHardwareKeyboard = v) } },
                )
            }

            Group("Volume buttons") {
                fun describe(t: String, mode: VolumeModifierMode): String {
                    if (t == "NONE" || t.isBlank()) return "Nothing (volume as usual)"
                    val d = ExtraKeys.resolve(t)
                    val name = if (d.label == t && ExtraKeys.catalog.none { it.token == t }) "Types \"$t\"" else (ExtraKeys.catalog.firstOrNull { it.token == t }?.token?.lowercase()?.replaceFirstChar { c -> c.uppercase() } ?: d.label)
                    return if (d.action is ExtraKeys.Action.Modifier) "$name  ·  ${mode.label.lowercase()}" else name
                }
                GroupRow(
                    title = "Volume down", subtitle = describe(settings.volumeDownAction, settings.volumeDownMode),
                    icon = Icons.Rounded.VolumeUp, iconTint = MaterialTheme.colorScheme.secondary,
                    onClick = { picking = 0 },
                )
                RowDivider()
                GroupRow(
                    title = "Volume up", subtitle = describe(settings.volumeUpAction, settings.volumeUpMode),
                    icon = Icons.Rounded.VolumeUp, iconTint = MaterialTheme.colorScheme.secondary,
                    onClick = { picking = 1 },
                )
                RowDivider()
                Text(
                    "Bound buttons no longer change the volume while a terminal is open. The vibration or ringer volume can still be changed from the notification shade.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    picking?.let { which ->
        val current = if (which == 0) settings.volumeDownAction else settings.volumeUpAction
        val currentMode = if (which == 0) settings.volumeDownMode else settings.volumeUpMode
        KeyPickerSheet(
            title = if (which == 0) "Volume down does…" else "Volume up does…",
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
            title = if (r == 0) "Add to row 1" else "Add to row 2",
            selected = rows()[r].toSet(),
            allowNone = false,
            onPick = { token -> val cur = rows()[r]; update(r, if (token in cur) cur - token else cur + token) },
            onDismiss = { adding = null },
        )
    }

    preset?.let { p ->
        AlertDialog(
            onDismissRequest = { preset = null },
            title = { Text("Replace both rows?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The ${p.name} preset takes over both rows. Whatever is arranged there now is lost.", style = MaterialTheme.typography.bodyMedium)
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
            confirmButton = { FilledTonalButton(onClick = { save(p.row1, p.row2); selected = null; preset = null }) { Text("Replace") } },
            dismissButton = { TextButton(onClick = { preset = null }) { Text("Cancel") } },
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
                TextButton(onClick = onDismiss) { Text("Done") }
            }
            if (allowNone) {
                Spacer(Modifier.height(6.dp))
                FilterChip(selected = "NONE" in selected, onClick = { onPick("NONE") }, label = { Text("Nothing — normal volume button") })
            }
            section("Modifiers") { chips(listOf("CTRL", "ALT", "SHIFT")) }
            if (modifierMode != null && onModifierMode != null && selected.any { byToken[it]?.action is ExtraKeys.Action.Modifier }) {
                Spacer(Modifier.height(8.dp))
                Segmented(VolumeModifierMode.entries.map { it.label }, modifierMode.ordinal) { i -> onModifierMode(VolumeModifierMode.entries[i]) }
                Text(modifierMode.help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
            section("Navigation") { chips(listOf("UP", "DOWN", "LEFT", "RIGHT", "NAV", "HOME", "END", "PGUP", "PGDN")) }
            section("Editing") { chips(listOf("ESC", "TAB", "STAB", "ENTER", "BKSP", "DEL", "INS")) }
            section("Actions") { chips(listOf("SNIPPETS", "SEARCH", "KEYBOARD", "PASTE", "FILE", "COMPOSE")) }
            section("Function keys") {
                if (showFn) chips((1..12).map { "F$it" })
                else TextButton(onClick = { showFn = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("Show F1 – F12") }
            }
            section("Custom text") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (customSelected.isNotEmpty()) chips(customSelected)
                    androidx.compose.material3.OutlinedButton(onClick = { customOpen = true }) {
                        Icon(Icons.Rounded.Add, null, Modifier.width(18.dp)); Spacer(Modifier.width(6.dp)); Text("Type text to insert…")
                    }
                }
            }
        }
    }

    if (customOpen) {
        AlertDialog(
            onDismissRequest = { customOpen = false },
            title = { Text("Custom text") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Inserted exactly as typed, e.g. \"sudo \" or \"| grep \".", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Field(custom, { custom = it }, "Text", mono = true)
                }
            },
            confirmButton = { FilledTonalButton(enabled = custom.isNotEmpty(), onClick = { onPick(custom); custom = ""; customOpen = false }) { Text("Use") } },
            dismissButton = { TextButton(onClick = { customOpen = false }) { Text("Cancel") } },
        )
    }
}
