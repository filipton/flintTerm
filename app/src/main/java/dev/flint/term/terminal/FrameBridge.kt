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

    /** Bit for [resetGlyphs]: lay out each row's draw runs, not only its hash. */
    const val LAYOUT_RUNS = 1

    /** Bit for [resetGlyphs]: private-use codepoints go to the icon font's slot. */
    const val LAYOUT_NERD = 2

    /** The faces a run can be in; the core numbers them the same way. */
    const val SLOT_REGULAR = 0
    const val SLOT_BOLD = 1
    const val SLOT_ITALIC = 2
    const val SLOT_BOLD_ITALIC = 3
    const val SLOT_SYMBOL = 4
    const val SLOTS = 5

    /** On a fallback run: the core would draw this by number if told its glyphs. */
    const val FALLBACK_LEARN = 1 shl 24

    /**
     * New metrics or a new font for the buffer's row runs: everything told to
     * it through [setGlyphs] and [learnGlyph] is forgotten.
     */
    @JvmStatic external fun resetGlyphs(buffer: Long, flags: Int, cellW: Float, baseline: Float)

    /**
     * The glyphs printable ASCII after the space draws with in one face, all
     * out of font number [font]: [idsAddr] points at 94 native ints, or is 0
     * when the face cannot be drawn by number.
     */
    @JvmStatic external fun setGlyphs(buffer: Long, slot: Int, font: Int, idsAddr: Long)

    /** What [cp] shaped to in one face; a negative [glyphId] keeps it text. */
    @JvmStatic external fun learnGlyph(buffer: Long, slot: Int, cp: Int, font: Int, glyphId: Int)

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

    /**
     * `Session.sendText`, with the text already UTF-8 in [len] bytes at
     * [addr], which the caller keeps alive for the call; see [Utf8Buffer].
     */
    @JvmStatic external fun sendBytes(session: Long, addr: Long, len: Int)

    /** What [imageInto] answers: the bitmap holds the image. */
    const val IMAGE_COPIED = 1

    /** That generation of the image is gone, and so should its bitmap be. */
    const val IMAGE_GONE = 0

    /**
     * Writes one generation of an image into an `ARGB_8888` [bitmap] of its
     * size, premultiplied; [IMAGE_COPIED], [IMAGE_GONE] or -1 when it could
     * not be written.
     */
    @JvmStatic external fun imageInto(session: Long, id: Int, generation: Int, bitmap: android.graphics.Bitmap): Int

    @JvmStatic external fun releaseSession(session: Long)

    @JvmStatic external fun releaseBuffer(buffer: Long)
}
