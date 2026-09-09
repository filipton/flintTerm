package dev.flint.term.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountTest {
    private val key = Account(id = "a1", name = "Deploy", username = "deploy", authType = AuthType.KEY, identityId = "k1")

    @Test
    fun `a host without an account keeps its own login`() {
        val host = Host(username = "root", authType = AuthType.PASSWORD, password = "hunter2")
        val out = host.withAccount(key)
        assertEquals("root", out.username)
        assertEquals(AuthType.PASSWORD, out.authType)
        assertEquals("hunter2", out.password)
    }

    @Test
    fun `an account supplies the username and the key`() {
        val host = Host(accountId = "a1", username = "root", authType = AuthType.PASSWORD, password = "hunter2")
        val out = host.withAccount(key)
        assertEquals("deploy", out.username)
        assertEquals(AuthType.KEY, out.authType)
        assertEquals("k1", out.identityId)
        assertEquals("", out.password)
    }

    @Test
    fun `an account without a username leaves the host's own in place`() {
        val host = Host(accountId = "a1", username = "pi")
        val out = host.withAccount(key.copy(username = "  "))
        assertEquals("pi", out.username)
        assertEquals(AuthType.KEY, out.authType)
    }

    @Test
    fun `an account belonging to another host is ignored`() {
        val host = Host(accountId = "other", username = "root", authType = AuthType.PASSWORD)
        val out = host.withAccount(key)
        assertEquals("root", out.username)
        assertNull(out.identityId)
    }

    @Test
    fun `the stored record is left as written`() {
        val host = Host(accountId = "a1", username = "root", authType = AuthType.PASSWORD, password = "hunter2")
        host.withAccount(key)
        assertEquals("root", host.username)
        assertEquals("hunter2", host.password)
    }
}
