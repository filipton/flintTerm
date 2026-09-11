package dev.flint.term.terminal

/**
 * The snapshot a frame is drawn from, fetched over plain JNI.
 *
 * Every other call into the core goes through uniffi, which runs over JNA, and
 * JNA builds a status structure and a return structure by reflection, with
 * native memory behind each, for every call. The snapshot is the one call made
 * on every frame, and it was several of those trips a frame: profiling put JNA
 * and the garbage it left at a few percent of the app while output streamed.
 * Nothing but numbers cross here.
 *
 * The handles are strong references the view takes once through uniffi
 * (`frameHandle()`) and gives back with the release calls, so a session torn
 * down elsewhere cannot free what a frame is still reading.
 */
@androidx.annotation.Keep
internal object FrameBridge {
    init {
        // uniffi loads the library through JNA; JNI looks up its own symbols,
        // so the same library has to be loaded the Java way as well.
        System.loadLibrary("flintterm")
    }

    /** Bit for [snapshotInto]: find the links too. */
    const val WANT_LINKS = 1

    /** Fills the buffer and returns its length, or -1. */
    @JvmStatic external fun snapshotInto(session: Long, buffer: Long, flags: Int): Long

    /** Where the buffer starts; it only moves when its length changes. */
    @JvmStatic external fun bufferAddress(buffer: Long): Long

    @JvmStatic external fun releaseSession(session: Long)

    @JvmStatic external fun releaseBuffer(buffer: Long)
}
