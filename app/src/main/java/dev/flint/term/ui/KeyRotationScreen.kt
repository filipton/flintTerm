package dev.flint.term.ui

import dev.flint.term.R
import androidx.compose.ui.res.stringResource
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.flint.term.App
import dev.flint.term.core.KeyAlgorithm
import dev.flint.term.core.generateKey
import dev.flint.term.data.HardwareKeys
import dev.flint.term.data.Identity
import dev.flint.term.session.KeyRotation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Replacing one key everywhere it is used.
 *
 * The plan is shown before anything happens, because the interesting part is
 * the list: people are usually surprised by how many machines a key opens. Then
 * each host reports for itself, and the summary is honest about what is still
 * on the old key — a half-finished rotation is a normal outcome, not an error
 * state, and the old key stays valid on everything that did not complete.
 */
@Composable
fun KeyRotationScreen(nav: NavController, identityId: String) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val identities by app.store.identities.collectAsStateWithLifecycle()
    val hosts by app.store.hosts.collectAsStateWithLifecycle()
    val accounts by app.store.accounts.collectAsStateWithLifecycle()
    val groups by app.store.groups.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val old = identities.firstOrNull { it.id == identityId }
    if (old == null) {
        androidx.compose.runtime.LaunchedEffect(Unit) { nav.popBackStack() }
        return
    }

    // Worked out once and held: the rotation switches hosts over as it goes, so
    // reading this again mid-run would watch its own targets disappear.
    val users = remember(identityId) { KeyRotation.users(identityId, hosts, accounts, groups) }
    var name by remember { mutableStateOf("${old.name}-new") }
    var removeOld by remember { mutableStateOf(true) }
    var running by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf<Identity?>(null) }
    val progress = remember { mutableStateMapOf<String, KeyRotation.Progress>() }

    fun start() {
        running = true
        scope.launch {
            val fresh = runCatching { replacement(old, name.trim()) }
                .onFailure {
                    running = false
                    Toast.makeText(context, it.message ?: "could not make the new key", Toast.LENGTH_LONG).show()
                }
                .getOrNull() ?: return@launch
            app.store.upsertIdentity(fresh)
            KeyRotation.rotate(app.sessions, app.store, old, fresh, users.map { it.host }, removeOld) { p ->
                scope.launch(Dispatchers.Main) { progress[p.hostId] = p }
            }
            running = false
            finished = fresh
        }
    }

    val done = finished != null
    val stuck = users.filter { progress[it.host.id]?.stage == KeyRotation.Stage.FAILED }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppHeader(
                title = stringResource(R.string.keyrotationscreen_replace_key),
                subtitle = old.name,
                onBack = if (running) null else ({ nav.popBackStack() }),
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 40.dp)) {
            item {
                Group(modifier = Modifier.padding(top = 8.dp)) {
                    GroupRow(
                        title = old.name,
                        subtitle = old.fingerprint,
                        subtitleMono = true,
                        icon = Icons.Rounded.Key,
                        iconTint = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            if (users.isEmpty()) {
                item {
                    EmptyState(
                        Icons.Rounded.Key,
                        stringResource(R.string.keyrotationscreen_nothing_uses_this_key),
                        stringResource(R.string.keyrotationscreen_no_saved_host_logs_in_with_it_so_there_is_nothin),
                    )
                }
                return@LazyColumn
            }
            item { GroupLabel(if (users.size == 1) stringResource(R.string.keyrotationscreen_one_host_uses_it) else stringResource(R.string.keyrotationscreen_hosts_use_it, users.size)) }
            item {
                Group {
                    users.forEachIndexed { i, user ->
                        val state = progress[user.host.id]
                        GroupRow(
                            title = user.host.displayName,
                            subtitle = state?.let { describe(it) } ?: user.reason,
                            icon = iconFor(user.host.icon),
                            iconTint = MaterialTheme.colorScheme.secondary,
                            trailing = { StageMark(state?.stage) },
                        )
                        if (i < users.lastIndex) RowDivider()
                    }
                }
            }
            if (!running && !done) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Field(name, { name = it }, stringResource(R.string.keyrotationscreen_name_for_the_new_key))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.keyrotationscreen_remove_the_old_key_afterwards), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    stringResource(R.string.keyrotationscreen_only_from_hosts_where_the_new_key_has_already_lo),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            AppSwitch(removeOld, { removeOld = it })
                        }
                        Text(
                            stringResource(R.string.keyrotationscreen_each_host_gets_the_new_key_is_asked_to_log_in_wi) +
                                stringResource(R.string.keyrotationscreen_a_host_that_cannot_be_reached_keeps_the_old_key),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = { start() }, Modifier.fillMaxWidth(), enabled = name.isNotBlank()) {
                            Text(if (users.size == 1) stringResource(R.string.keyrotationscreen_replace_on_1_host) else stringResource(R.string.keyrotationscreen_replace_on_hosts, users.size))
                        }
                    }
                }
            }
            if (done) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            if (stuck.isEmpty()) {
                                stringResource(R.string.keyrotationscreen_every_host_now_logs_in_with, name.trim())
                            } else {
                                stringResource(R.string.keyrotationscreen_of_hosts_moved_over_still_on_the_old_key, users.size - stuck.size, users.size) +
                                    stuck.joinToString(", ") { it.host.displayName } + stringResource(R.string.keyrotationscreen_they_are_unchanged_so_nothing_has_been_lost)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (stuck.isEmpty()) {
                            TextButton(onClick = {
                                app.store.deleteIdentity(old.id)
                                nav.popBackStack()
                            }, Modifier.fillMaxWidth()) {
                                Icon(Icons.Rounded.Delete, null, Modifier.width(18.dp), tint = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.keyrotationscreen_delete_the_old_key), color = MaterialTheme.colorScheme.error)
                            }
                        }
                        Button(onClick = { nav.popBackStack() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.keyrotationscreen_done)) }
                    }
                }
            }
        }
    }
}

/** One line about where a host has got to, in the words of whatever answered. */
private fun describe(p: KeyRotation.Progress): String = when (p.stage) {
    KeyRotation.Stage.WAITING -> "Waiting"
    KeyRotation.Stage.INSTALLING -> "Installing the new key…"
    KeyRotation.Stage.VERIFYING -> "Logging in with the new key…"
    KeyRotation.Stage.SWITCHED -> "New key works"
    KeyRotation.Stage.REMOVING -> "Removing the old key…"
    KeyRotation.Stage.DONE -> p.detail.ifBlank { "Done" }
    KeyRotation.Stage.FAILED -> p.detail.ifBlank { "Failed" }
}

@Composable
private fun StageMark(stage: KeyRotation.Stage?) {
    when (stage) {
        null, KeyRotation.Stage.WAITING -> Unit
        KeyRotation.Stage.DONE -> Icon(Icons.Rounded.CheckCircle, "Done", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        KeyRotation.Stage.FAILED -> Icon(Icons.Rounded.Error, "Failed", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
        else -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
    }
}

/**
 * A key of the same kind as [old].
 *
 * Same kind on purpose: a key living in the keystore is replaced by another one
 * there, and an Ed25519 key by an Ed25519 key. Rotating is not the moment to
 * change what a server has to accept.
 */
private suspend fun replacement(old: Identity, name: String): Identity = withContext(Dispatchers.IO) {
    if (old.hardware) {
        val identity = Identity(name = name, hardware = true)
        val info = HardwareKeys.generate(identity.id, old.requireAuth)
        identity.copy(
            publicKey = HardwareKeys.openSshPublicKey(identity.id, name.ifBlank { "flintterm" }),
            fingerprint = HardwareKeys.fingerprint(identity.id),
            backing = when (info.backing) {
                HardwareKeys.Backing.StrongBox -> "StrongBox"
                HardwareKeys.Backing.TrustedEnvironment -> "Secure hardware"
                HardwareKeys.Backing.Software -> "Keystore (software)"
            },
            requireAuth = info.userAuthRequired,
        )
    } else {
        val algorithm = when (old.publicKey.substringBefore(' ')) {
            "ssh-ed25519" -> KeyAlgorithm.ED25519
            "ecdsa-sha2-nistp256" -> KeyAlgorithm.ECDSA_P256
            else -> KeyAlgorithm.RSA4096
        }
        val k = generateKey(algorithm, name, old.passphrase.ifEmpty { null })
        Identity(name = name, privateKey = k.privateKey, publicKey = k.publicKey, fingerprint = k.fingerprint, passphrase = old.passphrase)
    }
}
