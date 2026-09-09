package dev.flint.term.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The order the keystore is asked in.
 *
 * The keystore will not create a key whose terms it cannot honor, so every
 * property has to be something we can fall back from — and what we fall back to
 * has to still be a usable key. That decision is kept as a list so it can be
 * read here rather than on a phone.
 */
class HardwareKeyLadderTest {

    private val newest = 35
    private val oreo = 27 // Below StrongBox and "only while unlocked", both of which arrived in 28.

    @Test
    fun `the best terms are asked for first`() {
        val first = HardwareKeys.ladder(newest, requireAuth = true).first()
        assertEquals(HardwareKeys.Attempt(strongBox = true, whileUnlocked = true, userAuth = true), first)
    }

    @Test
    fun `an ordinary key is always the last resort`() {
        for (requireAuth in listOf(true, false)) {
            val last = HardwareKeys.ladder(newest, requireAuth).last()
            assertEquals(
                "a phone with no biometric and no screen lock must still get a key",
                HardwareKeys.Attempt(strongBox = false, whileUnlocked = false, userAuth = false),
                last,
            )
        }
    }

    /** A fingerprint per signature is the user's own decision, so it goes last. */
    @Test
    fun `asking for a fingerprint is given up after the hardware properties`() {
        val ladder = HardwareKeys.ladder(newest, requireAuth = true)
        val firstWithout = ladder.indexOfFirst { !it.userAuth }
        assertEquals("every attempt with it comes first", 4, firstWithout)
        assertTrue(ladder.take(firstWithout).all { it.userAuth })
        assertTrue(ladder.drop(firstWithout).none { it.userAuth })
    }

    @Test
    fun `nothing is asked for that the platform does not have`() {
        val ladder = HardwareKeys.ladder(oreo, requireAuth = true)
        assertTrue(ladder.none { it.strongBox || it.whileUnlocked })
        // Still both answers to the one question that old platform can answer.
        assertEquals(listOf(true, false), ladder.map { it.userAuth })
    }

    @Test
    fun `a key nobody asked to protect is never bound to a fingerprint`() {
        assertTrue(HardwareKeys.ladder(newest, requireAuth = false).none { it.userAuth })
        assertEquals(4, HardwareKeys.ladder(newest, requireAuth = false).size)
    }

    @Test
    fun `no rung is offered twice`() {
        val ladder = HardwareKeys.ladder(newest, requireAuth = true)
        assertEquals(ladder.size, ladder.toSet().size)
        assertFalse(ladder.isEmpty())
    }
}
