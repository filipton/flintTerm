package dev.flint.term.data

import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import dev.flint.term.App
import dev.flint.term.ui.AppLock
import java.security.Signature
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The person, in front of a signature.
 *
 * A key created with "ask before every use" is enforced by the keystore, not by
 * this app: the platform will not sign with it until a `BiometricPrompt` has
 * authorized that very `Signature` object. That prompt only exists on the main
 * thread and on top of an activity, while signing happens deep inside a
 * connection on a background thread — so the signing thread hands the prompt
 * over and waits for the answer.
 */
object SigningGate {
    private const val TAG = "SigningGate"

    /**
     * Long enough for someone to pick the phone up and try a second finger,
     * short enough that a connection nobody is watching gives up by itself
     * rather than holding a thread until the session is torn down.
     */
    private const val WAIT_SECONDS = 90L

    /** Raised when the signature cannot be authorized; the message is shown as-is. */
    class Refused(message: String) : Exception(message)

    /**
     * Get [signature] authorized and hand it back ready to sign.
     *
     * Blocks the calling thread, which must therefore not be the main one — the
     * prompt is answered there.
     */
    fun authorize(signature: Signature, keyName: String): Signature {
        check(Looper.myLooper() != Looper.getMainLooper()) { "a signature cannot be authorized on the main thread" }
        val activity = App.foregroundActivity
            ?: throw Refused("this key asks for a fingerprint before every use, so open flintTerm and try again")
        if (BiometricManager.from(activity).canAuthenticate(authenticators()) != BiometricManager.BIOMETRIC_SUCCESS) {
            // The key was made when the device had a way to prove who is holding
            // it. Removing the fingerprint or the screen lock takes that away,
            // and the keystore will never sign with this key again.
            throw Refused("this device has no fingerprint, face or screen lock set up any more, so this key cannot be used")
        }

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Sign in with $keyName")
            .setSubtitle("This key needs you here for every signature")
            .setAllowedAuthenticators(authenticators())
            .apply {
                // A negative button is required precisely when the device
                // credential is not among the authenticators, and forbidden when
                // it is.
                if (!credentialAllowed()) setNegativeButtonText("Cancel")
            }
            .build()

        val done = CountDownLatch(1)
        // Written on the main thread and read here; the latch is what makes that
        // safe, and it is also what makes the wait a wait rather than a poll.
        val authorized = java.util.concurrent.atomic.AtomicReference<Signature?>(null)
        ContextCompat.getMainExecutor(activity).execute {
            AppLock.ask(activity, info, BiometricPrompt.CryptoObject(signature)) { result ->
                // The keystore authorizes the object it was handed, so the one
                // that comes back is the one to sign with.
                if (result != null) authorized.set(result.cryptoObject?.signature ?: signature)
                done.countDown()
            }
        }
        if (!done.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
            Log.i(TAG, "nobody answered the signing prompt for $keyName")
            throw Refused("nobody confirmed the fingerprint prompt for $keyName")
        }
        return authorized.get() ?: throw Refused("the fingerprint prompt for $keyName was dismissed")
    }

    /**
     * Below API 30 a crypto-bound prompt cannot fall back to the PIN or pattern,
     * so those keys are biometric-only and the prompt has to say so too.
     */
    private fun credentialAllowed() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    private fun authenticators(): Int =
        if (credentialAllowed()) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        }
}
