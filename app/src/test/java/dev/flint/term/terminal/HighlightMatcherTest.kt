package dev.flint.term.terminal

import dev.flint.term.data.HighlightRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the keyword rules paint. The colors come out as ARGB, which is what the
 * renderer writes into its per-cell array, so the tests compare against the
 * same opaque values a cell would be drawn with.
 */
class HighlightMatcherTest {

    private val red = 0xFFF7768E.toInt()
    private val green = 0xFF9ECE6A.toInt()

    private fun matcher(vararg rules: HighlightRule) = HighlightMatcher(rules.toList())

    private fun colorsOf(m: HighlightMatcher, text: String, cols: Int = text.length): IntArray =
        IntArray(cols).also { m.fill(text, it, 0, cols) }

    @Test
    fun `a match colors only what it matched`() {
        val m = matcher(HighlightRule(pattern = "error", color = red))
        val colors = colorsOf(m, "an error here")
        assertEquals(0, colors[0])
        assertEquals(red, colors[3])
        assertEquals(red, colors[7])
        assertEquals(0, colors[8])
    }

    @Test
    fun `every occurrence on the row is colored, not just the first`() {
        val m = matcher(HighlightRule(pattern = "ab", color = red))
        assertEquals(listOf(0, 3), m.spans("ab ab").map { it.start })
    }

    @Test
    fun `whole line colors the row from a single match`() {
        val m = matcher(HighlightRule(pattern = "fatal", color = red, wholeLine = true))
        val colors = colorsOf(m, "boot: fatal", cols = 20)
        assertTrue(colors.all { it == red })
    }

    @Test
    fun `the first rule that claims a cell keeps it`() {
        val m = matcher(
            HighlightRule(pattern = "ok", color = green),
            HighlightRule(pattern = "not ok", color = red, wholeLine = true),
        )
        val colors = colorsOf(m, "not ok")
        // "ok" was claimed by the rule listed first; the whole-line rule takes
        // what is left of the row.
        assertEquals(red, colors[0])
        assertEquals(green, colors[4])
    }

    @Test
    fun `a rule that is switched off does nothing`() {
        val m = matcher(HighlightRule(pattern = "error", color = red, enabled = false))
        assertTrue(m.isEmpty)
        assertTrue(colorsOf(m, "an error").all { it == 0 })
    }

    @Test
    fun `a malformed pattern is refused rather than thrown`() {
        val bad = HighlightRule(id = "r1", pattern = "error(", color = red)
        val m = matcher(bad, HighlightRule(pattern = "warn", color = green))
        assertNotNull(m.errors["r1"])
        assertFalse(m.errors.getValue("r1").isBlank())
        // The rules around it still work, and matching the broken one's text
        // does not blow up.
        val colors = colorsOf(m, "error( warn")
        assertEquals(0, colors[0])
        assertEquals(green, colors[7])
    }

    @Test
    fun `a broken pattern is reported even while the rule is switched off`() {
        val m = matcher(HighlightRule(id = "r2", pattern = "*nope", color = red, enabled = false))
        assertNotNull(m.errors["r2"])
    }

    @Test
    fun `error names the trouble for the editor and stays quiet about good patterns`() {
        assertNull(HighlightMatcher.error("(?i)\\berror\\b"))
        assertNull(HighlightMatcher.error(""))
        assertNotNull(HighlightMatcher.error("[unclosed"))
    }

    @Test
    fun `a pattern that matches nothing costs nothing`() {
        val m = matcher(HighlightRule(pattern = "z*", color = red))
        // "z*" matches the empty string at every position: the walk has to
        // step past those rather than sit on one forever.
        assertTrue(colorsOf(m, "a line of text").all { it == 0 })
    }

    @Test
    fun `a match running past the last column is clipped to the row`() {
        val m = matcher(HighlightRule(pattern = "warning", color = red))
        val colors = colorsOf(m, "a warning", cols = 5)
        assertEquals(red, colors[2])
        assertEquals(5, colors.size)
    }

    @Test
    fun `the presets do what they say on a line from a log`() {
        val m = HighlightMatcher(HighlightMatcher.presets())
        val line = "nginx: error while loading, warning ignored, service ok"
        val spans = m.spans(line)
        assertEquals(3, spans.size)
        assertTrue(spans.any { line.substring(it.start, it.end) == "error" })
        assertTrue(spans.any { line.substring(it.start, it.end) == "warning" })
        assertTrue(spans.any { line.substring(it.start, it.end) == "ok" })
    }
}
