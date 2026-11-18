package com.apexobd.app.platform

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.apexobd.core.transport.ElmResponseBuffer
import com.apexobd.core.transport.ObdTransport
import com.apexobd.core.transport.TransportError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID

data class BleUartPairSpec(
    val servicePrefix: String,
    val notifyUuid: String,
    val writeUuid: String
)

object BleElmGattCatalog {
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    val knownProfiles = listOf(
        // Vgate / generic dual-char ELM327 BLE
        BleUartPairSpec("0000fff0", "0000fff1", "0000fff2"),
        // HM-10 / OBDLink / single shared characteristic FFE0
        BleUartPairSpec("0000ffe0", "0000ffe1", "0000ffe1"),
        // 18F0 BLE ELM327
        BleUartPairSpec("000018f0", "00002af0", "00002af1"),
        // ISSC / Microchip transparent UART
        BleUartPairSpec("49535343-fe7d-4ae5-8fa9-9fafd205e455", "49535343-1e4d-4bd9-ba61-23c647249616", "49535343-8841-43f4-a8d4-ecbe34729bb3"),
        // Nordic UART Service
        BleUartPairSpec("6e400001-b5a3-f393-e0a9-e50e24dcca9e", "6e400003-b5a3-f393-e0a9-e50e24dcca9e", "6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    )

    fun matchesPrefix(uuidString: String, prefix: String): Boolean =
        uuidString.lowercase().startsWith(prefix.lowercase())
}

/**
 * Bluetooth Low Energy (GATT) ELM327 transport for BLE 4.0+ adapters (Vgate iCar Pro, Veepeak, etc.).
 */
class BluetoothLeObdTransport(
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
            else -> "BLE GATT"
        }

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var chunkSize = 20

    private val frameBuffer = ElmResponseBuffer()
    private val commandMutex = Mutex()
    @Volatile
    private var readySignal: CompletableDeferred<Unit>? = null
    @Volatile
    private var pendingResponse: CompletableDeferred<String>? = null
    @Volatile
    private var pendingWriteAck: CompletableDeferred<Unit>? = null

    @SuppressLint("MissingPermission")
    override suspend fun connect() = withContext(Dispatchers.IO) {
        disconnect()
        if (!BluetoothClassicObdTransport.hasConnectPermission(context)) {
            throw TransportError.Disconnected("Bluetooth Connect permission required")
        }
        val adapter = BluetoothClassicObdTransport.bluetoothAdapter(context)
            ?: throw TransportError.Disconnected("Bluetooth unavailable")
        if (!adapter.isEnabled) {
            throw TransportError.Disconnected("Bluetooth is turned off")
        }
        if (deviceAddress.isBlank()) {
            throw TransportError.Disconnected("Select a BLE OBD2 adapter in Connection settings")
        }

        val device = runCatching { adapter.getRemoteDevice(deviceAddress.trim().uppercase()) }
            .getOrElse { throw TransportError.Disconnected("Invalid BLE MAC '$deviceAddress'") }

        val signal = CompletableDeferred<Unit>()
        readySignal = signal
        frameBuffer.reset()

        val g = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            ?: throw TransportError.Disconnected("connectGatt returned null")
        gatt = g

        try {
            withTimeout(7500L) { signal.await() }
            _isConnected.value = true
        } catch (t: Throwable) {
            disconnect()
            throw TransportError.Disconnected("BLE handshake failed: ${t.message}", t)
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        _isConnected.value = false
        readySignal?.cancel()
        pendingResponse?.cancel()
        pendingWriteAck?.cancel()
        readySignal = null
        pendingResponse = null
        pendingWriteAck = null
        writeChar = null
        notifyChar = null
        runCatching {
            if (BluetoothClassicObdTransport.hasConnectPermission(context)) {
                gatt?.disconnect()
                gatt?.close()
            }
        }
        gatt = null
        frameBuffer.reset()
    }

    @SuppressLint("MissingPermission")
    override suspend fun sendCommand(command: String, timeoutMs: Long): String = commandMutex.withLock {
        val g = gatt ?: throw TransportError.Disconnected("BLE GATT not connected")
        val wc = writeChar ?: throw TransportError.Disconnected("BLE write characteristic missing")
        if (!_isConnected.value) throw TransportError.Disconnected("BLE disconnected")

        val payload = ElmResponseBuffer.formatCommandBytes(command)
        val resp = CompletableDeferred<String>()
        frameBuffer.reset()
        pendingResponse = resp

        try {
            val useNoResp = (wc.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            val writeType = if (useNoResp) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

            var offset = 0
            while (offset < payload.size) {
                val end = (offset + chunkSize).coerceAtMost(payload.size)
                val slice = payload.copyOfRange(offset, end)
                val ack = if (!useNoResp) CompletableDeferred<Unit>() else null
                pendingWriteAck = ack
                val ok = writeChunkCompat(g, wc, slice, writeType)
                if (!ok) {
                    throw TransportError.Disconnected("BLE write failed for '$command'")
                }
                if (ack != null) {
                    withTimeout(timeoutMs) { ack.await() }
                }
                offset = end
            }

            return withTimeout(timeoutMs) { resp.await() }
        } catch (e: CancellationException) {
            disconnect()
            throw e
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            throw TransportError.Timeout(command)
        } finally {
            pendingResponse = null
            pendingWriteAck = null
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun writeChunkCompat(
        g: BluetoothGatt,
        wc: BluetoothGattCharacteristic,
        slice: ByteArray,
        writeType: Int
    ): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        g.writeCharacteristic(wc, slice, writeType) == BluetoothStatusCodes.SUCCESS
    } else {
        wc.writeType = writeType
        wc.value = slice
        g.writeCharacteristic(wc)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                runCatching { gatt.requestMtu(128) }
                runCatching { gatt.discoverServices() }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _isConnected.value = false
                readySignal?.completeExceptionally(TransportError.Disconnected("BLE disconnected ($status)"))
                pendingResponse?.completeExceptionally(TransportError.Disconnected("BLE link dropped ($status)"))
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && mtu > 23) {
                chunkSize = (mtu - 3).coerceIn(20, 120)
            }
        }

        @Suppress("DEPRECATION")
        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                readySignal?.completeExceptionally(TransportError.Disconnected("GATT discovery failed ($status)"))
                return
            }
            val pair = selectUartCharacteristics(gatt)
            if (pair == null) {
                readySignal?.completeExceptionally(TransportError.Disconnected("No compatible ELM327 GATT service found"))
                return
            }
            notifyChar = pair.first
            writeChar = pair.second
            gatt.setCharacteristicNotification(pair.first, true)
            val cccd = pair.first.getDescriptor(BleElmGattCatalog.CCCD_UUID)
            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(cccd)
                }
            } else {
                readySignal?.complete(Unit)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            readySignal?.complete(Unit)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            pendingWriteAck?.complete(Unit)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncoming(value)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            characteristic.value?.let(::handleIncoming)
        }
    }

    private fun handleIncoming(bytes: ByteArray) {
        val complete = frameBuffer.appendBytes(bytes) ?: return
        pendingResponse?.complete(complete)
    }

    private fun selectUartCharacteristics(gatt: BluetoothGatt): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
        val services = gatt.services.orEmpty()
        for (spec in BleElmGattCatalog.knownProfiles) {
            val svc = services.firstOrNull { BleElmGattCatalog.matchesPrefix(it.uuid.toString(), spec.servicePrefix) }
                ?: continue
            val nChar = svc.characteristics.firstOrNull { BleElmGattCatalog.matchesPrefix(it.uuid.toString(), spec.notifyUuid) }
            val wChar = svc.characteristics.firstOrNull { BleElmGattCatalog.matchesPrefix(it.uuid.toString(), spec.writeUuid) }
            if (nChar != null && wChar != null) return nChar to wChar
        }
        // Generic fallback: any custom service offering both notify/indicate and write properties
        for (svc in services) {
            val nChar = svc.characteristics.firstOrNull {
                (it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0
            }
            val wChar = svc.characteristics.firstOrNull {
                (it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
            }
            if (nChar != null && wChar != null) return nChar to wChar
        }
        return null
    }

    companion object {
        fun hasScanPermission(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            }

        @SuppressLint("MissingPermission")
        suspend fun scanNearbyAdapters(context: Context, durationMs: Long = 4000L): List<BtDeviceCandidate> {
            if (!hasScanPermission(context) || !BluetoothClassicObdTransport.hasConnectPermission(context)) {
                return emptyList()
            }
            val adapter = BluetoothClassicObdTransport.bluetoothAdapter(context) ?: return emptyList()
            if (!adapter.isEnabled) return emptyList()
            val scanner = adapter.bluetoothLeScanner ?: return emptyList()

            val seen = linkedMapOf<String, BtDeviceCandidate>()
            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val d = result.device ?: return
                    val name = d.name?.takeIf { it.isNotBlank() }
                        ?: result.scanRecord?.deviceName?.takeIf { it.isNotBlank() }
                        ?: return
                    seen[d.address] = BtDeviceCandidate(
                        address = d.address,
                        name = name,
                        likelyObd = BluetoothClassicObdTransport.isLikelyObdName(name)
                    )
                }
            }
            runCatching { scanner.startScan(cb) }
            try {
                delay(durationMs)
            } finally {
                runCatching { scanner.stopScan(cb) }
            }
            return seen.values
                .sortedWith(compareByDescending<BtDeviceCandidate> { it.likelyObd }.thenBy { it.name })
        }
    }
}
