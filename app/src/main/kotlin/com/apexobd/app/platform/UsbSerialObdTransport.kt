package com.apexobd.app.platform

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import com.apexobd.core.transport.ElmResponseBuffer
import com.apexobd.core.transport.ObdTransport
import com.apexobd.core.transport.TransportError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

enum class UsbChipFamily { FTDI, CP210X, CH340, CDC_ACM, GENERIC_BULK }

data class UsbSerialCandidate(
    val deviceId: Int,
    val vendorId: Int,
    val productId: Int,
    val label: String,
    val chipFamily: UsbChipFamily,
    val hasPermission: Boolean
)

/**
 * Pure USB control-transfer math shared across chipset initialization and JVM unit tests.
 */
object UsbSerialCodec {
    fun detectChipFamily(vendorId: Int, interfaceClass: Int): UsbChipFamily = when (vendorId) {
        0x0403 -> UsbChipFamily.FTDI
        0x10C4 -> UsbChipFamily.CP210X
        0x1A86 -> UsbChipFamily.CH340
        else -> if (interfaceClass == UsbConstants.USB_CLASS_COMM || interfaceClass == UsbConstants.USB_CLASS_CDC_DATA) {
            UsbChipFamily.CDC_ACM
        } else {
            UsbChipFamily.GENERIC_BULK
        }
    }

    fun cdcLineCodingBytes(baudRate: Int): ByteArray = byteArrayOf(
        (baudRate and 0xFF).toByte(),
        ((baudRate shr 8) and 0xFF).toByte(),
        ((baudRate shr 16) and 0xFF).toByte(),
        ((baudRate shr 24) and 0xFF).toByte(),
        0x00, // 1 stop bit
        0x00, // no parity
        0x08  // 8 data bits
    )

    fun ftdiBaudDivisorValue(baudRate: Int): Int {
        val base = 3_000_000
        return (base / baudRate.coerceAtLeast(9600)).coerceIn(1, 0xFFFF)
    }

    fun cp210xBaudBytes(baudRate: Int): ByteArray = byteArrayOf(
        (baudRate and 0xFF).toByte(),
        ((baudRate shr 8) and 0xFF).toByte(),
        ((baudRate shr 16) and 0xFF).toByte(),
        ((baudRate shr 24) and 0xFF).toByte()
    )
}

/**
 * Native Android USB Host ELM327 / OBDLink OTG serial transport supporting FTDI, CP210x,
 * CH340, and CDC-ACM USB-to-UART cables without external serial driver bloat.
 */
class UsbSerialObdTransport(
    private val context: Context,
    private val targetDeviceId: Int = 0,
    private val baudRate: Int = 38400
) : ObdTransport {

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    override val endpointLabel: String get() = "USB OTG @ $baudRate"

    private var connection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private var isFtdiChip = false
    private val frameBuffer = ElmResponseBuffer()

    override suspend fun connect() = withContext(Dispatchers.IO) {
        disconnect()
        val usbMgr = context.getSystemService(UsbManager::class.java)
            ?: throw TransportError.Disconnected("USB Host unavailable on device")

        val devices = usbMgr.deviceList.values.toList()
        val device = if (targetDeviceId != 0) {
            devices.firstOrNull { it.deviceId == targetDeviceId } ?: devices.firstOrNull()
        } else {
            devices.firstOrNull()
        } ?: throw TransportError.Disconnected("No USB OBD2 adapter attached via OTG")

        if (!usbMgr.hasPermission(device)) {
            requestPermission(context, device)
            throw TransportError.Disconnected("USB OTG permission needed — approve prompt and retry")
        }

        val (iface, inEp, outEp) = findBulkEndpoints(device)
            ?: throw TransportError.Disconnected("USB device has no serial bulk endpoints")

        val conn = usbMgr.openDevice(device)
            ?: throw TransportError.Disconnected("Failed to open USB device")

        if (!conn.claimInterface(iface, true)) {
            conn.close()
            throw TransportError.Disconnected("Failed to claim USB serial interface")
        }

        val family = UsbSerialCodec.detectChipFamily(device.vendorId, iface.interfaceClass)
        configureSerialChip(conn, iface.id, family, baudRate)
        isFtdiChip = family == UsbChipFamily.FTDI

        connection = conn
        claimedInterface = iface
        epIn = inEp
        epOut = outEp
        frameBuffer.reset()
        _isConnected.value = true
    }

    private fun configureSerialChip(conn: UsbDeviceConnection, ifaceId: Int, family: UsbChipFamily, baud: Int) {
        when (family) {
            UsbChipFamily.FTDI -> {
                conn.controlTransfer(0x40, 0x00, 0, ifaceId, null, 0, 500) // Reset
                conn.controlTransfer(0x40, 0x03, UsbSerialCodec.ftdiBaudDivisorValue(baud), ifaceId, null, 0, 500)
                conn.controlTransfer(0x40, 0x04, 0x0008, ifaceId, null, 0, 500) // 8N1
                conn.controlTransfer(0x40, 0x01, 0x0303, ifaceId, null, 0, 500) // DTR+RTS
            }
            UsbChipFamily.CP210X -> {
                conn.controlTransfer(0x41, 0x00, 1, ifaceId, null, 0, 500) // UART Enable
                val b = UsbSerialCodec.cp210xBaudBytes(baud)
                conn.controlTransfer(0x41, 0x1E, 0, ifaceId, b, b.size, 500)
            }
            UsbChipFamily.CH340 -> {
                conn.controlTransfer(0x40, 0xA1, 0, 0, null, 0, 500)
                val div = if (baud >= 115200) 0xCC03 else 0x8A02
                conn.controlTransfer(0x40, 0x9A, 0x1312, div, null, 0, 500)
                conn.controlTransfer(0x40, 0x9A, 0x2518, 0x00C3, null, 0, 500)
            }
            UsbChipFamily.CDC_ACM, UsbChipFamily.GENERIC_BULK -> {
                val line = UsbSerialCodec.cdcLineCodingBytes(baud)
                conn.controlTransfer(0x21, 0x22, 0x03, ifaceId, null, 0, 500)
                conn.controlTransfer(0x21, 0x20, 0, ifaceId, line, line.size, 500)
            }
        }
    }

    private fun findBulkEndpoints(device: UsbDevice): Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
                }
            }
            if (inEp != null && outEp != null) {
                return Triple(iface, inEp, outEp)
            }
        }
        return null
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        _isConnected.value = false
        val conn = connection
        val iface = claimedInterface
        runCatching { if (conn != null && iface != null) conn.releaseInterface(iface) }
        runCatching { conn?.close() }
        connection = null
        claimedInterface = null
        epIn = null
        epOut = null
        frameBuffer.reset()
    }

    override suspend fun sendCommand(command: String, timeoutMs: Long): String = withContext(Dispatchers.IO) {
        val conn = connection ?: throw TransportError.Disconnected("USB device not open")
        val inEndpoint = epIn ?: throw TransportError.Disconnected("USB IN endpoint missing")
        val outEndpoint = epOut ?: throw TransportError.Disconnected("USB OUT endpoint missing")

        try {
            val drain = ByteArray(inEndpoint.maxPacketSize.coerceAtLeast(64))
            while (conn.bulkTransfer(inEndpoint, drain, drain.size, 10) > 0) {
                // drain stale adapter bytes
            }
            frameBuffer.reset()

            val payload = ElmResponseBuffer.formatCommandBytes(command)
            val written = conn.bulkTransfer(outEndpoint, payload, payload.size, timeoutMs.toInt().coerceAtLeast(250))
            if (written < 0) throw TransportError.Disconnected("USB bulk write failed on '$command'")

            val buf = ByteArray(inEndpoint.maxPacketSize.coerceAtLeast(64))
            val ftdi = isFtdiChip || hasFtdiHeader()
            val deadline = System.currentTimeMillis() + timeoutMs

            while (true) {
                val rem = deadline - System.currentTimeMillis()
                if (rem <= 0L) throw TransportError.Timeout(command)
                val n = conn.bulkTransfer(inEndpoint, buf, buf.size, rem.coerceAtMost(120L).toInt().coerceAtLeast(20))
                if (n > 0) {
                    val startOffset = if (ftdi && n >= 2) 2 else 0
                    if (n > startOffset) {
                        val slice = if (startOffset == 0) buf else buf.copyOfRange(startOffset, n)
                        val complete = frameBuffer.appendBytes(slice, slice.size)
                        if (complete != null) return@withContext complete
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            ""
        } catch (e: CancellationException) {
            disconnect()
            throw e
        } catch (e: TransportError) {
            if (e is TransportError.Disconnected) disconnect()
            throw e
        } catch (t: Throwable) {
            disconnect()
            throw TransportError.Disconnected("USB I/O error: ${t.message}", t)
        }
    }

    private fun hasFtdiHeader(): Boolean {
        val iface = claimedInterface ?: return false
        return iface.name?.uppercase()?.contains("FTDI") == true
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.apexobd.app.USB_PERMISSION"

        fun requestPermission(context: Context, device: UsbDevice) {
            val usbMgr = context.getSystemService(UsbManager::class.java) ?: return
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), flags)
            runCatching { usbMgr.requestPermission(device, pi) }
        }

        fun listAttachedAdapters(context: Context): List<UsbSerialCandidate> {
            val usbMgr = context.getSystemService(UsbManager::class.java) ?: return emptyList()
            return usbMgr.deviceList.values.map { d ->
                val cls = if (d.interfaceCount > 0) d.getInterface(0).interfaceClass else 0
                val fam = UsbSerialCodec.detectChipFamily(d.vendorId, cls)
                val product = d.productName?.takeIf { it.isNotBlank() } ?: "USB Serial (%04X:%04X)".format(d.vendorId, d.productId)
                UsbSerialCandidate(
                    deviceId = d.deviceId,
                    vendorId = d.vendorId,
                    productId = d.productId,
                    label = product,
                    chipFamily = fam,
                    hasPermission = usbMgr.hasPermission(d)
                )
            }
        }
    }
}
