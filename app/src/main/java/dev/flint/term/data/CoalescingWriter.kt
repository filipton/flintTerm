package dev.flint.term.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * Writes the latest of a stream of values, at most once per [quietMs].
 *
 * A slider drag changes the settings every frame and every Enter adds a line
 * of history; writing the whole store for each would be work thrown away by the
 * next one a few milliseconds later. The first change starts the clock and
 * whatever is newest when it runs out is written, so a burst costs one write
 * and a change that keeps coming, like a long drag, is still on disk within
 * [quietMs] rather than only once it stops.
 *
 * Writes run one at a time, in order, on [scope] — or on the caller for
 * [flush], which is for the moments that cannot wait: the app leaving the
 * screen, after which the process may be killed without another word.
 */
internal class CoalescingWriter<T : Any>(
    scope: CoroutineScope,
    private val quietMs: Long,
    private val write: (T) -> Unit,
) {
    private val pending = AtomicReference<T?>(null)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val lock = Any()

    init {
        scope.launch {
            for (tick in wake) {
                delay(quietMs)
                drain()
            }
        }
    }

    /** Replaces whatever was waiting; only the newest value is ever written. */
    fun submit(value: T) {
        pending.set(value)
        wake.trySend(Unit)
    }

    /** Writes what is waiting now, on the calling thread. */
    fun flush() = drain()

    private fun drain() {
        // Taking the value inside the lock is what keeps writes in order: a
        // later drain can only take a newer value, never overtake with an older.
        synchronized(lock) {
            val value = pending.getAndSet(null) ?: return
            write(value)
        }
    }
}
