package dev.flint.term.session

import android.util.Log
import androidx.compose.runtime.snapshotFlow
import dev.flint.term.data.Store
import dev.flint.term.ui.Pane
import dev.flint.term.ui.Workspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Which of a session's panes was the one sharing the screen. */
enum class PaneKind { TERM, FILES, SERVER }

/** One session as the tab strip had it: the host to dial, and what was open around it. */
data class OpenSession(
    val hostId: String,
    /** Its file browser was a tab of its own. */
    val filesOpen: Boolean = false,
    /**
     * The name it had been renamed to, or null when it still wore the host's
     * own. Only a rename is worth keeping: the default is whatever the host is
     * called *now*, which is the better answer if it has been renamed since.
     */
    val name: String? = null,
    /**
     * This session's pane was the one shown beside whatever was current. At
     * most one entry in a list can say so, since there is only one split.
     */
    val besidePane: PaneKind? = null,
)

/** A live session, reduced to what deciding whether to record it needs. */
data class LiveSession(val id: String, val hostId: String?, val name: String? = null)

/**
 * What was open when the app was last alive, so a cold start can put it back.
 *
 * Android kills a backgrounded app whenever it wants the memory, and the person
 * who comes back to it did not close anything — so the shape of the work should
 * still be there. Only the *list* is kept: no scrollback, no state, nothing that
 * would be a stale copy of a machine that has moved on. Each entry is dialled
 * again from scratch, which is also why tmux is worth turning on for a host.
 *
 * A playback, a serial console and a local shell are left out: there is nothing
 * on the other end of them to reconnect to. That falls out of having no host id.
 */
class OpenSessions(private val file: File, private val scope: CoroutineScope) {
    /**
     * The list as it stood last time.
     *
     * Read in the constructor, before anything can write over it: the recorder
     * below starts from "nothing is open", which is true of a process that has
     * just started and would otherwise erase the answer before it is asked for.
     *
     * A file half written when the app was killed leaves an empty list, which
     * is the same thing the app does on its very first run.
     */
    val saved: List<OpenSession> = runCatching {
        if (file.exists()) decode(file.readText()) else emptyList()
    }.getOrDefault(emptyList())

    private val claimed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val live = MutableStateFlow<List<LiveSession>>(emptyList())

    /**
     * True for the first caller only.
     *
     * The restore runs once per process and never again: a host that cannot be
     * reached is left closed, and nothing about a failed connection may lead
     * back round to trying it a second time.
     */
    fun claimRestore(): Boolean = claimed.compareAndSet(false, true)

    /** The sessions as they now stand; called on every register, ending and removal. */
    fun record(sessions: List<LiveSession>) {
        live.value = sessions
    }

    /**
     * Begin writing the file.
     *
     * Deliberately not started in the constructor: until the restore has had
     * its turn, what is on disk is still the truth about what should be open,
     * and an empty list written a moment too early would throw it away.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            combine(
                live,
                // The workspace knows about tabs and the split; neither goes
                // through the session manager, so both are watched here.
                snapshotFlow { Workspace.files.toList() to Workspace.beside },
            ) { sessions, (files, beside) ->
                snapshot(sessions, files.toSet(), beside?.let { it.sessionId to it.kind() })
            }
                .distinctUntilChanged()
                .collectLatest { entries ->
                    // collectLatest cancels this wait when the next change
                    // lands, so closing four tabs in a row costs one write.
                    delay(WRITE_DELAY_MS)
                    write(entries)
                }
        }
    }

    private suspend fun write(entries: List<OpenSession>) = withContext(Dispatchers.IO) {
        val text = encode(entries)
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.writeText(text)
            if (!tmp.renameTo(file)) file.writeText(text)
        } catch (e: Exception) {
            Log.e(TAG, "could not write $file", e)
        }
    }

    companion object {
        private const val TAG = "OpenSessions"

        /** Long enough that a burst of tab changes settles, short enough to survive a kill. */
        private const val WRITE_DELAY_MS = 700L

        /** What belongs in the file, in tab order. */
        fun snapshot(
            live: List<LiveSession>,
            filesOpen: Set<String>,
            beside: Pair<String, PaneKind>?,
        ): List<OpenSession> = live.mapNotNull { s ->
            val hostId = s.hostId ?: return@mapNotNull null
            OpenSession(
                hostId = hostId,
                filesOpen = s.id in filesOpen,
                besidePane = beside?.takeIf { it.first == s.id }?.second,
                name = s.name,
            )
        }

        fun encode(entries: List<OpenSession>): String {
            val array = JSONArray()
            for (e in entries) {
                array.put(
                    JSONObject().apply {
                        put("hostId", e.hostId)
                        put("filesOpen", e.filesOpen)
                        e.besidePane?.let { put("besidePane", it.name.lowercase()) }
                        e.name?.let { put("name", it) }
                    },
                )
            }
            return array.toString()
        }

        fun decode(text: String): List<OpenSession> {
            if (text.isBlank()) return emptyList()
            val array = JSONArray(text)
            return (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val hostId = o.optString("hostId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                OpenSession(
                    hostId = hostId,
                    filesOpen = o.optBoolean("filesOpen", false),
                    besidePane = o.optString("besidePane").takeIf { it.isNotBlank() }?.let { name ->
                        PaneKind.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    },
                    // Optional, like every field but the host: a file written
                    // before names were kept simply has none, and comes back as
                    // a session called after its host.
                    name = o.optString("name").takeIf { it.isNotBlank() },
                )
            }
        }

        /**
         * Dial [entries] again, in order, and hand back the sessions that were
         * opened.
         *
         * Through [SessionManager.openSsh] rather than anything of its own, so
         * a restored session is the same session a tap on the host row makes:
         * tmux reattaches, the machine is woken, the tunnel comes up. A host
         * that has since been deleted is simply skipped.
         *
         * Data saver is not consulted on purpose. Holding a *transfer* back for
         * Wi-Fi saves real megabytes; refusing to open a shell saves nothing
         * and leaves the person staring at an app that will not connect.
         */
        fun reopen(store: Store, sessions: SessionManager, entries: List<OpenSession>): List<String> {
            val opened = mutableListOf<String>()
            var beside: Pane? = null
            for (entry in entries) {
                val host = store.host(entry.hostId) ?: continue
                val session = sessions.openSsh(host)
                entry.name?.let { session.rename(it) }
                opened += session.id
                if (entry.filesOpen) Workspace.openFiles(session.id)
                when (entry.besidePane) {
                    PaneKind.TERM -> beside = Pane.Term(session.id)
                    PaneKind.FILES -> beside = Pane.Files(session.id)
                    PaneKind.SERVER -> {
                        Workspace.openServer(session.id)
                        beside = Pane.Server(session.id)
                    }
                    null -> {}
                }
            }
            if (beside != null) Workspace.beside = beside
            return opened
        }
    }
}

private fun Pane.kind(): PaneKind = when (this) {
    is Pane.Term -> PaneKind.TERM
    is Pane.Files -> PaneKind.FILES
    is Pane.Server -> PaneKind.SERVER
}
