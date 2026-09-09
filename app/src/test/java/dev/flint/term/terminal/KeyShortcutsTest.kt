package dev.flint.term.terminal

import android.view.KeyEvent
import dev.flint.term.data.KeyBinding
import dev.flint.term.terminal.KeyShortcuts.Shortcut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyShortcutsTest {

    private fun ctrlShift(keyCode: Int) = KeyShortcuts.resolve(keyCode, ctrl = true, shift = true, alt = false)
    private fun ctrl(keyCode: Int) = KeyShortcuts.resolve(keyCode, ctrl = true, shift = false, alt = false)

    @Test
    fun `ctrl+shift chords are the app's`() {
        assertEquals(Shortcut.COPY, ctrlShift(KeyEvent.KEYCODE_C))
        assertEquals(Shortcut.PASTE, ctrlShift(KeyEvent.KEYCODE_V))
        assertEquals(Shortcut.NEW_SESSION, ctrlShift(KeyEvent.KEYCODE_N))
        assertEquals(Shortcut.SEARCH, ctrlShift(KeyEvent.KEYCODE_F))
        assertEquals(Shortcut.CLOSE_SESSION, ctrlShift(KeyEvent.KEYCODE_W))
    }

    @Test
    fun `tabs move with tab or with the brackets`() {
        assertEquals(Shortcut.NEXT_TAB, ctrl(KeyEvent.KEYCODE_TAB))
        assertEquals(Shortcut.PREV_TAB, ctrlShift(KeyEvent.KEYCODE_TAB))
        assertEquals(Shortcut.NEXT_TAB, ctrlShift(KeyEvent.KEYCODE_RIGHT_BRACKET))
        assertEquals(Shortcut.PREV_TAB, ctrlShift(KeyEvent.KEYCODE_LEFT_BRACKET))
    }

    @Test
    fun `zoom answers to every shape of plus and minus`() {
        assertEquals(Shortcut.FONT_BIGGER, ctrlShift(KeyEvent.KEYCODE_EQUALS))
        assertEquals(Shortcut.FONT_BIGGER, ctrlShift(KeyEvent.KEYCODE_PLUS))
        assertEquals(Shortcut.FONT_BIGGER, ctrlShift(KeyEvent.KEYCODE_NUMPAD_ADD))
        assertEquals(Shortcut.FONT_SMALLER, ctrlShift(KeyEvent.KEYCODE_MINUS))
        assertEquals(Shortcut.FONT_SMALLER, ctrlShift(KeyEvent.KEYCODE_NUMPAD_SUBTRACT))
        assertEquals(Shortcut.FONT_RESET, ctrl(KeyEvent.KEYCODE_0))
        assertEquals(Shortcut.FONT_RESET, ctrl(KeyEvent.KEYCODE_NUMPAD_0))
    }

    @Test
    fun `the shell keeps its control keys`() {
        for (key in intArrayOf(KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_L, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_Z)) {
            assertNull("Ctrl+$key belongs to the remote", ctrl(key))
        }
    }

    @Test
    fun `shift alone or no modifier at all is just typing`() {
        assertNull(KeyShortcuts.resolve(KeyEvent.KEYCODE_C, ctrl = false, shift = true, alt = false))
        assertNull(KeyShortcuts.resolve(KeyEvent.KEYCODE_TAB, ctrl = false, shift = false, alt = false))
        assertNull(KeyShortcuts.resolve(KeyEvent.KEYCODE_0, ctrl = false, shift = false, alt = false))
    }

    @Test
    fun `alt makes a chord the shell's meta prefix, not ours`() {
        assertNull(KeyShortcuts.resolve(KeyEvent.KEYCODE_C, ctrl = true, shift = true, alt = true))
        assertNull(KeyShortcuts.resolve(KeyEvent.KEYCODE_TAB, ctrl = true, shift = false, alt = true))
    }

    @Test
    fun `chords nothing is bound to fall through`() {
        assertNull(ctrlShift(KeyEvent.KEYCODE_G))
        assertNull(ctrlShift(KeyEvent.KEYCODE_1))
        assertNull(ctrlShift(KeyEvent.KEYCODE_ENTER))
        assertNull(ctrl(KeyEvent.KEYCODE_1))
    }

    // ---- chords someone has changed ------------------------------------------

    private val custom = listOf(
        KeyBinding(Shortcut.COPY.name, KeyEvent.KEYCODE_Y, shift = true),
        KeyBinding(Shortcut.SEARCH.name, KeyEvent.KEYCODE_0),
    )

    @Test
    fun `an empty list is the built-in set`() {
        assertEquals(Shortcut.COPY, KeyShortcuts.resolve(KeyEvent.KEYCODE_C, ctrl = true, shift = true, alt = false, bindings = emptyList()))
        assertEquals(Shortcut.FONT_RESET, KeyShortcuts.resolve(KeyEvent.KEYCODE_0, ctrl = true, shift = false, alt = false, bindings = emptyList()))
    }

    @Test
    fun `a custom binding wins, and what it replaced goes quiet`() {
        assertEquals(Shortcut.COPY, KeyShortcuts.resolve(KeyEvent.KEYCODE_Y, ctrl = true, shift = true, alt = false, bindings = custom))
        assertEquals(Shortcut.SEARCH, KeyShortcuts.resolve(KeyEvent.KEYCODE_0, ctrl = true, shift = false, alt = false, bindings = custom))
        // The list is the whole truth rather than an overlay, so Ctrl+Shift+C is nobody's now.
        assertNull(KeyShortcuts.resolve(KeyEvent.KEYCODE_C, ctrl = true, shift = true, alt = false, bindings = custom))
    }

    @Test
    fun `the built-in set survives a round trip through the bindings`() {
        for (case in KeyShortcuts.defaults) {
            assertEquals(
                "${case.action} lost its chord",
                KeyShortcuts.resolve(case.keyCode, ctrl = true, shift = case.shift, alt = false),
                KeyShortcuts.resolve(case.keyCode, ctrl = true, shift = case.shift, alt = false, bindings = KeyShortcuts.defaults),
            )
        }
    }

    @Test
    fun `the shell keeps its control keys whatever is bound`() {
        // What the editor refuses, resolve refuses too: a binding can arrive
        // from a restored backup that never saw the editor's answer.
        val reckless = listOf(
            KeyBinding(Shortcut.COPY.name, KeyEvent.KEYCODE_C),
            KeyBinding(Shortcut.CLOSE_SESSION.name, KeyEvent.KEYCODE_D),
            KeyBinding(Shortcut.SEARCH.name, KeyEvent.KEYCODE_L),
            KeyBinding(Shortcut.PASTE.name, KeyEvent.KEYCODE_V, ctrl = false, shift = true),
        )
        assertNull(KeyShortcuts.resolve(KeyEvent.KEYCODE_C, ctrl = true, shift = false, alt = false, bindings = reckless))
        assertNull(KeyShortcuts.resolve(KeyEvent.KEYCODE_D, ctrl = true, shift = false, alt = false, bindings = reckless))
        assertNull(KeyShortcuts.resolve(KeyEvent.KEYCODE_L, ctrl = true, shift = false, alt = false, bindings = reckless))
        assertNull(KeyShortcuts.resolve(KeyEvent.KEYCODE_V, ctrl = false, shift = true, alt = false, bindings = reckless))
    }

    @Test
    fun `a bound chord still needs its own modifiers`() {
        assertNull(KeyShortcuts.resolve(KeyEvent.KEYCODE_Y, ctrl = true, shift = false, alt = false, bindings = custom))
        assertNull(KeyShortcuts.resolve(KeyEvent.KEYCODE_Y, ctrl = false, shift = true, alt = false, bindings = custom))
        assertNull(KeyShortcuts.resolve(KeyEvent.KEYCODE_Y, ctrl = true, shift = true, alt = true, bindings = custom))
    }

    @Test
    fun `an action nothing binds any more answers to nothing`() {
        assertNull(KeyShortcuts.resolve(KeyEvent.KEYCODE_V, ctrl = true, shift = true, alt = false, bindings = custom))
        // The placeholder a cleared row leaves behind is not a chord.
        val cleared = listOf(KeyBinding(Shortcut.PASTE.name, KeyEvent.KEYCODE_UNKNOWN))
        assertNull(KeyShortcuts.resolve(KeyEvent.KEYCODE_UNKNOWN, ctrl = true, shift = true, alt = false, bindings = cleared))
        assertNull(KeyShortcuts.binding(cleared, Shortcut.PASTE))
    }

    @Test
    fun `an action whose name means nothing is ignored`() {
        val stale = listOf(KeyBinding("SPLIT_PANE_SIDEWAYS", KeyEvent.KEYCODE_Y, shift = true))
        assertNull(KeyShortcuts.resolve(KeyEvent.KEYCODE_Y, ctrl = true, shift = true, alt = false, bindings = stale))
    }

    // ---- what the editor refuses ---------------------------------------------

    @Test
    fun `the editor takes every chord the built-in set uses`() {
        for (case in KeyShortcuts.defaults) {
            assertNull("${KeyShortcuts.describe(case)} was refused", KeyShortcuts.problem(case))
        }
    }

    @Test
    fun `the editor refuses a chord the terminal needs`() {
        // Ctrl and a letter alone is Ctrl+C, Ctrl+D, Ctrl+L: the shell's.
        assertNotNull(KeyShortcuts.problem(KeyBinding(Shortcut.COPY.name, KeyEvent.KEYCODE_C)))
        assertNotNull(KeyShortcuts.problem(KeyBinding(Shortcut.COPY.name, KeyEvent.KEYCODE_L)))
        // Punctuation without Shift carries a control character just as well.
        assertNotNull(KeyShortcuts.problem(KeyBinding(Shortcut.COPY.name, KeyEvent.KEYCODE_LEFT_BRACKET)))
        // No Ctrl at all is ordinary typing.
        assertNotNull(KeyShortcuts.problem(KeyBinding(Shortcut.COPY.name, KeyEvent.KEYCODE_C, ctrl = false, shift = true)))
        // Alt is the shell's meta prefix.
        assertNotNull(KeyShortcuts.problem(KeyBinding(Shortcut.COPY.name, KeyEvent.KEYCODE_C, shift = true, alt = true)))
        // Nothing has been pressed yet.
        assertNotNull(KeyShortcuts.problem(KeyBinding(Shortcut.COPY.name, KeyEvent.KEYCODE_UNKNOWN, shift = true)))
    }

    @Test
    fun `the editor takes Ctrl+Shift, and the two plain chords already claimed`() {
        assertNull(KeyShortcuts.problem(KeyBinding(Shortcut.COPY.name, KeyEvent.KEYCODE_Y, shift = true)))
        assertNull(KeyShortcuts.problem(KeyBinding(Shortcut.NEXT_TAB.name, KeyEvent.KEYCODE_TAB)))
        assertNull(KeyShortcuts.problem(KeyBinding(Shortcut.FONT_RESET.name, KeyEvent.KEYCODE_0)))
    }

    @Test
    fun `a refusal says why in a sentence`() {
        val why = KeyShortcuts.problem(KeyBinding(Shortcut.COPY.name, KeyEvent.KEYCODE_C))
        assertNotNull(why)
        assertTrue("not a sentence: $why", why!!.endsWith(".") && why.length > 20)
    }

    @Test
    fun `a chord is written the way the keyboard has it printed`() {
        assertEquals("Ctrl+Shift+C", KeyShortcuts.describe(KeyBinding(Shortcut.COPY.name, KeyEvent.KEYCODE_C, shift = true)))
        assertEquals("Ctrl+0", KeyShortcuts.describe(KeyBinding(Shortcut.FONT_RESET.name, KeyEvent.KEYCODE_0)))
    }
}
