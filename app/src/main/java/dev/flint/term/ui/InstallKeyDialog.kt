package dev.flint.term.ui

import dev.flint.term.R
import androidx.compose.ui.res.stringResource
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.flint.term.App
import dev.flint.term.data.AuthType
import dev.flint.term.data.Host
import dev.flint.term.data.Identity
import dev.flint.term.session.KeyDeploy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Install key on server": pick a key (unless given), log in with a password or the
 * host's current credentials, append the public key, then offer to switch the host to it.
 */
@Composable
fun InstallKeyDialog(raw: Host, preselected: Identity? = null, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    // What this host logs in as, which may come from a shared account rather
    // than from the host itself.
    val host = remember(raw) { app.store.effective(raw) }
    val identities by app.store.identities.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var identity by remember { mutableStateOf(preselected ?: identities.firstOrNull { it.id == host.identityId } ?: identities.firstOrNull()) }
    var pickKey by remember { mutableStateOf(false) }
    var usePassword by remember { mutableStateOf(host.authType != AuthType.KEY) }
    var password by remember { mutableStateOf(host.password) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<String?>(null) }

    val canRun = identity != null && !busy && (!usePassword || password.isNotEmpty())

    if (done != null) {
        val alreadyUsesKey = host.authType == AuthType.KEY && host.identityId == identity?.id
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.installkeydialog_key_installed)) },
            text = {
                Text(
                    if (alreadyUsesKey) stringResource(R.string.installkeydialog_is_now_on, identity?.name.orEmpty(), host.displayName, done.orEmpty())
                    else stringResource(R.string.installkeydialog_is_now_on_use_it_for_this_host_from_now_on_inste, identity?.name.orEmpty(), host.displayName, done.orEmpty()),
                )
            },
            confirmButton = {
                if (alreadyUsesKey) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.installkeydialog_done)) }
                } else {
                    Button(onClick = {
                        app.store.upsertHost(raw.copy(authType = AuthType.KEY, identityId = identity?.id, accountId = null))
                        onDismiss()
                    }) { Text(stringResource(R.string.installkeydialog_use_the_key)) }
                }
            },
            dismissButton = { if (!alreadyUsesKey) TextButton(onClick = onDismiss) { Text(stringResource(R.string.installkeydialog_keep_password)) } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.installkeydialog_install_key_on, host.displayName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.installkeydialog_adds_the_public_key_to_ssh_authorized_keys_for_o, host.username.ifBlank { "the user" }, host.hostname),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                GroupRow(
                    title = identity?.name ?: stringResource(R.string.installkeydialog_choose_a_key),
                    subtitle = identity?.fingerprint ?: stringResource(R.string.installkeydialog_no_keys_yet_generate_one_in_keys),
                    subtitleMono = identity != null,
                    icon = Icons.Rounded.Key,
                    onClick = { if (identities.size > 1) pickKey = true },
                )
                Segmented(listOf("Password", stringResource(R.string.installkeydialog_current_login)), if (usePassword) 0 else 1) { usePassword = it == 0 }
                if (usePassword) {
                    Field(
                        password, { password = it }, stringResource(R.string.installkeydialog_password_for, host.username.ifBlank { "user" }),
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        autofill = ContentType.Password,
                    )
                } else {
                    Text(
                        stringResource(R.string.installkeydialog_connects_the_way_this_host_is_set_up_now_useful, host.authType.name.lowercase()),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.width(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.installkeydialog_connecting_and_installing), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = canRun, onClick = {
                val id = identity ?: return@Button
                busy = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) { KeyDeploy.install(app.sessions, host, id, if (usePassword) password else null) }
                    busy = false
                    r.onSuccess { done = it }.onFailure { Toast.makeText(context, context.getString(R.string.installkeydialog_install_failed, it.message), Toast.LENGTH_LONG).show() }
                }
            }) { Text(stringResource(R.string.installkeydialog_install)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.installkeydialog_cancel)) } },
    )

    if (pickKey) {
        ActionSheet(
            onDismiss = { pickKey = false },
            title = stringResource(R.string.installkeydialog_which_key),
            actions = identities.map { i -> SheetAction(i.name, Icons.Rounded.Key, subtitle = i.fingerprint) { identity = i; pickKey = false } },
        )
    }
}

@Suppress("unused")
private val keepPadding = Modifier.padding(0.dp)
