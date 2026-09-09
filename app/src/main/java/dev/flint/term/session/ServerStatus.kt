package dev.flint.term.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * What a machine is doing right now, as far as it was willing to say.
 *
 * Every field is nullable or empty on purpose. A phone showing "0%" for a
 * number the server never reported is worse than a phone showing nothing: the
 * zero looks like an answer. So anything this OS does not expose — per-core
 * time on macOS, a thermal zone on a machine without one — stays absent all the
 * way to the screen.
 */
data class ServerStatus(
    val hostname: String? = null,
    /** What `uname -s` said: "Linux", "Darwin", "FreeBSD". */
    val os: String? = null,
    val uptimeSeconds: Long? = null,
    val load: LoadAverage? = null,
    val cpuCount: Int? = null,
    /** Busy share of the whole machine over the sampling second, 0..1. */
    val cpu: Double? = null,
    /** Busy share per core, in core order; empty where the OS does not break it down. */
    val cores: List<Double> = emptyList(),
    val memory: MemoryUse? = null,
    /** Absent when the machine has no swap at all, which is not the same as unused swap. */
    val swap: MemoryUse? = null,
    val disks: List<DiskUse> = emptyList(),
    val interfaces: List<NetRate> = emptyList(),
    val temperatureC: Double? = null,
    val processes: List<ProcessUse> = emptyList(),
) {
    /** The interface actually carrying traffic; the one worth a single line of screen. */
    val busiestInterface: NetRate? get() = interfaces.maxByOrNull { it.rxPerSecond + it.txPerSecond }

    /** The disk closest to full — what a glance at a widget is really asking about. */
    val fullestDisk: DiskUse? get() = disks.maxByOrNull { it.fraction }
}

data class LoadAverage(val one: Double, val five: Double, val fifteen: Double)

data class MemoryUse(val totalBytes: Long, val usedBytes: Long, val cachedBytes: Long? = null) {
    val fraction: Double get() = if (totalBytes > 0) (usedBytes.toDouble() / totalBytes).coerceIn(0.0, 1.0) else 0.0
    val freeBytes: Long get() = (totalBytes - usedBytes).coerceAtLeast(0)
}

data class DiskUse(val filesystem: String, val mount: String, val totalBytes: Long, val usedBytes: Long) {
    val fraction: Double get() = if (totalBytes > 0) (usedBytes.toDouble() / totalBytes).coerceIn(0.0, 1.0) else 0.0
}

data class NetRate(val name: String, val rxPerSecond: Long, val txPerSecond: Long)

data class ProcessUse(val pid: Int, val cpuPercent: Double?, val memPercent: Double?, val command: String)

/**
 * One shell script's worth of vitals, and the parser that reads it back.
 *
 * It is one script rather than a dozen commands because every round trip is a
 * second of somebody's life on a link that may be a train window: the whole
 * thing is sent once, the server does the waiting, and one reply comes back.
 * The two-sample readings — CPU time, interface counters — are why the script
 * sleeps in the middle; a rate needs two points and only the server can take
 * them a known distance apart.
 *
 * The reply is cut into sections by marker lines, which also means anything a
 * chatty profile printed before the first marker is thrown away rather than
 * parsed as data.
 */
object ServerProbe {

    /** How far apart the script takes its two samples; the divisor for every rate. */
    private const val SAMPLE_SECONDS = 1.0

    /** Enough rows to be a "top", not so many that a busy machine floods the channel. */
    private const val TOP_PROCESSES = 10

    /**
     * The script, POSIX sh, run through the session's exec channel.
     *
     * Linux answers out of /proc, which is exact and costs nothing. Everything
     * else is asked in BSD dialect — sysctl for the counters, vm_stat for the
     * page ledger, netstat for the interfaces — and whatever a particular BSD
     * does not have simply produces an empty section.
     */
    val SCRIPT: String = """
        echo "@@os"
        uname -s 2>/dev/null || echo unknown
        echo "@@hostname"
        hostname 2>/dev/null || uname -n 2>/dev/null || true
        case `uname -s 2>/dev/null` in
        Linux)
          echo "@@nproc"
          nproc 2>/dev/null || grep -c '^processor' /proc/cpuinfo 2>/dev/null || true
          echo "@@loadavg"
          cat /proc/loadavg 2>/dev/null || true
          echo "@@meminfo"
          cat /proc/meminfo 2>/dev/null || true
          echo "@@stat1"
          cat /proc/stat 2>/dev/null || true
          echo "@@net1"
          cat /proc/net/dev 2>/dev/null || true
          sleep 1
          echo "@@stat2"
          cat /proc/stat 2>/dev/null || true
          echo "@@net2"
          cat /proc/net/dev 2>/dev/null || true
          echo "@@uptime"
          cat /proc/uptime 2>/dev/null || true
          echo "@@thermal"
          cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null || true
          ;;
        *)
          echo "@@nproc"
          sysctl -n hw.ncpu 2>/dev/null || true
          echo "@@loadavg"
          sysctl -n vm.loadavg 2>/dev/null || true
          echo "@@uptimecmd"
          uptime 2>/dev/null || true
          echo "@@boottime"
          sysctl -n kern.boottime 2>/dev/null || true
          echo "@@now"
          date +%s 2>/dev/null || true
          echo "@@memtotal"
          sysctl -n hw.memsize 2>/dev/null || sysctl -n hw.physmem 2>/dev/null || true
          echo "@@vmstat"
          vm_stat 2>/dev/null || true
          echo "@@vmsysctl"
          sysctl hw.pagesize vm.stats.vm.v_page_count vm.stats.vm.v_free_count vm.stats.vm.v_inactive_count vm.stats.vm.v_cache_count 2>/dev/null || true
          echo "@@swap"
          sysctl -n vm.swapusage 2>/dev/null || true
          echo "@@net1"
          netstat -ibn 2>/dev/null || true
          sleep 1
          echo "@@net2"
          netstat -ibn 2>/dev/null || true
          ;;
        esac
        echo "@@df"
        df -P -k 2>/dev/null || df -k 2>/dev/null || df 2>/dev/null || true
        echo "@@ps"
        { ps -eo pid,pcpu,pmem,comm --sort=-pcpu 2>/dev/null ||
          ps -Ao pid,pcpu,pmem,comm -r 2>/dev/null ||
          ps -o pid,pcpu,pmem,comm 2>/dev/null; } | head -200
        echo "@@end"
    """.trimIndent()

    fun parse(output: String): ServerStatus {
        val s = sections(output)
        val os = s["os"]?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val linux = os.equals("Linux", ignoreCase = true)

        val cpu = if (linux) cpuFromProcStat(s["stat1"].orEmpty(), s["stat2"].orEmpty()) else CpuSample(null, emptyList())
        val net = if (linux) {
            rates(procNetDev(s["net1"].orEmpty()), procNetDev(s["net2"].orEmpty()))
        } else {
            rates(netstatCounters(s["net1"].orEmpty()), netstatCounters(s["net2"].orEmpty()))
        }
        val memory = if (linux) memFromMeminfo(s["meminfo"].orEmpty()) else memFromBsd(s)
        val swap = if (linux) swapFromMeminfo(s["meminfo"].orEmpty()) else swapFromSysctl(s["swap"]?.firstOrNull())

        return ServerStatus(
            hostname = s["hostname"]?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() },
            os = os,
            uptimeSeconds = if (linux) uptimeFromProc(s["uptime"]?.firstOrNull()) else uptimeFromBoottime(s),
            load = parseLoad(s["loadavg"]?.firstOrNull()) ?: loadFromUptime(s["uptimecmd"]?.firstOrNull()),
            cpuCount = s["nproc"]?.firstOrNull()?.trim()?.toIntOrNull()?.takeIf { it > 0 },
            cpu = cpu.overall,
            cores = cpu.cores,
            memory = memory,
            swap = swap,
            disks = parseDf(s["df"].orEmpty()),
            interfaces = net,
            temperatureC = parseThermal(s["thermal"]?.firstOrNull()),
            processes = parsePs(s["ps"].orEmpty()),
        )
    }

    // ---- sections ---------------------------------------------------------

    private val MARKER = Regex("^@@[a-z0-9]+$")

    private fun sections(output: String): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        var current: MutableList<String>? = null
        for (line in output.lineSequence()) {
            val trimmed = line.trim()
            if (MARKER.matches(trimmed)) {
                current = out.getOrPut(trimmed.removePrefix("@@")) { mutableListOf() }
            } else {
                current?.add(line)
            }
        }
        return out
    }

    // ---- CPU --------------------------------------------------------------

    private class CpuSample(val overall: Double?, val cores: List<Double>)

    /**
     * The share of the second that was not idle, from the two /proc/stat samples.
     *
     * iowait counts as idle here: a core waiting on a disk is not doing work,
     * and calling it busy is how a machine that is merely slow ends up looking
     * pegged.
     */
    private fun cpuFromProcStat(first: List<String>, second: List<String>): CpuSample {
        val a = statLines(first)
        val b = statLines(second)
        val overall = busy(a["cpu"], b["cpu"])
        val cores = a.keys
            .filter { it.length > 3 && it.startsWith("cpu") }
            .sortedBy { it.drop(3).toIntOrNull() ?: Int.MAX_VALUE }
            .mapNotNull { busy(a[it], b[it]) }
        return CpuSample(overall, cores)
    }

    private fun statLines(lines: List<String>): Map<String, LongArray> =
        lines.mapNotNull { line ->
            val f = line.trim().split(WHITESPACE)
            if (f.size < 5 || !f[0].startsWith("cpu")) return@mapNotNull null
            val times = f.drop(1).mapNotNull { it.toLongOrNull() }
            if (times.size < 4) null else f[0] to times.toLongArray()
        }.toMap()

    private fun busy(a: LongArray?, b: LongArray?): Double? {
        if (a == null || b == null) return null
        val total = b.sum() - a.sum()
        val idle = (b.at(3) + b.at(4)) - (a.at(3) + a.at(4))
        if (total <= 0) return null
        return ((total - idle).toDouble() / total).coerceIn(0.0, 1.0)
    }

    private fun LongArray.at(i: Int): Long = if (i < size) this[i] else 0

    // ---- memory -----------------------------------------------------------

    /**
     * Used memory as the kernel itself defines "unavailable".
     *
     * MemAvailable is the honest number — it already accounts for the cache the
     * kernel would hand back under pressure — so used is total minus that.
     * Kernels too old to publish it fall back to the arithmetic it replaced.
     */
    private fun memFromMeminfo(lines: List<String>): MemoryUse? {
        val kb = meminfo(lines)
        val total = kb["MemTotal"] ?: return null
        if (total <= 0) return null
        val cached = listOfNotNull(kb["Cached"], kb["Buffers"], kb["SReclaimable"]).takeIf { it.isNotEmpty() }?.sum()
        val available = kb["MemAvailable"]
        val used = when {
            available != null -> total - available
            cached != null -> total - (kb["MemFree"] ?: 0) - cached
            else -> return null
        }
        return MemoryUse(total * 1024, used.coerceAtLeast(0) * 1024, cached?.times(1024))
    }

    private fun swapFromMeminfo(lines: List<String>): MemoryUse? {
        val kb = meminfo(lines)
        val total = kb["SwapTotal"] ?: return null
        if (total <= 0) return null
        val free = kb["SwapFree"] ?: return null
        return MemoryUse(total * 1024, (total - free).coerceAtLeast(0) * 1024)
    }

    private fun meminfo(lines: List<String>): Map<String, Long> = lines.mapNotNull { line ->
        val name = line.substringBefore(':', "").trim()
        if (name.isEmpty() || name == line.trim()) return@mapNotNull null
        val value = line.substringAfter(':').trim().split(WHITESPACE).firstOrNull()?.toLongOrNull()
        value?.let { name to it }
    }.toMap()

    /**
     * macOS and FreeBSD, from whichever page ledger the machine has.
     *
     * "Used" here is what is not free and not file cache — roughly what
     * Activity Monitor calls memory used — because the alternative, counting
     * the cache as used, reports every Unix box as nearly full.
     */
    private fun memFromBsd(s: Map<String, List<String>>): MemoryUse? {
        val declared = s["memtotal"]?.firstOrNull()?.trim()?.toLongOrNull()?.takeIf { it > 0 }
        val vmStat = s["vmstat"].orEmpty()
        if (vmStat.isNotEmpty()) {
            val page = Regex("page size of (\\d+) bytes").find(vmStat.joinToString("\n"))?.groupValues?.get(1)?.toLongOrNull()
            val pages = vmStatPages(vmStat)
            val total = declared
            if (page != null && total != null && pages.isNotEmpty()) {
                val free = ((pages["pages free"] ?: 0L) + (pages["pages speculative"] ?: 0L)) * page
                val cached = pages["file-backed pages"]?.times(page)
                val used = (total - free - (cached ?: 0L)).coerceAtLeast(0)
                return MemoryUse(total, used, cached)
            }
        }
        val sysctl = namedSysctl(s["vmsysctl"].orEmpty())
        val page = sysctl["hw.pagesize"] ?: return null
        val total = declared ?: sysctl["vm.stats.vm.v_page_count"]?.times(page) ?: return null
        val free = (sysctl["vm.stats.vm.v_free_count"] ?: return null) * page
        val cached = listOfNotNull(sysctl["vm.stats.vm.v_inactive_count"], sysctl["vm.stats.vm.v_cache_count"])
            .takeIf { it.isNotEmpty() }?.sum()?.times(page)
        return MemoryUse(total, (total - free - (cached ?: 0L)).coerceAtLeast(0), cached)
    }

    /** `Pages free:                 12345.` → "pages free" to 12345. */
    private fun vmStatPages(lines: List<String>): Map<String, Long> = lines.mapNotNull { line ->
        val label = line.substringBefore(':', "").trim().lowercase()
        if (label.isEmpty() || label == line.trim().lowercase()) return@mapNotNull null
        val value = line.substringAfter(':').trim().trimEnd('.').toLongOrNull() ?: return@mapNotNull null
        label to value
    }.toMap()

    private fun namedSysctl(lines: List<String>): Map<String, Long> = lines.mapNotNull { line ->
        val name = line.substringBefore(':', "").trim()
        if (name.isEmpty() || name == line.trim()) return@mapNotNull null
        line.substringAfter(':').trim().toLongOrNull()?.let { name to it }
    }.toMap()

    /** `total = 2048.00M  used = 1024.00M  free = 1024.00M`. */
    private fun swapFromSysctl(line: String?): MemoryUse? {
        if (line.isNullOrBlank()) return null
        val fields = Regex("(total|used)\\s*=\\s*([0-9.]+)([KMGT]?)").findAll(line)
            .associate { it.groupValues[1] to swapBytes(it.groupValues[2], it.groupValues[3]) }
        val total = fields["total"] ?: return null
        if (total <= 0) return null
        return MemoryUse(total, fields["used"] ?: return null)
    }

    private fun swapBytes(number: String, unit: String): Long {
        val scale = when (unit) {
            "K" -> 1L shl 10
            "M" -> 1L shl 20
            "G" -> 1L shl 30
            "T" -> 1L shl 40
            else -> 1L
        }
        return ((number.toDoubleOrNull() ?: 0.0) * scale).toLong()
    }

    // ---- load, uptime, temperature ---------------------------------------

    /** `/proc/loadavg` and `sysctl vm.loadavg` differ only in the braces around it. */
    private fun parseLoad(line: String?): LoadAverage? {
        if (line.isNullOrBlank()) return null
        val n = line.replace("{", " ").replace("}", " ").trim().split(WHITESPACE)
            .mapNotNull { it.toDoubleOrNull() }
        return if (n.size >= 3) LoadAverage(n[0], n[1], n[2]) else null
    }

    /** `… load averages: 1.23 1.45 1.67` — the BSD fallback when vm.loadavg is missing. */
    private fun loadFromUptime(line: String?): LoadAverage? {
        if (line.isNullOrBlank()) return null
        val tail = line.substringAfter("load average", "").substringAfter(':', "")
        return parseLoad(tail.replace(',', ' '))
    }

    private fun uptimeFromProc(line: String?): Long? =
        line?.trim()?.split(WHITESPACE)?.firstOrNull()?.toDoubleOrNull()?.toLong()?.takeIf { it >= 0 }

    /** `{ sec = 1757000000, usec = 0 } Mon Sep …` against the clock the same script read. */
    private fun uptimeFromBoottime(s: Map<String, List<String>>): Long? {
        val boot = Regex("sec\\s*=\\s*(\\d+)").find(s["boottime"]?.firstOrNull().orEmpty())
            ?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val now = s["now"]?.firstOrNull()?.trim()?.toLongOrNull() ?: return null
        return (now - boot).takeIf { it >= 0 }
    }

    /**
     * Thermal zone 0, which reports millidegrees on Linux but plain degrees on
     * a few embedded kernels. A reading outside anything a running machine
     * could survive is dropped rather than shown.
     */
    private fun parseThermal(line: String?): Double? {
        val raw = line?.trim()?.toDoubleOrNull() ?: return null
        val celsius = if (raw > 200) raw / 1000.0 else raw
        return celsius.takeIf { it > -50 && it < 150 }
    }

    // ---- disks ------------------------------------------------------------

    private fun parseDf(lines: List<String>): List<DiskUse> {
        var blockBytes = 1024L
        val seen = LinkedHashMap<String, DiskUse>()
        for (line in lines) {
            if (line.isBlank()) continue
            val f = line.trim().split(WHITESPACE)
            if (f.size < 6) continue
            if (f[0].equals("Filesystem", ignoreCase = true)) {
                // "1024-blocks", "512-blocks", "1K-blocks": the header is the
                // only place the unit is written down, and df is not always
                // asked in kilobytes.
                blockBytes = when {
                    f[1].startsWith("512") -> 512L
                    f[1].startsWith("1024") || f[1].startsWith("1K", ignoreCase = true) -> 1024L
                    else -> blockBytes
                }
                continue
            }
            val total = f[1].toLongOrNull() ?: continue
            val used = f[2].toLongOrNull() ?: continue
            // -P guarantees the mount point is last, and it may contain spaces.
            val mount = f.drop(5).joinToString(" ")
            if (!realMount(f[0], mount) || total <= 0) continue
            seen.getOrPut(mount) { DiskUse(f[0], mount, total * blockBytes, used * blockBytes) }
        }
        return seen.values.sortedWith(compareByDescending<DiskUse> { it.mount == "/" }.thenBy { it.mount })
    }

    private val PSEUDO_FILESYSTEMS = setOf(
        "tmpfs", "devtmpfs", "devfs", "udev", "none", "squashfs", "efivarfs",
        "ramfs", "shm", "cgroup", "cgroup2", "sysfs", "proc", "map",
    )

    /**
     * Mounts worth a row. A phone's screen has space for the disks somebody
     * might run out of, not for the kernel's own bookkeeping — and a snap loop
     * mount is permanently 100% full, which reads as an emergency every time.
     */
    private fun realMount(filesystem: String, mount: String): Boolean {
        if (filesystem.lowercase() in PSEUDO_FILESYSTEMS) return false
        if (mount.startsWith("/snap") || mount.startsWith("/dev") || mount.startsWith("/proc") ||
            mount.startsWith("/sys") || mount.startsWith("/run")
        ) return false
        // macOS splits the system across read-only helper volumes; only the one
        // holding everybody's files means anything here.
        if (mount.startsWith("/System/Volumes/") && mount != "/System/Volumes/Data") return false
        return true
    }

    // ---- network ----------------------------------------------------------

    private fun rates(first: Map<String, Pair<Long, Long>>, second: Map<String, Pair<Long, Long>>): List<NetRate> =
        second.mapNotNull { (name, b) ->
            val a = first[name] ?: return@mapNotNull null
            val rx = b.first - a.first
            val tx = b.second - a.second
            // A counter that went backwards wrapped, and there is no honest
            // rate to report for that second.
            if (rx < 0 || tx < 0) return@mapNotNull null
            NetRate(name, (rx / SAMPLE_SECONDS).toLong(), (tx / SAMPLE_SECONDS).toLong())
        }.sortedByDescending { it.rxPerSecond + it.txPerSecond }

    private fun procNetDev(lines: List<String>): Map<String, Pair<Long, Long>> = lines.mapNotNull { line ->
        if (!line.contains(':')) return@mapNotNull null
        val name = line.substringBefore(':').trim()
        if (name.isEmpty() || isLoopback(name)) return@mapNotNull null
        val f = line.substringAfter(':').trim().split(WHITESPACE).mapNotNull { it.toLongOrNull() }
        if (f.size < 9) null else name to (f[0] to f[8])
    }.toMap()

    /**
     * `netstat -ibn`, where the link-level row is the one with the byte totals.
     *
     * Counted from the right, because neither end is stable: loopback has no
     * address column where a NIC does, and FreeBSD slips an `Idrop` in that
     * macOS does not have. What both agree on is the tail — Ibytes, Opkts,
     * Oerrs, Obytes, Coll — so the header is read for how far in from the end
     * the two byte columns sit, and the rows are indexed the same way.
     */
    private fun netstatCounters(lines: List<String>): Map<String, Pair<Long, Long>> {
        var rxFromEnd = 5
        var txFromEnd = 2
        val out = LinkedHashMap<String, Pair<Long, Long>>()
        for (line in lines) {
            val f = line.trim().split(WHITESPACE)
            if (f.size < 9) continue
            if (f[0].equals("Name", ignoreCase = true)) {
                val rx = f.indexOfLast { it.equals("Ibytes", ignoreCase = true) }
                val tx = f.indexOfLast { it.equals("Obytes", ignoreCase = true) }
                if (rx >= 0 && tx >= 0) {
                    rxFromEnd = f.size - rx
                    txFromEnd = f.size - tx
                }
                continue
            }
            if (f.none { it.startsWith("<Link#") }) continue
            val name = f[0].trimEnd('*')
            if (isLoopback(name) || name in out) continue
            val rx = f.getOrNull(f.size - rxFromEnd)?.toLongOrNull() ?: continue
            val tx = f.getOrNull(f.size - txFromEnd)?.toLongOrNull() ?: continue
            out[name] = rx to tx
        }
        return out
    }

    private fun isLoopback(name: String) = name == "lo" || name.startsWith("lo0")

    // ---- processes --------------------------------------------------------

    /**
     * The heaviest processes, sorted here rather than trusted from the server.
     *
     * `--sort` is a GNU extension: busybox and the BSDs fall through to a `ps`
     * that lists in whatever order it likes, so the ordering is redone on this
     * side and both cases arrive the same way.
     */
    private fun parsePs(lines: List<String>): List<ProcessUse> {
        val header = lines.firstOrNull { it.isNotBlank() } ?: return emptyList()
        if (!header.contains("%CPU", ignoreCase = true)) return emptyList()
        val seen = LinkedHashMap<Int, ProcessUse>()
        for (line in lines.drop(lines.indexOf(header) + 1)) {
            if (line.isBlank()) continue
            val f = line.trim().split(WHITESPACE, limit = 4)
            if (f.size < 4) continue
            val pid = f[0].toIntOrNull() ?: continue
            seen.getOrPut(pid) {
                ProcessUse(pid, f[1].toDoubleOrNull(), f[2].toDoubleOrNull(), f[3].trim())
            }
        }
        return seen.values
            .sortedByDescending { it.cpuPercent ?: -1.0 }
            .take(TOP_PROCESSES)
    }

    private val WHITESPACE = Regex("\\s+")
}

/**
 * A way to ask one session's machine a question, for as long as somebody is
 * asking.
 *
 * An SSH session already has a connection, so a command costs one channel and
 * no second login — which is what makes a five-second refresh reasonable at
 * all. A Mosh session has left SSH behind and has no channel to lend, so one
 * connection of its own is opened and then held: reconnecting every five
 * seconds would cost more than the answers are worth.
 *
 * One at a time. Everything on a status pane shares this, and two commands
 * racing would otherwise be two connections dialled for the same session — and
 * a server asked one thing at a time is a server that never sees a burst from
 * a phone somebody left on a table.
 */
class SessionExec(
    private val sessions: SessionManager,
    private val session: TerminalSession,
) {
    private var borrowed: TerminalSession? = null
    private val lock = Mutex()

    /** True once commands are going over a connection of our own rather than the session's. */
    var ownConnection: Boolean = false
        private set

    suspend fun run(command: String): String = lock.withLock {
        withContext(Dispatchers.IO) {
            borrowed?.let { return@withContext it.core.execLive(command) }
            val direct = runCatching { session.core.execLive(command) }
            direct.getOrNull()?.let { return@withContext it }
            val host = session.host ?: throw direct.exceptionOrNull() ?: IllegalStateException("no host")
            val own = sessions.connectHeadless(host)
            borrowed = own
            ownConnection = true
            own.core.execLive(command)
        }
    }

    /** Nobody is asking any more; anything opened for this is closed. */
    fun close() {
        borrowed?.destroy()
        borrowed = null
    }
}

/** A live source of vitals for one session. */
class ServerStatusProbe(private val exec: SessionExec) {

    val ownConnection: Boolean get() = exec.ownConnection

    suspend fun read(): ServerStatus = ServerProbe.parse(exec.run(ServerProbe.SCRIPT))
}
