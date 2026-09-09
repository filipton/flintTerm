package dev.flint.term.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultTest {
    private val key = Identity(id = "k1", name = "laptop", privateKey = "PRIVATE", publicKey = "ssh-ed25519 AAAA", passphrase = "pp")
    private val keystore = Identity(id = "hw", name = "phone", hardware = true, backing = "StrongBox", publicKey = "ecdsa AAAA")
    private val token = Identity(id = "sk", name = "yubikey", securityKey = true, skCredentialId = "handle", skPublicKey = "pub")
    private val web = Host(id = "h1", label = "web", hostname = "web.example", password = "hunter2", identityId = "k1", authType = AuthType.KEY)
    private val db = Host(id = "h2", label = "db", hostname = "db.example", identityId = "hw", authType = AuthType.KEY, jumpHostId = "h1")

    private fun full() = Snapshot(
        hosts = listOf(web, db),
        identities = listOf(key, keystore, token),
        tunnels = listOf(Tunnel(id = "t1", name = "office", config = "[Interface]\nPrivateKey = x")),
        proxies = listOf(SavedProxy(id = "p1", name = "corp", password = "pw")),
        tailscaleProfiles = listOf(TailscaleProfile(id = "ts", name = "tailnet", authKey = "tskey", joined = true)),
        accounts = listOf(Account(id = "a1", name = "ops", password = "apw", identityId = "k1")),
    )

    @Test
    fun a_keystore_key_never_goes_into_a_backup_but_a_security_key_does() {
        val out = Vault.strip(full(), withSecrets = true)
        assertEquals(listOf("k1", "sk"), out.identities.map { it.id })
        assertEquals("PRIVATE", out.identities[0].privateKey)
        assertEquals("handle", out.identities[1].skCredentialId)
    }

    @Test
    fun without_secrets_every_secret_field_is_blank_and_nothing_else_changes() {
        val out = Vault.strip(full(), withSecrets = false)
        assertEquals("", out.hosts[0].password)
        assertEquals("", out.accounts[0].password)
        assertEquals("", out.identities[0].privateKey)
        assertEquals("", out.identities[0].passphrase)
        assertEquals("ssh-ed25519 AAAA", out.identities[0].publicKey)
        assertEquals("", out.tunnels[0].config)
        assertEquals("", out.proxies[0].password)
        assertEquals("", out.tailscaleProfiles[0].authKey)
        assertEquals("web.example", out.hosts[0].hostname)
    }

    @Test
    fun a_restore_adds_what_is_new_and_lets_the_file_win_on_a_shared_id() {
        val local = Snapshot(hosts = listOf(web.copy(label = "web (old name)"), Host(id = "mine", label = "only here")))
        val incoming = Snapshot(hosts = listOf(web, Host(id = "theirs", label = "only in the file")))
        val out = Vault.merge(local, incoming, incomingHasSecrets = true)
        assertEquals(listOf("h1", "mine", "theirs"), out.hosts.map { it.id })
        assertEquals("web", out.hosts[0].label)
    }

    @Test
    fun a_file_without_secrets_keeps_the_secrets_this_device_already_had() {
        val local = Snapshot(
            hosts = listOf(web), identities = listOf(key), tunnels = full().tunnels, proxies = full().proxies,
            tailscaleProfiles = full().tailscaleProfiles, accounts = full().accounts,
        )
        val incoming = Vault.strip(local.copy(hosts = listOf(web.copy(label = "renamed"))), withSecrets = false)
        val out = Vault.merge(local, incoming, incomingHasSecrets = false)
        assertEquals("renamed", out.hosts[0].label)
        assertEquals("hunter2", out.hosts[0].password)
        assertEquals("PRIVATE", out.identities[0].privateKey)
        assertEquals("pp", out.identities[0].passphrase)
        assertEquals("[Interface]\nPrivateKey = x", out.tunnels[0].config)
        assertEquals("pw", out.proxies[0].password)
        assertEquals("tskey", out.tailscaleProfiles[0].authKey)
        assertEquals("apw", out.accounts[0].password)
    }

    @Test
    fun a_file_with_secrets_replaces_them() {
        val local = Snapshot(hosts = listOf(web))
        val out = Vault.merge(local, Snapshot(hosts = listOf(web.copy(password = "newer"))), incomingHasSecrets = true)
        assertEquals("newer", out.hosts[0].password)
    }

    @Test
    fun a_host_whose_key_stayed_on_the_other_phone_falls_back_to_a_password() {
        // The file was made on a phone whose "hw" key was in its keystore; it
        // is not in the file, so a host that used it cannot keep pointing there.
        val incoming = Vault.strip(full(), withSecrets = true)
        val out = Vault.merge(Snapshot(), incoming, incomingHasSecrets = true)
        val restoredDb = out.hosts.first { it.id == "h2" }
        assertNull(restoredDb.identityId)
        assertEquals(AuthType.PASSWORD, restoredDb.authType)
        assertEquals("h1", restoredDb.jumpHostId)
        // While the host that used a key the file does carry is untouched.
        val restoredWeb = out.hosts.first { it.id == "h1" }
        assertEquals("k1", restoredWeb.identityId)
        assertEquals(AuthType.KEY, restoredWeb.authType)
    }

    @Test
    fun a_keystore_key_here_is_not_replaced_by_a_record_of_the_same_id() {
        val local = Snapshot(identities = listOf(keystore))
        val incoming = Snapshot(identities = listOf(keystore.copy(hardware = false, privateKey = "smuggled", name = "not the phone")))
        val out = Vault.merge(local, incoming, incomingHasSecrets = true)
        assertEquals(1, out.identities.size)
        assertTrue(out.identities[0].hardware)
        assertEquals("phone", out.identities[0].name)
    }

    @Test
    fun a_server_key_this_device_has_seen_outranks_the_one_in_the_file() {
        val seen = KnownHost("web.example", 22, "ssh-ed25519", "NEW", "SHA256:new")
        val remembered = KnownHost("web.example", 22, "ssh-ed25519", "OLD", "SHA256:old")
        val extra = KnownHost("db.example", 22, "ssh-ed25519", "DB", "SHA256:db")
        val out = Vault.merge(Snapshot(knownHosts = listOf(seen)), Snapshot(knownHosts = listOf(remembered, extra)), true)
        assertEquals(listOf("NEW", "DB"), out.knownHosts.map { it.keyBase64 })
    }

    @Test
    fun history_is_the_union_and_counts_take_the_larger_number() {
        val local = Snapshot(history = mapOf("h1" to listOf("ls", "htop")), historyCounts = mapOf("h1" to mapOf("ls" to 5)))
        val incoming = Snapshot(history = mapOf("h1" to listOf("htop", "df -h"), "h2" to listOf("uptime")), historyCounts = mapOf("h1" to mapOf("ls" to 2, "htop" to 3)))
        val out = Vault.merge(local, incoming, true)
        assertEquals(setOf("ls", "htop", "df -h"), out.history["h1"]!!.toSet())
        assertEquals(listOf("uptime"), out.history["h2"])
        assertEquals(5, out.historyCounts["h1"]!!["ls"])
        assertEquals(3, out.historyCounts["h1"]!!["htop"])
    }

    @Test
    fun device_bound_settings_stay_while_preferences_come_from_the_file() {
        val local = Snapshot(settings = Settings(appLock = true, fontFamily = "file:custom.ttf", automationAllowed = listOf("net.dinglisch.android.taskerm"), fontSizeSp = 13f))
        val incoming = Snapshot(settings = Settings(appLock = false, fontFamily = "file:theirs.ttf", automationAllowed = emptyList(), fontSizeSp = 16f, theme = "catppuccin-mocha", customSchemes = listOf(Schemes.DEFAULT.copy(id = "c1", name = "Mine", custom = true))))
        val out = Vault.merge(local, incoming, true).settings
        assertTrue(out.appLock)
        assertEquals("file:custom.ttf", out.fontFamily)
        assertEquals(listOf("net.dinglisch.android.taskerm"), out.automationAllowed)
        assertEquals(16f, out.fontSizeSp)
        assertEquals("catppuccin-mocha", out.theme)
        // An imported scheme is a preference too: it travels with the backup.
        assertEquals(listOf("Mine"), out.customSchemes.map { it.name })
        // A built-in font in the file is a preference like any other.
        assertEquals("fira", Vault.merge(local, Snapshot(settings = Settings(fontFamily = "fira")), true).settings.fontFamily)
    }

    @Test
    fun a_tailscale_profile_arrives_unjoined_because_the_state_directory_did_not_travel() {
        val out = Vault.merge(Snapshot(), full(), true)
        assertFalse(out.tailscaleProfiles[0].joined)
        val keptLocal = Vault.merge(full(), full(), true)
        assertTrue(keptLocal.tailscaleProfiles[0].joined)
    }

    @Test
    fun references_to_things_the_merge_did_not_end_up_with_are_dropped() {
        val incoming = Snapshot(
            hosts = listOf(Host(id = "h", groupId = "gone", accountId = "gone", tunnelId = "gone", proxyId = "gone", tailscaleId = "gone", jumpHostId = "gone", startupSnippetIds = listOf("gone", "s1"))),
            snippets = listOf(Snippet(id = "s1", hostIds = listOf("h", "gone"))),
        )
        val h = Vault.merge(Snapshot(), incoming, true).hosts[0]
        assertNull(h.groupId); assertNull(h.accountId); assertNull(h.tunnelId); assertNull(h.proxyId); assertNull(h.tailscaleId); assertNull(h.jumpHostId)
        assertEquals(listOf("s1"), h.startupSnippetIds)
        assertEquals(listOf("h"), Vault.merge(Snapshot(), incoming, true).snippets[0].hostIds)
    }

    @Test
    fun describe_reads_as_a_sentence() {
        assertEquals("nothing but settings", Vault.describe(Snapshot()))
        assertEquals("1 host", Vault.describe(Snapshot(hosts = listOf(web))))
        assertEquals("2 hosts, 2 keys and 1 tunnel", Vault.describe(Snapshot(hosts = listOf(web, db), identities = listOf(key, token), tunnels = full().tunnels)))
    }
}
