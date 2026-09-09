package dev.flint.term.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The bundled asset is generated, but generated files break too — a scheme
 * with fifteen colors would crash a session, and two with one id would hide
 * one of them. Every line is checked here, the way the app will read it.
 */
class SchemeCatalogTest {
    /** Gradle runs unit tests from the module directory; a checkout elsewhere is tried too. */
    private val asset = listOf("src/main/assets/schemes.txt", "app/src/main/assets/schemes.txt").map(::File).first { it.exists() }
    private val catalog = Schemes.parseCatalog(asset.readText())

    @Test fun `several hundred schemes, each complete`() {
        assertTrue("only ${catalog.size} schemes", catalog.size >= 300)
        catalog.forEach { s ->
            assertEquals(s.name, 16, s.ansi.size)
            assertTrue(s.name, s.name.isNotBlank() && s.id.isNotBlank())
            assertTrue(s.name, s.ansi.all { it <= 0xFFFFFFu } && s.foreground <= 0xFFFFFFu && s.background <= 0xFFFFFFu)
        }
    }

    @Test fun `no two schemes share an id, and none shadows a built-in`() {
        val ids = catalog.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        val builtIn = Schemes.BUILT_IN.map { it.id }.toSet()
        assertTrue(ids.none { it in builtIn })
        // Nor a built-in's name: the generator leaves those out so the list has no doubles.
        val names = Schemes.BUILT_IN.map { it.name.lowercase() }.toSet()
        assertTrue(catalog.none { it.name.lowercase() in names })
    }

    @Test fun `every featured name is really in the catalog`() {
        val featured = Schemes.featured(catalog)
        assertEquals(Schemes.BUILT_IN.size + Schemes.FEATURED_NAMES.size, featured.size)
        assertEquals(Schemes.BUILT_IN, featured.take(Schemes.BUILT_IN.size))
    }

    @Test fun `light and dark are told apart by the background`() {
        val byName = catalog.associateBy { it.name }
        assertTrue(byName.getValue("Catppuccin Latte").isLight)
        assertTrue(byName.getValue("Gruvbox Light").isLight)
        assertFalse(byName.getValue("Catppuccin Mocha").isLight)
        assertFalse(byName.getValue("TokyoNight").isLight)
        assertTrue(Schemes.BUILT_IN.first { it.id == "LIGHT" }.isLight)
        assertFalse(Schemes.DEFAULT.isLight)
    }

    @Test fun `the six old ids still resolve to their old palettes, before and after loading`() {
        // Synchronous, with nothing loaded: a session at boot must not draw the wrong colors.
        assertEquals("Nord", Schemes.find("NORD")?.name)
        assertEquals(0x2e3440u, Schemes.find("NORD")?.background)
        Schemes.install(catalog)
        assertEquals(0x2e3440u, Schemes.find("NORD")?.background)
        assertEquals("Solarized Dark", Schemes.find("SOLARIZED_DARK")?.name)
        assertNotNull(Schemes.find("catppuccin-mocha"))
    }

    @Test fun `an unknown id falls back rather than failing`() {
        Schemes.install(catalog)
        assertNull(Schemes.find("no-such-scheme"))
        assertSame(Schemes.DEFAULT, Schemes.resolve("no-such-scheme"))
        assertSame(Schemes.DEFAULT, Schemes.resolve(null))
        // A host pointing at a deleted scheme takes the settings' choice, not a hardcoded one.
        assertEquals("NORD", Schemes.resolve("deleted-custom", "NORD").id)
        assertEquals("no-such-scheme", Schemes.nameOf("no-such-scheme"))
    }

    @Test fun `custom schemes resolve by id once the store hands them over`() {
        val mine = Schemes.DEFAULT.copy(id = "mine-1", name = "Mine", custom = true)
        Schemes.setCustom(listOf(mine))
        assertEquals("Mine", Schemes.find("mine-1")?.name)
        Schemes.setCustom(emptyList())
        assertNull(Schemes.find("mine-1"))
    }

    @Test fun `a broken line is dropped, not fatal`() {
        val text = "# comment\nok\tOk\t${"aabbcc ".repeat(16).trim()}\tffffff\t000000\tffffff\t333333\nshort\tShort\taabbcc\tffffff\t000000\tffffff\t333333\n\n"
        val out = Schemes.parseCatalog(text)
        assertEquals(listOf("ok"), out.map { it.id })
    }
}
