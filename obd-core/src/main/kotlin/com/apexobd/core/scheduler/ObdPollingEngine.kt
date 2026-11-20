package com.apexobd.core.scheduler

import com.apexobd.core.elm.Elm327Client
import com.apexobd.core.elm.ElmHandshakeInfo
import com.apexobd.core.elm.ObdCommandResult
import com.apexobd.core.formula.CustomPidDefinition
import com.apexobd.core.formula.FormulaEvaluator
import com.apexobd.core.model.FuelCoastingMode
import com.apexobd.core.model.FuelType
import com.apexobd.core.model.TransmissionType
import com.apexobd.core.model.VehicleProfile
import com.apexobd.core.protocol.DerivedSensorCatalog
import com.apexobd.core.protocol.FuelEconomyTracker
import com.apexobd.core.protocol.FuelMetricsSnapshot
import com.apexobd.core.protocol.ObdPidRegistry
import com.apexobd.core.protocol.PidDefinition
import com.apexobd.core.protocol.PollTier
import com.apexobd.core.protocol.ToyotaMeterCapabilities
import com.apexobd.core.protocol.ToyotaMeterDiscoveryResult
import com.apexobd.core.protocol.ToyotaMeterProtocol
import com.apexobd.core.transport.ObdTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
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
    BALANCED("Balanced (Low Latency + Battery)", 0L, 12),
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

    private val _ecuOdometerKm = MutableStateFlow<Double?>(null)
    val ecuOdometerKm: StateFlow<Double?> = _ecuOdometerKm.asStateFlow()

    private val _ecuFuelLiters = MutableStateFlow<Double?>(null)
    val ecuFuelLiters: StateFlow<Double?> = _ecuFuelLiters.asStateFlow()

    @Volatile
    private var latestMeterFuelLiters: Double? = null

    @Volatile
    private var activeSensorKeys: Set<String> = emptySet()

    @Volatile
    private var backgroundRecordKeys: Set<String> = emptySet()

    @Volatile
    private var customPids: Map<String, CustomPidDefinition> = emptyMap()

    @Volatile
    private var pollingProfile: PollingProfile = PollingProfile.BALANCED

    @Volatile
    private var vehicleProfile: VehicleProfile = VehicleProfile()

    @Volatile
    private var fuelCoastingMode: FuelCoastingMode = FuelCoastingMode.REALISTIC_DFCO

    @Volatile
    private var externalGpsSpeedKmh: Double? = null

    @Volatile
    private var externalImuAccelG: Double? = null

    @Volatile
    private var externalRoadGradePct: Double = 0.0

    @Volatile
    private var backgroundIdleMode: Boolean = false

    private var pollJob: Job? = null

    fun setActiveSensors(keys: Set<String>) {
        activeSensorKeys = keys
    }

    fun setBackgroundIdleMode(idle: Boolean) {
        backgroundIdleMode = idle
    }

    fun setBackgroundRecordKeys(keys: Set<String>) {
        backgroundRecordKeys = keys
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

    fun setFuelCoastingMode(mode: FuelCoastingMode) {
        fuelCoastingMode = mode
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
        pollJob = scope.launch(Dispatchers.IO) {
            var attempt = 0
            while (isActive) {
                try {
                    _connectionState.value = if (attempt == 0) {
                        ConnectionState.Connecting
                    } else {
                        ConnectionState.Reconnecting(attempt, "Reconnecting to ${transport.endpointLabel}")
                    }
                    transport.connect()
                    val handshake = client.initialize(aggressiveTiming = pollingProfile != PollingProfile.ECO_BATTERY)
                    val discovered = ObdPidRegistry.discoverSupportedPids(client)
                    val meterDiscovery = if (handshake.isCanProtocol) {
                        ToyotaMeterProtocol.discover(client)
                    } else {
                        ToyotaMeterDiscoveryResult(ToyotaMeterCapabilities())
                    }
                    val allCapabilities = discovered + meterDiscovery.capabilities.capabilityTokens()
                    _supportedPids.value = allCapabilities
                    _availableSensorKeys.value = computeAvailableSensorKeys(allCapabilities)

                    latestMeterFuelLiters = meterDiscovery.initialFuelLiters
                    meterDiscovery.initialFuelLiters?.let { liters ->
                        _ecuFuelLiters.value = liters
                        fuelTracker.syncFuelRemainingFromEcu(liters, vehicleProfile.tankCapacityLiters)
                    }
                    meterDiscovery.initialOdometerKm?.let { odo ->
                        _ecuOdometerKm.value = odo.toDouble()
                        fuelTracker.syncOdometerFromEcu(odo.toDouble())
                    }

                    _connectionState.value = ConnectionState.Connected(handshake, allCapabilities.size)
                    attempt = 0

                    runPollingLoop(handshake, discovered, meterDiscovery)
                } catch (e: CancellationException) {
                    throw e
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
        val job = pollJob
        pollJob = null
        job?.cancel()
        runCatching { transport.disconnect() }
        _connectionState.value = ConnectionState.Disconnected
        _pollingHz.value = 0
        _avgLatencyMs.value = 0
    }

    private fun computeAvailableSensorKeys(discovered: Set<String>): Set<String> {
        val out = linkedSetOf<String>()
        for (pid in ObdPidRegistry.standardPids) {
            val fuelViaMeter = pid.key == "fuel_level" && ToyotaMeterCapabilities.TOKEN_FUEL_LEVEL in discovered
            if (discovered.isEmpty() || pid.pidHex in discovered || fuelViaMeter) {
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

    private fun slowCooldownMs(key: String, isVisible: Boolean): Long = when (key) {
        "voltage" -> if (isVisible) 2200L else 4000L
        "lambda", "o2_b1s1_lambda", "o2_b1s1_wr_lambda", "o2_b1s1_v", "o2_b1s2_v" ->
            if (isVisible) 850L else 1200L
        "fuel_system_status", "stft_b1", "ltft_b1", "stft_b2", "ltft_b2", "throttle" ->
            if (isVisible) 700L else 1200L
        "timing_advance", "fuel_pressure" -> if (isVisible) 450L else 1500L
        else -> if (isVisible) 1800L else 2800L
    }

    private fun selectNextDueSecondary(
        slowAll: List<PidDefinition>,
        requested: Set<String>,
        lastPollMs: Map<String, Long>,
        nowMs: Long
    ): PidDefinition? {
        var best: PidDefinition? = null
        var bestScore = Long.MIN_VALUE
        for (pid in slowAll) {
            val isVisible = pid.key in requested
            val cooldown = slowCooldownMs(pid.key, isVisible)
            val last = lastPollMs[pid.key] ?: 0L
            val elapsed = nowMs - last
            if (last == 0L || elapsed >= cooldown) {
                val overdue = if (last == 0L) 10_000L else (elapsed - cooldown).coerceAtMost(9_000L)
                val tierBonus = if (isVisible) 20_000L else 0L
                val unpolledBonus = if (last == 0L) 5_000L else 0L
                val speedPriority = if (cooldown <= 900L) 500L else 0L
                val score = tierBonus + unpolledBonus + speedPriority + overdue
                if (score > bestScore) {
                    bestScore = score
                    best = pid
                }
            }
        }
        return best
    }

    private suspend fun runPollingLoop(
        handshake: ElmHandshakeInfo,
        discovered: Set<String>,
        meterDiscovery: ToyotaMeterDiscoveryResult
    ) {
        var cycle = 0
        var samplesInSec = 0
        var latencyAccumMs = 0L
        var rttCount = 0
        var windowStart = System.currentTimeMillis()
        var multiPidFailStreak = 0
        var multiPidEverSucceeded = false
        var multiPidRetryAtMs = 0L
        val lastSlowPollMs = HashMap<String, Long>(24)
        val lastCustomPollMs = HashMap<String, Long>(16)
        val meterCaps = meterDiscovery.capabilities
        var lastMeterFuelMs = if (meterDiscovery.initialFuelLiters != null) windowStart else 0L
        var lastMeterOdoMs = if (meterDiscovery.initialOdometerKm != null) windowStart else 0L
        var lastMeterFastMs = windowStart
        var lastMeterCoolantMs = windowStart
        val values = _liveValues.value.toMutableMap()
        meterDiscovery.initialFuelLiters?.let { liters ->
            val cap = vehicleProfile.tankCapacityLiters.coerceAtLeast(1.0)
            values["fuel_level"] = ((liters / cap) * 100.0).coerceIn(0.0, 100.0)
            values["fuel_remaining"] = liters.coerceIn(0.0, cap)
        }
        meterDiscovery.initialOdometerKm?.let { values["ecu_odometer"] = it.toDouble() }
        meterDiscovery.initialClusterSpeedKmh?.let { values["cluster_speed"] = it }
        meterDiscovery.initialClusterRpm?.let { values["cluster_rpm"] = it }
        meterDiscovery.initialClusterCoolantC?.let { values["cluster_coolant"] = it }
        if (meterCaps.hasAny) {
            emitDerivedAndSnapshot(values)
        }
        val fastPriorityOrder = listOf("rpm", "rel_throttle", "pedal_d", "pedal_e", "engine_load", "obd_speed", "map", "maf", "throttle")
        val liveTimeoutMs = if (handshake.isCanProtocol) 650L else 1400L

        while (currentCoroutineContext().isActive && transport.isConnected.value) {
            val profile = pollingProfile
            val requested = activeSensorKeys.ifEmpty {
                setOf("rpm", "obd_speed", "voltage", "iat", "coolant", "rel_throttle", "maf", "timing_advance")
            }
            val background = backgroundRecordKeys

            val expandedKeys = expandDependencies(requested + background, discovered)
            val allAvailableTargets = expandedKeys
                .mapNotNull { ObdPidRegistry.findByKey(it) }
                .distinctBy { it.pidHex }
                .filter { discovered.isEmpty() || it.pidHex in discovered }

            val relThrSupported = "45" in discovered || discovered.isEmpty()
            val mafSupported = "10" in discovered || discovered.isEmpty()
            val coreFuelAirKeys = buildSet {
                add("rpm")
                add("obd_speed")
                add(if (relThrSupported) "rel_throttle" else "throttle")
                if (mafSupported) add("maf") else add("engine_load")
                if (!mafSupported && "0B" in discovered) add("map")
            }

            val isHighTarget: (PidDefinition) -> Boolean = { pid ->
                pid.defaultTier == PollTier.HIGH && (pid.key in requested || pid.key in coreFuelAirKeys)
            }
            val highTargets = allAvailableTargets
                .filter(isHighTarget)
                .sortedBy { pid ->
                    val idx = fastPriorityOrder.indexOf(pid.key)
                    if (idx >= 0) idx else 100
                }
            val slowAll = allAvailableTargets.filterNot(isHighTarget)
            val loopNowMs = System.currentTimeMillis()
            val dueSlowPid = selectNextDueSecondary(slowAll, requested, lastSlowPollMs, loopNowMs)

            if (multiPidFailStreak >= 3 && multiPidEverSucceeded && loopNowMs >= multiPidRetryAtMs) {
                multiPidFailStreak = 0
            }
            val canUseMultiPid = handshake.isCanProtocol && multiPidFailStreak < 3

            if (canUseMultiPid && highTargets.size >= 2) {
                for (chunk in ObdPidRegistry.packSingleFrameCanChunks(highTargets)) {
                    if (!currentCoroutineContext().isActive || !transport.isConnected.value) break
                    if (chunk.size == 1) {
                        val (ok, rtt) = pollSingleAndEmit(chunk.first(), handshake.isCanProtocol, values, liveTimeoutMs)
                        if (ok) samplesInSec++
                        latencyAccumMs += rtt
                        rttCount++
                    } else {
                        // Trailing '1' instructs ELM327 CAN to return immediately after 1 frame (~40ms vs ~100ms ATST wait)
                        val cmd = "01" + chunk.joinToString("") { it.pidHex } + "1"
                        val t0 = System.currentTimeMillis()
                        val res = client.execute(cmd, timeoutMs = liveTimeoutMs)
                        latencyAccumMs += (System.currentTimeMillis() - t0)
                        rttCount++

                        val decodedList = if (res is ObdCommandResult.Success) {
                            ObdPidRegistry.decodeMultiMode01Response(res.lines)
                        } else emptyList()

                        if (decodedList.isEmpty()) {
                            multiPidFailStreak++
                            if (multiPidFailStreak >= 3) {
                                // Only retry after 15s if multi-PID previously succeeded on this ECU;
                                // otherwise keep disabled for this session so unsupported ECUs never stall periodically.
                                multiPidRetryAtMs = if (multiPidEverSucceeded) {
                                    System.currentTimeMillis() + 15_000L
                                } else {
                                    Long.MAX_VALUE
                                }
                            }
                            for (pid in chunk) {
                                val (ok, rtt) = pollSingleAndEmit(pid, handshake.isCanProtocol, values, liveTimeoutMs)
                                if (ok) samplesInSec++
                                latencyAccumMs += rtt
                                rttCount++
                            }
                        } else {
                            multiPidEverSucceeded = true
                            multiPidFailStreak = 0
                            val decodedHexes = HashSet<String>(chunk.size)
                            for (item in decodedList) {
                                values[item.key] = item.value
                                decodedHexes += item.pidHex
                                samplesInSec++
                            }
                            emitDerivedAndSnapshot(values)
                            for (pid in chunk) {
                                if (pid.pidHex !in decodedHexes) {
                                    val (ok, rtt) = pollSingleAndEmit(pid, handshake.isCanProtocol, values, liveTimeoutMs)
                                    if (ok) samplesInSec++
                                    latencyAccumMs += rtt
                                    rttCount++
                                }
                            }
                        }
                    }
                }
            } else {
                val rpmPid = highTargets.firstOrNull { it.key == "rpm" }
                val others = highTargets.filter { it.key != "rpm" }

                if (others.isEmpty() && rpmPid != null) {
                    val (ok, rtt) = pollSingleAndEmit(rpmPid, handshake.isCanProtocol, values, liveTimeoutMs)
                    if (ok) samplesInSec++
                    latencyAccumMs += rtt
                    rttCount++
                } else {
                    val midRpmIndex = if (others.size >= 4) others.size / 2 else -1
                    if (rpmPid != null) {
                        val (okRpm, rttRpm) = pollSingleAndEmit(rpmPid, handshake.isCanProtocol, values, liveTimeoutMs)
                        if (okRpm) samplesInSec++
                        latencyAccumMs += rttRpm
                        rttCount++
                    }
                    for ((idx, pid) in others.withIndex()) {
                        if (!currentCoroutineContext().isActive || !transport.isConnected.value) break
                        if (rpmPid != null && midRpmIndex >= 1 && idx == midRpmIndex) {
                            val (okRpm, rttRpm) = pollSingleAndEmit(rpmPid, handshake.isCanProtocol, values, liveTimeoutMs)
                            if (okRpm) samplesInSec++
                            latencyAccumMs += rttRpm
                            rttCount++
                        }
                        val (ok, rtt) = pollSingleAndEmit(pid, handshake.isCanProtocol, values, liveTimeoutMs)
                        if (ok) samplesInSec++
                        latencyAccumMs += rtt
                        rttCount++
                    }
                }
            }

            if (dueSlowPid != null && currentCoroutineContext().isActive && transport.isConnected.value) {
                lastSlowPollMs[dueSlowPid.key] = System.currentTimeMillis()
                val (ok, rtt) = pollSingleAndEmit(dueSlowPid, handshake.isCanProtocol, values, liveTimeoutMs)
                if (ok) samplesInSec++
                latencyAccumMs += rtt
                rttCount++
            }

            val customNowMs = System.currentTimeMillis()
            val dueCustom = expandedKeys
                .mapNotNull { customPids[it] }
                .firstOrNull { custom ->
                    val cooldown = if (!custom.headerHex.isNullOrBlank()) 2500L else 450L
                    val last = lastCustomPollMs[custom.key] ?: 0L
                    last == 0L || (customNowMs - last) >= cooldown
                }

            if (dueCustom != null && currentCoroutineContext().isActive && transport.isConnected.value) {
                lastCustomPollMs[dueCustom.key] = customNowMs
                val t0 = System.currentTimeMillis()
                val res = executeCustomPidWithHeader(
                    custom = dueCustom,
                    isCanProtocol = handshake.isCanProtocol,
                    timeoutMs = liveTimeoutMs,
                    explicitEcmRxFallback = meterDiscovery.requiresExplicitEcmRxFilter
                )
                latencyAccumMs += (System.currentTimeMillis() - t0)
                rttCount++
                if (res is ObdCommandResult.Success) {
                    val decoded = FormulaEvaluator.decodeCustomPid(dueCustom, res)
                    if (decoded != null) {
                        values[dueCustom.key] = decoded
                        samplesInSec++
                    }
                }
            }

            if (meterCaps.hasAny && handshake.isCanProtocol && currentCoroutineContext().isActive && transport.isConnected.value) {
                val meterNowMs = System.currentTimeMillis()
                val needFuel = meterCaps.fuelLevel && "2F" !in discovered && (meterNowMs - lastMeterFuelMs >= 12_000L)
                val needOdo = meterCaps.odometer && (meterNowMs - lastMeterOdoMs >= 30_000L)
                val needClsSpd = meterCaps.clusterSpeed && "cluster_speed" in requested && (meterNowMs - lastMeterFastMs >= 450L)
                val needClsRpm = meterCaps.clusterRpm && "cluster_rpm" in requested && (meterNowMs - lastMeterFastMs >= 450L)
                val needClsCool = meterCaps.clusterCoolant && "cluster_coolant" in requested && (meterNowMs - lastMeterCoolantMs >= 2500L)

                if (needFuel || needOdo || needClsSpd || needClsRpm || needClsCool) {
                    val t0 = System.currentTimeMillis()
                    val polledCount = pollToyotaMeterBatch(
                        needFuel = needFuel,
                        needOdo = needOdo,
                        needClsSpd = needClsSpd,
                        needClsRpm = needClsRpm,
                        needClsCool = needClsCool,
                        values = values,
                        timeoutMs = maxOf(liveTimeoutMs, 800L),
                        explicitEcmRxFallback = meterDiscovery.requiresExplicitEcmRxFilter
                    )
                    if (needFuel) lastMeterFuelMs = meterNowMs
                    if (needOdo) lastMeterOdoMs = meterNowMs
                    if (needClsSpd || needClsRpm) lastMeterFastMs = meterNowMs
                    if (needClsCool) lastMeterCoolantMs = meterNowMs
                    samplesInSec += polledCount
                    latencyAccumMs += (System.currentTimeMillis() - t0)
                    rttCount++
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
            val effectiveDelayMs = when {
                backgroundIdleMode -> maxOf(profile.cycleDelayMs, 140L)
                else -> profile.cycleDelayMs
            }
            if (effectiveDelayMs > 0L) {
                delay(effectiveDelayMs)
            }
        }
    }

    private suspend fun pollToyotaMeterBatch(
        needFuel: Boolean,
        needOdo: Boolean,
        needClsSpd: Boolean,
        needClsRpm: Boolean,
        needClsCool: Boolean,
        values: MutableMap<String, Double>,
        timeoutMs: Long,
        explicitEcmRxFallback: Boolean = false
    ): Int {
        var count = 0
        try {
            client.execute("ATSH${ToyotaMeterCapabilities.HEADER_TX}", timeoutMs = 800L)
            client.execute("ATCRA${ToyotaMeterCapabilities.HEADER_RX}", timeoutMs = 800L)

            if (needFuel) {
                val res = client.execute("2129", timeoutMs = timeoutMs)
                if (res is ObdCommandResult.Success) {
                    ToyotaMeterProtocol.decodeFuelLiters(res.lines)?.let { liters ->
                        latestMeterFuelLiters = liters
                        _ecuFuelLiters.value = liters
                        val cap = vehicleProfile.tankCapacityLiters.coerceAtLeast(1.0)
                        values["fuel_level"] = ((liters / cap) * 100.0).coerceIn(0.0, 100.0)
                        count++
                    }
                }
            }
            if (needOdo) {
                val res = client.execute("21E1", timeoutMs = maxOf(timeoutMs, 850L))
                if (res is ObdCommandResult.Success) {
                    ToyotaMeterProtocol.decodeOdometerKm(res.lines)?.let { odoKm ->
                        val odoD = odoKm.toDouble()
                        values["ecu_odometer"] = odoD
                        _ecuOdometerKm.value = odoD
                        fuelTracker.syncOdometerFromEcu(odoD)
                        count++
                    }
                }
            }
            if (needClsSpd) {
                val res = client.execute("2121", timeoutMs = timeoutMs)
                if (res is ObdCommandResult.Success) {
                    ToyotaMeterProtocol.decodeClusterSpeedKmh(res.lines)?.let {
                        values["cluster_speed"] = it
                        count++
                    }
                }
            }
            if (needClsRpm) {
                val res = client.execute("2122", timeoutMs = timeoutMs)
                if (res is ObdCommandResult.Success) {
                    ToyotaMeterProtocol.decodeClusterRpm(res.lines)?.let {
                        values["cluster_rpm"] = it
                        count++
                    }
                }
            }
            if (needClsCool) {
                val res = client.execute("2123", timeoutMs = timeoutMs)
                if (res is ObdCommandResult.Success) {
                    ToyotaMeterProtocol.decodeClusterCoolantC(res.lines)?.let {
                        values["cluster_coolant"] = it
                        count++
                    }
                }
            }
        } finally {
            client.restoreDefaultCanHeaderAndFilter(
                explicitEcmRxFallback = explicitEcmRxFallback,
                timeoutMs = 800L
            )
        }
        return count
    }

    private suspend fun executeCustomPidWithHeader(
        custom: CustomPidDefinition,
        isCanProtocol: Boolean,
        timeoutMs: Long,
        explicitEcmRxFallback: Boolean = false
    ): ObdCommandResult {
        val rawHeader = custom.headerHex?.replace(" ", "")?.uppercase()?.takeIf { it.isNotBlank() }
        if (!isCanProtocol || rawHeader == null || rawHeader == "7DF") {
            return client.execute(custom.modeAndPidHex, timeoutMs = timeoutMs)
        }
        val replyFilter = computeStandardCanReplyHeader(rawHeader)
        return try {
            client.execute("ATSH$rawHeader", timeoutMs = 800L)
            if (replyFilter != null) {
                client.execute("ATCRA$replyFilter", timeoutMs = 800L)
            }
            client.execute(custom.modeAndPidHex, timeoutMs = maxOf(timeoutMs, 900L))
        } finally {
            client.restoreDefaultCanHeaderAndFilter(
                explicitEcmRxFallback = explicitEcmRxFallback,
                timeoutMs = 800L
            )
        }
    }

    private fun expandDependencies(requested: Set<String>, discovered: Set<String>): Set<String> {
        val fuelKeys = setOf(
            "kpl_instant", "kpl_road_avg", "kpl_moving_avg", "kpl_trip_avg",
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
                if ("03" in discovered) add("fuel_system_status")
                if ("06" in discovered) add("stft_b1")
                if ("07" in discovered) add("ltft_b1")
                if ("44" in discovered) add("lambda")
                else if ("34" in discovered) add("o2_b1s1_lambda")
                else if ("24" in discovered) add("o2_b1s1_wr_lambda")
                if ("2F" in discovered) add("fuel_level")
            }
            if ("ve_pct" in requested) {
                add("maf"); add("map"); add("rpm"); add("iat")
            }
            if ("trip_range" in requested || "trip_avg_speed" in requested) {
                if ("0D" in discovered) add("obd_speed")
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
        values: MutableMap<String, Double>,
        timeoutMs: Long = if (isCanProtocol) 650L else 1400L
    ): Pair<Boolean, Long> {
        val cmd = if (isCanProtocol) "01${pid.pidHex}1" else "01${pid.pidHex}"
        val t0 = System.currentTimeMillis()
        val res = client.execute(cmd, timeoutMs = timeoutMs)
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

    companion object {
        internal fun computeStandardCanReplyHeader(txHeaderHex: String): String? {
            if (txHeaderHex.length != 3) return null
            val tx = txHeaderHex.toIntOrNull(16) ?: return null
            if (tx in 0x700..0x7F7) {
                return "%03X".format(tx + 8)
            }
            return null
        }
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
        val effectiveMapKpa = mapKpa ?: FuelEconomyTracker.inferMapFromEngineLoadKpa(loadPct, baroKpa)
        val maf = values["maf"] ?: FuelEconomyTracker.estimateMafSpeedDensity(
            displacementLiters = vp.displacementLiters,
            vePct = vp.volumetricEfficiencyPct,
            rpm = rpm,
            mapKpa = effectiveMapKpa,
            iatCelsius = iat,
            baroKpa = baroKpa,
            engineLoadPct = loadPct
        )

        val relThrottle = values["rel_throttle"]
        val absThrottle = values["throttle"]
        val isManualOrEv = vp.transmission == TransmissionType.MANUAL ||
            vp.transmission == TransmissionType.SINGLE_SPEED_EV
        val minDfcoSpeed = if (isManualOrEv) 18.0 else 26.0
        val minDfcoRpm = if (isManualOrEv) 1500.0 else 1700.0
        val dfcoProbable = vp.fuelType != FuelType.ELECTRIC &&
            fuelCoastingMode == FuelCoastingMode.REALISTIC_DFCO &&
            speedKmh >= minDfcoSpeed &&
            (rpm ?: 0.0) >= minDfcoRpm &&
            (if (relThrottle != null) relThrottle <= 1.5 else (absThrottle != null && absThrottle <= 12.0)) &&
            (effectiveMapKpa != null && effectiveMapKpa <= baroKpa * 0.28)

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

        val meterFuelLiters = latestMeterFuelLiters
        if (meterFuelLiters != null) {
            val cap = vp.tankCapacityLiters.coerceAtLeast(1.0)
            values["fuel_level"] = ((meterFuelLiters / cap) * 100.0).coerceIn(0.0, 100.0)
        }

        val ecuFuelRate = values["fuel_rate"]
        val effectiveLambda = values["lambda"] ?: values["o2_b1s1_lambda"] ?: values["o2_b1s1_wr_lambda"]
        val fuelStatus = values["fuel_system_status"]
        if (maf != null || ecuFuelRate != null || vp.fuelType == FuelType.ELECTRIC) {
            val wheelKw = hp?.let { it * 0.7457 }
            val fuel = fuelTracker.update(
                profile = vp,
                speedKmh = speedKmh,
                rpm = rpm,
                mafGramsPerSec = values["maf"],
                mapKpa = mapKpa,
                iatCelsius = iat,
                lambda = effectiveLambda,
                throttlePct = absThrottle,
                relThrottlePct = relThrottle,
                baroKpa = baroKpa,
                coolantCelsius = values["coolant"],
                engineLoadPct = loadPct,
                stftPct = averageTrim(values["stft_b1"], values["stft_b2"]),
                ltftPct = averageTrim(values["ltft_b1"], values["ltft_b2"]),
                ecuFuelRateLph = ecuFuelRate,
                ecuFuelLevelPct = if (meterFuelLiters == null) values["fuel_level"] else null,
                ecuFuelLiters = meterFuelLiters,
                wheelPowerKw = wheelKw,
                fuelSystemStatus = fuelStatus,
                fuelCoastingMode = fuelCoastingMode
            )
            values["fuel_flow_lph"] = fuel.fuelFlowLitersPerHour
            values["kpl_instant"] = fuel.instantKpl
            values["kpl_trip_avg"] = fuel.tripAvgKpl
            values["kpl_road_avg"] = fuel.allTimeTotalAvgKpl.takeIf { it > 0.0 } ?: fuel.tripAvgKpl
            values["kpl_moving_avg"] = fuel.allTimeRoadAvgKpl.takeIf { it > 0.0 } ?: fuel.roadOnlyAvgKpl
            values["fuel_used_l"] = fuel.totalFuelUsedLiters
            values["fuel_remaining"] = fuel.estimatedRemainingFuelLiters
            val dteKm = fuel.estimatedRangeKm
            if (dteKm != null) values["range_dte"] = dteKm else values.remove("range_dte")
            values["efficiency_score"] = fuelTracker.computeEfficiencyDeltaScore(
                instantKpl = fuel.instantKpl,
                baselineAvgKpl = fuel.allTimeTotalAvgKpl.takeIf { it > 1.0 } ?: fuel.roadOnlyAvgKpl,
                throttlePct = absThrottle,
                engineLoadPct = loadPct,
                speedKmh = speedKmh,
                accelG = externalImuAccelG,
                dfcoActive = fuel.dfcoActive
            )
            _fuelMetrics.value = fuel
        } else {
            // Only expose DTE if real fuel economy history already exists (conservative cumulative-anchored model)
            fuelTracker.seedFromProfile(vp)
            val effectiveRemL = meterFuelLiters?.coerceIn(0.0, vp.tankCapacityLiters) ?: vp.estimatedRemainingFuelLiters
            values["fuel_remaining"] = effectiveRemL
            val allTimeTotalAvg = if (vp.allTimeTotalFuelLiters > 0.01) {
                (vp.allTimeTotalDistanceKm / vp.allTimeTotalFuelLiters).takeIf { it > 0.5 }
            } else null
            val estAvg = FuelEconomyTracker.computeConservativeDteKpl(
                allTimeTotalAvgKpl = allTimeTotalAvg,
                recentDteKpl = vp.recentDteKpl,
                remainingFuelLiters = effectiveRemL,
                tankCapacityLiters = vp.tankCapacityLiters
            )
            val dteKm = FuelEconomyTracker.computeEstimatedRangeKm(
                remainingFuelLiters = effectiveRemL,
                tankCapacityLiters = vp.tankCapacityLiters,
                dteKpl = estAvg
            )
            if (dteKm != null) {
                values["range_dte"] = dteKm
            } else {
                values.remove("range_dte")
            }
        }

        _liveValues.value = values.toMap()
    }
}
