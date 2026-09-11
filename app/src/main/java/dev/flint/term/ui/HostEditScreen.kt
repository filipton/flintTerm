package dev.flint.term.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.DoorFront
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.flint.term.App
import dev.flint.term.data.AuthType
import dev.flint.term.data.EnvEntry
import dev.flint.term.data.ForwardType
import dev.flint.term.data.requestable
import dev.flint.term.data.Host
import dev.flint.term.data.HostAddress
import dev.flint.term.data.HostIcon
import dev.flint.term.data.Schemes
import dev.flint.term.data.KnockSettings
import dev.flint.term.data.PortForward
import dev.flint.term.data.Protocol
import dev.flint.term.data.ProxyType
import dev.flint.term.data.TerminalImages
import dev.flint.term.data.TunnelMode
import dev.flint.term.data.WolSettings
import dev.flint.term.data.WolSource
import dev.flint.term.session.PortKnock
import dev.flint.term.session.Wol
import dev.flint.term.terminal.TerminalView
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Power
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Toast
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HostEditScreen(nav: NavController, id: String) {
    val app = LocalContext.current.applicationContext as App
    val existing = remember(id) { app.store.host(id) }
    // Values handed in by an ssh:// link for a brand-new host.
    val draft = remember(id) { if (existing == null) App.hostDraft.also { App.hostDraft = null } else null }
    val identities by app.store.identities.collectAsStateWithLifecycle()
    val hosts by app.store.hosts.collectAsStateWithLifecycle()

    var label by remember { mutableStateOf(existing?.label ?: "") }
    var hostname by remember { mutableStateOf(existing?.hostname ?: draft?.hostname ?: "") }
    var port by remember { mutableStateOf((existing?.port ?: draft?.port ?: 22).toString()) }
    var username by remember { mutableStateOf(existing?.username ?: draft?.username ?: "") }
    var groupId by remember { mutableStateOf(existing?.groupId) }
    var newGroup by remember { mutableStateOf(false) }
    var color by remember { mutableStateOf(existing?.color ?: 0) }
    var icon by remember { mutableStateOf(existing?.icon ?: HostIcon.SERVER) }
    var authType by remember { mutableStateOf(existing?.authType ?: AuthType.PASSWORD) }
    var password by remember { mutableStateOf(existing?.password ?: "") }
    var showPassword by remember { mutableStateOf(false) }
    var identityId by remember { mutableStateOf(existing?.identityId) }
    var accountId by remember { mutableStateOf(existing?.accountId) }
    var accountSheet by remember { mutableStateOf(false) }
    // A brand-new account being written without leaving the host being edited.
    var newAccount by remember { mutableStateOf<dev.flint.term.data.Account?>(null) }
    var jumpHostId by remember { mutableStateOf(existing?.jumpHostId) }
    var preCommand by remember { mutableStateOf(existing?.preConnectCommand ?: "") }
    var waitSeconds by remember { mutableStateOf(existing?.waitForHostSeconds ?: 90) }
    var proxyId by remember { mutableStateOf(existing?.proxyId) }
    var proxySheet by remember { mutableStateOf(false) }
    var startup by remember { mutableStateOf(existing?.startupCommand ?: "") }
    var forwards by remember { mutableStateOf(existing?.forwards ?: emptyList()) }
    var editingForward by remember { mutableStateOf<PortForward?>(null) }
    var keySheet by remember { mutableStateOf(false) }
    var jumpSheet by remember { mutableStateOf(false) }
    var vpnSheet by remember { mutableStateOf(false) }
    var tunnelId by remember { mutableStateOf(existing?.tunnelId) }
    var tunnelMode by remember { mutableStateOf(existing?.tunnelMode ?: TunnelMode.WHEN_NEEDED) }
    var wol by remember { mutableStateOf(existing?.wol ?: WolSettings()) }
    var knock by remember { mutableStateOf(existing?.knock ?: KnockSettings()) }
    var askBeforeSigning by remember { mutableStateOf(existing?.askBeforeAgentSigning) }
    var tailscaleId by remember { mutableStateOf(existing?.tailscaleId) }
    var persistent by remember { mutableStateOf(existing?.persistent ?: false) }
    var forwardAgent by remember { mutableStateOf(existing?.forwardAgent ?: false) }
    var showInFiles by remember { mutableStateOf(existing?.showInFiles ?: false) }
    var recordSessions by remember { mutableStateOf(existing?.recordSessions ?: false) }
    var addresses by remember { mutableStateOf(existing?.addresses ?: emptyList()) }
    var openAddress by remember { mutableStateOf<String?>(null) }
    // Which address the VPN sheet is picking for: null is the host's own.
    var vpnFor by remember { mutableStateOf<String?>(null) }
    var protocol by remember { mutableStateOf(existing?.protocol ?: Protocol.SSH) }
    var mosh by remember { mutableStateOf(existing?.mosh ?: false) }
    var hostTheme by remember { mutableStateOf(existing?.theme) }
    var themeSheet by remember { mutableStateOf(false) }
    var lookSheet by remember { mutableStateOf(false) }
    var tmuxSession by remember { mutableStateOf(existing?.tmuxSession ?: "main") }
    var tmuxResumeLast by remember { mutableStateOf(existing?.tmuxResumeLast ?: true) }
    var tmuxPrefix by remember { mutableStateOf(existing?.tmuxPrefix ?: "C-b") }
    // The three per-host terminal overrides: null is "no opinion", and the app
    // setting answers for them.
    var keyboardProtocol by remember { mutableStateOf(existing?.keyboardProtocol) }
    var fixtermsCtrlKeys by remember { mutableStateOf(existing?.fixtermsCtrlKeys) }
    var terminalImages by remember { mutableStateOf(existing?.terminalImages) }
    var tmuxControls by remember { mutableStateOf(existing?.tmuxControls) }
    // 0 is "follow the app setting", which is the slider's bottom step rather
    // than a switch of its own — the size and whether there is one are one choice.
    var fontSizeSp by remember { mutableStateOf(existing?.fontSizeSp ?: 0f) }
    var patterns by remember { mutableStateOf(existing?.notifyPatterns?.joinToString("\n") ?: "") }
    var startupSnippetIds by remember { mutableStateOf(existing?.startupSnippetIds ?: emptyList()) }
    var snippetSheet by remember { mutableStateOf(false) }
    var env by remember { mutableStateOf(existing?.env ?: emptyList()) }
    val tunnels by app.store.tunnels.collectAsStateWithLifecycle()
    val tailnets by app.store.tailscaleProfiles.collectAsStateWithLifecycle()
    val proxies by app.store.proxies.collectAsStateWithLifecycle()
    val accounts by app.store.accounts.collectAsStateWithLifecycle()
    val snippets by app.store.snippets.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var detecting by remember { mutableStateOf(false) }
    var detected by remember { mutableStateOf<List<Triple<String, String, String>>?>(null) } // iface, mac, broadcast

    val draftId = existing?.id ?: remember { java.util.UUID.randomUUID().toString() }
    val preview = Host(id = draftId, label = label, hostname = hostname, username = username, color = color, icon = icon, protocol = protocol)
    fun draft() = (existing ?: Host(id = draftId)).copy(
        hostname = hostname.trim(), port = port.toIntOrNull() ?: 22, username = username.trim(), authType = authType, password = password,
        identityId = if (authType == AuthType.KEY) identityId else null, accountId = accountId,
        jumpHostId = jumpHostId, preConnectCommand = preCommand.trim(),
        waitForHostSeconds = 0, proxyId = proxyId, tunnelId = tunnelId, tailscaleId = tailscaleId, wol = WolSettings(),
    )
    // Telnet asks for its own credentials on the wire, so it needs neither user nor key.
    val telnet = protocol == Protocol.TELNET
    // A chosen account supplies the login, so this host's own fields are left
    // alone and not asked about.
    val account = accounts.firstOrNull { it.id == accountId }

    // Field-level problems. They only become visible once Save has been pressed, so a
    // half-typed form is not covered in red while you are still filling it in.
    var showErrors by remember { mutableStateOf(false) }
    val hostError = if (hostname.isBlank()) "Required" else null
    val portError = if (port.toIntOrNull() !in 1..65535) "1–65535" else null
    val userError = when {
        telnet || account != null -> null
        username.isBlank() -> "Required"
        else -> null
    }
    val keyError = if (!telnet && account == null && authType == AuthType.KEY && identityId == null) "Pick a key" else null
    val macError = when {
        !wol.enabled -> null
        wol.mac.isBlank() -> "Required to wake this machine"
        !Wol.isValidMac(wol.mac) -> "Six hex pairs, e.g. a8:a1:59:23:8e:88"
        else -> null
    }
    // What an empty broadcast will resolve to right now, so "Auto" is not a black box.
    val knockProblem = remember(knock.enabled, knock.sequence) {
        if (knock.enabled) PortKnock.problem(knock.sequence) else null
    }
    val autoBroadcastHint = remember(hostname, wol.broadcast, addresses) {
        if (wol.broadcast.isNotBlank()) null
        else (listOf(hostname) + addresses.map { it.hostname })
            .firstNotNullOfOrNull { Wol.autoBroadcast(it) }
            ?.let { "Auto, $it on your current network" }
            ?: "Auto, one packet per interface, because no address is on a network this phone is on"
    }

    /** Enough to reach the machine — what "Detect MAC" needs, and never gated on the MAC itself. */
    val connectable = hostError == null && portError == null && userError == null && keyError == null
    val valid = connectable && macError == null
    // Shown in a toast when Save is refused, so a problem scrolled off screen still gets named.
    val firstProblem = when {
        hostError != null -> "The host address is required"
        portError != null -> "The port must be between 1 and 65535"
        userError != null -> "A username is required"
        keyError != null -> "Choose an SSH key, or switch to password auth"
        macError != null -> "Wake on LAN needs a valid MAC address"
        else -> null
    }

    // Hosts that may act as jump host: not this one, and not anything that already routes through it.
    val jumpCandidates = remember(hosts, draftId) {
        hosts.filter { h ->
            h.id != draftId && app.store.jumpChain(h).none { it.id == draftId }
        }
    }
    val jump = hosts.firstOrNull { it.id == jumpHostId }
    val hostGroups by app.store.groups.collectAsStateWithLifecycle()
    val settings by app.store.settings.collectAsStateWithLifecycle()
    // What "App default" resolves to for this host: its group's scheme, else the app's.
    val inheritedTheme = hostGroups.firstOrNull { it.id == groupId }?.theme ?: settings.theme

    fun save() {
        app.store.upsertHost(
            (existing ?: Host(id = draftId)).copy(
                label = label.trim(), hostname = hostname.trim(), port = port.toInt(), username = username.trim(),
                groupId = groupId, color = color, icon = icon,
                authType = authType, password = password,
                identityId = if (authType == AuthType.KEY) identityId else null,
                accountId = accountId,
                jumpHostId = jumpHostId, preConnectCommand = preCommand.trim(), waitForHostSeconds = waitSeconds,
                proxyId = proxyId,
                startupCommand = startup.trim(), forwards = forwards,
                startupSnippetIds = startupSnippetIds.filter { id -> snippets.any { it.id == id } },
                env = env.requestable(),
                tunnelId = tunnelId,
                tunnelMode = tunnelMode,
                tailscaleId = tailscaleId,
                persistent = persistent,
                forwardAgent = forwardAgent,
                askBeforeAgentSigning = askBeforeSigning,
                showInFiles = showInFiles,
                recordSessions = recordSessions,
                addresses = addresses.filter { it.hostname.isNotBlank() }.map { it.copy(hostname = it.hostname.trim(), label = it.label.trim()) },
                theme = hostTheme,
                protocol = protocol,
                mosh = mosh && protocol == Protocol.SSH,
                tmuxSession = tmuxSession.trim().ifBlank { "main" },
                tmuxResumeLast = tmuxResumeLast,
                tmuxPrefix = tmuxPrefix.trim().ifBlank { "C-b" },
                keyboardProtocol = keyboardProtocol,
                fixtermsCtrlKeys = fixtermsCtrlKeys,
                terminalImages = terminalImages,
                tmuxControls = tmuxControls,
                fontSizeSp = fontSizeSp,
                notifyPatterns = patterns.lines().map { it.trim() }.filter { it.isNotEmpty() },
                wol = wol.copy(mac = Wol.normalizeMac(wol.mac), broadcast = wol.broadcast.trim()),
                // Stored the way it is sent, so what the field shows next time
                // is what actually leaves the phone.
                knock = knock.copy(sequence = PortKnock.format(PortKnock.parse(knock.sequence))),
            ),
        )
        nav.popBackStack()
    }

    // The editor grew past the point where one long scroll was findable, so the
    // parts that are settings-of-their-own live on pages of their own. They are
    // pages inside this screen rather than routes, because the draft being
    // edited has not been saved yet and navigating away would lose it.
    var page by rememberSaveable { mutableStateOf(EditorPage.Main) }
    // The arrow in the bar walks back a page before it leaves; the system back
    // gesture has to do the same, or a swipe from a subpage throws away a host
    // that was never saved.
    androidx.activity.compose.BackHandler(enabled = page != EditorPage.Main) { page = EditorPage.Main }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppHeader(
                title = when {
                    page != EditorPage.Main -> page.title
                    existing == null -> "New host"
                    else -> "Edit host"
                },
                onBack = { if (page != EditorPage.Main) page = EditorPage.Main else nav.popBackStack() },
                actions = {
                    Button(
                        onClick = {
                            if (valid) {
                                save()
                            } else {
                                showErrors = true
                                firstProblem?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                            }
                        },
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text("Save") }
                },
            )
        },
    ) { padding ->
        // One remembered position per page, not one for the screen. Walking into
        // a subpage should start at its top, and walking back out should land
        // where you were reading — a single scroll state cannot do both.
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScreenScroll("host/$id/${page.name}"))) {
            // ---- identity / look ------------------------------------------------
            if (page == EditorPage.Main) Group {
                Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    // The glyph is the control for how this host looks; the badge says so.
                    Box(Modifier.clip(RoundedCornerShape(16.dp)).clickable { lookSheet = true }) {
                        HostGlyph(preview, 52)
                        Box(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.Edit, "Color and icon", Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onPrimary) }
                    }
                    Spacer(Modifier.width(12.dp))
                    Field(label, { label = it }, "Name", placeholder = hostname.ifBlank { "My server" })
                }
                // A group is an object now — it carries a jump host, a VPN and a
                // login for its members — so it is picked rather than typed.
                Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Group", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(hostGroups) { g ->
                            FilterChip(
                                selected = g.id == groupId,
                                onClick = { groupId = if (groupId == g.id) null else g.id },
                                label = { Text(g.label) },
                            )
                        }
                        item {
                            FilterChip(
                                selected = false,
                                onClick = { newGroup = true },
                                label = { Text("New group") },
                                leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(16.dp)) },
                            )
                        }
                    }
                    hostGroups.firstOrNull { it.id == groupId }?.takeIf { it.defaultCount() > 0 }?.let { g ->
                        Text(
                            "Anything this host leaves unset is taken from ${g.label}.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ---- connection --------------------------------------------------------
            if (page == EditorPage.Main) Group("Connection") {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Segmented(listOf("SSH", "Telnet"), protocol.ordinal) { picked ->
                        val next = Protocol.entries[picked]
                        // Move the port along with the protocol unless it was customized.
                        if (port == (if (protocol == Protocol.TELNET) "23" else "22")) {
                            port = if (next == Protocol.TELNET) "23" else "22"
                        }
                        protocol = next
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Field(
                            hostname, { hostname = it }, "Host", Modifier.weight(1f), placeholder = "example.com", mono = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            error = hostError.takeIf { showErrors },
                        )
                        Field(
                            port, { port = it.filter { c -> c.isDigit() }.take(5) }, "Port", Modifier.width(104.dp), mono = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            error = portError.takeIf { showErrors },
                        )
                    }
                    if (protocol == Protocol.SSH && account == null) {
                        Field(
                            username, { username = it }, "Username", placeholder = "root", mono = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                            error = userError.takeIf { showErrors },
                            autofill = ContentType.Username,
                        )
                    }
                }
                // What the machine said it runs, read on the last connection.
                // A fact about the host rather than a setting, so there is
                // nothing to edit — and nothing at all until it has answered once.
                val detectedOs = hosts.firstOrNull { it.id == draftId }?.detectedOs.orEmpty()
                if (detectedOs.isNotBlank()) {
                    RowDivider()
                    GroupRow(
                        title = detectedOs,
                        subtitle = "Read from the host when it was last connected",
                        icon = Icons.Rounded.Memory,
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---- auth --------------------------------------------------------------
            // Telnet devices prompt for their own credentials on the wire.
            if (page == EditorPage.Main && protocol == Protocol.SSH) Group("Authentication") {
                // A login shared with other hosts, or this host's own. The row
                // comes first because it decides whether anything below it is
                // asked at all.
                GroupRow(
                    title = account?.label ?: "This host only",
                    subtitle = account?.let { a ->
                        val how = when (a.authType) {
                            AuthType.KEY -> identities.firstOrNull { it.id == a.identityId }?.name?.ifBlank { "key" } ?: "no key chosen"
                            AuthType.PASSWORD -> "password"
                            AuthType.NONE -> "no authentication"
                        }
                        "${a.username.ifBlank { "no username" }}  ·  $how  ·  shared account"
                    } ?: "Username and key typed in here, used by this host alone",
                    icon = Icons.Rounded.Person,
                    onClick = { accountSheet = true },
                )
                if (account == null) RowDivider()
                if (account == null) Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Segmented(listOf("Password", "SSH key", "None"), authType.ordinal) { authType = AuthType.entries[it] }
                    if (authType == AuthType.KEY) {
                        val selected = identities.firstOrNull { it.id == identityId }
                        val keyMissing = showErrors && keyError != null
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .then(if (keyMissing) Modifier.border(1.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(14.dp)) else Modifier)
                                .clickable { keySheet = true },
                        ) {
                            GroupRow(
                                title = selected?.name ?: if (identities.isEmpty()) "No keys yet" else "Choose a key",
                                subtitle = selected?.fingerprint ?: "Tap to pick or create one",
                                subtitleMono = selected != null,
                                icon = Icons.Rounded.Key,
                            )
                        }
                    }
                    if (authType != AuthType.NONE) {
                        Field(
                            password, { password = it },
                            if (authType == AuthType.KEY) "Password fallback (optional)" else "Password",
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            autofill = ContentType.Password,
                            trailing = {
                                IconButton(onClick = { showPassword = !showPassword }) {
                                    Icon(if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Toggle visibility")
                                }
                            },
                        )
                    }
                }
            }

            // ---- route: jump host / proxy ----------------------------------------------
            if (page == EditorPage.Route) Group("Route") {
                // One VPN row rather than a switch per kind: a host goes through
                // a WireGuard tunnel or a tailnet, never both, so the choice is
                // one list with the kind spelled out underneath.
                val tunnel = tunnels.firstOrNull { it.id == tunnelId }
                val tailnet = tailnets.firstOrNull { it.id == tailscaleId }
                GroupRow(
                    title = "VPN",
                    subtitle = when {
                        tailnet != null -> "${tailnet.name.ifBlank { "Tailscale" }}  ·  Tailscale, so the host can be a MagicDNS name"
                        jump != null && tunnel != null -> "${tunnel.name}  ·  ignored while a jump host is set (its tunnel is used)"
                        tunnel != null -> "${tunnel.name}  ·  WireGuard"
                        else -> "Direct network"
                    },
                    icon = if (tailnet != null) Icons.Rounded.Hub else Icons.Rounded.VpnLock,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    onClick = { vpnFor = null; vpnSheet = true },
                )
                if (tunnel != null && jump == null) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Segmented(TunnelMode.entries.map { it.label }, tunnelMode.ordinal) { tunnelMode = TunnelMode.entries[it] }
                        Text(
                            if (tunnelMode == TunnelMode.WHEN_NEEDED) "Connect directly when the host is on the phone's current network, or when it answers directly. Otherwise use ${tunnel.name}. Good for a home server you also reach over Wi-Fi."
                            else "Every connection goes through ${tunnel.name}.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                RowDivider()
                GroupRow(
                    title = "Other addresses",
                    subtitle = if (addresses.isEmpty()) {
                        "The same machine at another address, such as a LAN address or a tailnet name. Tried in order"
                    } else {
                        "${addresses.size} more, tried in order after ${hostname.ifBlank { "the address above" }}"
                    },
                    icon = Icons.Rounded.SwapHoriz,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    trailing = {
                        TextButton(
                            onClick = {
                                // The port a second address wants is almost always the one
                                // the first uses; filling it in is one field fewer to type.
                                val fresh = HostAddress(port = port.toIntOrNull() ?: 22)
                                addresses = addresses + fresh
                                openAddress = fresh.id
                            },
                        ) { Text("Add") }
                    },
                )
                addresses.forEachIndexed { i, address ->
                    val t = tunnels.firstOrNull { it.id == address.tunnelId }
                    val ts = tailnets.firstOrNull { it.id == address.tailscaleId }
                    val via = when {
                        ts != null -> "through ${ts.name.ifBlank { "Tailscale" }}"
                        t != null -> "through ${t.name}"
                        else -> "direct"
                    }
                    val open = openAddress == address.id
                    fun change(f: (HostAddress) -> HostAddress) {
                        addresses = addresses.map { if (it.id == address.id) f(it) else it }
                    }
                    // Folded to a line each: a host with four addresses is a
                    // list to read down, not four forms to scroll past.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { openAddress = if (open) null else address.id }
                            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                address.label.ifBlank { address.hostname.ifBlank { "New address" } },
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                            )
                            Text(
                                listOfNotNull(
                                    address.hostname.takeIf { it.isNotBlank() && address.label.isNotBlank() }
                                        ?.let { "$it:${address.port.takeIf { p -> p > 0 } ?: port}" },
                                    via,
                                ).joinToString("  ·  "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        // Order is what "tried in order" means, so it is moved
                        // here rather than by editing and re-adding the list.
                        IconButton(
                            onClick = { addresses = addresses.toMutableList().also { it.add(i - 1, it.removeAt(i)) } },
                            enabled = i > 0,
                        ) { Icon(Icons.Rounded.KeyboardArrowUp, "Try this one sooner") }
                        IconButton(
                            onClick = { addresses = addresses.toMutableList().also { it.add(i + 1, it.removeAt(i)) } },
                            enabled = i < addresses.lastIndex,
                        ) { Icon(Icons.Rounded.KeyboardArrowDown, "Try this one later") }
                        Icon(
                            if (open) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                            if (open) "Close" else "Edit",
                            Modifier.padding(end = 8.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (open) {
                        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Field(
                                    address.hostname, { v -> change { it.copy(hostname = v) } },
                                    "Address", Modifier.weight(1f), placeholder = "192.168.1.10", mono = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                )
                                Field(
                                    address.port.takeIf { it > 0 }?.toString() ?: "",
                                    { v -> change { a -> a.copy(port = v.filter { c -> c.isDigit() }.take(5).toIntOrNull() ?: 0) } },
                                    "Port", Modifier.width(104.dp), mono = true, placeholder = port,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                )
                            }
                            Field(
                                address.label, { v -> change { it.copy(label = v) } },
                                "Name (optional)", placeholder = "Home LAN",
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { vpnFor = address.id; vpnSheet = true }, modifier = Modifier.weight(1f)) {
                                    Icon(if (ts != null) Icons.Rounded.Hub else Icons.Rounded.VpnLock, null, Modifier.width(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        when {
                                            ts != null -> "Through ${ts.name.ifBlank { "Tailscale" }}"
                                            t != null -> "Through ${t.name}"
                                            else -> "No VPN"
                                        },
                                        maxLines = 1,
                                    )
                                }
                                IconButton(onClick = { addresses = addresses.filterNot { it.id == address.id } }) {
                                    Icon(Icons.Rounded.Delete, "Remove address", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                            // Tailscale has no "try it directly first": the node is
                            // the route, and there is nothing to fall back from.
                            if (t != null && ts == null) {
                                Segmented(TunnelMode.entries.map { it.label }, address.tunnelMode.ordinal) { m ->
                                    change { it.copy(tunnelMode = TunnelMode.entries[m]) }
                                }
                                Text(
                                    if (address.tunnelMode == TunnelMode.WHEN_NEEDED) {
                                        "Reached directly while this phone is on the same network as it, or while it answers directly. Otherwise ${t.name} is used."
                                    } else {
                                        "This address always goes through ${t.name}."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (i < addresses.lastIndex) HorizontalDivider()
                }
                RowDivider()
                GroupRow(
                    title = "Jump host",
                    subtitle = jump?.let { "${it.displayName}  ·  ${it.target}" } ?: "Connect directly",
                    icon = Icons.Rounded.AltRoute,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    onClick = { jumpSheet = true },
                )
                // Mosh is UDP, and every route here carries it: the WireGuard tunnel
                // directly, a jump host through a relay, and Tailscale through the
                // node's own SOCKS5 proxy, which speaks UDP ASSOCIATE.
                if (protocol == Protocol.SSH) {
                    RowDivider()
                    GroupRow(
                        title = "Mosh",
                        subtitle = when {
                            mosh && tailscaleId != null -> "Starts mosh-server over SSH, then keeps the session over UDP inside your tailnet"
                            mosh && tunnel != null -> "Starts mosh-server over SSH, then keeps the session over UDP through ${tunnel.name}"
                            mosh && jump != null -> "Starts mosh-server over SSH, then relays UDP through ${jump.displayName} (needs python3 there)"
                            mosh -> "Starts mosh-server over SSH, then keeps the session over UDP: survives roaming and sleep"
                            else -> "Needs mosh-server on the host, and UDP 60000–61000 open"
                        },
                        icon = Icons.Rounded.Bolt,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        trailing = { AppSwitch(mosh, { mosh = it }) },
                    )
                }
                if (jump != null) {
                    RowDivider()
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Bolt, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.secondary)
                            Spacer(Modifier.width(8.dp))
                            Text("Before connecting, run on ${jump.displayName}", style = MaterialTheme.typography.titleSmall)
                        }
                        Field(
                            preCommand, { preCommand = it }, "Command (optional)", mono = true, singleLine = false, minLines = 2,
                            placeholder = "wakeonlan aa:bb:cc:dd:ee:ff",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                        )
                        Text(
                            "Output is shown in the connection log. Afterwards the tunnel to ${hostname.ifBlank { "the host" }} is retried until it answers. This is useful for waking a machine up.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Keep trying for", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text(if (waitSeconds == 0) "one attempt" else "${waitSeconds}s", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        AppSlider(
                            value = waitSeconds.toFloat(),
                            onValueChange = { waitSeconds = ((it / 15f).roundToInt() * 15).coerceIn(0, 600) },
                            valueRange = 0f..600f,
                        )
                    }
                } else {
                    RowDivider()
                    // Proxies are kept with the tunnels and tailnets, since the
                    // same one usually fronts several hosts: here you pick one.
                    val picked = proxies.firstOrNull { it.id == proxyId }
                    GroupRow(
                        title = "Proxy",
                        subtitle = picked?.let { "${it.label}  ·  ${it.target}" } ?: "None",
                        icon = Icons.Rounded.Lan,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        onClick = { proxySheet = true },
                    )
                }
                RowDivider()
                GroupRow(
                    title = "Forward SSH agent",
                    subtitle = when {
                        // Mosh drops the SSH connection once the UDP session is up,
                        // and the forwarded agent goes with it — same as real mosh.
                        // The SSH connection is kept open beside the Mosh session
                        // to hold the agent socket, which is worth saying: it is a
                        // connection that does not survive a network change.
                        forwardAgent && mosh -> "Kept alive beside Mosh, and ends if the network changes"
                        forwardAgent -> "ssh and git on the host may use your keys. The signing happens on this phone"
                        else -> "Off  ·  like ssh -A"
                    },
                    icon = Icons.Rounded.Key,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    trailing = { AppSwitch(forwardAgent, { forwardAgent = it }) },
                )
                if (forwardAgent) {
                    // Only under the switch it qualifies: asking about
                    // signatures means nothing for a host that forwards nothing.
                    val asking = askBeforeSigning ?: settings.confirmAgentSignatures
                    GroupRow(
                        title = "Ask before each signature",
                        subtitle = when {
                            asking -> "A prompt here every time something on this host signs with your key"
                            else -> "This host signs whenever it asks, as ssh -A does"
                        },
                        icon = Icons.Rounded.Fingerprint,
                        iconTint = MaterialTheme.colorScheme.tertiary,
                        trailing = { AppSwitch(asking, { askBeforeSigning = it }) },
                    )
                }
                RowDivider()
                GroupRow(
                    title = "Knock first",
                    subtitle = if (knock.enabled) {
                        knockProblem ?: PortKnock.format(PortKnock.parse(knock.sequence))
                    } else {
                        "Off  ·  for a host behind a knock daemon"
                    },
                    icon = Icons.Rounded.DoorFront,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    trailing = { AppSwitch(knock.enabled, { knock = knock.copy(enabled = it) }) },
                )
                if (knock.enabled) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field(
                            knock.sequence, { knock = knock.copy(sequence = it) }, "Sequence", mono = true,
                            placeholder = "7000, 8000/udp, 9000",
                            error = knockProblem,
                            hint = "TCP unless a port says /udp",
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Field(
                                knock.delayMs.toString(),
                                { v -> v.filter { it.isDigit() }.take(5).toIntOrNull()?.let { knock = knock.copy(delayMs = it) } },
                                "Between knocks", Modifier.weight(1f), mono = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                            Field(
                                knock.pauseMs.toString(),
                                { v -> v.filter { it.isDigit() }.take(5).toIntOrNull()?.let { knock = knock.copy(pauseMs = it) } },
                                "Then wait", Modifier.weight(1f), mono = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                        }
                        // Worth saying plainly, because the rest of this page is
                        // about routes the knock does not take.
                        Text(
                            "Milliseconds. The packets leave this phone, so a knock cannot open a firewall that only a jump host, tunnel or proxy can reach.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ---- wake on lan -----------------------------------------------------------
            if (page == EditorPage.Wol) Group("Wake on LAN") {
                GroupRow(
                    title = "Wake the machine before connecting",
                    subtitle = "Sends a magic packet, then keeps trying to connect while it boots",
                    icon = Icons.Rounded.Power,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    trailing = { AppSwitch(wol.enabled, { wol = wol.copy(enabled = it) }) },
                )
                if (wol.enabled) {
                    RowDivider()
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        fun detectMac() {
                            detecting = true
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { app.sessions.runCommand(draft(), Wol.DETECT_COMMAND) }
                                detecting = false
                                r.onSuccess { out ->
                                    val found = Wol.parseDetected(out)
                                    when {
                                        found.isEmpty() -> Toast.makeText(context, "No network interface with a MAC address found", Toast.LENGTH_LONG).show()
                                        found.size == 1 -> { wol = wol.copy(mac = found[0].second, broadcast = found[0].third.ifBlank { wol.broadcast }) }
                                        else -> detected = found
                                    }
                                }.onFailure { Toast.makeText(context, "Could not read MAC: ${it.message}", Toast.LENGTH_LONG).show() }
                            }
                        }
                        Field(
                            wol.mac, { wol = wol.copy(mac = it) }, "MAC address", mono = true, placeholder = "a8:a1:59:23:8e:88",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                            error = macError.takeIf { showErrors },
                            hint = if (detecting) "Connecting to read it…" else "Tap the magnifier to read it off the machine (it must be on)",
                            trailing = {
                                if (detecting) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    IconButton(onClick = ::detectMac, enabled = connectable) {
                                        Icon(
                                            Icons.Rounded.Search,
                                            "Detect the MAC address",
                                            tint = if (connectable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                        )
                                    }
                                }
                            },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Field(
                                wol.broadcast, { wol = wol.copy(broadcast = it) }, "Broadcast", Modifier.weight(1f), mono = true,
                                placeholder = "Auto",
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                hint = if (wol.broadcast.isBlank()) autoBroadcastHint else null,
                            )
                            Field(wol.port.toString(), { v -> v.filter { it.isDigit() }.take(5).toIntOrNull()?.let { wol = wol.copy(port = it) } }, "Port", Modifier.width(96.dp), mono = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        }
                        Text("Send from", style = MaterialTheme.typography.titleSmall)
                        Segmented(WolSource.entries.map { it.label }, wol.sendFrom.ordinal) { wol = wol.copy(sendFrom = WolSource.entries[it]) }
                        Text(
                            when (wol.sendFrom) {
                                WolSource.AUTO -> "Decided when you connect. If this phone is on the machine's network (the same subnet as the broadcast address or the host IP) the phone broadcasts. If it is not, " +
                                    (if (jump != null) "${jump.displayName} sends the packet." else "it still tries from the phone. Add a jump host so it also works when you are away.")
                                WolSource.PHONE -> "The phone broadcasts on the network it is on now. This only reaches the machine when you are on the same network."
                                WolSource.JUMP_HOST -> if (jump != null) "The jump host ${jump.displayName} sends the packet (wakeonlan, python3, etherwake or bash, whichever it has). Its output shows in the connection log."
                                    else "Pick a jump host above first. Until then the phone sends the packet."
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Wake automatically on every connect", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            AppSwitch(wol.autoWake, { wol = wol.copy(autoWake = it) })
                        }
                        if (jump == null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Keep trying for", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Text(if (waitSeconds == 0) "one attempt" else "${waitSeconds}s", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            }
                            AppSlider(
                                value = waitSeconds.toFloat(),
                                onValueChange = { waitSeconds = ((it / 15f).roundToInt() * 15).coerceIn(0, 600) },
                                valueRange = 0f..600f,
                            )
                        }
                    }
                }
            }

            // ---- the pages -----------------------------------------------------------
            if (page == EditorPage.Main) Group("Settings") {
                val tunnel = tunnels.firstOrNull { it.id == tunnelId }
                val tailnet = tailnets.firstOrNull { it.id == tailscaleId }
                GroupRow(
                    title = EditorPage.Route.title,
                    subtitle = listOfNotNull(
                        tailnet?.let { it.name.ifBlank { "Tailscale" } } ?: tunnel?.name,
                        addresses.size.takeIf { it > 0 }?.let { "$it more address${if (it > 1) "es" else ""}" },
                        jump?.displayName?.let { "through $it" },
                        "Mosh".takeIf { mosh },
                        "agent".takeIf { forwardAgent },
                        "asks".takeIf { forwardAgent && (askBeforeSigning ?: settings.confirmAgentSignatures) },
                        "knock".takeIf { knock.enabled },
                    ).joinToString("  ·  ").ifEmpty { "Direct connection" },
                    icon = Icons.Rounded.AltRoute,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    onClick = { page = EditorPage.Route },
                )
                RowDivider()
                GroupRow(
                    title = EditorPage.OnConnect.title,
                    subtitle = listOfNotNull(
                        startupSnippetIds.size.takeIf { it > 0 }?.let { "$it snippet${if (it > 1) "s" else ""}" },
                        "a command".takeIf { startup.isNotBlank() },
                        env.size.takeIf { it > 0 }?.let { "$it variable${if (it > 1) "s" else ""}" },
                        "tmux".takeIf { persistent },
                        "recorded".takeIf { recordSessions },
                    ).joinToString("  ·  ").ifEmpty { "Nothing yet" },
                    icon = Icons.Rounded.PlayArrow,
                    iconTint = MaterialTheme.colorScheme.primary,
                    onClick = { page = EditorPage.OnConnect },
                )
                RowDivider()
                val overrides =
                    listOfNotNull(keyboardProtocol, fixtermsCtrlKeys, terminalImages, tmuxControls, fontSizeSp.takeIf { it > 0f }).size
                GroupRow(
                    title = EditorPage.Terminal.title,
                    subtitle = listOfNotNull(
                        hostTheme?.let { Schemes.nameOf(it) } ?: "App default  ·  ${Schemes.nameOf(inheritedTheme)}",
                        overrides.takeIf { it > 0 }?.let { "$it override${if (it > 1) "s" else ""}" },
                        "alerts".takeIf { patterns.isNotBlank() },
                    ).joinToString("  ·  "),
                    icon = Icons.Rounded.Palette,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    onClick = { page = EditorPage.Terminal },
                    trailing = { PaletteChip(hostTheme, inheritedTheme) },
                )
                RowDivider()
                GroupRow(
                    title = EditorPage.Forwards.title,
                    subtitle = when (forwards.size) {
                        0 -> "None"
                        1 -> forwards.first().describe()
                        else -> "${forwards.size} forwards"
                    },
                    icon = Icons.Rounded.SwapHoriz,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    onClick = { page = EditorPage.Forwards },
                )
                RowDivider()
                GroupRow(
                    title = EditorPage.Wol.title,
                    subtitle = if (wol.enabled) {
                        Wol.normalizeMac(wol.mac).ifBlank { "No MAC address yet" }
                    } else {
                        "Off"
                    },
                    icon = Icons.Rounded.Power,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    onClick = { page = EditorPage.Wol },
                )
            }

            if (page == EditorPage.Main) Group("This host") {
                GroupRow(
                    title = "Show in Files app",
                    subtitle = if (showInFiles) "In Android's file picker" else "Off",
                    icon = Icons.Rounded.FolderOpen,
                    iconTint = MaterialTheme.colorScheme.primary,
                    trailing = { AppSwitch(showInFiles, { showInFiles = it }) },
                )
            }

            // ---- terminal ------------------------------------------------------------
            if (page == EditorPage.Terminal) Group("This host's terminal") {
                OverrideRow(
                    title = "Keyboard protocol",
                    icon = Icons.Rounded.Keyboard,
                    value = keyboardProtocol,
                    subtitle = if (keyboardProtocol ?: settings.keyboardProtocol) {
                        "Ctrl+[, Shift+Enter and Ctrl+Shift+letter reach programs here that ask for them"
                    } else {
                        "Plain xterm keys, for a device whose terminal predates the protocol"
                    },
                    onChange = { keyboardProtocol = it },
                )
                RowDivider()
                OverrideRow(
                    title = "Ctrl+[, Ctrl+I and Ctrl+M",
                    icon = Icons.Rounded.Keyboard,
                    value = fixtermsCtrlKeys,
                    subtitle = if (fixtermsCtrlKeys ?: settings.fixtermsCtrlKeys) {
                        "Sent as keys of their own here, so a tmux binding on Ctrl+[ fires"
                    } else {
                        "Sent as the Escape, Tab and Enter bytes, as a plain terminal always has"
                    },
                    onChange = { fixtermsCtrlKeys = it },
                )
                RowDivider()
                OverrideRow(
                    title = "Inline images",
                    icon = Icons.Rounded.Image,
                    value = terminalImages,
                    subtitle = if (terminalImages ?: (settings.terminalImages != TerminalImages.OFF)) {
                        "Programs here may draw pictures in the terminal. The app picks the protocol"
                    } else {
                        "Image escapes from this host are ignored"
                    },
                    onChange = { terminalImages = it },
                )
                RowDivider()
                OverrideRow(
                    title = "tmux controls",
                    icon = Icons.Rounded.Dashboard,
                    value = tmuxControls,
                    // Not "Follow": leaving this alone means "when this host is
                    // attached to tmux", which is a condition rather than a
                    // deferral to the app's setting.
                    neutral = "Auto",
                    subtitle = if (settings.tmuxControls && (tmuxControls ?: persistent)) {
                        "The chords sheet, the window list and the swipe that changes window"
                    } else {
                        "Off  ·  nothing to drive without a tmux on the other end"
                    },
                    onChange = { tmuxControls = it },
                )
                RowDivider()
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Font size", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(
                            if (fontSizeSp > 0f) "${fontSizeSp.roundToInt()} sp" else "Follow settings",
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    AppSlider(
                        value = if (fontSizeSp > 0f) fontSizeSp else FOLLOW_SETTINGS_SP,
                        onValueChange = { v ->
                            val sp = v.roundToInt().toFloat()
                            fontSizeSp = if (sp <= FOLLOW_SETTINGS_SP) 0f else sp
                        },
                        valueRange = FOLLOW_SETTINGS_SP..TerminalView.MAX_SP,
                    )
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(12.dp),
                    ) {
                        Text("~ $ ls -la  # preview 0123", style = CodeStyle.copy(fontSize = (if (fontSizeSp > 0f) fontSizeSp else settings.fontSizeSp).sp))
                    }
                    Text(
                        if (fontSizeSp > 0f) {
                            "Pinching in a session on this host changes this size, not the app's."
                        } else {
                            "This host draws at the app's ${settings.fontSizeSp.roundToInt()} sp, and pinching changes that."
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                RowDivider()
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(
                        patterns, { patterns = it }, "Notify when output matches (one regex per line)", mono = true, singleLine = false, minLines = 2,
                        placeholder = "build (finished|failed)\nERROR",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                    )
                    Text("Matching lines raise a notification while the app is in the background.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // Six hundred previews do not belong inline on a settings page; the
            // picker opens as a sheet, since this draft host has no route to
            // come back to.
            if (page == EditorPage.Terminal) Group("Color scheme") {
                GroupRow(
                    title = hostTheme?.let { Schemes.nameOf(it) } ?: "App default",
                    subtitle = if (hostTheme == null) Schemes.nameOf(inheritedTheme) else "This host only",
                    icon = Icons.Rounded.Palette,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    onClick = { themeSheet = true },
                    trailing = { PaletteChip(hostTheme, inheritedTheme) },
                )
            }

            if (page == EditorPage.OnConnect) Group("After login") {
                GroupRow(
                    title = "Record every session",
                    subtitle = if (recordSessions) {
                        "Saved as ${settings.recordingFormat.label.lowercase()}, login to logout"
                    } else {
                        "Off  ·  record by hand from the terminal's ⋮ menu"
                    },
                    icon = Icons.Rounded.FiberManualRecord,
                    iconTint = MaterialTheme.colorScheme.error,
                    trailing = { AppSwitch(recordSessions, { recordSessions = it }) },
                )
                RowDivider()
                GroupRow(
                    title = "Persistent session",
                    subtitle = "Attach to tmux at login, and attach again automatically when the connection drops",
                    icon = Icons.Rounded.Power,
                    iconTint = MaterialTheme.colorScheme.primary,
                    trailing = { AppSwitch(persistent, { persistent = it }) },
                )
                if (persistent) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Field(tmuxSession, { tmuxSession = it }, "tmux session name", Modifier.weight(1f), mono = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false))
                            // What the chords sheet and the window list send, so
                            // a tmux rebound to C-a is driven as it is configured.
                            Field(tmuxPrefix, { tmuxPrefix = it }, "tmux prefix", Modifier.width(120.dp), mono = true, placeholder = "C-b", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false))
                        }
                        Text("Needs tmux on the host. Your shell runs inside it, so running programs survive disconnects.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("Back to the last session", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "Reattach to whichever tmux session you were last in, so switching sessions on the host sticks. The name above is only used when tmux has none.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            AppSwitch(tmuxResumeLast, { tmuxResumeLast = it })
                        }
                    }
                }
                RowDivider()
                // Snippets run before the one-off command, and both run before the
                // tmux attach line — which uses exec, so nothing after it would.
                val chosenSnippets = startupSnippetIds.mapNotNull { id -> snippets.firstOrNull { it.id == id } }
                GroupRow(
                    title = "Run snippets",
                    subtitle = when {
                        chosenSnippets.isEmpty() -> "Saved commands, run in order before the one below"
                        chosenSnippets.size == 1 -> chosenSnippets.first().name.ifBlank { "One snippet" }
                        else -> "${chosenSnippets.size} snippets  ·  ${chosenSnippets.joinToString(", ") { it.name.ifBlank { "unnamed" } }}"
                    },
                    icon = Icons.Rounded.AutoAwesome,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    onClick = { snippetSheet = true },
                )
                // A command only this host needs is not worth a snippet in the
                // shared list, so it lives here, next to the snippets it runs after.
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Field(
                        startup, { startup = it }, "Command for this host only", mono = true, singleLine = false, minLines = 2,
                        placeholder = "cd /srv && ./status.sh",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                    )
                    Text(
                        "Runs after the snippets above and before tmux, without being saved to the snippet list.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Only SSH has a channel to ask on: a telnet console has no such
                // idea, and a Mosh session is not an SSH shell by the time it starts.
                if (protocol == Protocol.SSH) {
                    RowDivider()
                    GroupRow(
                        title = "Environment variables",
                        subtitle = when {
                            env.isEmpty() && mosh -> "Passed to mosh-server, which sets them itself. The server's AcceptEnv does not apply"
                            env.isEmpty() -> "Asked for when the shell opens. Most servers accept only LANG and LC_*"
                            else -> "${env.size} set on connect"
                        },
                        icon = Icons.Rounded.DataObject,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        trailing = { TextButton(onClick = { env = env + EnvEntry("", "") }) { Text("Add") } },
                    )
                    env.forEachIndexed { i, e ->
                        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Field(
                                    // A name with a space or an equals sign in it is not a
                                    // variable name, so those keys never make it into the field.
                                    e.name, { v -> env = env.mapIndexed { j, x -> if (j == i) x.copy(name = v.filter { c -> !c.isWhitespace() && c != '=' }) else x } },
                                    "Name", Modifier.weight(1f), placeholder = "LC_TERMINAL", mono = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                                )
                                Field(
                                    e.value, { v -> env = env.mapIndexed { j, x -> if (j == i) x.copy(value = v) else x } },
                                    "Value", Modifier.weight(1f), placeholder = "flintTerm", mono = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                                )
                                IconButton(onClick = { env = env.filterIndexed { j, _ -> j != i } }) {
                                    Icon(Icons.Rounded.Delete, "Remove variable", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                            if (i < env.lastIndex) HorizontalDivider()
                        }
                    }
                    if (env.isNotEmpty()) {
                        Text(
                            if (mosh) {
                                "Mosh takes these as mosh-server arguments, so they are set for the session whatever the server's AcceptEnv says. Names must look like variable names, and anything else is left out."
                            } else {
                                "sshd only passes the names its AcceptEnv lists, and by default that is LANG and LC_* only. Anything else is dropped silently, so the connection log shows how many were asked for, not how many arrived. Turning Mosh on avoids this limit."
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                        )
                    }
                }
            }

            // ---- forwards ------------------------------------------------------------
            if (page == EditorPage.Forwards) Group("Port forwarding") {
                forwards.forEachIndexed { i, f ->
                    GroupRow(
                        title = f.describe(),
                        subtitle = if (f.autoStart) "Starts with the session" else "Manual",
                        subtitleMono = false,
                        icon = Icons.Rounded.SwapHoriz,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        onClick = { editingForward = f },
                        trailing = { IconButton(onClick = { forwards = forwards.filterNot { it.id == f.id } }) { Icon(Icons.Rounded.Delete, "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant) } },
                    )
                    if (i < forwards.lastIndex) RowDivider()
                }
                if (forwards.isNotEmpty()) RowDivider()
                GroupRow(title = "Add forward", icon = Icons.Rounded.Add, iconTint = MaterialTheme.colorScheme.primary, onClick = { editingForward = PortForward() })
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    if (themeSheet) {
        ThemePickerSheet(selected = hostTheme, fallback = inheritedTheme, onDismiss = { themeSheet = false }) { picked -> hostTheme = picked }
    }
    if (lookSheet) {
        ModalBottomSheet(onDismissRequest = { lookSheet = false }, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HostGlyph(preview, 44)
                    Spacer(Modifier.width(14.dp))
                    Text("Color & icon", style = MaterialTheme.typography.titleLarge)
                }
                ColorPicker(selected = if (color != 0) Color(color) else null) { c -> color = if (color == c.toArgb()) 0 else c.toArgb() }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HostIcon.entries.forEach { hi ->
                        val sel = hi == icon
                        val accent = accentFor(draftId, color)
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (sel) accent.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable { icon = hi },
                            contentAlignment = Alignment.Center,
                        ) { Icon(iconFor(hi), hi.label, Modifier.size(22.dp), tint = if (sel) accent else MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
    if (newGroup) {
        NewGroupDialog(
            onDismiss = { newGroup = false },
            onCreate = { name ->
                groupId = app.store.groupIdFor(name)
                newGroup = false
            },
        )
    }
    if (accountSheet) {
        ActionSheet(
            onDismiss = { accountSheet = false },
            title = "Account",
            subtitle = "Who to log in as",
            actions = listOf(
                SheetAction(
                    "This host only",
                    Icons.Rounded.Person,
                    subtitle = "Keep the username and key typed into this host",
                ) { accountId = null; accountSheet = false },
            ) + accounts.map { a ->
                SheetAction(a.label, Icons.Rounded.Person, subtitle = a.username.ifBlank { "no username" }) {
                    accountId = a.id
                    accountSheet = false
                }
            } + SheetAction("New account…", Icons.Rounded.Add) {
                accountSheet = false
                // Seeded with whatever has been typed here already, so making
                // this host's login shared does not mean typing it twice.
                newAccount = dev.flint.term.data.Account(
                    name = "",
                    username = username.trim(),
                    authType = authType,
                    password = password,
                    identityId = identityId,
                )
            },
        )
    }
    newAccount?.let { seed ->
        AccountSheet(
            account = seed,
            onDismiss = { newAccount = null },
            onSave = { saved ->
                app.store.upsertAccount(saved)
                accountId = saved.id
                newAccount = null
            },
        )
    }
    if (keySheet) {
        ActionSheet(
            onDismiss = { keySheet = false },
            title = "SSH key",
            actions = identities.map { i ->
                SheetAction(i.name, Icons.Rounded.Key, subtitle = i.fingerprint) { identityId = i.id; keySheet = false }
            } + SheetAction("Manage keys…", Icons.Rounded.Add) { keySheet = false; nav.navigate(Routes.KEYS) },
        )
    }
    detected?.let { list ->
        ActionSheet(
            onDismiss = { detected = null },
            title = "Which interface?",
            subtitle = "The first one carries the default route.",
            actions = list.map { (iface, mac, bc) ->
                SheetAction(iface, subtitle = mac + if (bc.isNotBlank()) "  ·  broadcast $bc" else "") {
                    wol = wol.copy(mac = mac, broadcast = bc.ifBlank { wol.broadcast }); detected = null
                }
            },
        )
    }
    if (vpnSheet) {
        // The same list serves the host's own address and each extra one; only
        // where the answer is written down differs.
        val target = vpnFor
        fun choose(tunnel: String?, tailnet: String?) {
            if (target == null) {
                tunnelId = tunnel; tailscaleId = tailnet
            } else {
                addresses = addresses.map { if (it.id == target) it.copy(tunnelId = tunnel, tailscaleId = tailnet) else it }
            }
            vpnSheet = false
            vpnFor = null
        }
        ActionSheet(
            onDismiss = { vpnSheet = false; vpnFor = null },
            title = "VPN",
            subtitle = if (target == null) {
                "Only this host's traffic goes through it. No system VPN needed."
            } else {
                "Used for this address only, so a LAN address can stay direct."
            },
            actions = listOf(
                SheetAction("Direct network", Icons.Rounded.SwapHoriz, subtitle = "No VPN") { choose(null, null) },
            ) +
                tunnels.map { t ->
                    SheetAction(t.name.ifBlank { "Tunnel" }, Icons.Rounded.VpnLock, subtitle = "WireGuard tunnel") { choose(t.id, null) }
                } +
                tailnets.map { p ->
                    SheetAction(p.name.ifBlank { "Tailscale" }, Icons.Rounded.Hub, subtitle = if (p.joined) "Tailscale" else "Tailscale, not joined yet") { choose(null, p.id) }
                } +
                SheetAction("Manage VPN & tunnels…", Icons.Rounded.Add) { vpnSheet = false; vpnFor = null; nav.navigate(Routes.TUNNELS) },
        )
    }
    if (snippetSheet) {
        // Snippets scoped to other hosts are not offered, and neither are the
        // ones with {{placeholders}}: those need an answer, and nobody is at the
        // keyboard when a session logs in.
        val offered = snippets.filter { (it.hostIds.isEmpty() || draftId in it.hostIds) && it.placeholders.isEmpty() }
        val asking = snippets.count { (it.hostIds.isEmpty() || draftId in it.hostIds) && it.placeholders.isNotEmpty() }
        ActionSheet(
            onDismiss = { snippetSheet = false },
            title = "Run on connect",
            subtitle = when {
                offered.isEmpty() && asking > 0 -> "Every snippet for this host asks for a value"
                offered.isEmpty() -> "No snippets for this host yet"
                asking > 0 -> "Run in the order picked. $asking snippet(s) ask for a value and cannot run unattended."
                else -> "Run in the order picked, before the host's own command"
            },
            actions = offered.map { sn ->
                val on = sn.id in startupSnippetIds
                SheetAction(
                    sn.name.ifBlank { "Unnamed snippet" } + if (on) "  ✓" else "",
                    Icons.Rounded.AutoAwesome,
                    subtitle = sn.command.lines().first().take(60),
                ) {
                    // Kept open: picking several is the normal case, and the
                    // order they are switched on in is the order they run in.
                    startupSnippetIds = if (on) startupSnippetIds - sn.id else startupSnippetIds + sn.id
                }
            } + SheetAction("Manage snippets…", Icons.Rounded.Add) { snippetSheet = false; nav.navigate(Routes.SNIPPETS) },
        )
    }
    if (proxySheet) {
        ActionSheet(
            onDismiss = { proxySheet = false },
            title = "Proxy",
            subtitle = "The first hop is dialled through it. Proxies are managed with the tunnels.",
            actions = listOf(SheetAction("None", Icons.Rounded.SwapHoriz) { proxyId = null; proxySheet = false }) +
                proxies.map { p ->
                    SheetAction(p.label, Icons.Rounded.Lan, subtitle = p.target) { proxyId = p.id; proxySheet = false }
                } +
                SheetAction("Manage proxies…", Icons.Rounded.Add) { proxySheet = false; nav.navigate(Routes.TUNNELS) },
        )
    }
    if (jumpSheet) {
        ActionSheet(
            onDismiss = { jumpSheet = false },
            title = "Connect through",
            subtitle = "The jump host is connected first, then this host is reached through it.",
            actions = listOf(SheetAction("Direct connection", Icons.Rounded.SwapHoriz) { jumpHostId = null; jumpSheet = false }) +
                jumpCandidates.map { h ->
                    SheetAction(h.displayName, iconFor(h.icon), subtitle = h.target + (h.jumpHostId?.let { " (itself via a jump host)" } ?: "")) { jumpHostId = h.id; jumpSheet = false }
                },
        )
    }
    editingForward?.let { f ->
        ForwardDialog(
            initial = f,
            onDismiss = { editingForward = null },
            onSave = { saved ->
                forwards = if (forwards.any { it.id == saved.id }) forwards.map { if (it.id == saved.id) saved else it } else forwards + saved
                editingForward = null
            },
        )
    }
}

@Composable
fun ForwardDialog(initial: PortForward, onDismiss: () -> Unit, onSave: (PortForward) -> Unit) {
    var type by remember { mutableStateOf(initial.type) }
    var bindHost by remember { mutableStateOf(initial.bindHost) }
    var bindPort by remember { mutableStateOf(initial.bindPort.toString()) }
    var targetHost by remember { mutableStateOf(initial.targetHost) }
    var targetPort by remember { mutableStateOf(initial.targetPort.toString()) }
    var autoStart by remember { mutableStateOf(initial.autoStart) }
    val dynamic = type == ForwardType.DYNAMIC
    val valid = bindPort.toIntOrNull() in 0..65535 && (dynamic || (targetPort.toIntOrNull() in 1..65535 && targetHost.isNotBlank()))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Port forward") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Segmented(listOf("Local -L", "Remote -R", "SOCKS -D"), type.ordinal) { type = ForwardType.entries[it] }
                Text(
                    when (type) {
                        ForwardType.LOCAL -> "This device listens, and connections go to the target through the server."
                        ForwardType.REMOTE -> "The server listens, and connections are delivered to the target from this device."
                        ForwardType.DYNAMIC -> "A SOCKS5 proxy on this device. Point a browser or app at it and its traffic leaves from the server."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(bindHost, { bindHost = it }, if (type == ForwardType.REMOTE) "Remote bind" else "Bind address", Modifier.weight(1f), mono = true)
                    Field(bindPort, { bindPort = it.filter { c -> c.isDigit() }.take(5) }, "Port", Modifier.width(88.dp), mono = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                if (!dynamic) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(targetHost, { targetHost = it }, "Target host", Modifier.weight(1f), mono = true)
                    Field(targetPort, { targetPort = it.filter { c -> c.isDigit() }.take(5) }, "Port", Modifier.width(88.dp), mono = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Start automatically", Modifier.weight(1f))
                    AppSwitch(checked = autoStart, onCheckedChange = { autoStart = it })
                }
            }
        },
        confirmButton = {
            Button(enabled = valid, onClick = {
                onSave(initial.copy(type = type, bindHost = bindHost.trim(), bindPort = bindPort.toInt(), targetHost = targetHost.trim(), targetPort = targetPort.toInt(), autoStart = autoStart))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * A per-host answer that is allowed to be "no answer at all".
 *
 * Three choices rather than a switch, because a host that has never been asked
 * has to stay unasked: it follows the app setting, and keeps following it when
 * that setting changes. A switch can only say yes or no, and would pin every
 * host it was ever shown for.
 */
@Composable
private fun OverrideRow(
    title: String,
    icon: ImageVector,
    value: Boolean?,
    subtitle: String,
    onChange: (Boolean?) -> Unit,
    /** What "leave it to the app" reads as for this particular row. */
    neutral: String = "Follow",
) {
    GroupRow(title = title, subtitle = subtitle, icon = icon, iconTint = MaterialTheme.colorScheme.secondary)
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
        Segmented(listOf(neutral, "On", "Off"), when (value) { null -> 0; true -> 1; false -> 2 }) { picked ->
            onChange(when (picked) { 0 -> null; 1 -> true; else -> false })
        }
    }
}

@Suppress("unused")
private fun unusedOutlined(): @Composable () -> Unit = { OutlinedButton(onClick = {}) {} }

/**
 * The step below the smallest real size, where the font slider means "follow
 * settings" rather than a size at all.
 *
 * A position on the same slider rather than a switch beside it, because "no
 * opinion" and "13 sp" are answers to one question, and dragging off the end is
 * how you take the opinion back.
 */
private const val FOLLOW_SETTINGS_SP = TerminalView.MIN_SP - 1f

/**
 * The host editor's pages.
 *
 * Everything used to be one scroll, which meant hunting for the one row you
 * came for. Each page is a thing you set up in one sitting.
 */
private enum class EditorPage(val title: String) {
    Main("Host"),
    Route("Route & VPN"),
    OnConnect("On connect"),
    Terminal("Terminal"),
    Forwards("Port forwarding"),
    Wol("Wake on LAN"),
}
