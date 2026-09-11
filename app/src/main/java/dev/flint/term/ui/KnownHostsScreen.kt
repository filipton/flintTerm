package dev.flint.term.ui

import dev.flint.term.R
import androidx.compose.ui.res.stringResource
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.flint.term.App
import dev.flint.term.data.KnownHost

/** Every server key that was trusted, searchable, with forget / copy / forget-all. */
@Composable
fun KnownHostsScreen(nav: NavController) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val known by app.store.knownHosts.collectAsStateWithLifecycle()
    val hosts by app.store.hosts.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<KnownHost?>(null) }
    var confirmAll by remember { mutableStateOf(false) }

    val shown = known
        .filter { query.isBlank() || it.host.contains(query, true) || it.fingerprint.contains(query, true) || it.keyType.contains(query, true) }
        .sortedWith(compareBy({ it.isHashed }, { it.host }, { it.port })) // the nameless ones last

    // A hashed entry has no name to match a saved host against, and inventing one would be a lie.
    fun nameFor(k: KnownHost): String? =
        if (k.isHashed) null else hosts.firstOrNull { it.hostname.equals(k.host, true) && it.port == k.port }?.displayName

    fun titleFor(k: KnownHost): String = if (k.isHashed) KnownHost.HASHED_LABEL else "${k.host}:${k.port}"

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppHeader(
                title = stringResource(R.string.knownhostsscreen_trusted_host_keys),
                subtitle = stringResource(R.string.knownhostsscreen_server, known.size, if (known.size == 1) "" else "s"),
                onBack = { nav.popBackStack() },
                actions = { if (known.isNotEmpty()) IconButton(onClick = { confirmAll = true }) { Icon(Icons.Rounded.Delete, stringResource(R.string.knownhostsscreen_forget_all)) } },
            )
        },
    ) { padding ->
        LazyColumn(state = rememberScreenListState("knownhosts"), modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 40.dp)) {
            if (known.isEmpty()) {
                item { EmptyState(Icons.Rounded.Security, stringResource(R.string.knownhostsscreen_nothing_trusted_yet), stringResource(R.string.knownhostsscreen_server_keys_are_stored_here_after_you_accept_the)) }
            } else {
                if (known.size > 6) {
                    item { Field(query, { query = it }, stringResource(R.string.knownhostsscreen_search_host_or_fingerprint), Modifier.padding(horizontal = 16.dp, vertical = 6.dp), leading = { Icon(Icons.Rounded.Search, null) }) }
                }
                item {
                    Group {
                        shown.forEachIndexed { i, k ->
                            val label = nameFor(k)
                            GroupRow(
                                title = if (label != null) "$label  ·  ${titleFor(k)}" else titleFor(k),
                                subtitle = "${k.keyType}  ${k.fingerprint}",
                                subtitleMono = true,
                                icon = Icons.Rounded.Security,
                                iconTint = if (label != null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                                onClick = { detail = k },
                            )
                            if (i < shown.lastIndex) RowDivider()
                        }
                    }
                }
                item {
                    Text(
                        stringResource(R.string.knownhostsscreen_forgetting_a_key_means_the_next_connection_asks),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 14.dp),
                    )
                }
            }
        }
    }

    detail?.let { k ->
        ActionSheet(
            onDismiss = { detail = null },
            title = titleFor(k),
            subtitle = "${k.keyType}  ${k.fingerprint}",
            actions = listOf(
                SheetAction(stringResource(R.string.knownhostsscreen_copy_fingerprint), Icons.Rounded.ContentCopy) {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("fingerprint", k.fingerprint))
                    Toast.makeText(context, context.getString(R.string.knownhostsscreen_fingerprint_copied), Toast.LENGTH_SHORT).show(); detail = null
                },
                SheetAction(stringResource(R.string.knownhostsscreen_copy_known_hosts_line), Icons.Rounded.ContentCopy, subtitle = stringResource(R.string.knownhostsscreen_paste_into_ssh_known_hosts_elsewhere)) {
                    // A hashed entry copies out as its hash, which is the host field OpenSSH itself wrote.
                    val hostPart = when {
                        k.isHashed -> k.identity
                        k.port == 22 -> k.host
                        else -> "[${k.host}]:${k.port}"
                    }
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("known_hosts", "$hostPart ${k.keyType} ${k.keyBase64}"))
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show(); detail = null
                },
                SheetAction(stringResource(R.string.knownhostsscreen_forget_this_key), Icons.Rounded.Delete, danger = true) { app.store.forgetHostKey(k); detail = null },
            ),
        )
    }
    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text(stringResource(R.string.knownhostsscreen_forget_all_host_keys)) },
            text = { Text(stringResource(R.string.knownhostsscreen_every_server_will_ask_you_to_verify_its_fingerpr)) },
            confirmButton = { Button(onClick = { known.forEach { app.store.forgetHostKey(it) }; confirmAll = false }) { Text(stringResource(R.string.knownhostsscreen_forget_all)) } },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text(stringResource(R.string.knownhostsscreen_cancel)) } },
        )
    }
}
