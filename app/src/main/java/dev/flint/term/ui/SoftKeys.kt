package dev.flint.term.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.flint.term.terminal.TerminalView

/**
 * A keyboard of our own, for when the system one is in the way.
 *
 * Not a clone and not an input method: it is drawn inside this app and types
 * into this terminal, which is the only place it is ever needed. No enabling it
 * in system settings, no dictionaries, no autocorrect, no language packs.
 *
 * The geometry is deliberately the one every Android keyboard already uses,
 * down to the half-key indent on the home row and the wide shift and backspace,
 * because a keyboard that moves the letters is a keyboard nobody can type on.
 * The one difference is the bottom row, where a terminal wants Ctrl more than
 * it wants an emoji key.
 *
 * Every cap goes through the same code as the key bar above it, so Ctrl behaves
 * identically in both: tap to arm it, or hold and slide onto a letter.
 */
private class Key(val token: String, val weight: Float = 1f)

/** Uniform, so no row is a hair shorter than the one above it. */
private val CAP_HEIGHT = 46.dp

/**
 * The strip every layer opens with — digits, top symbols, F keys. Shorter than
 * a cap, and the same on all three layers, so switching layer does not change
 * the keyboard's height and shove the terminal up and down.
 */
private val STRIP_HEIGHT = 38.dp

private val COMPACT_CAP_HEIGHT = 34.dp
private val COMPACT_STRIP_HEIGHT = 28.dp

/** Each half of the split keyboard is five standard keys wide. */
private const val SPLIT_UNITS = 5f

/** Wide enough for five keys a thumb can hit, and no wider, so each half stays in reach. */
private val SPLIT_HALF_MAX = 270.dp

/** Even on a narrow screen the two halves must read as two. */
private val SPLIT_MIN_GAP = 24.dp

/**
 * [splitAt] is where the row breaks when the keyboard is split: halfway by
 * count, which puts t|y, g|h and v|b where a split keyboard puts them.
 */
private class KeyboardRow(
    val keys: List<Key>,
    val inset: Float = 0f,
    val height: Dp = CAP_HEIGHT,
    val splitAt: Int = (keys.size + 1) / 2,
)

private fun row(vararg tokens: String, inset: Float = 0f, height: Dp = CAP_HEIGHT, splitAt: Int = (tokens.size + 1) / 2) =
    KeyboardRow(tokens.map { Key(it) }, inset, height, splitAt)

private val LETTERS = listOf(
    // The digits are a strip above the keyboard rather than part of it, and a
    // phone keyboard draws them shorter for exactly that reason.
    row("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", height = STRIP_HEIGHT),
    row("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
    // The half-key step that puts a under q and s under w, as on every phone.
    row("a", "s", "d", "f", "g", "h", "j", "k", "l", inset = 0.5f),
    KeyboardRow(
        listOf(Key("SHIFT", 1.5f)) + listOf("z", "x", "c", "v", "b", "n", "m").map { Key(it) } + Key("BKSP", 1.5f),
    ),
)

// Page one of two, laid out where a phone keyboard's symbol page puts these
// keys, down to the digits on top and the page key under the shift. Only the
// two a phone spends on multiply and divide are given over to ~ and `, which a
// shell needs and a phone has no use for.
private val SYMBOLS = listOf(
    row("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", height = STRIP_HEIGHT),
    row("+", "~", "`", "=", "/", "_", "<", ">", "[", "]"),
    row("!", "@", "#", "$", "%", "^", "&", "*", "(", ")"),
    KeyboardRow(
        listOf(Key(PAGE, 1.5f)) + listOf("-", "'", "\"", ":", ";", ",", "?").map { Key(it) } + Key("BKSP", 1.5f),
    ),
)

// Page two, in the same shape as a phone keyboard's second symbol page: the six
// characters it opens with — ` ~ \ | { } — are where a thumb already expects
// them, and the rest of that page is currencies, bullets and card suits, which
// a terminal has no use for. Those places carry the F keys, the arrows and the
// keys a shell needs instead.
private val FUNCTIONS = listOf(
    row("F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", height = STRIP_HEIGHT),
    row("`", "~", "\\", "|", "{", "}", "ESC", "TAB", "INS", "DEL"),
    // Left, down, up, right: the order of hjkl, of the arrow cluster's bottom
    // row, and of every other terminal's key bar. Kept adjacent so the eye finds
    // the group rather than four keys that happen to be arrows.
    //
    // In the middle of the row rather than at its left edge, which is the corner
    // a thumb reaches for last, and which is roughly where the key bar keeps its
    // own arrows: one place to look for them whichever of the two is up.
    //
    // Ten across and flush, like every other row on both pages: a row indented
    // by half a key puts this page's caps between the other page's, and paging
    // between the two then moves every key under the thumb.
    //
    // Split, the four go together to the right half, where hjkl are, rather
    // than one of them being left behind on the other side of the gap.
    row("HOME", "END", "PGUP", "PGDN", "LEFT", "DOWN", "UP", "RIGHT", "NAV", "SEARCH", splitAt = 4),
    KeyboardRow(
        listOf(Key(PAGE, 1.5f)) + listOf("F11", "F12", "ALT", "SHIFT", "STAB", "PASTE", "KEYBOARD").map { Key(it) } + Key("BKSP", 1.5f),
    ),
)

/** Keys that do something rather than type something, drawn a shade darker. */
private val SPECIAL = setOf("SHIFT", "BKSP", "ENTER", "CTRL", "ALT", "ESC", "TAB", "STAB", "DEL", "INS")

/** What a key reads as here, where a word would not fit or a glyph says it better. */
private val LABELS = mapOf("SHIFT" to "⇧", "ENTER" to "⏎", "BKSP" to "⌫")

/**
 * Not a key of this keyboard: the cap in the shift position on the symbol and
 * function pages, which turns to the other one. Named rather than typed, so it
 * cannot collide with a token the bar knows.
 */
private const val PAGE = "@page"

/** Not a key either: the cap that goes between letters and symbols. */
private const val LAYER = "@layer"

// Where a phone keyboard puts the emoji key, a terminal wants Ctrl. The comma
// keeps its place beside it, and space, full stop and enter keep theirs, so the
// row still reads the way the hand expects.
private val BOTTOM = KeyboardRow(
    listOf(Key(LAYER, 1.35f), Key("CTRL", 1.2f), Key(",", 0.9f), Key(" ", 4.25f), Key(".", 0.9f), Key("ENTER", 1.4f)),
)

// Split, the space bar is under both thumbs, as on every split keyboard: either
// half of it is a space, so neither hand has to reach across the gap for one.
private val BOTTOM_SPLIT = KeyboardRow(
    listOf(Key(LAYER, 1.35f), Key("CTRL", 1.2f), Key(",", 0.9f), Key(" ", 1.55f), Key(" ", 2.6f), Key(".", 0.9f), Key("ENTER", 1.5f)),
    splitAt = 4,
)

private enum class Layer { LETTERS, SYMBOLS, FUNCTIONS }

@Composable
fun TerminalKeyboard(
    view: TerminalView,
    chrome: Color,
    onChrome: Color,
    modifier: Modifier = Modifier,
    onSnippets: () -> Unit = {},
    onSearch: () -> Unit = {},
    onCompose: () -> Unit = {},
    onChords: (() -> Unit)? = null,
    /**
     * For a phone on its side: split in two, one half under each thumb, and
     * shorter caps, where five full rows would leave no terminal above them.
     */
    compact: Boolean = false,
) {
    // Every row keeps its height relative to the others, so the strip still
    // reads as a strip and switching layer still moves nothing.
    fun Dp.scaled(): Dp = if (!compact) this else if (this == STRIP_HEIGHT) COMPACT_STRIP_HEIGHT else COMPACT_CAP_HEIGHT
    val mods by view.modifiers.collectAsStateWithLifecycle()
    val pickToInsert = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) view.onFilesDropped?.invoke(uris)
    }
    val actions = BarActions(view, onSnippets, onSearch, onCompose, onChords) { pickToInsert.launch(arrayOf("*/*")) }
    val cap = onChrome.copy(alpha = 0.10f)
    val capText = onChrome.copy(alpha = 0.9f)
    var layer by remember { mutableStateOf(Layer.LETTERS) }

    // Everything that is not a letter is drawn a shade darker, the way a phone
    // keyboard separates the keys that type from the keys that do something.
    val special = cap.copy(alpha = 0.04f).compositeOver(chrome)

    @Composable
    fun RowScope.key(token: String, weight: Float, height: Dp = CAP_HEIGHT) {
        val isSpecial = token in SPECIAL
        KeyFor(
            token,
            actions,
            mods,
            if (isSpecial) special else cap,
            capText,
            Modifier.weight(weight).padding(horizontal = 2.dp),
            compact = true,
            capHeight = height,
            labelOverride = LABELS[token],
        )
    }

    // Little padding at the top: the key bar sits directly above with padding
    // of its own, and two lots of it read as a gap between the two halves of
    // what is really one keyboard.
    Column(
        modifier.background(chrome).padding(start = 3.dp, end = 3.dp, top = 1.dp, bottom = if (compact) 3.dp else 6.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 7.dp),
    ) {
        val rows = when (layer) {
            Layer.LETTERS -> LETTERS
            Layer.SYMBOLS -> SYMBOLS
            Layer.FUNCTIONS -> FUNCTIONS
        } + if (compact) BOTTOM_SPLIT else BOTTOM

        @Composable
        fun RowScope.cap(k: Key, height: Dp) {
            when (k.token) {
                // Where a phone keyboard says 1/2, and for the same reason: the
                // second page of everything that is not a letter, one tap away
                // and one tap back.
                PAGE -> KeyCapText(
                    if (layer == Layer.SYMBOLS) "1/2" else "2/2",
                    special, capText, Modifier.weight(k.weight).padding(horizontal = 2.dp), height,
                ) {
                    layer = if (layer == Layer.SYMBOLS) Layer.FUNCTIONS else Layer.SYMBOLS
                }
                // Letters and symbols under one key, as on a phone: from either
                // page away from the letters this reads abc and goes straight
                // back, rather than being the next stop on a cycle through three.
                LAYER -> KeyCapText(
                    if (layer == Layer.LETTERS) "?123" else "abc",
                    special, capText, Modifier.weight(k.weight).padding(horizontal = 2.dp), height,
                ) {
                    layer = if (layer == Layer.LETTERS) Layer.SYMBOLS else Layer.LETTERS
                }
                else -> key(k.token, k.weight, height)
            }
        }

        if (!compact) {
            rows.forEach { line ->
                Row(Modifier.fillMaxWidth()) {
                    if (line.inset > 0f) Spacer(Modifier.weight(line.inset))
                    line.keys.forEach { cap(it, line.height) }
                    if (line.inset > 0f) Spacer(Modifier.weight(line.inset))
                }
            }
        } else {
            // On its side, a phone is held at both ends, and a keyboard stretched
            // across the middle puts half its keys out of reach of either thumb.
            // Each half is a phone keyboard's half at a phone keyboard's size,
            // against its own edge, and the middle is left empty.
            val width = LocalConfiguration.current.screenWidthDp.dp - 6.dp
            val half = minOf((width - SPLIT_MIN_GAP) / 2, SPLIT_HALF_MAX)
            rows.forEach { line ->
                val left = line.keys.take(line.splitAt)
                val right = line.keys.drop(line.splitAt)
                // The same width of key on both sides of a row, however its keys
                // fall: the half with less in it is padded, on its outer edge, so
                // the two halves meet the gap the way they met each other.
                val units = maxOf(SPLIT_UNITS, left.sumOf { it.weight.toDouble() }.toFloat(), right.sumOf { it.weight.toDouble() }.toFloat())
                val height = line.height.scaled()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(Modifier.width(half)) {
                        val pad = units - left.sumOf { it.weight.toDouble() }.toFloat()
                        if (pad > 0f) Spacer(Modifier.weight(pad))
                        left.forEach { cap(it, height) }
                    }
                    Row(Modifier.width(half)) {
                        right.forEach { cap(it, height) }
                        val pad = units - right.sumOf { it.weight.toDouble() }.toFloat()
                        if (pad > 0f) Spacer(Modifier.weight(pad))
                    }
                }
            }
        }
    }
}
