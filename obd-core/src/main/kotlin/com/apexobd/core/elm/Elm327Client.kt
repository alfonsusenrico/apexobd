package com.apexobd.core.elm

import com.apexobd.core.transport.ObdTransport
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
    val protocolCode: String
)

/**
 * Thread-safe ELM327 command processor.
 * Synchronizes every AT/OBD exchange via a [Mutex] so polling never collides
 * with on-demand DTC scans or clearances.
 */
class Elm327Client(
    private val transport: ObdTransport
) {
    private val commandMutex = Mutex()

    suspend fun initialize(): ElmHandshakeInfo = commandMutex.withLock {
        val resetResp = transport.sendCommand("ATZ", timeoutMs = 3000L).trim()
        val version = resetResp.lines().lastOrNull { it.isNotBlank() }?.trim() ?: "ELM327"

        val initCommands = listOf(
            "ATE0",  // Echo off
            "ATL0",  // Linefeeds off
            "ATS0",  // Spaces off (smaller frames, still accepts spaces in response parser)
            "ATAT1", // Adaptive timing auto-1
            "ATSP0"  // Auto protocol
        )
        for (at in initCommands) {
            transport.sendCommand(at, timeoutMs = 1500L)
        }

        val protoName = cleanLines(transport.sendCommand("ATDP", timeoutMs = 1500L))
            .firstOrNull() ?: "Auto"
        val protoCode = cleanLines(transport.sendCommand("ATDPN", timeoutMs = 1500L))
            .firstOrNull() ?: "0"

        ElmHandshakeInfo(
            adapterVersion = version,
            protocolName = protoName,
            protocolCode = protoCode
        )
    }

    suspend fun execute(command: String, timeoutMs: Long = 2200L): ObdCommandResult = commandMutex.withLock {
        val raw = transport.sendCommand(command, timeoutMs)
        val lines = cleanLines(raw)
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
        fun cleanLines(raw: String): List<String> {
            return raw.replace("\r", "\n")
                .lines()
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.equals("SEARCHING...", ignoreCase = true) }
        }

        /**
         * Parses an OBD hex response line (with or without spaces, e.g. "410C1AF8" or "41 0C 1A F8")
         * into integer bytes. Strips ISO-TP multi-frame prefixes like "0:" when present.
         */
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

        /**
         * Extracts the Mode 01/02 data payload bytes after matching the response prefix (e.g. 0x41, pid).
         */
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
