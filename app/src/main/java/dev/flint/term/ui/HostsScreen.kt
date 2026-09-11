package dev.flint.term.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.flint.term.App
import dev.flint.term.core.SessionState
import kotlinx.coroutines.launch
import dev.flint.term.data.Host
import dev.flint.term.session.TerminalSession
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostsScreen(nav: NavController) {
    val app = LocalContext.current.applicationContext as App
    val hosts by app.store.hosts.collectAsStateWithLifecycle()
    val sessions by app.sessions.sessions.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var sheetHost by remember { mutableStateOf<Host?>(null) }
    var installKeyHost by remember { mutableStateOf<Host?>(null) }
    val serialDevices by app.serial.devices.collectAsStateWithLifecycle()
    var serialTarget by remember { mutableStateOf<dev.flint.term.session.SerialDevice?>(null) }
    val scope = rememberCoroutineScope()
    // Adapters come and go with the cable, so re-scan whenever this screen appears.
    LaunchedEffect(Unit) { app.serial.refresh() }

    val accounts by app.store.accounts.collectAsStateWithLifecycle()
    val hostGroups by app.store.groups.collectAsStateWithLifecycle()
    // Shown as they will be connected: a host that borrows its login from an
    // account should say so in its address line rather than look user-less.
    val filtered = remember(hosts, query, accounts, hostGroups) {
        val q = query.trim().lowercase()
        hosts.map { app.store.effective(it) }
            .filter { q.isEmpty() || it.displayName.lowercase().contains(q) || it.hostname.lowercase().contains(q) || app.store.groupName(it).lowercase().contains(q) }
            .sortedWith(
                compareBy<Host> { app.store.groupName(it).lowercase() }
                    .thenByDescending { it.lastConnected }
                    .thenBy { it.displayName.lowercase() },
            )
    }
    val groups = remember(filtered, hostGroups) { filtered.groupBy { app.store.groupName(it) } }
    // What has been typed may be somewhere to go rather than something to find.
    val quick = remember(query, hosts) {
        dev.flint.term.session.QuickConnect.parse(query)?.takeIf { dev.flint.term.session.QuickConnect.saved(hosts, it) == null }
    }

    val settings by app.store.settings.collectAsStateWithLifecycle()
    // Servers announcing themselves on this network: browsed while this screen
    // is up, and again whenever the list is pulled down.
    val scanner = dev.flint.term.session.rememberNearbyScanner(settings.discoverNearbyHosts)
    val announced by scanner.found.collectAsStateWithLifecycle()
    val scanning by scanner.scanning.collectAsStateWithLifecycle()
    val nearby = remember(announced, hosts) { dev.flint.term.session.Discovery.offer(announced, hosts) }
    val pullState = rememberPullToRefreshState()
    val active = sessions.filter { !it.isFinished }

    // "Can I even reach this?" for the rows that are not a session already.
    // Only what is on screen is asked, and only hosts the phone can dial
    // directly — see `session/Reachability.kt` for who is left out and why.
    val listState = rememberScreenListState("hosts")
    val reach by dev.flint.term.session.Reachability.shared.state.collectAsStateWithLifecycle()
    val net by app.network.state.collectAsStateWithLifecycle()
    val onScreen by remember(groups) {
        derivedStateOf {
            listState.layoutInfo.visibleItemsInfo
                .mapNotNull { (it.key as? String)?.takeIf { k -> k.startsWith("g-") } }
                .flatMap { groups[it.removePrefix("g-")].orEmpty() }
        }
    }
    var recheck by remember { mutableStateOf(0) }
    LaunchedEffect(onScreen, net, recheck) {
        dev.flint.term.session.Reachability.shared.probe(onScreen, app.sessions.savingData(), net.online)
    }

    // Files arriving from the share sheet turn the list into a "pick a destination" screen.
    val sharing = App.pendingShare.isNotEmpty()

    // Using a host as a VPN needs Android's own consent first, and that only
    // comes back to an activity — so the host waits here until it is given.
    val vpn by dev.flint.term.session.SshVpnService.state.collectAsStateWithLifecycle()
    var vpnPending by remember { mutableStateOf<Host?>(null) }
    var moreSheet by remember { mutableStateOf(false) }
    var importSheet by remember { mutableStateOf(false) }
    val vpnConsent = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val host = vpnPending
        vpnPending = null
        if (result.resultCode == android.app.Activity.RESULT_OK && host != null) {
            dev.flint.term.session.SshVpnService.start(app, host.id)
        }
    }
    fun useAsVpn(host: Host) {
        val consent = dev.flint.term.session.SshVpnService.consent(app)
        if (consent == null) {
            dev.flint.term.session.SshVpnService.start(app, host.id)
        } else {
            vpnPending = host
            vpnConsent.launch(consent)
        }
    }
    // A failure has to be seen once; leaving it in the state would put it back
    // on screen every time the list is recomposed.
    LaunchedEffect(vpn.error) {
        vpn.error?.let {
            android.widget.Toast.makeText(app, it, android.widget.Toast.LENGTH_LONG).show()
            dev.flint.term.session.SshVpnService.clearError()
        }
    }

    fun connect(host: Host) {
        val s = app.sessions.openSsh(host)
        nav.navigate(if (sharing) Routes.sftp(s.id) else Routes.terminal(s.id))
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppHeader(
                title = "flintTerm",
                actions = {
                    CommandPaletteButton()
                    IconButton(onClick = { nav.navigate(Routes.TUNNELS) }) { Icon(Icons.Rounded.VpnLock, "VPNs and proxies") }
                    IconButton(onClick = { moreSheet = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { nav.navigate(Routes.hostEdit("new")) },
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text("New host") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(20.dp),
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // A pull is "look again": it re-scans the network when discovery
                // is on, and asks every host on screen a second time either way.
                .pullToRefresh(scanning, pullState) {
                    dev.flint.term.session.Reachability.shared.invalidate()
                    recheck++
                    if (settings.discoverNearbyHosts) scanner.scan()
                },
            contentPadding = PaddingValues(bottom = 110.dp),
        ) {
            item { SearchField(query, { query = it }, "Search hosts", Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }

            quick?.let { target ->
                item(key = "quick") {
                    Group {
                        GroupRow(
                            title = "Connect to ${target.target}",
                            subtitle = "Not saved. The session can save it afterwards",
                            icon = Icons.Rounded.Bolt,
                            iconTint = MaterialTheme.colorScheme.tertiary,
                            onClick = { query = ""; connect(target) },
                        )
                    }
                }
            }

            if (sharing) {
                item {
                    val pending = App.pendingShare.toList()
                    val names = remember(pending) { pending.map { app.transfers.nameFor(it) } }
                    ShareBanner(names) { App.pendingShare.clear() }
                }
            }

            if (active.isNotEmpty()) {
                item { GroupLabel("Active sessions") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(active, key = { it.id }) { s ->
                            SessionChip(
                                s,
                                onClick = { nav.navigate(Routes.terminal(s.id)) },
                                onClose = { app.sessions.remove(s.id) },
                            )
                        }
                    }
                }
            }

            if (vpn.running) {
                item {
                    Group("VPN") {
                        GroupRow(
                            title = "Traffic goes through ${vpn.label}",
                            subtitle = vpn.stats?.let { st ->
                                val active = if (st.active == 1UL) "1 connection" else "${st.active} connections"
                                "$active  ·  ${humanBytes(st.sent.toLong())} sent  ·  ${humanBytes(st.received.toLong())} received"
                            } ?: if (vpn.connecting) "Connecting…" else "Starting…",
                            icon = Icons.Rounded.VpnLock,
                            iconTint = Status.online,
                            trailing = {
                                TextButton(onClick = { dev.flint.term.session.SshVpnService.stop(app) }) { Text("Stop") }
                            },
                        )
                    }
                }
            }

            item {
                Group("This device") {
                    GroupRow(
                        title = "Local shell",
                        subtitle = "/system/bin/sh on this device",
                        icon = Icons.Rounded.Terminal,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        onClick = {
                            val s = app.sessions.openLocal()
                            nav.navigate(Routes.terminal(s.id))
                        },
                    )
                    serialDevices.forEach { d ->
                        RowDivider()
                        GroupRow(
                            title = d.label,
                            subtitle = "USB serial  ·  ${d.ids}",
                            icon = Icons.Rounded.Cable,
                            iconTint = MaterialTheme.colorScheme.tertiary,
                            onClick = { serialTarget = d },
                        )
                    }
                }
            }

            if (hosts.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Rounded.Dns,
                        title = "No hosts yet",
                        body = "Add a server and connect with a tap. Keys, jump hosts and port forwards live on the host.",
                        actionLabel = "Add your first host",
                        onAction = { nav.navigate(Routes.hostEdit("new")) },
                    )
                }
            } else if (filtered.isEmpty()) {
                item { EmptyState(Icons.Rounded.Search, "Nothing matches", "Try a different name or address.") }
            }

            groups.forEach { (group, list) ->
                item(key = "g-$group") {
                    val record = hostGroups.firstOrNull { it.name == group && group.isNotBlank() }
                    Group(
                        title = group.ifBlank { if (groups.size > 1) "Other" else "Hosts" },
                        // The heading is the way into what the group shares.
                        onTitleClick = record?.let { { nav.navigate(Routes.groupEdit(it.id)) } },
                    ) {
                        list.forEachIndexed { i, host ->
                            HostRow(
                                host = host,
                                via = host.jumpHostId?.let { id -> hosts.firstOrNull { it.id == id } },
                                connected = active.any { it.host?.id == host.id && it.isConnected },
                                reach = reach[host.id] ?: dev.flint.term.session.Reach.UNKNOWN,
                                onClick = { connect(host) },
                                onMore = { sheetHost = host },
                            )
                            if (i < list.lastIndex) RowDivider()
                        }
                    }
                }
            }

            if (settings.discoverNearbyHosts && (nearby.isNotEmpty() || scanning)) {
                item(key = "nearby") {
                    Group("Nearby") {
                        if (nearby.isEmpty()) {
                            GroupRow(
                                title = "Looking for hosts on this network…",
                                icon = Icons.Rounded.Wifi,
                                iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        nearby.forEachIndexed { i, service ->
                            GroupRow(
                                title = service.name,
                                subtitle = "${service.address}:${service.port}",
                                subtitleMono = true,
                                icon = Icons.Rounded.Wifi,
                                iconTint = MaterialTheme.colorScheme.tertiary,
                                // Announcing a port is not the same as knowing how
                                // to log in, so this fills in the editor rather
                                // than dialing.
                                onClick = {
                                    App.hostDraft = dev.flint.term.session.Discovery.host(service)
                                    nav.navigate(Routes.hostEdit("new"))
                                },
                            )
                            if (i < nearby.lastIndex) RowDivider()
                        }
                    }
                }
            }
        }
    }

    serialTarget?.let { d ->
        SerialConnectDialog(
            device = d,
            onDismiss = { serialTarget = null },
            onConnected = { session -> serialTarget = null; nav.navigate(Routes.terminal(session.id)) },
        )
    }
    InstallKeyHost(installKeyHost) { installKeyHost = null }
    if (moreSheet) {
        ActionSheet(
            onDismiss = { moreSheet = false },
            title = "Hosts",
            actions = listOf(
                SheetAction(
                    "Run on many hosts",
                    Icons.Rounded.PlayArrow,
                    subtitle = "One command across several servers, each answer beside its host",
                ) { moreSheet = false; nav.navigate(Routes.BROADCAST) },
                SheetAction(
                    "Groups",
                    Icons.Rounded.Folder,
                    subtitle = "What the hosts in a group share: a jump host, a VPN, a proxy, an account",
                ) { moreSheet = false; nav.navigate(Routes.GROUPS) },
                SheetAction(
                    "Transfers",
                    Icons.Rounded.SwapHoriz,
                    subtitle = "Files on their way to or from a host",
                ) { moreSheet = false; nav.navigate(Routes.TRANSFERS) },
                SheetAction(
                    "Import from OpenSSH",
                    Icons.Rounded.FileDownload,
                    subtitle = "Hosts from ~/.ssh/config, trusted keys from known_hosts",
                ) { moreSheet = false; importSheet = true },
            ),
        )
    }
    if (importSheet) ImportSheet { importSheet = false }
    sheetHost?.let { host ->
        ActionSheet(
            onDismiss = { sheetHost = null },
            header = {
                Row(Modifier.padding(horizontal = 24.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    HostGlyph(host, 40)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(host.displayName, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(host.target, style = CodeStyle.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                Spacer(Modifier.height(8.dp))
            },
            actions = listOfNotNull(
                SheetAction("Connect", Icons.Rounded.Terminal) { sheetHost = null; connect(host) },
                if (host.wol.enabled) SheetAction(
                    "Wake up",
                    Icons.Rounded.Bolt,
                    subtitle = "Send the magic packet. " + dev.flint.term.session.Wol.decide(host, host.jumpHostId != null).reason,
                ) {
                    sheetHost = null
                    val jump = host.jumpHostId?.let { id -> hosts.firstOrNull { it.id == id } }
                    if (dev.flint.term.session.Wol.decide(host, jump != null).source == dev.flint.term.data.WolSource.JUMP_HOST && jump != null) {
                        // Run the script in a visible session on the jump host.
                        val s = app.sessions.openSsh(jump, extraStartup = dev.flint.term.session.Wol.remoteScript(host.wol, dev.flint.term.session.Wol.wakeTarget(host)))
                        nav.navigate(Routes.terminal(s.id))
                    } else {
                        Thread {
                            val r = runCatching { dev.flint.term.session.Wol.sendFromPhone(host.wol, dev.flint.term.session.Wol.candidates(host)) }
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                android.widget.Toast.makeText(app, r.fold({ "Magic packet sent to ${host.wol.mac}" }, { "Wake-on-LAN failed: ${it.message}" }), android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }.start()
                    }
                } else null,
                SheetAction("Browse files", Icons.Rounded.Folder, subtitle = "Open an SFTP session") {
                    sheetHost = null
                    val s = app.sessions.openSsh(host)
                    nav.navigate(Routes.sftp(s.id))
                },
                SheetAction("Status", Icons.Rounded.Speed, subtitle = "Load, memory, disks and what is running") {
                    sheetHost = null
                    val s = app.sessions.openSsh(host)
                    nav.navigate(Routes.server(s.id))
                },
                SheetAction("Install SSH key", Icons.Rounded.Key, subtitle = "Put one of your keys into authorized_keys on the server") { sheetHost = null; installKeyHost = host },
                SheetAction("Edit", Icons.Rounded.Edit) { sheetHost = null; nav.navigate(Routes.hostEdit(host.id)) },
                SheetAction("Add to home screen", Icons.Rounded.Add) {
                    sheetHost = null
                    if (!dev.flint.term.session.Shortcuts.pin(app, host)) android.widget.Toast.makeText(app, "This launcher does not support pinned shortcuts", android.widget.Toast.LENGTH_SHORT).show()
                },
                SheetAction("Duplicate", Icons.Rounded.ContentCopy) {
                    sheetHost = null
                    app.store.upsertHost(host.copy(id = UUID.randomUUID().toString(), label = (host.label.ifBlank { host.displayName }) + " copy", lastConnected = 0))
                },
                SheetAction("Delete", Icons.Rounded.Delete, danger = true) { sheetHost = null; app.store.deleteHost(host.id) },
            ),
        )
    }
}

@Composable
private fun InstallKeyHost(host: Host?, onDone: () -> Unit) {
    if (host != null) InstallKeyDialog(raw = host, onDismiss = onDone)
}


@Composable
private fun SessionChip(session: TerminalSession, onClick: () -> Unit, onClose: () -> Unit) {
    val state by session.state.collectAsStateWithLifecycle()
    val title by session.title.collectAsStateWithLifecycle()
    Row(
        Modifier
            .widthIn(min = 150.dp, max = 240.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .combinedClickableCompat(onClick)
            .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(state)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(session.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                title ?: when (state) {
                    is SessionState.Connecting -> "Connecting…"
                    is SessionState.Connected -> "Connected"
                    is SessionState.Disconnected -> "Ended"
                },
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onClose, Modifier.size(32.dp)) { Icon(Icons.Rounded.Close, "Close", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLong: (() -> Unit)? = null): Modifier =
    combinedClickable(onClick = onClick, onLongClick = onLong)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HostRow(
    host: Host,
    via: Host?,
    connected: Boolean,
    reach: dev.flint.term.session.Reach,
    onClick: () -> Unit,
    onMore: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onMore)
            .padding(start = 14.dp, top = 9.dp, bottom = 9.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HostGlyph(host)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(host.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (connected) {
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(Status.online))
                } else {
                    // A session on the host is the better answer to the same
                    // question, so the probe only speaks when there is none.
                    ReachDot(reach)
                }
                // "3 h ago" sits up here rather than on a line of its own: it is
                // the least of what a row says and was costing a third of it.
                relativeTime(host.lastConnected)?.let {
                    Spacer(Modifier.weight(1f))
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            Text(host.target, style = CodeStyle.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val bits = buildList {
                if (via != null) add("via ${via.displayName}")
                // What the machine turned out to be, once it has been connected to
                // once: more use than any label, and it costs a line nobody else
                // was using.
                host.detectedOs.takeIf { it.isNotBlank() }?.let { add(it.take(28)) }
                if (host.tunnelId != null) add("WireGuard")
                if (host.wol.enabled) add("WOL")
                if (host.forwards.isNotEmpty()) add("${host.forwards.size} forward${if (host.forwards.size > 1) "s" else ""}")
            }
            if (bits.isNotEmpty()) {
                Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (via != null) {
                        Icon(Icons.Rounded.AltRoute, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(bits.joinToString("  ·  "), style = MaterialTheme.typography.labelSmall, color = if (via != null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        IconButton(onClick = onMore) { Icon(Icons.Rounded.MoreVert, "More", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/**
 * What a plain TCP connect found, drawn as quietly as it can be: a dim dot for
 * a host that answered, a hollow ring for one that did not, and — the important
 * case — nothing whatsoever for a host nobody has asked about.
 */
@Composable
private fun ReachDot(reach: dev.flint.term.session.Reach) {
    if (reach == dev.flint.term.session.Reach.UNKNOWN) return
    Spacer(Modifier.width(8.dp))
    val color = if (reach == dev.flint.term.session.Reach.REACHABLE) Status.online else Status.offline
    Box(
        Modifier
            .size(7.dp)
            .clip(RoundedCornerShape(50))
            .then(
                if (reach == dev.flint.term.session.Reach.REACHABLE) {
                    Modifier.background(color.copy(alpha = 0.5f))
                } else {
                    Modifier.border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(50))
                },
            ),
    )
}

@Suppress("unused")
private val Transparent = Color.Transparent

/** Names the shared files and says why the host list is asking for a destination. */
@Composable
private fun ShareBanner(names: List<String?>, onCancel: () -> Unit) {
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
                "Pick a host, then a folder",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                maxLines = 1,
            )
        }
        IconButton(onClick = onCancel) {
            Icon(Icons.Rounded.Close, "Cancel upload", tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}
