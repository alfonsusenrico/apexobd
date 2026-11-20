package com.apexobd.app.ui

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.apexobd.app.data.prefs.MapCursorStyle
import com.apexobd.app.data.prefs.ShareCardAspectRatio
import com.apexobd.app.data.prefs.ShareCardBackgroundMode
import com.apexobd.app.data.prefs.ShareCardMetric
import com.apexobd.app.data.prefs.TripCardConfig
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
import com.apexobd.app.data.prefs.HudCanvasBackdrop
import com.apexobd.app.data.prefs.HudCardStyle
import com.apexobd.app.data.prefs.HudGridScale
import com.apexobd.app.data.prefs.HudThemePreset
import com.apexobd.app.data.prefs.MapCameraMode
import com.apexobd.app.data.prefs.MapProvider
import com.apexobd.app.data.prefs.MapStylePreset
import com.apexobd.app.data.prefs.UserSettings
import com.apexobd.app.platform.AccelSensorTracker
import com.apexobd.app.platform.BluetoothClassicObdTransport
import com.apexobd.app.platform.BluetoothLeObdTransport
import com.apexobd.app.platform.BtDeviceCandidate
import com.apexobd.app.platform.GpsSnapshot
import com.apexobd.app.platform.LocationTracker
import com.apexobd.app.platform.LoggingForegroundService
import com.apexobd.app.platform.OfflineMapManager
import com.apexobd.app.platform.TripCardRenderer
import com.apexobd.app.platform.TripExporter
import com.apexobd.app.platform.TripShareStatsCalculator
import com.apexobd.app.platform.UsbSerialCandidate
import com.apexobd.app.platform.UsbSerialObdTransport
import com.apexobd.app.platform.WifiNetworkBinder
import com.apexobd.core.formula.CustomPidDefinition
import com.apexobd.core.formula.TorqueCsvImporter
import com.apexobd.core.model.FuelCoastingMode
import com.apexobd.core.model.FuelType
import com.apexobd.core.model.SensorQuantity
import com.apexobd.core.model.TransmissionType
import com.apexobd.core.model.UnitConverter
import com.apexobd.core.model.UnitPreferences
import com.apexobd.core.model.VehicleBodyType
import com.apexobd.core.protocol.DerivedSensorCatalog
import com.apexobd.core.protocol.DiagnosticReport
import com.apexobd.core.protocol.DtcDecoder
import com.apexobd.core.protocol.EcuDiscoveryDumper
import com.apexobd.core.protocol.FuelEconomyTracker
import com.apexobd.core.protocol.FuelMetricsSnapshot
import com.apexobd.core.protocol.GaugeSmoothingMode
import com.apexobd.core.protocol.ObdPidRegistry
import com.apexobd.core.protocol.TelemetryDisplaySmoother
import com.apexobd.core.protocol.TripRunCalculator
import com.apexobd.core.protocol.TripRunMetrics
import com.apexobd.core.scheduler.ConnectionState
import com.apexobd.core.scheduler.ObdPollingEngine
import com.apexobd.core.scheduler.PollingProfile
import com.apexobd.core.scheduler.Timer0100State
import com.apexobd.core.scheduler.Timer0100Tracker
import com.apexobd.core.transport.ObdConnectionType
import com.apexobd.core.transport.ObdTransport
import com.apexobd.core.transport.WifiTcpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
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

data class PickerSensorOption(
    val item: SensorCatalogItem,
    val available: Boolean
)

/**
 * Per-channel Compose [State] holder updated strictly on display VSync so rapid telemetry
 * updates invalidate only matching leaf digits/Canvas gauges rather than the entire HUD tree.
 */
@Stable
class HudLiveValueStore {
    private val valueStates = HashMap<String, MutableState<Double?>>(32)
    private val historyStates = HashMap<String, MutableState<List<Double>>>(8)

    fun valueStateFor(key: String): State<Double?> =
        valueStates.getOrPut(key) { mutableStateOf(null) }

    fun historyStateFor(key: String): State<List<Double>> =
        historyStates.getOrPut(key) { mutableStateOf(emptyList()) }

    fun updateValuesFrom(values: Map<String, Double>) {
        for ((k, v) in values) {
            val slot = valueStates.getOrPut(k) { mutableStateOf(v) }
            if (slot.value != v) slot.value = v
        }
        for ((k, slot) in valueStates) {
            if (k !in values && slot.value != null) slot.value = null
        }
    }

    fun updateHistoryFrom(history: Map<String, List<Double>>) {
        for ((k, list) in history) {
            val slot = historyStates.getOrPut(k) { mutableStateOf(list) }
            if (slot.value != list) slot.value = list
        }
        for ((k, slot) in historyStates) {
            if (k !in history && slot.value.isNotEmpty()) slot.value = emptyList()
        }
    }
}

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
    private var activeEngineVehicleId: Long? = null
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

    val tripSessions: StateFlow<List<TripSessionEntity>> = activeVehicle
        .map { it?.id ?: -1L }
        .distinctUntilChanged()
        .flatMapLatest { vid ->
            if (vid > 0L) tripDao.observeSessionsForVehicle(vid) else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _onHudScreen = MutableStateFlow(false)
    val onHudScreen: StateFlow<Boolean> = _onHudScreen.asStateFlow()

    private val _isAppInForeground = MutableStateFlow(true)
    private val _backgroundServiceActive = MutableStateFlow(true)
    val backgroundServiceActive: StateFlow<Boolean> = _backgroundServiceActive.asStateFlow()

    private val _visiblePageIndex = MutableStateFlow(0)
    private val _chartKeys = MutableStateFlow<Set<String>>(emptySet())
    @Volatile
    private var hasVisibleTimer0100 = false
    @Volatile
    private var cachedCatalogLookup: Map<String, SensorCatalogItem> = buildAllKnownCatalogLookup(emptyList())

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _supportedPids = MutableStateFlow<Set<String>>(emptySet())
    val supportedPids: StateFlow<Set<String>> = _supportedPids.asStateFlow()

    private val _availableSensorKeys = MutableStateFlow<Set<String>>(emptySet())
    val availableSensorKeys: StateFlow<Set<String>> = _availableSensorKeys.asStateFlow()

    private val _liveValues = MutableStateFlow<Map<String, Double>>(mapOf("trip_time" to 0.0))
    val liveValues: StateFlow<Map<String, Double>> = _liveValues.asStateFlow()

    // Per-sensor UI display store driven on hardware VSync via `stepVisualDisplayFrame`;
    // raw telemetry logging (`telemetry_samples`) and trip math strictly read `_liveValues`.
    val hudLiveValues = HudLiveValueStore()
    private var lastVisualFrameMs = 0L
    private var lastPublishedHistoryRef: Map<String, List<Double>>? = null

    private val _tripRunMetrics = MutableStateFlow(TripRunMetrics())
    val tripRunMetrics: StateFlow<TripRunMetrics> = _tripRunMetrics.asStateFlow()

    private val _pendingSaveTrip = MutableStateFlow<TripSessionEntity?>(null)
    val pendingSaveTrip: StateFlow<TripSessionEntity?> = _pendingSaveTrip.asStateFlow()

    enum class ShareCardExportAction { SHARE_TO_APPS, DOWNLOAD_PNG }

    data class ShareCardDraft(
        val session: TripSessionEntity,
        val samples: List<TelemetrySampleEntity>,
        val vehicle: VehicleProfileEntity?,
        val stats: TripShareStatsCalculator.AggregatedStats
    )

    private val _shareCardDraft = MutableStateFlow<ShareCardDraft?>(null)
    val shareCardDraft: StateFlow<ShareCardDraft?> = _shareCardDraft.asStateFlow()

    private val _shareCardPreparing = MutableStateFlow(false)
    val shareCardPreparing: StateFlow<Boolean> = _shareCardPreparing.asStateFlow()

    private val _shareCardExporting = MutableStateFlow(false)
    val shareCardExporting: StateFlow<Boolean> = _shareCardExporting.asStateFlow()

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

    private val _deepScanRunning = MutableStateFlow(false)
    val deepScanRunning: StateFlow<Boolean> = _deepScanRunning.asStateFlow()

    private val _ecuDumpReady = MutableStateFlow(java.io.File(application.filesDir, "ecu_dump.txt").exists())
    val ecuDumpReady: StateFlow<Boolean> = _ecuDumpReady.asStateFlow()

    @Volatile
    private var explorerOpen = false

    private val _vin = MutableStateFlow<String?>(null)
    val vin: StateFlow<String?> = _vin.asStateFlow()

    private val _timer0100 = MutableStateFlow(Timer0100State())
    val timer0100: StateFlow<Timer0100State> = _timer0100.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isRecordingPaused = MutableStateFlow(false)
    val isRecordingPaused: StateFlow<Boolean> = _isRecordingPaused.asStateFlow()

    private val smootherSession = TelemetryDisplaySmoother.AdaptiveSession()

    private val _ecuExplorerValues = MutableStateFlow<Map<String, Double>>(emptyMap())
    val ecuExplorerValues: StateFlow<Map<String, Double>> = _ecuExplorerValues.asStateFlow()

    private val _btPairedDevices = MutableStateFlow<List<BtDeviceCandidate>>(emptyList())
    val btPairedDevices: StateFlow<List<BtDeviceCandidate>> = _btPairedDevices.asStateFlow()

    private val _bleScannedDevices = MutableStateFlow<List<BtDeviceCandidate>>(emptyList())
    val bleScannedDevices: StateFlow<List<BtDeviceCandidate>> = _bleScannedDevices.asStateFlow()

    private val _bleScanning = MutableStateFlow(false)
    val bleScanning: StateFlow<Boolean> = _bleScanning.asStateFlow()

    private val _usbDevices = MutableStateFlow<List<UsbSerialCandidate>>(emptyList())
    val usbDevices: StateFlow<List<UsbSerialCandidate>> = _usbDevices.asStateFlow()

    val wifiConnected = wifiBinder.wifiConnected
    val cellularOnline = wifiBinder.cellularOnline

    private var activeSessionId: Long? = null
    private val mutableHistory = mutableMapOf<String, ArrayDeque<Double>>()

    init {
        wifiBinder.register()
        LoggingForegroundService.setTerminateListener {
            terminateBackgroundService()
        }
        viewModelScope.launch(Dispatchers.IO) {
            ApexDatabase.seedDefaults(hudDao, vehicleDao)
            tripDao.deleteDraftSessions()
            offlineMapManager.refreshAll()
        }

        // Sync VehicleProfile into polling engine and offline supported PID cache
        viewModelScope.launch {
            var lastSeenVehicleId: Long? = null
            activeVehicle.collect { v ->
                val switchedVehicle = lastSeenVehicleId != null && v != null && lastSeenVehicleId != v.id
                lastSeenVehicleId = v?.id
                if (v != null && _connectionState.value !is ConnectionState.Connected) {
                    _supportedPids.value = v.savedSupportedPids()
                }
                if (switchedVehicle) {
                    _diagnosticReport.value = null
                    _diagnosticError.value = null
                    _vin.value = null
                    _fuelMetrics.value = null
                }
                val currentEngine = engine ?: return@collect
                if (v != null) {
                    val switchedEngine = activeEngineVehicleId != null && activeEngineVehicleId != v.id
                    activeEngineVehicleId = v.id
                    if (switchedEngine) {
                        _fuelMetrics.value = null
                    }
                    currentEngine.setVehicleProfile(v.toCoreModel(), resetFuelSession = switchedEngine)
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

        // Keep OBD adapter connected whenever the background listener service is active (Home, HUD, or background)
        viewModelScope.launch {
            combine(
                _backgroundServiceActive,
                _onHudScreen,
                _isRecording,
                _isAppInForeground,
                settings
            ) { serviceActive, onHud, recording, inForeground, st ->
                BackgroundLifecycleState(
                    serviceActive = serviceActive,
                    onHud = onHud,
                    recording = recording,
                    inForeground = inForeground,
                    settings = st
                )
            }.collect { state ->
                val st = state.settings
                val isWifiAdapter = st.connectionType == ObdConnectionType.WIFI_TCP
                engine?.setFuelCalculationPaused(st.pauseFuelCalculation)
                engine?.setPollingProfile(st.pollingProfile)
                wifiBinder.setLowLatencyLocks(
                    wifiLockEnabled = state.serviceActive && isWifiAdapter,
                    wakeLockEnabled = state.serviceActive
                )
                locationTracker.setPreferPureGnss(state.serviceActive && isWifiAdapter)
                wifiBinder.setKeepCellularActive(
                    state.serviceActive && isWifiAdapter && st.dualCellularKeepalive
                )

                if (state.serviceActive) {
                    if (!LoggingForegroundService.isRunning && (state.inForeground || state.recording)) {
                        val (title, text) = buildBackgroundNotificationText(
                            connectionState = _connectionState.value,
                            isRecording = state.recording,
                            endpoint = st.adapterDetailedEndpoint()
                        )
                        LoggingForegroundService.start(getApplication(), title, text)
                    }
                    val needsImu = (state.onHud && state.inForeground) || state.recording
                    if (needsImu) accelTracker.start() else accelTracker.stop()
                    val idleInBackground = !(state.onHud && state.inForeground) && !state.recording && !explorerOpen
                    engine?.setBackgroundIdleMode(idleInBackground)
                    if (!suppressAutoConnect && engine == null) {
                        reconnectTransport(st)
                    }
                } else {
                    accelTracker.stop()
                    locationTracker.stopUpdates()
                    transportMutex.withLock {
                        if (engine != null) {
                            flushActiveVehicleStats()
                            engine?.stop()
                            engine = null
                            engineCollectorsJob?.cancel()
                            engineCollectorsJob = null
                        }
                        activeEngineVehicleId = null
                    }
                    LoggingForegroundService.stop(getApplication())
                    _fuelMetrics.value = null
                    _connectionState.value = ConnectionState.Disconnected
                    _pollingHz.value = 0
                }
            }
        }

        // Keep foreground notification text synchronized with live connection & recording status
        viewModelScope.launch {
            combine(_backgroundServiceActive, _connectionState, _isRecording, settings) { active, conn, rec, st ->
                Triple(active, rec, conn to st.adapterDetailedEndpoint())
            }.collect { (active, rec, pair) ->
                if (!active) return@collect
                val (conn, endpoint) = pair
                val (title, text) = buildBackgroundNotificationText(conn, rec, endpoint)
                LoggingForegroundService.updateIfRunning(getApplication(), title, text)
            }
        }

        viewModelScope.launch {
            customPids.collect { list ->
                cachedCatalogLookup = buildAllKnownCatalogLookup(list)
            }
        }

        // Visible HUD page polling set; lightweight keepalive set on Home/Background; Run Recording uses logger queue
        viewModelScope.launch {
            combine(
                pagesWithWidgets,
                _visiblePageIndex,
                _isRecording,
                combine(_onHudScreen, _isAppInForeground, _backgroundServiceActive) { h, fg, svc -> Triple(h, fg, svc) },
                settings
            ) { pages, pageIdx, recording, flags, st ->
                val (onHud, inForeground, serviceActive) = flags
                val activeHud = onHud && inForeground
                val visibleWidgets = if (activeHud) pages.getOrNull(pageIdx)?.widgets.orEmpty() else emptyList()
                _chartKeys.value = visibleWidgets
                    .filter { it.displayType == WidgetDisplayType.CHART.name }
                    .map { it.sensorKey }
                    .toSet()
                hasVisibleTimer0100 = visibleWidgets.any { it.sensorKey == "timer_0100" }

                val visibleKeys = when {
                    explorerOpen -> ObdPidRegistry.standardPids.map { it.key }.toSet()
                    activeHud -> buildSet {
                        visibleWidgets.forEach { widget ->
                            add(widget.sensorKey)
                            if (widget.sensorKey == "fuel_ctrl") add("kpl_road_avg")
                        }
                    }
                    else -> BACKGROUND_KEEPALIVE_KEYS
                }
                val backgroundKeys = if (recording) RECORDING_EXTRA_KEYS else emptySet()
                val needsGps = serviceActive && (activeHud || recording)
                val needsHighFreqGps = recording || (activeHud && st.pollingProfile != PollingProfile.ECO_BATTERY && visibleKeys.any { it in setOf("gps_speed", "minimap", "timer_0100") })
                if (needsGps) {
                    locationTracker.startUpdates(highFrequency = needsHighFreqGps)
                } else {
                    locationTracker.stopUpdates()
                }
                visibleKeys to backgroundKeys
            }.collect { (visibleKeys, backgroundKeys) ->
                engine?.setActiveSensors(visibleKeys)
                engine?.setBackgroundRecordKeys(backgroundKeys)
            }
        }

        // Direct 0ms-overhead GPS speed emission independent of OBD poll cycle cadence
        viewModelScope.launch {
            combine(_onHudScreen, _isRecording, locationTracker.gps) { onHud, recording, gps ->
                Triple(onHud || recording, recording, gps)
            }.collect { (active, recording, gps) ->
                if (active && gps.hasRealFix) {
                    val imuG = accelTracker.accelG.value
                    engine?.updateExternalTelemetry(gps.speedKmh, imuG, gps.roadGradePct)
                    _liveValues.update { cur ->
                        val prevSpeed = cur["gps_speed"]
                        if (prevSpeed == gps.speedKmh) cur else cur.toMutableMap().apply { put("gps_speed", gps.speedKmh) }
                    }
                    if (!recording && !onHudScreen.value) Unit else {
                        _ecuExplorerValues.value = _liveValues.value
                    }
                }
            }
        }

        // Reset C1 spring track state whenever the HUD exits
        viewModelScope.launch {
            _onHudScreen.collectLatest { onHud ->
                if (!onHud) {
                    lastVisualFrameMs = 0L
                    lastPublishedHistoryRef = null
                    smootherSession.reset()
                    hudLiveValues.updateValuesFrom(_liveValues.value)
                }
            }
        }
    }

    /**
     * Driven directly by Compose `withFrameNanos` on the Main thread so visual gauges advance
     * strictly in phase with hardware display VSync without allocating or waking background loops.
     */
    fun stepVisualDisplayFrame(frameTimeMs: Long) {
        val dtMs = if (lastVisualFrameMs <= 0L) 16L else (frameTimeMs - lastVisualFrameMs).coerceIn(1L, 120L)
        lastVisualFrameMs = frameTimeMs
        val curSettings = settings.value
        val smoothed = smootherSession.step(
            rawTargets = _liveValues.value,
            dtMs = dtMs,
            nowMs = frameTimeMs,
            mode = curSettings.gaugeSmoothingMode,
            liveMicroMotion = curSettings.liveMicroMotion
        )
        hudLiveValues.updateValuesFrom(smoothed)
        val hist = _historyBuffers.value
        if (hist !== lastPublishedHistoryRef) {
            lastPublishedHistoryRef = hist
            hudLiveValues.updateHistoryFrom(hist)
        }
    }

    fun setOnHudScreen(active: Boolean) {
        _onHudScreen.value = active
        if (active && !_backgroundServiceActive.value) {
            resumeBackgroundService()
        }
    }

    fun onAppForegrounded() {
        _isAppInForeground.value = true
        if (!_backgroundServiceActive.value) {
            resumeBackgroundService()
        } else if (!LoggingForegroundService.isRunning) {
            val (title, text) = buildBackgroundNotificationText(
                connectionState = _connectionState.value,
                isRecording = _isRecording.value,
                endpoint = settings.value.adapterDetailedEndpoint()
            )
            LoggingForegroundService.start(getApplication(), title, text)
        }
    }

    fun onAppBackgrounded() {
        _isAppInForeground.value = false
    }

    fun onPermissionsUpdated() {
        if (_backgroundServiceActive.value) {
            val (title, text) = buildBackgroundNotificationText(
                connectionState = _connectionState.value,
                isRecording = _isRecording.value,
                endpoint = settings.value.adapterDetailedEndpoint()
            )
            LoggingForegroundService.start(getApplication(), title, text)
        }
    }

    fun resumeBackgroundService() {
        _backgroundServiceActive.value = true
        val (title, text) = buildBackgroundNotificationText(
            connectionState = _connectionState.value,
            isRecording = _isRecording.value,
            endpoint = settings.value.adapterDetailedEndpoint()
        )
        LoggingForegroundService.start(getApplication(), title, text)
    }

    fun terminateBackgroundService() {
        if (_isRecording.value) {
            stopRecording()
        }
        _backgroundServiceActive.value = false
        LoggingForegroundService.stop(getApplication())
    }

    private fun buildBackgroundNotificationText(
        connectionState: ConnectionState,
        isRecording: Boolean,
        endpoint: String
    ): Pair<String, String> {
        val title = if (isRecording) {
            "ApexOBD · Recording & Listening"
        } else {
            "ApexOBD Background Listener"
        }
        val stateText = when (connectionState) {
            is ConnectionState.Connected -> "Connected ($endpoint)"
            is ConnectionState.Connecting -> "Connecting to $endpoint"
            is ConnectionState.Reconnecting -> "Reconnecting #${connectionState.attempt} ($endpoint)"
            is ConnectionState.Disconnected -> "Listener active ($endpoint)"
        }
        val body = "$stateText · Active in background · Tap to stop"
        return title to body
    }

    fun setVisiblePageIndex(index: Int) {
        _visiblePageIndex.value = index.coerceAtLeast(0)
    }

    fun forceConnectAndDiscoverForExplorer() {
        explorerOpen = true
        if (!_backgroundServiceActive.value) {
            resumeBackgroundService()
        }
        if (_deepScanRunning.value) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (_explorerStatus.value?.startsWith("Deep scan") != true) {
                    _explorerStatus.value = "Checking OBD connection..."
                }
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
                    _explorerStatus.value = "Adapter offline (${st.adapterDetailedEndpoint()}) · Showing standard PIDs"
                    return@launch
                }
                if (_explorerStatus.value?.startsWith("Deep scan complete") != true) {
                    _explorerStatus.value = null
                }
                val explorerKeys = ObdPidRegistry.standardPids.map { it.key }.toSet()
                engine?.setBackgroundIdleMode(false)
                engine?.setActiveSensors(explorerKeys)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _explorerStatus.value = "ECU explorer offline: ${t.message ?: "not connected"}"
            }
        }
    }

    fun restoreVisibleCanvasPolling() {
        explorerOpen = false
        if (!_backgroundServiceActive.value) {
            viewModelScope.launch {
                transportMutex.withLock {
                    engineCollectorsJob?.cancel()
                    engine?.stop()
                    engine = null
                }
                _connectionState.value = ConnectionState.Disconnected
                _pollingHz.value = 0
            }
            return
        }
        val activeHud = _onHudScreen.value && _isAppInForeground.value
        val nextKeys = if (activeHud) {
            pagesWithWidgets.value.getOrNull(_visiblePageIndex.value)?.widgets.orEmpty()
                .map { it.sensorKey }.toSet()
        } else {
            BACKGROUND_KEEPALIVE_KEYS
        }
        engine?.setBackgroundIdleMode(!activeHud && !_isRecording.value)
        engine?.setActiveSensors(nextKeys)
    }

    fun runDeepEcuDump(host: String = settings.value.wifiHost, port: Int = settings.value.wifiPort) {
        if (_deepScanRunning.value) return
        _deepScanRunning.value = true
        suppressAutoConnect = true
        _explorerStatus.value = "Deep Scan starting · pausing live poll..."
        viewModelScope.launch(Dispatchers.IO) {
            var scanFailed = false
            val st = settings.value
            val dumpText = transportMutex.withLock {
                engineCollectorsJob?.cancel()
                engineCollectorsJob = null
                engine?.stop()
                engine = null
                delay(200L)
                val transport = if (host != st.wifiHost || port != st.wifiPort) {
                    WifiTcpTransport(host = host, port = port, socketFactoryProvider = { wifiBinder.socketFactory() })
                } else {
                    buildTransport(st)
                }
                try {
                    transport.connect()
                    EcuDiscoveryDumper.runFullReadOnlyScan(transport) { progress ->
                        _explorerStatus.value = progress
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    scanFailed = true
                    "DEEP ECU SCAN ERROR: ${t.message}\n${t.stackTraceToString()}"
                } finally {
                    runCatching { transport.disconnect() }
                }
            }
            java.io.File(getApplication<Application>().filesDir, "ecu_dump.txt").writeText(dumpText)
            _ecuDumpReady.value = true
            _explorerStatus.value = if (scanFailed) {
                "Deep scan failed: ${dumpText.lineSequence().firstOrNull().orEmpty()}"
            } else {
                "Deep scan complete · saved ecu_dump.txt (resuming live values)"
            }
            _deepScanRunning.value = false
            suppressAutoConnect = false
            prefs.updateWifiEndpoint(host, port)
            val nextSettings = settings.value.copy(
                wifiHost = host,
                wifiPort = port
            )
            if (explorerOpen) {
                forceConnectAndDiscoverForExplorer()
            } else if (_backgroundServiceActive.value) {
                reconnectTransport(nextSettings)
            }
        }
    }

    fun shareEcuDump() {
        viewModelScope.launch(Dispatchers.IO) {
            val dumpFile = java.io.File(getApplication<Application>().filesDir, "ecu_dump.txt")
            if (!dumpFile.exists()) return@launch
            TripExporter.shareFile(
                context = getApplication(),
                fileName = "ecu_dump.txt",
                mimeType = "text/plain",
                content = dumpFile.readText()
            )
        }
    }

    private fun buildTransport(s: UserSettings): ObdTransport {
        val ctx = getApplication<Application>()
        return when (s.connectionType) {
            ObdConnectionType.WIFI_TCP -> WifiTcpTransport(
                host = s.wifiHost,
                port = s.wifiPort,
                socketFactoryProvider = { wifiBinder.socketFactory() }
            )
            ObdConnectionType.BLUETOOTH_CLASSIC -> BluetoothClassicObdTransport(
                context = ctx,
                deviceAddress = s.btDeviceAddress,
                deviceNameHint = s.btDeviceName
            )
            ObdConnectionType.BLUETOOTH_LE -> BluetoothLeObdTransport(
                context = ctx,
                deviceAddress = s.bleDeviceAddress,
                deviceNameHint = s.bleDeviceName
            )
            ObdConnectionType.USB_SERIAL -> UsbSerialObdTransport(
                context = ctx,
                targetDeviceId = s.usbDeviceId,
                baudRate = s.usbBaudRate
            )
        }
    }

    private suspend fun reconnectTransport(s: UserSettings) = transportMutex.withLock {
        if (engine != null) {
            flushActiveVehicleStats()
            engineCollectorsJob?.cancel()
            engineCollectorsJob = null
            engine?.stop()
            engine = null
        }
        _fuelMetrics.value = null

        val transport = buildTransport(s)
        val currentVehicle = activeVehicle.value
        activeEngineVehicleId = currentVehicle?.id
        val newEngine = ObdPollingEngine(transport, viewModelScope)
        newEngine.setPollingProfile(s.pollingProfile)
        newEngine.setFuelCoastingMode(s.fuelCoastingMode)
        newEngine.setFuelCalculationPaused(s.pauseFuelCalculation)
        currentVehicle?.let { newEngine.setVehicleProfile(it.toCoreModel(), resetFuelSession = true) }
        newEngine.setCustomPids(
            customPids.value.map {
                CustomPidDefinition(it.key, it.name, it.shortName, it.modeAndPidHex, it.formula, it.minValue, it.maxValue, it.unit, it.headerHex)
            }
        )
        engine = newEngine

        val activeHud = _onHudScreen.value && _isAppInForeground.value
        val initialKeys = when {
            explorerOpen -> ObdPidRegistry.standardPids.map { it.key }.toSet()
            activeHud -> pagesWithWidgets.value.getOrNull(_visiblePageIndex.value)?.widgets.orEmpty()
                .map { it.sensorKey }.toSet()
            else -> BACKGROUND_KEEPALIVE_KEYS
        }
        newEngine.setBackgroundIdleMode(!activeHud && !_isRecording.value && !explorerOpen)
        newEngine.setActiveSensors(initialKeys)
        newEngine.setBackgroundRecordKeys(if (_isRecording.value) RECORDING_EXTRA_KEYS else emptySet())

        engineCollectorsJob = viewModelScope.launch {
            launch { newEngine.connectionState.collect { _connectionState.value = it } }
            launch {
                newEngine.supportedPids.collect { pids ->
                    _supportedPids.value = pids
                    if (pids.isNotEmpty()) {
                        val csv = pids.sorted().joinToString(",")
                        val targetId = activeEngineVehicleId
                        val cur = if (targetId != null) vehicleDao.getById(targetId) else activeVehicle.value
                        if (cur != null && cur.supportedPidsCsv != csv) {
                            vehicleDao.updateVehicle(cur.copy(supportedPidsCsv = csv))
                        }
                    }
                }
            }
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
                    val gps = locationTracker.gps.value
                    val imuG = accelTracker.accelG.value
                    val speedGps = if (gps.hasRealFix) gps.speedKmh else obdSpd
                    newEngine.updateExternalTelemetry(speedGps, imuG, gps.roadGradePct)

                    val merged = map.toMutableMap()
                    merged["gps_speed"] = speedGps
                    merged["accel_g"] = imuG
                    val withCentralTrip = TripRunCalculator.applyToLiveValues(merged, _tripRunMetrics.value)
                    _liveValues.value = withCentralTrip
                    _ecuExplorerValues.value = withCentralTrip
                    appendChartHistoryOnly(withCentralTrip)

                    if (hasVisibleTimer0100 || _isRecording.value) {
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
        val targetId = activeEngineVehicleId ?: return
        val current = vehicleDao.getById(targetId) ?: return
        vehicleDao.updateVehicle(
            current.copy(
                allTimeOdometerKm = fm.allTimeOdometerKm,
                allTimeRoadDistanceKm = fm.allTimeRoadDistanceKm,
                allTimeRoadFuelLiters = fm.allTimeRoadFuelLiters,
                allTimeTotalDistanceKm = fm.allTimeTotalDistanceKm,
                allTimeTotalFuelLiters = fm.allTimeTotalFuelLiters,
                fuelUsedSinceFullRefuelLiters = fm.fuelUsedSinceFullRefuelLiters,
                estimatedRemainingFuelLiters = fm.estimatedRemainingFuelLiters,
                recentDteKpl = fm.rawRecentDteKpl ?: current.recentDteKpl
            )
        )
    }

    private suspend fun flushActiveVehicleStats() {
        if (engine == null || activeEngineVehicleId == null) return
        _fuelMetrics.value?.let { persistFuelMetricsToProfile(it) }
    }

    private fun updateBest0100IfBetter(candidate: Double?) {
        if (candidate == null) return
        val targetId = activeEngineVehicleId ?: return
        val cur = vehicles.value.firstOrNull { it.id == targetId } ?: return
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
                        "Not connected to OBD2 adapter (${st.adapterDetailedEndpoint()}). Check adapter connection and tap Rescan."
                    return@launch
                }
                val result = DtcDecoder.scanAll(client)
                _diagnosticReport.value = result
                if (_vin.value == null) _vin.value = DtcDecoder.readVin(client)
                if (result.confirmed.isEmpty() && result.pending.isEmpty() && result.permanent.isEmpty() && result.readiness == null) {
                    _diagnosticError.value = "ECU did not respond to diagnostic query. Ensure ignition is ON."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _diagnosticError.value = "Diagnostic scan failed: ${t.message ?: "adapter disconnected"}"
            } finally {
                _diagnosticScanning.value = false
                if (!_backgroundServiceActive.value && !_isRecording.value && _connectionState.value !is ConnectionState.Connected) {
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
            } catch (e: CancellationException) {
                throw e
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

    fun togglePauseResumeRecording() {
        if (!_isRecording.value) return
        _isRecordingPaused.value = !_isRecordingPaused.value
    }

    private fun startRecording() {
        if (_isRecording.value) return
        _pendingSaveTrip.value = null
        locationTracker.clearTrail()
        _isRecordingPaused.value = false
        _isRecording.value = true
        if (!_backgroundServiceActive.value) {
            _backgroundServiceActive.value = true
        }
        val (title, text) = buildBackgroundNotificationText(
            connectionState = _connectionState.value,
            isRecording = true,
            endpoint = settings.value.adapterDetailedEndpoint()
        )
        LoggingForegroundService.start(getApplication(), title, text)

        loggingJob = viewModelScope.launch(Dispatchers.IO) {
            val vehicleId = activeVehicle.value?.id ?: 1L
            val startMs = System.currentTimeMillis()
            val sessionId = tripDao.insertSession(
                TripSessionEntity(startedAtMs = startMs, vehicleId = vehicleId, isSaved = false)
            )
            activeSessionId = sessionId

            var currentTrip = TripRunMetrics(activeOrCompleted = true, isRecording = true, isPaused = false)
            _tripRunMetrics.value = currentTrip
            _liveValues.update { TripRunCalculator.applyToLiveValues(it, currentTrip) }

            var maxSpeed = 0.0
            var peakRpm = 0.0
            var totalCount = 0
            var activeElapsedMs = 0L
            var accumulatedFuelL = 0.0
            var segmentFuelBaseline = _fuelMetrics.value?.totalFuelUsedLiters ?: 0.0
            var wasPaused = false
            var lastStepMs = startMs
            var lastGpsMoveMs = startMs
            var prevGps: GpsSnapshot? = null
            val batch = ArrayList<TelemetrySampleEntity>(10)

            try {
                while (_isRecording.value) {
                    val nowMs = System.currentTimeMillis()
                    val dtMs = (nowMs - lastStepMs).coerceAtLeast(0L)
                    lastStepMs = nowMs
                    val pausedNow = _isRecordingPaused.value
                    val curTotalFuel = _fuelMetrics.value?.totalFuelUsedLiters ?: segmentFuelBaseline

                    if (pausedNow) {
                        if (!wasPaused) {
                            accumulatedFuelL += (curTotalFuel - segmentFuelBaseline).coerceAtLeast(0.0)
                            wasPaused = true
                        }
                        prevGps = null
                        val pausedTrip = currentTrip.copy(isPaused = true)
                        currentTrip = pausedTrip
                        _tripRunMetrics.value = pausedTrip
                        _liveValues.update { TripRunCalculator.applyToLiveValues(it, pausedTrip) }
                        delay(250L)
                        continue
                    }

                    if (wasPaused) {
                        segmentFuelBaseline = curTotalFuel
                        lastGpsMoveMs = nowMs
                        prevGps = null
                        wasPaused = false
                    }

                    activeElapsedMs += dtMs

                    val gps = locationTracker.gps.value
                    val vals = _liveValues.value
                    val obdSpd = vals["obd_speed"] ?: 0.0
                    val speed = if (gps.hasRealFix && gps.speedKmh > obdSpd * 0.2) gps.speedKmh else obdSpd
                    val rpm = vals["rpm"] ?: 0.0
                    if (speed > maxSpeed) maxSpeed = speed
                    if (rpm > peakRpm) peakRpm = rpm

                    val prev = prevGps
                    val gpsDeltaKm = if (
                        prev != null &&
                        prev.hasRealFix &&
                        gps.hasRealFix &&
                        (gps.latitude != prev.latitude || gps.longitude != prev.longitude)
                    ) {
                        lastGpsMoveMs = nowMs
                        haversineKm(prev.latitude, prev.longitude, gps.latitude, gps.longitude)
                    } else null
                    prevGps = gps

                    val totalFuelUsed = accumulatedFuelL + (curTotalFuel - segmentFuelBaseline).coerceAtLeast(0.0)
                    currentTrip = TripRunCalculator.computeStep(
                        previousDistanceKm = currentTrip.distanceKm,
                        gpsDeltaKm = gpsDeltaKm,
                        gpsHasRealFix = gps.hasRealFix,
                        msSinceLastGpsMovement = nowMs - lastGpsMoveMs,
                        useSimulator = false,
                        speedKmh = speed,
                        dtMs = dtMs,
                        elapsedMs = activeElapsedMs,
                        fuelDeltaLiters = totalFuelUsed
                    ).copy(isPaused = false)
                    _tripRunMetrics.value = currentTrip
                    _liveValues.update { TripRunCalculator.applyToLiveValues(it, currentTrip) }

                    batch += TelemetrySampleEntity(
                        sessionId = sessionId,
                        timestampMs = nowMs,
                        latitude = if (gps.hasRealFix) gps.latitude else null,
                        longitude = if (gps.hasRealFix) gps.longitude else null,
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
                                distanceKm = currentTrip.distanceKm,
                                avgSpeedKmh = currentTrip.avgSpeedKmh ?: 0.0,
                                maxSpeedKmh = maxSpeed,
                                peakRpm = peakRpm,
                                best0100Sec = _timer0100.value.bestRunSec,
                                sampleCount = totalCount,
                                vehicleId = vehicleId,
                                fuelUsedLiters = currentTrip.fuelUsedLiters,
                                avgKpl = currentTrip.avgKpl ?: 0.0,
                                activeDurationSec = activeElapsedMs / 1000L,
                                isSaved = false
                            )
                        )
                    }
                    delay(250L)
                }
            } finally {
                withContext(NonCancellable) {
                    if (batch.isNotEmpty()) {
                        tripDao.insertSamples(batch.toList())
                        batch.clear()
                    }
                    val endMs = System.currentTimeMillis()
                    val curTotalFuel = _fuelMetrics.value?.totalFuelUsedLiters ?: segmentFuelBaseline
                    val finalFuel = if (wasPaused) {
                        accumulatedFuelL
                    } else {
                        accumulatedFuelL + (curTotalFuel - segmentFuelBaseline).coerceAtLeast(0.0)
                    }.coerceAtLeast(0.0)
                    val finalStep = TripRunCalculator.computeStep(
                        previousDistanceKm = currentTrip.distanceKm,
                        gpsDeltaKm = null,
                        gpsHasRealFix = false,
                        msSinceLastGpsMovement = 0L,
                        useSimulator = false,
                        speedKmh = 0.0,
                        dtMs = 0L,
                        elapsedMs = activeElapsedMs.coerceAtLeast(0L),
                        fuelDeltaLiters = finalFuel
                    ).copy(isRecording = false, isPaused = false)
                    _tripRunMetrics.value = finalStep
                    _liveValues.update { TripRunCalculator.applyToLiveValues(it, finalStep) }

                    val finalSession = TripSessionEntity(
                        id = sessionId,
                        startedAtMs = startMs,
                        endedAtMs = endMs,
                        distanceKm = finalStep.distanceKm,
                        avgSpeedKmh = finalStep.avgSpeedKmh ?: 0.0,
                        maxSpeedKmh = maxSpeed,
                        peakRpm = peakRpm,
                        best0100Sec = _timer0100.value.bestRunSec,
                        sampleCount = totalCount,
                        vehicleId = vehicleId,
                        fuelUsedLiters = finalStep.fuelUsedLiters,
                        avgKpl = finalStep.avgKpl ?: 0.0,
                        activeDurationSec = activeElapsedMs / 1000L,
                        isSaved = false
                    )
                    tripDao.updateSession(finalSession)
                    flushActiveVehicleStats()
                    _pendingSaveTrip.value = finalSession
                }
            }
        }
    }

    private fun stopRecording() {
        _isRecording.value = false
        _isRecordingPaused.value = false
        if (!_backgroundServiceActive.value) {
            LoggingForegroundService.stop(getApplication())
        }
        activeSessionId = null
    }

    fun savePendingTrip() {
        val pending = _pendingSaveTrip.value ?: return
        _pendingSaveTrip.value = null
        viewModelScope.launch(Dispatchers.IO) {
            tripDao.markSessionSaved(pending.id)
        }
    }

    fun discardPendingTrip() {
        val pending = _pendingSaveTrip.value ?: return
        _pendingSaveTrip.value = null
        val reset = TripRunMetrics()
        _tripRunMetrics.value = reset
        _liveValues.update { TripRunCalculator.applyToLiveValues(it, reset) }
        locationTracker.clearTrail()
        viewModelScope.launch(Dispatchers.IO) {
            tripDao.deleteSessionById(pending.id)
        }
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

    fun exportSessionShareCard(session: TripSessionEntity) {
        if (_shareCardPreparing.value) return
        _shareCardPreparing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val samples = tripDao.getSamplesForSession(session.id)
                val vehicle = vehicleDao.getById(session.vehicleId) ?: activeVehicle.value
                val stats = TripShareStatsCalculator.summarize(session, samples)
                _shareCardDraft.value = ShareCardDraft(
                    session = session,
                    samples = samples,
                    vehicle = vehicle,
                    stats = stats
                )
            } finally {
                _shareCardPreparing.value = false
            }
        }
    }

    fun dismissShareCardComposer() {
        _shareCardDraft.value = null
    }

    fun updateSavedTripCardConfig(config: TripCardConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.saveTripCardConfig(config)
        }
    }

    fun copyPickedCardBackgroundPhoto(uri: Uri, onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val savedPath = TripCardRenderer.copyCardBackgroundPhotoToInternal(getApplication(), uri)
            withContext(Dispatchers.Main) {
                onResult(savedPath)
            }
        }
    }

    suspend fun loadSurroundingRoadsForCard(
        samples: List<TelemetrySampleEntity>,
        boxWidth: Int,
        boxHeight: Int,
        mainRoadsOnly: Boolean = true
    ): TripCardRenderer.RoadContextLayer? = TripCardRenderer.captureSurroundingRoadsLayer(
        context = getApplication(),
        samples = samples,
        boxWidth = boxWidth,
        boxHeight = boxHeight,
        activePmtilesPath = settings.value.activePmtilesPath,
        mainRoadsOnly = mainRoadsOnly
    )

    fun confirmShareCardExport(
        config: TripCardConfig,
        action: ShareCardExportAction = ShareCardExportAction.SHARE_TO_APPS,
        roadContextLayer: TripCardRenderer.RoadContextLayer? = null
    ) {
        val draft = _shareCardDraft.value ?: return
        if (_shareCardExporting.value) return
        _shareCardExporting.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                prefs.saveTripCardConfig(config)
                val st = settings.value
                val fuelType = draft.vehicle?.let { runCatching { FuelType.valueOf(it.fuelType) }.getOrNull() } ?: FuelType.GASOLINE
                val resolvedRoadLayer = if (config.showSurroundingRoads) {
                    val (reqW, reqH) = TripCardRenderer.routeCanvasSizeForConfig(config)
                    val matchesExisting = roadContextLayer != null &&
                        !roadContextLayer.roadsAlphaBitmap.isRecycled &&
                        roadContextLayer.boxWidth == reqW &&
                        roadContextLayer.boxHeight == reqH &&
                        roadContextLayer.mainRoadsOnly == config.surroundingRoadsMainOnly
                    if (matchesExisting) {
                        roadContextLayer
                    } else {
                        TripCardRenderer.captureSurroundingRoadsLayer(
                            context = getApplication(),
                            samples = draft.samples,
                            boxWidth = reqW,
                            boxHeight = reqH,
                            activePmtilesPath = st.activePmtilesPath,
                            mainRoadsOnly = config.surroundingRoadsMainOnly
                        )
                    }
                } else null

                val outFile = TripCardRenderer.renderTripCardToPngFile(
                    context = getApplication(),
                    session = draft.session,
                    samples = draft.samples,
                    vehicle = draft.vehicle,
                    unitPrefs = st.toUnitPreferences(),
                    fuelType = fuelType,
                    selectedMetrics = config.selectedMetrics,
                    config = config,
                    precomputedStats = draft.stats,
                    roadContextLayer = resolvedRoadLayer
                )

                withContext(Dispatchers.Main) {
                    _shareCardDraft.value = null
                }

                when (action) {
                    ShareCardExportAction.SHARE_TO_APPS -> {
                        TripExporter.sharePreparedFile(
                            context = getApplication(),
                            file = outFile,
                            mimeType = "image/png",
                            title = "Export Route Card"
                        )
                    }
                    ShareCardExportAction.DOWNLOAD_PNG -> {
                        val savedRes = TripExporter.savePngToPictures(
                            context = getApplication(),
                            sourceFile = outFile,
                            displayName = "apex_route_${draft.session.id}_${System.currentTimeMillis()}.png"
                        )
                        withContext(Dispatchers.Main) {
                            val msg = if (savedRes.isSuccess) {
                                "Saved to ${savedRes.getOrNull()}"
                            } else {
                                "Save failed: ${savedRes.exceptionOrNull()?.message ?: "unknown"}"
                            }
                            Toast.makeText(getApplication(), msg, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } finally {
                _shareCardExporting.value = false
            }
        }
    }

    fun copyPickedVehiclePhoto(uri: Uri, onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val tag = (activeVehicle.value?.id ?: 0L).toString()
            val savedPath = TripCardRenderer.copyVehiclePhotoToInternal(getApplication(), uri, tag)
            withContext(Dispatchers.Main) {
                onResult(savedPath)
            }
        }
    }

    suspend fun getSamplesForSession(sessionId: Long): List<TelemetrySampleEntity> =
        tripDao.getSamplesForSession(sessionId)

    // --- Vehicle Profiles & Odometer / Tank Management ---
    fun selectVehicle(vehicleId: Long) {
        if (activeVehicle.value?.id == vehicleId) return
        if (_isRecording.value) {
            Toast.makeText(
                getApplication(),
                "Finish active trip recording before switching cars",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            if (engine != null && activeEngineVehicleId != null && activeEngineVehicleId != vehicleId) {
                flushActiveVehicleStats()
            }
            val target = vehicleDao.getById(vehicleId)
            _fuelMetrics.value = null
            _diagnosticReport.value = null
            _diagnosticError.value = null
            _vin.value = null
            if (_connectionState.value !is ConnectionState.Connected) {
                _supportedPids.value = target?.savedSupportedPids().orEmpty()
            }
            vehicleDao.setActiveVehicle(vehicleId)
        }
    }

    fun saveVehicleProfile(
        existing: VehicleProfileEntity?,
        name: String,
        engineCc: Int,
        carType: VehicleBodyType,
        transmission: TransmissionType,
        modelYear: Int?,
        photoUri: String?,
        weightKg: Double,
        fuelType: FuelType,
        tankCapacityDisplayUnit: Double,
        estRemainingDisplayUnit: Double,
        odometerDisplayUnit: Double,
        redlineRpm: Int,
        mafTrimPct: Double = existing?.mafTrimPct ?: 100.0
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val st = settings.value
            val unitPrefs = st.toUnitPreferences()
            val tankL = toInternalVolume(tankCapacityDisplayUnit, fuelType, unitPrefs)
            val remL = toInternalVolume(estRemainingDisplayUnit, fuelType, unitPrefs).coerceIn(0.0, tankL)
            val odoKm = toInternalDistanceKm(odometerDisplayUnit, unitPrefs)
            val safeCc = engineCc.coerceIn(0, 12000)

            val entity = (existing ?: VehicleProfileEntity(isActive = true)).copy(
                name = name.trim().ifBlank { "My Vehicle" },
                engineCc = safeCc,
                displacementLiters = (safeCc / 1000.0).coerceIn(0.0, 12.0),
                carType = carType.name,
                transmission = transmission.name,
                modelYear = modelYear?.takeIf { it in 1960..2035 },
                photoUri = photoUri?.takeIf { it.isNotBlank() },
                dragCoefficientAreaCdA = carType.defaultCdA,
                drivetrainLossPct = transmission.defaultDrivetrainLossPct,
                weightKg = weightKg.coerceIn(400.0, 8000.0),
                fuelType = fuelType.name,
                tankCapacityLiters = tankL.coerceAtLeast(1.0),
                estimatedRemainingFuelLiters = remL,
                mafTrimPct = mafTrimPct.coerceIn(75.0, 135.0),
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

    fun deleteVehicleProfile(vehicleId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val all = vehicleDao.getVehiclesOnce()
            if (all.size <= 1) return@launch
            val remaining = all.filter { it.id != vehicleId }
            if (remaining.isEmpty()) return@launch
            if (activeEngineVehicleId == vehicleId) {
                activeEngineVehicleId = null
                _fuelMetrics.value = null
            }
            tripDao.deleteSessionsForVehicle(vehicleId)
            vehicleDao.deleteVehicle(vehicleId)
            if (all.firstOrNull { it.isActive }?.id == vehicleId) {
                vehicleDao.setActiveVehicle(remaining.first().id)
            }
        }
    }

    // --- Refuel Checkpoints (Optionally calibrates Fuel Trim % on Full-Tank fills) ---
    fun addRefuelCheckpoint(
        volumeDisplayUnit: Double,
        pricePaid: Double,
        currencyCode: String,
        ronValue: Int?,
        fuelName: String?,
        odometerDisplayUnit: Double,
        isFullTank: Boolean = true,
        autoCalibrateTrim: Boolean = true
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
                    cycleAvgKpl = cycleAvgKpl,
                    isFullTank = isFullTank
                )
            )

            val calibratedTrim = if (isFullTank && autoCalibrateTrim && prev?.isFullTank == true) {
                com.apexobd.core.protocol.FuelEconomyTracker.computeCalibratedTrimPct(
                    currentTrimPct = vehicle.mafTrimPct,
                    actualPumpLiters = volumeL,
                    obdFuelUsedSinceFullLiters = vehicle.fuelUsedSinceFullRefuelLiters
                )
            } else {
                vehicle.mafTrimPct
            }

            val newRemaining = if (isFullTank) {
                vehicle.tankCapacityLiters
            } else {
                (vehicle.estimatedRemainingFuelLiters + volumeL).coerceIn(0.0, vehicle.tankCapacityLiters)
            }
            val newOdo = maxOf(vehicle.allTimeOdometerKm, odoKm)
            val updated = vehicle.copy(
                allTimeOdometerKm = newOdo,
                estimatedRemainingFuelLiters = newRemaining,
                mafTrimPct = calibratedTrim,
                fuelUsedSinceFullRefuelLiters = if (isFullTank) 0.0 else vehicle.fuelUsedSinceFullRefuelLiters
            )
            vehicleDao.updateVehicle(updated)
            engine?.fuelTracker?.applyRefuelAdded(volumeL, vehicle.tankCapacityLiters, isFullTank = isFullTank)
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
            val scale = settings.value.hudGridScale
            val existing = hudDao.getWidgetsForPage(pageId)
            val nextSortOrder = (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1
            val sw = spanCols.coerceIn(1, scale.cols)
            val sh = spanRows.coerceIn(1, scale.rows)
            val (slotCol, slotRow) = findFirstFreeGridSlot(existing, scale.cols, scale.rows, sw, sh)
            val xFrac = (slotCol.toFloat() / scale.cols.toFloat()).coerceIn(0f, 0.95f)
            val yFrac = (slotRow.toFloat() / scale.rows.toFloat()).coerceIn(0f, 0.95f)
            hudDao.insertWidget(
                HudWidgetEntity(
                    pageId = pageId,
                    sensorKey = sensorKey,
                    displayType = displayType.name,
                    spanCols = sw,
                    spanRows = sh,
                    sortOrder = nextSortOrder,
                    gridCol = slotCol,
                    gridRow = slotRow,
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
            WidgetDisplayType.SEGMENTED_BAR,
            WidgetDisplayType.CHART
        )
        val cur = runCatching { WidgetDisplayType.valueOf(widget.displayType) }.getOrNull() ?: return
        val idx = cycle.indexOf(cur)
        if (idx == -1) return
        val next = cycle[(idx + 1) % cycle.size]
        updateWidgetDisplayType(widget, next)
    }

    fun updateWidgetDisplayType(widget: HudWidgetEntity, displayType: WidgetDisplayType) {
        viewModelScope.launch { hudDao.updateWidget(widget.copy(displayType = displayType.name)) }
    }

    fun updateWidgetSize(widget: HudWidgetEntity, cols: Int, rows: Int) {
        val scale = settings.value.hudGridScale
        val clampedCols = cols.coerceIn(1, scale.cols)
        val clampedRows = rows.coerceIn(1, scale.rows)
        val nextCol = if (widget.gridCol >= 0) widget.gridCol.coerceIn(0, (scale.cols - clampedCols).coerceAtLeast(0)) else widget.gridCol
        val nextRow = if (widget.gridRow >= 0) widget.gridRow.coerceIn(0, (scale.rows - clampedRows).coerceAtLeast(0)) else widget.gridRow
        viewModelScope.launch {
            hudDao.updateWidget(
                widget.copy(
                    spanCols = clampedCols,
                    spanRows = clampedRows,
                    gridCol = nextCol,
                    gridRow = nextRow
                )
            )
        }
    }

    fun updateWidgetGridPosition(widget: HudWidgetEntity, col: Int, row: Int) {
        val scale = settings.value.hudGridScale
        val sw = widget.spanCols.coerceIn(1, scale.cols)
        val sh = widget.spanRows.coerceIn(1, scale.rows)
        val clampedCol = col.coerceIn(0, (scale.cols - sw).coerceAtLeast(0))
        val clampedRow = row.coerceIn(0, (scale.rows - sh).coerceAtLeast(0))
        viewModelScope.launch {
            hudDao.updateWidget(
                widget.copy(
                    gridCol = clampedCol,
                    gridRow = clampedRow,
                    freeformXFraction = (clampedCol.toFloat() / scale.cols.toFloat()).coerceIn(0f, 0.95f),
                    freeformYFraction = (clampedRow.toFloat() / scale.rows.toFloat()).coerceIn(0f, 0.95f)
                )
            )
        }
    }

    fun updateWidgetFreeformPosition(
        widget: HudWidgetEntity,
        xFrac: Float,
        yFrac: Float,
        maxXFrac: Float = 0.95f,
        maxYFrac: Float = 0.95f
    ) {
        val scale = settings.value.hudGridScale
        val sw = widget.spanCols.coerceIn(1, scale.cols)
        val sh = widget.spanRows.coerceIn(1, scale.rows)
        val clampedX = xFrac.coerceIn(0f, maxXFrac.coerceIn(0f, 1f))
        val clampedY = yFrac.coerceIn(0f, maxYFrac.coerceIn(0f, 1f))
        val nearestCol = (clampedX * scale.cols).roundToInt().coerceIn(0, (scale.cols - sw).coerceAtLeast(0))
        val nearestRow = (clampedY * scale.rows).roundToInt().coerceIn(0, (scale.rows - sh).coerceAtLeast(0))
        viewModelScope.launch {
            hudDao.updateWidget(
                widget.copy(
                    freeformXFraction = clampedX,
                    freeformYFraction = clampedY,
                    gridCol = nearestCol,
                    gridRow = nearestRow
                )
            )
        }
    }

    fun deleteWidget(widgetId: Long) {
        viewModelScope.launch { hudDao.deleteWidget(widgetId) }
    }

    // --- Settings Actions ---
    fun toggleGridSnap() {
        viewModelScope.launch { prefs.setGridSnapEnabled(!settings.value.gridSnapEnabled) }
    }

    fun toggleWidgetBorders() {
        viewModelScope.launch { prefs.setShowWidgetBorders(!settings.value.showWidgetBorders) }
    }

    fun setHudTheme(preset: HudThemePreset) {
        viewModelScope.launch { prefs.setHudTheme(preset) }
    }

    fun setHudCanvasBackdrop(backdrop: HudCanvasBackdrop) {
        viewModelScope.launch { prefs.setHudCanvasBackdrop(backdrop) }
    }

    fun setHudCardStyle(style: HudCardStyle) {
        viewModelScope.launch { prefs.setHudCardStyle(style) }
    }

    fun setHudGridScale(scale: HudGridScale) {
        viewModelScope.launch { prefs.setHudGridScale(scale) }
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

    fun refreshLocalAdapters() {
        val ctx = getApplication<Application>()
        _btPairedDevices.value = BluetoothClassicObdTransport.listPairedAdapters(ctx)
        _usbDevices.value = UsbSerialObdTransport.listAttachedAdapters(ctx)
    }

    fun scanBleAdapters() {
        if (_bleScanning.value) return
        _bleScanning.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _bleScannedDevices.value = BluetoothLeObdTransport.scanNearbyAdapters(getApplication())
            } finally {
                _bleScanning.value = false
            }
        }
    }

    fun selectConnectionType(type: ObdConnectionType) {
        viewModelScope.launch {
            val prev = settings.value
            prefs.setConnectionType(type)
            val next = prev.copy(connectionType = type)
            if (!_backgroundServiceActive.value) {
                resumeBackgroundService()
            } else {
                reconnectTransport(next)
            }
        }
    }

    fun selectBluetoothDevice(candidate: BtDeviceCandidate) {
        viewModelScope.launch {
            prefs.updateBluetoothTarget(candidate.address, candidate.name)
            prefs.setConnectionType(ObdConnectionType.BLUETOOTH_CLASSIC)
            val next = settings.value.copy(
                connectionType = ObdConnectionType.BLUETOOTH_CLASSIC,
                btDeviceAddress = candidate.address,
                btDeviceName = candidate.name
            )
            if (!_backgroundServiceActive.value) {
                resumeBackgroundService()
            } else {
                reconnectTransport(next)
            }
        }
    }

    fun selectBleDevice(candidate: BtDeviceCandidate) {
        viewModelScope.launch {
            prefs.updateBleTarget(candidate.address, candidate.name)
            prefs.setConnectionType(ObdConnectionType.BLUETOOTH_LE)
            val next = settings.value.copy(
                connectionType = ObdConnectionType.BLUETOOTH_LE,
                bleDeviceAddress = candidate.address,
                bleDeviceName = candidate.name
            )
            if (!_backgroundServiceActive.value) {
                resumeBackgroundService()
            } else {
                reconnectTransport(next)
            }
        }
    }

    fun selectUsbDevice(deviceId: Int, baudRate: Int) {
        viewModelScope.launch {
            prefs.updateUsbConfig(deviceId, baudRate)
            prefs.setConnectionType(ObdConnectionType.USB_SERIAL)
            val next = settings.value.copy(
                connectionType = ObdConnectionType.USB_SERIAL,
                usbDeviceId = deviceId,
                usbBaudRate = baudRate
            )
            if (!_backgroundServiceActive.value) {
                resumeBackgroundService()
            } else {
                reconnectTransport(next)
            }
        }
    }

    fun requestUsbAdapterPermission(deviceId: Int) {
        val usbMgr = getApplication<Application>().getSystemService(android.hardware.usb.UsbManager::class.java) ?: return
        val dev = usbMgr.deviceList.values.firstOrNull { it.deviceId == deviceId } ?: return
        UsbSerialObdTransport.requestPermission(getApplication(), dev)
    }

    fun updateConnectionSettings(host: String, port: Int, dualCell: Boolean) {
        viewModelScope.launch {
            prefs.updateWifiEndpoint(host, port)
            prefs.setConnectionType(ObdConnectionType.WIFI_TCP)
            prefs.setDualCellularKeepalive(dualCell)
            val next = settings.value.copy(
                connectionType = ObdConnectionType.WIFI_TCP,
                wifiHost = host,
                wifiPort = port,
                dualCellularKeepalive = dualCell
            )
            if (!_backgroundServiceActive.value) {
                resumeBackgroundService()
            } else {
                reconnectTransport(next)
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

    fun setMapStylePreset(style: MapStylePreset) {
        viewModelScope.launch { prefs.setMapStylePreset(style) }
    }

    fun setMapCameraMode(mode: MapCameraMode) {
        viewModelScope.launch { prefs.setMapCameraMode(mode) }
    }

    fun setMapCursorStyle(style: MapCursorStyle) {
        viewModelScope.launch { prefs.setMapCursorStyle(style) }
    }

    fun toggleShareCardMetric(metric: ShareCardMetric) {
        val current = settings.value.shareCardMetrics
        val updated = if (metric in current) {
            if (current.size <= 1) return
            current - metric
        } else {
            if (current.size >= ShareCardMetric.MAX_SELECTED) return
            current + metric
        }
        viewModelScope.launch { prefs.setShareCardMetrics(updated) }
    }

    fun resetShareCardMetricsToDefault() {
        viewModelScope.launch { prefs.setShareCardMetrics(ShareCardMetric.DEFAULT_SELECTION) }
    }

    fun setHighContrastMapRoads(enabled: Boolean) {
        viewModelScope.launch { prefs.setHighContrastMapRoads(enabled) }
    }

    fun setGaugeSmoothingMode(mode: GaugeSmoothingMode) {
        viewModelScope.launch { prefs.setGaugeSmoothingMode(mode) }
    }

    fun setLiveMicroMotion(enabled: Boolean) {
        viewModelScope.launch { prefs.setLiveMicroMotion(enabled) }
    }

    fun setFuelCoastingMode(mode: FuelCoastingMode) {
        viewModelScope.launch {
            prefs.setFuelCoastingMode(mode)
            engine?.setFuelCoastingMode(mode)
        }
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

    fun addManualCustomPid(name: String, modePidHex: String, formula: String, unit: String, headerHex: String? = null) {
        val clean = modePidHex.replace(" ", "").uppercase()
        val cleanHeader = headerHex?.replace(" ", "")?.uppercase()?.takeIf { it.isNotBlank() }
        if (name.isBlank() || clean.length < 4 || formula.isBlank()) return
        val key = "custom_${clean.lowercase()}"
        viewModelScope.launch {
            customPidDao.upsertAll(
                listOf(
                    CustomPidEntity(key, name.trim(), name.trim().take(10), clean, formula.trim(), 0.0, 200.0, unit.trim(), cleanHeader)
                )
            )
        }
    }

    fun pickerSensorsAlphabetical(): List<PickerSensorOption> {
        val effectiveSupported = _supportedPids.value.ifEmpty { activeVehicle.value?.savedSupportedPids().orEmpty() }
        val list = mutableListOf<PickerSensorOption>()

        ObdPidRegistry.standardPids.forEach { pid ->
            val avail = effectiveSupported.isEmpty() || pid.pidHex in effectiveSupported
            list += PickerSensorOption(
                item = SensorCatalogItem(pid.key, pid.name, pid.shortName, "01${pid.pidHex}", pid.unit, pid.quantity, pid.minValue, pid.maxValue),
                available = avail
            )
        }

        DerivedSensorCatalog.all
            .filter { it.key !in setOf("gps_speed", "accel_g") }
            .forEach { spec ->
                val avail = DerivedSensorCatalog.isAvailable(spec, effectiveSupported)
                list += PickerSensorOption(
                    item = SensorCatalogItem(spec.key, spec.name, spec.shortName, spec.sourceLabel, spec.defaultUnit, spec.quantity, spec.minValue, spec.maxValue),
                    available = avail
                )
            }

        listOf(
            SensorCatalogItem("gps_speed", "GPS Speed", "GPS Spd", "GPS", "km/h", SensorQuantity.SPEED, 0.0, 220.0),
            SensorCatalogItem("accel_g", "Acceleration G-Force", "Accel", "IMU", "g", SensorQuantity.RAW, -1.5, 1.5),
            SensorCatalogItem("minimap", "Map (Live GPS)", "Map", "MAP", "", SensorQuantity.RAW, 0.0, 1.0, isSpecialWidget = true),
            SensorCatalogItem("fuel_ctrl", "KPL · AVG", "KPL · AVG", "ALL-TIME", "km/L", SensorQuantity.FUEL_ECONOMY_KPL, 0.0, 40.0, isSpecialWidget = true),
            SensorCatalogItem("dtc_checker", "DTC Checker & Clear", "DTC", "03/04", "", SensorQuantity.RAW, 0.0, 1.0, isSpecialWidget = true),
            SensorCatalogItem("timer_0100", "0–100 km/h Sprint Timer", "0–100", "PERF", "s", SensorQuantity.RAW, 0.0, 20.0, isSpecialWidget = true),
            SensorCatalogItem("data_logger", "Run Telemetry Logger", "Run Log", "LOG", "", SensorQuantity.RAW, 0.0, 1.0, isSpecialWidget = true)
        ).forEach { item ->
            list += PickerSensorOption(item = item, available = true)
        }

        customPids.value.forEach { cp ->
            list += PickerSensorOption(
                item = SensorCatalogItem(cp.key, cp.name, cp.shortName, cp.modeAndPidHex, cp.unit, SensorQuantity.RAW, cp.minValue, cp.maxValue),
                available = true
            )
        }

        return list.sortedBy { it.item.label.lowercase() }
    }

    fun setPickerLivePreview(active: Boolean) {
        if (active) {
            val allKeys = buildSet {
                ObdPidRegistry.standardPids.forEach { add(it.key) }
                DerivedSensorCatalog.all.forEach { add(it.key) }
                customPids.value.forEach { add(it.key) }
                addAll(listOf("gps_speed", "accel_g"))
            }
            engine?.setBackgroundIdleMode(false)
            engine?.setActiveSensors(allKeys)
        } else {
            restoreVisibleCanvasPolling()
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
            SensorCatalogItem("gps_speed", "GPS Speed", "GPS Spd", "GPS", "km/h", SensorQuantity.SPEED, 0.0, 220.0),
            SensorCatalogItem("accel_g", "Acceleration G-Force", "Accel", "IMU", "g", SensorQuantity.RAW, -1.5, 1.5),
            SensorCatalogItem("minimap", "Map (Live GPS)", "Map", "MAP", "", SensorQuantity.RAW, 0.0, 1.0, isSpecialWidget = true),
            SensorCatalogItem("fuel_ctrl", "KPL · AVG", "KPL · AVG", "ALL-TIME", "km/L", SensorQuantity.FUEL_ECONOMY_KPL, 0.0, 40.0, isSpecialWidget = true),
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

    private fun buildAllKnownCatalogLookup(customList: List<CustomPidEntity>): Map<String, SensorCatalogItem> {
        val map = HashMap<String, SensorCatalogItem>(80)
        ObdPidRegistry.standardPids.forEach {
            map[it.key] = SensorCatalogItem(it.key, it.name, it.shortName, "01${it.pidHex}", it.unit, it.quantity, it.minValue, it.maxValue)
        }
        DerivedSensorCatalog.all.forEach {
            map[it.key] = SensorCatalogItem(it.key, it.name, it.shortLabelForCatalog(), it.sourceLabel, it.defaultUnit, it.quantity, it.minValue, it.maxValue)
        }
        map["gps_speed"] = SensorCatalogItem("gps_speed", "GPS Speed", "GPS Spd", "GPS", "km/h", SensorQuantity.SPEED, 0.0, 220.0)
        map["accel_g"] = SensorCatalogItem("accel_g", "Acceleration G-Force", "Accel", "IMU", "g", SensorQuantity.RAW, -1.5, 1.5)
        map["minimap"] = SensorCatalogItem("minimap", "Map (Live GPS)", "Map", "MAP", "", SensorQuantity.RAW, 0.0, 1.0, isSpecialWidget = true)
        map["fuel_ctrl"] = SensorCatalogItem("fuel_ctrl", "KPL · AVG", "KPL · AVG", "ALL-TIME", "km/L", SensorQuantity.FUEL_ECONOMY_KPL, 0.0, 40.0, isSpecialWidget = true)
        map["dtc_checker"] = SensorCatalogItem("dtc_checker", "DTC Checker & Clear (03/07/04)", "DTC", "03/04", "", SensorQuantity.RAW, 0.0, 1.0, isSpecialWidget = true)
        map["timer_0100"] = SensorCatalogItem("timer_0100", "0–100 km/h Performance Timer", "0–100", "PERF", "s", SensorQuantity.RAW, 0.0, 20.0, isSpecialWidget = true)
        map["data_logger"] = SensorCatalogItem("data_logger", "Run Logger Quick Toggle", "Run Log", "LOG", "", SensorQuantity.RAW, 0.0, 1.0, isSpecialWidget = true)
        customList.forEach {
            map[it.key] = SensorCatalogItem(it.key, it.name, it.shortName, it.modeAndPidHex, it.unit, SensorQuantity.RAW, it.minValue, it.maxValue)
        }
        return map
    }

    fun catalogItemFor(key: String): SensorCatalogItem {
        return cachedCatalogLookup[key]
            ?: SensorCatalogItem(key, key.uppercase(), key.uppercase(), "PID", "", SensorQuantity.RAW, 0.0, 100.0)
    }

    fun activeFuelType(): FuelType =
        activeVehicle.value?.let { runCatching { FuelType.valueOf(it.fuelType) }.getOrNull() } ?: FuelType.GASOLINE

    fun convertValueOnly(
        valueMetric: Double?,
        quantity: SensorQuantity,
        prefs: UnitPreferences,
        fuelType: FuelType
    ): Double? {
        if (valueMetric == null || quantity == SensorQuantity.RAW) return valueMetric
        return UnitConverter.convert(valueMetric, quantity, prefs, fuelType).first
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
        LoggingForegroundService.setTerminateListener(null)
        LoggingForegroundService.stop(getApplication())
        accelTracker.stop()
        locationTracker.dispose()
        wifiBinder.unregister()
        viewModelScope.launch {
            flushActiveVehicleStats()
            engine?.stop()
        }
    }

    private data class BackgroundLifecycleState(
        val serviceActive: Boolean,
        val onHud: Boolean,
        val recording: Boolean,
        val inForeground: Boolean,
        val settings: UserSettings
    )

    companion object {
        private val BACKGROUND_KEEPALIVE_KEYS = setOf(
            "rpm", "obd_speed", "rel_throttle", "engine_load", "coolant", "voltage", "kpl_road_avg"
        )

        private val RECORDING_EXTRA_KEYS = setOf(
            "rpm", "obd_speed", "throttle", "rel_throttle", "maf", "map",
            "iat", "baro", "engine_load", "stft_b1", "ltft_b1",
            "coolant", "voltage", "timing_advance", "kpl_road_avg"
        )
    }
}
