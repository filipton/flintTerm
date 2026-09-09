package dev.flint.term.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSshExportTest {
    private val key = Identity(name = "phone-key", privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n", publicKey = "ssh-ed25519 AAAA")
    private val keystoreKey = Identity(name = "keystore", hardware = true, backing = "StrongBox")
    private val tokenKey = Identity(name = "yubikey", securityKey = true)
    private val bastion = Host(label = "bastion", hostname = "10.0.2.2", port = 2222, username = "pilif", authType = AuthType.KEY, identityId = key.id)

    @Test
    fun roundTripsThroughTheImporter() {
        val lab = Host(
            label = "lab box", hostname = "192.168.50.10", username = "admin",
            authType = AuthType.KEY, identityId = key.id, jumpHostId = bastion.id,
        )
        val text = OpenSshExport.export(listOf(bastion, lab), emptyList(), emptyList(), listOf(key))
        val back = OpenSshImport.parseConfig(text)

        assertEquals(listOf("bastion", "lab-box"), back.map { it.alias })
        assertEquals("10.0.2.2", back[0].hostName)
        assertEquals("pilif", back[0].user)
        assertEquals(2222, back[0].port)
        assertEquals("~/.ssh/phone-key", back[0].identityFile)
        assertNull(back[0].proxyJump)
        assertEquals("192.168.50.10", back[1].hostName)
        assertEquals("admin", back[1].user)
        assertEquals(22, back[1].port)
        assertEquals("bastion", back[1].proxyJump)
    }

    @Test
    fun neverWritesAPassword() {
        val host = Host(label = "router", hostname = "10.0.0.1", username = "root", authType = AuthType.PASSWORD, password = "hunter2")
        val account = Account(name = "shared", username = "ops", authType = AuthType.PASSWORD, password = "correct-horse")
        val viaAccount = Host(label = "web", hostname = "web.example.org", accountId = account.id)
        val text = OpenSshExport.export(listOf(host, viaAccount), emptyList(), listOf(account), emptyList())

        assertFalse(text.contains("hunter2"))
        assertFalse(text.contains("correct-horse"))
        assertTrue(text.contains("Will ask for a password: router, web"))
    }

    @Test
    fun writesWhatAHostInheritsFromItsGroup() {
        val account = Account(name = "ops", username = "ops", authType = AuthType.KEY, identityId = key.id)
        val group = HostGroup(name = "datacentre", accountId = account.id, jumpHostId = bastion.id)
        // Nothing of its own beyond the address: everything it connects with comes from the group.
        val member = Host(label = "db", hostname = "10.10.0.5", groupId = group.id)
        val text = OpenSshExport.export(listOf(bastion, member), listOf(group), listOf(account), listOf(key))
        val db = OpenSshImport.parseConfig(text).single { it.alias == "db" }

        assertEquals("ops", db.user)
        assertEquals("~/.ssh/phone-key", db.identityFile)
        assertEquals("bastion", db.proxyJump)
    }

    @Test
    fun turnsAwkwardLabelsIntoUsableAliases() {
        val hosts = listOf(
            Host(label = "Home NAS", hostname = "nas.lan"),
            Host(label = "Home NAS", hostname = "nas2.lan"),
            Host(label = "prod *", hostname = "prod.example.org"),
            Host(label = "  ", hostname = "bare.example.org"),
        )
        val aliases = OpenSshImport.parseConfig(OpenSshExport.export(hosts, emptyList(), emptyList(), emptyList())).map { it.alias }

        assertEquals(listOf("Home-NAS", "Home-NAS-2", "prod", "bare.example.org"), aliases)
        // A wildcard surviving into an alias would silently apply the block to every host in the file.
        assertTrue(aliases.none { a -> a.any { it == '*' || it == '?' || it == '!' || it.isWhitespace() } })
    }

    @Test
    fun writesAJumpChainAsProxyJump() {
        val middle = Host(label = "middle", hostname = "10.1.1.1", jumpHostId = bastion.id)
        val inner = Host(label = "inner", hostname = "10.2.2.2", jumpHostId = middle.id)
        val back = OpenSshImport.parseConfig(
            OpenSshExport.export(listOf(bastion, middle, inner), emptyList(), emptyList(), listOf(key)),
        ).associateBy { it.alias }

        assertNull(back.getValue("bastion").proxyJump)
        assertEquals("bastion", back.getValue("middle").proxyJump)
        assertEquals("middle", back.getValue("inner").proxyJump)
    }

    @Test
    fun refusesToWriteALoopingJumpChain() {
        val a = Host(label = "a", hostname = "10.0.0.1")
        val b = Host(label = "b", hostname = "10.0.0.2", jumpHostId = a.id)
        val looping = a.copy(jumpHostId = b.id)
        val text = OpenSshExport.export(listOf(looping, b), emptyList(), emptyList(), emptyList())

        assertFalse(text.contains("ProxyJump"))
        assertTrue(text.contains("loops back on itself"))
    }

    @Test
    fun namesTheThingsSshCannotSay() {
        val host = Host(
            label = "nas", hostname = "nas.lan", tunnelId = "t1", mosh = true,
            wol = WolSettings(enabled = true, mac = "aa:bb:cc:dd:ee:ff"),
            knock = KnockSettings(enabled = true, sequence = "7000,8000"),
        )
        val telnet = Host(label = "switch", hostname = "10.0.0.9", protocol = Protocol.TELNET)
        val text = OpenSshExport.export(listOf(host, telnet), emptyList(), emptyList(), emptyList())

        assertTrue(text.contains("WireGuard"))
        assertTrue(text.contains("Mosh"))
        assertTrue(text.contains("wake-on-LAN"))
        assertTrue(text.contains("port-knock"))
        // A telnet host has no honest Host block, so it is named as absent instead.
        assertTrue(text.contains("switch is not in this file: it speaks telnet"))
        assertEquals(listOf("nas"), OpenSshImport.parseConfig(text).map { it.alias })
    }

    @Test
    fun saysWhenAKeyCannotLeaveTheDevice() {
        val hosts = listOf(
            Host(label = "vault", hostname = "vault.lan", authType = AuthType.KEY, identityId = keystoreKey.id),
            Host(label = "gate", hostname = "gate.lan", authType = AuthType.KEY, identityId = tokenKey.id),
        )
        val text = OpenSshExport.export(hosts, emptyList(), emptyList(), listOf(keystoreKey, tokenKey))

        assertTrue(text.contains("cannot leave the device at all: vault, gate"))
        assertTrue(text.contains("in this phone's keystore"))
        assertTrue(text.contains("on a security key"))
        assertNull(OpenSshImport.parseConfig(text).first().identityFile)
    }

    @Test
    fun writesOnlyTheKeysItCanRead() {
        val hosts = listOf(
            bastion,
            Host(label = "vault", hostname = "vault.lan", authType = AuthType.KEY, identityId = keystoreKey.id),
            Host(label = "gate", hostname = "gate.lan", authType = AuthType.KEY, identityId = tokenKey.id),
        )
        val unused = Identity(name = "old", privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nBBBB\n")
        val files = OpenSshExport.keyFiles(hosts, emptyList(), emptyList(), listOf(key, keystoreKey, tokenKey, unused))

        assertEquals(listOf("phone-key"), files.map { it.name })
        assertEquals(key.privateKey, files[0].privateKey)
        // The path the config points at is the path the key is written to.
        assertTrue(OpenSshExport.export(hosts, emptyList(), emptyList(), listOf(key, keystoreKey, tokenKey))
            .contains("IdentityFile ${OpenSshExport.identityPath(files[0].name)}"))
    }

    @Test
    fun writesForwardsAndTheRestOfWhatSshUnderstands() {
        val host = Host(
            label = "dev", hostname = "dev.example.org", username = "pilif", forwardAgent = true,
            startupCommand = "cd /srv\nls",
            env = listOf(EnvEntry("LC_TERM", "android term")),
            forwards = listOf(
                PortForward(type = ForwardType.LOCAL, bindPort = 8080, targetHost = "localhost", targetPort = 80),
                PortForward(type = ForwardType.REMOTE, bindHost = "0.0.0.0", bindPort = 9000, targetHost = "127.0.0.1", targetPort = 22),
                PortForward(type = ForwardType.DYNAMIC, bindPort = 1080),
                PortForward(type = ForwardType.LOCAL, bindPort = 5432, targetPort = 5432, autoStart = false),
            ),
        )
        val text = OpenSshExport.export(listOf(host), emptyList(), emptyList(), emptyList(), keepaliveSeconds = 30)

        assertTrue(text.contains("    LocalForward 127.0.0.1:8080 localhost:80"))
        assertTrue(text.contains("    RemoteForward 0.0.0.0:9000 127.0.0.1:22"))
        assertTrue(text.contains("    DynamicForward 127.0.0.1:1080"))
        assertTrue(text.contains("    # LocalForward 127.0.0.1:5432 localhost:5432"))
        assertTrue(text.contains("    ForwardAgent yes"))
        assertTrue(text.contains("""    SetEnv LC_TERM="android term""""))
        assertTrue(text.contains("    RequestTTY yes"))
        assertTrue(text.contains("    RemoteCommand cd /srv; ls"))
        assertTrue(text.contains("    ServerAliveInterval 30"))
    }
}
