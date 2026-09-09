package dev.flint.term.data.imports

import dev.flint.term.data.Host
import dev.flint.term.data.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectBotImportTest {
    /**
     * Shaped like the real thing: booleans as 0/1, a NULL column left out
     * altogether rather than written as null, and the `profiles` array that
     * carries the settings this app has no per-host home for.
     */
    private val export = """
        {
          "version": 8,
          "profiles": [
            { "id": 1, "name": "Default", "fontSize": 12, "delKey": "del", "encoding": "UTF-8" }
          ],
          "hosts": [
            {
              "id": 10, "nickname": "branch-host", "protocol": "ssh", "username": "user",
              "hostname": "example.com", "port": 22, "color": "red", "useKeys": 1,
              "useAuthAgent": "no", "pubkeyId": -1, "wantSession": 1, "compression": 0,
              "stayConnected": 0, "quickDisconnect": 0, "scrollbackLines": 140,
              "useCtrlAltAsMetaKey": 0, "profileId": 1, "ipVersion": "IPV4_AND_IPV6"
            },
            {
              "id": 11, "nickname": "switch", "protocol": "telnet",
              "hostname": "10.0.0.1", "port": 23, "profileId": 1
            },
            {
              "id": 12, "nickname": "rig", "protocol": "ssh", "username": "pilif",
              "hostname": "10.0.2.2", "port": 2222, "color": "gray",
              "postLogin": "tmux attach || tmux", "profileId": 1
            },
            { "id": 13, "nickname": "phone shell", "protocol": "local", "hostname": "", "port": 22 },
            { "id": 14, "nickname": "no host at all", "protocol": "ssh", "port": 22 }
          ],
          "port_forwards": []
        }
    """.trimIndent()

    @Test
    fun readsTheHostsAndLeavesOutWhatIsNotOne() {
        val hosts = ConnectBotImport.parse(export)
        // The local shell and the row with no hostname are not machines.
        assertEquals(listOf("branch-host", "switch", "rig"), hosts.map { it.label })
    }

    @Test
    fun mapsTheFieldsThatHaveAnEquivalent() {
        val hosts = ConnectBotImport.parse(export)
        val branch = hosts[0]
        assertEquals("example.com", branch.hostname)
        assertEquals(22, branch.port)
        assertEquals("user", branch.username)
        assertTrue("red should become a color", branch.color != 0)
        assertEquals("", branch.startupCommand)

        val rig = hosts[2]
        assertEquals("tmux attach || tmux", rig.startupCommand)
        assertEquals(2222, rig.port)
        assertEquals("pilif", rig.username)
    }

    @Test
    fun telnetStaysTelnet() {
        val switch = ConnectBotImport.parse(export)[1]
        assertEquals(Protocol.TELNET, switch.protocol)
        assertEquals("", switch.username)
        assertEquals(23, switch.port)
    }

    @Test
    fun aMissingColorIsSimplyNoColor() {
        assertEquals(0, ConnectBotImport.parse(export)[1].color)
    }

    @Test
    fun readsTheSchemaVersion() {
        assertEquals(8, ConnectBotImport.schemaVersion(export))
        assertNull(ConnectBotImport.schemaVersion("""{"hosts":[]}"""))
    }

    @Test
    fun aFileFromSomewhereElseIsRefusedRatherThanMisread() {
        assertFalse(ConnectBotImport.looksLikeExport("not json at all"))
        assertFalse(ConnectBotImport.looksLikeExport("""{"servers":[]}"""))
        assertTrue(ConnectBotImport.looksLikeExport("""{"hosts":[]}"""))
        assertEquals(emptyList<ImportedHost>(), ConnectBotImport.parse("not json at all"))
    }

    @Test
    fun aFieldTheExporterOmittedIsNotAnError() {
        // Every optional column left out: ConnectBot writes nothing at all for
        // a NULL, so this is the shape of an ordinary host, not a damaged one.
        val bare = """{"version":8,"hosts":[{"nickname":"bare","protocol":"ssh","hostname":"h","port":22}]}"""
        val host = ConnectBotImport.parse(bare).single()
        assertEquals("bare", host.label)
        assertEquals("", host.username)
        assertEquals(0, host.color)
    }

    @Test
    fun importingTwiceAddsNothingTheSecondTime() {
        val hosts = ConnectBotImport.parse(export)
        val saved = hosts.map { it.toHost() }
        val again = planImport(hosts, saved)
        assertEquals(0, again.fresh.size)
        assertEquals(3, again.alreadyHere)
        assertEquals("0 hosts, 3 already here", again.describe())
    }

    @Test
    fun aHostAlreadyHereIsCountedAndTheRestAreOffered() {
        val hosts = ConnectBotImport.parse(export)
        val existing = listOf(Host(hostname = "EXAMPLE.COM", port = 22, username = "user", label = "mine"))
        val plan = planImport(hosts, existing)
        assertEquals(listOf("switch", "rig"), plan.fresh.map { it.label })
        assertEquals(1, plan.alreadyHere)
        assertEquals("2 hosts, 1 already here", plan.describe())
        // Nothing about the saved host is touched by the plan itself.
        assertEquals("mine", existing.single().label)
    }
}
