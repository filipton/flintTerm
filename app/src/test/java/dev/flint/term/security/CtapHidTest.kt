package dev.flint.term.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CtapHidTest {
    private val channel = 0x11223344

    @Test
    fun `a short message is one packet, padded to the report size`() {
        val frames = CtapHid.frames(channel, CtapHid.CMD_CBOR, byteArrayOf(4))
        assertEquals(1, frames.size)
        val packet = frames[0]
        assertEquals(CtapHid.PACKET_SIZE, packet.size)
        assertArrayEquals(byteArrayOf(0x11, 0x22, 0x33, 0x44), packet.copyOf(4))
        assertEquals(0x90.toByte(), packet[4]) // 0x80 | CMD_CBOR
        assertEquals(0, packet[5].toInt())
        assertEquals(1, packet[6].toInt())
        assertEquals(4, packet[7].toInt())
    }

    /**
     * The case that only appears with a real answer: everything past the first
     * 57 bytes rides continuation packets, numbered from zero.
     */
    @Test
    fun `a long message is split across numbered continuation packets`() {
        val payload = ByteArray(200) { it.toByte() }
        val frames = CtapHid.frames(channel, CtapHid.CMD_CBOR, payload)
        assertEquals(1 + 3, frames.size) // 57 + 59 + 59 + 25
        assertEquals(0, frames[1][4].toInt())
        assertEquals(1, frames[2][4].toInt())
        assertEquals(2, frames[3][4].toInt())
        assertEquals(200, ((frames[0][5].toInt() and 0xFF) shl 8) or (frames[0][6].toInt() and 0xFF))

        val rebuilt = frames.drop(1).fold(frames[0].copyOfRange(7, 64)) { acc, f -> acc + f.copyOfRange(5, 64) }
        assertArrayEquals(payload, rebuilt.copyOf(200))
    }

    @Test
    fun `a response split across continuation frames is put back together`() {
        val payload = ByteArray(300) { (it * 7).toByte() }
        val reassembler = CtapHid.Reassembler(channel)
        val frames = CtapHid.frames(channel, CtapHid.CMD_CBOR, payload)
        frames.dropLast(1).forEach { assertNull(reassembler.accept(it)) }
        val message = reassembler.accept(frames.last())
        assertEquals(CtapHid.CMD_CBOR, message?.command)
        assertArrayEquals(payload, message?.payload)
    }

    /** Splicing over a gap would put unrelated bytes into the middle of a
     *  signature, so a gap has to stop the exchange. */
    @Test
    fun `a dropped continuation packet is refused rather than spliced over`() {
        val frames = CtapHid.frames(channel, CtapHid.CMD_CBOR, ByteArray(300))
        val reassembler = CtapHid.Reassembler(channel)
        reassembler.accept(frames[0])
        reassembler.accept(frames[1])
        val error = runCatching { reassembler.accept(frames[3]) }.exceptionOrNull()
        assertTrue("$error", error is CtapException)
        assertTrue("$error", error?.message?.contains("out of order") == true)
    }

    /** Another application on the same token has its own channel and its
     *  packets are not ours to reassemble. */
    @Test
    fun `packets for another channel are ignored`() {
        val reassembler = CtapHid.Reassembler(channel)
        val other = CtapHid.frames(0x55667788, CtapHid.CMD_CBOR, byteArrayOf(1, 2, 3))
        assertNull(reassembler.accept(other[0]))
        val mine = CtapHid.frames(channel, CtapHid.CMD_CBOR, byteArrayOf(9))
        assertArrayEquals(byteArrayOf(9), reassembler.accept(mine[0])?.payload)
    }

    /**
     * The whole USB path with the wire taken out: framing out, a software token
     * in the middle, framing back — including the KEEPALIVE a token sends while
     * it waits for a finger, which must not be mistaken for the answer.
     */
    @Test
    fun `a full CTAP2 exchange survives framing in both directions`() {
        val authenticator = SoftwareAuthenticator()
        val device = FakeHidDevice(authenticator)
        val transport = HidCtapTransport(device)
        var touchAnnounced = false
        transport.onTouchNeeded = { touchAnnounced = true }
        transport.open()

        val info = Ctap.getInfo(transport)
        assertTrue(info.versions.contains("FIDO_2_0"))
        assertTrue(info.supports(SecurityKeyAlgorithm.EcdsaP256))

        val credential = Ctap.makeCredential(transport, Ctap.DEFAULT_APPLICATION, SecurityKeyAlgorithm.EcdsaP256, "test")
        assertEquals(65, credential.publicKey.size)
        assertEquals(0x04.toByte(), credential.publicKey[0])
        assertEquals(32, credential.credentialId.size)
        assertTrue("the token should have asked for a touch", touchAnnounced)

        val assertion = Ctap.getAssertion(transport, Ctap.DEFAULT_APPLICATION, credential.credentialId, ByteArray(32))
        assertEquals(37, assertion.authenticatorData.size)
        assertEquals(0x01, assertion.authenticatorData[32].toInt() and 0x01)
        transport.close()
    }

    @Test
    fun `an unknown credential comes back as a sentence about this key`() {
        val transport = HidCtapTransport(FakeHidDevice(SoftwareAuthenticator())).apply { open() }
        val error = runCatching {
            Ctap.getAssertion(transport, Ctap.DEFAULT_APPLICATION, ByteArray(32), ByteArray(32))
        }.exceptionOrNull()
        assertTrue("$error", error is CtapException)
        assertTrue("$error", error?.message?.contains("does not hold that credential") == true)
    }

    /** A request that skips the PIN a token asked for is refused in words, not
     *  in a code — which is what the enrollment flow catches to put the prompt up. */
    @Test
    fun `a token that wants a PIN says so instead of failing obscurely`() {
        val authenticator = SoftwareAuthenticator().apply { pin = "1234" }
        val transport = HidCtapTransport(FakeHidDevice(authenticator)).apply { open() }
        val error = runCatching {
            Ctap.makeCredential(transport, Ctap.DEFAULT_APPLICATION, SecurityKeyAlgorithm.EcdsaP256, "test")
        }.exceptionOrNull()
        assertTrue("$error", error?.message?.contains("PIN") == true)
        assertTrue("$error", (error as CtapException).recoverable)
    }

    /** A key pulled out mid-exchange is the failure people will actually hit. */
    @Test
    fun `a key removed mid-exchange is reported as removed`() {
        val device = FakeHidDevice(SoftwareAuthenticator())
        val transport = HidCtapTransport(device).apply { open() }
        device.unplugged = true
        val error = runCatching { Ctap.getInfo(transport) }.exceptionOrNull()
        assertTrue("$error", error is CtapException)
        assertEquals("The security key was removed.", error?.message)
    }
}

/**
 * A [SoftwareAuthenticator] wearing the CTAPHID framing a USB token would.
 *
 * It frames its answers with the same code the app uses to unframe them, which
 * is the point: a token that framed differently would prove nothing about the
 * app's reassembly, and the constants are shared anyway.
 */
class FakeHidDevice(private val authenticator: SoftwareAuthenticator) : HidReports {
    override val packetSize = CtapHid.PACKET_SIZE

    /** Set to model the key being pulled out of the socket. */
    var unplugged = false

    private val pending = ArrayDeque<ByteArray>()
    private var reassembler = CtapHid.Reassembler(CtapHid.BROADCAST_CHANNEL)
    private var channel = CtapHid.BROADCAST_CHANNEL

    override fun write(report: ByteArray) {
        if (unplugged) throw CtapException("The security key was removed.", recoverable = true)
        val message = reassembler.accept(report) ?: return
        when (message.command) {
            CtapHid.CMD_INIT -> {
                channel = 0x0A0B0C0D
                reassembler = CtapHid.Reassembler(channel)
                val body = message.payload + byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D, 2, 5, 0, 0, 0x04)
                queue(CtapHid.BROADCAST_CHANNEL, CtapHid.CMD_INIT, body)
            }
            CtapHid.CMD_CBOR -> {
                // A real token says "waiting for a finger" before it answers, and
                // the app is expected to relay that rather than sit silent.
                queue(channel, CtapHid.CMD_KEEPALIVE, byteArrayOf(CtapHid.KEEPALIVE_UP_NEEDED.toByte()))
                val answer = authenticator.process(message.payload[0].toInt() and 0xFF, message.payload.copyOfRange(1, message.payload.size))
                queue(channel, CtapHid.CMD_CBOR, answer)
            }
            CtapHid.CMD_CANCEL -> Unit
        }
    }

    private fun queue(channel: Int, command: Int, payload: ByteArray) {
        pending += CtapHid.frames(channel, command, payload, packetSize)
    }

    override fun read(timeoutMs: Int): ByteArray? = pending.removeFirstOrNull()

    override fun close() = pending.clear()
}
