package dev.flint.term.ui

import dev.flint.term.R
import androidx.compose.ui.res.stringResource
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
        topBar = { AppHeader(title = stringResource(R.string.accountsscreen_accounts), onBack = { nav.popBackStack() }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = Account() },
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text(stringResource(R.string.accountsscreen_add)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(20.dp),
            )
        },
    ) { padding ->
        LazyColumn(state = rememberScreenListState("accounts"), modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 110.dp)) {
            if (accounts.isEmpty()) {
                item {
                    EmptyState(
                        Icons.Rounded.Person,
                        stringResource(R.string.accountsscreen_no_accounts_yet),
                        stringResource(R.string.accountsscreen_an_account_is_a_username_and_a_key_or_password_t),
                        actionLabel = stringResource(R.string.accountsscreen_add_an_account),
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
                                    1 -> stringResource(R.string.accountsscreen_1_host)
                                    else -> stringResource(R.string.accountsscreen_hosts, used)
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
        AuthType.KEY -> identities.firstOrNull { it.id == a.identityId }?.name?.ifBlank { "key" } ?: stringResource(R.string.accountsscreen_no_key_chosen)
        AuthType.PASSWORD -> if (a.password.isEmpty()) stringResource(R.string.accountsscreen_password_asked_for) else "password"
        AuthType.NONE -> stringResource(R.string.accountsscreen_no_authentication)
    }
    return listOf(a.username.ifBlank { stringResource(R.string.accountsscreen_no_username) }, how).joinToString("  ·  ")
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
            Text(if (onDelete == null) stringResource(R.string.accountsscreen_new_account) else stringResource(R.string.accountsscreen_edit_account), style = MaterialTheme.typography.titleLarge)
            Field(draft.name, { draft = draft.copy(name = it) }, "Name", placeholder = stringResource(R.string.accountsscreen_deploy_user))
            Field(
                draft.username, { draft = draft.copy(username = it) }, "Username", mono = true, placeholder = stringResource(R.string.accountsscreen_root),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                autofill = ContentType.Username,
            )
            Segmented(listOf("Password", stringResource(R.string.accountsscreen_ssh_key), "None"), draft.authType.ordinal) {
                draft = draft.copy(authType = AuthType.entries[it])
            }
            if (draft.authType == AuthType.KEY) {
                if (identities.isEmpty()) {
                    Text(
                        stringResource(R.string.accountsscreen_no_keys_yet_generate_or_import_one_on_the_ssh_ke),
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
                    if (draft.authType == AuthType.KEY) stringResource(R.string.accountsscreen_password_fallback_optional) else "Password",
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    autofill = ContentType.Password,
                    trailing = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, stringResource(R.string.accountsscreen_toggle_visibility))
                        }
                    },
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Rounded.Delete, null)
                        Text(stringResource(R.string.accountsscreen_delete))
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.accountsscreen_cancel)) }
                Button(
                    onClick = { onSave(draft.copy(name = draft.name.trim(), username = draft.username.trim())) },
                    enabled = draft.username.isNotBlank() || draft.name.isNotBlank(),
                ) { Text(stringResource(R.string.accountsscreen_save)) }
            }
            // Deleting an account should not quietly log a host out, so what
            // happens instead is said before it is pressed.
            if (onDelete != null) {
                Text(
                    stringResource(R.string.accountsscreen_deleting_puts_this_login_back_into_each_host_tha),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
