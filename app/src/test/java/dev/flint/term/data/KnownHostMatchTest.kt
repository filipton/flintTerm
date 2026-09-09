package dev.flint.term.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fixture below is not hand-rolled: it is the verbatim output of
 * `ssh-keygen -H` (OpenSSH 10.5p1) over a file holding the three plain lines
 * named in [PLAIN]. Testing our HMAC against our own encoder would only prove
 * we agree with ourselves; the point is to agree with OpenSSH.
 */
class KnownHostMatchTest {
    private companion object {
        const val KEY1 = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIKTMsbzSJgIc+4/XVfil8YMA/BgsdApO/AXmr5R6o9dk"
        const val KEY2 = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIPi/LLicZZ1Ttil2h/tiv1rTrX+ESCvEFGYAoKInw61L"

        /** What went into `ssh-keygen -H`, in the order the hashed lines came out. */
        val PLAIN = listOf("shell.example.org", "[shell.example.org]:2222", "[Mixed.Example.NET]:2222")

        val HASHED = """
            |1|flNVUVlqXAVh8yauJM8455CWxVc=|ArNwUzL/d2ouWvSNk/2UGLs2OPs= $KEY1
            |1|j0XY5X68Mq6eiomfd5BWRpT7VTA=|VnCIVcxqE9E1IwpgqD8JkMphfY4= $KEY2
            |1|Jg8gpeH8D0kaH73xaTxLT9o7LxI=|YWWa2oloutPG3+Obdg/V/CClEFk= $KEY1
        """.trimIndent()
    }

    private val entries = OpenSshImport.parseKnownHosts(HASHED)

    @Test fun `ssh-keygen -H output is imported as hashed entries`() {
        assertEquals(3, entries.size)
        assertTrue(entries.all { it.isHashed })
        // The name is gone, so nothing may be shown in its place.
        assertTrue(entries.all { it.host.isEmpty() && it.port == 0 })
        assertEquals("flNVUVlqXAVh8yauJM8455CWxVc=", entries[0].hashSalt)
        assertEquals("ArNwUzL/d2ouWvSNk/2UGLs2OPs=", entries[0].hashedHost)
        assertEquals(KEY2.substringAfter(' '), entries[1].keyBase64)
    }

    @Test fun `an entry matches the host it was made from and nothing else`() {
        val e = entries[0]
        assertTrue(KnownHostMatch.matches(e, "shell.example.org", 22))
        assertFalse(KnownHostMatch.matches(e, "shell.example.com", 22))
        assertFalse(KnownHostMatch.matches(e, "hell.example.org", 22))
        assertFalse(KnownHostMatch.matches(e, "", 22))
        // The default port is hashed bare, so the same name on another port is another entry.
        assertFalse(KnownHostMatch.matches(e, "shell.example.org", 2222))
        // And no other line in the file answers to that name either.
        assertEquals(1, entries.count { KnownHostMatch.matches(it, "shell.example.org", 22) })
    }

    @Test fun `a non-default port is hashed in the bracket form`() {
        val e = entries[1]
        assertTrue(KnownHostMatch.matches(e, "shell.example.org", 2222))
        assertFalse(KnownHostMatch.matches(e, "shell.example.org", 22))
        assertFalse(KnownHostMatch.matches(e, "shell.example.org", 2022))
        assertEquals("[shell.example.org]:2222", KnownHostMatch.hostField("shell.example.org", 2222))
        assertEquals("shell.example.org", KnownHostMatch.hostField("shell.example.org", 22))
    }

    @Test fun `the hash covers the lowercase form, as ssh writes and reads it`() {
        val e = entries[2]
        assertTrue(KnownHostMatch.matches(e, "mixed.example.net", 2222))
        assertTrue(KnownHostMatch.matches(e, "Mixed.Example.NET", 2222))
        assertEquals("[mixed.example.net]:2222", KnownHostMatch.hostField("Mixed.Example.NET", 2222))
    }

    @Test fun `a malformed entry matches nothing and is never imported`() {
        val junk = listOf(
            "|1|hashed=|abc= $KEY1",             // neither half is base64 of the right length
            "|1|flNVUVlqXAVh8yauJM8455CWxVc= $KEY1", // no hash half at all
            "|2|flNVUVlqXAVh8yauJM8455CWxVc=|ArNwUzL/d2ouWvSNk/2UGLs2OPs= $KEY1", // unknown hash type
            "|1||ArNwUzL/d2ouWvSNk/2UGLs2OPs= $KEY1", // empty salt
            "|1|flNVUVlqXAVh8yauJM8455CWxVc=|not base64 $KEY1",
        )
        for (line in junk) {
            assertEquals(line, emptyList<KnownHost>(), OpenSshImport.parseKnownHosts(line))
            assertNull(line, KnownHostMatch.parseField(line.substringBefore(' ')))
        }
        // Even carried into a stored entry by hand, an unreadable hash is not a match.
        val bad = KnownHost("", 0, "ssh-ed25519", "AAAA", "SHA256:x", hashSalt = "??", hashedHost = "??")
        assertFalse(KnownHostMatch.matches(bad, "shell.example.org", 22))
        assertFalse(KnownHostMatch.matches("", "", "shell.example.org", 22))
    }

    @Test fun `plain entries are read exactly as before`() {
        val plain = OpenSshImport.parseKnownHosts(
            """
            shell.example.org $KEY1
            [shell.example.org]:2222,alias.local $KEY2
            """.trimIndent()
        )
        assertEquals(3, plain.size)
        assertTrue(plain.none { it.isHashed })
        assertEquals("shell.example.org" to 22, plain[0].host to plain[0].port)
        assertEquals("shell.example.org" to 2222, plain[1].host to plain[1].port)
        assertEquals("alias.local" to 22, plain[2].host to plain[2].port)
        assertNotNull(plain[0].fingerprint)
        // A plain entry is never answered by the hashed path.
        assertFalse(KnownHostMatch.matches(plain[0], "shell.example.org", 22))
    }

    @Test fun `lookup prefers a name it knows, then walks the hashed ones`() {
        assertEquals(entries[0], KnownHostMatch.find(entries, "shell.example.org", 22))
        assertEquals(entries[1], KnownHostMatch.find(entries, "shell.example.org", 2222))
        assertEquals(entries[2], KnownHostMatch.find(entries, "mixed.example.net", 2222))
        assertNull(KnownHostMatch.find(entries, "shell.example.org", 2022))
        assertNull(KnownHostMatch.find(entries, "nobody.example.org", 22))

        // A plain entry for the same name wins, because it is the one that was saved for it.
        val plain = KnownHost("shell.example.org", 22, "ssh-rsa", "AAAAB3", "SHA256:plain")
        val both = entries + plain
        assertEquals(plain, KnownHostMatch.find(both, "shell.example.org", 22))
        assertEquals(entries[1], KnownHostMatch.find(both, "shell.example.org", 2222))
    }

    @Test fun `entries stand apart even though hashed ones share an empty name`() {
        // Forgetting or replacing one hashed entry must not take the rest with it.
        assertTrue(entries[0].sameHostAs(entries[0].copy(keyBase64 = "other")))
        assertFalse(entries[0].sameHostAs(entries[1]))
        assertFalse(entries[0].sameHostAs(KnownHost("", 0, "ssh-rsa", "AAAA", "SHA256:x")))
        val left = entries.filterNot { it.sameHostAs(entries[1]) }
        assertEquals(listOf(entries[0], entries[2]), left)
    }
}
