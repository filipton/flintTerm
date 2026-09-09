package dev.flint.term.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposeHistoryTest {

    @Test
    fun `the last line sent is the first one reached`() {
        var history = emptyList<String>()
        history = ComposeHistory.remember(history, "restart the worker")
        history = ComposeHistory.remember(history, "and tail the log")
        assertEquals(listOf("and tail the log", "restart the worker"), history)
    }

    @Test
    fun `the same line twice in a row is kept once`() {
        var history = ComposeHistory.remember(emptyList(), "run the tests")
        history = ComposeHistory.remember(history, "run the tests")
        assertEquals(listOf("run the tests"), history)
    }

    @Test
    fun `a line that comes round again is kept where it lands`() {
        var history = ComposeHistory.remember(emptyList(), "run the tests")
        history = ComposeHistory.remember(history, "fix the failure")
        history = ComposeHistory.remember(history, "run the tests")
        assertEquals(listOf("run the tests", "fix the failure", "run the tests"), history)
    }

    @Test
    fun `only the last twenty are kept`() {
        var history = emptyList<String>()
        repeat(25) { history = ComposeHistory.remember(history, "prompt $it") }
        assertEquals(ComposeHistory.LIMIT, history.size)
        assertEquals("prompt 24", history.first())
        assertEquals("prompt 5", history.last())
    }

    @Test
    fun `blank lines and stray whitespace are not history`() {
        var history = ComposeHistory.remember(emptyList(), "   ")
        assertEquals(emptyList<String>(), history)
        history = ComposeHistory.remember(history, "  deploy\n")
        assertEquals(listOf("deploy"), history)
        // Trimmed, so the same line typed with a trailing newline is the same line.
        assertEquals(listOf("deploy"), ComposeHistory.remember(history, "deploy "))
    }
}
