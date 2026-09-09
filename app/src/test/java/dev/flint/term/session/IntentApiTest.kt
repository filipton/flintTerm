package dev.flint.term.session

import dev.flint.term.data.Host
import dev.flint.term.data.Snippet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentApiTest {
    private val web = Host(id = "id-web", label = "Web", hostname = "web1.example.com", username = "deploy")
    private val db = Host(id = "id-db", label = "Database", hostname = "db.example.com", username = "root", port = 2222)
    private val spare = Host(id = "id-spare", label = "", hostname = "web1.example.com", username = "root")
    private val hosts = listOf(web, db, spare)

    @Test
    fun `reads each action with the extras it needs`() {
        assertEquals(
            IntentApi.Request.Connect("web1"),
            IntentApi.request(IntentApi.ACTION_CONNECT, "web1", null, null),
        )
        assertEquals(
            IntentApi.Request.Run("web1", "uptime"),
            IntentApi.request(IntentApi.ACTION_RUN, "web1", "uptime", null),
        )
        assertEquals(
            IntentApi.Request.Type("deploy", "web1"),
            IntentApi.request(IntentApi.ACTION_SNIPPET, "web1", null, "deploy"),
        )
        assertEquals(
            IntentApi.Request.Close(null),
            IntentApi.request(IntentApi.ACTION_DISCONNECT, null, null, null),
        )
    }

    @Test
    fun `blank extras count as missing rather than as an empty name`() {
        assertEquals(IntentApi.Request.Close(null), IntentApi.request(IntentApi.ACTION_DISCONNECT, "   ", null, null))
        assertEquals(IntentApi.Request.Connect("web1"), IntentApi.request(IntentApi.ACTION_CONNECT, "  web1 ", null, null))
        assertEquals(IntentApi.Request.Type("deploy", null), IntentApi.request(IntentApi.ACTION_SNIPPET, "", null, "deploy"))
    }

    @Test
    fun `says which extra is missing instead of half doing the call`() {
        val cases = listOf(
            IntentApi.request(IntentApi.ACTION_CONNECT, null, null, null),
            IntentApi.request(IntentApi.ACTION_RUN, "web1", " ", null),
            IntentApi.request(IntentApi.ACTION_RUN, null, "uptime", null),
            IntentApi.request(IntentApi.ACTION_SNIPPET, "web1", null, null),
        )
        cases.forEach { assertTrue("$it", it is IntentApi.Request.Refused) }
        assertEquals(
            "RUN needs a command extra",
            (IntentApi.request(IntentApi.ACTION_RUN, "web1", " ", null) as IntentApi.Request.Refused).reason,
        )
    }

    @Test
    fun `an action we do not speak is refused by name`() {
        val refused = IntentApi.request("dev.flint.term.action.REBOOT", "web1", null, null)
        assertEquals(
            "“dev.flint.term.action.REBOOT” is not one of CONNECT, RUN, SNIPPET or DISCONNECT",
            (refused as IntentApi.Request.Refused).reason,
        )
        assertTrue(IntentApi.request(null, null, null, null) is IntentApi.Request.Refused)
    }

    @Test
    fun `a host is found by id, by label, by user at hostname and by hostname`() {
        assertEquals(listOf(web), IntentApi.resolveHost(hosts, "id-web"))
        assertEquals(listOf(web), IntentApi.resolveHost(hosts, "web"))
        assertEquals(listOf(web), IntentApi.resolveHost(hosts, "deploy@web1.example.com"))
        assertEquals(listOf(db), IntentApi.resolveHost(hosts, "db.example.com"))
    }

    @Test
    fun `the label is read the way it is written, in any case, and with spaces around it`() {
        assertEquals(listOf(db), IntentApi.resolveHost(hosts, "DATABASE"))
        assertEquals(listOf(db), IntentApi.resolveHost(hosts, "  Database  "))
    }

    @Test
    fun `a host on a port of its own is found the way the app writes it`() {
        assertEquals(listOf(db), IntentApi.resolveHost(hosts, "root@db.example.com:2222"))
    }

    @Test
    fun `a narrower match wins over a wider one`() {
        // Both saved hosts live on web1.example.com; naming one of them by its
        // login has to reach that one rather than count as an ambiguity.
        assertEquals(listOf(spare), IntentApi.resolveHost(hosts, "root@web1.example.com"))
    }

    @Test
    fun `a name that could mean several hosts names them all`() {
        val found = IntentApi.resolveHost(hosts, "web1.example.com")
        assertEquals(listOf(web, spare), found)
        assertEquals(
            "“web1.example.com” matches 2 hosts: Web, root@web1.example.com",
            IntentApi.problem("host", "web1.example.com", found) { it.displayName },
        )
    }

    @Test
    fun `a name that means nothing says so`() {
        assertEquals(emptyList<Host>(), IntentApi.resolveHost(hosts, "staging"))
        assertEquals(emptyList<Host>(), IntentApi.resolveHost(hosts, "   "))
        assertEquals(
            "No host matches “staging”",
            IntentApi.problem("host", "staging", IntentApi.resolveHost(hosts, "staging")) { it.displayName },
        )
        assertNull(IntentApi.problem("host", "web", IntentApi.resolveHost(hosts, "web")) { it.displayName })
    }

    @Test
    fun `a snippet is found by id or by name`() {
        val deploy = Snippet(id = "sn-1", name = "Deploy", command = "make deploy")
        val logs = Snippet(id = "sn-2", name = "Logs", command = "journalctl -f")
        val all = listOf(deploy, logs)
        assertEquals(listOf(deploy), IntentApi.resolveSnippet(all, "sn-1"))
        assertEquals(listOf(logs), IntentApi.resolveSnippet(all, "logs"))
        assertEquals(emptyList<Snippet>(), IntentApi.resolveSnippet(all, "make deploy"))
        assertEquals(
            "No snippet matches “restart”",
            IntentApi.problem("snippet", "restart", IntentApi.resolveSnippet(all, "restart")) { it.name },
        )
    }

    @Test
    fun `a command that printed something and exited zero comes back as it is`() {
        assertEquals(IntentApi.Outcome(0, "14:02  up 9 days\n"), IntentApi.outcome(Result.success("14:02  up 9 days\n")))
    }

    @Test
    fun `a non-zero exit is a status and an output again, not a failure`() {
        val result = Result.failure<String>(RuntimeException("command exited with 2: grep: no such file"))
        assertEquals(IntentApi.Outcome(2, "grep: no such file"), IntentApi.outcome(result))
        assertEquals(IntentApi.Outcome(1, ""), IntentApi.outcome(Result.failure(RuntimeException("command exited with 1: "))))
    }

    @Test
    fun `a failure that was not the command's own counts as one`() {
        assertEquals(
            IntentApi.Outcome(1, "authentication failed: no more methods"),
            IntentApi.outcome(Result.failure(RuntimeException("authentication failed: no more methods"))),
        )
    }

    @Test
    fun `the record names what was asked for, not what came of it`() {
        assertEquals("Run uptime on web1", IntentApi.summarize(IntentApi.Request.Run("web1", "uptime")))
        assertEquals("Type deploy on web1", IntentApi.summarize(IntentApi.Request.Type("deploy", "web1")))
        assertEquals("Type deploy", IntentApi.summarize(IntentApi.Request.Type("deploy", null)))
        assertEquals("Disconnect everything", IntentApi.summarize(IntentApi.Request.Close(null)))
    }

    // ---- who is allowed to call ------------------------------------------

    @Test
    fun `an app has to be allowed by name`() {
        assertFalse(IntentApi.trusted("net.dinglisch.android.taskerm", emptyList()))
        assertTrue(IntentApi.trusted("net.dinglisch.android.taskerm", listOf("net.dinglisch.android.taskerm")))
        assertFalse(IntentApi.trusted("com.evil.app", listOf("net.dinglisch.android.taskerm")))
    }

    @Test
    fun `a caller Android will not name cannot be told apart, so the switch is the gate`() {
        assertTrue(IntentApi.trusted("", emptyList()))
    }

    @Test
    fun `being turned away names the app so it can be allowed`() {
        assertTrue(IntentApi.untrusted("com.android.shell").contains("com.android.shell"))
    }
}
