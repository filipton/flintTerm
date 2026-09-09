package dev.flint.term.security

import java.io.ByteArrayOutputStream

/**
 * The slice of CBOR that CTAP2 speaks, and no more.
 *
 * A security key exchanges maps of small integers holding byte strings, text and
 * nested maps; there are no tags, no floats and no indefinite lengths, because
 * CTAP2's canonical form forbids them. Writing those few cases out is a few
 * hundred lines and costs the app no dependency, which matters more here than
 * usual: this code reads bytes from a device somebody plugged in.
 *
 * Canonical order is not decoration either. A token computes its `pinUvAuthParam`
 * over the bytes it received, so a map serialized in the wrong order is a
 * request it will refuse.
 */
object Cbor {
    private const val UNSIGNED = 0
    private const val NEGATIVE = 1
    private const val BYTES = 2
    private const val TEXT = 3
    private const val ARRAY = 4
    private const val MAP = 5
    private const val SIMPLE = 7

    /** Raised for anything that is not CBOR we can act on; the message is shown. */
    class Malformed(message: String) : Exception(message)

    // ---- encoding ------------------------------------------------------------

    fun encode(value: Any?): ByteArray = ByteArrayOutputStream().also { write(it, value) }.toByteArray()

    private fun write(out: ByteArrayOutputStream, value: Any?) {
        when (value) {
            null -> out.write(0xF6)
            is Boolean -> out.write(if (value) 0xF5 else 0xF4)
            is Int -> write(out, value.toLong())
            is Long -> if (value >= 0) head(out, UNSIGNED, value) else head(out, NEGATIVE, -1 - value)
            is ByteArray -> {
                head(out, BYTES, value.size.toLong())
                out.write(value)
            }
            is String -> {
                val utf8 = value.toByteArray(Charsets.UTF_8)
                head(out, TEXT, utf8.size.toLong())
                out.write(utf8)
            }
            is List<*> -> {
                head(out, ARRAY, value.size.toLong())
                value.forEach { write(out, it) }
            }
            is Map<*, *> -> {
                head(out, MAP, value.size.toLong())
                // Canonical order is over the encoded keys: shorter first, then
                // bytewise. Sorting here rather than asking every caller to keep
                // its literals in order is the only way it cannot be forgotten.
                value.entries
                    .map { encode(it.key) to it.value }
                    .sortedWith { a, b -> canonicalOrder(a.first, b.first) }
                    .forEach { (key, v) ->
                        out.write(key)
                        write(out, v)
                    }
            }
            else -> throw Malformed("${value.javaClass.simpleName} is not something CTAP2 carries")
        }
    }

    private fun head(out: ByteArrayOutputStream, major: Int, value: Long) {
        val prefix = major shl 5
        when {
            value < 24 -> out.write(prefix or value.toInt())
            value < 0x100 -> {
                out.write(prefix or 24)
                out.write(value.toInt())
            }
            value < 0x10000 -> {
                out.write(prefix or 25)
                out.write((value ushr 8).toInt() and 0xFF)
                out.write(value.toInt() and 0xFF)
            }
            value < 0x1_0000_0000L -> {
                out.write(prefix or 26)
                for (shift in intArrayOf(24, 16, 8, 0)) out.write((value ushr shift).toInt() and 0xFF)
            }
            else -> {
                out.write(prefix or 27)
                for (shift in intArrayOf(56, 48, 40, 32, 24, 16, 8, 0)) out.write((value ushr shift).toInt() and 0xFF)
            }
        }
    }

    /**
     * Shorter key first, then bytewise — and unsigned, or a key starting 0x80
     * would sort before one starting 0x01.
     */
    private fun canonicalOrder(a: ByteArray, b: ByteArray): Int {
        if (a.size != b.size) return a.size - b.size
        for (i in a.indices) {
            val difference = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (difference != 0) return difference
        }
        return 0
    }

    // ---- decoding ------------------------------------------------------------

    /**
     * The one value in [bytes]. Trailing bytes are an error rather than a
     * shrug: a truncated or doubled response is worth noticing here, not three
     * layers up when a field turns out to be missing.
     */
    fun decode(bytes: ByteArray): Any? {
        val reader = Reader(bytes)
        val value = reader.value()
        if (reader.at != bytes.size) throw Malformed("${bytes.size - reader.at} bytes left over after the CBOR value")
        return value
    }

    private class Reader(val bytes: ByteArray, var at: Int = 0) {
        fun byte(): Int {
            if (at >= bytes.size) throw Malformed("the CBOR value stops in the middle")
            return bytes[at++].toInt() and 0xFF
        }

        fun take(n: Long): ByteArray {
            if (n < 0 || at + n > bytes.size) throw Malformed("a CBOR string claims $n bytes that are not there")
            val out = bytes.copyOfRange(at, at + n.toInt())
            at += n.toInt()
            return out
        }

        fun argument(low: Int): Long = when (low) {
            in 0..23 -> low.toLong()
            24 -> byte().toLong()
            25 -> (byte().toLong() shl 8) or byte().toLong()
            26 -> (0 until 4).fold(0L) { acc, _ -> (acc shl 8) or byte().toLong() }
            27 -> (0 until 8).fold(0L) { acc, _ -> (acc shl 8) or byte().toLong() }
            // 31 is an indefinite length, which canonical CTAP2 CBOR does not use.
            else -> throw Malformed("this CBOR uses a length form CTAP2 does not")
        }

        fun value(): Any? {
            val initial = byte()
            val major = initial shr 5
            val low = initial and 0x1F
            return when (major) {
                UNSIGNED -> argument(low)
                NEGATIVE -> -1 - argument(low)
                BYTES -> take(argument(low))
                TEXT -> String(take(argument(low)), Charsets.UTF_8)
                ARRAY -> (0 until argument(low)).map { value() }
                MAP -> {
                    val out = LinkedHashMap<Any?, Any?>()
                    repeat(argument(low).toInt()) { out[value()] = value() }
                    out
                }
                SIMPLE -> when (low) {
                    20 -> false
                    21 -> true
                    22 -> null
                    23 -> null
                    else -> throw Malformed("this CBOR carries a simple value CTAP2 does not use")
                }
                else -> throw Malformed("this CBOR carries a tag CTAP2 does not use")
            }
        }
    }

    // ---- reading a decoded map -----------------------------------------------

    /**
     * Field [key] of a CTAP2 response map, insisting on its type.
     *
     * Tokens differ, and a missing or surprising field should name itself rather
     * than arrive later as a ClassCastException with no context.
     */
    inline fun <reified T> field(map: Any?, key: Any, what: String): T {
        val m = map as? Map<*, *> ?: throw Malformed("the security key answered $what with something that is not a map")
        val value = m[normalize(key)] ?: throw Malformed("the security key left $what out of its answer")
        return value as? T ?: throw Malformed("the security key answered $what with a ${value.javaClass.simpleName}")
    }

    inline fun <reified T> optional(map: Any?, key: Any): T? = (map as? Map<*, *>)?.get(normalize(key)) as? T

    /** Integer keys always come back as [Long], so lookups have to use one too. */
    fun normalize(key: Any): Any = if (key is Int) key.toLong() else key
}
