package dev.flint.term.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.flint.term.core.KeyCode
import dev.flint.term.terminal.ExtraKeys
import dev.flint.term.terminal.ModState
import dev.flint.term.terminal.TerminalView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Key bar above the soft keyboard: sticky modifiers, arrows and the characters
 * that are painful on a phone keyboard. Coloured to sit on the terminal chrome.
 */
@Composable
fun ExtraKeysBar(
    view: TerminalView,
    chrome: Color,
    onChrome: Color,
    modifier: Modifier = Modifier,
    row1: List<String> = ExtraKeys.defaultRow1,
    row2: List<String> = ExtraKeys.defaultRow2,
    onSnippets: () -> Unit = {},
    onSearch: () -> Unit = {},
    onCompose: () -> Unit = {},
    /** Holding Ctrl opens the chords sheet; null leaves the long press as a lock. */
    onChords: (() -> Unit)? = null,
) {
    val mods by view.modifiers.collectAsStateWithLifecycle()
    // The picked files take the path files dropped on the terminal already
    // take: uploaded to the host, with the name typed where the cursor is.
    val pickToInsert = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) view.onFilesDropped?.invoke(uris)
    }
    view.onInsertFile = { pickToInsert.launch(arrayOf("*/*")) }
    val cap = onChrome.copy(alpha = 0.08f)
    val capText = onChrome.copy(alpha = 0.85f)

    @Composable
    fun androidx.compose.foundation.layout.RowScope.render(token: String, weight: Float?) {
        val def = ExtraKeys.resolve(token)
        val m = if (weight != null) Modifier.weight(weight) else Modifier
        when (val a = def.action) {
            is ExtraKeys.Action.Modifier -> {
                val state = when (a.which) { 'c' -> mods.ctrl; 'a' -> mods.alt; else -> mods.shift }
                val chords = onChords?.takeIf { a.which == 'c' }
                ModifierCap(def.label, state, cap, capText, m, onTap = { view.toggleModifier(a.which) }, onLong = { chords?.invoke() ?: view.toggleModifier(a.which, lock = true) })
            }
            is ExtraKeys.Action.Key -> if (def.repeat) {
                RepeatCap(when (token) { "UP" -> Icons.Rounded.KeyboardArrowUp; "DOWN" -> Icons.Rounded.KeyboardArrowDown; "LEFT" -> Icons.Rounded.KeyboardArrowLeft; else -> Icons.Rounded.KeyboardArrowRight }, def.label, cap, capText, m) { view.sendKey(a.code, a.ctrl, a.alt, a.shift) }
            } else {
                KeyCap(def.label, cap, capText, m, mono = def.mono) { view.sendKey(a.code, a.ctrl, a.alt, a.shift) }
            }
            is ExtraKeys.Action.Text -> KeyCap(def.label, cap, capText, m, mono = def.mono) { view.sendText(a.text) }
            ExtraKeys.Action.Snippets -> KeyCap(def.label, cap, capText, m, mono = true, accent = true) { onSnippets() }
            ExtraKeys.Action.Search -> KeyCap(def.label, cap, capText, m, mono = true) { onSearch() }
            ExtraKeys.Action.ToggleKeyboard -> KeyCap(def.label, cap, capText, m, mono = true) { view.toggleKeyboard() }
            ExtraKeys.Action.Paste -> KeyCap(def.label, cap, capText, m) { view.paste() }
            ExtraKeys.Action.InsertFile -> KeyCap(def.label, cap, capText, m, mono = true) { pickToInsert.launch(arrayOf("*/*")) }
            ExtraKeys.Action.Compose -> KeyCap(def.label, cap, capText, m, mono = true) { onCompose() }
            ExtraKeys.Action.Nav -> NavCap(def.label, cap, capText, m, view)
        }
    }

    Column(modifier.background(chrome).padding(horizontal = 6.dp, vertical = 5.dp)) {
        if (row1.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                row1.forEach { t -> render(t, if (t == "SHIFT") 1.1f else if (t in setOf("UP", "DOWN", "LEFT", "RIGHT")) 0.85f else 1f) }
            }
        }
        if (row2.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(top = if (row1.isNotEmpty()) 5.dp else 0.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                row2.forEach { t -> render(t, null) }
            }
        }
    }
}

private val CapShape = RoundedCornerShape(10.dp)

@Composable
private fun KeyCap(label: String, cap: Color, text: Color, modifier: Modifier = Modifier, mono: Boolean = false, accent: Boolean = false, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .height(38.dp)
            .widthIn(min = if (mono) 38.dp else 46.dp)
            .clip(CapShape)
            .background(if (accent) primary.copy(alpha = 0.22f) else cap)
            .pointerInput(onClick) {
                detectTapGestures(onPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                })
            }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = if (mono) 16.sp else 12.sp,
            fontFamily = if (mono) MonoFamily else null,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = if (mono) 0.sp else 0.4.sp,
            color = if (accent) primary else text,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
private fun ModifierCap(label: String, state: ModState, cap: Color, text: Color, modifier: Modifier = Modifier, onTap: () -> Unit, onLong: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val primary = MaterialTheme.colorScheme.primary
    val (bg, fg) = when (state) {
        ModState.OFF -> cap to text
        ModState.ONCE -> primary.copy(alpha = 0.28f) to primary
        ModState.LOCKED -> primary to MaterialTheme.colorScheme.onPrimary
    }
    Box(
        modifier
            .height(38.dp)
            .clip(CapShape)
            .background(bg)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onTap() },
                    onLongPress = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLong() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp, color = fg, maxLines = 1)
    }
}

/**
 * The cap you drag instead of tapping: hold it and slide, and the cursor walks
 * with your thumb — the same translation the two-finger gesture on the terminal
 * uses, so one arrow cap does the work of four and a lot of tapping.
 *
 * Every movement is consumed so the row it sits in cannot decide halfway
 * through that the drag was meant to scroll it sideways.
 */
@Composable
private fun NavCap(label: String, cap: Color, text: Color, modifier: Modifier = Modifier, view: TerminalView) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier
            .height(38.dp)
            .widthIn(min = 38.dp)
            .clip(CapShape)
            .background(cap)
            .pointerInput(view) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    view.navDragStart(down.position.x, down.position.y)
                    while (true) {
                        val finger = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!finger.pressed) break
                        view.navDragMove(finger.position.x, finger.position.y)
                        finger.consume()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 16.sp, fontFamily = MonoFamily, fontWeight = FontWeight.SemiBold, color = text, maxLines = 1)
    }
}

@Composable
private fun RepeatCap(icon: ImageVector, label: String, cap: Color, text: Color, modifier: Modifier = Modifier, onKey: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    Box(
        modifier
            .height(38.dp)
            .clip(CapShape)
            .background(cap)
            .pointerInput(onKey) {
                awaitEachGesture {
                    awaitFirstDown()
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onKey()
                    val repeater = scope.launch {
                        delay(400)
                        while (isActive) {
                            onKey()
                            delay(45)
                        }
                    }
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        repeater.cancel()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = text)
    }
}
