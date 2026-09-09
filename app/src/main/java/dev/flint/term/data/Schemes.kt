package dev.flint.term.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * A terminal color scheme: sixteen ANSI colors and the four roles around them,
 * each as 0xRRGGBB.
 *
 * Schemes are known by [id], which is what hosts, groups and settings store.
 * The six the app has always had keep their old enum names as ids so that a
 * store written before the catalog existed reads back unchanged; catalog
 * schemes use a slug of their name; a custom one gets a random id, like a host.
 */
data class TermScheme(
    val id: String,
    val name: String,
    val ansi: List<UInt>,
    val foreground: UInt,
    val background: UInt,
    val cursor: UInt,
    val selection: UInt,
    /** Imported by the user, so it can be renamed, shared and deleted. */
    val custom: Boolean = false,
) {
    /** Whether text is dark on light, judged by the background alone. */
    val isLight: Boolean get() = luminance(background) > 0.5

    /** The scheme in Ghostty's theme format, which is also what the catalog is built from. */
    fun toGhostty(): String = buildString {
        ansi.forEachIndexed { i, c -> append("palette = ").append(i).append("=#").append(hex(c)).append('\n') }
        append("background = #").append(hex(background)).append('\n')
        append("foreground = #").append(hex(foreground)).append('\n')
        append("cursor-color = #").append(hex(cursor)).append('\n')
        append("selection-background = #").append(hex(selection)).append('\n')
    }

    companion object {
        fun hex(c: UInt): String = (c and 0xFFFFFFu).toString(16).padStart(6, '0')

        /** WCAG relative luminance, 0 (black) to 1 (white). */
        fun luminance(c: UInt): Double {
            fun ch(v: UInt): Double {
                val s = v.toDouble() / 255.0
                return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * ch((c shr 16) and 0xFFu) + 0.7152 * ch((c shr 8) and 0xFFu) + 0.0722 * ch(c and 0xFFu)
        }

        /** [t] of the way from [a] to [b], per channel. */
        fun mix(a: UInt, b: UInt, t: Double): UInt {
            fun ch(shift: Int): UInt {
                val x = ((a shr shift) and 0xFFu).toDouble()
                val y = ((b shr shift) and 0xFFu).toDouble()
                return (x + (y - x) * t).toInt().coerceIn(0, 255).toUInt()
            }
            return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        /** A fresh id for a scheme the user brought in. */
        fun newId(): String = UUID.randomUUID().toString()
    }
}

/**
 * Every scheme the app can draw: the six built in, the bundled catalog, and
 * whatever the user imported.
 *
 * The catalog is six hundred palettes in an asset. Nothing that needs a
 * palette *right now* — a new session, the chrome around a pane — should have
 * to parse it on the UI thread, so it is loaded once on a background thread at
 * startup and consulted through an index after that. The built-ins are plain
 * Kotlin and always there, which is why the app default is one of them.
 */
object Schemes {
    /** Where the catalog comes from, for the About screen. */
    const val CATALOG_LICENSE = "Color schemes from iTerm2-Color-Schemes by Mark Badolato and contributors, MIT license. " +
        "Each scheme remains the work of its own author."

    const val DEFAULT_ID = "ONE_DARK"

    private fun scheme(id: String, name: String, ansi: List<Long>, fg: Long, bg: Long, cursor: Long, selection: Long) =
        TermScheme(id, name, ansi.map { it.toUInt() }, fg.toUInt(), bg.toUInt(), cursor.toUInt(), selection.toUInt())

    /**
     * The schemes the app shipped with before the catalog, under the ids it
     * stored them by. Their palettes are frozen here on purpose: a store that
     * says NORD must keep drawing the Nord it was drawing.
     */
    val BUILT_IN: List<TermScheme> = listOf(
        scheme(
            "ONE_DARK", "One Dark",
            listOf(0x282c34, 0xe06c75, 0x98c379, 0xe5c07b, 0x61afef, 0xc678dd, 0x56b6c2, 0xabb2bf,
                0x5c6370, 0xe06c75, 0x98c379, 0xe5c07b, 0x61afef, 0xc678dd, 0x56b6c2, 0xffffff),
            0xabb2bf, 0x1e2127, 0x528bff, 0x3e4451,
        ),
        scheme(
            "DRACULA", "Dracula",
            listOf(0x21222c, 0xff5555, 0x50fa7b, 0xf1fa8c, 0xbd93f9, 0xff79c6, 0x8be9fd, 0xf8f8f2,
                0x6272a4, 0xff6e6e, 0x69ff94, 0xffffa5, 0xd6acff, 0xff92df, 0xa4ffff, 0xffffff),
            0xf8f8f2, 0x282a36, 0xf8f8f2, 0x44475a,
        ),
        scheme(
            "GRUVBOX", "Gruvbox Dark",
            listOf(0x282828, 0xcc241d, 0x98971a, 0xd79921, 0x458588, 0xb16286, 0x689d6a, 0xa89984,
                0x928374, 0xfb4934, 0xb8bb26, 0xfabd2f, 0x83a598, 0xd3869b, 0x8ec07c, 0xebdbb2),
            0xebdbb2, 0x282828, 0xebdbb2, 0x504945,
        ),
        scheme(
            "SOLARIZED_DARK", "Solarized Dark",
            listOf(0x073642, 0xdc322f, 0x859900, 0xb58900, 0x268bd2, 0xd33682, 0x2aa198, 0xeee8d5,
                0x002b36, 0xcb4b16, 0x586e75, 0x657b83, 0x839496, 0x6c71c4, 0x93a1a1, 0xfdf6e3),
            0x839496, 0x002b36, 0x93a1a1, 0x073642,
        ),
        scheme(
            "NORD", "Nord",
            listOf(0x3b4252, 0xbf616a, 0xa3be8c, 0xebcb8b, 0x81a1c1, 0xb48ead, 0x88c0d0, 0xe5e9f0,
                0x4c566a, 0xbf616a, 0xa3be8c, 0xebcb8b, 0x81a1c1, 0xb48ead, 0x8fbcbb, 0xeceff4),
            0xd8dee9, 0x2e3440, 0xd8dee9, 0x434c5e,
        ),
        scheme(
            "LIGHT", "Light",
            listOf(0x000000, 0xc91b00, 0x00c200, 0xc7c400, 0x0225c7, 0xca30c7, 0x00c5c7, 0xc7c7c7,
                0x686868, 0xff6e67, 0x5ffa68, 0xfffc67, 0x6871ff, 0xff77ff, 0x60fdff, 0xffffff),
            0x2e3436, 0xfafafa, 0x2e3436, 0xc6d9f0,
        ),
    )

    val DEFAULT: TermScheme = BUILT_IN.first { it.id == DEFAULT_ID }

    /**
     * Catalog names worth showing before the alphabet, by how often people ask
     * for them. Matched case-insensitively against the catalog; a name that is
     * not there is simply not featured (the catalog test checks they all are).
     */
    val FEATURED_NAMES: List<String> = listOf(
        "Catppuccin Mocha", "Catppuccin Latte", "Catppuccin Frappe", "Catppuccin Macchiato",
        "TokyoNight", "Rose Pine", "Everforest Dark Hard", "Kanagawa Wave",
        "Gruvbox Light", "iTerm2 Solarized Light", "One Half Light",
        "GitHub Dark", "GitHub Light Default", "Monokai Classic", "Ayu",
    )

    private val _catalog = MutableStateFlow<List<TermScheme>>(emptyList())
    /** The bundled schemes, empty until [install] has run. */
    val catalog: StateFlow<List<TermScheme>> = _catalog
    private val _custom = MutableStateFlow<List<TermScheme>>(emptyList())
    /** The user's imported schemes; the store keeps this in step with its settings. */
    val custom: StateFlow<List<TermScheme>> = _custom

    @Volatile private var loaded = false
    private val loadedFlow = MutableStateFlow(false)
    @Volatile private var index: Map<String, TermScheme> = BUILT_IN.associateBy { it.id }

    /** Hand over the parsed catalog; call once, from a background thread. */
    fun install(schemes: List<TermScheme>) {
        _catalog.value = schemes
        rebuild()
        loaded = true
        loadedFlow.value = true
    }

    fun setCustom(schemes: List<TermScheme>) {
        if (_custom.value == schemes) return
        _custom.value = schemes
        rebuild()
    }

    private fun rebuild() {
        // Built-ins last so that nothing can shadow the six stable ids.
        index = (_catalog.value + _custom.value).associateBy { it.id } + BUILT_IN.associateBy { it.id }
    }

    /** Suspends until the catalog is in, so a session started at boot still gets its scheme. */
    suspend fun awaitLoaded() {
        if (!loaded) loadedFlow.first { it }
    }

    fun find(id: String?): TermScheme? = id?.let { index[it] }

    /**
     * The first of [ids] that names a scheme, else the app default.
     *
     * Callers pass the host's choice and then the settings' — so a host whose
     * custom scheme was deleted falls back to the app default the way the
     * settings define it, not to a hardcoded one.
     */
    fun resolve(vararg ids: String?): TermScheme = ids.firstNotNullOfOrNull { find(it) } ?: DEFAULT

    /** A scheme's display name, or the id when nothing by that id is known. */
    fun nameOf(id: String?): String = find(id)?.name ?: id ?: DEFAULT.name

    /** The built-ins followed by whichever of [FEATURED_NAMES] the catalog has, in that order. */
    fun featured(catalog: List<TermScheme> = this.catalog.value): List<TermScheme> {
        val byName = catalog.associateBy { it.name.lowercase() }
        return BUILT_IN + FEATURED_NAMES.mapNotNull { byName[it.lowercase()] }
    }

    /**
     * The asset, one scheme per line as tools/gen-schemes.py writes it. A bad
     * line is dropped rather than failing the whole catalog: the file is
     * generated, but a scheme is not worth a crash either way.
     */
    fun parseCatalog(text: String): List<TermScheme> = text.lineSequence().mapNotNull(::parseLine).toList()

    private fun parseLine(line: String): TermScheme? {
        if (line.isBlank() || line.startsWith("#")) return null
        val f = line.split('\t')
        if (f.size != 7) return null
        val ansi = f[2].split(' ').mapNotNull { it.toUIntOrNull(16) }
        if (ansi.size != 16) return null
        val fg = f[3].toUIntOrNull(16) ?: return null
        val bg = f[4].toUIntOrNull(16) ?: return null
        return TermScheme(f[0], f[1], ansi, fg, bg, f[5].toUIntOrNull(16) ?: fg, f[6].toUIntOrNull(16) ?: TermScheme.mix(bg, fg, 0.25))
    }
}
