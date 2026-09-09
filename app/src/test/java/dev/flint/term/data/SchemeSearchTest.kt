package dev.flint.term.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SchemeSearchTest {
    private fun s(name: String) = Schemes.DEFAULT.copy(id = name.lowercase().replace(' ', '-'), name = name)
    private val all = listOf(
        s("Midnight in Mojave"), s("TokyoNight Night"), s("Night Owl"), s("Nord"), s("Dracula"), s("Tokyo Night Storm"),
    )

    @Test fun `a blank query is the whole list, in its order`() {
        assertSame(all, SchemeSearch.rank(all, ""))
        assertSame(all, SchemeSearch.rank(all, "   "))
    }

    @Test fun `prefix first, then a word start, then anywhere, alphabetical within each`() {
        assertEquals(
            listOf("Night Owl", "Tokyo Night Storm", "TokyoNight Night", "Midnight in Mojave"),
            SchemeSearch.rank(all, "night").map { it.name },
        )
    }

    @Test fun `case does not matter`() {
        assertEquals(listOf("Nord"), SchemeSearch.rank(all, "NORD").map { it.name })
        assertEquals(listOf("Nord"), SchemeSearch.rank(all, "nO").map { it.name })
    }

    @Test fun `no match is an empty list`() {
        assertEquals(emptyList<TermScheme>(), SchemeSearch.rank(all, "gruvbox"))
    }

    @Test fun `spaces in the query are matched as typed`() {
        assertEquals(listOf("Tokyo Night Storm"), SchemeSearch.rank(all, "tokyo n").map { it.name })
    }
}
