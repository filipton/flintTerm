package dev.flint.term.terminal

/**
 * Where a row of cells has to be cut so that each piece can be drawn with a
 * single typeface.
 *
 * Nerd Font icons live in the private-use planes, which no terminal font
 * covers; the glyphs come from a symbols-only font bundled next to it. The
 * split is done here rather than by handing Android a fallback chain because
 * a chain needs `Typeface.CustomFallbackBuilder` (API 29) and, more to the
 * point, says nothing about a TTF somebody imported themselves — this rule
 * holds on every API level and for every font.
 *
 * [isSymbol] is what the renderer asks, cell by cell, so nothing is allocated
 * while drawing; [split] is the same rule read over a whole row.
 */
object RunSplitter {

    /** One stretch of a row that is drawn with one typeface. */
    data class Run(val start: Int, val end: Int, val symbol: Boolean)

    /**
     * Whether [cp] belongs to a private-use area: the BMP block U+E000–U+F8FF
     * and plane 15 (U+F0000–U+FFFFF), the two ranges Nerd Fonts patch icons
     * into. Plane 16 is left alone — nothing puts glyphs there, and it is
     * where a codepoint that is really a bug tends to land.
     */
    fun isSymbol(cp: Int): Boolean = cp in 0xE000..0xF8FF || cp in 0xF0000..0xFFFFF

    /**
     * [codepoints] cut into runs, each of which is entirely symbols or
     * entirely not. Empty input gives no runs.
     */
    fun split(codepoints: IntArray): List<Run> {
        if (codepoints.isEmpty()) return emptyList()
        val runs = ArrayList<Run>()
        var start = 0
        var symbol = isSymbol(codepoints[0])
        for (i in 1 until codepoints.size) {
            val s = isSymbol(codepoints[i])
            if (s != symbol) {
                runs += Run(start, i, symbol)
                start = i
                symbol = s
            }
        }
        runs += Run(start, codepoints.size, symbol)
        return runs
    }

    /** The same, for a string; surrogate pairs count as the one codepoint they spell. */
    fun split(text: String): List<Run> = split(text.codePoints().toArray())
}
