package dev.flint.term.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.flint.term.core.SessionState
import dev.flint.term.core.StepStatus as CoreStep

/**
 * What the connection actually did, step by step.
 *
 * The steps used to be printed into the terminal, above the first prompt, where
 * they were both in the way and impossible to read back once output scrolled.
 * Here the chain is a list — which address was tried, which tunnel came up,
 * where it stopped — so a failure can be pointed at rather than guessed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSheet(
    label: String,
    target: String?,
    /** What the host reported itself to be, if it has been connected to before. */
    os: String?,
    steps: List<dev.flint.term.session.TerminalSession.Step>,
    state: SessionState,
    /** What the server printed before login, if it printed anything. */
    banner: String?,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val failed = state as? SessionState.Disconnected
    val connecting = state is SessionState.Connecting
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
            Text(
                when {
                    connecting -> "Connecting"
                    failed != null -> "Connection ended"
                    else -> "Connection"
                },
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                listOfNotNull(target ?: label, os?.takeIf { it.isNotBlank() }).joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Above the steps, because a banner is often the reason the
            // connection is still going: a login URL to open, or a notice to
            // read before deciding to carry on.
            banner?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(16.dp))
                ServerMessage(it)
            }
            Spacer(Modifier.height(18.dp))
            if (steps.isEmpty()) {
                Text(
                    "Nothing to show. This session did not have to go through anything.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            steps.forEachIndexed { i, step ->
                val last = i == steps.lastIndex
                // Everything before the end got past; only the last step can be
                // where it stopped, and only when the session is not running.
                // The core says how each step ended; only a step still marked
                // "running" when the session is already over needs a verdict here.
                val status = when (step.status) {
                    CoreStep.DONE -> StepStatus.Done
                    CoreStep.WARNING -> StepStatus.Warned
                    CoreStep.FAILED -> StepStatus.Failed
                    CoreStep.RUNNING -> when {
                        connecting -> StepStatus.Running
                        failed != null && last -> StepStatus.Failed
                        else -> StepStatus.Done
                    }
                }
                StepRow(step.text, status, showLine = !last)
            }
            failed?.let { s ->
                val why = s.error ?: s.exitCode?.let { "exit code $it" }
                if (why != null) {
                    Spacer(Modifier.height(14.dp))
                    SelectionContainer {
                        Text(
                            why,
                            style = CodeStyle.copy(fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The server's own words, printed before anyone logged in.
 *
 * The URLs in it are links, because for a growing number of servers the banner
 * *is* the login: Tailscale's SSH check mode prints an address to open in a
 * browser and then waits for it. A URL that has to be copied off a phone screen
 * by hand is a URL that does not get opened.
 */
@Composable
fun ServerMessage(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            "Message from the server",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        SelectionContainer {
            Text(withLinks(text.trimEnd()), style = CodeStyle.copy(fontSize = 12.sp))
        }
    }
}

/** The same text, with every URL in it turned into something tappable. */
@Composable
private fun withLinks(text: String) = buildAnnotatedString {
    val style = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    var at = 0
    for (match in URL_RE.findAll(text)) {
        append(text.substring(at, match.range.first))
        // Trailing punctuation belongs to the sentence, not to the address.
        val url = match.value.trimEnd('.', ',', ')', ']', '>', ';', ':')
        withLink(LinkAnnotation.Url(url, style)) { append(url) }
        append(match.value.substring(url.length))
        at = match.range.last + 1
    }
    append(text.substring(at))
}

private val URL_RE = Regex("""https?://[^\s'"<>()\[\]{}`]+""")

private enum class StepStatus { Done, Running, Warned, Failed }

@Composable
private fun StepRow(text: String, status: StepStatus, showLine: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(28.dp)) {
            when (status) {
                StepStatus.Done -> Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = Status.online)
                StepStatus.Running -> CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                StepStatus.Warned -> Icon(Icons.Rounded.PriorityHigh, null, Modifier.size(18.dp), tint = Status.busy)
                StepStatus.Failed -> Icon(Icons.Rounded.Close, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
            }
            if (showLine) {
                Box(
                    Modifier
                        .width(2.dp)
                        .height(18.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = when (status) {
                StepStatus.Failed -> MaterialTheme.colorScheme.error
                StepStatus.Warned -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f).padding(bottom = if (showLine) 6.dp else 0.dp),
            overflow = TextOverflow.Visible,
        )
    }
}
