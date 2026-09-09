package dev.flint.term.terminal

import dev.flint.term.data.CursorStyle

/**
 * Which shape the cursor is actually drawn as, given what the program asked
 * for and what the preference says.
 *
 * The program wins. A full-screen editor that switches to a bar in insert mode
 * is saying something about the mode you are in, and a preference that
 * overruled it would throw that away. The preference is the shape for
 * everything else — which is nearly all the time, because a shell prompt never
 * sends DECSCUSR at all.
 *
 * The snapshot carries only the shape, not whether it was ever set, so
 * "the program has spoken" is read off the shape itself: alacritty starts every
 * screen at a block, so anything else came from a DECSCUSR the program sent.
 * The cost is that a program asking explicitly for a block is indistinguishable
 * from one that asked for nothing, and the preference applies to both.
 */
object CursorShapes {
    const val BLOCK = 0
    const val UNDERLINE = 1
    const val BAR = 2
    const val HIDDEN = 3

    /** [fromProgram] is the snapshot's `cursor_shape` byte. */
    fun effective(fromProgram: Int, preference: CursorStyle): Int = when {
        // Hidden is never a shape somebody picks; the program turned the cursor
        // off (DECTCEM) and the screen has no business drawing one.
        fromProgram == HIDDEN -> HIDDEN
        fromProgram != BLOCK -> fromProgram
        else -> when (preference) {
            CursorStyle.BLOCK -> BLOCK
            CursorStyle.UNDERLINE -> UNDERLINE
            CursorStyle.BAR -> BAR
        }
    }
}
