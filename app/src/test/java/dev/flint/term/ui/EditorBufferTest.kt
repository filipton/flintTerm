package dev.flint.term.ui

import dev.flint.term.terminal.Highlighter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorBufferTest {

    private fun buffer(path: String, saved: String = "") =
        EditorBuffer(path, saved, Highlighter.indentFor(path.substringAfterLast('/')))

    @Test
    fun `tab inserts two spaces, or a real tab where the file demands one`() {
        assertEquals("  ", buffer("/etc/nginx/nginx.conf").indent)
        assertEquals("  ", buffer("/srv/app/main.py").indent)
        assertEquals("\t", buffer("/srv/app/main.go").indent)
        assertEquals("\t", buffer("/srv/app/Makefile").indent)
    }

    @Test
    fun `tab lands at the caret and takes the selection with it`() {
        val py = buffer("/srv/app/main.py", "def f():\nreturn 1\n")
        val typed = py.tab("def f():\nreturn 1\n", 9, 9)
        assertEquals("def f():\n  return 1\n", typed.text)
        assertEquals(11, typed.caret)

        val go = buffer("/srv/app/main.go")
        assertEquals("\tx", go.tab("REPLACEx", 0, 7).text)
        assertEquals(1, go.tab("REPLACEx", 0, 7).caret)
        // A selection given backwards is the same selection.
        assertEquals("\tx", go.tab("REPLACEx", 7, 0).text)
    }

    @Test
    fun `the dirty flag follows the text, and a save clears it`() {
        val original = "listen 80;\n"
        val buffer = buffer("/etc/nginx/nginx.conf", original)
        assertFalse(buffer.isDirty(original))
        assertTrue(buffer.isDirty("listen 443;\n"))

        val saved = buffer.withSaved("listen 443;\n")
        assertFalse(saved.isDirty("listen 443;\n"))
        assertTrue(saved.isDirty(original))
        assertEquals(buffer.path, saved.path)
        assertEquals(buffer.indent, saved.indent)
    }

    @Test
    fun `the backup sits beside the file, keeping its name`() {
        assertEquals("/etc/nginx/nginx.conf.bak", buffer("/etc/nginx/nginx.conf").backupPath)
        assertEquals("/home/pi/.bashrc.bak", buffer("/home/pi/.bashrc").backupPath)
    }
}
