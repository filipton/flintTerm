package dev.flint.term.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.flint.term.App
import dev.flint.term.data.ForwardType
import dev.flint.term.data.PortForward
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** A wildcard bind is reachable from the phone itself only through loopback. */
private fun reachableBind(bindHost: String): String =
    if (bindHost.isBlank() || bindHost == "0.0.0.0" || bindHost == "::" || bindHost == "*") "127.0.0.1" else bindHost

/** Live view of the session's tunnels; toggling starts/stops them immediately. */
@Composable
fun ForwardsScreen(nav: NavController, sessionId: String) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val session = remember(sessionId) { app.sessions.get(sessionId) }
    if (session == null) {
        LaunchedEffect(Unit) { nav.popBackStack() }
        return
    }
    val hosts by app.store.hosts.collectAsStateWithLifecycle()
    val host = hosts.firstOrNull { it.id == session.host?.id } ?: session.host
    val active by session.forwards.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<PortForward?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppHeader(title = "Port forwards", subtitle = session.label, onBack = { nav.popBackStack() }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = PortForward() },
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text("Add forward") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(20.dp),
            )
        },
    ) { padding ->
        val configured = host?.forwards ?: emptyList()
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 100.dp)) {
            if (configured.isEmpty()) {
                EmptyState(
                    Icons.Rounded.SwapHoriz, "No forwards yet",
                    "Tunnel a local port to something behind the server (-L), expose a local service on the server (-R), or run a SOCKS proxy through it (-D). Forwards are saved on the host and can start with every session.",
                )
            } else {
                Group {
                    configured.forEachIndexed { i, f ->
                        val running = active.firstOrNull { it.forward.id == f.id }
                        val status = when {
                            running == null -> "Stopped"
                            running.error != null -> "Error: ${running.error}"
                            f.bindPort == 0 -> "Listening on port ${running.boundPort}"
                            else -> "Active"
                        }
                        GroupRow(
                            title = f.describe(),
                            subtitle = status,
                            icon = Icons.Rounded.SwapHoriz,
                            iconTint = if (running?.error != null) MaterialTheme.colorScheme.error else if (running != null) Status.online else MaterialTheme.colorScheme.onSurfaceVariant,
                            onClick = { editing = f },
                            trailing = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val live = running?.takeIf { it.error == null }
                                    // A bindPort of 0 means the system picked one, so ask the tunnel what it got.
                                    val port = if (f.bindPort == 0) live?.boundPort ?: 0 else f.bindPort
                                    if (live != null) when (f.type) {
                                        // Nine times in ten the forward fronts a web UI, and typing the address is the tedious part.
                                        ForwardType.LOCAL -> IconButton(onClick = {
                                            val url = "http://${reachableBind(f.bindHost)}:$port/"
                                            runCatching {
                                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                            }.onFailure { Toast.makeText(context, "Nothing can open $url", Toast.LENGTH_SHORT).show() }
                                        }) { Icon(Icons.Rounded.OpenInBrowser, "Open") }
                                        // A SOCKS proxy has no page to visit; what other apps need is the address.
                                        ForwardType.DYNAMIC -> IconButton(onClick = {
                                            val address = "socks5://127.0.0.1:$port"
                                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("proxy", address))
                                            Toast.makeText(context, "Copied $address", Toast.LENGTH_SHORT).show()
                                        }) { Icon(Icons.Rounded.ContentCopy, "Copy proxy address") }
                                        ForwardType.REMOTE -> Unit
                                    }
                                    AppSwitch(
                                        checked = running != null && running.error == null,
                                        enabled = session.isConnected,
                                        onCheckedChange = { on ->
                                            scope.launch(Dispatchers.IO) {
                                                if (on) session.startForward(f) else running?.let { session.stopForward(it) }
                                            }
                                        },
                                    )
                                }
                            },
                        )
                        if (i < configured.lastIndex) RowDivider()
                    }
                }
            }
        }
    }

    editing?.let { f ->
        ForwardDialog(
            initial = f,
            onDismiss = { editing = null },
            onSave = { saved ->
                host?.let { h ->
                    val list = if (h.forwards.any { it.id == saved.id }) h.forwards.map { if (it.id == saved.id) saved else it } else h.forwards + saved
                    app.store.upsertHost(h.copy(forwards = list))
                    if (session.isConnected) scope.launch(Dispatchers.IO) { session.startForward(saved) }
                }
                editing = null
            },
        )
    }
}
