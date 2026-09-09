package dev.flint.term.ui

import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.flint.term.App
import dev.flint.term.core.CoreException
import dev.flint.term.core.isVault
import dev.flint.term.core.openVault
import dev.flint.term.core.sealVault
import dev.flint.term.data.Snapshot
import dev.flint.term.data.Vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Back up to a file": choose a passphrase, choose where, done.
 *
 * The file is sealed before the picker opens, so the passphrase is out of
 * hand by the time anything is written — and so a cancelled picker costs
 * nothing but the second the sealing took.
 */
@Composable
fun BackupDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val scope = rememberCoroutineScope()
    var passphrase by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var withSecrets by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var sealed by remember { mutableStateOf<ByteArray?>(null) }
    val snapshot = remember { app.store.snapshot() }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = sealed
        if (uri == null || bytes == null) { onDismiss(); return@rememberLauncherForActivityResult }
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } != null }.getOrDefault(false)
            }
            Toast.makeText(
                context,
                if (ok) "Backed up ${Vault.describe(Vault.strip(snapshot, withSecrets))}" else "Could not write the backup",
                Toast.LENGTH_LONG,
            ).show()
            onDismiss()
        }
    }

    val mismatch = again.isNotEmpty() && again != passphrase
    val short = passphrase.isNotEmpty() && passphrase.length < 8
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Back up to a file") },
        text = {
            Column {
                Text(
                    "Hosts, keys, snippets, tunnels and settings, encrypted with a passphrase you choose. " +
                        "There is no way to open the file without it.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(6.dp))
                OutlinedTextField(
                    passphrase, { passphrase = it }, label = { Text("Passphrase") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = short, supportingText = if (short) ({ Text("At least 8 characters") }) else null,
                    modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.NewPassword },
                )
                OutlinedTextField(
                    again, { again = it }, label = { Text("Again") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = mismatch, supportingText = if (mismatch) ({ Text("The two do not match") }) else null,
                    modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.NewPassword },
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Checkbox(withSecrets, { withSecrets = it })
                    Spacer(Modifier.width(4.dp))
                    Column {
                        Text("Include passwords, private keys and tunnel configs", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Off leaves a file safe to hand to someone else; keys in the phone's keystore never leave it either way",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = !busy && passphrase.length >= 8 && again == passphrase, onClick = {
                busy = true
                scope.launch {
                    val plain = Vault.plain(snapshot, withSecrets, Build.MODEL)
                    sealed = withContext(Dispatchers.IO) { sealVault(passphrase, plain) }
                    passphrase = ""; again = ""
                    save.launch("flintterm-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.${Vault.EXTENSION}")
                }
            }) { Text(if (busy) "Encrypting…" else "Choose where") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * "Restore from a file": pick it, give the passphrase, see what it holds,
 * merge it in. Nothing already here is deleted; an entry on both sides
 * takes the file's version.
 */
@Composable
fun RestoreFlow(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<ByteArray?>(null) }
    var passphrase by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var opened by remember { mutableStateOf<Pair<Vault.Meta, Snapshot>?>(null) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) { onDismiss(); return@rememberLauncherForActivityResult }
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            when {
                bytes == null -> { Toast.makeText(context, "Could not read that file", Toast.LENGTH_SHORT).show(); onDismiss() }
                !isVault(bytes) -> { Toast.makeText(context, "That is not a backup made by this app", Toast.LENGTH_LONG).show(); onDismiss() }
                else -> file = bytes
            }
        }
    }
    // Once, after the launcher is registered: launching during composition
    // itself throws, because the registration happens in a side effect too.
    LaunchedEffect(Unit) { pick.launch(arrayOf("*/*")) }

    val bytes = file
    val result = opened
    if (bytes != null && result == null) {
        AlertDialog(
            onDismissRequest = { if (!busy) onDismiss() },
            title = { Text("Open the backup") },
            text = {
                Column {
                    OutlinedTextField(
                        passphrase, { passphrase = it; error = null }, label = { Text("Passphrase") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = error != null, supportingText = error?.let { { Text(it) } },
                        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
                    )
                }
            },
            confirmButton = {
                Button(enabled = !busy && passphrase.isNotEmpty(), onClick = {
                    busy = true
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) {
                            runCatching { Vault.parse(openVault(passphrase, bytes)) }
                        }
                        busy = false
                        outcome.onSuccess { opened = it }.onFailure { e ->
                            error = when (e) {
                                is CoreException.WrongPassphrase -> "Wrong passphrase"
                                is Vault.NotABackup -> e.message
                                else -> e.message ?: "Could not open the backup"
                            }
                        }
                    }
                }) { Text(if (busy) "Opening…" else "Open") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
        )
    }

    if (result != null) {
        val (meta, snap) = result
        val made = buildString {
            if (meta.device.isNotBlank()) append("Made on ${meta.device}")
            if (meta.exportedAt > 0) {
                append(if (isEmpty()) "Made " else " ")
                append(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(meta.exportedAt)))
            }
            if (!meta.withSecrets) append(if (isEmpty()) "Without" else ", without").append(" passwords or private keys")
            if (isNotEmpty()) append(".")
        }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Restore ${Vault.describe(snap)}?") },
            text = {
                Column {
                    if (made.isNotEmpty()) {
                        Text(made, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.padding(4.dp))
                    }
                    Text(
                        "Everything in the file is added. Where this phone already has the same entry, the file's version replaces it. Nothing is deleted.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    app.store.restore(Vault.merge(app.store.snapshot(), snap, meta.withSecrets))
                    Toast.makeText(context, "Restored ${Vault.describe(snap)}", Toast.LENGTH_LONG).show()
                    onDismiss()
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    }
}
