package dev.flint.term.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.math.BigInteger
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * The one thing the Kotlin side has to get right on its own: the public key it
 * takes home and the signature it relays have to be about the same key.
 *
 * Everything downstream — the `authorized_keys` line, the SSH signature blob —
 * is assembled in Rust and proven against a real sshd by
 * `crates/ssh-core/tests/security_key.rs`. What that test cannot see is whether
 * this side pulled the right bytes out of a COSE key and passed the assertion
 * through unaltered, which is what these do.
 */
class SecurityKeySigningTest {
    private fun transport(authenticator: SoftwareAuthenticator = SoftwareAuthenticator()) =
        HidCtapTransport(FakeHidDevice(authenticator)).apply { open() }

    @Test
    fun `an ECDSA assertion verifies under the public key enrollment returned`() {
        check(SecurityKeyAlgorithm.EcdsaP256)
    }

    @Test
    fun `an Ed25519 assertion verifies under the public key enrollment returned`() {
        assumeTrue(
            "this JVM has no Ed25519",
            SoftwareAuthenticator.supportedAlgorithms().contains(SecurityKeyAlgorithm.Ed25519),
        )
        check(SecurityKeyAlgorithm.Ed25519)
    }

    private fun check(algorithm: SecurityKeyAlgorithm) {
        val transport = transport()
        val credential = Ctap.makeCredential(transport, Ctap.DEFAULT_APPLICATION, algorithm, "chain")
        assertEquals(algorithm, credential.algorithm)

        // Exactly what ssh-core does before it asks the token for anything.
        val message = "the bytes a server asked to have signed".toByteArray()
        val clientDataHash = MessageDigest.getInstance("SHA-256").digest(message)
        val assertion = Ctap.getAssertion(transport, credential.application, credential.credentialId, clientDataHash)

        // And exactly what a verifier does with the answer.
        val signed = assertion.authenticatorData + clientDataHash
        val verifier = Signature.getInstance(if (algorithm == SecurityKeyAlgorithm.Ed25519) "Ed25519" else "SHA256withECDSA")
        verifier.initVerify(publicKeyOf(algorithm, credential.publicKey))
        verifier.update(signed)
        assertTrue("the assertion should verify under the enrolled key", verifier.verify(assertion.signature))

        // The rpIdHash the server recomputes, and the user-presence flag without
        // which OpenSSH refuses the signature whatever else is right.
        val expected = MessageDigest.getInstance("SHA-256").digest(Ctap.DEFAULT_APPLICATION.toByteArray())
        assertEquals(expected.toList(), assertion.authenticatorData.copyOf(32).toList())
        assertEquals(1, assertion.authenticatorData[32].toInt() and 0x01)
    }

    /** A token that signs unattended produces a signature no server will take,
     *  and the flag is the only place that shows. */
    @Test
    fun `an untouched token leaves the user-presence flag clear`() {
        val authenticator = SoftwareAuthenticator().apply { userPresent = false }
        val transport = transport(authenticator)
        val credential = Ctap.makeCredential(transport, Ctap.DEFAULT_APPLICATION, SecurityKeyAlgorithm.EcdsaP256, "chain")
        val assertion = Ctap.getAssertion(transport, credential.application, credential.credentialId, ByteArray(32))
        assertEquals(0, assertion.authenticatorData[32].toInt() and 0x01)
    }

    /** A token with no Ed25519 is the common case, and settling for ECDSA is
     *  better than refusing to enroll. */
    @Test
    fun `a token that only does ECDSA reports as much`() {
        val onlyEcdsa = SoftwareAuthenticator(algorithms = listOf(SecurityKeyAlgorithm.EcdsaP256))
        val info = Ctap.getInfo(transport(onlyEcdsa))
        assertTrue(info.supports(SecurityKeyAlgorithm.EcdsaP256))
        assertTrue(!info.supports(SecurityKeyAlgorithm.Ed25519))
        assertEquals(
            "This key does not support Ed25519, using ECDSA.",
            SecurityKeyIdentity.substitution(SecurityKeyAlgorithm.Ed25519, SecurityKeyAlgorithm.EcdsaP256),
        )
    }

    /** A CTAP 2.0 token may leave the algorithm list out entirely; treating that
     *  as "none" would refuse keys that work. */
    @Test
    fun `a token that lists no algorithms is assumed to do ECDSA`() {
        val silent = CtapInfo(versions = listOf("FIDO_2_0"), algorithms = emptyList(), pinSet = false, aaguid = null)
        assertTrue(silent.supports(SecurityKeyAlgorithm.EcdsaP256))
        assertTrue(!silent.supports(SecurityKeyAlgorithm.Ed25519))
    }

    /** The report descriptor is the only thing that tells a token from a
     *  keyboard, so matching it is worth pinning. */
    @Test
    fun `a FIDO report descriptor is recognized and a keyboard is not`() {
        val fido = byteArrayOf(0x06, 0xD0.toByte(), 0xF1.toByte(), 0x09, 0x01, 0xA1.toByte(), 0x01)
        val keyboard = byteArrayOf(0x05, 0x01, 0x09, 0x06, 0xA1.toByte(), 0x01)
        assertTrue(UsbCtap.isFidoDescriptor(fido))
        assertTrue(!UsbCtap.isFidoDescriptor(keyboard))
        assertTrue(!UsbCtap.isFidoDescriptor(ByteArray(0)))
    }

    private fun publicKeyOf(algorithm: SecurityKeyAlgorithm, bytes: ByteArray): PublicKey = when (algorithm) {
        // SubjectPublicKeyInfo for Ed25519 is a fixed 12-byte prefix and then
        // the key, which is the only way the JDK will take one.
        SecurityKeyAlgorithm.Ed25519 -> KeyFactory.getInstance("Ed25519").generatePublic(
            X509EncodedKeySpec(
                byteArrayOf(0x30, 0x2A, 0x30, 0x05, 0x06, 0x03, 0x2B, 0x65, 0x70, 0x03, 0x21, 0x00) + bytes,
            ),
        )
        SecurityKeyAlgorithm.EcdsaP256 -> {
            val parameters = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            val spec = parameters.getParameterSpec(ECParameterSpec::class.java)
            val point = ECPoint(
                BigInteger(1, bytes.copyOfRange(1, 33)),
                BigInteger(1, bytes.copyOfRange(33, 65)),
            )
            KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, spec))
        }
    }
}
