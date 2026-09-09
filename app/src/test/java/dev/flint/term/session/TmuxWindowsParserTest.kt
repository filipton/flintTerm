package dev.flint.term.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxWindowsParserTest {

    @Test
    fun `three windows, one of them the active one`() {
        val windows = TmuxWindows.parse(
            """
            0:zsh:0
            1:vim:1
            2:logs:0
            """.trimIndent(),
        )
        assertEquals(listOf(0, 1, 2), windows.map { it.index })
        assertEquals(listOf("zsh", "vim", "logs"), windows.map { it.name })
        assertEquals(listOf(false, true, false), windows.map { it.active })
    }

    @Test
    fun `a window name may contain colons`() {
        val w = TmuxWindows.parse("4:ssh: build:1").single()
        assertEquals(4, w.index)
        assertEquals("ssh: build", w.name)
        assertTrue(w.active)
    }

    @Test
    fun `a nameless window keeps its number`() {
        val w = TmuxWindows.parse("7::0").single()
        assertEquals(7, w.index)
        assertEquals("", w.name)
    }

    @Test
    fun `anything that is not a window is dropped`() {
        val windows = TmuxWindows.parse(
            """
            Welcome to example.com
            no server running on /tmp/tmux-1000/default
            0:zsh:1

            2:vim
            x:broken:0
            """.trimIndent(),
        )
        assertEquals(listOf(0), windows.map { it.index })
    }

    @Test
    fun `windows past nine have no prefix key of their own`() {
        val windows = TmuxWindows.parse("9:nine:0\n10:ten:1")
        assertTrue(windows[0].hasPrefixKey)
        assertFalse(windows[1].hasPrefixKey)
        assertEquals("tmux select-window -t 10", TmuxWindows.selectCommand(10))
    }

    @Test
    fun `the command asks for the three fields this parser reads`() {
        assertEquals(
            "tmux list-windows -F '#{window_index}:#{window_name}:#{window_active}'",
            TmuxWindows.COMMAND,
        )
    }
}
