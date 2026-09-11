package dev.flint.term.ui

import androidx.compose.ui.graphics.toArgb
import dev.flint.term.R
import androidx.compose.ui.res.stringResource
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import dev.flint.term.App
import dev.flint.term.terminal.Palettes
import dev.flint.term.terminal.TerminalView
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import dev.flint.term.core.SessionState
import dev.flint.term.data.fontSize
import dev.flint.term.session.TerminalSession
import dev.flint.term.session.WorkingDirectory

/**
 * One thing the workspace can show: a session's terminal, its files, or how the
 * machine itself is doing.
 *
 * The file browser used to be a screen you left the terminal for, which is the
 * wrong shape for the job — copying a path out of a listing and into a command
 * meant walking back and forth. All three are tabs of the same session now, and
 * on a screen with room they can be open beside each other.
 */
sealed interface Pane {
    val sessionId: String

    data class Term(override val sessionId: String) : Pane
    data class Files(override val sessionId: String) : Pane
    data class Server(override val sessionId: String) : Pane
}

/**
 * What is open, across the screens that draw it.
 *
 * Sessions live in the SessionManager and the route says which one you are
 * looking at; this is only the part that neither of them knows — which sessions
 * have their files open as a tab, and what is being shown alongside.
 */
object Workspace {
    /** Sessions whose file browser is open as a tab of its own. */
    val files = mutableStateListOf<String>()

    /** Sessions whose server status is open as a tab of its own. */
    val servers = mutableStateListOf<String>()

    /** The pane shown beside the one you are in, if any. */
    var beside by mutableStateOf<Pane?>(null)

    /**
     * What is typed goes to both halves of the split.
     *
     * Two shells being brought to the same state is the case for it — the same
     * command on a pair of servers, watched side by side rather than run twice
     * — and it only means anything while both panes are terminals.
     */
    var broadcast by mutableStateOf(false)

    /** Where the split sits, as a share of the space given to the first pane. */
    var splitAt by mutableStateOf(0.5f)

    /** One above the other rather than side by side; null follows the window's shape. */
    var stacked by mutableStateOf<Boolean?>(null)

    fun openFiles(sessionId: String) {
        if (sessionId !in files) files.add(sessionId)
    }

    fun openServer(sessionId: String) {
        if (sessionId !in servers) servers.add(sessionId)
    }

    /** A session that has ended takes its tabs and its half of the split with it. */
    fun forget(sessionId: String) {
        files.remove(sessionId)
        servers.remove(sessionId)
        if (beside?.sessionId == sessionId) closeBeside()
    }

    fun close(pane: Pane) {
        if (beside == pane) closeBeside()
        if (pane is Pane.Files) files.remove(pane.sessionId)
        if (pane is Pane.Server) servers.remove(pane.sessionId)
    }

    /**
     * The second pane goes, and broadcasting with it: coming back to a split
     * later and finding that what you type is going somewhere else as well is
     * the one surprise this feature must not spring.
     */
    fun closeBeside() {
        beside = null
        broadcast = false
    }

    /** Whether anything but the terminals themselves is open. */
    val hasTabs: Boolean get() = files.isNotEmpty() || servers.isNotEmpty()
}

/**
 * The open panes as a thin strip of tabs.
 *
 * Names, not badges: a row of icons says nothing about which session is which,
 * and the strip is only there to answer that. A session's files sit directly
 * after it, so the pair reads as one host. The tab you are in is the one with a
 * background, and only it carries a close button — closing a tab you cannot see
 * is not something to do by accident.
 */
@Composable
fun WorkspaceTabs(
    sessions: List<TerminalSession>,
    current: Pane,
    onChrome: Color,
    onChromeMuted: Color,
    onOpen: (Pane) -> Unit,
    onClose: (Pane) -> Unit,
    /** A long press on a terminal tab, where the screen has somewhere to rename it. */
    onRename: ((TerminalSession) -> Unit)? = null,
) {
    val tabs = remember(sessions, Workspace.files.toList(), Workspace.servers.toList()) {
        sessions.flatMap { s ->
            listOfNotNull(
                Pane.Term(s.id) to s,
                (Pane.Files(s.id) to s).takeIf { s.id in Workspace.files },
                (Pane.Server(s.id) to s).takeIf { s.id in Workspace.servers },
            )
        }
    }
    val scroll = rememberLazyListState()
    // Follow the current tab: a switch by swipe or from the menu can pick one
    // that is scrolled off the end.
    LaunchedEffect(current, tabs.size) {
        val at = tabs.indexOfFirst { it.first == current }
        if (at >= 0) runCatching { scroll.animateScrollToItem(at) }
    }
    val manyHosts = sessions.size > 1
    LazyRow(
        state = scroll,
        modifier = Modifier.fillMaxWidth().height(30.dp),
        contentPadding = PaddingValues(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(tabs, key = { (pane, _) -> pane.toString() }) { (pane, session) ->
            val selected = pane == current
            val state by session.state.collectAsStateWithLifecycle()
            val dead = state is SessionState.Disconnected
            val name by session.name.collectAsStateWithLifecycle()
            val cwd by session.cwd.collectAsStateWithLifecycle()
            val home by session.home.collectAsStateWithLifecycle()
            // Where the shell is, when it reports it: with one host open that is
            // all the tab has to say, and with several the name still has to
            // come first or the tabs are told apart by their folders alone. A
            // name somebody typed is never replaced by a folder.
            val dir = WorkingDirectory.shorten(cwd, home).takeIf { !session.renamed }
            val label = when (pane) {
                is Pane.Term -> when {
                    dir == null -> name
                    manyHosts -> "$name · $dir"
                    else -> dir
                }
                is Pane.Files -> if (manyHosts) stringResource(R.string.workspace_files, name) else "Files"
                is Pane.Server -> if (manyHosts) stringResource(R.string.workspace_server, name) else "Server"
            }
            val glyph = paneIcon(pane)
            // Which tabs the keys are going to, while typing is broadcast: the
            // pane being worked in and the one beside it, both terminals.
            val both = Workspace.broadcast && current is Pane.Term && Workspace.beside is Pane.Term &&
                (pane == current || pane == Workspace.beside)
            Row(
                Modifier
                    .height(26.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) onChrome.copy(alpha = 0.13f) else Color.Transparent)
                    // The tab is where the name is, so it is where renaming it
                    // belongs; the files and server tabs borrow their session's.
                    .combinedClickable(
                        onClick = { onOpen(pane) },
                        onLongClick = onRename?.let { rename -> { rename(session) } },
                    )
                    .padding(start = if (glyph != null) 7.dp else 10.dp, end = if (selected) 2.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (glyph != null) {
                    Icon(glyph, null, Modifier.size(12.dp), tint = if (selected) onChrome else onChromeMuted)
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        dead && pane is Pane.Term -> onChromeMuted.copy(alpha = 0.55f)
                        selected -> onChrome
                        else -> onChromeMuted
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 140.dp),
                )
                if (both) {
                    Spacer(Modifier.width(5.dp))
                    Text(
                        stringResource(R.string.workspace_both),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                            .padding(horizontal = 4.dp),
                    )
                }
                if (selected) {
                    IconButton(onClick = { onClose(pane) }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.workspace_close, label), Modifier.size(13.dp), tint = onChromeMuted)
                    }
                }
            }
        }
    }
}

/** What marks a tab as something other than the terminal; the terminal needs no badge. */
private fun paneIcon(pane: Pane) = when (pane) {
    is Pane.Term -> null
    is Pane.Files -> Icons.Rounded.Folder
    is Pane.Server -> Icons.Rounded.Speed
}

/**
 * The hinge of a foldable, if this device has one and it is currently in a
 * posture where it matters.
 *
 * A flat, fully unfolded tablet reports a hinge too; only a fold that actually
 * separates the two halves is worth laying out around, since that is the one a
 * pane would otherwise be draped across.
 */
@Composable
fun rememberFold(): FoldingFeature? {
    val activity = LocalContext.current.activity() ?: return null
    val tracker = remember(activity) { WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity) }
    val info by tracker.collectAsStateWithLifecycle(initialValue = null)
    return info?.displayFeatures
        ?.filterIsInstance<FoldingFeature>()
        ?.firstOrNull { it.isSeparating }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/**
 * Two panes with a divider that can be dragged, or dropped onto the fold.
 *
 * The divider is a handle rather than a hairline: it is dragged with a finger,
 * on a screen where every other pixel belongs to a terminal. When the device is
 * folded open the split goes to the hinge instead and stays there — a pane
 * lying across the crease is the one arrangement nobody wants.
 */
@Composable
fun SplitArea(
    stacked: Boolean,
    onChrome: Color,
    modifier: Modifier = Modifier,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    val fold = rememberFold()
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val density = LocalDensity.current
    BoxWithConstraints(modifier.onGloballyPositioned { bounds = it.boundsInWindow() }) {
        val total = if (stacked) constraints.maxHeight else constraints.maxWidth
        // A hinge is reported in window coordinates, so it only means something
        // here once this area knows where it is.
        val atFold = fold?.takeIf {
            bounds != Rect.Zero &&
                (it.orientation == FoldingFeature.Orientation.HORIZONTAL) == stacked
        }?.let {
            val edge = if (stacked) it.bounds.centerY() - bounds.top else it.bounds.centerX() - bounds.left
            (edge / total).takeIf { f -> f > 0.1f && f < 0.9f }
        }
        val fraction = atFold ?: Workspace.splitAt
        val handle = with(density) { 10.dp.toPx() }
        val firstSize = with(density) { (total * fraction - handle / 2).coerceAtLeast(0f).toDp() }
        val drag = Modifier.pointerInput(stacked, total, atFold != null) {
            if (atFold != null) return@pointerInput
            detectDragGestures { change, amount ->
                change.consume()
                val moved = if (stacked) amount.y else amount.x
                Workspace.splitAt = (Workspace.splitAt + moved / total).coerceIn(0.2f, 0.8f)
            }
        }
        val grip = @Composable {
            Box(
                Modifier
                    .then(if (stacked) Modifier.fillMaxWidth().height(10.dp) else Modifier.fillMaxHeight().width(10.dp))
                    .then(drag),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .then(if (stacked) Modifier.width(36.dp).height(3.dp) else Modifier.height(36.dp).width(3.dp))
                        .clip(RoundedCornerShape(2.dp))
                        .background(onChrome.copy(alpha = if (atFold != null) 0.15f else 0.35f)),
                )
            }
        }
        if (stacked) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(firstSize)) { first() }
                grip()
                Box(Modifier.fillMaxSize()) { second() }
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxHeight().width(firstSize)) { first() }
                grip()
                Box(Modifier.fillMaxSize()) { second() }
            }
        }
    }
}

/**
 * The colors the chrome around a pane takes from that pane's own color scheme.
 *
 * Every row the bars take is a row of terminal nobody can read, so they are
 * kept thin — and they blend with the session's background rather than the
 * app's surface, so the screen reads as one thing rather than a terminal in a
 * frame.
 */
data class Chrome(val terminal: Color, val bar: Color, val on: Color, val muted: Color)

@Composable
fun chromeFor(vararg theme: String?): Chrome {
    // Re-resolved when the catalog lands or a custom scheme goes away.
    val catalog by dev.flint.term.data.Schemes.catalog.collectAsStateWithLifecycle()
    val custom by dev.flint.term.data.Schemes.custom.collectAsStateWithLifecycle()
    val palette = remember(theme.toList(), catalog, custom) { Palettes.forTheme(*theme) }
    val bg = Color(0xFF000000.toInt() or palette.background.toInt())
    val dark = bg.luminance() < 0.5f
    val bar = if (dark) lerpColor(bg, Color.White, 0.06f) else lerpColor(bg, Color.Black, 0.05f)
    val on = if (dark) Color(0xFFE8ECF4) else Color(0xFF151A26)
    return Chrome(bg, bar, on, on.copy(alpha = 0.6f))
}

internal fun lerpColor(a: Color, b: Color, t: Float) = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f,
)

/**
 * The pane sharing the screen, under a line of its own that says which it is.
 *
 * It is a real pane, not a preview: the terminal in it takes the keyboard when
 * it is tapped. What it does not have is the menus and the key bar, which
 * belong to whichever pane you are actually working in — so "swap" is one tap
 * away, and moves this one into that place.
 */
@Composable
fun PaneBeside(
    pane: Pane,
    nav: NavController,
    modifier: Modifier = Modifier,
    onSwap: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val session = app.sessions.get(pane.sessionId)
    val settings by app.store.settings.collectAsStateWithLifecycle()
    if (session == null) {
        LaunchedEffect(pane) { Workspace.closeBeside() }
        return
    }
    val chrome = chromeFor(session.host?.theme, settings.theme)
    Column(modifier.background(chrome.terminal)) {
        Row(
            Modifier.fillMaxWidth().background(chrome.bar).height(26.dp).padding(start = 10.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            paneIcon(pane)?.let {
                Icon(it, null, Modifier.size(12.dp), tint = chrome.muted)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                session.label,
                style = MaterialTheme.typography.labelMedium,
                color = chrome.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSwap, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Rounded.SwapHoriz, stringResource(R.string.workspace_work_in_this_pane), Modifier.size(14.dp), tint = chrome.muted)
            }
            IconButton(onClick = { Workspace.closeBeside() }, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Rounded.Close, stringResource(R.string.workspace_close_this_pane), Modifier.size(13.dp), tint = chrome.muted)
            }
        }
        when (pane) {
            is Pane.Term -> SecondaryTerminal(session, chrome, settings, Modifier.fillMaxSize())
            is Pane.Files -> SftpPane(nav, session, null, Modifier.fillMaxSize(), handleBack = false)
            is Pane.Server -> ServerPane(nav, session, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun SecondaryTerminal(
    session: TerminalSession,
    chrome: Chrome,
    settings: dev.flint.term.data.Settings,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val app = context.applicationContext as dev.flint.term.App
    // Read the host back out of the store rather than off the session, which
    // holds the copy it was dialed with: a size changed since then still has to
    // reach the pane beside the terminal.
    val hosts by app.store.hosts.collectAsStateWithLifecycle()
    val fontSp = hosts.firstOrNull { it.id == session.host?.id }.fontSize(settings)
    val view = remember(session.id) {
        TerminalView(context).apply {
            this.session = session
            fontSizeSp = fontSp
        }
    }
    LaunchedEffect(fontSp) { if (view.fontSizeSp != fontSp) view.fontSizeSp = fontSp }
    LaunchedEffect(settings.fontFamily, settings.ligatures) { view.setFont(settings.fontFamily, settings.ligatures) }
    LaunchedEffect(chrome) {
        view.backgroundColorInt = chrome.terminal.toArgb()
        view.foregroundColorInt = chrome.on.toArgb()
    }
    DisposableEffect(view) { onDispose { view.session = null } }
    AndroidView(factory = { view }, modifier = modifier)
}

/**
 * A pane reached by its own route, with the tab strip above it and whatever is
 * open beside it.
 *
 * The terminal draws its own frame because it has a menu, a search bar and a
 * key row to fit into it; everything else that can be a pane comes through
 * here.
 */
@Composable
fun WorkspaceFrame(
    nav: NavController,
    session: TerminalSession,
    current: Pane,
    actions: @Composable () -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val settings by app.store.settings.collectAsStateWithLifecycle()
    val sessions by app.sessions.sessions.collectAsStateWithLifecycle()
    val chrome = chromeFor(session.host?.theme, settings.theme)
    val live = sessions.filterNot { it.isFinished }
    val state by session.state.collectAsStateWithLifecycle()
    val name by session.name.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(chrome.bar).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(40.dp).padding(start = 2.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", Modifier.size(19.dp), tint = chrome.on)
            }
            Spacer(Modifier.width(4.dp))
            StatusDot(state, size = 7)
            Spacer(Modifier.width(8.dp))
            Text(
                name,
                style = MaterialTheme.typography.titleSmall,
                color = chrome.on,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            actions()
        }
        if (live.size > 1 || Workspace.hasTabs) {
            if (settings.showSessionTabs) {
                WorkspaceTabs(
                    sessions = live,
                    current = current,
                    onChrome = chrome.on,
                    onChromeMuted = chrome.muted,
                    onOpen = { pane -> if (pane != current) nav.navigate(pane.route()) { popUpTo(Routes.HOSTS) } },
                    onClose = { pane -> closePane(app, nav, pane, current) },
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val beside = Workspace.beside
            if (beside == null || beside == current) {
                content(Modifier.fillMaxSize())
            } else {
                SplitArea(stacked = splitStacked(), onChrome = chrome.on, modifier = Modifier.fillMaxSize(),
                    first = { content(Modifier.fillMaxSize()) },
                    second = {
                        PaneBeside(beside, nav, Modifier.fillMaxSize()) {
                            Workspace.beside = current
                            nav.navigate(beside.route()) { popUpTo(Routes.HOSTS) }
                        }
                    },
                )
            }
        }
    }
}

/** Where a pane lives in the navigation graph. */
fun Pane.route(): String = when (this) {
    is Pane.Term -> Routes.terminal(sessionId)
    is Pane.Files -> Routes.sftp(sessionId)
    is Pane.Server -> Routes.server(sessionId)
}

/**
 * Closing a tab has to leave you somewhere. A file browser or a status pane
 * falls back to its own terminal; a terminal to the next session along, or to
 * the host list.
 */
fun closePane(app: App, nav: NavController, pane: Pane, current: Pane) {
    if (pane !is Pane.Term) {
        Workspace.close(pane)
        if (pane == current) nav.navigate(Routes.terminal(pane.sessionId)) { popUpTo(Routes.HOSTS) }
        return
    }
    val next = app.sessions.sessions.value.filterNot { it.isFinished }.firstOrNull { it.id != pane.sessionId }
    app.sessions.get(pane.sessionId)?.close()
    app.sessions.remove(pane.sessionId)
    Workspace.forget(pane.sessionId)
    if (pane == current) {
        if (next != null) nav.navigate(Routes.terminal(next.id)) { popUpTo(Routes.HOSTS) } else nav.popBackStack()
    }
}

/**
 * Side by side when there is width for two usable panes, stacked otherwise —
 * a phone held upright has neither the columns nor the reason to split across.
 */
@Composable
fun splitStacked(): Boolean {
    val configuration = LocalConfiguration.current
    return Workspace.stacked ?: (configuration.screenWidthDp < 720)
}
