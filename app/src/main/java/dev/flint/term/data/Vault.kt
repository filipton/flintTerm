package dev.flint.term.data

import org.json.JSONObject

/**
 * The backup file's contents, before sealing and after opening.
 *
 * The file is the store's own JSON with the secrets left in the clear, plus a
 * `vault` block saying where and when it was made; the passphrase-sealing
 * around it is `seal_vault` in the Rust core, whose header comment is the
 * format's specification. Nothing here does any cryptography.
 *
 * A restore is a merge, never a replacement: what the file has is added, an
 * entry that exists on both sides takes the file's version, and nothing that
 * only this device knows is thrown away. That is the behavior a restore
 * after a reinstall, a move to a new phone and a copy of a friend's hosts all
 * want, and it means a restore cannot lose anything it did not write.
 */
object Vault {
    const val EXTENSION = "atvault"
    const val FORMAT = 1

    /** The `vault` block: what the file says about itself. */
    data class Meta(val exportedAt: Long, val device: String, val withSecrets: Boolean)

    /** A file that opened but was not a backup — a store file, say, or a newer format. */
    class NotABackup(message: String) : IllegalArgumentException(message)

    /** The plaintext to seal: [snap] as JSON, with or without its secrets. */
    fun plain(snap: Snapshot, withSecrets: Boolean, device: String, now: Long = System.currentTimeMillis()): ByteArray {
        val root = StoreJson.write(strip(snap, withSecrets)) { it }
        root.put("vault", JSONObject().apply {
            put("app", "androidterm"); put("format", FORMAT); put("exportedAt", now); put("device", device); put("secrets", withSecrets)
        })
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    /** What a sealed file held, once opened. */
    fun parse(bytes: ByteArray): Pair<Meta, Snapshot> {
        val root = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrElse { throw NotABackup("the file did not open as JSON") }
        val meta = root.optJSONObject("vault") ?: throw NotABackup("the file has no backup header")
        val format = meta.optInt("format")
        if (format > FORMAT) throw NotABackup("this backup was made by a newer version of the app (format $format)")
        return Meta(meta.optLong("exportedAt"), meta.optString("device"), meta.optBoolean("secrets")) to
            strip(StoreJson.read(root) { it }, withSecrets = true)
    }

    /**
     * [snap] as a backup can carry it.
     *
     * A keystore key never leaves its device, so the identity that names one
     * is left out: restored elsewhere it would be a key that cannot sign, and
     * restored here after a reinstall the keystore entry is gone anyway. A
     * security-key identity stays — its private half is on the token, and the
     * handle in the record is exactly what another phone needs to use it.
     */
    fun strip(snap: Snapshot, withSecrets: Boolean): Snapshot {
        val identities = snap.identities.filterNot { it.hardware }
        if (withSecrets) return snap.copy(identities = identities)
        return snap.copy(
            hosts = snap.hosts.map { it.copy(password = "") },
            accounts = snap.accounts.map { it.copy(password = "") },
            identities = identities.map { it.copy(privateKey = "", passphrase = "") },
            tunnels = snap.tunnels.map { it.copy(config = "") },
            proxies = snap.proxies.map { it.copy(password = "") },
            tailscaleProfiles = snap.tailscaleProfiles.map { it.copy(authKey = "") },
        )
    }

    /**
     * [incoming] laid over [local].
     *
     * A file made without secrets says nothing about them, so an entry it
     * replaces keeps the password or key this device already had for it.
     * Server keys are the other way around — what this device has seen is
     * more recent than what the file remembers, so a known host stays as it
     * is and only new ones are added.
     */
    fun merge(local: Snapshot, incoming: Snapshot, incomingHasSecrets: Boolean): Snapshot {
        val hosts = overlay(local.hosts, incoming.hosts, { it.id }) { mine, theirs ->
            if (incomingHasSecrets) theirs else theirs.copy(password = mine.password)
        }
        val accounts = overlay(local.accounts, incoming.accounts, { it.id }) { mine, theirs ->
            if (incomingHasSecrets) theirs else theirs.copy(password = mine.password)
        }
        val identities = overlay(local.identities, incoming.identities.filterNot { it.hardware }, { it.id }) { mine, theirs ->
            when {
                // A keystore key here cannot be replaced by a record from a
                // file, whatever the file says about the same id.
                mine.hardware -> mine
                incomingHasSecrets -> theirs
                else -> theirs.copy(privateKey = mine.privateKey, passphrase = mine.passphrase)
            }
        }
        val tunnels = overlay(local.tunnels, incoming.tunnels, { it.id }) { mine, theirs ->
            if (incomingHasSecrets) theirs else theirs.copy(config = mine.config)
        }
        val proxies = overlay(local.proxies, incoming.proxies, { it.id }) { mine, theirs ->
            if (incomingHasSecrets) theirs else theirs.copy(password = mine.password)
        }
        // Whether a node is joined is a fact about this device's state
        // directory, not about the profile, so the file's word on it is ignored.
        val tailscale = overlay(local.tailscaleProfiles, incoming.tailscaleProfiles.map { it.copy(joined = false) }, { it.id }) { mine, theirs ->
            (if (incomingHasSecrets) theirs else theirs.copy(authKey = mine.authKey)).copy(joined = mine.joined)
        }
        val groups = overlay(local.groups, incoming.groups, { it.id }) { _, theirs -> theirs }
        val snippets = overlay(local.snippets, incoming.snippets, { it.id }) { _, theirs -> theirs }
        val knownHosts = overlay(local.knownHosts, incoming.knownHosts, { it.identity }) { mine, _ -> mine }

        val history = (local.history.keys + incoming.history.keys).associateWith { id ->
            incoming.history[id].orEmpty().fold(local.history[id].orEmpty()) { acc, c -> CommandHistory.remember(acc, c) }
        }
        val counts = (local.historyCounts.keys + incoming.historyCounts.keys).associateWith { id ->
            val mine = local.historyCounts[id].orEmpty()
            val theirs = incoming.historyCounts[id].orEmpty()
            (mine.keys + theirs.keys).associateWith { c -> maxOf(mine[c] ?: 0, theirs[c] ?: 0) }
        }

        val merged = Snapshot(
            hosts = hosts, groups = groups, accounts = accounts, identities = identities, tunnels = tunnels,
            proxies = proxies, tailscaleProfiles = tailscale, snippets = snippets, knownHosts = knownHosts,
            history = history, historyCounts = counts, settings = settings(local.settings, incoming.settings),
        )
        return unlinkMissing(merged)
    }

    /**
     * What a restore takes from the file's settings: the preferences, and not
     * the things that belong to a device — the app lock (a phone with no
     * screen lock could otherwise be locked out), the automation allowlist
     * and its log (they name apps installed *there*), and an imported font
     * (the font file itself is not in the backup).
     */
    private fun settings(mine: Settings, theirs: Settings): Settings = theirs.copy(
        appLock = mine.appLock,
        appLockGraceSeconds = mine.appLockGraceSeconds,
        automation = mine.automation,
        automationAllowed = mine.automationAllowed,
        automationLog = mine.automationLog,
        fontFamily = if (theirs.fontFamily.startsWith("file:")) mine.fontFamily else theirs.fontFamily,
    )

    /**
     * [theirs] over [mine] by key: mine in their order, each replaced by the
     * matching entry through [both]; then whatever only they had, in its order.
     */
    private fun <T> overlay(mine: List<T>, theirs: List<T>, key: (T) -> String, both: (T, T) -> T): List<T> {
        val byKey = theirs.associateBy(key)
        val kept = mine.map { m -> byKey[key(m)]?.let { both(m, it) } ?: m }
        val seen = mine.map(key).toSet()
        return kept + theirs.filterNot { key(it) in seen }
    }

    /**
     * Drop references to things the merge did not end up with — an identity
     * left behind because it was a keystore key, most often — so nothing
     * points at an id that resolves to nothing. Follows [Store.deleteIdentity]:
     * a host whose key is gone falls back to a password.
     */
    private fun unlinkMissing(s: Snapshot): Snapshot {
        val ids = s.identities.map { it.id }.toSet()
        val hostIds = s.hosts.map { it.id }.toSet()
        val groups = s.groups.map { it.id }.toSet()
        val accounts = s.accounts.map { it.id }.toSet()
        val tunnels = s.tunnels.map { it.id }.toSet()
        val proxies = s.proxies.map { it.id }.toSet()
        val tailscale = s.tailscaleProfiles.map { it.id }.toSet()
        val snippets = s.snippets.map { it.id }.toSet()
        fun String?.inside(set: Set<String>) = this?.takeIf { it in set }
        return s.copy(
            hosts = s.hosts.map { h ->
                h.copy(
                    identityId = h.identityId.inside(ids),
                    authType = if (h.identityId != null && h.identityId !in ids) AuthType.PASSWORD else h.authType,
                    accountId = h.accountId.inside(accounts),
                    groupId = h.groupId.inside(groups),
                    jumpHostId = h.jumpHostId.inside(hostIds),
                    tunnelId = h.tunnelId.inside(tunnels),
                    proxyId = h.proxyId.inside(proxies),
                    tailscaleId = h.tailscaleId.inside(tailscale),
                    startupSnippetIds = h.startupSnippetIds.filter { it in snippets },
                    addresses = h.addresses.map { a -> a.copy(tunnelId = a.tunnelId.inside(tunnels), tailscaleId = a.tailscaleId.inside(tailscale)) },
                )
            },
            accounts = s.accounts.map { a ->
                a.copy(
                    identityId = a.identityId.inside(ids),
                    authType = if (a.identityId != null && a.identityId !in ids) AuthType.PASSWORD else a.authType,
                )
            },
            groups = s.groups.map { g ->
                g.copy(
                    accountId = g.accountId.inside(accounts), jumpHostId = g.jumpHostId.inside(hostIds),
                    tunnelId = g.tunnelId.inside(tunnels), proxyId = g.proxyId.inside(proxies), tailscaleId = g.tailscaleId.inside(tailscale),
                )
            },
            snippets = s.snippets.map { sn -> sn.copy(hostIds = sn.hostIds.filter { it in hostIds }) },
        )
    }

    /** "12 hosts, 3 keys and 5 snippets" — what a file holds, for a dialog. */
    fun describe(snap: Snapshot): String {
        val parts = listOfNotNull(
            count(snap.hosts.size, "host"),
            count(snap.identities.size, "key"),
            count(snap.snippets.size, "snippet"),
            count(snap.tunnels.size, "tunnel"),
            count(snap.groups.size, "group"),
            count(snap.accounts.size, "account"),
            count(snap.knownHosts.size, "server key"),
        )
        return when (parts.size) {
            0 -> "nothing but settings"
            1 -> parts[0]
            else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
        }
    }

    private fun count(n: Int, noun: String): String? = if (n == 0) null else "$n $noun${if (n == 1) "" else "s"}"
}
