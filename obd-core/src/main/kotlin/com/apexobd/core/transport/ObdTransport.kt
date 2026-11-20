package com.apexobd.core.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.SocketFactory
import kotlin.math.cos
import kotlin.math.sin

sealed class TransportError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Timeout(cmd: String) : TransportError("Timed out waiting for prompt on '$cmd'")
    class Disconnected(reason: String, cause: Throwable? = null) : TransportError(reason, cause)
}

enum class ObdConnectionType(val label: String, val shortLabel: String) {
    WIFI_TCP("Wi-Fi (TCP/IP)", "Wi-Fi"),
    BLUETOOTH_CLASSIC("Bluetooth Classic (SPP)", "BT"),
    BLUETOOTH_LE("Bluetooth LE (GATT)", "BLE"),
    USB_SERIAL("USB-Serial (OTG)", "USB")
}

/**
 * Shared ASCII ELM327 framing buffer across TCP, Bluetooth Classic, BLE GATT, and USB Serial.
 * Collects incoming bytes until the ELM327 prompt character '>' arrives.
 */
class ElmResponseBuffer {
    private val sb = StringBuilder(128)
    private val lock = Any()

    fun reset() {
        synchronized(lock) { sb.setLength(0) }
    }

    fun appendByte(b: Int): String? {
        if (b <= 0) return null
        synchronized(lock) {
            val ch = b.toChar()
            if (ch == '>') {
                val out = sb.toString()
                sb.setLength(0)
                return out
            }
            sb.append(ch)
            return null
        }
    }

    fun appendBytes(bytes: ByteArray, length: Int = bytes.size): String? {
        val count = length.coerceIn(0, bytes.size)
        synchronized(lock) {
            for (i in 0 until count) {
                val raw = bytes[i].toInt() and 0xFF
                if (raw == 0) continue
                val ch = raw.toChar()
                if (ch == '>') {
                    val out = sb.toString()
                    sb.setLength(0)
                    return out
                }
                sb.append(ch)
            }
            return null
        }
    }

    companion object {
        fun formatCommandBytes(command: String): ByteArray =
            (command.trim() + "\r").toByteArray(Charsets.US_ASCII)
    }
}

interface ObdTransport {
    val isConnected: StateFlow<Boolean>
    val endpointLabel: String
    suspend fun connect()
    suspend fun disconnect()
    suspend fun sendCommand(command: String, timeoutMs: Long = 2500L): String
}

/**
 * Low-latency Wi-Fi ELM327 TCP transport (default 192.168.0.10:35000).
 * Uses blocking buffered reads with a short socket read timeout rather than sleep-polling,
 * returning immediately upon receiving the ELM327 '>' prompt.
 */
class WifiTcpTransport(
    private val host: String = "192.168.0.10",
    private val port: Int = 35000,
    private val connectTimeoutMs: Int = 3500,
    private val socketFactoryProvider: () -> SocketFactory = { SocketFactory.getDefault() }
) : ObdTransport {

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    override val endpointLabel: String get() = "$host:$port"

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private val readBuffer = ByteArray(256)
    private var currentSoTimeoutMs: Int = -1

    override suspend fun connect() = withContext(Dispatchers.IO) {
        disconnect()
        try {
            val s = socketFactoryProvider().createSocket()
            runCatching { s.setPerformancePreferences(0, 2, 1) }
            s.tcpNoDelay = true
            s.keepAlive = true
            runCatching { s.trafficClass = 0x10 } // IPTOS_LOWDELAY for WMM low-latency queuing
            s.soTimeout = 200
            currentSoTimeoutMs = 200
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket = s
            input = s.getInputStream()
            output = s.getOutputStream()
            _isConnected.value = true
        } catch (t: Throwable) {
            disconnect()
            throw TransportError.Disconnected("Failed to connect to $host:$port: ${t.message}", t)
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
        currentSoTimeoutMs = -1
    }

    override suspend fun sendCommand(command: String, timeoutMs: Long): String = withContext(Dispatchers.IO) {
        val out = output ?: throw TransportError.Disconnected("Socket not connected")
        val inp = input ?: throw TransportError.Disconnected("Socket not connected")
        val sock = socket ?: throw TransportError.Disconnected("Socket not connected")

        try {
            // Drain any stale buffered bytes in bulk before transmitting the next command
            while (inp.available() > 0) {
                val toRead = inp.available().coerceAtMost(readBuffer.size)
                if (inp.read(readBuffer, 0, toRead) <= 0) break
            }
            val payload = (command.trim() + "\r").toByteArray(Charsets.US_ASCII)
            out.write(payload)
            out.flush()

            val sb = StringBuilder(64)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) {
                    // Best-effort carriage return resync so ELM327 returns to '>' prompt on next command
                    runCatching {
                        out.write('\r'.code)
                        out.flush()
                    }
                    throw TransportError.Timeout(command)
                }
                val desiredSoTimeout = remaining.coerceAtMost(200L).toInt().coerceAtLeast(25)
                if (desiredSoTimeout != currentSoTimeoutMs) {
                    sock.soTimeout = desiredSoTimeout
                    currentSoTimeoutMs = desiredSoTimeout
                }
                try {
                    val count = inp.read(readBuffer)
                    if (count == -1) throw TransportError.Disconnected("Remote adapter closed socket")
                    for (i in 0 until count) {
                        val raw = readBuffer[i].toInt() and 0xFF
                        if (raw == 0) continue
                        val ch = raw.toChar()
                        if (ch == '>') {
                            return@withContext sb.toString()
                        }
                        sb.append(ch)
                    }
                } catch (_: SocketTimeoutException) {
                    // Loop checks deadline cleanly without spin-sleeping
                }
            }
            @Suppress("UNREACHABLE_CODE")
            sb.toString()
        } catch (e: CancellationException) {
            disconnect()
            throw e
        } catch (e: TransportError) {
            if (e is TransportError.Disconnected) disconnect()
            throw e
        } catch (t: Throwable) {
            disconnect()
            throw TransportError.Disconnected("I/O error on '$command': ${t.message}", t)
        }
    }
}

/**
 * Deterministic + dynamic physics ELM327 simulator supporting single PIDs,
 * CAN response-count hints (`010C1`), and CAN multi-PID batching (`010C0D1011`).
 */
class SimulatedObdTransport(
    private val responseDelayMs: Long = 4L
) : ObdTransport {
    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    override val endpointLabel: String = "Simulator (ELM327 v1.5)"

    private var startMillis = System.currentTimeMillis()
    private var dtcsCleared = false

    override suspend fun connect() {
        startMillis = System.currentTimeMillis()
        _isConnected.value = true
    }

    override suspend fun disconnect() {
        _isConnected.value = false
    }

    override suspend fun sendCommand(command: String, timeoutMs: Long): String {
        if (!_isConnected.value) throw TransportError.Disconnected("Simulator disconnected")
        if (responseDelayMs > 0) delay(responseDelayMs)
        val cmd = command.trim().uppercase()

        return when {
            cmd == "ATZ" -> "ELM327 v1.5\r\r"
            cmd.startsWith("AT") && cmd != "ATDP" && cmd != "ATDPN" -> "OK\r"
            cmd == "ATDP" -> "ISO 15765-4 (CAN 11/500)\r"
            cmd == "ATDPN" -> "A6\r"
            cmd == "04" -> {
                dtcsCleared = true
                "44\r"
            }
            cmd == "03" -> if (dtcsCleared) "43 00\r" else "43 02 01 71 04 20\r"
            cmd == "07" -> if (dtcsCleared) "47 00\r" else "47 01 03 01\r"
            cmd == "0A" -> "4A 00\r"
            cmd == "0902" -> "014\r0: 49 02 01 4A 54 44\r1: 4B 4E 33 44 55 35 41\r2: 30 31 32 33 34 35 36\r"
            cmd.startsWith("02") -> handleFreezeFrame(cmd)
            cmd.startsWith("01") -> handleMode01(cmd)
            cmd.startsWith("22") -> handleMode22(cmd)
            else -> "NO DATA\r"
        }
    }

    private fun handleMode01(cmd: String): String {
        val body = cmd.removePrefix("01")
        // Strip optional trailing CAN expected-response count ('1') when odd-length
        val pidHexStream = if (body.length % 2 == 1 && body.endsWith("1")) body.dropLast(1) else body
        if (pidHexStream.length > 2 && pidHexStream.length % 2 == 0) {
            // Multi-PID CAN response: 41 <pid1><bytes1><pid2><bytes2>...
            val out = StringBuilder("41")
            for (i in pidHexStream.indices step 2) {
                val pid = pidHexStream.substring(i, i + 2)
                val data = mode01Bytes(pid) ?: return "NO DATA\r"
                out.append(' ').append(pid)
                for (b in data) out.append(' ').append("%02X".format(b))
            }
            return out.append('\r').toString()
        }
        val pid = pidHexStream.take(2)
        val data = mode01Bytes(pid) ?: return "NO DATA\r"
        val out = StringBuilder("41 ").append(pid)
        for (b in data) out.append(' ').append("%02X".format(b))
        return out.append('\r').toString()
    }

    private fun mode01Bytes(pid: String): List<Int>? {
        val t = (System.currentTimeMillis() - startMillis) / 1000.0
        val rpm = (2800 + sin(t * 0.9) * 1800 + cos(t * 0.4) * 500).toInt().coerceIn(750, 6800)
        val speed = (74 + sin(t * 0.5) * 32).toInt().coerceIn(0, 180)
        val throttle = (36 + sin(t * 0.9) * 32).toInt().coerceIn(8, 96)
        val maf = ((12.0 + throttle * 0.52) * 100).toInt()
        val timing = (14.0 + cos(t * 0.9) * 8.0)

        return when (pid) {
            "00" -> listOf(0xBE, 0x3F, 0xA8, 0x13)
            "20" -> listOf(0x80, 0x07, 0xB0, 0x11)
            "40" -> listOf(0x7A, 0xD0, 0x00, 0x00)
            "60" -> listOf(0x06, 0x00, 0x00, 0x00)
            "80" -> listOf(0x00, 0x00, 0x00, 0x00)
            "A0" -> listOf(0x00, 0x00, 0x00, 0x00)
            "01" -> if (dtcsCleared) listOf(0x00, 0x07, 0x65, 0x00) else listOf(0x82, 0x07, 0x65, 0x08)
            "04" -> listOf((throttle * 255) / 100)
            "05" -> listOf(90 + 40)
            "06" -> listOf(130)
            "07" -> listOf(127)
            "0B" -> listOf((95 + throttle * 0.8).toInt().coerceIn(30, 220))
            "0C" -> {
                val raw = rpm * 4
                listOf((raw shr 8) and 0xFF, raw and 0xFF)
            }
            "0D" -> listOf(speed)
            "0E" -> listOf(((timing + 64.0) * 2.0).toInt().coerceIn(0, 255))
            "0F" -> listOf(34 + 40)
            "10" -> listOf((maf shr 8) and 0xFF, maf and 0xFF)
            "11" -> listOf((throttle * 255) / 100)
            "21" -> listOf(0x00, if (dtcsCleared) 0x00 else 0x2A)
            "2F" -> listOf(180)
            "33" -> listOf(101)
            "42" -> {
                val mv = 14120 + (sin(t * 0.3) * 140).toInt()
                listOf((mv shr 8) and 0xFF, mv and 0xFF)
            }
            "45" -> listOf((((throttle - 8).coerceAtLeast(0)) * 255) / 100)
            "46" -> listOf(30 + 40)
            "49" -> listOf((((throttle - 8).coerceAtLeast(0)) * 255) / 100)
            "4A" -> listOf((((throttle - 8).coerceAtLeast(0)) * 255) / 100)
            "5C" -> listOf(96 + 40)
            "62" -> listOf(125 + (throttle * 0.6).toInt())
            "63" -> listOf(0x01, 0x2C)
            else -> null
        }
    }

    private fun handleFreezeFrame(cmd: String): String {
        val pid = cmd.substring(2, 4)
        return when (pid) {
            "02" -> "42 02 01 71\r"
            "04" -> "42 04 7A\r"
            "05" -> "42 05 81\r"
            "0C" -> "42 0C 26 48\r"
            "0D" -> "42 0D 42\r"
            "06" -> "42 06 97\r"
            else -> "NO DATA\r"
        }
    }

    private fun handleMode22(cmd: String): String {
        return when (cmd.take(6)) {
            "221E1C" -> "62 1E 1C 04 88\r"
            "221470" -> "62 14 70 88\r"
            else -> "NO DATA\r"
        }
    }
}
