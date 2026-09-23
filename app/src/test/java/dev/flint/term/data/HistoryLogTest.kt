package dev.flint.term.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The history's own file: one line per command, rewritten only when it has doubled. */
class HistoryLogTest {
    @get:Rule val dir = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file by lazy { File(dir.root, "history.jsonl") }

    @After fun stop() = scope.cancel()

    /** A quiet period long enough that only [HistoryLog.flush] ever writes. */
    private fun log() = HistoryLog(file, scope, 60_000)

    private fun reopened() = log().also { assertTrue(it.load()) }

    @Test fun `commands survive a reload with their order and counts`() {
        val a = log()
        assertFalse(a.load())
        a.remember("h1", "git status")
        a.remember("h1", "make test")
        a.remember("h1", "git status")
        a.remember("h2", "uptime -p")
        a.flush()
        val b = reopened()
        assertEquals(mapOf("h1" to listOf("make test", "git status"), "h2" to listOf("uptime -p")), b.history.value)
        assertEquals(mapOf("git status" to 2, "make test" to 1), b.counts.value["h1"])
        assertEquals(a.history.value, b.history.value)
        assertEquals(a.counts.value, b.counts.value)
    }

    @Test fun `an Enter appends one line and leaves the rest of the file alone`() {
        val a = log()
        a.remember("h1", "git status")
        a.flush()
        val before = file.readText()
        a.remember("h1", "make test")
        a.flush()
        val after = file.readText()
        assertTrue(after.startsWith(before))
        val added = after.removePrefix(before).trimEnd('\n')
        assertEquals(1, added.lines().size)
        assertEquals("make test", JSONObject(added).getString("run"))
    }

    @Test fun `what is not worth keeping writes nothing`() {
        val a = log()
        a.remember("h1", "ls")
        a.remember("h1", " secret-thing")
        a.flush()
        assertFalse(file.exists())
    }

    @Test fun `the file is rewritten once it has doubled, and reads back the same`() {
        val a = log()
        repeat(3000) {
            a.remember("h1", "command number ${it % 40}")
            a.flush()
        }
        // 3000 appends of ~45 bytes would be well over 100 KB left alone.
        assertTrue("file is ${file.length()} bytes", file.length() < 40 * 1024)
        val b = reopened()
        assertEquals(a.history.value, b.history.value)
        assertEquals(a.counts.value, b.counts.value)
        assertEquals(75, b.counts.value["h1"]!!["command number 0"])
    }

    @Test fun `imports, forgetting and a restore replay in order`() {
        val a = log()
        a.remember("h1", "git status")
        a.add("h1", listOf("make all", "cargo test"), mapOf("cargo test" to 9))
        a.remember("h2", "uptime -p")
        a.forget("h2")
        a.flush()
        val b = reopened()
        assertEquals(mapOf("h1" to listOf("git status", "make all", "cargo test")), b.history.value)
        assertEquals(9, b.counts.value["h1"]!!["cargo test"])
        b.replaceAll(mapOf("h3" to listOf("htop -d 5")), mapOf("h3" to mapOf("htop -d 5" to 4)))
        b.flush()
        val c = reopened()
        assertEquals(mapOf("h3" to listOf("htop -d 5")), c.history.value)
        assertEquals(mapOf("h3" to mapOf("htop -d 5" to 4)), c.counts.value)
    }

    @Test fun `a half-written last line is dropped and the next write starts clean`() {
        val a = log()
        a.remember("h1", "git status")
        a.flush()
        file.appendText("""{"host":"h1","run":"make te""")
        val b = reopened()
        assertEquals(listOf("git status"), b.history.value["h1"])
        b.flush()
        b.remember("h1", "make test")
        b.flush()
        assertEquals(listOf("git status", "make test"), reopened().history.value["h1"])
        assertTrue(file.readText().lines().filter { it.isNotEmpty() }.all { runCatching { JSONObject(it) }.isSuccess })
    }

    @Test fun `a store written without history is the same whatever the history holds`() {
        val base = Snapshot(hosts = listOf(Host(id = "h1", label = "web")))
        val one = StoreJson.write(base.copy(history = mapOf("h1" to listOf("git status"))), withHistory = false) { it }
        val two = StoreJson.write(base.copy(history = mapOf("h1" to listOf("git status", "make test"))), withHistory = false) { it }
        assertEquals(one.toString(), two.toString())
        assertFalse(one.has("history"))
        assertFalse(one.has("historyCounts"))
    }

    @Test fun `history inline in an old store moves into the log`() {
        val old = StoreJson.write(
            Snapshot(
                hosts = listOf(Host(id = "h1")),
                history = mapOf("h1" to listOf("git status", "make test")),
                // From before counts were bounded: one for a command long gone.
                historyCounts = mapOf("h1" to mapOf("git status" to 3, "make test" to 1, "gone command" to 7)),
            ),
        ) { it }
        val snap = StoreJson.read(JSONObject(old.toString())) { it }
        val a = log()
        assertFalse(a.load())
        a.replaceAll(snap.history, snap.historyCounts)
        a.flush()
        val b = reopened()
        assertEquals(listOf("git status", "make test"), b.history.value["h1"])
        assertEquals(mapOf("git status" to 3, "make test" to 1), b.counts.value["h1"])
        // What the store is rewritten as no longer carries it.
        assertFalse(StoreJson.write(snap, withHistory = false) { it }.has("history"))
    }

    @Test fun `a backup still carries the history, and restores it into the log`() {
        val a = log()
        a.remember("h1", "git status")
        a.remember("h1", "git status")
        a.flush()
        val snap = Snapshot(hosts = listOf(Host(id = "h1")), history = a.history.value, historyCounts = a.counts.value)
        val (_, back) = Vault.parse(Vault.plain(snap, withSecrets = true, device = "test"))
        assertEquals(snap.history, back.history)
        assertEquals(snap.historyCounts, back.historyCounts)

        val fresh = HistoryLog(File(dir.root, "other.jsonl"), scope, 60_000)
        val merged = Vault.merge(Snapshot(), back, incomingHasSecrets = true)
        fresh.replaceAll(merged.history, merged.historyCounts)
        fresh.flush()
        val reread = HistoryLog(File(dir.root, "other.jsonl"), scope, 60_000).also { it.load() }
        assertEquals(mapOf("h1" to listOf("git status")), reread.history.value)
        assertEquals(mapOf("h1" to mapOf("git status" to 2)), reread.counts.value)
    }

    @Test fun `a backup from before the split restores its inline history`() {
        val file = """
            {"version":1,"hosts":[{"id":"h1","label":"web"}],
             "history":{"h1":["df -h","git log"]},"historyCounts":{"h1":{"git log":4}},
             "vault":{"app":"flintterm","format":1,"exportedAt":1,"device":"old","secrets":true}}
        """.trimIndent()
        val (_, back) = Vault.parse(file.toByteArray())
        assertEquals(listOf("df -h", "git log"), back.history["h1"])
        val a = log()
        a.replaceAll(back.history, back.historyCounts)
        a.flush()
        val b = reopened()
        assertEquals(listOf("df -h", "git log"), b.history.value["h1"])
        assertEquals(4, b.counts.value["h1"]!!["git log"])
    }
}
