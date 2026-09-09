package dev.flint.term.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a session types after login. The ordering is load-bearing: the tmux
 * attach line runs `exec`, so whatever ends up below it never runs at all.
 */
class StartupLinesTest {

    private val status = Snippet(id = "s1", name = "Status", command = "./status.sh")
    private val logs = Snippet(id = "s2", name = "Logs", command = "tail -f /var/log/syslog")
    private val asks = Snippet(id = "s3", name = "Restart", command = "systemctl restart {{unit}}")
    private val all = listOf(status, logs, asks)

    @Test
    fun `snippets run before the host's own command and tmux comes last`() {
        val host = Host(startupSnippetIds = listOf("s1"), startupCommand = "cd /srv")
        assertEquals(
            listOf("./status.sh", "cd /srv", "tmux attach"),
            host.startupLines(all, tmux = "tmux attach").lines(),
        )
    }

    @Test
    fun `an extra command from the caller goes first of all`() {
        val host = Host(startupSnippetIds = listOf("s1"), startupCommand = "cd /srv")
        assertEquals(
            listOf("wake.sh", "./status.sh", "cd /srv"),
            host.startupLines(all, extra = "  wake.sh  ").lines(),
        )
    }

    @Test
    fun `snippets keep the order the host lists them in, not the order they were saved`() {
        val host = Host(startupSnippetIds = listOf("s2", "s1"))
        assertEquals(listOf("tail -f /var/log/syslog", "./status.sh"), host.startupLines(all).lines())
    }

    /** Nobody is at the keyboard at login, so a snippet asking for a value cannot run. */
    @Test
    fun `a snippet with a placeholder is left out rather than typed verbatim`() {
        val host = Host(startupSnippetIds = listOf("s3", "s1"))
        val lines = host.startupLines(all)
        assertEquals(listOf("./status.sh"), lines.lines())
        assertTrue(lines, !lines.contains("{{"))
    }

    /** A snippet deleted while a host still named it must not become a blank line. */
    @Test
    fun `an unknown snippet id is skipped`() {
        val host = Host(startupSnippetIds = listOf("gone", "s1"), startupCommand = "cd /srv")
        assertEquals(listOf("./status.sh", "cd /srv"), host.startupLines(all).lines())
    }

    @Test
    fun `an empty snippet command adds no line`() {
        val blank = Snippet(id = "s4", name = "Nothing", command = "   ")
        val host = Host(startupSnippetIds = listOf("s4", "s1"))
        assertEquals(listOf("./status.sh"), host.startupLines(all + blank).lines())
    }

    @Test
    fun `a host with nothing to run types nothing`() {
        assertEquals("", Host().startupLines(all))
        assertEquals("", Host(startupCommand = "   ").startupLines(emptyList()))
    }

    /**
     * The tmux line replaces the shell, so a snippet placed after it would be
     * silently dropped — the reason the order is asserted rather than assumed.
     */
    @Test
    fun `nothing follows the tmux attach line`() {
        val host = Host(startupSnippetIds = listOf("s1", "s2"), startupCommand = "cd /srv")
        val lines = host.startupLines(all, extra = "wake.sh", tmux = "tmux new-session -A -s main").lines()
        assertEquals("tmux new-session -A -s main", lines.last())
        assertEquals(5, lines.size)
    }
}
