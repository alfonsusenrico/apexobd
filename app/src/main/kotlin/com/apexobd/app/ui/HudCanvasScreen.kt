package com.apexobd.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apexobd.app.data.db.HudWidgetEntity
import com.apexobd.app.data.db.WidgetDisplayType
import com.apexobd.app.ui.components.AnalogDialWidget
import com.apexobd.app.ui.components.BarGaugeWidget
import com.apexobd.app.ui.components.DataLoggerWidget
import com.apexobd.app.ui.components.DigitalReadoutWidget
import com.apexobd.app.ui.components.DtcCheckerWidget
import com.apexobd.app.ui.components.FuelControlWidget
import com.apexobd.app.ui.components.MiniMapWidget
import com.apexobd.app.ui.components.PerfTimerWidget
import com.apexobd.app.ui.components.SemiDigitalArcWidget
import com.apexobd.app.ui.components.SparklineChartWidget
import com.apexobd.app.ui.theme.TorqueEvoColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun ApexAppRoot(
    viewModel: HudViewModel,
    onSystemChromeVisibility: (showSystemBars: Boolean) -> Unit
) {
    var onHudPage by remember { mutableStateOf(false) }

    LaunchedEffect(onHudPage) {
        viewModel.setOnHudScreen(onHudPage)
        onSystemChromeVisibility(!onHudPage)
    }

    if (!onHudPage) {
        HomeMenuScreen(
            viewModel = viewModel,
            onOpenHudCanvas = { onHudPage = true }
        )
    } else {
        HudCanvasScreen(
            viewModel = viewModel,
            onBackToHome = { onHudPage = false }
        )
    }
}

@Composable
fun HudCanvasScreen(
    viewModel: HudViewModel,
    onBackToHome: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val pages by viewModel.pagesWithWidgets.collectAsStateWithLifecycle()
    val liveValues by viewModel.liveValues.collectAsStateWithLifecycle()
    val historyBuffers by viewModel.historyBuffers.collectAsStateWithLifecycle()
    val gps by viewModel.locationTracker.gps.collectAsStateWithLifecycle()
    val diagReport by viewModel.diagnosticReport.collectAsStateWithLifecycle()
    val timer0100 by viewModel.timer0100.collectAsStateWithLifecycle()
    val isRecording by viewModel.isRecording.collectAsStateWithLifecycle()
    val sessions by viewModel.tripSessions.collectAsStateWithLifecycle()
    val supportedPids by viewModel.supportedPids.collectAsStateWithLifecycle()

    var editMode by remember { mutableStateOf(false) }
    var showContextMenu by remember { mutableStateOf(false) }
    var showComponentPicker by remember { mutableStateOf(false) }

    BackHandler {
        when {
            showComponentPicker -> showComponentPicker = false
            showContextMenu -> showContextMenu = false
            editMode -> editMode = false
            else -> onBackToHome()
        }
    }

    val pagerState = rememberPagerState(pageCount = { pages.size.coerceAtLeast(1) })
    LaunchedEffect(pagerState.currentPage, pages.size) {
        if (pages.isNotEmpty()) {
            viewModel.setVisiblePageIndex(pagerState.currentPage.coerceAtMost(pages.lastIndex))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TorqueEvoColors.PureBlack)
            .graphicsLayer { scaleX = if (settings.hudMirror) -1f else 1f }
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { showContextMenu = true }
                )
            }
    ) {
        if (pages.isNotEmpty()) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = !editMode,
                modifier = Modifier.fillMaxSize()
            ) { pageIdx ->
                val pageBundle = pages[pageIdx]
                ProportionalHudPageCanvas(
                    widgets = pageBundle.widgets,
                    editMode = editMode,
                    gridSnap = settings.gridSnapEnabled,
                    pageIndex = pageIdx,
                    viewModel = viewModel,
                    liveValues = liveValues,
                    historyBuffers = historyBuffers,
                    onLongPress = { showContextMenu = true },
                    onToggleRecord = { viewModel.toggleRecording() },
                    onNavigateExternal = { viewModel.openExternalNavigation(context) },
                    onToggleFuelPause = { viewModel.togglePauseFuelCalculation() },
                    onScanDtc = { viewModel.scanDiagnostics() },
                    onReset0100 = { viewModel.reset0100Best() },
                    onMoveWidgetDelta = { wId, delta -> viewModel.moveWidget(pageBundle.page.id, wId, delta) },
                    onCycleDisplay = { viewModel.cycleWidgetDisplayType(it) },
                    onCycleSize = { viewModel.cycleWidgetSize(it) },
                    onDeleteWidget = { viewModel.deleteWidget(it.id) },
                    onUpdateFreeformPos = { w, xf, yf -> viewModel.updateWidgetFreeformPosition(w, xf, yf) },
                    renderContextExtras = Triple(gps, diagReport, timer0100),
                    extraFlags = Triple(settings.mapProvider, settings.pauseFuelCalculation, isRecording to (sessions.firstOrNull()?.sampleCount ?: 0))
                )
            }
        }

        // Subtle low-opacity edge dots (zero chrome, glance-only page awareness)
        if (pages.size > 1) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 6.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                pages.forEachIndexed { idx, _ ->
                    val active = idx == pagerState.currentPage
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .height(if (active) 14.dp else 5.dp)
                            .clip(CircleShape)
                            .background(
                                if (active) TorqueEvoColors.ElectricBlue.copy(alpha = 0.7f)
                                else Color(0x45FFFFFF)
                            )
                    )
                }
            }
        }

        if (editMode) {
            Text(
                text = "EDITING · LONG-PRESS TO FINISH",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                color = TorqueEvoColors.ElectricBlue,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xCC0D1016))
                    .border(1.dp, TorqueEvoColors.ElectricBlue.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                    .clickable { editMode = false }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }

    // HIDDEN HOLD-TAP CONTEXT MENU
    if (showContextMenu) {
        val currentPage = pages.getOrNull(pagerState.currentPage)
        Dialog(onDismissRequest = { showContextMenu = false }) {
            Column(
                modifier = Modifier
                    .width(280.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF0E1116))
                    .border(1.dp, Color(0x2EFFFFFF), RoundedCornerShape(14.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "HUD CANVAS MENU",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.9.sp,
                    color = TorqueEvoColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
                ContextMenuButton("+ Add New Component") {
                    showContextMenu = false
                    showComponentPicker = true
                }
                ContextMenuButton("Move / Edit Components: ${if (editMode) "On" else "Off"}") {
                    editMode = !editMode
                    showContextMenu = false
                }
                ContextMenuButton("Grid Snap (8×4 / 4×8): ${if (settings.gridSnapEnabled) "On" else "Off"}") {
                    viewModel.toggleGridSnap()
                    showContextMenu = false
                }
                ContextMenuButton("+ Add New Blank Page") {
                    viewModel.addBlankPage("Page ${pages.size + 1}") { newIdx ->
                        scope.launch { pagerState.animateScrollToPage(newIdx) }
                    }
                    showContextMenu = false
                }
                if (pages.size > 1 && currentPage != null) {
                    ContextMenuButton("Delete Current Page (${currentPage.page.title})", color = TorqueEvoColors.CriticalRed) {
                        viewModel.deletePage(currentPage.page.id)
                        showContextMenu = false
                    }
                }
                ContextMenuButton(
                    "Pause Fuel Avg (Persisted): ${if (settings.pauseFuelCalculation) "On" else "Off"}",
                    color = if (settings.pauseFuelCalculation) TorqueEvoColors.WarnAmber else TorqueEvoColors.TextPrimary
                ) {
                    viewModel.togglePauseFuelCalculation()
                    showContextMenu = false
                }
                ContextMenuButton("← Back to Home Menu", color = TorqueEvoColors.ElectricBlue) {
                    showContextMenu = false
                    onBackToHome()
                }
            }
        }
    }

    // AVAILABLE-ONLY COMPONENT PICKER
    if (showComponentPicker) {
        val currentPage = pages.getOrNull(pagerState.currentPage)
        if (currentPage != null) {
            AvailableComponentPickerSheet(
                groupedCatalog = viewModel.availableGroupedCatalog(),
                supportedPidCount = supportedPids.size,
                onAddComponent = { key, type, cols, rows ->
                    viewModel.addWidget(currentPage.page.id, key, type, cols, rows)
                    showComponentPicker = false
                },
                onDismiss = { showComponentPicker = false }
            )
        }
    }
}

private data class GridPlacement(
    val widget: HudWidgetEntity,
    val col: Int,
    val row: Int,
    val spanCols: Int,
    val spanRows: Int
)

private fun packWidgetsIntoGrid(
    widgets: List<HudWidgetEntity>,
    cols: Int,
    rows: Int
): List<GridPlacement> {
    val occupied = Array(rows * 2) { BooleanArray(cols) }
    val out = mutableListOf<GridPlacement>()

    for (w in widgets) {
        val sw = w.spanCols.coerceIn(1, cols)
        val sh = w.spanRows.coerceIn(1, rows)
        var placed = false
        for (r in 0 until occupied.size - sh + 1) {
            for (c in 0..cols - sw) {
                var fits = true
                for (dr in 0 until sh) {
                    for (dc in 0 until sw) {
                        if (occupied[r + dr][c + dc]) {
                            fits = false
                            break
                        }
                    }
                    if (!fits) break
                }
                if (fits) {
                    for (dr in 0 until sh) {
                        for (dc in 0 until sw) {
                            occupied[r + dr][c + dc] = true
                        }
                    }
                    out += GridPlacement(w, c, r, sw, sh)
                    placed = true
                    break
                }
            }
            if (placed) break
        }
        if (!placed) {
            out += GridPlacement(w, 0, rows - 1, sw, sh)
        }
    }
    return out
}

@Composable
private fun ProportionalHudPageCanvas(
    widgets: List<HudWidgetEntity>,
    editMode: Boolean,
    gridSnap: Boolean,
    pageIndex: Int,
    viewModel: HudViewModel,
    liveValues: Map<String, Double>,
    historyBuffers: Map<String, List<Double>>,
    onLongPress: () -> Unit,
    onToggleRecord: () -> Unit,
    onNavigateExternal: () -> Unit,
    onToggleFuelPause: () -> Unit,
    onScanDtc: () -> Unit,
    onReset0100: () -> Unit,
    onMoveWidgetDelta: (Long, Int) -> Unit,
    onCycleDisplay: (HudWidgetEntity) -> Unit,
    onCycleSize: (HudWidgetEntity) -> Unit,
    onDeleteWidget: (HudWidgetEntity) -> Unit,
    onUpdateFreeformPos: (HudWidgetEntity, Float, Float) -> Unit,
    renderContextExtras: Triple<com.apexobd.app.platform.GpsSnapshot, com.apexobd.core.protocol.DiagnosticReport?, com.apexobd.core.scheduler.Timer0100State>,
    extraFlags: Triple<com.apexobd.app.data.prefs.MapProvider, Boolean, Pair<Boolean, Int>>
) {
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 10.dp, end = 16.dp, top = 10.dp, bottom = 10.dp)
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = { onLongPress() })
            }
    ) {
        val isLandscape = maxWidth > maxHeight
        val cols = if (isLandscape) 8 else 4
        val rows = if (isLandscape) 4 else 8
        val gap = 8.dp

        val availW = maxWidth
        val availH = maxHeight
        val cellW = (availW - gap * (cols - 1)) / cols
        val cellH = (availH - gap * (rows - 1)) / rows
        val availWPx = with(density) { availW.toPx() }
        val availHPx = with(density) { availH.toPx() }

        // Visual snap grid lines ONLY visible during Edit Mode + Grid Snap On
        if (editMode && gridSnap) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cellWPx = cellW.toPx()
                val cellHPx = cellH.toPx()
                val gapPx = gap.toPx()
                val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        val x = c * (cellWPx + gapPx)
                        val y = r * (cellHPx + gapPx)
                        drawRoundRect(
                            color = TorqueEvoColors.ElectricBlue.copy(alpha = 0.18f),
                            topLeft = Offset(x, y),
                            size = Size(cellWPx, cellHPx),
                            cornerRadius = CornerRadius(10.dp.toPx()),
                            style = Stroke(width = 1.dp.toPx(), pathEffect = dash)
                        )
                    }
                }
            }
        }

        if (widgets.isEmpty()) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "Blank HUD Canvas · Page ${pageIndex + 1}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0x73FFFFFF)
                )
                Text(
                    "Hold tap anywhere to add components, toggle grid snap (${cols}×${rows}), pause idle fuel, or return to Home",
                    fontSize = 12.sp,
                    color = Color(0x40FFFFFF),
                    textAlign = TextAlign.Center
                )
            }
        }

        val placements = remember(widgets, cols, rows, gridSnap) {
            if (gridSnap) packWidgetsIntoGrid(widgets, cols, rows) else emptyList()
        }

        if (gridSnap) {
            placements.forEach { slot ->
                val w = slot.widget
                val boxW = cellW * slot.spanCols + gap * (slot.spanCols - 1)
                val boxH = cellH * slot.spanRows + gap * (slot.spanRows - 1)
                val xOff = (cellW + gap) * slot.col
                val yOff = (cellH + gap) * slot.row

                Box(
                    modifier = Modifier
                        .offset(x = xOff, y = yOff)
                        .size(width = boxW, height = boxH)
                ) {
                    HudWidgetSlotWrapper(
                        widget = w,
                        editMode = editMode,
                        showFreeformArrows = true,
                        onSwapPrev = { onMoveWidgetDelta(w.id, -1) },
                        onSwapNext = { onMoveWidgetDelta(w.id, +1) },
                        onCycleDisplay = { onCycleDisplay(w) },
                        onCycleSize = { onCycleSize(w) },
                        onDelete = { onDeleteWidget(w) }
                    ) {
                        RenderWidgetContent(
                            widget = w,
                            viewModel = viewModel,
                            liveValues = liveValues,
                            historyBuffers = historyBuffers,
                            gps = renderContextExtras.first,
                            diagReport = renderContextExtras.second,
                            timer0100 = renderContextExtras.third,
                            mapProvider = extraFlags.first,
                            fuelPaused = extraFlags.second,
                            isRecording = extraFlags.third.first,
                            sampleCount = extraFlags.third.second,
                            onToggleRecord = onToggleRecord,
                            onNavigateExternal = onNavigateExternal,
                            onToggleFuelPause = onToggleFuelPause,
                            onScanDtc = onScanDtc,
                            onReset0100 = onReset0100
                        )
                    }
                }
            }
        } else {
            widgets.forEach { w ->
                val sw = w.spanCols.coerceIn(1, cols)
                val sh = w.spanRows.coerceIn(1, rows)
                val boxW = cellW * sw + gap * (sw - 1)
                val boxH = cellH * sh + gap * (sh - 1)

                var dragX by remember(w.id, w.freeformXFraction, availWPx) {
                    mutableFloatStateOf(w.freeformXFraction * availWPx)
                }
                var dragY by remember(w.id, w.freeformYFraction, availHPx) {
                    mutableFloatStateOf(w.freeformYFraction * availHPx)
                }

                Box(
                    modifier = Modifier
                        .offset { IntOffset(dragX.roundToInt(), dragY.roundToInt()) }
                        .size(width = boxW, height = boxH)
                        .then(
                            if (editMode) {
                                Modifier.pointerInput(w.id, availWPx, availHPx) {
                                    detectDragGestures(
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragX = (dragX + dragAmount.x).coerceIn(0f, (availWPx - 40f).coerceAtLeast(10f))
                                            dragY = (dragY + dragAmount.y).coerceIn(0f, (availHPx - 40f).coerceAtLeast(10f))
                                        },
                                        onDragEnd = {
                                            if (availWPx > 1f && availHPx > 1f) {
                                                onUpdateFreeformPos(w, dragX / availWPx, dragY / availHPx)
                                            }
                                        }
                                    )
                                }
                            } else Modifier
                        )
                ) {
                    HudWidgetSlotWrapper(
                        widget = w,
                        editMode = editMode,
                        showFreeformArrows = false,
                        onSwapPrev = {},
                        onSwapNext = {},
                        onCycleDisplay = { onCycleDisplay(w) },
                        onCycleSize = { onCycleSize(w) },
                        onDelete = { onDeleteWidget(w) }
                    ) {
                        RenderWidgetContent(
                            widget = w,
                            viewModel = viewModel,
                            liveValues = liveValues,
                            historyBuffers = historyBuffers,
                            gps = renderContextExtras.first,
                            diagReport = renderContextExtras.second,
                            timer0100 = renderContextExtras.third,
                            mapProvider = extraFlags.first,
                            fuelPaused = extraFlags.second,
                            isRecording = extraFlags.third.first,
                            sampleCount = extraFlags.third.second,
                            onToggleRecord = onToggleRecord,
                            onNavigateExternal = onNavigateExternal,
                            onToggleFuelPause = onToggleFuelPause,
                            onScanDtc = onScanDtc,
                            onReset0100 = onReset0100
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HudWidgetSlotWrapper(
    widget: HudWidgetEntity,
    editMode: Boolean,
    showFreeformArrows: Boolean,
    onSwapPrev: () -> Unit,
    onSwapNext: () -> Unit,
    onCycleDisplay: () -> Unit,
    onCycleSize: () -> Unit,
    onDelete: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (editMode) {
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x0D00A8FF))
                        .border(1.dp, TorqueEvoColors.ElectricBlue.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
                } else Modifier
            )
    ) {
        content()

        if (editMode) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                if (showFreeformArrows) {
                    EditPill("◀", Color.White, onSwapPrev)
                    EditPill("▶", Color.White, onSwapNext)
                }
                if (widget.sensorKey !in setOf("minimap", "dtc_checker", "timer_0100", "data_logger", "fuel_ctrl")) {
                    EditPill("◐", TorqueEvoColors.SkyBlue, onCycleDisplay)
                }
                EditPill("⤢", TorqueEvoColors.SkyBlue, onCycleSize)
                EditPill("×", TorqueEvoColors.CriticalRed, onDelete)
            }
        }
    }
}

@Composable
private fun RenderWidgetContent(
    widget: HudWidgetEntity,
    viewModel: HudViewModel,
    liveValues: Map<String, Double>,
    historyBuffers: Map<String, List<Double>>,
    gps: com.apexobd.app.platform.GpsSnapshot,
    diagReport: com.apexobd.core.protocol.DiagnosticReport?,
    timer0100: com.apexobd.core.scheduler.Timer0100State,
    mapProvider: com.apexobd.app.data.prefs.MapProvider,
    fuelPaused: Boolean,
    isRecording: Boolean,
    sampleCount: Int,
    onToggleRecord: () -> Unit,
    onNavigateExternal: () -> Unit,
    onToggleFuelPause: () -> Unit,
    onScanDtc: () -> Unit,
    onReset0100: () -> Unit
) {
    val type = runCatching { WidgetDisplayType.valueOf(widget.displayType) }.getOrDefault(WidgetDisplayType.DIGITAL)

    when (widget.sensorKey) {
        "minimap" -> MiniMapWidget(
            gps = gps,
            mapProvider = mapProvider,
            isRecording = isRecording,
            onToggleRecord = onToggleRecord,
            onNavigateExternal = onNavigateExternal
        )
        "fuel_ctrl" -> {
            val (kplVal, kplSym) = viewModel.convertForDisplay(
                liveValues["kpl_road_avg"],
                com.apexobd.core.model.SensorQuantity.FUEL_ECONOMY_KPL,
                "km/L"
            )
            val (dteVal, dteSym) = viewModel.convertForDisplay(
                liveValues["range_dte"],
                com.apexobd.core.model.SensorQuantity.DISTANCE_KM,
                "km"
            )
            FuelControlWidget(
                kplValue = kplVal,
                kplUnit = kplSym,
                estRangeValue = dteVal,
                estRangeUnit = dteSym,
                paused = fuelPaused,
                onTogglePause = onToggleFuelPause
            )
        }
        "dtc_checker" -> DtcCheckerWidget(report = diagReport, onScan = onScanDtc)
        "timer_0100" -> PerfTimerWidget(timer = timer0100, onReset = onReset0100)
        "data_logger" -> DataLoggerWidget(isRecording = isRecording, latestSampleCount = sampleCount, onToggleRecord = onToggleRecord)
        else -> {
            val meta = viewModel.catalogItemFor(widget.sensorKey)
            val rawVal = liveValues[widget.sensorKey]
            val (dispVal, dispUnit) = viewModel.convertForDisplay(rawVal, meta.quantity, meta.unit)
            val (cMin, cMax) = viewModel.convertBounds(meta.minValue, meta.maxValue, meta.quantity)
            val warnConv = widget.warnThreshold?.let { viewModel.convertForDisplay(it, meta.quantity, meta.unit).first }
            val critConv = widget.critThreshold?.let { viewModel.convertForDisplay(it, meta.quantity, meta.unit).first }

            when (type) {
                WidgetDisplayType.ANALOG_DIAL -> AnalogDialWidget(meta.shortLabel, dispVal, dispUnit, cMin, cMax, warnConv, critConv)
                WidgetDisplayType.SEMI_DIGITAL -> SemiDigitalArcWidget(meta.shortLabel, dispVal, dispUnit, cMin, cMax, warnConv, critConv)
                WidgetDisplayType.BAR -> BarGaugeWidget(meta.shortLabel, dispVal, dispUnit, cMin, cMax, warnConv, critConv)
                WidgetDisplayType.CHART -> {
                    val hist = (historyBuffers[widget.sensorKey] ?: emptyList()).map {
                        viewModel.convertForDisplay(it, meta.quantity, meta.unit).first ?: it
                    }
                    SparklineChartWidget(meta.shortLabel, dispVal, dispUnit, cMin, cMax, hist)
                }
                else -> DigitalReadoutWidget(meta.shortLabel, dispVal, dispUnit, cMin, cMax, warnConv, critConv)
            }
        }
    }
}

@Composable
private fun EditPill(symbol: String, tint: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(21.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Color(0xEB080A0E))
            .border(1.dp, Color(0x3DFFFFFF), RoundedCornerShape(5.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(symbol, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = tint)
    }
}

@Composable
private fun ContextMenuButton(
    label: String,
    color: Color = TorqueEvoColors.TextPrimary,
    onClick: () -> Unit
) {
    Text(
        text = label,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 9.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AvailableComponentPickerSheet(
    groupedCatalog: List<GroupedSensorCatalogItem>,
    supportedPidCount: Int,
    onAddComponent: (sensorKey: String, displayType: WidgetDisplayType, spanCols: Int, spanRows: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedGroup by remember { mutableStateOf(SensorCatalogGroup.ECU_MODE01) }
    val filteredItems = groupedCatalog.filter { it.group == selectedGroup }
    var selectedItem by remember(selectedGroup, filteredItems.size) {
        mutableStateOf(filteredItems.firstOrNull()?.item ?: groupedCatalog.first().item)
    }
    var selectedType by remember { mutableStateOf(WidgetDisplayType.DIGITAL) }
    var selectedSize by remember { mutableStateOf(2 to 1) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = TorqueEvoColors.ElevatedDark
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 26.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Add Component", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TorqueEvoColors.TextPrimary)
            Text(
                if (supportedPidCount > 0) "Showing only $supportedPidCount ECU Mode 01 PIDs & derived metrics supported by this vehicle"
                else "Showing standard PIDs & phone sensors (connect to car to filter by vehicle ECU)",
                fontSize = 11.sp,
                color = TorqueEvoColors.TextSecondary
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = selectedGroup == SensorCatalogGroup.ECU_MODE01, onClick = { selectedGroup = SensorCatalogGroup.ECU_MODE01 }, label = { Text("ECU Mode 01") })
                FilterChip(selected = selectedGroup == SensorCatalogGroup.CALCULATED, onClick = { selectedGroup = SensorCatalogGroup.CALCULATED }, label = { Text("Calculated") })
                FilterChip(selected = selectedGroup == SensorCatalogGroup.PHONE_AND_WIDGETS, onClick = { selectedGroup = SensorCatalogGroup.PHONE_AND_WIDGETS }, label = { Text("Phone & Widgets") })
                if (groupedCatalog.any { it.group == SensorCatalogGroup.CUSTOM_PIDS }) {
                    FilterChip(selected = selectedGroup == SensorCatalogGroup.CUSTOM_PIDS, onClick = { selectedGroup = SensorCatalogGroup.CUSTOM_PIDS }, label = { Text("Custom 21/22") })
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                filteredItems.forEach { entry ->
                    val item = entry.item
                    val active = item.key == selectedItem.key
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (active) Color(0x2600A8FF) else TorqueEvoColors.CardSurface)
                            .border(1.dp, if (active) TorqueEvoColors.ElectricBlue else TorqueEvoColors.SubtleBorder, RoundedCornerShape(8.dp))
                            .clickable {
                                selectedItem = item
                                if (item.isSpecialWidget) {
                                    selectedSize = if (item.key == "minimap") 2 to 3 else if (item.key == "dtc_checker") 4 to 1 else 2 to 1
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TorqueEvoColors.TextPrimary)
                            Text("${item.pidLabel} · Label: ${item.shortLabel}", fontSize = 10.sp, color = TorqueEvoColors.TextSecondary)
                        }
                        if (item.unit.isNotBlank()) {
                            Text(item.unit, fontSize = 11.sp, color = TorqueEvoColors.ElectricBlue, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            if (!selectedItem.isSpecialWidget) {
                Text("Component Visual Style", fontSize = 11.sp, color = TorqueEvoColors.TextSecondary, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        WidgetDisplayType.ANALOG_DIAL to "Precision Radial",
                        WidgetDisplayType.SEMI_DIGITAL to "Semi-Arc",
                        WidgetDisplayType.DIGITAL to "Digital Tile",
                        WidgetDisplayType.BAR to "Line Bar",
                        WidgetDisplayType.CHART to "Area Chart"
                    ).forEach { (dt, lbl) ->
                        FilterChip(selected = selectedType == dt, onClick = { selectedType = dt }, label = { Text(lbl, fontSize = 11.sp) })
                    }
                }
            }

            Text("Grid Size (8×4 Land / 4×8 Port)", fontSize = 11.sp, color = TorqueEvoColors.TextSecondary, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    (1 to 1) to "Small (1×1)",
                    (2 to 1) to "Wide (2×1)",
                    (2 to 2) to "Medium (2×2)",
                    (3 to 2) to "Large (3×2)",
                    (4 to 1) to "Banner (4×1)"
                ).forEach { (sz, lbl) ->
                    FilterChip(selected = selectedSize == sz, onClick = { selectedSize = sz }, label = { Text(lbl, fontSize = 11.sp) })
                }
            }

            Button(
                onClick = {
                    val resolvedType = when (selectedItem.key) {
                        "minimap" -> WidgetDisplayType.MINI_MAP
                        "dtc_checker" -> WidgetDisplayType.DTC_CHECKER
                        "timer_0100" -> WidgetDisplayType.PERF_TIMER
                        "data_logger" -> WidgetDisplayType.DATA_LOGGER
                        else -> selectedType
                    }
                    onAddComponent(selectedItem.key, resolvedType, selectedSize.first, selectedSize.second)
                },
                colors = ButtonDefaults.buttonColors(containerColor = TorqueEvoColors.ElectricBlue, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Add to Canvas", fontWeight = FontWeight.Bold)
            }
        }
    }
}
