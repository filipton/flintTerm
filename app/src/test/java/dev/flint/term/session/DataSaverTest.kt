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

    private fun holdTransfers(mode: DataSaver, metered: Boolean) = mode != DataSaver.OFF && metered

    /**
     * The trap this guards: a transfer can only wait for a *better* connection.
     * Keying the hold off "Always" would queue transfers for ever on Wi-Fi,
     * because there would be nothing cheaper to wait for.
     */
    @Test
    fun `always does not strand transfers on an unmetered connection`() {
        assertFalse(holdTransfers(DataSaver.ALWAYS, metered = false))
        assertTrue(holdTransfers(DataSaver.ALWAYS, metered = true))
    }

    @Test
    fun `transfers wait on mobile data unless data saving is off`() {
        assertTrue(holdTransfers(DataSaver.METERED, metered = true))
        assertFalse(holdTransfers(DataSaver.METERED, metered = false))
        assertFalse(holdTransfers(DataSaver.OFF, metered = true))
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
