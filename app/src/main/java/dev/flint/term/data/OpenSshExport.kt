package dev.flint.term.data

/**
 * The saved hosts written out as an OpenSSH `~/.ssh/config`.
 *
 * This is [OpenSshImport] run backwards, and deliberately so: what that parser
 * reads out of a config is what this puts back into one, so a host that came in
 * through it can leave the same way. An app you cannot leave is one people are
 * careful about adopting.
 *
 * Two rules shape everything below. Nothing secret is written — a password
 * never leaves, and a key is named only by the path it would have on the other
 * machine. And nothing is dropped in silence: where the app knows something
 * ssh_config has no word for — a WireGuard tunnel, Mosh, a knock sequence — the
 * export says so in a comment, because a config that quietly differs from what
 * you actually connect with is worse than no config at all.
 */
object OpenSshExport {
    /** A private key the export may write beside the config, under the name the config already points at. */
    data class KeyFile(val name: String, val privateKey: String, val publicKey: String)

    /** Where a config written here says a key lives, and where [keyFiles] expects it to be put. */
    fun identityPath(name: String): String = "~/.ssh/$name"

    /**
     * The whole config, ready to write.
     *
     * [keepaliveSeconds] is the app-wide setting; 0 leaves `ServerAliveInterval`
     * out rather than pinning the other machine to a number it never chose.
     */
    fun export(
        hosts: List<Host>,
        groups: List<HostGroup>,
        accounts: List<Account>,
        identities: List<Identity>,
        keepaliveSeconds: Int = 0,
    ): String {
        val plan = plan(hosts, groups, accounts, identities)
        return buildString {
            append(header(plan))
            for (entry in plan) {
                append('\n')
                append(block(entry, keepaliveSeconds))
            }
        }
    }

    /**
     * The keys the config points at, for the optional second half of the export.
     *
     * Only the ones this app can actually read: a keystore key and a key on a
     * FIDO2 token have no private half here to copy, and asking for them would
     * produce an empty file that looks like a key.
     */
    fun keyFiles(
        hosts: List<Host>,
        groups: List<HostGroup>,
        accounts: List<Account>,
        identities: List<Identity>,
    ): List<KeyFile> {
        val names = keyNames(identities)
        val referenced = plan(hosts, groups, accounts, identities).mapNotNull { it.identity?.id }.toSet()
        return identities
            .filter { it.id in referenced && !it.hardware && !it.securityKey && it.privateKey.isNotBlank() }
            .map { KeyFile(names.getValue(it.id), it.privateKey, it.publicKey) }
    }

    // ---- planning ----------------------------------------------------------

    /** One host as it will be written: resolved, named, and with everything the config cannot say collected. */
    private class Entry(
        val host: Host,
        /** null when there is no honest `Host` block to write — a telnet host, or one with no address. */
        val alias: String?,
        val identity: Identity?,
        /** The file name under `~/.ssh` to point `IdentityFile` at, or null when the key cannot be referenced. */
        val keyName: String?,
        val proxyJump: String?,
        val notes: List<String>,
    )

    private fun plan(
        hosts: List<Host>,
        groups: List<HostGroup>,
        accounts: List<Account>,
        identities: List<Identity>,
    ): List<Entry> {
        val byGroup = groups.associateBy { it.id }
        val byAccount = accounts.associateBy { it.id }
        val byIdentity = identities.associateBy { it.id }
        val keyNames = keyNames(identities)

        // A host must be written with the values it connects with, not the ones
        // it happens to store: a group's key dropped on the way out would be a
        // config that fails for a reason nobody can see.
        val resolved = LinkedHashMap<String, Host>()
        for (h in hosts) {
            val withGroup = h.withGroup(byGroup[h.groupId])
            resolved[h.id] = withGroup.withAccount(byAccount[withGroup.accountId])
        }

        val taken = mutableSetOf<String>()
        val aliases = LinkedHashMap<String, String>()
        for (h in hosts) {
            val host = resolved.getValue(h.id)
            if (!writable(host)) continue
            val base = slug(host.label).ifEmpty { slug(host.hostname) }.ifEmpty { "host" }
            aliases[h.id] = unique(base, taken)
        }

        return hosts.map { h ->
            val host = resolved.getValue(h.id)
            val alias = aliases[h.id]
            val notes = mutableListOf<String>()
            if (alias == null) {
                notes += when {
                    host.protocol == Protocol.TELNET -> "it speaks telnet, which ssh_config has no word for"
                    else -> "it has no address"
                }
                return@map Entry(host, null, null, null, null, notes)
            }

            val identity = host.identityId?.let { byIdentity[it] }?.takeIf { host.authType == AuthType.KEY }
            val keyName = identity?.takeIf { !it.hardware && !it.securityKey }?.let { keyNames.getValue(it.id) }
            if (identity == null && host.authType == AuthType.KEY) {
                notes += "it is set to sign with a key that is no longer saved, so ssh will have to find another way in"
            }
            if (identity != null && keyName == null) {
                notes += if (identity.securityKey) {
                    "it signs with ${identity.name.ifBlank { "a key" }} on a security key, which stays on the token"
                } else {
                    "it signs with ${identity.name.ifBlank { "a key" }} in this phone's keystore, which cannot be read out"
                }
            }

            var jump: String? = null
            when (val j = jumpFor(host, resolved, aliases)) {
                is Jump.Alias -> jump = j.alias
                is Jump.Unsayable -> notes += j.why
                Jump.None -> Unit
            }

            notes += unsayable(host)
            Entry(host, alias, identity, keyName, jump, notes)
        }
    }

    private fun writable(host: Host): Boolean = host.protocol == Protocol.SSH && host.hostname.isNotBlank()

    private sealed class Jump {
        object None : Jump()
        class Alias(val alias: String) : Jump()
        class Unsayable(val why: String) : Jump()
    }

    private fun jumpFor(host: Host, resolved: Map<String, Host>, aliases: Map<String, String>): Jump {
        val jumpId = host.jumpHostId ?: return Jump.None
        val jump = resolved[jumpId] ?: return Jump.Unsayable("it connects through a jump host that is no longer saved")
        val alias = aliases[jumpId]
            ?: return Jump.Unsayable("it connects through ${jump.displayName}, which is not in this file")
        // ssh follows the chain itself, so one alias is enough — but only if the
        // chain ends. A group whose jump host is a member of that same group is
        // one edit away from a loop, and ssh would follow it forever.
        val seen = mutableSetOf(host.id)
        var at: Host? = jump
        while (at != null) {
            if (!seen.add(at.id)) return Jump.Unsayable("its jump host chain loops back on itself")
            at = at.jumpHostId?.let { resolved[it] }
        }
        return Jump.Alias(alias)
    }

    /** What this host carries that no ssh_config line can express. */
    private fun unsayable(host: Host): List<String> = buildList {
        if (host.tunnelId != null) add("it is reached through a WireGuard tunnel; bring the tunnel up before connecting")
        if (host.tailscaleId != null) add("its address is on a tailnet, so this machine has to be on that tailnet too")
        if (host.mosh) add("this app runs the session over Mosh; `mosh ${host.hostname}` is the equivalent here")
        if (host.wol.enabled) add("it is woken with a wake-on-LAN packet first")
        if (host.knock.enabled) add("a port-knock sequence opens the firewall for it first")
        if (host.proxyId != null || host.proxy.enabled) add("it is dialed through a saved proxy; ssh needs a ProxyCommand for that")
        if (host.preConnectCommand.isNotBlank()) add("a command runs on the jump host before it is dialed")
        if (host.persistent) add("this app attaches to tmux on login and reconnects by itself")
        if (host.startupSnippetIds.isNotEmpty()) add("snippets are typed after login, and they are not in this file")
        if (host.addresses.isNotEmpty()) {
            val extra = host.addresses.mapNotNull { it.hostname.ifBlank { null } }
            if (extra.isNotEmpty()) add("it has further addresses tried in turn: ${extra.joinToString(", ")}")
        }
        if (host.forwards.any { !it.autoStart }) add("some of its forwards are started by hand, and are commented out below")
    }

    // ---- writing -----------------------------------------------------------

    private fun header(plan: List<Entry>): String {
        val needKey = plan.filter { it.keyName != null }.mapNotNull { it.alias }
        val needPassword = plan.filter { it.alias != null && it.host.authType == AuthType.PASSWORD }.mapNotNull { it.alias }
        val onDevice = plan.filter { it.alias != null && it.identity != null && it.keyName == null }.mapNotNull { it.alias }
        return buildString {
            appendLine("# An OpenSSH config for the hosts saved in flintTerm.")
            appendLine("#")
            appendLine("# No secret is in this file and none can be put in one. Passwords are never")
            appendLine("# written; a key is named only by the path it would have on this machine, so")
            appendLine("# put the key there yourself or export the keys alongside this config.")
            if (needKey.isNotEmpty() || needPassword.isNotEmpty() || onDevice.isNotEmpty()) appendLine("#")
            if (needKey.isNotEmpty()) appendLine("# Wants a key at the path named below: ${needKey.joinToString(", ")}")
            if (needPassword.isNotEmpty()) appendLine("# Will ask for a password: ${needPassword.joinToString(", ")}")
            if (onDevice.isNotEmpty()) {
                appendLine("# Signs with a key held in the phone's keystore or on a security key, which")
                appendLine("# cannot leave the device at all: ${onDevice.joinToString(", ")}")
            }
        }
    }

    private fun block(entry: Entry, keepaliveSeconds: Int): String = buildString {
        val host = entry.host
        val alias = entry.alias
        if (alias == null) {
            appendLine("# ${host.displayName} is not in this file: ${entry.notes.firstOrNull() ?: "it cannot be described here"}.")
            return@buildString
        }
        if (entry.notes.isNotEmpty()) {
            appendLine("# What ssh_config cannot say about $alias:")
            for (note in entry.notes) appendLine("#   - $note")
        }
        appendLine("Host $alias")
        appendLine("    HostName ${host.hostname}")
        if (host.port != 22) appendLine("    Port ${host.port}")
        if (host.username.isNotBlank()) appendLine("    User ${host.username}")
        entry.keyName?.let {
            appendLine("    IdentityFile ${identityPath(it)}")
            // Without this, ssh would still offer every other key in the agent
            // first, which is exactly what the host does not want.
            appendLine("    IdentitiesOnly yes")
        }
        entry.proxyJump?.let { appendLine("    ProxyJump $it") }
        if (host.forwardAgent) appendLine("    ForwardAgent yes")
        for (env in host.env.requestable()) appendLine("    SetEnv ${env.name}=${quoted(env.value)}")
        for (f in host.forwards) {
            val line = when (f.type) {
                ForwardType.LOCAL -> "LocalForward ${f.bindHost}:${f.bindPort} ${f.targetHost}:${f.targetPort}"
                ForwardType.REMOTE -> "RemoteForward ${f.bindHost}:${f.bindPort} ${f.targetHost}:${f.targetPort}"
                ForwardType.DYNAMIC -> "DynamicForward ${f.bindHost}:${f.bindPort}"
            }
            appendLine(if (f.autoStart) "    $line" else "    # $line")
        }
        val startup = host.startupCommand.trim()
        if (startup.isNotEmpty()) {
            // The app types this into a shell that stays; ssh runs it instead of
            // the shell, which is the closest thing ssh_config has to offer.
            appendLine("    RequestTTY yes")
            appendLine("    RemoteCommand ${startup.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("; ")}")
        }
        if (keepaliveSeconds > 0) appendLine("    ServerAliveInterval $keepaliveSeconds")
    }

    // ---- names -------------------------------------------------------------

    /** One usable file name per key, so the config and the written keys agree on it. */
    private fun keyNames(identities: List<Identity>): Map<String, String> {
        val taken = mutableSetOf<String>()
        return identities.associate { it.id to unique(slug(it.name).ifEmpty { "key" }, taken) }
    }

    /**
     * A label turned into something that can follow `Host`.
     *
     * A saved host is named for a person to read ("Home NAS"), and ssh reads the
     * first word of that as the whole alias and the rest as more aliases. So
     * anything that is not plainly a name becomes a hyphen, wildcards included:
     * a stray `*` would silently make the block match every host in the file.
     */
    private fun slug(raw: String): String = raw.trim().replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-')

    private fun unique(base: String, taken: MutableSet<String>): String {
        var name = base
        var n = 2
        while (!taken.add(name.lowercase())) name = "$base-${n++}"
        return name
    }

    /** ssh_config takes the rest of the line as the value, unless it needs to hold a space. */
    private fun quoted(value: String): String =
        if (value.isEmpty() || value.any { it.isWhitespace() }) "\"" + value.replace("\"", "\\\"") + "\"" else value
}
