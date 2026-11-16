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
    val protocolCode: String,
    val isCanProtocol: Boolean
)

/**
 * Thread-safe, low-latency ELM327 command processor.
 * Uses aggressive adaptive timing (`ATAT2`) and trimmed timeout (`ATST19` = 100ms)
 * while synchronizing every command via a [Mutex].
 */
class Elm327Client(
    private val transport: ObdTransport
) {
    private val commandMutex = Mutex()

    suspend fun initialize(aggressiveTiming: Boolean = true): ElmHandshakeInfo = commandMutex.withLock {
        val resetResp = transport.sendCommand("ATZ", timeoutMs = 2500L).trim()
        val version = resetResp.lines().lastOrNull { it.isNotBlank() }?.trim() ?: "ELM327"

        val initCommands = buildList {
            add("ATE0")   // Echo off
            add("ATL0")   // Linefeeds off
            add("ATS0")   // Spaces off (reduces UART/Wi-Fi bytes by ~33%)
            add(if (aggressiveTiming) "ATAT2" else "ATAT1")
            add("ATST19") // 25 * 4ms = 100ms max response timeout (cuts dead-wait latency)
            add("ATSP0")  // Auto-detect vehicle protocol
        }
        for (at in initCommands) {
            transport.sendCommand(at, timeoutMs = 1200L)
        }

        val protoName = cleanLines(transport.sendCommand("ATDP", timeoutMs = 1200L))
            .firstOrNull() ?: "Auto"
        val protoCode = cleanLines(transport.sendCommand("ATDPN", timeoutMs = 1200L))
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

    suspend fun execute(command: String, timeoutMs: Long = 1600L): ObdCommandResult = commandMutex.withLock {
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
         * Reassembles ISO-TP multi-frame or single-frame Mode 01/02 byte streams.
         * Strips frame length headers (like "00B") and sequence prefixes ("0:", "1:").
         */
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
