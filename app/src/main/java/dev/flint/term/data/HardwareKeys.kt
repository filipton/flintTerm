package dev.flint.term.data

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import dev.flint.term.core.CoreException
import dev.flint.term.core.KeystoreSigner
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * SSH keys that live in the phone's keystore and cannot be read out.
 *
 * The platform will sign with such a key but never hand it over — not to this
 * app, not to a backup, not to a sync. That is the point: a key here cannot
 * leak from a stolen phone or a copied database, at the cost of being tied to
 * this device, so each device gets its own and every server it uses authorises
 * all of them.
 *
 * The keystore does ECDSA on P-256, which is exactly what SSH calls
 * `ecdsa-sha2-nistp256`, so no conversion of the algorithm is needed — only of
 * the wire formats, which is what this file mostly is.
 */
object HardwareKeys {
    private const val TAG = "HardwareKeys"
    private const val STORE = "AndroidKeyStore"
    // Also from the old name, and also unrenameable: it addresses keys that
    // already exist in the keystore and cannot be exported and re-imported.
    private const val PREFIX = "androidterm-ssh-"
    const val SSH_TYPE = "ecdsa-sha2-nistp256"

    /** Where the key material ended up, which is worth being honest about. */
    enum class Backing { StrongBox, TrustedEnvironment, Software }

    data class Info(
        val alias: String,
        val backing: Backing,
        val onlyWhileUnlocked: Boolean = false,
        /** The keystore will not sign with this key until the person is there. */
        val userAuthRequired: Boolean = false,
    )

    /** One rung of the ladder: what to ask the keystore for this time. */
    data class Attempt(val strongBox: Boolean, val whileUnlocked: Boolean, val userAuth: Boolean)

    fun aliasFor(id: String) = PREFIX + id

    private fun store(): KeyStore = KeyStore.getInstance(STORE).apply { load(null) }

    /**
     * What to ask for, best first, giving way one property at a time.
     *
     * The keystore refuses to create a key it cannot honor rather than creating
     * a weaker one, so every property that a given phone might not have is
     * something to fall back from: StrongBox exists on few devices, and neither
     * "only while unlocked" nor "ask every time" can be granted without a screen
     * lock. Asking for a fingerprint is the user's own decision, so it is the
     * last thing given up, and what actually happened is reported back.
     */
    internal fun ladder(sdk: Int, requireAuth: Boolean): List<Attempt> {
        val properties = listOf(true to true, true to false, false to true, false to false)
        val auths = if (requireAuth) listOf(true, false) else listOf(false)
        return auths.flatMap { userAuth ->
            properties.mapNotNull { (strongBox, whileUnlocked) ->
                // Both of these arrived in Android 9; asking for them earlier
                // would only throw.
                if (sdk < Build.VERSION_CODES.P && (strongBox || whileUnlocked)) null
                else Attempt(strongBox, whileUnlocked, userAuth)
            }
        }
    }

    /**
     * Create a key for [id]. Prefers StrongBox — a separate security chip — and
     * falls back to the ordinary trusted environment, which is still far better
     * than a file this app can read.
     *
     * With [requireAuth] the key is bound to the person: a timeout of zero means
     * *this* signature needs a fingerprint, face or the screen lock, every time,
     * enforced by the platform rather than asked for by this app. A device with
     * nothing to prove who is holding it cannot have such a key at all, and gets
     * an ordinary one — [Info.userAuthRequired] says which it was.
     */
    fun generate(id: String, requireAuth: Boolean = false): Info {
        val alias = aliasFor(id)
        var last: Exception? = null
        for ((strongBox, whileUnlocked, userAuth) in ladder(Build.VERSION.SDK_INT, requireAuth)) {
            try {
                val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .apply {
                        if (strongBox) setIsStrongBoxBacked(true)
                        if (whileUnlocked) setUnlockedDeviceRequired(true)
                        if (userAuth) requireUserPresence()
                    }
                    .build()
                val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, STORE)
                generator.initialize(spec)
                generator.generateKeyPair()
                Log.i(TAG, "keystore key created (strongbox=$strongBox, whileUnlocked=$whileUnlocked, userAuth=$userAuth)")
                return Info(alias, backingOf(alias), whileUnlocked, userAuth)
            } catch (e: Exception) {
                Log.i(TAG, "keystore key (strongbox=$strongBox, whileUnlocked=$whileUnlocked, userAuth=$userAuth) not created: ${e.message}")
                runCatching { store().deleteEntry(alias) }
                last = e
            }
        }
        throw last ?: IllegalStateException("the keystore would not create a key")
    }

    /**
     * Bind the key to the person for each single use.
     *
     * A validity of zero seconds — `-1` before Android 11 said the same thing —
     * is what makes it per-signature rather than a session that stays unlocked
     * for a while. From Android 11 the screen lock counts as well as a
     * fingerprint, which matters on a phone whose owner has no biometrics
     * enrolled.
     */
    private fun KeyGenParameterSpec.Builder.requireUserPresence() {
        setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION")
            setUserAuthenticationValidityDurationSeconds(-1)
        }
    }

    fun delete(id: String) {
        runCatching { store().deleteEntry(aliasFor(id)) }
    }

    fun exists(id: String): Boolean = runCatching { store().containsAlias(aliasFor(id)) }.getOrDefault(false)

    private fun backingOf(alias: String): Backing {
        val key = store().getKey(alias, null) as? PrivateKey ?: return Backing.Software
        val info = runCatching {
            KeyFactory.getInstance(key.algorithm, STORE).getKeySpec(key, KeyInfo::class.java)
        }.getOrNull() ?: return Backing.Software
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> Backing.StrongBox
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> Backing.TrustedEnvironment
                else -> Backing.Software
            }
            @Suppress("DEPRECATION")
            info.isInsideSecureHardware -> Backing.TrustedEnvironment
            else -> Backing.Software
        }
    }

    fun backing(id: String): Backing = backingOf(aliasFor(id))

    /**
     * Whether the platform will insist on the person before signing with this
     * key.
     *
     * Asked of the keystore rather than of our own records: the answer was fixed
     * when the key was created and cannot be changed afterwards, so the keystore
     * is the only place that cannot be out of date.
     */
    fun requiresUserAuth(id: String): Boolean {
        val key = runCatching { store().getKey(aliasFor(id), null) as? PrivateKey }.getOrNull() ?: return false
        val info = runCatching {
            KeyFactory.getInstance(key.algorithm, STORE).getKeySpec(key, KeyInfo::class.java)
        }.getOrNull() ?: return false
        return info.isUserAuthenticationRequired
    }

    /** The public key as OpenSSH writes it: "ecdsa-sha2-nistp256 AAAA… comment". */
    fun openSshPublicKey(id: String, comment: String): String {
        val blob = publicKeyBlob(id)
        return "$SSH_TYPE ${Base64.encodeToString(blob, Base64.NO_WRAP)} $comment".trim()
    }

    fun fingerprint(id: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBlob(id))
        return "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP).trimEnd('=')
    }

    /**
     * The SSH public key blob: the type name, the curve name, and the point in
     * the uncompressed form (0x04 ‖ X ‖ Y), each length-prefixed.
     */
    private fun publicKeyBlob(id: String): ByteArray {
        val cert = store().getCertificate(aliasFor(id)) ?: throw IllegalStateException("no keystore key for this identity")
        val key = cert.publicKey as? ECPublicKey ?: throw IllegalStateException("keystore key is not an EC key")
        val size = 32 // P-256 coordinates, left-padded to the field size.
        val point = ByteArrayOutputStream().apply {
            write(0x04)
            write(fixed(key.w.affineX.toByteArray(), size))
            write(fixed(key.w.affineY.toByteArray(), size))
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            writeString(SSH_TYPE.toByteArray())
            writeString("nistp256".toByteArray())
            writeString(point)
        }.toByteArray()
    }

    /**
     * Sign with the keystore key and return the blob SSH expects: the type name
     * followed by r and s as SSH mpints. The platform gives back DER, so the two
     * integers have to be dug out of it.
     */
    fun sign(id: String, data: ByteArray, name: String = "this key"): ByteArray {
        val key = store().getKey(aliasFor(id), null) as? PrivateKey
            ?: throw IllegalStateException("no keystore key for this identity")
        val signer = Signature.getInstance("SHA256withECDSA").apply { initSign(key) }
        // The prompt goes up between initSign and sign, because what the person
        // authorizes is this operation and no other.
        val authorized = if (requiresUserAuth(id)) SigningGate.authorize(signer, name) else signer
        val der = authorized.run {
            update(data)
            sign()
        }
        val (r, s) = derToRs(der)
        val inner = ByteArrayOutputStream().apply {
            writeString(mpint(r))
            writeString(mpint(s))
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            writeString(SSH_TYPE.toByteArray())
            writeString(inner)
        }.toByteArray()
    }

    /** A signer for one identity, handed to the core so it can authenticate. */
    fun signer(id: String, comment: String): KeystoreSigner = object : KeystoreSigner {
        override fun publicKey(): String = openSshPublicKey(id, comment)

        override fun sign(data: ByteArray): ByteArray = try {
            HardwareKeys.sign(id, data, comment)
        } catch (e: SigningGate.Refused) {
            // Already a sentence aimed at whoever is holding the phone; wrapping
            // it in "keystore refused to sign" would only bury it.
            throw CoreException.Other(e.message ?: "the signature was not confirmed")
        } catch (e: Exception) {
            // A refusal has to reach the user as a reason, not as a hang.
            throw CoreException.Other("keystore refused to sign: ${e.message}")
        }

        override fun toString() = "KeystoreSigner($id)"
    }

    // ---- wire helpers --------------------------------------------------------

    private fun ByteArrayOutputStream.writeString(bytes: ByteArray) {
        write(byteArrayOf((bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(), (bytes.size ushr 8).toByte(), bytes.size.toByte()))
        write(bytes)
    }

    /** Left-pad or trim a BigInteger's bytes to exactly [size]. */
    private fun fixed(bytes: ByteArray, size: Int): ByteArray = when {
        bytes.size == size -> bytes
        bytes.size > size -> bytes.copyOfRange(bytes.size - size, bytes.size)
        else -> ByteArray(size - bytes.size) + bytes
    }

    /** An SSH mpint: two's complement, big-endian, no leading zero unless the top bit is set. */
    private fun mpint(v: ByteArray): ByteArray {
        var i = 0
        while (i < v.size - 1 && v[i] == 0.toByte() && (v[i + 1].toInt() and 0x80) == 0) i++
        val trimmed = v.copyOfRange(i, v.size)
        return if (trimmed.isNotEmpty() && (trimmed[0].toInt() and 0x80) != 0) byteArrayOf(0) + trimmed else trimmed
    }

    /** Pull r and s out of a DER SEQUENCE { INTEGER r, INTEGER s }. */
    private fun derToRs(der: ByteArray): Pair<ByteArray, ByteArray> {
        var at = 0
        fun byte(): Int = der[at++].toInt() and 0xFF
        fun length(): Int {
            val first = byte()
            if (first and 0x80 == 0) return first
            var len = 0
            repeat(first and 0x7F) { len = (len shl 8) or byte() }
            return len
        }
        require(byte() == 0x30) { "signature is not a DER sequence" }
        length()
        require(byte() == 0x02) { "signature has no first integer" }
        val rLen = length()
        val r = der.copyOfRange(at, at + rLen)
        at += rLen
        require(byte() == 0x02) { "signature has no second integer" }
        val sLen = length()
        val s = der.copyOfRange(at, at + sLen)
        at += sLen
        return r to s
    }
}
