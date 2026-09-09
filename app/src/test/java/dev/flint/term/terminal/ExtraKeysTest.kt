package dev.flint.term.terminal

import dev.flint.term.core.KeyCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtraKeysTest {

    @Test
    fun `shift+tab is a cap of its own`() {
        val def = ExtraKeys.resolve("STAB")
        assertEquals("⇧⇥", def.label)
        assertEquals(ExtraKeys.Action.Key(KeyCode.Tab, shift = true), def.action)
    }

    @Test
    fun `the paperclip opens the file picker`() {
        assertEquals(ExtraKeys.Action.InsertFile, ExtraKeys.resolve("FILE").action)
    }

    @Test
    fun `a plain key carries no modifiers`() {
        val tab = ExtraKeys.resolve("TAB").action as ExtraKeys.Action.Key
        assertTrue(!tab.shift && !tab.ctrl && !tab.alt)
    }

    @Test
    fun `shift+tab sits next to the snippets cap`() {
        val row = ExtraKeys.defaultRow2
        assertEquals("STAB", row[row.indexOf("SNIPPETS") + 1])
    }

    @Test
    fun `an unknown token is still typed literally`() {
        val def = ExtraKeys.resolve("ls -la")
        assertEquals(ExtraKeys.Action.Text("ls -la"), def.action)
        assertEquals("ls -la", def.label)
    }

    @Test
    fun `every catalog token resolves to itself`() {
        for (def in ExtraKeys.catalog) assertEquals(def, ExtraKeys.resolve(def.token))
        // And every default row is made of tokens the catalog knows.
        val known = ExtraKeys.catalog.map { it.token }.toSet()
        for (t in ExtraKeys.defaultRow1) assertTrue(t, t in known)
    }

    @Test
    fun `every preset is built from keys that exist`() {
        val known = ExtraKeys.catalog.map { it.token }.toSet()
        for (p in ExtraKeys.presets) {
            for (t in p.row1 + p.row2) {
                // Punctuation is meant to be typed as it stands; a name is not.
                // A misremembered one (CHORDS, SHIFTTAB) would resolve quietly
                // to literal text and put "CHORDS" into somebody's shell.
                if (t.any { it.isLetter() }) assertTrue("${p.name}: unknown key $t", t in known)
                else assertEquals(ExtraKeys.Action.Text(t), ExtraKeys.resolve(t).action)
            }
            assertTrue("${p.name}: empty row", p.row1.isNotEmpty() && p.row2.isNotEmpty())
        }
    }
}
