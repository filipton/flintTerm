package dev.flint.term.data

import java.security.MessageDigest

/** Parsers for OpenSSH's `~/.ssh/config` and `known_hosts`, plus `ssh://` links. */
object OpenSshImport {
    /** One `Host` block that names a concrete machine (wildcard aliases are skipped). */
    data class ConfigEntry(
        val alias: String,
        val hostName: String,
        val user: String,
        val port: Int,
        val identityFile: String?,
        val proxyJump: String?,
    )

    fun parseConfig(text: String): List<ConfigEntry> {
        val out = mutableListOf<ConfigEntry>()
        var aliases: List<String> = emptyList()
        val opts = mutableMapOf<String, String>()
        fun flush() {
            for (a in aliases) {
                if (a.any { it == '*' || it == '?' || it == '!' }) continue
                out += ConfigEntry(
                    alias = a,
                    hostName = opts["hostname"] ?: a,
                    user = opts["user"] ?: "",
                    port = opts["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 22,
                    identityFile = opts["identityfile"],
                    proxyJump = opts["proxyjump"]?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() && it != "none" },
                )
            }
            aliases = emptyList(); opts.clear()
        }
        for (raw in text.lines()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val m = Regex("""^(\S+)\s*[=\s]\s*(.+)$""").find(line) ?: continue
            val key = m.groupValues[1].lowercase()
            val value = m.groupValues[2].trim().trim('"')
            when (key) {
                "host" -> { flush(); aliases = value.split(Regex("\\s+")).filter { it.isNotBlank() } }
                "match" -> { flush(); aliases = emptyList() }
                else -> if (aliases.isNotEmpty() && key !in opts) opts[key] = value // first value wins, like ssh
            }
        }
        flush()
        return out
    }

    /**
     * Plain and hashed entries; `@cert-authority` / `@revoked` markers are skipped.
     *
     * A hashed field is taken over as it stands, salt and all. Debian and Ubuntu
     * ship `HashKnownHosts yes`, so for many people it is the whole file — and
     * while the names in it are gone for good, an entry can still be recognized
     * later by testing a name against it.
     */
    fun parseKnownHosts(text: String): List<KnownHost> {
        val out = mutableListOf<KnownHost>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("@")) continue
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 3) continue
            val (hostsField, keyType, keyB64) = parts
            if (!keyType.startsWith("ssh-") && !keyType.startsWith("ecdsa-")) continue
            val fp = fingerprint(keyB64) ?: continue
            for (h in hostsField.split(',')) {
                if (h.startsWith("|")) {
                    // An unreadable hash is dropped rather than kept as something that can never match.
                    val (salt, hashed) = KnownHostMatch.parseField(h) ?: continue
                    out += KnownHost("", 0, keyType, keyB64, fp, hashSalt = salt, hashedHost = hashed)
                    continue
                }
                if (h.contains('*') || h.contains('?')) continue
                val (host, port) = if (h.startsWith("[")) {
                    val close = h.indexOf(']')
                    if (close < 0) continue
                    h.substring(1, close) to (h.substring(close + 1).removePrefix(":").toIntOrNull() ?: 22)
                } else h to 22
                if (host.isBlank()) continue
                out += KnownHost(host, port, keyType, keyB64, fp)
            }
        }
        return out
    }

    /** `SHA256:` + unpadded base64 of the key blob, as ssh-keygen -l prints it. */
    fun fingerprint(keyBase64: String): String? = runCatching {
        val blob = java.util.Base64.getDecoder().decode(keyBase64)
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        "SHA256:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(digest)
    }.getOrNull()

    /** What importing the given entries would create, given what is already there. */
    data class Plan(val entry: ConfigEntry, val existing: Host?, val identity: Identity?, val jumpAlias: String?)

    fun plan(entries: List<ConfigEntry>, hosts: List<Host>, identities: List<Identity>): List<Plan> = entries.map { e ->
        val existing = hosts.firstOrNull { it.hostname.equals(e.hostName, true) && it.port == e.port && (e.user.isBlank() || it.username == e.user) }
        val keyName = e.identityFile?.substringAfterLast('/')?.removeSuffix(".pub")
        val identity = keyName?.let { n -> identities.firstOrNull { it.name.equals(n, true) || it.name.equals(n.removePrefix("id_"), true) } }
        Plan(e, existing, identity, e.proxyJump?.substringAfterLast('@')?.substringBefore(':'))
    }

    /** Creates hosts for the chosen plans, linking ProxyJump aliases to the hosts made (or already present) for them. */
    fun apply(store: Store, chosen: List<Plan>, group: String) {
        // Imported hosts land in one group, made on the spot if it is new.
        val groupId = store.groupIdFor(group)
        val made = mutableMapOf<String, Host>()
        for (p in chosen) {
            val h = Host(
                label = p.entry.alias,
                hostname = p.entry.hostName,
                port = p.entry.port,
                username = p.entry.user,
                authType = if (p.identity != null) AuthType.KEY else AuthType.PASSWORD,
                identityId = p.identity?.id,
                groupId = groupId,
            )
            made[p.entry.alias] = h
            store.upsertHost(h)
        }
        val all = store.hosts.value
        for (p in chosen) {
            val jumpAlias = p.jumpAlias ?: continue
            val jump = made[jumpAlias] ?: all.firstOrNull { it.label.equals(jumpAlias, true) || it.hostname.equals(jumpAlias, true) } ?: continue
            val h = made[p.entry.alias] ?: continue
            if (jump.id != h.id) store.upsertHost(h.copy(jumpHostId = jump.id))
        }
    }
}
