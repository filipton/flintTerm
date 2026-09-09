package dev.flint.term.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class NfcCtapTest {
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun `a short command is one APDU asking for a response`() {
        val apdus = NfcCtap.commands(byteArrayOf(0x04))
        assertEquals(1, apdus.size)
        assertEquals("80100000010400", hex(apdus[0]))
    }

    /**
     * A makeCredential is always longer than a short APDU can carry, so chaining
     * is the normal path and not an edge case. Every command but the last says
     * "more follows" in the top nibble of its class byte.
     */
    @Test
    fun `a long command is chained across APDUs`() {
        val data = ByteArray(600) { it.toByte() }
        val apdus = NfcCtap.commands(data)
        assertEquals(3, apdus.size)
        assertEquals(0x90.toByte(), apdus[0][0])
        assertEquals(0x90.toByte(), apdus[1][0])
        assertEquals(0x80.toByte(), apdus[2][0])
        assertEquals(255, apdus[0][4].toInt() and 0xFF)
        assertEquals(600 - 510, apdus[2][4].toInt() and 0xFF)
        // Only the last one carries an Le; the others are pure continuation.
        assertEquals(5 + 255, apdus[0].size)
        assertEquals(5 + 90 + 1, apdus[2].size)

        val rebuilt = ByteArrayOutputStream()
        apdus.forEachIndexed { i, apdu ->
            val length = apdu[4].toInt() and 0xFF
            rebuilt.write(apdu, 5, length)
            assertEquals(i == apdus.lastIndex, apdu.size == 5 + length + 1)
        }
        assertArrayEquals(data, rebuilt.toByteArray())
    }

    @Test
    fun `61xx means there is more to fetch`() {
        assertEquals(0x20, NfcCtap.moreToCome(0x6120))
        assertEquals(256, NfcCtap.moreToCome(0x6100))
        assertEquals(null, NfcCtap.moreToCome(NfcCtap.SW_OK))
        assertEquals("8011000020", hex(NfcCtap.getResponse(0x20)))
    }

    /** A card that is not a security key answers the SELECT, so the honest place
     *  to notice is there rather than three commands later. */
    @Test
    fun `a card without the FIDO applet is named as such`() {
        val transport = NfcCtapTransport(object : ApduChannel {
            override fun transceive(apdu: ByteArray) = byteArrayOf(0x6A, 0x82.toByte())
            override fun close() = Unit
        })
        val error = runCatching { transport.open() }.exceptionOrNull()
        assertTrue("$error", error?.message?.contains("not a security key") == true)
    }

    @Test
    fun `a tag pulled out of the field is reported as moved away`() {
        val transport = NfcCtapTransport(object : ApduChannel {
            override fun transceive(apdu: ByteArray): ByteArray = throw java.io.IOException("tag was lost")
            override fun close() = Unit
        })
        val error = runCatching { transport.open() }.exceptionOrNull()
        assertTrue("$error", error is CtapException)
        assertTrue("$error", error?.message?.contains("moved away") == true)
    }

    /**
     * The whole NFC path with the card taken out: chained commands in, a
     * software token in the middle, a GET RESPONSE chain back.
     */
    @Test
    fun `a full CTAP2 exchange survives APDU chaining in both directions`() {
        val authenticator = SoftwareAuthenticator()
        val transport = NfcCtapTransport(FakeCard(authenticator))
        transport.open()

        val credential = Ctap.makeCredential(transport, Ctap.DEFAULT_APPLICATION, SecurityKeyAlgorithm.EcdsaP256, "tap")
        assertEquals(65, credential.publicKey.size)

        val assertion = Ctap.getAssertion(transport, Ctap.DEFAULT_APPLICATION, credential.credentialId, ByteArray(32))
        assertEquals(37, assertion.authenticatorData.size)
        assertTrue(assertion.signature.isNotEmpty())
        transport.close()
    }
}

/**
 * A [SoftwareAuthenticator] behaving like a contactless card.
 *
 * It reassembles chained commands and dribbles its answers back a few bytes at a
 * time, which is what makes the GET RESPONSE loop worth testing: a card that
 * answered everything at once would never exercise it.
 */
private class FakeCard(private val authenticator: SoftwareAuthenticator) : ApduChannel {
    private val incoming = ByteArrayOutputStream()
    private var outgoing = ByteArray(0)

    override fun transceive(apdu: ByteArray): ByteArray {
        val cla = apdu[0].toInt() and 0xFF
        val ins = apdu[1].toInt() and 0xFF
        return when {
            cla == 0x00 && ins == 0xA4 -> byteArrayOf(0x55, 0x32, 0x46, 0x5F, 0x56, 0x32) + ok()
            ins == 0x11 -> more(apdu[4].toInt() and 0xFF)
            ins == 0x10 -> {
                val length = apdu[4].toInt() and 0xFF
                incoming.write(apdu, 5, length)
                if (cla and 0x10 != 0) return ok()
                val message = incoming.toByteArray()
                incoming.reset()
                outgoing = authenticator.process(message[0].toInt() and 0xFF, message.copyOfRange(1, message.size))
                more(CHUNK)
            }
            else -> byteArrayOf(0x6D, 0x00)
        }
    }

    /** Hand back at most [want] bytes and say how many are left. */
    private fun more(want: Int): ByteArray {
        val n = minOf(want, CHUNK, outgoing.size)
        val piece = outgoing.copyOf(n)
        outgoing = outgoing.copyOfRange(n, outgoing.size)
        val status = if (outgoing.isEmpty()) ok() else byteArrayOf(0x61, minOf(outgoing.size, 255).toByte())
        return piece + status
    }

    private fun ok() = byteArrayOf(0x90.toByte(), 0x00)

    override fun close() = Unit

    private companion object {
        /** Small on purpose, so a short answer still needs several fetches. */
        const val CHUNK = 40
    }
}
