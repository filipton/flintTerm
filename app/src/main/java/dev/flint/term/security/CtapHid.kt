package dev.flint.term.security

/**
 * CTAPHID: CTAP2 cut into the 64-byte reports a USB HID device moves.
 *
 * A message goes out as one initialization packet — channel, command, the total
 * length, then as much payload as fits — followed by continuation packets
 * numbered from zero. The answer comes back the same way, and the length in its
 * first packet is the only thing that says how many more to wait for.
 *
 * A token also sends KEEPALIVE while it waits for a finger, which is not the
 * answer and must not be mistaken for one: it is what makes "touch your key"
 * more than a guess.
 */
object CtapHid {
    const val PACKET_SIZE = 64
    const val BROADCAST_CHANNEL = -1 // 0xFFFFFFFF

    const val CMD_PING = 0x01
    const val CMD_INIT = 0x06
    const val CMD_CBOR = 0x10
    const val CMD_CANCEL = 0x11
    const val CMD_KEEPALIVE = 0x3B
    const val CMD_ERROR = 0x3F

    /** The token is waiting for a touch, rather than working. */
    const val KEEPALIVE_UP_NEEDED = 0x02

    private const val INIT_HEADER = 7
    private const val CONT_HEADER = 5

    /**
     * The packets carrying [payload], ready to write one at a time.
     *
     * Every packet is padded to the full report size: a HID interrupt endpoint
     * moves whole reports, and a short write is a report the token never sees.
     */
    fun frames(channel: Int, command: Int, payload: ByteArray, packetSize: Int = PACKET_SIZE): List<ByteArray> {
        require(command in 0..0x7F) { "a CTAPHID command is seven bits" }
        val first = packetSize - INIT_HEADER
        val rest = packetSize - CONT_HEADER
        if (payload.size > first + rest * 128) {
            throw CtapException("That request is too large for this security key.")
        }
        val out = mutableListOf<ByteArray>()
        val head = ByteArray(packetSize)
        writeChannel(head, channel)
        head[4] = (0x80 or command).toByte()
        head[5] = ((payload.size ushr 8) and 0xFF).toByte()
        head[6] = (payload.size and 0xFF).toByte()
        val headBytes = minOf(first, payload.size)
        payload.copyInto(head, INIT_HEADER, 0, headBytes)
        out += head

        var at = headBytes
        var sequence = 0
        while (at < payload.size) {
            val packet = ByteArray(packetSize)
            writeChannel(packet, channel)
            packet[4] = sequence.toByte()
            val n = minOf(rest, payload.size - at)
            payload.copyInto(packet, CONT_HEADER, at, at + n)
            at += n
            sequence++
            out += packet
        }
        return out
    }

    /**
     * Puts an answer back together as its packets arrive.
     *
     * Kept as a state machine rather than a loop over a reader because that is
     * the only shape in which the sequence numbers can be checked: a dropped
     * packet shows up as a gap, and continuing past one would splice unrelated
     * bytes into the middle of a signature.
     */
    class Reassembler(private val channel: Int) {
        private var command = -1
        private var expected = -1
        private val buffer = java.io.ByteArrayOutputStream()
        private var sequence = 0

        /** The finished message once [packet] completed it, else null. */
        fun accept(packet: ByteArray): Message? {
            if (packet.size < CONT_HEADER) throw CtapException("The security key sent a packet that is too short.")
            val theirs = readChannel(packet)
            // Another application on the same device has its own channel and its
            // traffic is not ours to reassemble.
            if (theirs != channel) return null
            val marker = packet[4].toInt() and 0xFF
            if (marker and 0x80 != 0) {
                command = marker and 0x7F
                expected = ((packet[5].toInt() and 0xFF) shl 8) or (packet[6].toInt() and 0xFF)
                buffer.reset()
                sequence = 0
                append(packet, INIT_HEADER)
            } else {
                if (command < 0) throw CtapException("The security key sent a continuation before anything to continue.")
                if (marker != sequence) {
                    throw CtapException("The security key's answer arrived out of order (packet $marker, expected $sequence).")
                }
                sequence++
                append(packet, CONT_HEADER)
            }
            return if (buffer.size() >= expected) Message(command, buffer.toByteArray().copyOf(expected)) else null
        }

        private fun append(packet: ByteArray, from: Int) {
            val room = expected - buffer.size()
            val n = minOf(room, packet.size - from)
            if (n > 0) buffer.write(packet, from, n)
        }
    }

    data class Message(val command: Int, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /** What a CTAPHID ERROR packet's one byte means. */
    fun errorMessage(code: Int): String = when (code) {
        0x01 -> "The security key did not understand the request."
        0x04 -> "The security key lost part of the request."
        0x05 -> "The security key stopped answering."
        0x06 -> "The security key is busy with another app."
        0x0B -> "The security key closed the channel. Unplug it and plug it back in."
        else -> "The security key reported HID error 0x%02x.".format(code)
    }

    private fun writeChannel(packet: ByteArray, channel: Int) {
        packet[0] = (channel ushr 24).toByte()
        packet[1] = (channel ushr 16).toByte()
        packet[2] = (channel ushr 8).toByte()
        packet[3] = channel.toByte()
    }

    private fun readChannel(packet: ByteArray): Int =
        ((packet[0].toInt() and 0xFF) shl 24) or
            ((packet[1].toInt() and 0xFF) shl 16) or
            ((packet[2].toInt() and 0xFF) shl 8) or
            (packet[3].toInt() and 0xFF)
}

/**
 * The 64-byte reports themselves, without saying who moves them.
 *
 * USB is one implementation and a test's fake device is another, which is what
 * makes the framing above testable without hardware.
 */
interface HidReports : java.io.Closeable {
    val packetSize: Int
    fun write(report: ByteArray)

    /** One report, or null if none arrived within [timeoutMs]. */
    fun read(timeoutMs: Int): ByteArray?
}

/**
 * CTAP2 over CTAPHID.
 *
 * The channel is allocated once with INIT, because a token hands out a private
 * channel per application and using the broadcast one for real traffic collides
 * with whatever else is talking to the same key.
 */
class HidCtapTransport(
    private val reports: HidReports,
    override val kind: KeyTransport = KeyTransport.USB,
    /** The device's own product name, so a PIN prompt can say which key it means. */
    override val name: String? = null,
    /** How long to wait for a touch before giving up on the person. */
    private val touchTimeoutMs: Int = 60_000,
) : CtapTransport {
    private var channel = CtapHid.BROADCAST_CHANNEL

    /** Called every time the token reports it is waiting for a finger. */
    var onTouchNeeded: () -> Unit = {}

    fun open() {
        val nonce = ByteArray(8).also { java.security.SecureRandom().nextBytes(it) }
        val answer = transceive(CtapHid.CMD_INIT, nonce, READY_TIMEOUT_MS)
        if (answer.size < 17 || !answer.copyOfRange(0, 8).contentEquals(nonce)) {
            throw CtapException("This device answered, but not like a security key.")
        }
        channel = ((answer[8].toInt() and 0xFF) shl 24) or
            ((answer[9].toInt() and 0xFF) shl 16) or
            ((answer[10].toInt() and 0xFF) shl 8) or
            (answer[11].toInt() and 0xFF)
    }

    override fun exchange(command: Int, payload: ByteArray): ByteArray {
        val answer = transceive(CtapHid.CMD_CBOR, byteArrayOf(command.toByte()) + payload, touchTimeoutMs)
        if (answer.isEmpty()) throw CtapException("The security key answered nothing.")
        val status = answer[0].toInt() and 0xFF
        if (status != 0) throw Ctap.failure(status, kind)
        return answer.copyOfRange(1, answer.size)
    }

    override fun close() {
        // A token holds the channel until it hears otherwise, and a half-finished
        // request would leave the next one waiting on a touch nobody asked for.
        runCatching { if (channel != CtapHid.BROADCAST_CHANNEL) writeAll(CtapHid.CMD_CANCEL, ByteArray(0)) }
        reports.close()
    }

    private fun transceive(command: Int, payload: ByteArray, timeoutMs: Int): ByteArray {
        writeAll(command, payload)
        val reassembler = CtapHid.Reassembler(channel)
        val deadline = System.currentTimeMillis() + timeoutMs
        var announced = false
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) throw CtapException("The security key did not answer in time.", recoverable = true)
            val packet = reports.read(minOf(remaining, READ_SLICE_MS.toLong()).toInt())
                ?: if (System.currentTimeMillis() >= deadline) {
                    throw CtapException("The security key did not answer in time.", recoverable = true)
                } else {
                    continue
                }
            val message = reassembler.accept(packet) ?: continue
            when {
                message.command == command -> return message.payload
                // Said once: a token repeats KEEPALIVE every 100 ms for as long
                // as it is waiting, and it is the same news each time.
                message.command == CtapHid.CMD_KEEPALIVE -> {
                    if (!announced && message.payload.firstOrNull()?.toInt() == CtapHid.KEEPALIVE_UP_NEEDED) {
                        announced = true
                        onTouchNeeded()
                    }
                }
                message.command == CtapHid.CMD_ERROR ->
                    throw CtapException(CtapHid.errorMessage(message.payload.firstOrNull()?.toInt()?.and(0xFF) ?: 0))
                else -> throw CtapException("The security key answered a question nobody asked (0x%02x).".format(message.command))
            }
        }
    }

    private fun writeAll(command: Int, payload: ByteArray) {
        CtapHid.frames(channel, command, payload, reports.packetSize).forEach(reports::write)
    }

    private companion object {
        /** The token is right there, so INIT either answers at once or is not a token. */
        const val READY_TIMEOUT_MS = 3_000

        /** Short reads keep the deadline honest without spinning the CPU. */
        const val READ_SLICE_MS = 250
    }
}
