package dev.flint.term.terminal

import android.view.KeyEvent
import dev.flint.term.core.KeyCode
import dev.flint.term.core.ModifierKey
import dev.flint.term.data.CapsLockAction

/**
 * Android key codes, translated into the keys a terminal knows.
 *
 * It sits outside the view because the translation is the part worth testing,
 * and a test that has to inflate a view to press a key is a test nobody runs.
 */
object HardwareKeyMap {

    /** Keys with a name of their own; null means the key stands for text. */
    fun special(keyCode: Int): KeyCode? = when (keyCode) {
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> KeyCode.Enter
        KeyEvent.KEYCODE_TAB -> KeyCode.Tab
        KeyEvent.KEYCODE_DEL -> KeyCode.Backspace
        KeyEvent.KEYCODE_FORWARD_DEL -> KeyCode.Delete
        KeyEvent.KEYCODE_ESCAPE -> KeyCode.Escape
        KeyEvent.KEYCODE_DPAD_UP -> KeyCode.Up
        KeyEvent.KEYCODE_DPAD_DOWN -> KeyCode.Down
        KeyEvent.KEYCODE_DPAD_LEFT -> KeyCode.Left
        KeyEvent.KEYCODE_DPAD_RIGHT -> KeyCode.Right
        KeyEvent.KEYCODE_MOVE_HOME -> KeyCode.Home
        KeyEvent.KEYCODE_MOVE_END -> KeyCode.End
        KeyEvent.KEYCODE_PAGE_UP -> KeyCode.PageUp
        KeyEvent.KEYCODE_PAGE_DOWN -> KeyCode.PageDown
        KeyEvent.KEYCODE_INSERT -> KeyCode.Insert
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> KeyCode.Function((keyCode - KeyEvent.KEYCODE_F1 + 1).toUByte())
        else -> null
    }

    /** The modifier struck on its own, or null when the key is not one. */
    fun modifier(keyCode: Int): ModifierKey? = when (keyCode) {
        KeyEvent.KEYCODE_SHIFT_LEFT -> ModifierKey.LEFT_SHIFT
        KeyEvent.KEYCODE_SHIFT_RIGHT -> ModifierKey.RIGHT_SHIFT
        KeyEvent.KEYCODE_CTRL_LEFT -> ModifierKey.LEFT_CONTROL
        KeyEvent.KEYCODE_CTRL_RIGHT -> ModifierKey.RIGHT_CONTROL
        KeyEvent.KEYCODE_ALT_LEFT -> ModifierKey.LEFT_ALT
        KeyEvent.KEYCODE_ALT_RIGHT -> ModifierKey.RIGHT_ALT
        KeyEvent.KEYCODE_META_LEFT -> ModifierKey.LEFT_SUPER
        KeyEvent.KEYCODE_META_RIGHT -> ModifierKey.RIGHT_SUPER
        else -> null
    }

    /** What a Caps Lock key that has been given another job should do. */
    sealed interface CapsAction {
        /** Send this key as the cap goes down. */
        data class Send(val code: KeyCode) : CapsAction
        /** Hold Control for as long as the key is held. */
        data object HoldControl : CapsAction
        /** Let that Control go. */
        data object ReleaseControl : CapsAction
        /** Ours, with nothing to do — the Escape half coming back up. */
        data object Swallow : CapsAction
        /** Caps Lock is still Caps Lock; the platform can have it. */
        data object None : CapsAction
    }

    /**
     * Caps Lock, remapped.
     *
     * It is the key under the left little finger and the least useful one on
     * the board, which is why every terminal person moves it; [CapsLockAction]
     * says where to. Control is held rather than sent, the way the volume keys
     * hold a modifier, because Control is only ever half of a chord.
     */
    fun capsLock(keyCode: Int, down: Boolean, capsLockAs: CapsLockAction): CapsAction {
        if (keyCode != KeyEvent.KEYCODE_CAPS_LOCK) return CapsAction.None
        return when (capsLockAs) {
            CapsLockAction.NONE -> CapsAction.None
            CapsLockAction.ESC -> if (down) CapsAction.Send(KeyCode.Escape) else CapsAction.Swallow
            CapsLockAction.CTRL -> if (down) CapsAction.HoldControl else CapsAction.ReleaseControl
        }
    }

    /**
     * The meta state to hand `getUnicodeChar`.
     *
     * Ctrl and Alt are dropped because the terminal applies them itself: left
     * in, they would turn the key into whatever the layout maps the chord to
     * instead of the plain character the encoder needs. A remapped Caps Lock
     * goes the same way — Android keeps toggling its caps state whatever the
     * key has been turned into, and left in it would make every letter shout.
     */
    fun unicodeMeta(metaState: Int, capsLockAs: CapsLockAction = CapsLockAction.NONE): Int {
        var claimed = KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK
        if (capsLockAs != CapsLockAction.NONE) claimed = claimed or KeyEvent.META_CAPS_LOCK_ON
        return metaState and claimed.inv()
    }
}
