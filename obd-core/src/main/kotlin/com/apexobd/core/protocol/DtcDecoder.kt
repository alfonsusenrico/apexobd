package com.apexobd.core.protocol

import com.apexobd.core.elm.Elm327Client
import com.apexobd.core.elm.ObdCommandResult

enum class DtcCategory {
    CONFIRMED,
    PENDING,
    PERMANENT
}

data class DtcItem(
    val code: String,
    val category: DtcCategory,
    val description: String
)

data class ReadinessMonitor(
    val name: String,
    val ready: Boolean
)

data class ReadinessStatus(
    val milOn: Boolean,
    val dtcCount: Int,
    val compressionIgnition: Boolean,
    val monitors: List<ReadinessMonitor>
)

data class FreezeFrameSnapshot(
    val triggerDtc: String?,
    val readings: List<DecodedReading>
)

data class DiagnosticReport(
    val confirmed: List<DtcItem>,
    val pending: List<DtcItem>,
    val permanent: List<DtcItem>,
    val readiness: ReadinessStatus?,
    val freezeFrame: FreezeFrameSnapshot?,
    val ecuResponded: Boolean = true
)

object DtcDecoder {

    private val dtcDescriptions = mapOf(
        "P0100" to "Mass or Volume Air Flow Circuit",
        "P0101" to "Mass or Volume Air Flow Circuit Range/Performance",
        "P0106" to "Manifold Absolute Pressure/Barometric Pressure Range/Performance",
        "P0113" to "Intake Air Temperature Sensor 1 Circuit High",
        "P0115" to "Engine Coolant Temperature Circuit",
        "P0128" to "Coolant Thermostat Below Regulating Temperature",
        "P0130" to "O2 Sensor Circuit (Bank 1 Sensor 1)",
        "P0171" to "System Too Lean (Bank 1)",
        "P0172" to "System Too Rich (Bank 1)",
        "P0174" to "System Too Lean (Bank 2)",
        "P0299" to "Turbocharger/Supercharger Underboost",
        "P0300" to "Random/Multiple Cylinder Misfire Detected",
        "P0301" to "Cylinder 1 Misfire Detected",
        "P0302" to "Cylinder 2 Misfire Detected",
        "P0303" to "Cylinder 3 Misfire Detected",
        "P0304" to "Cylinder 4 Misfire Detected",
        "P0401" to "Exhaust Gas Recirculation Flow Insufficient",
        "P0420" to "Catalyst System Efficiency Below Threshold (Bank 1)",
        "P0430" to "Catalyst System Efficiency Below Threshold (Bank 2)",
        "P0442" to "Evaporative Emission System Leak Detected (Small Leak)",
        "P0455" to "Evaporative Emission System Leak Detected (Large Leak)",
        "P0505" to "Idle Air Control System",
        "P0562" to "System Voltage Low",
        "U0100" to "Lost Communication With ECM/PCM"
    )

    fun describe(code: String): String =
        dtcDescriptions[code.uppercase()] ?: "Manufacturer / SAE Diagnostic Trouble Code"

    fun decodeTwoByteDtc(high: Int, low: Int): String? {
        if (high == 0 && low == 0) return null
        val prefix = when ((high shr 6) and 0x03) {
            0 -> 'P'
            1 -> 'C'
            2 -> 'B'
            else -> 'U'
        }
        val d1 = (high shr 4) and 0x03
        val d2 = high and 0x0F
        val d3 = (low shr 4) and 0x0F
        val d4 = low and 0x0F
        return "%c%d%X%X%X".format(prefix, d1, d2, d3, d4)
    }

    /**
     * Parses responses from Mode 03 (0x43), Mode 07 (0x47), or Mode 0A (0x4A).
     * Handles single-frame CAN (`43 01 01 71`), legacy 2-byte pairs (`43 01 71`),
     * and ISO-TP multi-frame (`014` / `0: 43 06 01 71 01 74` / `1: 03 00 ...`) responses.
     */
    fun parseDtcResponse(lines: List<String>, expectedModeResp: Int, category: DtcCategory): List<DtcItem> {
        val codes = linkedSetOf<String>()
        val hasMultiFramePrefix = lines.any { it.contains(":") }
        val byteStreams: List<List<Int>> = if (hasMultiFramePrefix) {
            listOf(Elm327Client.flattenIsoTpBytes(lines))
        } else {
            lines.map { Elm327Client.parseHexBytes(it) }.filter { it.isNotEmpty() }
        }
        for (bytes in byteStreams) {
            val modeIdx = bytes.indexOf(expectedModeResp)
            if (modeIdx == -1 || modeIdx >= bytes.lastIndex) continue
            val afterMode = bytes.drop(modeIdx + 1)
            if (afterMode.isEmpty()) continue
            // In CAN (ISO 15765-4), byte 0 after mode is DTC count when remaining length is odd
            // or when multi-frame ISO-TP framing is used (`43 <count> <H1> <L1> ...`).
            val dtcBytes = if (afterMode.size % 2 == 1 || hasMultiFramePrefix) afterMode.drop(1) else afterMode
            for (i in 0 until dtcBytes.size - 1 step 2) {
                val code = decodeTwoByteDtc(dtcBytes[i], dtcBytes[i + 1]) ?: continue
                codes.add(code)
            }
        }
        return codes.map { DtcItem(code = it, category = category, description = describe(it)) }
    }

    fun parseReadiness(lines: List<String>): ReadinessStatus? {
        val payload = Elm327Client.extractPidPayload(lines, 0x41, 0x01)
            ?: extractToyotaMode2106ReadinessPayload(lines)
            ?: return null
        return parseReadinessPayload(payload)
    }

    private fun extractToyotaMode2106ReadinessPayload(lines: List<String>): List<Int>? {
        val flat = Elm327Client.flattenIsoTpBytes(lines)
        if (flat.size >= 6 && flat[0] == 0x61 && flat[1] == 0x06) {
            return flat.subList(2, 6)
        }
        return Elm327Client.extractPidPayload(lines, 0x61, 0x06)?.takeIf { it.size >= 4 }?.take(4)
    }

    fun parseReadinessPayload(payload: List<Int>): ReadinessStatus? {
        if (payload.size < 4) return null
        val a = payload[0]
        val b = payload[1]
        val c = payload[2]
        val d = payload[3]

        val milOn = (a and 0x80) != 0
        val dtcCount = a and 0x7F
        val compression = (b and 0x08) != 0

        val monitors = mutableListOf<ReadinessMonitor>()
        fun addIfSupported(name: String, supportedBit: Int, incompleteBit: Int) {
            if (supportedBit != 0) {
                monitors += ReadinessMonitor(name = name, ready = (incompleteBit == 0))
            }
        }
        // Continuous monitors in byte B (bits 0..2 supported, bits 4..6 incomplete)
        addIfSupported("Misfire", b and 0x01, b and 0x10)
        addIfSupported("Fuel System", b and 0x02, b and 0x20)
        addIfSupported("Components", b and 0x04, b and 0x40)

        // Non-continuous spark-ignition monitors in bytes C (supported) & D (incomplete)
        if (!compression) {
            addIfSupported("Catalyst", c and 0x01, d and 0x01)
            addIfSupported("Heated Catalyst", c and 0x02, d and 0x02)
            addIfSupported("EVAP System", c and 0x04, d and 0x04)
            addIfSupported("Secondary Air", c and 0x08, d and 0x08)
            addIfSupported("O2 Sensor", c and 0x20, d and 0x20)
            addIfSupported("O2 Heater", c and 0x40, d and 0x40)
            addIfSupported("EGR / VVT", c and 0x80, d and 0x80)
        } else {
            addIfSupported("NMHC Catalyst", c and 0x01, d and 0x01)
            addIfSupported("NOx / SCR", c and 0x02, d and 0x02)
            addIfSupported("Boost Pressure", c and 0x08, d and 0x08)
            addIfSupported("Exhaust Gas Sensor", c and 0x20, d and 0x20)
            addIfSupported("PM Filter", c and 0x40, d and 0x40)
            addIfSupported("EGR / VVT", c and 0x80, d and 0x80)
        }

        return ReadinessStatus(
            milOn = milOn,
            dtcCount = dtcCount,
            compressionIgnition = compression,
            monitors = monitors
        )
    }

    /**
     * Decodes Mode 09 PID 02 (VIN) multi-frame ISO-TP ASCII payload.
     */
    fun parseVin(lines: List<String>): String? {
        val allBytes = mutableListOf<Int>()
        for (line in lines) {
            val bytes = Elm327Client.parseHexBytes(line)
            if (bytes.isNotEmpty()) allBytes.addAll(bytes)
        }
        val markerIdx = (0 until allBytes.size - 2).firstOrNull {
            allBytes[it] == 0x49 && allBytes[it + 1] == 0x02
        } ?: return null
        // Skip 49 02 <messageCount=01>
        val asciiBytes = allBytes.drop(markerIdx + 3)
        val vin = asciiBytes
            .filter { it in 0x30..0x39 || it in 0x41..0x5A }
            .map { it.toChar() }
            .joinToString("")
            .take(17)
        return vin.takeIf { it.length >= 11 }
    }

    suspend fun scanAll(client: Elm327Client): DiagnosticReport {
        var anyResponded = false
        val confirmed = when (val r = client.execute("03")) {
            is ObdCommandResult.Success -> {
                anyResponded = true
                parseDtcResponse(r.lines, 0x43, DtcCategory.CONFIRMED)
            }
            else -> emptyList()
        }
        val pending = when (val r = client.execute("07")) {
            is ObdCommandResult.Success -> {
                anyResponded = true
                parseDtcResponse(r.lines, 0x47, DtcCategory.PENDING)
            }
            else -> emptyList()
        }
        val permanent = when (val r = client.execute("0A")) {
            is ObdCommandResult.Success -> {
                anyResponded = true
                parseDtcResponse(r.lines, 0x4A, DtcCategory.PERMANENT)
            }
            else -> emptyList()
        }
        var readiness = when (val r = client.execute("0101")) {
            is ObdCommandResult.Success -> {
                val parsed = parseReadiness(r.lines)
                if (parsed != null) anyResponded = true
                parsed
            }
            else -> null
        }
        // Fallback for Toyota/Daihatsu K3-VE ECUs that omit 0101 in Mode 01 but expose SAE 0101 bytes under Mode 2106
        if (readiness == null) {
            when (val r2106 = client.execute("2106", timeoutMs = 900L)) {
                is ObdCommandResult.Success -> {
                    val parsed = parseReadiness(r2106.lines)
                    if (parsed != null) {
                        anyResponded = true
                        readiness = parsed
                    }
                }
                else -> Unit
            }
        }
        if (!anyResponded) {
            val liveness = client.execute("0100", timeoutMs = 1000L)
            if (liveness is ObdCommandResult.Success &&
                Elm327Client.extractPidPayload(liveness.lines, 0x41, 0x00) != null
            ) {
                anyResponded = true
            }
        }

        val freezeFrame = readFreezeFrame(client)
        return DiagnosticReport(
            confirmed = confirmed,
            pending = pending,
            permanent = permanent,
            readiness = readiness,
            freezeFrame = freezeFrame,
            ecuResponded = anyResponded
        )
    }

    private suspend fun readFreezeFrame(client: Elm327Client): FreezeFrameSnapshot? {
        val trigRes = client.execute("0202")
        val triggerCode = if (trigRes is ObdCommandResult.Success) {
            val payload = Elm327Client.extractPidPayload(trigRes.lines, 0x42, 0x02)
            if (payload != null && payload.size >= 2) decodeTwoByteDtc(payload[0], payload[1]) else null
        } else null

        val ffPids = listOf("04", "05", "06", "0C", "0D")
        val readings = mutableListOf<DecodedReading>()
        for (pidHex in ffPids) {
            val r = client.execute("02$pidHex")
            if (r is ObdCommandResult.Success) {
                val def = ObdPidRegistry.findByPidHex(pidHex) ?: continue
                val payload = Elm327Client.extractPidPayload(r.lines, 0x42, pidHex.toInt(16)) ?: continue
                val value = def.decoder(payload) ?: continue
                readings += DecodedReading(def.key, def.pidHex, value, def.unit)
            }
        }
        if (triggerCode == null && readings.isEmpty()) return null
        return FreezeFrameSnapshot(triggerCode, readings)
    }

    suspend fun clearDtcs(client: Elm327Client): Boolean {
        val primary = client.execute("04", timeoutMs = 3500L)
        if (primary is ObdCommandResult.Success && isPositiveClearResponse(primary.lines)) {
            return true
        }
        // Some CAN ECUs require physical ECM addressing (7E0 -> 7E8) for Mode 04 clear
        return try {
            client.execute("ATSH7E0", timeoutMs = 800L)
            val fallback = client.execute("04", timeoutMs = 3500L)
            fallback is ObdCommandResult.Success && isPositiveClearResponse(fallback.lines)
        } finally {
            client.execute("ATSH7DF", timeoutMs = 800L)
        }
    }

    private fun isPositiveClearResponse(lines: List<String>): Boolean {
        for (line in lines) {
            val bytes = Elm327Client.parseHexBytes(line)
            if (bytes.isNotEmpty() && bytes[0] == 0x44) return true
            if (line.trim().equals("OK", ignoreCase = true)) return true
        }
        return false
    }

    suspend fun readVin(client: Elm327Client): String? {
        return when (val r = client.execute("0902", timeoutMs = 3500L)) {
            is ObdCommandResult.Success -> parseVin(r.lines)
            else -> null
        }
    }
}
