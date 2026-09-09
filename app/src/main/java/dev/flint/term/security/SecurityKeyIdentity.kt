package dev.flint.term.security

import android.content.Context
import android.util.Base64
import dev.flint.term.core.CoreException
import dev.flint.term.core.SecurityKeyAssertion
import dev.flint.term.core.SecurityKeyCredential
import dev.flint.term.core.SecurityKeyToken
import dev.flint.term.core.securityKeyFingerprint
import dev.flint.term.core.securityKeyPublicKey
import dev.flint.term.data.Identity

/**
 * A stored identity whose private half is on a security key.
 *
 * Only the credential travels: the four fields the token needs to recognize what
 * it is being asked to sign for, kept as text so they survive the store's JSON.
 * Assembling them into an SSH key or a signature is the core's job, so this file
 * is the translation and nothing else.
 */
object SecurityKeyIdentity {
    /** What enrollment leaves on the identity, and what signing reads back. */
    fun credentialOf(identity: Identity): SecurityKeyCredential = SecurityKeyCredential(
        algorithm = algorithmOf(identity).core,
        publicKey = decode(identity.skPublicKey),
        application = identity.skApplication.ifBlank { Ctap.DEFAULT_APPLICATION },
        credentialId = decode(identity.skCredentialId),
    )

    fun algorithmOf(identity: Identity): SecurityKeyAlgorithm =
        SecurityKeyAlgorithm.entries.firstOrNull { it.name == identity.skAlgorithm } ?: SecurityKeyAlgorithm.EcdsaP256

    fun transportOf(identity: Identity): KeyTransport =
        KeyTransport.entries.firstOrNull { it.name == identity.skTransport } ?: KeyTransport.USB

    /**
     * Enroll a new credential and turn it into an identity ready to store.
     *
     * The public key and fingerprint are worked out here, while the person is
     * still on the enrollment screen: a token that answered with something
     * unusable should be a message they can act on now, not a connection that
     * fails next week.
     */
    fun enroll(
        context: Context,
        transport: KeyTransport,
        algorithm: SecurityKeyAlgorithm,
        name: String,
        application: String = Ctap.DEFAULT_APPLICATION,
    ): Identity {
        val prompt = SecurityKeyPrompt("Add a security key", transport)
        val enrolled = SecurityKeys.withToken(context, transport, prompt) { token ->
            val info = runCatching { Ctap.getInfo(token) }.getOrNull()
            // A key that cannot do Ed25519 is common and not a failure; saying
            // what happened beats making the person guess why the key type on
            // the row is not the one they picked.
            val usable = if (info == null || info.supports(algorithm)) algorithm else SecurityKeyAlgorithm.EcdsaP256
            // Asked for before the touch, because a token with a PIN will refuse
            // the credential otherwise and the person would have touched it for
            // nothing. A token without one never sees this.
            val pin = info?.let {
                Ctap.unlock(token, it, CtapPermission.MAKE_CREDENTIAL, application, SecurityKeyGate.asker(prompt))
            }
            try {
                Ctap.makeCredential(token, application, usable, name, pin)
            } finally {
                pin?.clear()
            }
        }
        val credential = SecurityKeyCredential(
            algorithm = enrolled.algorithm.core,
            publicKey = enrolled.publicKey,
            application = enrolled.application,
            credentialId = enrolled.credentialId,
        )
        return Identity(
            name = name,
            publicKey = securityKeyPublicKey(credential, name.ifBlank { "security-key" }),
            fingerprint = securityKeyFingerprint(credential),
            securityKey = true,
            skAlgorithm = enrolled.algorithm.name,
            skApplication = enrolled.application,
            skPublicKey = encode(enrolled.publicKey),
            skCredentialId = encode(enrolled.credentialId),
            skTransport = transport.name,
        )
    }

    /**
     * What was actually enrolled, when it is not what was asked for.
     *
     * Worth a sentence on screen: a token with no Ed25519 quietly producing an
     * ECDSA key looks like the app ignoring the choice.
     */
    fun substitution(asked: SecurityKeyAlgorithm, got: SecurityKeyAlgorithm): String? =
        if (asked == got) null else "This key does not support ${asked.label}, using ${got.label}."

    /** The token behind [identity], reached again for each signature. */
    fun token(context: Context, identity: Identity): SecurityKeyToken = object : SecurityKeyToken {
        override fun assert(
            application: String,
            credentialId: ByteArray,
            clientDataHash: ByteArray,
        ): SecurityKeyAssertion {
            val transport = transportOf(identity)
            val prompt = SecurityKeyPrompt("Sign in with ${identity.name}", transport)
            return try {
                SecurityKeys.withToken(context, transport, prompt) { token ->
                    // A token whose PIN was set after enrollment would otherwise
                    // refuse the assertion with a code; asking it first is what
                    // turns that into a prompt.
                    val info = runCatching { Ctap.getInfo(token) }.getOrNull()
                    val pin = info?.let {
                        Ctap.unlock(token, it, CtapPermission.GET_ASSERTION, application, SecurityKeyGate.asker(prompt))
                    }
                    val assertion = try {
                        Ctap.getAssertion(token, application, credentialId, clientDataHash, pin)
                    } finally {
                        pin?.clear()
                    }
                    SecurityKeyAssertion(assertion.authenticatorData, assertion.signature)
                }
            } catch (e: CtapException) {
                // Already a sentence for whoever is holding the phone; wrapping
                // it would only bury it.
                throw CoreException.Other(e.message ?: "the security key did not answer")
            } catch (e: Exception) {
                throw CoreException.Other("the security key could not be used: ${e.message}")
            }
        }

        override fun toString() = "SecurityKeyToken(${identity.name})"
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(text: String): ByteArray =
        runCatching { Base64.decode(text, Base64.NO_WRAP) }.getOrDefault(ByteArray(0))
}

/** The core's spelling of the same two algorithms. */
val SecurityKeyAlgorithm.core: dev.flint.term.core.SecurityKeyAlgorithm
    get() = when (this) {
        SecurityKeyAlgorithm.Ed25519 -> dev.flint.term.core.SecurityKeyAlgorithm.ED25519
        SecurityKeyAlgorithm.EcdsaP256 -> dev.flint.term.core.SecurityKeyAlgorithm.ECDSA_P256
    }
