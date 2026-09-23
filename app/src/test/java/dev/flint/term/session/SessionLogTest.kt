package dev.flint.term.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLogTest {
    // Lines as the core writes them (see `recording.rs`, whose tests pin these
    // same bytes), so reading back is checked against the real format.
    private fun header(cols: Int, rows: Int, startedAt: Long) =
        "{\"version\":2,\"width\":$cols,\"height\":$rows,\"timestamp\":${startedAt / 1000}," +
            "\"env\":{\"TERM\":\"xterm-256color\"}}"

    private fun event(ms: Long, json: String) =
        "[" + String.format(java.util.Locale.US, "%.6f", ms / 1000.0) + ",\"o\",\"" + json + "\"]"

    @Test
    fun `file names carry the host and the moment, and nothing a path would mind`() {
        val name = SessionLog.fileName("pi@raspberrypi:2222", 0, "log")
        assertTrue(name, name.matches(Regex("""pi-raspberrypi-2222-\d{8}-\d{6}\.log""")))
        assertTrue(SessionLog.fileName("../..", 0, "cast").startsWith("session-"))
    }

    @Test
    fun `a cast reads back as the frames that were written`() {
        val cast = header(cols = 100, rows = 30, startedAt = 1_700_000_000_000) + "\n" +
            event(0, "hello\\r\\n") + "\n" +
            event(1500, "\\u001b[31mred\\u001b[0m") + "\n"
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
    fun `a file smaller than the cap comes back whole`() {
        val f = java.io.File.createTempFile("rec", ".log").apply { deleteOnExit() }
        f.writeText("one\ntwo\nthree\n")
        val tail = SessionLog.tail(f, 1024)
        assertEquals("one\ntwo\nthree\n", tail.text)
        assertTrue(!tail.truncated)
    }

    @Test
    fun `a file over the cap comes back as whole lines from the end`() {
        val f = java.io.File.createTempFile("rec", ".log").apply { deleteOnExit() }
        f.writeText((1..2000).joinToString("\n") { "line $it" } + "\n")
        val tail = SessionLog.tail(f, 200)
        assertTrue(tail.truncated)
        assertTrue(tail.text.length <= 200)
        // No half a line at the front, and the very end is still there.
        assertTrue(tail.text.startsWith("line "))
        assertTrue(tail.text.endsWith("line 2000\n"))
    }

    @Test
    fun `a multi-byte character is never cut in half`() {
        val f = java.io.File.createTempFile("rec", ".log").apply { deleteOnExit() }
        // Four bytes a line, so a byte-aligned cut lands inside a character.
        f.writeText((1..500).joinToString("\n") { "☃☃" } + "\n")
        val tail = SessionLog.tail(f, 101)
        assertTrue(tail.truncated)
        assertTrue(!tail.text.contains('\uFFFD'))
    }

    @Test
    fun `a huge cast keeps its header so the grid is still known`() {
        val f = java.io.File.createTempFile("rec", ".cast").apply { deleteOnExit() }
        f.writeText(
            header(cols = 123, rows = 45, startedAt = 0) + "\n" +
                (1..3000).joinToString("\n") { event(it * 10L, "frame $it") } + "\n",
        )
        val tail = SessionLog.castTail(f, 500)
        assertTrue(tail.truncated)
        val read = SessionLog.parseCast(tail.text)
        assertEquals(123, read.cols)
        assertEquals(45, read.rows)
        assertTrue(read.frames.isNotEmpty())
        // The end of the recording, not the start of it.
        assertTrue(read.frames.last().text == "frame 3000")
    }

    @Test
    fun `a broken line costs only itself`() {
        val cast = header(80, 24, 0) + "\n" +
            event(0, "before\\n") + "\n" +
            "[not a frame\n" +
            event(20, "after\\n") + "\n"
        val read = SessionLog.parseCast(cast)
        assertEquals(listOf("before\n", "after\n"), read.frames.map { it.text })
    }

    @Test
    fun `a recording names one file per format, both after the same moment`() {
        val dir = java.io.File("rec")
        val both = SessionRecording(dir, "pi@box", dev.flint.term.data.RecordingFormat.BOTH, startedAt = 0)
        assertEquals(listOf(both.textFile, both.castFile), both.files)
        assertEquals(both.textFile!!.name.removeSuffix(".log"), both.castFile!!.name.removeSuffix(".cast"))
        val text = SessionRecording(dir, "pi@box", dev.flint.term.data.RecordingFormat.TEXT, startedAt = 0)
        assertEquals(null, text.castFile)
        assertEquals(listOf(text.textFile), text.files)
    }
}
