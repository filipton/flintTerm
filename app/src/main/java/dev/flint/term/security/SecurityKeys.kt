package dev.flint.term.security

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.nfc.NfcAdapter
import android.nfc.tech.IsoDep
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import dev.flint.term.App
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Finding a security key, and holding on to it for the length of one operation.
 *
 * Both transports end in a [CtapTransport], so enrollment and signing are the
 * same code either way; what differs is only how long it takes for something to
 * turn up. USB is polled because a key may already be plugged in, and NFC uses
 * reader mode, which needs an activity in the foreground — so signing on a phone
 * whose screen is off can only fail, and says so rather than hanging.
 */
object SecurityKeys {
    private const val TAG = "SecurityKeys"

    /** Long enough to fish a key out of a pocket, short enough to give up on nobody. */
    const val WAIT_MS = 60_000L

    fun usbSupported(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)

    fun nfcSupported(context: Context): Boolean = NfcAdapter.getDefaultAdapter(context) != null

    fun nfcEnabled(context: Context): Boolean = NfcAdapter.getDefaultAdapter(context)?.isEnabled == true

    /** Security-key-shaped USB devices attached right now. */
    fun attachedKeys(context: Context): List<UsbDevice> =
        UsbCtap.candidates(context.getSystemService(UsbManager::class.java))

    /**
     * Which transports this phone could use at all, with the reason when it
     * cannot — the enrollment screen offers a choice, and offering one that will
     * certainly fail is worse than not offering it.
     */
    fun availability(context: Context): Map<KeyTransport, String?> = mapOf(
        KeyTransport.USB to when {
            !usbSupported(context) -> "This phone cannot host USB devices"
            else -> null
        },
        KeyTransport.NFC to when {
            !nfcSupported(context) -> "This phone has no NFC"
            !nfcEnabled(context) -> "NFC is turned off in Settings"
            else -> null
        },
    )

    /**
     * Wait for a key on [transport], run [block] with it, and let it go.
     *
     * Blocking, so not from the main thread — an NFC tag is delivered on a
     * binder thread and the USB poll is a sleep loop, and the prompt that tells
     * the person what to do is drawn on the main thread meanwhile.
     */
    fun <T> withToken(
        context: Context,
        transport: KeyTransport,
        prompt: SecurityKeyPrompt,
        block: (CtapTransport) -> T,
    ): T {
        check(Looper.myLooper() != Looper.getMainLooper()) { "a security key cannot be waited for on the main thread" }
        SecurityKeyGate.show(prompt)
        try {
            val opened = when (transport) {
                KeyTransport.USB -> openUsb(context, prompt)
                KeyTransport.NFC -> openNfc(prompt)
            }
            opened.use { token ->
                if (token is HidCtapTransport) token.onTouchNeeded = { SecurityKeyGate.touchNeeded() }
                return block(token)
            }
        } finally {
            SecurityKeyGate.hide()
        }
    }

    // ---- USB -----------------------------------------------------------------

    private fun openUsb(context: Context, prompt: SecurityKeyPrompt): CtapTransport {
        if (!usbSupported(context)) {
            throw CtapException("This phone cannot host USB devices, so a security key can only be used over NFC.")
        }
        val deadline = System.currentTimeMillis() + WAIT_MS
        var lastFailure: CtapException? = null
        // A device that turned out not to be a token, or that we were refused
        // access to, is not asked about again: the loop runs every few hundred
        // milliseconds, and asking again would be a permission dialog that never
        // stops coming back.
        val ruledOut = mutableSetOf<Int>()
        while (System.currentTimeMillis() < deadline) {
            if (prompt.canceled) throw CtapException("Canceled.", recoverable = true)
            for (device in attachedKeys(context).filterNot { it.deviceId in ruledOut }) {
                if (!UsbCtap.hasPermission(context.getSystemService(UsbManager::class.java), device)) {
                    if (!askForUsb(context, device)) {
                        ruledOut += device.deviceId
                        lastFailure = CtapException(
                            "flintTerm was not allowed to use ${device.productName ?: "that USB device"}.",
                        )
                        continue
                    }
                }
                try {
                    return UsbCtap.open(context, device)
                } catch (e: CtapException) {
                    // A HID device that is not a token is the normal case on a
                    // hub; remember why in case nothing better turns up.
                    Log.i(TAG, "not usable as a security key: ${e.message}")
                    ruledOut += device.deviceId
                    lastFailure = e
                }
            }
            Thread.sleep(POLL_MS)
        }
        throw lastFailure
            ?: CtapException("No security key found. Plug one in, or hold it to the back of the phone to use NFC.", recoverable = true)
    }

    /** The system permission dialog, waited out on this thread. */
    private fun askForUsb(context: Context, device: UsbDevice): Boolean {
        val done = CountDownLatch(1)
        val granted = java.util.concurrent.atomic.AtomicBoolean(false)
        ContextCompat.getMainExecutor(context).execute {
            UsbCtap.requestPermission(context, device) {
                granted.set(it)
                done.countDown()
            }
        }
        if (!done.await(PERMISSION_WAIT_SECONDS, TimeUnit.SECONDS)) return false
        return granted.get()
    }

    // ---- NFC -----------------------------------------------------------------

    private fun openNfc(prompt: SecurityKeyPrompt): CtapTransport {
        val activity = App.foregroundActivity
            ?: throw CtapException("Open flintTerm and try again. NFC only works while the app is on screen.")
        val adapter = NfcAdapter.getDefaultAdapter(activity)
            ?: throw CtapException("This phone has no NFC, so a security key has to be plugged in over USB.")
        if (!adapter.isEnabled) {
            throw CtapException("NFC is turned off. Turn it on in Settings, then hold the key to the back of the phone.")
        }
        val arrived = ArrayBlockingQueue<IsoDep>(1)
        val callback = NfcAdapter.ReaderCallback { tag ->
            // A tag with no ISO-DEP is a transit card or a hotel key, not a
            // security key; ignoring it leaves reader mode running for the real one.
            IsoDep.get(tag)?.let { arrived.offer(it) }
        }
        onMain(activity) {
            adapter.enableReaderMode(
                activity,
                callback,
                // Presence checks are what make a key moved away an error rather
                // than a hang, and NDEF is not what a token is holding.
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                null,
            )
        }
        try {
            val deadline = System.currentTimeMillis() + WAIT_MS
            while (System.currentTimeMillis() < deadline) {
                if (prompt.canceled) throw CtapException("Canceled.", recoverable = true)
                val tag = arrived.poll(POLL_MS, TimeUnit.MILLISECONDS) ?: continue
                SecurityKeyGate.touchNeeded()
                return NfcCtapTransport(IsoDepChannel(tag)).also { it.open() }
            }
            throw CtapException("No security key was held against the phone.", recoverable = true)
        } finally {
            onMain(activity) { runCatching { adapter.disableReaderMode(activity) } }
        }
    }

    private fun onMain(activity: Activity, block: () -> Unit) {
        ContextCompat.getMainExecutor(activity).execute(block)
    }

    private const val POLL_MS = 400L
    private const val PERMISSION_WAIT_SECONDS = 60L
}

/**
 * What the person is being asked to do, while they are being asked.
 *
 * [canceled] is read by the waiting loops rather than interrupting them: a
 * half-finished CTAP2 exchange leaves a token waiting for a touch nobody will
 * give, and the next connection would inherit it.
 */
class SecurityKeyPrompt(val title: String, val transport: KeyTransport) {
    @Volatile
    var canceled: Boolean = false
        private set

    fun cancel() {
        canceled = true
    }
}

/**
 * One turn of "what is the PIN?", handed across from the screen to the worker.
 *
 * The answer is a [CharArray] and stays one the whole way: a String would be
 * immutable, so the PIN would sit in the heap until a garbage collection nobody
 * can schedule, and every copy the app made would have to be found again. This
 * array is wiped by [Ctap.unlock] the moment the token has been satisfied, and
 * the PIN is never logged, never put in the store, and never shown.
 */
class SecurityKeyPinRequest(
    /** The token's own name, when the transport knows one. */
    val tokenName: String?,
    /** What was wrong with the last attempt — "Wrong PIN. 2 attempts left". */
    val problem: String?,
) {
    private val answered = ArrayBlockingQueue<CharArray>(1)

    fun submit(pin: CharArray) {
        answered.offer(pin)
    }

    fun cancel() {
        answered.offer(GAVE_UP)
    }

    /** Blocks the worker until somebody types or gives up; null is giving up. */
    internal fun await(prompt: SecurityKeyPrompt, deadline: Long): CharArray? {
        while (System.currentTimeMillis() < deadline) {
            if (prompt.canceled) return null
            val answer = answered.poll(POLL_MS, TimeUnit.MILLISECONDS) ?: continue
            return if (answer === GAVE_UP) null else answer
        }
        return null
    }

    private companion object {
        /** Distinguishable by identity, so an empty PIN is not mistaken for a refusal. */
        val GAVE_UP = CharArray(0)
        const val POLL_MS = 200L
    }
}

/**
 * The one prompt the app shows for a security key, wherever it is signing from.
 *
 * Signing happens on a background thread deep inside a connection, exactly as it
 * does for a keystore key, so this is the same arrangement
 * [dev.flint.term.data.SigningGate] uses: the worker publishes what it is
 * waiting for and the main thread draws it.
 */
object SecurityKeyGate {
    private val _prompt = MutableStateFlow<Showing?>(null)
    val prompt: StateFlow<Showing?> = _prompt

    /** What to draw: the title, what to do about it, and how to give up. */
    data class Showing(
        val title: String,
        val body: String,
        val prompt: SecurityKeyPrompt,
        /** Non-null while the token is waiting to be told its PIN. */
        val pin: SecurityKeyPinRequest? = null,
    )

    internal fun show(prompt: SecurityKeyPrompt) {
        _prompt.value = Showing(prompt.title, waitingText(prompt.transport), prompt)
    }

    /**
     * Turn the same dialog into a PIN prompt and wait there.
     *
     * Called from the worker holding the token, which is why it blocks: the
     * exchange with the key is half-finished, and a PIN typed after the token
     * has been let go is a PIN typed for nothing.
     */
    fun asker(prompt: SecurityKeyPrompt): PinAsker = PinAsker { tokenName, problem ->
        val request = SecurityKeyPinRequest(tokenName, problem)
        // Nothing on screen means nothing can be typed, and waiting five minutes
        // to discover that would look like the app having hung.
        val showing = _prompt.updateAndGet { it?.copy(pin = request) }
        if (showing == null) null else try {
            request.await(prompt, System.currentTimeMillis() + PIN_WAIT_MS)
        } finally {
            _prompt.update { it?.copy(pin = null) }
        }
    }

    /** Long enough to find a PIN somebody wrote down, and to give up on one nobody remembers. */
    private const val PIN_WAIT_MS = 5 * 60_000L

    /** The token said it is waiting for a finger, which is worth saying out loud. */
    internal fun touchNeeded() {
        _prompt.update { it?.copy(body = "Touch the security key.") }
    }

    internal fun hide() {
        _prompt.value = null
    }

    private fun waitingText(transport: KeyTransport) = when (transport) {
        KeyTransport.USB -> "Plug the security key in, then touch it."
        KeyTransport.NFC -> "Hold the security key against the back of the phone and keep it there."
    }
}
