package com.apexobd.core.scheduler

import com.apexobd.core.elm.Elm327Client
import com.apexobd.core.elm.ElmHandshakeInfo
import com.apexobd.core.elm.ObdCommandResult
import com.apexobd.core.formula.CustomPidDefinition
import com.apexobd.core.formula.FormulaEvaluator
import com.apexobd.core.model.FuelType
import com.apexobd.core.model.VehicleProfile
import com.apexobd.core.protocol.DerivedSensorCatalog
import com.apexobd.core.protocol.FuelEconomyTracker
import com.apexobd.core.protocol.FuelMetricsSnapshot
import com.apexobd.core.protocol.ObdPidRegistry
import com.apexobd.core.protocol.PidDefinition
import com.apexobd.core.protocol.PollTier
import com.apexobd.core.transport.ObdTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

enum class PollingProfile(
    val label: String,
    val cycleDelayMs: Long,
    val slowPollEveryNCycles: Int
) {
    MAX_PERFORMANCE("Max Speed (0ms delay)", 0L, 18),
    BALANCED("Balanced (Low Latency + Battery)", 8L, 20),
    ECO_BATTERY("Eco Battery (Low Radio Duty)", 100L, 20)
}

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data class Connected(val handshake: ElmHandshakeInfo, val supportedPidCount: Int) : ConnectionState()
    data class Reconnecting(val attempt: Int, val reason: String) : ConnectionState()
}

data class Timer0100State(
    val armed: Boolean = true,
    val running: Boolean = false,
    val currentSec: Double = 0.0,
    val lastRunSec: Double? = null,
    val bestRunSec: Double? = null
)

object PerformanceCalculator {
    fun estimateTorqueAndHp(
        rpm: Double?,
        mafGramsPerSec: Double?,
        actualTorquePct: Double?,
        refTorqueNm: Double?,
        drivetrainLossPct: Double = 15.0
    ): Pair<Double?, Double?> {
        if (rpm == null || rpm < 100.0) return null to null

        if (actualTorquePct != null && refTorqueNm != null && refTorqueNm > 0.0) {
            val torqueNm = (actualTorquePct / 100.0) * refTorqueNm
            val hp = (torqueNm * rpm) / 7127.0
            return torqueNm.coerceAtLeast(0.0) to hp.coerceAtLeast(0.0)
        }

        if (mafGramsPerSec != null) {
            val crankHp = mafGramsPerSec / 0.80
            val wheelFactor = (1.0 - (drivetrainLossPct / 100.0)).coerceIn(0.5, 1.0)
            val wheelHp = crankHp * wheelFactor
            val torqueNm = (wheelHp * 7127.0) / rpm
            return torqueNm to wheelHp
        }
        return null to null
    }

    /**
     * Multi-sensor torque & wheel HP calculation combining:
     * 1) Native ECU torque PIDs (0162/0163) when available,
     * 2) Willans-line BSFC (Load 0104) + Spark Timing Advance (010E) - FMEP(RPM) friction,
     * 3) Longitudinal road-load dynamics (IMU accel, speed, Baro/IAT air density, grade) cross-blend.
     */
    fun estimatePhysicsTorqueAndHp(
        profile: VehicleProfile,
        rpm: Double?,
        speedKmh: Double,
        mafGramsPerSec: Double?,
        actualTorquePct: Double?,
        refTorqueNm: Double?,
        engineLoadPct: Double?,
        timingAdvanceDeg: Double?,
        baroKpa: Double?,
        iatCelsius: Double?,
        imuAccelG: Double? = null,
        roadGradePct: Double = 0.0,
        dfcoActive: Boolean = false
    ): Pair<Double?, Double?> {
        if (dfcoActive) return 0.0 to 0.0

        val wheelFactor = (1.0 - (profile.drivetrainLossPct / 100.0)).coerceIn(0.5, 1.0)

        // Longitudinal road dynamics power at the wheels (for EV or active acceleration cross-check)
        val vMs = (speedKmh / 3.6).coerceAtLeast(0.0)
        val roadDynWheelKw = if (vMs > 2.5 && imuAccelG != null) {
            val tempK = (iatCelsius ?: 25.0) + 273.15
            val airDensity = (baroKpa ?: 101.325) / (0.287058 * tempK) // kg/m^3
            val effMass = profile.weightKg * 1.05
            val aMs2 = imuAccelG * 9.80665
            val fInertia = effMass * aMs2
            val fAero = 0.5 * airDensity * profile.dragCoefficientAreaCdA * vMs * vMs
            val fRoll = profile.rollingResistanceCrr * profile.weightKg * 9.80665
            val fGrade = profile.weightKg * 9.80665 * (roadGradePct / 100.0).coerceIn(-0.25, 0.25)
            ((fInertia + fAero + fRoll + fGrade) * vMs / 1000.0)
        } else null

        if (profile.fuelType == FuelType.ELECTRIC) {
            val kw = roadDynWheelKw ?: return null to null
            val hp = kw * 1.34102
            val tq = if (rpm != null && rpm > 100.0) (hp * 7127.0) / rpm else 0.0
            return tq to hp
        }

        if (rpm == null || rpm < 100.0) return null to null

        if (actualTorquePct != null && refTorqueNm != null && refTorqueNm > 0.0) {
            val torqueNm = (actualTorquePct / 100.0) * refTorqueNm * wheelFactor
            val hp = (torqueNm * rpm) / 7127.0
            return torqueNm.coerceAtLeast(0.0) to hp.coerceAtLeast(0.0)
        }

        if (mafGramsPerSec == null) {
            if (roadDynWheelKw != null && roadDynWheelKw > 0.5) {
                val hp = roadDynWheelKw * 1.34102
                val tq = (hp * 7127.0) / rpm
                return tq.coerceAtLeast(0.0) to hp.coerceAtLeast(0.0)
            }
            return null to null
        }

        // Willans-line BSFC curve: lowest BSFC (~255 g/kWh gasoline, ~205 g/kWh diesel) at mid-high load
        val load = (engineLoadPct ?: 40.0).coerceIn(5.0, 100.0)
        val baseBsfc = if (profile.fuelType == FuelType.DIESEL) 210.0 else 258.0
        val bsfc = when {
            load < 25.0 -> baseBsfc * 1.38
            load <= 78.0 -> baseBsfc
            else -> baseBsfc * 1.12
        }
        val sparkEff = when {
            timingAdvanceDeg == null -> 1.0
            timingAdvanceDeg < 5.0 -> 0.86 // knocked/retarded timing loses BMEP
            timingAdvanceDeg in 12.0..38.0 -> 1.0
            else -> 0.96
        }

        val fuelGps = (mafGramsPerSec / profile.fuelType.stoichiometricAfr).coerceAtLeast(0.0)
        val grossKw = ((fuelGps * 3600.0) / bsfc) * sparkEff
        // Mechanical FMEP + accessory pumping intercept scales with displacement and RPM^1.3
        val rpmNorm = (rpm / 1000.0).coerceAtLeast(0.4)
        val frictionKw = profile.displacementLiters * (1.25 * rpmNorm + 0.16 * rpmNorm * rpmNorm)
        val crankKw = (grossKw - frictionKw).coerceAtLeast(0.0)
        val thermoWheelKw = crankKw * wheelFactor

        val finalWheelKw = if (roadDynWheelKw != null && imuAccelG != null && imuAccelG > 0.05 && thermoWheelKw > 5.0) {
            (thermoWheelKw * 0.65) + (roadDynWheelKw.coerceAtLeast(0.0) * 0.35)
        } else {
            thermoWheelKw
        }
        val finalWheelHp = (finalWheelKw * 1.34102).coerceAtLeast(0.0)
        val torqueNm = (finalWheelHp * 7127.0) / rpm
        return torqueNm to finalWheelHp
    }
}

class Timer0100Tracker {
    private var state = Timer0100State()
    private var startMs: Long = 0L

    fun onSpeedSample(speedKmh: Double, nowMs: Long = System.currentTimeMillis()): Timer0100State {
        if (!state.running) {
            if (speedKmh <= 1.0) {
                state = state.copy(armed = true)
            } else if (state.armed && speedKmh > 1.5 && speedKmh < 25.0) {
                startMs = nowMs
                state = state.copy(armed = false, running = true, currentSec = 0.0)
            }
        } else {
            val elapsed = (nowMs - startMs) / 1000.0
            if (speedKmh >= 100.0) {
                val best = state.bestRunSec?.let { minOf(it, elapsed) } ?: elapsed
                state = state.copy(running = false, currentSec = elapsed, lastRunSec = elapsed, bestRunSec = best)
            } else if (speedKmh < 1.0 && elapsed > 1.5) {
                state = state.copy(running = false, armed = true, currentSec = 0.0)
            } else {
                state = state.copy(currentSec = elapsed)
            }
        }
        return state
    }

    fun resetBest(): Timer0100State {
        state = Timer0100State()
        return state
    }
}

/**
 * Low-latency priority-tiered polling scheduler with 2x primary sensor interleaving,
 * incremental UI state emission, dynamic multi-car availability detection, and
 * idle-excluding fuel economy tracking.
 */
class ObdPollingEngine(
    private val transport: ObdTransport,
    private val scope: CoroutineScope
) {
    val client = Elm327Client(transport)
    val fuelTracker = FuelEconomyTracker()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _supportedPids = MutableStateFlow<Set<String>>(emptySet())
    val supportedPids: StateFlow<Set<String>> = _supportedPids.asStateFlow()

    private val _availableSensorKeys = MutableStateFlow<Set<String>>(emptySet())
    val availableSensorKeys: StateFlow<Set<String>> = _availableSensorKeys.asStateFlow()

    private val _liveValues = MutableStateFlow<Map<String, Double>>(emptyMap())
    val liveValues: StateFlow<Map<String, Double>> = _liveValues.asStateFlow()

    private val _fuelMetrics = MutableStateFlow<FuelMetricsSnapshot?>(null)
    val fuelMetrics: StateFlow<FuelMetricsSnapshot?> = _fuelMetrics.asStateFlow()

    private val _pollingHz = MutableStateFlow(0)
    val pollingHz: StateFlow<Int> = _pollingHz.asStateFlow()

    private val _avgLatencyMs = MutableStateFlow(0)
    val avgLatencyMs: StateFlow<Int> = _avgLatencyMs.asStateFlow()

    @Volatile
    private var activeSensorKeys: Set<String> = emptySet()

    @Volatile
    private var customPids: Map<String, CustomPidDefinition> = emptyMap()

    @Volatile
    private var pollingProfile: PollingProfile = PollingProfile.BALANCED

    @Volatile
    private var vehicleProfile: VehicleProfile = VehicleProfile()

    @Volatile
    private var externalGpsSpeedKmh: Double? = null

    @Volatile
    private var externalImuAccelG: Double? = null

    @Volatile
    private var externalRoadGradePct: Double = 0.0

    private var pollJob: Job? = null

    fun setActiveSensors(keys: Set<String>) {
        activeSensorKeys = keys
    }

    fun setCustomPids(defs: List<CustomPidDefinition>) {
        customPids = defs.associateBy { it.key }
    }

    fun setPollingProfile(profile: PollingProfile) {
        pollingProfile = profile
    }

    fun setVehicleProfile(profile: VehicleProfile, resetFuelSession: Boolean = false) {
        vehicleProfile = profile
        fuelTracker.seedFromProfile(profile, force = resetFuelSession)
    }

    fun setFuelCalculationPaused(paused: Boolean) {
        fuelTracker.paused = paused
    }

    fun updateExternalGpsSpeed(speedKmh: Double?) {
        externalGpsSpeedKmh = speedKmh
    }

    fun updateExternalTelemetry(gpsSpeedKmh: Double?, imuAccelG: Double? = null, roadGradePct: Double = 0.0) {
        externalGpsSpeedKmh = gpsSpeedKmh
        externalImuAccelG = imuAccelG
        externalRoadGradePct = roadGradePct
    }

    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            var attempt = 0
            while (isActive) {
                try {
                    _connectionState.value = if (attempt == 0) {
                        ConnectionState.Connecting
                    } else {
                        ConnectionState.Reconnecting(attempt, "Reconnecting to ${transport.endpointLabel}")
                    }
                    transport.connect()
                    val handshake = client.initialize(aggressiveTiming = pollingProfile == PollingProfile.MAX_PERFORMANCE)
                    val discovered = ObdPidRegistry.discoverSupportedPids(client)
                    _supportedPids.value = discovered
                    _availableSensorKeys.value = computeAvailableSensorKeys(discovered)
                    _connectionState.value = ConnectionState.Connected(handshake, discovered.size)
                    attempt = 0

                    runPollingLoop(handshake, discovered)
                } catch (t: Throwable) {
                    if (!isActive) break
                    attempt++
                    _connectionState.value = ConnectionState.Reconnecting(attempt, t.message ?: "Connection lost")
                    runCatching { transport.disconnect() }
                    val backoffMs = when {
                        attempt == 1 -> 1000L
                        attempt == 2 -> 2500L
                        else -> 5000L
                    }
                    delay(backoffMs)
                }
            }
        }
    }

    suspend fun stop() {
        pollJob?.cancel()
        pollJob = null
        runCatching { transport.disconnect() }
        _connectionState.value = ConnectionState.Disconnected
        _pollingHz.value = 0
        _avgLatencyMs.value = 0
    }

    private fun computeAvailableSensorKeys(discovered: Set<String>): Set<String> {
        val out = linkedSetOf<String>()
        for (pid in ObdPidRegistry.standardPids) {
            if (discovered.isEmpty() || pid.pidHex in discovered) {
                out.add(pid.key)
            }
        }
        for (derived in DerivedSensorCatalog.all) {
            if (DerivedSensorCatalog.isAvailable(derived, discovered)) {
                out.add(derived.key)
            }
        }
        out.addAll(customPids.keys)
        return out
    }

    private suspend fun runPollingLoop(handshake: ElmHandshakeInfo, discovered: Set<String>) {
        var cycle = 0
        var samplesInSec = 0
        var latencyAccumMs = 0L
        var rttCount = 0
        var windowStart = System.currentTimeMillis()
        var supportsMultiPid = handshake.isCanProtocol
        val values = _liveValues.value.toMutableMap()

        while (scope.isActive && transport.isConnected.value) {
            val profile = pollingProfile
            val requested = activeSensorKeys.ifEmpty {
                setOf("rpm", "obd_speed", "voltage", "iat", "coolant", "throttle", "maf", "timing_advance")
            }

            val expandedKeys = expandDependencies(requested, discovered)
            val standardTargets = expandedKeys
                .mapNotNull { ObdPidRegistry.findByKey(it) }
                .distinctBy { it.pidHex }
                .filter { discovered.isEmpty() || it.pidHex in discovered }
                .filter { it.defaultTier == PollTier.HIGH || cycle % profile.slowPollEveryNCycles == 0 }

            if (supportsMultiPid && standardTargets.size >= 2) {
                for (chunk in standardTargets.chunked(5)) {
                    if (!transport.isConnected.value) break
                    if (chunk.size == 1) {
                        val (ok, rtt) = pollSingleAndEmit(chunk.first(), handshake.isCanProtocol, values)
                        if (ok) samplesInSec++
                        latencyAccumMs += rtt
                        rttCount++
                    } else {
                        val cmd = "01" + chunk.joinToString("") { it.pidHex }
                        val t0 = System.currentTimeMillis()
                        val res = client.execute(cmd)
                        latencyAccumMs += (System.currentTimeMillis() - t0)
                        rttCount++

                        val decodedList = if (res is ObdCommandResult.Success) {
                            ObdPidRegistry.decodeMultiMode01Response(res.lines)
                        } else emptyList()

                        if (decodedList.isEmpty()) {
                            supportsMultiPid = false
                            for (pid in chunk) {
                                val (ok, rtt) = pollSingleAndEmit(pid, handshake.isCanProtocol, values)
                                if (ok) samplesInSec++
                                latencyAccumMs += rtt
                                rttCount++
                            }
                        } else {
                            for (item in decodedList) {
                                values[item.key] = item.value
                                samplesInSec++
                            }
                            emitDerivedAndSnapshot(values)
                        }
                    }
                }
            } else {
                // Interleave primary RPM / high-priority PID every 2nd step for 2x smoother gauge updates
                val rpmPid = standardTargets.firstOrNull { it.key == "rpm" }
                val others = standardTargets.filter { it.key != "rpm" }

                if (others.isEmpty() && rpmPid != null) {
                    val (ok, rtt) = pollSingleAndEmit(rpmPid, handshake.isCanProtocol, values)
                    if (ok) samplesInSec++
                    latencyAccumMs += rtt
                    rttCount++
                } else {
                    for ((idx, pid) in others.withIndex()) {
                        if (!transport.isConnected.value) break
                        if (rpmPid != null && idx % 2 == 0) {
                            val (okRpm, rttRpm) = pollSingleAndEmit(rpmPid, handshake.isCanProtocol, values)
                            if (okRpm) samplesInSec++
                            latencyAccumMs += rttRpm
                            rttCount++
                        }
                        val (ok, rtt) = pollSingleAndEmit(pid, handshake.isCanProtocol, values)
                        if (ok) samplesInSec++
                        latencyAccumMs += rtt
                        rttCount++
                    }
                }
            }

            val customTargets = expandedKeys
                .mapNotNull { customPids[it] }
                .filter { cycle % 3 == 0 }

            for (custom in customTargets) {
                if (!transport.isConnected.value) break
                val t0 = System.currentTimeMillis()
                val res = client.execute(custom.modeAndPidHex)
                latencyAccumMs += (System.currentTimeMillis() - t0)
                rttCount++
                if (res is ObdCommandResult.Success) {
                    val decoded = FormulaEvaluator.decodeCustomPid(custom, res)
                    if (decoded != null) {
                        values[custom.key] = decoded
                        samplesInSec++
                    }
                }
            }

            emitDerivedAndSnapshot(values)

            val now = System.currentTimeMillis()
            if (now - windowStart >= 1000L) {
                _pollingHz.value = max(0, samplesInSec)
                _avgLatencyMs.value = if (rttCount > 0) (latencyAccumMs / rttCount).toInt() else 0
                samplesInSec = 0
                latencyAccumMs = 0L
                rttCount = 0
                windowStart = now
            }
            cycle++
            if (profile.cycleDelayMs > 0L) {
                delay(profile.cycleDelayMs)
            }
        }
    }

    private fun expandDependencies(requested: Set<String>, discovered: Set<String>): Set<String> {
        val fuelKeys = setOf(
            "kpl_instant", "kpl_road_avg", "kpl_trip_avg",
            "fuel_flow_lph", "fuel_used_l", "fuel_remaining", "range_dte", "efficiency_score"
        )
        return buildSet {
            addAll(requested)
            if (requested.any { it in fuelKeys }) {
                if ("5E" in discovered) add("fuel_rate")
                if ("10" in discovered) add("maf")
                if ("0D" in discovered) add("obd_speed")
                if ("0C" in discovered) add("rpm")
                if ("45" in discovered) add("rel_throttle") else if ("11" in discovered) add("throttle")
                if ("0B" in discovered) add("map")
                if ("0F" in discovered) add("iat")
                if ("33" in discovered) add("baro")
                if ("04" in discovered) add("engine_load")
                if ("05" in discovered) add("coolant")
                if ("06" in discovered) add("stft_b1")
                if ("07" in discovered) add("ltft_b1")
                if ("44" in discovered) add("lambda")
                if ("2F" in discovered) add("fuel_level")
            }
            if ("ve_pct" in requested) {
                add("maf"); add("map"); add("rpm"); add("iat")
            }
            if ("hp" in requested || "torque" in requested) {
                add("rpm"); add("maf"); add("map"); add("obd_speed")
                if ("04" in discovered) add("engine_load")
                if ("0E" in discovered) add("timing_advance")
                if ("33" in discovered) add("baro")
                if ("0F" in discovered) add("iat")
                if ("62" in discovered && "63" in discovered) {
                    add("actual_torque_pct")
                    add("ref_torque_nm")
                }
            }
            if ("boost_kpa" in requested || "boost_bar" in requested) {
                add("map")
                if ("33" in discovered) add("baro")
            }
        }
    }

    private suspend fun pollSingleAndEmit(
        pid: PidDefinition,
        isCanProtocol: Boolean,
        values: MutableMap<String, Double>
    ): Pair<Boolean, Long> {
        val cmd = if (isCanProtocol) "01${pid.pidHex}1" else "01${pid.pidHex}"
        val t0 = System.currentTimeMillis()
        val res = client.execute(cmd)
        val rtt = System.currentTimeMillis() - t0
        if (res is ObdCommandResult.Success) {
            val decodedList = ObdPidRegistry.decodeAllMode01Responses(pid.pidHex, res.lines)
            if (decodedList.isNotEmpty()) {
                for (decoded in decodedList) {
                    values[decoded.key] = decoded.value
                }
                emitDerivedAndSnapshot(values)
                return true to rtt
            }
        }
        return false to rtt
    }

    private fun averageTrim(b1: Double?, b2: Double?): Double? = when {
        b1 != null && b2 != null -> (b1 + b2) / 2.0
        b1 != null -> b1
        else -> b2
    }

    private fun emitDerivedAndSnapshot(values: MutableMap<String, Double>) {
        val vp = vehicleProfile
        externalGpsSpeedKmh?.let { values["gps_speed"] = it }
        externalImuAccelG?.let { values["accel_g"] = it }

        val speedKmh = values["obd_speed"] ?: externalGpsSpeedKmh ?: 0.0
        val rpm = values["rpm"]
        val mapKpa = values["map"]
        val iat = values["iat"]
        val baroKpa = values["baro"] ?: 101.325
        val loadPct = values["engine_load"]
        val maf = values["maf"] ?: FuelEconomyTracker.estimateMafSpeedDensity(
            displacementLiters = vp.displacementLiters,
            vePct = vp.volumetricEfficiencyPct,
            rpm = rpm,
            mapKpa = mapKpa,
            iatCelsius = iat,
            baroKpa = baroKpa,
            engineLoadPct = loadPct
        )

        val relThrottle = values["rel_throttle"]
        val absThrottle = values["throttle"]
        val dfcoProbable = vp.fuelType != FuelType.ELECTRIC &&
            speedKmh > 12.0 &&
            (rpm ?: 0.0) > 1150.0 &&
            (if (relThrottle != null) relThrottle <= 1.5 else (absThrottle != null && absThrottle <= 12.0 && (mapKpa == null || mapKpa <= baroKpa * 0.30))) &&
            (mapKpa == null || mapKpa <= baroKpa * 0.36)

        val (tq, hp) = PerformanceCalculator.estimatePhysicsTorqueAndHp(
            profile = vp,
            rpm = rpm,
            speedKmh = speedKmh,
            mafGramsPerSec = maf,
            actualTorquePct = values["actual_torque_pct"],
            refTorqueNm = values["ref_torque_nm"],
            engineLoadPct = loadPct,
            timingAdvanceDeg = values["timing_advance"],
            baroKpa = baroKpa,
            iatCelsius = iat,
            imuAccelG = externalImuAccelG,
            roadGradePct = externalRoadGradePct,
            dfcoActive = dfcoProbable
        )
        if (hp != null) values["hp"] = hp
        if (tq != null) values["torque"] = tq

        if (mapKpa != null) {
            val boostKpa = mapKpa - baroKpa
            values["boost_kpa"] = boostKpa
            values["boost_bar"] = boostKpa / 100.0
        }

        FuelEconomyTracker.computeVolumetricEfficiencyPct(
            displacementLiters = vp.displacementLiters,
            rpm = rpm,
            mafGramsPerSec = values["maf"],
            mapKpa = mapKpa,
            iatCelsius = iat
        )?.let { values["ve_pct"] = it }

        val ecuFuelRate = values["fuel_rate"]
        if (maf != null || ecuFuelRate != null || vp.fuelType == FuelType.ELECTRIC) {
            val wheelKw = hp?.let { it * 0.7457 }
            val fuel = fuelTracker.update(
                profile = vp,
                speedKmh = speedKmh,
                rpm = rpm,
                mafGramsPerSec = values["maf"],
                mapKpa = mapKpa,
                iatCelsius = iat,
                lambda = values["lambda"],
                throttlePct = absThrottle,
                relThrottlePct = relThrottle,
                baroKpa = baroKpa,
                coolantCelsius = values["coolant"],
                engineLoadPct = loadPct,
                stftPct = averageTrim(values["stft_b1"], values["stft_b2"]),
                ltftPct = averageTrim(values["ltft_b1"], values["ltft_b2"]),
                ecuFuelRateLph = ecuFuelRate,
                ecuFuelLevelPct = values["fuel_level"],
                wheelPowerKw = wheelKw
            )
            values["fuel_flow_lph"] = fuel.fuelFlowLitersPerHour
            values["kpl_instant"] = fuel.instantKpl
            values["kpl_trip_avg"] = fuel.tripAvgKpl
            values["kpl_road_avg"] = fuel.allTimeRoadAvgKpl.takeIf { it > 0.0 } ?: fuel.roadOnlyAvgKpl
            values["fuel_used_l"] = fuel.totalFuelUsedLiters
            values["fuel_remaining"] = fuel.estimatedRemainingFuelLiters
            values["range_dte"] = fuel.estimatedRangeKm
            values["efficiency_score"] = fuelTracker.computeEfficiencyDeltaScore(
                instantKpl = fuel.instantKpl,
                baselineAvgKpl = fuel.allTimeRoadAvgKpl.takeIf { it > 1.0 } ?: fuel.roadOnlyAvgKpl,
                throttlePct = absThrottle,
                engineLoadPct = loadPct,
                speedKmh = speedKmh,
                accelG = externalImuAccelG,
                dfcoActive = fuel.dfcoActive
            )
            _fuelMetrics.value = fuel
        } else {
            // Still expose configured static Tank/Range estimate even before live fuel PIDs stream
            fuelTracker.seedFromProfile(vp)
            values["fuel_remaining"] = vp.estimatedRemainingFuelLiters
            val estAvg = if (vp.allTimeRoadFuelLiters > 0.01) vp.allTimeRoadDistanceKm / vp.allTimeRoadFuelLiters else 12.0
            values["range_dte"] = vp.estimatedRemainingFuelLiters * estAvg
        }

        _liveValues.value = values.toMap()
    }
}
