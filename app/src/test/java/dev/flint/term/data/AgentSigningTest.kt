package dev.flint.term.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSigningTest {
    private val plain = Host(label = "web", forwardAgent = false)
    private val forwarding = Host(label = "web", forwardAgent = true)

    @Test
    fun `a host that forwards nothing is never asked about`() {
        assertFalse(asksBeforeAgentSigning(listOf(plain), fallback = true))
    }

    @Test
    fun `a forwarding host follows the setting when it says nothing itself`() {
        assertTrue(asksBeforeAgentSigning(listOf(forwarding), fallback = true))
        assertFalse(asksBeforeAgentSigning(listOf(forwarding), fallback = false))
    }

    @Test
    fun `a host's own answer wins either way`() {
        assertFalse(asksBeforeAgentSigning(listOf(forwarding.copy(askBeforeAgentSigning = false)), fallback = true))
        assertTrue(asksBeforeAgentSigning(listOf(forwarding.copy(askBeforeAgentSigning = true)), fallback = false))
    }

    @Test
    fun `a hop that wants to be asked is asked, whatever the far end says`() {
        // The socket passes through the jump host, so it can use the agent just
        // as the far end can — and the stricter machine on the route decides.
        val hop = forwarding.copy(label = "bastion", askBeforeAgentSigning = true)
        val far = forwarding.copy(askBeforeAgentSigning = false)
        assertTrue(asksBeforeAgentSigning(listOf(far, hop), fallback = false))
    }

    @Test
    fun `a route with nothing forwarded is quiet even with the setting on`() {
        assertFalse(asksBeforeAgentSigning(listOf(plain, plain.copy(label = "bastion")), fallback = true))
    }
}
