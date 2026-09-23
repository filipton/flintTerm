package dev.flint.term.session

import android.content.Context
import android.util.Log
import dev.flint.term.data.RecordingFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.io.RandomAccessFile
import java.io.Writer
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * How a session's output is turned into a file.
 *
 * The formatting is the part that is easy to get wrong — one unescaped control
 * character makes a whole cast unplayable — so it lives here, as functions over
 * strings that can be tested without a terminal anywhere near them.
 */
object SessionLog {
    /** What the core asks every server for, and so what a cast should claim. */
    const val TERM = "xterm-256color"

    private const val ESC = '\u001B'
    private const val BEL = '\u0007'

    /**
     * [text] as a person reads it: escape sequences and the control characters a
     * terminal consumes are dropped, newlines and tabs survive.
     *
     * The same shape as the output watcher in the core (see `watch_output`):
     * CSI runs to its final byte, OSC and the other string sequences run to BEL
     * or ST, and a two-character sequence takes its second character with it.
     */
    fun stripEscapes(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == ESC -> i = skipSequence(text, i)
                c == '\n' || c == '\t' -> { out.append(c); i++ }
                // Carriage returns, bells and backspaces are how a terminal draws,
                // not what it printed; a file keeps only the text.
                c < ' ' || c == '\u007F' -> i++
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /** Index just past the escape sequence starting at [at], which holds ESC. */
    private fun skipSequence(text: String, at: Int): Int {
        val next = text.getOrNull(at + 1) ?: return at + 1
        return when {
            next == '[' -> {
                var j = at + 2
                while (j < text.length && text[j].code !in 0x40..0x7e) j++
                j + 1
            }
            // OSC, DCS, SOS, PM, APC: a string, ended by BEL or ST (ESC \).
            next == ']' || next == 'P' || next == 'X' || next == '^' || next == '_' -> {
                var j = at + 2
                while (j < text.length) {
                    if (text[j] == BEL) return j + 1
                    if (text[j] == ESC && text.getOrNull(j + 1) == '\\') return j + 2
                    j++
                }
                j
            }
            // An intermediate byte means one more character belongs to it ("ESC ( B").
            next in ' '..'/' -> at + 3
            else -> at + 2
        }
    }

    /**
     * The first line of an asciinema v2 cast: the size the recording was made
     * at, when it started, and the terminal it claims to be.
     */
    fun castHeader(cols: Int, rows: Int, startedAt: Long, shell: String = ""): String {
        val env = StringBuilder("{\"TERM\":").append(jsonString(TERM))
        if (shell.isNotBlank()) env.append(",\"SHELL\":").append(jsonString(shell))
        env.append('}')
        return "{\"version\":2,\"width\":$cols,\"height\":$rows," +
            "\"timestamp\":${startedAt / 1000},\"env\":$env}"
    }

    /** One cast event: `[elapsed,"o","…"]`, the seconds counted from the start. */
    fun castEvent(elapsedMillis: Long, text: String): String =
        "[" + String.format(Locale.US, "%.6f", elapsedMillis.coerceAtLeast(0) / 1000.0) +
            ",\"o\"," + jsonString(text) + "]"

    /** [s] as a JSON string, quotes included. */
    private fun jsonString(s: String): String {
        val out = StringBuilder(s.length + 2).append('"')
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c == '\b' -> out.append("\\b")
                c == '\u000C' -> out.append("\\f")
                c < ' ' || c == '\u007F' -> out.append(String.format(Locale.US, "\\u%04x", c.code))
                else -> out.append(c)
            }
        }
        return out.append('"').toString()
    }

    /** `<host>-<yyyyMMdd-HHmmss>.<suffix>`, with anything awkward for a file name replaced. */
    fun fileName(hostLabel: String, at: Long, suffix: String): String {
        // A dot goes too: a name made of them ("..") would be a path, not a file.
        val name = hostLabel.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '-' }
            .joinToString("")
            .take(48)
            .trim('-')
            .ifEmpty { "session" }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(at))
        return "$name-$stamp.$suffix"
    }

    /** One frame of a cast: when it was printed, and what was printed. */
    data class Frame(val atMillis: Long, val text: String)

    /** A cast, read back: the size it was recorded at and every frame in order. */
    data class Cast(val cols: Int, val rows: Int, val frames: List<Frame>) {
        val durationMillis: Long get() = frames.lastOrNull()?.atMillis ?: 0
    }

    /**
     * Read an asciinema v2 cast.
     *
     * Written by hand rather than with a JSON parser because the format is one
     * object per line and only three fields matter — and because a recording
     * with one malformed line is still worth playing up to that line, which is
     * not how a parser that throws would treat it.
     */
    /**
     * The most of a recording worth holding in memory at once.
     *
     * A session left recording in the background grows without limit, and these
     * files reach gigabytes. Reading one whole is an allocation nothing on a
     * phone can serve, so everything that reads a recording reads the end of it.
     */
    const val VIEW_MAX_BYTES = 4L * 1024 * 1024
    const val CAST_MAX_BYTES = 16L * 1024 * 1024

    /** A piece of a recording, and whether there was more of it before this. */
    data class Tail(val text: String, val truncated: Boolean)

    /**
     * The last [maxBytes] of [file], starting at a line boundary.
     *
     * Starting at a newline drops the partial line the cut lands in, and with it
     * any half of a UTF-8 character, so what comes back always decodes.
     */
    fun tail(file: File, maxBytes: Long): Tail {
        val len = file.length()
        if (len <= maxBytes) return Tail(file.readText(), false)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(len - maxBytes)
            val buf = ByteArray(maxBytes.toInt())
            raf.readFully(buf)
            var from = 0
            while (from < buf.size && buf[from] != '\n'.code.toByte()) from++
            if (from < buf.size) from++
            return Tail(String(buf, from, buf.size - from, Charsets.UTF_8), true)
        }
    }

    /**
     * A cast small enough to play: its header line, then the end of it.
     *
     * The header carries the size of the grid, so it is read from the front
     * whatever else is dropped.
     */
    fun castTail(file: File, maxBytes: Long): Tail {
        if (file.length() <= maxBytes) return Tail(file.readText(), false)
        val header = file.bufferedReader().use { it.readLine() }.orEmpty()
        val rest = tail(file, maxBytes)
        return Tail(header + "\n" + rest.text, true)
    }

    fun parseCast(text: String): Cast {
        var cols = 80
        var rows = 24
        val frames = mutableListOf<Frame>()
        for ((index, line) in text.lineSequence().withIndex()) {
            if (line.isBlank()) continue
            if (index == 0 && line.startsWith("{")) {
                cols = numberField(line, "width")?.toInt() ?: cols
                rows = numberField(line, "height")?.toInt() ?: rows
                continue
            }
            if (!line.startsWith("[")) continue
            val comma = line.indexOf(',')
            val seconds = line.substring(1, if (comma > 0) comma else line.length).trim().toDoubleOrNull() ?: continue
            // The payload is the last JSON string on the line: ["o" is the kind.
            val open = line.indexOf('"', line.indexOf(',', comma + 1) + 1)
            if (open < 0) continue
            val payload = jsonStringAt(line, open) ?: continue
            frames += Frame((seconds * 1000).toLong(), payload)
        }
        return Cast(cols.coerceIn(2, 500), rows.coerceIn(1, 200), frames)
    }

    /** The number after `"name":` in a header line, if it is there. */
    private fun numberField(line: String, name: String): Double? {
        val at = line.indexOf("\"$name\"")
        if (at < 0) return null
        val colon = line.indexOf(':', at)
        if (colon < 0) return null
        val digits = line.drop(colon + 1).takeWhile { it.isDigit() || it == '.' || it == '-' || it == ' ' }.trim()
        return digits.toDoubleOrNull()
    }

    /** The JSON string starting at [open] (which holds its opening quote), unescaped. */
    private fun jsonStringAt(line: String, open: Int): String? {
        val out = StringBuilder()
        var i = open + 1
        while (i < line.length) {
            when (val c = line[i]) {
                '"' -> return out.toString()
                '\\' -> {
                    when (val esc = line.getOrNull(i + 1) ?: return null) {
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'u' -> {
                            val hex = line.substring(i + 2, (i + 6).coerceAtMost(line.length))
                            out.append(hex.toIntOrNull(16)?.toChar() ?: ' ')
                            i += 4
                        }
                        else -> out.append(esc)
                    }
                    i++
                }
                else -> out.append(c)
            }
            i++
        }
        return null
    }

    /**
     * Where recordings land: a folder of their own beside the app's other files,
     * on external app storage so a file manager can reach a log worth keeping.
     */
    fun recordingsDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "recordings")
}

/**
 * UTF-8 decoding of a stream that arrives in pieces.
 *
 * The core hands output over in whatever chunks the socket produced, and those
 * cut through the middle of a character as happily as anywhere else. Decoding
 * each chunk on its own turns both halves of an é or a box-drawing line into
 * replacement characters; this holds the unfinished tail back for the next one.
 * One per stream, used from one thread.
 */
class Utf8Chunks {
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    /** The start of a character the last chunk ended in the middle of; at most three bytes. */
    private var carry = EMPTY

    fun decode(bytes: ByteArray): String {
        val input = ByteBuffer.wrap(if (carry.isEmpty()) bytes else carry + bytes)
        val out = CharBuffer.allocate(input.remaining() + 1)
        // Not the end of input: an incomplete sequence at the end is left in
        // the buffer instead of being replaced, and that is the carry.
        decoder.decode(input, out, false)
        carry = if (input.hasRemaining()) ByteArray(input.remaining()).also { input.get(it) } else EMPTY
        return out.flip().toString()
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}

/**
 * One session's recording: the files it writes, and a queue that keeps the disk
 * off the path output takes.
 *
 * It is fed the terminal's own byte stream, so the cast replays with the colors
 * and the timing the session actually had, and the text log is that same stream
 * with the escape sequences taken out. Chunks are written on an IO dispatcher,
 * and a file that cannot be written is given up on rather than allowed to break
 * the session — the terminal must not notice that anyone is recording.
 */
class SessionRecording(
    dir: File,
    hostLabel: String,
    format: RecordingFormat,
    cols: Int,
    rows: Int,
    shell: String = "",
    private val startedAt: Long = System.currentTimeMillis(),
) {
    private val textFile = if (format.text) File(dir, SessionLog.fileName(hostLabel, startedAt, "log")) else null
    private val castFile = if (format.cast) File(dir, SessionLog.fileName(hostLabel, startedAt, "cast")) else null

    /** The files this recording writes, in the order they were named. */
    val files: List<File> = listOfNotNull(textFile, castFile)

    /** Either [text], or [bytes] still to be decoded in stream order. */
    private class Chunk(val at: Long, val text: String?, val bytes: ByteArray?)

    /**
     * Bounded, and full means the output thread waits rather than anything
     * being dropped: a gap in a log is a lie about the session, and a gap in a
     * cast breaks the replay from there on. Nothing but a stalled disk fills
     * this many chunks, and then holding the session back is the lesser harm
     * than memory growing until the app is killed.
     */
    private val chunks = Channel<Chunk>(QUEUE_CHUNKS)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            val utf8 = Utf8Chunks()
            val text = textFile?.let { Sink(it) }
            val cast = castFile?.let { Sink(it) }
            val sinks = listOfNotNull(text, cast)
            // Flushed at most once a second, and within a second of the output
            // stopping: a flush per chunk was a pair of syscalls for every
            // redraw, and a second is all a killed app can lose. The first write
            // after a flush starts the clock, as the store's writes do.
            val written = Channel<Unit>(Channel.CONFLATED)
            val flusher = launch {
                for (tick in written) {
                    delay(FLUSH_MS)
                    synchronized(sinks) { sinks.forEach { it.flush() } }
                }
            }
            try {
                dir.mkdirs()
                cast?.write(SessionLog.castHeader(cols, rows, startedAt, shell) + "\n")
                for (chunk in chunks) {
                    val s = chunk.text ?: utf8.decode(chunk.bytes!!)
                    if (s.isEmpty()) continue
                    synchronized(sinks) {
                        text?.write(SessionLog.stripEscapes(s))
                        // The cast keeps the stream as it came, escape sequences
                        // included: that is what makes it replay as the session
                        // looked rather than as a transcript of it.
                        cast?.write(SessionLog.castEvent(chunk.at - startedAt, s) + "\n")
                    }
                    written.trySend(Unit)
                }
            } finally {
                // Nobody is reading any more, so a waiting append must not be
                // left blocked on a queue that will never drain.
                chunks.cancel()
                flusher.cancel()
                synchronized(sinks) { sinks.forEach { it.close() } }
            }
        }
    }

    /** Bytes on disk so far, for the terminal to say how the recording is going. */
    fun bytes(): Long = files.sumOf { it.length() }

    fun append(text: String) {
        if (text.isEmpty()) return
        chunks.trySendBlocking(Chunk(System.currentTimeMillis(), text, null))
    }

    /**
     * The raw stream, decoded here rather than by the caller so a character
     * split across two chunks comes out whole. [bytes] is kept, not copied.
     */
    fun append(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        chunks.trySendBlocking(Chunk(System.currentTimeMillis(), null, bytes))
    }

    /** Closes the files; nothing is recorded after this. */
    fun finish() {
        chunks.close()
    }

    /** An append-only file that stops trying once the disk has said no. */
    private class Sink(private val file: File) {
        private var out: Writer? = null
        private var broken = false

        fun write(s: String) {
            if (broken) return
            try {
                val w = out ?: FileWriter(file, true).buffered().also { out = it }
                w.write(s)
            } catch (e: Exception) {
                giveUp(e)
            }
        }

        /** A recording is worth having even if the app is killed before anyone stops it. */
        fun flush() {
            if (broken) return
            try {
                out?.flush()
            } catch (e: Exception) {
                giveUp(e)
            }
        }

        private fun giveUp(e: Exception) {
            Log.w("SessionLog", "cannot record to ${file.name}", e)
            broken = true
            close()
        }

        fun close() {
            runCatching { out?.close() }
            out = null
        }
    }

    private companion object {
        const val FLUSH_MS = 1_000L
        const val QUEUE_CHUNKS = 256
    }
}
