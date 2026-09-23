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
            SessionLog.castHeader(cols = 123, rows = 45, startedAt = 0) + "\n" +
                (1..3000).joinToString("\n") { SessionLog.castEvent(it * 10L, "frame $it") } + "\n",
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
        val cast = SessionLog.castHeader(80, 24, 0) + "\n" +
            SessionLog.castEvent(0, "before\n") + "\n" +
            "[not a frame\n" +
            SessionLog.castEvent(20, "after\n") + "\n"
        val read = SessionLog.parseCast(cast)
        assertEquals(listOf("before\n", "after\n"), read.frames.map { it.text })
    }

    @Test
    fun `a character split between chunks comes out whole`() {
        val bytes = "é─ok😀".toByteArray()
        val utf8 = Utf8Chunks()
        // Every cut, including through the four bytes of the emoji.
        for (cut in 1 until bytes.size) {
            val out = utf8.decode(bytes.copyOfRange(0, cut)) + utf8.decode(bytes.copyOfRange(cut, bytes.size))
            assertEquals("é─ok😀", out)
        }
        assertEquals("", utf8.decode(byteArrayOf(0xE2.toByte())))
        assertEquals("─", utf8.decode(byteArrayOf(0x94.toByte(), 0x80.toByte())))
    }

    @Test
    fun `a byte that can never start a character is replaced, not held`() {
        assertEquals("a�b", Utf8Chunks().decode(byteArrayOf(0x61, 0xFF.toByte(), 0x62)))
    }

    @Test
    fun `a recording fed bytes writes them whole and closes`() {
        val dir = java.nio.file.Files.createTempDirectory("rec").toFile().apply { deleteOnExit() }
        val rec = SessionRecording(dir, "host", dev.flint.term.data.RecordingFormat.BOTH, 80, 24)
        val stream = "${esc}[1mé${esc}[0m\n"
        val bytes = stream.toByteArray()
        // Cut through the middle of the é.
        rec.append(bytes.copyOfRange(0, 6))
        rec.append(bytes.copyOfRange(6, bytes.size))
        rec.finish()
        val log = rec.files.first { it.name.endsWith(".log") }
        val cast = rec.files.first { it.name.endsWith(".cast") }
        // Closing the files is what writes them out, and that happens on the
        // recording's own thread once it has taken the last chunk.
        val deadline = System.currentTimeMillis() + 5_000
        while ((log.length() == 0L || !cast.readText().contains('é')) && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals("é\n", log.readText())
        assertEquals(stream, SessionLog.parseCast(cast.readText()).frames.joinToString("") { it.text })
    }
}
