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
    val clusterCoolant: Boolean = false,
    val ecmDistanceSinceClear: Boolean = false
) {
    val hasAny: Boolean
        get() = fuelLevel || odometer || clusterSpeed || clusterRpm || clusterCoolant

    fun capabilityTokens(): Set<String> = buildSet {
        if (fuelLevel) add(TOKEN_FUEL_LEVEL)
        if (odometer) add(TOKEN_ODOMETER)
        if (clusterSpeed) add(TOKEN_CLUSTER_SPEED)
        if (clusterRpm) add(TOKEN_CLUSTER_RPM)
        if (clusterCoolant) add(TOKEN_CLUSTER_COOLANT)
        if (ecmDistanceSinceClear) add(TOKEN_ECM_DIST_2131)
    }

    companion object {
        const val HEADER_TX = "7C0"
        const val HEADER_RX = "7C8"
        const val ECM_HEADER_TX = "7E0"
        const val ECM_HEADER_RX = "7E8"

        const val TOKEN_FUEL_LEVEL = "7C0_2129"
        const val TOKEN_ODOMETER = "7C0_21E1"
        const val TOKEN_CLUSTER_SPEED = "7C0_2121"
        const val TOKEN_CLUSTER_RPM = "7C0_2122"
        const val TOKEN_CLUSTER_COOLANT = "7C0_2123"
        const val TOKEN_ECM_DIST_2131 = "7E0_2131"
    }
}

data class ToyotaMeterDiscoveryResult(
    val capabilities: ToyotaMeterCapabilities,
    val initialFuelLiters: Double? = null,
    val initialOdometerKm: Long? = null,
    val initialClusterSpeedKmh: Double? = null,
    val initialClusterRpm: Double? = null,
    val initialClusterCoolantC: Double? = null,
    val initialEcmDistanceSinceClearKm: Double? = null,
    val requiresExplicitEcmRxFilter: Boolean = false
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

    suspend fun discover(
        client: Elm327Client,
        mode01Supports0131: Boolean = false
    ): ToyotaMeterDiscoveryResult {
        var fuelLiters: Double? = null
        var odometerKm: Long? = null
        var clusterSpeed: Double? = null
        var clusterRpm: Double? = null
        var clusterCoolant: Double? = null
        var ecmDistSinceClearKm: Double? = null

        var requiresExplicitEcmRx = false
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
            client.restoreDefaultCanHeaderAndFilter(explicitEcmRxFallback = false, timeoutMs = 800L)
            // Verify Engine ECU (7E8) still responds after clearing 7C8 filter; some ELM327 v1.5 clones
            // ignore ATCRA/ATCM/ATAR and require an explicit ATCRA7E8 to unlock Mode 01 responses.
            val verify = client.execute("01001", timeoutMs = 750L)
            val ecmOk = verify is ObdCommandResult.Success &&
                Elm327Client.extractPidPayload(verify.lines, 0x41, 0x00) != null
            if (!ecmOk) {
                requiresExplicitEcmRx = true
                client.restoreDefaultCanHeaderAndFilter(explicitEcmRxFallback = true, timeoutMs = 800L)
            }
        }

        // If 7C0 cluster odometer/fuel are absent and Mode 01 0131 is not advertised (e.g. 2014 Avanza K3-VE),
        // probe Toyota/Daihatsu Engine ECM Mode 2131 (Distance Traveled Since DTCs Cleared, 16-bit km).
        if (odometerKm == null && !mode01Supports0131) {
            val ecm2131 = client.execute("2131", timeoutMs = 700L)
            if (ecm2131 is ObdCommandResult.Success) {
                ecmDistSinceClearKm = decodeEcmDistanceSinceClearKm(ecm2131.lines)
            }
        }

        val caps = ToyotaMeterCapabilities(
            fuelLevel = fuelLiters != null,
            odometer = odometerKm != null,
            clusterSpeed = clusterSpeed != null,
            clusterRpm = clusterRpm != null,
            clusterCoolant = clusterCoolant != null,
            ecmDistanceSinceClear = ecmDistSinceClearKm != null
        )
        return ToyotaMeterDiscoveryResult(
            capabilities = caps,
            initialFuelLiters = fuelLiters,
            initialOdometerKm = odometerKm,
            initialClusterSpeedKmh = clusterSpeed,
            initialClusterRpm = clusterRpm,
            initialClusterCoolantC = clusterCoolant,
            initialEcmDistanceSinceClearKm = ecmDistSinceClearKm,
            requiresExplicitEcmRxFilter = requiresExplicitEcmRx
        )
    }

    fun decodeEcmDistanceSinceClearKm(lines: List<String>): Double? {
        val payload = Elm327Client.extractPidPayload(lines, 0x61, 0x31) ?: return null
        if (payload.size < 2) return null
        val km = ((payload[0] and 0xFF) shl 8) or (payload[1] and 0xFF)
        return km.toDouble()
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

/**
 * OEM-style fuel float slosh filter for raw undamped `7C0 2129` readings (0.5 L steps, ~12 s cadence).
 * Rejects cornering/braking slosh spikes while moving, applies a sliding median + slow asymmetric EMA
 * (faster downward tracking than upward while moving), and snaps immediately only after 2 consecutive
 * stationary readings confirm a real refuel (+3.0 L or more).
 */
class FuelFloatSloshFilter {
    private val window = ArrayDeque<Double>(WINDOW_SIZE)
    private var filteredLiters: Double? = null
    private var refuelStreak = 0

    val currentLiters: Double?
        get() = filteredLiters

    fun seed(initialLiters: Double, tankCapacityLiters: Double): Double {
        val cap = tankCapacityLiters.coerceAtLeast(1.0)
        val clamped = initialLiters.coerceIn(0.0, cap)
        window.clear()
        window.addLast(clamped)
        filteredLiters = clamped
        refuelStreak = 0
        return clamped
    }

    fun reset() {
        window.clear()
        filteredLiters = null
        refuelStreak = 0
    }

    fun onRawSample(
        rawLiters: Double,
        speedKmh: Double,
        accelG: Double,
        tankCapacityLiters: Double
    ): Double {
        val cap = tankCapacityLiters.coerceAtLeast(1.0)
        val raw = rawLiters.coerceIn(0.0, cap)
        val prev = filteredLiters ?: return seed(raw, cap)
        val stationary = speedKmh <= 1.5

        if (stationary && raw >= prev + REFUEL_JUMP_LITERS) {
            refuelStreak++
            if (refuelStreak >= 2) {
                return seed(raw, cap)
            }
        } else {
            refuelStreak = 0
        }

        if (!stationary) {
            if (kotlin.math.abs(raw - prev) > MOVING_SLOSH_REJECT_LITERS || kotlin.math.abs(accelG) > MOVING_MAX_ACCEL_G) {
                return prev
            }
        }

        if (window.size >= WINDOW_SIZE) {
            window.removeFirst()
        }
        window.addLast(raw)
        val sorted = window.sorted()
        val median = if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2]
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) * 0.5
        }

        val delta = median - prev
        val step = if (stationary) {
            (delta * 0.22).coerceIn(-0.35, 0.35)
        } else {
            (delta * 0.08).coerceIn(-0.18, 0.04)
        }
        val next = (prev + step).coerceIn(0.0, cap)
        filteredLiters = next
        return next
    }

    companion object {
        private const val WINDOW_SIZE = 11
        private const val REFUEL_JUMP_LITERS = 3.0
        private const val MOVING_SLOSH_REJECT_LITERS = 2.5
        private const val MOVING_MAX_ACCEL_G = 0.12
    }
}
