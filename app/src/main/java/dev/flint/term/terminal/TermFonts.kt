package dev.flint.term.terminal

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.res.ResourcesCompat
import dev.flint.term.R
import java.io.File

/** Terminal font families: three bundled ones plus any TTF/OTF the user imported. */
object TermFonts {
    data class Family(val id: String, val label: String, val ligatures: Boolean, val custom: Boolean = false)
    class Faces(val regular: Typeface, val bold: Typeface, val italic: Typeface, val boldItalic: Typeface)

    const val DEFAULT = "jetbrains"

    val builtin = listOf(
        Family("jetbrains", "JetBrains Mono", ligatures = true),
        Family("fira", "Fira Code", ligatures = true),
        Family("hack", "Hack", ligatures = false),
    )

    private fun dir(context: Context) = File(context.filesDir, "fonts").apply { mkdirs() }

    fun all(context: Context): List<Family> = builtin + dir(context).listFiles().orEmpty()
        .filter { it.extension.lowercase() in setOf("ttf", "otf") }
        .sortedBy { it.name.lowercase() }
        .map { Family("file:" + it.name, it.nameWithoutExtension, ligatures = true, custom = true) }

    fun family(context: Context, id: String): Family = all(context).firstOrNull { it.id == id } ?: builtin.first()

    fun load(context: Context, id: String): Faces {
        fun res(r: Int, fallback: Typeface) = ResourcesCompat.getFont(context, r) ?: fallback
        val mono = Typeface.MONOSPACE
        return when {
            id == "fira" -> {
                val reg = res(R.font.fira_code_regular, mono)
                val b = res(R.font.fira_code_bold, Typeface.create(mono, Typeface.BOLD))
                Faces(reg, b, Typeface.create(reg, Typeface.ITALIC), Typeface.create(b, Typeface.BOLD_ITALIC))
            }
            id == "hack" -> Faces(
                res(R.font.hack_regular, mono), res(R.font.hack_bold, Typeface.create(mono, Typeface.BOLD)),
                res(R.font.hack_italic, Typeface.create(mono, Typeface.ITALIC)), res(R.font.hack_bold_italic, Typeface.create(mono, Typeface.BOLD_ITALIC)),
            )
            id.startsWith("file:") -> {
                val f = File(dir(context), id.removePrefix("file:"))
                val reg = runCatching { Typeface.createFromFile(f) }.getOrNull() ?: return load(context, DEFAULT)
                Faces(reg, Typeface.create(reg, Typeface.BOLD), Typeface.create(reg, Typeface.ITALIC), Typeface.create(reg, Typeface.BOLD_ITALIC))
            }
            else -> Faces(
                res(R.font.jetbrains_mono_regular, mono), res(R.font.jetbrains_mono_bold, Typeface.create(mono, Typeface.BOLD)),
                res(R.font.jetbrains_mono_italic, Typeface.create(mono, Typeface.ITALIC)), res(R.font.jetbrains_mono_bold_italic, Typeface.create(mono, Typeface.BOLD_ITALIC)),
            )
        }
    }

    /** Copy a picked font file in; returns its family id, or null when it is not a usable font. */
    fun import(context: Context, uri: Uri): String? {
        val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) c.getString(i) else null
        } ?: uri.lastPathSegment ?: return null
        val safe = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._ -]"), "_")
        if (safe.substringAfterLast('.', "").lowercase() !in setOf("ttf", "otf")) return null
        val target = File(dir(context), safe)
        context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } } ?: return null
        if (runCatching { Typeface.createFromFile(target) }.getOrNull() == null) { target.delete(); return null }
        return "file:$safe"
    }

    fun delete(context: Context, id: String) {
        if (id.startsWith("file:")) File(dir(context), id.removePrefix("file:")).delete()
    }
}
