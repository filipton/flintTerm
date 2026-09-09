package dev.flint.term.session

import dev.flint.term.core.PromptKind

/**
 * Notices when a long command has finished, so the phone can say so.
 *
 * A build, a backup or a package upgrade is exactly when the phone goes back
 * into a pocket, and coming back to look is the only way to find out whether it
 * worked. There are two ways to know, and this takes whichever it is given.
 *
 * A shell that emits OSC 133 marks says it outright: the command started here,
 * it ended there, and this is the status it ended with. That is the truth, so
 * the moment one mark arrives the guesswork below is switched off for good — a
 * shell either draws its prompt with the marks or it does not, and a heuristic
 * second-guessing a fact can only make it worse.
 *
 * Without marks — which is nearly every shell as it ships — the only signal
 * left is the prompt coming back. That is read off the output alone, because
 * that is the one stream every session has: a prompt, then the newline the
 * shell echoes when Enter is pressed, starts the clock, and the next prompt
 * stops it. The rule is deliberately shy: only a command slow enough that
 * somebody might have walked away is worth a notification, and a wrong guess
 * costs one nobody wanted.
 */
class CommandWatch(
    /** Below this, the command was over before anyone could look away. */
    private val minMillis: Long = 30_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** A prompt is on screen and nothing has been sent yet. */
    private var atPrompt = false
    /** When Enter was pressed, or null while no command is running. */
    private var startedAt: Long? = null
    /** What is on the current output line; the prompt is hunted on its tail. */
    private val line = StringBuilder()
    /** This shell marks its prompts, so nothing has to be guessed any more. */
    private var marked = false

    /**
     * How long the command that just ended took, and — only when the shell
     * marked it — the status it ended with.
     */
    data class Finished(val elapsedMillis: Long, val exitStatus: Int? = null)

    /**
     * A shell prompt mark, in the order the shell sent it.
     *
     * `C` is where the command really begins and `D` is where it really ends,
     * so those two are the clock. `A` and `B` are the prompt being drawn and
     * waiting; reaching one with a command still timing means it never ended
     * on its own — Ctrl-C, or a shell that skipped the mark — and there is
     * nothing honest left to report about it.
     */
    fun onMark(kind: PromptKind, exit: Int?): Finished? {
        if (!marked) {
            // Whatever the heuristic had going was a guess about this same
            // shell; it is worth less than the marks and cannot outlive them.
            marked = true
            reset()
        }
        return when (kind) {
            PromptKind.OUTPUT_START -> {
                startedAt = now()
                null
            }
            PromptKind.FINISHED -> {
                val start = startedAt ?: return null
                startedAt = null
                val elapsed = now() - start
                if (elapsed >= minMillis) Finished(elapsed, exit) else null
            }
            else -> {
                startedAt = null
                null
            }
        }
    }

    /**
     * Output as it arrives, escape sequences already removed. Returns non-null
     * once per command, when the prompt comes back after long enough — unless
     * this shell has shown a mark, in which case [onMark] answers instead.
     */
    fun onOutput(text: String): Finished? {
        if (marked) return null
        var finished: Finished? = null
        for (ch in text) {
            when (ch) {
                '\n' -> {
                    // The shell echoes this when Enter is pressed, which is the
                    // only moment we can be sure a command was actually sent.
                    if (atPrompt) {
                        atPrompt = false
                        startedAt = now()
                    }
                    line.setLength(0)
                }
                '\r' -> line.setLength(0)
                else -> {
                    line.append(ch)
                    if (line.length > 400) line.delete(0, line.length - 400)
                }
            }
        }
        if (looksLikePrompt(line.toString())) {
            val start = startedAt
            if (start != null) {
                val elapsed = now() - start
                startedAt = null
                if (elapsed >= minMillis) finished = Finished(elapsed)
            }
            atPrompt = true
        }
        return finished
    }

    /**
     * Nothing is running any more — a disconnect, or a session being replaced.
     * A shell that marks its prompts is still that shell afterwards, so that
     * much is remembered.
     */
    fun reset() {
        atPrompt = false
        startedAt = null
        line.setLength(0)
    }

    companion object {
        /**
         * Does this line look like a shell waiting for the next command?
         *
         * Two shapes cover nearly everything. The classic one ends in `$` or
         * `#` and a space, and so does most of what themed shells draw. The
         * other puts its glyph first and the path last — `→  ~` — where the
         * only thing to go on is the glyph and the fact that a prompt is short.
         *
         * The trailing space matters in both: it is what separates a prompt
         * waiting for input from a line of output that happens to end in a
         * bracket. And a long line is output however it ends.
         */
        fun looksLikePrompt(line: String): Boolean {
            if (line.isEmpty() || line.length > 200 || !line.endsWith(" ")) return false
            val text = line.trimEnd()
            if (text.lastOrNull() in PROMPT_MARKERS) return true
            // Glyph-first prompts only: `$` or `#` at the start of a line is as
            // likely to be output — a comment, a quoted shell snippet — as a prompt.
            return text.length <= 80 && text.firstOrNull() in LEADING_MARKERS
        }

        /** `$` and `#` for the classics; the rest are what themed shells end with. */
        private val PROMPT_MARKERS = listOf('$', '#', '>', '%', '❯', '→', '➜', '»', 'λ')

        /** Glyphs no program prints at the start of a line, but prompts do. */
        private val LEADING_MARKERS = listOf('❯', '→', '➜', '»', 'λ')
    }
}
