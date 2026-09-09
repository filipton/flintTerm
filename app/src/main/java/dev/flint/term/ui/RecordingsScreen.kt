package dev.flint.term.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.flint.term.App
import dev.flint.term.session.SessionLog
import dev.flint.term.session.shareFile
import dev.flint.term.terminal.Palettes
import dev.flint.term.terminal.TerminalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The sessions that were recorded, and a way to watch them again.
 *
 * A cast is only worth writing if something plays it, and "copy the file off
 * the phone and run asciinema" is not that. The player here is the app's own
 * terminal fed the recorded bytes on the recorded timing, so what comes back is
 * what was on the screen — colors, cursor moves and all.
 */
@Composable
fun RecordingsScreen(nav: NavController) {
    val context = LocalContext.current
    val dir = remember { SessionLog.recordingsDir(context) }
    var files by remember { mutableStateOf(listRecordings(dir)) }
    var playing by remember { mutableStateOf<File?>(null) }
    var reading by remember { mutableStateOf<File?>(null) }

    playing?.let { file ->
        CastPlayer(file) { playing = null }
        return
    }
    reading?.let { file ->
        LogViewer(file) { reading = null }
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppHeader(title = "Recordings", subtitle = dir.absolutePath, onBack = { nav.popBackStack() }) },
    ) { padding ->
        LazyColumn(state = rememberScreenListState("recordings"), modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 40.dp)) {
            if (files.isEmpty()) {
                item {
                    EmptyState(
                        Icons.Rounded.Movie,
                        "Nothing recorded yet",
                        "Start a recording from a terminal's menu, or switch one on for a host that should always be logged.",
                    )
                }
            }
            items(files, key = { it.absolutePath }) { file ->
                val cast = file.name.endsWith(".cast")
                Group(modifier = Modifier.padding(top = 8.dp)) {
                    GroupRow(
                        title = file.name,
                        subtitle = "${humanBytes(file.length())}  ·  ${relativeTime(file.lastModified()) ?: "just now"}" +
                            if (cast) "  ·  plays back" else "  ·  plain text",
                        icon = if (cast) Icons.Rounded.Movie else Icons.Rounded.Description,
                        iconTint = if (cast) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                        onClick = { if (cast) playing = file else reading = file },
                        trailing = {
                            Row {
                                IconButton(onClick = { shareFile(context, file) }) { Icon(Icons.Rounded.Share, "Share") }
                                IconButton(onClick = { file.delete(); files = listRecordings(dir) }) {
                                    Icon(Icons.Rounded.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

private fun listRecordings(dir: File): List<File> =
    dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()

/** Wipes the screen and the scrollback, so a replay starts from nothing. */
private const val RESET = "\u001Bc"

/** A recorded session, replayed into a real terminal. */
@Composable
private fun CastPlayer(file: File, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val settings by app.store.settings.collectAsStateWithLifecycle()
    var cast by remember(file) { mutableStateOf<SessionLog.Cast?>(null) }
    var failure by remember(file) { mutableStateOf<String?>(null) }
    LaunchedEffect(file) {
        withContext(Dispatchers.IO) { runCatching { file.readText() } }
            .onSuccess { cast = SessionLog.parseCast(it) }
            .onFailure { failure = it.message ?: "cannot read the recording" }
    }

    val ready = cast
    if (ready == null) {
        Scaffold(topBar = { AppHeader(title = file.name, onBack = onBack) }) { p ->
            Box(Modifier.fillMaxSize().padding(p), contentAlignment = Alignment.Center) {
                Text(failure ?: "Reading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    val palette = remember(settings.theme) { Palettes.forTheme(settings.theme) }
    val session = remember(file, ready) { app.sessions.openPlayback(file.name, ready.cols, ready.rows) }
    val view = remember(file, ready) {
        TerminalView(context).apply {
            this.session = session
            fontSizeSp = settings.fontSizeSp
        }
    }
    DisposableEffect(file, ready) { onDispose { session.destroy() } }

    // Where the replay is: the next frame to print. Everything else — the clock
    // on screen, the slider — is derived from it, so there is one thing to move.
    var at by remember(file) { mutableStateOf(0) }
    var playing by remember(file) { mutableStateOf(true) }
    var speed by remember(file) { mutableStateOf(1) }

    LaunchedEffect(file, playing, speed) {
        if (!playing) return@LaunchedEffect
        while (at < ready.frames.size) {
            val frame = ready.frames[at]
            val previous = if (at == 0) 0 else ready.frames[at - 1].atMillis
            // A long silence is the boring part of every recording: two seconds
            // is enough to feel a pause without sitting through it.
            delay(((frame.atMillis - previous) / speed).coerceIn(0, 2000))
            session.core.pushOutput(frame.text.toByteArray(Charsets.UTF_8))
            at++
        }
        playing = false
    }

    /** Show the screen as it was at [target] by replaying up to it at once. */
    fun seek(target: Int) {
        session.core.pushOutput(RESET.toByteArray())
        val text = StringBuilder()
        for (i in 0 until target.coerceIn(0, ready.frames.size)) text.append(ready.frames[i].text)
        session.core.pushOutput(text.toString().toByteArray(Charsets.UTF_8))
        at = target.coerceIn(0, ready.frames.size)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppHeader(
                title = file.name,
                subtitle = "${ready.cols}×${ready.rows}  ·  ${formatSeconds(ready.durationMillis)}",
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.weight(1f).fillMaxWidth().background(Color(palette.background.toInt()))) {
                AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { playing = !playing }) {
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play")
                }
                IconButton(onClick = { seek(0); playing = true }) { Icon(Icons.Rounded.Replay, "From the start") }
                val position = ready.frames.getOrNull((at - 1).coerceAtLeast(0))?.atMillis ?: 0
                Text(
                    "${formatSeconds(position)} / ${formatSeconds(ready.durationMillis)}",
                    style = CodeStyle.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                // 1×, 2×, 4× and round again: three speeds cover "watch it" and
                // "get to the end", and a menu for that would be too much.
                TextButton(onClick = { speed = if (speed >= 4) 1 else speed * 2 }) { Text("${speed}×") }
            }
            // The screen at a moment is everything printed before it, so seeking
            // means replaying — instantly, with the waiting taken out.
            Slider(
                value = at.toFloat(),
                onValueChange = { v ->
                    playing = false
                    seek(v.toInt())
                },
                valueRange = 0f..ready.frames.size.toFloat().coerceAtLeast(1f),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}

/** A plain-text recording, for reading rather than watching. */
@Composable
private fun LogViewer(file: File, onBack: () -> Unit) {
    var text by remember(file) { mutableStateOf<String?>(null) }
    LaunchedEffect(file) {
        text = withContext(Dispatchers.IO) {
            // The tail is what anybody wants from a log this long; the whole of
            // a day's session would only be slower to draw.
            runCatching { file.readText().takeLast(400_000) }.getOrElse { "Cannot read this file: ${it.message}" }
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppHeader(title = file.name, subtitle = humanBytes(file.length()), onBack = onBack) },
    ) { padding ->
        SelectionContainer(Modifier.fillMaxSize().padding(padding)) {
            Text(
                text ?: "Reading…",
                style = CodeStyle.copy(fontSize = 12.sp),
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(12.dp),
            )
        }
    }
}

private fun formatSeconds(millis: Long): String {
    val total = millis / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
