package dev.flint.term.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.flint.term.core.ProbeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ping, port and name checks run *inside* a VPN.
 *
 * The app's tunnels are userspace, so nothing else on the phone can see into
 * them — `ping` in a shell would go out of the phone's ordinary network and
 * prove nothing. These probes run in the tunnel's own stack, which is the only
 * way to answer "is the machine actually reachable through this VPN?".
 */
@Composable
fun DiagnosticsSection(
    /** Ping is meaningless on a tailnet: the node offers TCP and UDP, not ICMP. */
    canPing: Boolean,
    onPing: suspend (String) -> ProbeResult,
    onPort: suspend (String, Int) -> ProbeResult,
    onResolve: (suspend (String) -> ProbeResult)?,
) {
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var busy by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf(listOf<ProbeResult>()) }

    fun run(probe: suspend () -> ProbeResult) {
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { probe() } }
            results = listOf(
                r.getOrElse { ProbeResult(false, it.message ?: "failed", null) },
            ) + results.take(4)
            busy = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Check what is reachable", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(
                target, { target = it }, "Address or name", Modifier.weight(1f), mono = true,
                placeholder = "10.0.0.5",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            Field(
                port, { v -> port = v.filter { it.isDigit() }.take(5) }, "Port", Modifier.width(96.dp), mono = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canPing) {
                OutlinedButton(
                    enabled = !busy && target.isNotBlank(),
                    onClick = { run { onPing(target.trim()) } },
                    modifier = Modifier.weight(1f),
                ) { Text("Ping") }
            }
            OutlinedButton(
                enabled = !busy && target.isNotBlank() && port.toIntOrNull() != null,
                onClick = { run { onPort(target.trim(), port.toInt()) } },
                modifier = Modifier.weight(1f),
            ) { Text("Port") }
            if (onResolve != null) {
                OutlinedButton(
                    enabled = !busy && target.isNotBlank(),
                    onClick = { run { onResolve(target.trim()) } },
                    modifier = Modifier.weight(1f),
                ) { Text("Resolve") }
            }
        }
        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Checking…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // Newest first, a few kept: enough to compare "before" with "after".
        results.forEach { r ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .padding(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    if (r.ok) Icons.Rounded.Check else Icons.Rounded.Close,
                    null,
                    Modifier.size(15.dp),
                    tint = if (r.ok) Status.online else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(8.dp))
                SelectionContainer {
                    Text(r.detail, style = CodeStyle.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        Text(
            if (canPing) {
                "Sent inside the tunnel, not from the phone's ordinary network — a ping from anywhere else would not go through it."
            } else {
                "Dialled through the node, so it answers for the tailnet rather than for this phone's network."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
    }
}
