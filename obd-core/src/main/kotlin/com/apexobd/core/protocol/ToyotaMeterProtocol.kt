package com.apexobd.core.protocol

import com.apexobd.core.elm.Elm327Client
import com.apexobd.core.elm.ObdCommandResult

/**
 * Capabilities discovered on a Toyota/Daihatsu Combination Meter (Instrument Cluster ECU `7C0`/`7C8`)
 * via strictly read-only Mode 21 probes.
 */
data class ToyotaMeterCapabilities(
    val fuelLevel: Boolean = false,
    val odometer: Boolean = false,
    val clusterSpeed: Boolean = false,
    val clusterRpm: Boolean = false,
    val clusterCoolant: Boolean = false
) {
    val hasAny: Boolean
        get() = fuelLevel || odometer || clusterSpeed || clusterRpm || clusterCoolant

    fun capabilityTokens(): Set<String> = buildSet {
        if (fuelLevel) add(TOKEN_FUEL_LEVEL)
        if (odometer) add(TOKEN_ODOMETER)
        if (clusterSpeed) add(TOKEN_CLUSTER_SPEED)
        if (clusterRpm) add(TOKEN_CLUSTER_RPM)
        if (clusterCoolant) add(TOKEN_CLUSTER_COOLANT)
    }

    companion object {
        const val HEADER_TX = "7C0"
        const val HEADER_RX = "7C8"

        const val TOKEN_FUEL_LEVEL = "7C0_2129"
        const val TOKEN_ODOMETER = "7C0_21E1"
        const val TOKEN_CLUSTER_SPEED = "7C0_2121"
        const val TOKEN_CLUSTER_RPM = "7C0_2122"
        const val TOKEN_CLUSTER_COOLANT = "7C0_2123"
    }
}

data class ToyotaMeterDiscoveryResult(
    val capabilities: ToyotaMeterCapabilities,
    val initialFuelLiters: Double? = null,
    val initialOdometerKm: Long? = null,
    val initialClusterSpeedKmh: Double? = null,
    val initialClusterRpm: Double? = null,
    val initialClusterCoolantC: Double? = null
)

/**
 * Read-only decoder and probe helper for Toyota/Daihatsu Combination Meter (`7C0` -> `7C8`) Mode 21 PIDs:
 * - `2129` (`61 29 <A>`): Digital Fuel Tank Level (`A * 0.5` L; verified `0x25` = `18.5 L` on 2024 Avanza W100)
 * - `21E1` (`61 E1 ...`): Cluster Odometer (`data[7..10]` 32-bit BE km; verified `00 00 77 0D` = `30,477 km`)
 * - `2121` (`61 21 <A>`): Cluster Speed (`A` km/h)
 * - `2122` (`61 22 <A>`): Cluster RPM (`A * 50` RPM)
 * - `2123` (`61 23 <A>`): Cluster Coolant Temp (`A * 0.5` °C)
 */
object ToyotaMeterProtocol {

    suspend fun discover(client: Elm327Client): ToyotaMeterDiscoveryResult {
        var fuelLiters: Double? = null
        var odometerKm: Long? = null
        var clusterSpeed: Double? = null
        var clusterRpm: Double? = null
        var clusterCoolant: Double? = null

        try {
            val sh = client.execute("ATSH${ToyotaMeterCapabilities.HEADER_TX}", timeoutMs = 800L)
            if (sh !is ObdCommandResult.Success) {
                return ToyotaMeterDiscoveryResult(ToyotaMeterCapabilities())
            }
            client.execute("ATCRA${ToyotaMeterCapabilities.HEADER_RX}", timeoutMs = 800L)

            val fuelRes = client.execute("2129", timeoutMs = 700L)
            if (fuelRes is ObdCommandResult.Success) {
                fuelLiters = decodeFuelLiters(fuelRes.lines)
            }

            // If 2129 did not answer on 7C0, check 21E1 once before skipping further 7C0 probes on non-Toyota cars
            val odoRes = client.execute("21E1", timeoutMs = 850L)
            if (odoRes is ObdCommandResult.Success) {
                odometerKm = decodeOdometerKm(odoRes.lines)
            }

            if (fuelLiters != null || odometerKm != null) {
                val spdRes = client.execute("2121", timeoutMs = 650L)
                if (spdRes is ObdCommandResult.Success) {
                    clusterSpeed = decodeClusterSpeedKmh(spdRes.lines)
                }
                val rpmRes = client.execute("2122", timeoutMs = 650L)
                if (rpmRes is ObdCommandResult.Success) {
                    clusterRpm = decodeClusterRpm(rpmRes.lines)
                }
                val coolRes = client.execute("2123", timeoutMs = 650L)
                if (coolRes is ObdCommandResult.Success) {
                    clusterCoolant = decodeClusterCoolantC(coolRes.lines)
                }
            }
        } finally {
            client.execute("ATCRA", timeoutMs = 800L)
            client.execute("ATSH7DF", timeoutMs = 800L)
        }

        val caps = ToyotaMeterCapabilities(
            fuelLevel = fuelLiters != null,
            odometer = odometerKm != null,
            clusterSpeed = clusterSpeed != null,
            clusterRpm = clusterRpm != null,
            clusterCoolant = clusterCoolant != null
        )
        return ToyotaMeterDiscoveryResult(
            capabilities = caps,
            initialFuelLiters = fuelLiters,
            initialOdometerKm = odometerKm,
            initialClusterSpeedKmh = clusterSpeed,
            initialClusterRpm = clusterRpm,
            initialClusterCoolantC = clusterCoolant
        )
    }

    fun decodeFuelLiters(lines: List<String>): Double? {
        val payload = Elm327Client.extractPidPayload(lines, 0x61, 0x29) ?: return null
        val raw = payload.getOrNull(0) ?: return null
        if (raw == 0xFF) return null
        return raw * 0.5
    }

    fun decodeOdometerKm(lines: List<String>): Long? {
        val flat = Elm327Client.flattenIsoTpBytes(lines)
        if (flat.size < 13 || flat[0] != 0x61 || flat[1] != 0xE1) return null
        val data = flat.drop(2)
        if (data.size < 11) return null
        // Validate optional BCD month byte when full 17-byte timestamped cluster frame is present
        if (data.size >= 14) {
            val bcdMonth = data[12]
            val monthValid = bcdMonth in 0x01..0x09 || bcdMonth in 0x10..0x12
            if (!monthValid) return null
        }
        val odo = ((data[7].toLong() and 0xFFL) shl 24) or
            ((data[8].toLong() and 0xFFL) shl 16) or
            ((data[9].toLong() and 0xFFL) shl 8) or
            (data[10].toLong() and 0xFFL)
        return odo.takeIf { it in 1L..999_999L }
    }

    fun decodeClusterSpeedKmh(lines: List<String>): Double? {
        val payload = Elm327Client.extractPidPayload(lines, 0x61, 0x21) ?: return null
        val raw = payload.getOrNull(0) ?: return null
        if (raw == 0xFF) return null
        return raw.toDouble()
    }

    fun decodeClusterRpm(lines: List<String>): Double? {
        val payload = Elm327Client.extractPidPayload(lines, 0x61, 0x22) ?: return null
        val raw = payload.getOrNull(0) ?: return null
        if (raw == 0xFF) return null
        return raw * 50.0
    }

    fun decodeClusterCoolantC(lines: List<String>): Double? {
        val payload = Elm327Client.extractPidPayload(lines, 0x61, 0x23) ?: return null
        val raw = payload.getOrNull(0) ?: return null
        if (raw == 0xFF) return null
        return raw * 0.5
    }
}
