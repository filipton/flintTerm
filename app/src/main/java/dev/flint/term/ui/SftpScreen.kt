package dev.flint.term.ui

import dev.flint.term.R
import androidx.compose.ui.res.stringResource
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.flint.term.App
import dev.flint.term.core.FileEntry
import dev.flint.term.core.SessionState
import dev.flint.term.core.SftpClient
import dev.flint.term.data.EditorChoice
import dev.flint.term.data.Host
import dev.flint.term.data.Protocol
import dev.flint.term.session.TerminalSession
import dev.flint.term.transfer.ExternalEdit
import dev.flint.term.transfer.Transfer
import dev.flint.term.transfer.TransferKind
import dev.flint.term.transfer.TransferService
import dev.flint.term.transfer.TransferStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class SortBy(val label: String) { NAME("Name"), SIZE("Size"), MODIFIED("Modified") }

@Composable
fun SftpScreen(nav: NavController, sessionId: String, initialPath: String? = null) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val session = remember(sessionId) { app.sessions.get(sessionId) }
    if (session == null) {
        LaunchedEffect(Unit) { nav.popBackStack() }
        return
    }
    // Opening a host's files gives them a tab of their own, so stepping back to
    // the terminal no longer means losing the folder you were in.
    LaunchedEffect(sessionId) { Workspace.openFiles(sessionId) }
    WorkspaceFrame(nav, session, Pane.Files(sessionId)) { modifier ->
        SftpPane(nav, session, initialPath, modifier)
    }
}

/**
 * The file browser itself, which is the same thing wherever it is drawn: on its
 * own route, or in half a split screen next to the terminal it belongs to.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SftpPane(
    nav: NavController,
    session: TerminalSession,
    initialPath: String? = null,
    modifier: Modifier = Modifier,
    /**
     * Whether Back walks this pane up a folder. False for the pane beside
     * another one: Back there means "leave the screen I am working in", and a
     * file browser quietly eating it would be a surprise.
     */
    handleBack: Boolean = true,
) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val settings by app.store.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var client by remember { mutableStateOf<SftpClient?>(null) }
    var path by rememberSaveable { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var sortBy by remember { mutableStateOf(SortBy.NAME) }
    val transfers by app.transfers.transfers.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var pendingDownload by remember { mutableStateOf<FileEntry?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var goTo by remember { mutableStateOf(false) }
    var addSheet by remember { mutableStateOf(false) }
    var sortSheet by remember { mutableStateOf(false) }
    var entrySheet by remember { mutableStateOf<FileEntry?>(null) }
    var renaming by remember { mutableStateOf<FileEntry?>(null) }
    var viewing by remember { mutableStateOf<Pair<String, String>?>(null) }
    // What is being sent to another host, and where to once a host is chosen.
    var copySource by remember { mutableStateOf<FileEntry?>(null) }
    var copyHost by remember { mutableStateOf<Host?>(null) }
    val hosts by app.store.hosts.collectAsStateWithLifecycle()

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    /**
     * What a tap on a text file does. The built-in editor is the quick way in,
     * so it is what a tap gets unless the setting says another app should have
     * the file — and either way "Open in…" is still in the sheet.
     */
    fun edit(e: FileEntry) {
        if (settings.editor == EditorChoice.BUILT_IN) nav.navigate(Routes.edit(session.id, e.path))
        else ExternalEdit.open(context, app.transfers, session, e, edit = true) { toast(it) }
    }

    fun refresh(target: String = path) {
        val c = client ?: return
        loading = true
        scope.launch(Dispatchers.IO) {
            val result = runCatching { c.list(target) }
            withContext(Dispatchers.Main) {
                result.onSuccess { entries = it; path = target; error = null }.onFailure { error = it.message }
                loading = false
            }
        }
    }

    LaunchedEffect(session) {
        // The screen can be opened straight from the host list; wait for the session first.
        val state = session.state.first { it !is SessionState.Connecting }
        if (state !is SessionState.Connected) {
            error = (state as? SessionState.Disconnected)?.error ?: "Not connected"
            loading = false
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            val result = runCatching {
                val c = session.core.openSftp()
                // Start where a tapped path points: the directory itself, or the file's folder.
                val target = initialPath?.let { p ->
                    val abs = if (p.startsWith("~")) c.home().trimEnd('/') + p.removePrefix("~") else p
                    listOf(abs, abs.substringBeforeLast('/').ifEmpty { "/" }).firstOrNull { runCatching { c.list(it) }.isSuccess }
                }
                // Coming back from another screen, resume the folder we were in.
                    ?: path.takeIf { it.isNotEmpty() && runCatching { c.list(it) }.isSuccess }
                    // Otherwise where the shell of this session is, if it says:
                    // opening the files of a terminal that is deep in a tree
                    // and landing at the top of it is a walk back down for
                    // nothing. It may be a directory this account cannot list
                    // (the shell is another user's, or it has since gone), so
                    // the home directory is still the answer if it fails.
                    ?: session.cwd.value?.takeIf { runCatching { c.list(it) }.isSuccess }
                val home = c.home()
                // The server's own answer, which beats the terminal's guess at
                // it — see TerminalSession.home.
                session.noteHome(home)
                c to (target ?: home)
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { (c, home) -> client = c; refresh(home) }
                    .onFailure { error = it.message; loading = false }
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { client?.let { c -> scope.launch(Dispatchers.IO) { runCatching { c.shutdown() } } } }
    }

    val saveTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val entry = pendingDownload ?: return@rememberLauncherForActivityResult
        pendingDownload = null
        if (uri != null) {
            app.transfers.downloadFile(session, entry, uri)
            toast("Downloading ${entry.name}")
        }
    }
    val saveDirTo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val entry = pendingDownload ?: return@rememberLauncherForActivityResult
        pendingDownload = null
        if (tree != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            app.transfers.downloadDirectory(session, entry, tree)
            toast("Downloading folder ${entry.name}")
        }
    }
    val pickUpload = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { app.transfers.upload(session, it, path) }
        if (uris.isNotEmpty()) toast(if (uris.size == 1) "Uploading…" else "Uploading ${uris.size} files…")
    }

    val finishedUploads = transfers.count { it.kind == TransferKind.UPLOAD && it.status == TransferStatus.DONE }
    LaunchedEffect(finishedUploads) { if (finishedUploads > 0 && client != null) refresh() }
    LaunchedEffect(path) { listState.scrollToItem(0) }

    BackHandler(enabled = handleBack && path != "/" && path.isNotEmpty() && viewing == null) { refresh(parent(path)) }

    val shown = remember(entries, sortBy, settings.showHiddenFiles) {
        val visible = if (settings.showHiddenFiles) entries else entries.filterNot { it.name.startsWith(".") }
        val cmp: Comparator<FileEntry> = when (sortBy) {
            SortBy.NAME -> compareBy { it.name.lowercase() }
            SortBy.SIZE -> compareByDescending { it.size }
            SortBy.MODIFIED -> compareByDescending { it.mtime ?: 0u }
        }
        visible.sortedWith(compareByDescending<FileEntry> { it.isDir }.then(cmp))
    }

    Box(modifier.background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            // The folder you are in and the three things done to a listing, on
            // one line: a pane has no room for a header of its own.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Breadcrumbs(path, onNavigate = { refresh(it) }, onGoTo = { goTo = true }) }
                TransfersAction(nav)
                IconButton(onClick = { sortSheet = true }, enabled = client != null) { Icon(Icons.Rounded.Sort, "Sort") }
                IconButton(onClick = { refresh() }, enabled = client != null) { Icon(Icons.Rounded.Refresh, "Refresh") }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) else Spacer(Modifier.height(4.dp))
            if (client != null && App.pendingShare.isNotEmpty()) {
                val pending = App.pendingShare.toList()
                val names = remember(pending) { pending.map { app.transfers.nameFor(it) } }
                ShareUploadBar(
                    names = names,
                    path = path,
                    onUpload = {
                        pending.forEach { app.transfers.upload(session, it, path) }
                        App.pendingShare.clear()
                    },
                    onCancel = { App.pendingShare.clear() },
                )
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 100.dp)) {
                if (!loading && shown.isEmpty() && error == null && client != null) {
                    item { EmptyState(Icons.Rounded.FolderOpen, stringResource(R.string.sftpscreen_empty_folder), stringResource(R.string.sftpscreen_upload_something_with_the_button)) }
                }
                items(shown, key = { it.path }) { e ->
                    FileRow(
                        e,
                        onClick = {
                            if (e.isDir) refresh(e.path)
                            else if (editableInApp(e)) edit(e)
                            else entrySheet = e
                        },
                        onLong = { entrySheet = e },
                    )
                }
            }
        }
        if (client != null) {
            FloatingActionButton(
                onClick = { addSheet = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) { Icon(Icons.Rounded.Add, "Add") }
        }
    }

    // ---- sheets & dialogs ---------------------------------------------------
    entrySheet?.let { e ->
        val actions = buildList {
            if (e.isDir) {
                add(SheetAction(stringResource(R.string.sftpscreen_open), Icons.Rounded.FolderOpen) { entrySheet = null; refresh(e.path) })
                add(SheetAction(stringResource(R.string.sftpscreen_download_folder), Icons.Rounded.Download) { entrySheet = null; pendingDownload = e; saveDirTo.launch(null) })
            } else {
                add(SheetAction(stringResource(R.string.sftpscreen_download), Icons.Rounded.Download, subtitle = humanBytes(e.size.toLong())) { entrySheet = null; pendingDownload = e; saveTo.launch(e.name) })
                if (e.size < 512_000u) {
                    add(SheetAction(stringResource(R.string.sftpscreen_view), Icons.Rounded.Visibility) {
                        entrySheet = null
                        scope.launch(Dispatchers.IO) {
                            val r = runCatching { client!!.readText(e.path, 512_000u) }
                            withContext(Dispatchers.Main) { r.onSuccess { viewing = e.name to it }.onFailure { toast(it.message ?: "failed") } }
                        }
                    })
                }
            }
            if (editableInApp(e)) {
                add(SheetAction(stringResource(R.string.sftpscreen_edit), Icons.Rounded.Edit, subtitle = stringResource(R.string.sftpscreen_here_with_highlighting)) {
                    entrySheet = null
                    nav.navigate(Routes.edit(session.id, e.path))
                })
            }
            add(SheetAction(stringResource(R.string.sftpscreen_copy_to_host), Icons.Rounded.SwapHoriz, subtitle = stringResource(R.string.sftpscreen_straight_to_another_server)) {
                entrySheet = null
                copySource = e
            })
            if (!e.isDir && e.size < 50_000_000u) {
                add(SheetAction(stringResource(R.string.sftpscreen_edit_in), Icons.Rounded.Edit, subtitle = stringResource(R.string.sftpscreen_changes_are_uploaded_back_automatically)) {
                    entrySheet = null
                    ExternalEdit.open(context, app.transfers, session, e, edit = true) { toast(it) }
                })
                add(SheetAction(stringResource(R.string.sftpscreen_open_in), Icons.Rounded.OpenInNew) {
                    entrySheet = null
                    ExternalEdit.open(context, app.transfers, session, e, edit = false) { toast(it) }
                })
            }
            add(SheetAction(stringResource(R.string.sftpscreen_copy_path), Icons.Rounded.ContentCopy) {
                entrySheet = null
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("path", e.path))
                toast("Path copied")
            })
            add(SheetAction(stringResource(R.string.sftpscreen_rename), Icons.Rounded.DriveFileRenameOutline) { entrySheet = null; renaming = e })
            add(SheetAction(stringResource(R.string.sftpscreen_delete), Icons.Rounded.Delete, danger = true) {
                entrySheet = null
                scope.launch(Dispatchers.IO) {
                    val r = runCatching { client!!.remove(e.path, e.isDir && !e.isSymlink) }
                    withContext(Dispatchers.Main) { r.onFailure { toast(it.message ?: "failed") }; refresh() }
                }
            })
        }
        ActionSheet(
            onDismiss = { entrySheet = null },
            header = {
                Row(Modifier.padding(horizontal = 24.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val (icon, tint) = iconForEntry(e)
                    IconTile(icon, tint, 40, 12)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(e.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(details(e), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(8.dp))
            },
            actions = actions,
        )
    }
    copySource?.let { e ->
        val dest = copyHost
        if (dest == null) {
            // Telnet hosts have no SFTP, and copying a host onto itself is a rename.
            val targets = remember(hosts, session.host?.id) {
                hosts.filter { it.protocol == Protocol.SSH && it.id != session.host?.id }
            }
            ActionSheet(
                onDismiss = { copySource = null },
                title = stringResource(R.string.sftpscreen_copy_to),
                subtitle = e.name,
                actions = if (targets.isEmpty()) {
                    listOf(SheetAction(stringResource(R.string.sftpscreen_no_other_ssh_hosts_saved), Icons.Rounded.Dns) { copySource = null })
                } else {
                    targets.map { h -> SheetAction(h.displayName, iconFor(h.icon), subtitle = h.target) { copyHost = h } }
                },
            )
        } else {
            DestinationPicker(
                host = dest,
                what = e.name,
                owner = scope,
                onDismiss = { copySource = null; copyHost = null },
                onPick = { dir ->
                    copySource = null
                    copyHost = null
                    app.transfers.copyToHost(session, e, dest, dir)
                    toast("Copying ${e.name} to ${dest.displayName}")
                },
            )
        }
    }
    if (addSheet) {
        ActionSheet(
            onDismiss = { addSheet = false },
            title = stringResource(R.string.sftpscreen_add),
            actions = listOf(
                SheetAction(stringResource(R.string.sftpscreen_upload_files), Icons.Rounded.Upload, subtitle = stringResource(R.string.sftpscreen_into_fmt, path)) { addSheet = false; pickUpload.launch(arrayOf("*/*")) },
                SheetAction(stringResource(R.string.sftpscreen_new_folder), Icons.Rounded.CreateNewFolder) { addSheet = false; newFolder = true },
                SheetAction(stringResource(R.string.sftpscreen_go_to_path), Icons.Rounded.FolderOpen) { addSheet = false; goTo = true },
            ),
        )
    }
    if (sortSheet) {
        ActionSheet(
            onDismiss = { sortSheet = false },
            title = stringResource(R.string.sftpscreen_sort_by),
            actions = SortBy.entries.map { s -> SheetAction(s.label + if (s == sortBy) "   ✓" else "") { sortBy = s; sortSheet = false } } +
                SheetAction(if (settings.showHiddenFiles) stringResource(R.string.sftpscreen_hide_dotfiles) else stringResource(R.string.sftpscreen_show_dotfiles), Icons.Rounded.Visibility) {
                    app.store.updateSettings { it.copy(showHiddenFiles = !it.showHiddenFiles) }; sortSheet = false
                },
        )
    }
    if (goTo) {
        var target by remember { mutableStateOf(path) }
        AlertDialog(
            onDismissRequest = { goTo = false },
            title = { Text(stringResource(R.string.sftpscreen_go_to_path_2)) },
            text = { Field(target, { target = it }, stringResource(R.string.sftpscreen_remote_path), mono = true) },
            confirmButton = {
                Button(enabled = target.isNotBlank(), onClick = {
                    goTo = false
                    val t = target.trim().let { if (it.startsWith("~")) it.replaceFirst("~", "") else it }
                    scope.launch(Dispatchers.IO) {
                        val resolved = runCatching { client!!.canonicalize(t.ifEmpty { "." }) }.getOrElse { t }
                        withContext(Dispatchers.Main) { refresh(resolved) }
                    }
                }) { Text("Go") }
            },
            dismissButton = { TextButton(onClick = { goTo = false }) { Text(stringResource(R.string.sftpscreen_cancel)) } },
        )
    }
    if (newFolder) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newFolder = false },
            title = { Text(stringResource(R.string.sftpscreen_new_folder)) },
            text = { Field(name, { name = it }, "Name") },
            confirmButton = {
                Button(enabled = name.isNotBlank() && !name.contains('/'), onClick = {
                    newFolder = false
                    scope.launch(Dispatchers.IO) {
                        val r = runCatching { client!!.mkdir(join(path, name.trim())) }
                        withContext(Dispatchers.Main) { r.onFailure { toast(it.message ?: "failed") }; refresh() }
                    }
                }) { Text(stringResource(R.string.sftpscreen_create)) }
            },
            dismissButton = { TextButton(onClick = { newFolder = false }) { Text(stringResource(R.string.sftpscreen_cancel)) } },
        )
    }
    renaming?.let { e ->
        var name by remember { mutableStateOf(e.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.sftpscreen_rename)) },
            text = { Field(name, { name = it }, stringResource(R.string.sftpscreen_new_name)) },
            confirmButton = {
                Button(enabled = name.isNotBlank() && name != e.name, onClick = {
                    renaming = null
                    scope.launch(Dispatchers.IO) {
                        val r = runCatching { client!!.rename(e.path, join(parent(e.path), name.trim())) }
                        withContext(Dispatchers.Main) { r.onFailure { toast(it.message ?: "failed") }; refresh() }
                    }
                }) { Text(stringResource(R.string.sftpscreen_rename)) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.sftpscreen_cancel)) } },
        )
    }
    viewing?.let { (name, text) ->
        AlertDialog(
            onDismissRequest = { viewing = null },
            title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                SelectionContainer {
                    Text(text, style = CodeStyle.copy(fontSize = 12.sp), modifier = Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()))
                }
            },
            confirmButton = { TextButton(onClick = { viewing = null }) { Text(stringResource(R.string.sftpscreen_close)) } },
        )
    }
}

@Composable
private fun Breadcrumbs(path: String, onNavigate: (String) -> Unit, onGoTo: () -> Unit) {
    val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState(), reverseScrolling = true).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Crumb("/", selected = parts.isEmpty()) { onNavigate("/") }
        var acc = ""
        parts.forEachIndexed { i, p ->
            acc += "/$p"
            val target = acc
            Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 2.dp))
            Crumb(p, selected = i == parts.lastIndex) { if (i == parts.lastIndex) onGoTo() else onNavigate(target) }
        }
    }
}

@Composable
private fun Crumb(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = CodeStyle.copy(fontSize = 13.sp),
        color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        maxLines = 1,
    )
}

/** The headless connection the picker browses, kept together so it can be closed as one. */
private class Destination {
    var session: TerminalSession? = null
    var sftp: SftpClient? = null
    /** Set the moment the dialog goes away, so a connection that lands later is not left running. */
    @Volatile var closed = false

    fun close() {
        closed = true
        runCatching { sftp?.shutdown() }
        runCatching { session?.destroy() }
        sftp = null
        session = null
    }
}

/**
 * Choose a folder on another host by looking at it: a headless SFTP session is
 * opened there and its directories are browsed with the same breadcrumbs and
 * rows as the main screen.
 *
 * [owner] is the screen's scope rather than the dialog's, because closing the
 * connection is the last thing that happens and a scope of this composable's
 * own is already cancelled by then.
 */
@Composable
private fun DestinationPicker(
    host: Host,
    what: String,
    owner: CoroutineScope,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val app = LocalContext.current.applicationContext as App
    val settings by app.store.settings.collectAsStateWithLifecycle()
    val dest = remember(host.id) { Destination() }
    var ready by remember { mutableStateOf(false) }
    var path by remember { mutableStateOf("") }
    var dirs by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    fun open(target: String) {
        val c = dest.sftp ?: return
        busy = true
        owner.launch(Dispatchers.IO) {
            val result = runCatching { c.list(target) }
            withContext(Dispatchers.Main) {
                result.onSuccess { list -> dirs = list.filter { it.isDir }; path = target; error = null }
                    .onFailure { error = it.message }
                busy = false
            }
        }
    }

    LaunchedEffect(host.id) {
        val result = runCatching { app.sessions.openSftpSession(host) }
        result.onSuccess { (s, c) ->
            dest.session = s
            dest.sftp = c
            if (dest.closed) {
                withContext(Dispatchers.IO) { dest.close() }
                return@onSuccess
            }
            ready = true
            open(withContext(Dispatchers.IO) { runCatching { c.home() }.getOrDefault("/") })
        }.onFailure {
            error = it.message ?: "could not connect"
            busy = false
        }
    }
    DisposableEffect(dest) {
        onDispose {
            dest.closed = true
            owner.launch(Dispatchers.IO) { dest.close() }
        }
    }

    val shown = remember(dirs, settings.showHiddenFiles) {
        if (settings.showHiddenFiles) dirs else dirs.filterNot { it.name.startsWith(".") }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sftpscreen_copy_to_fmt, host.displayName), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.fillMaxWidth().height(320.dp)) {
                Text(
                    what,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Breadcrumbs(path, onNavigate = { open(it) }, onGoTo = {})
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp))
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    if (path.length > 1) item { UpRow { open(parent(path)) } }
                    items(shown, key = { it.path }) { d -> FileRow(d, onClick = { open(d.path) }, onLong = { open(d.path) }) }
                    if (ready && !busy && shown.isEmpty() && path.length > 1) {
                        item { Text(stringResource(R.string.sftpscreen_no_folders_here), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp)) }
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = ready && !busy && path.isNotEmpty(), onClick = { onPick(path) }) { Text(stringResource(R.string.sftpscreen_copy_here)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.sftpscreen_cancel)) } },
    )
}

@Composable
private fun UpRow(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(Icons.Rounded.FolderOpen, Tone.Blue, size = 38, corner = 11)
        Spacer(Modifier.width(14.dp))
        Text("..", style = CodeStyle.copy(fontSize = 15.sp))
    }
}

private fun iconForEntry(e: FileEntry): Pair<ImageVector, Color> {
    if (e.isSymlink) return Icons.Rounded.Link to Tone.Cyan
    if (e.isDir) return Icons.Rounded.Folder to Tone.Blue
    return when (e.name.substringAfterLast('.', "").lowercase()) {
        "png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "heic" -> Icons.Rounded.Image to Tone.Pink
        "mp4", "mkv", "mov", "avi", "webm", "mp3", "flac", "wav", "ogg" -> Icons.Rounded.Movie to Tone.Purple
        "zip", "tar", "gz", "tgz", "bz2", "xz", "zst", "7z", "rar", "deb", "rpm", "apk" -> Icons.Rounded.Archive to Tone.Amber
        "sh", "py", "rs", "kt", "kts", "js", "ts", "go", "c", "h", "cpp", "java", "rb", "toml", "yaml", "yml", "json", "xml", "conf", "cfg", "ini" -> Icons.Rounded.Code to Tone.Mint
        "txt", "md", "log", "pdf", "doc", "docx" -> Icons.Rounded.Description to Tone.TextMuted
        else -> Icons.Rounded.InsertDriveFile to Tone.TextMuted
    }
}

/** Parent directory of an absolute remote path; "/" is its own parent. */
private fun parent(path: String): String = path.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }

private fun join(dir: String, name: String) = if (dir.endsWith("/")) dir + name else "$dir/$name"

private fun fmtDate(mtime: UInt): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(mtime.toLong() * 1000L))

/** The `rwxr-xr-x` part of a POSIX mode. */
private fun perms(mode: UInt): String {
    val bits = mode.toInt()
    val sb = StringBuilder(9)
    for (shift in intArrayOf(6, 3, 0)) {
        val v = (bits shr shift) and 0b111
        sb.append(if (v and 0b100 != 0) 'r' else '-')
        sb.append(if (v and 0b010 != 0) 'w' else '-')
        sb.append(if (v and 0b001 != 0) 'x' else '-')
    }
    return sb.toString()
}

private fun details(e: FileEntry): String = buildString {
    if (!e.isDir) append(humanBytes(e.size.toLong())).append("   ")
    e.mtime?.let { append(fmtDate(it)) }
    e.permissions?.let { append("   ").append(perms(it)) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(e: FileEntry, onClick: () -> Unit, onLong: () -> Unit) {
    val (icon, tint) = iconForEntry(e)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, tint, size = 38, corner = 11)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(e.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (e.name.startsWith(".")) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            Text(details(e), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** Compact prompt naming the shared files and where they are about to land. */
@Composable
private fun ShareUploadBar(names: List<String?>, path: String, onUpload: () -> Unit, onCancel: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Upload, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                shareLabel(names),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.sftpscreen_into) + path.trimEnd('/').substringAfterLast('/').ifEmpty { path.ifEmpty { "…" } },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onUpload) {
            Text(stringResource(R.string.sftpscreen_upload), color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
        }
        IconButton(onClick = onCancel) {
            Icon(Icons.Rounded.Close, stringResource(R.string.sftpscreen_cancel_upload), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

/** "notes.txt", "a.txt, b.png", "a.txt, b.png  +3", or a plain count when the names are unknown. */
fun shareLabel(names: List<String?>): String {
    val known = names.filterNotNull()
    if (known.isEmpty()) return if (names.size == 1) "1 file" else "${names.size} files"
    val shown = known.take(2).joinToString(", ")
    val rest = names.size - minOf(known.size, 2)
    return if (rest > 0) "$shown  +$rest" else shown
}
