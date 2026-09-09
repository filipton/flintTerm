package dev.flint.term.data

/**
 * Finding a scheme by name in a list of six hundred.
 *
 * The name is all anyone searches by, so this is deliberately small: a
 * case-insensitive substring match, with the ones whose name *starts* with
 * the query ahead of the ones that merely contain it, and a word boundary
 * counted as a start too — "night" should find "TokyoNight Night" and
 * "Night Owl" before "Midnight in Mojave".
 */
object SchemeSearch {
    /** [schemes] that match [query], best first; the whole list, in its order, for a blank query. */
    fun rank(schemes: List<TermScheme>, query: String): List<TermScheme> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return schemes
        return schemes
            .mapNotNull { s -> tier(s.name.lowercase(), q)?.let { it to s } }
            .sortedWith(compareBy({ it.first }, { it.second.name.lowercase() }))
            .map { it.second }
    }

    /** 0 for a prefix, 1 for a word start, 2 for anywhere; null for no match. */
    internal fun tier(name: String, q: String): Int? {
        val at = name.indexOf(q)
        return when {
            at < 0 -> null
            at == 0 -> 0
            wordStarts(name).any { name.startsWith(q, it) } -> 1
            else -> 2
        }
    }

    private fun wordStarts(name: String): List<Int> =
        name.indices.filter { i -> i > 0 && !name[i - 1].isLetterOrDigit() && name[i].isLetterOrDigit() }
}
