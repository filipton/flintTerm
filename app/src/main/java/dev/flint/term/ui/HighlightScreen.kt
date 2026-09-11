package dev.flint.term.ui

import dev.flint.term.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FormatColorText
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.flint.term.App
import dev.flint.term.data.HighlightRule

/**
 * The rules that recolor what the terminal draws, in the order they are tried.
 *
 * Order is the whole of the precedence: the first rule to claim a piece of a
 * row keeps it, so a rule moved up here is a rule that wins more often. Each
 * one is edited against a test line, because a pattern that is nearly right
 * looks exactly like one that is right until something is run through it.
 */
@Composable
fun HighlightScreen(nav: NavController) {
    val app = LocalContext.current.applicationContext as App
    val settings by app.store.settings.collectAsStateWithLifecycle()
    val rules = settings.highlightRules
    var editing by remember { mutableStateOf<HighlightRule?>(null) }
    // Errors are worked out once for the list; the rows only read them.
    val errors = remember(rules) {
        dev.flint.term.core.highlightErrors(rules.map { it.toCore() }).associate { it.id to it.reason }
    }

    fun write(list: List<HighlightRule>) = app.store.updateSettings { it.copy(highlightRules = list) }

    fun move(from: Int, to: Int) {
        if (to !in rules.indices) return
        val list = rules.toMutableList()
        list.add(to, list.removeAt(from))
        write(list)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppHeader(title = stringResource(R.string.highlightscreen_highlighting), subtitle = stringResource(R.string.highlightscreen_recolor_what_matches_as_it_is_drawn), onBack = { nav.popBackStack() }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = HighlightRule(color = HostAccents[4].value.toLong().toInt()) },
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text(stringResource(R.string.highlightscreen_new_rule)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(20.dp),
            )
        },
    ) { padding ->
        LazyColumn(state = rememberScreenListState("highlights"), modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 110.dp)) {
            item {
                Group {
                    GroupRow(
                        title = stringResource(R.string.highlightscreen_highlight_matches),
                        subtitle = if (rules.isEmpty()) stringResource(R.string.highlightscreen_nothing_to_apply_yet) else stringResource(R.string.highlightscreen_of_rules_on, rules.count { it.enabled }, rules.size),
                        icon = Icons.Rounded.FormatColorText,
                        iconTint = MaterialTheme.colorScheme.primary,
                        checked = settings.highlightEnabled, onCheckedChange = { v -> app.store.updateSettings { it.copy(highlightEnabled = v) } },
                    )
                }
            }
            if (rules.isEmpty()) {
                item {
                    EmptyState(
                        Icons.Rounded.FormatColorText, stringResource(R.string.highlightscreen_no_rules_yet),
                        stringResource(R.string.highlightscreen_a_rule_paints_what_its_pattern_matches_on_screen),
                        stringResource(R.string.highlightscreen_add_the_presets),
                    ) { write(dev.flint.term.core.highlightPresets().map { HighlightRule(pattern = it.pattern, color = it.color) }) }
                }
            } else {
                item {
                    Group("Rules") {
                        rules.forEachIndexed { i, rule ->
                            GroupRow(
                                title = rule.pattern.ifBlank { "(empty)" },
                                subtitle = errors[rule.id]
                                    ?: buildString {
                                        append(if (rule.wholeLine) stringResource(R.string.highlightscreen_whole_line) else stringResource(R.string.highlightscreen_the_match))
                                        if (!rule.enabled) append("   ·   off")
                                    },
                                subtitleMono = errors[rule.id] != null,
                                icon = Icons.Rounded.FormatColorText,
                                iconTint = if (errors[rule.id] != null) MaterialTheme.colorScheme.error else Color(rule.color),
                                titleColor = if (rule.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                onClick = { editing = rule },
                                trailing = {
                                    Row {
                                        IconButton(onClick = { move(i, i - 1) }, enabled = i > 0) {
                                            Icon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.highlightscreen_move_up))
                                        }
                                        IconButton(onClick = { move(i, i + 1) }, enabled = i < rules.lastIndex) {
                                            Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.highlightscreen_move_down))
                                        }
                                    }
                                },
                            )
                            if (i < rules.lastIndex) RowDivider()
                        }
                    }
                }
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            stringResource(R.string.highlightscreen_tried_from_the_top_the_first_rule_to_claim_a_pie),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val missing = remember(rules) {
                            dev.flint.term.core.highlightPresets()
                                .filter { p -> rules.none { it.pattern == p.pattern } }
                                .map { HighlightRule(pattern = it.pattern, color = it.color) }
                        }
                        if (missing.isNotEmpty()) {
                            TextButton(onClick = { write(rules + missing) }) { Text(stringResource(R.string.highlightscreen_add_the_presets)) }
                        }
                    }
                }
            }
        }
    }

    editing?.let { rule ->
        HighlightRuleEditor(
            rule,
            onDismiss = { editing = null },
            onSave = { edited ->
                val at = rules.indexOfFirst { it.id == edited.id }
                write(if (at < 0) rules + edited else rules.toMutableList().also { it[at] = edited })
                editing = null
            },
            onDelete = { write(rules.filterNot { it.id == rule.id }); editing = null },
        )
    }
}

/**
 * One rule, with a line to try it on.
 *
 * The pattern is text somebody typed, so it is compiled while it is typed and
 * a bad one is said out loud here rather than left to fail somewhere the
 * terminal is drawing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HighlightRuleEditor(
    initial: HighlightRule,
    onDismiss: () -> Unit,
    onSave: (HighlightRule) -> Unit,
    onDelete: () -> Unit,
) {
    var pattern by remember { mutableStateOf(initial.pattern) }
    var color by remember { mutableStateOf(initial.color) }
    var wholeLine by remember { mutableStateOf(initial.wholeLine) }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var testLine by remember { mutableStateOf("sshd[42]: error: connection reset, warning: retrying — ok") }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isNew = initial.pattern.isBlank()
    val error = remember(pattern) { dev.flint.term.core.highlightError(pattern) }
    val preview = remember(pattern, color, wholeLine, testLine, error) {
        val rule = HighlightRule(pattern = pattern, color = color, wholeLine = wholeLine).toCore()
        buildAnnotatedString {
            append(testLine)
            dev.flint.term.core.highlightPreview(listOf(rule), testLine).forEach { s ->
                addStyle(SpanStyle(color = Color(s.color)), s.start.toInt().coerceIn(0, testLine.length), s.end.toInt().coerceIn(0, testLine.length))
            }
        }
    }
    val matched = remember(preview) { preview.spanStyles.isNotEmpty() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (isNew) stringResource(R.string.highlightscreen_new_rule) else stringResource(R.string.highlightscreen_edit_rule), style = MaterialTheme.typography.titleLarge)
            Field(
                pattern, { pattern = it }, "Pattern", mono = true,
                placeholder = stringResource(R.string.highlightscreen_i_berror_b),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                error = error,
                hint = stringResource(R.string.highlightscreen_a_regular_expression_i_at_the_front_ignores_case),
            )
            Text(stringResource(R.string.highlightscreen_color), style = MaterialTheme.typography.titleSmall)
            ColorPicker(selected = Color(color), onSelect = { color = it.value.toLong().toInt() })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.highlightscreen_color_the_whole_line), Modifier.weight(1f))
                AppSwitch(wholeLine, { wholeLine = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.highlightscreen_rule_is_on), Modifier.weight(1f))
                AppSwitch(enabled, { enabled = it })
            }
            Text(stringResource(R.string.highlightscreen_test_line), style = MaterialTheme.typography.titleSmall)
            Field(testLine, { testLine = it }, "Try it on", mono = true, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(12.dp),
            ) {
                Text(preview, style = CodeStyle.copy(fontSize = 13.sp), color = MaterialTheme.colorScheme.onSurface)
            }
            Text(
                when {
                    error != null -> stringResource(R.string.highlightscreen_nothing_is_highlighted_while_the_pattern_is_brok)
                    pattern.isBlank() -> stringResource(R.string.highlightscreen_type_a_pattern_to_see_what_it_would_do)
                    matched -> if (wholeLine) stringResource(R.string.highlightscreen_matches_so_the_whole_line_is_colored) else stringResource(R.string.highlightscreen_matches_only_what_matched_is_colored)
                    else -> stringResource(R.string.highlightscreen_nothing_matched_this_line)
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    enabled = pattern.isNotBlank() && error == null,
                    onClick = { onSave(initial.copy(pattern = pattern, color = color, wholeLine = wholeLine, enabled = enabled)) },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.highlightscreen_save)) }
                if (!isNew) {
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Rounded.Delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.highlightscreen_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** A rule in the shape the core matches it in. */
private fun HighlightRule.toCore() =
    dev.flint.term.core.HighlightRule(id, pattern, color, wholeLine, enabled)
