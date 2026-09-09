package dev.flint.term.security

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * A security key on the end of a USB cable, over the FIDO HID protocol.
 *
 * There is no USB class for "security key": a token is an ordinary HID device
 * and what marks it out is its report descriptor, which declares the usage page
 * 0xF1D0. That descriptor has to be asked for with a control transfer, so a
 * device is opened before it can be recognized — which is also why permission is
 * asked for first, the same way [dev.flint.term.session.SerialManager] does for
 * serial adapters.
 */
object UsbCtap {
    private const val TAG = "UsbCtap"
    const val ACTION_PERMISSION = "dev.flint.term.SECURITY_KEY_PERMISSION"

    /** HID class descriptor type, for the GET_DESCRIPTOR that fetches the report descriptor. */
    private const val DESCRIPTOR_REPORT = 0x22
    private const val REQUEST_GET_DESCRIPTOR = 0x06

    /** The FIDO usage page, written in a report descriptor as `06 D0 F1`. */
    private val FIDO_USAGE_PAGE = byteArrayOf(0x06, 0xD0.toByte(), 0xF1.toByte())

    /**
     * Whether a HID report descriptor belongs to a FIDO authenticator.
     *
     * `06 D0 F1` is "usage page 0xF1D0" as a two-byte item, and it is the only
     * thing in the descriptor that identifies a token. Matching the byte string
     * rather than parsing the descriptor is enough because the three bytes are a
     * complete item: a longer item would have a different prefix.
     */
    fun isFidoDescriptor(descriptor: ByteArray): Boolean {
        if (descriptor.size < FIDO_USAGE_PAGE.size) return false
        for (i in 0..descriptor.size - FIDO_USAGE_PAGE.size) {
            if (descriptor.copyOfRange(i, i + FIDO_USAGE_PAGE.size).contentEquals(FIDO_USAGE_PAGE)) return true
        }
        return false
    }

    /** The HID interfaces of [device], in the order they are declared. */
    private fun hidInterfaces(device: UsbDevice): List<UsbInterface> =
        (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .filter { it.interfaceClass == UsbConstants.USB_CLASS_HID }

    /**
     * Devices that could be security keys, without opening any of them.
     *
     * Only a HID interface with a pair of interrupt endpoints can carry CTAPHID,
     * and that is as far as a listing can go: telling a token from a keyboard
     * needs the report descriptor, and reading it needs permission the person
     * has not been asked for yet.
     */
    fun candidates(manager: UsbManager?): List<UsbDevice> =
        manager?.deviceList?.values.orEmpty().filter { device ->
            hidInterfaces(device).any { endpointsOf(it) != null }
        }

    fun hasPermission(manager: UsbManager?, device: UsbDevice): Boolean = manager?.hasPermission(device) == true

    /**
     * Ask the user for access to [device]. [onResult] runs on the main thread;
     * it is a system dialog, so it can take as long as the person does.
     */
    fun requestPermission(context: Context, device: UsbDevice, onResult: (Boolean) -> Unit) {
        val manager = context.getSystemService(UsbManager::class.java)
        if (manager == null) {
            onResult(false)
            return
        }
        if (manager.hasPermission(device)) {
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
        manager.requestPermission(device, pending)
    }

    /**
     * Open [device] as a CTAPHID transport, or say why it is not one.
     *
     * The interface is claimed with `force`, because on many phones the kernel's
     * HID driver has it first and will not give it up otherwise.
     */
    fun open(context: Context, device: UsbDevice): HidCtapTransport {
        val manager = context.getSystemService(UsbManager::class.java)
            ?: throw CtapException("This phone has no USB host support, so a security key cannot be plugged in.")
        if (!manager.hasPermission(device)) {
            throw CtapException("flintTerm was not allowed to use ${device.productName ?: "that USB device"}.")
        }
        val connection = manager.openDevice(device)
            ?: throw CtapException("Could not open ${device.productName ?: "the USB device"}. Unplug it and plug it back in.")
        for (iface in hidInterfaces(device)) {
            val endpoints = endpointsOf(iface) ?: continue
            if (!connection.claimInterface(iface, true)) continue
            val descriptor = reportDescriptor(connection, iface)
            if (descriptor == null || !isFidoDescriptor(descriptor)) {
                connection.releaseInterface(iface)
                continue
            }
            val reports = UsbHidReports(connection, iface, endpoints.first, endpoints.second)
            return HidCtapTransport(reports, name = device.productName).also {
                runCatching { it.open() }.onFailure { e ->
                    reports.close()
                    throw e
                }
            }
        }
        connection.close()
        throw CtapException("${device.productName ?: "That device"} is not a security key.")
    }

    /** The interrupt in/out pair a CTAPHID interface must have. */
    private fun endpointsOf(iface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
        var input: UsbEndpoint? = null
        var output: UsbEndpoint? = null
        for (i in 0 until iface.endpointCount) {
            val endpoint = iface.getEndpoint(i)
            if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_INT) continue
            if (endpoint.direction == UsbConstants.USB_DIR_IN) input = input ?: endpoint else output = output ?: endpoint
        }
        val i = input ?: return null
        val o = output ?: return null
        return i to o
    }

    /** GET_DESCRIPTOR(REPORT) on the interface, which is how a token identifies itself. */
    private fun reportDescriptor(connection: UsbDeviceConnection, iface: UsbInterface): ByteArray? {
        val buffer = ByteArray(1024)
        val read = connection.controlTransfer(
            UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_STANDARD or 0x01, // recipient: interface
            REQUEST_GET_DESCRIPTOR,
            DESCRIPTOR_REPORT shl 8,
            iface.id,
            buffer,
            buffer.size,
            CONTROL_TIMEOUT_MS,
        )
        if (read <= 0) {
            Log.i(TAG, "no report descriptor from interface ${iface.id}")
            return null
        }
        return buffer.copyOf(read)
    }

    private const val CONTROL_TIMEOUT_MS = 1_000
}

/** The interrupt endpoints of a claimed HID interface, as plain reports. */
private class UsbHidReports(
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val input: UsbEndpoint,
    private val output: UsbEndpoint,
) : HidReports {
    override val packetSize: Int = output.maxPacketSize.coerceAtMost(CtapHid.PACKET_SIZE)

    override fun write(report: ByteArray) {
        val sent = connection.bulkTransfer(output, report, report.size, WRITE_TIMEOUT_MS)
        // The one failure worth naming apart from the rest: it is what being
        // unplugged mid-signature looks like from here.
        if (sent < 0) throw CtapException("The security key was removed.", recoverable = true)
    }

    override fun read(timeoutMs: Int): ByteArray? {
        val buffer = ByteArray(input.maxPacketSize.coerceAtLeast(CtapHid.PACKET_SIZE))
        val read = connection.bulkTransfer(input, buffer, buffer.size, timeoutMs)
        // A timeout and a disconnection are both -1 here, so a caller's deadline
        // is what tells them apart; anything shorter than a header is neither.
        return if (read < 5) null else buffer.copyOf(read)
    }

    override fun close() {
        runCatching { connection.releaseInterface(iface) }
        runCatching { connection.close() }
    }

    private companion object {
        const val WRITE_TIMEOUT_MS = 2_000
    }
}
