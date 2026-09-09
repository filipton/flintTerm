package dev.flint.term.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * The lines this session has already sent from the compose field, newest first.
 *
 * A prompt is rarely right the first time, and the next one is usually the last
 * one with a sentence changed — so what was sent stays reachable rather than
 * having to be typed again. A line sent twice in a row is kept once: two
 * identical entries only make the walk back longer.
 */
object ComposeHistory {
    /** Far enough back to find this morning's prompt, short enough to walk. */
    const val LIMIT = 20

    fun remember(history: List<String>, line: String): List<String> {
        val text = line.trim()
        if (text.isEmpty() || history.firstOrNull() == text) return history
        return (listOf(text) + history).take(LIMIT)
    }
}

/**
 * What one session's compose field is holding.
 *
 * It lives outside the composition because the field is a place to write, and
 * writing gets interrupted: opening the file browser, answering a message,
 * looking something up. Coming back to a lost half-written prompt would teach
 * everyone to write it somewhere else instead.
 */
class ComposeState {
    var open by mutableStateOf(false)
    var text by mutableStateOf("")

    /** Sent lines, newest first. */
    var history by mutableStateOf(emptyList<String>())
}

/** The compose state of each session, kept for as long as the setting says. */
object ComposeLines {
    private val states = HashMap<String, ComposeState>()

    fun of(sessionId: String): ComposeState = states.getOrPut(sessionId) { ComposeState() }

    fun forget(sessionId: String) {
        states.remove(sessionId)
    }
}

/**
 * A line written here, sent to the session in one piece.
 *
 * Typing a long prompt for an agent straight into a terminal is miserable:
 * there is no autocorrect, no suggestions, no voice input, and no way back to
 * fix the third line of it. This is an ordinary Android text field with none of
 * that turned off, so the keyboard does what it does everywhere else and only
 * the finished text reaches the shell — as a paste, which a program that asked
 * for bracketed paste sees as text rather than as somebody typing very fast.
 *
 * Send adds the Enter that runs it; a long press sends the text and leaves the
 * cursor at the end of it, for a prompt still being thought about or for a
 * program that wants its own submit key. The field keeps focus either way,
 * because the next prompt usually follows the answer straight away.
 */
@Composable
fun ComposeLine(
    state: ComposeState,
    chrome: Color,
    onChrome: Color,
    modifier: Modifier = Modifier,
    onSend: (text: String, enter: Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val haptic = LocalHapticFeedback.current
    // How far ↑ has walked back, or -1 when what is in the field is the user's
    // own writing rather than something the history put there.
    var recalled by remember(state) { mutableStateOf(-1) }

    LaunchedEffect(state) { runCatching { focus.requestFocus() } }

    fun send(enter: Boolean) {
        val text = state.text
        if (text.isBlank()) return
        onSend(text, enter)
        state.history = ComposeHistory.remember(state.history, text)
        state.text = ""
        recalled = -1
    }

    /** One step further back, stopping at the oldest line rather than wrapping. */
    fun recall() {
        val at = (recalled + 1).coerceAtMost(state.history.lastIndex)
        if (at < 0) return
        recalled = at
        state.text = state.history[at]
    }

    Row(
        modifier.background(chrome).padding(start = 4.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.Close, "Close the compose line", Modifier.size(17.dp), tint = onChrome.copy(alpha = 0.6f))
        }
        Box(
            Modifier
                .weight(1f)
                .heightIn(min = 36.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(onChrome.copy(alpha = 0.08f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (state.text.isEmpty()) {
                Text(
                    "Write a line",
                    style = MaterialTheme.typography.bodyMedium,
                    color = onChrome.copy(alpha = 0.45f),
                )
            }
            BasicTextField(
                value = state.text,
                onValueChange = { new ->
                    // Once it has been edited it is no longer the history's line,
                    // so ↑ goes back to moving the cursor.
                    if (new != state.history.getOrNull(recalled)) recalled = -1
                    state.text = new
                },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = onChrome),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                maxLines = 6,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send(enter = true) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    // A hardware ↑ walks the history while the field is showing
                    // one of its lines or nothing at all; once there is writing
                    // of one's own in it, the key belongs to the cursor again.
                    .onPreviewKeyEvent { event ->
                        val walking = recalled >= 0 || state.text.isEmpty()
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp && walking) {
                            recall()
                            true
                        } else {
                            false
                        }
                    },
            )
        }
        Spacer(Modifier.width(2.dp))
        IconButton(onClick = ::recall, enabled = state.history.isNotEmpty(), modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Rounded.KeyboardArrowUp,
                "Previous line",
                Modifier.size(20.dp),
                tint = onChrome.copy(alpha = if (state.history.isEmpty()) 0.25f else 0.7f),
            )
        }
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = if (state.text.isBlank()) 0.12f else 0.9f))
                .pointerInput(state) {
                    detectTapGestures(
                        onTap = { send(enter = true) },
                        onLongPress = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            send(enter = false)
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.Send,
                "Send, or hold to send without Enter",
                Modifier.size(17.dp),
                tint = if (state.text.isBlank()) onChrome.copy(alpha = 0.4f) else MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}
