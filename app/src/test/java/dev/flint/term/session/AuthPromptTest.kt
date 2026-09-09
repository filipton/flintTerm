package dev.flint.term.session

import dev.flint.term.core.Prompt
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The login question, from the core thread that asks it to the answer it gets back. */
class AuthPromptTest {
    private val code = listOf(Prompt("Verification code: ", echo = false))
    private val pool = Executors.newCachedThreadPool()

    /** Ask from another thread, as the core does, and hand back what it got. */
    private fun asking(prompts: AuthPrompts, fields: List<Prompt> = code) =
        pool.submit<List<String>?> { prompts.ask("Two-factor", "Enter the code", fields) }

    /** The dialog is up once the question has actually reached the flow. */
    private fun waitForPrompt(prompts: AuthPrompts): AuthPrompt =
        runBlocking { prompts.pending.first { it != null }!! }

    @Test
    fun `the answer reaches the connection that asked`() {
        val prompts = AuthPrompts()
        val asked = asking(prompts)
        waitForPrompt(prompts).answer(listOf("424242"))
        assertEquals(listOf("424242"), asked.get(2, TimeUnit.SECONDS))
    }

    @Test
    fun `the question is on screen only while it is being asked`() {
        val prompts = AuthPrompts()
        assertNull(prompts.pending.value)
        val asked = asking(prompts)
        val prompt = waitForPrompt(prompts)
        assertEquals("Two-factor", prompt.name)
        assertEquals("Enter the code", prompt.instruction)
        assertEquals(code, prompt.fields)
        prompt.answer(listOf("424242"))
        asked.get(2, TimeUnit.SECONDS)
        assertNull(prompts.pending.value)
    }

    @Test
    fun `walking away from it is not an answer`() {
        val prompts = AuthPrompts()
        val asked = asking(prompts)
        waitForPrompt(prompts).cancel()
        assertNull(asked.get(2, TimeUnit.SECONDS))
        assertNull(prompts.pending.value)
    }

    @Test
    fun `nobody there ends the wait rather than holding the connection open`() {
        val prompts = AuthPrompts(waitMillis = 50)
        assertNull(asking(prompts).get(2, TimeUnit.SECONDS))
        assertNull(prompts.pending.value)
    }

    /**
     * Two connections asking at once are asked one at a time. Stacked dialogs
     * would mean answering the one underneath blind — with a code that belongs
     * to the other host.
     */
    @Test
    fun `a second connection waits its turn`() {
        val prompts = AuthPrompts()
        val first = asking(prompts)
        val one = waitForPrompt(prompts)
        val second = asking(prompts, listOf(Prompt("Password: ", echo = false)))
        // The second question cannot have replaced the first while it stands.
        Thread.sleep(50)
        assertEquals(one, prompts.pending.value)
        one.answer(listOf("424242"))
        assertEquals(listOf("424242"), first.get(2, TimeUnit.SECONDS))

        val two = waitForPrompt(prompts)
        assertTrue(two.fields[0].text.contains("Password"))
        two.answer(listOf("hunter2"))
        assertEquals(listOf("hunter2"), second.get(2, TimeUnit.SECONDS))
    }

    @Test
    fun `a session with nothing to ask with cancels every question`() {
        assertNull(NO_PROMPTS.ask("Two-factor", "Enter the code", code))
    }
}
