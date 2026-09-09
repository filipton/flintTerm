package dev.flint.term.terminal

import dev.flint.term.data.HighlightRule

/**
 * The user's keyword rules, applied to one row of terminal text at a time.
 *
 * Nothing here touches the bytes the server sent: a rule recolors what is on
 * screen as it is drawn, so turning a rule off puts the original colors
 * straight back and the scrollback is still exactly what came over the wire.
 *
 * Rules are tried in the order they are listed and the first one to claim a
 * cell keeps it, which is what makes reordering them in the editor mean
 * something.
 *
 * A pattern is text somebody typed, so a bad one is not a crash: it is
 * refused at construction, reported through [errors] for the editor to show,
 * and skipped everywhere else.
 *
 * (This is the terminal's own highlighting. [Highlighter] is a different
 * thing entirely — the syntax colors in the file editor.)
 */
class HighlightMatcher(rules: List<HighlightRule>) {

    /** A stretch of a row one rule claimed, in char offsets. */
    data class Span(val start: Int, val end: Int, val color: Int, val wholeLine: Boolean)

    private class Compiled(val regex: Regex, val color: Int, val wholeLine: Boolean)

    private val compiled: List<Compiled>

    /** Rule id to the reason its pattern was refused; empty when all of them compiled. */
    val errors: Map<String, String>

    init {
        val ok = ArrayList<Compiled>(rules.size)
        val bad = LinkedHashMap<String, String>()
        for (rule in rules) {
            if (rule.pattern.isEmpty()) continue
            val regex = try {
                Regex(rule.pattern)
            } catch (e: Exception) {
                // Reported even for a rule that is switched off: the editor is
                // where a broken pattern has to be visible.
                bad[rule.id] = reason(e)
                continue
            }
            // Alpha is forced on: a rule is stored as ARGB, and a color with no
            // alpha would be drawn as an invisible black.
            if (rule.enabled && rule.color != 0) ok += Compiled(regex, rule.color or 0xff000000.toInt(), rule.wholeLine)
        }
        compiled = ok
        errors = bad
    }

    /** Nothing to apply: the renderer skips the whole pass. */
    val isEmpty: Boolean get() = compiled.isEmpty()

    /**
     * Paint [text] into [out], one ARGB color per column starting at [offset],
     * leaving columns no rule claimed at zero.
     *
     * This is what the renderer calls, so it writes into a grid-sized array it
     * already owns rather than handing back objects to be thrown away a frame
     * later.
     */
    fun fill(text: String, out: IntArray, offset: Int, cols: Int) {
        forEachMatch(text) { start, end, color, wholeLine ->
            val from = if (wholeLine) 0 else start.coerceIn(0, cols)
            val to = if (wholeLine) cols else end.coerceIn(0, cols)
            for (i in from until to) if (out[offset + i] == 0) out[offset + i] = color
        }
    }

    /** The same matches as objects, for the editor's preview and for tests. */
    fun spans(text: String): List<Span> {
        val out = ArrayList<Span>()
        forEachMatch(text) { start, end, color, wholeLine ->
            out += Span(if (wholeLine) 0 else start, if (wholeLine) text.length else end, color, wholeLine)
        }
        return out
    }

    private inline fun forEachMatch(text: String, action: (start: Int, end: Int, color: Int, wholeLine: Boolean) -> Unit) {
        if (text.isEmpty()) return
        for (rule in compiled) {
            var m = rule.regex.find(text)
            var found = 0
            while (m != null && found < MAX_MATCHES) {
                val start = m.range.first
                val end = m.range.last + 1
                if (end > start) {
                    action(start, end, rule.color, rule.wholeLine)
                    found++
                }
                // A whole-line rule has said everything it has to say the
                // moment it matches once.
                if (rule.wholeLine) break
                val next = if (end > start) end else start + 1
                if (next > text.length) break
                m = rule.regex.find(text, next)
            }
        }
    }

    companion object {
        /**
         * A row is one line of a terminal; past this many hits a rule is
         * repeating itself, and the budget is what keeps a pattern like `.?`
         * from costing more than the frame it is drawn in.
         */
        private const val MAX_MATCHES = 128

        /**
         * The three rules every log already asks for: errors red, warnings
         * yellow, ok green. Fresh ids each time, so adding them twice does not
         * produce two rules that are the same rule.
         */
        fun presets(): List<HighlightRule> = listOf(
            HighlightRule(pattern = "(?i)\\b(error|errors|failed|failure|fatal|panic|denied)\\b", color = 0xFFF7768E.toInt()),
            HighlightRule(pattern = "(?i)\\b(warn|warning|warnings|deprecated|timeout)\\b", color = 0xFFE0AF68.toInt()),
            HighlightRule(pattern = "(?i)\\b(ok|success|succeeded|done|passed|active|running)\\b", color = 0xFF9ECE6A.toInt()),
        )

        /** Null when [pattern] compiles, the reason it did not otherwise. */
        fun error(pattern: String): String? =
            if (pattern.isEmpty()) null else try {
                Regex(pattern)
                null
            } catch (e: Exception) {
                reason(e)
            }

        private fun reason(e: Exception): String =
            e.message?.lineSequence()?.firstOrNull()?.trim()?.ifBlank { null } ?: "Not a valid pattern"
    }
}
