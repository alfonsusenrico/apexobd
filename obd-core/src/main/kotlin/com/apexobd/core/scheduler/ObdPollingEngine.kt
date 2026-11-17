package com.apexobd.core.scheduler

import com.apexobd.core.elm.Elm327Client
import com.apexobd.core.elm.ElmHandshakeInfo
import com.apexobd.core.elm.ObdCommandResult
import com.apexobd.core.formula.CustomPidDefinition
import com.apexobd.core.formula.FormulaEvaluator
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

    fun setVehicleProfile(profile: VehicleProfile) {
        vehicleProfile = profile
    }

    fun setFuelCalculationPaused(paused: Boolean) {
        fuelTracker.paused = paused
    }

    fun updateExternalGpsSpeed(speedKmh: Double?) {
        externalGpsSpeedKmh = speedKmh
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
        val fuelKeys = setOf("kpl_instant", "kpl_road_avg", "kpl_trip_avg", "fuel_flow_lph", "fuel_used_l", "efficiency_score")
        return buildSet {
            addAll(requested)
            if (requested.any { it in fuelKeys }) {
                add("maf")
                add("obd_speed")
                add("throttle")
                add("rpm")
                if ("0B" in discovered) add("map")
                if ("0F" in discovered) add("iat")
                if ("04" in discovered) add("engine_load")
            }
            if ("ve_pct" in requested) {
                add("maf"); add("map"); add("rpm"); add("iat")
            }
            if ("hp" in requested || "torque" in requested) {
                add("rpm"); add("maf"); add("map")
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

    private fun emitDerivedAndSnapshot(values: MutableMap<String, Double>) {
        val vp = vehicleProfile
        val speedKmh = externalGpsSpeedKmh ?: values["obd_speed"] ?: 0.0
        val rpm = values["rpm"]
        val mapKpa = values["map"]
        val iat = values["iat"]
        val maf = values["maf"] ?: FuelEconomyTracker.estimateMafSpeedDensity(
            displacementLiters = vp.displacementLiters,
            vePct = vp.volumetricEfficiencyPct,
            rpm = rpm,
            mapKpa = mapKpa,
            iatCelsius = iat
        )

        val (tq, hp) = PerformanceCalculator.estimateTorqueAndHp(
            rpm = rpm,
            mafGramsPerSec = maf,
            actualTorquePct = values["actual_torque_pct"],
            refTorqueNm = values["ref_torque_nm"],
            drivetrainLossPct = vp.drivetrainLossPct
        )
        if (hp != null) values["hp"] = hp
        if (tq != null) values["torque"] = tq

        if (mapKpa != null) {
            val baroKpa = values["baro"] ?: 101.3
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

        if (maf != null || values["maf"] != null) {
            val fuel = fuelTracker.update(
                profile = vp,
                speedKmh = speedKmh,
                rpm = rpm,
                mafGramsPerSec = values["maf"],
                mapKpa = mapKpa,
                iatCelsius = iat,
                lambda = values["lambda"],
                throttlePct = values["throttle"]
            )
            values["fuel_flow_lph"] = fuel.fuelFlowLitersPerHour
            values["kpl_instant"] = fuel.instantKpl
            values["kpl_trip_avg"] = fuel.tripAvgKpl
            values["kpl_road_avg"] = fuel.roadOnlyAvgKpl
            values["fuel_used_l"] = fuel.totalFuelUsedLiters
            values["efficiency_score"] = FuelEconomyTracker.computeDrivingEfficiencyScore(
                instantKpl = fuel.instantKpl,
                throttlePct = values["throttle"],
                engineLoadPct = values["engine_load"],
                speedKmh = speedKmh
            )
            _fuelMetrics.value = fuel
        }

        _liveValues.value = values.toMap()
    }
}
