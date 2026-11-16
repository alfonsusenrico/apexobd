package com.apexobd.core.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
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
    private var input: BufferedInputStream? = null
    private var output: OutputStream? = null

    override suspend fun connect() = withContext(Dispatchers.IO) {
        disconnect()
        try {
            val s = socketFactoryProvider().createSocket()
            s.tcpNoDelay = true
            s.soTimeout = 150
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket = s
            input = BufferedInputStream(s.getInputStream(), 2048)
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
    }

    override suspend fun sendCommand(command: String, timeoutMs: Long): String = withContext(Dispatchers.IO) {
        val out = output ?: throw TransportError.Disconnected("Socket not connected")
        val inp = input ?: throw TransportError.Disconnected("Socket not connected")
        val sock = socket ?: throw TransportError.Disconnected("Socket not connected")

        try {
            while (inp.available() > 0) {
                inp.read()
            }
            val payload = (command.trim() + "\r").toByteArray(Charsets.US_ASCII)
            out.write(payload)
            out.flush()

            val sb = StringBuilder(64)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) throw TransportError.Timeout(command)
                sock.soTimeout = remaining.coerceAtMost(250L).toInt().coerceAtLeast(20)
                try {
                    val b = inp.read()
                    if (b == -1) throw TransportError.Disconnected("Remote adapter closed socket")
                    val ch = b.toChar()
                    if (ch == '>') {
                        return@withContext sb.toString()
                    }
                    sb.append(ch)
                } catch (_: SocketTimeoutException) {
                    // Loop checks deadline cleanly without spin-sleeping
                }
            }
            @Suppress("UNREACHABLE_CODE")
            sb.toString()
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
            "46" -> listOf(30 + 40)
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
