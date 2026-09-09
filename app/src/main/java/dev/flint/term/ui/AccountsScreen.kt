package dev.flint.term.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.flint.term.App
import dev.flint.term.data.Account
import dev.flint.term.data.AuthType

/**
 * The logins hosts share.
 *
 * Kept apart from the hosts because a login outlives any one of them: the same
 * `deploy` user and the same key open every machine in a fleet, and when the
 * key is replaced there should be one thing to edit rather than forty.
 */
@Composable
fun AccountsScreen(nav: NavController) {
    val app = LocalContext.current.applicationContext as App
    val accounts by app.store.accounts.collectAsStateWithLifecycle()
    val hosts by app.store.hosts.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Account?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppHeader(title = "Accounts", onBack = { nav.popBackStack() }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = Account() },
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text("Add") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(20.dp),
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 110.dp)) {
            if (accounts.isEmpty()) {
                item {
                    EmptyState(
                        Icons.Rounded.Person,
                        "No accounts yet",
                        "An account is a username and a key or password that hosts can share. Add one, then pick it in a host instead of typing the login again.",
                        actionLabel = "Add an account",
                        onAction = { editing = Account() },
                    )
                }
            }
            items(accounts, key = { it.id }) { a ->
                val used = hosts.count { it.accountId == a.id }
                Group(modifier = Modifier.padding(top = 10.dp)) {
                    GroupRow(
                        title = a.label,
                        subtitle = accountSummary(a, app),
                        icon = if (a.authType == AuthType.KEY) Icons.Rounded.Key else Icons.Rounded.Password,
                        onClick = { editing = a },
                        trailing = {
                            Text(
                                when (used) {
                                    0 -> "unused"
                                    1 -> "1 host"
                                    else -> "$used hosts"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }
        }
    }

    editing?.let { draft ->
        AccountSheet(
            account = draft,
            onDismiss = { editing = null },
            onSave = { app.store.upsertAccount(it); editing = null },
            onDelete = { app.store.deleteAccount(draft.id); editing = null }.takeIf { accounts.any { it.id == draft.id } },
        )
    }
}

/** What an account does, in one line: the login and how it proves itself. */
@Composable
private fun accountSummary(a: Account, app: App): String {
    val identities by app.store.identities.collectAsStateWithLifecycle()
    val how = when (a.authType) {
        AuthType.KEY -> identities.firstOrNull { it.id == a.identityId }?.name?.ifBlank { "key" } ?: "no key chosen"
        AuthType.PASSWORD -> if (a.password.isEmpty()) "password asked for" else "password"
        AuthType.NONE -> "no authentication"
    }
    return listOf(a.username.ifBlank { "no username" }, how).joinToString("  ·  ")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSheet(
    account: Account,
    onDismiss: () -> Unit,
    onSave: (Account) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val app = LocalContext.current.applicationContext as App
    val identities by app.store.identities.collectAsStateWithLifecycle()
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by remember(account.id) { mutableStateOf(account) }
    var showPassword by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (onDelete == null) "New account" else "Edit account", style = MaterialTheme.typography.titleLarge)
            Field(draft.name, { draft = draft.copy(name = it) }, "Name", placeholder = "Deploy user")
            Field(
                draft.username, { draft = draft.copy(username = it) }, "Username", mono = true, placeholder = "root",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                autofill = ContentType.Username,
            )
            Segmented(listOf("Password", "SSH key", "None"), draft.authType.ordinal) {
                draft = draft.copy(authType = AuthType.entries[it])
            }
            if (draft.authType == AuthType.KEY) {
                if (identities.isEmpty()) {
                    Text(
                        "No keys yet — generate or import one on the SSH keys screen.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        identities.forEach { id ->
                            FilterChip(
                                selected = draft.identityId == id.id,
                                onClick = { draft = draft.copy(identityId = id.id) },
                                label = { Text(id.name.ifBlank { id.fingerprint.take(20) }) },
                                leadingIcon = { Icon(Icons.Rounded.Key, null) },
                            )
                        }
                    }
                }
            }
            if (draft.authType != AuthType.NONE) {
                Field(
                    draft.password, { draft = draft.copy(password = it) },
                    if (draft.authType == AuthType.KEY) "Password fallback (optional)" else "Password",
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
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Rounded.Delete, null)
                        Text(" Delete")
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(
                    onClick = { onSave(draft.copy(name = draft.name.trim(), username = draft.username.trim())) },
                    enabled = draft.username.isNotBlank() || draft.name.isNotBlank(),
                ) { Text("Save") }
            }
            // Deleting an account should not quietly log a host out, so what
            // happens instead is said before it is pressed.
            if (onDelete != null) {
                Text(
                    "Deleting puts this login back into each host that used it, so nothing stops working.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
