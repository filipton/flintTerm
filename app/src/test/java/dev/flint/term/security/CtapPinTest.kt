package dev.flint.term.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyPair
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
 * The client-PIN protocol, checked against everything except a real token.
 *
 * There is no security key on the machine this was written on, so the standard
 * of proof has to come from somewhere else. It comes from three places: the
 * published vectors of the primitives CTAP 2.1 builds on (RFC 4231 for
 * HMAC-SHA-256, RFC 5869 for HKDF, NIST SP 800-38A for AES-256-CBC), a second
 * implementation of the spec's own paragraphs written here in the test and in
 * [SoftwareAuthenticator], and the two of them agreeing across a whole
 * enroll-and-sign exchange. What none of that can prove is that a Yubikey
 * behaves the way the specification says.
 */
class CtapPinTest {
    // ---- the primitives, against their own published vectors -----------------

    /** RFC 4231 test case 1, which is the MAC every part of this rests on. */
    @Test
    fun `authenticate is HMAC-SHA-256, truncated in protocol one and whole in two`() {
        val key = ByteArray(20) { 0x0b }
        val message = "Hi There".toByteArray()
        val expected = hex("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7")

        assertArrayEquals(expected.copyOf(16), PinProtocol.One.authenticate(key, message))
        assertArrayEquals(expected, PinProtocol.Two.authenticate(key, message))
    }

    /**
     * Protocol two's `authenticate` takes only the HMAC half of a shared secret.
     * A 64-byte key that MACs like its own first 32 bytes is the whole rule.
     */
    @Test
    fun `protocol two authenticates under the first half of the shared secret`() {
        val secret = ByteArray(64) { it.toByte() }
        assertArrayEquals(
            PinProtocol.Two.authenticate(secret.copyOf(32), "message".toByteArray()),
            PinProtocol.Two.authenticate(secret, "message".toByteArray()),
        )
    }

    /**
     * NIST SP 800-38A F.2.5, the AES-256-CBC vector.
     *
     * Protocol two takes its IV as a parameter here so the ciphertext can be
     * pinned to a published one; in the app it is random, as the spec says.
     */
    @Test
    fun `encrypt is AES-256-CBC`() {
        val aesKey = hex("603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4")
        val iv = hex("000102030405060708090a0b0c0d0e0f")
        val plaintext = hex("6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e51")
        val expected = hex("f58c4c04d6e5f1ba779eabfb5f7bfbd6" + "9cfc4e967edb808d679f777bc6702c7d")

        // The AES key is the second half of a protocol-two shared secret.
        val secret = ByteArray(32) + aesKey
        assertArrayEquals(iv + expected, PinProtocol.Two.encrypt(secret, plaintext, iv))
        assertArrayEquals(plaintext, PinProtocol.Two.decrypt(secret, iv + expected))

        // Protocol one is the same cipher with a zero IV and no key splitting.
        assertArrayEquals(
            aesCbc(Cipher.ENCRYPT_MODE, aesKey, ByteArray(16), plaintext),
            PinProtocol.One.encrypt(aesKey, plaintext),
        )
        assertArrayEquals(plaintext, PinProtocol.One.decrypt(aesKey, PinProtocol.One.encrypt(aesKey, plaintext)))
    }

    /** Protocol two prepends a fresh IV, so the same plaintext is never twice the same. */
    @Test
    fun `protocol two picks a new IV every time`() {
        val secret = ByteArray(64) { it.toByte() }
        val once = PinProtocol.Two.encrypt(secret, ByteArray(16))
        val twice = PinProtocol.Two.encrypt(secret, ByteArray(16))
        assertEquals(32, once.size)
        assertTrue("two encryptions should differ", !once.contentEquals(twice))
        assertArrayEquals(ByteArray(16), PinProtocol.Two.decrypt(secret, twice))
    }

    /** RFC 5869 test case 1, proving the HKDF this test then measures ours against. */
    @Test
    fun `the reference HKDF matches RFC 5869`() {
        val okm = hkdf(
            salt = hex("000102030405060708090a0b0c"),
            ikm = ByteArray(22) { 0x0b },
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
        )
        assertArrayEquals(
            hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"),
            okm,
        )
    }

    /**
     * Protocol two's KDF: two separate HKDF invocations, concatenated. The spec
     * is explicit that one call with L=64 is not the same thing, so the halves
     * are checked apart as well as together.
     */
    @Test
    fun `protocol two derives an HMAC key and an AES key`() {
        val z = ByteArray(32) { (it * 7).toByte() }
        val hmacKey = hkdf(ByteArray(32), z, "CTAP2 HMAC key".toByteArray(), 32)
        val aesKey = hkdf(ByteArray(32), z, "CTAP2 AES key".toByteArray(), 32)

        val derived = PinProtocol.Two.kdf(z)
        assertEquals(64, derived.size)
        assertArrayEquals(hmacKey, derived.copyOf(32))
        assertArrayEquals(aesKey, derived.copyOfRange(32, 64))
        assertTrue("the halves must not be one 64-byte expansion", !hmacKey.contentEquals(aesKey))
    }

    /** Protocol one's KDF is SHA-256 of the shared point's x-coordinate, and nothing else. */
    @Test
    fun `protocol one derives the shared secret by hashing Z`() {
        val z = ByteArray(32) { (it * 3).toByte() }
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(z), PinProtocol.One.kdf(z))
    }

    /** `LEFT(SHA-256(PIN), 16)` — the only form of the PIN that leaves the phone. */
    @Test
    fun `the PIN hash is the first half of its SHA-256`() {
        val pin = "1234".toByteArray()
        assertArrayEquals(hex("03ac674216f3e15c761ee1a5e255f067"), ClientPin.pinHash(pin))
        assertArrayEquals(
            MessageDigest.getInstance("SHA-256").digest(pin).copyOf(16),
            ClientPin.pinHash(pin),
        )
    }

    /** A PIN is UTF-8, and the code path that encodes it never builds a String. */
    @Test
    fun `a PIN is encoded as UTF-8`() {
        assertArrayEquals(byteArrayOf(0x31, 0x32, 0x33, 0x34), ClientPin.encodePin(charArrayOf('1', '2', '3', '4')))
        assertArrayEquals("hüsn".toByteArray(Charsets.UTF_8), ClientPin.encodePin("hüsn".toCharArray()))
    }

    // ---- key agreement -------------------------------------------------------

    /**
     * The platform half of the exchange, checked from the other side: an
     * authenticator generated here does its own ECDH and its own KDF, and has to
     * arrive at the same secret.
     */
    @Test
    fun `key agreement arrives at the same secret as the authenticator does`() {
        for (protocol in listOf(PinProtocol.One, PinProtocol.Two)) {
            val authenticator = p256()
            val agreed = ClientPin.encapsulate(protocol, coseOf(authenticator))

            val z = KeyAgreement.getInstance("ECDH").run {
                init(authenticator.private)
                doPhase(pointOf(agreed.platformKey), true)
                generateSecret()
            }
            val theirs = if (protocol == PinProtocol.Two) {
                hkdf(ByteArray(32), z, "CTAP2 HMAC key".toByteArray(), 32) +
                    hkdf(ByteArray(32), z, "CTAP2 AES key".toByteArray(), 32)
            } else {
                MessageDigest.getInstance("SHA-256").digest(z)
            }
            assertArrayEquals("protocol ${protocol.version}", theirs, agreed.sharedSecret)
        }
    }

    /** The platform key goes out in the shape §6.5.6's `getPublicKey` describes. */
    @Test
    fun `the platform key agreement key is a P-256 COSE key`() {
        val agreed = ClientPin.encapsulate(PinProtocol.Two, coseOf(p256()))
        assertEquals(2L, agreed.platformKey[1L])
        assertEquals(-25L, agreed.platformKey[3L])
        assertEquals(1L, agreed.platformKey[-1L])
        assertEquals(32, (agreed.platformKey[-2L] as ByteArray).size)
        assertEquals(32, (agreed.platformKey[-3L] as ByteArray).size)
    }

    /**
     * A point that is not on P-256 is the classic way to walk an ECDH private
     * key out one bit at a time, so it is refused before anything multiplies it.
     */
    @Test
    fun `a key agreement that is not on the curve is refused`() {
        val real = coseOf(p256())
        val bogus = real.toMutableMap().apply { this[-3L] = ByteArray(32) { 0x11 } }
        val error = runCatching { ClientPin.encapsulate(PinProtocol.One, bogus) }.exceptionOrNull()
        assertTrue("$error", error is CtapException)
        assertTrue("$error", error?.message?.contains("not a P-256 point") == true)
    }

    /** Newer tokens list protocol two, and it is the one worth speaking. */
    @Test
    fun `protocol two is preferred wherever it is offered`() {
        assertSame(PinProtocol.Two, PinProtocol.choose(listOf(1L, 2L)))
        assertSame(PinProtocol.Two, PinProtocol.choose(listOf(2L)))
        assertSame(PinProtocol.One, PinProtocol.choose(listOf(1L)))
        // A CTAP 2.0 token lists nothing and knows only protocol one.
        assertSame(PinProtocol.One, PinProtocol.choose(emptyList()))
    }

    // ---- the whole exchange, against the software authenticator --------------

    @Test
    fun `a PIN-protected token enrolls and signs, on both protocols`() {
        for (protocols in listOf(listOf(2L, 1L), listOf(1L))) {
            val token = pinned(protocols = protocols)
            val transport = HidCtapTransport(FakeHidDevice(token)).apply { open() }
            val asked = Asker("1234")
            val info = Ctap.getInfo(transport)
            assertTrue(info.pinSet)

            val pin = Ctap.unlock(transport, info, CtapPermission.MAKE_CREDENTIAL, Ctap.DEFAULT_APPLICATION, asked)!!
            assertEquals(protocols.first(), pin.protocol.version)
            val credential = Ctap.makeCredential(
                transport,
                Ctap.DEFAULT_APPLICATION,
                SecurityKeyAlgorithm.EcdsaP256,
                "chain",
                pin,
            )

            val signing = Ctap.unlock(transport, info, CtapPermission.GET_ASSERTION, Ctap.DEFAULT_APPLICATION, Asker("1234"))!!
            val assertion = Ctap.getAssertion(
                transport,
                credential.application,
                credential.credentialId,
                ByteArray(32) { 0x5a },
                signing,
            )
            assertTrue("the token should have signed", assertion.signature.isNotEmpty())
            assertEquals(1, asked.problems.size) // Only the first ask, with nothing wrong yet.
        }
    }

    /** A CTAP 2.0 token knows only `getPinToken`, and that is not a rare token. */
    @Test
    fun `a token without the permissions subcommand falls back to getPinToken`() {
        val token = pinned(permissions = false)
        val transport = HidCtapTransport(FakeHidDevice(token)).apply { open() }
        val info = Ctap.getInfo(transport)
        assertTrue("the option should be absent", !info.pinUvAuthTokenSupported)
        assertTrue(Ctap.unlock(transport, info, CtapPermission.GET_ASSERTION, Ctap.DEFAULT_APPLICATION, Asker("1234")) != null)
    }

    /** And a token that advertises it and then refuses it is retried on the old one. */
    @Test
    fun `a token that rejects the permissions subcommand is retried on the old one`() {
        val token = pinned(permissions = false)
        val transport = HidCtapTransport(FakeHidDevice(token)).apply { open() }
        // The token's own getInfo says false; claiming otherwise is exactly the
        // case this fallback exists for.
        val lying = Ctap.getInfo(transport).copy(pinUvAuthTokenSupported = true)
        val pin = ClientPin.token(transport, lying, "1234".toByteArray(), CtapPermission.GET_ASSERTION, Ctap.DEFAULT_APPLICATION)
        assertSame(PinProtocol.Two, pin.protocol)
        assertEquals(32, pin.param(ByteArray(32)).size)
    }

    /** The count is the whole point of the retry: it says what the next mistake costs. */
    @Test
    fun `a wrong PIN is asked again with the attempts remaining`() {
        val token = pinned()
        val transport = HidCtapTransport(FakeHidDevice(token)).apply { open() }
        val asked = Asker("9999", "1234")
        val info = Ctap.getInfo(transport)

        assertTrue(Ctap.unlock(transport, info, CtapPermission.GET_ASSERTION, Ctap.DEFAULT_APPLICATION, asked) != null)
        assertEquals(listOf(null, "Wrong PIN — 7 attempts left."), asked.problems)
    }

    @Test
    fun `the last attempt is counted in the singular`() {
        val token = pinned().apply { pinRetries = 2 }
        val transport = HidCtapTransport(FakeHidDevice(token)).apply { open() }
        val asked = Asker("9999", "1234")
        Ctap.unlock(transport, Ctap.getInfo(transport), CtapPermission.GET_ASSERTION, Ctap.DEFAULT_APPLICATION, asked)
        assertEquals("Wrong PIN — 1 attempt left before the key locks.", asked.problems.last())
    }

    /** A token out of attempts is finished, and saying anything softer would be a lie. */
    @Test
    fun `a token whose attempts run out says a reset is the only way back`() {
        val token = pinned().apply { pinRetries = 1 }
        val transport = HidCtapTransport(FakeHidDevice(token)).apply { open() }
        val error = runCatching {
            Ctap.unlock(transport, Ctap.getInfo(transport), CtapPermission.GET_ASSERTION, Ctap.DEFAULT_APPLICATION, Asker("9999"))
        }.exceptionOrNull()
        assertEquals(0x32, (error as CtapStatusException).status)
        assertTrue("$error", error.message?.contains("factory reset") == true)
        assertTrue("a locked key is not worth retrying", !error.recoverable)
    }

    /** Three in a row and the token wants a power cycle, which is a thing a person can do. */
    @Test
    fun `three wrong PINs in a row ask for the key to be unplugged`() {
        val token = pinned()
        val transport = HidCtapTransport(FakeHidDevice(token)).apply { open() }
        val error = runCatching {
            Ctap.unlock(
                transport,
                Ctap.getInfo(transport),
                CtapPermission.GET_ASSERTION,
                Ctap.DEFAULT_APPLICATION,
                Asker("9991", "9992", "9993"),
            )
        }.exceptionOrNull()
        assertEquals(0x34, (error as CtapStatusException).status)
        assertTrue("$error", error.message?.contains("Unplug the security key") == true)
    }

    /** Nobody is made to type a PIN into a token that has none. */
    @Test
    fun `a token without a PIN is never asked for one`() {
        val transport = HidCtapTransport(FakeHidDevice(SoftwareAuthenticator())).apply { open() }
        val asked = Asker()
        assertNull(Ctap.unlock(transport, Ctap.getInfo(transport), CtapPermission.GET_ASSERTION, Ctap.DEFAULT_APPLICATION, asked))
        assertTrue("nothing should have been asked", asked.problems.isEmpty())
    }

    @Test
    fun `giving up on the prompt gives up on the operation`() {
        val transport = HidCtapTransport(FakeHidDevice(pinned())).apply { open() }
        val error = runCatching {
            Ctap.unlock(transport, Ctap.getInfo(transport), CtapPermission.GET_ASSERTION, Ctap.DEFAULT_APPLICATION, Asker())
        }.exceptionOrNull()
        assertEquals("Canceled.", error?.message)
        assertTrue("$error", (error as CtapException).recoverable)
    }

    /**
     * The promise the whole design rests on: what the person typed is gone from
     * memory by the time the token has answered.
     */
    @Test
    fun `the PIN is wiped once the token has been satisfied`() {
        val transport = HidCtapTransport(FakeHidDevice(pinned())).apply { open() }
        val asked = Asker("1234")
        Ctap.unlock(transport, Ctap.getInfo(transport), CtapPermission.GET_ASSERTION, Ctap.DEFAULT_APPLICATION, asked)
        assertArrayEquals(CharArray(4), asked.handed.single())
    }

    /** The proof a request carries is a MAC over its client-data hash, and nothing else. */
    @Test
    fun `pinUvAuthParam is authenticate over the client data hash`() {
        val secret = ByteArray(32) { it.toByte() }
        val hash = ByteArray(32) { (it * 5).toByte() }
        assertArrayEquals(
            PinProtocol.One.authenticate(secret, hash),
            PinUvAuthToken(PinProtocol.One, secret.copyOf()).param(hash),
        )
    }

    // ---- what each refusal says ----------------------------------------------

    /**
     * Every PIN status a token can answer with, and the sentence it becomes.
     *
     * The wording is the feature as much as the protocol is: "0x34" tells nobody
     * to unplug anything, and a token that only a reset will recover must not be
     * described as one somebody can unlock.
     */
    @Test
    fun `every PIN error becomes something a person can act on`() {
        assertEquals("Wrong PIN.", Ctap.statusMessage(0x31))
        assertTrue(Ctap.isRecoverable(0x31))

        assertTrue(Ctap.statusMessage(0x32).contains("factory reset"))
        assertTrue("a locked key is not retryable", !Ctap.isRecoverable(0x32))

        assertTrue(Ctap.statusMessage(0x33).contains("Try again"))
        assertTrue(Ctap.isRecoverable(0x33))

        assertTrue(Ctap.statusMessage(0x34, KeyTransport.USB).contains("Unplug the security key"))
        assertTrue(Ctap.statusMessage(0x34, KeyTransport.NFC).contains("away from the phone"))
        assertTrue("a power cycle is not something retrying fixes", !Ctap.isRecoverable(0x34))

        assertEquals("This security key has no PIN set, so there is nothing to unlock it with.", Ctap.statusMessage(0x35))
        assertTrue(!Ctap.isRecoverable(0x35))

        assertEquals("This security key needs its PIN.", Ctap.statusMessage(0x36))
        assertTrue(Ctap.isRecoverable(0x36))

        assertTrue(Ctap.statusMessage(0x37).contains("longer PIN"))
    }

    /** A status byte has to survive as far as the code that branches on it. */
    @Test
    fun `a refusal carries its status as well as its sentence`() {
        val failure = Ctap.failure(0x34, KeyTransport.NFC)
        assertEquals(0x34, failure.status)
        assertEquals(Ctap.statusMessage(0x34, KeyTransport.NFC), failure.message)
    }

    // ---- helpers -------------------------------------------------------------

    /** A [PinAsker] that answers with the PINs it was given, and remembers what it was told. */
    private class Asker(private vararg val pins: String) : PinAsker {
        val problems = mutableListOf<String?>()
        val handed = mutableListOf<CharArray>()
        private var at = 0

        override fun ask(tokenName: String?, problem: String?): CharArray? {
            problems += problem
            if (at >= pins.size) return null
            return pins[at++].toCharArray().also { handed += it }
        }
    }

    private fun pinned(
        pin: String = "1234",
        protocols: List<Long> = listOf(2L, 1L),
        permissions: Boolean = true,
    ) = SoftwareAuthenticator().apply {
        this.pin = pin
        pinProtocols = protocols
        permissionsSubcommand = permissions
    }

    private fun p256(): KeyPair = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair()

    private fun coseOf(pair: KeyPair): Map<Any, Any?> {
        val point = (pair.public as ECPublicKey).w
        return mapOf(
            1L to 2L,
            3L to -25L,
            -1L to 1L,
            -2L to fixed(point.affineX),
            -3L to fixed(point.affineY),
        )
    }

    private fun pointOf(cose: Map<Any, Any?>): ECPublicKey {
        val parameters = AlgorithmParameters.getInstance("EC")
            .apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
        val point = ECPoint(
            BigInteger(1, cose[-2L] as ByteArray),
            BigInteger(1, cose[-3L] as ByteArray),
        )
        return java.security.KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, parameters)) as ECPublicKey
    }

    private fun fixed(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return when {
            bytes.size == 32 -> bytes
            bytes.size > 32 -> bytes.copyOfRange(bytes.size - 32, bytes.size)
            else -> ByteArray(32 - bytes.size) + bytes
        }
    }

    /** HKDF-SHA-256 in full, from RFC 5869 — the reference the app's one-block form is measured against. */
    private fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(salt, ikm)
        val out = java.io.ByteArrayOutputStream()
        var previous = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            previous = hmac(prk, previous + info + byteArrayOf(counter.toByte()))
            out.write(previous)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    private fun hmac(key: ByteArray, message: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(message)
    }

    private fun aesCbc(mode: Int, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/NoPadding").run {
            init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(data)
        }

    private fun hex(text: String): ByteArray =
        ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
