package com.apexobd.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WarningAmber
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apexobd.app.platform.TripExporter
import com.apexobd.app.ui.components.DualMapCanvas
import com.apexobd.app.ui.theme.TorqueEvoColors
import com.apexobd.core.model.FuelType
import com.apexobd.core.model.UnitConverter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private enum class ActiveHomeSheet { NONE, VEHICLE, REFUEL, FAULTS, RUNS_AND_MAPS, OFFLINE_MAPS, ECU_PIDS, SETTINGS }

@Composable
fun HomeMenuScreen(
    viewModel: HudViewModel,
    onOpenHudCanvas: () -> Unit
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val vehicles by viewModel.vehicles.collectAsStateWithLifecycle()
    val activeVehicle by viewModel.activeVehicle.collectAsStateWithLifecycle()
    val refuels by viewModel.refuelLogs.collectAsStateWithLifecycle()
    val pages by viewModel.pagesWithWidgets.collectAsStateWithLifecycle()
    val sessions by viewModel.tripSessions.collectAsStateWithLifecycle()
    val gps by viewModel.locationTracker.gps.collectAsStateWithLifecycle()
    val report by viewModel.diagnosticReport.collectAsStateWithLifecycle()
    val diagnosticError by viewModel.diagnosticError.collectAsStateWithLifecycle()
    val diagnosticScanning by viewModel.diagnosticScanning.collectAsStateWithLifecycle()
    val explorerStatus by viewModel.explorerStatus.collectAsStateWithLifecycle()
    val vin by viewModel.vin.collectAsStateWithLifecycle()
    val supportedPids by viewModel.supportedPids.collectAsStateWithLifecycle()
    val liveValues by viewModel.ecuExplorerValues.collectAsStateWithLifecycle()
    val offlineRegions by viewModel.offlineMapManager.regions.collectAsStateWithLifecycle()
    val pmtilesPacks by viewModel.offlineMapManager.pmtilesPacks.collectAsStateWithLifecycle()
    val offlineMsg by viewModel.offlineMapManager.statusMessage.collectAsStateWithLifecycle()

    var activeSheet by remember { mutableStateOf(ActiveHomeSheet.NONE) }

    val unitPrefs = settings.toUnitPreferences()
    val fuelType = activeVehicle?.let { runCatching { FuelType.valueOf(it.fuelType) }.getOrNull() } ?: FuelType.GASOLINE

    // Load last saved trip GPS coordinates for compact Home map preview
    val latestTrip = sessions.firstOrNull()
    var lastTripTrail by remember(latestTrip?.id) { mutableStateOf<List<Pair<Double, Double>>>(emptyList()) }
    LaunchedEffect(latestTrip?.id) {
        if (latestTrip != null) {
            val samples = viewModel.getSamplesForSession(latestTrip.id)
            lastTripTrail = samples.mapNotNull { s ->
                if (s.latitude != null && s.longitude != null) s.latitude to s.longitude else null
            }
        } else {
            lastTripTrail = emptyList()
        }
    }

    val allTimeAvgKpl = activeVehicle?.allTimeAvgKpl() ?: 0.0
    val (dispAvg, avgSym) = UnitConverter.convertFuelEconomy(allTimeAvgKpl, unitPrefs, fuelType)
    val (dispOdo, distSym) = UnitConverter.convertDistanceKm(activeVehicle?.allTimeOdometerKm ?: 0.0, unitPrefs)
    val estRangeKm = (activeVehicle?.estimatedRemainingFuelLiters ?: 0.0) * (allTimeAvgKpl.takeIf { it > 1.0 } ?: 12.0)
    val (dispRange, _) = UnitConverter.convertDistanceKm(estRangeKm, unitPrefs)
    val (dispRemFuel, volSym) = UnitConverter.convertVolume(activeVehicle?.estimatedRemainingFuelLiters ?: 0.0, unitPrefs, fuelType)
    val (dispCapFuel, _) = UnitConverter.convertVolume(activeVehicle?.tankCapacityLiters ?: 45.0, unitPrefs, fuelType)

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
        "Log fuel & checkpoint avg"
    }

    val faultCount = (report?.confirmed?.size ?: 0) + (report?.pending?.size ?: 0)
    val dateFmt = remember { SimpleDateFormat("dd MMM", Locale.getDefault()) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(TorqueEvoColors.HomeBg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        val isLandscape = maxWidth > maxHeight

        val headerRow: @Composable () -> Unit = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("ApexOBD", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TorqueEvoColors.TextPrimary)
                        Text("v1.0", fontSize = 10.sp, color = TorqueEvoColors.TextSecondary)
                    }
                    Text(
                        text = "${settings.wifiHost}:${settings.wifiPort} · ${if (settings.useSimulator) "SIM" else "Wi-Fi"}",
                        fontSize = 11.sp,
                        color = TorqueEvoColors.ElectricBlue
                    )
                }
                HorizontalDivider(color = TorqueEvoColors.SubtleBorder)
            }
        }

        val launchHudButton: @Composable () -> Unit = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(TorqueEvoColors.ElectricBlue)
                    .clickable { onOpenHudCanvas() }
                    .padding(horizontal = 15.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("LIVE DRIVING DISPLAY", fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp, color = Color(0xBF020810))
                    Text("Open HUD Canvas", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF020810))
                    Text(
                        "${pages.size} Page(s) · Grid Snap ${if (settings.gridSnapEnabled) "On" else "Off"} · Hold tap to edit",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xCC020810)
                    )
                }
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Color(0xFF020810), modifier = Modifier.size(22.dp))
            }
        }

        val lastRunMapCard: @Composable (Modifier) -> Unit = { mod ->
            val tripSub = if (latestTrip != null) {
                val (td, ts) = UnitConverter.convertDistanceKm(latestTrip.distanceKm, unitPrefs)
                val (tk, ks) = UnitConverter.convertFuelEconomy(latestTrip.avgKpl, unitPrefs, fuelType)
                val mins = (((latestTrip.endedAtMs ?: System.currentTimeMillis()) - latestTrip.startedAtMs) / 60_000L).coerceAtLeast(1L)
                "%.1f %s · %.1f %s · %d min".format(td, ts, tk, ks, mins)
            } else {
                "No recorded run yet"
            }
            val mapCenterLat = lastTripTrail.lastOrNull()?.first ?: gps.latitude
            val mapCenterLon = lastTripTrail.lastOrNull()?.second ?: gps.longitude

            Column(
                modifier = mod
                    .clip(RoundedCornerShape(12.dp))
                    .background(TorqueEvoColors.ElevatedDark)
                    .border(1.dp, TorqueEvoColors.SubtleBorder, RoundedCornerShape(12.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = latestTrip?.let { "Last Saved Run · ${dateFmt.format(Date(it.startedAtMs))}" } ?: "Last Saved Run",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TorqueEvoColors.TextPrimary
                    )
                    Text(tripSub, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = TorqueEvoColors.ElectricBlue)
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = true)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, TorqueEvoColors.SubtleBorder, RoundedCornerShape(8.dp))
                ) {
                    DualMapCanvas(
                        mapProvider = settings.mapProvider,
                        latitude = mapCenterLat,
                        longitude = mapCenterLon,
                        trail = lastTripTrail,
                        activePmtilesPath = settings.activePmtilesPath,
                        modifier = Modifier.fillMaxSize()
                    )
                    Text(
                        text = "Navigate ↗",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TorqueEvoColors.ElectricBlue,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xE007090D))
                            .border(1.dp, TorqueEvoColors.SubtleBorder, RoundedCornerShape(6.dp))
                            .clickable { viewModel.openExternalNavigation(context) }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        val carProfileCard: @Composable () -> Unit = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(TorqueEvoColors.CarProfileSurface)
                    .border(1.dp, TorqueEvoColors.ElectricBlue.copy(alpha = 0.42f), RoundedCornerShape(12.dp))
                    .clickable { activeSheet = ActiveHomeSheet.VEHICLE }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
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
                        Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = TorqueEvoColors.ElectricBlue, modifier = Modifier.size(20.dp))
                    }
                    Column {
                        Text("VEHICLE PROFILE", fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp, color = TorqueEvoColors.ElectricBlue)
                        Text(activeVehicle?.name ?: "Avanza 1.3L", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TorqueEvoColors.TextPrimary)
                        Text(
                            "%.1fL · %.0f kg · %s · Tank %.1f/%.0f %s".format(
                                activeVehicle?.displacementLiters ?: 1.3,
                                activeVehicle?.weightKg ?: 1175.0,
                                fuelType.label,
                                dispRemFuel,
                                dispCapFuel,
                                volSym
                            ),
                            fontSize = 10.sp,
                            color = TorqueEvoColors.TextSecondary,
                            maxLines = 1
                        )
                    }
                }
                Text(
                    "Edit ▾",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TorqueEvoColors.TextPrimary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(Color(0xFF161A22))
                        .border(1.dp, TorqueEvoColors.SubtleBorder, RoundedCornerShape(7.dp))
                        .padding(horizontal = 9.dp, vertical = 5.dp)
                )
            }
        }

        val statStripCard: @Composable () -> Unit = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(TorqueEvoColors.ElevatedDark)
                    .border(1.dp, TorqueEvoColors.SubtleBorder, RoundedCornerShape(10.dp))
                    .clickable { activeSheet = ActiveHomeSheet.VEHICLE }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatCell("All time avg", if (allTimeAvgKpl > 0.1) "%.1f %s".format(dispAvg, avgSym) else "— $avgSym", TorqueEvoColors.ElectricBlue, Modifier.weight(1f))
                StatCell("Odometer", "${dispOdo.roundToInt()} $distSym", TorqueEvoColors.TextPrimary, Modifier.weight(1f))
                StatCell("Est. Range", "${dispRange.roundToInt()} $distSym", TorqueEvoColors.NominalGreen, Modifier.weight(1f))
                StatCell("Best 0–100", activeVehicle?.best0100Sec?.let { "%.2f s".format(it) } ?: "—", TorqueEvoColors.TextPrimary, Modifier.weight(0.9f))
            }
        }

        val modulesGrid: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    HomeModuleTile(
                        title = "Refuel Log",
                        subtitle = refuelSub,
                        icon = Icons.Default.LocalGasStation,
                        tint = TorqueEvoColors.NominalGreen,
                        bg = TorqueEvoColors.RefuelSurface,
                        border = TorqueEvoColors.NominalGreen.copy(alpha = 0.28f),
                        modifier = Modifier.weight(1f),
                        onClick = { activeSheet = ActiveHomeSheet.REFUEL }
                    )
                    HomeModuleTile(
                        title = "Fault Codes",
                        subtitle = if (report == null) "Scan & Clear 04" else "$faultCount DTC(s)",
                        icon = Icons.Default.WarningAmber,
                        tint = TorqueEvoColors.WarnAmber,
                        bg = TorqueEvoColors.CardSurface,
                        border = TorqueEvoColors.SubtleBorder,
                        modifier = Modifier.weight(1f),
                        onClick = { activeSheet = ActiveHomeSheet.FAULTS }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    HomeModuleTile(
                        title = "Run Logs & Maps",
                        subtitle = "${sessions.size} Run(s) · ${offlineRegions.size + pmtilesPacks.size} Offline",
                        icon = Icons.Default.Route,
                        tint = TorqueEvoColors.SkyBlue,
                        bg = TorqueEvoColors.CardSurface,
                        border = TorqueEvoColors.SubtleBorder,
                        modifier = Modifier.weight(1f),
                        onClick = { activeSheet = ActiveHomeSheet.RUNS_AND_MAPS }
                    )
                    HomeModuleTile(
                        title = "ECU PIDs",
                        subtitle = "${supportedPids.size.takeIf { it > 0 } ?: 16} Supported · UDS 22",
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

        val settingsCard: @Composable () -> Unit = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(TorqueEvoColors.SettingsSurface)
                    .border(1.dp, Color(0x2EFFFFFF), RoundedCornerShape(12.dp))
                    .clickable { activeSheet = ActiveHomeSheet.SETTINGS }
                    .padding(horizontal = 13.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = TorqueEvoColors.ElectricBlue, modifier = Modifier.size(18.dp))
                    Column {
                        Text("Adapter, Units, Map Mode & Polling", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TorqueEvoColors.TextPrimary)
                        Text(
                            "${settings.mapProvider.label} · $avgSym, $distSym, ${unitPrefs.pressure.symbol}",
                            fontSize = 10.sp,
                            color = TorqueEvoColors.TextSecondary,
                            maxLines = 1
                        )
                    }
                }
                Text("Configure →", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TorqueEvoColors.ElectricBlue)
            }
        }

        if (isLandscape) {
            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                headerRow()
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(modifier = Modifier.weight(1.05f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        launchHudButton()
                        lastRunMapCard(Modifier.weight(1f).fillMaxWidth())
                        settingsCard()
                    }
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
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
                launchHudButton()
                lastRunMapCard(Modifier.weight(1f).fillMaxWidth())
                modulesGrid()
                settingsCard()
            }
        }
    }

    when (activeSheet) {
        ActiveHomeSheet.VEHICLE -> VehicleProfileSheet(
            vehicles = vehicles,
            activeVehicle = activeVehicle,
            unitPrefs = unitPrefs,
            onSelectVehicle = { viewModel.selectVehicle(it) },
            onSaveVehicle = { existing, n, d, w, ft, cap, rem, odo, red ->
                viewModel.saveVehicleProfile(existing, n, d, w, ft, cap, rem, odo, red)
            },
            onDismiss = { activeSheet = ActiveHomeSheet.NONE }
        )
        ActiveHomeSheet.REFUEL -> RefuelLogSheet(
            vehicle = activeVehicle,
            logs = refuels,
            defaultCurrency = settings.currencyCode,
            unitPrefs = unitPrefs,
            onAddRefuel = { vol, price, cur, ron, fName, odo ->
                viewModel.addRefuelCheckpoint(vol, price, cur, ron, fName, odo)
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
            unitPrefs = unitPrefs,
            fuelType = fuelType,
            onExport = { s, asJson -> viewModel.exportSession(s, asJson) },
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
            onDiscoverExplorer = { viewModel.forceConnectAndDiscoverForExplorer() },
            onStopExplorer = { viewModel.restoreVisibleCanvasPolling() },
            onAddCustomPid = { n, hex, form, u -> viewModel.addManualCustomPid(n, hex, form, u) },
            onImportCsv = { viewModel.importTorqueCsv(it) },
            onRunDeepDump = { viewModel.runDeepEcuDump() },
            onDismiss = { activeSheet = ActiveHomeSheet.NONE }
        )
        ActiveHomeSheet.SETTINGS -> SettingsSheet(
            settings = settings,
            onSaveConnection = { h, p, sim, dual -> viewModel.updateConnectionSettings(h, p, sim, dual) },
            onSelectPolling = { viewModel.setPollingProfile(it) },
            onSelectMapProvider = { viewModel.setMapProvider(it) },
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
            maxLines = 1
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
