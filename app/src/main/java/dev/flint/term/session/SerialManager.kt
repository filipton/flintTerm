package dev.flint.term.session

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import dev.flint.term.core.ExternalSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One port on an attached USB serial adapter. */
data class SerialDevice(
    val deviceId: Int,
    val portNumber: Int,
    val driverName: String,
    val productName: String?,
    val vendorId: Int,
    val productId: Int,
    val portCount: Int,
) {
    /** "CP2102" or, failing a product name, the driver's own name. */
    val name: String get() = productName?.takeIf { it.isNotBlank() } ?: driverName
    val label: String get() = if (portCount > 1) "$name (port ${portNumber + 1})" else name
    val ids: String get() = String.format("%04x:%04x", vendorId, productId)
}

/** Line settings; the defaults are what almost every console uses. */
data class SerialSettings(
    val baudRate: Int = 115200,
    val dataBits: Int = 8,
    val stopBits: Int = UsbSerialPort.STOPBITS_1,
    val parity: Int = UsbSerialPort.PARITY_NONE,
) {
    /** "115200 8N1" */
    val summary: String
        get() {
            val p = when (parity) {
                UsbSerialPort.PARITY_EVEN -> "E"
                UsbSerialPort.PARITY_ODD -> "O"
                UsbSerialPort.PARITY_MARK -> "M"
                UsbSerialPort.PARITY_SPACE -> "S"
                else -> "N"
            }
            val s = if (stopBits == UsbSerialPort.STOPBITS_2) "2" else "1"
            return "$baudRate $dataBits$p$s"
        }

    companion object {
        val BAUD_RATES = listOf(9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600)
    }
}

/**
 * Finds USB serial adapters, asks for permission, and opens ports.
 *
 * The device belongs to Android, not to the Rust core, so a serial session uses
 * `Backend.External`: [SerialConnection] pushes what it reads into the session
 * and writes back whatever is typed.
 */
class SerialManager(private val context: Context) {
    private val usb get() = context.getSystemService(UsbManager::class.java)

    private val _devices = MutableStateFlow<List<SerialDevice>>(emptyList())
    val devices: StateFlow<List<SerialDevice>> = _devices

    /** Re-scan; call when the hosts screen appears or a device is plugged in. */
    fun refresh() {
        _devices.value = runCatching { scan() }.getOrElse {
            Log.w(TAG, "USB scan failed: ${it.message}")
            emptyList()
        }
    }

    private fun scan(): List<SerialDevice> {
        val manager = usb ?: return emptyList()
        return UsbSerialProber.getDefaultProber().findAllDrivers(manager).flatMap { driver ->
            driver.ports.mapIndexed { i, _ ->
                SerialDevice(
                    deviceId = driver.device.deviceId,
                    portNumber = i,
                    driverName = driver.javaClass.simpleName.removeSuffix("SerialDriver"),
                    productName = driver.device.productName,
                    vendorId = driver.device.vendorId,
                    productId = driver.device.productId,
                    portCount = driver.ports.size,
                )
            }
        }
    }

    private fun rawDevice(id: Int): UsbDevice? = usb?.deviceList?.values?.firstOrNull { it.deviceId == id }

    fun hasPermission(device: SerialDevice): Boolean {
        val raw = rawDevice(device.deviceId) ?: return false
        return usb?.hasPermission(raw) == true
    }

    /**
     * Ask the user for access to [device]. [onResult] runs on the main thread;
     * granting is a system dialog, so this can take a while.
     */
    fun requestPermission(device: SerialDevice, onResult: (Boolean) -> Unit) {
        val manager = usb
        val raw = rawDevice(device.deviceId)
        if (manager == null || raw == null) {
            onResult(false)
            return
        }
        if (manager.hasPermission(raw)) {
            onResult(true)
            return
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != ACTION_PERMISSION) return
                runCatching { context.unregisterReceiver(this) }
                onResult(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
        val pending = PendingIntent.getBroadcast(
            context,
            device.deviceId,
            Intent(ACTION_PERMISSION).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.requestPermission(raw, pending)
    }

    /** Open the port. Throws with a readable message if the adapter will not talk. */
    fun open(device: SerialDevice, settings: SerialSettings): UsbSerialPort {
        val manager = usb ?: error("no USB service")
        val raw = rawDevice(device.deviceId) ?: error("${device.name} is no longer attached")
        val driver = UsbSerialProber.getDefaultProber().probeDevice(raw) ?: error("no driver for ${device.ids}")
        val port = driver.ports.getOrNull(device.portNumber) ?: error("port ${device.portNumber} is gone")
        val connection = manager.openDevice(raw) ?: error("could not open ${device.name} — permission denied?")
        port.open(connection)
        runCatching { port.setParameters(settings.baudRate, settings.dataBits, settings.stopBits, settings.parity) }
            .onFailure {
                runCatching { port.close() }
                error("${device.name} rejected ${settings.summary}: ${it.message}")
            }
        // Many boards only send once DTR/RTS are asserted; ignore adapters that lack them.
        runCatching { port.dtr = true }
        runCatching { port.rts = true }
        return port
    }

    companion object {
        private const val TAG = "SerialManager"
        const val ACTION_PERMISSION = "dev.flint.term.USB_PERMISSION"
    }
}

/**
 * Glue between an open [UsbSerialPort] and a session's external transport:
 * a reader thread pushes device bytes into the terminal, and everything typed
 * goes back out to the port.
 */
class SerialConnection(
    private val port: UsbSerialPort,
    private val session: TerminalSession,
) : ExternalSink, SerialInputOutputManager.Listener {
    private val io = SerialInputOutputManager(port, this)

    @Volatile
    private var closed = false

    fun start() {
        io.start()
    }

    // ---- device -> terminal ----
    override fun onNewData(data: ByteArray) {
        if (!closed) session.core.pushOutput(data)
    }

    override fun onRunError(e: Exception) {
        if (closed) return
        closed = true
        // Unplugged, or the adapter stopped answering: end the session with a reason.
        runCatching { session.core.note("Serial port closed: ${e.message ?: e.javaClass.simpleName}") }
        runCatching { session.core.externalClosed() }
        runCatching { port.close() }
    }

    // ---- terminal -> device ----
    override fun onInput(data: ByteArray) {
        if (closed) return
        runCatching { port.write(data, WRITE_TIMEOUT_MS) }
            .onFailure { Log.w("SerialConnection", "write failed: ${it.message}") }
    }

    /** A serial line has no window-size channel; the far end learns it via `stty` if at all. */
    override fun onResize(cols: UShort, rows: UShort) = Unit

    override fun onClose() {
        if (closed) return
        closed = true
        runCatching { io.stop() }
        runCatching { port.close() }
    }

    private companion object {
        const val WRITE_TIMEOUT_MS = 2000
    }
}
