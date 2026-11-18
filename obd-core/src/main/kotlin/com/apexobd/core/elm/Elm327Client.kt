package com.apexobd.core.elm

import com.apexobd.core.transport.ObdTransport
import com.apexobd.core.transport.TransportError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed class ObdCommandResult {
    data class Success(val raw: String, val lines: List<String>) : ObdCommandResult()
    data class NoData(val reason: String) : ObdCommandResult()
    data class Error(val message: String) : ObdCommandResult()
}

data class ElmHandshakeInfo(
    val adapterVersion: String,
    val protocolName: String,
    val protocolCode: String,
    val isCanProtocol: Boolean
)

/**
 * Thread-safe ELM327 command processor.
 * Uses reliable adaptive timing (`ATAT1`) compatible with ELM327 v1.5 Wi-Fi adapters
 * while synchronizing every command via a [Mutex].
 */
class Elm327Client(
    private val transport: ObdTransport
) {
    private val commandMutex = Mutex()

    suspend fun initialize(aggressiveTiming: Boolean = false): ElmHandshakeInfo = commandMutex.withLock {
        val resetResp = transport.sendCommand("ATZ", timeoutMs = 3500L).trim()
        val version = resetResp.lines().lastOrNull { it.isNotBlank() }?.trim() ?: "ELM327"
        // Give ELM327 v1.5 Wi-Fi UART bridges a brief settle window after reset
        delay(300L)

        val initCommands = listOf(
            "ATE0",  // Echo off
            "ATL0",  // Linefeeds off
            "ATS0",  // Spaces off (cuts Wi-Fi/UART payload bytes)
            if (aggressiveTiming) "ATAT2" else "ATAT1",
            "ATSP0"  // Auto-detect vehicle protocol
        )
        for (at in initCommands) {
            transport.sendCommand(at, timeoutMs = 2000L)
        }

        // Trigger auto-protocol search on the vehicle bus before reading ATDP/ATDPN,
        // because ELM327 reports "AUTO" (A0) until the first OBD frame is exchanged.
        try {
            transport.sendCommand("0100", timeoutMs = 3000L)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
        }

        var protoName = cleanLines(transport.sendCommand("ATDP", timeoutMs = 1500L), "ATDP")
            .joinToString(" ").trim().ifBlank { "Auto" }
        val protoCode = cleanLines(transport.sendCommand("ATDPN", timeoutMs = 1500L), "ATDPN")
            .joinToString(" ").trim().uppercase().ifBlank { "0" }

        var isCan = detectCanProtocol(protoName, protoCode)
        if (!isCan && protoName.equals("Auto", ignoreCase = true) && protoCode.contains("AUTO")) {
            delay(50L)
            val retryName = cleanLines(transport.sendCommand("ATDP", timeoutMs = 1500L), "ATDP")
                .joinToString(" ").trim()
            if (retryName.isNotBlank()) protoName = retryName
            isCan = detectCanProtocol(protoName, protoCode)
        }

        // Apply tight 100ms ATST timeout only on CAN buses; keep standard P2 timeout on legacy K-line/KWP2000
        if (aggressiveTiming && isCan) {
            runCatching { transport.sendCommand("ATST19", timeoutMs = 1500L) }
        }

        ElmHandshakeInfo(
            adapterVersion = version,
            protocolName = protoName,
            protocolCode = protoCode,
            isCanProtocol = isCan
        )
    }

    suspend fun execute(command: String, timeoutMs: Long = 2000L): ObdCommandResult = commandMutex.withLock {
        val raw = try {
            transport.sendCommand(command, timeoutMs)
        } catch (e: CancellationException) {
            throw e
        } catch (t: TransportError) {
            return@withLock ObdCommandResult.Error(t.message ?: "Adapter not connected")
        } catch (t: Throwable) {
            return@withLock ObdCommandResult.Error(t.message ?: "OBD I/O error")
        }
        val lines = cleanLines(raw, command)
        if (lines.isEmpty()) {
            return@withLock ObdCommandResult.NoData("Empty response")
        }
        val joined = lines.joinToString(" ").uppercase()
        return@withLock when {
            joined.contains("NO DATA") -> ObdCommandResult.NoData("NO DATA")
            joined.contains("STOPPED") -> ObdCommandResult.Error("STOPPED")
            joined.contains("UNABLE TO CONNECT") -> ObdCommandResult.Error("UNABLE TO CONNECT")
            joined.contains("BUS INIT") && joined.contains("ERROR") -> ObdCommandResult.Error(joined)
            joined == "?" -> ObdCommandResult.Error("Adapter rejected command '$command'")
            else -> ObdCommandResult.Success(raw = raw, lines = lines)
        }
    }

    companion object {
        private val CAN_PROTOCOL_TOKEN = Regex("""(?:^|[\s,])A?[6-9A-C](?=[\s,]|$)""")

        fun detectCanProtocol(protoName: String, protoCode: String): Boolean {
            val combined = "$protoName $protoCode"
            return CAN_PROTOCOL_TOKEN.containsMatchIn(protoCode.uppercase()) ||
                combined.contains("CAN", ignoreCase = true) ||
                combined.contains("15765", ignoreCase = true)
        }

        fun cleanLines(raw: String, sentCommand: String? = null): List<String> {
            val trimmedCmd = sentCommand?.trim()
            return raw.replace("\r", "\n")
                .lines()
                .map { it.trim() }
                .filter { line ->
                    line.isNotBlank() &&
                        !line.equals("SEARCHING...", ignoreCase = true) &&
                        (trimmedCmd == null || !line.equals(trimmedCmd, ignoreCase = true))
                }
        }

        fun parseHexBytes(line: String): List<Int> {
            val cleaned = line.substringAfter(":").replace(" ", "").trim()
            if (cleaned.length < 2 || cleaned.length % 2 != 0) return emptyList()
            val out = ArrayList<Int>(cleaned.length / 2)
            for (i in cleaned.indices step 2) {
                val b = cleaned.substring(i, i + 2).toIntOrNull(16) ?: return emptyList()
                out.add(b)
            }
            return out
        }

        fun flattenIsoTpBytes(lines: List<String>): List<Int> {
            val hasMultiFramePrefix = lines.any { it.contains(":") }
            if (!hasMultiFramePrefix) {
                for (line in lines) {
                    val bytes = parseHexBytes(line)
                    if (bytes.isNotEmpty()) return bytes
                }
                return emptyList()
            }
            val merged = mutableListOf<Int>()
            for (line in lines) {
                if (!line.contains(":")) continue
                merged.addAll(parseHexBytes(line))
            }
            return merged
        }

        fun extractPidPayload(lines: List<String>, expectedModeResp: Int, expectedPid: Int): List<Int>? {
            for (line in lines) {
                val bytes = parseHexBytes(line)
                if (bytes.size >= 3 && bytes[0] == expectedModeResp && bytes[1] == expectedPid) {
                    return bytes.drop(2)
                }
            }
            return null
        }
    }
}
