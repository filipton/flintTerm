package dev.flint.term.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.KeyboardTab
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.flint.term.App
import dev.flint.term.core.FileEntry
import dev.flint.term.core.SessionState
import dev.flint.term.core.SftpClient
import dev.flint.term.terminal.Highlighter
import dev.flint.term.transfer.ExternalEdit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * How much of a remote file the editor will take on.
 *
 * A phone text field lays out every character it holds, so the ceiling is not
 * about memory: it is the point past which typing stops feeling like typing.
 * Bigger files still have "Open in…", which streams to a real app.
 */
const val EDITOR_MAX_BYTES: Long = 2L * 1024 * 1024

/**
 * Edit a file on the host without leaving the app.
 *
 * The file is read whole over SFTP, colored by [Highlighter], and written back
 * whole. Nothing is written until Save is tapped, and leaving with changes on
 * screen asks first — a half-saved `sshd_config` is how people lock themselves
 * out of a server.
 */
@Composable
fun EditorScreen(nav: NavController, sessionId: String, path: String) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val settings by app.store.settings.collectAsStateWithLifecycle()
    val session = remember(sessionId) { app.sessions.get(sessionId) }
    if (session == null) {
        LaunchedEffect(Unit) { nav.popBackStack() }
        return
    }
    val scope = rememberCoroutineScope()
    var client by remember { mutableStateOf<SftpClient?>(null) }
    var buffer by remember { mutableStateOf<EditorBuffer?>(null) }
    var value by remember { mutableStateOf(TextFieldValue()) }
    var entry by remember { mutableStateOf<FileEntry?>(null) }
    var language by remember { mutableStateOf<Highlighter.Language?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var oversized by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    // Kept here rather than in Settings: it is a per-file habit, like a font
    // size in a viewer, not something worth a row in the settings screen.
    var lineNumbers by rememberSaveable { mutableStateOf(false) }

    val name = path.substringAfterLast('/').ifEmpty { path }
    val dirty = buffer?.isDirty(value.text) == true

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    LaunchedEffect(sessionId, path) {
        val state = session.state.first { it !is SessionState.Connecting }
        if (state !is SessionState.Connected) {
            error = (state as? SessionState.Disconnected)?.error ?: "Not connected"
            loading = false
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            val result = runCatching {
                val c = session.core.openSftp()
                val stat = c.stat(path)
                // Ask the size before the bytes: reading first would spend the
                // whole transfer only to refuse the file afterwards.
                val text = if (stat.size.toLong() > EDITOR_MAX_BYTES) null else c.readText(path, EDITOR_MAX_BYTES.toULong())
                Triple(c, stat, text)
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { (c, stat, text) ->
                    client = c
                    entry = stat
                    if (text == null) {
                        oversized = true
                    } else {
                        val shebang = text.lineSequence().firstOrNull().orEmpty()
                        value = TextFieldValue(text)
                        language = Highlighter.languageFor(name, shebang)
                        buffer = EditorBuffer(path, text, Highlighter.indentFor(name, shebang))
                    }
                }.onFailure { error = it.message ?: "Could not open this file" }
                loading = false
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { client?.let { c -> scope.launch(Dispatchers.IO) { runCatching { c.shutdown() } } } }
    }

    fun save(then: () -> Unit = {}) {
        val c = client ?: return
        val b = buffer ?: return
        val text = value.text
        saving = true
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                // The backup is what the host has right now, which is exactly
                // what this screen loaded or last wrote — no second read needed.
                if (settings.editorBackup) c.writeText(b.backupPath, b.saved)
                c.writeText(path, text)
            }
            withContext(Dispatchers.Main) {
                saving = false
                result.onSuccess {
                    buffer = b.withSaved(text)
                    toast("Saved $name")
                    then()
                }.onFailure { toast(it.message ?: "Could not save $name") }
            }
        }
    }

    fun insertIndent() {
        val b = buffer ?: return
        val typed = b.tab(value.text, value.selection.start, value.selection.end)
        value = TextFieldValue(typed.text, TextRange(typed.caret))
    }

    fun leave() {
        if (dirty) confirmLeave = true else nav.popBackStack()
    }

    BackHandler(enabled = dirty) { confirmLeave = true }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppHeader(
                title = name,
                subtitle = if (dirty) "$path  ·  unsaved changes" else path,
                onBack = { leave() },
                actions = {
                    if (buffer != null) {
                        IconButton(onClick = { insertIndent() }) { Icon(Icons.Rounded.KeyboardTab, "Insert indent") }
                        IconButton(onClick = { lineNumbers = !lineNumbers }) {
                            Icon(
                                Icons.Rounded.FormatListNumbered,
                                "Line numbers",
                                tint = if (lineNumbers) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { save() }, enabled = dirty && !saving) { Icon(Icons.Rounded.Save, "Save") }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                oversized -> EmptyState(
                    Icons.Rounded.OpenInNew,
                    "Too large to edit here",
                    "$name is ${humanBytes(entry?.size?.toLong() ?: 0)}. The editor stops at 2 MB so typing stays quick. Another app can open it instead.",
                    actionLabel = "Open in…",
                    onAction = { entry?.let { ExternalEdit.open(context, app.transfers, session, it, edit = true) { m -> toast(m) } } },
                )
                error != null -> EmptyState(Icons.Rounded.Error, "Cannot open this file", error.orEmpty())
                buffer != null -> CodeField(
                    value = value,
                    onValueChange = { value = it },
                    language = language,
                    lineNumbers = lineNumbers,
                    onTab = { insertIndent() },
                )
            }
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Unsaved changes") },
            text = { Text("$name has changes that are not on the host yet.") },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { confirmLeave = false; nav.popBackStack() }) { Text("Discard") }
                    Button(onClick = { confirmLeave = false; save { nav.popBackStack() } }) { Text("Save") }
                }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } },
        )
    }
}

/**
 * The text field itself: monospaced, colored, and scrolled as one piece with
 * its line-number gutter.
 *
 * The field is not given its own scroll: it grows to the height of the
 * document inside a scrolling column, so the gutter — drawn behind the field
 * from the same [TextLayoutResult] the field laid out — scrolls with the text
 * for free, and stays aligned with wrapped lines, which a column of numbers
 * beside the field never would.
 */
@Composable
private fun CodeField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    language: Highlighter.Language?,
    lineNumbers: Boolean,
    onTab: () -> Unit,
) {
    val colors = codeColors()
    val transformation = remember(language, colors) { CodeHighlighting(language, colors) }
    val scroll = rememberScrollState()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val numberStyle = CodeStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 12.sp)
    // Where each line of the document begins, so a laid-out line can be turned
    // back into a line number without counting newlines for every one of them.
    val starts = remember(value.text) { lineStarts(value.text) }
    val labels = remember(numberStyle) { HashMap<Int, TextLayoutResult>() }
    val gutter = if (!lineNumbers) 0.dp else with(density) {
        measurer.measure(AnnotatedString("8".repeat(maxOf(2, starts.size.toString().length))), numberStyle).size.width.toDp() + 16.dp
    }
    val topPad = 8.dp

    Column(Modifier.fillMaxSize().verticalScroll(scroll).imePadding()) {
        Spacer(Modifier.height(topPad))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = CodeStyle.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, lineHeight = 19.sp),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation = transformation,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
            ),
            onTextLayout = { layout = it },
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    val result = layout ?: return@drawBehind
                    if (!lineNumbers) return@drawBehind
                    // Only the numbers in the viewport are drawn; a 2000-line
                    // file would otherwise pay for 2000 of them every frame.
                    val top = (scroll.value - topPad.toPx()).coerceAtLeast(0f)
                    val last = result.getLineForVerticalPosition(top + scroll.viewportSize)
                    var line = result.getLineForVerticalPosition(top)
                    while (line <= last) {
                        val index = starts.binarySearch(result.getLineStart(line))
                        // Negative means this is the continuation of a wrapped
                        // line, which carries no number of its own.
                        if (index >= 0) {
                            val label = labels.getOrPut(index + 1) { measurer.measure(AnnotatedString((index + 1).toString()), numberStyle) }
                            drawText(label, topLeft = Offset(gutter.toPx() - 10.dp.toPx() - label.size.width, result.getLineTop(line)))
                        }
                        line++
                    }
                }
                .padding(start = gutter + 12.dp, end = 12.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Tab) {
                        onTab()
                        true
                    } else {
                        false
                    }
                },
        )
        // Room to scroll the last line clear of the keyboard.
        Spacer(Modifier.height(160.dp))
    }
}

/**
 * Colors the document one line at a time, keeping what each line's text
 * tokenized to.
 *
 * Re-highlighting everything on every keystroke is what makes naive editors
 * unusable: the regex pass over a 2000-line file costs far more than the frame
 * it has to fit in. Keying the cache on the line's own text means a keystroke
 * only re-tokenizes the line it landed on, and moving a line around the file
 * costs nothing at all. The whole-document memo on top of that is for the
 * several calls Compose makes with the same text within one frame.
 */
private class CodeHighlighting(
    private val language: Highlighter.Language?,
    private val colors: Map<Highlighter.Token, SpanStyle>,
) : VisualTransformation {
    private val perLine = HashMap<String, List<Highlighter.Span>>()
    private var lastText: String? = null
    private var lastResult: TransformedText? = null

    override fun filter(text: AnnotatedString): TransformedText {
        if (language == null) return TransformedText(text, OffsetMapping.Identity)
        val raw = text.text
        lastResult?.let { if (raw == lastText) return it }
        // Lines that were edited away would otherwise sit in the map forever.
        if (perLine.size > 8000) perLine.clear()
        val styled = buildAnnotatedString {
            append(raw)
            var start = 0
            while (true) {
                val newline = raw.indexOf('\n', start)
                val end = if (newline < 0) raw.length else newline
                if (end > start) {
                    val line = raw.substring(start, end)
                    for (span in perLine.getOrPut(line) { Highlighter.spans(line, language) }) {
                        colors[span.token]?.let { addStyle(it, start + span.start, start + span.end) }
                    }
                }
                if (newline < 0) break
                start = newline + 1
            }
        }
        val result = TransformedText(styled, OffsetMapping.Identity)
        lastText = raw
        lastResult = result
        return result
    }
}

/**
 * The palette the editor paints tokens with.
 *
 * Keywords, types and keys borrow the theme's own accents so an imported theme
 * carries through; strings and numbers need a green and an orange the schemes
 * have no slot for, and those are chosen per background so they stay readable
 * on paper-white as well as on ink.
 */
@Composable
private fun codeColors(): Map<Highlighter.Token, SpanStyle> {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) {
        val dark = scheme.background.luminance() < 0.5f
        mapOf(
            Highlighter.Token.KEYWORD to SpanStyle(color = scheme.tertiary),
            Highlighter.Token.TYPE to SpanStyle(color = scheme.secondary),
            Highlighter.Token.KEY to SpanStyle(color = scheme.primary),
            Highlighter.Token.STRING to SpanStyle(color = if (dark) Tone.Green else Color(0xFF2F7A2F)),
            Highlighter.Token.NUMBER to SpanStyle(color = if (dark) Tone.Orange else Color(0xFFB35309)),
            Highlighter.Token.COMMENT to SpanStyle(color = scheme.onSurfaceVariant.copy(alpha = 0.8f)),
        )
    }
}

/** The offset every line of [text] starts at, ascending, so it can be searched. */
private fun lineStarts(text: String): IntArray {
    val out = ArrayList<Int>()
    out.add(0)
    var i = text.indexOf('\n')
    while (i >= 0) {
        out.add(i + 1)
        i = text.indexOf('\n', i + 1)
    }
    return out.toIntArray()
}

/**
 * The file being edited, apart from the screen showing it: what the host has,
 * what Tab owes this file, and where a backup goes.
 *
 * It is a value rather than a mutable model so that saving — which is the only
 * thing that changes what the host has — is a new buffer Compose can see.
 */
class EditorBuffer(val path: String, val saved: String, val indent: String) {

    /** The text and the caret after an edit. */
    data class Typed(val text: String, val caret: Int)

    /** Where [save]'s optional copy of the previous contents goes. */
    val backupPath: String get() = "$path.bak"

    /** Whether what is on screen still differs from what the host holds. */
    fun isDirty(text: String): Boolean = text != saved

    /** The same file, now that [text] is what the host holds. */
    fun withSaved(text: String): EditorBuffer = EditorBuffer(path, text, indent)

    /**
     * Tab, which in a file means one indent step and not a literal tab
     * character — unless the file is a Makefile or Go, where it must be.
     */
    fun tab(text: String, selectionStart: Int, selectionEnd: Int): Typed {
        val from = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val to = maxOf(selectionStart, selectionEnd).coerceIn(from, text.length)
        return Typed(text.substring(0, from) + indent + text.substring(to), from + indent.length)
    }
}

/**
 * Whether tapping this file should open it here rather than hand it to another app.
 *
 * A name the highlighter knows is text by definition; beyond those it falls
 * back to the same guess "Open in…" makes, which already treats the
 * extension-less files of a server as text.
 */
fun editableInApp(entry: FileEntry): Boolean =
    !entry.isDir &&
        entry.size.toLong() <= EDITOR_MAX_BYTES &&
        (Highlighter.languageFor(entry.name) != null || ExternalEdit.mimeFor(entry.name).startsWith("text/"))
