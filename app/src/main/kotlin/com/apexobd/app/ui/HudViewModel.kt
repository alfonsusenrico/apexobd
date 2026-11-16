package com.apexobd.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.apexobd.app.data.db.ApexDatabase
import com.apexobd.app.data.db.CustomPidEntity
import com.apexobd.app.data.db.HudPageEntity
import com.apexobd.app.data.db.HudWidgetEntity
import com.apexobd.app.data.db.TelemetrySampleEntity
import com.apexobd.app.data.db.TripSessionEntity
import com.apexobd.app.data.db.WidgetDisplayType
import com.apexobd.app.data.prefs.AppPreferences
import com.apexobd.app.data.prefs.UserSettings
import com.apexobd.app.platform.GpsSnapshot
import com.apexobd.app.platform.LocationTracker
import com.apexobd.app.platform.LoggingForegroundService
import com.apexobd.app.platform.TripExporter
import com.apexobd.app.platform.WifiNetworkBinder
import com.apexobd.core.formula.CustomPidDefinition
import com.apexobd.core.formula.TorqueCsvImporter
import com.apexobd.core.protocol.DiagnosticReport
import com.apexobd.core.protocol.DtcDecoder
import com.apexobd.core.protocol.ObdPidRegistry
import com.apexobd.core.scheduler.ConnectionState
import com.apexobd.core.scheduler.ObdPollingEngine
import com.apexobd.core.scheduler.Timer0100State
import com.apexobd.core.scheduler.Timer0100Tracker
import com.apexobd.core.transport.SimulatedObdTransport
import com.apexobd.core.transport.WifiTcpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    val minValue: Double,
    val maxValue: Double
)

data class HudPageWithWidgets(
    val page: HudPageEntity,
    val widgets: List<HudWidgetEntity>
)

class HudViewModel(application: Application) : AndroidViewModel(application) {

    private val db = ApexDatabase.getInstance(application)
    private val hudDao = db.hudDao()
    private val customPidDao = db.customPidDao()
    private val tripDao = db.tripDao()
    private val prefs = AppPreferences(application)
    private val wifiBinder = WifiNetworkBinder(application)
    val locationTracker = LocationTracker(application)

    private var engine: ObdPollingEngine? = null
    private var loggingJob: Job? = null
    private val timer0100Tracker = Timer0100Tracker()

    val settings: StateFlow<UserSettings> = prefs.settings.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        UserSettings()
    )

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

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _supportedPids = MutableStateFlow<Set<String>>(emptySet())
    val supportedPids: StateFlow<Set<String>> = _supportedPids.asStateFlow()

    private val _pollingHz = MutableStateFlow(0)
    val pollingHz: StateFlow<Int> = _pollingHz.asStateFlow()

    private val _liveValues = MutableStateFlow<Map<String, Double>>(emptyMap())
    val liveValues: StateFlow<Map<String, Double>> = _liveValues.asStateFlow()

    private val _historyBuffers = MutableStateFlow<Map<String, List<Double>>>(emptyMap())
    val historyBuffers: StateFlow<Map<String, List<Double>>> = _historyBuffers.asStateFlow()

    private val _diagnosticReport = MutableStateFlow<DiagnosticReport?>(null)
    val diagnosticReport: StateFlow<DiagnosticReport?> = _diagnosticReport.asStateFlow()

    private val _vin = MutableStateFlow<String?>(null)
    val vin: StateFlow<String?> = _vin.asStateFlow()

    private val _timer0100 = MutableStateFlow(Timer0100State())
    val timer0100: StateFlow<Timer0100State> = _timer0100.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private var activeSessionId: Long? = null

    init {
        wifiBinder.register()
        locationTracker.startUpdates()
        viewModelScope.launch {
            val initial = prefs.settings.first()
            reconnectTransport(initial)
        }
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
        viewModelScope.launch {
            pagesWithWidgets.collect { pages ->
                val keys = pages.flatMap { it.widgets }.map { it.sensorKey }.toSet()
                engine?.setActiveSensors(keys)
            }
        }
    }

    fun reconnectWithCurrentSettings() {
        viewModelScope.launch {
            reconnectTransport(settings.value)
        }
    }

    private suspend fun reconnectTransport(s: UserSettings) {
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
        engine = newEngine

        val keys = pagesWithWidgets.value.flatMap { it.widgets }.map { it.sensorKey }.toSet()
        newEngine.setActiveSensors(keys)

        viewModelScope.launch { newEngine.connectionState.collect { _connectionState.value = it } }
        viewModelScope.launch { newEngine.supportedPids.collect { _supportedPids.value = it } }
        viewModelScope.launch { newEngine.pollingHz.collect { _pollingHz.value = it } }
        viewModelScope.launch {
            newEngine.liveValues.collect { map ->
                val obdSpd = map["obd_speed"] ?: 0.0
                locationTracker.updateSimulatedStep(obdSpd)
                val gps = locationTracker.gps.value
                val merged = map.toMutableMap()
                merged["gps_speed"] = if (gps.hasRealFix) gps.speedKmh else obdSpd
                _liveValues.value = merged
                appendHistory(merged)
                _timer0100.value = timer0100Tracker.onSpeedSample(merged["gps_speed"] ?: 0.0)
            }
        }
        newEngine.start()
    }

    private fun appendHistory(values: Map<String, Double>) {
        val current = _historyBuffers.value.toMutableMap()
        for ((k, v) in values) {
            val buf = (current[k] ?: emptyList()).toMutableList()
            buf.add(v)
            if (buf.size > 28) buf.removeAt(0)
            current[k] = buf
        }
        _historyBuffers.value = current
    }

    fun scanDiagnostics() {
        val client = engine?.client ?: return
        viewModelScope.launch {
            _diagnosticReport.value = DtcDecoder.scanAll(client)
            if (_vin.value == null) {
                _vin.value = DtcDecoder.readVin(client)
            }
        }
    }

    fun clearDtcs() {
        val client = engine?.client ?: return
        viewModelScope.launch {
            if (DtcDecoder.clearDtcs(client)) {
                _diagnosticReport.value = DtcDecoder.scanAll(client)
            }
        }
    }

    fun reset0100Best() {
        _timer0100.value = timer0100Tracker.resetBest()
    }

    fun toggleRecording() {
        if (_isRecording.value) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        if (_isRecording.value) return
        _isRecording.value = true
        LoggingForegroundService.start(getApplication())

        loggingJob = viewModelScope.launch(Dispatchers.IO) {
            val startMs = System.currentTimeMillis()
            val sessionId = tripDao.insertSession(TripSessionEntity(startedAtMs = startMs))
            activeSessionId = sessionId

            var distanceKm = 0.0
            var maxSpeed = 0.0
            var peakRpm = 0.0
            var count = 0
            var prevGps: GpsSnapshot? = null

            while (_isRecording.value) {
                val gps = locationTracker.gps.value
                val vals = _liveValues.value
                val speed = vals["gps_speed"] ?: gps.speedKmh
                val rpm = vals["rpm"] ?: 0.0
                if (speed > maxSpeed) maxSpeed = speed
                if (rpm > peakRpm) peakRpm = rpm

                val prev = prevGps
                if (prev != null) {
                    distanceKm += haversineKm(prev.latitude, prev.longitude, gps.latitude, gps.longitude)
                }
                prevGps = gps

                val json = TripExporter.encodePayload(vals)

                tripDao.insertSample(
                    TelemetrySampleEntity(
                        sessionId = sessionId,
                        timestampMs = System.currentTimeMillis(),
                        latitude = gps.latitude,
                        longitude = gps.longitude,
                        gpsSpeedKmh = speed,
                        payloadJson = json
                    )
                )
                count++
                tripDao.updateSession(
                    TripSessionEntity(
                        id = sessionId,
                        startedAtMs = startMs,
                        endedAtMs = null,
                        distanceKm = distanceKm,
                        maxSpeedKmh = maxSpeed,
                        peakRpm = peakRpm,
                        best0100Sec = _timer0100.value.bestRunSec,
                        sampleCount = count
                    )
                )
                delay(250L)
            }
        }
    }

    private fun stopRecording() {
        _isRecording.value = false
        loggingJob?.cancel()
        loggingJob = null
        LoggingForegroundService.stop(getApplication())
        val sid = activeSessionId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val existing = tripSessions.value.firstOrNull { it.id == sid } ?: return@launch
            tripDao.updateSession(existing.copy(endedAtMs = System.currentTimeMillis()))
            activeSessionId = null
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

    // Dynamic Page & Component Management
    fun addPage(title: String, onCreated: (Int) -> Unit) {
        viewModelScope.launch {
            val currentCount = pagesWithWidgets.value.size
            val pageId = hudDao.insertPage(HudPageEntity(title = title, sortOrder = currentCount))
            hudDao.insertWidgets(
                listOf(
                    HudWidgetEntity(pageId = pageId, sensorKey = "rpm", displayType = WidgetDisplayType.ANALOG_DIAL.name, spanCols = 2, spanRows = 2, sortOrder = 0),
                    HudWidgetEntity(pageId = pageId, sensorKey = "gps_speed", displayType = WidgetDisplayType.DIGITAL.name, spanCols = 2, spanRows = 1, sortOrder = 1)
                )
            )
            onCreated(currentCount)
        }
    }

    fun deletePage(pageId: Long) {
        viewModelScope.launch {
            if (pagesWithWidgets.value.size > 1) {
                hudDao.deletePage(pageId)
            }
        }
    }

    fun addWidget(pageId: Long, sensorKey: String, displayType: WidgetDisplayType, spanCols: Int, spanRows: Int) {
        viewModelScope.launch {
            val existing = hudDao.getWidgetsForPage(pageId)
            hudDao.insertWidget(
                HudWidgetEntity(
                    pageId = pageId,
                    sensorKey = sensorKey,
                    displayType = displayType.name,
                    spanCols = spanCols,
                    spanRows = spanRows,
                    sortOrder = existing.size
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
        val next = when (widget.spanCols to widget.spanRows) {
            1 to 1 -> 2 to 1
            2 to 1 -> 2 to 2
            else -> 1 to 1
        }
        viewModelScope.launch {
            hudDao.updateWidget(widget.copy(spanCols = next.first, spanRows = next.second))
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
            val reindexed = list.mapIndexed { i, w -> w.copy(sortOrder = i) }
            hudDao.updateWidgets(reindexed)
        }
    }

    fun deleteWidget(widgetId: Long) {
        viewModelScope.launch { hudDao.deleteWidget(widgetId) }
    }

    // Settings & Custom PIDs
    fun updateWifiEndpoint(host: String, port: Int, useSim: Boolean) {
        viewModelScope.launch {
            prefs.updateWifiEndpoint(host, port)
            prefs.setUseSimulator(useSim)
            reconnectTransport(settings.value.copy(wifiHost = host, wifiPort = port, useSimulator = useSim))
        }
    }

    fun toggleHudMirror() {
        viewModelScope.launch { prefs.setHudMirror(!settings.value.hudMirror) }
    }

    fun toggleImperialUnits() {
        viewModelScope.launch { prefs.setImperialUnits(!settings.value.imperialUnits) }
    }

    fun importTorqueCsv(csv: String) {
        val parsed = TorqueCsvImporter.parseCsv(csv)
        if (parsed.isEmpty()) return
        viewModelScope.launch {
            customPidDao.upsertAll(
                parsed.map {
                    CustomPidEntity(
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

    fun addManualCustomPid(name: String, modePidHex: String, formula: String, unit: String) {
        val clean = modePidHex.replace(" ", "").uppercase()
        if (name.isBlank() || clean.length < 4 || formula.isBlank()) return
        val key = "custom_${clean.lowercase()}"
        viewModelScope.launch {
            customPidDao.upsertAll(
                listOf(
                    CustomPidEntity(
                        key = key,
                        name = name.trim(),
                        shortName = name.trim().take(10),
                        modeAndPidHex = clean,
                        formula = formula.trim(),
                        minValue = 0.0,
                        maxValue = 200.0,
                        unit = unit.trim()
                    )
                )
            )
        }
    }

    fun fullCatalog(): List<SensorCatalogItem> {
        val builtInSpecials = listOf(
            SensorCatalogItem("gps_speed", "GPS Speed", "GPS Spd", "GPS", "km/h", 0.0, 240.0),
            SensorCatalogItem("boost_bar", "Boost / Vacuum", "Boost", "CALC", "bar", -1.0, 2.5),
            SensorCatalogItem("hp", "Estimated Horsepower", "Power", "CALC", "HP", 0.0, 400.0),
            SensorCatalogItem("torque", "Estimated Torque", "Torque", "CALC", "Nm", 0.0, 600.0),
            SensorCatalogItem("minimap", "Google Mini-Map (Live GPS)", "Mini-Map", "MAP", "", 0.0, 1.0),
            SensorCatalogItem("dtc_checker", "DTC Checker & Clear (03/07/04)", "DTC", "03/04", "", 0.0, 1.0),
            SensorCatalogItem("timer_0100", "0–100 km/h Performance Timer", "0–100", "PERF", "s", 0.0, 20.0),
            SensorCatalogItem("data_logger", "Data Logger Quick-Record", "Logger", "LOG", "", 0.0, 1.0)
        )
        val standard = ObdPidRegistry.standardPids.map {
            SensorCatalogItem(it.key, it.name, it.shortName, "01${it.pidHex}", it.unit, it.minValue, it.maxValue)
        }
        val customs = customPids.value.map {
            SensorCatalogItem(it.key, it.name, it.shortName, it.modeAndPidHex, it.unit, it.minValue, it.maxValue)
        }
        return builtInSpecials + standard + customs
    }

    fun catalogItemFor(key: String): SensorCatalogItem {
        return fullCatalog().firstOrNull { it.key == key }
            ?: SensorCatalogItem(key, key.uppercase(), key.uppercase(), "PID", "", 0.0, 100.0)
    }

    override fun onCleared() {
        super.onCleared()
        locationTracker.stopUpdates()
        wifiBinder.unregister()
        viewModelScope.launch { engine?.stop() }
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}
