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
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
    /** The ⋯ cap at the end of the first row; null leaves it off. */
    onMore: (() -> Unit)? = null,
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

    val actions = BarActions(view, onSnippets, onSearch, onCompose, onChords) { pickToInsert.launch(arrayOf("*/*")) }

    @Composable
    fun androidx.compose.foundation.layout.RowScope.render(token: String, weight: Float?) {
        KeyFor(token, actions, mods, cap, capText, if (weight != null) Modifier.weight(weight) else Modifier)
    }


    Column(modifier.background(chrome).padding(horizontal = 6.dp, vertical = 5.dp)) {
        if (row1.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                row1.forEach { t -> render(t, if (t == "SHIFT") 1.1f else if (t in ExtraKeys.ARROWS) 0.85f else 1f) }
                // Fixed at the end and never scrolled away: it is the way to
                // everything the row had no space for, so it cannot itself be
                // the thing that scrolled off.
                if (onMore != null) {
                    KeyCap("⋯", cap, capText, Modifier.width(38.dp), mono = true) { onMore() }
                }
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
private fun KeyCap(label: String, cap: Color, text: Color, modifier: Modifier = Modifier, mono: Boolean = false, accent: Boolean = false, compact: Boolean = false, onClick: () -> Unit) {
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
            .padding(horizontal = if (compact) 2.dp else 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = if (compact) 11.sp else if (mono) 16.sp else 12.sp,
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
private fun ModifierCap(
    label: String,
    state: ModState,
    cap: Color,
    text: Color,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
    onLong: () -> Unit,
    /** Letters offered on a press-and-slide; empty leaves the long press alone. */
    slideTargets: List<Char> = emptyList(),
    onSlide: (Char) -> Unit = {},
) {
    val haptic = LocalHapticFeedback.current
    val primary = MaterialTheme.colorScheme.primary
    val (bg, fg) = when (state) {
        ModState.OFF -> cap to text
        ModState.ONCE -> primary.copy(alpha = 0.28f) to primary
        ModState.LOCKED -> primary to MaterialTheme.colorScheme.onPrimary
    }
    // Where the finger is, in window coordinates, so the strip above can say
    // which of its cells it is over. The cap only knows where it is itself.
    var capLeft by remember { mutableFloatStateOf(0f) }
    var sliding by remember { mutableStateOf(false) }
    var slideAt by remember { mutableFloatStateOf(0f) }
    val width = LocalConfiguration.current.screenWidthDp
    val density = LocalDensity.current
    val screenPx = with(density) { width.dp.toPx() }
    // A plain function of a position rather than a remembered value: the gesture
    // callbacks are built once, so anything they close over is whatever it was
    // when the finger went down, and the answer has to be worked out when the
    // finger lifts instead.
    val pick: (Float) -> Char? = { at ->
        if (slideTargets.isEmpty()) {
            null
        } else {
            val cell = screenPx / slideTargets.size
            slideTargets.getOrNull((at / cell).toInt().coerceIn(0, slideTargets.lastIndex))
        }
    }
    val hovered = if (sliding) pick(slideAt) else null
    if (sliding) SlideStrip(slideTargets, hovered)
    Box(
        modifier
            .height(38.dp)
            .clip(CapShape)
            .background(bg)
            .then(
                if (slideTargets.isEmpty()) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onTap() },
                            onLongPress = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLong() },
                        )
                    }
                } else {
                    // Two detectors rather than one hand-rolled loop: the tap is
                    // the ordinary arm-the-modifier, and the long press turns
                    // into a drag that picks from the strip. Both are the stock
                    // gesture helpers, which is why the timing matches every
                    // other long press on the phone.
                    Modifier
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onTap() },
                            )
                        }
                        .pointerInput(slideTargets) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { at ->
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    sliding = true
                                    slideAt = capLeft + at.x
                                },
                                onDragEnd = {
                                    val picked = pick(slideAt)
                                    sliding = false
                                    picked?.let { onSlide(it) }
                                },
                                onDragCancel = { sliding = false },
                                onDrag = { change, amount ->
                                    change.consume()
                                    slideAt += amount.x
                                },
                            )
                        }
                },
            )
            .onGloballyPositioned { capLeft = it.positionInWindow().x },
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
private fun RepeatCap(icon: ImageVector?, label: String, cap: Color, text: Color, modifier: Modifier = Modifier, onKey: () -> Unit) {
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
        if (icon != null) {
            Icon(icon, label, tint = text)
        } else {
            Text(label, fontSize = 16.sp, fontFamily = MonoFamily, fontWeight = FontWeight.SemiBold, color = text, maxLines = 1)
        }
    }
}

/** A cap that is not a key at all: it does something to the keyboard drawing it. */
@Composable
fun KeyCapText(label: String, cap: Color, text: Color, modifier: Modifier = Modifier, onTap: () -> Unit) {
    KeyCap(label, cap, text, modifier, onClick = onTap)
}

/** The letters a held Ctrl offers, in the order the shell needs them. */
private val CTRL_SLIDE = listOf('c', 'd', 'z', 'l', 'a', 'e', 'r', 'w', 'u', 'k')

/** What a cap can reach besides the terminal itself. */
class BarActions(
    val view: TerminalView,
    val onSnippets: () -> Unit,
    val onSearch: () -> Unit,
    val onCompose: () -> Unit,
    val onChords: (() -> Unit)?,
    /** Whether holding Ctrl offers its common targets to slide onto. */
    val slideCtrl: Boolean = true,
    val onInsertFile: () -> Unit,
)

/**
 * One cap, wherever it is drawn.
 *
 * The bar and the overflow pad show the same keys and must do the same thing
 * with them, so what a token means lives here rather than in either of them.
 */
@Composable
fun KeyFor(
    token: String,
    actions: BarActions,
    mods: dev.flint.term.terminal.Modifiers,
    cap: Color,
    capText: Color,
    modifier: Modifier = Modifier,
    /** Ten caps across a phone leaves little room; trims the padding and the text. */
    compact: Boolean = false,
) {
    val view = actions.view
    val def = ExtraKeys.resolve(token)
    when (val a = def.action) {
        is ExtraKeys.Action.Modifier -> {
            val state = when (a.which) { 'c' -> mods.ctrl; 'a' -> mods.alt; else -> mods.shift }
            val chords = actions.onChords?.takeIf { a.which == 'c' }
            ModifierCap(
                def.label, state, cap, capText, modifier,
                onTap = { view.toggleModifier(a.which) },
                onLong = { chords?.invoke() ?: view.toggleModifier(a.which, lock = true) },
                slideTargets = if (a.which == 'c' && actions.slideCtrl) CTRL_SLIDE else emptyList(),
                onSlide = { c -> view.sendKey(dev.flint.term.core.KeyCode.Char(c.code.toUInt()), ctrl = true) },
            )
        }
        is ExtraKeys.Action.Key -> if (def.repeat) {
            // Only the arrows have an icon. Backspace repeats too, and drawing
            // it with the fall-through icon made it a second right arrow.
            RepeatCap(
                when (token) {
                    "UP" -> Icons.Rounded.KeyboardArrowUp
                    "DOWN" -> Icons.Rounded.KeyboardArrowDown
                    "LEFT" -> Icons.Rounded.KeyboardArrowLeft
                    "RIGHT" -> Icons.Rounded.KeyboardArrowRight
                    else -> null
                },
                def.label, cap, capText, modifier,
            ) { view.sendKey(a.code, a.ctrl, a.alt, a.shift) }
        } else {
            KeyCap(def.label, cap, capText, modifier, mono = def.mono, compact = compact) { view.sendKey(a.code, a.ctrl, a.alt, a.shift) }
        }
        is ExtraKeys.Action.Text -> KeyCap(def.label, cap, capText, modifier, mono = def.mono, compact = compact) { view.sendText(a.text) }
        ExtraKeys.Action.Snippets -> KeyCap(def.label, cap, capText, modifier, mono = true, accent = true, compact = compact) { actions.onSnippets() }
        ExtraKeys.Action.Search -> KeyCap(def.label, cap, capText, modifier, mono = true, compact = compact) { actions.onSearch() }
        ExtraKeys.Action.ToggleKeyboard -> KeyCap(def.label, cap, capText, modifier, mono = true, compact = compact) { view.toggleKeyboard() }
        ExtraKeys.Action.Paste -> KeyCap(def.label, cap, capText, modifier, compact = compact) { view.paste() }
        ExtraKeys.Action.InsertFile -> KeyCap(def.label, cap, capText, modifier, mono = true, compact = compact) { actions.onInsertFile() }
        ExtraKeys.Action.Compose -> KeyCap(def.label, cap, capText, modifier, mono = true, compact = compact) { actions.onCompose() }
        ExtraKeys.Action.Nav -> NavCap(def.label, cap, capText, modifier, view)
    }
}

/**
 * Everything the bar has no room for, as one pad.
 *
 * The second row exists because a phone is narrow, and it costs a line of
 * terminal for keys most people press once an hour. This is the other answer:
 * one row on screen, and a ⋯ that opens the rest over the keyboard for as long
 * as it is wanted. The system keyboard goes away while it is up, because the
 * two would otherwise fight for the same half of the screen.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExtraKeysPad(
    view: TerminalView,
    onDismiss: () -> Unit,
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
    val cap = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val capText = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // The pad is the keyboard while it is open.
    LaunchedEffect(Unit) { view.hideKeyboard() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        // About the height a keyboard would take, and no more: the point is to
        // see what you are typing into while you type it.
        Column(
            Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 24.dp)
                .heightIn(max = 330.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            PadSection("Function", (1..12).map { "F$it" }, actions, mods, cap, capText)
            PadSection("Moving about", listOf("UP", "DOWN", "LEFT", "RIGHT", "HOME", "END", "PGUP", "PGDN", "NAV"), actions, mods, cap, capText)
            PadSection("Editing", listOf("ESC", "TAB", "STAB", "ENTER", "BKSP", "DEL", "INS", "CTRL", "ALT", "SHIFT"), actions, mods, cap, capText)
            PadSection("Doing", listOf("SNIPPETS", "PASTE", "SEARCH", "FILE", "COMPOSE", "KEYBOARD"), actions, mods, cap, capText)
            PadSection(
                "Symbols",
                listOf("-", "_", "/", "\\", "|", "~", ":", ";", "'", "\"", "`", "(", ")", "[", "]", "{", "}", "<", ">", "^", "#", "@", "$", "!", "&", "*", "+", "=", "%", "?", ","),
                actions, mods, cap, capText,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PadSection(
    title: String,
    tokens: List<String>,
    actions: BarActions,
    mods: dev.flint.term.terminal.Modifiers,
    cap: Color,
    capText: Color,
) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 6.dp),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        tokens.forEach { KeyFor(it, actions, mods, cap, capText, Modifier.widthIn(min = 46.dp)) }
    }
}

/**
 * The strip of Ctrl targets shown while a finger is held on the Ctrl cap.
 *
 * A popup rather than part of the bar, so it can sit above it without the bar
 * growing, and full width so that mapping a finger to a cell is a division
 * rather than a hit test against something that may have scrolled.
 */
@Composable
private fun SlideStrip(targets: List<Char>, hovered: Char?) {
    if (targets.isEmpty()) return
    Popup(alignment = Alignment.TopCenter, offset = IntOffset(0, 0)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            targets.forEach { c ->
                val on = c == hovered
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "^${c.uppercaseChar()}",
                        fontSize = 15.sp,
                        fontFamily = MonoFamily,
                        fontWeight = FontWeight.Bold,
                        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
