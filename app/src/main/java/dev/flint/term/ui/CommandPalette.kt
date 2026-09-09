package dev.flint.term.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.flint.term.App
import dev.flint.term.core.KeyCode
import dev.flint.term.core.KeyEventKind
import dev.flint.term.core.KeyPress
import dev.flint.term.data.Host

/**
 * Whether the palette is up, and the chord that puts it there.
 *
 * A plain object rather than state passed down a tree: the magnifier that opens
 * it sits in a screen's top bar, the palette itself is drawn over the whole app
 * from [MainActivity], and the two never meet in the composition.
 */
object CommandPaletteState {
    var open by mutableStateOf(false)
        private set

    fun show() {
        open = true
    }

    fun hide() {
        open = false
    }

    /**
     * Ctrl+Shift+P, the chord every editor has trained people to reach for.
     *
     * It is read in the activity's own key dispatch rather than added to
     * `KeyShortcuts`: the palette is a property of the app, not of a terminal,
     * and it has to open from the host list and the settings index too — where
     * no terminal is on screen to claim a chord for it.
     */
    fun opens(event: KeyEvent): Boolean =
        event.keyCode == KeyEvent.KEYCODE_P && event.isCtrlPressed && event.isShiftPressed && !event.isAltPressed
}

/**
 * One field that finds anything: a host to connect to, a snippet to type, a
 * setting buried in one of ten sections, or something the app can simply do.
 *
 * The ranking and the bolding live in [PaletteSearch]; everything here is the
 * drawing of it and what a pick does.
 */
@Composable
fun CommandPalette(nav: NavController) {
    if (!CommandPaletteState.open) return
    val app = LocalContext.current.applicationContext as App
    val hosts by app.store.hosts.collectAsStateWithLifecycle()
    val snippets by app.store.snippets.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }

    val entries = remember(hosts, snippets) {
        val recent = hosts
            .map { app.store.effective(it) }
            .sortedWith(compareByDescending<Host> { it.lastConnected }.thenBy { it.displayName.lowercase() })
            .map { PaletteEntry(it.displayName, it.target, PaletteKind.HOST, id = it.id) }
        // A snippet that asks for a value is left out: filling in a
        // {{placeholder}} takes a dialog that can ask, and this is one field
        // with nobody standing by to answer.
        val typeable = snippets
            .filter { it.placeholders.isEmpty() }
            .map { PaletteEntry(it.name.ifBlank { it.command }, it.command, PaletteKind.SNIPPET, id = it.id) }
        recent + typeable + PaletteCatalog.settings() + PaletteCatalog.actions()
    }
    val sections = remember(entries, query) { PaletteSearch.sections(PaletteSearch.rank(entries, query)) }

    fun close() {
        CommandPaletteState.hide()
    }

    fun go(route: String) {
        close()
        nav.navigate(route)
    }

    fun connect(id: String, files: Boolean) {
        val host = app.store.host(id) ?: return
        close()
        val session = app.sessions.openSsh(host)
        nav.navigate(if (files) Routes.sftp(session.id) else Routes.terminal(session.id))
    }

    fun type(id: String) {
        val snippet = app.store.snippets.value.firstOrNull { it.id == id } ?: return
        // The session it goes to is the newest one still running: the palette
        // can be opened from a screen that has no session of its own, and the
        // last one opened is the one that was being worked in.
        val session = app.sessions.sessions.value.lastOrNull { !it.isFinished }
        close()
        if (session == null) {
            android.widget.Toast.makeText(app, "No open session to type into", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            session.core.sendText(snippet.command)
            if (snippet.run) session.core.sendKey(KeyPress(KeyCode.Enter, false, false, false, KeyEventKind.PRESS))
        }
        nav.navigate(Routes.terminal(session.id))
    }

    fun choose(entry: PaletteEntry) {
        when (entry.kind) {
            PaletteKind.HOST -> connect(entry.id, files = false)
            PaletteKind.SNIPPET -> type(entry.id)
            PaletteKind.SETTING, PaletteKind.ACTION -> go(entry.route)
        }
    }

    Dialog(onDismissRequest = { close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
                PaletteField(query, { query = it }, focus) { close() }
                // The keyboard is up and the field is live the moment it opens:
                // a palette you have to tap into first is a screen, not a chord.
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                LazyColumn(
                    Modifier.fillMaxSize().navigationBarsPadding(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    if (sections.isEmpty()) {
                        item {
                            Text(
                                if (query.isBlank()) "Nothing here yet — add a host to start." else "Nothing matches.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(24.dp),
                            )
                        }
                    }
                    sections.forEach { (kind, rows) ->
                        item(key = "s-${kind.name}") {
                            GroupLabel(if (kind == PaletteKind.HOST && query.isBlank()) "Recent hosts" else kind.label)
                        }
                        items(rows.size, key = { "${kind.name}-${rows[it].entry.route}-${rows[it].entry.id}-${rows[it].entry.title}" }) { i ->
                            PaletteRow(
                                match = rows[i],
                                onClick = { choose(rows[i].entry) },
                                onFiles = if (kind == PaletteKind.HOST) {
                                    { connect(rows[i].entry.id, files = true) }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaletteField(value: String, onChange: (String) -> Unit, focus: FocusRequester, onClose: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    "Hosts, snippets, settings, actions",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value, onChange, singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            )
        }
        IconButton(onClick = onClose, Modifier.size(32.dp)) {
            Icon(Icons.Rounded.Close, "Close", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PaletteRow(match: PaletteMatch, onClick: () -> Unit, onFiles: (() -> Unit)?) {
    val entry = match.entry
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 20.dp, top = 8.dp, bottom = 8.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(glyph(entry.kind), tint(entry.kind))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                bolded(entry.title, match.titleHits),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.subtitle.isNotBlank()) {
                Text(
                    bolded(entry.subtitle, match.subtitleHits),
                    style = if (entry.kind == PaletteKind.HOST || entry.kind == PaletteKind.SNIPPET) {
                        CodeStyle.copy(fontSize = 12.sp)
                    } else {
                        MaterialTheme.typography.bodySmall
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onFiles != null) {
            IconButton(onClick = onFiles) {
                Icon(Icons.Rounded.Folder, "Browse files", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The characters the query found, drawn bold, so a result explains itself. */
private fun bolded(text: String, hits: List<Int>): AnnotatedString = buildAnnotatedString {
    append(text)
    hits.forEach { at -> if (at in text.indices) addStyle(SpanStyle(fontWeight = FontWeight.Bold), at, at + 1) }
}

private fun glyph(kind: PaletteKind): ImageVector = when (kind) {
    PaletteKind.HOST -> Icons.Rounded.Dns
    PaletteKind.SNIPPET -> Icons.Rounded.AutoAwesome
    PaletteKind.SETTING -> Icons.Rounded.Settings
    PaletteKind.ACTION -> Icons.Rounded.Bolt
}

@Composable
private fun tint(kind: PaletteKind): Color = when (kind) {
    PaletteKind.HOST -> MaterialTheme.colorScheme.primary
    PaletteKind.SNIPPET -> MaterialTheme.colorScheme.secondary
    PaletteKind.SETTING -> MaterialTheme.colorScheme.onSurfaceVariant
    PaletteKind.ACTION -> MaterialTheme.colorScheme.tertiary
}

/** The magnifier that opens the palette, for a screen's top bar. */
@Composable
fun CommandPaletteButton() {
    IconButton(onClick = { CommandPaletteState.show() }) {
        Icon(Icons.Rounded.Search, "Search everything")
    }
}
