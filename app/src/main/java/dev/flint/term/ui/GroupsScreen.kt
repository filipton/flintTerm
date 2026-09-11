package dev.flint.term.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.flint.term.App
import dev.flint.term.data.HostGroup
import dev.flint.term.data.Schemes

/**
 * The groups hosts are filed under, and what they hand down.
 *
 * A group is a folder that also carries settings: the jump host, VPN, proxy,
 * account and color its members would otherwise each need typed in. Nothing
 * here overrides a host — a value set on the host itself always wins.
 */
@Composable
fun GroupsScreen(nav: NavController) {
    val app = LocalContext.current.applicationContext as App
    val groups by app.store.groups.collectAsStateWithLifecycle()
    val hosts by app.store.hosts.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppHeader(title = "Groups", onBack = { nav.popBackStack() }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    val made = HostGroup(name = "New group")
                    app.store.upsertGroup(made)
                    nav.navigate(Routes.groupEdit(made.id))
                },
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text("Add") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(20.dp),
            )
        },
    ) { padding ->
        LazyColumn(state = rememberScreenListState("groups"), modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 110.dp)) {
            if (groups.isEmpty()) {
                item {
                    EmptyState(
                        Icons.Rounded.Folder,
                        "No groups yet",
                        "A group files hosts together and carries what they share: a jump host, a VPN, a proxy, an account. Hosts you give a group to appear here.",
                    )
                }
            }
            items(groups, key = { it.id }) { g ->
                val count = hosts.count { it.groupId == g.id }
                Group(modifier = Modifier.padding(top = 10.dp)) {
                    GroupRow(
                        title = g.label,
                        subtitle = listOfNotNull(
                            when (count) {
                                0 -> "no hosts"
                                1 -> "1 host"
                                else -> "$count hosts"
                            },
                            g.defaultCount().takeIf { it > 0 }?.let { "$it shared setting${if (it > 1) "s" else ""}" },
                        ).joinToString("  ·  "),
                        icon = Icons.Rounded.Folder,
                        onClick = { nav.navigate(Routes.groupEdit(g.id)) },
                    )
                }
            }
        }
    }
}

/** One group: its name, and the defaults its hosts inherit. */
@Composable
fun GroupEditScreen(nav: NavController, id: String) {
    val app = LocalContext.current.applicationContext as App
    val groups by app.store.groups.collectAsStateWithLifecycle()
    val hosts by app.store.hosts.collectAsStateWithLifecycle()
    val accounts by app.store.accounts.collectAsStateWithLifecycle()
    val tunnels by app.store.tunnels.collectAsStateWithLifecycle()
    val tailnets by app.store.tailscaleProfiles.collectAsStateWithLifecycle()
    val proxies by app.store.proxies.collectAsStateWithLifecycle()
    val settings by app.store.settings.collectAsStateWithLifecycle()
    val group = groups.firstOrNull { it.id == id }

    if (group == null) {
        // Deleted from under us (or a stale link): nothing to edit.
        Scaffold(topBar = { AppHeader(title = "Group", onBack = { nav.popBackStack() }) }) { p ->
            Column(Modifier.fillMaxSize().padding(p)) {
                EmptyState(Icons.Rounded.Folder, "Gone", "This group no longer exists.")
            }
        }
        return
    }

    var name by remember(group.id) { mutableStateOf(group.name) }
    var sheet by remember { mutableStateOf<GroupPick?>(null) }
    val members = hosts.count { it.groupId == group.id }

    fun save(block: (HostGroup) -> HostGroup) = app.store.upsertGroup(block(group))

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppHeader(
                title = group.label,
                subtitle = if (members == 1) "1 host" else "$members hosts",
                onBack = {
                    save { it.copy(name = name.trim()) }
                    nav.popBackStack()
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScreenScroll("group-edit"))) {
            Group {
                Column(Modifier.padding(14.dp)) {
                    Field(name, { name = it }, "Group name", placeholder = "Work")
                }
            }

            Group("Shared by every host in this group") {
                GroupRow(
                    title = "Account",
                    subtitle = accounts.firstOrNull { it.id == group.accountId }?.let { "${it.label}  ·  ${it.username}" } ?: "None",
                    icon = Icons.Rounded.Person,
                    onClick = { sheet = GroupPick.Account },
                )
                RowDivider()
                GroupRow(
                    title = "VPN",
                    subtitle = when {
                        group.tailscaleId != null -> "${tailnets.firstOrNull { it.id == group.tailscaleId }?.name.orEmpty().ifBlank { "Tailscale" }}  ·  Tailscale"
                        group.tunnelId != null -> "${tunnels.firstOrNull { it.id == group.tunnelId }?.name.orEmpty()}  ·  WireGuard"
                        else -> "None"
                    },
                    icon = if (group.tailscaleId != null) Icons.Rounded.Hub else Icons.Rounded.VpnLock,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    onClick = { sheet = GroupPick.Vpn },
                )
                RowDivider()
                GroupRow(
                    title = "Jump host",
                    subtitle = hosts.firstOrNull { it.id == group.jumpHostId }?.displayName ?: "None",
                    icon = Icons.Rounded.AltRoute,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    onClick = { sheet = GroupPick.Jump },
                )
                RowDivider()
                GroupRow(
                    title = "Proxy",
                    subtitle = proxies.firstOrNull { it.id == group.proxyId }?.let { "${it.label}  ·  ${it.target}" } ?: "None",
                    icon = Icons.Rounded.SwapHoriz,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    onClick = { sheet = GroupPick.Proxy },
                )
                RowDivider()
                GroupRow(
                    title = "Color scheme",
                    subtitle = group.theme?.let { Schemes.nameOf(it) } ?: "App default  ·  ${Schemes.nameOf(settings.theme)}",
                    icon = Icons.Rounded.Palette,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    onClick = {
                        // The name is the one thing on this page that is not written
                        // as it is changed, so it is committed before we leave.
                        save { it.copy(name = name.trim()) }
                        nav.navigate(Routes.groupTheme(group.id))
                    },
                    trailing = { PaletteChip(group.theme, settings.theme) },
                )
            }

            Group("Accent") {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ColorPicker(selected = if (group.color != 0) Color(group.color) else null) { c ->
                        save { it.copy(color = if (it.color == c.toArgb()) 0 else c.toArgb()) }
                    }
                    Text(
                        "Used on the cards of hosts that have not picked a color of their own.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Group {
                GroupRow(
                    title = "Delete group",
                    subtitle = "Its hosts keep every setting of their own and simply stop inheriting",
                    icon = Icons.Rounded.Delete,
                    iconTint = MaterialTheme.colorScheme.error,
                    titleColor = MaterialTheme.colorScheme.error,
                    onClick = {
                        app.store.deleteGroup(group.id)
                        nav.popBackStack()
                    },
                )
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    when (sheet) {
        GroupPick.Account -> ActionSheet(
            onDismiss = { sheet = null },
            title = "Account",
            actions = listOf(SheetAction("None", Icons.Rounded.Person) { save { it.copy(accountId = null) }; sheet = null }) +
                accounts.map { a ->
                    SheetAction(a.label, Icons.Rounded.Person, subtitle = a.username) { save { it.copy(accountId = a.id) }; sheet = null }
                },
        )
        GroupPick.Vpn -> ActionSheet(
            onDismiss = { sheet = null },
            title = "VPN",
            subtitle = "Used by hosts in this group that have not chosen one",
            actions = listOf(SheetAction("Direct network", Icons.Rounded.VpnLock) { save { it.copy(tunnelId = null, tailscaleId = null) }; sheet = null }) +
                tunnels.map { t ->
                    SheetAction(t.name, Icons.Rounded.VpnLock, subtitle = "WireGuard") { save { it.copy(tunnelId = t.id, tailscaleId = null) }; sheet = null }
                } +
                tailnets.map { p ->
                    SheetAction(p.name.ifBlank { "Tailscale" }, Icons.Rounded.Hub, subtitle = "Tailscale") { save { it.copy(tailscaleId = p.id, tunnelId = null) }; sheet = null }
                },
        )
        GroupPick.Jump -> ActionSheet(
            onDismiss = { sheet = null },
            title = "Jump host",
            actions = listOf(SheetAction("None", Icons.Rounded.AltRoute) { save { it.copy(jumpHostId = null) }; sheet = null }) +
                // A member of the group cannot be its own way in, so those are
                // left out rather than offered and then ignored.
                hosts.filter { it.groupId != group.id && !it.isTelnet }.map { h ->
                    SheetAction(h.displayName, Icons.Rounded.AltRoute, subtitle = h.target) { save { it.copy(jumpHostId = h.id) }; sheet = null }
                },
        )
        GroupPick.Proxy -> ActionSheet(
            onDismiss = { sheet = null },
            title = "Proxy",
            actions = listOf(SheetAction("None", Icons.Rounded.SwapHoriz) { save { it.copy(proxyId = null) }; sheet = null }) +
                proxies.map { p ->
                    SheetAction(p.label, Icons.Rounded.SwapHoriz, subtitle = p.target) { save { it.copy(proxyId = p.id) }; sheet = null }
                },
        )
        null -> {}
    }
}

private enum class GroupPick { Account, Vpn, Jump, Proxy }

/** Left for the host editor: a text prompt for naming a group on the spot. */
@Composable
fun NewGroupDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New group") },
        text = { Field(name, { name = it }, "Name", placeholder = "Work") },
        confirmButton = {
            TextButton(onClick = { onCreate(name.trim()) }, enabled = name.isNotBlank()) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
