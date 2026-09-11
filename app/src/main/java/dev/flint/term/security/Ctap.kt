package dev.flint.term.security

import java.io.Closeable

/**
 * CTAP2, the language a FIDO2 security key speaks, over whatever can carry it.
 *
 * Android's Credential Manager is deliberately not used: it wraps the challenge
 * in a clientDataJSON of its own making, and SSH needs the raw challenge hash
 * signed. So the app talks to the token itself, and everything transport-shaped
 * — USB reports, NFC APDUs — lives behind [CtapTransport].
 */
interface CtapTransport : Closeable {
    /** "USB" or "NFC", for messages that have to tell somebody where to look. */
    val kind: KeyTransport

    /** What the token calls itself, when the transport was told — USB says so, NFC does not. */
    val name: String? get() = null

    /**
     * One command: the CTAP2 command byte and its CBOR, back to the response
     * CBOR with the status byte already checked. Blocks for as long as the
     * token takes, which for anything needing a touch is until somebody touches
     * it.
     */
    fun exchange(command: Int, payload: ByteArray): ByteArray
}

/** How a token is reached. Both end at the same CTAP2 exchange. */
enum class KeyTransport(val label: String) {
    USB("USB"),
    NFC("NFC"),
}

/**
 * Anything that went wrong between here and the token, in words for the person
 * holding the phone.
 *
 * [recoverable] marks the failures worth offering to try again: a key that was
 * pulled out or a touch nobody gave, as opposed to a token that cannot do what
 * was asked at all.
 */
open class CtapException(message: String, val recoverable: Boolean = false) : Exception(message)

/**
 * The same, for a refusal that came back as a CTAP2 status byte.
 *
 * The sentence is still the whole of what a person needs, but the client-PIN
 * flow has to tell "wrong PIN, ask again" from "this token is finished", and a
 * message is a poor thing to branch on.
 */
class CtapStatusException(val status: Int, message: String, recoverable: Boolean) : CtapException(message, recoverable)

/** CTAP2 commands. Only what an SSH key needs is here. */
object CtapCommand {
    const val MAKE_CREDENTIAL = 0x01
    const val GET_ASSERTION = 0x02
    const val GET_INFO = 0x04
    const val CLIENT_PIN = ClientPin.COMMAND
}

/** What a token said about itself. */
data class CtapInfo(
    val versions: List<String>,
    /** COSE algorithm ids the token will make a credential with, best first. */
    val algorithms: List<Long>,
    /** A PIN has been set and the token will insist on it for this credential. */
    val pinSet: Boolean,
    val aaguid: ByteArray?,
    /** PIN/UV auth protocols the token offers, in its own order of preference. */
    val pinProtocols: List<Long> = emptyList(),
    /**
     * The token understands `getPinUvAuthTokenUsingPinWithPermissions`.
     *
     * Which is worth knowing before asking: the permissions subcommand scopes a
     * PIN token to one relying party and one operation, and a CTAP 2.0 token
     * that has never heard of it answers with an error rather than a token.
     */
    val pinUvAuthTokenSupported: Boolean = false,
) {
    /**
     * Which of the two SSH key types this token can actually make.
     *
     * A token that lists nothing is not saying "none": `algorithms` is optional
     * in CTAP 2.0, and a 2.0 token that omits it does ES256. Treating silence as
     * ES256 is what every other client does, and the alternative is refusing
     * perfectly good keys.
     */
    fun supports(algorithm: SecurityKeyAlgorithm): Boolean = when {
        algorithms.isEmpty() -> algorithm == SecurityKeyAlgorithm.EcdsaP256
        else -> algorithms.contains(algorithm.coseId)
    }

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/** The two signature types OpenSSH has an `sk-` key type for. */
enum class SecurityKeyAlgorithm(val coseId: Long, val keyType: String, val label: String) {
    Ed25519(-8L, "sk-ssh-ed25519@openssh.com", "Ed25519"),
    EcdsaP256(-7L, "sk-ecdsa-sha2-nistp256@openssh.com", "ECDSA"),
}

/** What enrollment got back from the token, ready to be kept. */
data class EnrolledCredential(
    val algorithm: SecurityKeyAlgorithm,
    /** Ed25519: 32 bytes. ECDSA: the point as 0x04 ‖ X ‖ Y. */
    val publicKey: ByteArray,
    val application: String,
    val credentialId: ByteArray,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/** One assertion, exactly as the token answered it. */
data class CtapAssertion(val authenticatorData: ByteArray, val signature: ByteArray) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * The three CTAP2 requests an SSH key needs, and the parsing of their answers.
 *
 * Nothing here keeps state, so the same code serves a token on USB, a token held
 * against the phone, and the software one the tests use.
 */
object Ctap {
    /** OpenSSH's default `application`, which is the CTAP2 relying party id. */
    const val DEFAULT_APPLICATION = "ssh:"

    private const val FLAG_ATTESTED_CREDENTIAL_DATA = 0x40

    fun getInfo(transport: CtapTransport): CtapInfo {
        val answer = Cbor.decode(transport.exchange(CtapCommand.GET_INFO, ByteArray(0)))
        val versions = Cbor.optional<List<*>>(answer, 1).orEmpty().filterIsInstance<String>()
        val options = Cbor.optional<Map<*, *>>(answer, 4).orEmpty()
        val algorithms = Cbor.optional<List<*>>(answer, 10).orEmpty()
            .mapNotNull { Cbor.optional<Long>(it, "alg") }
        return CtapInfo(
            versions = versions,
            algorithms = algorithms,
            pinSet = options[Cbor.normalize("clientPin")] == true,
            aaguid = Cbor.optional<ByteArray>(answer, 3),
            pinProtocols = Cbor.optional<List<*>>(answer, 6).orEmpty().filterIsInstance<Long>(),
            pinUvAuthTokenSupported = options[Cbor.normalize("pinUvAuthToken")] == true,
        )
    }

    /**
     * Make a credential the token will keep, and take home its public half.
     *
     * `rk` is left off on purpose: a discoverable credential takes one of the
     * token's handful of slots for ever, and SSH does not need one — the app
     * remembers the credential id and hands it back on every login.
     */
    fun makeCredential(
        transport: CtapTransport,
        application: String,
        algorithm: SecurityKeyAlgorithm,
        userName: String,
        /** From [unlock] when the token has a PIN, and null when it has none. */
        pin: PinUvAuthToken? = null,
    ): EnrolledCredential {
        // A token signs its attestation over this, and nothing here reads the
        // attestation, so what it is does not matter — only that it is 32 bytes
        // and not the same every time. It is also what the PIN proof is over,
        // so it is made once rather than inline.
        val clientDataHash = randomBytes(32)
        val request = linkedMapOf<Any, Any?>(
            1 to clientDataHash,
            2 to mapOf("id" to application, "name" to application),
            3 to mapOf("id" to randomBytes(32), "name" to userName, "displayName" to userName),
            4 to listOf(mapOf("alg" to algorithm.coseId, "type" to "public-key")),
            7 to mapOf("rk" to false),
        )
        if (pin != null) {
            request[8] = pin.param(clientDataHash)
            request[9] = pin.protocol.version
        }
        val answer = Cbor.decode(transport.exchange(CtapCommand.MAKE_CREDENTIAL, Cbor.encode(request)))
        val authenticatorData = Cbor.field<ByteArray>(answer, 2, "the new credential")
        return parseAttestedCredential(authenticatorData, algorithm, application)
    }

    fun getAssertion(
        transport: CtapTransport,
        application: String,
        credentialId: ByteArray,
        clientDataHash: ByteArray,
        pin: PinUvAuthToken? = null,
    ): CtapAssertion {
        val request = linkedMapOf<Any, Any?>(
            1 to application,
            2 to clientDataHash,
            3 to listOf(mapOf("type" to "public-key", "id" to credentialId)),
            // Asking for user presence explicitly is what makes the token light
            // up and wait; without it a token may sign unattended, and OpenSSH
            // will not accept a signature whose "user present" flag is clear.
            5 to mapOf("up" to true),
        )
        if (pin != null) {
            // The "uv" option stays off on purpose: a request carrying a
            // pinUvAuthParam has already verified the user, and setting both is
            // what the spec tells platforms not to do.
            request[6] = pin.param(clientDataHash)
            request[7] = pin.protocol.version
        }
        val answer = Cbor.decode(transport.exchange(CtapCommand.GET_ASSERTION, Cbor.encode(request)))
        return CtapAssertion(
            authenticatorData = Cbor.field(answer, 2, "the assertion"),
            signature = Cbor.field(answer, 3, "the assertion"),
        )
    }

    /**
     * Dig the new public key out of the authenticator data.
     *
     * Its layout is fixed-width until the credential id, which carries its own
     * two-byte length, and the COSE key runs to the end — so the whole thing can
     * be walked without a CBOR parser until the last field.
     */
    internal fun parseAttestedCredential(
        authenticatorData: ByteArray,
        algorithm: SecurityKeyAlgorithm,
        application: String,
    ): EnrolledCredential {
        if (authenticatorData.size < 38) {
            throw CtapException("The security key returned an answer too short to hold a new key.")
        }
        if (authenticatorData[32].toInt() and FLAG_ATTESTED_CREDENTIAL_DATA == 0) {
            throw CtapException("The security key did not return a new key. Try again, or try another key.")
        }
        var at = 37 + 16 // rpIdHash, flags, counter, then the token's AAGUID.
        if (authenticatorData.size < at + 2) throw CtapException("The security key returned a truncated credential.")
        val idLength = ((authenticatorData[at].toInt() and 0xFF) shl 8) or (authenticatorData[at + 1].toInt() and 0xFF)
        at += 2
        if (authenticatorData.size < at + idLength) throw CtapException("The security key returned a truncated credential.")
        val credentialId = authenticatorData.copyOfRange(at, at + idLength)
        at += idLength
        val cose = Cbor.decode(authenticatorData.copyOfRange(at, authenticatorData.size))
        return EnrolledCredential(
            algorithm = algorithm,
            publicKey = coseToPublicKey(cose, algorithm),
            application = application,
            credentialId = credentialId,
        )
    }

    /**
     * A COSE key as SSH wants the same key: 32 raw bytes for Ed25519, an
     * uncompressed point for P-256.
     *
     * The coordinates are padded back to the field size because a token is
     * allowed to send them short — a leading zero byte is a legal thing to drop
     * and an illegal thing to hand to OpenSSH.
     */
    internal fun coseToPublicKey(cose: Any?, algorithm: SecurityKeyAlgorithm): ByteArray {
        val declared = Cbor.optional<Long>(cose, 3)
        if (declared != null && declared != algorithm.coseId) {
            throw CtapException("The security key made a ${describeCose(declared)} key when it was asked for ${algorithm.label}.")
        }
        val x = Cbor.field<ByteArray>(cose, -2, "the new key")
        return when (algorithm) {
            SecurityKeyAlgorithm.Ed25519 -> pad(x, 32)
            SecurityKeyAlgorithm.EcdsaP256 -> {
                val y = Cbor.field<ByteArray>(cose, -3, "the new key")
                byteArrayOf(0x04) + pad(x, 32) + pad(y, 32)
            }
        }
    }

    private fun describeCose(alg: Long): String = when (alg) {
        -8L -> "Ed25519"
        -7L -> "ECDSA P-256"
        else -> "COSE $alg"
    }

    private fun pad(value: ByteArray, size: Int): ByteArray = when {
        value.size == size -> value
        value.size < size -> ByteArray(size - value.size) + value
        else -> throw CtapException("The security key returned a $size-byte coordinate as ${value.size} bytes.")
    }

    private fun randomBytes(n: Int) = ByteArray(n).also { java.security.SecureRandom().nextBytes(it) }

    /**
     * What a CTAP2 status byte means, said so somebody can do something about it.
     *
     * The codes worth naming are the ones a person causes or can fix; the rest
     * are reported by number, which is at least searchable.
     */
    fun statusMessage(status: Int, transport: KeyTransport? = null): String = when (status) {
        0x27 -> "The security key refused this. The touch was declined."
        0x2D -> "The security key request was canceled."
        0x2E -> "This security key does not hold that credential. It may be a different key from the one this identity was made with."
        0x2F, 0x3A -> "Nobody touched the security key in time."
        0x30 -> "The security key would not allow this operation."
        0x26 -> "This security key does not support that signature type."
        0x31 -> "Wrong PIN."
        0x33 -> "The security key would not accept this app's PIN proof. Try again."
        // A factory reset is the only way out of this one, and it takes every
        // credential on the token with it. Saying "unlock it on a computer"
        // would be a promise nothing can keep.
        0x32 -> "This security key is locked: too many wrong PINs. Only a factory reset will unlock it, and that erases every credential on it."
        0x34 -> when (transport) {
            KeyTransport.NFC -> "Too many wrong PINs in a row. Take the key away from the phone, then hold it back and try again."
            else -> "Too many wrong PINs in a row. Unplug the security key, plug it back in, and try again."
        }
        0x35 -> "This security key has no PIN set, so there is nothing to unlock it with."
        0x36 -> "This security key needs its PIN."
        0x37 -> "This security key wants a longer PIN than it has. Change its PIN on a computer, then try again."
        0x28 -> "The security key is full. Delete a credential on it and try again."
        0x19 -> "This security key already holds a credential for this identity."
        0x11, 0x12, 0x14 -> "The security key did not understand the request."
        else -> "The security key answered with error 0x%02x.".format(status)
    }

    /** Statuses worth an "try again" rather than an "this will not work". */
    fun isRecoverable(status: Int): Boolean = status in setOf(0x27, 0x2D, 0x2F, 0x3A, 0x31, 0x33, 0x36)

    /** The one place a status byte becomes the exception the app carries around. */
    fun failure(status: Int, transport: KeyTransport? = null): CtapStatusException =
        CtapStatusException(status, statusMessage(status, transport), isRecoverable(status))

    /**
     * Get a `pinUvAuthToken` for [permissions], asking the person for the PIN as
     * many times as the token will bear, or null when the token has no PIN.
     *
     * The retry loop is the feature: a token counts down, and somebody who has
     * mistyped once needs to be told how many tries are left before the next one
     * costs them every credential on the key. What the token itself refuses to
     * count — a locked key, one that wants a power cycle — is passed straight
     * out, because there is nothing to try again with.
     */
    fun unlock(
        transport: CtapTransport,
        info: CtapInfo,
        permissions: Int,
        rpId: String,
        ask: PinAsker,
    ): PinUvAuthToken? {
        if (!info.pinSet) return null
        var problem: String? = null
        while (true) {
            val typed = ask.ask(transport.name, problem) ?: throw CtapException("Canceled.", recoverable = true)
            val pin = ClientPin.encodePin(typed)
            try {
                if (pin.size > ClientPin.MAX_PIN_BYTES) {
                    problem = "That PIN is too long for a security key."
                    continue
                }
                return ClientPin.token(transport, info, pin, permissions, rpId)
            } catch (e: CtapStatusException) {
                // 0x31 is the wrong PIN and 0x33 is the token disliking the proof
                // itself, which in practice a stale key agreement causes; both are
                // worth one more go, and both cost an attempt.
                if (e.status != 0x31 && e.status != 0x33) throw e
                problem = wrongPin(e, transport)
            } finally {
                typed.fill('\u0000')
                pin.fill(0)
            }
        }
    }

    /** "Wrong PIN. 2 attempts left", when the token will say how many. */
    private fun wrongPin(failure: CtapStatusException, transport: CtapTransport): String {
        val left = runCatching { ClientPin.retries(transport) }.getOrNull()
        return when {
            left == null -> failure.message ?: "Wrong PIN."
            left <= 0 -> "Wrong PIN. This security key is now locked."
            left == 1 -> "Wrong PIN. 1 attempt left before the key locks."
            else -> "Wrong PIN. $left attempts left."
        }
    }
}

/**
 * Whoever can put the question "what is the PIN?" in front of a person.
 *
 * It answers with a [CharArray] and never a String, and the caller wipes it as
 * soon as the token has been satisfied: see [ClientPin.encodePin]. Null is
 * somebody deciding not to.
 */
fun interface PinAsker {
    /**
     * [tokenName] is the key's own name where the transport knows it, and
     * [problem] is what went wrong with the last attempt, if there was one.
     */
    fun ask(tokenName: String?, problem: String?): CharArray?
}
