package com.apexobd.core.protocol

import com.apexobd.core.elm.Elm327Client
import com.apexobd.core.elm.ObdCommandResult
import com.apexobd.core.model.SensorQuantity

enum class PidCategory {
    ENGINE_AIR,
    TEMPERATURE,
    PRESSURE_BOOST,
    FUEL_TRIM,
    ELECTRICAL_STATUS,
    EMISSIONS_O2,
    TORQUE_TRANSMISSION
}

enum class PollTier {
    HIGH,
    MEDIUM
}

data class PidDefinition(
    val pidHex: String,
    val key: String,
    val name: String,
    val shortName: String,
    val unit: String,
    val minValue: Double,
    val maxValue: Double,
    val category: PidCategory,
    val defaultTier: PollTier = PollTier.MEDIUM,
    val quantity: SensorQuantity = defaultQuantityForUnit(unit),
    val decoder: (List<Int>) -> Double?
) {
    companion object {
        fun defaultQuantityForUnit(unit: String): SensorQuantity = when (unit) {
            "km/h" -> SensorQuantity.SPEED
            "km" -> SensorQuantity.DISTANCE_KM
            "°C" -> SensorQuantity.TEMPERATURE
            "kPa" -> SensorQuantity.PRESSURE_KPA
            "g/s" -> SensorQuantity.AIRFLOW_GS
            "L/h" -> SensorQuantity.FUEL_FLOW_LPH
            "Nm" -> SensorQuantity.TORQUE_NM
            else -> SensorQuantity.RAW
        }
    }
}

data class DecodedReading(
    val key: String,
    val pidHex: String,
    val value: Double,
    val unit: String,
    val timestampMs: Long = System.currentTimeMillis()
)

object ObdPidRegistry {

    private val supportPidBlocks = listOf("00", "20", "40", "60", "80", "A0", "C0")

    val standardPids: List<PidDefinition> = listOf(
        // Engine & Air
        pid("04", "engine_load", "Calculated Engine Load", "Load", "%", 0.0, 100.0, PidCategory.ENGINE_AIR, PollTier.HIGH) { b ->
            b.getOrNull(0)?.let { it * 100.0 / 255.0 }
        },
        pid("0C", "rpm", "Engine RPM", "RPM", "RPM", 0.0, 8000.0, PidCategory.ENGINE_AIR, PollTier.HIGH) { b ->
            if (b.size >= 2) ((b[0] * 256) + b[1]) / 4.0 else null
        },
        pid("0D", "obd_speed", "Vehicle Speed (OBD)", "OBD Spd", "km/h", 0.0, 220.0, PidCategory.ENGINE_AIR, PollTier.HIGH) { b ->
            b.getOrNull(0)?.toDouble()
        },
        pid("0E", "timing_advance", "Timing Advance", "Timing", "°", -64.0, 63.5, PidCategory.ENGINE_AIR, PollTier.MEDIUM) { b ->
            b.getOrNull(0)?.let { (it / 2.0) - 64.0 }
        },
        pid("10", "maf", "Intake Air Flow (MAF)", "MAF", "g/s", 0.0, 655.35, PidCategory.ENGINE_AIR, PollTier.HIGH) { b ->
            if (b.size >= 2) ((b[0] * 256) + b[1]) / 100.0 else null
        },
        pid("11", "throttle", "Throttle Position (Abs)", "Abs Thr", "%", 0.0, 100.0, PidCategory.ENGINE_AIR, PollTier.HIGH) { b ->
            b.getOrNull(0)?.let { it * 100.0 / 255.0 }
        },
        pid("1F", "run_time", "Run Time Since Start", "Run Time", "s", 0.0, 65535.0, PidCategory.ELECTRICAL_STATUS) { b ->
            if (b.size >= 2) ((b[0] * 256) + b[1]).toDouble() else null
        },
        pid("43", "abs_load", "Absolute Load Value", "Abs Load", "%", 0.0, 25700.0, PidCategory.ENGINE_AIR) { b ->
            if (b.size >= 2) ((b[0] * 256) + b[1]) * 100.0 / 255.0 else null
        },
        pid("45", "rel_throttle", "Throttle Position (Relative)", "Rel Thr", "%", 0.0, 100.0, PidCategory.ENGINE_AIR, PollTier.HIGH) { b ->
            b.getOrNull(0)?.let { it * 100.0 / 255.0 }
        },
        pid("49", "pedal_d", "Accelerator Pedal D", "Pedal D", "%", 0.0, 100.0, PidCategory.ENGINE_AIR, PollTier.HIGH) { b ->
            b.getOrNull(0)?.let { it * 100.0 / 255.0 }
        },
        pid("4A", "pedal_e", "Accelerator Pedal E", "Pedal E", "%", 0.0, 100.0, PidCategory.ENGINE_AIR, PollTier.HIGH) { b ->
            b.getOrNull(0)?.let { it * 100.0 / 255.0 }
        },
        pid("4C", "commanded_throttle", "Commanded Throttle Actuator", "Cmd Thr", "%", 0.0, 100.0, PidCategory.ENGINE_AIR, PollTier.HIGH) { b ->
            b.getOrNull(0)?.let { it * 100.0 / 255.0 }
        },

        // Temperatures
        pid("05", "coolant", "Engine Coolant Temp", "Coolant", "°C", -40.0, 150.0, PidCategory.TEMPERATURE) { b ->
            b.getOrNull(0)?.let { it - 40.0 }
        },
        pid("0F", "iat", "Intake Air Temp (IAT)", "IAT", "°C", -40.0, 120.0, PidCategory.TEMPERATURE) { b ->
            b.getOrNull(0)?.let { it - 40.0 }
        },
        pid("3C", "cat_temp_b1s1", "Catalyst Temp B1S1", "Cat B1S1", "°C", -40.0, 1000.0, PidCategory.EMISSIONS_O2) { b ->
            if (b.size >= 2) (((b[0] * 256) + b[1]) / 10.0) - 40.0 else null
        },
        pid("3E", "cat_temp_b1s2", "Catalyst Temp B1S2", "Cat B1S2", "°C", -40.0, 1000.0, PidCategory.EMISSIONS_O2) { b ->
            if (b.size >= 2) (((b[0] * 256) + b[1]) / 10.0) - 40.0 else null
        },
        pid("46", "ambient_temp", "Ambient Air Temp", "Ambient", "°C", -40.0, 80.0, PidCategory.TEMPERATURE) { b ->
            b.getOrNull(0)?.let { it - 40.0 }
        },
        pid("5C", "oil_temp", "Engine Oil Temp", "Oil Temp", "°C", -40.0, 160.0, PidCategory.TEMPERATURE) { b ->
            b.getOrNull(0)?.let { it - 40.0 }
        },

        // Pressures & Boost
        pid("0A", "fuel_pressure", "Fuel Pressure (Gauge)", "Fuel P", "kPa", 0.0, 765.0, PidCategory.PRESSURE_BOOST) { b ->
            b.getOrNull(0)?.let { it * 3.0 }
        },
        pid("0B", "map", "Intake Manifold Pressure", "MAP", "kPa", 0.0, 255.0, PidCategory.PRESSURE_BOOST, PollTier.HIGH) { b ->
            b.getOrNull(0)?.toDouble()
        },
        pid("22", "fuel_rail_rel", "Fuel Rail Pressure (Rel)", "Rail Rel", "kPa", 0.0, 5177.27, PidCategory.PRESSURE_BOOST) { b ->
            if (b.size >= 2) ((b[0] * 256) + b[1]) * 0.079 else null
        },
        pid("23", "fuel_rail_gauge", "Fuel Rail Pressure (GDI)", "Rail GDI", "kPa", 0.0, 655350.0, PidCategory.PRESSURE_BOOST) { b ->
            if (b.size >= 2) ((b[0] * 256) + b[1]) * 10.0 else null
        },
        pid("33", "baro", "Barometric Pressure", "Baro", "kPa", 0.0, 255.0, PidCategory.PRESSURE_BOOST) { b ->
            b.getOrNull(0)?.toDouble()
        },
        pid("59", "fuel_rail_abs", "Fuel Rail Pressure (Abs)", "Rail Abs", "kPa", 0.0, 655350.0, PidCategory.PRESSURE_BOOST) { b ->
            if (b.size >= 2) ((b[0] * 256) + b[1]) * 10.0 else null
        },

        // Fuel Trims & Consumption
        pid("03", "fuel_system_status", "Fuel System Status", "Fuel Sys", "", 0.0, 31.0, PidCategory.FUEL_TRIM, PollTier.MEDIUM) { b ->
            b.getOrNull(0)?.toDouble()
        },
        pid("06", "stft_b1", "Short Term Fuel Trim B1", "STFT B1", "%", -100.0, 99.2, PidCategory.FUEL_TRIM) { b ->
            b.getOrNull(0)?.let { (it - 128.0) * 100.0 / 128.0 }
        },
        pid("07", "ltft_b1", "Long Term Fuel Trim B1", "LTFT B1", "%", -100.0, 99.2, PidCategory.FUEL_TRIM) { b ->
            b.getOrNull(0)?.let { (it - 128.0) * 100.0 / 128.0 }
        },
        pid("08", "stft_b2", "Short Term Fuel Trim B2", "STFT B2", "%", -100.0, 99.2, PidCategory.FUEL_TRIM) { b ->
            b.getOrNull(0)?.let { (it - 128.0) * 100.0 / 128.0 }
        },
        pid("09", "ltft_b2", "Long Term Fuel Trim B2", "LTFT B2", "%", -100.0, 99.2, PidCategory.FUEL_TRIM) { b ->
            b.getOrNull(0)?.let { (it - 128.0) * 100.0 / 128.0 }
        },
        pid("2F", "fuel_level", "Fuel Tank Level", "Fuel %", "%", 0.0, 100.0, PidCategory.FUEL_TRIM) { b ->
            b.getOrNull(0)?.let { it * 100.0 / 255.0 }
        },
        pid("44", "lambda", "Commanded Equivalence Ratio", "Lambda", "λ", 0.0, 2.0, PidCategory.FUEL_TRIM) { b ->
            if (b.size >= 2) ((b[0] * 256) + b[1]) * 2.0 / 65536.0 else null
        },
        pid("5E", "fuel_rate", "Engine Fuel Rate", "Fuel Rate", "L/h", 0.0, 3276.75, PidCategory.FUEL_TRIM) { b ->
            if (b.size >= 2) ((b[0] * 256) + b[1]) / 20.0 else null
        },

        // Electrical & Status
        pid("21", "dist_mil", "Distance Traveled With MIL", "MIL Dist", "km", 0.0, 65535.0, PidCategory.ELECTRICAL_STATUS) { b ->
            if (b.size >= 2) ((b[0] * 256) + b[1]).toDouble() else null
        },
        pid("30", "warmups_since_clear", "Warm-ups Since Clear", "Warmups", "", 0.0, 255.0, PidCategory.ELECTRICAL_STATUS) { b ->
            b.getOrNull(0)?.toDouble()
        },
        pid("31", "dist_since_clear", "Distance Since Codes Cleared", "Clr Dist", "km", 0.0, 65535.0, PidCategory.ELECTRICAL_STATUS) { b ->
            if (b.size >= 2) ((b[0] * 256) + b[1]).toDouble() else null
        },
        pid("42", "voltage", "Control Module Voltage", "Voltage", "V", 0.0, 20.0, PidCategory.ELECTRICAL_STATUS) { b ->
            if (b.size >= 2) ((b[0] * 256) + b[1]) / 1000.0 else null
        },
        pid("4D", "time_mil_on", "Time Run With MIL On", "MIL Time", "min", 0.0, 65535.0, PidCategory.ELECTRICAL_STATUS) { b ->
            if (b.size >= 2) ((b[0] * 256) + b[1]).toDouble() else null
        },
        pid("4E", "time_since_clear", "Time Since Codes Cleared", "Clr Time", "min", 0.0, 65535.0, PidCategory.ELECTRICAL_STATUS) { b ->
            if (b.size >= 2) ((b[0] * 256) + b[1]).toDouble() else null
        },

        // O2 & Emissions
        pid("14", "o2_b1s1_v", "O2 Sensor 1 Voltage", "O2 B1S1", "V", 0.0, 1.275, PidCategory.EMISSIONS_O2) { b ->
            b.getOrNull(0)?.let { it / 200.0 }
        },
        pid("14", "o2_b1s1_stft", "O2 Sensor 1 Fuel Trim", "O2 Trim", "%", -100.0, 99.2, PidCategory.EMISSIONS_O2) { b ->
            b.getOrNull(1)?.takeIf { it != 0xFF }?.let { (it - 128.0) * 100.0 / 128.0 }
        },
        pid("15", "o2_b1s2_v", "O2 Sensor 2 Voltage", "O2 B1S2", "V", 0.0, 1.275, PidCategory.EMISSIONS_O2) { b ->
            b.getOrNull(0)?.let { it / 200.0 }
        },
        pid("24", "o2_b1s1_wr_lambda", "O2 Sensor 1 WR Ratio", "O2 S1 λ", "λ", 0.0, 2.0, PidCategory.EMISSIONS_O2, PollTier.MEDIUM) { b ->
            if (b.size >= 2) ((b[0] * 256) + b[1]) * 2.0 / 65536.0 else null
        },
        pid("34", "o2_b1s1_lambda", "O2 Sensor 1 WR Lambda", "O2 λ", "λ", 0.0, 2.0, PidCategory.EMISSIONS_O2, PollTier.MEDIUM) { b ->
            if (b.size >= 2) ((b[0] * 256) + b[1]) * 2.0 / 65536.0 else null
        },
        pid("2C", "commanded_egr", "Commanded EGR", "EGR", "%", 0.0, 100.0, PidCategory.EMISSIONS_O2) { b ->
            b.getOrNull(0)?.let { it * 100.0 / 255.0 }
        },
        pid("2E", "evap_purge", "Commanded Evap Purge", "EVAP", "%", 0.0, 100.0, PidCategory.EMISSIONS_O2) { b ->
            b.getOrNull(0)?.let { it * 100.0 / 255.0 }
        },

        // Torque & Transmission
        pid("62", "actual_torque_pct", "Actual Engine % Torque", "Torque %", "%", -125.0, 130.0, PidCategory.TORQUE_TRANSMISSION, PollTier.HIGH) { b ->
            b.getOrNull(0)?.let { it - 125.0 }
        },
        pid("63", "ref_torque_nm", "Engine Reference Torque", "Ref Tq", "Nm", 0.0, 65535.0, PidCategory.TORQUE_TRANSMISSION) { b ->
            if (b.size >= 2) ((b[0] * 256) + b[1]).toDouble() else null
        },
        pid("A4", "actual_gear", "Transmission Actual Gear", "Gear", "", 0.0, 12.0, PidCategory.TORQUE_TRANSMISSION, PollTier.HIGH) { b ->
            if (b.size >= 4) (((b[2] * 256) + b[3]) / 1000.0) else null
        }
    )

    private val byKey: Map<String, PidDefinition> = standardPids.associateBy { it.key }
    private val byPidHexGroup: Map<String, List<PidDefinition>> = standardPids.groupBy { it.pidHex }

    private val twoBytePids = setOf("03", "0C", "10", "1F", "21", "22", "23", "31", "3C", "3E", "42", "43", "44", "4D", "4E", "59", "5E", "63")
    private val fourBytePids = setOf("24", "34", "A4")

    fun dataByteLength(pidHex: String): Int = when (pidHex.uppercase()) {
        in fourBytePids -> 4
        in twoBytePids -> 2
        else -> 1
    }

    /**
     * Groups CAN Mode 01 PIDs so every response (`0x41` + `[pidHex + data]` entries) fits
     * within a single 7-byte ISO 15765-4 CAN frame, avoiding slow ISO-TP flow-control handshakes.
     */
    fun packSingleFrameCanChunks(pids: List<PidDefinition>): List<List<PidDefinition>> {
        if (pids.isEmpty()) return emptyList()
        val out = mutableListOf<List<PidDefinition>>()
        var cur = mutableListOf<PidDefinition>()
        var payloadBytes = 1 // 0x41 service byte
        for (pid in pids) {
            val itemBytes = 1 + dataByteLength(pid.pidHex)
            if (cur.isNotEmpty() && payloadBytes + itemBytes > 7) {
                out += cur
                cur = mutableListOf(pid)
                payloadBytes = 1 + itemBytes
            } else {
                cur += pid
                payloadBytes += itemBytes
            }
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    fun findByKey(key: String): PidDefinition? = byKey[key]
    fun findByPidHex(pidHex: String): PidDefinition? = byPidHexGroup[pidHex.uppercase()]?.firstOrNull()

    /**
     * Queries `0100`, `0120`, `0140`, `0160`, `0180`, `01A0` sequentially, stopping when
     * the next block bit (bit 0 of the 4th byte) is 0.
     *
     * Unions bitmasks from ALL responding ECUs per block (so a secondary module like TCM `7E9`
     * answering before ECM `7E8` never hides engine PIDs) and retries any advertised block up to
     * 3 times before giving up so transient CAN/Wi-Fi hiccups never drop blocks like `0140`.
     */
    suspend fun discoverSupportedPids(client: Elm327Client, isCanProtocol: Boolean = false): Set<String> {
        val supported = linkedSetOf<String>()
        for (blockHex in supportPidBlocks) {
            val blockInt = blockHex.toInt(16)
            var blockSupported: Set<String> = emptySet()

            for (attempt in 0 until 3) {
                // On attempt 2 over CAN, also try single-frame hint `"01XX1"` in case functional multi-ECU timed out
                val cmd = if (attempt == 2 && isCanProtocol) "01${blockHex}1" else "01$blockHex"
                val res = client.execute(cmd, timeoutMs = 2000L)
                if (res is ObdCommandResult.Success) {
                    val payloads = Elm327Client.extractAllPidPayloads(res.lines, 0x41, blockInt)
                        .filter { it.size >= 4 }
                    if (payloads.isNotEmpty()) {
                        val union = linkedSetOf<String>()
                        for (p in payloads) {
                            union.addAll(decodeBitmask(blockInt, p.take(4)))
                        }
                        blockSupported = union
                        break
                    }
                }
                if (attempt < 2) {
                    kotlinx.coroutines.delay(80L)
                }
            }

            if (blockSupported.isEmpty()) break
            supported.addAll(blockSupported)

            val nextBlockCode = "%02X".format(blockInt + 0x20)
            if (!blockSupported.contains(nextBlockCode)) break
        }
        return supported
    }

    /**
     * Decodes a 4-byte SAE J1979 bitmask response starting at [basePid] (0x00, 0x20, ...).
     * MSB of byte 0 corresponds to PID base+1; LSB of byte 3 corresponds to PID base+32.
     */
    fun decodeBitmask(basePid: Int, fourBytes: List<Int>): Set<String> {
        if (fourBytes.size < 4) return emptySet()
        val out = linkedSetOf<String>()
        for (byteIdx in 0 until 4) {
            val byteVal = fourBytes[byteIdx]
            for (bit in 7 downTo 0) {
                if (((byteVal shr bit) and 1) == 1) {
                    val pidNum = basePid + (byteIdx * 8) + (7 - bit) + 1
                    out.add("%02X".format(pidNum))
                }
            }
        }
        return out
    }

    fun decodeMode01Response(pidHex: String, lines: List<String>): DecodedReading? =
        decodeAllMode01Responses(pidHex, lines).firstOrNull()

    fun decodeAllMode01Responses(pidHex: String, lines: List<String>): List<DecodedReading> {
        val defs = byPidHexGroup[pidHex.uppercase()] ?: return emptyList()
        val pidInt = pidHex.toIntOrNull(16) ?: return emptyList()
        val payload = Elm327Client.extractPidPayload(lines, 0x41, pidInt) ?: return emptyList()
        return defs.mapNotNull { def ->
            def.decoder(payload)?.let { DecodedReading(def.key, def.pidHex, it, def.unit) }
        }
    }

    /**
     * Decodes a concatenated CAN multi-PID Mode 01 response (e.g. `41 0C 1A F8 0D 54 11 61`)
     * by walking `[pidByte, payloadBytes...]` sequentially according to SAE J1979 lengths.
     */
    fun decodeMultiMode01Response(lines: List<String>): List<DecodedReading> {
        val bytes = Elm327Client.flattenIsoTpBytes(lines)
        val modeIdx = bytes.indexOf(0x41)
        if (modeIdx == -1 || modeIdx >= bytes.lastIndex) return emptyList()

        val out = mutableListOf<DecodedReading>()
        var i = modeIdx + 1
        while (i < bytes.size) {
            val pidHex = "%02X".format(bytes[i])
            i++
            val len = dataByteLength(pidHex)
            if (i + len > bytes.size) break
            val slice = bytes.subList(i, i + len)
            i += len
            val defs = byPidHexGroup[pidHex] ?: continue
            for (def in defs) {
                val value = def.decoder(slice) ?: continue
                out += DecodedReading(def.key, def.pidHex, value, def.unit)
            }
        }
        return out
    }

    private fun pid(
        pidHex: String,
        key: String,
        name: String,
        shortName: String,
        unit: String,
        min: Double,
        max: Double,
        cat: PidCategory,
        tier: PollTier = PollTier.MEDIUM,
        decoder: (List<Int>) -> Double?
    ) = PidDefinition(pidHex, key, name, shortName, unit, min, max, cat, tier, PidDefinition.defaultQuantityForUnit(unit), decoder)
}
