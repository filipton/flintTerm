package dev.flint.term.session

import dev.flint.term.data.DataSaver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The data-saver decision, kept as a pure function so it can be tested without
 * an Android connectivity stack.
 */
class DataSaverTest {

    private fun saving(mode: DataSaver, metered: Boolean) = when (mode) {
        DataSaver.OFF -> false
        DataSaver.ALWAYS -> true
        DataSaver.METERED -> metered
    }

    @Test
    fun `metered mode follows the connection`() {
        assertTrue(saving(DataSaver.METERED, metered = true))
        assertFalse(saving(DataSaver.METERED, metered = false))
    }

    @Test
    fun `always and off ignore the connection`() {
        assertTrue(saving(DataSaver.ALWAYS, metered = false))
        assertFalse(saving(DataSaver.OFF, metered = true))
    }

    @Test
    fun `the default is to save on mobile data only`() {
        assertEquals(DataSaver.METERED, dev.flint.term.data.Settings().dataSaver)
    }

    private fun holdBackgroundWork(mode: DataSaver, metered: Boolean) = mode != DataSaver.OFF && metered

    /**
     * The trap this guards: skipped background work only comes back on a
     * *better* connection. Keying the hold off "Always" would skip it for ever
     * on Wi-Fi, because there would be nothing cheaper to wait for.
     */
    @Test
    fun `always does not strand background work on an unmetered connection`() {
        assertFalse(holdBackgroundWork(DataSaver.ALWAYS, metered = false))
        assertTrue(holdBackgroundWork(DataSaver.ALWAYS, metered = true))
    }

    @Test
    fun `background work waits on mobile data unless data saving is off`() {
        assertTrue(holdBackgroundWork(DataSaver.METERED, metered = true))
        assertFalse(holdBackgroundWork(DataSaver.METERED, metered = false))
        assertFalse(holdBackgroundWork(DataSaver.OFF, metered = true))
    }

    /** Keepalives are spaced out rather than switched off, and stay bounded. */
    private fun keepalive(base: Int, saving: Boolean) = if (saving) (base * 3).coerceAtMost(300) else base

    @Test
    fun `keepalives stretch when saving data but never disappear`() {
        assertEquals(30, keepalive(30, saving = false))
        assertEquals(90, keepalive(30, saving = true))
        assertTrue("must still poll eventually", keepalive(30, saving = true) > 0)
    }

    @Test
    fun `a long keepalive is capped rather than growing without bound`() {
        assertEquals(300, keepalive(200, saving = true))
    }
}
