package dev.flint.term.terminal

import dev.flint.term.core.KeyPress

/**
 * The snapshot a frame is drawn from, fetched over plain JNI, and the few
 * other calls made at the rate of frames or fingers: scrolling, dragging a
 * selection, and keys.
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

    /** Bit for [snapshotInto]: paint the keyword colour rules into the cells. */
    const val WANT_HIGHLIGHTS = 2

    /** Fills the buffer and returns its length, or -1. */
    @JvmStatic external fun snapshotInto(session: Long, buffer: Long, flags: Int): Long

    /** Where the buffer starts; it only moves when its length changes. */
    @JvmStatic external fun bufferAddress(buffer: Long): Long

    /** `Session.scroll`: `lines` > 0 scrolls towards history. */
    @JvmStatic external fun scroll(session: Long, col: Int, row: Int, lines: Int)

    /** `Session.selectUpdate`. */
    @JvmStatic external fun selectUpdate(session: Long, col: Int, row: Int, moveStart: Boolean)

    /** `Session.sendKey`, with the press packed by [KeyPacking]. */
    @JvmStatic external fun sendKey(session: Long, key: Int, arg: Int, mods: Int, kind: Int)

    fun sendKey(session: Long, press: KeyPress) =
        sendKey(session, KeyPacking.key(press.key), KeyPacking.arg(press.key), KeyPacking.mods(press), press.kind.ordinal)

    @JvmStatic external fun releaseSession(session: Long)

    @JvmStatic external fun releaseBuffer(buffer: Long)
}
