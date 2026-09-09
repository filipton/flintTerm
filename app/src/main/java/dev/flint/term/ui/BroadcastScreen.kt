package dev.flint.term.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.flint.term.App
import dev.flint.term.data.Host
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One command, several hosts.
 *
 * The alternative is opening five sessions and typing the same thing five
 * times, which is how mistakes happen — the fifth one gets a typo. Each host
 * gets its own connection and its own result, so one refusing does not stop
 * the rest.
 */
@Composable
fun BroadcastScreen(nav: NavController) {
    val app = LocalContext.current.applicationContext as App
    val hosts by app.store.hosts.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // Telnet has no exec channel and a serial line has no idea of a command.
    val eligible = remember(hosts) { hosts.filterNot { it.isTelnet || it.hostname.isBlank() } }
    var chosen by remember { mutableStateOf(setOf<String>()) }
    var command by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf(mapOf<String, Result<String>>()) }

    fun run() {
        val targets = eligible.filter { it.id in chosen }
        if (targets.isEmpty() || command.isBlank()) return
        running = true
        results = emptyMap()
        scope.launch {
            // Each host on its own: a slow one must not hold up the others, and
            // one failing is a result, not the end of the run.
            val jobs = targets.map { host ->
                launch(Dispatchers.IO) {
                    val r = app.sessions.runCommand(host, command.trim())
                    withContext(Dispatchers.Main) { results = results + (host.id to r) }
                }
            }
            jobs.forEach { it.join() }
            running = false
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppHeader(
                title = "Run on many hosts",
                subtitle = if (chosen.isEmpty()) "Pick the hosts" else "${chosen.size} selected",
                onBack = { nav.popBackStack() },
                actions = {
                    if (eligible.isNotEmpty()) {
                        TextButton(onClick = {
                            chosen = if (chosen.size == eligible.size) emptySet() else eligible.map { it.id }.toSet()
                        }) { Text(if (chosen.size == eligible.size) "None" else "All") }
                    }
                },
            )
        },
    ) { padding ->
        if (eligible.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(Icons.Rounded.PlayArrow, "No hosts to run on", "Add an SSH host and it can take part in a broadcast.")
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 40.dp)) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(
                        command, { command = it }, "Command", mono = true, singleLine = false, minLines = 2,
                        placeholder = "uptime",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                    )
                    Text(
                        "Runs without a terminal, so nothing interactive: no prompts, no editors, no sudo asking for a password.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { run() },
                        enabled = !running && command.isNotBlank() && chosen.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (running) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(10.dp))
                            Text("Running on ${chosen.size}…")
                        } else {
                            Text(if (chosen.isEmpty()) "Pick hosts first" else "Run on ${chosen.size} host${if (chosen.size > 1) "s" else ""}")
                        }
                    }
                }
            }
            items(eligible, key = { it.id }) { host ->
                BroadcastRow(
                    host = host,
                    selected = host.id in chosen,
                    result = results[host.id],
                    waiting = running && host.id in chosen && results[host.id] == null,
                    onToggle = { chosen = if (host.id in chosen) chosen - host.id else chosen + host.id },
                )
            }
        }
    }
}

@Composable
private fun BroadcastRow(
    host: Host,
    selected: Boolean,
    result: Result<String>?,
    waiting: Boolean,
    onToggle: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clickable { onToggle() }.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(host.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(host.target, style = CodeStyle.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            when {
                waiting -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                result?.isSuccess == true -> Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = Status.online)
                result != null -> Icon(Icons.Rounded.Close, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
            }
        }
        result?.let { r ->
            val text = r.fold({ it.trimEnd().ifEmpty { "(no output)" } }, { it.message ?: "failed" })
            SelectionContainer {
                Text(
                    text,
                    style = CodeStyle.copy(fontSize = 11.sp),
                    color = if (r.isSuccess) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .padding(start = 48.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .padding(8.dp),
                    maxLines = 12,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}
