package dev.flint.term.data.imports

import dev.flint.term.data.Schemes
import dev.flint.term.data.TermScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Each format on the real Dracula file from the iTerm2-Color-Schemes repository
 * (under src/test/resources/schemes), which is also the app's built-in
 * Dracula — so every parser has to land on the same sixteen colors.
 */
class SchemeImportTest {
    private fun sample(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("schemes/$name")!!.bufferedReader().use { it.readText() }

    private val dracula: TermScheme = Schemes.BUILT_IN.first { it.id == "DRACULA" }

    private fun assertDracula(parsed: SchemeImport.Parsed, format: SchemeImport.Format) {
        assertEquals(format, parsed.format)
        val s = parsed.scheme
        assertEquals(dracula.ansi, s.ansi)
        assertEquals(dracula.foreground, s.foreground)
        assertEquals(dracula.background, s.background)
        assertEquals(dracula.cursor, s.cursor)
        assertTrue(s.custom)
        assertTrue(s.id.isNotBlank() && s.id != dracula.id)
    }

    @Test fun ghostty() {
        val p = SchemeImport.parse(sample("dracula.ghostty"), "Dracula").getOrThrow()
        assertDracula(p, SchemeImport.Format.GHOSTTY)
        assertEquals(dracula.selection, p.scheme.selection)
        assertEquals("Dracula", p.scheme.name)
    }

    @Test fun alacrittyToml() {
        val p = SchemeImport.parse(sample("dracula.alacritty.toml"), "Dracula").getOrThrow()
        assertDracula(p, SchemeImport.Format.ALACRITTY)
        assertEquals(dracula.selection, p.scheme.selection)
    }

    @Test fun alacrittyTomlInlineTablesAndHexPrefix() {
        val text = """
            [colors]
            primary = { background = "0x282a36", foreground = "0xf8f8f2" }
            normal = { black = "0x21222c", red = "0xff5555", green = "0x50fa7b", yellow = "0xf1fa8c", blue = "0xbd93f9", magenta = "0xff79c6", cyan = "0x8be9fd", white = "0xf8f8f2" }
            bright = { black = "0x6272a4", red = "0xff6e6e", green = "0x69ff94", yellow = "0xffffa5", blue = "0xd6acff", magenta = "0xff92df", cyan = "0xa4ffff", white = "0xffffff" }
            [colors.cursor]
            cursor = "0xf8f8f2"
            [colors.vi_mode_cursor]
            cursor = "0x000000"
        """.trimIndent()
        assertDracula(SchemeImport.parse(text).getOrThrow(), SchemeImport.Format.ALACRITTY)
    }

    /** The pre-0.13 `alacritty.yml` block, as the Dracula project published it. */
    @Test fun alacrittyYaml() {
        val text = """
            # Dracula for Alacritty
            colors:
              # Default colors
              primary:
                background: '0x282a36'
                foreground: '0xf8f8f2'
              cursor:
                text: CellBackground
                cursor: CellForeground
              selection:
                text: CellForeground
                background: '0x44475a'
              normal:
                black:   '0x21222c'
                red:     '0xff5555'
                green:   '0x50fa7b'
                yellow:  '0xf1fa8c'
                blue:    '0xbd93f9'
                magenta: '0xff79c6'
                cyan:    '0x8be9fd'
                white:   '0xf8f8f2'
              bright:
                black:   '0x6272a4'
                red:     '0xff6e6e'
                green:   '0x69ff94'
                yellow:  '0xffffa5'
                blue:    '0xd6acff'
                magenta: '0xff92df'
                cyan:    '0xa4ffff'
                white:   '0xffffff'
        """.trimIndent()
        val p = SchemeImport.parse(text, "Dracula").getOrThrow()
        assertDracula(p, SchemeImport.Format.ALACRITTY)
        assertEquals(dracula.selection, p.scheme.selection)
    }

    @Test fun windowsTerminal() {
        val p = SchemeImport.parse(sample("dracula.windowsterminal.json"), "ignored").getOrThrow()
        assertDracula(p, SchemeImport.Format.WINDOWS_TERMINAL)
        assertEquals(dracula.selection, p.scheme.selection)
        // The file names itself, and that beats the file name.
        assertEquals("Dracula", p.scheme.name)
    }

    @Test fun windowsTerminalSettingsFragment() {
        val text = """{"schemes": [${sample("dracula.windowsterminal.json")}], "profiles": {}}"""
        assertDracula(SchemeImport.parse(text).getOrThrow(), SchemeImport.Format.WINDOWS_TERMINAL)
    }

    @Test fun iterm2() {
        val p = SchemeImport.parse(sample("dracula.itermcolors"), "Dracula").getOrThrow()
        assertDracula(p, SchemeImport.Format.ITERM2)
        assertEquals(dracula.selection, p.scheme.selection)
        assertEquals("Dracula", p.scheme.name)
    }

    @Test fun xresources() {
        val p = SchemeImport.parse(sample("dracula.Xresources"), "Dracula").getOrThrow()
        assertDracula(p, SchemeImport.Format.XRESOURCES)
        // No selection color in the file: something visible is made up.
        assertTrue(p.scheme.selection != p.scheme.background)
    }

    @Test fun xresourcesSpellings() {
        val text = buildString {
            append("#define base00 #282a36\n#define base05 #f8f8f2\n")
            append("URxvt.foreground: base05\nXTerm*background: base00\n")
            dracula.ansi.forEachIndexed { i, c ->
                val prefix = listOf("*.", "*", "URxvt.", "XTerm*", "xterm*", "")[i % 6]
                append("${prefix}color$i: rgb:${TermScheme.hex(c).chunked(2).joinToString("/")}\n")
            }
        }
        val p = SchemeImport.parse(text, "Dracula").getOrThrow()
        assertDracula(p, SchemeImport.Format.XRESOURCES)
    }

    @Test fun detectionIsByContentNotName() {
        // Same text, no hint at all: each format is still recognised.
        assertEquals(SchemeImport.Format.GHOSTTY, SchemeImport.parse(sample("dracula.ghostty")).getOrThrow().format)
        assertEquals(SchemeImport.Format.ITERM2, SchemeImport.parse(sample("dracula.itermcolors")).getOrThrow().format)
        assertEquals(SchemeImport.Format.XRESOURCES, SchemeImport.parse(sample("dracula.Xresources")).getOrThrow().format)
        assertEquals(SchemeImport.Format.ALACRITTY, SchemeImport.parse(sample("dracula.alacritty.toml")).getOrThrow().format)
        assertEquals(SchemeImport.Format.WINDOWS_TERMINAL, SchemeImport.parse(sample("dracula.windowsterminal.json")).getOrThrow().format)
        assertEquals("Imported scheme", SchemeImport.parse(sample("dracula.ghostty")).getOrThrow().scheme.name)
    }

    @Test fun nothingRecognisableIsAClearError() {
        val err = SchemeImport.parse("Host box\n  HostName box.example.com\n").exceptionOrNull()!!
        assertTrue(err.message!!, err.message!!.startsWith("Not a color scheme"))
        assertTrue(SchemeImport.parse("").isFailure)
    }

    @Test fun aRecognisedButShortFileSaysWhatIsMissing() {
        val text = sample("dracula.ghostty").lines().filterNot { it.startsWith("palette = 9=") || it.startsWith("foreground") }.joinToString("\n")
        val err = SchemeImport.parse(text).exceptionOrNull()!!
        assertEquals("Looks like a Ghostty scheme, but it is missing ANSI color 9 and the foreground.", err.message)
    }

    @Test fun aSharedSchemeReadsBackAsItself() {
        val nord = Schemes.BUILT_IN.first { it.id == "NORD" }
        val back = SchemeImport.parse(nord.toGhostty()).getOrThrow().scheme
        assertEquals(nord.ansi, back.ansi)
        assertEquals(nord.foreground, back.foreground)
        assertEquals(nord.background, back.background)
        assertEquals(nord.cursor, back.cursor)
        assertEquals(nord.selection, back.selection)
    }

    @Test fun colorSpellings() {
        assertEquals(0xaabbccu, SchemeImport.color("#aabbcc"))
        assertEquals(0xaabbccu, SchemeImport.color("0xAABBCC"))
        assertEquals(0xaabbccu, SchemeImport.color("'aabbcc'"))
        assertEquals(0xaabbccu, SchemeImport.color("#abc"))
        assertEquals(0xaabbccu, SchemeImport.color("rgb:aa/bb/cc"))
        assertEquals(0xaabbccu, SchemeImport.color("rgb:aaaa/bbbb/cccc"))
        assertEquals(null, SchemeImport.color("CellForeground"))
    }
}
