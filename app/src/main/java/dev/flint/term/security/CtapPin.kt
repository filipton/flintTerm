package dev.flint.term.security

import java.math.BigInteger
import java.nio.CharBuffer
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * `authenticatorClientPIN`, which is how a token with a PIN is talked into
 * working at all.
 *
 * The shape is always the same: agree an ephemeral secret with the token over
 * P-256, send it the first 16 bytes of SHA-256 of the PIN under that secret, and
 * get back a `pinUvAuthToken` — a key the token will accept a MAC from for the
 * rest of the session. Every later request carries `authenticate(token, …)` over
 * its client-data hash, which is what proves the PIN was entered without the PIN
 * itself ever going near the wire.
 *
 * Everything in this file is deliberately free of transports and of Android, so
 * the parts that must be exactly right — the key derivation, the ciphers, the
 * MAC truncation — can be checked against the primitives' own published vectors
 * rather than against a token nobody here has.
 */
sealed class PinProtocol(val version: Long) {
    /** SHA-256 of the ECDH x-coordinate, or the pair of keys protocol two splits. */
    abstract fun kdf(z: ByteArray): ByteArray

    abstract fun encrypt(key: ByteArray, plaintext: ByteArray): ByteArray

    abstract fun decrypt(key: ByteArray, ciphertext: ByteArray): ByteArray

    abstract fun authenticate(key: ByteArray, message: ByteArray): ByteArray

    /**
     * The original, from CTAP 2.0: one 32-byte secret used as both the AES key
     * and the HMAC key, and a fixed all-zero IV.
     */
    object One : PinProtocol(1) {
        override fun kdf(z: ByteArray): ByteArray = sha256(z)

        override fun encrypt(key: ByteArray, plaintext: ByteArray): ByteArray =
            aes(Cipher.ENCRYPT_MODE, key, ByteArray(16), plaintext)

        override fun decrypt(key: ByteArray, ciphertext: ByteArray): ByteArray {
            if (ciphertext.isEmpty() || ciphertext.size % 16 != 0) {
                throw CtapException("The security key sent a PIN answer of the wrong length.")
            }
            return aes(Cipher.DECRYPT_MODE, key, ByteArray(16), ciphertext)
        }

        override fun authenticate(key: ByteArray, message: ByteArray): ByteArray =
            hmacSha256(key, message).copyOf(16)
    }

    /**
     * The FIPS-shaped rewrite: 64 bytes of key material split into an HMAC half
     * and an AES half, a fresh IV in front of every ciphertext, and a MAC that is
     * no longer truncated. Newer tokens list it first, and it is the better of
     * the two, so it is what this app asks for when it is offered.
     */
    object Two : PinProtocol(2) {
        private const val HMAC_INFO = "CTAP2 HMAC key"
        private const val AES_INFO = "CTAP2 AES key"

        override fun kdf(z: ByteArray): ByteArray =
            hkdfSha256(ByteArray(32), z, HMAC_INFO) + hkdfSha256(ByteArray(32), z, AES_INFO)

        override fun encrypt(key: ByteArray, plaintext: ByteArray): ByteArray =
            encrypt(key, plaintext, randomBytes(16))

        /** The IV is a parameter so a test can pin the ciphertext against a published vector. */
        internal fun encrypt(key: ByteArray, plaintext: ByteArray, iv: ByteArray): ByteArray =
            iv + aes(Cipher.ENCRYPT_MODE, aesHalf(key), iv, plaintext)

        override fun decrypt(key: ByteArray, ciphertext: ByteArray): ByteArray {
            if (ciphertext.size < 32 || (ciphertext.size - 16) % 16 != 0) {
                throw CtapException("The security key sent a PIN answer of the wrong length.")
            }
            return aes(Cipher.DECRYPT_MODE, aesHalf(key), ciphertext.copyOf(16), ciphertext.copyOfRange(16, ciphertext.size))
        }

        override fun authenticate(key: ByteArray, message: ByteArray): ByteArray =
            hmacSha256(hmacHalf(key), message)

        /** The shared secret's first half; a pinUvAuthToken is already only that long. */
        private fun hmacHalf(key: ByteArray) = if (key.size > 32) key.copyOf(32) else key

        private fun aesHalf(key: ByteArray) = if (key.size > 32) key.copyOfRange(32, key.size) else key
    }

    companion object {
        /**
         * The protocol to speak to a token that offers [offered].
         *
         * A token listing nothing at all is a CTAP 2.0 one, which knows only
         * protocol one; anything else gets protocol two when it is on the list,
         * because it is the version whose crypto is not a special case.
         */
        fun choose(offered: List<Long>): PinProtocol = when {
            offered.contains(Two.version) -> Two
            else -> One
        }
    }
}

/** Our ephemeral public key as the token wants to see it, and the secret it agrees. */
class PinKeyAgreement(val platformKey: Map<Any, Any?>, val sharedSecret: ByteArray) {
    /** The secret is worth exactly one transaction; nothing keeps it afterwards. */
    fun clear() {
        sharedSecret.fill(0)
    }
}

/**
 * A `pinUvAuthToken` and the protocol it was minted under.
 *
 * It is a key, so it is held in a byte array that gets wiped rather than
 * anywhere it could outlive the operation that needed it.
 */
class PinUvAuthToken(val protocol: PinProtocol, private val token: ByteArray) {
    /** `pinUvAuthParam`: the MAC a request carries to prove the PIN was given. */
    fun param(message: ByteArray): ByteArray = protocol.authenticate(token, message)

    fun clear() {
        token.fill(0)
    }
}

/** What a `pinUvAuthToken` is being asked for, as the permissions bitfield. */
object CtapPermission {
    const val MAKE_CREDENTIAL = 0x01
    const val GET_ASSERTION = 0x02
}

/**
 * The `authenticatorClientPIN` subcommands this app has any use for, and the
 * exchanges that run them.
 *
 * Setting or changing a PIN is deliberately absent: a token's PIN is the thing
 * that protects every credential on it, including ones this app never made, and
 * changing it belongs where the person can see all of them.
 */
object ClientPin {
    const val COMMAND = 0x06

    const val SUB_GET_RETRIES = 0x01
    const val SUB_GET_KEY_AGREEMENT = 0x02
    const val SUB_GET_PIN_TOKEN = 0x05
    const val SUB_GET_TOKEN_WITH_PERMISSIONS = 0x09

    /** The most bytes a PIN may be once it is UTF-8, per CTAP 2.1. */
    const val MAX_PIN_BYTES = 63

    /** The fewest code points a token will accept, absent one saying otherwise. */
    const val MIN_PIN_LENGTH = 4

    /**
     * `LEFT(SHA-256(PIN), 16)` — the proof of knowledge a token compares against
     * what it stored. Only ever sent encrypted under the shared secret.
     */
    fun pinHash(pin: ByteArray): ByteArray = sha256(pin).copyOf(16)

    /**
     * The PIN as the bytes the hash is taken over, without ever making a String.
     *
     * A String would be immutable and interned-adjacent: it would sit in the heap
     * until a garbage collection that may never come, readable by anything that
     * gets a dump. A CharArray can be — and is — overwritten the moment the token
     * has what it needs.
     */
    fun encodePin(pin: CharArray): ByteArray {
        val encoded = Charsets.UTF_8.encode(CharBuffer.wrap(pin))
        val out = ByteArray(encoded.remaining())
        encoded.get(out)
        // The encoder handed back its own buffer holding the same bytes.
        if (encoded.hasArray()) encoded.array().fill(0)
        return out
    }

    /** How many PIN attempts the token says are left, or null if it did not say. */
    fun retries(transport: CtapTransport): Int? {
        val request = linkedMapOf<Any, Any?>(2 to SUB_GET_RETRIES.toLong())
        val answer = Cbor.decode(transport.exchange(COMMAND, Cbor.encode(request)))
        return Cbor.optional<Long>(answer, 3)?.toInt()
    }

    /** §6.5.5.4: the token's key-agreement key, and our half of the same. */
    fun agree(transport: CtapTransport, protocol: PinProtocol): PinKeyAgreement {
        val request = linkedMapOf<Any, Any?>(
            1 to protocol.version,
            2 to SUB_GET_KEY_AGREEMENT.toLong(),
        )
        val answer = Cbor.decode(transport.exchange(COMMAND, Cbor.encode(request)))
        return encapsulate(protocol, Cbor.field<Map<*, *>>(answer, 1, "the key agreement"))
    }

    /**
     * Exchange the PIN for a `pinUvAuthToken`.
     *
     * `getPinUvAuthTokenUsingPinWithPermissions` is asked for where the token
     * says it understands it, because it scopes the token to this relying party
     * and to the one operation about to happen. The superseded `getPinToken` is
     * the fallback, and it is not a rare one: a CTAP 2.0 token knows nothing
     * else. A token that lists the newer subcommand and then rejects it is
     * retried on the old one — a failed attempt makes the authenticator throw its
     * key-agreement key away, so the retry starts from a fresh agreement.
     */
    fun token(
        transport: CtapTransport,
        info: CtapInfo,
        pin: ByteArray,
        permissions: Int,
        rpId: String,
    ): PinUvAuthToken {
        val protocol = PinProtocol.choose(info.pinProtocols)
        if (info.pinUvAuthTokenSupported) {
            try {
                return token(transport, protocol, pin, SUB_GET_TOKEN_WITH_PERMISSIONS, permissions, rpId)
            } catch (e: CtapStatusException) {
                if (e.status !in RETRY_ON_OLD_SUBCOMMAND) throw e
            }
        }
        return token(transport, protocol, pin, SUB_GET_PIN_TOKEN, permissions, rpId)
    }

    private fun token(
        transport: CtapTransport,
        protocol: PinProtocol,
        pin: ByteArray,
        subCommand: Int,
        permissions: Int,
        rpId: String,
    ): PinUvAuthToken {
        val agreed = agree(transport, protocol)
        val hash = pinHash(pin)
        try {
            val request = linkedMapOf<Any, Any?>(
                1 to protocol.version,
                2 to subCommand.toLong(),
                3 to agreed.platformKey,
                6 to protocol.encrypt(agreed.sharedSecret, hash),
            )
            if (subCommand == SUB_GET_TOKEN_WITH_PERMISSIONS) {
                request[9] = permissions.toLong()
                request[10] = rpId
            }
            val answer = Cbor.decode(transport.exchange(COMMAND, Cbor.encode(request)))
            val encrypted = Cbor.field<ByteArray>(answer, 2, "the PIN token")
            return PinUvAuthToken(protocol, protocol.decrypt(agreed.sharedSecret, encrypted))
        } finally {
            hash.fill(0)
            agreed.clear()
        }
    }

    /**
     * §6.5.5.4's `encapsulate`: a throwaway P-256 pair, ECDH against the token's
     * point, and the KDF over the x-coordinate.
     *
     * The peer's point is checked to be on the curve before anything is
     * multiplied by it. An invalid-curve point is the classic way to make an ECDH
     * implementation leak its private key one bit at a time, and the key here is
     * ephemeral but the PIN encrypted under the resulting secret is not.
     */
    internal fun encapsulate(protocol: PinProtocol, peerCose: Any?): PinKeyAgreement {
        val peer = coseToPoint(peerCose)
        val pair = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec(CURVE)) }
            .generateKeyPair()
        val z = KeyAgreement.getInstance("ECDH").run {
            init(pair.private)
            doPhase(peer, true)
            generateSecret()
        }
        try {
            val point = (pair.public as ECPublicKey).w
            return PinKeyAgreement(coseOf(point), protocol.kdf(z))
        } finally {
            z.fill(0)
        }
    }

    /**
     * A COSE_Key as `getKeyAgreement` returns it, turned into a point.
     *
     * `alg` is not checked against anything: protocol one specifies -25 there
     * "although this is not the algorithm actually used", and tokens in the field
     * send a range of values for it.
     */
    internal fun coseToPoint(cose: Any?): ECPublicKey {
        val x = BigInteger(1, Cbor.field<ByteArray>(cose, -2, "the key agreement"))
        val y = BigInteger(1, Cbor.field<ByteArray>(cose, -3, "the key agreement"))
        if (!onCurve(x, y)) throw CtapException("The security key offered a key agreement that is not a P-256 point.")
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), p256())) as ECPublicKey
    }

    /** Our public point in the shape §6.5.6's `getPublicKey` describes. */
    internal fun coseOf(point: ECPoint): Map<Any, Any?> = mapOf(
        // Long keys, so this map reads back through [Cbor.field] exactly as a
        // decoded one would; CBOR cannot tell the two apart on the wire.
        1L to 2L, // kty: EC2
        3L to -25L, // alg: ECDH-ES + HKDF-256, which is what the spec names here.
        -1L to 1L, // crv: P-256
        -2L to coordinate(point.affineX),
        -3L to coordinate(point.affineY),
    )

    /** y² = x³ - 3x + b over the P-256 prime field. */
    internal fun onCurve(x: BigInteger, y: BigInteger): Boolean {
        if (x.signum() < 0 || y.signum() < 0 || x >= P || y >= P) return false
        // BigInteger.TWO is API 33, and this runs on anything from Android 8.
        val left = y.modPow(BigInteger.valueOf(2), P)
        val right = (x.modPow(BigInteger.valueOf(3), P) - x.multiply(BigInteger.valueOf(3)) + B).mod(P)
        return left == right
    }

    private fun coordinate(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return when {
            bytes.size == 32 -> bytes
            // BigInteger prepends a sign byte when the top bit is set, and drops
            // leading zeros; a COSE coordinate is 32 bytes either way.
            bytes.size > 32 -> bytes.copyOfRange(bytes.size - 32, bytes.size)
            else -> ByteArray(32 - bytes.size) + bytes
        }
    }

    private fun p256(): ECParameterSpec = AlgorithmParameters.getInstance("EC")
        .apply { init(ECGenParameterSpec(CURVE)) }
        .getParameterSpec(ECParameterSpec::class.java)

    private const val CURVE = "secp256r1"

    /** A token that will not run the newer subcommand at all, whatever it advertised. */
    private val RETRY_ON_OLD_SUBCOMMAND = setOf(0x01, 0x02, 0x2B)

    private val P = BigInteger("115792089210356248762697446949407573530086143415290314195533631308867097853951")
    private val B = BigInteger("41058363725152142129326129780047268409114441015993725554835256314039467401291")
}

// ---- primitives ------------------------------------------------------------

internal fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

internal fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
    // An all-zero key is legal HKDF salt and illegal to SecretKeySpec, which
    // refuses an empty key but not this one; nothing here ever passes empty.
    init(SecretKeySpec(key, "HmacSHA256"))
    doFinal(message)
}

/**
 * HKDF-SHA-256 for exactly one output block, which is all protocol two asks for.
 *
 * L is 32 and the hash is 32, so expand is a single round: T(1) = HMAC(PRK,
 * info ‖ 0x01). Writing the general form would be code with no second caller.
 */
internal fun hkdfSha256(salt: ByteArray, ikm: ByteArray, info: String): ByteArray =
    hmacSha256(hmacSha256(salt, ikm), info.toByteArray(Charsets.UTF_8) + byteArrayOf(0x01))

private fun aes(mode: Int, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
    Cipher.getInstance("AES/CBC/NoPadding").run {
        init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        doFinal(data)
    }

private fun randomBytes(n: Int) = ByteArray(n).also { java.security.SecureRandom().nextBytes(it) }
