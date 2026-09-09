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

private class KeyboardRow(val keys: List<Key>, val inset: Float = 0f, val height: Dp = CAP_HEIGHT)

private fun row(vararg tokens: String, inset: Float = 0f, height: Dp = CAP_HEIGHT) =
    KeyboardRow(tokens.map { Key(it) }, inset, height)

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
    row("HOME", "END", "PGUP", "PGDN", "LEFT", "DOWN", "UP", "RIGHT", "NAV", "SEARCH"),
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
) {
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
        modifier.background(chrome).padding(start = 3.dp, end = 3.dp, top = 1.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        val rows = when (layer) {
            Layer.LETTERS -> LETTERS
            Layer.SYMBOLS -> SYMBOLS
            Layer.FUNCTIONS -> FUNCTIONS
        }
        rows.forEach { line ->
            Row(Modifier.fillMaxWidth()) {
                if (line.inset > 0f) Spacer(Modifier.weight(line.inset))
                line.keys.forEach {
                    if (it.token == PAGE) {
                        // Where a phone keyboard says 1/2, and for the same
                        // reason: the second page of everything that is not a
                        // letter, one tap away and one tap back.
                        KeyCapText(
                            if (layer == Layer.SYMBOLS) "1/2" else "2/2",
                            special, capText, Modifier.weight(it.weight).padding(horizontal = 2.dp), line.height,
                        ) {
                            layer = if (layer == Layer.SYMBOLS) Layer.FUNCTIONS else Layer.SYMBOLS
                        }
                    } else {
                        key(it.token, it.weight, line.height)
                    }
                }
                if (line.inset > 0f) Spacer(Modifier.weight(line.inset))
            }
        }
        // Where a phone keyboard puts the emoji key, a terminal wants Ctrl. The
        // comma keeps its place beside it, and space, full stop and enter keep
        // theirs, so the row still reads the way the hand expects.
        Row(Modifier.fillMaxWidth()) {
            // Letters and symbols under one key, as on a phone: from either
            // page away from the letters this reads abc and goes straight
            // back, rather than being the next stop on a cycle through three.
            KeyCapText(
                if (layer == Layer.LETTERS) "?123" else "abc",
                special, capText, Modifier.weight(1.35f).padding(horizontal = 2.dp), CAP_HEIGHT,
            ) {
                layer = if (layer == Layer.LETTERS) Layer.SYMBOLS else Layer.LETTERS
            }
            key("CTRL", 1.2f)
            key(",", 0.9f)
            key(" ", 4.25f)
            key(".", 0.9f)
            key("ENTER", 1.4f)
        }
    }
}
