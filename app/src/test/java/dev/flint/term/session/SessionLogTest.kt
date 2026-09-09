package dev.flint.term.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLogTest {
    private val esc = "\u001B"
    private val bel = "\u0007"

    @Test
    fun `plain text comes out as it went in`() {
        val text = "total 12\ndrwxr-xr-x  3 pi pi 4096 Jan  1 10:00 .\n\tindented"
        assertEquals(text, SessionLog.stripEscapes(text))
    }

    @Test
    fun `color and cursor sequences are dropped`() {
        val colored = "${esc}[1;32mpi@raspberrypi${esc}[0m:${esc}[01;34m~${esc}[00m$ "
        assertEquals("pi@raspberrypi:~$ ", SessionLog.stripEscapes(colored))
        assertEquals("done", SessionLog.stripEscapes("${esc}[2J${esc}[Hdone"))
    }

    @Test
    fun `an OSC title goes with either of its terminators`() {
        assertEquals("ls", SessionLog.stripEscapes("$esc]0;pi@raspberrypi: ~$bel" + "ls"))
        assertEquals("ls", SessionLog.stripEscapes("$esc]0;pi@raspberrypi: ~$esc\\ls"))
    }

    @Test
    fun `a charset selection takes its final character with it`() {
        assertEquals("plain", SessionLog.stripEscapes("$esc(Bplain"))
    }

    @Test
    fun `carriage returns and bells leave the text alone`() {
        assertEquals("50%75%\n", SessionLog.stripEscapes("50%\r75%$bel\r\n"))
    }

    @Test
    fun `a cast line quotes what would otherwise break the JSON`() {
        val line = SessionLog.castEvent(1_500, "say \"hi\"\\\r\n\u0001")
        assertEquals("[1.500000,\"o\",\"say \\\"hi\\\"\\\\\\r\\n\\u0001\"]", line)
    }

    @Test
    fun `the elapsed time is the seconds since the recording started`() {
        assertTrue(SessionLog.castEvent(0, "x").startsWith("[0.000000,"))
        assertTrue(SessionLog.castEvent(90_061, "x").startsWith("[90.061000,"))
    }

    @Test
    fun `the header carries the size the recording was made at`() {
        val header = SessionLog.castHeader(cols = 132, rows = 43, startedAt = 1_700_000_123_456, shell = "/bin/zsh")
        assertEquals(
            "{\"version\":2,\"width\":132,\"height\":43,\"timestamp\":1700000123," +
                "\"env\":{\"TERM\":\"xterm-256color\",\"SHELL\":\"/bin/zsh\"}}",
            header,
        )
    }

    @Test
    fun `a header without a known shell claims none`() {
        assertEquals(
            "{\"version\":2,\"width\":80,\"height\":24,\"timestamp\":0,\"env\":{\"TERM\":\"xterm-256color\"}}",
            SessionLog.castHeader(80, 24, 0),
        )
    }

    @Test
    fun `file names carry the host and the moment, and nothing a path would mind`() {
        val name = SessionLog.fileName("pi@raspberrypi:2222", 0, "log")
        assertTrue(name, name.matches(Regex("""pi-raspberrypi-2222-\d{8}-\d{6}\.log""")))
        assertTrue(SessionLog.fileName("../..", 0, "cast").startsWith("session-"))
    }

    @Test
    fun `a cast reads back as the frames that were written`() {
        val cast = SessionLog.castHeader(cols = 100, rows = 30, startedAt = 1_700_000_000_000) + "\n" +
            SessionLog.castEvent(0, "hello\r\n") + "\n" +
            SessionLog.castEvent(1500, "\u001B[31mred\u001B[0m") + "\n"
        val read = SessionLog.parseCast(cast)
        assertEquals(100, read.cols)
        assertEquals(30, read.rows)
        assertEquals(2, read.frames.size)
        assertEquals("hello\r\n", read.frames[0].text)
        assertEquals(1500, read.frames[1].atMillis)
        // The escape sequence has to survive the round trip, or a replay is
        // black and white.
        assertEquals("\u001B[31mred\u001B[0m", read.frames[1].text)
        assertEquals(1500, read.durationMillis)
    }

    @Test
    fun `a broken line costs only itself`() {
        val cast = SessionLog.castHeader(80, 24, 0) + "\n" +
            SessionLog.castEvent(0, "before\n") + "\n" +
            "[not a frame\n" +
            SessionLog.castEvent(20, "after\n") + "\n"
        val read = SessionLog.parseCast(cast)
        assertEquals(listOf("before\n", "after\n"), read.frames.map { it.text })
    }
}
