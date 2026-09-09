package dev.flint.term.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

/**
 * Single JSON document on disk, secrets encrypted with [Crypto]. Small enough
 * that a full rewrite on every change is cheaper than a database.
 */
class Store(context: Context) {
    private val appContext = context.applicationContext
    private val file = File(context.filesDir, "store.json")
    private val tmp = File(context.filesDir, "store.json.tmp")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()

    private val _hosts = MutableStateFlow<List<Host>>(emptyList())
    val hosts: StateFlow<List<Host>> = _hosts
    private val _identities = MutableStateFlow<List<Identity>>(emptyList())
    val identities: StateFlow<List<Identity>> = _identities
    private val _knownHosts = MutableStateFlow<List<KnownHost>>(emptyList())
    val knownHosts: StateFlow<List<KnownHost>> = _knownHosts
    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings
    private val _tunnels = MutableStateFlow<List<Tunnel>>(emptyList())
    val tunnels: StateFlow<List<Tunnel>> = _tunnels
    private val _tailscale = MutableStateFlow<List<TailscaleProfile>>(emptyList())
    val tailscaleProfiles: StateFlow<List<TailscaleProfile>> = _tailscale
    private val _proxies = MutableStateFlow<List<SavedProxy>>(emptyList())
    val proxies: StateFlow<List<SavedProxy>> = _proxies
    /** Commands run per host id, oldest first; the source for completions. */
    private val _history = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val history: StateFlow<Map<String, List<String>>> = _history
    /**
     * How often each command has been run on a host.
     *
     * The list above keeps one entry per command, so it can only say what was
     * run *last*; the count is what lets a suggestion say what is run *most*.
     */
    private val _historyCounts = MutableStateFlow<Map<String, Map<String, Int>>>(emptyMap())
    val historyCounts: StateFlow<Map<String, Map<String, Int>>> = _historyCounts
    private val _snippets = MutableStateFlow<List<Snippet>>(emptyList())
    val snippets: StateFlow<List<Snippet>> = _snippets
    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts: StateFlow<List<Account>> = _accounts
    private val _groups = MutableStateFlow<List<HostGroup>>(emptyList())
    val groups: StateFlow<List<HostGroup>> = _groups

    init {
        load()
    }

    fun host(id: String): Host? = _hosts.value.firstOrNull { it.id == id }
    fun identity(id: String?): Identity? = id?.let { i -> _identities.value.firstOrNull { it.id == i } }

    fun account(id: String?): Account? = id?.let { a -> _accounts.value.firstOrNull { it.id == a } }

    /**
     * [host] with everything it borrows filled in: the login of the account it
     * names.
     *
     * The stored record keeps its own fields untouched while an account is
     * chosen, so switching back puts the old login back. Everything that
     * *connects* wants the borrowed values, so it asks for this; everything that
     * *edits* wants the record as written, and does not.
     */
    fun effective(host: Host): Host {
        val withGroup = host.withGroup(group(host.groupId))
        return withGroup.withAccount(account(withGroup.accountId))
    }

    fun group(id: String?): HostGroup? = id?.let { g -> _groups.value.firstOrNull { it.id == g } }

    /** The group's name, for sectioning and search; blank when a host has none. */
    fun groupName(host: Host): String = group(host.groupId)?.name.orEmpty()

    fun upsertGroup(g: HostGroup) {
        _groups.update { list -> if (list.any { it.id == g.id }) list.map { if (it.id == g.id) g else it } else list + g }
        persist()
    }

    /** The hosts keep everything of their own; they simply stop inheriting. */
    fun deleteGroup(id: String) {
        _groups.update { list -> list.filterNot { it.id == id } }
        _hosts.update { list -> list.map { if (it.groupId == id) it.copy(groupId = null) else it } }
        persist()
    }

    /**
     * The id of the group called [name], created if there is none.
     *
     * Imports and the Tailscale screen think in names — "Work", the tailnet's
     * own name — and should not have to care whether that group exists yet.
     */
    fun groupIdFor(name: String): String? {
        val wanted = name.trim()
        if (wanted.isEmpty()) return null
        _groups.value.firstOrNull { it.name.equals(wanted, ignoreCase = true) }?.let { return it.id }
        val made = HostGroup(name = wanted)
        _groups.update { it + made }
        persist()
        return made.id
    }

    fun upsertAccount(a: Account) {
        _accounts.update { list -> if (list.any { it.id == a.id }) list.map { if (it.id == a.id) a else it } else list + a }
        persist()
    }

    /** Hosts left pointing at nothing would silently lose their login, so they get it back. */
    fun deleteAccount(id: String) {
        val gone = account(id)
        _accounts.update { list -> list.filterNot { it.id == id } }
        if (gone != null) {
            _hosts.update { list ->
                list.map { h ->
                    if (h.accountId != id) {
                        h
                    } else {
                        h.copy(
                            accountId = null,
                            username = h.username.ifBlank { gone.username },
                            authType = gone.authType,
                            password = gone.password,
                            identityId = gone.identityId,
                        )
                    }
                }
            }
        }
        persist()
    }

    fun upsertHost(host: Host) {
        val was = _hosts.value.firstOrNull { it.id == host.id }
        _hosts.update { list -> if (list.any { it.id == host.id }) list.map { if (it.id == host.id) host else it } else list + host }
        persist()
        // The Files app caches its list of storage locations until told otherwise.
        if (was?.showInFiles != host.showInFiles || (host.showInFiles && was?.displayName != host.displayName)) {
            dev.flint.term.files.SftpDocumentsProvider.refreshRoots(appContext)
        }
    }

    fun deleteHost(id: String) {
        _history.update { it - id }
        if (_hosts.value.firstOrNull { it.id == id }?.showInFiles == true) {
            dev.flint.term.files.SftpDocumentsProvider.refreshRoots(appContext)
        }
        _hosts.update { list -> list.filterNot { it.id == id }.map { if (it.jumpHostId == id) it.copy(jumpHostId = null) else it } }
        persist()
    }

    /** The chain of jump hosts for [host], outermost first; stops on cycles. */
    fun jumpChain(host: Host): List<Host> {
        val chain = ArrayList<Host>()
        val seen = HashSet<String>().apply { add(host.id) }
        var cur = host.jumpHostId?.let(::host)
        while (cur != null && seen.add(cur.id) && chain.size < 8) {
            chain.add(0, cur)
            cur = cur.jumpHostId?.let(::host)
        }
        return chain
    }

    fun touchHost(id: String) {
        _hosts.update { list -> list.map { if (it.id == id) it.copy(lastConnected = System.currentTimeMillis()) else it } }
        persist()
    }

    /**
     * Remember what a session found the host to be running.
     *
     * A field of its own rather than a whole [upsertHost]: the answer arrives
     * seconds after a connection, by which time the rest of the host may have
     * been edited, and writing the record we started from would undo that.
     */
    fun setDetectedOs(id: String, os: String) {
        val text = os.trim().lines().firstOrNull()?.trim()?.take(120).orEmpty()
        if (text.isEmpty() || _hosts.value.firstOrNull { it.id == id }?.detectedOs == text) return
        _hosts.update { list -> list.map { if (it.id == id) it.copy(detectedOs = text) else it } }
        persist()
    }

    /** Remember that a host's shell history has just been read. */
    fun markHistoryImported(id: String) {
        _hosts.update { list -> list.map { if (it.id == id) it.copy(historyImportedAt = System.currentTimeMillis()) else it } }
        persist()
    }

    fun upsertIdentity(identity: Identity) {
        _identities.update { list -> if (list.any { it.id == identity.id }) list.map { if (it.id == identity.id) identity else it } else list + identity }
        persist()
    }

    fun deleteIdentity(id: String) {
        // A keystore entry outlives the app's own records unless it is asked to go.
        if (_identities.value.firstOrNull { it.id == id }?.hardware == true) HardwareKeys.delete(id)
        _identities.update { list -> list.filterNot { it.id == id } }
        _hosts.update { list -> list.map { if (it.identityId == id) it.copy(identityId = null, authType = AuthType.PASSWORD) else it } }
        _accounts.update { list -> list.map { if (it.identityId == id) it.copy(identityId = null, authType = AuthType.PASSWORD) else it } }
        persist()
    }

    fun upsertSnippet(sn: Snippet) {
        _snippets.update { list -> if (list.any { it.id == sn.id }) list.map { if (it.id == sn.id) sn else it } else list + sn }
        persist()
    }

    fun deleteSnippet(id: String) {
        _snippets.update { list -> list.filterNot { it.id == id } }
        // A host would otherwise keep waiting for a snippet that no longer exists.
        _hosts.update { list -> list.map { h -> if (id in h.startupSnippetIds) h.copy(startupSnippetIds = h.startupSnippetIds - id) else h } }
        persist()
    }

    fun tunnel(id: String?): Tunnel? = id?.let { t -> _tunnels.value.firstOrNull { it.id == t } }

    fun upsertTunnel(t: Tunnel) {
        _tunnels.update { list -> if (list.any { it.id == t.id }) list.map { if (it.id == t.id) t else it } else list + t }
        persist()
    }

    fun deleteTunnel(id: String) {
        _tunnels.update { list -> list.filterNot { it.id == id } }
        _hosts.update { list -> list.map { if (it.tunnelId == id) it.copy(tunnelId = null) else it } }
        persist()
    }

    fun tailscaleProfile(id: String?): TailscaleProfile? = id?.let { p -> _tailscale.value.firstOrNull { it.id == p } }

    fun upsertTailscaleProfile(p: TailscaleProfile) {
        _tailscale.update { list -> if (list.any { it.id == p.id }) list.map { if (it.id == p.id) p else it } else list + p }
        persist()
    }

    fun deleteTailscaleProfile(id: String) {
        _tailscale.update { list -> list.filterNot { it.id == id } }
        _hosts.update { list -> list.map { if (it.tailscaleId == id) it.copy(tailscaleId = null) else it } }
        persist()
    }

    fun rememberCommand(hostId: String, command: String) {
        val before = _history.value[hostId].orEmpty()
        val after = CommandHistory.remember(before, command)
        val trimmed = command.trim()
        if (after === before || after == before) {
            // Already known, but running it again is exactly what the count is for.
            if (CommandHistory.worthKeeping(trimmed) && trimmed in after) bumpCount(hostId, trimmed)
            return
        }
        _history.update { it + (hostId to after) }
        bumpCount(hostId, trimmed)
        persist()
    }

    private fun bumpCount(hostId: String, command: String) {
        _historyCounts.update { all ->
            val forHost = all[hostId].orEmpty()
            all + (hostId to (forHost + (command to ((forHost[command] ?: 0) + 1))))
        }
    }

    /** Merge commands read from a host's own shell history file. */
    fun addHistory(hostId: String, commands: List<String>, counts: Map<String, Int> = emptyMap()) {
        if (commands.isEmpty()) return
        val merged = commands.fold(_history.value[hostId].orEmpty()) { acc, c -> CommandHistory.remember(acc, c) }
        _history.update { it + (hostId to merged) }
        if (counts.isNotEmpty()) {
            _historyCounts.update { all ->
                val forHost = all[hostId].orEmpty()
                // The file's own repetitions are better evidence than anything
                // counted here, so they win rather than adding to it.
                all + (hostId to (forHost + counts.mapValues { (c, n) -> maxOf(n, forHost[c] ?: 0) }))
            }
        }
        persist()
    }

    fun clearHistory(hostId: String) {
        _history.update { it - hostId }
        _historyCounts.update { it - hostId }
        persist()
    }

    fun proxy(id: String?): SavedProxy? = id?.let { p -> _proxies.value.firstOrNull { it.id == p } }

    fun upsertProxy(p: SavedProxy) {
        _proxies.update { list -> if (list.any { it.id == p.id }) list.map { if (it.id == p.id) p else it } else list + p }
        persist()
    }

    fun deleteProxy(id: String) {
        _proxies.update { list -> list.filterNot { it.id == id } }
        _hosts.update { list -> list.map { if (it.proxyId == id) it.copy(proxyId = null) else it } }
        persist()
    }

    /**
     * The key trusted for a server. Imported hashed entries carry no name at all,
     * so the only way to recognize one is to test it — a linear walk of a list
     * that is already in memory, and only for a host nothing was saved under.
     */
    fun knownHost(host: String, port: Int): KnownHost? = KnownHostMatch.find(_knownHosts.value, host, port)

    fun rememberHostKey(k: KnownHost) {
        _knownHosts.update { list -> list.filterNot { it.sameHostAs(k) } + k }
        persist()
    }

    /** Forgets the entry itself, which is the only handle a hashed one offers. */
    fun forgetHostKey(k: KnownHost) {
        _knownHosts.update { list -> list.filterNot { it.sameHostAs(k) } }
        persist()
    }

    fun updateSettings(block: (Settings) -> Settings) {
        _settings.update(block)
        Schemes.setCustom(_settings.value.customSchemes)
        persist()
    }

    // ---- persistence -----------------------------------------------------

    private fun persist() {
        val snapshot = toJson()
        scope.launch {
            writeLock.withLock {
                try {
                    tmp.writeText(snapshot.toString())
                    if (!tmp.renameTo(file)) {
                        file.writeText(snapshot.toString())
                    }
                } catch (e: Exception) {
                    Log.e("Store", "persist failed", e)
                }
            }
        }
    }

    private fun load() {
        if (!file.exists()) return
        try {
            fromJson(JSONObject(file.readText()))
        } catch (e: Exception) {
            Log.e("Store", "load failed", e)
        }
    }

    private fun toJson(): JSONObject = StoreJson.write(snapshot(), Crypto::encrypt)

    private fun fromJson(root: JSONObject) = replace(StoreJson.read(root, Crypto::decrypt))

    /** Everything, as plain values; what a backup is made from. */
    fun snapshot(): Snapshot = Snapshot(
        hosts = _hosts.value, groups = _groups.value, accounts = _accounts.value, identities = _identities.value,
        tunnels = _tunnels.value, proxies = _proxies.value, tailscaleProfiles = _tailscale.value,
        snippets = _snippets.value, knownHosts = _knownHosts.value, history = _history.value,
        historyCounts = _historyCounts.value, settings = _settings.value,
    )

    private fun replace(snap: Snapshot) {
        _hosts.value = snap.hosts
        _identities.value = snap.identities
        _knownHosts.value = snap.knownHosts
        _tunnels.value = snap.tunnels
        _tailscale.value = snap.tailscaleProfiles
        _proxies.value = snap.proxies
        _history.value = snap.history
        _historyCounts.value = snap.historyCounts
        _snippets.value = snap.snippets
        _accounts.value = snap.accounts
        _groups.value = snap.groups
        _settings.value = snap.settings
        // Imported schemes are looked up by id from wherever a palette is
        // needed, so the catalog has to know them as soon as the file is read.
        Schemes.setCustom(snap.settings.customSchemes)
    }

    /** Put a merged restore in place of what is here, and on disk. */
    fun restore(snap: Snapshot) {
        replace(snap)
        persist()
        // Hosts shown in the Files app may have arrived or changed name.
        dev.flint.term.files.SftpDocumentsProvider.refreshRoots(appContext)
    }

    companion object {
        /**
         * Id given to the profile migrated from the single-node era, so hosts
         * that only recorded "viaTailscale" still point at it and its state
         * directory can be moved into place.
         */
        const val LEGACY_TAILSCALE_ID = "tailscale-default"
    }
}
