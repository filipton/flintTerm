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

private class KeyboardRow(val keys: List<Key>, val inset: Float = 0f)

private fun row(vararg tokens: String, inset: Float = 0f) = KeyboardRow(tokens.map { Key(it) }, inset)

private val LETTERS = listOf(
    row("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
    row("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
    // The half-key step that puts a under q and s under w, as on every phone.
    row("a", "s", "d", "f", "g", "h", "j", "k", "l", inset = 0.5f),
    KeyboardRow(
        listOf(Key("SHIFT", 1.5f)) + listOf("z", "x", "c", "v", "b", "n", "m").map { Key(it) } + Key("BKSP", 1.5f),
    ),
)

private val SYMBOLS = listOf(
    row("~", "`", "|", "\\", "/", "<", ">", "[", "]", "="),
    row("!", "@", "#", "$", "%", "^", "&", "*", "(", ")"),
    row("-", "_", "+", "{", "}", ":", ";", "'", "\"", inset = 0.5f),
    KeyboardRow(
        listOf(Key("ALT", 1.5f)) + listOf("?", ",", ".", "STAB", "TAB", "ESC", "DEL").map { Key(it) } + Key("BKSP", 1.5f),
    ),
)

// F keys, arrows and the rest, so the bar's overflow is not the only way to
// reach them while this keyboard is up.
private val FUNCTIONS = listOf(
    row("F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10"),
    row("F11", "F12", "ESC", "TAB", "STAB", "INS", "DEL", "HOME", "END", "ENTER"),
    row("LEFT", "DOWN", "UP", "RIGHT", "PGUP", "PGDN", "NAV", "SEARCH", "PASTE", inset = 0.5f),
    KeyboardRow(
        listOf(Key("ALT", 1.5f)) + listOf("SHIFT", "SNIPPETS", "COMPOSE", "FILE", "KEYBOARD", ",", ".").map { Key(it) } + Key("BKSP", 1.5f),
    ),
)

/** Uniform, so no row is a hair shorter than the one above it. */
private val CAP_HEIGHT = 46.dp

/** Keys that do something rather than type something, drawn a shade darker. */
private val SPECIAL = setOf("SHIFT", "BKSP", "ENTER", "CTRL", "ALT", "ESC", "TAB", "STAB", "DEL", "INS")

/** What a key reads as here, where a word would not fit or a glyph says it better. */
private val LABELS = mapOf("SHIFT" to "⇧", "ENTER" to "⏎", "BKSP" to "⌫")

/** The three layers, and what the bottom-left key says to get to the next one. */
private enum class Layer(val next: String) { LETTERS("?123"), SYMBOLS("Fn"), FUNCTIONS("abc") }

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
    /** Whether the digits get a row of their own, as on a phone keyboard. */
    numberRow: Boolean = true,
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
    fun RowScope.key(token: String, weight: Float) {
        val isSpecial = token in SPECIAL
        KeyFor(
            token,
            actions,
            mods,
            if (isSpecial) special else cap,
            capText,
            Modifier.weight(weight).padding(horizontal = 2.dp),
            compact = true,
            capHeight = CAP_HEIGHT,
            labelOverride = LABELS[token],
        )
    }

    Column(modifier.background(chrome).padding(horizontal = 3.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        val rows = when (layer) {
            Layer.LETTERS -> if (numberRow) LETTERS else LETTERS.drop(1)
            Layer.SYMBOLS -> SYMBOLS
            Layer.FUNCTIONS -> FUNCTIONS
        }
        rows.forEach { line ->
            Row(Modifier.fillMaxWidth()) {
                if (line.inset > 0f) Spacer(Modifier.weight(line.inset))
                line.keys.forEach { key(it.token, it.weight) }
                if (line.inset > 0f) Spacer(Modifier.weight(line.inset))
            }
        }
        // Where a phone keyboard puts the emoji key, a terminal wants Ctrl. The
        // comma keeps its place beside it, and space, full stop and enter keep
        // theirs, so the row still reads the way the hand expects.
        Row(Modifier.fillMaxWidth()) {
            KeyCapText(layer.next, special, capText, Modifier.weight(1.35f).padding(horizontal = 2.dp), CAP_HEIGHT) {
                layer = Layer.entries[(layer.ordinal + 1) % Layer.entries.size]
            }
            key("CTRL", 1.2f)
            key(",", 0.9f)
            key(" ", 4.25f)
            key(".", 0.9f)
            key("ENTER", 1.4f)
        }
    }
}
