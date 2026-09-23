package dev.flint.term.session

import android.content.Context
import dev.flint.term.data.RecordingFormat
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Recordings as the app sees them: what they are called, where they live, and
 * how one is read back.
 *
 * Writing them is the core's job (`recording.rs`), next to the stream: the
 * formats here and there have to agree, and the tests on both sides pin them
 * to the same bytes.
 */
object SessionLog {
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
 * One session's recording, as the app holds it: the files it is written to.
 *
 * The core does the writing, on a thread of its own, from the terminal's own
 * byte stream, so the cast replays with the colors and the timing the session
 * actually had and the text log is that same stream with the escape sequences
 * taken out. Naming the files stays here, beside the screen that lists them.
 */
class SessionRecording(
    dir: File,
    hostLabel: String,
    format: RecordingFormat,
    val startedAt: Long = System.currentTimeMillis(),
) {
    val textFile = if (format.text) File(dir, SessionLog.fileName(hostLabel, startedAt, "log")) else null
    val castFile = if (format.cast) File(dir, SessionLog.fileName(hostLabel, startedAt, "cast")) else null

    /** The files this recording writes, in the order they were named. */
    val files: List<File> = listOfNotNull(textFile, castFile)

    /** Bytes on disk so far, for the terminal to say how the recording is going. */
    fun bytes(): Long = files.sumOf { it.length() }
}
