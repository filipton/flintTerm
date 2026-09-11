package dev.flint.term.ui

import dev.flint.term.R
import androidx.compose.ui.res.stringResource
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
    var confirmRecord by remember { mutableStateOf(false) }
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
    val keyError = if (!telnet && account == null && authType == AuthType.KEY && identityId == null) stringResource(R.string.hosteditscreen_pick_a_key) else null
    val macError = when {
        !wol.enabled -> null
        wol.mac.isBlank() -> stringResource(R.string.hosteditscreen_required_to_wake_this_machine)
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
            ?.let { "Auto, ${it} on your current network" }
            ?: "Auto, one packet per interface, because no address is on a network this phone is on"
    }

    /** Enough to reach the machine — what "Detect MAC" needs, and never gated on the MAC itself. */
    val connectable = hostError == null && portError == null && userError == null && keyError == null
    val valid = connectable && macError == null
    // Shown in a toast when Save is refused, so a problem scrolled off screen still gets named.
    val firstProblem = when {
        hostError != null -> stringResource(R.string.hosteditscreen_the_host_address_is_required)
        portError != null -> stringResource(R.string.hosteditscreen_the_port_must_be_between_1_and_65535)
        userError != null -> stringResource(R.string.hosteditscreen_a_username_is_required)
        keyError != null -> stringResource(R.string.hosteditscreen_choose_an_ssh_key_or_switch_to_password_auth)
        macError != null -> stringResource(R.string.hosteditscreen_wake_on_lan_needs_a_valid_mac_address)
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
                    existing == null -> stringResource(R.string.hosteditscreen_new_host)
                    else -> stringResource(R.string.hosteditscreen_edit_host)
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
                    ) { Text(stringResource(R.string.hosteditscreen_save)) }
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
                        ) { Icon(Icons.Rounded.Edit, stringResource(R.string.hosteditscreen_color_and_icon), Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onPrimary) }
                    }
                    Spacer(Modifier.width(12.dp))
                    Field(label, { label = it }, "Name", placeholder = hostname.ifBlank { stringResource(R.string.hosteditscreen_my_server) })
                }
                // A group is an object now — it carries a jump host, a VPN and a
                // login for its members — so it is picked rather than typed.
                Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.hosteditscreen_group), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                label = { Text(stringResource(R.string.hosteditscreen_new_group)) },
                                leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(16.dp)) },
                            )
                        }
                    }
                    hostGroups.firstOrNull { it.id == groupId }?.takeIf { it.defaultCount() > 0 }?.let { g ->
                        Text(
                            stringResource(R.string.hosteditscreen_anything_this_host_leaves_unset_is_taken_from, g.label),
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
                            hostname, { hostname = it }, "Host", Modifier.weight(1f), placeholder = stringResource(R.string.hosteditscreen_example_com), mono = true,
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
                            username, { username = it }, "Username", placeholder = stringResource(R.string.hosteditscreen_root), mono = true,
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
                        subtitle = stringResource(R.string.hosteditscreen_read_from_the_host_when_it_was_last_connected),
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
                    title = account?.label ?: stringResource(R.string.hosteditscreen_this_host_only),
                    subtitle = account?.let { a ->
                        val how = when (a.authType) {
                            AuthType.KEY -> identities.firstOrNull { it.id == a.identityId }?.name?.ifBlank { "key" } ?: stringResource(R.string.hosteditscreen_no_key_chosen)
                            AuthType.PASSWORD -> "password"
                            AuthType.NONE -> stringResource(R.string.hosteditscreen_no_authentication)
                        }
                        stringResource(R.string.hosteditscreen_shared_account, a.username.ifBlank { "no username" }, how)
                    } ?: stringResource(R.string.hosteditscreen_username_and_key_typed_in_here_used_by_this_host),
                    icon = Icons.Rounded.Person,
                    onClick = { accountSheet = true },
                )
                if (account == null) RowDivider()
                if (account == null) Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Segmented(listOf("Password", stringResource(R.string.hosteditscreen_ssh_key), "None"), authType.ordinal) { authType = AuthType.entries[it] }
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
                                title = selected?.name ?: if (identities.isEmpty()) stringResource(R.string.hosteditscreen_no_keys_yet) else stringResource(R.string.hosteditscreen_choose_a_key),
                                subtitle = selected?.fingerprint ?: stringResource(R.string.hosteditscreen_tap_to_pick_or_create_one),
                                subtitleMono = selected != null,
                                icon = Icons.Rounded.Key,
                            )
                        }
                    }
                    if (authType != AuthType.NONE) {
                        Field(
                            password, { password = it },
                            if (authType == AuthType.KEY) stringResource(R.string.hosteditscreen_password_fallback_optional) else "Password",
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            autofill = ContentType.Password,
                            trailing = {
                                IconButton(onClick = { showPassword = !showPassword }) {
                                    Icon(if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, stringResource(R.string.hosteditscreen_toggle_visibility))
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
                        tailnet != null -> stringResource(R.string.hosteditscreen_tailscale_so_the_host_can_be_a_magicdns_name, tailnet.name.ifBlank { "Tailscale" })
                        jump != null && tunnel != null -> stringResource(R.string.hosteditscreen_ignored_while_a_jump_host_is_set_its_tunnel_is_u, tunnel.name)
                        tunnel != null -> stringResource(R.string.hosteditscreen_wireguard, tunnel.name)
                        else -> stringResource(R.string.hosteditscreen_direct_network)
                    },
                    icon = if (tailnet != null) Icons.Rounded.Hub else Icons.Rounded.VpnLock,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    onClick = { vpnFor = null; vpnSheet = true },
                )
                if (tunnel != null && jump == null) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Segmented(TunnelMode.entries.map { stringResource(it.label) }, tunnelMode.ordinal) { tunnelMode = TunnelMode.entries[it] }
                        Text(
                            if (tunnelMode == TunnelMode.WHEN_NEEDED) stringResource(R.string.hosteditscreen_connect_directly_when_the_host_is_on_the_phone_s, tunnel.name)
                            else stringResource(R.string.hosteditscreen_every_connection_goes_through, tunnel.name),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                RowDivider()
                GroupRow(
                    title = stringResource(R.string.hosteditscreen_other_addresses),
                    subtitle = if (addresses.isEmpty()) {
                        stringResource(R.string.hosteditscreen_the_same_machine_at_another_address_such_as_a_la)
                    } else {
                        stringResource(R.string.hosteditscreen_more_tried_in_order_after, addresses.size, hostname.ifBlank { "the address above" })
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
                        ) { Text(stringResource(R.string.hosteditscreen_add)) }
                    },
                )
                addresses.forEachIndexed { i, address ->
                    val t = tunnels.firstOrNull { it.id == address.tunnelId }
                    val ts = tailnets.firstOrNull { it.id == address.tailscaleId }
                    val via = when {
                        ts != null -> stringResource(R.string.hosteditscreen_through, ts.name.ifBlank { "Tailscale" })
                        t != null -> stringResource(R.string.hosteditscreen_through, t.name)
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
                                address.label.ifBlank { address.hostname.ifBlank { stringResource(R.string.hosteditscreen_new_address) } },
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
                        ) { Icon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.hosteditscreen_try_this_one_sooner)) }
                        IconButton(
                            onClick = { addresses = addresses.toMutableList().also { it.add(i + 1, it.removeAt(i)) } },
                            enabled = i < addresses.lastIndex,
                        ) { Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.hosteditscreen_try_this_one_later)) }
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
                                "Name (optional)", placeholder = stringResource(R.string.hosteditscreen_home_lan),
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { vpnFor = address.id; vpnSheet = true }, modifier = Modifier.weight(1f)) {
                                    Icon(if (ts != null) Icons.Rounded.Hub else Icons.Rounded.VpnLock, null, Modifier.width(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        when {
                                            ts != null -> stringResource(R.string.hosteditscreen_through_2, ts.name.ifBlank { "Tailscale" })
                                            t != null -> stringResource(R.string.hosteditscreen_through_2, t.name)
                                            else -> "No VPN"
                                        },
                                        maxLines = 1,
                                    )
                                }
                                IconButton(onClick = { addresses = addresses.filterNot { it.id == address.id } }) {
                                    Icon(Icons.Rounded.Delete, stringResource(R.string.hosteditscreen_remove_address), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                            // Tailscale has no "try it directly first": the node is
                            // the route, and there is nothing to fall back from.
                            if (t != null && ts == null) {
                                Segmented(TunnelMode.entries.map { stringResource(it.label) }, address.tunnelMode.ordinal) { m ->
                                    change { it.copy(tunnelMode = TunnelMode.entries[m]) }
                                }
                                Text(
                                    if (address.tunnelMode == TunnelMode.WHEN_NEEDED) {
                                        stringResource(R.string.hosteditscreen_reached_directly_while_this_phone_is_on_the_same, t.name)
                                    } else {
                                        stringResource(R.string.hosteditscreen_this_address_always_goes_through, t.name)
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
                    title = stringResource(R.string.hosteditscreen_jump_host),
                    subtitle = jump?.let { "${it.displayName}  ·  ${it.target}" } ?: stringResource(R.string.hosteditscreen_connect_directly),
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
                        title = stringResource(R.string.hosteditscreen_mosh),
                        subtitle = when {
                            mosh && tailscaleId != null -> stringResource(R.string.hosteditscreen_starts_mosh_server_over_ssh_then_keeps_the_sessi)
                            mosh && tunnel != null -> stringResource(R.string.hosteditscreen_starts_mosh_server_over_ssh_then_keeps_the_sessi_2, tunnel.name)
                            mosh && jump != null -> stringResource(R.string.hosteditscreen_starts_mosh_server_over_ssh_then_relays_udp_thro, jump.displayName)
                            mosh -> stringResource(R.string.hosteditscreen_starts_mosh_server_over_ssh_then_keeps_the_sessi_3)
                            else -> stringResource(R.string.hosteditscreen_needs_mosh_server_on_the_host_and_udp_6000061000)
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
                            Text(stringResource(R.string.hosteditscreen_before_connecting_run_on, jump.displayName), style = MaterialTheme.typography.titleSmall)
                        }
                        Field(
                            preCommand, { preCommand = it }, stringResource(R.string.hosteditscreen_command_optional), mono = true, singleLine = false, minLines = 2,
                            placeholder = stringResource(R.string.hosteditscreen_wakeonlan_aa_bb_cc_dd_ee_ff),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                        )
                        Text(
                            stringResource(R.string.hosteditscreen_output_is_shown_in_the_connection_log_afterwards, hostname.ifBlank { "the host" }),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.hosteditscreen_keep_trying_for), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text(if (waitSeconds == 0) stringResource(R.string.hosteditscreen_one_attempt) else "${waitSeconds}s", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
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
                        title = stringResource(R.string.hosteditscreen_proxy),
                        subtitle = picked?.let { "${it.label}  ·  ${it.target}" } ?: "None",
                        icon = Icons.Rounded.Lan,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        onClick = { proxySheet = true },
                    )
                }
                RowDivider()
                GroupRow(
                    title = stringResource(R.string.hosteditscreen_forward_ssh_agent),
                    subtitle = when {
                        // Mosh drops the SSH connection once the UDP session is up,
                        // and the forwarded agent goes with it — same as real mosh.
                        // The SSH connection is kept open beside the Mosh session
                        // to hold the agent socket, which is worth saying: it is a
                        // connection that does not survive a network change.
                        forwardAgent && mosh -> stringResource(R.string.hosteditscreen_kept_alive_beside_mosh_and_ends_if_the_network_c)
                        forwardAgent -> stringResource(R.string.hosteditscreen_ssh_and_git_on_the_host_may_use_your_keys_the_si)
                        else -> stringResource(R.string.hosteditscreen_off_like_ssh_a)
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
                        title = stringResource(R.string.hosteditscreen_ask_before_each_signature),
                        subtitle = when {
                            asking -> stringResource(R.string.hosteditscreen_a_prompt_here_every_time_something_on_this_host)
                            else -> stringResource(R.string.hosteditscreen_this_host_signs_whenever_it_asks_as_ssh_a_does)
                        },
                        icon = Icons.Rounded.Fingerprint,
                        iconTint = MaterialTheme.colorScheme.tertiary,
                        trailing = { AppSwitch(asking, { askBeforeSigning = it }) },
                    )
                }
                RowDivider()
                GroupRow(
                    title = stringResource(R.string.hosteditscreen_knock_first),
                    subtitle = if (knock.enabled) {
                        knockProblem ?: PortKnock.format(PortKnock.parse(knock.sequence))
                    } else {
                        stringResource(R.string.hosteditscreen_off_for_a_host_behind_a_knock_daemon)
                    },
                    icon = Icons.Rounded.DoorFront,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    trailing = { AppSwitch(knock.enabled, { knock = knock.copy(enabled = it) }) },
                )
                if (knock.enabled) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field(
                            knock.sequence, { knock = knock.copy(sequence = it) }, "Sequence", mono = true,
                            placeholder = stringResource(R.string.hosteditscreen_7000_8000_udp_9000),
                            error = knockProblem,
                            hint = stringResource(R.string.hosteditscreen_tcp_unless_a_port_says_udp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Field(
                                knock.delayMs.toString(),
                                { v -> v.filter { it.isDigit() }.take(5).toIntOrNull()?.let { knock = knock.copy(delayMs = it) } },
                                stringResource(R.string.hosteditscreen_between_knocks), Modifier.weight(1f), mono = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                            Field(
                                knock.pauseMs.toString(),
                                { v -> v.filter { it.isDigit() }.take(5).toIntOrNull()?.let { knock = knock.copy(pauseMs = it) } },
                                stringResource(R.string.hosteditscreen_then_wait), Modifier.weight(1f), mono = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                        }
                        // Worth saying plainly, because the rest of this page is
                        // about routes the knock does not take.
                        Text(
                            stringResource(R.string.hosteditscreen_milliseconds_the_packets_leave_this_phone_so_a_k),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ---- wake on lan -----------------------------------------------------------
            if (page == EditorPage.Wol) Group(stringResource(R.string.hosteditscreen_wake_on_lan)) {
                GroupRow(
                    title = stringResource(R.string.hosteditscreen_wake_the_machine_before_connecting),
                    subtitle = stringResource(R.string.hosteditscreen_sends_a_magic_packet_then_keeps_trying_to_connec),
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
                                        found.isEmpty() -> Toast.makeText(context, context.getString(R.string.hosteditscreen_no_network_interface_with_a_mac_address_found), Toast.LENGTH_LONG).show()
                                        found.size == 1 -> { wol = wol.copy(mac = found[0].second, broadcast = found[0].third.ifBlank { wol.broadcast }) }
                                        else -> detected = found
                                    }
                                }.onFailure { Toast.makeText(context, context.getString(R.string.hosteditscreen_could_not_read_mac, it.message), Toast.LENGTH_LONG).show() }
                            }
                        }
                        Field(
                            wol.mac, { wol = wol.copy(mac = it) }, stringResource(R.string.hosteditscreen_mac_address), mono = true, placeholder = "a8:a1:59:23:8e:88",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                            error = macError.takeIf { showErrors },
                            hint = if (detecting) stringResource(R.string.hosteditscreen_connecting_to_read_it) else stringResource(R.string.hosteditscreen_tap_the_magnifier_to_read_it_off_the_machine_it),
                            trailing = {
                                if (detecting) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    IconButton(onClick = ::detectMac, enabled = connectable) {
                                        Icon(
                                            Icons.Rounded.Search,
                                            stringResource(R.string.hosteditscreen_detect_the_mac_address),
                                            tint = if (connectable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                        )
                                    }
                                }
                            },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Field(
                                wol.broadcast, { wol = wol.copy(broadcast = it) }, "Broadcast", Modifier.weight(1f), mono = true,
                                placeholder = stringResource(R.string.hosteditscreen_auto),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                hint = if (wol.broadcast.isBlank()) autoBroadcastHint else null,
                            )
                            Field(wol.port.toString(), { v -> v.filter { it.isDigit() }.take(5).toIntOrNull()?.let { wol = wol.copy(port = it) } }, "Port", Modifier.width(96.dp), mono = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        }
                        Text(stringResource(R.string.hosteditscreen_send_from), style = MaterialTheme.typography.titleSmall)
                        Segmented(WolSource.entries.map { stringResource(it.label) }, wol.sendFrom.ordinal) { wol = wol.copy(sendFrom = WolSource.entries[it]) }
                        Text(
                            when (wol.sendFrom) {
                                WolSource.AUTO -> stringResource(R.string.hosteditscreen_decided_when_you_connect_if_this_phone_is_on_the) +
                                    (if (jump != null) stringResource(R.string.hosteditscreen_sends_the_packet, jump.displayName) else stringResource(R.string.hosteditscreen_it_still_tries_from_the_phone_add_a_jump_host_so))
                                WolSource.PHONE -> stringResource(R.string.hosteditscreen_the_phone_broadcasts_on_the_network_it_is_on_now)
                                WolSource.JUMP_HOST -> if (jump != null) stringResource(R.string.hosteditscreen_the_jump_host_sends_the_packet_wakeonlan_python3, jump.displayName)
                                    else stringResource(R.string.hosteditscreen_pick_a_jump_host_above_first_until_then_the_phon)
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.hosteditscreen_wake_automatically_on_every_connect), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            AppSwitch(wol.autoWake, { wol = wol.copy(autoWake = it) })
                        }
                        if (jump == null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.hosteditscreen_keep_trying_for), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Text(if (waitSeconds == 0) stringResource(R.string.hosteditscreen_one_attempt) else "${waitSeconds}s", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
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
                        addresses.size.takeIf { it > 0 }?.let { stringResource(R.string.hosteditscreen_more_address, it, if (it > 1) "es" else "") },
                        jump?.displayName?.let { stringResource(R.string.hosteditscreen_through, it) },
                        "Mosh".takeIf { mosh },
                        "agent".takeIf { forwardAgent },
                        "asks".takeIf { forwardAgent && (askBeforeSigning ?: settings.confirmAgentSignatures) },
                        "knock".takeIf { knock.enabled },
                    ).joinToString("  ·  ").ifEmpty { stringResource(R.string.hosteditscreen_direct_connection) },
                    icon = Icons.Rounded.AltRoute,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    onClick = { page = EditorPage.Route },
                )
                RowDivider()
                GroupRow(
                    title = EditorPage.OnConnect.title,
                    subtitle = listOfNotNull(
                        startupSnippetIds.size.takeIf { it > 0 }?.let { stringResource(R.string.hosteditscreen_snippet, it, if (it > 1) "s" else "") },
                        stringResource(R.string.hosteditscreen_a_command).takeIf { startup.isNotBlank() },
                        env.size.takeIf { it > 0 }?.let { stringResource(R.string.hosteditscreen_variable, it, if (it > 1) "s" else "") },
                        "tmux".takeIf { persistent },
                        "recorded".takeIf { recordSessions },
                    ).joinToString("  ·  ").ifEmpty { stringResource(R.string.hosteditscreen_nothing_yet) },
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
                        hostTheme?.let { Schemes.nameOf(it) } ?: stringResource(R.string.hosteditscreen_app_default, Schemes.nameOf(inheritedTheme)),
                        overrides.takeIf { it > 0 }?.let { stringResource(R.string.hosteditscreen_override, it, if (it > 1) "s" else "") },
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
                        else -> stringResource(R.string.hosteditscreen_forwards, forwards.size)
                    },
                    icon = Icons.Rounded.SwapHoriz,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    onClick = { page = EditorPage.Forwards },
                )
                RowDivider()
                GroupRow(
                    title = EditorPage.Wol.title,
                    subtitle = if (wol.enabled) {
                        Wol.normalizeMac(wol.mac).ifBlank { stringResource(R.string.hosteditscreen_no_mac_address_yet) }
                    } else {
                        "Off"
                    },
                    icon = Icons.Rounded.Power,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    onClick = { page = EditorPage.Wol },
                )
            }

            if (page == EditorPage.Main) Group(stringResource(R.string.hosteditscreen_this_host)) {
                GroupRow(
                    title = stringResource(R.string.hosteditscreen_show_in_files_app),
                    subtitle = if (showInFiles) stringResource(R.string.hosteditscreen_in_android_s_file_picker) else "Off",
                    icon = Icons.Rounded.FolderOpen,
                    iconTint = MaterialTheme.colorScheme.primary,
                    trailing = { AppSwitch(showInFiles, { showInFiles = it }) },
                )
            }

            // ---- terminal ------------------------------------------------------------
            if (page == EditorPage.Terminal) Group(stringResource(R.string.hosteditscreen_this_host_s_terminal)) {
                OverrideRow(
                    title = stringResource(R.string.hosteditscreen_keyboard_protocol),
                    icon = Icons.Rounded.Keyboard,
                    value = keyboardProtocol,
                    subtitle = if (keyboardProtocol ?: settings.keyboardProtocol) {
                        stringResource(R.string.hosteditscreen_ctrl_shift_enter_and_ctrl_shift_letter_reach_pro)
                    } else {
                        stringResource(R.string.hosteditscreen_plain_xterm_keys_for_a_device_whose_terminal_pre)
                    },
                    onChange = { keyboardProtocol = it },
                )
                RowDivider()
                OverrideRow(
                    title = stringResource(R.string.hosteditscreen_ctrl_ctrl_i_and_ctrl_m),
                    icon = Icons.Rounded.Keyboard,
                    value = fixtermsCtrlKeys,
                    subtitle = if (fixtermsCtrlKeys ?: settings.fixtermsCtrlKeys) {
                        stringResource(R.string.hosteditscreen_sent_as_keys_of_their_own_here_so_a_tmux_binding)
                    } else {
                        stringResource(R.string.hosteditscreen_sent_as_the_escape_tab_and_enter_bytes_as_a_plai)
                    },
                    onChange = { fixtermsCtrlKeys = it },
                )
                RowDivider()
                OverrideRow(
                    title = stringResource(R.string.hosteditscreen_inline_images),
                    icon = Icons.Rounded.Image,
                    value = terminalImages,
                    subtitle = if (terminalImages ?: (settings.terminalImages != TerminalImages.OFF)) {
                        stringResource(R.string.hosteditscreen_programs_here_may_draw_pictures_in_the_terminal)
                    } else {
                        stringResource(R.string.hosteditscreen_image_escapes_from_this_host_are_ignored)
                    },
                    onChange = { terminalImages = it },
                )
                RowDivider()
                OverrideRow(
                    title = stringResource(R.string.hosteditscreen_tmux_controls),
                    icon = Icons.Rounded.Dashboard,
                    value = tmuxControls,
                    // Not "Follow": leaving this alone means "when this host is
                    // attached to tmux", which is a condition rather than a
                    // deferral to the app's setting.
                    neutral = "Auto",
                    subtitle = if (settings.tmuxControls && (tmuxControls ?: persistent)) {
                        stringResource(R.string.hosteditscreen_the_chords_sheet_the_window_list_and_the_swipe_t)
                    } else {
                        stringResource(R.string.hosteditscreen_off_nothing_to_drive_without_a_tmux_on_the_other)
                    },
                    onChange = { tmuxControls = it },
                )
                RowDivider()
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.hosteditscreen_font_size), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(
                            if (fontSizeSp > 0f) "${fontSizeSp.roundToInt()} sp" else stringResource(R.string.hosteditscreen_follow_settings),
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
                        Text(stringResource(R.string.hosteditscreen_ls_la_preview_0123), style = CodeStyle.copy(fontSize = (if (fontSizeSp > 0f) fontSizeSp else settings.fontSizeSp).sp))
                    }
                    Text(
                        if (fontSizeSp > 0f) {
                            stringResource(R.string.hosteditscreen_pinching_in_a_session_on_this_host_changes_this)
                        } else {
                            stringResource(R.string.hosteditscreen_this_host_draws_at_the_app_s_sp_and_pinching_cha, settings.fontSizeSp.roundToInt())
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                RowDivider()
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(
                        patterns, { patterns = it }, stringResource(R.string.hosteditscreen_notify_when_output_matches_one_regex_per_line), mono = true, singleLine = false, minLines = 2,
                        placeholder = stringResource(R.string.hosteditscreen_build_finished_failed_nerror),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                    )
                    Text(stringResource(R.string.hosteditscreen_matching_lines_raise_a_notification_while_the_ap), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // Six hundred previews do not belong inline on a settings page; the
            // picker opens as a sheet, since this draft host has no route to
            // come back to.
            if (page == EditorPage.Terminal) Group(stringResource(R.string.hosteditscreen_color_scheme)) {
                GroupRow(
                    title = hostTheme?.let { Schemes.nameOf(it) } ?: stringResource(R.string.hosteditscreen_app_default_2),
                    subtitle = if (hostTheme == null) Schemes.nameOf(inheritedTheme) else stringResource(R.string.hosteditscreen_this_host_only),
                    icon = Icons.Rounded.Palette,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    onClick = { themeSheet = true },
                    trailing = { PaletteChip(hostTheme, inheritedTheme) },
                )
            }

            if (confirmRecord) {
                AlertDialog(
                    onDismissRequest = { confirmRecord = false },
                    title = { Text(stringResource(R.string.hosteditscreen_record_every_session)) },
                    text = {
                        Text(
                            stringResource(R.string.hosteditscreen_everything_the_host_prints_is_written_to_a_file) +
                                stringResource(R.string.hosteditscreen_recording_carries_on_while_the_app_is_in_the_bac) +
                                stringResource(R.string.hosteditscreen_left_open_all_day_can_reach_several_gigabytes_on) +
                                stringResource(R.string.hosteditscreen_large_recording_can_be_opened_again),
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { recordSessions = true; confirmRecord = false }) { Text(stringResource(R.string.hosteditscreen_record)) }
                    },
                    dismissButton = { TextButton(onClick = { confirmRecord = false }) { Text(stringResource(R.string.hosteditscreen_cancel)) } },
                )
            }

            if (page == EditorPage.OnConnect) Group(stringResource(R.string.hosteditscreen_after_login)) {
                GroupRow(
                    title = stringResource(R.string.hosteditscreen_record_every_session_2),
                    subtitle = if (recordSessions) {
                        stringResource(R.string.hosteditscreen_saved_as_login_to_logout, stringResource(settings.recordingFormat.label).lowercase())
                    } else {
                        stringResource(R.string.hosteditscreen_off_record_by_hand_from_the_terminal_s_menu)
                    },
                    icon = Icons.Rounded.FiberManualRecord,
                    iconTint = MaterialTheme.colorScheme.error,
                    trailing = { AppSwitch(recordSessions, { if (it) confirmRecord = true else recordSessions = false }) },
                )
                RowDivider()
                GroupRow(
                    title = stringResource(R.string.hosteditscreen_persistent_session),
                    subtitle = stringResource(R.string.hosteditscreen_attach_to_tmux_at_login_and_attach_again_automat),
                    icon = Icons.Rounded.Power,
                    iconTint = MaterialTheme.colorScheme.primary,
                    trailing = { AppSwitch(persistent, { persistent = it }) },
                )
                if (persistent) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Field(tmuxSession, { tmuxSession = it }, stringResource(R.string.hosteditscreen_tmux_session_name), Modifier.weight(1f), mono = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false))
                            // What the chords sheet and the window list send, so
                            // a tmux rebound to C-a is driven as it is configured.
                            Field(tmuxPrefix, { tmuxPrefix = it }, stringResource(R.string.hosteditscreen_tmux_prefix), Modifier.width(120.dp), mono = true, placeholder = "C-b", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false))
                        }
                        Text(stringResource(R.string.hosteditscreen_needs_tmux_on_the_host_your_shell_runs_inside_it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.hosteditscreen_back_to_the_last_session), style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    stringResource(R.string.hosteditscreen_reattach_to_whichever_tmux_session_you_were_last),
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
                    title = stringResource(R.string.hosteditscreen_run_snippets),
                    subtitle = when {
                        chosenSnippets.isEmpty() -> stringResource(R.string.hosteditscreen_saved_commands_run_in_order_before_the_one_below)
                        chosenSnippets.size == 1 -> chosenSnippets.first().name.ifBlank { stringResource(R.string.hosteditscreen_one_snippet) }
                        else -> stringResource(R.string.hosteditscreen_snippets, chosenSnippets.size, chosenSnippets.joinToString(", ") { it.name.ifBlank { "unnamed" } })
                    },
                    icon = Icons.Rounded.AutoAwesome,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    onClick = { snippetSheet = true },
                )
                // A command only this host needs is not worth a snippet in the
                // shared list, so it lives here, next to the snippets it runs after.
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Field(
                        startup, { startup = it }, stringResource(R.string.hosteditscreen_command_for_this_host_only), mono = true, singleLine = false, minLines = 2,
                        placeholder = stringResource(R.string.hosteditscreen_cd_srv_status_sh),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                    )
                    Text(
                        stringResource(R.string.hosteditscreen_runs_after_the_snippets_above_and_before_tmux_wi),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Only SSH has a channel to ask on: a telnet console has no such
                // idea, and a Mosh session is not an SSH shell by the time it starts.
                if (protocol == Protocol.SSH) {
                    RowDivider()
                    GroupRow(
                        title = stringResource(R.string.hosteditscreen_environment_variables),
                        subtitle = when {
                            env.isEmpty() && mosh -> stringResource(R.string.hosteditscreen_passed_to_mosh_server_which_sets_them_itself_the)
                            env.isEmpty() -> stringResource(R.string.hosteditscreen_asked_for_when_the_shell_opens_most_servers_acce)
                            else -> stringResource(R.string.hosteditscreen_set_on_connect, env.size)
                        },
                        icon = Icons.Rounded.DataObject,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        trailing = { TextButton(onClick = { env = env + EnvEntry("", "") }) { Text(stringResource(R.string.hosteditscreen_add)) } },
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
                                    "Value", Modifier.weight(1f), placeholder = stringResource(R.string.hosteditscreen_flintterm), mono = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                                )
                                IconButton(onClick = { env = env.filterIndexed { j, _ -> j != i } }) {
                                    Icon(Icons.Rounded.Delete, stringResource(R.string.hosteditscreen_remove_variable), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                            if (i < env.lastIndex) HorizontalDivider()
                        }
                    }
                    if (env.isNotEmpty()) {
                        Text(
                            if (mosh) {
                                stringResource(R.string.hosteditscreen_mosh_takes_these_as_mosh_server_arguments_so_the)
                            } else {
                                stringResource(R.string.hosteditscreen_sshd_only_passes_the_names_its_acceptenv_lists_a)
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                        )
                    }
                }
            }

            // ---- forwards ------------------------------------------------------------
            if (page == EditorPage.Forwards) Group(stringResource(R.string.hosteditscreen_port_forwarding)) {
                forwards.forEachIndexed { i, f ->
                    GroupRow(
                        title = f.describe(),
                        subtitle = if (f.autoStart) stringResource(R.string.hosteditscreen_starts_with_the_session) else "Manual",
                        subtitleMono = false,
                        icon = Icons.Rounded.SwapHoriz,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        onClick = { editingForward = f },
                        trailing = { IconButton(onClick = { forwards = forwards.filterNot { it.id == f.id } }) { Icon(Icons.Rounded.Delete, "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant) } },
                    )
                    if (i < forwards.lastIndex) RowDivider()
                }
                if (forwards.isNotEmpty()) RowDivider()
                GroupRow(title = stringResource(R.string.hosteditscreen_add_forward), icon = Icons.Rounded.Add, iconTint = MaterialTheme.colorScheme.primary, onClick = { editingForward = PortForward() })
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
                    Text(stringResource(R.string.hosteditscreen_color_icon), style = MaterialTheme.typography.titleLarge)
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
                        ) { Icon(iconFor(hi), stringResource(hi.label), Modifier.size(22.dp), tint = if (sel) accent else MaterialTheme.colorScheme.onSurfaceVariant) }
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
            title = stringResource(R.string.hosteditscreen_account),
            subtitle = stringResource(R.string.hosteditscreen_who_to_log_in_as),
            actions = listOf(
                SheetAction(
                    stringResource(R.string.hosteditscreen_this_host_only),
                    Icons.Rounded.Person,
                    subtitle = stringResource(R.string.hosteditscreen_keep_the_username_and_key_typed_into_this_host),
                ) { accountId = null; accountSheet = false },
            ) + accounts.map { a ->
                SheetAction(a.label, Icons.Rounded.Person, subtitle = a.username.ifBlank { stringResource(R.string.hosteditscreen_no_username) }) {
                    accountId = a.id
                    accountSheet = false
                }
            } + SheetAction(stringResource(R.string.hosteditscreen_new_account), Icons.Rounded.Add) {
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
            title = stringResource(R.string.hosteditscreen_ssh_key),
            actions = identities.map { i ->
                SheetAction(i.name, Icons.Rounded.Key, subtitle = i.fingerprint) { identityId = i.id; keySheet = false }
            } + SheetAction(stringResource(R.string.hosteditscreen_manage_keys), Icons.Rounded.Add) { keySheet = false; nav.navigate(Routes.KEYS) },
        )
    }
    detected?.let { list ->
        ActionSheet(
            onDismiss = { detected = null },
            title = stringResource(R.string.hosteditscreen_which_interface),
            subtitle = stringResource(R.string.hosteditscreen_the_first_one_carries_the_default_route),
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
                stringResource(R.string.hosteditscreen_only_this_host_s_traffic_goes_through_it_no_syst)
            } else {
                stringResource(R.string.hosteditscreen_used_for_this_address_only_so_a_lan_address_can)
            },
            actions = listOf(
                SheetAction(stringResource(R.string.hosteditscreen_direct_network), Icons.Rounded.SwapHoriz, subtitle = "No VPN") { choose(null, null) },
            ) +
                tunnels.map { t ->
                    SheetAction(t.name.ifBlank { "Tunnel" }, Icons.Rounded.VpnLock, subtitle = stringResource(R.string.hosteditscreen_wireguard_tunnel)) { choose(t.id, null) }
                } +
                tailnets.map { p ->
                    SheetAction(p.name.ifBlank { "Tailscale" }, Icons.Rounded.Hub, subtitle = if (p.joined) "Tailscale" else stringResource(R.string.hosteditscreen_tailscale_not_joined_yet)) { choose(null, p.id) }
                } +
                SheetAction(stringResource(R.string.hosteditscreen_manage_vpn_tunnels), Icons.Rounded.Add) { vpnSheet = false; vpnFor = null; nav.navigate(Routes.TUNNELS) },
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
            title = stringResource(R.string.hosteditscreen_run_on_connect),
            subtitle = when {
                offered.isEmpty() && asking > 0 -> stringResource(R.string.hosteditscreen_every_snippet_for_this_host_asks_for_a_value)
                offered.isEmpty() -> stringResource(R.string.hosteditscreen_no_snippets_for_this_host_yet)
                asking > 0 -> stringResource(R.string.hosteditscreen_run_in_the_order_picked_snippet_s_ask_for_a_valu, asking)
                else -> stringResource(R.string.hosteditscreen_run_in_the_order_picked_before_the_host_s_own_co)
            },
            actions = offered.map { sn ->
                val on = sn.id in startupSnippetIds
                SheetAction(
                    sn.name.ifBlank { stringResource(R.string.hosteditscreen_unnamed_snippet) } + if (on) "  ✓" else "",
                    Icons.Rounded.AutoAwesome,
                    subtitle = sn.command.lines().first().take(60),
                ) {
                    // Kept open: picking several is the normal case, and the
                    // order they are switched on in is the order they run in.
                    startupSnippetIds = if (on) startupSnippetIds - sn.id else startupSnippetIds + sn.id
                }
            } + SheetAction(stringResource(R.string.hosteditscreen_manage_snippets), Icons.Rounded.Add) { snippetSheet = false; nav.navigate(Routes.SNIPPETS) },
        )
    }
    if (proxySheet) {
        ActionSheet(
            onDismiss = { proxySheet = false },
            title = stringResource(R.string.hosteditscreen_proxy),
            subtitle = stringResource(R.string.hosteditscreen_the_first_hop_is_dialled_through_it_proxies_are),
            actions = listOf(SheetAction(stringResource(R.string.hosteditscreen_none), Icons.Rounded.SwapHoriz) { proxyId = null; proxySheet = false }) +
                proxies.map { p ->
                    SheetAction(p.label, Icons.Rounded.Lan, subtitle = p.target) { proxyId = p.id; proxySheet = false }
                } +
                SheetAction(stringResource(R.string.hosteditscreen_manage_proxies), Icons.Rounded.Add) { proxySheet = false; nav.navigate(Routes.TUNNELS) },
        )
    }
    if (jumpSheet) {
        ActionSheet(
            onDismiss = { jumpSheet = false },
            title = stringResource(R.string.hosteditscreen_connect_through),
            subtitle = stringResource(R.string.hosteditscreen_the_jump_host_is_connected_first_then_this_host),
            actions = listOf(SheetAction(stringResource(R.string.hosteditscreen_direct_connection), Icons.Rounded.SwapHoriz) { jumpHostId = null; jumpSheet = false }) +
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
        title = { Text(stringResource(R.string.hosteditscreen_port_forward)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Segmented(listOf(stringResource(R.string.hosteditscreen_local_l), stringResource(R.string.hosteditscreen_remote_r), "SOCKS -D"), type.ordinal) { type = ForwardType.entries[it] }
                Text(
                    when (type) {
                        ForwardType.LOCAL -> stringResource(R.string.hosteditscreen_this_device_listens_and_connections_go_to_the_ta)
                        ForwardType.REMOTE -> stringResource(R.string.hosteditscreen_the_server_listens_and_connections_are_delivered)
                        ForwardType.DYNAMIC -> stringResource(R.string.hosteditscreen_a_socks5_proxy_on_this_device_point_a_browser_or)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(bindHost, { bindHost = it }, if (type == ForwardType.REMOTE) stringResource(R.string.hosteditscreen_remote_bind) else stringResource(R.string.hosteditscreen_bind_address), Modifier.weight(1f), mono = true)
                    Field(bindPort, { bindPort = it.filter { c -> c.isDigit() }.take(5) }, "Port", Modifier.width(88.dp), mono = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                if (!dynamic) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(targetHost, { targetHost = it }, stringResource(R.string.hosteditscreen_target_host), Modifier.weight(1f), mono = true)
                    Field(targetPort, { targetPort = it.filter { c -> c.isDigit() }.take(5) }, "Port", Modifier.width(88.dp), mono = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.hosteditscreen_start_automatically), Modifier.weight(1f))
                    AppSwitch(checked = autoStart, onCheckedChange = { autoStart = it })
                }
            }
        },
        confirmButton = {
            Button(enabled = valid, onClick = {
                onSave(initial.copy(type = type, bindHost = bindHost.trim(), bindPort = bindPort.toInt(), targetHost = targetHost.trim(), targetPort = targetPort.toInt(), autoStart = autoStart))
            }) { Text(stringResource(R.string.hosteditscreen_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.hosteditscreen_cancel)) } },
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
