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
import org.json.JSONObject
import java.io.File

/**
 * Single JSON document on disk, secrets encrypted with [Crypto]. Small enough
 * that a full rewrite is cheaper than a database, as long as a burst of changes
 * is one rewrite and not one each: see [CoalescingWriter] and [SecretCache].
 * The command history, which changes on every Enter, is kept apart in a
 * [HistoryLog] so that it does not rewrite the rest.
 */
class Store(context: Context) {
    private val appContext = context.applicationContext
    private val file = File(context.filesDir, "store.json")
    private val tmp = File(context.filesDir, "store.json.tmp")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val secrets = SecretCache(Crypto::encrypt, Crypto::decrypt)
    private val writer = CoalescingWriter<Snapshot>(scope, WRITE_QUIET_MS, ::write)
    private val historyFile = File(context.filesDir, "history.jsonl")
    private val historyLog = HistoryLog(historyFile, scope, WRITE_QUIET_MS)

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
    val history: StateFlow<Map<String, List<String>>> = historyLog.history
    /**
     * How often each command has been run on a host.
     *
     * The list above keeps one entry per command, so it can only say what was
     * run *last*; the count is what lets a suggestion say what is run *most*.
     */
    val historyCounts: StateFlow<Map<String, Map<String, Int>>> = historyLog.counts
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
        historyLog.forget(id)
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

    /** Appends to the history file alone; the store is not written for a command. */
    fun rememberCommand(hostId: String, command: String) = historyLog.remember(hostId, command)

    /** Merge commands read from a host's own shell history file. */
    fun addHistory(hostId: String, commands: List<String>, counts: Map<String, Int> = emptyMap()) =
        historyLog.add(hostId, commands, counts)

    fun clearHistory(hostId: String) = historyLog.forget(hostId)

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

    /**
     * A slider calls this on every frame of a drag, most of which land on the
     * value it already has, so a change to nothing is not a change at all.
     */
    fun updateSettings(block: (Settings) -> Settings) {
        while (true) {
            val old = _settings.value
            val new = block(old)
            if (new == old) return
            if (_settings.compareAndSet(old, new)) break
        }
        Schemes.setCustom(_settings.value.customSchemes)
        persist()
    }

    // ---- persistence -----------------------------------------------------

    /**
     * Queues the current state for the disk. Taking the snapshot is only
     * gathering references to immutable values, so it is done here, where the
     * state is known to be the one just changed; turning it into JSON and
     * sealing its secrets is left to the writer, off whichever thread called.
     */
    private fun persist() {
        writer.submit(snapshot())
    }

    /**
     * Puts any change still waiting on disk before returning. For when the
     * process may be about to go: the app leaving the screen is the last word
     * Android promises before it may kill us.
     */
    fun flush() {
        historyLog.flush()
        writer.flush()
    }

    private fun write(snap: Snapshot) {
        try {
            val text = secrets.write { seal -> StoreJson.write(snap, withHistory = false, secret = seal) }.toString()
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text)
            }
        } catch (e: Exception) {
            Log.e("Store", "persist failed", e)
        }
    }

    private fun load() {
        // A log that cannot be read is treated as missing, so a store that
        // still holds the history inline puts it back.
        val haveLog = try {
            historyLog.load()
        } catch (e: Exception) {
            Log.e("Store", "history load failed", e)
            false
        }
        if (!file.exists()) return
        try {
            val snap = StoreJson.read(JSONObject(file.readText()), secrets::open)
            replace(snap)
            if (snap.history.isNotEmpty() || snap.historyCounts.isNotEmpty()) {
                // A store from before the history had a file of its own. It
                // is moved there and on disk before the store is written
                // without it, so a kill in between loses nothing. A log that
                // already exists came from that move, and is the newer copy.
                if (!haveLog) {
                    historyLog.replaceAll(snap.history, snap.historyCounts)
                    historyLog.flush()
                }
                if (historyFile.exists()) persist()
            }
        } catch (e: Exception) {
            Log.e("Store", "load failed", e)
        }
    }

    /** Everything, as plain values; what a backup is made from. */
    fun snapshot(): Snapshot = Snapshot(
        hosts = _hosts.value, groups = _groups.value, accounts = _accounts.value, identities = _identities.value,
        tunnels = _tunnels.value, proxies = _proxies.value, tailscaleProfiles = _tailscale.value,
        snippets = _snippets.value, knownHosts = _knownHosts.value, history = historyLog.history.value,
        historyCounts = historyLog.counts.value, settings = _settings.value,
    )

    /** Everything but the history, which [HistoryLog] loads and keeps itself. */
    private fun replace(snap: Snapshot) {
        _hosts.value = snap.hosts
        _identities.value = snap.identities
        _knownHosts.value = snap.knownHosts
        _tunnels.value = snap.tunnels
        _tailscale.value = snap.tailscaleProfiles
        _proxies.value = snap.proxies
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
        historyLog.replaceAll(snap.history, snap.historyCounts)
        persist()
        // Not left to wait out the quiet period: a restore is the one change
        // somebody is likely to check survived by closing the app straight away.
        scope.launch { flush() }
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

        /** Long enough to fold a drag or a burst of commands into one write. */
        private const val WRITE_QUIET_MS = 750L
    }
}
