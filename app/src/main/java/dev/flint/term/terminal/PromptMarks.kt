package dev.flint.term.terminal

import dev.flint.term.core.PromptKind
import dev.flint.term.core.PromptMarkAt

/**
 * Reading a shell's prompt marks: which prompt to jump to, and which rows the
 * last command's output covers.
 *
 * Rows are the core's own, counted from the top visible line, so a mark that
 * has scrolled into the scrollback is negative. Everything here is decided on
 * the list of marks alone — no terminal, no screen — which is what makes the
 * awkward cases (a command still running, a shell that marks nothing) worth
 * writing down once.
 */
object PromptMarks {
    /** A run of whole rows, both ends included. */
    data class Rows(val first: Int, val last: Int)

    /**
     * The rows of the last command's output, or null when there is no output
     * to copy.
     *
     * It starts at the `C` that said the command was running. It ends at the
     * row above the `D` that ended it — a shell prints nothing between the end
     * of one command and the prompt of the next, so `D` lands on the first cell
     * of that prompt, which is not part of the output — and at [bottomRow]
     * while the command is still running, since the output is still arriving
     * there.
     */
    fun lastOutput(marks: List<PromptMarkAt>, bottomRow: Int): Rows? {
        val start = marks.lastOrNull { it.kind == PromptKind.OUTPUT_START } ?: return null
        val end = marks.firstOrNull { it.kind == PromptKind.FINISHED && it.row >= start.row }
        val last = if (end == null) bottomRow else end.row - 1
        // A command that printed nothing ends where it started, and copying an
        // empty selection is worse than saying there was nothing to copy.
        return if (last < start.row) null else Rows(start.row, last)
    }

    /**
     * The rows a prompt was drawn on, in order and without repeats.
     *
     * Both marks of the prompt are read: `A` is where it starts and `B` where
     * what you typed does, which is one row and one place to jump to. Taking
     * either means a shell that only sends one of the two is still navigable.
     */
    fun promptRows(marks: List<PromptMarkAt>): List<Int> =
        marks.filter { it.kind == PromptKind.PROMPT_START || it.kind == PromptKind.COMMAND_START }
            .map { it.row }
            .distinct()
            .sorted()

    /**
     * The prompt to scroll to, as a row: the nearest one above the top of the
     * screen, or the nearest one below it. Null when there is none that way,
     * which is what the top and the bottom of the buffer feel like.
     */
    fun jumpTarget(marks: List<PromptMarkAt>, previous: Boolean): Int? {
        val rows = promptRows(marks)
        return if (previous) rows.lastOrNull { it < 0 } else rows.firstOrNull { it > 0 }
    }
}
