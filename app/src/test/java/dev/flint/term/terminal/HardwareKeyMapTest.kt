package dev.flint.term.terminal

import android.view.KeyEvent
import dev.flint.term.core.KeyCode
import dev.flint.term.core.ModifierKey
import dev.flint.term.data.CapsLockAction
import dev.flint.term.terminal.HardwareKeyMap.CapsAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HardwareKeyMapTest {

    @Test
    fun `caps lock as escape sends it once, on the way down`() {
        assertEquals(
            CapsAction.Send(KeyCode.Escape),
            HardwareKeyMap.capsLock(KeyEvent.KEYCODE_CAPS_LOCK, down = true, CapsLockAction.ESC),
        )
        assertEquals(
            CapsAction.Swallow,
            HardwareKeyMap.capsLock(KeyEvent.KEYCODE_CAPS_LOCK, down = false, CapsLockAction.ESC),
        )
    }

    @Test
    fun `caps lock as control is held, not sent`() {
        assertEquals(
            CapsAction.HoldControl,
            HardwareKeyMap.capsLock(KeyEvent.KEYCODE_CAPS_LOCK, down = true, CapsLockAction.CTRL),
        )
        assertEquals(
            CapsAction.ReleaseControl,
            HardwareKeyMap.capsLock(KeyEvent.KEYCODE_CAPS_LOCK, down = false, CapsLockAction.CTRL),
        )
    }

    @Test
    fun `left alone, caps lock belongs to the platform`() {
        assertEquals(CapsAction.None, HardwareKeyMap.capsLock(KeyEvent.KEYCODE_CAPS_LOCK, down = true, CapsLockAction.NONE))
        assertEquals(CapsAction.None, HardwareKeyMap.capsLock(KeyEvent.KEYCODE_A, down = true, CapsLockAction.CTRL))
    }

    @Test
    fun `a remapped caps lock stops letters from shouting`() {
        val meta = KeyEvent.META_CAPS_LOCK_ON or KeyEvent.META_SHIFT_ON
        assertEquals(meta, HardwareKeyMap.unicodeMeta(meta, CapsLockAction.NONE))
        assertEquals(KeyEvent.META_SHIFT_ON, HardwareKeyMap.unicodeMeta(meta, CapsLockAction.CTRL))
        assertEquals(KeyEvent.META_SHIFT_ON, HardwareKeyMap.unicodeMeta(meta, CapsLockAction.ESC))
    }

    @Test
    fun `ctrl and alt never reach the layout`() {
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_ALT_ON or KeyEvent.META_SHIFT_ON
        assertEquals(KeyEvent.META_SHIFT_ON, HardwareKeyMap.unicodeMeta(meta))
    }

    @Test
    fun `special keys keep their terminal names`() {
        assertEquals(KeyCode.Escape, HardwareKeyMap.special(KeyEvent.KEYCODE_ESCAPE))
        assertEquals(KeyCode.Backspace, HardwareKeyMap.special(KeyEvent.KEYCODE_DEL))
        assertEquals(KeyCode.Delete, HardwareKeyMap.special(KeyEvent.KEYCODE_FORWARD_DEL))
        assertEquals(KeyCode.Up, HardwareKeyMap.special(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(KeyCode.Function(5u), HardwareKeyMap.special(KeyEvent.KEYCODE_F5))
        // A letter is text, and text is not the map's business.
        assertNull(HardwareKeyMap.special(KeyEvent.KEYCODE_A))
    }

    @Test
    fun `modifiers are told apart by side`() {
        assertEquals(ModifierKey.LEFT_CONTROL, HardwareKeyMap.modifier(KeyEvent.KEYCODE_CTRL_LEFT))
        assertEquals(ModifierKey.RIGHT_SHIFT, HardwareKeyMap.modifier(KeyEvent.KEYCODE_SHIFT_RIGHT))
        assertEquals(ModifierKey.LEFT_SUPER, HardwareKeyMap.modifier(KeyEvent.KEYCODE_META_LEFT))
        assertNull(HardwareKeyMap.modifier(KeyEvent.KEYCODE_CAPS_LOCK))
    }
}
