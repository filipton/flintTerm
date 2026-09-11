package dev.flint.term.ui

import dev.flint.term.R
import androidx.compose.ui.res.stringResource
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.flint.term.App
import dev.flint.term.data.OpenSshExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Export as an SSH config": the saved hosts as a file another machine can use.
 *
 * The backup beside it is the round trip back into this app; this is the way
 * out of it, and the two are worth keeping apart in the reader's head. Which is
 * also why the keys are a separate, deliberate step: the config on its own is
 * harmless, and the moment it stops being harmless the person should have said
 * so out loud.
 */
@Composable
fun SshConfigExportDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val scope = rememberCoroutineScope()

    val hosts = app.store.hosts.value
    val identities = app.store.identities.value
    val text = remember {
        OpenSshExport.export(
            hosts, app.store.groups.value, app.store.accounts.value, identities,
            app.store.settings.value.keepaliveSeconds,
        )
    }
    val keys = remember {
        OpenSshExport.keyFiles(hosts, app.store.groups.value, app.store.accounts.value, identities)
    }
    // What the file actually names, which is not always every saved host: a
    // telnet host or one with no address is listed as absent instead.
    val written = remember(text) { text.lineSequence().count { it.startsWith("Host ") } }
    var withKeys by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }

    fun done(ok: Boolean, what: String) {
        Toast.makeText(context, if (ok) context.getString(R.string.sshconfigexportdialog_exported, what) else "Could not write the export", Toast.LENGTH_LONG).show()
        onDismiss()
    }

    val saveConfig = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) { onDismiss(); return@rememberLauncherForActivityResult }
        scope.launch {
            val ok = withContext(Dispatchers.IO) { write(context, uri, text) }
            done(ok, "$written ${if (written == 1) "host" else "hosts"}")
        }
    }
    val saveFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) { onDismiss(); return@rememberLauncherForActivityResult }
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                var all = create(context, root, CONFIG_NAME)?.let { write(context, it, text) } ?: false
                for (key in keys) {
                    // Every key is attempted whatever the last one did: half a
                    // folder is still worth more than the failure that stopped it.
                    val wrote = create(context, root, key.name)?.let { write(context, it, key.privateKey) } ?: false
                    if (key.publicKey.isNotBlank()) {
                        create(context, root, "${key.name}.pub")?.let { write(context, it, key.publicKey.trimEnd() + "\n") }
                    }
                    all = all && wrote
                }
                all
            }
            done(ok, "${written} ${if (written == 1) "host" else "hosts"} and ${keys.size} ${if (keys.size == 1) "key" else "keys"}")
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.sshconfigexportdialog_write_private_to_a_folder, keys.size, if (keys.size == 1) "key" else "keys")) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.sshconfigexportdialog_these_leave_the_phone_unencrypted_as_plain_files) +
                            keys.joinToString(", ") { it.name } + ".",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.padding(4.dp))
                    Text(
                        stringResource(R.string.sshconfigexportdialog_put_them_somewhere_you_control_and_set_their_per) +
                            stringResource(R.string.sshconfigexportdialog_ssh_refuses_a_private_key_that_the_rest_of_the_m),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { Button(onClick = { confirming = false; saveFolder.launch(null) }) { Text(stringResource(R.string.sshconfigexportdialog_choose_a_folder)) } },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.sshconfigexportdialog_cancel)) } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sshconfigexportdialog_export_as_an_ssh_config)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.sshconfigexportdialog_of_your_hosts_as_an_openssh_config_ready_to_drop, written) +
                        stringResource(R.string.sshconfigexportdialog_groups_and_accounts_are_resolved_first_so_each_h),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(4.dp))
                Text(
                    stringResource(R.string.sshconfigexportdialog_no_password_is_written_anything_ssh_has_no_setti),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (keys.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Checkbox(withKeys, { withKeys = it })
                        Spacer(Modifier.width(4.dp))
                        Column {
                            Text(stringResource(R.string.sshconfigexportdialog_write_the_private_keys_beside_it), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                stringResource(R.string.sshconfigexportdialog_of_your_keys_can_be_copied_keystore_and_security, keys.size),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { if (withKeys) confirming = true else saveConfig.launch(CONFIG_NAME) }) {
                Text(if (withKeys) "Continue" else stringResource(R.string.sshconfigexportdialog_choose_where))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.sshconfigexportdialog_cancel)) } },
    )
}

/** Not `config`: a picker that lands in Downloads should not tempt anyone to overwrite a real one. */
private const val CONFIG_NAME = "ssh_config"

private fun write(context: Context, uri: Uri, text: String): Boolean = runCatching {
    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } != null
}.getOrDefault(false)

private fun create(context: Context, parent: Uri, name: String): Uri? =
    runCatching { DocumentsContract.createDocument(context.contentResolver, parent, "text/plain", name) }.getOrNull()
