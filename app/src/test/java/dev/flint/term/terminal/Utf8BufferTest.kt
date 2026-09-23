package dev.flint.term.terminal

import dev.flint.term.core.KeyPress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class Utf8BufferTest {

    private fun Utf8Buffer.packed(): ByteArray = ByteArray(length) { bytes.get(it) }

    @Test
    fun `text packs to the bytes the string would have been sent as`() {
        val buf = Utf8Buffer()
        for (text in listOf("ls -la", "héllo", "€ ✓ ❯", "日本語", "emoji 🙂 and 𝄞", "", "a\u0000b")) {
            assertEquals(text, buf.pack(text), buf.length)
            assertArrayEquals(text, text.toByteArray(Charsets.UTF_8), buf.packed())
        }
    }

    @Test
    fun `a lone surrogate goes as a question mark, as it did as a string`() {
        val buf = Utf8Buffer()
        for (text in listOf("a\uD83Db", "\uDE42", "end\uD83D")) {
            buf.pack(text)
            assertArrayEquals(text.toByteArray(Charsets.UTF_8), buf.packed())
        }
    }

    @Test
    fun `the memory is kept until a longer text needs more`() {
        val buf = Utf8Buffer(initial = 8)
        buf.pack("ab")
        val first = buf.bytes
        buf.pack("cd")
        assertSame(first, buf.bytes)
        val long = "x".repeat(100) + "日本"
        buf.pack(long)
        assertArrayEquals(long.toByteArray(Charsets.UTF_8), buf.packed())
        // A short text after a long one is not the long one's tail.
        buf.pack("ok")
        assertArrayEquals("ok".toByteArray(), buf.packed())
    }

    @Test
    fun `text typed into two panes is packed once and handed to both`() {
        val seen = mutableListOf<Pair<String, ByteArray>>()
        val target = object : KeyTarget {
            override fun key(press: KeyPress) {}
            override fun text(text: String) {}
            override fun paste(text: String) {}
            override fun text(text: String, utf8: Utf8Buffer) {
                seen += text to utf8.packed()
            }
        }
        KeySink(primary = target, mirror = target).text("ç")
        assertEquals(2, seen.size)
        for ((text, bytes) in seen) {
            assertEquals("ç", text)
            assertArrayEquals("ç".toByteArray(), bytes)
        }
    }
}
