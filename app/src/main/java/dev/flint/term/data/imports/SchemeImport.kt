package dev.flint.term.data.imports

import dev.flint.term.data.TermScheme
import org.json.JSONArray
import org.json.JSONObject

/**
 * A color scheme as another terminal wrote it down.
 *
 * Five formats, told apart by content rather than by extension, because these
 * files have every extension and none: a Ghostty theme has no suffix, an
 * Alacritty one is `.toml` or `.yml`, Windows Terminal's is a fragment of
 * `settings.json`, iTerm2's is a plist that calls itself `.itermcolors`, and
 * an Xresources scheme is whatever the author named it. Each parser reads the
 * text into the same [Draft]; the first one that ends up with sixteen ANSI
 * colors, a foreground and a background wins. Cursor and selection are the
 * two roles a file is allowed to leave out, since half of them do.
 *
 * Everything here is plain Kotlin — no XML library, no TOML library — so the
 * same code runs under the unit tests and on the phone.
 */
object SchemeImport {
    enum class Format(val label: String) {
        WINDOWS_TERMINAL("Windows Terminal"),
        ITERM2("iTerm2"),
        ALACRITTY("Alacritty"),
        GHOSTTY("Ghostty"),
        XRESOURCES("Xresources"),
    }

    data class Parsed(val scheme: TermScheme, val format: Format)

    /**
     * The scheme in [text], as a new custom scheme named after the file when
     * the file does not name itself; or the reason there is none.
     *
     * A file that is recognisably one of the formats but short of colors gets
     * told what it lacks, which is what someone hand-writing one wants to hear.
     */
    fun parse(text: String, fallbackName: String? = null): Result<Parsed> {
        val drafts = Format.entries.mapNotNull { f -> draft(f, text)?.let { f to it } }
        drafts.firstOrNull { it.second.complete }?.let { (format, d) ->
            val name = d.name?.takeIf { it.isNotBlank() } ?: fallbackName?.takeIf { it.isNotBlank() } ?: "Imported scheme"
            return Result.success(Parsed(d.toScheme(name.trim()), format))
        }
        drafts.firstOrNull()?.let { (format, d) ->
            return Result.failure(IllegalArgumentException("Looks like a ${format.label} scheme, but it is missing ${d.missing()}."))
        }
        return Result.failure(IllegalArgumentException("Not a color scheme in a format this app knows: Ghostty, Alacritty, Windows Terminal, iTerm2 or Xresources."))
    }

    private fun draft(format: Format, text: String): Draft? = runCatching {
        when (format) {
            Format.WINDOWS_TERMINAL -> windowsTerminal(text)
            Format.ITERM2 -> iterm2(text)
            Format.ALACRITTY -> alacritty(text)
            Format.GHOSTTY -> ghostty(text)
            Format.XRESOURCES -> xresources(text)
        }
    }.getOrNull()

    /** What one parser managed to pull out; [complete] once it can be a scheme. */
    class Draft {
        val ansi = HashMap<Int, UInt>()
        var foreground: UInt? = null
        var background: UInt? = null
        var cursor: UInt? = null
        var selection: UInt? = null
        var name: String? = null

        /** Whether anything at all was read: a parser that found nothing did not recognise the file. */
        val any: Boolean get() = ansi.isNotEmpty() || foreground != null || background != null
        val complete: Boolean get() = (0 until 16).all { it in ansi } && foreground != null && background != null

        fun missing(): String {
            val gaps = mutableListOf<String>()
            val colors = (0 until 16).filter { it !in ansi }
            if (colors.isNotEmpty()) gaps += if (colors.size == 16) "the 16 ANSI colors" else "ANSI color${if (colors.size > 1) "s" else ""} ${colors.joinToString(", ")}"
            if (foreground == null) gaps += "the foreground"
            if (background == null) gaps += "the background"
            return gaps.joinToString(" and ")
        }

        fun toScheme(name: String): TermScheme {
            val fg = foreground!!
            val bg = background!!
            return TermScheme(
                id = TermScheme.newId(), name = name, ansi = (0 until 16).map { ansi.getValue(it) },
                foreground = fg, background = bg,
                cursor = cursor ?: fg,
                // A quarter of the way from the background to the text: visible on any scheme, loud on none.
                selection = selection ?: TermScheme.mix(bg, fg, 0.25),
                custom = true,
            )
        }
    }

    private val ANSI_NAMES = listOf("black", "red", "green", "yellow", "blue", "magenta", "cyan", "white")

    // ---- Ghostty ------------------------------------------------------------

    /** `palette = 3=#rrggbb`, `background = #rrggbb`, and so on, one per line. */
    fun ghostty(text: String): Draft? {
        val d = Draft()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq < 0) continue
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim()
            when (key) {
                "palette" -> {
                    val (n, c) = value.split('=', limit = 2).takeIf { it.size == 2 } ?: continue
                    val i = n.trim().toIntOrNull() ?: continue
                    color(c)?.let { if (i in 0..15) d.ansi[i] = it }
                }
                "background" -> d.background = color(value)
                "foreground" -> d.foreground = color(value)
                "cursor-color" -> d.cursor = color(value)
                "selection-background" -> d.selection = color(value)
            }
        }
        return d.takeIf { it.any }
    }

    // ---- Alacritty ----------------------------------------------------------

    /**
     * Alacritty's `[colors.primary]` / `[colors.normal]` / `[colors.bright]`
     * TOML, and the older YAML with the same names nested by indentation. Both
     * are flattened to `colors.normal.red = …` first, which is all the TOML or
     * YAML either needs: no strings with escapes, no arrays, no anchors that
     * matter. Inline TOML tables (`primary = { background = "…" }`) are
     * flattened too, since some theme files are written that way.
     */
    fun alacritty(text: String): Draft? {
        val flat = HashMap<String, String>()
        val yamlStack = ArrayList<Pair<Int, String>>() // (indent, key) of the enclosing YAML maps
        var tomlSection = ""
        for (raw in text.lineSequence()) {
            val noComment = stripComment(raw)
            val line = noComment.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                tomlSection = line.substring(1, line.length - 1).trim()
                continue
            }
            val eq = line.indexOf('=')
            val colon = line.indexOf(':')
            if (eq > 0 && (colon < 0 || eq < colon)) {
                val key = line.substring(0, eq).trim()
                val value = line.substring(eq + 1).trim()
                val path = if (tomlSection.isEmpty()) key else "$tomlSection.$key"
                if (value.startsWith("{") && value.endsWith("}")) {
                    value.substring(1, value.length - 1).split(',').forEach { pair ->
                        val (k, v) = pair.split('=', limit = 2).map { it.trim() }.takeIf { it.size == 2 } ?: return@forEach
                        flat["$path.$k"] = v
                    }
                } else {
                    flat[path] = value
                }
            } else if (colon > 0) {
                val indent = noComment.indexOfFirst { !it.isWhitespace() }
                val key = line.substring(0, colon).trim()
                val value = line.substring(colon + 1).trim().removePrefix("&primary").trim()
                while (yamlStack.isNotEmpty() && yamlStack.last().first >= indent) yamlStack.removeAt(yamlStack.size - 1)
                if (value.isEmpty() || value.startsWith("&")) {
                    yamlStack += indent to key
                } else {
                    flat[(yamlStack.map { it.second } + key).joinToString(".")] = value
                }
            }
        }
        if (flat.keys.none { it.contains("primary.") || it.contains("normal.") }) return null
        val d = Draft()
        // `cursor.cursor` must not be satisfied by `vi_mode_cursor.cursor`, hence the dot.
        fun get(tail: String): UInt? = flat.entries.firstOrNull { it.key == tail || it.key.endsWith(".$tail") }?.value?.let(::color)
        ANSI_NAMES.forEachIndexed { i, n ->
            get("normal.$n")?.let { d.ansi[i] = it }
            get("bright.$n")?.let { d.ansi[i + 8] = it }
        }
        d.foreground = get("primary.foreground")
        d.background = get("primary.background")
        d.cursor = get("cursor.cursor")
        d.selection = get("selection.background")
        return d.takeIf { it.any }
    }

    private fun stripComment(line: String): String {
        var quote: Char? = null
        for ((i, ch) in line.withIndex()) {
            when {
                quote != null -> if (ch == quote) quote = null
                ch == '\'' || ch == '"' -> quote = ch
                ch == '#' && (i == 0 || line[i - 1].isWhitespace()) -> return line.substring(0, i)
            }
        }
        return line
    }

    // ---- Windows Terminal ---------------------------------------------------

    /** One scheme object, or a `settings.json` fragment whose `schemes` list starts with one. */
    fun windowsTerminal(text: String): Draft? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return null
        val root: JSONObject = when {
            trimmed.startsWith("[") -> JSONArray(trimmed).optJSONObject(0) ?: return null
            else -> JSONObject(trimmed)
        }
        val obj = root.optJSONArray("schemes")?.optJSONObject(0) ?: root
        if (!obj.has("black") && !obj.has("background")) return null
        val d = Draft()
        fun get(key: String): UInt? = obj.optString(key).takeIf { it.isNotBlank() }?.let(::color)
        ANSI_NAMES.forEachIndexed { i, n ->
            // Windows Terminal calls magenta "purple"; files written by hand use either.
            val names = if (n == "magenta") listOf("purple", "magenta") else listOf(n)
            names.firstNotNullOfOrNull { get(it) }?.let { d.ansi[i] = it }
            names.firstNotNullOfOrNull { get("bright" + it.replaceFirstChar { c -> c.uppercase() }) }?.let { d.ansi[i + 8] = it }
        }
        d.foreground = get("foreground")
        d.background = get("background")
        d.cursor = get("cursorColor")
        d.selection = get("selectionBackground")
        d.name = obj.optString("name").takeIf { it.isNotBlank() }
        return d.takeIf { it.any }
    }

    // ---- iTerm2 -------------------------------------------------------------

    private val PLIST_ENTRY = Regex("""<key>([^<]+)</key>\s*<dict>(.*?)</dict>""", RegexOption.DOT_MATCHES_ALL)
    private val PLIST_COMPONENT = Regex("""<key>(Red|Green|Blue) Component</key>\s*<(?:real|integer)>([-+0-9.eE]+)</(?:real|integer)>""")

    /**
     * `.itermcolors`: a plist of dicts, one per role, each holding its channels
     * as floats. The shape is regular enough that two regexes read it, which
     * spares the phone an XML parser that would otherwise go looking for
     * Apple's DTD on the network.
     */
    fun iterm2(text: String): Draft? {
        if (!text.contains("<key>")) return null
        val d = Draft()
        for (m in PLIST_ENTRY.findAll(text)) {
            val key = m.groupValues[1].trim()
            val parts = PLIST_COMPONENT.findAll(m.groupValues[2]).associate { it.groupValues[1] to it.groupValues[2].toDoubleOrNull() }
            val r = parts["Red"] ?: continue
            val g = parts["Green"] ?: continue
            val b = parts["Blue"] ?: continue
            fun ch(v: Double) = Math.round(v.coerceIn(0.0, 1.0) * 255).toUInt()
            val c = (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
            when {
                key.startsWith("Ansi ") && key.endsWith(" Color") -> key.removePrefix("Ansi ").removeSuffix(" Color").toIntOrNull()?.let { if (it in 0..15) d.ansi[it] = c }
                key == "Foreground Color" -> d.foreground = c
                key == "Background Color" -> d.background = c
                key == "Cursor Color" -> d.cursor = c
                key == "Selection Color" -> d.selection = c
            }
        }
        return d.takeIf { it.any }
    }

    // ---- Xresources ---------------------------------------------------------

    private val XRES_LINE = Regex("""^\s*(?:[A-Za-z0-9_\-*.?]*[*.])?(color\d{1,2}|foreground|background|cursorColor|highlightColor)\s*:\s*(.+?)\s*$""")
    private val XRES_DEFINE = Regex("""^\s*#define\s+(\S+)\s+(\S+)""")

    /**
     * `*.color0: #…`, `URxvt.color0`, `XTerm*color0` and the bare `color0`,
     * with `#define` names resolved, since many published schemes name their
     * base colors once and refer to them.
     */
    fun xresources(text: String): Draft? {
        val defines = HashMap<String, String>()
        val d = Draft()
        for (raw in text.lineSequence()) {
            if (raw.trimStart().startsWith("!")) continue
            val define = XRES_DEFINE.find(raw)
            if (define != null) { defines[define.groupValues[1]] = define.groupValues[2]; continue }
            val m = XRES_LINE.find(raw) ?: continue
            val key = m.groupValues[1]
            val value = defines[m.groupValues[2]] ?: m.groupValues[2]
            val c = color(value) ?: continue
            when (key) {
                "foreground" -> d.foreground = c
                "background" -> d.background = c
                "cursorColor" -> d.cursor = c
                "highlightColor" -> d.selection = c
                else -> key.removePrefix("color").toIntOrNull()?.let { if (it in 0..15) d.ansi[it] = c }
            }
        }
        return d.takeIf { it.any }
    }

    // ---- colors -------------------------------------------------------------

    private val RGB_SLASH = Regex("""^rgb:([0-9a-fA-F]{1,4})/([0-9a-fA-F]{1,4})/([0-9a-fA-F]{1,4})$""")

    /** `#rrggbb`, `0xrrggbb`, bare `rrggbb`, `#rgb`, and X11's `rgb:rr/gg/bb`; quotes tolerated. */
    fun color(raw: String): UInt? {
        val s = raw.trim().trim('"', '\'', ',').trim()
        RGB_SLASH.find(s)?.let { m ->
            // Each channel is 1–4 hex digits scaled to 16 bits; keep the top byte.
            fun ch(h: String) = (h.toInt(16) shl (4 * (4 - h.length)) shr 8).toUInt()
            return (ch(m.groupValues[1]) shl 16) or (ch(m.groupValues[2]) shl 8) or ch(m.groupValues[3])
        }
        val hex = when {
            s.startsWith("#") -> s.substring(1)
            s.startsWith("0x") || s.startsWith("0X") -> s.substring(2)
            else -> s
        }
        return when (hex.length) {
            6 -> hex.toUIntOrNull(16)
            3 -> hex.toUIntOrNull(16)?.let { v ->
                val r = (v shr 8) and 0xFu; val g = (v shr 4) and 0xFu; val b = v and 0xFu
                (r * 0x11u shl 16) or (g * 0x11u shl 8) or (b * 0x11u)
            }
            else -> null
        }
    }
}
