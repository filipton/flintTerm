package dev.flint.term.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hoho.android.usbserial.driver.UsbSerialPort
import dev.flint.term.App
import dev.flint.term.session.SerialDevice
import dev.flint.term.session.SerialSettings
import dev.flint.term.session.TerminalSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Line settings for a USB serial console, then permission, then open. Most
 * consoles are 115200 8N1, so that is one tap away.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SerialConnectDialog(
    device: SerialDevice,
    onDismiss: () -> Unit,
    onConnected: (TerminalSession) -> Unit,
) {
    val app = LocalContext.current.applicationContext as App
    val scope = rememberCoroutineScope()
    var baud by remember { mutableStateOf(115200) }
    var dataBits by remember { mutableStateOf(8) }
    var parity by remember { mutableStateOf(UsbSerialPort.PARITY_NONE) }
    var stopBits by remember { mutableStateOf(UsbSerialPort.STOPBITS_1) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val settings = SerialSettings(baud, dataBits, stopBits, parity)

    fun connect() {
        busy = true
        error = null
        // Permission is a system dialog; the result comes back on the main thread.
        app.serial.requestPermission(device) { granted ->
            if (!granted) {
                busy = false
                error = "Permission for ${device.name} was denied"
                return@requestPermission
            }
            scope.launch {
                val opened = withContext(Dispatchers.IO) { runCatching { app.serial.open(device, settings) } }
                opened.onSuccess { port ->
                    onConnected(app.sessions.openSerial(device, settings, port))
                }.onFailure {
                    busy = false
                    error = it.message ?: "could not open ${device.name}"
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(device.label) },
        text = {
            Column {
                Text(
                    "USB serial  ·  ${device.ids}",
                    style = CodeStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                Text("Speed", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SerialSettings.BAUD_RATES.forEach { r ->
                        FilterChip(selected = r == baud, onClick = { baud = r }, label = { Text("$r") })
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Format", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FORMATS.forEach { (label, f) ->
                        val (d, p, st) = f
                        FilterChip(
                            selected = d == dataBits && p == parity && st == stopBits,
                            onClick = { dataBits = d; parity = p; stopBits = st },
                            label = { Text(label) },
                        )
                    }
                }
                error?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(enabled = !busy, onClick = ::connect) { Text(if (busy) "Opening…" else "Connect") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The handful of line formats anyone actually uses. */
private val FORMATS: List<Pair<String, Triple<Int, Int, Int>>> = listOf(
    "8N1" to Triple(8, UsbSerialPort.PARITY_NONE, UsbSerialPort.STOPBITS_1),
    "8E1" to Triple(8, UsbSerialPort.PARITY_EVEN, UsbSerialPort.STOPBITS_1),
    "8O1" to Triple(8, UsbSerialPort.PARITY_ODD, UsbSerialPort.STOPBITS_1),
    "7E1" to Triple(7, UsbSerialPort.PARITY_EVEN, UsbSerialPort.STOPBITS_1),
    "8N2" to Triple(8, UsbSerialPort.PARITY_NONE, UsbSerialPort.STOPBITS_2),
)
