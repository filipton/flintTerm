package dev.flint.term.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.rounded.History
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardHide
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Splitscreen
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Close
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.flint.term.terminal.ExtraKeys
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.Text
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.flint.term.App
import dev.flint.term.core.KeyCode
import dev.flint.term.core.SessionState
import dev.flint.term.session.Scrollback
import dev.flint.term.session.SessionLog
import dev.flint.term.session.WorkingDirectory
import dev.flint.term.session.coreOptions
import dev.flint.term.session.shareFile
import dev.flint.term.terminal.KeyShortcuts
import dev.flint.term.terminal.Palettes
import dev.flint.term.terminal.PromptMarks
import dev.flint.term.terminal.TerminalView
import androidx.compose.material.icons.rounded.Tab
import dev.flint.term.data.usesTmuxControls
import dev.flint.term.terminal.sendChord
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
fun TerminalScreen(nav: NavController, sessionId: String) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val session = remember(sessionId) { app.sessions.get(sessionId) }
    if (session == null) {
        LaunchedEffect(Unit) { nav.popBackStack() }
        return
    }
    val settings by app.store.settings.collectAsStateWithLifecycle()
    val state by session.state.collectAsStateWithLifecycle()
    val title by session.title.collectAsStateWithLifecycle()
    val name by session.name.collectAsStateWithLifecycle()
    val progress by session.progress.collectAsStateWithLifecycle()
    val sessions by app.sessions.sessions.collectAsStateWithLifecycle()
    val recording by session.recording.collectAsStateWithLifecycle()
    val banner by session.banner.collectAsStateWithLifecycle()
    val cwd by session.cwd.collectAsStateWithLifecycle()
    val cwdHome by session.home.collectAsStateWithLifecycle()
    /** Whether this shell marks its prompts; everything below gates on it. */
    val marksPrompts by session.marksPrompts.collectAsStateWithLifecycle()
    /** Where the shell says it is, ready to be shown; null unless it says. */
    val dir = WorkingDirectory.shorten(cwd, cwdHome)
    // What is typed on the current line, refreshed as it changes, for completions.
    var typed by remember(sessionId) { mutableStateOf("") }
    var connectionSheet by remember { mutableStateOf(false) }
    var serverMessage by remember { mutableStateOf(false) }
    // Swiping the drawer away during a connection means "let me watch the
    // terminal"; it should not spring back on the next step.
    var sheetDismissed by remember(sessionId) { mutableStateOf(false) }
    val catalog by dev.flint.term.data.Schemes.catalog.collectAsStateWithLifecycle()
    val custom by dev.flint.term.data.Schemes.custom.collectAsStateWithLifecycle()
    val palette = remember(session.host?.theme, settings.theme, catalog, custom) { Palettes.forTheme(session.host?.theme, settings.theme) }
    val termFg = Color(0xFF000000.toInt() or palette.foreground.toInt())
    // Every pane takes its chrome from its own color scheme, so the two halves
    // of a split each look like the session they belong to.
    val colors = chromeFor(session.host?.theme, settings.theme)
    val termBg = colors.terminal
    val chrome = colors.bar
    val onChrome = colors.on
    val onChromeMuted = colors.muted

    // The host's own font size, read from the store rather than from
    // session.host, because the pinch below writes it and the session was
    // handed its copy of the host when it was opened. 0 means "follow the app".
    val savedHosts by app.store.hosts.collectAsStateWithLifecycle()
    val hostFontSp = session.host?.id?.let { id -> savedHosts.firstOrNull { it.id == id }?.fontSizeSp } ?: 0f
    val fontSp = if (hostFontSp > 0f) hostFontSp else settings.fontSizeSp

    val view = remember(sessionId) {
        TerminalView(context).apply {
            this.session = session
            fontSizeSp = fontSp
        }
    }
    LaunchedEffect(settings.predictiveEcho, session) {
        session.setPrediction(
            when (settings.predictiveEcho) {
                dev.flint.term.data.PredictiveEcho.ADAPTIVE -> dev.flint.term.core.PredictionMode.ADAPTIVE
                dev.flint.term.data.PredictiveEcho.ALWAYS -> dev.flint.term.core.PredictionMode.ALWAYS
                dev.flint.term.data.PredictiveEcho.NEVER -> dev.flint.term.core.PredictionMode.NEVER
            },
        )
    }
    LaunchedEffect(fontSp) { if (view.fontSizeSp != fontSp) view.fontSizeSp = fontSp }
    LaunchedEffect(settings.fontFamily, settings.ligatures) { view.setFont(settings.fontFamily, settings.ligatures) }
    LaunchedEffect(palette) {
        view.backgroundColorInt = termBg.value.toLong().toInt()
        view.foregroundColorInt = termFg.value.toLong().toInt()
        view.accentColor = 0xFF000000.toInt() or palette.cursor.toInt()
        session.setPalette(palette)
    }
    // A host with a size of its own keeps its own: pinching a switch console to
    // fit its 80x24 firmware terminal should not shrink every other session.
    fun writeFontSize(sp: Float) {
        val own = session.host?.id?.takeIf { hostFontSp > 0f }?.let { app.store.host(it) }
        if (own != null) app.store.upsertHost(own.copy(fontSizeSp = sp)) else app.store.updateSettings { it.copy(fontSizeSp = sp) }
    }
    view.onFontSizeChanged = { sp -> writeFontSize(sp) }
    var link by remember { mutableStateOf<TerminalView.Link?>(null) }
    view.onLinkTap = { link = it }

    val historyAll by app.store.history.collectAsStateWithLifecycle()
    val hostHistory = session.host?.id?.let { historyAll[it] }.orEmpty()
    val countsAll by app.store.historyCounts.collectAsStateWithLifecycle()
    val hostCounts = session.host?.id?.let { countsAll[it] }.orEmpty()
    // No event says "the user typed a character" — the emulator only knows the
    // screen changed — so the line is read on a slow tick, and only while there
    // is history to match it against.
    LaunchedEffect(sessionId, hostHistory.isEmpty(), settings.completeFromHistory) {
        if (hostHistory.isEmpty() || !settings.completeFromHistory) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(400)
            val line = runCatching { session.core.currentInput() }.getOrNull() ?: continue
            val t = dev.flint.term.data.CommandHistory.typed(line, hostHistory)
            if (t != typed) typed = t
        }
    }

    // What the feature is: the rest of the most recent match, drawn where the
    // cursor is. The list only exists for when that one guess is wrong.
    val ghost = remember(typed, hostHistory, hostCounts, settings.completeFromHistory) {
        if (settings.completeFromHistory) dev.flint.term.data.CommandHistory.ghost(hostHistory, typed, hostCounts) else null
    }
    var moreSuggestions by remember { mutableStateOf(emptyList<String>()) }
    // Where the list should hang from: just under the cursor, in view pixels,
    // read when the list appears — by then the line has stopped moving.
    var anchor by remember { mutableStateOf(IntOffset.Zero) }
    LaunchedEffect(typed, hostHistory, settings.completeFromHistory) {
        moreSuggestions = emptyList()
        if (!settings.completeFromHistory) return@LaunchedEffect
        val all = dev.flint.term.data.CommandHistory.suggest(hostHistory, typed, counts = hostCounts)
        if (all.size < 2) return@LaunchedEffect
        // Long enough never to appear mid-word, short enough to be there by the
        // time somebody has stopped to think about what to type.
        kotlinx.coroutines.delay(1800)
        view.cursorPosition()?.let { (x, y, h) -> anchor = IntOffset(x.toInt(), (y + h).toInt()) }
        moreSuggestions = all
    }

    /** Send only what is missing; the shell already has what was typed. */
    fun accept(command: String) {
        val rest = command.removePrefix(typed)
        if (rest == command && typed.isNotEmpty()) {
            repeat(typed.length) { view.sendKey(dev.flint.term.core.KeyCode.Backspace) }
        }
        view.sendText(rest)
        typed = command
        moreSuggestions = emptyList()
    }

    view.ghost = ghost
    view.doubleTapSendsTab = settings.doubleTapSendsTab
    view.twoFingerDragArrows = settings.twoFingerDragArrows
    view.nerdGlyphs = settings.nerdGlyphs
    view.cursorStyle = settings.cursorStyle
    view.cursorBlink = settings.cursorBlink
    // Bold-as-bright is resolved down in the core; the session manager pushes
    // every option change to the sessions that are already open, so nothing is
    // needed here beyond the knobs the view itself reads.
    LaunchedEffect(settings.highlightEnabled, settings.highlightRules) {
        view.setHighlights(if (settings.highlightEnabled) settings.highlightRules else emptyList())
    }
    view.onAcceptGhost = {
        val hint = ghost
        if (settings.completeFromHistory && settings.tabAcceptsSuggestion && hint != null) {
            view.sendText(hint)
            typed += hint
            moreSuggestions = emptyList()
            true
        } else {
            false
        }
    }

    LaunchedEffect(session) {
        session.bell.collectLatest {
            if (settings.vibrateOnBell) {
                context.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }
    LaunchedEffect(session) {
        session.clipboard.collectLatest { text ->
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("terminal", text))
            Toast.makeText(context, "Copied from remote", Toast.LENGTH_SHORT).show()
        }
    }
    val rootView = LocalView.current
    DisposableEffect(settings.keepScreenOn) {
        rootView.keepScreenOn = settings.keepScreenOn
        onDispose { rootView.keepScreenOn = false }
    }
    DisposableEffect(view) { onDispose { view.session = null } }

    var menu by remember { mutableStateOf(false) }
    var switcher by remember { mutableStateOf(false) }
    var splitPicker by remember { mutableStateOf(false) }
    /** The session a rename dialog is open for, if one is. */
    var renaming by remember { mutableStateOf<dev.flint.term.session.TerminalSession?>(null) }

    /**
     * Send files to the host and type where they landed.
     *
     * Only for a session that has a host: a local shell and a serial console
     * have no far side to put a file on.
     */
    fun insertFiles(uris: List<android.net.Uri>): Boolean {
        if (session.host == null || uris.isEmpty()) return false
        val dir = settings.terminalUploadDir
        Toast.makeText(context, if (uris.size == 1) "Sending to $dir…" else "Sending ${uris.size} files to $dir…", Toast.LENGTH_SHORT).show()
        dev.flint.term.transfer.InsertFile.into(
            app.transfers, session, uris, dir,
            onPath = { quoted -> view.sendText("$quoted ") },
            onFailure = { why -> Toast.makeText(context, why, Toast.LENGTH_LONG).show() },
        )
        return true
    }
    val pickToInsert = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> insertFiles(uris) }
    view.onFilesDropped = { uris -> insertFiles(uris) }
    view.onDrop = { event, uris ->
        // The grant lives on the activity and is deliberately not released: the
        // upload reads the file on its own thread, long after the drop is over.
        val activity = context as? androidx.fragment.app.FragmentActivity
        if (activity != null) runCatching { androidx.core.app.ActivityCompat.requestDragAndDropPermissions(activity, event) }
        insertFiles(uris)
    }
    // The compose field belongs to the session, not to this composition: a trip
    // to the file browser and back should find the half-written prompt where it
    // was left. What the setting decides is whether it survives leaving at all.
    val compose = remember(sessionId) { ComposeLines.of(sessionId) }
    DisposableEffect(sessionId) {
        onDispose { if (!app.store.settings.value.composeRemembersState) ComposeLines.forget(sessionId) }
    }
    view.onCompose = { compose.open = true }

    var snippets by remember { mutableStateOf(false) }
    var pad by remember { mutableStateOf(false) }
    var chords by remember { mutableStateOf(false) }
    var tmuxWindows by remember { mutableStateOf(false) }
    var scrollbackSheet by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Straight to the picker's own file, rather than through the cache: a save
    // the person chose the place for has no reason to be staged first.
    val saveScrollback = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        val text = Scrollback.text(session.core.allLines())
                        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } != null
                    }.getOrDefault(false)
                }
                Toast.makeText(context, if (ok) "Saved the scrollback" else "Could not save the scrollback", Toast.LENGTH_SHORT).show()
            }
        }
    }
    var query by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf<List<Pair<Int, Int>>>(emptyList()) } // (absolute line, column)
    var matchIndex by remember { mutableStateOf(0) }

    LaunchedEffect(settings.volumeDownAction, settings.volumeUpAction, settings.volumeDownMode, settings.volumeUpMode) {
        view.volumeDownAction = settings.volumeDownAction
        view.volumeUpAction = settings.volumeUpAction
        view.volumeDownMode = settings.volumeDownMode
        view.volumeUpMode = settings.volumeUpMode
    }
    view.onSnippets = { snippets = true }
    view.onSearch = { searching = !searching; if (!searching) view.searchHighlight = null }

    fun open(id: String) = nav.navigate(Routes.terminal(id)) { popUpTo(Routes.HOSTS) }

    /** The ⋮ entry: says where the files went, since nothing else will. */
    fun toggleRecording() {
        if (session.recording.value != null) {
            val files = session.stopRecording()
            val where = files.firstOrNull()?.parentFile?.absolutePath
            val message = if (where == null) "Recording stopped" else "Saved ${files.joinToString(" and ") { it.name }} in $where"
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        } else {
            val started = session.startRecording(
                SessionLog.recordingsDir(context),
                settings.recordingFormat,
                view.gridCols.coerceAtLeast(20),
                view.gridRows.coerceAtLeast(5),
            )
            val names = started?.files?.joinToString(" and ") { it.name }
            Toast.makeText(
                context,
                if (names == null) "This session has ended" else "Recording to $names",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    // A host set to be recorded should not depend on anyone remembering: it
    // starts as soon as there is a shell to record, and the ⋮ menu can still
    // stop it.
    LaunchedEffect(sessionId, state is SessionState.Connected) {
        if (session.host?.recordSessions != true || state !is SessionState.Connected) return@LaunchedEffect
        session.startRecording(
            SessionLog.recordingsDir(context),
            settings.recordingFormat,
            view.gridCols.coerceAtLeast(20),
            view.gridRows.coerceAtLeast(5),
        )
    }

    session.host?.let { host ->
        view.onCommandEntered = { line ->
            // Recording uses the same reading of the line the suggestions do.
            app.store.rememberCommand(host.id, dev.flint.term.data.CommandHistory.typed(line, historyAll[host.id].orEmpty()))
            typed = ""
        }
    }

    // Swiping sideways moves along the tab strip, in the order shown there.
    fun switchSession(next: Boolean): Boolean {
        val live = sessions.filterNot { it.isFinished }
        val at = live.indexOfFirst { it.id == sessionId }
        val to = if (next) at + 1 else at - 1
        return if (at >= 0 && to in live.indices) {
            open(live[to].id)
            true
        } else {
            false
        }
    }
    // Off is off: some people drag sideways to select text and do not want the
    // session to change underneath them. On a host driven through tmux the
    // windows are the sessions, so the same fling moves between those instead.
    view.onSwitchSession = { next ->
        val host = session.host
        if (host != null && host.usesTmuxControls(settings)) {
            view.sendChord(if (next) "prefix n" else "prefix p", host.tmuxPrefix.ifBlank { "C-b" })
        } else if (settings.swipeBetweenSessions) {
            switchSession(next)
        } else {
            false
        }
    }

    fun closeSession(id: String) {
        // Closing the tab you are on has to leave you somewhere:
        // the next session along, or back to the host list.
        val next = sessions.filterNot { it.isFinished }.firstOrNull { it.id != id }
        app.sessions.get(id)?.close()
        app.sessions.remove(id)
        Workspace.forget(id)
        ComposeLines.forget(id)
        if (id == sessionId) {
            view.hideKeyboard()
            if (next != null) open(next.id) else nav.popBackStack()
        }
    }

    // Hardware keyboard: the chords the app answers instead of the shell.
    // What each one needs — the tab order, the saved font size, the session
    // list — lives here, so the view only asks and the mapping stays testable.
    view.onShortcut = { chord ->
        when (chord) {
            KeyShortcuts.Shortcut.COPY -> view.copySelection()
            KeyShortcuts.Shortcut.PASTE -> { view.paste(); true }
            KeyShortcuts.Shortcut.NEW_SESSION -> {
                val cols = view.gridCols.coerceAtLeast(20)
                val rows = view.gridRows.coerceAtLeast(5)
                val host = session.host
                open(if (host != null) app.sessions.openSsh(host, cols, rows).id else app.sessions.openLocal(cols, rows).id)
                true
            }
            KeyShortcuts.Shortcut.NEXT_TAB -> switchSession(true)
            KeyShortcuts.Shortcut.PREV_TAB -> switchSession(false)
            KeyShortcuts.Shortcut.SEARCH -> { searching = true; true }
            KeyShortcuts.Shortcut.CLOSE_SESSION -> { closeSession(sessionId); true }
            // Zoom goes where the pinch goes — this host's size when it has one,
            // the app's when it does not — and the effect above applies it.
            KeyShortcuts.Shortcut.FONT_BIGGER -> { writeFontSize((fontSp + 1f).coerceIn(TerminalView.MIN_SP, TerminalView.MAX_SP)); true }
            KeyShortcuts.Shortcut.FONT_SMALLER -> { writeFontSize((fontSp - 1f).coerceIn(TerminalView.MIN_SP, TerminalView.MAX_SP)); true }
            // Reset on a host that has a size of its own gives the override back
            // rather than pinning it to the stock 13 sp: "reset" should undo the
            // thing you did, which was overriding in the first place.
            KeyShortcuts.Shortcut.FONT_RESET -> {
                val own = session.host?.id?.takeIf { hostFontSp > 0f }?.let { app.store.host(it) }
                if (own != null) app.store.upsertHost(own.copy(fontSizeSp = 0f))
                else app.store.updateSettings { it.copy(fontSizeSp = dev.flint.term.data.Settings().fontSizeSp) }
                true
            }
        }
    }


    // Automatic reconnects replace the session object; follow it.
    LaunchedEffect(sessionId) {
        app.sessions.replacements.collect { (old, new) -> if (old == sessionId) open(new) }
    }

    fun showMatch(i: Int) {
        if (matches.isEmpty()) { view.searchHighlight = null; return }
        val idx = Math.floorMod(i, matches.size)
        matchIndex = idx
        val (line, col) = matches[idx]
        val history = session.core.historySize().toInt()
        val rows = view.gridRows.coerceAtLeast(1)
        // Put the hit roughly a third down the screen.
        val offset = (history - line + rows / 3).coerceIn(0, history)
        session.core.scrollToOffset(offset.toUInt())
        view.searchHighlight = Triple(line - (history - offset), col, query.length)
    }

    /**
     * Move the view to the prompt above or below the top of the screen, and
     * say whether there was one to move to.
     */
    fun jumpToPrompt(previous: Boolean): Boolean {
        val row = PromptMarks.jumpTarget(session.core.marks(), previous) ?: return false
        // The marks are counted from the top visible line, so putting one there
        // is the offset it stands away from where the screen is now.
        val offset = session.core.displayOffset().toInt() - row
        session.core.scrollToOffset(offset.coerceIn(0, session.core.historySize().toInt()).toUInt())
        return true
    }

    /** Copy what the last command printed, as its marks bound it. */
    fun copyLastOutput(): Boolean {
        // A command still running ends where its output has got to, rather than
        // at the bottom of a screen that is mostly empty.
        var bottom = (view.gridRows - 1).coerceAtLeast(0)
        while (bottom > 0 && session.core.rowText(bottom.toUShort()).isBlank()) bottom--
        val rows = PromptMarks.lastOutput(session.core.marks(), bottom) ?: return false
        return view.copyRows(rows.first, rows.last)
    }

    LaunchedEffect(query, searching) {
        if (!searching || query.isBlank()) { matches = emptyList(); view.searchHighlight = null; return@LaunchedEffect }
        val q = query
        val found = withContext(Dispatchers.Default) {
            val lines = session.core.allLines()
            buildList {
                lines.forEachIndexed { i, l ->
                    var from = 0
                    while (true) {
                        val at = l.indexOf(q, from, ignoreCase = true)
                        if (at < 0) break
                        add(i to at); from = at + q.length
                    }
                }
            }
        }
        matches = found
        if (found.isNotEmpty()) showMatch(found.size - 1) else view.searchHighlight = null
    }

    BackHandler {
        if (searching) { searching = false; view.searchHighlight = null; return@BackHandler }
        if (compose.open) { compose.open = false; return@BackHandler }
        view.hideKeyboard()
        nav.popBackStack()
    }

    val live = sessions.filterNot { it.isFinished }
    val here = Pane.Term(sessionId)

    // Broadcasting only means anything between two terminals, and only while
    // both are on screen: a file browser has nothing to type into, and a split
    // that has been closed leaves nowhere for the second copy to go.
    val other = (Workspace.beside as? Pane.Term)?.takeIf { it != here }
    view.mirror = if (Workspace.broadcast) other?.let { app.sessions.get(it.sessionId) } else null

    Column(Modifier.fillMaxSize().background(chrome).statusBarsPadding()) {
        // ---- top strip ------------------------------------------------------
        // Every row this bar takes is a row of terminal nobody can read, so it
        // is one line: where you are, and the two things you reach for. What
        // used to be a second line of subtitle is only worth the space while
        // the connection is not simply up, and then it says so beside the name.
        Row(Modifier.fillMaxWidth().height(40.dp).padding(start = 2.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { view.hideKeyboard(); nav.popBackStack() }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", Modifier.size(19.dp), tint = onChrome)
            }
            Spacer(Modifier.width(4.dp))
            StatusDot(state, size = 7)
            Spacer(Modifier.width(8.dp))
            // The title is still the handle for "how did this connection go?".
            Row(
                Modifier.weight(1f).clickable { connectionSheet = true },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    // A name somebody chose outranks everything. Otherwise it
                    // is which machine and where on it, the second half being
                    // the one that changes while you work.
                    if (name != session.defaultLabel) name
                    else listOfNotNull(title ?: name, dir).joinToString(" · "),
                    style = MaterialTheme.typography.titleSmall,
                    color = onChrome,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                val note = when (val st = state) {
                    is SessionState.Connecting -> progress.lastOrNull()?.text ?: "connecting…"
                    is SessionState.Disconnected -> st.error ?: "disconnected"
                    is SessionState.Connected -> null
                }
                if (note != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        note,
                        style = MaterialTheme.typography.labelSmall,
                        color = onChromeMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
            if (session.host != null) {
                IconButton(
                    onClick = { nav.navigate(Routes.sftp(sessionId)) },
                    enabled = state is SessionState.Connected,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Rounded.Folder, "Files", Modifier.size(19.dp), tint = if (state is SessionState.Connected) onChrome else onChromeMuted.copy(alpha = 0.35f))
                }
            }
            IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.MoreVert, "More", Modifier.size(19.dp), tint = onChrome)
            }
        }

        // One session needs no tabs, and the terminal should keep every row it
        // can — so the strip only exists once there is something to switch between.
        AnimatedVisibility(visible = (live.size > 1 || Workspace.hasTabs) && settings.showSessionTabs) {
            WorkspaceTabs(
                sessions = live,
                current = here,
                onChrome = onChrome,
                onChromeMuted = onChromeMuted,
                onOpen = { pane -> if (pane != here) nav.navigate(pane.route()) { popUpTo(Routes.HOSTS) } },
                onClose = { pane -> if (pane is Pane.Term) closeSession(pane.sessionId) else Workspace.close(pane) },
                onRename = { renaming = it },
            )
        }

        AnimatedVisibility(visible = searching) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, null, tint = onChromeMuted)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Search scrollback", color = onChromeMuted, style = MaterialTheme.typography.bodyMedium)
                    BasicTextField(
                        query, { query = it }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = onChrome),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                Text(if (matches.isEmpty()) (if (query.isBlank()) "" else "0") else "${matchIndex + 1}/${matches.size}", style = MaterialTheme.typography.labelSmall, color = onChromeMuted)
                IconButton(onClick = { showMatch(matchIndex - 1) }, enabled = matches.isNotEmpty()) { Icon(Icons.Rounded.KeyboardArrowUp, "Previous", tint = onChrome) }
                IconButton(onClick = { showMatch(matchIndex + 1) }, enabled = matches.isNotEmpty()) { Icon(Icons.Rounded.KeyboardArrowDown, "Next", tint = onChrome) }
                IconButton(onClick = { searching = false; view.searchHighlight = null }) { Icon(Icons.Rounded.Close, "Close search", tint = onChrome) }
            }
        }

        // ---- terminal + overlays ---------------------------------------------
        val terminalArea: @Composable (Modifier) -> Unit = { area ->
        BoxWithConstraints(
            area.background(termBg).onGloballyPositioned { coords ->
                // The rectangle a floating window shrinks out of and expands
                // back into; it has to be known before anyone asks for one.
                val r = coords.boundsInWindow()
                Pip.trackTerminal(
                    context,
                    sessionId,
                    android.graphics.Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt()),
                )
            },
        ) {
            val maxWidthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
            val maxHeightPx = with(LocalDensity.current) { maxHeight.roundToPx() }
            AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())

            // Beside the scroll indicator, because that is what they move: a
            // prompt above the screen is somewhere in the scrollback, and the
            // menu is a long way to go for something you do several times.
            if (marksPrompts) {
                PromptChevrons(
                    onJump = { previous -> jumpToPrompt(previous) },
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }

            LinkChip(
                link = link,
                onDismiss = { link = null },
                onOpen = { l ->
                    if (l.isUrl) {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(l.text)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            .onFailure { Toast.makeText(context, "Nothing can open that link", Toast.LENGTH_SHORT).show() }
                    } else {
                        nav.navigate(Routes.sftp(sessionId, l.text.substringBefore(':')))
                    }
                    link = null
                },
                onCopy = { l ->
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("link", l.text))
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show(); link = null
                },
                canBrowse = session.host != null && state is SessionState.Connected,
                modifier = Modifier.align(Alignment.BottomCenter),
            )

            // The other matches, once typing has stopped for a moment. They are
            // deliberately late: a list on every keystroke is in the way, and
            // the gray hint after the cursor answers while typing continues.
            if (settings.completeFromHistory && moreSuggestions.isNotEmpty()) {
                // Under the cursor, kept inside the terminal: a list that hangs
                // off the edge of the screen is worse than one slightly to the
                // left of where the typing is.
                var size by remember { mutableStateOf(IntSize.Zero) }
                val box = with(LocalDensity.current) {
                    IntSize(maxWidthPx, maxHeightPx)
                }
                SuggestionList(
                    suggestions = moreSuggestions,
                    typed = typed,
                    onChrome = onChrome,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset {
                            val x = anchor.x.coerceIn(0, (box.width - size.width).coerceAtLeast(0))
                            // Below the cursor if it fits, above it if not.
                            val below = anchor.y + 6
                            val y = if (below + size.height <= box.height) below else (anchor.y - size.height - 24).coerceAtLeast(0)
                            IntOffset(x, y)
                        }
                        .onSizeChanged { size = it },
                    onPick = { command -> accept(command) },
                )
            }
        }
        }

        // What is open beside the terminal, if anything. A pane is only shown
        // once, so asking to split with the pane you are in is a no-op rather
        // than the same session drawn twice.
        val beside = Workspace.beside
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (beside == null || beside == here) {
                terminalArea(Modifier.fillMaxSize())
            } else {
                SplitArea(
                    stacked = splitStacked(),
                    onChrome = onChrome,
                    modifier = Modifier.fillMaxSize(),
                    first = { terminalArea(Modifier.fillMaxSize()) },
                    second = {
                        PaneBeside(beside, nav, Modifier.fillMaxSize()) {
                            Workspace.beside = here
                            nav.navigate(beside.route()) { popUpTo(Routes.HOSTS) }
                        }
                    },
                )
            }
        }

        AnimatedVisibility(visible = state is SessionState.Disconnected) {
            val s = state as? SessionState.Disconnected
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Session ended", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    val detail = s?.error ?: s?.exitCode?.let { "exit code $it" }
                    if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer, maxLines = 2)
                }
                TextButton(onClick = { connectionSheet = true }) { Text("Details") }
                Spacer(Modifier.width(4.dp))
                session.host?.let { h ->
                    Button(onClick = {
                        val fresh = app.sessions.openSsh(h, view.gridCols.coerceAtLeast(20), view.gridRows.coerceAtLeast(5))
                        app.sessions.remove(sessionId)
                        open(fresh.id)
                    }) { Text("Reconnect") }
                    Spacer(Modifier.width(8.dp))
                }
                OutlinedButton(onClick = { app.sessions.remove(sessionId); nav.popBackStack() }) { Text("Close") }
            }
        }

        if (compose.open) {
            ComposeLine(
                state = compose,
                chrome = chrome,
                onChrome = onChrome,
                modifier = Modifier.fillMaxWidth(),
                onSend = { text, enter ->
                    view.pasteText(text)
                    if (enter) view.sendKey(KeyCode.Enter)
                },
                onClose = { compose.open = false },
            )
        }

        val conf = LocalConfiguration.current
        val hardwareKeyboard = conf.keyboard == Configuration.KEYBOARD_QWERTY && conf.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
        val barRows = settings.extraKeysRows
        if (barRows > 0 && !(hardwareKeyboard && settings.hideExtraKeysWithHardwareKeyboard)) {
            ExtraKeysBar(
                view, chrome, onChrome, Modifier.fillMaxWidth().navigationBarsPadding().imePadding(),
                row1 = settings.extraKeysRow1 ?: ExtraKeys.defaultRow1,
                // Hidden rather than emptied: the second row keeps whatever was
                // arranged in it and comes back the way it was left.
                row2 = if (barRows >= 2) settings.extraKeysRow2 ?: ExtraKeys.defaultRow2 else emptyList(),
                onSnippets = { snippets = true },
                onSearch = { searching = !searching; if (!searching) view.searchHighlight = null },
                onCompose = { compose.open = true },
                onChords = if (settings.ctrlLongPressOpensChords) ({ chords = true }) else null,
                onMore = { pad = true },
            )
        } else {
            Spacer(Modifier.fillMaxWidth().navigationBarsPadding().imePadding())
        }
    }

    if (pad) {
        ExtraKeysPad(
            view,
            onDismiss = { pad = false },
            onSnippets = { pad = false; snippets = true },
            onSearch = { pad = false; searching = !searching; if (!searching) view.searchHighlight = null },
            onCompose = { pad = false; compose.open = true },
            onChords = if (settings.ctrlLongPressOpensChords) ({ pad = false; chords = true }) else null,
        )
    }

    TmuxControls(
        view, session, settings, chords, tmuxWindows,
        onDismiss = { chords = false; tmuxWindows = false },
        onManage = { chords = false; nav.navigate(Routes.CHORDS) },
    )

    if (snippets) {
        SnippetSheet(
            hostId = session.host?.id,
            onDismiss = { snippets = false },
            onManage = { nav.navigate(Routes.SNIPPETS) },
            onInsert = { text, run -> if (run) view.sendLine(text) else view.sendText(text) },
        )
    }

    // The connection shows itself: the steps are the progress indicator, and
    // the drawer gets out of the way once there is a shell to look at.
    var shownAt by remember(sessionId) { mutableStateOf(0L) }
    LaunchedEffect(state, sheetDismissed, progress.isNotEmpty()) {
        when (state) {
            is SessionState.Connecting -> if (!sheetDismissed && progress.isNotEmpty() && !connectionSheet) {
                connectionSheet = true
                shownAt = System.currentTimeMillis()
            }
            is SessionState.Connected -> if (connectionSheet) {
                // A connection over the LAN is done in a blink, and a drawer that
                // appears and vanishes in 200 ms reads as a glitch. Let it stand
                // long enough to be seen, and to be caught and held if wanted.
                val shown = System.currentTimeMillis() - shownAt
                if (shown < MIN_SHEET_MS) kotlinx.coroutines.delay(MIN_SHEET_MS - shown)
                connectionSheet = false
            }
            is SessionState.Disconnected -> Unit // the banner offers "Details"
        }
    }
    if (connectionSheet) {
        ConnectionSheet(
            label = session.label,
            target = session.host?.target,
            os = session.host?.id?.let { id -> app.store.hosts.value.firstOrNull { it.id == id }?.detectedOs },
            steps = progress,
            state = state,
            banner = banner.takeIf { settings.showAuthBanners },
            onDismiss = {
                connectionSheet = false
                if (state is SessionState.Connecting) sheetDismissed = true
            },
        )
    }
    banner?.let { text ->
        if (serverMessage) {
            AlertDialog(
                onDismissRequest = { serverMessage = false },
                text = { ServerMessage(text) },
                confirmButton = { TextButton(onClick = { serverMessage = false }) { Text("Close") } },
            )
        }
    }
    renaming?.let { target ->
        RenameSessionDialog(target) { renaming = null }
    }
    if (scrollbackSheet) {
        ActionSheet(
            onDismiss = { scrollbackSheet = false },
            title = "Share the scrollback",
            subtitle = "Everything the buffer still holds, as plain text",
            actions = listOf(
                SheetAction("Share", Icons.Rounded.Share, subtitle = "Send it to another app") {
                    scrollbackSheet = false
                    scope.launch {
                        val file = withContext(Dispatchers.IO) {
                            runCatching { Scrollback.write(context, session.label, session.core.allLines()) }.getOrNull()
                        }
                        if (file == null) {
                            Toast.makeText(context, "Could not write the scrollback", Toast.LENGTH_SHORT).show()
                        } else {
                            shareFile(context, file)
                        }
                    }
                },
                SheetAction("Save to a file", Icons.Rounded.Save, subtitle = "Choose where it goes") {
                    scrollbackSheet = false
                    saveScrollback.launch(Scrollback.fileName(session.label))
                },
            ),
        )
    }
    if (switcher) {
        ActionSheet(
            onDismiss = { switcher = false },
            title = "Sessions",
            actions = sessions.map { s ->
                val st = s.state.value
                SheetAction(
                    s.label + if (s.id == sessionId) "  ·  current" else "",
                    // No icon: a column of server glyphs looks busy and tells
                    // you nothing the name has not already said.
                    icon = null,
                    subtitle = s.title.value ?: when (st) {
                        is SessionState.Connecting -> "Connecting…"
                        is SessionState.Connected -> s.host?.target ?: "local"
                        is SessionState.Disconnected -> "Ended"
                    },
                ) { switcher = false; if (s.id != sessionId) open(s.id) }
            } + listOfNotNull(
                SheetAction("Rename", Icons.Rounded.Edit, subtitle = "What this session is called in the strip") {
                    switcher = false
                    renaming = session
                },
                SheetAction("New local shell", Icons.Rounded.Add) {
                    switcher = false
                    open(app.sessions.openLocal(view.gridCols.coerceAtLeast(20), view.gridRows.coerceAtLeast(5)).id)
                },
                session.host?.let { h ->
                    SheetAction("New session to ${h.displayName}", Icons.Rounded.Add) {
                        switcher = false
                        open(app.sessions.openSsh(h, view.gridCols.coerceAtLeast(20), view.gridRows.coerceAtLeast(5)).id)
                    }
                },
            ),
        )
    }
    if (splitPicker) {
        // What can share the screen: another session's terminal, or the files
        // of any session that is up — including this one, which is the pairing
        // the split mostly exists for.
        val others = live.filter { it.id != sessionId }
        ActionSheet(
            onDismiss = { splitPicker = false },
            title = "Show beside this one",
            actions = listOfNotNull(
                session.host?.let { h ->
                    SheetAction("Files", Icons.Rounded.Folder, subtitle = h.displayName) {
                        splitPicker = false
                        Workspace.openFiles(sessionId)
                        Workspace.beside = Pane.Files(sessionId)
                    }
                },
                // Watching the load while the command that is moving it runs in
                // the other half is the whole reason this pane can be split.
                session.host?.let { h ->
                    SheetAction("Server", Icons.Rounded.Speed, subtitle = h.displayName) {
                        splitPicker = false
                        Workspace.openServer(sessionId)
                        Workspace.beside = Pane.Server(sessionId)
                    }
                },
            ) + others.map { s ->
                SheetAction(s.label, Icons.Rounded.Terminal, subtitle = s.host?.target ?: "local") {
                    splitPicker = false
                    Workspace.beside = Pane.Term(s.id)
                }
            } + others.filter { it.host != null }.map { s ->
                SheetAction("Files · ${s.label}", Icons.Rounded.Folder, subtitle = s.host?.target) {
                    splitPicker = false
                    Workspace.openFiles(s.id)
                    Workspace.beside = Pane.Files(s.id)
                }
            },
        )
    }
    if (menu) {
        ActionSheet(
            onDismiss = { menu = false },
            actions = menuGroup(
                "Session",
                sessions.count { !it.isFinished }.takeIf { it > 1 }?.let { live ->
                    SheetAction("Sessions", Icons.Rounded.Layers, subtitle = "$live open") { menu = false; switcher = true }
                },
                // A second terminal on this host is usually wanted for the work
                // already in front of you, and walking it back down the tree by
                // hand is the part worth skipping.
                cwd?.takeIf { WorkingDirectory.isUsable(it) }?.let { where ->
                    session.host?.let { h ->
                        SheetAction("New session here", Icons.Rounded.Terminal, subtitle = dir) {
                            menu = false
                            view.hideKeyboard()
                            val fresh = app.sessions.openSsh(
                                h,
                                view.gridCols.coerceAtLeast(20),
                                view.gridRows.coerceAtLeast(5),
                                extraStartup = WorkingDirectory.cdCommand(where),
                            )
                            open(fresh.id)
                        }
                    }
                },
                recording.let { rec ->
                    if (rec == null) {
                        SheetAction("Record session", Icons.Rounded.FiberManualRecord, subtitle = settings.recordingFormat.help) {
                            menu = false; toggleRecording()
                        }
                    } else {
                        SheetAction("Stop recording", Icons.Rounded.StopCircle, subtitle = "${humanBytes(rec.bytes())} written so far") {
                            menu = false; toggleRecording()
                        }
                    }
                },
                SheetAction("Float this terminal", Icons.Rounded.PictureInPictureAlt, subtitle = "Keeps it in view while you use another app") {
                    menu = false
                    view.hideKeyboard()
                    if (!Pip.float(context)) Toast.makeText(context, "This device cannot float a window", Toast.LENGTH_SHORT).show()
                },
                session.host?.let {
                    SheetAction("Port forwards", Icons.Rounded.SwapHoriz) {
                        menu = false; view.hideKeyboard(); nav.navigate(Routes.forwards(sessionId))
                    }
                },
                // A session opened from the search field or a link is to a host
                // nothing knows about yet; this is where it stops being one-off.
                session.host?.takeIf { app.store.host(it.id) == null }?.let { h ->
                    SheetAction("Save host", Icons.Rounded.Add, subtitle = "Keep ${h.target} in the host list") {
                        menu = false
                        App.hostDraft = h
                        nav.navigate(Routes.hostEdit("new"))
                    }
                },
                // The connection drawer is gone by the time most people read a
                // banner properly, and a login URL in one may still be wanted.
                banner?.takeIf { settings.showAuthBanners }?.let {
                    SheetAction("Server message", Icons.Rounded.Campaign, subtitle = "What the server printed before login") {
                        menu = false; serverMessage = true
                    }
                },
                // Last in its group on purpose: the one entry here that cannot
                // be undone should not sit where a thumb lands by accident.
                SheetAction(if (state is SessionState.Disconnected) "Close" else "Disconnect", Icons.Rounded.PowerSettingsNew, danger = true) {
                    menu = false
                    view.hideKeyboard()
                    app.sessions.remove(sessionId)
                    nav.popBackStack()
                },
            ) + menuGroup(
                "View",
                SheetAction("Search scrollback", Icons.Rounded.Search) { menu = false; searching = true },
                // Only a shell that marks its prompts has these; see the
                // README on OSC 133.
                SheetAction("Previous prompt", Icons.Rounded.KeyboardArrowUp, subtitle = "Back up to where the last command was typed") {
                    menu = false
                    if (!jumpToPrompt(previous = true)) Toast.makeText(context, "No prompt further up", Toast.LENGTH_SHORT).show()
                }.takeIf { marksPrompts },
                SheetAction("Next prompt", Icons.Rounded.KeyboardArrowDown) {
                    menu = false
                    if (!jumpToPrompt(previous = false)) Toast.makeText(context, "No prompt further down", Toast.LENGTH_SHORT).show()
                }.takeIf { marksPrompts },
                SheetAction("Share the scrollback", Icons.Rounded.Share, subtitle = "The whole buffer as plain text") {
                    menu = false; scrollbackSheet = true
                },
                session.host?.let {
                    SheetAction("Server", Icons.Rounded.Speed, subtitle = "Load, memory, disks and what is running") {
                        menu = false
                        view.hideKeyboard()
                        nav.navigate(Routes.server(sessionId))
                    }
                },
                if (Workspace.beside == null) {
                    SheetAction("Split screen", Icons.Rounded.Splitscreen, subtitle = "Work in two panes at once") {
                        menu = false; splitPicker = true
                    }
                } else {
                    SheetAction("Close the second pane", Icons.Rounded.Splitscreen) { menu = false; Workspace.closeBeside() }
                },
                Workspace.beside?.let {
                    val stacked = splitStacked()
                    SheetAction(
                        if (stacked) "Put the panes side by side" else "Put one pane above the other",
                        Icons.Rounded.SwapHoriz,
                    ) { menu = false; Workspace.stacked = !stacked }
                },
                // Mosh has left SSH behind and has no channel to ask over, so
                // there is nothing to list; the chords still work there.
                session.host?.takeIf { it.usesTmuxControls(settings) && !it.mosh }?.let {
                    SheetAction("tmux windows", Icons.Rounded.Tab, subtitle = "Switch to a window on the other end") {
                        menu = false; tmuxWindows = true
                    }
                },
                SheetAction("Recordings", Icons.Rounded.Movie, subtitle = "Play back a recorded session") {
                    menu = false
                    view.hideKeyboard()
                    nav.navigate(Routes.RECORDINGS)
                },
            ) + menuGroup(
                "Type",
                SheetAction("Compose a line", Icons.Rounded.EditNote, subtitle = "Write it with autocorrect and voice, then send the lot") {
                    menu = false; compose.open = true
                },
                other?.let {
                    SheetAction(
                        "Type in both panes",
                        Icons.Rounded.Keyboard,
                        subtitle = if (Workspace.broadcast) "On: keys go to this terminal and the one beside it" else "Send what you type to the other terminal as well",
                    ) { menu = false; Workspace.broadcast = !Workspace.broadcast }
                },
                session.host?.let {
                    SheetAction("Insert a file", Icons.Rounded.AttachFile, subtitle = "Sends it to ${settings.terminalUploadDir} and types the path") {
                        menu = false
                        pickToInsert.launch(arrayOf("*/*"))
                    }
                },
                SheetAction("Paste", Icons.Rounded.ContentPaste) { menu = false; view.paste() },
                SheetAction("Select all", Icons.Rounded.SelectAll) { menu = false; view.selectAll() },
                // The output between the last two marks, however far up the
                // scrollback it starts and whether or not it has finished.
                SheetAction("Copy the last output", Icons.Rounded.ContentCopy, subtitle = "Everything the last command printed") {
                    menu = false
                    if (!copyLastOutput()) Toast.makeText(context, "The last command printed nothing", Toast.LENGTH_SHORT).show()
                }.takeIf { marksPrompts },
                SheetAction("Snippets", Icons.Rounded.AutoAwesome) { menu = false; snippets = true },
                // A control byte rather than a keypress, unless the setting says
                // otherwise: see Settings.rawControlKeys for why the faithful
                // encoding is the wrong answer here.
                SheetAction("Send Ctrl+C", null) { menu = false; view.sendKey(KeyCode.Char('c'.code.toUInt()), ctrl = true) },
                SheetAction("Send Ctrl+D", null) { menu = false; view.sendKey(KeyCode.Char('d'.code.toUInt()), ctrl = true) },
                SheetAction("Hide keyboard", Icons.Rounded.KeyboardHide) { menu = false; view.hideKeyboard() },
            ),
        )
    }
}

/**
 * A run of terminal-menu entries under one heading.
 *
 * The entries a session has no use for arrive here as nulls and drop out, so a
 * group left with nothing in it takes its heading down with it rather than
 * stranding one over empty space.
 */
private fun menuGroup(name: String, vararg actions: SheetAction?): List<SheetAction> =
    actions.filterNotNull().map { it.copy(section = name) }

/** How long the connection drawer stays up even when the connection was instant. */
private const val MIN_SHEET_MS = 1100L

/**
 * The two chevrons that walk the session from one prompt to the next.
 *
 * They sit at the right edge, where the scroll indicator is drawn, because
 * that is what they move — the prompt before this one is usually somewhere up
 * in the scrollback. Small and half transparent: this is beside the text, not
 * on top of it, and a session whose shell says nothing about its prompts never
 * shows them at all.
 */
@Composable
private fun PromptChevrons(onJump: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(end = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        listOf(true to Icons.Rounded.KeyboardArrowUp, false to Icons.Rounded.KeyboardArrowDown).forEach { (previous, icon) ->
            Box(
                Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.5f))
                    .clickable { onJump(previous) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    if (previous) "Previous prompt" else "Next prompt",
                    Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.inverseOnSurface,
                )
            }
        }
    }
}

/** Floating strip shown after tapping a URL or path in the terminal. */
@Composable
private fun LinkChip(
    link: TerminalView.Link?,
    onDismiss: () -> Unit,
    onOpen: (TerminalView.Link) -> Unit,
    onCopy: (TerminalView.Link) -> Unit,
    canBrowse: Boolean,
    modifier: Modifier = Modifier,
) {
    // Goes away on its own; a fresh tap replaces it.
    LaunchedEffect(link) { if (link != null) { kotlinx.coroutines.delay(7000); onDismiss() } }
    var shown by remember { mutableStateOf(link) }
    if (link != null) shown = link
    AnimatedVisibility(visible = link != null, modifier = modifier, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
        val l = shown ?: return@AnimatedVisibility
        Row(
            Modifier
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.inverseSurface)
                .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (l.isUrl) Icons.Rounded.Link else Icons.Rounded.Folder, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.inverseOnSurface)
            Spacer(Modifier.width(8.dp))
            Text(
                l.text, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall, fontFamily = MonoFamily,
                color = MaterialTheme.colorScheme.inverseOnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(4.dp))
            if (l.isUrl || canBrowse) {
                TextButton(onClick = { onOpen(l) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.inversePrimary)) {
                    Text(if (l.isUrl) "Open" else "Files")
                }
            }
            TextButton(onClick = { onCopy(l) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.inversePrimary)) { Text("Copy") }
        }
    }
}

/**
 * The commands this host's history offers for what is being typed.
 *
 * A short floating list rather than a bar across the screen: it is a second
 * opinion about the gray hint after the cursor, so it appears near the bottom
 * of the terminal, only after typing has paused, and only when there is more
 * than one thing to say.
 */
@Composable
private fun SuggestionList(
    suggestions: List<String>,
    typed: String,
    onChrome: Color,
    modifier: Modifier = Modifier,
    onPick: (String) -> Unit,
) {
    Surface(
        modifier = modifier.widthIn(max = 340.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            suggestions.take(5).forEach { command ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(command) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.History, null, Modifier.size(13.dp), tint = onChrome.copy(alpha = 0.45f))
                    Spacer(Modifier.width(8.dp))
                    // What was typed is already on the screen; the rest is what
                    // this row would add, so that is the part worth reading.
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(typed) }
                            append(command.removePrefix(typed))
                        },
                        style = CodeStyle.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
