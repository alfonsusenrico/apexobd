package com.apexobd.core.elm

import com.apexobd.core.transport.ObdTransport
import com.apexobd.core.transport.TransportError
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
        runCatching { transport.sendCommand("0100", timeoutMs = 3000L) }

        val protoName = cleanLines(transport.sendCommand("ATDP", timeoutMs = 1500L), "ATDP")
            .firstOrNull() ?: "Auto"
        val protoCode = cleanLines(transport.sendCommand("ATDPN", timeoutMs = 1500L), "ATDPN")
            .firstOrNull()?.uppercase() ?: "0"

        val isCan = protoCode.lastOrNull() in listOf('6', '7', '8', '9', 'A', 'B', 'C') ||
            protoName.contains("CAN", ignoreCase = true) ||
            protoName.contains("15765", ignoreCase = true)

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
