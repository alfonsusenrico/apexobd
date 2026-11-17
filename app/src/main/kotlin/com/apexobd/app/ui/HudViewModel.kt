package com.apexobd.app.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.apexobd.app.data.db.ApexDatabase
import com.apexobd.app.data.db.CustomPidEntity
import com.apexobd.app.data.db.HudPageEntity
import com.apexobd.app.data.db.HudWidgetEntity
import com.apexobd.app.data.db.RefuelLogEntity
import com.apexobd.app.data.db.TelemetrySampleEntity
import com.apexobd.app.data.db.TripSessionEntity
import com.apexobd.app.data.db.VehicleProfileEntity
import com.apexobd.app.data.db.WidgetDisplayType
import com.apexobd.app.data.prefs.AppPreferences
import com.apexobd.app.data.prefs.MapProvider
import com.apexobd.app.data.prefs.UserSettings
import com.apexobd.app.platform.AccelSensorTracker
import com.apexobd.app.platform.GpsSnapshot
import com.apexobd.app.platform.LocationTracker
import com.apexobd.app.platform.LoggingForegroundService
import com.apexobd.app.platform.OfflineMapManager
import com.apexobd.app.platform.TripExporter
import com.apexobd.app.platform.WifiNetworkBinder
import com.apexobd.core.formula.CustomPidDefinition
import com.apexobd.core.formula.TorqueCsvImporter
import com.apexobd.core.model.FuelType
import com.apexobd.core.model.SensorQuantity
import com.apexobd.core.model.UnitConverter
import com.apexobd.core.model.UnitPreferences
import com.apexobd.core.protocol.DerivedSensorCatalog
import com.apexobd.core.protocol.DiagnosticReport
import com.apexobd.core.protocol.DtcDecoder
import com.apexobd.core.protocol.EcuDiscoveryDumper
import com.apexobd.core.protocol.FuelMetricsSnapshot
import com.apexobd.core.protocol.ObdPidRegistry
import com.apexobd.core.scheduler.ConnectionState
import com.apexobd.core.scheduler.ObdPollingEngine
import com.apexobd.core.scheduler.PollingProfile
import com.apexobd.core.scheduler.Timer0100State
import com.apexobd.core.scheduler.Timer0100Tracker
import com.apexobd.core.transport.SimulatedObdTransport
import com.apexobd.core.transport.WifiTcpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class SensorCatalogItem(
    val key: String,
    val label: String,
    val shortLabel: String,
    val pidLabel: String,
    val unit: String,
    val quantity: SensorQuantity,
    val minValue: Double,
    val maxValue: Double,
    val isSpecialWidget: Boolean = false
)

data class HudPageWithWidgets(
    val page: HudPageEntity,
    val widgets: List<HudWidgetEntity>
)

enum class SensorCatalogGroup { ECU_MODE01, CALCULATED, PHONE_AND_WIDGETS, CUSTOM_PIDS }

data class GroupedSensorCatalogItem(
    val group: SensorCatalogGroup,
    val item: SensorCatalogItem
)

@OptIn(ExperimentalCoroutinesApi::class)
class HudViewModel(application: Application) : AndroidViewModel(application) {

    private val db = ApexDatabase.getInstance(application)
    private val vehicleDao = db.vehicleDao()
    private val hudDao = db.hudDao()
    private val customPidDao = db.customPidDao()
    private val tripDao = db.tripDao()
    private val prefs = AppPreferences(application)

    private val wifiBinder = WifiNetworkBinder(application)
    val locationTracker = LocationTracker(application)
    val accelTracker = AccelSensorTracker(application)
    val offlineMapManager = OfflineMapManager(application)

    private var engine: ObdPollingEngine? = null
    private var engineCollectorsJob: Job? = null
    private var loggingJob: Job? = null
    private val timer0100Tracker = Timer0100Tracker()
    private val transportMutex = Mutex()
    private var lastProfilePersistMs = 0L

    @Volatile
    private var suppressAutoConnect = false

    val settings: StateFlow<UserSettings> = prefs.settings.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        UserSettings()
    )

    val vehicles: StateFlow<List<VehicleProfileEntity>> = vehicleDao.observeVehicles()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val activeVehicle: StateFlow<VehicleProfileEntity?> = vehicles.map { list ->
        list.firstOrNull { it.isActive } ?: list.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val refuelLogs: StateFlow<List<RefuelLogEntity>> = activeVehicle
        .map { it?.id ?: -1L }
        .distinctUntilChanged()
        .flatMapLatest { vid ->
            if (vid > 0L) vehicleDao.observeRefuelsForVehicle(vid) else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val pagesWithWidgets: StateFlow<List<HudPageWithWidgets>> = combine(
        hudDao.observePages(),
        hudDao.observeAllWidgets()
    ) { pages, widgets ->
        val grouped = widgets.groupBy { it.pageId }
        pages.map { p -> HudPageWithWidgets(p, grouped[p.id].orEmpty().sortedBy { it.sortOrder }) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val customPids: StateFlow<List<CustomPidEntity>> = customPidDao.observeCustomPids()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val tripSessions: StateFlow<List<TripSessionEntity>> = tripDao.observeSessions()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _onHudScreen = MutableStateFlow(false)
    val onHudScreen: StateFlow<Boolean> = _onHudScreen.asStateFlow()

    private val _visiblePageIndex = MutableStateFlow(0)
    private val _chartKeys = MutableStateFlow<Set<String>>(emptySet())

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

    private val _historyBuffers = MutableStateFlow<Map<String, List<Double>>>(emptyMap())
    val historyBuffers: StateFlow<Map<String, List<Double>>> = _historyBuffers.asStateFlow()

    private val _pollingHz = MutableStateFlow(0)
    val pollingHz: StateFlow<Int> = _pollingHz.asStateFlow()

    private val _avgLatencyMs = MutableStateFlow(0)
    val avgLatencyMs: StateFlow<Int> = _avgLatencyMs.asStateFlow()

    private val _diagnosticReport = MutableStateFlow<DiagnosticReport?>(null)
    val diagnosticReport: StateFlow<DiagnosticReport?> = _diagnosticReport.asStateFlow()

    private val _diagnosticError = MutableStateFlow<String?>(null)
    val diagnosticError: StateFlow<String?> = _diagnosticError.asStateFlow()

    private val _diagnosticScanning = MutableStateFlow(false)
    val diagnosticScanning: StateFlow<Boolean> = _diagnosticScanning.asStateFlow()

    private val _explorerStatus = MutableStateFlow<String?>(null)
    val explorerStatus: StateFlow<String?> = _explorerStatus.asStateFlow()

    private val _vin = MutableStateFlow<String?>(null)
    val vin: StateFlow<String?> = _vin.asStateFlow()

    private val _timer0100 = MutableStateFlow(Timer0100State())
    val timer0100: StateFlow<Timer0100State> = _timer0100.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _ecuExplorerValues = MutableStateFlow<Map<String, Double>>(emptyMap())
    val ecuExplorerValues: StateFlow<Map<String, Double>> = _ecuExplorerValues.asStateFlow()

    val wifiConnected = wifiBinder.wifiConnected
    val cellularOnline = wifiBinder.cellularOnline

    private var activeSessionId: Long? = null
    private val mutableHistory = mutableMapOf<String, ArrayDeque<Double>>()

    init {
        wifiBinder.register()
        viewModelScope.launch {
            ApexDatabase.seedDefaults(hudDao, vehicleDao)
            offlineMapManager.refreshAll()
        }

        // Sync VehicleProfile into polling engine
        viewModelScope.launch {
            activeVehicle.collect { v ->
                if (v != null) {
                    engine?.setVehicleProfile(v.toCoreModel())
                }
            }
        }

        // Sync Custom PIDs into polling engine
        viewModelScope.launch {
            customPids.collect { list ->
                engine?.setCustomPids(
                    list.map {
                        CustomPidDefinition(
                            key = it.key,
                            name = it.name,
                            shortName = it.shortName,
                            modeAndPidHex = it.modeAndPidHex,
                            formula = it.formula,
                            minValue = it.minValue,
                            maxValue = it.maxValue,
                            unit = it.unit,
                            headerHex = it.headerHex
                        )
                    }
                )
            }
        }

        // Start/stop OBD + Sensors + Cellular keepalive strictly according to active HUD or recording state
        viewModelScope.launch {
            combine(_onHudScreen, _isRecording, settings) { onHud, recording, st ->
                Triple(onHud || recording, recording, st)
            }.collect { (shouldPoll, _, st) ->
                engine?.setFuelCalculationPaused(st.pauseFuelCalculation)
                engine?.setPollingProfile(st.pollingProfile)
                wifiBinder.setKeepCellularActive(shouldPoll && !st.useSimulator && st.dualCellularKeepalive)

                if (shouldPoll) {
                    accelTracker.start()
                    if (!suppressAutoConnect && engine == null) {
                        reconnectTransport(st)
                    }
                } else {
                    accelTracker.stop()
                    locationTracker.stopUpdates()
                    flushActiveVehicleStats()
                    transportMutex.withLock {
                        engine?.stop()
                        engine = null
                        engineCollectorsJob?.cancel()
                    }
                    _connectionState.value = ConnectionState.Disconnected
                    _pollingHz.value = 0
                }
            }
        }

        // Visible page only polling set (plus logged baseline PIDs while Run Recording is active)
        viewModelScope.launch {
            combine(pagesWithWidgets, _visiblePageIndex, _isRecording, settings, _onHudScreen) { pages, pageIdx, recording, st, onHud ->
                val visibleWidgets = if (onHud) pages.getOrNull(pageIdx)?.widgets.orEmpty() else emptyList()
                _chartKeys.value = visibleWidgets
                    .filter { it.displayType == WidgetDisplayType.CHART.name }
                    .map { it.sensorKey }
                    .toSet()

                val activeKeys = buildSet {
                    visibleWidgets.forEach { widget ->
                        add(widget.sensorKey)
                        if (widget.sensorKey == "fuel_ctrl") add("kpl_road_avg")
                    }
                    if (recording) {
                        addAll(listOf("rpm", "obd_speed", "throttle", "rel_throttle", "maf", "map", "iat", "baro", "engine_load", "stft_b1", "ltft_b1", "coolant", "voltage", "timing_advance", "kpl_road_avg"))
                    }
                }
                val needsHighFreqGps = recording || (onHud && st.pollingProfile != PollingProfile.ECO_BATTERY && activeKeys.any { it in setOf("gps_speed", "minimap", "timer_0100") })
                if (onHud || recording) {
                    locationTracker.startUpdates(highFrequency = needsHighFreqGps)
                }
                activeKeys
            }.collect { keys ->
                engine?.setActiveSensors(keys)
            }
        }
    }

    fun setOnHudScreen(active: Boolean) {
        _onHudScreen.value = active
    }

    fun setVisiblePageIndex(index: Int) {
        _visiblePageIndex.value = index.coerceAtLeast(0)
    }

    fun forceConnectAndDiscoverForExplorer() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _explorerStatus.value = "Checking OBD connection..."
                val st = settings.value
                if (engine == null) {
                    reconnectTransport(st)
                    var waited = 0
                    while (_connectionState.value !is ConnectionState.Connected && waited < 20) {
                        if (_connectionState.value is ConnectionState.Reconnecting) break
                        delay(100L)
                        waited++
                    }
                }
                if (_connectionState.value !is ConnectionState.Connected) {
                    _explorerStatus.value = "Adapter offline (${st.wifiHost}:${st.wifiPort}) · Showing standard PIDs"
                    return@launch
                }
                _explorerStatus.value = null
                val explorerKeys = ObdPidRegistry.standardPids.map { it.key }.toSet()
                engine?.setActiveSensors(explorerKeys)
            } catch (t: Throwable) {
                _explorerStatus.value = "ECU explorer offline: ${t.message ?: "not connected"}"
            }
        }
    }

    fun restoreVisibleCanvasPolling() {
        if (!_onHudScreen.value && !_isRecording.value) {
            viewModelScope.launch {
                transportMutex.withLock {
                    engineCollectorsJob?.cancel()
                    engine?.stop()
                    engine = null
                }
                _connectionState.value = ConnectionState.Disconnected
                _pollingHz.value = 0
            }
        } else {
            val visible = pagesWithWidgets.value.getOrNull(_visiblePageIndex.value)?.widgets.orEmpty()
            engine?.setActiveSensors(visible.map { it.sensorKey }.toSet())
        }
    }

    fun runDeepEcuDump(host: String = settings.value.wifiHost, port: Int = settings.value.wifiPort) {
        suppressAutoConnect = true
        viewModelScope.launch(Dispatchers.IO) {
            val dumpText = transportMutex.withLock {
                engine?.stop()
                engine = null
                delay(200L)
                val transport = WifiTcpTransport(
                    host = host,
                    port = port,
                    socketFactoryProvider = { wifiBinder.socketFactory() }
                )
                try {
                    transport.connect()
                    EcuDiscoveryDumper.runFullReadOnlyScan(transport)
                } catch (t: Throwable) {
                    "DEEP ECU SCAN ERROR: ${t.message}\n${t.stackTraceToString()}"
                } finally {
                    runCatching { transport.disconnect() }
                }
            }
            java.io.File(getApplication<Application>().filesDir, "ecu_dump.txt").writeText(dumpText)
            suppressAutoConnect = false
            prefs.updateWifiEndpoint(host, port)
            prefs.setUseSimulator(false)
            if (_onHudScreen.value || _isRecording.value) {
                reconnectTransport(settings.value.copy(wifiHost = host, wifiPort = port, useSimulator = false))
            }
        }
    }

    private suspend fun reconnectTransport(s: UserSettings) = transportMutex.withLock {
        engineCollectorsJob?.cancel()
        engine?.stop()

        val transport = if (s.useSimulator) {
            SimulatedObdTransport()
        } else {
            WifiTcpTransport(
                host = s.wifiHost,
                port = s.wifiPort,
                socketFactoryProvider = { wifiBinder.socketFactory() }
            )
        }
        val newEngine = ObdPollingEngine(transport, viewModelScope)
        newEngine.setPollingProfile(s.pollingProfile)
        newEngine.setFuelCalculationPaused(s.pauseFuelCalculation)
        activeVehicle.value?.let { newEngine.setVehicleProfile(it.toCoreModel(), resetFuelSession = false) }
        newEngine.setCustomPids(
            customPids.value.map {
                CustomPidDefinition(it.key, it.name, it.shortName, it.modeAndPidHex, it.formula, it.minValue, it.maxValue, it.unit, it.headerHex)
            }
        )
        engine = newEngine

        val visible = pagesWithWidgets.value.getOrNull(_visiblePageIndex.value)?.widgets.orEmpty()
        newEngine.setActiveSensors(visible.map { it.sensorKey }.toSet())

        engineCollectorsJob = viewModelScope.launch {
            launch { newEngine.connectionState.collect { _connectionState.value = it } }
            launch { newEngine.supportedPids.collect { _supportedPids.value = it } }
            launch { newEngine.availableSensorKeys.collect { _availableSensorKeys.value = it } }
            launch { newEngine.pollingHz.collect { _pollingHz.value = it } }
            launch { newEngine.avgLatencyMs.collect { _avgLatencyMs.value = it } }
            launch {
                newEngine.fuelMetrics.collect { fm ->
                    _fuelMetrics.value = fm
                    if (fm != null) {
                        val now = System.currentTimeMillis()
                        if (now - lastProfilePersistMs > 5000L) {
                            lastProfilePersistMs = now
                            persistFuelMetricsToProfile(fm)
                        }
                    }
                }
            }
            launch {
                newEngine.liveValues.collect { map ->
                    val obdSpd = map["obd_speed"] ?: 0.0
                    locationTracker.updateSimulatedStep(obdSpd)
                    val gps = locationTracker.gps.value
                    val imuG = accelTracker.accelG.value
                    val speedGps = if (gps.hasRealFix) gps.speedKmh else obdSpd
                    newEngine.updateExternalTelemetry(speedGps, imuG, gps.roadGradePct)

                    val merged = map.toMutableMap()
                    merged["gps_speed"] = speedGps
                    merged["accel_g"] = imuG
                    _liveValues.value = merged
                    _ecuExplorerValues.value = merged
                    appendChartHistoryOnly(merged)

                    if ("timer_0100" in pagesWithWidgets.value.getOrNull(_visiblePageIndex.value)?.widgets.orEmpty().map { it.sensorKey } || _isRecording.value) {
                        val nextTimer = timer0100Tracker.onSpeedSample(if (obdSpd > 0.5) obdSpd else speedGps)
                        _timer0100.value = nextTimer
                        updateBest0100IfBetter(nextTimer.bestRunSec)
                    }
                }
            }
        }
        newEngine.start()
    }

    private suspend fun persistFuelMetricsToProfile(fm: FuelMetricsSnapshot) {
        val current = activeVehicle.value ?: return
        vehicleDao.updateVehicle(
            current.copy(
                allTimeOdometerKm = fm.allTimeOdometerKm,
                allTimeRoadDistanceKm = fm.allTimeRoadDistanceKm,
                allTimeRoadFuelLiters = fm.allTimeRoadFuelLiters,
                estimatedRemainingFuelLiters = fm.estimatedRemainingFuelLiters
            )
        )
    }

    private suspend fun flushActiveVehicleStats() {
        _fuelMetrics.value?.let { persistFuelMetricsToProfile(it) }
    }

    private fun updateBest0100IfBetter(candidate: Double?) {
        if (candidate == null) return
        val cur = activeVehicle.value ?: return
        val existingBest = cur.best0100Sec
        if (existingBest == null || candidate < existingBest) {
            viewModelScope.launch(Dispatchers.IO) {
                vehicleDao.updateVehicle(cur.copy(best0100Sec = candidate))
            }
        }
    }

    private fun appendChartHistoryOnly(values: Map<String, Double>) {
        val chartTargets = _chartKeys.value
        if (chartTargets.isEmpty()) return
        val snapshot = mutableMapOf<String, List<Double>>()
        for (k in chartTargets) {
            val v = values[k] ?: continue
            val dq = mutableHistory.getOrPut(k) { ArrayDeque(30) }
            if (dq.size >= 30) dq.removeFirst()
            dq.addLast(v)
            snapshot[k] = dq.toList()
        }
        if (snapshot.isNotEmpty()) {
            _historyBuffers.value = snapshot
        }
    }

    fun scanDiagnostics(onDone: (() -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            _diagnosticScanning.value = true
            _diagnosticError.value = null
            try {
                val st = settings.value
                if (engine == null || _connectionState.value is ConnectionState.Disconnected || _connectionState.value is ConnectionState.Reconnecting) {
                    reconnectTransport(st)
                }
                var waited = 0
                while (_connectionState.value !is ConnectionState.Connected && waited < 22) {
                    if (_connectionState.value is ConnectionState.Reconnecting) break
                    delay(100L)
                    waited++
                }
                val client = engine?.client
                if (client == null || _connectionState.value !is ConnectionState.Connected) {
                    _diagnosticError.value =
                        "Not connected to OBD adapter (${st.wifiHost}:${st.wifiPort}). Connect to adapter Wi-Fi or turn on Demo Simulator in Settings."
                    return@launch
                }
                val result = DtcDecoder.scanAll(client)
                _diagnosticReport.value = result
                if (_vin.value == null) _vin.value = DtcDecoder.readVin(client)
                if (result.confirmed.isEmpty() && result.pending.isEmpty() && result.permanent.isEmpty() && result.readiness == null) {
                    _diagnosticError.value = "ECU did not respond to diagnostic query. Ensure ignition is ON."
                }
            } catch (t: Throwable) {
                _diagnosticError.value = "Diagnostic scan failed: ${t.message ?: "adapter disconnected"}"
            } finally {
                _diagnosticScanning.value = false
                if (!_onHudScreen.value && !_isRecording.value && _connectionState.value !is ConnectionState.Connected) {
                    transportMutex.withLock {
                        engineCollectorsJob?.cancel()
                        engine?.stop()
                        engine = null
                    }
                    _connectionState.value = ConnectionState.Disconnected
                }
                withContext(Dispatchers.Main) {
                    onDone?.invoke()
                }
            }
        }
    }

    fun clearDtcs() {
        val client = engine?.client
        if (client == null || _connectionState.value !is ConnectionState.Connected) {
            _diagnosticError.value = "Cannot clear DTCs while adapter is offline."
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _diagnosticError.value = null
                if (DtcDecoder.clearDtcs(client)) {
                    _diagnosticReport.value = DtcDecoder.scanAll(client)
                } else {
                    _diagnosticError.value = "Mode 04 clear command was rejected or timed out."
                }
            } catch (t: Throwable) {
                _diagnosticError.value = "Clear DTCs failed: ${t.message ?: "adapter disconnected"}"
            }
        }
    }

    fun reset0100Best() {
        _timer0100.value = timer0100Tracker.resetBest()
    }

    fun toggleRecording() {
        if (_isRecording.value) stopRecording() else startRecording()
    }

    private fun startRecording() {
        if (_isRecording.value) return
        _isRecording.value = true
        LoggingForegroundService.start(getApplication())

        loggingJob = viewModelScope.launch(Dispatchers.IO) {
            val vehicleId = activeVehicle.value?.id ?: 1L
            val startMs = System.currentTimeMillis()
            val startFuel = _fuelMetrics.value?.totalFuelUsedLiters ?: 0.0
            val sessionId = tripDao.insertSession(TripSessionEntity(startedAtMs = startMs, vehicleId = vehicleId))
            activeSessionId = sessionId

            var distanceKm = 0.0
            var maxSpeed = 0.0
            var peakRpm = 0.0
            var totalCount = 0
            var prevGps: GpsSnapshot? = null
            val batch = ArrayList<TelemetrySampleEntity>(10)

            try {
                while (_isRecording.value) {
                    val gps = locationTracker.gps.value
                    val vals = _liveValues.value
                    val obdSpd = vals["obd_speed"] ?: 0.0
                    val speed = if (gps.hasRealFix && gps.speedKmh > obdSpd * 0.2) gps.speedKmh else obdSpd
                    val rpm = vals["rpm"] ?: 0.0
                    if (speed > maxSpeed) maxSpeed = speed
                    if (rpm > peakRpm) peakRpm = rpm

                    val prev = prevGps
                    if (prev != null) {
                        distanceKm += haversineKm(prev.latitude, prev.longitude, gps.latitude, gps.longitude)
                    }
                    prevGps = gps

                    val curFuel = ((_fuelMetrics.value?.totalFuelUsedLiters ?: startFuel) - startFuel).coerceAtLeast(0.0)
                    val curAvg = if (curFuel > 0.005) (distanceKm / curFuel).coerceIn(0.0, 99.9) else 0.0

                    batch += TelemetrySampleEntity(
                        sessionId = sessionId,
                        timestampMs = System.currentTimeMillis(),
                        latitude = gps.latitude,
                        longitude = gps.longitude,
                        gpsSpeedKmh = speed,
                        payloadJson = TripExporter.encodePayload(vals)
                    )
                    totalCount++

                    if (batch.size >= 10) {
                        tripDao.insertSamples(batch.toList())
                        batch.clear()
                        tripDao.updateSession(
                            TripSessionEntity(
                                id = sessionId,
                                startedAtMs = startMs,
                                endedAtMs = null,
                                distanceKm = distanceKm,
                                maxSpeedKmh = maxSpeed,
                                peakRpm = peakRpm,
                                best0100Sec = _timer0100.value.bestRunSec,
                                sampleCount = totalCount,
                                vehicleId = vehicleId,
                                fuelUsedLiters = curFuel,
                                avgKpl = curAvg
                            )
                        )
                    }
                    delay(250L)
                }
            } finally {
                if (batch.isNotEmpty()) {
                    tripDao.insertSamples(batch.toList())
                    batch.clear()
                }
                val finalFuel = ((_fuelMetrics.value?.totalFuelUsedLiters ?: startFuel) - startFuel).coerceAtLeast(0.0)
                val finalAvg = if (finalFuel > 0.005) (distanceKm / finalFuel).coerceIn(0.0, 99.9) else 0.0
                tripDao.updateSession(
                    TripSessionEntity(
                        id = sessionId,
                        startedAtMs = startMs,
                        endedAtMs = System.currentTimeMillis(),
                        distanceKm = distanceKm,
                        maxSpeedKmh = maxSpeed,
                        peakRpm = peakRpm,
                        best0100Sec = _timer0100.value.bestRunSec,
                        sampleCount = totalCount,
                        vehicleId = vehicleId,
                        fuelUsedLiters = finalFuel,
                        avgKpl = finalAvg
                    )
                )
                flushActiveVehicleStats()
            }
        }
    }

    private fun stopRecording() {
        _isRecording.value = false
        LoggingForegroundService.stop(getApplication())
        activeSessionId = null
    }

    fun exportSession(session: TripSessionEntity, asJson: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val samples = tripDao.getSamplesForSession(session.id)
            val content = if (asJson) TripExporter.toJson(session, samples) else TripExporter.toCsv(session, samples)
            val ext = if (asJson) "json" else "csv"
            val mime = if (asJson) "application/json" else "text/csv"
            TripExporter.shareFile(getApplication(), "apex_trip_${session.id}.$ext", mime, content)
        }
    }

    suspend fun getSamplesForSession(sessionId: Long): List<TelemetrySampleEntity> =
        tripDao.getSamplesForSession(sessionId)

    // --- Vehicle Profiles & Odometer / Tank Management ---
    fun selectVehicle(vehicleId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            vehicleDao.setActiveVehicle(vehicleId)
        }
    }

    fun saveVehicleProfile(
        existing: VehicleProfileEntity?,
        name: String,
        displacementLiters: Double,
        weightKg: Double,
        fuelType: FuelType,
        tankCapacityDisplayUnit: Double,
        estRemainingDisplayUnit: Double,
        odometerDisplayUnit: Double,
        redlineRpm: Int
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val st = settings.value
            val unitPrefs = st.toUnitPreferences()
            val tankL = toInternalVolume(tankCapacityDisplayUnit, fuelType, unitPrefs)
            val remL = toInternalVolume(estRemainingDisplayUnit, fuelType, unitPrefs).coerceIn(0.0, tankL)
            val odoKm = toInternalDistanceKm(odometerDisplayUnit, unitPrefs)

            val entity = (existing ?: VehicleProfileEntity(isActive = true)).copy(
                name = name.trim().ifBlank { "My Vehicle" },
                displacementLiters = displacementLiters.coerceIn(0.0, 12.0),
                weightKg = weightKg.coerceIn(400.0, 8000.0),
                fuelType = fuelType.name,
                tankCapacityLiters = tankL.coerceAtLeast(1.0),
                estimatedRemainingFuelLiters = remL,
                redlineRpm = redlineRpm.coerceIn(2500, 14000),
                allTimeOdometerKm = odoKm.coerceAtLeast(0.0)
            )
            if (existing == null) {
                val id = vehicleDao.insertVehicle(entity)
                vehicleDao.setActiveVehicle(id)
            } else {
                vehicleDao.updateVehicle(entity)
                engine?.fuelTracker?.syncManualProfileCounters(entity.toCoreModel())
                engine?.setVehicleProfile(entity.toCoreModel())
            }
        }
    }

    // --- Refuel Checkpoints (Completely Independent of OBD All-Time & Run Tracking) ---
    fun addRefuelCheckpoint(
        volumeDisplayUnit: Double,
        pricePaid: Double,
        currencyCode: String,
        ronValue: Int?,
        fuelName: String?,
        odometerDisplayUnit: Double
    ) {
        val vehicle = activeVehicle.value ?: return
        if (volumeDisplayUnit <= 0.0) return

        viewModelScope.launch(Dispatchers.IO) {
            val st = settings.value
            val unitPrefs = st.toUnitPreferences()
            val fuelType = runCatching { FuelType.valueOf(vehicle.fuelType) }.getOrDefault(FuelType.GASOLINE)

            val volumeL = toInternalVolume(volumeDisplayUnit, fuelType, unitPrefs)
            val odoKm = toInternalDistanceKm(odometerDisplayUnit, unitPrefs)

            val prev = vehicleDao.getLatestRefuelForVehicle(vehicle.id)
            val cycleDist = prev?.let { (odoKm - it.odometerKm).takeIf { d -> d > 0.0 } }
            val cycleAvgKpl = if (cycleDist != null && volumeL > 0.0) (cycleDist / volumeL).coerceIn(0.0, 99.9) else null

            vehicleDao.insertRefuel(
                RefuelLogEntity(
                    vehicleId = vehicle.id,
                    timestampMs = System.currentTimeMillis(),
                    odometerKm = odoKm,
                    volumeLiters = volumeL,
                    pricePaid = pricePaid.coerceAtLeast(0.0),
                    currencyCode = currencyCode.trim().uppercase().ifBlank { st.currencyCode },
                    ronValue = ronValue?.takeIf { fuelType == FuelType.GASOLINE && it > 0 },
                    fuelName = fuelName?.trim()?.takeIf { it.isNotBlank() },
                    cycleDistanceKm = cycleDist,
                    cycleAvgKpl = cycleAvgKpl
                )
            )

            val newRemaining = (vehicle.estimatedRemainingFuelLiters + volumeL).coerceIn(0.0, vehicle.tankCapacityLiters)
            val newOdo = maxOf(vehicle.allTimeOdometerKm, odoKm)
            val updated = vehicle.copy(
                allTimeOdometerKm = newOdo,
                estimatedRemainingFuelLiters = newRemaining
            )
            vehicleDao.updateVehicle(updated)
            engine?.fuelTracker?.applyRefuelAdded(volumeL, vehicle.tankCapacityLiters)
            engine?.setVehicleProfile(updated.toCoreModel())
        }
    }

    fun deleteRefuelCheckpoint(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { vehicleDao.deleteRefuel(id) }
    }

    // --- Canvas Page & Widget Management ---
    fun addBlankPage(title: String, onCreated: (Int) -> Unit) {
        viewModelScope.launch {
            val currentCount = pagesWithWidgets.value.size
            hudDao.insertPage(HudPageEntity(title = title.ifBlank { "Page ${currentCount + 1}" }, sortOrder = currentCount))
            onCreated(currentCount)
        }
    }

    fun deletePage(pageId: Long) {
        viewModelScope.launch {
            if (pagesWithWidgets.value.size > 1) hudDao.deletePage(pageId)
        }
    }

    fun clearPageWidgets(pageId: Long) {
        viewModelScope.launch {
            hudDao.deleteWidgetsForPage(pageId)
        }
    }

    fun addWidget(pageId: Long, sensorKey: String, displayType: WidgetDisplayType, spanCols: Int, spanRows: Int) {
        viewModelScope.launch {
            val existing = hudDao.getWidgetsForPage(pageId)
            val idx = existing.size
            val xFrac = ((idx % 3) * 0.28f).coerceAtMost(0.75f)
            val yFrac = (((idx / 3) % 3) * 0.28f).coerceAtMost(0.75f)
            hudDao.insertWidget(
                HudWidgetEntity(
                    pageId = pageId,
                    sensorKey = sensorKey,
                    displayType = displayType.name,
                    spanCols = spanCols,
                    spanRows = spanRows,
                    sortOrder = idx,
                    freeformXFraction = xFrac,
                    freeformYFraction = yFrac
                )
            )
        }
    }

    fun cycleWidgetDisplayType(widget: HudWidgetEntity) {
        val cycle = listOf(
            WidgetDisplayType.ANALOG_DIAL,
            WidgetDisplayType.SEMI_DIGITAL,
            WidgetDisplayType.DIGITAL,
            WidgetDisplayType.BAR,
            WidgetDisplayType.CHART
        )
        val cur = runCatching { WidgetDisplayType.valueOf(widget.displayType) }.getOrNull() ?: return
        val idx = cycle.indexOf(cur)
        if (idx == -1) return
        val next = cycle[(idx + 1) % cycle.size]
        viewModelScope.launch { hudDao.updateWidget(widget.copy(displayType = next.name)) }
    }

    fun cycleWidgetSize(widget: HudWidgetEntity) {
        val order = listOf(1 to 1, 2 to 1, 2 to 2, 3 to 2, 4 to 1)
        val idx = order.indexOf(widget.spanCols to widget.spanRows)
        val next = order[(idx + 1) % order.size]
        viewModelScope.launch {
            hudDao.updateWidget(widget.copy(spanCols = next.first, spanRows = next.second))
        }
    }

    fun updateWidgetFreeformPosition(widget: HudWidgetEntity, xFrac: Float, yFrac: Float) {
        viewModelScope.launch {
            hudDao.updateWidget(
                widget.copy(
                    freeformXFraction = xFrac.coerceIn(0f, 0.85f),
                    freeformYFraction = yFrac.coerceIn(0f, 0.85f)
                )
            )
        }
    }

    fun moveWidget(pageId: Long, widgetId: Long, delta: Int) {
        viewModelScope.launch {
            val list = hudDao.getWidgetsForPage(pageId).toMutableList()
            val idx = list.indexOfFirst { it.id == widgetId }
            val target = idx + delta
            if (idx == -1 || target !in list.indices) return@launch
            val item = list.removeAt(idx)
            list.add(target, item)
            hudDao.updateWidgets(list.mapIndexed { i, w -> w.copy(sortOrder = i) })
        }
    }

    fun deleteWidget(widgetId: Long) {
        viewModelScope.launch { hudDao.deleteWidget(widgetId) }
    }

    // --- Settings Actions ---
    fun toggleGridSnap() {
        viewModelScope.launch { prefs.setGridSnapEnabled(!settings.value.gridSnapEnabled) }
    }

    fun togglePauseFuelCalculation() {
        val next = !settings.value.pauseFuelCalculation
        viewModelScope.launch {
            prefs.setPauseFuelCalculation(next)
            engine?.setFuelCalculationPaused(next)
        }
    }

    fun toggleHudMirror() {
        viewModelScope.launch { prefs.setHudMirror(!settings.value.hudMirror) }
    }

    fun updateConnectionSettings(host: String, port: Int, useSim: Boolean, dualCell: Boolean) {
        viewModelScope.launch {
            prefs.updateWifiEndpoint(host, port)
            prefs.setUseSimulator(useSim)
            prefs.setDualCellularKeepalive(dualCell)
            if (_onHudScreen.value || _isRecording.value) {
                reconnectTransport(settings.value.copy(wifiHost = host, wifiPort = port, useSimulator = useSim, dualCellularKeepalive = dualCell))
            }
        }
    }

    fun setPollingProfile(profile: PollingProfile) {
        viewModelScope.launch {
            prefs.setPollingProfile(profile)
            engine?.setPollingProfile(profile)
        }
    }

    fun setMapProvider(provider: MapProvider) {
        viewModelScope.launch { prefs.setMapProvider(provider) }
    }

    fun setActivePmtilesPath(path: String?) {
        viewModelScope.launch { prefs.setActivePmtilesPath(path) }
    }

    fun setCurrencyCode(code: String) {
        viewModelScope.launch { prefs.setCurrencyCode(code) }
    }

    fun updateUnits(prefsUpdate: UnitPreferences) {
        viewModelScope.launch {
            prefs.updateUnits(
                speed = prefsUpdate.speed,
                temp = prefsUpdate.temperature,
                pressure = prefsUpdate.pressure,
                fuelEconomy = prefsUpdate.fuelEconomy,
                volume = prefsUpdate.volume,
                airflow = prefsUpdate.airflow,
                power = prefsUpdate.power,
                torque = prefsUpdate.torque
            )
        }
    }

    fun importTorqueCsv(csv: String) {
        val parsed = TorqueCsvImporter.parseCsv(csv)
        if (parsed.isEmpty()) return
        viewModelScope.launch {
            customPidDao.upsertAll(
                parsed.map {
                    CustomPidEntity(it.key, it.name, it.shortName, it.modeAndPidHex, it.formula, it.minValue, it.maxValue, it.unit, it.headerHex)
                }
            )
        }
    }

    fun addManualCustomPid(name: String, modePidHex: String, formula: String, unit: String) {
        val clean = modePidHex.replace(" ", "").uppercase()
        if (name.isBlank() || clean.length < 4 || formula.isBlank()) return
        val key = "custom_${clean.lowercase()}"
        viewModelScope.launch {
            customPidDao.upsertAll(
                listOf(
                    CustomPidEntity(key, name.trim(), name.trim().take(10), clean, formula.trim(), 0.0, 200.0, unit.trim())
                )
            )
        }
    }

    fun availableGroupedCatalog(): List<GroupedSensorCatalogItem> {
        val supported = _supportedPids.value
        val out = mutableListOf<GroupedSensorCatalogItem>()

        ObdPidRegistry.standardPids
            .filter { supported.isEmpty() || it.pidHex in supported }
            .forEach {
                out += GroupedSensorCatalogItem(
                    SensorCatalogGroup.ECU_MODE01,
                    SensorCatalogItem(it.key, it.name, it.shortName, "01${it.pidHex}", it.unit, it.quantity, it.minValue, it.maxValue)
                )
            }

        DerivedSensorCatalog.all
            .filter { DerivedSensorCatalog.isAvailable(it, supported) }
            .filter { it.key !in setOf("gps_speed", "accel_g") }
            .forEach {
                out += GroupedSensorCatalogItem(
                    SensorCatalogGroup.CALCULATED,
                    SensorCatalogItem(it.key, it.name, it.shortLabelForCatalog(), it.sourceLabel, it.defaultUnit, it.quantity, it.minValue, it.maxValue)
                )
            }

        listOf(
            SensorCatalogItem("gps_speed", "GPS Speed", "GPS Spd", "GPS", "km/h", SensorQuantity.SPEED, 0.0, 240.0),
            SensorCatalogItem("accel_g", "Acceleration G-Force", "Accel", "IMU", "g", SensorQuantity.RAW, -1.5, 1.5),
            SensorCatalogItem("minimap", "Run & Nav Mini-Map", "Mini-Map", "MAP", "", SensorQuantity.RAW, 0.0, 1.0, isSpecialWidget = true),
            SensorCatalogItem("fuel_ctrl", "All Time KPL + Idle Pause", "KPL Ctrl", "FUEL", "km/L", SensorQuantity.FUEL_ECONOMY_KPL, 0.0, 40.0, isSpecialWidget = true),
            SensorCatalogItem("dtc_checker", "DTC Checker & Clear (03/07/04)", "DTC", "03/04", "", SensorQuantity.RAW, 0.0, 1.0, isSpecialWidget = true),
            SensorCatalogItem("timer_0100", "0–100 km/h Performance Timer", "0–100", "PERF", "s", SensorQuantity.RAW, 0.0, 20.0, isSpecialWidget = true),
            SensorCatalogItem("data_logger", "Run Logger Quick Toggle", "Run Log", "LOG", "", SensorQuantity.RAW, 0.0, 1.0, isSpecialWidget = true)
        ).forEach {
            out += GroupedSensorCatalogItem(SensorCatalogGroup.PHONE_AND_WIDGETS, it)
        }

        customPids.value.forEach {
            out += GroupedSensorCatalogItem(
                SensorCatalogGroup.CUSTOM_PIDS,
                SensorCatalogItem(it.key, it.name, it.shortName, it.modeAndPidHex, it.unit, SensorQuantity.RAW, it.minValue, it.maxValue)
            )
        }
        return out
    }

    private fun com.apexobd.core.protocol.DerivedSensorSpec.shortLabelForCatalog() = this.shortName

    fun catalogItemFor(key: String): SensorCatalogItem {
        return availableGroupedCatalog().firstOrNull { it.item.key == key }?.item
            ?: ObdPidRegistry.findByKey(key)?.let {
                SensorCatalogItem(it.key, it.name, it.shortName, "01${it.pidHex}", it.unit, it.quantity, it.minValue, it.maxValue)
            }
            ?: DerivedSensorCatalog.all.firstOrNull { it.key == key }?.let {
                SensorCatalogItem(it.key, it.name, it.shortName, it.sourceLabel, it.defaultUnit, it.quantity, it.minValue, it.maxValue)
            }
            ?: SensorCatalogItem(key, key.uppercase(), key.uppercase(), "PID", "", SensorQuantity.RAW, 0.0, 100.0)
    }

    fun convertForDisplay(valueMetric: Double?, quantity: SensorQuantity, rawFallbackUnit: String): Pair<Double?, String> {
        val prefs = settings.value.toUnitPreferences()
        val fuelType = activeVehicle.value?.let { runCatching { FuelType.valueOf(it.fuelType) }.getOrNull() } ?: FuelType.GASOLINE
        if (quantity == SensorQuantity.RAW) return valueMetric to rawFallbackUnit
        val (_, unitLabel) = UnitConverter.convert(0.0, quantity, prefs, fuelType)
        val convertedVal = valueMetric?.let { UnitConverter.convert(it, quantity, prefs, fuelType).first }
        return convertedVal to unitLabel
    }

    fun convertBounds(minMetric: Double, maxMetric: Double, quantity: SensorQuantity): Pair<Double, Double> {
        val prefs = settings.value.toUnitPreferences()
        val fuelType = activeVehicle.value?.let { runCatching { FuelType.valueOf(it.fuelType) }.getOrNull() } ?: FuelType.GASOLINE
        if (quantity == SensorQuantity.RAW || quantity == SensorQuantity.FUEL_ECONOMY_KPL) return minMetric to maxMetric
        val cMin = UnitConverter.convert(minMetric, quantity, prefs, fuelType).first
        val cMax = UnitConverter.convert(maxMetric, quantity, prefs, fuelType).first
        return minOf(cMin, cMax) to maxOf(cMin, cMax)
    }

    fun openExternalNavigation(context: Context) {
        val g = locationTracker.gps.value
        LocationTracker.launchExternalNavigation(context, g.latitude, g.longitude)
    }

    private fun toInternalVolume(displayVal: Double, fuelType: FuelType, prefs: UnitPreferences): Double = when {
        fuelType == FuelType.ELECTRIC -> displayVal
        prefs.volume == com.apexobd.core.model.VolumeUnit.GALLONS_US -> displayVal / 0.264172
        else -> displayVal
    }

    private fun toInternalDistanceKm(displayVal: Double, prefs: UnitPreferences): Double =
        if (prefs.speed == com.apexobd.core.model.SpeedUnit.MPH) displayVal / 0.621371 else displayVal

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    override fun onCleared() {
        super.onCleared()
        accelTracker.stop()
        locationTracker.stopUpdates()
        wifiBinder.unregister()
        viewModelScope.launch {
            flushActiveVehicleStats()
            engine?.stop()
        }
    }
}
