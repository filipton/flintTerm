package dev.flint.term.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.flint.term.App
import dev.flint.term.data.SchemeSearch
import dev.flint.term.data.Schemes
import dev.flint.term.data.TermScheme
import java.io.File

/**
 * Picking terminal colors by looking at them.
 *
 * A list of scheme names said nothing about what the terminal would draw, so
 * every entry here is a mock session painted with the very palette the session
 * will run with — same colors in the same roles. With six hundred of them the
 * list is lazy and has a search box; the ones people ask for most come first.
 *
 * [groupId] switches the screen from the app-wide default to one group's
 * override, which may also be "App default".
 */
@Composable
fun ThemeScreen(nav: NavController, groupId: String? = null) {
    val app = LocalContext.current.applicationContext as App
    val context = LocalContext.current
    val settings by app.store.settings.collectAsStateWithLifecycle()
    val groups by app.store.groups.collectAsStateWithLifecycle()
    val group = groupId?.let { id -> groups.firstOrNull { it.id == id } }
    var importing by rememberSaveable { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<TermScheme?>(null) }
    var renaming by remember { mutableStateOf<TermScheme?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppHeader(
                title = "Color scheme",
                subtitle = group?.let { "For every host in ${it.label}" } ?: "Used by hosts that have not picked one",
                onBack = { nav.popBackStack() },
                actions = {
                    IconButton(onClick = { importing = true }) { Icon(Icons.Rounded.FileDownload, "Import a scheme") }
                },
            )
        },
    ) { padding ->
        if (groupId != null && group == null) {
            // Deleted from under us while this screen was open.
            Column(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(Icons.Rounded.Palette, "Gone", "This group no longer exists.")
            }
            return@Scaffold
        }
        ThemePicker(
            selected = if (group != null) group.theme else settings.theme,
            fallback = if (group != null) settings.theme else null,
            modifier = Modifier.fillMaxSize().padding(padding),
            onImport = { importing = true },
            onCustomMenu = { menuFor = it },
            onSelect = { picked ->
                if (group != null) {
                    app.store.upsertGroup(group.copy(theme = picked))
                } else if (picked != null) {
                    app.store.updateSettings { it.copy(theme = picked) }
                    app.sessions.applyTheme()
                }
                nav.popBackStack()
            },
        )
    }

    SchemeImportFlow(open = importing, onClose = { importing = false })

    menuFor?.let { scheme ->
        ActionSheet(
            onDismiss = { menuFor = null },
            title = scheme.name,
            subtitle = "Custom scheme",
            actions = listOf(
                SheetAction("Rename", Icons.Rounded.Edit) { menuFor = null; renaming = scheme },
                SheetAction("Share", Icons.Rounded.Share, subtitle = "As a Ghostty theme file") {
                    menuFor = null
                    shareScheme(context, scheme)
                },
                SheetAction("Delete", Icons.Rounded.Delete, danger = true) {
                    menuFor = null
                    app.store.updateSettings { s ->
                        s.copy(
                            customSchemes = s.customSchemes.filter { it.id != scheme.id },
                            theme = if (s.theme == scheme.id) Schemes.DEFAULT_ID else s.theme,
                        )
                    }
                    // Hosts and groups that named it now resolve to the app
                    // default; sessions already open should follow suit.
                    app.sessions.applyTheme()
                },
            ),
        )
    }

    renaming?.let { scheme ->
        var name by remember(scheme.id) { mutableStateOf(scheme.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename") },
            text = { Field(name, { name = it }, "Name") },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        app.store.updateSettings { s ->
                            s.copy(customSchemes = s.customSchemes.map { if (it.id == scheme.id) it.copy(name = name.trim()) else it })
                        }
                        renaming = null
                    },
                ) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
}

/** Hand a scheme to another app as the same text the catalog is built from. */
private fun shareScheme(context: android.content.Context, scheme: TermScheme) {
    val dir = File(context.cacheDir, "schemes").apply { mkdirs() }
    val safe = scheme.name.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifBlank { "scheme" }
    val file = File(dir, "$safe.ghostty").apply { writeText(scheme.toGhostty()) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, scheme.name)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Share ${scheme.name}"))
}

/**
 * The schemes, each shown as a small terminal running in it, behind a search
 * box: "Featured", then every dark one and every light one alphabetically,
 * then the user's own. Typing collapses that into one ranked list.
 *
 * Kept apart from [ThemeScreen] because the host editor shows the same list in
 * a sheet: its draft host is unsaved, so it cannot send you off to a route
 * and back.
 */
@Composable
fun ThemePicker(
    selected: String?,
    /** Non-null offers "App default" first, previewed as the scheme it resolves to. */
    fallback: String?,
    modifier: Modifier = Modifier,
    /** Offers "Import a scheme" at the end of the custom section. */
    onImport: (() -> Unit)? = null,
    /** The ⋮ on a custom scheme's card. */
    onCustomMenu: ((TermScheme) -> Unit)? = null,
    onSelect: (String?) -> Unit,
) {
    val catalog by Schemes.catalog.collectAsStateWithLifecycle()
    val custom by Schemes.custom.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val sections = remember(catalog, custom, query) { sections(catalog, custom, query) }

    Column(modifier) {
        Field(
            query, { query = it }, "Search",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            leading = { Icon(Icons.Rounded.Search, null) },
            trailing = if (query.isEmpty()) null else {
                { IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear") } }
            },
        )
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (fallback != null && query.isBlank()) {
                item(key = "fallback") {
                    val scheme = Schemes.resolve(fallback)
                    ThemeCard(scheme, "App default", scheme.name, selected == null) { onSelect(null) }
                }
            }
            if (sections.isEmpty()) {
                item(key = "none") {
                    EmptyState(Icons.Rounded.Search, "No scheme called \"${query.trim()}\"", "Names are all there is to search. Try a shorter one.")
                }
            }
            sections.forEach { section ->
                if (section.title != null) {
                    item(key = "h:${section.title}") { GroupLabel(section.title, Modifier.padding(start = 0.dp, top = 8.dp)) }
                }
                items(section.schemes, key = { it.id }) { scheme ->
                    ThemeCard(
                        scheme, scheme.name, null, selected == scheme.id,
                        onMore = if (scheme.custom && onCustomMenu != null) ({ onCustomMenu(scheme) }) else null,
                    ) { onSelect(scheme.id) }
                }
                if (section.title == CUSTOM && onImport != null) {
                    item(key = "import") {
                        Group {
                            GroupRow(
                                title = "Import a scheme",
                                subtitle = "From a Ghostty, Alacritty, Windows Terminal, iTerm2 or Xresources file, or pasted text",
                                icon = Icons.Rounded.Add,
                                onClick = onImport,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** [ThemePicker] in a full-height sheet, for screens that cannot navigate away. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemePickerSheet(selected: String?, fallback: String?, onDismiss: () -> Unit, onSelect: (String?) -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = MaterialTheme.colorScheme.background) {
        ThemePicker(selected, fallback, Modifier.fillMaxHeight(), onSelect = { onSelect(it); onDismiss() })
    }
}

private const val CUSTOM = "Custom"

private class SchemeSection(val title: String?, val schemes: List<TermScheme>)

/**
 * What the list shows. The custom section is always there when the query is
 * blank, even empty, so the import row has a home; a search does not have
 * sections at all, only the ranked answer.
 */
private fun sections(catalog: List<TermScheme>, custom: List<TermScheme>, query: String): List<SchemeSection> {
    if (query.isNotBlank()) {
        val hits = SchemeSearch.rank(Schemes.BUILT_IN + catalog + custom, query)
        return if (hits.isEmpty()) emptyList() else listOf(SchemeSection(null, hits))
    }
    val byName = compareBy<TermScheme> { it.name.lowercase() }
    return listOf(
        SchemeSection("Featured", Schemes.featured(catalog)),
        SchemeSection("Dark", catalog.filter { !it.isLight }.sortedWith(byName)),
        SchemeSection("Light", catalog.filter { it.isLight }.sortedWith(byName)),
        SchemeSection(CUSTOM, custom.sortedWith(byName)),
    )
}

/**
 * A pill of a scheme's own colors, for the rows that lead here. Takes the same
 * id list as `Palettes.forTheme`: the host's choice, then the fallback.
 */
@Composable
fun PaletteChip(vararg ids: String?) {
    // Re-resolve once the catalog is in, or a row drawn at startup keeps the default's colors.
    val catalog by Schemes.catalog.collectAsStateWithLifecycle()
    val custom by Schemes.custom.collectAsStateWithLifecycle()
    val colors = remember(ids.toList(), catalog, custom) { TermColors(Schemes.resolve(*ids)) }
    Row(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(colors.background)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(9.dp))
            .padding(horizontal = 7.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(1, 2, 3, 4, 6).forEach { i ->
            Box(Modifier.size(7.dp).clip(CircleShape).background(colors.ansi(i)))
        }
    }
}

@Composable
fun ThemeCard(
    scheme: TermScheme,
    title: String,
    caption: String?,
    selected: Boolean,
    onMore: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val colors = remember(scheme) { TermColors(scheme) }
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.background)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape,
            )
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = if (onMore != null) 4.dp else 14.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // A window's traffic lights, borrowed to show three of the palette's hues.
            listOf(1, 3, 2).forEach { i ->
                Box(Modifier.size(8.dp).clip(CircleShape).background(colors.ansi(i)))
                Spacer(Modifier.width(5.dp))
            }
            Spacer(Modifier.width(4.dp))
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = colors.foreground,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (caption != null) {
                Text(caption, style = MaterialTheme.typography.labelMedium, color = colors.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (selected) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Rounded.CheckCircle, "Selected", Modifier.size(18.dp), tint = colors.ansi(2))
            }
            if (onMore != null) {
                IconButton(onClick = onMore, Modifier.size(32.dp)) { Icon(Icons.Rounded.MoreVert, "More", Modifier.size(18.dp), tint = colors.foreground) }
            }
        }
        Spacer(Modifier.height(8.dp))
        MockSession(colors)
    }
}

@Composable
private fun MockSession(colors: TermColors) {
    val style = CodeStyle.copy(fontSize = 11.sp, lineHeight = 15.sp)
    val lines = remember(colors) { mockLines(colors) }
    Column {
        lines.forEach { line ->
            // Never wrap: a preview that reflows is no longer a picture of a terminal,
            // and a long line has to end at the card's edge like the real thing.
            Text(line, style = style, softWrap = false, maxLines = 1, overflow = TextOverflow.Clip)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(prompt(colors, ""), style = style, softWrap = false, maxLines = 1, overflow = TextOverflow.Clip)
            Box(Modifier.size(6.dp, 13.dp).background(colors.cursor))
        }
    }
}

/**
 * A session that exercises the colors a shell really reaches for: the prompt,
 * `ls --color`, git's status letters, a compiler error, and a faint comment.
 */
private fun mockLines(c: TermColors): List<AnnotatedString> = listOf(
    prompt(c, "ls"),
    buildAnnotatedString {
        seg("src", c.ansi(4))
        seg("  ", c.foreground)
        seg("build.sh", c.ansi(2))
        seg("  Cargo.toml", c.foreground)
    },
    prompt(c, "git status -sb"),
    buildAnnotatedString {
        seg("## ", c.dim)
        seg("main", c.ansi(2))
        seg("...origin/main", c.ansi(1))
        seg(" [ahead 1]", c.ansi(3))
    },
    buildAnnotatedString {
        seg(" M ", c.ansi(3))
        seg("src/main.rs", c.foreground)
        seg("   ", c.foreground)
        seg("?? ", c.ansi(1))
        seg("notes.txt", c.foreground)
    },
    prompt(c, "cargo test"),
    buildAnnotatedString {
        seg("error[E0432]", c.ansi(1))
        seg(": unresolved import", c.foreground)
        seg("  # 12 passed, 1 failed", c.dim)
    },
)

private fun prompt(c: TermColors, command: String) = buildAnnotatedString {
    seg("dev@arch", c.ansi(2))
    seg(":", c.dim)
    seg("~/src", c.ansi(4))
    seg("$ ", c.dim)
    seg(command, c.foreground)
}

private fun AnnotatedString.Builder.seg(text: String, color: Color) {
    withStyle(SpanStyle(color = color)) { append(text) }
}

/** A scheme's palette as Compose colors, in the roles the preview draws with. */
private class TermColors(scheme: TermScheme) {
    private val colors = scheme.ansi.map { it.opaque() }
    val background = scheme.background.opaque()
    val foreground = scheme.foreground.opaque()
    val cursor = scheme.cursor.opaque()

    /**
     * Faint text — comments, the prompt's punctuation. Deliberately not ANSI 8:
     * a few schemes set that to their own background, which would draw nothing.
     */
    val dim = foreground.copy(alpha = 0.55f)

    fun ansi(i: Int): Color = colors.getOrElse(i) { foreground }
}

/** Palettes are stored without an alpha channel; the screen needs one. */
private fun UInt.opaque() = Color(0xFF000000.toInt() or toInt())
