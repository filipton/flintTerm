package dev.flint.term.ui

import dev.flint.term.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Subject
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.flint.term.App
import dev.flint.term.core.SessionState
import dev.flint.term.session.Container
import dev.flint.term.session.ContainerAction
import dev.flint.term.session.ContainerGroup
import dev.flint.term.session.ContainerLogs
import dev.flint.term.session.ContainerProbe
import dev.flint.term.session.ContainerState
import dev.flint.term.session.DiskUse
import dev.flint.term.session.ServerStatus
import dev.flint.term.session.ServerStatusProbe
import dev.flint.term.session.SessionExec
import dev.flint.term.session.TerminalSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/** How often the numbers are refetched while somebody is looking at them. */
private const val REFRESH_MILLIS = 5_000L

/**
 * How often the container list is refetched. Slower than the vitals on
 * purpose: `stats --no-stream` samples for a second or two on the server
 * before it answers, and a list of containers changes far less often than a
 * load average does.
 */
private const val CONTAINERS_REFRESH_MILLIS = 10_000L

@Composable
fun ServerScreen(nav: NavController, sessionId: String) {
    val app = LocalContext.current.applicationContext as App
    val session = remember(sessionId) { app.sessions.get(sessionId) }
    if (session == null) {
        LaunchedEffect(Unit) { nav.popBackStack() }
        return
    }
    LaunchedEffect(sessionId) { Workspace.openServer(sessionId) }
    WorkspaceFrame(nav, session, Pane.Server(sessionId)) { modifier ->
        ServerPane(nav, session, modifier)
    }
}

/**
 * How the machine on the other end is doing, refreshed while you watch.
 *
 * The loop runs only while this pane is on screen and the app is in front of
 * the person: a status pane left behind a lock screen is somebody's mobile data
 * spent on numbers nobody is reading. It also waits for the session to be
 * connected rather than racing it, so a pane opened during the handshake does
 * not dial a second connection to ask the same question.
 */
@Composable
fun ServerPane(nav: NavController, session: TerminalSession, modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as App
    val state by session.state.collectAsStateWithLifecycle()
    // One way in and out for everything this pane asks, so the two loops share
    // a connection instead of dialling one each.
    val exec = remember(session.id) { SessionExec(app.sessions, session) }
    val probe = remember(exec) { ServerStatusProbe(exec) }
    val containers = remember(exec) { ContainerProbe(exec) }
    var status by remember(session.id) { mutableStateOf<ServerStatus?>(null) }
    var error by remember(session.id) { mutableStateOf<String?>(null) }
    var groups by remember(session.id) { mutableStateOf<List<ContainerGroup>?>(null) }
    var containerError by remember(session.id) { mutableStateOf<String?>(null) }
    // Bumped after an action, which restarts the loop and so reads the list
    // again straight away rather than leaving a stale row on screen.
    var afterAction by remember(session.id) { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    DisposableEffect(exec) { onDispose { exec.close() } }

    var watching by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        watching = true
        onPauseOrDispose { watching = false }
    }

    val connected = state is SessionState.Connected
    LaunchedEffect(session.id, watching, connected) {
        if (!watching || !connected) return@LaunchedEffect
        while (isActive) {
            runCatching { probe.read() }
                .onSuccess { status = it; error = null }
                .onFailure { error = it.message ?: "could not read the server's status" }
            delay(REFRESH_MILLIS)
        }
    }

    LaunchedEffect(session.id, watching, connected, afterAction) {
        if (!watching || !connected) return@LaunchedEffect
        while (isActive) {
            runCatching { containers.read() }
                .onSuccess { groups = it; containerError = null }
                .onFailure { containerError = it.message ?: "could not list the containers" }
            delay(CONTAINERS_REFRESH_MILLIS)
        }
    }

    val reading = status
    Column(
        modifier
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScreenScroll("server"))
            .padding(bottom = 24.dp),
    ) {
        when {
            reading != null -> Vitals(reading, error)
            !connected -> Waiting(stringResource(R.string.serverscreen_waiting_for_the_connection))
            error != null -> Column {
                Spacer(Modifier.height(24.dp))
                EmptyState(Icons.Rounded.Speed, stringResource(R.string.serverscreen_nothing_came_back), error!!)
            }
            else -> Waiting(stringResource(R.string.serverscreen_reading_the_machine))
        }
        // Only where a runtime answered. A machine with neither docker nor
        // podman is not told what it does not have, which is why the section
        // needs no switch: it is either useful or it is not there.
        groups?.let { found ->
            ContainersSection(
                groups = found,
                error = containerError,
                onAction = { action, container ->
                    scope.launch {
                        runCatching { containers.run(action, container) }
                            .onSuccess { containerError = null }
                            .onFailure { containerError = it.message ?: "${action.label} failed" }
                        afterAction++
                    }
                },
                onLogs = { container ->
                    val runtime = containers.runtime ?: return@ContainersSection
                    val tab = ContainerLogs.open(app.sessions, session, runtime, container)
                    if (tab != null) nav.navigate(Routes.terminal(tab.id)) { popUpTo(Routes.HOSTS) }
                },
            )
        }
    }
}

/**
 * The containers on this machine, a card per compose project.
 *
 * A project is one thing with parts, and its parts are the rows — so the
 * heading is the project's own name rather than a repetition of the word
 * "containers", which the icons and the "Up 3 hours" underneath already say.
 * Anything compose did not start goes under one heading at the end.
 */
@Composable
private fun ContainersSection(
    groups: List<ContainerGroup>,
    error: String?,
    onAction: (ContainerAction, Container) -> Unit,
    onLogs: (Container) -> Unit,
) {
    var chosen by remember { mutableStateOf<Container?>(null) }
    var confirming by remember { mutableStateOf<Container?>(null) }

    if (groups.isEmpty()) {
        Group(title = stringResource(R.string.serverscreen_containers)) {
            Text(
                stringResource(R.string.serverscreen_no_containers),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
    }
    groups.forEach { group ->
        Group(title = group.project ?: if (groups.size > 1) stringResource(R.string.serverscreen_other_containers) else "Containers") {
            group.containers.forEach { container ->
                ContainerRow(container) { chosen = container }
            }
        }
    }
    if (error != null) {
        Text(
            error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
        )
    }

    chosen?.let { container ->
        ActionSheet(
            onDismiss = { chosen = null },
            title = container.name,
            subtitle = listOf(container.image, container.status).filter { it.isNotEmpty() }.joinToString(" · "),
            actions = listOf(
                SheetAction(stringResource(R.string.serverscreen_logs), Icons.AutoMirrored.Rounded.Subject) {
                    chosen = null
                    onLogs(container)
                },
            ) + actionsFor(container.state).map { action ->
                SheetAction(action.label, actionIcon(action), danger = action.asksFirst) {
                    chosen = null
                    if (action.asksFirst) confirming = container else onAction(action, container)
                }
            },
        )
    }

    confirming?.let { container ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(R.string.serverscreen_stop_fmt, container.name)) },
            text = { Text(stringResource(R.string.serverscreen_it_stays_down_until_something_starts_it_again)) },
            confirmButton = {
                Button(onClick = { confirming = null; onAction(ContainerAction.STOP, container) }) { Text(stringResource(R.string.serverscreen_stop)) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(R.string.serverscreen_cancel)) } },
        )
    }
}

@Composable
private fun ContainerRow(container: Container, onClick: () -> Unit) {
    val numbers = listOfNotNull(
        container.cpuPercent?.let { String.format(Locale.US, "%.1f%%", it) },
        container.memoryBytes?.let { humanBytes(it) },
    ).joinToString("  ")
    GroupRow(
        // Inside a project the service name is what the compose file calls it,
        // and the container name is that with a project and a number stapled on.
        title = container.service ?: container.name,
        subtitle = listOf(container.status, container.image, container.ports)
            .filter { it.isNotEmpty() }
            .joinToString(" · "),
        icon = Icons.Rounded.Inventory2,
        iconTint = stateColor(container.state),
        onClick = onClick,
        trailing = numbers.takeIf { it.isNotEmpty() }?.let {
            {
                Text(
                    it,
                    style = CodeStyle.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        },
    )
}

/** What a container can be asked to do next, given what it is doing now. */
private fun actionsFor(state: ContainerState): List<ContainerAction> = when (state) {
    ContainerState.RUNNING -> listOf(ContainerAction.RESTART, ContainerAction.PAUSE, ContainerAction.STOP)
    ContainerState.PAUSED -> listOf(ContainerAction.UNPAUSE, ContainerAction.STOP)
    ContainerState.RESTARTING -> listOf(ContainerAction.STOP)
    else -> listOf(ContainerAction.START)
}

private fun actionIcon(action: ContainerAction) = when (action) {
    ContainerAction.START, ContainerAction.UNPAUSE -> Icons.Rounded.PlayArrow
    ContainerAction.STOP -> Icons.Rounded.Stop
    ContainerAction.RESTART -> Icons.Rounded.RestartAlt
    ContainerAction.PAUSE -> Icons.Rounded.Pause
}

@Composable
private fun stateColor(state: ContainerState): Color = when (state) {
    ContainerState.RUNNING -> Status.online
    ContainerState.PAUSED, ContainerState.RESTARTING -> Status.busy
    // A stopped container is not a fault — most machines have a few — so it is
    // grey rather than red, and only the running ones catch the eye.
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun Waiting(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Vitals(s: ServerStatus, error: String?) {
    // A reading that has gone stale is still worth showing, but not worth
    // showing silently: the last good numbers stay put with the reason above them.
    if (error != null) {
        Text(
            error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
        )
    }

    // The machine's own name is the best label this card could have; "Machine"
    // is only there for a host too terse to answer `hostname`.
    Group(title = s.hostname ?: "Machine") {
        s.uptimeSeconds?.let { Reading("Uptime", uptimeText(it)) }
        s.os?.let { Reading("System", it + (s.cpuCount?.let { n -> " · $n cores" } ?: "")) }
        s.temperatureC?.let { Reading("Temperature", String.format(Locale.US, "%.1f °C", it)) }
    }

    if (s.cpu != null || s.load != null) {
        Group(title = "CPU") {
            s.cpu?.let {
                Reading("Busy", percent(it), meter = it)
            }
            s.load?.let {
                Reading(
                    "Load",
                    String.format(Locale.US, "%.2f  %.2f  %.2f", it.one, it.five, it.fifteen),
                    // One minute against the core count is the number that says
                    // "this machine is over its head", so that is the bar.
                    meter = s.cpuCount?.let { n -> it.one / n },
                )
            }
            if (s.cores.isNotEmpty()) CoreBars(s.cores)
        }
    }

    s.memory?.let { memory ->
        Group(title = stringResource(R.string.serverscreen_memory)) {
            Reading(
                "Used",
                "${humanBytes(memory.usedBytes)} of ${humanBytes(memory.totalBytes)}",
                meter = memory.fraction,
            )
            memory.cachedBytes?.let { Reading("Cached", humanBytes(it)) }
            s.swap?.let { swap ->
                Reading("Swap", "${humanBytes(swap.usedBytes)} of ${humanBytes(swap.totalBytes)}", meter = swap.fraction)
            }
        }
    }

    if (s.disks.isNotEmpty()) {
        Group(title = stringResource(R.string.serverscreen_disks)) {
            s.disks.forEach { disk -> DiskRow(disk) }
        }
    }

    s.busiestInterface?.let { net ->
        Group(title = stringResource(R.string.serverscreen_network)) {
            Reading(net.name, "↓ ${humanBytes(net.rxPerSecond)}/s   ↑ ${humanBytes(net.txPerSecond)}/s")
        }
    }

    if (s.processes.isNotEmpty()) {
        Group(title = stringResource(R.string.serverscreen_top_processes)) {
            s.processes.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        p.command,
                        style = CodeStyle.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        listOfNotNull(
                            p.cpuPercent?.let { String.format(Locale.US, "%.1f%% cpu", it) },
                            p.memPercent?.let { String.format(Locale.US, "%.1f%% mem", it) },
                        ).joinToString("  "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

/** A label, its value, and optionally a bar underneath saying how close to full it is. */
@Composable
private fun Reading(label: String, value: String, meter: Double? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                value,
                style = CodeStyle.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (meter != null) {
            Spacer(Modifier.height(6.dp))
            Meter(meter.toFloat(), Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun DiskRow(disk: DiskUse) {
    Reading(disk.mount, "${humanBytes(disk.usedBytes)} of ${humanBytes(disk.totalBytes)}", meter = disk.fraction)
}

/**
 * One thin bar per core, filling from the bottom.
 *
 * The shape of the row is the point: sixteen cores at 6% each and one core
 * pinned are the same average and completely different machines.
 */
@Composable
private fun CoreBars(cores: List<Double>) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).height(30.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        cores.forEach { core ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(core.toFloat().coerceIn(0f, 1f))
                        .clip(RoundedCornerShape(2.dp))
                        .background(pressureColor(core.toFloat())),
                )
            }
        }
    }
}

@Composable
private fun Meter(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(5.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .clip(RoundedCornerShape(3.dp))
                .background(pressureColor(fraction)),
        )
    }
}

/** Green until it matters, then amber, then red — the bar should read before the number does. */
@Composable
private fun pressureColor(fraction: Float): Color = when {
    fraction >= 0.9f -> MaterialTheme.colorScheme.error
    fraction >= 0.75f -> Status.busy
    else -> MaterialTheme.colorScheme.primary
}

private fun percent(fraction: Double): String = "${(fraction * 100).roundToInt()}%"

/** Coarse on purpose: "up 12 days" is the answer, "12d 3h 41m 8s" is trivia. */
internal fun uptimeText(seconds: Long): String {
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3_600
    val minutes = (seconds % 3_600) / 60
    return when {
        days > 0 -> "${days} d ${hours} h"
        hours > 0 -> "${hours} h ${minutes} min"
        else -> "${minutes} min"
    }
}
