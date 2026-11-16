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
 * Wi-Fi ELM327 TCP transport (e.g. 192.168.0.10:35000).
 * Accepts a [socketFactoryProvider] so Android can bind sockets to the Wi-Fi Network
 * even when cellular data is active.
 */
class WifiTcpTransport(
    private val host: String = "192.168.0.10",
    private val port: Int = 35000,
    private val connectTimeoutMs: Int = 4000,
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
            s.soTimeout = 500
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket = s
            input = BufferedInputStream(s.getInputStream())
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

        try {
            // Drain any stale bytes before sending next command
            while (inp.available() > 0) {
                inp.read()
            }
            val payload = (command.trim() + "\r").toByteArray(Charsets.US_ASCII)
            out.write(payload)
            out.flush()

            val sb = StringBuilder()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (inp.available() > 0) {
                    val b = inp.read()
                    if (b == -1) throw TransportError.Disconnected("Remote adapter closed socket")
                    val ch = b.toChar()
                    if (ch == '>') {
                        return@withContext sb.toString()
                    }
                    sb.append(ch)
                } else {
                    delay(8)
                }
            }
            throw TransportError.Timeout(command)
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
 * Deterministic + dynamic physics ELM327 simulator so the full app and tests
 * run without a physical vehicle plugged in.
 */
class SimulatedObdTransport(
    private val responseDelayMs: Long = 12L
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
        val t = (System.currentTimeMillis() - startMillis) / 1000.0
        val rpm = (2800 + sin(t * 0.9) * 1800 + cos(t * 0.4) * 500).toInt().coerceIn(750, 6800)
        val speed = (74 + sin(t * 0.5) * 32).toInt().coerceIn(0, 180)
        val throttle = (36 + sin(t * 0.9) * 32).toInt().coerceIn(8, 96)
        val maf = ((12.0 + throttle * 0.52) * 100).toInt()
        val timing = (14.0 + cos(t * 0.9) * 8.0)

        val pid = cmd.substring(2, 4)
        return when (pid) {
            "00" -> "41 00 BE 3F A8 13\r"
            "20" -> "41 20 80 07 B0 11\r"
            "40" -> "41 40 7A D0 00 00\r"
            "60" -> "41 60 06 00 00 00\r"
            "80" -> "41 80 00 00 00 00\r"
            "A0" -> "41 A0 00 00 00 00\r"
            "01" -> if (dtcsCleared) "41 01 00 07 65 00\r" else "41 01 82 07 65 08\r"
            "04" -> hex2(0x41, 0x04, (throttle * 255) / 100)
            "05" -> hex2(0x41, 0x05, 90 + 40) // 90 C
            "06" -> hex2(0x41, 0x06, 130) // +1.56% STFT
            "07" -> hex2(0x41, 0x07, 127) // -0.78% LTFT
            "0B" -> hex2(0x41, 0x0B, (95 + throttle * 0.8).toInt().coerceIn(30, 220)) // MAP kPa
            "0C" -> {
                val raw = rpm * 4
                hex3(0x41, 0x0C, (raw shr 8) and 0xFF, raw and 0xFF)
            }
            "0D" -> hex2(0x41, 0x0D, speed)
            "0E" -> hex2(0x41, 0x0E, ((timing + 64.0) * 2.0).toInt().coerceIn(0, 255))
            "0F" -> hex2(0x41, 0x0F, 34 + 40) // 34 C IAT
            "10" -> hex3(0x41, 0x10, (maf shr 8) and 0xFF, maf and 0xFF)
            "11" -> hex2(0x41, 0x11, (throttle * 255) / 100)
            "21" -> hex3(0x41, 0x21, 0x00, if (dtcsCleared) 0x00 else 0x2A)
            "2F" -> hex2(0x41, 0x2F, 180) // ~70% fuel
            "33" -> hex2(0x41, 0x33, 101) // 101 kPa baro
            "42" -> {
                val mv = 14120 + (sin(t * 0.3) * 140).toInt()
                hex3(0x41, 0x42, (mv shr 8) and 0xFF, mv and 0xFF)
            }
            "46" -> hex2(0x41, 0x46, 30 + 40) // 30 C ambient
            "5C" -> hex2(0x41, 0x5C, 96 + 40) // 96 C oil
            "62" -> hex2(0x41, 0x62, 125 + (throttle * 0.6).toInt())
            "63" -> hex3(0x41, 0x63, 0x01, 0x2C) // 300 Nm reference torque
            else -> "NO DATA\r"
        }
    }

    private fun handleFreezeFrame(cmd: String): String {
        val pid = cmd.substring(2, 4)
        return when (pid) {
            "02" -> "42 02 01 71\r" // P0171
            "04" -> "42 04 7A\r"    // 48% load
            "05" -> "42 05 81\r"    // 89 C
            "0C" -> "42 0C 26 48\r" // 2450 RPM
            "0D" -> "42 0D 42\r"    // 66 km/h
            "06" -> "42 06 97\r"    // +18% STFT
            else -> "NO DATA\r"
        }
    }

    private fun handleMode22(cmd: String): String {
        return when (cmd.take(6)) {
            "221E1C" -> "62 1E 1C 04 88\r" // Trans temp 76.0 C with ((A*256)+B)/10-40
            "221470" -> "62 14 70 88\r"    // Oil temp 96 C with A-40
            else -> "NO DATA\r"
        }
    }

    private fun hex2(a: Int, b: Int, c: Int) = "%02X %02X %02X\r".format(a, b, c)
    private fun hex3(a: Int, b: Int, c: Int, d: Int) = "%02X %02X %02X %02X\r".format(a, b, c, d)
}
