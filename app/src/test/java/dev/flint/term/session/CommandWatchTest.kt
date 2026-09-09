package dev.flint.term.session

import dev.flint.term.core.PromptKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandWatchTest {
    private var clock = 0L
    private fun watch(minMillis: Long = 30_000) = CommandWatch(minMillis) { clock }

    /** The prompt, then the newline the shell echoes when Enter is pressed. */
    private fun startCommand(w: CommandWatch, prompt: String = "pilif@box:~$ ") {
        w.onOutput(prompt)
        w.onOutput("make -j8\n")
    }

    @Test
    fun `a prompt is recognized by its last character and a space`() {
        assertTrue(CommandWatch.looksLikePrompt("pilif@box:~$ "))
        assertTrue(CommandWatch.looksLikePrompt("root@box:/# "))
        assertTrue(CommandWatch.looksLikePrompt("~ ❯ "))
        assertTrue(CommandWatch.looksLikePrompt("→ ~ "))
        assertFalse(CommandWatch.looksLikePrompt("pilif@box:~$"))
        assertFalse(CommandWatch.looksLikePrompt("total 48 "))
        assertFalse(CommandWatch.looksLikePrompt("compiling 50% of the crates in this workspace, hold on a moment please "))
        assertFalse(CommandWatch.looksLikePrompt(""))
    }

    @Test
    fun `a slow command reports when the prompt returns`() {
        val w = watch()
        startCommand(w)
        clock += 45_000
        assertNull(w.onOutput("cc main.c\n"))
        val done = w.onOutput("pilif@box:~$ ")
        assertNotNull(done)
        assertTrue(done!!.elapsedMillis >= 45_000)
    }

    @Test
    fun `a quick command says nothing`() {
        val w = watch()
        startCommand(w)
        clock += 200
        assertNull(w.onOutput("hello\npilif@box:~$ "))
    }

    @Test
    fun `output with no command behind it never reports`() {
        val w = watch()
        clock += 60_000
        assertNull(w.onOutput("a stray line\npilif@box:~$ "))
    }

    @Test
    fun `typing slowly does not start the clock`() {
        val w = watch()
        w.onOutput("pilif@box:~$ ")
        // Every keystroke is echoed; the command has not been sent yet.
        w.onOutput("m"); clock += 20_000
        w.onOutput("ake"); clock += 20_000
        w.onOutput("\n")
        clock += 1_000
        assertNull(w.onOutput("pilif@box:~$ "))
    }

    @Test
    fun `one command reports once`() {
        val w = watch()
        startCommand(w)
        clock += 60_000
        assertNotNull(w.onOutput("pilif@box:~$ "))
        assertNull(w.onOutput("pilif@box:~$ "))
    }

    @Test
    fun `a prompt split across two chunks is still seen`() {
        val w = watch()
        startCommand(w)
        clock += 60_000
        assertNull(w.onOutput("pilif@box"))
        assertNotNull(w.onOutput(":~$ "))
    }

    @Test
    fun `a reset drops the command being watched`() {
        val w = watch()
        startCommand(w)
        clock += 60_000
        w.reset()
        assertNull(w.onOutput("pilif@box:~$ "))
    }

    // ---- shells that mark their prompts (OSC 133) --------------------------

    @Test
    fun `marks report the real duration and the real status`() {
        val w = watch()
        w.onMark(PromptKind.PROMPT_START, null)
        w.onMark(PromptKind.COMMAND_START, null)
        assertNull(w.onMark(PromptKind.OUTPUT_START, null))
        clock += 41_000
        val done = w.onMark(PromptKind.FINISHED, 2)
        assertNotNull(done)
        assertEquals(41_000L, done!!.elapsedMillis)
        assertEquals(2, done.exitStatus)
    }

    @Test
    fun `a marked run that ends without a status does not claim one`() {
        val w = watch()
        w.onMark(PromptKind.OUTPUT_START, null)
        clock += 45_000
        val done = w.onMark(PromptKind.FINISHED, null)
        assertNotNull(done)
        assertNull(done!!.exitStatus)
    }

    @Test
    fun `a marked quick command still says nothing`() {
        val w = watch()
        w.onMark(PromptKind.OUTPUT_START, null)
        clock += 900
        assertNull(w.onMark(PromptKind.FINISHED, 0))
    }

    @Test
    fun `the prompt heuristic stands down once a mark has been seen`() {
        val w = watch()
        w.onMark(PromptKind.PROMPT_START, null)
        // The same output that would have started and ended a guessed command.
        startCommand(w)
        clock += 60_000
        assertNull(w.onOutput("pilif@box:~$ "))
    }

    @Test
    fun `a mark arriving mid-guess drops the guess`() {
        val w = watch()
        startCommand(w)
        clock += 60_000
        // The shell only marked the next prompt; the command it interrupted
        // was never timed properly, so nothing is reported for it.
        assertNull(w.onMark(PromptKind.PROMPT_START, null))
        assertNull(w.onOutput("pilif@box:~$ "))
    }

    @Test
    fun `a prompt drawn before the command ended reports nothing`() {
        val w = watch()
        w.onMark(PromptKind.OUTPUT_START, null)
        clock += 60_000
        // Ctrl-C: the shell draws the next prompt without a D mark.
        assertNull(w.onMark(PromptKind.PROMPT_START, null))
        assertNull(w.onMark(PromptKind.FINISHED, 130))
    }

    @Test
    fun `a shell without marks keeps the heuristic`() {
        val w = watch()
        startCommand(w)
        clock += 45_000
        val done = w.onOutput("pilif@box:~$ ")
        assertNotNull(done)
        assertTrue(done!!.elapsedMillis >= 45_000)
        assertNull(done.exitStatus)
    }
}
