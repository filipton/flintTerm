package dev.flint.term.session

import android.util.Log
import dev.flint.term.core.AgentApproval
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The person, in front of a forwarded signature.
 *
 * Agent forwarding hands the machine you are on the ability to sign as you for
 * as long as you are connected: anyone with root there can use it to reach
 * every other host that key opens, and the key itself never has to move. That
 * is the deal `ssh -A` makes silently, and this is `ssh-add -c` — the far side
 * asks, and somebody says yes.
 *
 * The request arrives on a blocking thread deep inside a connection, so the
 * answer is waited for rather than polled, and nobody at the keyboard means no.
 */
object AgentGate {
    private const val TAG = "AgentGate"

    /**
     * Long enough to pick the phone up and read which key is being asked for,
     * short enough that a `git push` nobody is watching fails instead of
     * hanging until the server gives up.
     */
    private const val WAIT_SECONDS = 45L

    private val _prompt = MutableStateFlow<Asking?>(null)
    val prompt: StateFlow<Asking?> = _prompt

    /** One request, and the two ways it can end. */
    class Asking(val hostLabel: String, val keyName: String, val fingerprint: String) {
        private val latch = CountDownLatch(1)
        private val allowed = AtomicBoolean(false)

        fun allow() {
            allowed.set(true)
            latch.countDown()
        }

        fun deny() = latch.countDown()

        internal fun await(): Boolean {
            val answered = latch.await(WAIT_SECONDS, TimeUnit.SECONDS)
            return answered && allowed.get()
        }
    }

    /**
     * Approval for one host's forwarded agent, ready to hand to the core.
     *
     * [hostLabel] is in every prompt because it is the whole question: the same
     * key signing for a machine you opened a moment ago and for one you forgot
     * you were still connected to are very different things.
     */
    fun approvalFor(hostLabel: String): AgentApproval = object : AgentApproval {
        override fun allowSign(keyComment: String, fingerprint: String): Boolean {
            val asking = Asking(hostLabel, keyComment.ifBlank { "a key" }, fingerprint)
            // One at a time: two dialogs would stack, and the second request is
            // usually the same program trying again.
            synchronized(this@AgentGate) {
                _prompt.value = asking
                val answer = asking.await()
                _prompt.value = null
                if (!answer) Log.i(TAG, "refused a signature for $hostLabel with $keyComment")
                return answer
            }
        }
    }
}
