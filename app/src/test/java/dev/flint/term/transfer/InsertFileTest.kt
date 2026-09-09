package dev.flint.term.transfer

import org.junit.Assert.assertEquals
import org.junit.Test

class InsertFileTest {
    @Test
    fun `a plain path is typed as it is`() {
        assertEquals("/tmp/notes.txt", InsertFile.quote("/tmp/notes.txt"))
        assertEquals("/tmp/IMG_2026-09-08.png", InsertFile.quote("/tmp/IMG_2026-09-08.png"))
    }

    @Test
    fun `a name a shell would misread is quoted`() {
        assertEquals("'/tmp/my photo.png'", InsertFile.quote("/tmp/my photo.png"))
        assertEquals("'/tmp/report (final).pdf'", InsertFile.quote("/tmp/report (final).pdf"))
        assertEquals("'/tmp/rm -rf *'", InsertFile.quote("/tmp/rm -rf *"))
    }

    @Test
    fun `a quote in the name cannot end the quoting`() {
        assertEquals("""'/tmp/it'\''s here'""", InsertFile.quote("/tmp/it's here"))
    }
}
