package dev.flint.term.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenSshImportTest {
    private val config = """
        # comment
        Host *
            ServerAliveInterval 30

        Host bastion
            HostName 10.0.2.2
            User pilif
            Port 2222
            IdentityFile ~/.ssh/phone-key

        Host lab-box lab
            HostName 192.168.50.10
            User admin
            ProxyJump pilif@bastion:2222
            IdentityFile ~/.ssh/id_rsa_missing

        Match host foo
            User nobody

        Host=router
          HostName 10.0.2.2
          Port 2222
          Port 9999
    """.trimIndent()

    @Test
    fun parsesHostBlocks() {
        val e = OpenSshImport.parseConfig(config)
        assertEquals(listOf("bastion", "lab-box", "lab", "router"), e.map { it.alias })
        val bastion = e[0]
        assertEquals("10.0.2.2", bastion.hostName); assertEquals("pilif", bastion.user); assertEquals(2222, bastion.port)
        assertEquals("~/.ssh/phone-key", bastion.identityFile); assertNull(bastion.proxyJump)
        val lab = e[1]
        assertEquals("192.168.50.10", lab.hostName); assertEquals("pilif@bastion:2222", lab.proxyJump); assertEquals(22, lab.port)
        val router = e[3]
        assertEquals("", router.user); assertEquals(2222, router.port) // first value wins
    }

    @Test
    fun planMatchesKeysAndExistingHosts() {
        val entries = OpenSshImport.parseConfig(config)
        val existing = Host(label = "Router", hostname = "10.0.2.2", port = 2222, username = "pilif")
        val key = Identity(name = "phone-key")
        val plans = OpenSshImport.plan(entries, listOf(existing), listOf(key))
        assertEquals(existing, plans[0].existing)
        assertEquals(key, plans[0].identity)
        assertNull(plans[1].identity)
        assertEquals("bastion", plans[1].jumpAlias)
        assertEquals(existing, plans[3].existing) // no User in config → matches any user
    }

    @Test
    fun parsesKnownHosts() {
        val text = """
            [10.0.2.2]:2222 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINbQwQ/jtlRQ31cXLW4mzAha/nOKikzVC74thf31nUwl
            example.org,93.184.216.34 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINbQwQ/jtlRQ31cXLW4mzAha/nOKikzVC74thf31nUwl
            |1|hashed=|abc= ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINbQwQ/jtlRQ31cXLW4mzAha/nOKikzVC74thf31nUwl
            @cert-authority *.corp ssh-rsa AAAA
            # comment
        """.trimIndent()
        val k = OpenSshImport.parseKnownHosts(text)
        assertEquals(3, k.size)
        assertEquals(KnownHost("10.0.2.2", 2222, "ssh-ed25519", "AAAAC3NzaC1lZDI1NTE5AAAAINbQwQ/jtlRQ31cXLW4mzAha/nOKikzVC74thf31nUwl", "SHA256:BB+A3A+4MdhMxM4fSU15WGLHFHnR902HSvLtm2G5Hn0"), k[0])
        assertEquals("example.org", k[1].host); assertEquals(22, k[1].port)
        assertEquals("93.184.216.34", k[2].host)
    }
}
