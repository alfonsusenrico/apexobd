package com.apexobd.app.platform

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.apexobd.core.transport.ElmResponseBuffer
import com.apexobd.core.transport.ObdTransport
import com.apexobd.core.transport.TransportError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.OutputStream
import java.util.UUID

data class BtDeviceCandidate(
    val address: String,
    val name: String,
    val likelyObd: Boolean
)

/**
 * ELM327 Bluetooth Classic (RFCOMM / SPP) transport for paired BT OBD-II adapters.
 * Automatically picks a paired OBD adapter if no explicit MAC address is configured,
 * and falls back cleanly to raw RFCOMM channel 1 for clone adapters with broken SDP records.
 */
class BluetoothClassicObdTransport(
    private val context: Context,
    private val deviceAddress: String = "",
    private val deviceNameHint: String = ""
) : ObdTransport {

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    override val endpointLabel: String
        get() = when {
            deviceNameHint.isNotBlank() -> deviceNameHint
            deviceAddress.isNotBlank() -> deviceAddress
            else -> "BT SPP Auto"
        }

    private var socket: BluetoothSocket? = null
    private var input: BufferedInputStream? = null
    private var output: OutputStream? = null
    private val frameBuffer = ElmResponseBuffer()

    @SuppressLint("MissingPermission")
    override suspend fun connect() = withContext(Dispatchers.IO) {
        disconnect()
        if (!hasConnectPermission(context)) {
            throw TransportError.Disconnected("Bluetooth permission required in Android settings")
        }
        val adapter = bluetoothAdapter(context)
            ?: throw TransportError.Disconnected("Bluetooth adapter unavailable on this device")
        if (!adapter.isEnabled) {
            throw TransportError.Disconnected("Bluetooth is turned off")
        }

        val device = resolveTargetDevice(adapter)
        runCatching { adapter.cancelDiscovery() }

        val sock = openRfcommSocket(device)
        socket = sock
        input = BufferedInputStream(sock.inputStream, 2048)
        output = sock.outputStream
        frameBuffer.reset()
        _isConnected.value = true
    }

    @SuppressLint("MissingPermission")
    private fun resolveTargetDevice(adapter: BluetoothAdapter): BluetoothDevice {
        if (deviceAddress.isNotBlank()) {
            return runCatching { adapter.getRemoteDevice(deviceAddress.trim().uppercase()) }
                .getOrElse { throw TransportError.Disconnected("Invalid Bluetooth address '$deviceAddress'") }
        }
        val bonded = adapter.bondedDevices.orEmpty()
        val obdFirst = bonded.firstOrNull { isLikelyObdName(it.name) } ?: bonded.firstOrNull()
        return obdFirst ?: throw TransportError.Disconnected("No paired Bluetooth OBD2 adapter found")
    }

    @SuppressLint("MissingPermission")
    private fun openRfcommSocket(device: BluetoothDevice): BluetoothSocket {
        val primary = runCatching {
            device.createRfcommSocketToServiceRecord(SPP_UUID).apply { connect() }
        }
        if (primary.isSuccess) return primary.getOrThrow()

        primary.getOrNull()?.let { runCatching { it.close() } }
        val fallback = runCatching {
            val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            (method.invoke(device, 1) as BluetoothSocket).apply { connect() }
        }
        return fallback.getOrElse { err ->
            throw TransportError.Disconnected("Bluetooth SPP connect failed (${device.name ?: device.address}): ${err.message}", err)
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        _isConnected.value = false
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
        frameBuffer.reset()
    }

    override suspend fun sendCommand(command: String, timeoutMs: Long): String = withContext(Dispatchers.IO) {
        val out = output ?: throw TransportError.Disconnected("Bluetooth socket not connected")
        val inp = input ?: throw TransportError.Disconnected("Bluetooth socket not connected")
        val sock = socket ?: throw TransportError.Disconnected("Bluetooth socket not connected")
        if (!sock.isConnected) {
            disconnect()
            throw TransportError.Disconnected("Bluetooth connection closed")
        }

        try {
            while (inp.available() > 0) {
                inp.read()
            }
            frameBuffer.reset()
            out.write(ElmResponseBuffer.formatCommandBytes(command))
            out.flush()

            val scratch = ByteArray(512)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val avail = inp.available()
                if (avail > 0) {
                    val n = inp.read(scratch, 0, avail.coerceAtMost(scratch.size))
                    if (n < 0) throw TransportError.Disconnected("Bluetooth stream ended")
                    val complete = frameBuffer.appendBytes(scratch, n)
                    if (complete != null) return@withContext complete
                    continue
                }
                if (!sock.isConnected) {
                    throw TransportError.Disconnected("Bluetooth link lost")
                }
                if (System.currentTimeMillis() >= deadline) {
                    throw TransportError.Timeout(command)
                }
                delay(4L)
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
            throw TransportError.Disconnected("Bluetooth I/O error on '$command': ${t.message}", t)
        }
    }

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private val OBD_KEYWORDS = listOf("OBD", "ELM", "VGATE", "VEEPEAK", "KONNWEI", "CARISTA", "NEXAS", "ICAR", "V-LINK", "SCAN")

        fun isLikelyObdName(name: String?): Boolean {
            val upper = name?.uppercase().orEmpty()
            return OBD_KEYWORDS.any { it in upper }
        }

        fun hasConnectPermission(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }

        fun bluetoothAdapter(context: Context): BluetoothAdapter? =
            context.getSystemService(BluetoothManager::class.java)?.adapter

        @SuppressLint("MissingPermission")
        fun listPairedAdapters(context: Context): List<BtDeviceCandidate> {
            if (!hasConnectPermission(context)) return emptyList()
            val adapter = bluetoothAdapter(context) ?: return emptyList()
            return runCatching {
                adapter.bondedDevices.orEmpty()
                    .map { d ->
                        val label = d.name?.takeIf { it.isNotBlank() } ?: d.address
                        BtDeviceCandidate(d.address, label, isLikelyObdName(label))
                    }
                    .sortedWith(compareByDescending<BtDeviceCandidate> { it.likelyObd }.thenBy { it.name })
            }.getOrDefault(emptyList())
        }
    }
}
