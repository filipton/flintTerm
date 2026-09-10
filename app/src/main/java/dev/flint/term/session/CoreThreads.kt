package dev.flint.term.session

/**
 * Keeps the core's own threads attached to the JVM.
 *
 * The bridge is JNA, and JNA's default for a callback arriving on a thread it
 * does not know is to attach that thread to the JVM, run the callback, and
 * detach it again on the way out. Attaching means a whole `java.lang.Thread` —
 * stack, GC roots, bookkeeping — created and thrown away per call.
 *
 * The core calls back once per chunk of output, which for a session watching a
 * program that redraws itself is a dozen times a second, for as long as the
 * session is up. It does not stop when the screen goes off, so that is a dozen
 * threads a second built and torn down in a pocket. Asking JNA to leave the
 * thread attached makes every callback after the first on that thread free, and
 * a core worker lives as long as the session does.
 *
 * Call it first thing in any callback the core can make more than once.
 */
object CoreThreads {
    private val attached = ThreadLocal.withInitial { false }

    fun keepAttached() {
        // A thread JNA detached comes back as a new Thread with a new set of
        // locals, so a false reading here is the truth about this attachment.
        if (attached.get()) return
        attached.set(true)
        runCatching { com.sun.jna.Native.detach(false) }
    }
}
