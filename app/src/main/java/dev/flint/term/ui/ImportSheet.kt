package dev.flint.term.ui

import dev.flint.term.R
import androidx.compose.ui.res.stringResource
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.flint.term.App
import dev.flint.term.core.CoreException
import dev.flint.term.core.importPpk
import dev.flint.term.core.inspectKey
import dev.flint.term.data.Identity
import dev.flint.term.data.KnownHost
import dev.flint.term.data.OpenSshImport
import dev.flint.term.data.imports.ConnectBotImport
import dev.flint.term.data.imports.CsvImport
import dev.flint.term.data.imports.ImportPlan
import dev.flint.term.data.imports.planImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Import": pick a file another app wrote and take what this app can use.
 *
 * Two families, in one sheet. OpenSSH's own `config` and `known_hosts` are
 * text this app understands directly; ConnectBot, PuTTY and a spreadsheet are
 * other people's formats, listed under their own heading because picking the
 * wrong one of five is a lot easier than picking the wrong one of two.
 *
 * Nothing here overwrites: a host already saved is counted and skipped, never
 * updated from the file, because the file cannot know which of the two is the
 * newer truth.
 */
@Composable
fun ImportSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val scope = rememberCoroutineScope()
    var plans by remember { mutableStateOf<List<OpenSshImport.Plan>?>(null) }
    var keys by remember { mutableStateOf<List<KnownHost>?>(null) }
    /** Where the hosts came from, and what importing them would do. */
    var incoming by remember { mutableStateOf<Pair<String, ImportPlan>?>(null) }
    /** A CSV whose headers this could not read on its own, awaiting a mapping. */
    var columns by remember { mutableStateOf<CsvImport.Table?>(null) }
    var ppk by remember { mutableStateOf<ByteArray?>(null) }

    fun readText(uri: android.net.Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
    }.getOrNull()

    fun say(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    fun offer(source: String, hosts: List<dev.flint.term.data.imports.ImportedHost>) {
        val plan = planImport(hosts, app.store.hosts.value)
        if (plan.total == 0) say("No hosts in that file") else incoming = source to plan
    }

    val pickConfig = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val text = uri?.let(::readText) ?: return@rememberLauncherForActivityResult
        val entries = OpenSshImport.parseConfig(text)
        if (entries.isEmpty()) say("No Host entries found in that file")
        else plans = OpenSshImport.plan(entries, app.store.hosts.value, app.store.identities.value)
    }
    val pickKnown = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val text = uri?.let(::readText) ?: return@rememberLauncherForActivityResult
        val parsed = OpenSshImport.parseKnownHosts(text)
        if (parsed.isEmpty()) say("No host keys found in that file")
        else keys = parsed
    }
    val pickConnectBot = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val text = uri?.let(::readText) ?: return@rememberLauncherForActivityResult
        if (!ConnectBotImport.looksLikeExport(text)) say("That is not a ConnectBot export. Use Export hosts in ConnectBot first")
        else offer("ConnectBot", ConnectBotImport.parse(text))
    }
    val pickCsv = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val text = uri?.let(::readText) ?: return@rememberLauncherForActivityResult
        val table = CsvImport.read(text)
        when {
            table == null -> say("That file has no rows")
            // Headers this already understands need no questions asked.
            table.ready(table.suggested) -> offer("CSV", CsvImport.hosts(table, table.suggested))
            else -> columns = table
        }
    }
    val pickPpk = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val bytes = uri?.let { u -> runCatching { context.contentResolver.openInputStream(u)?.use { it.readBytes() } }.getOrNull() }
        if (bytes == null) say("Could not read that file") else ppk = bytes
    }

    if (plans == null && keys == null && incoming == null && columns == null && ppk == null) {
        ActionSheet(
            onDismiss = onDismiss,
            title = stringResource(R.string.importsheet_import),
            subtitle = stringResource(R.string.importsheet_copy_the_file_off_your_computer_or_other_phone_f),
            actions = listOf(
                SheetAction(stringResource(R.string.importsheet_ssh_config), Icons.Rounded.Description, subtitle = stringResource(R.string.importsheet_host_blocks_become_hosts_proxyjump_becomes_a_jum)) { pickConfig.launch(arrayOf("*/*")) },
                SheetAction(stringResource(R.string.importsheet_known_hosts), Icons.Rounded.Security, subtitle = stringResource(R.string.importsheet_trust_the_server_keys_your_computer_already_trus)) { pickKnown.launch(arrayOf("*/*")) },
                SheetAction(
                    stringResource(R.string.importsheet_connectbot_export), Icons.Rounded.Description,
                    subtitle = stringResource(R.string.importsheet_the_json_from_connectbot_s_export_hosts),
                    section = stringResource(R.string.importsheet_from_another_app),
                ) { pickConnectBot.launch(arrayOf("*/*")) },
                SheetAction(
                    stringResource(R.string.importsheet_putty_key), Icons.Rounded.VpnKey,
                    subtitle = stringResource(R.string.importsheet_a_ppk_file_version_2_or_3),
                    section = stringResource(R.string.importsheet_from_another_app),
                ) { pickPpk.launch(arrayOf("*/*")) },
                SheetAction(
                    "CSV", Icons.Rounded.GridOn,
                    subtitle = stringResource(R.string.importsheet_termius_s_template_or_any_spreadsheet_with_a_col),
                    section = stringResource(R.string.importsheet_from_another_app),
                ) { pickCsv.launch(arrayOf("*/*")) },
            ),
        )
    }

    plans?.let { list ->
        var ticked by remember(list) { mutableStateOf(list.filter { it.existing == null }.map { it.entry.alias }.toSet()) }
        AlertDialog(
            onDismissRequest = { plans = null; onDismiss() },
            title = { Text(stringResource(R.string.importsheet_import_of_hosts, ticked.size, list.size)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(list, key = { it.entry.alias }) { p ->
                        val e = p.entry
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Checkbox(e.alias in ticked, { on -> ticked = if (on) ticked + e.alias else ticked - e.alias })
                            Spacer(Modifier.width(4.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.alias, fontWeight = FontWeight.Medium)
                                Text(
                                    buildString {
                                        append(if (e.user.isBlank()) e.hostName else "${e.user}@${e.hostName}")
                                        if (e.port != 22) append(":${e.port}")
                                        if (p.jumpAlias != null) append("  ·  via ${p.jumpAlias}")
                                        if (p.identity != null) append("  ·  key ${p.identity.name}")
                                        else if (e.identityFile != null) append("  ·  key ${e.identityFile.substringAfterLast('/')} not in this app")
                                        if (p.existing != null) append("  ·  already added as ${p.existing.displayName}")
                                    },
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(enabled = ticked.isNotEmpty(), onClick = {
                    OpenSshImport.apply(app.store, list.filter { it.entry.alias in ticked }, group = "")
                    Toast.makeText(context, context.getString(R.string.importsheet_imported_host, ticked.size, if (ticked.size == 1) "" else "s"), Toast.LENGTH_SHORT).show()
                    plans = null; onDismiss()
                }) { Text(stringResource(R.string.importsheet_import)) }
            },
            dismissButton = { TextButton(onClick = { plans = null; onDismiss() }) { Text(stringResource(R.string.importsheet_cancel)) } },
        )
    }

    keys?.let { list ->
        // A hashed entry has no name to look up by, so both kinds are matched on the entry itself.
        val trusted = app.store.knownHosts.value
        val fresh = list.filter { k -> trusted.none { it.sameHostAs(k) && it.keyBase64 == k.keyBase64 } }
        AlertDialog(
            onDismissRequest = { keys = null; onDismiss() },
            title = { Text(stringResource(R.string.importsheet_trust_server_key, fresh.size, if (fresh.size == 1) "" else "s")) },
            text = {
                Column {
                    if (fresh.size < list.size) Text(stringResource(R.string.importsheet_already_trusted, list.size - fresh.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                    LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(fresh) { k ->
                            Column {
                                val name = when {
                                    k.isHashed -> KnownHost.HASHED_LABEL
                                    k.port == 22 -> k.host
                                    else -> "${k.host}:${k.port}"
                                }
                                Text(name, fontWeight = FontWeight.Medium)
                                Text("${k.keyType}  ${k.fingerprint}", style = MaterialTheme.typography.bodySmall, fontFamily = MonoFamily, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(enabled = fresh.isNotEmpty(), onClick = {
                    fresh.forEach { app.store.rememberHostKey(it) }
                    Toast.makeText(context, context.getString(R.string.importsheet_trusted_server_key, fresh.size, if (fresh.size == 1) "" else "s"), Toast.LENGTH_SHORT).show()
                    keys = null; onDismiss()
                }) { Text(stringResource(R.string.importsheet_trust)) }
            },
            dismissButton = { TextButton(onClick = { keys = null; onDismiss() }) { Text(stringResource(R.string.importsheet_cancel)) } },
        )
    }

    columns?.let { table ->
        var mapping by remember(table) { mutableStateOf(table.suggested) }
        AlertDialog(
            onDismissRequest = { columns = null; onDismiss() },
            title = { Text(stringResource(R.string.importsheet_which_column_is_which)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.importsheet_row_only_the_hostname_is_required, table.rows.size, if (table.rows.size == 1) "" else "s"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    for (field in CsvImport.Field.entries) {
                        ColumnChoice(field.label, table.headers, mapping[field]) { at ->
                            mapping = if (at == null) mapping - field else mapping + (field to at)
                        }
                    }
                }
            },
            confirmButton = {
                Button(enabled = table.ready(mapping), onClick = {
                    val hosts = CsvImport.hosts(table, mapping)
                    columns = null
                    offer("CSV", hosts)
                }) { Text(stringResource(R.string.importsheet_continue)) }
            },
            dismissButton = { TextButton(onClick = { columns = null; onDismiss() }) { Text(stringResource(R.string.importsheet_cancel)) } },
        )
    }

    incoming?.let { (source, plan) ->
        AlertDialog(
            onDismissRequest = { incoming = null; onDismiss() },
            title = { Text(stringResource(R.string.importsheet_import_from, plan.describe(), source)) },
            text = {
                Column {
                    if (plan.alreadyHere > 0) Text(
                        stringResource(R.string.importsheet_hosts_you_already_have_are_left_exactly_as_they),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(plan.fresh) { h ->
                            Column {
                                Text(h.label.ifBlank { h.hostname }, fontWeight = FontWeight.Medium)
                                Text(
                                    buildString {
                                        append(if (h.username.isBlank()) h.hostname else "${h.username}@${h.hostname}")
                                        append(":${h.port}")
                                        if (h.startupCommand.isNotBlank()) append("  ·  runs a command at login")
                                    },
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(enabled = plan.fresh.isNotEmpty(), onClick = {
                    // Loose in the list, like the OpenSSH import leaves them: a
                    // group named after the app they came from is a fact about
                    // this afternoon, not about the machines.
                    plan.fresh.forEach { app.store.upsertHost(it.toHost()) }
                    say("Imported ${plan.describe()}")
                    incoming = null; onDismiss()
                }) { Text(stringResource(R.string.importsheet_import)) }
            },
            dismissButton = { TextButton(onClick = { incoming = null; onDismiss() }) { Text(stringResource(R.string.importsheet_cancel)) } },
        )
    }

    ppk?.let { bytes ->
        var name by remember(bytes) { mutableStateOf("PuTTY key") }
        var passphrase by remember(bytes) { mutableStateOf("") }
        var error by remember(bytes) { mutableStateOf<String?>(null) }
        var busy by remember(bytes) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { ppk = null; onDismiss() },
            title = { Text(stringResource(R.string.importsheet_import_putty_key)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        stringResource(R.string.importsheet_the_key_is_converted_to_the_openssh_format_and_s),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Field(name, { name = it }, "Name")
                    Field(
                        passphrase, { passphrase = it }, stringResource(R.string.importsheet_passphrase_if_any),
                        visualTransformation = PasswordVisualTransformation(),
                        autofill = ContentType.Password,
                    )
                    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(enabled = name.isNotBlank() && !busy, onClick = {
                    busy = true
                    scope.launch(Dispatchers.IO) {
                        // Both calls can be slow — Argon2 by design, and the key
                        // parse by accident of size — so neither runs on the UI
                        // thread even though both look instantaneous.
                        val result = runCatching {
                            val openssh = importPpk(bytes, passphrase)
                            val info = inspectKey(openssh, null)
                            Identity(name = name.trim(), privateKey = openssh, publicKey = info.publicKey, fingerprint = info.fingerprint)
                        }
                        withContext(Dispatchers.Main) {
                            busy = false
                            result
                                .onSuccess {
                                    app.store.upsertIdentity(it)
                                    say("Imported ${it.name}")
                                    ppk = null
                                    onDismiss()
                                }
                                .onFailure { error = (it as? CoreException)?.message ?: it.message ?: "could not read that key" }
                        }
                    }
                }) { Text(if (busy) "Importing…" else "Import") }
            },
            dismissButton = { TextButton(onClick = { ppk = null; onDismiss() }) { Text(stringResource(R.string.importsheet_cancel)) } },
        )
    }
}

/** One row of the CSV mapping step: a field, and the column it is taken from. */
@Composable
private fun ColumnChoice(label: String, headers: List<String>, selected: Int?, onSelect: (Int?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontWeight = FontWeight.Medium)
        Box {
            Text(
                selected?.let { headers.getOrNull(it) }?.ifBlank { stringResource(R.string.importsheet_column, selected + 1) } ?: stringResource(R.string.importsheet_not_imported),
                style = MaterialTheme.typography.bodySmall,
                color = if (selected == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            )
            DropdownMenu(open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.importsheet_not_imported)) }, onClick = { onSelect(null); open = false })
                headers.forEachIndexed { at, header ->
                    DropdownMenuItem(
                        text = { Text(header.ifBlank { stringResource(R.string.importsheet_column, at + 1) }) },
                        onClick = { onSelect(at); open = false },
                    )
                }
            }
        }
    }
}
