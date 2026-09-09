package dev.flint.term.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.flint.term.App
import dev.flint.term.transfer.Transfer
import dev.flint.term.transfer.TransferKind
import dev.flint.term.transfer.TransferService
import dev.flint.term.transfer.TransferStatus

/**
 * Everything the transfer manager is doing or has done, on its own page so the
 * file browser keeps the whole screen for files. Reached from [TransfersAction].
 */
@Composable
fun TransfersScreen(nav: NavController) {
    val app = LocalContext.current.applicationContext as App
    val transfers by app.transfers.transfers.collectAsStateWithLifecycle()
    val active = transfers.filter { it.isActive }.sortedBy { it.startedAt }
    val finished = transfers.filterNot { it.isActive }.sortedByDescending { it.finishedAt ?: it.startedAt }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppHeader(
                title = "Transfers",
                subtitle = summary(active),
                onBack = { nav.popBackStack() },
                actions = {
                    if (finished.isNotEmpty()) TextButton(onClick = { app.transfers.clearFinished() }) { Text("Clear") }
                },
            )
        },
    ) { padding ->
        if (transfers.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(Icons.Rounded.Download, "Nothing yet", "Downloads, uploads and host-to-host copies show up here with speed, ETA and a way to stop them.")
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            if (active.isNotEmpty()) {
                item { GroupLabel("In progress") }
                items(active, key = { it.id }) { t ->
                    TransferRow(t, onCancel = { app.transfers.cancel(t.id) })
                }
            }
            if (finished.isNotEmpty()) {
                item { GroupLabel("Finished") }
                items(finished, key = { it.id }) { t -> TransferRow(t, onCancel = null) }
            }
        }
    }
}

/** Header button that opens [TransfersScreen], badged while transfers are running. */
@Composable
fun TransfersAction(nav: NavController) {
    val app = LocalContext.current.applicationContext as App
    val transfers by app.transfers.transfers.collectAsStateWithLifecycle()
    val active = transfers.count { it.isActive }
    BadgedBox(badge = { if (active > 0) Badge { Text("$active") } }) {
        IconButton(onClick = { nav.navigate(Routes.TRANSFERS) }) { Icon(Icons.Rounded.Download, "Transfers") }
    }
}

@Composable
private fun TransferRow(t: Transfer, onCancel: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (icon, tint) = when (t.status) {
            TransferStatus.DONE -> Icons.Rounded.CheckCircle to Status.online
            TransferStatus.FAILED -> Icons.Rounded.Error to MaterialTheme.colorScheme.error
            TransferStatus.CANCELLED -> Icons.Rounded.Cancel to MaterialTheme.colorScheme.onSurfaceVariant
            TransferStatus.WAITING_FOR_WIFI -> Icons.Rounded.Wifi to MaterialTheme.colorScheme.tertiary
            else -> when (t.kind) {
                TransferKind.DOWNLOAD -> Icons.Rounded.Download
                TransferKind.UPLOAD -> Icons.Rounded.Upload
                TransferKind.COPY -> Icons.Rounded.SwapHoriz
            } to MaterialTheme.colorScheme.primary
        }
        Icon(icon, null, Modifier.size(22.dp), tint = tint)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (t.filesTotal > 1) "${t.name}  (${t.filesDone}/${t.filesTotal} files)" else t.name,
                style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                detail(t),
                style = MaterialTheme.typography.labelSmall,
                color = if (t.status == TransferStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
            Text(t.route, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            if (t.isActive && t.status != TransferStatus.WAITING_FOR_WIFI) {
                Spacer(Modifier.height(6.dp))
                val f = t.fraction
                if (f != null) LinearProgressIndicator(progress = { f }, Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
                else LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
            }
        }
        if (onCancel != null) {
            IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, "Cancel", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            Spacer(Modifier.width(12.dp))
        }
    }
}

private fun detail(t: Transfer): String = when (t.status) {
    TransferStatus.WAITING_FOR_WIFI -> "waiting for Wi-Fi  ·  ${humanBytes(t.total ?: 0)}"
    TransferStatus.QUEUED -> "starting…"
    TransferStatus.RUNNING -> buildString {
        append(humanBytes(t.done))
        t.total?.let { append(" / ").append(humanBytes(it)) }
        if (t.speed > 0) append("  ·  ").append(humanBytes(t.speed)).append("/s")
        t.etaSeconds?.let { append("  ·  ").append(TransferService.eta(it)).append(" left") }
        t.currentFile?.let { append("\n").append(it) }
    }
    TransferStatus.DONE -> "${humanBytes(t.done)} done"
    TransferStatus.FAILED -> t.error ?: "failed"
    TransferStatus.CANCELLED -> "cancelled"
}

private fun summary(active: List<Transfer>): String? {
    if (active.isEmpty()) return null
    val speed = active.sumOf { it.speed }
    val what = if (active.size == 1) "1 running" else "${active.size} running"
    return if (speed > 0) "$what  ·  ${humanBytes(speed)}/s" else what
}
