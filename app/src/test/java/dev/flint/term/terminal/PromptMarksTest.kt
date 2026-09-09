package dev.flint.term.terminal

import dev.flint.term.core.PromptKind
import dev.flint.term.core.PromptMarkAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PromptMarksTest {
    private fun at(kind: PromptKind, row: Int, exit: Int? = null) = PromptMarkAt(kind, exit, row)

    /**
     * One finished command: the prompt on row 0, the output from row 1, and the
     * next prompt on row 4 carrying the `D` that ended the command — a shell
     * prints nothing between the two, so they share a cell.
     */
    private fun finished() = listOf(
        at(PromptKind.PROMPT_START, 0),
        at(PromptKind.COMMAND_START, 0),
        at(PromptKind.OUTPUT_START, 1),
        at(PromptKind.FINISHED, 4, exit = 0),
        at(PromptKind.PROMPT_START, 4),
    )

    @Test
    fun `a finished command ends above the prompt that followed it`() {
        assertEquals(PromptMarks.Rows(1, 3), PromptMarks.lastOutput(finished(), bottomRow = 20))
    }

    @Test
    fun `a running command ends at the last row it has printed`() {
        val running = finished().dropLast(2) + at(PromptKind.PROMPT_START, 4) + at(PromptKind.COMMAND_START, 4) +
            at(PromptKind.OUTPUT_START, 5)
        assertEquals(PromptMarks.Rows(5, 9), PromptMarks.lastOutput(running, bottomRow = 9))
    }

    @Test
    fun `a shell that marks nothing has no output to copy`() {
        assertNull(PromptMarks.lastOutput(emptyList(), bottomRow = 20))
    }

    @Test
    fun `a command that printed nothing has no output to copy`() {
        // The command ended on the row it started on: the prompt came straight
        // back, and there is nothing in between to select.
        val quiet = listOf(
            at(PromptKind.OUTPUT_START, 2),
            at(PromptKind.FINISHED, 2, exit = 1),
            at(PromptKind.PROMPT_START, 2),
        )
        assertNull(PromptMarks.lastOutput(quiet, bottomRow = 20))
    }

    @Test
    fun `output that started in the scrollback keeps its negative row`() {
        val scrolled = listOf(
            at(PromptKind.OUTPUT_START, -30),
            at(PromptKind.FINISHED, -4, exit = 0),
            at(PromptKind.PROMPT_START, -4),
        )
        assertEquals(PromptMarks.Rows(-30, -5), PromptMarks.lastOutput(scrolled, bottomRow = 20))
    }

    @Test
    fun `a jump goes to the nearest prompt off the top or the bottom`() {
        val marks = listOf(
            at(PromptKind.PROMPT_START, -40),
            at(PromptKind.PROMPT_START, -12),
            at(PromptKind.COMMAND_START, -12),
            at(PromptKind.PROMPT_START, 3),
            at(PromptKind.PROMPT_START, 14),
        )
        assertEquals(-12, PromptMarks.jumpTarget(marks, previous = true))
        assertEquals(3, PromptMarks.jumpTarget(marks, previous = false))
        assertEquals(listOf(-40, -12, 3, 14), PromptMarks.promptRows(marks))
    }

    @Test
    fun `there is nowhere to jump when every prompt is where the screen is`() {
        val marks = listOf(at(PromptKind.PROMPT_START, 0), at(PromptKind.COMMAND_START, 0))
        assertNull(PromptMarks.jumpTarget(marks, previous = true))
        assertNull(PromptMarks.jumpTarget(marks, previous = false))
    }
}
