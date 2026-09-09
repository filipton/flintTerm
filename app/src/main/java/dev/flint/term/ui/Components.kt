package dev.flint.term.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.flint.term.core.SessionState
import dev.flint.term.data.Host
import dev.flint.term.data.HostIcon

// ---------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------

/** Screen header: optional back arrow, a bold title with optional subtitle, trailing actions. */
@Composable
fun AppHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    large: Boolean = onBack == null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(if (large) 56.dp else 52.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            } else {
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = if (large) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            actions()
        }
    }
}

// ---------------------------------------------------------------------------
// Grouped rows (the "settings card" idiom)
// ---------------------------------------------------------------------------

@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 24.dp, end = 20.dp, top = 18.dp, bottom = 6.dp),
    )
}

/**
 * The color a [Group] sits on. A divider is a sliver of this between two rows,
 * so a group drawn on something other than the page background — a sheet, a
 * dialog — says so here, or its gaps show through in the wrong color.
 */
val LocalBackdrop = compositionLocalOf<Color?> { null }

/** Corner radius of a group's outside; the inside corners a divider cuts are [InnerCorner]. */
private val GroupCorner = 20.dp
private val InnerCorner = 5.dp
private val RowGap = 2.dp

/**
 * A rounded container holding rows, each its own rounded segment.
 *
 * Rows are separated by a sliver of page background rather than a hairline:
 * each row reads as a thing of its own, the way lists are drawn on the current
 * Android, and a tall group no longer looks like one undifferentiated slab.
 */
@Composable
fun Group(
    title: String? = null,
    modifier: Modifier = Modifier,
    /** Makes the label itself a control — used by the host list to open a group. */
    onTitleClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            GroupLabel(title, if (onTitleClick != null) Modifier.clickable(onClick = onTitleClick) else Modifier)
        }
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(GroupCorner))
                .background(MaterialTheme.colorScheme.surfaceContainer),
            content = content,
        )
    }
}

/**
 * The gap between two rows of a [Group].
 *
 * It paints the page background across the group and rounds the corners of
 * the rows on either side by drawing the same color over them — the group is
 * one clipped column, so this is the only place that knows where a row ends.
 */
@Composable
fun RowDivider() {
    val backdrop = LocalBackdrop.current ?: MaterialTheme.colorScheme.background
    Canvas(Modifier.fillMaxWidth().height(RowGap)) {
        val r = InnerCorner.toPx()
        val w = size.width
        val h = size.height
        drawRect(backdrop)
        // Four bites out of the neighboring rows: the bottom corners of the row
        // above, the top corners of the row below.
        fun bite(x: Float, y: Float, cx: Float, cy: Float) {
            val square = Path().apply { addRect(Rect(x, y, x + r, y + r)) }
            val disc = Path().apply { addOval(Rect(cx - r, cy - r, cx + r, cy + r)) }
            drawPath(Path.combine(PathOperation.Difference, square, disc), backdrop)
        }
        bite(0f, -r, r, -r)
        bite(w - r, -r, w - r, -r)
        bite(0f, h, r, h + r)
        bite(w - r, h, w - r, h + r)
    }
}

/**
 * Standard row: tinted icon tile, title, optional subtitle, optional trailing slot.
 *
 * A row with [onCheckedChange] draws its own switch and toggles on a tap
 * anywhere along it — the switch is small and the row is wide, and aiming for
 * the one when the other would do is the sort of thing that makes a list feel
 * fussy.
 */
@Composable
fun GroupRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    subtitleMono: Boolean = false,
    checked: Boolean? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val toggle = if (checked != null && onCheckedChange != null) ({ onCheckedChange(!checked) }) else null
    val click = onClick ?: toggle
    Row(
        (if (click != null) Modifier.clickable(onClick = click) else Modifier)
            .fillMaxWidth()
            .defaultMinSize(minHeight = 50.dp)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            IconTile(icon, iconTint, size = 30, corner = 9)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = titleColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = if (subtitleMono) CodeStyle.copy(fontSize = 12.sp, lineHeight = 16.sp) else MaterialTheme.typography.bodySmall.copy(lineHeight = 16.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        if (trailing != null || checked != null) {
            Spacer(Modifier.width(12.dp))
            if (trailing != null) trailing()
            if (checked != null && onCheckedChange != null) AppSwitch(checked, onCheckedChange)
        }
    }
}

/**
 * The app's switch: Material's, drawn a little smaller. At full size it is the
 * heaviest thing on a row of settings and pulls the eye from the words.
 */
@Composable
fun AppSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = modifier.scale(0.8f).height(28.dp),
    )
}

@Composable
fun IconTile(icon: ImageVector, tint: Color, size: Int = 32, corner: Int = 10) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape(corner.dp)).background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size((size * 0.55f).dp), tint = tint)
    }
}

// ---------------------------------------------------------------------------
// Host bits
// ---------------------------------------------------------------------------

fun iconFor(icon: HostIcon): ImageVector = when (icon) {
    HostIcon.SERVER -> Icons.Rounded.Dns
    HostIcon.CLOUD -> Icons.Rounded.Cloud
    HostIcon.PI -> Icons.Rounded.Memory
    HostIcon.LAPTOP -> Icons.Rounded.Laptop
    HostIcon.DESKTOP -> Icons.Rounded.Computer
    HostIcon.ROUTER -> Icons.Rounded.Router
    HostIcon.DATABASE -> Icons.Rounded.Storage
    HostIcon.CONTAINER -> Icons.Rounded.ViewInAr
    HostIcon.NAS -> Icons.Rounded.Storage
    HostIcon.HOME -> Icons.Rounded.Home
}

@Composable
fun HostGlyph(host: Host, size: Int = 44) {
    val accent = accentFor(host.id, host.color)
    IconTile(iconFor(host.icon), accent, size = size, corner = (size * 0.32f).toInt())
}

@Composable
fun StatusDot(state: SessionState, size: Int = 9) {
    val color = when (state) {
        is SessionState.Connected -> Status.online
        is SessionState.Connecting -> Status.busy
        is SessionState.Disconnected -> Status.offline
    }
    val pulse = rememberInfiniteTransition(label = "pulse")
    val alpha by pulse.animateFloat(
        initialValue = 1f, targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "alpha",
    )
    Box(
        Modifier
            .size(size.dp)
            .alpha(if (state is SessionState.Connecting) alpha else 1f)
            .clip(CircleShape)
            .background(color),
    )
}

// ---------------------------------------------------------------------------
// Inputs
// ---------------------------------------------------------------------------

@Composable
fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    mono: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    minLines: Int = 1,
    /**
     * Where a multi-line field stops growing and scrolls instead. A dialog's
     * buttons live under its field, and a pasted file that is taller than the
     * screen would push them under the keyboard.
     */
    maxLines: Int = Int.MAX_VALUE,
    /** Non-null turns the border red and prints the reason underneath. */
    error: String? = null,
    /** Gray hint under the field; hidden while an [error] is showing. */
    hint: String? = null,
    /**
     * What a password manager should see this field as.
     *
     * Naming it is the whole of the integration: the app never reads or keeps
     * anything the manager fills, it only stops making the manager guess from
     * a label.
     */
    autofill: ContentType? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .then(autofill?.let { t -> Modifier.semantics { contentType = t } } ?: Modifier),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) } },
        singleLine = singleLine,
        minLines = minLines,
        maxLines = if (singleLine) 1 else maxLines,
        isError = error != null,
        supportingText = (error ?: hint)?.let {
            {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        textStyle = if (mono) CodeStyle.copy(fontSize = 14.sp) else MaterialTheme.typography.bodyLarge,
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        trailingIcon = trailing,
        leadingIcon = leading,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            focusedLabelColor = MaterialTheme.colorScheme.primary,
            errorBorderColor = MaterialTheme.colorScheme.error,
            errorContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    )
}

@Composable
fun Segmented(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { i, label ->
            SegmentedButton(
                selected = selected == i,
                onClick = { onSelect(i) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                icon = { if (selected == i) Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) },
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    inactiveContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) { Text(label, maxLines = 1) }
        }
    }
}

/** Horizontal picker of accent colors, shown as filled circles with a ring on the selection. */
@Composable
fun ColorPicker(selected: Color?, onSelect: (Color) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        HostAccents.forEach { c ->
            val isSel = selected == c
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(c.copy(alpha = if (isSel) 1f else 0.55f))
                    .then(if (isSel) Modifier.border(2.5.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                    .clickable { onSelect(c) },
                contentAlignment = Alignment.Center,
            ) {
                if (isSel) Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = Color.Black.copy(alpha = 0.7f))
            }
        }
    }
}

/** A pill-shaped filter field, the same on every list that has one. */
@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)
            BasicTextField(
                value, onChange, singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            )
        }
        if (value.isNotEmpty()) {
            IconButton(onClick = { onChange("") }, Modifier.size(28.dp)) { Icon(Icons.Rounded.Close, "Clear", Modifier.size(18.dp)) }
        }
    }
}

// ---------------------------------------------------------------------------
// Sheets & empty states
// ---------------------------------------------------------------------------

data class SheetAction(
    val label: String,
    val icon: ImageVector? = null,
    val danger: Boolean = false,
    val subtitle: String? = null,
    /**
     * Heading to draw above this action, when it begins a run of related ones.
     * Repeating the same text on the actions that follow keeps them under the
     * one heading; the sheet only draws it where it changes.
     */
    val section: String? = null,
    val onClick: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(
    onDismiss: () -> Unit,
    title: String? = null,
    subtitle: String? = null,
    header: (@Composable () -> Unit)? = null,
    actions: List<SheetAction>,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        // The sheet is as tall as the screen and no taller, and these lists have
        // grown past it — the terminal's menu is two dozen rows in four groups.
        // Without a scroll here the tail of the list is simply unreachable.
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 20.dp)) {
            if (header != null) {
                header()
            } else if (title != null) {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(8.dp))
            }
            var section: String? = null
            actions.forEach { a ->
                if (a.section != null && a.section != section) {
                    Text(
                        a.section,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 24.dp, top = 10.dp, bottom = 2.dp),
                    )
                }
                section = a.section
                val tint = if (a.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { a.onClick() }
                        .defaultMinSize(minHeight = 48.dp)
                        .padding(horizontal = 24.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (a.icon != null) {
                        Icon(a.icon, null, Modifier.size(22.dp), tint = if (a.danger) tint else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(18.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(a.label, color = tint, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        if (a.subtitle != null) {
                            Text(
                                a.subtitle, style = MaterialTheme.typography.bodySmall.copy(lineHeight = 16.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(84.dp).clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.defaultMinSize(minHeight = 1.dp),
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** "3 min ago" style timestamp. */
fun relativeTime(millis: Long, now: Long = System.currentTimeMillis()): String? {
    if (millis <= 0) return null
    val d = (now - millis) / 1000
    return when {
        d < 60 -> "just now"
        d < 3600 -> "${d / 60} min ago"
        d < 86_400 -> "${d / 3600} h ago"
        d < 86_400 * 30 -> "${d / 86_400} d ago"
        else -> "${d / (86_400 * 30)} mo ago"
    }
}

fun humanBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var u = -1
    while (v >= 1024 && u < units.size - 1) { v /= 1024; u++ }
    return String.format(java.util.Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[u])
}

/** Slider with the app's neutral track instead of Material's secondary tint. */
@Composable
fun AppSlider(value: Float, onValueChange: (Float) -> Unit, valueRange: ClosedFloatingPointRange<Float>, modifier: Modifier = Modifier) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        modifier = modifier,
        colors = SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.primary,
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent,
        ),
    )
}
