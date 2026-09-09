package dev.flint.term.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class ModifierStateTest {

    private var now = 1_000L
    private val mods = ModifierState { now }

    @Test
    fun `a single tap sticks for one key`() {
        mods.tap('c')
        assertEquals(ModState.ONCE, mods.get('c'))
        mods.consumeOnce()
        assertEquals(ModState.OFF, mods.get('c'))
    }

    @Test
    fun `a second tap in the window locks it`() {
        mods.tap('c')
        now += 399
        mods.tap('c')
        assertEquals(ModState.LOCKED, mods.get('c'))
        // A locked modifier survives the keys it is used on.
        mods.consumeOnce()
        assertEquals(ModState.LOCKED, mods.get('c'))
    }

    @Test
    fun `a slow second tap still clears`() {
        mods.tap('c')
        now += 401
        mods.tap('c')
        assertEquals(ModState.OFF, mods.get('c'))
    }

    @Test
    fun `a tap while locked clears, however quick`() {
        mods.tap('a')
        now += 100
        mods.tap('a')
        assertEquals(ModState.LOCKED, mods.get('a'))
        now += 10
        mods.tap('a')
        assertEquals(ModState.OFF, mods.get('a'))
    }

    @Test
    fun `the window belongs to the modifier that was tapped`() {
        mods.tap('c')
        now += 50
        mods.tap('s')
        now += 50
        // Shift's own second tap locks; ctrl was never tapped twice.
        mods.tap('s')
        assertEquals(ModState.LOCKED, mods.get('s'))
        assertEquals(ModState.ONCE, mods.get('c'))
    }

    @Test
    fun `the window reopens after the modifier was spent`() {
        mods.tap('c')
        mods.consumeOnce()
        now += 10
        mods.tap('c')
        assertEquals(ModState.ONCE, mods.get('c'))
    }

    @Test
    fun `switched off, the second tap clears as it always did`() {
        mods.doubleTapLocks = false
        mods.tap('c')
        now += 10
        mods.tap('c')
        assertEquals(ModState.OFF, mods.get('c'))
    }

    @Test
    fun `a long press locks, and the next one lets go`() {
        mods.lock('c')
        assertEquals(ModState.LOCKED, mods.get('c'))
        mods.lock('c')
        assertEquals(ModState.OFF, mods.get('c'))
        // Locking straight from ONCE does not leave a lock-again window behind.
        mods.tap('c')
        mods.lock('c')
        assertEquals(ModState.LOCKED, mods.get('c'))
        now += 10
        mods.tap('c')
        assertEquals(ModState.OFF, mods.get('c'))
    }
}
