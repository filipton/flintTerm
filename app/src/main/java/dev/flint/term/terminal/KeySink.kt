package dev.flint.term.terminal

import dev.flint.term.core.KeyCode
import dev.flint.term.core.KeyPress
import dev.flint.term.session.TerminalSession

/** Somewhere typing can land: one session, or a stand-in for one in a test. */
interface KeyTarget {
    fun key(press: KeyPress)
    fun text(text: String)
    fun paste(text: String)

    /** [text], with the same text already packed as UTF-8 for the short way across. */
    fun text(text: String, utf8: Utf8Buffer) = text(text)
}

/**
 * The far end of the send path: the session being typed into, and a second one
 * getting the same keys when typing is broadcast across a split.
 *
 * It sits outside the view because "does what I type reach both panes?" is the
 * whole of that feature, and a test that has to inflate a view to answer it is
 * a test nobody runs. Either end may be missing — a session is briefly gone
 * while a reconnect replaces it, and most of the time there is no mirror at all
 * — so both are written to as far as they exist and neither can stop the other.
 */
class KeySink(var primary: KeyTarget? = null, var mirror: KeyTarget? = null) {

    /** Typed text as it goes across, packed once however many panes it reaches. */
    private val utf8 = Utf8Buffer()

    fun key(press: KeyPress) {
        primary?.key(press)
        mirror?.key(press)
    }

    fun text(text: String) {
        val p = primary
        val m = mirror
        if (p == null && m == null) return
        utf8.pack(text)
        p?.text(text, utf8)
        m?.text(text, utf8)
    }

    fun paste(text: String) {
        primary?.paste(text)
        mirror?.paste(text)
    }
}

/**
 * A live session as somewhere to type.
 *
 * A destroyed session quietly takes nothing: the core behind it is gone, and
 * its handle stays reachable only for as long as it takes the UI to notice.
 */
class SessionTarget(private val session: TerminalSession) : KeyTarget {
    private val core get() = session.takeIf { !it.destroyed }?.core

    override fun key(press: KeyPress) {
        if (!session.destroyed) session.sendKey(press)
    }

    override fun text(text: String) {
        core?.sendText(text)
    }

    override fun text(text: String, utf8: Utf8Buffer) {
        session.sendText(text, utf8)
    }

    override fun paste(text: String) {
        core?.paste(text)
    }
}

/**
 * Text as UTF-8 in native memory the core can read by address, for
 * [FrameBridge.sendBytes].
 *
 * Every committed word from the keyboard went across uniffi as a string, and
 * JNA built its status and return structures by reflection for each one, twice
 * while typing into two panes at once. The memory is kept and reused, and only
 * replaced by a larger block when a longer text comes along; the encoding is
 * done by hand so that nothing is allocated for a keystroke either.
 */
class Utf8Buffer(initial: Int = 256) {
    /** The packed text, valid up to [length]. */
    var bytes: java.nio.ByteBuffer = java.nio.ByteBuffer.allocateDirect(initial)
        private set

    /** How many bytes the last [pack] wrote. */
    var length = 0
        private set

    private var addressOf: java.nio.ByteBuffer? = null
    private var addressValue = 0L

    /**
     * Where [bytes] starts. Asked of JNA once per block, and not at all until
     * something is sent, which keeps the packing testable without a device.
     */
    val address: Long
        get() {
            if (addressOf !== bytes) {
                addressValue = com.sun.jna.Pointer.nativeValue(com.sun.jna.Native.getDirectBufferPointer(bytes))
                addressOf = bytes
            }
            return addressValue
        }

    /**
     * Encodes [text] from the start of the buffer and returns its length.
     *
     * A lone surrogate becomes `?`, as `String.toByteArray` makes it: that is
     * what the same text sent as a string used to arrive as.
     */
    fun pack(text: String): Int {
        // Three bytes a char at most: a pair of surrogates is four for two.
        val need = text.length * 3
        if (need > bytes.capacity()) bytes = java.nio.ByteBuffer.allocateDirect(maxOf(need, bytes.capacity() * 2))
        val b = bytes
        var at = 0
        var i = 0
        while (i < text.length) {
            val c = text[i].code
            when {
                c < 0x80 -> b.put(at++, c.toByte())
                c < 0x800 -> {
                    b.put(at++, (0xc0 or (c shr 6)).toByte())
                    b.put(at++, (0x80 or (c and 0x3f)).toByte())
                }
                Character.isHighSurrogate(text[i]) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> {
                    val cp = Character.toCodePoint(text[i], text[i + 1])
                    i++
                    b.put(at++, (0xf0 or (cp shr 18)).toByte())
                    b.put(at++, (0x80 or ((cp shr 12) and 0x3f)).toByte())
                    b.put(at++, (0x80 or ((cp shr 6) and 0x3f)).toByte())
                    b.put(at++, (0x80 or (cp and 0x3f)).toByte())
                }
                Character.isSurrogate(text[i]) -> b.put(at++, '?'.code.toByte())
                else -> {
                    b.put(at++, (0xe0 or (c shr 12)).toByte())
                    b.put(at++, (0x80 or ((c shr 6) and 0x3f)).toByte())
                    b.put(at++, (0x80 or (c and 0x3f)).toByte())
                }
            }
            i++
        }
        length = at
        return at
    }
}

/**
 * A [KeyPress] as the numbers [FrameBridge.sendKey] takes.
 *
 * The key is numbered in the order [KeyCode]'s variants are declared on the
 * Rust side, which is where it is unpacked again; the modifier keys and the
 * event kind go by their own declaration order, which the bindings keep.
 */
internal object KeyPacking {
    fun key(code: KeyCode): Int = when (code) {
        is KeyCode.Char -> 0
        KeyCode.Enter -> 1
        KeyCode.Tab -> 2
        KeyCode.Backspace -> 3
        KeyCode.Escape -> 4
        KeyCode.Up -> 5
        KeyCode.Down -> 6
        KeyCode.Left -> 7
        KeyCode.Right -> 8
        KeyCode.Home -> 9
        KeyCode.End -> 10
        KeyCode.PageUp -> 11
        KeyCode.PageDown -> 12
        KeyCode.Insert -> 13
        KeyCode.Delete -> 14
        is KeyCode.Function -> 15
        is KeyCode.Modifier -> 16
    }

    fun arg(code: KeyCode): Int = when (code) {
        is KeyCode.Char -> code.codepoint.toInt()
        is KeyCode.Function -> code.n.toInt()
        is KeyCode.Modifier -> code.which.ordinal
        else -> 0
    }

    fun mods(press: KeyPress): Int =
        (if (press.ctrl) 1 else 0) or (if (press.alt) 2 else 0) or (if (press.shift) 4 else 0)
}
