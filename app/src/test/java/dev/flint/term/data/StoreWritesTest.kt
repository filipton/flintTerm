package dev.flint.term.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.Collections

/** What keeps a store write cheap: secrets sealed once, and a burst written once. */
class StoreWritesTest {
    private var sealedCount = 0
    private var serial = 0

    /** A stand-in for the keystore: a new ciphertext every call, as GCM gives. */
    private fun cache() = SecretCache(
        encrypt = { sealedCount++; "enc1:${serial++}:$it" },
        decrypt = { if (it.startsWith("enc1:")) it.substringAfter(':').substringAfter(':') else it },
    )

    private fun hostsWith(vararg passwords: String) =
        Snapshot(hosts = passwords.mapIndexed { i, p -> Host(id = "h$i", password = p) })

    private fun SecretCache.file(snap: Snapshot) = write { seal -> StoreJson.write(snap, secret = seal) }.toString()

    @Test fun `an unchanged secret is not sealed again`() {
        val c = cache()
        val first = c.file(hostsWith("hunter2"))
        assertEquals(1, sealedCount)
        assertEquals(first, c.file(hostsWith("hunter2")))
        assertEquals(1, sealedCount)
    }

    @Test fun `a changed secret is sealed and the old one forgotten`() {
        val c = cache()
        c.file(hostsWith("old"))
        c.file(hostsWith("new"))
        assertEquals(2, sealedCount)
        c.file(hostsWith("old"))
        assertEquals(3, sealedCount)
    }

    @Test fun `two hosts sharing a password do not share a ciphertext`() {
        val c = cache()
        val out = StoreJson.read(JSONObject(c.file(hostsWith("same", "same")))) { it }
        assertNotEquals(out.hosts[0].password, out.hosts[1].password)
        c.file(hostsWith("same", "same"))
        assertEquals(2, sealedCount)
    }

    @Test fun `secrets read from the file are written back as they were`() {
        val onDisk = cache().file(hostsWith("from disk"))
        sealedCount = 0
        val c = cache()
        val snap = StoreJson.read(JSONObject(onDisk), c::open)
        assertEquals("from disk", snap.hosts[0].password)
        assertEquals(onDisk, c.file(snap))
        assertEquals(0, sealedCount)
    }

    @Test fun `a secret stored in the clear is sealed on the next write`() {
        val c = cache()
        assertEquals("plain", c.open("plain"))
        c.file(hostsWith("plain"))
        assertEquals(1, sealedCount)
    }

    @Test fun `a burst of values is written once, as the newest`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val written = Collections.synchronizedList(mutableListOf<Int>())
        val w = CoalescingWriter<Int>(scope, 100) { written += it }
        repeat(20) { w.submit(it) }
        Thread.sleep(400)
        assertEquals(listOf(19), written)
        w.submit(20)
        Thread.sleep(400)
        assertEquals(listOf(19, 20), written)
        scope.cancel()
    }

    @Test fun `a flush writes what is waiting without waiting for the clock`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val written = Collections.synchronizedList(mutableListOf<Int>())
        val w = CoalescingWriter<Int>(scope, 60_000) { written += it }
        w.submit(1)
        w.submit(2)
        w.flush()
        assertEquals(listOf(2), written)
        // Nothing waits any more, so a second flush has nothing to do.
        w.flush()
        assertEquals(listOf(2), written)
        scope.cancel()
    }

    @Test fun `counts go once they outnumber the history`() {
        val counts = mapOf("a" to 1, "b" to 2)
        assertSame(counts, CommandHistory.prune(counts, listOf("a", "b")))
        assertEquals(mapOf("b" to 2), CommandHistory.prune(counts, listOf("b")))
        val many = (0 until 5000).associate { "cmd $it" to 1 }
        val kept = (4500 until 5000).map { "cmd $it" }
        assertEquals(kept.toSet(), CommandHistory.prune(many, kept).keys)
    }
}
