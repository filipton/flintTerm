package dev.flint.term.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.flint.term.App
import dev.flint.term.core.TailscaleStatus
import dev.flint.term.data.AuthType
import dev.flint.term.data.Host
import dev.flint.term.data.HostIcon
import dev.flint.term.data.TailscaleProfile
import kotlinx.coroutines.launch

/** One line of status for a profile: what the node is doing, and in what color. */
@Composable
private fun statusLine(p: TailscaleProfile, st: TailscaleStatus): Pair<String, androidx.compose.ui.graphics.Color> = when {
    st.state == "running" -> ("Connected" + (st.selfName?.let { "  ·  ${it.substringBefore('.')}" } ?: "") +
        (st.ips.firstOrNull()?.let { "  ·  $it" } ?: "")) to Status.online
    st.state == "starting" -> "Starting…" to Status.busy
    st.state == "needs-login" -> "Waiting for login" to Status.busy
    st.state == "error" -> "Error: ${st.error ?: "unknown"}" to MaterialTheme.colorScheme.error
    // Idle is the normal resting state: the node comes up when a host needs it.
    p.joined -> "Idle, starts when a host needs it" to MaterialTheme.colorScheme.onSurfaceVariant
    else -> "Not joined to a tailnet yet" to Status.busy
}

/** The outcome of a "Test" press, under the row it belongs to. */
@Composable
fun TestResult(r: Result<String>) {
    Text(
        r.getOrElse { it.message ?: "failed" },
        style = MaterialTheme.typography.bodySmall,
        color = if (r.isSuccess) Status.online else MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(start = 72.dp, end = 20.dp, bottom = 12.dp),
    )
}

/**
 * The Tailscale part of the VPN screen: one row per profile, so several tailnets
 * (or accounts) sit side by side the way WireGuard tunnels do.
 */
@Composable
fun TailscaleSection(onOpen: (TailscaleProfile) -> Unit) {
    val app = LocalContext.current.applicationContext as App
    val ts = app.tailscale
    val profiles by app.store.tailscaleProfiles.collectAsStateWithLifecycle()
    val statuses by ts.statuses.collectAsStateWithLifecycle()
    val hosts by app.store.hosts.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<Map<String, Result<String>>>(emptyMap()) }

    GroupLabel("Tailscale")
    if (!ts.available) {
        Group {
            GroupRow(
                title = if (ts.supportedHere) "Not included in this build" else "Not available on this Android version",
                subtitle = ts.unavailableReason ?: "",
                icon = Icons.Rounded.Hub, iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    if (profiles.isEmpty()) {
        EmptyState(
            Icons.Rounded.Hub, "No Tailscale accounts yet",
            "Add one and this app becomes a machine on your tailnet. Hosts can then be reached by MagicDNS name, with no system VPN. You can add several accounts, and each is its own machine.",
        )
        return
    }
    Group {
        profiles.forEachIndexed { i, p ->
            val st = statuses[p.id] ?: TailscaleStatus("stopped", null, emptyList(), null, null, emptyList())
            val (label, tint) = statusLine(p, st)
            val users = hosts.count { it.tailscaleId == p.id }
            GroupRow(
                title = p.name.ifBlank { "Tailscale" },
                subtitle = label + if (users > 0) "  ·  $users host${if (users > 1) "s" else ""}" else "",
                icon = Icons.Rounded.Hub,
                iconTint = tint,
                onClick = { onOpen(p) },
                trailing = {
                    when {
                        testing == p.id -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        !p.joined -> TextButton(onClick = { onOpen(p) }) { Text("Join") }
                        // Nodes come up on their own, so the useful question here
                        // is "does this account work?", not "start it now".
                        else -> TextButton(
                            enabled = testing == null,
                            onClick = {
                                testing = p.id
                                results = results - p.id
                                scope.launch {
                                    val r = ts.test(p.id)
                                    results = results + (p.id to r)
                                    testing = null
                                }
                            },
                        ) { Text("Test") }
                    }
                },
            )
            results[p.id]?.let { r -> TestResult(r) }
            if (i < profiles.lastIndex) RowDivider()
        }
    }
    Text(
        "A node starts by itself when a host that uses it connects, and stops once nothing needs it.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 32.dp, vertical = 14.dp),
    )
}

/** Everything about one profile: joining, status, tailnet devices, settings, leaving. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TailscaleSheet(profile: TailscaleProfile, nav: NavController, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val ts = app.tailscale
    val profiles by app.store.tailscaleProfiles.collectAsStateWithLifecycle()
    val statuses by ts.statuses.collectAsStateWithLifecycle()
    // Follow the stored copy: joining and leaving rewrite it under us.
    val p = profiles.firstOrNull { it.id == profile.id } ?: profile
    val st = statuses[p.id] ?: TailscaleStatus("stopped", null, emptyList(), null, null, emptyList())
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember(p.id) { mutableStateOf(p.name) }
    var hostname by remember(p.id) { mutableStateOf(p.hostname) }
    var controlUrl by remember(p.id) { mutableStateOf(p.controlUrl) }
    var authKey by remember(p.id) { mutableStateOf("") }
    var showSettings by remember(p.id) { mutableStateOf(p.name.isBlank()) }
    var confirmLeave by remember(p.id) { mutableStateOf(false) }
    var confirmDelete by remember(p.id) { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        // The groups below sit on the sheet, not the page: their gaps must match it.
        CompositionLocalProvider(LocalBackdrop provides MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
            val (label, tint) = statusLine(p, st)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(Icons.Rounded.Hub, tint, 44, 14)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name.ifBlank { "Tailscale" }, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
            Spacer(Modifier.height(18.dp))

            if (!p.joined) {
                Text(
                    "This account is not on a tailnet yet. Log in with your browser, or paste a pre-auth key. Either way it only has to happen once.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                val url = st.loginUrl?.takeIf { st.state == "needs-login" }
                Button(
                    onClick = {
                        if (url != null) context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) else ts.upToJoin(p.id)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    when {
                        url != null -> {
                            Icon(Icons.Rounded.OpenInNew, null, Modifier.width(18.dp))
                            Spacer(Modifier.width(8.dp)); Text("Log in to Tailscale")
                        }
                        st.state == "starting" -> {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(8.dp)); Text("Getting a login link…")
                        }
                        else -> Text("Log in with a browser")
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HorizontalDivider(Modifier.weight(1f))
                    Text("  or paste an auth key  ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Field(
                    authKey, { authKey = it }, "Auth key",
                    placeholder = "tskey-auth-…",
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    hint = if (p.authKey.isNotBlank()) {
                        "A key is stored but has not joined a tailnet. Pasting another one replaces it and starts over."
                    } else {
                        "Joins without a browser. A single-use or ephemeral key is the safe kind to paste."
                    },
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    enabled = authKey.trim().isNotBlank(),
                    onClick = { ts.joinWithKey(p.id, authKey.trim()); authKey = "" },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Join with this key") }
                Spacer(Modifier.height(16.dp))
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (st.state == "running") {
                        OutlinedButton(onClick = { ts.down(p.id) }, Modifier.weight(1f)) { Text("Disconnect") }
                    } else {
                        Button(onClick = { ts.upByHand(p.id) }, Modifier.weight(1f)) { Text("Connect") }
                    }
                    OutlinedButton(onClick = { showSettings = !showSettings }, Modifier.weight(1f)) { Text("Settings") }
                }
                if (st.state != "running") {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Connecting is only needed to browse the tailnet from here. A host that uses this account brings the node up on its own.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            if (showSettings || !p.joined) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Field(name, { name = it }, "Name", placeholder = "Work tailnet", hint = "Shown when picking a VPN for a host.")
                    Field(hostname, { hostname = it }, "Machine name (optional)", placeholder = "flintterm-phone", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false))
                    Field(controlUrl, { controlUrl = it }, "Control server (optional, for Headscale)", placeholder = "https://headscale.example.com", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    Button(
                        onClick = {
                            val changed = hostname.trim() != p.hostname || controlUrl.trim() != p.controlUrl
                            app.store.upsertTailscaleProfile(p.copy(name = name.trim(), hostname = hostname.trim(), controlUrl = controlUrl.trim()))
                            showSettings = false
                            if (changed && st.state != "stopped") ts.restart(p.id)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Apply") }
                }
                Spacer(Modifier.height(16.dp))
            }

            if (st.state == "running" && st.peers.isNotEmpty()) {
                GroupLabel("Devices on this tailnet")
                Group {
                    st.peers.take(30).forEachIndexed { i, peer ->
                        GroupRow(
                            title = peer.name.substringBefore('.'),
                            subtitle = (peer.ips.firstOrNull() ?: "") +
                                (if (peer.os.isNotBlank()) "  ·  ${peer.os}" else "") +
                                (if (!peer.online) "  ·  offline" else ""),
                            icon = Icons.Rounded.Computer,
                            iconTint = if (peer.online) Status.online else MaterialTheme.colorScheme.onSurfaceVariant,
                            trailing = {
                                TextButton(onClick = {
                                    val h = Host(
                                        label = peer.name.substringBefore('.'),
                                        hostname = peer.name,
                                        username = "",
                                        authType = AuthType.KEY,
                                        tailscaleId = p.id,
                                        icon = HostIcon.CLOUD,
                                        groupId = app.store.groupIdFor(p.name.ifBlank { "Tailscale" }),
                                    )
                                    app.store.upsertHost(h)
                                    onDismiss()
                                    nav.navigate(Routes.hostEdit(h.id))
                                }) { Icon(Icons.Rounded.Add, null, Modifier.width(16.dp)); Spacer(Modifier.width(4.dp)); Text("Host") }
                            },
                        )
                        if (i < st.peers.take(30).lastIndex) RowDivider()
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            if (p.joined) {
                DiagnosticsSection(
                    canPing = false,
                    onPing = { throw UnsupportedOperationException() },
                    onPort = { host, port -> dev.flint.term.core.tailscalePortCheck(p.id, host, port.toUShort(), 8u) },
                    onResolve = null,
                )
                Spacer(Modifier.height(12.dp))
            }

            if (p.joined) {
                TextButton(onClick = { confirmLeave = true }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Logout, null, Modifier.width(18.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text("Leave tailnet", color = MaterialTheme.colorScheme.error)
                }
            }
            TextButton(onClick = { confirmDelete = true }, Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Delete, null, Modifier.width(18.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text("Delete this account", color = MaterialTheme.colorScheme.error)
            }
            Text(
                "Traffic stays inside this app: a host set to this account is dialled through its node, which starts for that connection and stops once nothing needs it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave the tailnet?") },
            text = { Text("This device is removed from the tailnet and the stored auth key is deleted. Hosts using this account stop working until it joins again.") },
            confirmButton = { Button(onClick = { ts.logout(p.id); confirmLeave = false }) { Text("Leave") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        val users = app.store.hosts.value.count { it.tailscaleId == p.id }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this account?") },
            text = {
                Text(
                    "The node leaves the tailnet and its identity is deleted from this device." +
                        if (users > 0) " $users host${if (users > 1) "s" else ""} set to it will fall back to a direct connection." else "",
                )
            },
            confirmButton = {
                Button(onClick = {
                    ts.forget(p.id)
                    app.store.deleteTailscaleProfile(p.id)
                    confirmDelete = false
                    onDismiss()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
