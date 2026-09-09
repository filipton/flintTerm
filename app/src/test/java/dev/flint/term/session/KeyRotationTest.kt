package dev.flint.term.session

import dev.flint.term.data.Account
import dev.flint.term.data.AuthType
import dev.flint.term.data.Host
import dev.flint.term.data.HostGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KeyRotationTest {
    private val key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExampleKeyForTests androidterm"

    // ---- who uses a key ----------------------------------------------------

    @Test
    fun `a host that names the key itself`() {
        val host = Host(label = "web", authType = AuthType.KEY, identityId = "k1")
        val users = KeyRotation.users("k1", listOf(host), emptyList(), emptyList())
        assertEquals(listOf("web"), users.map { it.host.label })
        assertEquals(null, users[0].through)
    }

    @Test
    fun `a host that gets the key from an account`() {
        val account = Account(id = "a1", name = "root", username = "root", authType = AuthType.KEY, identityId = "k1")
        val host = Host(label = "db", accountId = "a1", authType = AuthType.PASSWORD, identityId = null)
        val users = KeyRotation.users("k1", listOf(host), listOf(account), emptyList())
        assertEquals(1, users.size)
        assertEquals("root", users[0].through?.name)
        assertTrue(users[0].reason.contains("root"))
    }

    @Test
    fun `a host that gets the account from its group`() {
        val account = Account(id = "a1", name = "ops", username = "ops", authType = AuthType.KEY, identityId = "k1")
        val group = HostGroup(id = "g1", name = "datacenter", accountId = "a1")
        val host = Host(id = "h1", label = "edge", groupId = "g1", authType = AuthType.PASSWORD)
        val users = KeyRotation.users("k1", listOf(host), listOf(account), listOf(group))
        assertEquals(1, users.size)
        assertEquals("datacenter", users[0].viaGroup?.name)
        assertTrue(users[0].reason.contains("datacenter"))
    }

    @Test
    fun `a host with its own key is not counted for the account's key`() {
        val account = Account(id = "a1", name = "root", authType = AuthType.KEY, identityId = "k1")
        // Naming the account is what hands over the login, so the host's own
        // key is the one that is ignored — not the other way round.
        val host = Host(label = "web", accountId = "a1", authType = AuthType.KEY, identityId = "k2")
        assertEquals(1, KeyRotation.users("k1", listOf(host), listOf(account), emptyList()).size)
        assertEquals(0, KeyRotation.users("k2", listOf(host), listOf(account), emptyList()).size)
    }

    @Test
    fun `a host that logs in with a password uses no key at all`() {
        val host = Host(label = "old", authType = AuthType.PASSWORD, identityId = "k1")
        assertEquals(0, KeyRotation.users("k1", listOf(host), emptyList(), emptyList()).size)
    }

    // ---- taking the old key out of authorized_keys -------------------------
    // Run through a real shell: the script's whole job is to be correct in one,
    // and a string comparison would only prove it is the string I typed.

    private fun run(script: String): String {
        val p = ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        return out
    }

    private fun authorizedKeys(vararg lines: String): File =
        File.createTempFile("authorized_keys", "").apply { writeText(lines.joinToString("\n") + "\n") }

    @Test
    fun `removes only the line it was given`() {
        val other = "ssh-rsa AAAAB3NzaC1yc2ESomeoneElse other@laptop"
        val file = authorizedKeys(other, key, "# a comment")
        assertEquals("removed", run(KeyRotation.removeScript(key, file.absolutePath)))
        assertEquals(listOf(other, "# a comment"), file.readLines())
        file.delete()
    }

    @Test
    fun `the last key leaves an empty file rather than a failure`() {
        val file = authorizedKeys(key)
        assertEquals("removed", run(KeyRotation.removeScript(key, file.absolutePath)))
        assertEquals("", file.readText().trim())
        file.delete()
    }

    @Test
    fun `a key that is not there changes nothing`() {
        val other = "ssh-rsa AAAAB3NzaC1yc2ESomeoneElse other@laptop"
        val file = authorizedKeys(other)
        assertEquals("not there", run(KeyRotation.removeScript(key, file.absolutePath)))
        assertEquals(listOf(other), file.readLines())
        file.delete()
    }

    @Test
    fun `no authorized_keys is not an error`() {
        val missing = File.createTempFile("gone", "").apply { delete() }
        assertEquals("no authorized_keys", run(KeyRotation.removeScript(key, missing.absolutePath)))
    }

    @Test
    fun `a line that merely starts with the key survives`() {
        // A whole-line match is what keeps `command=` and `from=` prefixes, and
        // any longer comment, from being swept away with the bare key.
        val restricted = "command=\"/usr/bin/true\" $key"
        val file = authorizedKeys(restricted)
        assertEquals("not there", run(KeyRotation.removeScript(key, file.absolutePath)))
        assertEquals(listOf(restricted), file.readLines())
        file.delete()
    }

    @Test
    fun `the file keeps its permissions`() {
        val file = authorizedKeys(key, "ssh-rsa AAAAB3Other x@y")
        run("chmod 600 ${file.absolutePath}")
        run(KeyRotation.removeScript(key, file.absolutePath))
        assertEquals("600", run("stat -c %a ${file.absolutePath}"))
        file.delete()
    }
}
