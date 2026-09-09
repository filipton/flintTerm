package dev.flint.term.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxTest {
    @Test
    fun `resuming picks the session that was attached last`() {
        val cmd = Tmux.attachCommand("main", resumeLast = true)
        assertTrue(cmd, cmd.contains("session_last_attached"))
        assertTrue("the configured name is the fallback: $cmd", cmd.contains("\${S:-main}"))
    }

    @Test
    fun `without resuming the named session is used every time`() {
        val cmd = Tmux.attachCommand("work", resumeLast = false)
        assertFalse(cmd, cmd.contains("list-sessions"))
        assertTrue(cmd, cmd.contains("\${S:-work}"))
    }

    @Test
    fun `a quote in the session name cannot end the shell quoting`() {
        val cmd = Tmux.attachCommand("it's mine", resumeLast = false)
        assertFalse("a stray quote would break the command: $cmd", cmd.contains("it's"))
    }
}
