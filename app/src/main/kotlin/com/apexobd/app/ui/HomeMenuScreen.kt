package com.apexobd.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apexobd.app.platform.TripCardRenderer
import com.apexobd.app.platform.TripExporter
import com.apexobd.app.ui.components.ApexBrandLogo
import com.apexobd.app.ui.components.DualMapCanvas
import com.apexobd.app.ui.theme.TorqueEvoColors
import com.apexobd.core.model.FuelType
import com.apexobd.core.model.TransmissionType
import com.apexobd.core.model.UnitConverter
import com.apexobd.core.model.VehicleBodyType
import com.apexobd.core.protocol.FuelEconomyTracker
import com.apexobd.core.protocol.TripRunCalculator
import com.apexobd.core.scheduler.ConnectionState
import com.apexobd.core.transport.ObdConnectionType
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private enum class ActiveHomeSheet { NONE, ADAPTER, VEHICLE, REFUEL, FAULTS, RUNS_AND_MAPS, OFFLINE_MAPS, ECU_PIDS, SETTINGS }

@Composable
fun HomeMenuScreen(
    viewModel: HudViewModel,
    onOpenHudCanvas: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val vehicles by viewModel.vehicles.collectAsStateWithLifecycle()
    val activeVehicle by viewModel.activeVehicle.collectAsStateWithLifecycle()
    val refuels by viewModel.refuelLogs.collectAsStateWithLifecycle()
    val pages by viewModel.pagesWithWidgets.collectAsStateWithLifecycle()
    val sessions by viewModel.tripSessions.collectAsStateWithLifecycle()
    val segmentsBySession by viewModel.tripSegmentsBySession.collectAsStateWithLifecycle()
    val isRecording by viewModel.isRecording.collectAsStateWithLifecycle()
    val isRecordingPaused by viewModel.isRecordingPaused.collectAsStateWithLifecycle()
    val tripRunMetrics by viewModel.tripRunMetrics.collectAsStateWithLifecycle()
    val activeRunSegments by viewModel.activeRunSegments.collectAsStateWithLifecycle()
    val gps by viewModel.locationTracker.gps.collectAsStateWithLifecycle()
    val report by viewModel.diagnosticReport.collectAsStateWithLifecycle()
    val diagnosticError by viewModel.diagnosticError.collectAsStateWithLifecycle()
    val diagnosticScanning by viewModel.diagnosticScanning.collectAsStateWithLifecycle()
    val explorerStatus by viewModel.explorerStatus.collectAsStateWithLifecycle()
    val deepScanRunning by viewModel.deepScanRunning.collectAsStateWithLifecycle()
    val ecuDumpReady by viewModel.ecuDumpReady.collectAsStateWithLifecycle()
    val vin by viewModel.vin.collectAsStateWithLifecycle()
    val supportedPids by viewModel.supportedPids.collectAsStateWithLifecycle()
    val liveValues by viewModel.ecuExplorerValues.collectAsStateWithLifecycle()
    val offlineRegions by viewModel.offlineMapManager.regions.collectAsStateWithLifecycle()
    val pmtilesPacks by viewModel.offlineMapManager.pmtilesPacks.collectAsStateWithLifecycle()
    val offlineMsg by viewModel.offlineMapManager.statusMessage.collectAsStateWithLifecycle()
    val btPairedDevices by viewModel.btPairedDevices.collectAsStateWithLifecycle()
    val bleScannedDevices by viewModel.bleScannedDevices.collectAsStateWithLifecycle()
    val bleScanning by viewModel.bleScanning.collectAsStateWithLifecycle()
    val usbDevices by viewModel.usbDevices.collectAsStateWithLifecycle()
    val shareCardPreparing by viewModel.shareCardPreparing.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val backgroundServiceActive by viewModel.backgroundServiceActive.collectAsStateWithLifecycle()
    val ecuDistanceSinceClearKm by viewModel.ecuDistanceSinceClearKm.collectAsStateWithLifecycle()

    var activeSheet by remember { mutableStateOf(ActiveHomeSheet.NONE) }
    var startAddingNewVehicle by remember { mutableStateOf(false) }

    val unitPrefs = settings.toUnitPreferences()
    val fuelType = activeVehicle?.let { runCatching { FuelType.valueOf(it.fuelType) }.getOrNull() } ?: FuelType.GASOLINE

    // Load last saved trip GPS coordinates for compact Home map preview
    val latestTrip = sessions.firstOrNull()
    var lastTripTrail by remember(latestTrip?.id) { mutableStateOf<List<Pair<Double, Double>>>(emptyList()) }
    LaunchedEffect(latestTrip?.id) {
        if (latestTrip != null) {
            val samples = viewModel.getSamplesForSession(latestTrip.id)
            lastTripTrail = TripCardRenderer.extractValidGpsPoints(samples)
        } else {
            lastTripTrail = emptyList()
        }
    }

    val allTimeAvgKpl = activeVehicle?.allTimeAvgKpl() ?: 0.0
    val dteAvgKpl = activeVehicle?.let { v ->
        FuelEconomyTracker.computeConservativeDteKpl(
            allTimeTotalAvgKpl = allTimeAvgKpl.takeIf { it > 0.1 },
            recentDteKpl = v.recentDteKpl?.takeIf { it > 0.1 },
            remainingFuelLiters = v.estimatedRemainingFuelLiters,
            tankCapacityLiters = v.tankCapacityLiters,
            fullRefuelCycleKpl = v.fullRefuelCycleKpl?.takeIf { it > 0.1 }
        )
    }
    val (dispAvg, avgSym) = UnitConverter.convertFuelEconomy(allTimeAvgKpl, unitPrefs, fuelType)
    val (dispOdo, distSym) = UnitConverter.convertDistanceKm(activeVehicle?.allTimeOdometerKm ?: 0.0, unitPrefs)
    val estRangeKm: Double? = activeVehicle?.let { v ->
        FuelEconomyTracker.computeEstimatedRangeKm(
            remainingFuelLiters = v.estimatedRemainingFuelLiters,
            tankCapacityLiters = v.tankCapacityLiters,
            dteKpl = dteAvgKpl
        )
    }
    val dispRange: Double? = estRangeKm?.let { UnitConverter.convertDistanceKm(it, unitPrefs).first }
    val (dispRemFuel, volSym) = UnitConverter.convertVolume(activeVehicle?.estimatedRemainingFuelLiters ?: 0.0, unitPrefs, fuelType)
    val fuelLeftPct: Double? = activeVehicle?.let { v ->
        val cap = v.tankCapacityLiters.coerceAtLeast(1.0)
        ((v.estimatedRemainingFuelLiters / cap) * 100.0).coerceIn(0.0, 100.0)
    }
    val fuelLeftText = if (activeVehicle != null && fuelLeftPct != null) {
        "%.1f%s (%.1f%%)".format(dispRemFuel, volSym, fuelLeftPct)
    } else {
        "—"
    }

    val latestRefuel = refuels.firstOrNull()
    val refuelSub = if (latestRefuel != null) {
        val (rfVol, rfSym) = UnitConverter.convertVolume(latestRefuel.volumeLiters, unitPrefs, fuelType)
        val ronTxt = latestRefuel.ronValue?.let { " · RON $it" } ?: ""
        val cycTxt = latestRefuel.cycleAvgKpl?.let {
            val (cv, cs) = UnitConverter.convertFuelEconomy(it, unitPrefs, fuelType)
            " · %.1f %s".format(cv, cs)
        } ?: ""
        "%.1f %s%s%s".format(rfVol, rfSym, ronTxt, cycTxt)
    } else {
        "No entries"
    }

    val faultCount = (report?.confirmed?.size ?: 0) + (report?.pending?.size ?: 0)
    val dateFmt = remember { SimpleDateFormat("dd MMM", Locale.getDefault()) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(TorqueEvoColors.HomeBg)
            .windowInsetsPadding(
                WindowInsets.safeDrawing.union(WindowInsets.mandatorySystemGestures.only(WindowInsetsSides.Bottom))
            )
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        val isLandscape = maxWidth > maxHeight

        val headerRow: @Composable () -> Unit = {
            val adapterIcon = when (settings.connectionType) {
                ObdConnectionType.WIFI_TCP -> Icons.Default.Wifi
                ObdConnectionType.BLUETOOTH_CLASSIC, ObdConnectionType.BLUETOOTH_LE -> Icons.Default.Bluetooth
                ObdConnectionType.USB_SERIAL -> Icons.Default.Usb
            }
            val adapterTint = when (connectionState) {
                is ConnectionState.Connected -> TorqueEvoColors.NominalGreen
                is ConnectionState.Connecting -> TorqueEvoColors.ElectricBlue
                is ConnectionState.Reconnecting -> TorqueEvoColors.WarnAmber
                is ConnectionState.Disconnected -> if (backgroundServiceActive) TorqueEvoColors.ElectricBlue else TorqueEvoColors.CriticalRed
            }
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ApexBrandLogo.ComposeWordmark(height = 17.dp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier
                                .heightIn(min = 32.dp)
                                .widthIn(max = 178.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF101620))
                                .border(1.dp, adapterTint.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                                .clickable { activeSheet = ActiveHomeSheet.ADAPTER }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(adapterTint)
                            )
                            Icon(adapterIcon, contentDescription = "OBD2 Adapter", tint = adapterTint, modifier = Modifier.size(13.dp))
                            Text(
                                text = settings.adapterChipLabel(),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = adapterTint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF101620))
                                .border(1.dp, TorqueEvoColors.SubtleBorder, RoundedCornerShape(8.dp))
                                .clickable { activeSheet = ActiveHomeSheet.SETTINGS },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "App Settings",
                                tint = TorqueEvoColors.TextPrimary,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }
                HorizontalDivider(color = TorqueEvoColors.SubtleBorder)
            }
        }

        val hudDashboardHeroCard: @Composable (Modifier) -> Unit = { mod ->
            Box(
                modifier = mod
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0A0D13))
                    .border(1.dp, TorqueEvoColors.ElectricBlue.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                    .clickable { onOpenHudCanvas() }
                    .padding(18.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "HUD Dashboard",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = TorqueEvoColors.TextPrimary
                    )
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(TorqueEvoColors.ElectricBlue)
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "Open HUD",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF020810)
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = Color(0xFF020810),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        val lastRunMapCard: @Composable (Modifier) -> Unit = { mod ->
            if (isRecording) {
                val (td, ts) = UnitConverter.convertDistanceKm(tripRunMetrics.distanceKm, unitPrefs)
                val (tk, ks) = UnitConverter.convertFuelEconomy(tripRunMetrics.avgKpl ?: 0.0, unitPrefs, fuelType)
                val clockTxt = TripRunCalculator.formatDurationClock(tripRunMetrics.elapsedSec)
                val legCount = maxOf(tripRunMetrics.segmentCount, activeRunSegments.size, 1)
                val statusCol = if (isRecordingPaused) TorqueEvoColors.WarnAmber else TorqueEvoColors.NominalGreen
                val statusText = if (isRecordingPaused) {
                    "RUN PAUSED · LEG ${tripRunMetrics.currentSegmentIndex} ($legCount LEGS)"
                } else {
                    "RECORDING RUN · LEG ${tripRunMetrics.currentSegmentIndex}"
                }
                val kplTxt = if ((tripRunMetrics.avgKpl ?: 0.0) > 0.05) " · %.1f %s".format(tk, ks) else ""
                val activeMetricLine = "%.2f %s · %s%s".format(td, ts, clockTxt, kplTxt)
                val activeTrail = gps.trail.ifEmpty { lastTripTrail }
                val mapCenterLat = activeTrail.lastOrNull()?.first ?: gps.latitude
                val mapCenterLon = activeTrail.lastOrNull()?.second ?: gps.longitude

                Row(
                    modifier = mod
                        .clip(RoundedCornerShape(12.dp))
                        .background(TorqueEvoColors.ElevatedDark)
                        .border(1.2.dp, statusCol.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
                        .padding(9.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = statusText,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.4.sp,
                                color = statusCol,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = activeMetricLine,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = TorqueEvoColors.TextPrimary,
                                style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isRecordingPaused) "▶ Resume Leg ${tripRunMetrics.currentSegmentIndex + 1}" else "⏸ Pause",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.Black,
                                maxLines = 1,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isRecordingPaused) TorqueEvoColors.ElectricBlue else TorqueEvoColors.WarnAmber)
                                    .clickable { viewModel.togglePauseResumeRecording() }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                            Text(
                                text = "■ Finish Run",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFFDC2626))
                                    .clickable { viewModel.toggleRecording() }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .width(106.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, TorqueEvoColors.SubtleBorder, RoundedCornerShape(8.dp))
                    ) {
                        DualMapCanvas(
                            mapProvider = settings.mapProvider,
                            latitude = mapCenterLat,
                            longitude = mapCenterLon,
                            trail = activeTrail,
                            activePmtilesPath = settings.activePmtilesPath,
                            mapStylePreset = settings.mapStylePreset,
                            mapCameraMode = settings.mapCameraMode,
                            mapCursorStyle = settings.mapCursorStyle,
                            highContrastRoads = settings.highContrastMapRoads,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable { onOpenHudCanvas() }
                        )
                    }
                }
            } else {
                val tripMetricLine = if (latestTrip != null) {
                    val (td, ts) = UnitConverter.convertDistanceKm(latestTrip.distanceKm, unitPrefs)
                    val (tk, ks) = UnitConverter.convertFuelEconomy(latestTrip.avgKpl, unitPrefs, fuelType)
                    val mins = (latestTrip.effectiveDurationSec() / 60L).coerceAtLeast(1L)
                    val legNote = if (latestTrip.segmentCount > 1) " · ${latestTrip.segmentCount} legs" else ""
                    "%.1f %s · %.1f %s · %d min%s".format(td, ts, tk, ks, mins, legNote)
                } else {
                    "No recorded runs"
                }
                val mapCenterLat = lastTripTrail.lastOrNull()?.first ?: gps.latitude
                val mapCenterLon = lastTripTrail.lastOrNull()?.second ?: gps.longitude

                Row(
                    modifier = mod
                        .clip(RoundedCornerShape(12.dp))
                        .background(TorqueEvoColors.ElevatedDark)
                        .border(1.dp, TorqueEvoColors.SubtleBorder, RoundedCornerShape(12.dp))
                        .clickable { activeSheet = ActiveHomeSheet.RUNS_AND_MAPS }
                        .padding(9.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = latestTrip?.let { "LAST SAVED RUN · ${dateFmt.format(Date(it.startedAtMs)).uppercase()}" } ?: "LAST SAVED RUN",
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            color = TorqueEvoColors.TextSecondary,
                            maxLines = 1
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = tripMetricLine,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = TorqueEvoColors.TextPrimary,
                            style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Box(
                        modifier = Modifier
                            .width(114.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, TorqueEvoColors.SubtleBorder, RoundedCornerShape(8.dp))
                    ) {
                        DualMapCanvas(
                            mapProvider = settings.mapProvider,
                            latitude = mapCenterLat,
                            longitude = mapCenterLon,
                            trail = lastTripTrail,
                            activePmtilesPath = settings.activePmtilesPath,
                            mapStylePreset = settings.mapStylePreset,
                            mapCameraMode = settings.mapCameraMode,
                            mapCursorStyle = settings.mapCursorStyle,
                            highContrastRoads = settings.highContrastMapRoads,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable { activeSheet = ActiveHomeSheet.RUNS_AND_MAPS }
                        )
                    }
                }
            }
        }

        val carPhotoBmp = remember(activeVehicle?.photoUri) {
            activeVehicle?.photoUri?.takeIf { File(it).exists() }?.let { path ->
                runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
            }
        }
        val carProfileCard: @Composable () -> Unit = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(TorqueEvoColors.CarProfileSurface)
                    .border(1.dp, TorqueEvoColors.ElectricBlue.copy(alpha = 0.42f), RoundedCornerShape(12.dp))
                    .clickable {
                        startAddingNewVehicle = false
                        activeSheet = ActiveHomeSheet.VEHICLE
                    }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    vehicles.sortedBy { it.id }.forEach { v ->
                        val isSelected = v.id == activeVehicle?.id
                        val trShort = runCatching {
                            when (TransmissionType.valueOf(v.transmission)) {
                                TransmissionType.MANUAL -> "MT"
                                TransmissionType.AUTOMATIC -> "AT"
                                TransmissionType.CVT -> "CVT"
                                TransmissionType.DCT -> "DCT"
                                TransmissionType.SINGLE_SPEED_EV -> "EV"
                            }
                        }.getOrNull()?.let { " ($it)" } ?: ""
                        Text(
                            text = "${v.name}$trShort",
                            fontSize = 10.5.sp,
                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                            color = if (isSelected) Color.Black else TorqueEvoColors.TextPrimary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(7.dp))
                                .background(if (isSelected) TorqueEvoColors.ElectricBlue else Color(0xFF131922))
                                .border(
                                    1.dp,
                                    if (isSelected) TorqueEvoColors.ElectricBlue else TorqueEvoColors.SubtleBorder,
                                    RoundedCornerShape(7.dp)
                                )
                                .clickable { viewModel.selectVehicle(v.id) }
                                .padding(horizontal = 9.dp, vertical = 4.dp)
                        )
                    }
                    Text(
                        text = "+ Car",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = TorqueEvoColors.ElectricBlue,
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(TorqueEvoColors.ElectricBlue.copy(alpha = 0.12f))
                            .border(1.dp, TorqueEvoColors.ElectricBlue.copy(alpha = 0.45f), RoundedCornerShape(7.dp))
                            .clickable {
                                startAddingNewVehicle = true
                                activeSheet = ActiveHomeSheet.VEHICLE
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(TorqueEvoColors.ElectricBlue.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (carPhotoBmp != null) {
                                Image(
                                    bitmap = carPhotoBmp,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = TorqueEvoColors.ElectricBlue, modifier = Modifier.size(20.dp))
                            }
                        }
                        Column {
                            Text(activeVehicle?.name ?: "My Vehicle", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TorqueEvoColors.TextPrimary)
                            val carTypeLabel = activeVehicle?.let {
                                runCatching { VehicleBodyType.valueOf(it.carType).label }.getOrNull()
                            } ?: VehicleBodyType.MPV.label
                            val profileSubtitle = buildList {
                                activeVehicle?.engineCc?.takeIf { it > 0 }?.let { add("$it cc") }
                                activeVehicle?.modelYear?.takeIf { it > 0 }?.let { add("$it") }
                                add(carTypeLabel)
                            }.joinToString(" · ")
                            Text(
                                text = profileSubtitle,
                                fontSize = 10.sp,
                                color = TorqueEvoColors.TextSecondary,
                                maxLines = 1
                            )
                        }
                    }
                    Text(
                        "Edit",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TorqueEvoColors.TextPrimary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(Color(0xFF161A22))
                            .border(1.dp, TorqueEvoColors.SubtleBorder, RoundedCornerShape(7.dp))
                            .clickable {
                                startAddingNewVehicle = false
                                activeSheet = ActiveHomeSheet.VEHICLE
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }

        val statStripCard: @Composable () -> Unit = {
            val fuelLeftColor = when {
                fuelLeftPct == null -> TorqueEvoColors.TextPrimary
                fuelLeftPct < 15.0 -> TorqueEvoColors.CriticalRed
                fuelLeftPct < 25.0 -> TorqueEvoColors.WarnAmber
                else -> TorqueEvoColors.TextPrimary
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(TorqueEvoColors.ElevatedDark)
                    .border(1.dp, TorqueEvoColors.SubtleBorder, RoundedCornerShape(10.dp))
                    .clickable { activeSheet = ActiveHomeSheet.VEHICLE }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatCell("All time avg", if (allTimeAvgKpl > 0.1) "%.1f %s".format(dispAvg, avgSym) else "— $avgSym", TorqueEvoColors.ElectricBlue, Modifier.weight(0.95f))
                StatCell("Odometer", "${dispOdo.roundToInt()} $distSym", TorqueEvoColors.TextPrimary, Modifier.weight(0.95f))
                StatCell("Est. Range", dispRange?.let { "${it.roundToInt()} $distSym" } ?: "— $distSym", TorqueEvoColors.NominalGreen, Modifier.weight(0.85f))
                StatCell("Fuel Left", fuelLeftText, fuelLeftColor, Modifier.weight(1.35f))
            }
        }

        val modulesGrid: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    val draftRefuels = refuels.count { it.isDraft }
                    HomeModuleTile(
                        title = "Refuel Log",
                        subtitle = if (draftRefuels > 0) "$draftRefuels draft(s) to confirm" else refuelSub,
                        icon = Icons.Default.LocalGasStation,
                        tint = if (draftRefuels > 0) TorqueEvoColors.WarnAmber else TorqueEvoColors.NominalGreen,
                        bg = TorqueEvoColors.RefuelSurface,
                        border = (if (draftRefuels > 0) TorqueEvoColors.WarnAmber else TorqueEvoColors.NominalGreen).copy(alpha = 0.35f),
                        modifier = Modifier.weight(1f),
                        onClick = { activeSheet = ActiveHomeSheet.REFUEL }
                    )
                    HomeModuleTile(
                        title = "Fault Codes",
                        subtitle = if (report == null) "Not scanned" else "$faultCount DTC(s)",
                        icon = Icons.Default.WarningAmber,
                        tint = TorqueEvoColors.WarnAmber,
                        bg = TorqueEvoColors.CardSurface,
                        border = TorqueEvoColors.SubtleBorder,
                        modifier = Modifier.weight(1f),
                        onClick = { activeSheet = ActiveHomeSheet.FAULTS }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    val offlineTotal = offlineRegions.size + pmtilesPacks.size
                    HomeModuleTile(
                        title = "Run Logs & Maps",
                        subtitle = if (offlineTotal > 0) "${sessions.size} Run(s) · $offlineTotal Offline" else "${sessions.size} Run(s)",
                        icon = Icons.Default.Route,
                        tint = TorqueEvoColors.SkyBlue,
                        bg = TorqueEvoColors.CardSurface,
                        border = TorqueEvoColors.SubtleBorder,
                        modifier = Modifier.weight(1f),
                        onClick = { activeSheet = ActiveHomeSheet.RUNS_AND_MAPS }
                    )
                    HomeModuleTile(
                        title = "ECU PIDs",
                        subtitle = if (supportedPids.isNotEmpty()) "${supportedPids.size} Supported" else "Explorer",
                        icon = Icons.Default.Tune,
                        tint = TorqueEvoColors.AccentViolet,
                        bg = TorqueEvoColors.CardSurface,
                        border = TorqueEvoColors.SubtleBorder,
                        modifier = Modifier.weight(1f),
                        onClick = { activeSheet = ActiveHomeSheet.ECU_PIDS }
                    )
                }
            }
        }

        if (isLandscape) {
            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                headerRow()
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(modifier = Modifier.weight(1.08f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        hudDashboardHeroCard(Modifier.weight(1f).fillMaxWidth())
                        lastRunMapCard(Modifier.fillMaxWidth().height(92.dp))
                    }
                    Column(
                        modifier = Modifier.weight(0.95f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        carProfileCard()
                        statStripCard()
                        modulesGrid()
                    }
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                headerRow()
                carProfileCard()
                statStripCard()
                hudDashboardHeroCard(Modifier.weight(1f).fillMaxWidth())
                lastRunMapCard(Modifier.fillMaxWidth().height(106.dp))
                modulesGrid()
            }
        }
    }

    when (activeSheet) {
        ActiveHomeSheet.VEHICLE -> VehicleProfileSheet(
            vehicles = vehicles,
            activeVehicle = activeVehicle,
            unitPrefs = unitPrefs,
            startAddingNew = startAddingNewVehicle,
            connectionState = connectionState,
            liveEcuDistanceSinceClearKm = ecuDistanceSinceClearKm,
            onSelectVehicle = { viewModel.selectVehicle(it) },
            onPickPhoto = { uri, cb -> viewModel.copyPickedVehiclePhoto(uri, cb) },
            onCalibrateBaseline = { vehicleId, odoDisp, fuelDisp ->
                viewModel.calibrateVehicleBaseline(vehicleId, odoDisp, fuelDisp)
            },
            onSaveVehicle = { existing, n, cc, bt, tr, yr, photo, w, ft, cap, rem, odo, red, trim ->
                viewModel.saveVehicleProfile(existing, n, cc, bt, tr, yr, photo, w, ft, cap, rem, odo, red, trim)
            },
            onDeleteVehicle = { viewModel.deleteVehicleProfile(it) },
            onDismiss = {
                startAddingNewVehicle = false
                activeSheet = ActiveHomeSheet.NONE
            }
        )
        ActiveHomeSheet.REFUEL -> RefuelLogSheet(
            vehicle = activeVehicle,
            logs = refuels,
            defaultCurrency = settings.currencyCode,
            unitPrefs = unitPrefs,
            onAddRefuel = { vol, price, cur, ron, fName, odo, isFull, autoCal ->
                viewModel.addRefuelCheckpoint(vol, price, cur, ron, fName, odo, isFull, autoCal)
            },
            onConfirmEditRefuel = { existing, vol, price, cur, ron, fName, odo, isFull, autoCal ->
                viewModel.confirmOrEditRefuelCheckpoint(existing, vol, price, cur, ron, fName, odo, isFull, autoCal)
            },
            onDeleteRefuel = { viewModel.deleteRefuelCheckpoint(it) },
            onDismiss = { activeSheet = ActiveHomeSheet.NONE }
        )
        ActiveHomeSheet.FAULTS -> FaultCodesSheet(
            report = report,
            vin = vin,
            isScanning = diagnosticScanning,
            diagnosticError = diagnosticError,
            onScan = { viewModel.scanDiagnostics() },
            onClearDtcs = { viewModel.clearDtcs() },
            onDismiss = { activeSheet = ActiveHomeSheet.NONE }
        )
        ActiveHomeSheet.RUNS_AND_MAPS -> RunLogsAndMapsSheet(
            sessions = sessions,
            segmentsBySession = segmentsBySession,
            vehicleName = activeVehicle?.name ?: "Active Vehicle",
            settings = settings,
            onSelectMapStylePreset = { viewModel.setMapStylePreset(it) },
            onSelectMapCameraMode = { viewModel.setMapCameraMode(it) },
            onSelectMapCursorStyle = { viewModel.setMapCursorStyle(it) },
            onToggleHighContrastRoads = { viewModel.setHighContrastMapRoads(!settings.highContrastMapRoads) },
            unitPrefs = unitPrefs,
            fuelType = fuelType,
            onExport = { s, asJson -> viewModel.exportSession(s, asJson) },
            onShareCard = { s -> viewModel.exportSessionShareCard(s) },
            isPreparingCard = shareCardPreparing,
            onOpenOfflineMaps = { activeSheet = ActiveHomeSheet.OFFLINE_MAPS },
            onDismiss = { activeSheet = ActiveHomeSheet.NONE }
        )
        ActiveHomeSheet.OFFLINE_MAPS -> OfflineMapDownloaderDialog(
            activePmtilesPath = settings.activePmtilesPath,
            gps = gps,
            offlineRegions = offlineRegions,
            pmtilesPacks = pmtilesPacks,
            offlineStatusMsg = offlineMsg,
            onDownloadBounds = { n, box, mZ ->
                viewModel.offlineMapManager.downloadBoundsRegion(n, box.north, box.east, box.south, box.west, minZoom = 5.0, maxZoom = mZ)
            },
            onDeleteRegion = { viewModel.offlineMapManager.deleteRegion(it) },
            onImportPmtilesUri = { uri ->
                viewModel.offlineMapManager.importPmtilesFromUri(uri) { savedPath ->
                    viewModel.setActivePmtilesPath(savedPath)
                }
            },
            onDownloadPmtilesUrl = { n, url ->
                viewModel.offlineMapManager.downloadPmtilesFromUrl(n, url) { savedPath ->
                    viewModel.setActivePmtilesPath(savedPath)
                }
            },
            onSelectPmtilesPack = { path -> viewModel.setActivePmtilesPath(path) },
            onDeletePmtilesPack = { path -> viewModel.offlineMapManager.deletePmtilesPack(path) },
            onRefreshRegions = { viewModel.offlineMapManager.refreshAll() },
            onBackToRuns = { activeSheet = ActiveHomeSheet.RUNS_AND_MAPS },
            onDismiss = { activeSheet = ActiveHomeSheet.NONE }
        )
        ActiveHomeSheet.ECU_PIDS -> EcuPidsSheet(
            supportedPids = supportedPids,
            liveValues = liveValues,
            explorerStatus = explorerStatus,
            isDeepScanning = deepScanRunning,
            hasEcuDump = ecuDumpReady,
            onDiscoverExplorer = { viewModel.forceConnectAndDiscoverForExplorer() },
            onStopExplorer = { viewModel.restoreVisibleCanvasPolling() },
            onAddCustomPid = { n, hex, form, u, hdr -> viewModel.addManualCustomPid(n, hex, form, u, hdr) },
            onImportCsv = { viewModel.importTorqueCsv(it) },
            onRunDeepDump = { viewModel.runDeepEcuDump() },
            onShareDeepDump = { viewModel.shareEcuDump() },
            onDismiss = { activeSheet = ActiveHomeSheet.NONE }
        )
        ActiveHomeSheet.ADAPTER -> AdapterConnectionSheet(
            settings = settings,
            connectionState = connectionState,
            backgroundServiceActive = backgroundServiceActive,
            btPairedDevices = btPairedDevices,
            bleScannedDevices = bleScannedDevices,
            bleScanning = bleScanning,
            usbDevices = usbDevices,
            onRefreshLocalAdapters = { viewModel.refreshLocalAdapters() },
            onScanBleAdapters = { viewModel.scanBleAdapters() },
            onSelectConnectionType = { viewModel.selectConnectionType(it) },
            onSaveWifiConnection = { h, p, dual -> viewModel.updateConnectionSettings(h, p, dual) },
            onSelectBtDevice = { viewModel.selectBluetoothDevice(it) },
            onSelectBleDevice = { viewModel.selectBleDevice(it) },
            onSelectUsbDevice = { id, baud -> viewModel.selectUsbDevice(id, baud) },
            onRequestUsbPermission = { viewModel.requestUsbAdapterPermission(it) },
            onResumeBackgroundService = { viewModel.resumeBackgroundService() },
            onTerminateBackgroundService = { viewModel.terminateBackgroundService() },
            onDismiss = { activeSheet = ActiveHomeSheet.NONE }
        )
        ActiveHomeSheet.SETTINGS -> SettingsSheet(
            settings = settings,
            onOpenAdapterConnection = { activeSheet = ActiveHomeSheet.ADAPTER },
            onSelectPolling = { viewModel.setPollingProfile(it) },
            onSelectGaugeSmoothing = { viewModel.setGaugeSmoothingMode(it) },
            onToggleLiveMicroMotion = { viewModel.setLiveMicroMotion(!settings.liveMicroMotion) },
            onSelectFuelCoasting = { viewModel.setFuelCoastingMode(it) },
            onSelectGridScale = { viewModel.setHudGridScale(it) },
            onSelectMapStylePreset = { viewModel.setMapStylePreset(it) },
            onSelectMapCameraMode = { viewModel.setMapCameraMode(it) },
            onSelectMapCursorStyle = { viewModel.setMapCursorStyle(it) },
            onToggleShareMetric = { viewModel.toggleShareCardMetric(it) },
            onResetShareMetrics = { viewModel.resetShareCardMetricsToDefault() },
            onToggleHighContrastRoads = { viewModel.setHighContrastMapRoads(!settings.highContrastMapRoads) },
            onSelectHudTheme = { viewModel.setHudTheme(it) },
            onSelectCanvasBackdrop = { viewModel.setHudCanvasBackdrop(it) },
            onSelectCardStyle = { viewModel.setHudCardStyle(it) },
            onToggleWidgetBorders = { viewModel.toggleWidgetBorders() },
            onUpdateUnits = { viewModel.updateUnits(it) },
            onUpdateCurrency = { viewModel.setCurrencyCode(it) },
            onToggleHudMirror = { viewModel.toggleHudMirror() },
            onOpenOfflineMaps = { activeSheet = ActiveHomeSheet.OFFLINE_MAPS },
            onDismiss = { activeSheet = ActiveHomeSheet.NONE }
        )
        ActiveHomeSheet.NONE -> Unit
    }
}

@Composable
private fun StatCell(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label.uppercase(), fontSize = 8.5.sp, color = TorqueEvoColors.TextSecondary, letterSpacing = 0.4.sp, maxLines = 1)
        Spacer(Modifier.height(1.dp))
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HomeModuleTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    tint: Color,
    bg: Color,
    border: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Column {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TorqueEvoColors.TextPrimary, maxLines = 1)
            Text(subtitle, fontSize = 10.sp, color = TorqueEvoColors.TextSecondary, maxLines = 1)
        }
    }
}
