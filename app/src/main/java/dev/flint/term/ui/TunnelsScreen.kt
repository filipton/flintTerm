package dev.flint.term.ui

import dev.flint.term.R
import androidx.compose.ui.res.stringResource
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.flint.term.App
import dev.flint.term.core.TunnelStats
import dev.flint.term.core.wgGenerateKeypair
import dev.flint.term.data.ProxyType
import dev.flint.term.data.SavedProxy
import dev.flint.term.data.TailscaleProfile
import dev.flint.term.data.Tunnel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TunnelsScreen(nav: NavController) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val tunnels by app.store.tunnels.collectAsStateWithLifecycle()
    val proxies by app.store.proxies.collectAsStateWithLifecycle()
    val stats by app.tunnels.stats.collectAsStateWithLifecycle()
    val infos by app.tunnels.info.collectAsStateWithLifecycle()
    val hosts by app.store.hosts.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Tunnel?>(null) }
    var detail by remember { mutableStateOf<Tunnel?>(null) }
    var tailscaleDetail by remember { mutableStateOf<TailscaleProfile?>(null) }
    var editingProxy by remember { mutableStateOf<SavedProxy?>(null) }
    var addSheet by remember { mutableStateOf(false) }
    var testingTunnel by remember { mutableStateOf<String?>(null) }
    var tunnelResults by remember { mutableStateOf<Map<String, Result<String>>>(emptyMap()) }
    // Adding a profile and opening it are the same gesture: a new one is empty,
    // and the sheet is where it gets its name and joins a tailnet.
    fun addTailscale() {
        val p = TailscaleProfile(name = "")
        app.store.upsertTailscaleProfile(p)
        tailscaleDetail = p
    }

    // Live stats while the screen is open.
    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) { app.tunnels.refreshStats() }
            delay(2000)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppHeader(title = stringResource(R.string.tunnelsscreen_vpn_tunnels), onBack = { nav.popBackStack() }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (app.tailscale.available) addSheet = true else editing = Tunnel() },
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text(stringResource(R.string.tunnelsscreen_add)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(20.dp),
            )
        },
    ) { padding ->
        LazyColumn(state = rememberScreenListState("tunnels"), modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 110.dp)) {
            item { TailscaleSection(onOpen = { tailscaleDetail = it }) }
            item { GroupLabel("WireGuard") }
            if (tunnels.isEmpty()) {
                // A whole empty state per section turns this screen into three
                // screens; one line says the same thing and leaves room for the
                // sections that do have something in them.
                item { SectionHint(stringResource(R.string.tunnelsscreen_paste_a_wg_quick_config_with_add_only_this_app_s)) }
            } else {
                item {
                    Group {
                        tunnels.forEachIndexed { i, t ->
                            val st = stats[t.id]
                            val info = infos[t.id]
                            val users = hosts.count { it.tunnelId == t.id }
                            GroupRow(
                                title = t.name.ifBlank { info?.endpoint ?: "Tunnel" },
                                subtitle = describe(st, info) + if (users > 0) "  ·  $users host${if (users > 1) "s" else ""}" else "",
                                icon = Icons.Rounded.VpnLock,
                                iconTint = if (st?.running == true) Status.online else MaterialTheme.colorScheme.onSurfaceVariant,
                                onClick = { detail = t },
                                trailing = {
                                    // A tunnel starts itself when a host needs it, so the
                                    // question worth a button here is whether it works.
                                    if (testingTunnel == t.id) {
                                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    } else {
                                        TextButton(
                                            enabled = testingTunnel == null,
                                            onClick = {
                                                testingTunnel = t.id
                                                tunnelResults = tunnelResults - t.id
                                                scope.launch {
                                                    val r = app.tunnels.test(t.id)
                                                    tunnelResults = tunnelResults + (t.id to r)
                                                    testingTunnel = null
                                                }
                                            },
                                        ) { Text(stringResource(R.string.tunnelsscreen_test)) }
                                    }
                                },
                            )
                            tunnelResults[t.id]?.let { r -> TestResult(r) }
                            if (i < tunnels.lastIndex) RowDivider()
                        }
                    }
                }
                item { SectionHint(stringResource(R.string.tunnelsscreen_a_tunnel_starts_by_itself_when_a_host_that_uses)) }
            }
            // Proxies belong here for the same reason tunnels do: one entry, used
            // by whichever hosts need it, instead of retyped into each.
            item { GroupLabel("Proxies") }
            item {
                if (proxies.isEmpty()) {
                    SectionHint(stringResource(R.string.tunnelsscreen_a_socks5_or_http_proxy_kept_here_can_be_picked_b))
                } else {
                    Group {
                        proxies.forEachIndexed { i, p ->
                            val users = hosts.count { it.proxyId == p.id }
                            GroupRow(
                                title = p.label,
                                subtitle = p.target + if (users > 0) "  ·  $users host${if (users > 1) "s" else ""}" else "",
                                icon = Icons.Rounded.Lan,
                                iconTint = MaterialTheme.colorScheme.secondary,
                                onClick = { editingProxy = p },
                            )
                            if (i < proxies.lastIndex) RowDivider()
                        }
                    }
                }
            }
        }
    }

    editingProxy?.let { p -> ProxySheet(p, onDismiss = { editingProxy = null }) }

    tailscaleDetail?.let { p ->
        TailscaleSheet(p, nav, onDismiss = {
            // An account added and then abandoned before it was named or joined
            // would just be clutter on the screen.
            val stored = app.store.tailscaleProfile(p.id)
            if (stored != null && stored.name.isBlank() && !stored.joined && stored.authKey.isBlank()) {
                app.store.deleteTailscaleProfile(p.id)
            }
            tailscaleDetail = null
        })
    }
    if (addSheet) {
        ActionSheet(
            onDismiss = { addSheet = false },
            title = stringResource(R.string.tunnelsscreen_add),
            subtitle = stringResource(R.string.tunnelsscreen_both_route_only_this_app_s_traffic_with_no_syste),
            actions = listOf(
                SheetAction(stringResource(R.string.tunnelsscreen_wireguard_tunnel), Icons.Rounded.VpnLock, subtitle = stringResource(R.string.tunnelsscreen_paste_a_wg_quick_config)) {
                    addSheet = false; editing = Tunnel()
                },
                SheetAction(stringResource(R.string.tunnelsscreen_tailscale_account), Icons.Rounded.Hub, subtitle = stringResource(R.string.tunnelsscreen_join_a_tailnet_with_a_browser_login_or_an_auth_k)) {
                    addSheet = false; addTailscale()
                },
                SheetAction(stringResource(R.string.tunnelsscreen_proxy), Icons.Rounded.Lan, subtitle = stringResource(R.string.tunnelsscreen_a_socks5_or_http_proxy_hosts_can_be_dialled_thro)) {
                    addSheet = false; editingProxy = SavedProxy()
                },
            ),
        )
    }

    detail?.let { t ->
        val st = stats[t.id]
        val info = infos[t.id]
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { detail = null }, sheetState = state, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Icons.Rounded.VpnLock, if (st?.running == true) Status.online else MaterialTheme.colorScheme.onSurfaceVariant, 44, 14)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.name.ifBlank { "Tunnel" }, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(describe(st, info), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(16.dp))
                if (info != null) {
                    DetailLine("Endpoint", info.endpoint)
                    DetailLine("Addresses", info.addresses.joinToString(", "))
                    DetailLine(stringResource(R.string.tunnelsscreen_allowed_ips), info.allowedIps.joinToString(", "))
                    DetailLine("DNS", info.dns.joinToString(", ").ifBlank { stringResource(R.string.tunnelsscreen_none_use_ip_addresses) })
                    DetailLine("MTU", info.mtu.toString())
                    Text(stringResource(R.string.tunnelsscreen_this_device_s_public_key), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SelectionContainer(Modifier.weight(1f)) { Text(info.publicKey, style = CodeStyle.copy(fontSize = 12.sp)) }
                        IconButton(onClick = {
                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("public key", info.publicKey))
                            Toast.makeText(context, context.getString(R.string.tunnelsscreen_public_key_copied), Toast.LENGTH_SHORT).show()
                        }) { Icon(Icons.Rounded.ContentCopy, "Copy") }
                    }
                }
                st?.let { s ->
                    if (s.running) {
                        DetailLine("Traffic", stringResource(R.string.tunnelsscreen_sent_received, humanBytes(s.txBytes.toLong()), humanBytes(s.rxBytes.toLong())))
                    }
                }
                Spacer(Modifier.height(18.dp))
                // "Does this config actually work?" is the question a tunnel
                // screen should be able to answer without connecting to a host.
                var testing by remember(t.id) { mutableStateOf(false) }
                var testResult by remember(t.id) { mutableStateOf<Result<String>?>(null) }
                Button(
                    enabled = !testing,
                    onClick = {
                        testing = true
                        testResult = null
                        scope.launch {
                            testResult = app.tunnels.test(t.id)
                            testing = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (testing) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.tunnelsscreen_testing))
                    } else {
                        Text(stringResource(R.string.tunnelsscreen_test_connection))
                    }
                }
                testResult?.let { r ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        r.getOrElse { it.message ?: "failed" },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (r.isSuccess) Status.online else MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(16.dp))
                DiagnosticsSection(
                    canPing = true,
                    onPing = { host -> dev.flint.term.core.tunnelPing(t.id, host, 4u) },
                    onPort = { host, p -> dev.flint.term.core.tunnelPortCheck(t.id, host, p.toUShort(), 6u) },
                    onResolve = { name -> dev.flint.term.core.tunnelResolve(t.id, name) },
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (st?.running == true) {
                        OutlinedButton(onClick = { scope.launch { app.tunnels.stop(t.id) } }, Modifier.weight(1f)) { Text(stringResource(R.string.tunnelsscreen_stop)) }
                    } else {
                        Button(onClick = { scope.launch { app.tunnels.start(t.id).onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() } } }, Modifier.weight(1f)) { Text(stringResource(R.string.tunnelsscreen_start)) }
                    }
                    OutlinedButton(onClick = { detail = null; editing = t }, Modifier.weight(1f)) { Text(stringResource(R.string.tunnelsscreen_edit)) }
                }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { app.store.deleteTunnel(t.id); detail = null }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Delete, null, Modifier.width(18.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.tunnelsscreen_delete_tunnel), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    editing?.let { t -> TunnelEditor(t, onDismiss = { editing = null }) }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column(Modifier.padding(bottom = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = CodeStyle.copy(fontSize = 12.sp))
    }
}

private fun describe(st: TunnelStats?, info: dev.flint.term.core.TunnelInfo?): String {
    if (st == null || !st.running) return "Stopped" + (info?.let { "  ·  ${it.endpoint}" } ?: "")
    val hs = st.lastHandshakeSecs?.toLong()
    return when {
        hs == null -> "Connecting… no handshake yet"
        hs < 60 -> "Up  ·  handshake ${hs}s ago"
        hs < 3600 -> "Up  ·  handshake ${hs / 60} min ago"
        else -> "Idle  ·  last handshake ${hs / 3600} h ago"
    }
}

private const val TEMPLATE = """[Interface]
PrivateKey = %s
Address = 10.8.0.2/24
DNS = 10.8.0.1

[Peer]
PublicKey = <server public key>
AllowedIPs = 10.8.0.0/24, 192.168.1.0/24
Endpoint = vpn.example.com:51820
PersistentKeepalive = 25
"""

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TunnelEditor(initial: Tunnel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial.name) }
    var config by remember { mutableStateOf(initial.config) }
    var error by remember { mutableStateOf<String?>(null) }
    var publicKey by remember { mutableStateOf<String?>(null) }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val text = runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() }.getOrNull()
                withContext(Dispatchers.Main) {
                    if (text == null) Toast.makeText(context, context.getString(R.string.tunnelsscreen_could_not_read_file), Toast.LENGTH_SHORT).show()
                    else {
                        config = text
                        if (name.isBlank()) name = uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".conf") ?: ""
                    }
                }
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (initial.config.isBlank()) stringResource(R.string.tunnelsscreen_add_tunnel) else stringResource(R.string.tunnelsscreen_edit_tunnel), style = MaterialTheme.typography.titleLarge)
            Field(name, { name = it }, "Name", placeholder = stringResource(R.string.tunnelsscreen_home_vpn), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, Modifier.weight(1f)) {
                    Icon(Icons.Rounded.FileOpen, null, Modifier.width(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.tunnelsscreen_import_conf))
                }
                OutlinedButton(onClick = {
                    val kp = wgGenerateKeypair()
                    config = TEMPLATE.format(kp.privateKey)
                    publicKey = kp.publicKey
                }, Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Key, null, Modifier.width(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.tunnelsscreen_new_key_pair))
                }
            }
            publicKey?.let { pk ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(12.dp)) {
                    Text(stringResource(R.string.tunnelsscreen_add_this_public_key_as_a_peer_on_your_wireguard), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SelectionContainer(Modifier.weight(1f)) { Text(pk, style = CodeStyle.copy(fontSize = 12.sp)) }
                        IconButton(onClick = {
                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("public key", pk))
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        }) { Icon(Icons.Rounded.ContentCopy, "Copy") }
                    }
                }
            }
            Field(
                config, { config = it; error = null }, stringResource(R.string.tunnelsscreen_wg_quick_configuration), mono = true, singleLine = false, minLines = 8,
                placeholder = stringResource(R.string.tunnelsscreen_interface_nprivatekey_naddress_10_8_0_2_24_n_n_p),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Text(
                stringResource(R.string.tunnelsscreen_endpoint_allowedips_and_address_are_required_dns),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                enabled = config.isNotBlank(),
                onClick = {
                    app.tunnels.validate(config).onSuccess { info ->
                        app.store.upsertTunnel(initial.copy(name = name.trim().ifBlank { info.endpoint }, config = config.trim()))
                        onDismiss()
                    }.onFailure { error = it.message?.removePrefix("Other(")?.trimEnd(')') ?: "invalid config" }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.tunnelsscreen_save)) }
        }
    }
}

/** Add or change a saved proxy. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProxySheet(proxy: SavedProxy, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as App
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by remember(proxy.id) { mutableStateOf(proxy) }
    val existing = app.store.proxy(proxy.id) != null
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (existing) "Proxy" else stringResource(R.string.tunnelsscreen_new_proxy), style = MaterialTheme.typography.titleLarge)
            Segmented(listOf("SOCKS5", "HTTP"), if (draft.type == ProxyType.HTTP) 1 else 0) {
                draft = draft.copy(type = if (it == 1) ProxyType.HTTP else ProxyType.SOCKS5)
            }
            Field(draft.name, { draft = draft.copy(name = it) }, "Name (optional)", placeholder = stringResource(R.string.tunnelsscreen_work_proxy))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(
                    draft.host, { draft = draft.copy(host = it) }, "Host", Modifier.weight(1f), mono = true,
                    placeholder = stringResource(R.string.tunnelsscreen_proxy_example_com),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Field(
                    draft.port.toString(),
                    { v -> v.filter { it.isDigit() }.take(5).toIntOrNull()?.let { draft = draft.copy(port = it) } },
                    "Port", Modifier.width(104.dp), mono = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(
                    draft.username, { draft = draft.copy(username = it) }, stringResource(R.string.tunnelsscreen_user_optional), Modifier.weight(1f),
                    autofill = ContentType.Username,
                )
                Field(
                    draft.password, { draft = draft.copy(password = it) }, "Password", Modifier.weight(1f),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    autofill = ContentType.Password,
                )
            }
            Text(
                stringResource(R.string.tunnelsscreen_only_the_first_hop_goes_through_the_proxy_and_a),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                enabled = draft.usable,
                onClick = {
                    app.store.upsertProxy(draft.copy(host = draft.host.trim(), name = draft.name.trim(), username = draft.username.trim()))
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.tunnelsscreen_save)) }
            if (existing) {
                TextButton(onClick = { app.store.deleteProxy(draft.id); onDismiss() }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Delete, null, Modifier.width(18.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.tunnelsscreen_delete_this_proxy), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** One quiet line under a section heading, where a full empty state would shout. */
@Composable
private fun SectionHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 26.dp, vertical = 6.dp),
    )
}
