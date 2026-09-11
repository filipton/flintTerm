package dev.flint.term.data

/**
 * Commands the user has run, per host, for completing the next one.
 *
 * Typing a long command on a phone keyboard is the worst part of using a
 * terminal on one, and the same commands come round again and again — so what
 * has been run before is the best source of suggestions there is. Kept per host
 * because `systemctl restart` on the wrong machine is exactly what nobody wants
 * suggested.
 */
object CommandHistory {
    /** Beyond this a host's history is more weight than help. */
    const val LIMIT = 500

    /**
     * What the user typed, out of a line that also holds a prompt.
     *
     * Guessing the prompt is a losing game — `$`, `#`, `❯`, `→`, a path, a git
     * branch, two lines of color — so this does not guess. It takes the line's
     * suffixes, longest first, and picks the longest one the history recognizes.
     * The history is the only thing here that knows what a command looks like.
     * Falls back to the last word when nothing matches, which is the sensible
     * reading of "this is the start of a command".
     */
    fun typed(line: String, history: List<String> = emptyList()): String {
        val text = line.trimEnd()
        if (text.isBlank()) return ""
        val starts = mutableListOf(0)
        text.forEachIndexed { i, c -> if (c == ' ' && i + 1 < text.length) starts += i + 1 }
        for (start in starts) {
            val candidate = text.substring(start).trimStart()
            if (candidate.length < 2 || isPromptDecoration(candidate)) continue
            // startsWith(ignoreCase) rather than lowercasing: this runs on a
            // tick while somebody is typing, and lowercasing the whole history
            // for every candidate was thousands of throwaway strings a second.
            if (history.any { it.startsWith(candidate, ignoreCase = true) }) return candidate
        }
        // A line that ends in a space has nothing typed since the last word —
        // most often a prompt with the cursor sitting after it.
        if (line != text) return ""
        val last = text.substringAfterLast(' ').trim()
        return if (isPromptDecoration(last)) "" else last
    }

    /**
     * Is this the prompt talking rather than the person?
     *
     * A prompt's tail — `~`, `$`, `❯`, `~/src` — is on the line before anything
     * has been typed, and a history full of `~/bin/…` will happily match it. So
     * a candidate made only of the characters prompts are built from is not
     * treated as input, and an empty line stays an empty line.
     */
    private fun isPromptDecoration(candidate: String): Boolean =
        candidate.isEmpty() || candidate.all { it in PROMPT_CHARS }

    private const val PROMPT_CHARS = "~$#>%❯→➜»λ/:.-_ "

    /** Worth remembering? Not blanks, not one-offs of a couple of characters. */
    fun worthKeeping(command: String): Boolean {
        val c = command.trim()
        return c.length >= 3 && !c.startsWith(" ") && c.lines().size == 1
    }

    /**
     * Add [command] to [history], most recent last, without repeats.
     *
     * A repeat moves to the end rather than being dropped: the point of the list
     * is what you reach for most recently, not what you first typed.
     */
    fun remember(history: List<String>, command: String): List<String> {
        val c = command.trim()
        if (!worthKeeping(c)) return history
        return (history.filterNot { it == c } + c).takeLast(LIMIT)
    }

    /**
     * Commands that start with what is typed so far, best first.
     *
     * Prefix only, deliberately. Matching anywhere in the line looks clever
     * until `ls` offers `make tools/build.sh` — "tools" contains it — and a
     * suggestion that has nothing to do with what is being typed is worse than
     * no suggestion at all.
     *
     * "Best" is how often a command has been run, then how recently. Frequency
     * first because the command you want is usually the one you always want,
     * and recency to break the ties that leaves.
     */
    fun suggest(
        history: List<String>,
        typed: String,
        limit: Int = 5,
        counts: Map<String, Int> = emptyMap(),
    ): List<String> {
        val prefix = typed.trimStart()
        if (history.isEmpty() || prefix.isEmpty()) return emptyList()
        val lower = prefix.lowercase()
        val matches = ArrayList<Pair<String, Int>>()
        history.forEachIndexed { at, c ->
            if (c != prefix && c.lowercase().startsWith(lower)) matches += c to at
        }
        return matches
            .sortedWith(compareByDescending<Pair<String, Int>> { counts[it.first] ?: 1 }.thenByDescending { it.second })
            .map { it.first }
            .take(limit)
    }

    /**
     * The one suggestion worth drawing where the cursor is: the rest of the
     * most recent command that starts with [typed], or null.
     */
    fun ghost(history: List<String>, typed: String, counts: Map<String, Int> = emptyMap()): String? {
        val best = suggest(history, typed, limit = 1, counts = counts).firstOrNull() ?: return null
        return best.substring(typed.trimStart().length).takeIf { it.isNotEmpty() }
    }

    /**
     * Commands out of a shell history file.
     *
     * zsh writes `: <started>:<elapsed>;<command>` when extended history is on;
     * bash writes the command alone. Multi-line entries and the timestamps
     * themselves are of no use here and are dropped.
     */
    /**
     * How often each command appears in a shell history file.
     *
     * A history file keeps every repetition, which is the only honest measure
     * of "the command you always run" this app can get — [parseShellHistory]
     * throws it away by design, so it is counted separately.
     */
    fun countShellHistory(text: String): Map<String, Int> {
        val counts = HashMap<String, Int>()
        for (raw in text.lineSequence()) {
            var line = raw.trim()
            if (line.startsWith(": ") && line.contains(';')) line = line.substringAfter(';').trim()
            if (line.startsWith("#")) continue
            if (worthKeeping(line)) counts[line] = (counts[line] ?: 0) + 1
        }
        return counts
    }

    fun parseShellHistory(text: String): List<String> {
        val out = LinkedHashSet<String>()
        for (raw in text.lineSequence()) {
            var line = raw.trim()
            if (line.startsWith(": ") && line.contains(';')) line = line.substringAfter(';').trim()
            if (line.startsWith("#")) continue
            if (worthKeeping(line)) out += line
        }
        return out.toList().takeLast(LIMIT)
    }
}
