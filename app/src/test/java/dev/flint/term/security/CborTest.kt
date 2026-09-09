package dev.flint.term.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CborTest {
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    /** The examples from RFC 8949's appendix, so the encoder is checked against
     *  something other than its own decoder. */
    @Test
    fun `head lengths match the specification`() {
        assertEquals("00", hex(Cbor.encode(0L)))
        assertEquals("17", hex(Cbor.encode(23L)))
        assertEquals("1818", hex(Cbor.encode(24L)))
        assertEquals("1903e8", hex(Cbor.encode(1000L)))
        assertEquals("1a000f4240", hex(Cbor.encode(1000000L)))
        assertEquals("20", hex(Cbor.encode(-1L)))
        assertEquals("3903e7", hex(Cbor.encode(-1000L)))
        assertEquals("6161", hex(Cbor.encode("a")))
        assertEquals("4401020304", hex(Cbor.encode(byteArrayOf(1, 2, 3, 4))))
        assertEquals("f5", hex(Cbor.encode(true)))
        assertEquals("f6", hex(Cbor.encode(null)))
    }

    @Test
    fun `everything CTAP2 carries survives a round trip`() {
        val value = mapOf(
            1L to byteArrayOf(1, 2, 3),
            2L to mapOf("id" to "ssh:", "name" to "ssh:"),
            4L to listOf(mapOf("alg" to -8L, "type" to "public-key")),
            7L to mapOf("rk" to false),
        )
        @Suppress("UNCHECKED_CAST")
        val back = Cbor.decode(Cbor.encode(value)) as Map<Any?, Any?>
        assertArrayEquals(byteArrayOf(1, 2, 3), back[1L] as ByteArray)
        assertEquals("ssh:", Cbor.field<String>(back[2L], "id", "rp"))
        assertEquals(-8L, Cbor.optional<Long>((back[4L] as List<*>)[0], "alg"))
        assertEquals(false, Cbor.optional<Boolean>(back[7L], "rk"))
    }

    /**
     * A token computes over the bytes it received, so a map serialized in the
     * wrong order is a request it can legitimately refuse. Writing the keys in
     * the worst order and getting the canonical one back is the check.
     */
    @Test
    fun `map keys come out in canonical order`() {
        val jumbled = linkedMapOf<Any, Any?>(7 to 3L, 1 to 1L, "zz" to 4L, "a" to 5L, 2 to 2L)
        val encoded = Cbor.encode(jumbled)
        // Integer keys first because they encode to one byte, then the shorter
        // text key, then the longer one.
        assertEquals("a5" + "0101" + "0202" + "0703" + "616105" + "627a7a04", hex(encoded))
    }

    @Test
    fun `a truncated value is refused rather than guessed at`() {
        val error = runCatching { Cbor.decode(byteArrayOf(0x44, 1, 2)) }.exceptionOrNull()
        assertTrue("$error", error is Cbor.Malformed)
    }

    @Test
    fun `trailing bytes are an error, because a doubled answer is a bug worth seeing`() {
        val error = runCatching { Cbor.decode(byteArrayOf(0x01, 0x02)) }.exceptionOrNull()
        assertTrue("$error", error is Cbor.Malformed)
    }

    /** Indefinite lengths are legal CBOR and illegal CTAP2; saying so beats
     *  half-parsing one. */
    @Test
    fun `an indefinite length is refused`() {
        val error = runCatching { Cbor.decode(byteArrayOf(0x5F.toByte(), 0x41, 0x01, 0xFF.toByte())) }.exceptionOrNull()
        assertTrue("$error", error is Cbor.Malformed)
    }

    @Test
    fun `a missing field names itself`() {
        val error = runCatching { Cbor.field<ByteArray>(mapOf(1L to "x"), 2, "the assertion") }.exceptionOrNull()
        assertTrue("$error", error?.message?.contains("the assertion") == true)
    }
}
