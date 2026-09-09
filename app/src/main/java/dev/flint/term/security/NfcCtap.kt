package dev.flint.term.security

import android.nfc.tech.IsoDep
import java.io.ByteArrayOutputStream

/**
 * A security key held against the back of the phone, over NFC.
 *
 * The token is a smart card here, so CTAP2 travels inside ISO 7816 APDUs: select
 * the FIDO applet, then send the same command byte and CBOR as an APDU body. A
 * body longer than 255 bytes has to be chained across several commands, and an
 * answer longer than the card wants to send at once comes back in pieces that
 * GET RESPONSE asks for one at a time — an `authenticatorMakeCredential` is
 * always both, so neither path is exotic.
 */
object NfcCtap {
    /** The FIDO applet, the same on every token. */
    val AID = byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x06, 0x47, 0x2F, 0x00, 0x01)

    private const val CLA = 0x80
    private const val CLA_CHAINED = 0x90
    private const val INS_MSG = 0x10
    private const val INS_GET_RESPONSE = 0x11

    /** The largest body a short APDU can carry. */
    const val MAX_COMMAND_DATA = 255

    const val SW_OK = 0x9000

    /**
     * The APDUs carrying [data].
     *
     * Chaining is the CLA's top nibble: every command but the last has bit 0x10
     * set to say "more follows". The last one asks for a response with a Le of
     * zero, which on a short APDU means "as much as you have".
     */
    fun commands(data: ByteArray, chunk: Int = MAX_COMMAND_DATA): List<ByteArray> {
        require(chunk in 1..MAX_COMMAND_DATA) { "a short APDU carries at most $MAX_COMMAND_DATA bytes" }
        if (data.isEmpty()) return listOf(byteArrayOf(CLA.toByte(), INS_MSG.toByte(), 0x00, 0x00, 0x00))
        val out = mutableListOf<ByteArray>()
        var at = 0
        while (at < data.size) {
            val n = minOf(chunk, data.size - at)
            val last = at + n >= data.size
            val apdu = ByteArrayOutputStream()
            apdu.write(if (last) CLA else CLA_CHAINED)
            apdu.write(INS_MSG)
            apdu.write(0x00)
            apdu.write(0x00)
            apdu.write(n)
            apdu.write(data, at, n)
            if (last) apdu.write(0x00)
            out += apdu.toByteArray()
            at += n
        }
        return out
    }

    /** GET RESPONSE for the [remaining] bytes the card said it still holds. */
    fun getResponse(remaining: Int): ByteArray =
        byteArrayOf(CLA.toByte(), INS_GET_RESPONSE.toByte(), 0x00, 0x00, remaining.toByte())

    fun select(): ByteArray =
        byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, AID.size.toByte()) + AID + byteArrayOf(0x00)

    /** The two status bytes at the end of a response. */
    fun statusWord(response: ByteArray): Int {
        if (response.size < 2) throw CtapException("The security key sent a response with no status.")
        return ((response[response.size - 2].toInt() and 0xFF) shl 8) or (response[response.size - 1].toInt() and 0xFF)
    }

    fun body(response: ByteArray): ByteArray = response.copyOf(response.size - 2)

    /**
     * Whether [status] means "here is some of it, ask for the rest", and how
     * much is left. `0x61 xx` is the card's way of saying so; `xx` of zero means
     * it does not know, and 256 is the most a short GET RESPONSE can fetch.
     */
    fun moreToCome(status: Int): Int? =
        if (status shr 8 == 0x61) (status and 0xFF).let { if (it == 0) 256 else it } else null

    /** What a status word other than success means, where it is worth saying. */
    fun statusMessage(status: Int): String = when (status) {
        0x6A82 -> "This card answered, but it is not a security key."
        0x6982, 0x6985 -> "The security key would not run that — hold it still against the phone."
        0x6700 -> "The security key rejected the length of the request."
        0x6D00 -> "This security key does not speak FIDO2 over NFC."
        else -> "The security key answered with status 0x%04X.".format(status)
    }
}

/** Whatever moves one APDU and brings back the answer. */
interface ApduChannel : java.io.Closeable {
    fun transceive(apdu: ByteArray): ByteArray
}

/**
 * CTAP2 over NFC.
 *
 * A tag can leave the field at any moment, and the honest report of that is
 * "the key was moved away", not a stack trace: everything that touches the card
 * turns an IO failure into that sentence.
 */
class NfcCtapTransport(private val channel: ApduChannel) : CtapTransport {
    override val kind = KeyTransport.NFC

    /** Select the applet; the token is not usable until it answers this. */
    fun open() {
        val answer = send(NfcCtap.select())
        val status = NfcCtap.statusWord(answer)
        if (status != NfcCtap.SW_OK) throw CtapException(NfcCtap.statusMessage(status))
    }

    override fun exchange(command: Int, payload: ByteArray): ByteArray {
        val message = byteArrayOf(command.toByte()) + payload
        var answer = ByteArray(0)
        for (apdu in NfcCtap.commands(message)) {
            answer = send(apdu)
            val status = NfcCtap.statusWord(answer)
            // Every chained command but the last answers plain success with no
            // body; only the last one starts the reply.
            if (status != NfcCtap.SW_OK && NfcCtap.moreToCome(status) == null) {
                throw CtapException(NfcCtap.statusMessage(status))
            }
        }
        val collected = ByteArrayOutputStream()
        collected.write(NfcCtap.body(answer))
        var remaining = NfcCtap.moreToCome(NfcCtap.statusWord(answer))
        while (remaining != null) {
            answer = send(NfcCtap.getResponse(remaining))
            val status = NfcCtap.statusWord(answer)
            if (status != NfcCtap.SW_OK && NfcCtap.moreToCome(status) == null) {
                throw CtapException(NfcCtap.statusMessage(status))
            }
            collected.write(NfcCtap.body(answer))
            remaining = NfcCtap.moreToCome(status)
        }
        val ctap = collected.toByteArray()
        if (ctap.isEmpty()) throw CtapException("The security key answered nothing.")
        val ctapStatus = ctap[0].toInt() and 0xFF
        if (ctapStatus != 0) throw Ctap.failure(ctapStatus, kind)
        return ctap.copyOfRange(1, ctap.size)
    }

    override fun close() {
        runCatching { channel.close() }
    }

    private fun send(apdu: ByteArray): ByteArray = try {
        channel.transceive(apdu)
    } catch (e: Exception) {
        throw CtapException("The security key was moved away — hold it against the back of the phone until it is done.", recoverable = true)
    }
}

/** An [IsoDep] tag as an APDU channel. */
class IsoDepChannel(private val tag: IsoDep) : ApduChannel {
    init {
        // A touch is only asked for after the applet is selected, and a person
        // takes seconds over it; the default timeout is far shorter than that.
        tag.timeout = TIMEOUT_MS
        if (!tag.isConnected) tag.connect()
    }

    override fun transceive(apdu: ByteArray): ByteArray = tag.transceive(apdu)

    override fun close() {
        runCatching { tag.close() }
    }

    private companion object {
        const val TIMEOUT_MS = 30_000
    }
}
