package dev.flint.term.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * The command history, in a file of its own that grows by a line per command.
 *
 * It used to live in the store, so every Enter at a prompt rewrote the whole
 * store — every host, every setting, up to [CommandHistory.LIMIT] commands for
 * each host — to record one line. Here an Enter appends one line and nothing
 * else; the file is rewritten from what is in memory only once the appends
 * have made it about twice the size a fresh copy would be.
 *
 * Each line is a JSON object about one host:
 *
 * - `{"host": id, "run": command}` — a command was run, with exactly what
 *   [ran] makes of that: moved to the end of the list and counted.
 * - `{"host": id, "set": [commands], "counts": {command: n}}` — the host's
 *   history is this, whatever came before. A rewritten file is one of these
 *   per host; an import, a restore and a forgotten host (`set` empty) append
 *   one.
 *
 * The history was never sealed in the store, only kept in the app's private
 * directory, and it is kept there here, in the same way.
 */
internal class HistoryLog(private val file: File, scope: CoroutineScope, quietMs: Long) {
    private val tmp = File(file.path + ".tmp")

    private val _history = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val history: StateFlow<Map<String, List<String>>> = _history
    private val _counts = MutableStateFlow<Map<String, Map<String, Int>>>(emptyMap())
    val counts: StateFlow<Map<String, Map<String, Int>>> = _counts

    /**
     * Guards the state and [pending] together. A rewrite takes the state and
     * empties [pending] in one step, so a line is never both in the rewritten
     * file and appended after it, which would count its command twice.
     */
    private val lock = Any()
    private val pending = ArrayList<String>()
    private var rewrite = false

    /** Only touched by [write], which [CoalescingWriter] runs one at a time. */
    private var onDisk = 0L
    private var baseline = 0L

    private val writer = CoalescingWriter<Unit>(scope, quietMs) { write() }

    /** Reads the file; false when there is none yet. */
    fun load(): Boolean {
        if (!file.exists()) return false
        val text = file.readText()
        var state = State(emptyMap(), emptyMap())
        for (line in text.lineSequence()) {
            // A process killed mid-append can leave half a line at the end.
            // Everything before it is whole, and that is all that is lost.
            val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
            state = runCatching { apply(state, o) }.getOrDefault(state)
        }
        synchronized(lock) {
            _history.value = state.history
            _counts.value = state.counts
        }
        onDisk = file.length()
        baseline = compacted(state).sumOf { it.length + 1L }
        // The next append would be glued onto a half line and lost with it,
        // so a file that does not end where a line does is written afresh.
        if (tooBig() || (text.isNotEmpty() && !text.endsWith('\n'))) {
            synchronized(lock) { rewrite = true }
            writer.submit(Unit)
        }
        return true
    }

    fun remember(hostId: String, command: String) {
        synchronized(lock) {
            val before = State(_history.value, _counts.value)
            val after = ran(before, hostId, command)
            if (after === before) return
            _history.value = after.history
            _counts.value = after.counts
            pending += JSONObject().put("host", hostId).put("run", command).toString()
        }
        writer.submit(Unit)
    }

    /** Merge commands read from a host's own shell history file. */
    fun add(hostId: String, commands: List<String>, counts: Map<String, Int>) {
        if (commands.isEmpty()) return
        synchronized(lock) {
            val merged = commands.fold(_history.value[hostId].orEmpty()) { acc, c -> CommandHistory.remember(acc, c) }
            val forHost = _counts.value[hostId].orEmpty()
            // The file's own repetitions are better evidence than anything
            // counted here, so they win rather than adding to it.
            val newCounts = if (counts.isEmpty()) forHost else {
                CommandHistory.prune(forHost + counts.mapValues { (c, n) -> maxOf(n, forHost[c] ?: 0) }, merged)
            }
            setLocked(hostId, merged, newCounts)
        }
        writer.submit(Unit)
    }

    fun forget(hostId: String) {
        synchronized(lock) {
            if (hostId !in _history.value && hostId !in _counts.value) return
            setLocked(hostId, emptyList(), emptyMap())
        }
        writer.submit(Unit)
    }

    /** Everything replaced, as a restore or the move out of the store does. */
    fun replaceAll(history: Map<String, List<String>>, counts: Map<String, Map<String, Int>>) {
        val state = normalize(State(history, counts))
        synchronized(lock) {
            _history.value = state.history
            _counts.value = state.counts
            pending.clear()
            rewrite = true
        }
        writer.submit(Unit)
    }

    /** Puts anything waiting on disk before returning, on the calling thread. */
    fun flush() = writer.flush()

    private fun setLocked(hostId: String, commands: List<String>, counts: Map<String, Int>) {
        val state = set(State(_history.value, _counts.value), hostId, commands, counts)
        _history.value = state.history
        _counts.value = state.counts
        pending += setLine(hostId, state.history[hostId].orEmpty(), state.counts[hostId].orEmpty())
    }

    private fun tooBig() = onDisk > 2 * maxOf(baseline, MIN_COMPACT_BYTES)

    private fun write() {
        var whole: State? = null
        var lines: List<String> = emptyList()
        synchronized(lock) {
            if (pending.isEmpty() && !rewrite) return
            val adding = pending.sumOf { it.length + 1L }
            if (rewrite || onDisk + adding > 2 * maxOf(baseline, MIN_COMPACT_BYTES)) {
                whole = State(_history.value, _counts.value)
            } else {
                lines = ArrayList(pending)
            }
            pending.clear()
            rewrite = false
        }
        try {
            val all = whole
            if (all != null) {
                val text = compacted(all).joinToString("") { it + "\n" }
                tmp.writeText(text)
                if (!tmp.renameTo(file)) file.writeText(text)
                onDisk = file.length()
                baseline = onDisk
            } else {
                val bytes = lines.joinToString("") { it + "\n" }.toByteArray()
                FileOutputStream(file, true).use { it.write(bytes) }
                onDisk += bytes.size
            }
        } catch (e: Exception) {
            // What is on disk is no longer known to match what is in memory,
            // so the next write puts all of it down afresh. Asked for now, not
            // left for the next Enter: there may not be one before a kill.
            synchronized(lock) { rewrite = true }
            writer.submit(Unit)
        }
    }

    internal data class State(
        val history: Map<String, List<String>>,
        val counts: Map<String, Map<String, Int>>,
    )

    companion object {
        /** Below this a rewrite would cost more than the lines it saves. */
        private const val MIN_COMPACT_BYTES = 16 * 1024L

        /**
         * [state] after [command] was run on [hostId]: the command moved to the
         * end and counted. The same instance when there is nothing to record.
         */
        internal fun ran(state: State, hostId: String, command: String): State {
            val before = state.history[hostId].orEmpty()
            val after = CommandHistory.remember(before, command)
            val trimmed = command.trim()
            // Already known, but running it again is exactly what the count is for.
            if ((after === before || after == before) && (!CommandHistory.worthKeeping(command) || trimmed !in after)) return state
            val forHost = state.counts[hostId].orEmpty()
            val bumped = forHost + (trimmed to ((forHost[trimmed] ?: 0) + 1))
            return State(state.history + (hostId to after), state.counts + (hostId to CommandHistory.prune(bumped, after)))
        }

        private fun set(state: State, hostId: String, commands: List<String>, counts: Map<String, Int>): State {
            val kept = commands.takeLast(CommandHistory.LIMIT)
            if (kept.isEmpty()) return State(state.history - hostId, state.counts - hostId)
            return State(state.history + (hostId to kept), state.counts + (hostId to CommandHistory.prune(counts, kept)))
        }

        /**
         * Counts from before they were bounded can hold one for every command
         * ever run; only those still in the history matter.
         */
        internal fun normalize(state: State): State =
            (state.history.keys + state.counts.keys).fold(State(emptyMap(), emptyMap())) { acc, id ->
                set(acc, id, state.history[id].orEmpty(), state.counts[id].orEmpty())
            }

        private fun apply(state: State, o: JSONObject): State {
            val hostId = o.optString("host").ifEmpty { return state }
            if (o.has("run")) return ran(state, hostId, o.getString("run"))
            val arr = o.optJSONArray("set") ?: return state
            val commands = (0 until arr.length()).map { arr.getString(it) }
            val counts = HashMap<String, Int>()
            o.optJSONObject("counts")?.let { c -> for (k in c.keys()) counts[k] = c.optInt(k, 1) }
            return set(state, hostId, commands, counts)
        }

        private fun setLine(hostId: String, commands: List<String>, counts: Map<String, Int>): String =
            JSONObject().put("host", hostId).put("set", JSONArray(commands))
                .put("counts", JSONObject().apply { counts.forEach { (c, n) -> put(c, n) } })
                .toString()

        private fun compacted(state: State): List<String> =
            state.history.map { (id, commands) -> setLine(id, commands, state.counts[id].orEmpty()) }
    }
}
