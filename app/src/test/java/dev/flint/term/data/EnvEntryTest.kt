package dev.flint.term.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which of a host's environment variables reach the wire. The editor lets a row
 * be added before it is filled in, and the same name can be typed twice, so the
 * list is tidied on the way out rather than policed on the way in.
 */
class EnvEntryTest {

    @Test
    fun `an unnamed variable is never asked for`() {
        val entries = listOf(EnvEntry("", "orphan"), EnvEntry("   ", "also orphan"), EnvEntry("LANG", "en_GB.UTF-8"))
        assertEquals(listOf(EnvEntry("LANG", "en_GB.UTF-8")), entries.requestable())
    }

    @Test
    fun `a padded name is trimmed rather than sent with its spaces`() {
        assertEquals(listOf(EnvEntry("LC_TERMINAL", "flintTerm")), listOf(EnvEntry(" LC_TERMINAL ", "flintTerm")).requestable())
    }

    /** An empty value is a legitimate thing to ask for; an empty name is not. */
    @Test
    fun `an empty value is kept`() {
        assertEquals(listOf(EnvEntry("LC_TERMINAL", "")), listOf(EnvEntry("LC_TERMINAL", "")).requestable())
    }

    @Test
    fun `a repeated name keeps its first position and its last value`() {
        val entries = listOf(EnvEntry("LANG", "C"), EnvEntry("LC_ALL", "C"), EnvEntry("LANG", "en_GB.UTF-8"))
        assertEquals(listOf(EnvEntry("LANG", "en_GB.UTF-8"), EnvEntry("LC_ALL", "C")), entries.requestable())
    }

    @Test
    fun `a host asks for nothing by default`() {
        assertTrue(Host().env.requestable().isEmpty())
    }
}
