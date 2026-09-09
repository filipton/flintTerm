package dev.flint.term.ui

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
        topBar = { AppHeader(title = "VPN & tunnels", onBack = { nav.popBackStack() }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (app.tailscale.available) addSheet = true else editing = Tunnel() },
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text("Add") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(20.dp),
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 110.dp)) {
            item { TailscaleSection(onOpen = { tailscaleDetail = it }) }
            item { GroupLabel("WireGuard") }
            if (tunnels.isEmpty()) {
                // A whole empty state per section turns this screen into three
                // screens; one line says the same thing and leaves room for the
                // sections that do have something in them.
                item { SectionHint("Paste a wg-quick config with Add — only this app's traffic goes through it, no system VPN and no root.") }
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
                                        ) { Text("Test") }
                                    }
                                },
                            )
                            tunnelResults[t.id]?.let { r -> TestResult(r) }
                            if (i < tunnels.lastIndex) RowDivider()
                        }
                    }
                }
                item { SectionHint("A tunnel starts by itself when a host that uses it connects.") }
            }
            // Proxies belong here for the same reason tunnels do: one entry, used
            // by whichever hosts need it, instead of retyped into each.
            item { GroupLabel("Proxies") }
            item {
                if (proxies.isEmpty()) {
                    SectionHint("A SOCKS5 or HTTP proxy kept here can be picked by any host, so the same one is set up once.")
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
            title = "Add",
            subtitle = "Both route only this app's traffic — no system VPN, no root.",
            actions = listOf(
                SheetAction("WireGuard tunnel", Icons.Rounded.VpnLock, subtitle = "Paste a wg-quick config") {
                    addSheet = false; editing = Tunnel()
                },
                SheetAction("Tailscale account", Icons.Rounded.Hub, subtitle = "Join a tailnet with a browser login or an auth key") {
                    addSheet = false; addTailscale()
                },
                SheetAction("Proxy", Icons.Rounded.Lan, subtitle = "A SOCKS5 or HTTP proxy hosts can be dialled through") {
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
                    DetailLine("Allowed IPs", info.allowedIps.joinToString(", "))
                    DetailLine("DNS", info.dns.joinToString(", ").ifBlank { "none — use IP addresses" })
                    DetailLine("MTU", info.mtu.toString())
                    Text("This device's public key", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SelectionContainer(Modifier.weight(1f)) { Text(info.publicKey, style = CodeStyle.copy(fontSize = 12.sp)) }
                        IconButton(onClick = {
                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("public key", info.publicKey))
                            Toast.makeText(context, "Public key copied", Toast.LENGTH_SHORT).show()
                        }) { Icon(Icons.Rounded.ContentCopy, "Copy") }
                    }
                }
                st?.let { s ->
                    if (s.running) {
                        DetailLine("Traffic", "${humanBytes(s.txBytes.toLong())} sent  ·  ${humanBytes(s.rxBytes.toLong())} received")
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
                        Text("Testing…")
                    } else {
                        Text("Test connection")
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
                        OutlinedButton(onClick = { scope.launch { app.tunnels.stop(t.id) } }, Modifier.weight(1f)) { Text("Stop") }
                    } else {
                        Button(onClick = { scope.launch { app.tunnels.start(t.id).onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() } } }, Modifier.weight(1f)) { Text("Start") }
                    }
                    OutlinedButton(onClick = { detail = null; editing = t }, Modifier.weight(1f)) { Text("Edit") }
                }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { app.store.deleteTunnel(t.id); detail = null }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Delete, null, Modifier.width(18.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text("Delete tunnel", color = MaterialTheme.colorScheme.error)
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
                    if (text == null) Toast.makeText(context, "Could not read file", Toast.LENGTH_SHORT).show()
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
            Text(if (initial.config.isBlank()) "Add tunnel" else "Edit tunnel", style = MaterialTheme.typography.titleLarge)
            Field(name, { name = it }, "Name", placeholder = "Home VPN", keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, Modifier.weight(1f)) {
                    Icon(Icons.Rounded.FileOpen, null, Modifier.width(18.dp)); Spacer(Modifier.width(6.dp)); Text("Import .conf")
                }
                OutlinedButton(onClick = {
                    val kp = wgGenerateKeypair()
                    config = TEMPLATE.format(kp.privateKey)
                    publicKey = kp.publicKey
                }, Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Key, null, Modifier.width(18.dp)); Spacer(Modifier.width(6.dp)); Text("New key pair")
                }
            }
            publicKey?.let { pk ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(12.dp)) {
                    Text("Add this public key as a peer on your WireGuard server:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                config, { config = it; error = null }, "wg-quick configuration", mono = true, singleLine = false, minLines = 8,
                placeholder = "[Interface]\nPrivateKey = …\nAddress = 10.8.0.2/24\n\n[Peer]\nPublicKey = …\nEndpoint = host:51820\nAllowedIPs = 0.0.0.0/0",
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Text(
                "Endpoint, AllowedIPs and Address are required. DNS is used to resolve host names inside the tunnel. PreUp/PostUp lines are ignored.",
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
            ) { Text("Save") }
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
            Text(if (existing) "Proxy" else "New proxy", style = MaterialTheme.typography.titleLarge)
            Segmented(listOf("SOCKS5", "HTTP"), if (draft.type == ProxyType.HTTP) 1 else 0) {
                draft = draft.copy(type = if (it == 1) ProxyType.HTTP else ProxyType.SOCKS5)
            }
            Field(draft.name, { draft = draft.copy(name = it) }, "Name (optional)", placeholder = "Work proxy")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(
                    draft.host, { draft = draft.copy(host = it) }, "Host", Modifier.weight(1f), mono = true,
                    placeholder = "proxy.example.com",
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
                    draft.username, { draft = draft.copy(username = it) }, "User (optional)", Modifier.weight(1f),
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
                "Only the first hop goes through the proxy; a jump chain then continues over SSH. Mosh needs SOCKS5 with UDP ASSOCIATE — an HTTP proxy cannot carry it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                enabled = draft.usable,
                onClick = {
                    app.store.upsertProxy(draft.copy(host = draft.host.trim(), name = draft.name.trim(), username = draft.username.trim()))
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
            if (existing) {
                TextButton(onClick = { app.store.deleteProxy(draft.id); onDismiss() }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Delete, null, Modifier.width(18.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text("Delete this proxy", color = MaterialTheme.colorScheme.error)
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
