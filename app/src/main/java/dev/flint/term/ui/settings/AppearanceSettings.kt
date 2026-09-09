package dev.flint.term.ui.settings

import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EmojiSymbols
import androidx.compose.material.icons.rounded.FontDownload
import androidx.compose.material.icons.rounded.FormatBold
import androidx.compose.material.icons.rounded.FormatColorText
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.flint.term.App
import dev.flint.term.data.AppTheme
import dev.flint.term.data.CursorStyle
import dev.flint.term.data.Schemes
import dev.flint.term.terminal.TermFonts
import dev.flint.term.ui.ActionSheet
import dev.flint.term.ui.AppSlider
import dev.flint.term.ui.CodeStyle
import dev.flint.term.ui.AppSwitch
import dev.flint.term.ui.Group
import dev.flint.term.ui.GroupRow
import dev.flint.term.ui.IconTile
import dev.flint.term.ui.PaletteChip
import dev.flint.term.ui.RowDivider
import dev.flint.term.ui.Routes
import dev.flint.term.ui.Segmented
import dev.flint.term.ui.SheetAction
import kotlin.math.roundToInt

/** How the app and the terminal are drawn: theme, type, and the palette sessions run in. */
@Composable
fun AppearanceSettings(nav: NavController) {
    val app = LocalContext.current.applicationContext as App
    val settings by app.store.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var fontSheet by remember { mutableStateOf(false) }
    // Bumped when a font is imported or removed, so the sheet re-reads the list.
    var fontsVersion by remember { mutableStateOf(0) }
    val pickFont = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val id = TermFonts.import(context, uri)
        if (id == null) Toast.makeText(context, "That is not a TTF/OTF font", Toast.LENGTH_SHORT).show()
        else { app.store.updateSettings { it.copy(fontFamily = id) }; fontsVersion++ }
    }
    if (fontSheet) {
        val families = remember(fontsVersion) { TermFonts.all(context) }
        ActionSheet(
            onDismiss = { fontSheet = false },
            title = "Terminal font",
            actions = families.map { f ->
                SheetAction(
                    f.label + if (f.id == settings.fontFamily) "  ✓" else "",
                    if (f.custom) Icons.Rounded.FontDownload else Icons.Rounded.TextFields,
                    subtitle = when {
                        f.custom -> "Imported"
                        f.ligatures -> "Ligatures available"
                        else -> null
                    },
                ) { app.store.updateSettings { it.copy(fontFamily = f.id) }; fontSheet = false }
            } + SheetAction("Import font file…", Icons.Rounded.Add, subtitle = "Any monospaced TTF or OTF") { pickFont.launch(arrayOf("font/*", "application/octet-stream", "*/*")) } +
                families.filter { it.custom }.map { f -> SheetAction("Remove ${f.label}", Icons.Rounded.Delete, danger = true) {
                    TermFonts.delete(context, f.id); fontsVersion++
                    if (settings.fontFamily == f.id) app.store.updateSettings { it.copy(fontFamily = TermFonts.DEFAULT) }
                } },
        )
    }

    SettingsSection(nav, "Appearance") {
        Group("App") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Icons.Rounded.WbSunny, MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(14.dp))
                    Text("App theme", style = MaterialTheme.typography.bodyLarge)
                }
                Segmented(AppTheme.entries.map { it.label }, settings.appTheme.ordinal) { i ->
                    app.store.updateSettings { it.copy(appTheme = AppTheme.entries[i]) }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                RowDivider()
                GroupRow(
                    title = "Material You colors",
                    subtitle = "Tint the app with your wallpaper palette",
                    icon = Icons.Rounded.Palette,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    checked = settings.dynamicColor, onCheckedChange = { v -> app.store.updateSettings { it.copy(dynamicColor = v) } },
                )
            }
        }

        Group("Text") {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Font size", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text("${settings.fontSizeSp.roundToInt()} sp", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                AppSlider(
                    value = settings.fontSizeSp,
                    onValueChange = { v -> app.store.updateSettings { it.copy(fontSizeSp = v.roundToInt().toFloat()) } },
                    valueRange = 6f..32f,
                )
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(12.dp),
                ) {
                    Text("~ $ ls -la  # preview 0123", style = CodeStyle.copy(fontSize = settings.fontSizeSp.sp))
                }
                Text("Pinch in the terminal to change it on the fly.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
            RowDivider()
            val family = TermFonts.family(context, settings.fontFamily)
            GroupRow(
                title = "Font", subtitle = family.label + if (family.custom) "  ·  imported" else "",
                icon = Icons.Rounded.TextFields, iconTint = MaterialTheme.colorScheme.secondary,
                onClick = { fontSheet = true },
            )
            RowDivider()
            GroupRow(
                title = "Ligatures", subtitle = if (family.ligatures) "Join => -> != into single glyphs" else "${family.label} has no ligatures",
                icon = Icons.Rounded.Link, iconTint = MaterialTheme.colorScheme.secondary,
                trailing = { AppSwitch(settings.ligatures && family.ligatures, { v -> app.store.updateSettings { it.copy(ligatures = v) } }, enabled = family.ligatures) },
            )
            RowDivider()
            GroupRow(
                title = "Nerd Font glyphs",
                subtitle = "Draw prompt and file icons from the bundled symbols font",
                icon = Icons.Rounded.EmojiSymbols, iconTint = MaterialTheme.colorScheme.secondary,
                checked = settings.nerdGlyphs, onCheckedChange = { v -> app.store.updateSettings { it.copy(nerdGlyphs = v) } },
            )
        }

        Group("Terminal colors") {
            GroupRow(
                title = "Color scheme",
                subtitle = "${Schemes.nameOf(settings.theme)}  ·  hosts and groups can override it",
                icon = Icons.Rounded.Palette,
                iconTint = MaterialTheme.colorScheme.secondary,
                onClick = { nav.navigate(Routes.THEME) },
                trailing = { PaletteChip(settings.theme) },
            )
            RowDivider()
            GroupRow(
                title = "Bold text is bright",
                subtitle = "Draw bold in the light half of the palette, the way xterm does",
                icon = Icons.Rounded.FormatBold,
                iconTint = MaterialTheme.colorScheme.secondary,
                checked = settings.boldIsBright, onCheckedChange = { v -> app.store.updateSettings { it.copy(boldIsBright = v) } },
            )
        }

        Group("Cursor") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Icons.Rounded.Edit, MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(14.dp))
                    Text("Shape", style = MaterialTheme.typography.bodyLarge)
                }
                Segmented(CursorStyle.entries.map { it.label }, settings.cursorStyle.ordinal) { i ->
                    app.store.updateSettings { it.copy(cursorStyle = CursorStyle.entries[i]) }
                }
                Text(
                    "A program that picks its own shape keeps it — vim's insert-mode bar still works.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RowDivider()
            GroupRow(
                title = "Blink",
                subtitle = "Pauses while you type and while the terminal is off screen",
                icon = Icons.Rounded.Bolt,
                iconTint = MaterialTheme.colorScheme.secondary,
                checked = settings.cursorBlink, onCheckedChange = { v -> app.store.updateSettings { it.copy(cursorBlink = v) } },
            )
        }

        Group("Highlighting") {
            GroupRow(
                title = "Highlighting",
                subtitle = when {
                    !settings.highlightEnabled -> "Off  ·  recolor errors, warnings and the rest"
                    settings.highlightRules.isEmpty() -> "On, but there are no rules yet"
                    else -> "On  ·  ${settings.highlightRules.count { it.enabled }} rules"
                },
                icon = Icons.Rounded.FormatColorText,
                iconTint = MaterialTheme.colorScheme.secondary,
                onClick = { nav.navigate(Routes.HIGHLIGHTS) },
            )
        }
    }
}
