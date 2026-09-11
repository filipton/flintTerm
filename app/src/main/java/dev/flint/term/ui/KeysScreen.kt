package dev.flint.term.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.flint.term.App
import dev.flint.term.core.CertValidity
import dev.flint.term.core.CertificateInfo
import dev.flint.term.core.CoreException
import dev.flint.term.core.KeyAlgorithm
import dev.flint.term.core.generateKey
import dev.flint.term.core.inspectCertificate
import dev.flint.term.core.inspectKey
import dev.flint.term.data.HardwareKeys
import dev.flint.term.data.Identity
import dev.flint.term.security.KeyTransport
import dev.flint.term.security.SecurityKeyAlgorithm
import dev.flint.term.security.SecurityKeyIdentity
import dev.flint.term.security.SecurityKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeysScreen(nav: NavController) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val identities by app.store.identities.collectAsStateWithLifecycle()
    var showGenerate by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var showSecurityKey by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<Identity?>(null) }
    var installTarget by remember { mutableStateOf<Pair<dev.flint.term.data.Host, Identity>?>(null) }
    var pickHostFor by remember { mutableStateOf<Identity?>(null) }
    /** The key a certificate is being attached to, and the text so far. */
    var certFor by remember { mutableStateOf<Identity?>(null) }
    var certText by remember { mutableStateOf("") }
    val hosts by app.store.hosts.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    fun readFile(uri: android.net.Uri, into: (String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val text = runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() }.getOrNull()
            withContext(Dispatchers.Main) {
                if (text == null) Toast.makeText(context, "Could not read file", Toast.LENGTH_SHORT).show() else into(text)
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) readFile(uri) { importText = it }
    }
    val certPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) readFile(uri) { certText = it }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppHeader(
                title = "Keys",
                // A top-level screen now, so no back arrow: the bottom bar is
                // how you got here and how you leave.
                onBack = null,
                actions = {
                    CommandPaletteButton()
                    IconButton(onClick = { picker.launch(arrayOf("*/*")) }) { Icon(Icons.Rounded.FileOpen, "Import") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text("Add") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(20.dp),
            )
        },
    ) { padding ->
        LazyColumn(state = rememberScreenListState("keys"), modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 110.dp)) {
            // The other two halves of "what a host logs in with": who you are,
            // and which servers you have already agreed to trust.
            item {
                val accounts by app.store.accounts.collectAsStateWithLifecycle()
                val known by app.store.knownHosts.collectAsStateWithLifecycle()
                // Its own card, with air under it: without the gap it reads as
                // one long list where "Accounts" is a key.
                Group(modifier = Modifier.padding(top = 8.dp, bottom = 14.dp)) {
                    GroupRow(
                        title = "Accounts",
                        subtitle = when (accounts.size) {
                            0 -> "A username and key that hosts can share"
                            1 -> "1 login shared by hosts"
                            else -> "${accounts.size} logins shared by hosts"
                        },
                        icon = Icons.Rounded.Person,
                        onClick = { nav.navigate(Routes.ACCOUNTS) },
                    )
                    RowDivider()
                    GroupRow(
                        title = "Trusted host keys",
                        subtitle = if (known.isEmpty()) "None yet" else "${known.size} servers  ·  view, copy or forget",
                        icon = Icons.Rounded.Dns,
                        iconTint = MaterialTheme.colorScheme.tertiary,
                        onClick = { nav.navigate(Routes.KNOWN_HOSTS) },
                    )
                }
            }
            if (identities.isEmpty()) {
                item {
                    EmptyState(
                        Icons.Rounded.VpnKey, "No keys yet",
                        "Generate a key on this device, where the private half never leaves it. You can also import an OpenSSH or PEM key, or add a security key you plug in or tap.",
                        "Add a key",
                    ) { showAdd = true }
                }
            } else {
                item {
                    Group {
                        identities.forEachIndexed { i, id ->
                            GroupRow(
                                title = id.name,
                                subtitle = buildString {
                                    append(id.publicKey.substringBefore(' '))
                                    append("  ")
                                    append(id.fingerprint.removePrefix("SHA256:").take(16))
                                    append("…")
                                    // A second line, deliberately: what changes how
                                    // connecting feels — a key you cannot copy, one
                                    // that needs touching, one that asks, one with a
                                    // certificate — should not need a tap to discover.
                                    val traits = buildList {
                                        if (id.hardware) add(id.backing.ifBlank { "in the keystore" })
                                        if (id.securityKey) add("needs the key")
                                        if (id.certificate.isNotBlank()) add("certificate")
                                        if (id.requireAuth) add("asks each use")
                                    }
                                    if (traits.isNotEmpty()) append("\n").append(traits.joinToString("  ·  "))
                                },
                                subtitleMono = true,
                                icon = Icons.Rounded.Key,
                                iconTint = HostAccents[Math.floorMod(id.id.hashCode(), HostAccents.size)],
                                onClick = { detail = id },
                            )
                            if (i < identities.lastIndex) RowDivider()
                        }
                    }
                }
                item {
                    Text(
                        "Tap a key to see and share its public half. Add it to ~/.ssh/authorized_keys on the server.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 14.dp),
                    )
                }
            }
        }
    }

    detail?.let { opened ->
        // Read back from the store: attaching or removing a certificate happens
        // from inside this sheet, and the copy it was opened with knows nothing
        // about that.
        val id = identities.firstOrNull { it.id == opened.id } ?: opened
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { detail = null }, sheetState = state, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Icons.Rounded.Key, HostAccents[Math.floorMod(id.id.hashCode(), HostAccents.size)], size = 44, corner = 14)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(id.name, style = MaterialTheme.typography.titleLarge)
                        Text(id.publicKey.substringBefore(' '), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text("Fingerprint", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                SelectionContainer { Text(id.fingerprint, style = CodeStyle.copy(fontSize = 12.sp)) }
                Spacer(Modifier.height(14.dp))
                Text("Public key", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                SelectionContainer {
                    Text(
                        id.publicKey, style = CodeStyle.copy(fontSize = 12.sp), maxLines = 6,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                            .padding(12.dp)
                            .horizontalScroll(rememberScrollState()),
                    )
                }
                Spacer(Modifier.height(18.dp))
                CertificateBlock(
                    identity = id,
                    onAttach = { certText = id.certificate; certFor = id; detail = null },
                    onRemove = { app.store.upsertIdentity(id.copy(certificate = "")) },
                )
                if (id.securityKey) {
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Security, null, Modifier.width(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "The private half is on the security key, not on this phone. Every login asks you to " +
                                "${if (SecurityKeyIdentity.transportOf(id) == KeyTransport.NFC) "hold the key to the back of the phone" else "plug the key in"} " +
                                "and touch it. If you lose the key this identity is gone, so keep a second key authorized on your servers.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (id.hardware) {
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Fingerprint, null, Modifier.width(20.dp),
                            tint = if (id.requireAuth) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (id.requireAuth) {
                                "Every signature asks for your fingerprint, face or screen lock. The keystore decided that when the key was made, so it cannot be turned off. Make another key without it instead."
                            } else {
                                "Signs without asking. Only a key generated with “Ask before every use” can require it, and that cannot be added afterwards."
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("public key", id.publicKey))
                        Toast.makeText(context, "Public key copied", Toast.LENGTH_SHORT).show()
                    }, Modifier.weight(1f)) {
                        Icon(Icons.Rounded.ContentCopy, null, Modifier.width(18.dp)); Spacer(Modifier.width(8.dp)); Text("Copy")
                    }
                    Button(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, id.publicKey)
                        context.startActivity(Intent.createChooser(send, "Share public key"))
                    }, Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Share, null, Modifier.width(18.dp)); Spacer(Modifier.width(8.dp)); Text("Share")
                    }
                }
                Spacer(Modifier.height(10.dp))
                Button(onClick = { pickHostFor = id; detail = null }, Modifier.fillMaxWidth(), enabled = hosts.isNotEmpty()) {
                    Icon(Icons.Rounded.Dns, null, Modifier.width(18.dp)); Spacer(Modifier.width(8.dp)); Text("Install on a server…")
                }
                Spacer(Modifier.height(6.dp))
                // The one thing that is not "do something with this key" but
                // "stop using this key", which is why it sits on its own above
                // deleting: rotating is the safe version of what people reach
                // for delete to do.
                // Rotation makes a new key and deploys it; a security key's
                // replacement is another security key, which has to be enrolled
                // on the token itself before anything can be deployed.
                if (!id.securityKey) {
                    TextButton(onClick = { detail = null; nav.navigate(Routes.keyRotation(id.id)) }, Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Autorenew, null, Modifier.width(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Replace this key everywhere…")
                    }
                }
                Spacer(Modifier.height(2.dp))
                TextButton(onClick = { app.store.deleteIdentity(id.id); detail = null }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Delete, null, Modifier.width(18.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text("Delete key", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    pickHostFor?.let { id ->
        ActionSheet(
            onDismiss = { pickHostFor = null },
            title = "Install ${id.name} on",
            actions = hosts.map { h -> SheetAction(h.displayName, iconFor(h.icon), subtitle = h.target) { installTarget = h to id; pickHostFor = null } },
        )
    }
    installTarget?.let { (h, id) -> InstallKeyDialog(raw = h, preselected = id, onDismiss = { installTarget = null }) }

    if (showAdd) {
        ActionSheet(
            onDismiss = { showAdd = false },
            title = "Add a key",
            actions = listOf(
                SheetAction("Generate a key", Icons.Rounded.VpnKey, subtitle = "On this phone, or inside its keystore") {
                    showAdd = false
                    showGenerate = true
                },
                SheetAction("Add a security key", Icons.Rounded.Security, subtitle = "A FIDO2 key you plug in or tap") {
                    showAdd = false
                    showSecurityKey = true
                },
                SheetAction("Import a key…", Icons.Rounded.FileOpen, subtitle = "An OpenSSH or PEM private key file") {
                    showAdd = false
                    picker.launch(arrayOf("*/*"))
                },
            ),
        )
    }

    if (showSecurityKey) {
        SecurityKeySheet(onDismiss = { showSecurityKey = false }) { identity ->
            app.store.upsertIdentity(identity)
            showSecurityKey = false
        }
    }

    if (showGenerate) {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var name by remember { mutableStateOf("android-${android.os.Build.MODEL.lowercase().replace(' ', '-')}") }
        var alg by remember { mutableStateOf(0) }
        var passphrase by remember { mutableStateOf("") }
        var requireAuth by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        ModalBottomSheet(onDismissRequest = { showGenerate = false }, sheetState = state, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Generate key", style = MaterialTheme.typography.titleLarge)
                Field(name, { name = it }, "Name / comment")
                Segmented(listOf("Ed25519", "ECDSA", "RSA 4096", "In the chip"), alg) { alg = it }
                Text(
                    when (alg) {
                        0 -> "Modern, fast and small. Use this unless the server is very old."
                        1 -> "NIST P-256. Broad compatibility."
                        2 -> "Largest keys. Works with older servers."
                        // The honest trade: safest against a stolen phone, and
                        // impossible to move to another one.
                        else -> "Made inside this device's keystore and never readable by this app, a backup or a sync. It cannot leave this phone, so give each device its own key and authorise them all on the server. ECDSA P-256, the only kind the keystore makes."
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // A keystore key has no passphrase to protect: the hardware is
                // what protects it.
                if (alg != 3) {
                    Field(
                        passphrase, { passphrase = it }, "Passphrase (optional)",
                        visualTransformation = PasswordVisualTransformation(),
                        autofill = ContentType.NewPassword,
                    )
                } else {
                    // Offered here because here is the only place it can be:
                    // the keystore fixes this when it creates the key.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Ask before every use", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "A fingerprint, face or the screen lock for each signature, enforced by the keystore. It has to be decided now: the key cannot be changed afterwards.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        AppSwitch(requireAuth, { requireAuth = it })
                    }
                }
                Button(
                    enabled = name.isNotBlank() && !busy,
                    onClick = {
                        busy = true
                        scope.launch(Dispatchers.IO) {
                            try {
                                if (alg == 3) {
                                    val identity = Identity(name = name.trim(), hardware = true)
                                    val info = HardwareKeys.generate(identity.id, requireAuth)
                                    app.store.upsertIdentity(
                                        identity.copy(
                                            publicKey = HardwareKeys.openSshPublicKey(identity.id, identity.name.ifBlank { "flintterm" }),
                                            fingerprint = HardwareKeys.fingerprint(identity.id),
                                            backing = when (info.backing) {
                                                HardwareKeys.Backing.StrongBox -> "StrongBox"
                                                HardwareKeys.Backing.TrustedEnvironment -> "Secure hardware"
                                                HardwareKeys.Backing.Software -> "Keystore (software)"
                                            } + if (info.onlyWhileUnlocked) ", unlocked only" else "",
                                            // What the keystore granted, not what was asked for.
                                            requireAuth = info.userAuthRequired,
                                        ),
                                    )
                                    if (requireAuth && !info.userAuthRequired) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(
                                                context,
                                                "This device cannot tie a key to your fingerprint. Set up a screen lock first. The key was made without it.",
                                                Toast.LENGTH_LONG,
                                            ).show()
                                        }
                                    }
                                } else {
                                    val algo = when (alg) { 0 -> KeyAlgorithm.ED25519; 1 -> KeyAlgorithm.ECDSA_P256; else -> KeyAlgorithm.RSA4096 }
                                    val k = generateKey(algo, name.trim(), passphrase.ifEmpty { null })
                                    app.store.upsertIdentity(Identity(name = name.trim(), privateKey = k.privateKey, publicKey = k.publicKey, fingerprint = k.fingerprint, passphrase = passphrase))
                                }
                                withContext(Dispatchers.Main) { showGenerate = false }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) { busy = false; Toast.makeText(context, e.message ?: "could not generate the key", Toast.LENGTH_LONG).show() }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (busy) "Generating…" else "Generate") }
            }
        }
    }

    importText?.let { text ->
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var name by remember { mutableStateOf("imported key") }
        var passphrase by remember { mutableStateOf("") }
        val encrypted = text.contains("ENCRYPTED") || text.contains("bcrypt")
        ModalBottomSheet(onDismissRequest = { importText = null }, sheetState = state, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Import key", style = MaterialTheme.typography.titleLarge)
                Text(text.lineSequence().firstOrNull().orEmpty(), style = CodeStyle.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Field(name, { name = it }, "Name")
                Field(
                    passphrase, { passphrase = it }, if (encrypted) "Passphrase" else "Passphrase (if any)",
                    visualTransformation = PasswordVisualTransformation(),
                    autofill = ContentType.Password,
                )
                Button(enabled = name.isNotBlank(), onClick = {
                    scope.launch(Dispatchers.IO) {
                        try {
                            val k = inspectKey(text, passphrase.ifEmpty { null })
                            app.store.upsertIdentity(Identity(name = name.trim(), privateKey = text, publicKey = k.publicKey, fingerprint = k.fingerprint, passphrase = passphrase))
                            withContext(Dispatchers.Main) { importText = null }
                        } catch (e: CoreException) {
                            withContext(Dispatchers.Main) { Toast.makeText(context, "Invalid key: ${e.message}", Toast.LENGTH_LONG).show() }
                        }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Import") }
            }
        }
    }

    certFor?.let { id ->
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var parsed by remember { mutableStateOf<CertificateInfo?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        // Checked as it is typed or pasted, so a bad certificate is a message
        // under the field rather than a connection that fails next week.
        LaunchedEffect(certText) {
            val text = certText.trim()
            if (text.isEmpty()) {
                parsed = null
                error = null
                return@LaunchedEffect
            }
            withContext(Dispatchers.IO) {
                runCatching { inspectCertificate(text, id.publicKey.ifBlank { null }) }
                    .onSuccess { parsed = it; error = null }
                    .onFailure { parsed = null; error = it.message ?: "this is not a certificate" }
            }
        }
        ModalBottomSheet(onDismissRequest = { certFor = null }, sheetState = state, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Certificate for ${id.name}", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Paste the certificate your CA issued for this key. It is a single line ending in -cert-v01@openssh.com. Servers that trust the CA then accept this key without having seen it before, until the certificate expires.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Field(
                    certText, { certText = it }, "Certificate",
                    mono = true, singleLine = false, minLines = 3,
                    error = error ?: parsed?.let { if (it.host) "that is a host certificate, which identifies a server and not you" else null },
                )
                OutlinedButton(onClick = { certPicker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.FileOpen, null, Modifier.width(18.dp)); Spacer(Modifier.width(8.dp)); Text("Load from a file…")
                }
                parsed?.let { CertificateFacts(it) }
                Button(
                    enabled = parsed?.let { !it.host } == true,
                    onClick = { app.store.upsertIdentity(id.copy(certificate = certText.trim())); certFor = null },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Attach") }
            }
        }
    }
}

/**
 * What a key's certificate is, or an invitation to attach one.
 *
 * The certificate is read again here rather than trusted from when it was
 * stored: certificates expire on their own, so the only honest answer is the one
 * from a moment ago.
 */
@Composable
private fun CertificateBlock(identity: Identity, onAttach: () -> Unit, onRemove: () -> Unit) {
    var parsed by remember(identity.certificate) { mutableStateOf<CertificateInfo?>(null) }
    var error by remember(identity.certificate) { mutableStateOf<String?>(null) }
    LaunchedEffect(identity.certificate) {
        if (identity.certificate.isBlank()) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching { inspectCertificate(identity.certificate, identity.publicKey.ifBlank { null }) }
                .onSuccess { parsed = it; error = null }
                .onFailure { parsed = null; error = it.message ?: "this certificate no longer parses" }
        }
    }
    Text("Certificate", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
    if (identity.certificate.isBlank()) {
        Text(
            "None. If your servers trust an SSH certificate authority rather than a list of keys, attach the certificate it issued for this key.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onAttach, Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Verified, null, Modifier.width(18.dp)); Spacer(Modifier.width(8.dp)); Text("Attach a certificate…")
        }
    } else {
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        parsed?.let { CertificateFacts(it) }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onAttach, Modifier.weight(1f)) { Text("Replace…") }
            OutlinedButton(onClick = onRemove, Modifier.weight(1f)) { Text("Remove", color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** The parts of a certificate worth reading before relying on it. */
@Composable
private fun CertificateFacts(info: CertificateInfo) {
    val expired = info.validity != CertValidity.CURRENT
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(info.keyType, style = CodeStyle.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.tertiary)
        Text(
            "Logs in as ${certPrincipals(info)}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            certValidity(info),
            style = MaterialTheme.typography.bodySmall,
            color = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (info.keyId.isNotBlank()) {
            Text("Issued to ${info.keyId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SelectionContainer {
            Text("CA ${info.caFingerprint}", style = CodeStyle.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Whom the CA said this certificate may log in as. */
internal fun certPrincipals(info: CertificateInfo): String =
    if (info.principals.isEmpty()) "any account" else info.principals.joinToString(", ")

/** Its validity window, in the terms that matter: is it usable now, and until when. */
internal fun certValidity(info: CertificateInfo): String = when (info.validity) {
    CertValidity.EXPIRED -> "Expired ${stamp(info.validBefore)}"
    CertValidity.NOT_YET_VALID -> "Not valid until ${stamp(info.validAfter)}. Check this device's clock"
    // Certificates issued without an expiry carry a time no calendar can print,
    // and OpenSSH's own "forever" is up there too.
    CertValidity.CURRENT -> if (info.validBefore >= NO_EXPIRY) "No expiry" else "Valid until ${stamp(info.validBefore)}"
}

/** Year 10000, past which a date is a way of saying "never". */
private const val NO_EXPIRY = 253_402_300_800L

private fun stamp(unixSeconds: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(unixSeconds * 1000L))

/**
 * Adding a security key: pick how it is reached, what it should sign with, and
 * what to call it — then hold or plug it in and touch it.
 *
 * A transport this phone does not have is shown and disabled with the reason,
 * because "USB" greyed out with no explanation reads as a bug on a phone that
 * simply has no OTG.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SecurityKeySheet(onDismiss: () -> Unit, onEnrolled: (Identity) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val availability = remember { SecurityKeys.availability(context) }
    val transports = KeyTransport.entries
    var transport by remember { mutableStateOf(transports.firstOrNull { availability[it] == null } ?: KeyTransport.USB) }
    var algorithm by remember { mutableStateOf(SecurityKeyAlgorithm.Ed25519) }
    var name by remember { mutableStateOf("security key") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val blocked = availability[transport]
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = state, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Add a security key", style = MaterialTheme.typography.titleLarge)
            Text(
                "The private key is made on the token and stays there. Every login needs the key present and a touch on it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Segmented(transports.map { it.label }, transports.indexOf(transport)) { transport = transports[it] }
            Text(
                blocked ?: when (transport) {
                    KeyTransport.USB -> "Plug the key into this phone, with an adapter if it needs one."
                    KeyTransport.NFC -> "Hold the key against the back of the phone and keep it there."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (blocked != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val algorithms = SecurityKeyAlgorithm.entries
            Segmented(algorithms.map { it.label }, algorithms.indexOf(algorithm)) { algorithm = algorithms[it] }
            Text(
                if (algorithm == SecurityKeyAlgorithm.Ed25519) {
                    "Smaller and faster. Not every key supports it, and one that does not will make an ECDSA key instead and tell you."
                } else {
                    "NIST P-256. Every FIDO2 key can do this one."
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Field(name, { name = it }, "Name / comment")
            problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Button(
                enabled = name.isNotBlank() && !busy && blocked == null,
                onClick = {
                    busy = true
                    problem = null
                    scope.launch(Dispatchers.IO) {
                        // The waiting is on this thread; what to do about it is
                        // on screen already, drawn by SecurityKeyGate.
                        val result = runCatching { SecurityKeyIdentity.enroll(context, transport, algorithm, name.trim()) }
                        withContext(Dispatchers.Main) {
                            busy = false
                            result
                                .onSuccess { identity ->
                                    // A token with no Ed25519 quietly making an
                                    // ECDSA key would look like the choice being
                                    // ignored, so it is said out loud.
                                    SecurityKeyIdentity.substitution(algorithm, SecurityKeyIdentity.algorithmOf(identity))
                                        ?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                                    onEnrolled(identity)
                                }
                                .onFailure { problem = it.message ?: "the security key could not be used" }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "Waiting for the key…" else "Add security key") }
        }
    }
}
