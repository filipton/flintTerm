package dev.flint.term.ui

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
        Toast.makeText(context, if (ok) "Exported $what" else "Could not write the export", Toast.LENGTH_LONG).show()
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
            done(ok, "$written ${if (written == 1) "host" else "hosts"} and ${keys.size} ${if (keys.size == 1) "key" else "keys"}")
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Write ${keys.size} private ${if (keys.size == 1) "key" else "keys"} to a folder?") },
            text = {
                Column {
                    Text(
                        "These leave the phone unencrypted, as plain files anyone with the folder can read: " +
                            keys.joinToString(", ") { it.name } + ".",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.padding(4.dp))
                    Text(
                        "Put them somewhere you control, and set their permissions to 600 once they are there — " +
                            "ssh refuses a private key the rest of the machine can read.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { Button(onClick = { confirming = false; saveFolder.launch(null) }) { Text("Choose a folder") } },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export as an SSH config") },
        text = {
            Column {
                Text(
                    "$written of your hosts as an OpenSSH config, ready to drop into ~/.ssh on another machine. " +
                        "Groups and accounts are resolved first, so each host is written with the values it really connects with.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(4.dp))
                Text(
                    "No password is written, and anything ssh has no word for — a tunnel, Mosh, wake-on-LAN — is named in a comment.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (keys.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Checkbox(withKeys, { withKeys = it })
                        Spacer(Modifier.width(4.dp))
                        Column {
                            Text("Write the private keys beside it", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${keys.size} of your keys can be copied; keystore and security-key identities cannot leave the device",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { if (withKeys) confirming = true else saveConfig.launch(CONFIG_NAME) }) {
                Text(if (withKeys) "Continue" else "Choose where")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Not `config`: a picker that lands in Downloads should not tempt anyone to overwrite a real one. */
private const val CONFIG_NAME = "ssh_config"

private fun write(context: Context, uri: Uri, text: String): Boolean = runCatching {
    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } != null
}.getOrDefault(false)

private fun create(context: Context, parent: Uri, name: String): Uri? =
    runCatching { DocumentsContract.createDocument(context.contentResolver, parent, "text/plain", name) }.getOrNull()
