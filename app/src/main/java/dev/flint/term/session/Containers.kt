package dev.flint.term.session

import dev.flint.term.terminal.GridMemory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** The two runtimes this speaks to, and the command each answers to. */
enum class ContainerRuntime(val command: String) {
    DOCKER("docker"),
    PODMAN("podman"),
    ;

    companion object {
        fun of(name: String): ContainerRuntime? = entries.firstOrNull { it.command == name }
    }
}

/**
 * What a container is doing, reduced to the states worth a different button.
 *
 * Both runtimes report a longer list than this — "removing", "dead" — but from
 * a phone they all mean the same thing: it is not running and the only useful
 * offer is to start it.
 */
enum class ContainerState {
    RUNNING, PAUSED, RESTARTING, EXITED, CREATED, OTHER;

    val isUp: Boolean get() = this == RUNNING || this == PAUSED || this == RESTARTING

    companion object {
        fun of(text: String?): ContainerState = when (text?.trim()?.lowercase()) {
            "running", "up" -> RUNNING
            "paused" -> PAUSED
            "restarting" -> RESTARTING
            "exited", "stopped" -> EXITED
            "created", "configured", "initialized" -> CREATED
            else -> OTHER
        }
    }
}

/** One container as the list shows it, with its live numbers folded in. */
data class Container(
    val id: String,
    val name: String,
    val image: String,
    val state: ContainerState,
    /** The runtime's own words: "Up 36 hours", "Exited (137) 6 months ago". */
    val status: String,
    val ports: String = "",
    /** The compose project this belongs to, from the label compose stamps on it. */
    val project: String? = null,
    val service: String? = null,
    val cpuPercent: Double? = null,
    val memoryBytes: Long? = null,
    val memoryLimitBytes: Long? = null,
    val memoryPercent: Double? = null,
)

/** A compose project's containers, or the ones that belong to no project. */
data class ContainerGroup(val project: String?, val containers: List<Container>)

/** What can be done to a container from here. Nothing here removes anything. */
enum class ContainerAction(val verb: String, val label: String) {
    START("start", "Start"),
    STOP("stop", "Stop"),
    RESTART("restart", "Restart"),
    PAUSE("pause", "Pause"),
    UNPAUSE("unpause", "Unpause"),
    ;

    /**
     * Whether to ask before doing it. Only stopping does: a container is
     * somebody's service, and a mis-tap on a phone should not be what takes it
     * off the internet. Restart is loud but self-healing; pause is reversible.
     */
    val asksFirst: Boolean get() = this == STOP
}

/**
 * Everything about containers that is only text: the commands to send, and the
 * JSON that comes back.
 *
 * The two runtimes are close enough to share a screen and far enough apart to
 * need one parser that reads both. `{{json .}}` hands each of them straight to
 * Go's marshaller, so the shape of the reply is the shape of a Go struct nobody
 * designed for us: docker flattens everything to strings — labels as one
 * comma-joined line, ports as one printed line — while podman emits the struct
 * as it stands, with `Names` an array, `Labels` a map and `Ports` a list of
 * objects. So nothing here branches on which binary was found. It reads
 * whichever shape it is handed, which is also what keeps it right on the many
 * machines where `docker` is a shim over podman.
 */
object Containers {

    /** Lines of log fetched each time, and shown when a log tab opens. */
    const val LOG_TAIL = 200

    /**
     * Which runtime this machine has, if either.
     *
     * The question is not what is installed but what this login can list: a
     * docker binary with a dead daemon, or a user outside the docker group,
     * are both a section that would only ever show an error. `ps -q` is the
     * cheapest way to ask the real question. Docker is tried first because on
     * a machine with both it is usually the one wired to a running daemon.
     */
    val DETECT: String = """
        if command -v docker >/dev/null 2>&1 && docker ps -q >/dev/null 2>&1; then echo docker
        elif command -v podman >/dev/null 2>&1 && podman ps -q >/dev/null 2>&1; then echo podman
        fi
        exit 0
    """.trimIndent()

    fun runtimeFrom(output: String): ContainerRuntime? = output.lineSequence()
        .mapNotNull { ContainerRuntime.of(it.trim()) }
        .lastOrNull()

    /**
     * The list and the live numbers in one round trip.
     *
     * Two commands, one channel: `stats --no-stream` samples for a second or
     * two on its own, and asking for it separately would pay for the trip
     * twice. Marker lines cut the reply up, so anything a chatty login profile
     * printed first is discarded rather than parsed.
     */
    fun listScript(runtime: ContainerRuntime): String = """
        echo "@@ps"
        ${runtime.command} ps -a --format '{{json .}}' 2>/dev/null || true
        echo "@@stats"
        ${runtime.command} stats --no-stream --format '{{json .}}' 2>/dev/null || true
        echo "@@end"
    """.trimIndent()

    /**
     * The command for one action, or null if the id is not one we will paste
     * into a shell.
     *
     * The id arrives as JSON from the far end and leaves as part of a command
     * line, which is exactly the shape of an injection. Container ids are hex
     * and names are a narrow alphabet, so anything outside it is refused rather
     * than quoted — there is no legitimate container this rejects.
     */
    fun actionCommand(runtime: ContainerRuntime, action: ContainerAction, id: String): String? =
        if (!safeId(id)) null else "${runtime.command} ${action.verb} $id"

    fun logsCommand(runtime: ContainerRuntime, id: String, tail: Int = LOG_TAIL): String? =
        if (!safeId(id)) null else "${runtime.command} logs --tail $tail $id"

    private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")

    fun safeId(id: String): Boolean = SAFE_ID.matches(id)

    // ---- parsing ----------------------------------------------------------

    /** The whole reply to [listScript], as containers with their numbers attached. */
    fun parse(output: String): List<Container> {
        val sections = sections(output)
        return merge(parsePs(sections["ps"].orEmpty()), parseStats(sections["stats"].orEmpty()))
    }

    private val MARKER = Regex("^@@[a-z]+$")

    private fun sections(output: String): Map<String, String> {
        val out = LinkedHashMap<String, StringBuilder>()
        var current: StringBuilder? = null
        for (line in output.lineSequence()) {
            val trimmed = line.trim()
            if (MARKER.matches(trimmed)) {
                current = out.getOrPut(trimmed.removePrefix("@@")) { StringBuilder() }
            } else {
                current?.append(line)?.append('\n')
            }
        }
        return out.mapValues { it.value.toString() }
    }

    /** One JSON object per line; a line that is not one is somebody's warning. */
    private fun objects(text: String): List<JSONObject> = text.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("{") }
        .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
        .toList()

    fun parsePs(text: String): List<Container> = objects(text).mapNotNull { o ->
        val id = o.text("ID", "Id", "id")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val labels = labels(o.opt("Labels"))
        val state = o.text("State")?.let { ContainerState.of(it) }
            // Podman only reports `Exited`; docker only reports `State`. Where
            // neither is usable the human status line still starts with "Up".
            ?: ContainerState.of(o.text("Status")?.substringBefore(' '))
        Container(
            id = id,
            name = name(o) ?: id.take(12),
            image = o.text("Image").orEmpty(),
            state = state,
            status = o.text("Status").orEmpty(),
            ports = ports(o.opt("Ports")),
            project = labels["com.docker.compose.project"] ?: labels["io.podman.compose.project"],
            service = labels["com.docker.compose.service"] ?: labels["io.podman.compose.service"],
        )
    }

    private fun name(o: JSONObject): String? = when (val names = o.opt("Names")) {
        // Docker joins a container's names with commas; podman sends the array.
        is JSONArray -> (0 until names.length()).map { names.optString(it) }.firstOrNull { it.isNotEmpty() }
        is String -> names.substringBefore(',').trim().trimStart('/')
        else -> null
    }?.takeIf { it.isNotEmpty() }

    /**
     * Labels as a map, from either shape.
     *
     * Docker's is one line of `k=v` joined with commas, which is lossy — a
     * label whose value contains a comma (an image description, often) comes
     * back split. A fragment with no `=` in it can only be the rest of the one
     * before, so it is glued back on; that recovers the common case and cannot
     * make the labels we actually read here any worse.
     */
    fun labels(value: Any?): Map<String, String> = when (value) {
        is JSONObject -> value.keys().asSequence().associateWith { value.optString(it, "") }
        is String -> {
            val out = LinkedHashMap<String, String>()
            var last: String? = null
            for (part in value.split(',')) {
                val key = part.substringBefore('=', "")
                if (key.isEmpty() || key == part) {
                    last?.let { out[it] = out[it].orEmpty() + "," + part }
                } else {
                    out[key] = part.substringAfter('=')
                    last = key
                }
            }
            out
        }
        else -> emptyMap()
    }

    /** Docker prints the port line for us; podman leaves us its port structs. */
    private fun ports(value: Any?): String = when (value) {
        is String -> value
        is JSONArray -> (0 until value.length()).mapNotNull { value.optJSONObject(it) }.joinToString(", ") { p ->
            val protocol = p.optString("protocol", "tcp").ifEmpty { "tcp" }
            val range = p.optInt("range", 1).coerceAtLeast(1)
            val host = p.optString("host_ip", "").ifEmpty { "0.0.0.0" }
            val hostPort = span(p.optInt("host_port"), range)
            val containerPort = span(p.optInt("container_port"), range)
            "$host:$hostPort->$containerPort/$protocol"
        }
        else -> ""
    }

    private fun span(first: Int, range: Int): String =
        if (range > 1) "$first-${first + range - 1}" else first.toString()

    /** One container's live numbers, however the runtime chose to write them. */
    data class Stat(
        val id: String?,
        val name: String?,
        val cpuPercent: Double?,
        val memoryBytes: Long?,
        val memoryLimitBytes: Long?,
        val memoryPercent: Double?,
    )

    /**
     * `stats`, from any of the three shapes the two runtimes emit.
     *
     * Docker writes every field as the string it prints — "0.09%",
     * "15.02MiB / 15.54GiB". Podman's `{{json .}}` hands over its own struct
     * instead, where the same fields are plain numbers in bytes and percent,
     * and its `--format json` is a third spelling again, lower-case and with
     * the percent signs back. Each field is read as whichever of the two it
     * turns out to be, so all three land in the same place.
     */
    fun parseStats(text: String): List<Stat> = objects(text).map { o ->
        val usage = o.opt("MemUsage") ?: o.opt("mem_usage")
        Stat(
            id = o.text("ID", "Id", "id", "ContainerID", "Container"),
            name = o.text("Name", "name"),
            cpuPercent = number(o.opt("CPUPerc") ?: o.opt("cpu_percent") ?: o.opt("CPU")),
            memoryBytes = bytes(usage?.let { split(it, 0) }) ?: (usage as? Number)?.toLong(),
            memoryLimitBytes = bytes(usage?.let { split(it, 1) }) ?: number(o.opt("MemLimit"))?.toLong(),
            memoryPercent = number(o.opt("MemPerc") ?: o.opt("mem_percent")),
        )
    }

    /** One side of docker's "15.02MiB / 15.54GiB"; nothing for a plain number. */
    private fun split(value: Any, index: Int): String? =
        (value as? String)?.split('/')?.getOrNull(index)?.trim()

    /**
     * A percentage or a count, from a number or from the way it was printed.
     * Docker's "--" for a container it has no sample for is not a zero.
     */
    internal fun number(value: Any?): Double? = when (value) {
        is Number -> value.toDouble()
        is String -> value.trim().removeSuffix("%").trim().toDoubleOrNull()
        else -> null
    }

    private val SIZE = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*([kKmMgGtTpP]?[iI]?[bB])?")

    /** "15.02MiB", "875B", "3.092MB" — both unit families, since both are used. */
    internal fun bytes(text: String?): Long? {
        val trimmed = text?.trim().orEmpty()
        val m = SIZE.matchAt(trimmed, 0) ?: return null
        val n = m.groupValues[1].toDoubleOrNull() ?: return null
        val unit = m.groupValues[2].lowercase()
        val binary = unit.contains("i")
        val step = if (binary) 1024.0 else 1000.0
        val scale = when (unit.firstOrNull()) {
            'k' -> step
            'm' -> step * step
            'g' -> step * step * step
            't' -> step * step * step * step
            'p' -> step * step * step * step * step
            else -> 1.0
        }
        return (n * scale).toLong()
    }

    /**
     * The numbers onto the containers they belong to.
     *
     * `stats` identifies a container by a short id where `ps` may have given a
     * long one, or the other way round, so both the short id and the name are
     * kept as keys — every runtime agrees on at least one of them.
     */
    fun merge(containers: List<Container>, stats: List<Stat>): List<Container> {
        if (stats.isEmpty()) return containers
        val byKey = HashMap<String, Stat>()
        for (s in stats) {
            s.id?.takeIf { it.isNotEmpty() }?.let { byKey[it.take(12)] = s }
            s.name?.takeIf { it.isNotEmpty() }?.let { byKey.putIfAbsent(it, s) }
        }
        return containers.map { c ->
            val s = byKey[c.id.take(12)] ?: byKey[c.name] ?: return@map c
            c.copy(
                cpuPercent = s.cpuPercent,
                memoryBytes = s.memoryBytes,
                memoryLimitBytes = s.memoryLimitBytes,
                memoryPercent = s.memoryPercent,
            )
        }
    }

    /**
     * Grouped the way the machine's owner thinks about them: a compose project
     * is one thing with several parts, and the parts are listed in service
     * order. Whatever belongs to no project goes last, under its own heading,
     * rather than being scattered between the projects.
     */
    fun group(containers: List<Container>): List<ContainerGroup> {
        val byProject = containers.groupBy { it.project }
        val projects = byProject.keys.filterNotNull().sortedBy { it.lowercase() }
        val ordered = projects.map { it to byProject.getValue(it) } +
            listOfNotNull(byProject[null]?.let { null to it })
        return ordered.map { (project, list) ->
            ContainerGroup(project, list.sortedBy { (it.service ?: it.name).lowercase() })
        }
    }

    /**
     * The lines of [fetched] that are not already the tail of [shown].
     *
     * A log tab re-reads the last [LOG_TAIL] lines every couple of seconds, so
     * consecutive reads overlap by almost all of themselves; the new part is
     * whatever follows the longest overlap. Matching the text rather than
     * trusting a timestamp is what keeps a line from being printed twice, and
     * it needs nothing from the server. A container that produced more than
     * [LOG_TAIL] lines between two reads loses the middle — the overlap is
     * gone, and there is nothing left to line the two reads up by.
     */
    fun newTail(shown: List<String>, fetched: List<String>): List<String> {
        for (k in minOf(shown.size, fetched.size) downTo 1) {
            if (shown.takeLast(k) == fetched.take(k)) return fetched.drop(k)
        }
        return fetched
    }

    private fun JSONObject.text(vararg keys: String): String? {
        for (key in keys) {
            val v = opt(key)
            if (v is String) return v
        }
        return null
    }
}

/**
 * The containers on one machine, kept for as long as somebody is looking.
 *
 * Which runtime the machine has is asked once and then remembered: it is a
 * property of the host, not of the moment, and paying for a `command -v` on
 * every five-second refresh would double the cost of the section for an answer
 * that never changes.
 */
class ContainerProbe(private val exec: SessionExec) {

    var runtime: ContainerRuntime? = null
        private set

    /** True once we have asked and been told there is nothing here. */
    private var searched = false

    /** Null when this machine has no runtime this login can talk to. */
    suspend fun read(): List<ContainerGroup>? {
        val rt = runtime ?: run {
            if (searched) return null
            val found = Containers.runtimeFrom(exec.run(Containers.DETECT))
            searched = true
            runtime = found
            found ?: return null
        }
        return Containers.group(Containers.parse(exec.run(Containers.listScript(rt))))
    }

    /** Blocks until the runtime says it is done; throws what the runtime said. */
    suspend fun run(action: ContainerAction, container: Container) {
        val rt = runtime ?: return
        val command = Containers.actionCommand(rt, action, container.id)
            ?: throw IllegalArgumentException("${container.name} has an id this cannot act on")
        exec.run(command)
    }
}

/**
 * One container's log, followed into a terminal tab of its own.
 *
 * The tab is a real session so that it appears in the tab strip and is closed
 * like anything else, but it has nothing behind it: keystrokes go nowhere, and
 * the only thing that ever writes to it is this loop.
 *
 * It is a loop and not `logs -f` because the session's exec channel hands back
 * one string when the command ends, and a follow never ends — the tab would sit
 * empty for as long as the container lived. So the last [Containers.LOG_TAIL]
 * lines are re-read every couple of seconds and only the part that is new is
 * printed, which reads on screen the way a follow does.
 */
object ContainerLogs {

    private const val FOLLOW_MILLIS = 2_000L

    fun open(
        sessions: SessionManager,
        source: TerminalSession,
        runtime: ContainerRuntime,
        container: Container,
    ): TerminalSession? {
        val command = Containers.logsCommand(runtime, container.id) ?: return null
        val tab = sessions.openPlayback(
            label = "${container.name} logs",
            cols = GridMemory.cols.takeIf { it > 0 } ?: 80,
            rows = GridMemory.rows.takeIf { it > 0 } ?: 24,
            asTab = true,
        )
        // A connection of its own: the pane that opened this tab can be closed
        // a second later, and the log should outlive it.
        val exec = SessionExec(sessions, source)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            tab.write("$ $command")
            var shown = emptyList<String>()
            try {
                while (isActive && !tab.destroyed && sessions.get(tab.id) != null) {
                    val read = runCatching { exec.run(command) }
                    val text = read.getOrElse {
                        tab.write(it.message ?: "the log stopped")
                        break
                    }
                    val lines = if (text.isBlank()) emptyList() else text.trimEnd('\n').lines()
                    Containers.newTail(shown, lines).forEach { tab.write(it) }
                    shown = lines
                    delay(FOLLOW_MILLIS)
                }
            } finally {
                exec.close()
                scope.cancel()
            }
        }
        return tab
    }

    /** An exec channel ends its lines with a bare newline; a terminal needs both. */
    private fun TerminalSession.write(line: String) {
        runCatching { core.pushOutput((line + "\r\n").toByteArray(Charsets.UTF_8)) }
    }
}
