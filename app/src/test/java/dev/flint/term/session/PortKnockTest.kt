package dev.flint.term.session

import dev.flint.term.data.Host
import dev.flint.term.data.KnockSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PortKnockTest {
    @Test
    fun `reads a sequence the way knockd writes one`() {
        assertEquals(
            listOf(KnockStep(7000), KnockStep(8000, udp = true), KnockStep(9000)),
            PortKnock.parse("7000, 8000/udp, 9000"),
        )
    }

    @Test
    fun `takes spaces and tabs as separators too`() {
        assertEquals(listOf(KnockStep(1), KnockStep(2), KnockStep(3)), PortKnock.parse(" 1\t2\n3 "))
    }

    @Test
    fun `tcp is the default and can also be said out loud`() {
        assertEquals(listOf(KnockStep(22), KnockStep(23)), PortKnock.parse("22/tcp, 23"))
        assertEquals(listOf(KnockStep(22, udp = true)), PortKnock.parse("22/UDP"))
    }

    @Test
    fun `a repeated port is kept, because the server is listening for it twice`() {
        assertEquals(listOf(KnockStep(7000), KnockStep(7000)), PortKnock.parse("7000, 7000"))
    }

    @Test
    fun `anything that is not a port is left out rather than guessed at`() {
        assertEquals(emptyList<KnockStep>(), PortKnock.parse("http, 0, 65536, -1, 80x, 100/sctp, 10-20"))
    }

    @Test
    fun `says which token is the problem`() {
        assertEquals("Add at least one port", PortKnock.problem("   "))
        assertEquals("“70000” is not a port between 1 and 65535", PortKnock.problem("7000, 70000"))
        assertNull(PortKnock.problem("7000, 8000/udp"))
    }

    @Test
    fun `writes a sequence back the way it is sent`() {
        assertEquals("7000, 8000/udp", PortKnock.format(PortKnock.parse("7000  8000/UDP")))
    }

    @Test
    fun `there is nothing to send without a sequence, a host or the switch`() {
        val host = Host(hostname = "server", knock = KnockSettings(enabled = true, sequence = "7000"))
        assertNull(PortKnock.plan(host.copy(knock = host.knock.copy(enabled = false))))
        assertNull(PortKnock.plan(host.copy(knock = host.knock.copy(sequence = "nothing"))))
        assertNull(PortKnock.plan(host.copy(hostname = "  ")))
        assertEquals(listOf(KnockStep(7000)), PortKnock.plan(host)?.steps)
    }

    @Test
    fun `the waits are held to something a connection can sit through`() {
        val host = Host(
            hostname = "server",
            knock = KnockSettings(enabled = true, sequence = "1", delayMs = 99_000, pauseMs = -5),
        )
        val plan = PortKnock.plan(host)!!
        assertEquals(10_000, plan.delayMs)
        assertEquals(0, plan.pauseMs)
    }
}
