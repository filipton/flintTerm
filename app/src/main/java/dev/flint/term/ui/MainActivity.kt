package dev.flint.term.ui

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import android.content.Intent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import dev.flint.term.session.Shortcuts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.flint.term.App
import dev.flint.term.security.KeyTransport
import dev.flint.term.security.SecurityKeyGate
import dev.flint.term.security.SecurityKeyPinRequest
import dev.flint.term.session.AuthPrompt
import dev.flint.term.session.HostKeyPrompt
import dev.flint.term.session.QuickConnect
import dev.flint.term.ui.settings.AboutSettings
import dev.flint.term.ui.settings.AppearanceSettings
import dev.flint.term.ui.settings.AutomationSettings
import dev.flint.term.ui.settings.BackupSettings
import dev.flint.term.ui.settings.ConnectionsSettings
import dev.flint.term.ui.settings.FilesSettings
import dev.flint.term.ui.settings.KeyboardSettings
import dev.flint.term.ui.settings.SecuritySettings
import dev.flint.term.ui.settings.SessionsSettings
import dev.flint.term.ui.settings.TerminalSettings

object Routes {
    const val HOSTS = "hosts"
    const val HOST_EDIT = "host/{id}"
    const val KEYS = "keys"
    const val KEY_ROTATION = "keys/{id}/replace"
    const val ACCOUNTS = "accounts"
    const val GROUPS = "groups"
    const val GROUP_EDIT = "group/{id}"
    const val TUNNELS = "tunnels"
    const val SNIPPETS = "snippets"
    const val BROADCAST = "broadcast"
    const val EXTRA_KEYS = "extrakeys"
    const val CHORDS = "chords"
    const val KNOWN_HOSTS = "knownhosts"
    const val TRANSFERS = "transfers"
    const val RECORDINGS = "recordings"
    const val SETTINGS = "settings"
    // Settings is an index; each section below it is a screen of its own.
    const val SETTINGS_APPEARANCE = "settings/appearance"
    const val SETTINGS_KEYBOARD = "settings/keyboard"
    const val SETTINGS_TERMINAL = "settings/terminal"
    const val SETTINGS_SESSIONS = "settings/sessions"
    const val SETTINGS_CONNECTIONS = "settings/connections"
    const val SETTINGS_FILES = "settings/files"
    const val SETTINGS_BACKUP = "settings/backup"
    const val SETTINGS_SECURITY = "settings/security"
    const val SETTINGS_AUTOMATION = "settings/automation"
    const val SETTINGS_ABOUT = "settings/about"
    const val LICENSES = "settings/about/licenses"
    const val HIGHLIGHTS = "settings/appearance/highlighting"
    const val THEME = "theme"
    const val GROUP_THEME = "theme/group/{id}"
    const val TERMINAL = "terminal/{sessionId}"
    const val SFTP = "sftp/{sessionId}?path={path}"
    const val EDIT = "edit/{sessionId}?path={path}"
    const val SERVER = "server/{sessionId}"
    const val FORWARDS = "forwards/{sessionId}"

    fun hostEdit(id: String) = "host/$id"
    fun keyRotation(id: String) = "keys/$id/replace"
    fun groupEdit(id: String) = "group/$id"
    fun groupTheme(id: String) = "theme/group/$id"
    fun terminal(sessionId: String) = "terminal/$sessionId"
    fun sftp(sessionId: String, path: String? = null) = if (path == null) "sftp/$sessionId" else "sftp/$sessionId?path=${android.net.Uri.encode(path)}"
    fun edit(sessionId: String, path: String) = "edit/$sessionId?path=${android.net.Uri.encode(path)}"
    fun server(sessionId: String) = "server/$sessionId"
    fun forwards(sessionId: String) = "forwards/$sessionId"
}

/** The bottom bar's destinations, in the order they are shown. */
private val TOP_LEVEL: Map<String, Pair<String, androidx.compose.ui.graphics.vector.ImageVector>> = linkedMapOf(
    Routes.HOSTS to ("Hosts" to Icons.Rounded.Dns),
    Routes.KEYS to ("Keys" to Icons.Rounded.Key),
    Routes.SNIPPETS to ("Snippets" to Icons.Rounded.AutoAwesome),
    Routes.SETTINGS to ("Settings" to Icons.Rounded.Settings),
)

/**
 * The row of tabs along the bottom.
 *
 * Material's own bar is eighty density-independent pixels tall, which on a
 * phone is a tenth of the screen given to four words. This one is sixty-four
 * and says the same thing.
 */
@Composable
private fun TabBar(current: String, onPick: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .navigationBarsPadding()
            .height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TOP_LEVEL.forEach { (dest, look) ->
            val selected = current == dest
            val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onPick(dest) },
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 52.dp, height = 30.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) { Icon(look.second, look.first, Modifier.size(22.dp), tint = tint) }
                Spacer(Modifier.height(3.dp))
                Text(look.first, style = MaterialTheme.typography.labelMedium, color = tint)
            }
        }
    }
}

class MainActivity : FragmentActivity() {
    /** Intent handed in by a shortcut or notification, consumed by the NavHost. */
    private val pending = mutableStateOf<Intent?>(null)

    /** True while the app is a floating window, when only the terminal is drawn. */
    private val floating = mutableStateOf(false)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pending.value = intent
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        floating.value = isInPictureInPictureMode
        if (!isInPictureInPictureMode) Pip.leftFloating = true
    }

    /**
     * Android 11 and older have no `setAutoEnterEnabled`: leaving the app is a
     * callback, and the window has to be asked for by hand while it happens.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && Pip.autoEnter) Pip.float(this)
    }

    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    /**
     * Ctrl+Shift+P opens the command palette, wherever you are.
     *
     * Taken here rather than in the view tree because a terminal with the focus
     * eats key events on its way to the shell, and the palette has to open over
     * one of those as readily as over the host list.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (CommandPaletteState.opens(event)) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) CommandPaletteState.show()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val app = application as App
        pending.value = intent
        setContent {
            FlintTermTheme {
                val settings by app.store.settings.collectAsStateWithLifecycle()
                val lockable = remember { AppLock.canAuthenticate(this) }
                var locked by remember { mutableStateOf(lockable && AppLock.isLocked(settings.appLock, settings.appLockGraceSeconds, App.lastBackgroundedAt)) }
                // Re-evaluate when we come back to the foreground.
                val fg = App.foregroundActivities
                LaunchedEffect(fg, settings.appLock) { if (lockable && AppLock.isLocked(settings.appLock, settings.appLockGraceSeconds, App.lastBackgroundedAt)) locked = true }
                // Both of these are remembered before the two early returns
                // below, not after them. A `remember` whose call is skipped
                // loses its slot, so declaring them further down would mean
                // every lock and every float handing back a brand-new back
                // stack with every screen scrolled to the top.
                val held = rememberSaveableStateHolder()
                val nav = rememberNavController()
                if (locked) {
                    LockScreen(this) { locked = false }
                    return@FlintTermTheme
                }
                if (floating.value) {
                    FloatingTerminal(Pip.sessionId)
                    return@FlintTermTheme
                }
                // The app is composed away while it floats, so what a person
                // was looking at is put back through the saved state rather
                // than left to the terminal's own remembering.
                held.SaveableStateProvider("app") {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val snackbar = remember { SnackbarHostState() }
                    // What was open when the app was last killed, put back once
                    // per process. It lives here rather than in the App because
                    // this is the first point past the lock screen: a locked
                    // phone must not be opening connections behind it.
                    LaunchedEffect(Unit) {
                        val open = app.open
                        val restoring = open.claimRestore() && settings.restoreSessions
                        val reopened = if (restoring) {
                            dev.flint.term.session.OpenSessions.reopen(app.store, app.sessions, open.saved)
                        } else {
                            emptyList()
                        }
                        // Only now may the file be written over: until the
                        // restore has had its turn, it is still the truth.
                        open.start()
                        if (reopened.isEmpty()) return@LaunchedEffect
                        val what = if (reopened.size == 1) "Reopened 1 session" else "Reopened ${reopened.size} sessions"
                        if (snackbar.showSnackbar(what, actionLabel = "Undo", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                            reopened.forEach { id ->
                                app.sessions.get(id)?.close()
                                app.sessions.remove(id)
                                Workspace.forget(id)
                            }
                        }
                    }
                    val intentToHandle by pending
                    LaunchedEffect(intentToHandle) {
                        val i = intentToHandle ?: return@LaunchedEffect
                        pending.value = null
                        when (i.action) {
                            Shortcuts.ACTION_CONNECT -> i.getStringExtra(Shortcuts.EXTRA_HOST_ID)?.let { id -> app.store.host(id) }?.let { h ->
                                val s = app.sessions.openSsh(h)
                                nav.navigate(Routes.terminal(s.id)) { popUpTo(Routes.HOSTS) }
                            }
                            Shortcuts.ACTION_OPEN_SESSION -> i.getStringExtra(Shortcuts.EXTRA_SESSION_ID)?.let { id ->
                                if (app.sessions.get(id) != null) nav.navigate(Routes.terminal(id)) { popUpTo(Routes.HOSTS) }
                            }
                            // Plugged in while we were running, or launched by the attach event.
                            UsbManager.ACTION_USB_DEVICE_ATTACHED -> app.serial.refresh()
                            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                                val uris = shareUris(i)
                                if (uris.isNotEmpty()) {
                                    App.pendingShare.clear()
                                    App.pendingShare.addAll(uris)
                                    // The host list asks which server the files should go to.
                                    nav.popBackStack(Routes.HOSTS, false)
                                }
                            }
                            Intent.ACTION_VIEW -> i.data?.toString()?.let(QuickConnect::parse)?.let { link ->
                                // A saved host that matches connects straight away; otherwise open a prefilled editor.
                                val match = QuickConnect.saved(app.store.hosts.value, link)
                                if (match != null) {
                                    // The link may still say which tmux window to land on.
                                    val s = app.sessions.openSsh(match, extraStartup = link.startupCommand.ifBlank { null })
                                    nav.navigate(if (i.data?.scheme == "sftp") Routes.sftp(s.id) else Routes.terminal(s.id)) { popUpTo(Routes.HOSTS) }
                                } else {
                                    App.hostDraft = link
                                    nav.navigate(Routes.hostEdit("new"))
                                }
                            }
                        }
                    }
                    val spec = tween<Float>(260)
                    // The four places the app *is*, rather than four screens
                    // reached through a settings list: hosts, the things hosts
                    // log in with, the commands you run on them, and settings.
                    val entry by nav.currentBackStackEntryAsState()
                    val route = entry?.destination?.route
                    // A window that small is worth having for a terminal and
                    // nothing else, and only when the switch asks for one.
                    LaunchedEffect(route, settings.pipOnLeave) {
                        Pip.setAutoEnter(this@MainActivity, settings.pipOnLeave && route == Routes.TERMINAL)
                    }
                    // Tapping the floating window comes back to the session it
                    // was showing, whatever became of the navigation state.
                    LaunchedEffect(Unit) {
                        if (!Pip.leftFloating) return@LaunchedEffect
                        Pip.leftFloating = false
                        val id = Pip.sessionId ?: return@LaunchedEffect
                        if (app.sessions.get(id) == null) return@LaunchedEffect
                        withFrameNanos {} // let a restored back stack settle first
                        if (nav.currentDestination?.route != Routes.TERMINAL) {
                            nav.navigate(Routes.terminal(id)) { popUpTo(Routes.HOSTS) }
                        }
                    }
                    Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                    NavHost(
                        navController = nav,
                        startDestination = Routes.HOSTS,
                        enterTransition = { fadeIn(spec) + slideInHorizontally(tween(300)) { it / 12 } },
                        exitTransition = { fadeOut(tween(180)) },
                        popEnterTransition = { fadeIn(spec) },
                        popExitTransition = { fadeOut(tween(180)) + slideOutHorizontally(tween(260)) { it / 12 } },
                    ) {
                        composable(Routes.HOSTS) { HostsScreen(nav) }
                        composable(Routes.HOST_EDIT, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                            HostEditScreen(nav, it.arguments?.getString("id") ?: "new")
                        }
                        composable(Routes.KEYS) { KeysScreen(nav) }
                        composable(Routes.KEY_ROTATION, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                            KeyRotationScreen(nav, it.arguments?.getString("id").orEmpty())
                        }
                        composable(Routes.ACCOUNTS) { AccountsScreen(nav) }
                        composable(Routes.GROUPS) { GroupsScreen(nav) }
                        composable(Routes.GROUP_EDIT, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                            GroupEditScreen(nav, it.arguments?.getString("id").orEmpty())
                        }
                        composable(Routes.TUNNELS) { TunnelsScreen(nav) }
                        composable(Routes.SNIPPETS) { SnippetsScreen(nav) }
                        composable(Routes.BROADCAST) { BroadcastScreen(nav) }
                        composable(Routes.EXTRA_KEYS) { ExtraKeysScreen(nav) }
                        composable(Routes.CHORDS) { ChordsScreen(nav) }
                        composable(Routes.KNOWN_HOSTS) { KnownHostsScreen(nav) }
                        composable(Routes.TRANSFERS) { TransfersScreen(nav) }
                        composable(Routes.RECORDINGS) { RecordingsScreen(nav) }
                        composable(Routes.SETTINGS) { SettingsScreen(nav) }
                        composable(Routes.SETTINGS_APPEARANCE) { AppearanceSettings(nav) }
                        composable(Routes.SETTINGS_KEYBOARD) { KeyboardSettings(nav) }
                        composable(Routes.SETTINGS_TERMINAL) { TerminalSettings(nav) }
                        composable(Routes.SETTINGS_SESSIONS) { SessionsSettings(nav) }
                        composable(Routes.SETTINGS_CONNECTIONS) { ConnectionsSettings(nav) }
                        composable(Routes.SETTINGS_FILES) { FilesSettings(nav) }
                        composable(Routes.SETTINGS_BACKUP) { BackupSettings(nav) }
                        composable(Routes.SETTINGS_SECURITY) { SecuritySettings(nav) }
                        composable(Routes.SETTINGS_AUTOMATION) { AutomationSettings(nav) }
                        composable(Routes.SETTINGS_ABOUT) { AboutSettings(nav) }
                        composable(Routes.LICENSES) { dev.flint.term.ui.settings.LicensesScreen(nav) }
                        composable(Routes.HIGHLIGHTS) { HighlightScreen(nav) }
                        composable(Routes.THEME) { ThemeScreen(nav) }
                        composable(Routes.GROUP_THEME, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                            ThemeScreen(nav, groupId = it.arguments?.getString("id").orEmpty())
                        }
                        composable(Routes.TERMINAL, arguments = listOf(navArgument("sessionId") { type = NavType.StringType })) {
                            TerminalScreen(nav, it.arguments?.getString("sessionId") ?: "")
                        }
                        composable(
                            Routes.SFTP,
                            arguments = listOf(
                                navArgument("sessionId") { type = NavType.StringType },
                                navArgument("path") { type = NavType.StringType; nullable = true; defaultValue = null },
                            ),
                        ) {
                            SftpScreen(nav, it.arguments?.getString("sessionId") ?: "", it.arguments?.getString("path"))
                        }
                        composable(
                            Routes.EDIT,
                            arguments = listOf(
                                navArgument("sessionId") { type = NavType.StringType },
                                navArgument("path") { type = NavType.StringType; nullable = true; defaultValue = null },
                            ),
                        ) {
                            EditorScreen(nav, it.arguments?.getString("sessionId") ?: "", it.arguments?.getString("path").orEmpty())
                        }
                        composable(Routes.SERVER, arguments = listOf(navArgument("sessionId") { type = NavType.StringType })) {
                            ServerScreen(nav, it.arguments?.getString("sessionId") ?: "")
                        }
                        composable(Routes.FORWARDS, arguments = listOf(navArgument("sessionId") { type = NavType.StringType })) {
                            ForwardsScreen(nav, it.arguments?.getString("sessionId") ?: "")
                        }
                    }
                    }
                    if (route in TOP_LEVEL) {
                        TabBar(route.orEmpty()) { dest ->
                            if (route != dest) {
                                nav.navigate(dest) {
                                    // One entry per tab, and the host list is always underneath.
                                    popUpTo(Routes.HOSTS) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        }
                    }
                    }
                    // Above everything, including a terminal that fills the
                    // screen: what it has to say is about the app, not the pane.
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
                    }
                    // Over everything, and outside the NavHost: what it finds is
                    // the whole app, not the screen it was opened from.
                    CommandPalette(nav)
                    val prompt by app.sessions.hostKeyPrompt.collectAsStateWithLifecycle()
                    prompt?.let { HostKeyDialog(it) }
                    // A login question can come from a session with no screen of
                    // its own — a snippet, a broadcast, the widget — so it is
                    // asked here, over whatever the person is looking at.
                    val login by app.sessions.authPrompt.collectAsStateWithLifecycle()
                    login?.let { AuthPromptDialog(it) }
                    // Signing with a security key happens on a background thread
                    // wherever the connection is; the person has to be told to
                    // touch it whichever screen they are looking at.
                    val touch by SecurityKeyGate.prompt.collectAsStateWithLifecycle()
                    touch?.let { SecurityKeyDialog(it) }
                    val signing by dev.flint.term.session.AgentGate.prompt.collectAsStateWithLifecycle()
                    signing?.let { AgentSigningDialog(it) }
                }
                }
            }
        }
    }
}

/**
 * "web01 wants to sign with your key" — the question `ssh -A` never asks.
 *
 * Deny is the resting position: dismissing it, or walking away until it times
 * out, refuses. A signature that does not happen costs a `git push`; one that
 * happens without being noticed can cost every host the key opens.
 */
@Composable
private fun AgentSigningDialog(asking: dev.flint.term.session.AgentGate.Asking) {
    AlertDialog(
        onDismissRequest = { asking.deny() },
        title = { Text("Sign for ${asking.hostLabel}?") },
        text = {
            Column {
                Text("Something on ${asking.hostLabel} is asking to sign with a key kept on this phone. It can do that for as long as you stay connected.")
                Spacer(Modifier.height(12.dp))
                Text(asking.keyName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                CodeBox(asking.fingerprint)
            }
        },
        confirmButton = { Button(onClick = { asking.allow() }) { Text("Sign once") } },
        dismissButton = { TextButton(onClick = { asking.deny() }) { Text("Refuse") } },
    )
}

/** "Touch your key", for as long as something is waiting on one. */
@Composable
private fun SecurityKeyDialog(showing: SecurityKeyGate.Showing) {
    showing.pin?.let {
        SecurityKeyPinDialog(showing, it)
        return
    }
    AlertDialog(
        // Only the button dismisses it: tapping outside would leave the
        // connection waiting on a key with nothing on screen to say so.
        onDismissRequest = {},
        title = { Text(showing.title) },
        text = { Text(showing.body) },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { showing.prompt.cancel() }) { Text("Cancel") } },
    )
}

/**
 * The PIN a token asked for, taken without ever holding it as a String.
 *
 * A String is immutable, so a PIN typed into one stays in the heap until a
 * garbage collection nobody can schedule — readable by anything that gets a
 * memory dump, and impossible to find again to erase. What is typed here goes
 * straight into a [PinEntry]'s char array, is handed to the worker as one, and
 * is wiped there the moment the token has been satisfied. The field itself only
 * ever shows bullets, and the PIN is never logged, saved or copied anywhere.
 *
 * The one String is the keystroke the IME delivers, which is Android's boundary
 * and not ours to move.
 */
@Composable
private fun SecurityKeyPinDialog(showing: SecurityKeyGate.Showing, request: SecurityKeyPinRequest) {
    val entry = remember(request) { PinEntry() }
    // Leaving the dialog for any reason — a cancel, a wrong PIN, the connection
    // giving up — takes the digits with it.
    DisposableEffect(request) { onDispose { entry.clear() } }
    val focus = remember(request) { FocusRequester() }
    LaunchedEffect(request) { runCatching { focus.requestFocus() } }
    val ready = entry.length >= PinEntry.MINIMUM
    val send = { if (ready) request.submit(entry.take()) }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(showing.title) },
        text = {
            Column {
                Text(
                    request.tokenName?.let { "Enter the PIN for $it" } ?: "Enter the security key's PIN",
                    style = MaterialTheme.typography.bodyMedium,
                )
                // The token is held open while this is typed, so over NFC the key
                // has to stay where it is or the exchange starts again.
                if (showing.prompt.transport == KeyTransport.NFC) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Keep the key against the phone while you type.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Field(
                    entry.masked,
                    { entry.edit(it) },
                    "PIN",
                    modifier = Modifier.focusRequester(focus),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    error = request.problem,
                    hint = "At least ${PinEntry.MINIMUM} digits. Getting it wrong too often locks the key.",
                )
            }
        },
        confirmButton = { Button(enabled = ready, onClick = send) { Text("Unlock") } },
        dismissButton = { TextButton(onClick = { request.cancel() }) { Text("Cancel") } },
    )
}

/**
 * The digits as they are typed, in something that can actually be erased.
 *
 * The text handed to the field is bullets, so the buffer is the only place the
 * PIN exists; an edit is matched against it by counting how many bullets came
 * back rather than by reading any of them.
 */
private class PinEntry {
    private var buffer = CharArray(MAXIMUM)

    var length by mutableIntStateOf(0)
        private set

    val masked: String get() = MASK.toString().repeat(length)

    fun edit(text: String) {
        length = text.count { it == MASK }.coerceAtMost(length)
        text.forEach { if (it != MASK && length < MAXIMUM) buffer[length++] = it }
    }

    /** The PIN, handed over once; this side keeps nothing. */
    fun take(): CharArray = buffer.copyOf(length).also { clear() }

    fun clear() {
        buffer.fill(Char(0))
        length = 0
    }

    companion object {
        /** What CTAP 2.1 says a token accepts at the least, absent one saying otherwise. */
        const val MINIMUM = 4

        /** A PIN is at most 63 bytes; 63 characters is the loosest that can be. */
        const val MAXIMUM = 63

        private const val MASK = '•'
    }
}

/**
 * Whatever the server wants to know before it lets you in.
 *
 * The wording is the server's, not ours: it knows whether it is asking for a
 * code from an app, an answer to a challenge, or a password it has decided has
 * expired, and rewording that here could only make it wrong. Nothing typed here
 * is kept — it goes to the connection that asked and is gone with the dialog.
 */
@Composable
private fun AuthPromptDialog(prompt: AuthPrompt) {
    val answers = remember(prompt) { prompt.fields.map { mutableStateOf("") } }
    val focus = remember(prompt) { FocusRequester() }
    LaunchedEffect(prompt) { runCatching { focus.requestFocus() } }
    val send = { prompt.answer(answers.map { it.value }) }
    AlertDialog(
        onDismissRequest = { prompt.cancel() },
        title = { Text(prompt.name.ifBlank { "The server is asking" }) },
        text = {
            Column {
                val instruction = prompt.instruction.trim()
                if (instruction.isNotEmpty()) {
                    Text(instruction, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                }
                prompt.fields.forEachIndexed { i, field ->
                    if (i > 0) Spacer(Modifier.height(10.dp))
                    Field(
                        answers[i].value,
                        { answers[i].value = it },
                        field.text.trim().trimEnd(':').ifBlank { "Answer" },
                        modifier = if (i == 0) Modifier.focusRequester(focus) else Modifier,
                        // The server says whether what is typed may be seen; a
                        // one-time code and a password both say no.
                        visualTransformation = if (field.echo) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (field.echo) KeyboardType.Text else KeyboardType.Password,
                            imeAction = if (i == prompt.fields.lastIndex) ImeAction.Done else ImeAction.Next,
                        ),
                    )
                }
            }
        },
        confirmButton = { Button(onClick = { send() }) { Text("Send") } },
        dismissButton = { TextButton(onClick = { prompt.cancel() }) { Text("Cancel") } },
    )
}

@Composable
private fun HostKeyDialog(prompt: HostKeyPrompt) {
    val changed = prompt.previousFingerprint != null
    AlertDialog(
        onDismissRequest = { prompt.decision.complete(false) },
        title = { Text(if (changed) "Host key changed" else "New host") },
        text = {
            Column {
                if (changed) {
                    Text(
                        "The key for ${prompt.key.host}:${prompt.key.port} is different from the one you trusted before. " +
                            "That happens after a reinstall — or when someone is intercepting the connection.",
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Previously", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    CodeBox(prompt.previousFingerprint ?: "")
                    Spacer(Modifier.height(10.dp))
                } else {
                    Text("First connection to ${prompt.key.host}:${prompt.key.port}. Compare the fingerprint with the server before trusting it.")
                    Spacer(Modifier.height(12.dp))
                }
                Text(prompt.key.keyType, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                CodeBox(prompt.key.fingerprint)
            }
        },
        confirmButton = {
            Button(
                onClick = { prompt.decision.complete(true) },
                colors = if (changed) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError) else ButtonDefaults.buttonColors(),
            ) { Text(if (changed) "Trust new key" else "Trust") }
        },
        dismissButton = { TextButton(onClick = { prompt.decision.complete(false) }) { Text("Cancel") } },
    )
}

@Composable
private fun CodeBox(text: String) {
    Text(
        text,
        style = CodeStyle.copy(fontSize = 12.sp),
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(10.dp),
    )
}

/** The file(s) behind a share intent; text-only shares carry no stream and are ignored. */
private fun shareUris(i: Intent): List<android.net.Uri> =
    if (i.action == Intent.ACTION_SEND_MULTIPLE) {
        IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, android.net.Uri::class.java).orEmpty()
    } else {
        listOfNotNull(IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, android.net.Uri::class.java))
    }
