package dev.flint.term.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scheme ids and custom schemes through the store file, including a file from before the catalog. */
class SchemeStoreTest {
    private fun roundTrip(snap: Snapshot): Snapshot = StoreJson.read(StoreJson.write(snap) { it }) { it }

    @Test fun `custom schemes survive the file`() {
        val mine = TermScheme(
            id = "abc-123", name = "Mine",
            ansi = (0 until 16).map { (it * 0x111111).toUInt() },
            foreground = 0xfafafau, background = 0x101010u, cursor = 0xff0000u, selection = 0x333333u, custom = true,
        )
        val out = roundTrip(Snapshot(settings = Settings(theme = "abc-123", customSchemes = listOf(mine))))
        assertEquals(listOf(mine), out.settings.customSchemes)
        assertEquals("abc-123", out.settings.theme)
    }

    @Test fun `theme ids on hosts and groups are stored as they are`() {
        val out = roundTrip(
            Snapshot(
                hosts = listOf(Host(id = "h1", theme = "catppuccin-mocha"), Host(id = "h2")),
                groups = listOf(HostGroup(id = "g1", theme = "rose-pine"), HostGroup(id = "g2")),
                settings = Settings(theme = "tokyonight"),
            ),
        )
        assertEquals("catppuccin-mocha", out.hosts[0].theme)
        assertNull(out.hosts[1].theme)
        assertEquals("rose-pine", out.groups[0].theme)
        assertNull(out.groups[1].theme)
        assertEquals("tokyonight", out.settings.theme)
    }

    /** A store written when the theme was an enum: the names it wrote are ids now. */
    @Test fun `a file from before the catalog loads unchanged`() {
        val legacy = JSONObject(
            """
            {"version":1,
             "hosts":[{"id":"h1","label":"Box","hostname":"box","port":22,"username":"me","authType":"PASSWORD","theme":"NORD"},
                      {"id":"h2","label":"Other","hostname":"other","port":22,"username":"me","authType":"PASSWORD","theme":null}],
             "groups":[{"id":"g1","name":"Work","theme":"DRACULA"}],
             "settings":{"theme":"SOLARIZED_DARK","fontSizeSp":13.0}}
            """.trimIndent(),
        )
        val out = StoreJson.read(legacy) { it }
        assertEquals("NORD", out.hosts[0].theme)
        assertNull(out.hosts[1].theme)
        assertEquals("DRACULA", out.groups[0].theme)
        assertEquals("SOLARIZED_DARK", out.settings.theme)
        assertEquals(emptyList<TermScheme>(), out.settings.customSchemes)
        // And they still draw the palettes they always did, without any catalog loaded.
        assertEquals("Nord", Schemes.resolve(out.hosts[0].theme, out.settings.theme).name)
        assertEquals("Solarized Dark", Schemes.resolve(out.hosts[1].theme, out.settings.theme).name)
    }

    @Test fun `a missing or empty theme is the default, and an unknown one is kept for later`() {
        val out = StoreJson.read(JSONObject("""{"version":1,"settings":{"theme":""}}""")) { it }
        assertEquals(Schemes.DEFAULT_ID, out.settings.theme)
        val gone = StoreJson.read(JSONObject("""{"version":1,"settings":{"theme":"deleted-custom-id"}}""")) { it }
        assertEquals("deleted-custom-id", gone.settings.theme)
        assertEquals(Schemes.DEFAULT, Schemes.resolve(gone.settings.theme))
    }

    @Test fun `a custom scheme short of colors is dropped rather than crashing the load`() {
        val out = StoreJson.read(
            JSONObject("""{"version":1,"settings":{"customSchemes":[{"id":"x","name":"Bad","ansi":["000000"],"foreground":"ffffff","background":"000000"}]}}"""),
        ) { it }
        assertTrue(out.settings.customSchemes.isEmpty())
    }
}
