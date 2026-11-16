package com.apexobd.core.scheduler

import com.apexobd.core.elm.Elm327Client
import com.apexobd.core.elm.ElmHandshakeInfo
import com.apexobd.core.elm.ObdCommandResult
import com.apexobd.core.formula.CustomPidDefinition
import com.apexobd.core.formula.FormulaEvaluator
import com.apexobd.core.protocol.ObdPidRegistry
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

/**
 * Estimates Horsepower and Torque using ECU Torque PIDs (0162/0163) when available,
 * falling back to MAF (g/s) volumetric estimation (~0.80 g/s per HP on gasoline).
 */
object PerformanceCalculator {
    fun estimateTorqueAndHp(
        rpm: Double?,
        mafGramsPerSec: Double?,
        actualTorquePct: Double?,
        refTorqueNm: Double?
    ): Pair<Double?, Double?> {
        if (rpm == null || rpm < 100.0) return null to null

        if (actualTorquePct != null && refTorqueNm != null && refTorqueNm > 0.0) {
            val torqueNm = (actualTorquePct / 100.0) * refTorqueNm
            val hp = (torqueNm * rpm) / 7127.0
            return torqueNm.coerceAtLeast(0.0) to hp.coerceAtLeast(0.0)
        }

        if (mafGramsPerSec != null) {
            val hp = mafGramsPerSec / 0.80
            val torqueNm = (hp * 7127.0) / rpm
            return torqueNm to hp
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
 * Priority-tiered polling loop that fetches active HUD PIDs, calculates derived metrics
 * (HP, Torque, Boost relative to Baro), and handles automatic reconnects.
 */
class ObdPollingEngine(
    private val transport: ObdTransport,
    private val scope: CoroutineScope
) {
    val client = Elm327Client(transport)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _supportedPids = MutableStateFlow<Set<String>>(emptySet())
    val supportedPids: StateFlow<Set<String>> = _supportedPids.asStateFlow()

    private val _liveValues = MutableStateFlow<Map<String, Double>>(emptyMap())
    val liveValues: StateFlow<Map<String, Double>> = _liveValues.asStateFlow()

    private val _pollingHz = MutableStateFlow(0)
    val pollingHz: StateFlow<Int> = _pollingHz.asStateFlow()

    private var activeSensorKeys: Set<String> = emptySet()
    private var customPids: Map<String, CustomPidDefinition> = emptyMap()
    private var pollJob: Job? = null

    fun setActiveSensors(keys: Set<String>) {
        activeSensorKeys = keys
    }

    fun setCustomPids(defs: List<CustomPidDefinition>) {
        customPids = defs.associateBy { it.key }
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
                    val handshake = client.initialize()
                    val discovered = ObdPidRegistry.discoverSupportedPids(client)
                    _supportedPids.value = discovered
                    _connectionState.value = ConnectionState.Connected(handshake, discovered.size)
                    attempt = 0

                    runPollingLoop(discovered)
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
    }

    private suspend fun runPollingLoop(discovered: Set<String>) {
        var cycle = 0
        var samplesInSec = 0
        var windowStart = System.currentTimeMillis()
        val values = _liveValues.value.toMutableMap()

        while (scope.isActive && transport.isConnected.value) {
            val requested = activeSensorKeys.ifEmpty {
                setOf("rpm", "obd_speed", "voltage", "iat", "coolant", "throttle", "maf", "timing_advance")
            }

            // Ensure RPM & MAF are polled whenever derived HP/Torque is displayed
            val expandedKeys = buildSet {
                addAll(requested)
                if ("hp" in requested || "torque" in requested) {
                    add("rpm")
                    add("maf")
                    if ("62" in discovered && "63" in discovered) {
                        add("actual_torque_pct")
                        add("ref_torque_nm")
                    }
                }
                if ("boost_bar" in requested) {
                    add("map")
                    add("baro")
                }
            }

            val standardTargets = expandedKeys
                .mapNotNull { ObdPidRegistry.findByKey(it) }
                .filter { discovered.isEmpty() || it.pidHex in discovered }
                .filter { it.defaultTier == PollTier.HIGH || cycle % 5 == 0 }

            for (pid in standardTargets) {
                if (!transport.isConnected.value) break
                val res = client.execute("01${pid.pidHex}")
                if (res is ObdCommandResult.Success) {
                    val decoded = ObdPidRegistry.decodeMode01Response(pid.pidHex, res.lines)
                    if (decoded != null) {
                        values[decoded.key] = decoded.value
                        samplesInSec++
                    }
                }
            }

            val customTargets = expandedKeys
                .mapNotNull { customPids[it] }
                .filter { cycle % 3 == 0 }

            for (custom in customTargets) {
                if (!transport.isConnected.value) break
                val res = client.execute(custom.modeAndPidHex)
                if (res is ObdCommandResult.Success) {
                    val decoded = FormulaEvaluator.decodeCustomPid(custom, res)
                    if (decoded != null) {
                        values[custom.key] = decoded
                        samplesInSec++
                    }
                }
            }

            // Update derived metrics
            val (tq, hp) = PerformanceCalculator.estimateTorqueAndHp(
                rpm = values["rpm"],
                mafGramsPerSec = values["maf"],
                actualTorquePct = values["actual_torque_pct"],
                refTorqueNm = values["ref_torque_nm"]
            )
            if (hp != null) values["hp"] = hp
            if (tq != null) values["torque"] = tq

            val mapKpa = values["map"]
            if (mapKpa != null) {
                val baroKpa = values["baro"] ?: 101.3
                values["boost_bar"] = (mapKpa - baroKpa) / 100.0
            }

            _liveValues.value = values.toMap()

            val now = System.currentTimeMillis()
            if (now - windowStart >= 1000L) {
                _pollingHz.value = max(0, samplesInSec)
                samplesInSec = 0
                windowStart = now
            }
            cycle++
            delay(20L)
        }
    }
}
