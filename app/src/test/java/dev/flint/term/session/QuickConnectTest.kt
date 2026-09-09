package dev.flint.term.session

import dev.flint.term.data.Host
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickConnectTest {

    @Test
    fun `a bare hostname is a host on port 22`() {
        val h = QuickConnect.parse("rig.example.com")!!
        assertEquals("rig.example.com", h.hostname)
        assertEquals(22, h.port)
        assertEquals("", h.username)
    }

    @Test
    fun `user, address and port are all read`() {
        val h = QuickConnect.parse("pilif@10.0.2.2:2222")!!
        assertEquals("pilif", h.username)
        assertEquals("10.0.2.2", h.hostname)
        assertEquals(2222, h.port)
        // What the row offers to do, as the person typed it.
        assertEquals("pilif@10.0.2.2:2222", h.target)
    }

    @Test
    fun `surrounding space is not part of the address`() {
        assertEquals("rig", QuickConnect.parse("  rig  ")?.hostname)
    }

    @Test
    fun `an ssh URL says the same thing as the bare form`() {
        val url = QuickConnect.parse("ssh://pilif@10.0.2.2:2222")!!
        val bare = QuickConnect.parse("pilif@10.0.2.2:2222")!!
        assertEquals(bare.username to (bare.hostname to bare.port), url.username to (url.hostname to url.port))
    }

    @Test
    fun `sftp links name the same machine`() {
        val h = QuickConnect.parse("sftp://root@files.example.com")!!
        assertEquals("root", h.username)
        assertEquals("files.example.com", h.hostname)
        assertEquals(22, h.port)
    }

    @Test
    fun `a link may carry a path, and the path is not part of the address`() {
        val h = QuickConnect.parse("sftp://files.example.com/var/log")!!
        assertEquals("files.example.com", h.hostname)
    }

    @Test
    fun `a password in a link is dropped`() {
        val h = QuickConnect.parse("ssh://pilif:hunter2@rig")!!
        assertEquals("pilif", h.username)
        assertEquals("", h.password)
    }

    @Test
    fun `an encoded username comes back decoded`() {
        assertEquals("pi lif", QuickConnect.parse("ssh://pi%20lif@rig")?.username)
    }

    @Test
    fun `IPv6 needs its brackets, which is also where the port goes`() {
        val h = QuickConnect.parse("[2001:db8::1]:2222")!!
        assertEquals("2001:db8::1", h.hostname)
        assertEquals(2222, h.port)
        assertEquals("2001:db8::1", QuickConnect.parse("ssh://[2001:db8::1]")?.hostname)
        // Without them there is no telling which colon introduces the port.
        assertNull(QuickConnect.parse("2001:db8::1"))
    }

    @Test
    fun `what is not an address is turned down`() {
        for (nonsense in listOf(
            "",
            "   ",
            "web server",
            "prod:",
            ":22",
            "rig:",
            "rig:0",
            "rig:70000",
            "rig:ssh",
            "@rig",
            "pilif@",
            "http://rig",
            "ssh://",
            "rig..example.com",
            "-rig.example.com",
            "rig-.example.com",
            "rig/etc",
            "rig?tmux=main",
        )) {
            assertNull("expected $nonsense to be turned down", QuickConnect.parse(nonsense))
        }
    }

    @Test
    fun `a tmux window in the link becomes the startup command`() {
        val h = QuickConnect.parse("ssh://pilif@rig?tmux=work:2")!!
        assertEquals("pilif", h.username)
        assertEquals("rig", h.hostname)
        assertEquals("tmux select-window -t work:2 \\; attach -t work", h.startupCommand)
    }

    @Test
    fun `a tmux session with no window is just attached to`() {
        assertEquals("tmux attach -t main", QuickConnect.parse("ssh://rig?tmux=main")?.startupCommand)
    }

    @Test
    fun `the tmux target survives a port and other query parameters`() {
        val h = QuickConnect.parse("ssh://rig:2222/?fold=1&tmux=work:0#top")!!
        assertEquals(2222, h.port)
        assertEquals("tmux select-window -t work:0 \\; attach -t work", h.startupCommand)
    }

    @Test
    fun `a tmux value that is not a tmux target is dropped, not quoted`() {
        // The link comes from outside and the startup command goes into a shell.
        for (bad in listOf("ssh://rig?tmux=work;curl+evil", "ssh://rig?tmux=%24(id)", "ssh://rig?tmux="))
            assertEquals("", QuickConnect.parse(bad)?.startupCommand)
    }

    @Test
    fun `a target already saved is recognised, whoever it logs in as`() {
        val saved = listOf(
            Host(hostname = "rig", port = 22, username = "pilif"),
            Host(hostname = "rig", port = 2222, username = "root"),
        )
        assertEquals(saved[0], QuickConnect.saved(saved, QuickConnect.parse("rig")!!))
        assertEquals(saved[1], QuickConnect.saved(saved, QuickConnect.parse("root@rig:2222")!!))
        assertEquals(saved[0], QuickConnect.saved(saved, QuickConnect.parse("ssh://RIG")!!))
        // A different login on a port nothing is saved on is not that host.
        assertNull(QuickConnect.saved(saved, QuickConnect.parse("pilif@rig:2200")!!))
        assertNull(QuickConnect.saved(saved, QuickConnect.parse("root@rig")!!))
    }
}
