package dev.flint.term.ui

import dev.flint.term.R
import androidx.compose.ui.res.stringResource
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
    st.state == "needs-login" -> stringResource(R.string.tailscalesection_waiting_for_login) to Status.busy
    st.state == "error" -> stringResource(R.string.tailscalesection_error, st.error ?: "unknown") to MaterialTheme.colorScheme.error
    // Idle is the normal resting state: the node comes up when a host needs it.
    p.joined -> stringResource(R.string.tailscalesection_idle_starts_when_a_host_needs_it) to MaterialTheme.colorScheme.onSurfaceVariant
    else -> stringResource(R.string.tailscalesection_not_joined_to_a_tailnet_yet) to Status.busy
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
    // While this is on screen the status poll keeps up. Off screen it slows
    // right down, which is most of what it used to cost.
    androidx.compose.runtime.DisposableEffect(ts) {
        ts.watch()
        onDispose { ts.unwatch() }
    }
    val hosts by app.store.hosts.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<Map<String, Result<String>>>(emptyMap()) }

    GroupLabel("Tailscale")
    if (!ts.available) {
        Group {
            GroupRow(
                title = if (ts.supportedHere) stringResource(R.string.tailscalesection_not_included_in_this_build) else stringResource(R.string.tailscalesection_not_available_on_this_android_version),
                subtitle = ts.unavailableReason ?: "",
                icon = Icons.Rounded.Hub, iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    if (profiles.isEmpty()) {
        EmptyState(
            Icons.Rounded.Hub, stringResource(R.string.tailscalesection_no_tailscale_accounts_yet),
            stringResource(R.string.tailscalesection_add_one_and_this_app_becomes_a_machine_on_your_t),
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
                        !p.joined -> TextButton(onClick = { onOpen(p) }) { Text(stringResource(R.string.tailscalesection_join)) }
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
                        ) { Text(stringResource(R.string.tailscalesection_test)) }
                    }
                },
            )
            results[p.id]?.let { r -> TestResult(r) }
            if (i < profiles.lastIndex) RowDivider()
        }
    }
    Text(
        stringResource(R.string.tailscalesection_a_node_starts_by_itself_when_a_host_that_uses_it),
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
                    stringResource(R.string.tailscalesection_this_account_is_not_on_a_tailnet_yet_log_in_with),
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
                            Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.tailscalesection_log_in_to_tailscale))
                        }
                        st.state == "starting" -> {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.tailscalesection_getting_a_login_link))
                        }
                        else -> Text(stringResource(R.string.tailscalesection_log_in_with_a_browser))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HorizontalDivider(Modifier.weight(1f))
                    Text(stringResource(R.string.tailscalesection_or_paste_an_auth_key), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Field(
                    authKey, { authKey = it }, stringResource(R.string.tailscalesection_auth_key),
                    placeholder = stringResource(R.string.tailscalesection_tskey_auth),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    hint = if (p.authKey.isNotBlank()) {
                        stringResource(R.string.tailscalesection_a_key_is_stored_but_has_not_joined_a_tailnet_pas)
                    } else {
                        stringResource(R.string.tailscalesection_joins_without_a_browser_a_single_use_or_ephemera)
                    },
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    enabled = authKey.trim().isNotBlank(),
                    onClick = { ts.joinWithKey(p.id, authKey.trim()); authKey = "" },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.tailscalesection_join_with_this_key)) }
                Spacer(Modifier.height(16.dp))
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (st.state == "running") {
                        OutlinedButton(onClick = { ts.down(p.id) }, Modifier.weight(1f)) { Text(stringResource(R.string.tailscalesection_disconnect)) }
                    } else {
                        Button(onClick = { ts.upByHand(p.id) }, Modifier.weight(1f)) { Text(stringResource(R.string.tailscalesection_connect)) }
                    }
                    OutlinedButton(onClick = { showSettings = !showSettings }, Modifier.weight(1f)) { Text(stringResource(R.string.tailscalesection_settings)) }
                }
                if (st.state != "running") {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.tailscalesection_connecting_is_only_needed_to_browse_the_tailnet),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            if (showSettings || !p.joined) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Field(name, { name = it }, "Name", placeholder = stringResource(R.string.tailscalesection_work_tailnet), hint = stringResource(R.string.tailscalesection_shown_when_picking_a_vpn_for_a_host))
                    Field(hostname, { hostname = it }, "Machine name (optional)", placeholder = stringResource(R.string.tailscalesection_flintterm_phone), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false))
                    Field(controlUrl, { controlUrl = it }, "Control server (optional, for Headscale)", placeholder = stringResource(R.string.tailscalesection_https_headscale_example_com), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    Button(
                        onClick = {
                            val changed = hostname.trim() != p.hostname || controlUrl.trim() != p.controlUrl
                            app.store.upsertTailscaleProfile(p.copy(name = name.trim(), hostname = hostname.trim(), controlUrl = controlUrl.trim()))
                            showSettings = false
                            if (changed && st.state != "stopped") ts.restart(p.id)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.tailscalesection_apply)) }
                }
                Spacer(Modifier.height(16.dp))
            }

            if (st.state == "running" && st.peers.isNotEmpty()) {
                GroupLabel(stringResource(R.string.tailscalesection_devices_on_this_tailnet))
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
                                }) { Icon(Icons.Rounded.Add, null, Modifier.width(16.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.tailscalesection_host)) }
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
                    Text(stringResource(R.string.tailscalesection_leave_tailnet), color = MaterialTheme.colorScheme.error)
                }
            }
            TextButton(onClick = { confirmDelete = true }, Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Delete, null, Modifier.width(18.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.tailscalesection_delete_this_account), color = MaterialTheme.colorScheme.error)
            }
            Text(
                stringResource(R.string.tailscalesection_traffic_stays_inside_this_app_a_host_set_to_this),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.tailscalesection_leave_the_tailnet)) },
            text = { Text(stringResource(R.string.tailscalesection_this_device_is_removed_from_the_tailnet_and_the)) },
            confirmButton = { Button(onClick = { ts.logout(p.id); confirmLeave = false }) { Text(stringResource(R.string.tailscalesection_leave)) } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.tailscalesection_cancel)) } },
        )
    }
    if (confirmDelete) {
        val users = app.store.hosts.value.count { it.tailscaleId == p.id }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.tailscalesection_delete_this_account_2)) },
            text = {
                Text(
                    stringResource(R.string.tailscalesection_the_node_leaves_the_tailnet_and_its_identity_is) +
                        if (users > 0) " $users host${if (users > 1) "s" else ""} set to it will fall back to a direct connection." else "",
                )
            },
            confirmButton = {
                Button(onClick = {
                    ts.forget(p.id)
                    app.store.deleteTailscaleProfile(p.id)
                    confirmDelete = false
                    onDismiss()
                }) { Text(stringResource(R.string.tailscalesection_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.tailscalesection_cancel)) } },
        )
    }
}
