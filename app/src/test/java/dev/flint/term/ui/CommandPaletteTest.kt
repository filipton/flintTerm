package dev.flint.term.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the one field finds, and in which order.
 *
 * The palette is only worth having if the row you meant is the first one: a
 * ranked list that puts "Bold text is bright" above "Font" for `font` is a
 * slower way to do what scrolling already did.
 */
class CommandPaletteTest {

    private fun host(title: String, target: String = "root@$title") =
        PaletteEntry(title, target, PaletteKind.HOST, id = title)

    private fun setting(title: String, subtitle: String = "") =
        PaletteEntry(title, subtitle, PaletteKind.SETTING, route = "settings/x")

    private fun titles(matches: List<PaletteMatch>) = matches.map { it.entry.title }

    @Test
    fun `a title that begins with the query comes before one that merely contains it`() {
        val entries = listOf(setting("Bold text is bright"), setting("Font"), setting("Terminal font"))
        assertEquals(listOf("Font", "Terminal font"), titles(PaletteSearch.rank(entries, "font")))
    }

    @Test
    fun `among titles that all begin with the query, the shortest wins`() {
        val entries = listOf(setting("Session recordings"), setting("Session tabs"), setting("Sessions"))
        assertEquals(listOf("Sessions", "Session tabs", "Session recordings"), titles(PaletteSearch.rank(entries, "session")))
    }

    @Test
    fun `a contiguous run beats the same characters scattered`() {
        val entries = listOf(setting("Tell me about the bell"), setting("Session tabs"))
        assertEquals(listOf("Session tabs", "Tell me about the bell"), titles(PaletteSearch.rank(entries, "tab")))
    }

    @Test
    fun `a title match beats a description match`() {
        val entries = listOf(
            setting("Wallpaper", "Nothing to do with color"),
            setting("Material You colors", "Tint the app with your wallpaper palette"),
        )
        assertEquals("Wallpaper", titles(PaletteSearch.rank(entries, "wallpaper")).first())
    }

    @Test
    fun `initials find a row nobody would type out in full`() {
        val entries = listOf(setting("Nerd Font glyphs", "Draw prompt and file icons from the bundled symbols font"))
        val hit = PaletteSearch.rank(entries, "nrdgl").single()
        assertEquals("Nerd Font glyphs", hit.entry.title)
        // n-e-r-d, then the g and l of glyphs: the bold characters are what the
        // row uses to say why it is here.
        assertEquals(listOf(0, 2, 3, 10, 11), hit.titleHits)
    }

    @Test
    fun `a subsequence may run from the title into the description`() {
        val real = PaletteCatalog.settings()
        assertTrue(
            "nrdglf should still find the Nerd Font row",
            titles(PaletteSearch.rank(real, "nrdglf")).contains("Nerd Font glyphs"),
        )
    }

    @Test
    fun `characters out of order find nothing`() {
        val entries = listOf(setting("Nerd Font glyphs", "Draw prompt and file icons"))
        assertTrue(PaletteSearch.rank(entries, "glnrd").isEmpty())
    }

    @Test
    fun `an empty query offers recent hosts and nothing else`() {
        val entries = listOf(
            host("web01"), host("db02"), setting("App theme"),
            PaletteEntry("Deploy", "make deploy", PaletteKind.SNIPPET, id = "s1"),
        )
        val shown = PaletteSearch.rank(entries, "")
        assertEquals(listOf("web01", "db02"), titles(shown))
        assertTrue(shown.all { it.entry.kind == PaletteKind.HOST })
    }

    @Test
    fun `an empty query stops at a handful of hosts`() {
        val many = (1..20).map { host("host$it") }
        assertEquals(PaletteSearch.RECENT, PaletteSearch.rank(many, "   ").size)
    }

    @Test
    fun `the hosts an empty query shows are the ones it was given first`() {
        val entries = listOf(host("newest"), host("older"), host("oldest"))
        assertEquals(listOf("newest", "older", "oldest"), titles(PaletteSearch.rank(entries, "")))
    }

    @Test
    fun `every kind is searched, each under its own heading`() {
        val entries = listOf(
            host("app-server"),
            PaletteEntry("app logs", "tail -f /var/log/app.log", PaletteKind.SNIPPET, id = "s1"),
            setting("App theme"),
            PaletteEntry("Accounts", "A login several hosts share", PaletteKind.ACTION, route = "accounts"),
        )
        val sections = PaletteSearch.sections(PaletteSearch.rank(entries, "app"))
        assertEquals(
            setOf(PaletteKind.HOST, PaletteKind.SNIPPET, PaletteKind.SETTING),
            sections.map { it.first }.toSet(),
        )
        // Sections follow their best row, so whatever the query was really
        // about leads the list.
        assertTrue(sections.first().second.first().score >= sections.last().second.first().score)
    }

    @Test
    fun `the shipped settings index is reachable by the words on the row`() {
        val real = PaletteCatalog.settings()
        assertEquals("Data saver", titles(PaletteSearch.rank(real, "data sav")).first())
        assertEquals("Scrollback", titles(PaletteSearch.rank(real, "scroll")).first())
        assertTrue(titles(PaletteSearch.rank(real, "wallpaper")).contains("Material You colors"))
    }

    @Test
    fun `a query nothing answers comes back empty rather than with everything`() {
        assertTrue(PaletteSearch.rank(PaletteCatalog.settings(), "zzqq").isEmpty())
    }
}
