package com.apexobd.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apexobd.app.data.db.WidgetDisplayType
import com.apexobd.app.ui.components.TorqueEvoWidgetCard
import com.apexobd.app.ui.theme.TorqueEvoColors
import com.apexobd.core.scheduler.ConnectionState
import com.apexobd.core.scheduler.PollingProfile
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HudCanvasScreen(viewModel: HudViewModel) {
    val pages by viewModel.pagesWithWidgets.collectAsState()
    val liveValues by viewModel.liveValues.collectAsState()
    val historyBuffers by viewModel.historyBuffers.collectAsState()
    val gps by viewModel.locationTracker.gps.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val connState by viewModel.connectionState.collectAsState()
    val supportedPids by viewModel.supportedPids.collectAsState()
    val pollingHz by viewModel.pollingHz.collectAsState()
    val avgLatencyMs by viewModel.avgLatencyMs.collectAsState()
    val diagnosticReport by viewModel.diagnosticReport.collectAsState()
    val vin by viewModel.vin.collectAsState()
    val timer0100 by viewModel.timer0100.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val tripSessions by viewModel.tripSessions.collectAsState()

    var editMode by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var showParkedSetup by remember { mutableStateOf(false) }

    val pagerState = rememberPagerState(pageCount = { pages.size.coerceAtLeast(1) })
    LaunchedEffect(pagerState.currentPage) {
        viewModel.setVisiblePageIndex(pagerState.currentPage)
    }
    val scope = rememberCoroutineScope()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val gridColumns = if (isLandscape) 6 else 4

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TorqueEvoColors.PureBlack)
            .graphicsLayer {
                scaleX = if (settings.hudMirror) -1f else 1f
            }
    ) {
        if (pages.isNotEmpty()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = !editMode
            ) { pageIndex ->
                val pageBundle = pages[pageIndex]
                LazyVerticalGrid(
                    columns = GridCells.Fixed(gridColumns),
                    contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 12.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        items = pageBundle.widgets,
                        key = { it.id },
                        span = { GridItemSpan(it.spanCols.coerceIn(1, gridColumns)) }
                    ) { widget ->
                        val heightDp = if (widget.spanRows >= 2) 172.dp else 84.dp
                        TorqueEvoWidgetCard(
                            widget = widget,
                            catalogItem = viewModel.catalogItemFor(widget.sensorKey),
                            rawValue = liveValues[widget.sensorKey],
                            history = historyBuffers[widget.sensorKey].orEmpty(),
                            gps = gps,
                            diagnosticReport = diagnosticReport,
                            timer0100 = timer0100,
                            isRecording = isRecording,
                            imperialUnits = settings.imperialUnits,
                            editMode = editMode,
                            onScanDtcs = { viewModel.scanDiagnostics() },
                            onClearDtcs = { viewModel.clearDtcs() },
                            onToggleRecord = { viewModel.toggleRecording() },
                            onReset0100 = { viewModel.reset0100Best() },
                            onCycleType = { viewModel.cycleWidgetDisplayType(widget) },
                            onCycleSize = { viewModel.cycleWidgetSize(widget) },
                            onMoveLeft = { viewModel.moveWidget(pageBundle.page.id, widget.id, -1) },
                            onMoveRight = { viewModel.moveWidget(pageBundle.page.id, widget.id, +1) },
                            onDelete = { viewModel.deleteWidget(widget.id) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(heightDp)
                        )
                    }
                }
            }
        }

        // Faint Bottom Page Indicator Dots
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
                .background(Color(0x88000000), RoundedCornerShape(99.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            pages.forEachIndexed { idx, _ ->
                val active = pagerState.currentPage == idx
                Box(
                    modifier = Modifier
                        .height(6.dp)
                        .width(if (active) 20.dp else 6.dp)
                        .clip(CircleShape)
                        .background(
                            if (active) TorqueEvoColors.ElectricBlue else Color.White.copy(alpha = 0.28f)
                        )
                        .clickable {
                            scope.launch { pagerState.animateScrollToPage(idx) }
                        }
                )
            }
        }

        // Minimal unobtrusive corner trigger (replaces bulky navbars)
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 8.dp, end = 10.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.alpha(if (editMode || menuExpanded) 1f else 0.36f)
            ) {
                if (editMode) {
                    Text(
                        text = "Done",
                        color = TorqueEvoColors.PureBlack,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(99.dp))
                            .background(TorqueEvoColors.ElectricBlue)
                            .clickable { editMode = false }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
                Text(
                    text = "⚙ / +",
                    color = TorqueEvoColors.ElectricBlue,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(99.dp))
                        .background(Color(0xCC0A101A))
                        .clickable { menuExpanded = true }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                containerColor = TorqueEvoColors.ElevatedDark
            ) {
                DropdownMenuItem(
                    text = { Text("+ Pick & Add Component") },
                    onClick = {
                        menuExpanded = false
                        showPicker = true
                    }
                )
                DropdownMenuItem(
                    text = { Text(if (editMode) "Finish Editing Layout" else "✎ Edit / Move / Resize Widgets") },
                    onClick = {
                        menuExpanded = false
                        editMode = !editMode
                    }
                )
                DropdownMenuItem(
                    text = { Text("+ Add New Blank Page") },
                    onClick = {
                        menuExpanded = false
                        viewModel.addPage("Page ${pages.size + 1}") { idx ->
                            scope.launch { pagerState.animateScrollToPage(idx) }
                        }
                    }
                )
                if (pages.size > 1) {
                    DropdownMenuItem(
                        text = { Text("Delete Current Page", color = TorqueEvoColors.CriticalRed) },
                        onClick = {
                            menuExpanded = false
                            val current = pages.getOrNull(pagerState.currentPage) ?: return@DropdownMenuItem
                            viewModel.deletePage(current.page.id)
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text("⇄ Toggle HUD Mirror (${if (settings.hudMirror) "On" else "Off"})") },
                    onClick = {
                        menuExpanded = false
                        viewModel.toggleHudMirror()
                    }
                )
                DropdownMenuItem(
                    text = { Text("🔧 Parked Setup (Wi-Fi, DTC, Logs, PIDs)") },
                    onClick = {
                        menuExpanded = false
                        showParkedSetup = true
                    }
                )
            }
        }
    }

    if (showPicker) {
        val currentPageId = pages.getOrNull(pagerState.currentPage)?.page?.id
        if (currentPageId != null) {
            ModalBottomSheet(
                onDismissRequest = { showPicker = false },
                containerColor = TorqueEvoColors.ElevatedDark
            ) {
                ComponentPickerSheetContent(
                    catalog = viewModel.fullCatalog(),
                    onAdd = { sensorKey, displayType, cols, rows ->
                        viewModel.addWidget(currentPageId, sensorKey, displayType, cols, rows)
                        showPicker = false
                    }
                )
            }
        }
    }

    if (showParkedSetup) {
        ModalBottomSheet(
            onDismissRequest = { showParkedSetup = false },
            containerColor = TorqueEvoColors.ElevatedDark
        ) {
            ParkedSetupSheetContent(
                viewModel = viewModel,
                connState = connState,
                supportedCount = supportedPids.size,
                pollingHz = pollingHz,
                avgLatencyMs = avgLatencyMs,
                vin = vin,
                diagnosticReport = diagnosticReport,
                tripSessions = tripSessions,
                settings = settings
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComponentPickerSheetContent(
    catalog: List<SensorCatalogItem>,
    onAdd: (String, WidgetDisplayType, Int, Int) -> Unit
) {
    var selectedSensor by remember { mutableStateOf(catalog.first()) }
    var selectedType by remember { mutableStateOf(WidgetDisplayType.ANALOG_DIAL) }
    var selectedSize by remember { mutableStateOf(1 to 1) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Pick & Add HUD Component", fontWeight = FontWeight.Bold, fontSize = 17.sp)

        Text("1. Sensor / Feature", color = TorqueEvoColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        LazyColumn(modifier = Modifier.height(170.dp)) {
            items(catalog) { item ->
                val isSel = item.key == selectedSensor.key
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSel) TorqueEvoColors.DimBlue else Color.Transparent)
                        .clickable {
                            selectedSensor = item
                            selectedType = when (item.key) {
                                "minimap" -> WidgetDisplayType.MINI_MAP
                                "dtc_checker" -> WidgetDisplayType.DTC_CHECKER
                                "timer_0100" -> WidgetDisplayType.PERF_TIMER
                                "data_logger" -> WidgetDisplayType.DATA_LOGGER
                                else -> selectedType
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(item.label, fontSize = 13.sp, color = if (isSel) TorqueEvoColors.ElectricBlue else TorqueEvoColors.TextPrimary)
                    Text(item.pidLabel, fontSize = 11.sp, color = TorqueEvoColors.TextSecondary)
                }
            }
        }

        if (selectedSensor.key !in setOf("minimap", "dtc_checker", "timer_0100", "data_logger")) {
            Text("2. Display Type", color = TorqueEvoColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    WidgetDisplayType.ANALOG_DIAL to "Analog Dial",
                    WidgetDisplayType.SEMI_DIGITAL to "Semi-Digital",
                    WidgetDisplayType.DIGITAL to "Digital",
                    WidgetDisplayType.BAR to "Bar",
                    WidgetDisplayType.CHART to "Chart"
                ).forEach { (type, label) ->
                    FilterChip(
                        selected = selectedType == type,
                        onClick = { selectedType = type },
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }
        }

        Text("3. Size", color = TorqueEvoColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf((1 to 1) to "1×1 Small", (2 to 1) to "2×1 Wide", (2 to 2) to "2×2 Large").forEach { (dim, label) ->
                FilterChip(
                    selected = selectedSize == dim,
                    onClick = { selectedSize = dim },
                    label = { Text(label, fontSize = 12.sp) }
                )
            }
        }

        Button(
            onClick = { onAdd(selectedSensor.key, selectedType, selectedSize.first, selectedSize.second) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = TorqueEvoColors.ElectricBlue)
        ) {
            Text("Add to Page", color = TorqueEvoColors.PureBlack, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParkedSetupSheetContent(
    viewModel: HudViewModel,
    connState: ConnectionState,
    supportedCount: Int,
    pollingHz: Int,
    avgLatencyMs: Int,
    vin: String?,
    diagnosticReport: com.apexobd.core.protocol.DiagnosticReport?,
    tripSessions: List<com.apexobd.app.data.db.TripSessionEntity>,
    settings: com.apexobd.app.data.prefs.UserSettings
) {
    var hostInput by remember(settings.wifiHost) { mutableStateOf(settings.wifiHost) }
    var portInput by remember(settings.wifiPort) { mutableStateOf(settings.wifiPort.toString()) }
    var useSim by remember(settings.useSimulator) { mutableStateOf(settings.useSimulator) }
    var pidName by remember { mutableStateOf("") }
    var pidHex by remember { mutableStateOf("221E1C") }
    var pidFormula by remember { mutableStateOf("((A*256)+B)/10-40") }
    var pidUnit by remember { mutableStateOf("°C") }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Parked Setup & Diagnostics", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            val statusLine = when (connState) {
                is ConnectionState.Connected ->
                    "Connected • ${connState.handshake.protocolName} • $supportedCount PIDs • ${pollingHz}Hz • ${avgLatencyMs}ms RTT"
                is ConnectionState.Connecting -> "Connecting..."
                is ConnectionState.Reconnecting -> "Reconnecting (#${connState.attempt}): ${connState.reason}"
                ConnectionState.Disconnected -> "Disconnected"
            }
            Text(statusLine, color = TorqueEvoColors.SkyBlue, fontSize = 12.sp)
            if (vin != null) {
                Text("VIN (Mode 09): $vin", color = TorqueEvoColors.TextSecondary, fontSize = 12.sp)
            }
        }

        // Latency & Battery Efficiency Profile
        item {
            Text("Polling Efficiency & Battery Profile", fontWeight = FontWeight.Bold, color = TorqueEvoColors.ElectricBlue, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PollingProfile.entries.forEach { profile ->
                    FilterChip(
                        selected = settings.pollingProfile == profile,
                        onClick = { viewModel.setPollingProfile(profile) },
                        label = {
                            val short = when (profile) {
                                PollingProfile.MAX_PERFORMANCE -> "Max Speed"
                                PollingProfile.BALANCED -> "Balanced"
                                PollingProfile.ECO_BATTERY -> "Eco Battery"
                            }
                            Text(short, fontSize = 12.sp)
                        }
                    )
                }
            }
        }

        // Wi-Fi ELM327 Adapter Config
        item {
            Text("Wi-Fi ELM327 Adapter", fontWeight = FontWeight.Bold, color = TorqueEvoColors.ElectricBlue, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Use Built-in Simulator (Demo Mode)", fontSize = 13.sp)
                Switch(checked = useSim, onCheckedChange = { useSim = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = hostInput,
                    onValueChange = { hostInput = it },
                    label = { Text("IP Address") },
                    modifier = Modifier.weight(1.5f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = portInput,
                    onValueChange = { portInput = it },
                    label = { Text("Port") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    viewModel.updateWifiEndpoint(hostInput, portInput.toIntOrNull() ?: 35000, useSim)
                }) { Text("Apply & Reconnect") }
                OutlinedButton(onClick = { viewModel.toggleImperialUnits() }) {
                    Text(if (settings.imperialUnits) "Units: Imperial" else "Units: Metric")
                }
            }
        }

        // Diagnostics & Mode 04
        item {
            Text("Diagnostics (Modes 02 / 03 / 04 / 06 / 07 / 0A)", fontWeight = FontWeight.Bold, color = TorqueEvoColors.ElectricBlue, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.scanDiagnostics() }) { Text("Scan All DTCs & VIN") }
                OutlinedButton(onClick = { viewModel.clearDtcs() }) {
                    Text("Clear DTCs (04)", color = TorqueEvoColors.CriticalRed)
                }
            }
            if (diagnosticReport != null) {
                val count = diagnosticReport.confirmed.size + diagnosticReport.pending.size + diagnosticReport.permanent.size
                Text("Found $count DTC(s) • Freeze Frame items: ${diagnosticReport.freezeFrame?.readings?.size ?: 0}", fontSize = 12.sp)
            }
        }

        // Trip Logs & CSV / JSON Export
        item {
            Text("Recorded Trips (${tripSessions.size})", fontWeight = FontWeight.Bold, color = TorqueEvoColors.ElectricBlue, fontSize = 13.sp)
            if (tripSessions.isEmpty()) {
                Text("No recorded sessions yet. Tap Data Logger on the HUD to record.", color = TorqueEvoColors.TextSecondary, fontSize = 12.sp)
            }
        }
        items(tripSessions.take(5)) { session ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Trip #${session.id} • %.2f km".format(session.distanceKm), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("${session.sampleCount} samples • Max %.0f km/h".format(session.maxSpeedKmh), fontSize = 11.sp, color = TorqueEvoColors.TextSecondary)
                }
                TextButton(onClick = { viewModel.exportSession(session, asJson = false) }) { Text("CSV") }
                TextButton(onClick = { viewModel.exportSession(session, asJson = true) }) { Text("JSON") }
            }
        }

        // UDS Mode 22 Custom PID Entry
        item {
            Text("Add Custom UDS Mode 22 PID", fontWeight = FontWeight.Bold, color = TorqueEvoColors.ElectricBlue, fontSize = 13.sp)
            OutlinedTextField(value = pidName, onValueChange = { pidName = it }, label = { Text("Name (e.g. Trans Temp)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = pidHex, onValueChange = { pidHex = it }, label = { Text("Mode+PID") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(value = pidUnit, onValueChange = { pidUnit = it }, label = { Text("Unit") }, singleLine = true, modifier = Modifier.weight(0.7f))
            }
            OutlinedTextField(value = pidFormula, onValueChange = { pidFormula = it }, label = { Text("Formula (A, B...)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                viewModel.addManualCustomPid(pidName, pidHex, pidFormula, pidUnit)
                pidName = ""
            }) { Text("Save Custom PID") }
            Spacer(Modifier.height(16.dp))
        }
    }
}
