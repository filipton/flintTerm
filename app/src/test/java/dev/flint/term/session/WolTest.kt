package dev.flint.term.session

import dev.flint.term.data.WolSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class WolTest {

    @Test
    fun `every address of a host is a candidate for waking it`() {
        val host = dev.flint.term.data.Host(
            hostname = "box.tail1234.ts.net",
            addresses = listOf(
                dev.flint.term.data.HostAddress(hostname = "192.168.1.10", label = "Home LAN"),
                dev.flint.term.data.HostAddress(hostname = ""),
            ),
        )
        assertEquals(listOf("box.tail1234.ts.net", "192.168.1.10"), Wol.candidates(host))
        // A magic packet is link-local, so the private address is the one a
        // remote waker can do anything with — not the tailnet name that comes first.
        assertEquals("192.168.1.10", Wol.wakeTarget(host))
    }

    @Test
    fun `an explicit broadcast wins over every address`() {
        val w = dev.flint.term.data.WolSettings(mac = "a8:a1:59:23:8e:88", broadcast = "192.168.1.255")
        assertEquals(listOf("192.168.1.255"), Wol.broadcastTargets(w, listOf("10.9.9.9", "192.168.1.10")))
    }

    @Test
    fun `with no address on a local network the packet goes out every interface`() {
        // Nothing here is on one of this machine's networks, so a single limited
        // broadcast would leave on the default route alone and miss the rest.
        val w = dev.flint.term.data.WolSettings(mac = "a8:a1:59:23:8e:88")
        val targets = Wol.broadcastTargets(w, listOf("198.51.100.4", "server.example.com"))
        assertTrue("expected at least one target, got $targets", targets.isNotEmpty())
        assertEquals(targets.distinct(), targets)
        assertTrue("targets should be broadcast addresses: $targets", targets.all { it == Wol.LIMITED || it.count { c -> c == '.' } == 3 })
    }

    @Test
    fun `an explicit broadcast is used as given`() {
        assertEquals("192.168.1.255", Wol.resolveBroadcast("192.168.1.255", "192.168.1.10"))
        assertEquals("10.0.0.255", Wol.resolveBroadcast("  10.0.0.255  ", ""))
    }

    @Test
    fun `auto falls back to the limited broadcast for a name or an unknown subnet`() {
        // A hostname carries no subnet, and 203.0.113.7 is not on any local interface.
        assertEquals(Wol.LIMITED, Wol.resolveBroadcast("", "server.example.com"))
        assertEquals(Wol.LIMITED, Wol.resolveBroadcast("", "203.0.113.7"))
    }

    @Test
    fun `a fixed broadcast is pinned in the remote script`() {
        val script = Wol.remoteScript(WolSettings(mac = "a8:a1:59:23:8e:88", broadcast = "192.168.1.255"), "192.168.1.10")
        assertTrue(script, script.contains("B='192.168.1.255'"))
        assertTrue(script, !script.contains("ip route get"))
    }

    @Test
    fun `auto makes the remote script work out its own broadcast`() {
        val script = Wol.remoteScript(WolSettings(mac = "a8:a1:59:23:8e:88", broadcast = ""), "192.168.1.10")
        // It asks which interface reaches the target, reads that interface's brd, then falls back.
        assertTrue(script, script.contains("ip route get '192.168.1.10'"))
        assertTrue(script, script.contains("brd"))
        assertTrue(script, script.contains("B='${Wol.LIMITED}'"))
    }

    @Test
    fun `a single quote in the hostname cannot break out of the script`() {
        val script = Wol.remoteScript(WolSettings(mac = "a8:a1:59:23:8e:88"), "hos't; rm -rf /")
        assertTrue(script, !script.contains("host'; rm"))
        assertTrue(script, script.contains("ip route get 'host; rm -rf /'"))
    }

    /** The script is built by string concatenation, so prove a real shell accepts it. */
    @Test
    fun `generated scripts are valid shell`() {
        val sh = listOf("/bin/sh", "/system/bin/sh").map(::File).firstOrNull { it.canExecute() }
        assumeTrue("no /bin/sh available", sh != null)
        for (broadcast in listOf("", "192.168.1.255")) {
            val script = Wol.remoteScript(WolSettings(mac = "a8:a1:59:23:8e:88", broadcast = broadcast), "192.168.1.10")
            val file = File.createTempFile("wol", ".sh").apply { writeText(script); deleteOnExit() }
            val p = ProcessBuilder(sh!!.path, "-n", file.path).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertEquals("shell rejected the script (broadcast=\"$broadcast\"):\n$out\n--- script ---\n$script", 0, p.waitFor())
        }
    }
}
