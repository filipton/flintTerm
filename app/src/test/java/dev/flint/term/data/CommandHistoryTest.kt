package dev.flint.term.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandHistoryTest {
    private val history = listOf("echo hello", "systemctl restart nginx", "docker compose up -d", "ls -la /srv")

    @Test
    fun `the typed part is found without knowing what the prompt looks like`() {
        // A starship-ish prompt, a plain bash one, and a bare line all give the
        // same answer, because the history is what recognizes a command.
        assertEquals("ech", CommandHistory.typed("→  ~ ech", history))
        assertEquals("systemctl res", CommandHistory.typed("pilif@box:~$ systemctl res", history))
        assertEquals("docker comp", CommandHistory.typed("docker comp", history))
    }

    @Test
    fun `a bare prompt is not something someone typed`() {
        // "~" is what the prompt drew, and a history full of ~/… would match it.
        val h = listOf("~/.local/bin/thing", "echo hello")
        assertEquals("", CommandHistory.typed("→  ~ ", h))
        assertEquals("", CommandHistory.typed("pilif@box:~$ ", h))
        assertEquals("", CommandHistory.typed("~/src ❯ ", h))
        // But the moment something is typed, that is what counts.
        assertEquals("ec", CommandHistory.typed("→  ~ ec", h))
    }

    @Test
    fun `an unrecognised line falls back to the last word`() {
        assertEquals("qqq", CommandHistory.typed("~ % qqq", history))
    }

    @Test
    fun `suggestions prefer what starts with the prefix and what was run last`() {
        val h = listOf("git status", "git push origin main", "grep -r todo .")
        assertEquals(listOf("git push origin main", "git status"), CommandHistory.suggest(h, "git", limit = 2))
    }

    @Test
    fun `a command already typed in full is not suggested back`() {
        assertTrue(CommandHistory.suggest(history, "echo hello").none { it == "echo hello" })
    }

    @Test
    fun `a command that merely contains the prefix is not offered`() {
        // "tools" contains "ls"; suggesting it for `ls` is how the feature
        // earns its reputation for being useless.
        val h = listOf("make tools/build.sh", "ls -la /srv")
        assertEquals(listOf("ls -la /srv"), CommandHistory.suggest(h, "ls"))
    }

    @Test
    fun `the ghost is the rest of the most recent match`() {
        val h = listOf("git status", "git push origin main")
        assertEquals(" push origin main", CommandHistory.ghost(h, "git"))
        assertEquals(null, CommandHistory.ghost(h, "cargo"))
        // Nothing left to add means nothing to draw.
        assertEquals(null, CommandHistory.ghost(listOf("git status"), "git status"))
    }

    @Test
    fun `one letter is enough to suggest on`() {
        // Waiting for a second character means `ls` never suggests anything,
        // which is exactly when a suggestion would have saved the most typing.
        assertEquals(listOf("echo hello"), CommandHistory.suggest(history, "e"))
    }

    @Test
    fun `what is run most comes first, and recency breaks the tie`() {
        val h = listOf("git status", "git push", "git commit -am wip")
        val counts = mapOf("git status" to 40, "git push" to 3)
        assertEquals(
            listOf("git status", "git push", "git commit -am wip"),
            CommandHistory.suggest(h, "git", counts = counts),
        )
        // With nothing to separate them, the last one run wins.
        assertEquals(listOf("git commit -am wip", "git push", "git status"), CommandHistory.suggest(h, "git"))
    }

    @Test
    fun `a history file's repetitions are counted`() {
        val text = "make\nls -la\nmake\nmake\n"
        assertEquals(mapOf("make" to 3, "ls -la" to 1), CommandHistory.countShellHistory(text))
    }

    @Test
    fun `repeating a command moves it to the front rather than duplicating it`() {
        val after = CommandHistory.remember(listOf("one", "two"), "one")
        assertEquals(listOf("two", "one"), after)
    }

    @Test
    fun `trivial and blank entries are not kept`() {
        assertEquals(listOf("one"), CommandHistory.remember(listOf("one"), "ls"))
        assertEquals(listOf("one"), CommandHistory.remember(listOf("one"), "   "))
    }

    @Test
    fun `zsh timestamps are stripped and comments dropped`() {
        val text = """
            : 1700000000:0;echo from zsh
            # a comment
            plain bash line
        """.trimIndent()
        assertEquals(listOf("echo from zsh", "plain bash line"), CommandHistory.parseShellHistory(text))
    }
}
