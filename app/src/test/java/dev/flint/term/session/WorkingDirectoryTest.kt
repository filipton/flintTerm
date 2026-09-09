package dev.flint.term.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkingDirectoryTest {
    @Test
    fun `a directory under the home directory starts at a tilde`() {
        assertEquals("~/projects/foo", WorkingDirectory.shorten("/home/ada/projects/foo", "/home/ada"))
        assertEquals("~", WorkingDirectory.shorten("/home/ada", "/home/ada"))
        assertEquals("~", WorkingDirectory.shorten("/home/ada/", "/home/ada/"))
    }

    @Test
    fun `a directory outside the home directory is shown whole`() {
        assertEquals("/var/log", WorkingDirectory.shorten("/var/log", "/home/ada"))
        // A home directory that is a prefix of the name, not of the path.
        assertEquals("/home/adam/src", WorkingDirectory.shorten("/home/adam/src", "/home/ada"))
        assertEquals("/etc", WorkingDirectory.shorten("/etc"))
        assertEquals("/", WorkingDirectory.shorten("/"))
        // Root as a home directory would turn every path into a tilde.
        assertEquals("/etc", WorkingDirectory.shorten("/etc", "/"))
    }

    @Test
    fun `nothing to show is nothing to show`() {
        assertNull(WorkingDirectory.shorten(null))
        assertNull(WorkingDirectory.shorten(""))
        assertNull(WorkingDirectory.shorten("   "))
    }

    @Test
    fun `a path from a hostile machine cannot push the strip off the screen`() {
        val long = "/home/ada/" + "d".repeat(4000)
        val shown = WorkingDirectory.shorten(long, "/home/ada")!!
        assertTrue(shown.length <= 120)
        assertTrue(shown.startsWith("…"))
        // The end of the path is what a truncation must keep.
        assertTrue(long.endsWith(shown.removePrefix("…")))
    }

    @Test
    fun `control characters never reach the layout`() {
        assertEquals("/tmp/ab", WorkingDirectory.shorten("/tmp/a\nb"))
        assertEquals("/tmp/[31mx", WorkingDirectory.shorten("/tmp/\u001B[31mx"))
    }

    @Test
    fun `a path with shell metacharacters reaches the shell as one word`() {
        assertEquals("cd '/tmp/a b'", WorkingDirectory.cdCommand("/tmp/a b"))
        assertEquals("cd '/tmp/\$(reboot)'", WorkingDirectory.cdCommand("/tmp/\$(reboot)"))
        assertEquals("cd '/tmp/x;rm -rf ~'", WorkingDirectory.cdCommand("/tmp/x;rm -rf ~"))
        assertEquals("cd '/tmp/`id`'", WorkingDirectory.cdCommand("/tmp/`id`"))
        assertEquals("""cd '/tmp/it'\''s'""", WorkingDirectory.cdCommand("/tmp/it's"))
    }

    @Test
    fun `a path that cannot be typed safely is not offered`() {
        assertTrue(WorkingDirectory.isUsable("/tmp/a b"))
        assertTrue(WorkingDirectory.isUsable("/tmp/it's"))
        assertFalse(WorkingDirectory.isUsable(null))
        assertFalse(WorkingDirectory.isUsable("relative"))
        // The line would end here and the rest would be run as a command.
        assertFalse(WorkingDirectory.isUsable("/tmp/x\nreboot"))
        assertFalse(WorkingDirectory.isUsable("/" + "d".repeat(5000)))
    }
}
