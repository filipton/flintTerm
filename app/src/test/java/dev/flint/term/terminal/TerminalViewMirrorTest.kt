package dev.flint.term.terminal

import dev.flint.term.core.KeyCode
import dev.flint.term.core.KeyEventKind
import dev.flint.term.core.KeyPress
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalViewMirrorTest {

    /** A session stand-in that only writes down what it was told. */
    private class Recorder : KeyTarget {
        val keys = mutableListOf<KeyPress>()
        val typed = mutableListOf<String>()
        val pasted = mutableListOf<String>()

        override fun key(press: KeyPress) { keys += press }
        override fun text(text: String) { typed += text }
        override fun paste(text: String) { pasted += text }
    }

    private fun enter() = KeyPress(KeyCode.Enter, false, false, false, KeyEventKind.PRESS)

    @Test
    fun `with no mirror, only the session being typed into hears anything`() {
        val here = Recorder()
        val sink = KeySink(primary = here)

        sink.key(enter())
        sink.text("ls")
        sink.paste("a long prompt")

        assertEquals(listOf(enter()), here.keys)
        assertEquals(listOf("ls"), here.typed)
        assertEquals(listOf("a long prompt"), here.pasted)
    }

    @Test
    fun `a mirror gets the keys, the text and the pastes`() {
        val here = Recorder()
        val there = Recorder()
        val sink = KeySink(primary = here, mirror = there)

        sink.key(enter())
        sink.text("ls")
        sink.paste("a long prompt")

        assertEquals(here.keys, there.keys)
        assertEquals(here.typed, there.typed)
        assertEquals(here.pasted, there.pasted)
    }

    @Test
    fun `dropping the mirror stops the copies without disturbing the session`() {
        val here = Recorder()
        val there = Recorder()
        val sink = KeySink(primary = here, mirror = there)

        sink.text("shared")
        sink.mirror = null
        sink.text("mine")

        assertEquals(listOf("shared", "mine"), here.typed)
        assertEquals(listOf("shared"), there.typed)
    }

    @Test
    fun `a session that has gone does not take the mirror with it`() {
        val there = Recorder()
        // What a reconnect looks like from here: the primary is briefly nothing.
        val sink = KeySink(primary = null, mirror = there)

        sink.key(enter())
        sink.text("still typing")

        assertEquals(listOf(enter()), there.keys)
        assertEquals(listOf("still typing"), there.typed)
    }
}
