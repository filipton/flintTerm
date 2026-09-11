package dev.flint.term.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf

/**
 * The settings row a search result is heading for.
 *
 * A search hit knows which screen it lives on, but not where on it, and by the
 * time the screen is composed the search is gone. So the name is left here on
 * the way out and picked up by the section that opens, which scrolls to the row
 * with that title and lights it up for a moment.
 *
 * The name is the row's own text, already translated, because both sides read
 * it from the same string resource.
 */
object SettingsFocus {
    private val pending = mutableStateOf<String?>(null)

    /** Ask the next settings screen to scroll to the row called [title]. */
    fun want(title: String) {
        pending.value = title
    }

    /** What the screen being composed should look for, taken once. */
    fun take(): String? = pending.value

    /** Done with it, so going back to the screen later does not light it up again. */
    fun clear() {
        pending.value = null
    }
}

/** The row title the open settings screen should highlight, if any. */
val LocalSettingsFocus = compositionLocalOf<String?> { null }

/**
 * The scrolling column a settings row sits in, and where its top is on screen.
 *
 * A row knows where it is in the window; to scroll to it, what is needed is
 * where it is inside the column, which is that distance plus however far the
 * column is already scrolled.
 */
class SettingsScroll(val scroll: ScrollState) {
    var topInWindow by mutableFloatStateOf(0f)
}

/** The scrolling column of the open settings screen. */
val LocalSettingsScroll = compositionLocalOf<SettingsScroll?> { null }
