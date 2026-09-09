package dev.flint.term.session

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The buffer as plain text, on its way out of the app.
 *
 * Search could already look through the scrollback; nothing could get it out.
 * What leaves here is what a person would paste into an issue — the lines in
 * the order they were printed, no escape sequences, no colors, and none of the
 * empty screen that sits under the last command's output.
 */
object Scrollback {
    /**
     * Every line, oldest first, with the blank tail cut off.
     *
     * Blank lines *between* output are the shape of what ran and are kept; the
     * ones after it are only the rest of the screen the cursor never reached.
     */
    fun text(lines: List<String>): String =
        lines.map { it.trimEnd() }.dropLastWhile { it.isEmpty() }.joinToString("\n")

    /** What the file is called: which session, and when it was taken. */
    fun fileName(label: String, at: Long = System.currentTimeMillis()): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(at))
        val name = label.map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '-' }
            .joinToString("")
            .trim('-')
            .take(40)
            .ifBlank { "session" }
        return "scrollback-$name-$stamp.txt"
    }

    /**
     * Write [lines] where the file provider can reach them, and hand the file
     * back.
     *
     * A file rather than an intent extra because a full scrollback is megabytes
     * and a Binder transaction is a megabyte for the whole process: putting the
     * text in the intent would kill the app on exactly the buffers worth
     * sharing. Older files are swept first — the app that was handed one may
     * still have been reading it when the sheet closed, so nothing is deleted
     * on the way out.
     */
    fun write(context: Context, label: String, lines: List<String>, at: Long = System.currentTimeMillis()): File {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { if (at - it.lastModified() > KEEP_MS) it.delete() }
        return File(dir, fileName(label, at)).apply { writeText(text(lines)) }
    }

    /** Named in `file_paths.xml`; the provider hands out nothing else from the cache. */
    private const val DIR = "scrollback"

    /** Long enough for the receiving app to have finished with it. */
    private const val KEEP_MS = 60 * 60 * 1000L
}
