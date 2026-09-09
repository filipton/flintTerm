package dev.flint.term.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first

/**
 * Where each screen was scrolled to, by name.
 *
 * Compose saves a scroll position with the back-stack entry, and it is still
 * lost on the way out. A screen leaving for one without a bottom bar is
 * measured taller on its way through the transition, so it has less to scroll,
 * so the position clamps down to the new limit — and that clamp, not the place
 * anybody was reading, is what gets saved. So the position is recorded here as
 * it changes, and a clamp caused by the screen growing is not allowed to
 * overwrite it.
 *
 * Keyed by a name the caller picks rather than by route, because two screens
 * built from the same composable (the ten settings sections) each want their
 * own place.
 */
private val offsets = mutableMapOf<String, Int>()
private val lists = mutableMapOf<String, Pair<Int, Int>>()

/** A scroll state for a `verticalScroll` that comes back where it was. */
@Composable
fun rememberScreenScroll(key: String): ScrollState {
    // A plain `remember` rather than `rememberScrollState`, which is saveable:
    // the value the registry hands back on restore would win over this seed,
    // and that value has the same zero in it.
    val state = remember(key) { ScrollState(offsets[key] ?: 0) }
    LaunchedEffect(state) {
        var tallest = 0
        snapshotFlow { state.value to state.maxValue }.collect { (value, max) ->
            // Only while the screen is as scrollable as it has been: a smaller
            // limit means it has just been measured taller, on its way out, and
            // the value that came with it is a clamp rather than a choice.
            if (max <= 0 || max < tallest) return@collect
            tallest = max
            offsets[key] = value
        }
    }
    return state
}

/** The same for a lazy list, which remembers an item and an offset into it. */
@Composable
fun rememberScreenListState(key: String): LazyListState {
    val at = lists[key] ?: (0 to 0)
    val state = remember(key) { LazyListState(at.first, at.second) }
    LaunchedEffect(state) {
        // A remembered index can outlive the list that made it: a host deleted,
        // a search narrowing the rows, simply fewer of them this time. A lazy
        // list does not clamp an index it cannot reach — it draws nothing at
        // all — so the first real layout is checked before anything is kept.
        val count = snapshotFlow { state.layoutInfo.totalItemsCount }.first { it > 0 }
        if (state.firstVisibleItemIndex >= count) state.scrollToItem(0)
        snapshotFlow { Triple(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, state.layoutInfo.totalItemsCount) }
            .collect { (index, offset, items) -> if (items > 0) lists[key] = index to offset }
    }
    return state
}

/** Forget a screen's place, for a list whose contents have been replaced. */
fun forgetScroll(key: String) {
    offsets.remove(key)
    lists.remove(key)
}
