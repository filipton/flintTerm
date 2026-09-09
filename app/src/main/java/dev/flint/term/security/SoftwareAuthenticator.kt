package dev.flint.term.security

import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * A FIDO2 authenticator with no hardware behind it, for tests.
 *
 * Nothing in the app ever uses it: it exists because the CBOR, the CTAPHID
 * framing and the NFC APDU chaining are all exercised by talking to *something*,
 * and there is no security key on a build machine. Wrapped in a fake HID device
 * or a fake card it drives the same code a real token would, so the only part
 * left unproven is the wire itself.
 *
 * It answers raw CTAP2 — a status byte and then CBOR — because that is what a
 * transport carries; turning a status into a sentence is the transport's job and
 * is worth testing too.
 *
 * Its client-PIN half is written out from the specification's own words rather
 * than by calling [PinProtocol], on purpose: two implementations of the same
 * paragraphs that agree are evidence, and one implementation talking to itself
 * is not. It is the only check on the PIN protocol there can be here, because
 * this machine has no security key.
 */
class SoftwareAuthenticator(
    /** Off when the platform has no Ed25519, which is also how a token that only does ES256 is modeled. */
    private val algorithms: List<SecurityKeyAlgorithm> = supportedAlgorithms(),
) {
    /** Clear to model a token that signs without anybody being there. */
    var userPresent: Boolean = true

    /** Set to model a token that will not act until its PIN has been entered. */
    var pinRequired: Boolean = false

    /**
     * The PIN this token has, if any. Setting it turns on the whole client-PIN
     * side; a test double is allowed the String the app is not.
     */
    var pin: String? = null
        set(value) {
            field = value
            pinRequired = value != null
        }

    /** Which PIN/UV auth protocols to advertise, newest first as a real token does. */
    var pinProtocols: List<Long> = listOf(2L, 1L)

    /** Clear to model a CTAP 2.0 token that only knows `getPinToken` (0x05). */
    var permissionsSubcommand: Boolean = true

    /** What the token would answer `getPINRetries` with. */
    var pinRetries: Int = 8

    /** Set to model somebody declining the touch. */
    var touchDeclined: Boolean = false

    private val random = SecureRandom()
    private val credentials = mutableMapOf<String, Credential>()
    private var counter = 0
    private val clientPin = ClientPinSide()

    private class Credential(val algorithm: SecurityKeyAlgorithm, val keys: KeyPair, val application: String)

    /** One CTAP2 exchange: the status byte, then whatever CBOR goes with it. */
    fun process(command: Int, payload: ByteArray): ByteArray = try {
        when (command) {
            CtapCommand.GET_INFO -> ok(getInfo())
            CtapCommand.MAKE_CREDENTIAL -> ok(makeCredential(Cbor.decode(payload)))
            CtapCommand.GET_ASSERTION -> ok(getAssertion(Cbor.decode(payload)))
            ClientPin.COMMAND -> ok(clientPin.handle(Cbor.decode(payload)))
            else -> byteArrayOf(0x01) // CTAP1_ERR_INVALID_COMMAND
        }
    } catch (e: Refusal) {
        byteArrayOf(e.status.toByte())
    }

    /** A status the token would answer with rather than an exception. */
    private class Refusal(val status: Int) : Exception()

    private fun ok(body: Any?): ByteArray = byteArrayOf(0x00) + Cbor.encode(body)

    private fun getInfo(): Map<Any, Any?> = mapOf(
        1 to listOf("FIDO_2_0", "FIDO_2_1"),
        3 to ByteArray(16),
        4 to mapOf(
            "rk" to true,
            "up" to true,
            "clientPin" to pinRequired,
            "pinUvAuthToken" to permissionsSubcommand,
        ),
        6 to pinProtocols,
        10 to algorithms.map { mapOf("alg" to it.coseId, "type" to "public-key") },
    )

    private fun makeCredential(request: Any?): Map<Any, Any?> {
        clientPin.verifyRequest(request, hashKey = 1, paramKey = 8, protocolKey = 9)
        if (touchDeclined) throw Refusal(0x27) // CTAP2_ERR_OPERATION_DENIED
        val application = Cbor.field<String>(Cbor.field<Map<*, *>>(request, 2, "rp"), "id", "the rp id")
        val wanted = Cbor.field<List<*>>(request, 4, "the algorithm list")
            .mapNotNull { Cbor.optional<Long>(it, "alg") }
            .firstNotNullOfOrNull { id -> algorithms.firstOrNull { it.coseId == id } }
            ?: throw Refusal(0x26) // CTAP2_ERR_UNSUPPORTED_ALGORITHM

        val keys = generate(wanted)
        val credentialId = ByteArray(32).also(random::nextBytes)
        credentials[credentialId.toHex()] = Credential(wanted, keys, application)

        val attested = ByteArrayOutputStream().apply {
            write(ByteArray(16)) // AAGUID; a software token has none worth publishing.
            write(credentialId.size ushr 8)
            write(credentialId.size and 0xFF)
            write(credentialId)
            write(Cbor.encode(coseKey(wanted, keys)))
        }.toByteArray()
        val flags = (if (userPresent) 0x01 else 0x00) or 0x40
        return mapOf(
            1 to "none",
            2 to authenticatorData(application, flags, nextCounter()) + attested,
            3 to emptyMap<Any, Any?>(),
        )
    }

    private fun getAssertion(request: Any?): Map<Any, Any?> {
        clientPin.verifyRequest(request, hashKey = 2, paramKey = 6, protocolKey = 7)
        if (touchDeclined) throw Refusal(0x27)
        val application = Cbor.field<String>(request, 1, "the rp id")
        val clientDataHash = Cbor.field<ByteArray>(request, 2, "the client data hash")
        val allowed = Cbor.field<List<*>>(request, 3, "the credential list")
            .mapNotNull { Cbor.optional<ByteArray>(it, "id") }
        val (id, credential) = allowed
            .firstNotNullOfOrNull { id -> credentials[id.toHex()]?.let { id to it } }
            ?: throw Refusal(0x2E) // CTAP2_ERR_NO_CREDENTIALS
        if (credential.application != application) throw Refusal(0x2E)

        val flags = if (userPresent) 0x01 else 0x00
        val data = authenticatorData(application, flags, nextCounter())
        return mapOf(
            1 to mapOf("type" to "public-key", "id" to id),
            2 to data,
            3 to sign(credential, data + clientDataHash),
        )
    }

    private fun authenticatorData(application: String, flags: Int, count: Int): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256").digest(application.toByteArray()) +
            byteArrayOf(flags.toByte()) +
            byteArrayOf((count ushr 24).toByte(), (count ushr 16).toByte(), (count ushr 8).toByte(), count.toByte())

    private fun nextCounter(): Int = ++counter

    private fun generate(algorithm: SecurityKeyAlgorithm): KeyPair = when (algorithm) {
        SecurityKeyAlgorithm.Ed25519 -> KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        SecurityKeyAlgorithm.EcdsaP256 -> KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
    }

    private fun sign(credential: Credential, message: ByteArray): ByteArray {
        val algorithm = when (credential.algorithm) {
            SecurityKeyAlgorithm.Ed25519 -> "Ed25519"
            // ES256 is ECDSA over the SHA-256 of the message, which is what the
            // token does and what OpenSSH will check.
            SecurityKeyAlgorithm.EcdsaP256 -> "SHA256withECDSA"
        }
        return Signature.getInstance(algorithm).run {
            initSign(credential.keys.private)
            update(message)
            sign()
        }
    }

    /** The public half as COSE, the shape a token reports it in. */
    private fun coseKey(algorithm: SecurityKeyAlgorithm, keys: KeyPair): Map<Any, Any?> = when (algorithm) {
        SecurityKeyAlgorithm.Ed25519 -> mapOf(
            1 to 1L, // kty: OKP
            3 to algorithm.coseId,
            -1 to 6L, // crv: Ed25519
            -2 to rawEd25519(keys),
        )
        SecurityKeyAlgorithm.EcdsaP256 -> {
            val point = (keys.public as ECPublicKey).w
            mapOf(
                1 to 2L, // kty: EC2
                3 to algorithm.coseId,
                -1 to 1L, // crv: P-256
                -2 to fixed(point.affineX.toByteArray(), 32),
                -3 to fixed(point.affineY.toByteArray(), 32),
            )
        }
    }

    /**
     * The 32 raw bytes of an Ed25519 public key.
     *
     * The JDK will only hand it over as SubjectPublicKeyInfo, whose length for
     * Ed25519 is fixed — so the key is the tail, and taking it is exact rather
     * than a guess.
     */
    private fun rawEd25519(keys: KeyPair): ByteArray {
        val spki = keys.public.encoded
        if (spki.size < 32) throw IllegalStateException("an Ed25519 public key should be longer than this")
        return spki.copyOfRange(spki.size - 32, spki.size)
    }

    private fun fixed(bytes: ByteArray, size: Int): ByteArray = when {
        bytes.size == size -> bytes
        bytes.size > size -> bytes.copyOfRange(bytes.size - size, bytes.size)
        else -> ByteArray(size - bytes.size) + bytes
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /**
     * The authenticator's side of `authenticatorClientPIN`, from §6.5 outwards.
     *
     * Deliberately its own arithmetic: ECDH here, HKDF written out from RFC 5869
     * here, AES and HMAC assembled here. If it agreed with [PinProtocol] only
     * because it called it, a test between the two would prove nothing at all.
     */
    private inner class ClientPinSide {
        /** The key agreement key, thrown away and remade after every bad PIN. */
        private var keys = freshKeys()
        private var token = ByteArray(32).also(random::nextBytes)

        /** Three in a row and the token demands a power cycle, per §6.5.5.7.1. */
        private var mismatchesInARow = 0

        fun handle(request: Any?): Map<Any, Any?> = when (Cbor.field<Long>(request, 2, "the subcommand").toInt()) {
            ClientPin.SUB_GET_RETRIES -> mapOf(3 to pinRetries.toLong())
            ClientPin.SUB_GET_KEY_AGREEMENT -> mapOf(1 to publicKey())
            ClientPin.SUB_GET_PIN_TOKEN -> pinToken(request, withPermissions = false)
            ClientPin.SUB_GET_TOKEN_WITH_PERMISSIONS -> {
                if (!permissionsSubcommand) throw Refusal(0x02) // CTAP1_ERR_INVALID_PARAMETER
                pinToken(request, withPermissions = true)
            }
            else -> throw Refusal(0x02)
        }

        /**
         * A request's `pinUvAuthParam`, checked the way a token checks it: the
         * MAC has to be over the client-data hash and under the current token.
         */
        fun verifyRequest(request: Any?, hashKey: Int, paramKey: Int, protocolKey: Int) {
            if (!pinRequired) return
            val param = Cbor.optional<ByteArray>(request, paramKey) ?: throw Refusal(0x36) // CTAP2_ERR_PUAT_REQUIRED
            val version = Cbor.optional<Long>(request, protocolKey) ?: 1L
            val hash = Cbor.field<ByteArray>(request, hashKey, "the client data hash")
            if (!param.contentEquals(authenticate(version, token, hash))) {
                throw Refusal(0x33) // CTAP2_ERR_PIN_AUTH_INVALID
            }
        }

        private fun pinToken(request: Any?, withPermissions: Boolean): Map<Any, Any?> {
            val version = Cbor.field<Long>(request, 1, "the pin protocol")
            if (version !in pinProtocols) throw Refusal(0x02)
            if (withPermissions && Cbor.optional<Long>(request, 9) == null) throw Refusal(0x02)
            if (!withPermissions && Cbor.optional<Long>(request, 9) != null) throw Refusal(0x02)
            if (pinRetries <= 0) throw Refusal(0x32) // CTAP2_ERR_PIN_BLOCKED

            val secret = decapsulate(version, Cbor.field<Map<*, *>>(request, 3, "the platform key"))
            val offered = decrypt(version, secret, Cbor.field<ByteArray>(request, 6, "the pin hash"))
            val expected = digest(pin.orEmpty().toByteArray(Charsets.UTF_8)).copyOf(16)
            pinRetries--
            if (!offered.contentEquals(expected)) {
                keys = freshKeys()
                mismatchesInARow++
                throw when {
                    pinRetries <= 0 -> Refusal(0x32)
                    mismatchesInARow >= 3 -> Refusal(0x34) // CTAP2_ERR_PIN_AUTH_BLOCKED
                    else -> Refusal(0x31) // CTAP2_ERR_PIN_INVALID
                }
            }
            pinRetries = 8
            mismatchesInARow = 0
            token = ByteArray(32).also(random::nextBytes)
            return mapOf(2 to encrypt(version, secret, token))
        }

        // ---- the crypto, spelled out from §6.5.6 and §6.5.7 -------------------

        private fun publicKey(): Map<Any, Any?> {
            val point = (keys.public as ECPublicKey).w
            return mapOf(
                1 to 2L,
                3 to -25L,
                -1 to 1L,
                -2 to fixed(point.affineX.toByteArray(), 32),
                -3 to fixed(point.affineY.toByteArray(), 32),
            )
        }

        private fun decapsulate(version: Long, peer: Any?): ByteArray {
            val parameters = java.security.AlgorithmParameters.getInstance("EC")
                .apply { init(ECGenParameterSpec("secp256r1")) }
                .getParameterSpec(java.security.spec.ECParameterSpec::class.java)
            val point = java.security.spec.ECPoint(
                java.math.BigInteger(1, Cbor.field<ByteArray>(peer, -2, "the platform key")),
                java.math.BigInteger(1, Cbor.field<ByteArray>(peer, -3, "the platform key")),
            )
            val public = java.security.KeyFactory.getInstance("EC")
                .generatePublic(java.security.spec.ECPublicKeySpec(point, parameters))
            val z = javax.crypto.KeyAgreement.getInstance("ECDH").run {
                init(keys.private)
                doPhase(public, true)
                generateSecret()
            }
            return if (version == 2L) hkdf(z, "CTAP2 HMAC key") + hkdf(z, "CTAP2 AES key") else digest(z)
        }

        /** HKDF-SHA-256 with a 32-byte zero salt and a 32-byte output, per RFC 5869. */
        private fun hkdf(ikm: ByteArray, info: String): ByteArray {
            val prk = mac(ByteArray(32), ikm)
            return mac(prk, info.toByteArray(Charsets.UTF_8) + byteArrayOf(1))
        }

        private fun encrypt(version: Long, secret: ByteArray, plaintext: ByteArray): ByteArray = if (version == 2L) {
            val iv = ByteArray(16).also(random::nextBytes)
            iv + block(javax.crypto.Cipher.ENCRYPT_MODE, secret.copyOfRange(32, 64), iv, plaintext)
        } else {
            block(javax.crypto.Cipher.ENCRYPT_MODE, secret, ByteArray(16), plaintext)
        }

        private fun decrypt(version: Long, secret: ByteArray, ciphertext: ByteArray): ByteArray = if (version == 2L) {
            block(
                javax.crypto.Cipher.DECRYPT_MODE,
                secret.copyOfRange(32, 64),
                ciphertext.copyOf(16),
                ciphertext.copyOfRange(16, ciphertext.size),
            )
        } else {
            block(javax.crypto.Cipher.DECRYPT_MODE, secret, ByteArray(16), ciphertext)
        }

        private fun authenticate(version: Long, key: ByteArray, message: ByteArray): ByteArray {
            val full = mac(if (key.size > 32) key.copyOf(32) else key, message)
            return if (version == 2L) full else full.copyOf(16)
        }

        private fun mac(key: ByteArray, message: ByteArray): ByteArray =
            javax.crypto.Mac.getInstance("HmacSHA256").run {
                init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
                doFinal(message)
            }

        private fun block(mode: Int, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
            javax.crypto.Cipher.getInstance("AES/CBC/NoPadding").run {
                init(mode, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.IvParameterSpec(iv))
                doFinal(data)
            }

        private fun digest(bytes: ByteArray): ByteArray =
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes)

        private fun freshKeys(): KeyPair = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
    }

    companion object {
        /**
         * What this platform can actually sign with.
         *
         * Ed25519 arrived in the JDK at 15 and in Android at 33, so a token
         * modeled here does what the machine running it can do — which happens
         * to make "a token that only does ECDSA" a case the tests get for free.
         */
        fun supportedAlgorithms(): List<SecurityKeyAlgorithm> = SecurityKeyAlgorithm.entries.filter {
            it == SecurityKeyAlgorithm.EcdsaP256 || runCatching { KeyPairGenerator.getInstance("Ed25519") }.isSuccess
        }
    }
}
