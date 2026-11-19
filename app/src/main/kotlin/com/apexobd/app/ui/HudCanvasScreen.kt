package com.apexobd.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.apexobd.app.ui.components.formatTelemetryValue
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationServices
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apexobd.app.data.db.HudWidgetEntity
import com.apexobd.app.data.db.WidgetDisplayType
import com.apexobd.app.data.prefs.HudGridScale
import com.apexobd.app.ui.components.AnalogDialWidget
import com.apexobd.app.ui.components.BarGaugeWidget
import com.apexobd.app.ui.components.DataLoggerWidget
import com.apexobd.app.ui.components.DigitalReadoutWidget
import com.apexobd.app.ui.components.DtcCheckerWidget
import com.apexobd.app.ui.components.FuelControlWidget
import com.apexobd.app.ui.components.MiniMapWidget
import com.apexobd.app.ui.components.PerfTimerWidget
import com.apexobd.app.ui.components.SegmentedBarGaugeWidget
import com.apexobd.app.ui.components.SemiDigitalArcWidget
import com.apexobd.app.ui.components.SparklineChartWidget
import com.apexobd.app.ui.theme.LocalHudVisualStyle
import com.apexobd.app.ui.theme.TorqueEvoColors
import com.apexobd.app.ui.theme.hudCanvasBackdrop
import com.apexobd.app.ui.theme.resolveHudVisualPalette
import com.apexobd.core.model.FuelType
import com.apexobd.core.model.SensorQuantity
import com.apexobd.core.protocol.TripRunCalculator
import com.apexobd.core.scheduler.ConnectionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
fun ApexAppRoot(
    viewModel: HudViewModel,
    onHudModeChanged: (inHud: Boolean) -> Unit
) {
    var onHudPage by remember { mutableStateOf(false) }
    val pendingSaveTrip by viewModel.pendingSaveTrip.collectAsStateWithLifecycle()
    val shareCardDraft by viewModel.shareCardDraft.collectAsStateWithLifecycle()
    val shareCardPreparing by viewModel.shareCardPreparing.collectAsStateWithLifecycle()
    val shareCardExporting by viewModel.shareCardExporting.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val activeVehicle by viewModel.activeVehicle.collectAsStateWithLifecycle()

    LaunchedEffect(onHudPage) {
        viewModel.setOnHudScreen(onHudPage)
        onHudModeChanged(onHudPage)
    }

    Box(modifier = Modifier.fillMaxSize()) {
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

        pendingSaveTrip?.let { session ->
            val fuelType = activeVehicle?.let { runCatching { FuelType.valueOf(it.fuelType) }.getOrNull() } ?: FuelType.GASOLINE
            SaveTripSummaryDialog(
                session = session,
                vehicleName = activeVehicle?.name ?: "Active Vehicle",
                unitPrefs = settings.toUnitPreferences(),
                fuelType = fuelType,
                onSave = { viewModel.savePendingTrip() },
                onSaveAndShareCard = {
                    viewModel.savePendingTrip()
                    viewModel.exportSessionShareCard(session)
                },
                onDiscard = { viewModel.discardPendingTrip() }
            )
        }

        shareCardDraft?.let { draft ->
            val fuelType = draft.vehicle?.let { runCatching { FuelType.valueOf(it.fuelType) }.getOrNull() }
                ?: activeVehicle?.let { runCatching { FuelType.valueOf(it.fuelType) }.getOrNull() }
                ?: FuelType.GASOLINE
            ShareCardComposerDialog(
                draft = draft,
                initialConfig = settings.tripCardConfig,
                unitPrefs = settings.toUnitPreferences(),
                fuelType = fuelType,
                isExporting = shareCardExporting,
                onConfigChanged = { cfg -> viewModel.updateSavedTripCardConfig(cfg) },
                onPickBackgroundPhoto = { uri, cb -> viewModel.copyPickedCardBackgroundPhoto(uri, cb) },
                onLoadSurroundingRoads = { samples, w, h, mainOnly -> viewModel.loadSurroundingRoadsForCard(samples, w, h, mainOnly) },
                onConfirmExport = { cfg, action, roadLayer ->
                    viewModel.confirmShareCardExport(cfg, action, roadLayer)
                },
                onDismiss = { viewModel.dismissShareCardComposer() }
            )
        }

        if (shareCardPreparing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x99000000))
                    .zIndex(50f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(TorqueEvoColors.ElevatedDark)
                        .border(1.dp, TorqueEvoColors.ElectricBlue.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 24.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(
                        color = TorqueEvoColors.ElectricBlue,
                        modifier = Modifier.size(30.dp),
                        strokeWidth = 3.dp
                    )
                    Text(
                        "Generating route card...",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TorqueEvoColors.TextPrimary
                    )
                }
            }
        }
    }
}

@Composable
fun HudCanvasScreen(
    viewModel: HudViewModel,
    onBackToHome: () -> Unit
) {
    val scope = rememberCoroutineScope()

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val pages by viewModel.pagesWithWidgets.collectAsStateWithLifecycle()
    val liveValues by viewModel.displayValues.collectAsStateWithLifecycle()
    val historyBuffers by viewModel.historyBuffers.collectAsStateWithLifecycle()
    val gps by viewModel.locationTracker.gps.collectAsStateWithLifecycle()
    val diagReport by viewModel.diagnosticReport.collectAsStateWithLifecycle()
    val timer0100 by viewModel.timer0100.collectAsStateWithLifecycle()
    val isRecording by viewModel.isRecording.collectAsStateWithLifecycle()
    val isRecordingPaused by viewModel.isRecordingPaused.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val sessions by viewModel.tripSessions.collectAsStateWithLifecycle()
    val isGpsEnabled by viewModel.locationTracker.isGpsEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    fun hasLocPerm(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    var hasLocationPermission by remember { mutableStateOf(hasLocPerm()) }
    var dismissGpsPrompt by remember { mutableStateOf(false) }

    val locationSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) {
        hasLocationPermission = hasLocPerm()
        if (viewModel.locationTracker.refreshLocationEnabledState() && hasLocationPermission) {
            viewModel.locationTracker.restartUpdatesAndFetchCurrent()
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasLocationPermission = hasLocPerm()
        if (hasLocationPermission) {
            viewModel.locationTracker.restartUpdatesAndFetchCurrent()
        }
    }

    val enableGpsAction: () -> Unit = {
        if (!hasLocPerm()) {
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        } else {
            val req = viewModel.locationTracker.buildHighAccuracySettingsRequest()
            LocationServices.getSettingsClient(context)
                .checkLocationSettings(req)
                .addOnSuccessListener {
                    viewModel.locationTracker.restartUpdatesAndFetchCurrent()
                }
                .addOnFailureListener { ex ->
                    if (ex is ResolvableApiException) {
                        runCatching {
                            locationSettingsLauncher.launch(IntentSenderRequest.Builder(ex.resolution).build())
                        }.onFailure {
                            runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                        }
                    } else {
                        runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                    }
                }
        }
    }

    LaunchedEffect(Unit) {
        hasLocationPermission = hasLocPerm()
        if (viewModel.locationTracker.refreshLocationEnabledState() && hasLocationPermission) {
            viewModel.locationTracker.requestImmediateFix()
        }
    }

    LaunchedEffect(isGpsEnabled, hasLocationPermission) {
        if (isGpsEnabled && hasLocationPermission) {
            dismissGpsPrompt = false
        }
    }

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasLocationPermission = hasLocPerm()
                if (viewModel.locationTracker.refreshLocationEnabledState() && hasLocationPermission) {
                    viewModel.locationTracker.restartUpdatesAndFetchCurrent()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    var showConnectedToast by remember { mutableStateOf(false) }
    var wasNotConnected by remember { mutableStateOf(true) }
    LaunchedEffect(connectionState) {
        if (connectionState is ConnectionState.Connected) {
            if (wasNotConnected) {
                wasNotConnected = false
                showConnectedToast = true
                delay(1800L)
                showConnectedToast = false
            }
        } else {
            wasNotConnected = true
            showConnectedToast = false
        }
    }

    var editMode by remember { mutableStateOf(false) }
    var isDraggingAny by remember { mutableStateOf(false) }
    var selectedWidgetId by remember { mutableStateOf<Long?>(null) }
    var editingWidgetId by remember { mutableStateOf<Long?>(null) }
    var showContextMenu by remember { mutableStateOf(false) }
    var showAppearanceDialog by remember { mutableStateOf(false) }
    var showComponentPicker by remember { mutableStateOf(false) }

    val hudPalette = remember(
        settings.hudTheme,
        settings.hudCanvasBackdrop,
        settings.hudCardStyle,
        settings.showWidgetBorders
    ) {
        resolveHudVisualPalette(
            preset = settings.hudTheme,
            backdrop = settings.hudCanvasBackdrop,
            cardStyle = settings.hudCardStyle,
            showBorders = settings.showWidgetBorders
        )
    }

    BackHandler {
        when {
            showAppearanceDialog -> showAppearanceDialog = false
            showComponentPicker -> showComponentPicker = false
            editingWidgetId != null -> editingWidgetId = null
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

    LaunchedEffect(editMode, pagerState.currentPage) {
        if (!editMode) isDraggingAny = false
        selectedWidgetId = if (editMode) {
            pages.getOrNull(pagerState.currentPage)?.widgets?.firstOrNull()?.id
        } else {
            null
        }
    }

    CompositionLocalProvider(LocalHudVisualStyle provides hudPalette) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .hudCanvasBackdrop(hudPalette)
            .graphicsLayer { scaleX = if (settings.hudMirror) -1f else 1f }
    ) {
        if (pages.isNotEmpty()) {
            VerticalPager(
                state = pagerState,
                userScrollEnabled = !editMode,
                modifier = Modifier.fillMaxSize()
            ) { pageIdx ->
                val pageBundle = pages[pageIdx]
                ProportionalHudPageCanvas(
                    widgets = pageBundle.widgets,
                    editMode = editMode,
                    selectedWidgetId = selectedWidgetId,
                    gridSnap = settings.gridSnapEnabled,
                    gridScale = settings.hudGridScale,
                    pageIndex = pageIdx,
                    viewModel = viewModel,
                    liveValues = liveValues,
                    historyBuffers = historyBuffers,
                    onLongPressCanvas = { showContextMenu = true },
                    onHoldWidget = { w ->
                        selectedWidgetId = w.id
                        editingWidgetId = w.id
                    },
                    onSelectWidget = { w -> selectedWidgetId = w.id },
                    onDraggingChanged = { dragging -> isDraggingAny = dragging },
                    onCommitGridPos = { w, col, row -> viewModel.updateWidgetGridPosition(w, col, row) },
                    onToggleRecord = { viewModel.toggleRecording() },
                    onTogglePauseResume = { viewModel.togglePauseResumeRecording() },
                    onToggleFuelPause = { viewModel.togglePauseFuelCalculation() },
                    onScanDtc = { viewModel.scanDiagnostics() },
                    onReset0100 = { viewModel.reset0100Best() },
                    onUpdateFreeformPos = { w, xf, yf, mxf, myf ->
                        viewModel.updateWidgetFreeformPosition(w, xf, yf, mxf, myf)
                    },
                    renderContextExtras = Triple(gps, diagReport, timer0100),
                    extraFlags = Triple(settings.mapProvider, settings.pauseFuelCalculation, isRecording to (sessions.firstOrNull()?.sampleCount ?: 0)),
                    isRecordingPaused = isRecordingPaused,
                    onEnableGps = enableGpsAction
                )
            }
        }

        HudConnectionStatusBanner(
            connectionState = connectionState,
            showConnectedToast = showConnectedToast,
            endpoint = settings.adapterDetailedEndpoint(),
            mirrored = settings.hudMirror,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(top = 8.dp)
                .zIndex(20f)
        )

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
                                if (active) hudPalette.accent.copy(alpha = 0.78f)
                                else Color(0x45FFFFFF)
                            )
                    )
                }
            }
        }

        if (editMode) {
            val currentPage = pages.getOrNull(pagerState.currentPage)
            val selectedWidget = currentPage?.widgets?.firstOrNull { it.id == selectedWidgetId }
                ?: currentPage?.widgets?.firstOrNull()
            val canStyle = selectedWidget != null && !isFixedLayoutSpecialWidget(selectedWidget.sensorKey)

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .alpha(if (isDraggingAny) 0.25f else 1f)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xF00D1016))
                    .border(1.dp, TorqueEvoColors.ElectricBlue.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = selectedWidget?.let {
                        val meta = viewModel.catalogItemFor(it.sensorKey)
                        "${meta.shortLabel} (${it.spanCols}×${it.spanRows}) · Drag tiles"
                    } ?: "Drag tiles",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TorqueEvoColors.ElectricBlue,
                    modifier = Modifier.padding(end = 4.dp)
                )
                EditActionPill(
                    label = if (settings.gridSnapEnabled) "Snap: Tile" else "Snap: Free",
                    tint = if (settings.gridSnapEnabled) TorqueEvoColors.SkyBlue else TorqueEvoColors.WarnAmber
                ) { viewModel.toggleGridSnap() }
                if (selectedWidget != null && currentPage != null) {
                    if (canStyle) {
                        EditActionPill("◐ Style", TorqueEvoColors.SkyBlue) { viewModel.cycleWidgetDisplayType(selectedWidget) }
                    }
                    EditActionPill("⤢ Resize", TorqueEvoColors.SkyBlue) { editingWidgetId = selectedWidget.id }
                    EditActionPill("×", TorqueEvoColors.CriticalRed) {
                        val remaining = currentPage.widgets.filter { it.id != selectedWidget.id }
                        viewModel.deleteWidget(selectedWidget.id)
                        selectedWidgetId = remaining.firstOrNull()?.id
                    }
                }
                EditActionPill("Done ✓", Color(0xFF020810), bg = TorqueEvoColors.ElectricBlue) {
                    editMode = false
                }
            }
        }

        // HOLD-COMPONENT EDIT MENU (Style, Dynamic Resize, Drag & Drop, Remove)
        val editingPage = pages.getOrNull(pagerState.currentPage)
        val editingWidget = editingWidgetId?.let { targetId ->
            editingPage?.widgets?.firstOrNull { it.id == targetId }
        }
        if (editingWidget != null && editingPage != null) {
            ComponentEditDialog(
                widget = editingWidget,
                widgetLabel = viewModel.catalogItemFor(editingWidget.sensorKey).label,
                gridScale = settings.hudGridScale,
                onSelectDisplayType = { dt -> viewModel.updateWidgetDisplayType(editingWidget, dt) },
                onResize = { c, r -> viewModel.updateWidgetSize(editingWidget, c, r) },
                onEnterMoveMode = {
                    editingWidgetId = null
                    selectedWidgetId = editingWidget.id
                    editMode = true
                },
                onOpenCanvasMenu = {
                    editingWidgetId = null
                    showContextMenu = true
                },
                onRemove = {
                    viewModel.deleteWidget(editingWidget.id)
                    if (selectedWidgetId == editingWidget.id) selectedWidgetId = null
                    editingWidgetId = null
                },
                onDismiss = { editingWidgetId = null }
            )
        }

        // FULLSCREEN LANDSCAPE COMPONENT STUDIO WITH LIVE PREVIEW
        if (showComponentPicker) {
            val currentPage = pages.getOrNull(pagerState.currentPage)
            if (currentPage != null) {
                FullscreenComponentPickerDialog(
                    viewModel = viewModel,
                    gridScale = settings.hudGridScale,
                    liveValues = liveValues,
                    historyBuffers = historyBuffers,
                    gps = gps,
                    diagReport = diagReport,
                    timer0100 = timer0100,
                    mapProvider = settings.mapProvider,
                    fuelPaused = settings.pauseFuelCalculation,
                    isRecording = isRecording,
                    sampleCount = sessions.firstOrNull()?.sampleCount ?: 0,
                    onToggleRecord = { viewModel.toggleRecording() },
                    onToggleFuelPause = { viewModel.togglePauseFuelCalculation() },
                    onScanDtc = { viewModel.scanDiagnostics() },
                    onReset0100 = { viewModel.reset0100Best() },
                    onAddComponent = { key, type, cols, rows ->
                        viewModel.addWidget(currentPage.page.id, key, type, cols, rows)
                        showComponentPicker = false
                    },
                    onDismiss = { showComponentPicker = false }
                )
            }
        }
    }

    // HIDDEN HOLD-TAP CONTEXT MENU (Landscape-friendly scrollable sheet)
    if (showContextMenu) {
        val currentPage = pages.getOrNull(pagerState.currentPage)
        Dialog(onDismissRequest = { showContextMenu = false }) {
            Column(
                modifier = Modifier
                    .width(340.dp)
                    .heightIn(max = 360.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF0E1116))
                    .border(1.dp, Color(0x2EFFFFFF), RoundedCornerShape(14.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    "HUD CANVAS MENU",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.9.sp,
                    color = TorqueEvoColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
                ContextMenuButton("+ Add New Component") {
                    showContextMenu = false
                    showComponentPicker = true
                }
                ContextMenuButton("Move / Drag Components: ${if (editMode) "On" else "Off"}") {
                    editMode = !editMode
                    showContextMenu = false
                }
                ContextMenuButton(
                    "Component Borders: ${if (settings.showWidgetBorders) "Visible" else "Hidden"}",
                    color = if (settings.showWidgetBorders) TorqueEvoColors.TextPrimary else TorqueEvoColors.SkyBlue
                ) {
                    viewModel.toggleWidgetBorders()
                    showContextMenu = false
                }
                ContextMenuButton(
                    "Theme & Background (${settings.hudTheme.label}) ▸",
                    color = Color(settings.hudTheme.accentHex)
                ) {
                    showContextMenu = false
                    showAppearanceDialog = true
                }
                ContextMenuButton(
                    "Grid Snap (${settings.hudGridScale.cols}×${settings.hudGridScale.rows}): ${if (settings.gridSnapEnabled) "Snap to Tile" else "Freeform"}"
                ) {
                    viewModel.toggleGridSnap()
                    showContextMenu = false
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Grid Scale:", fontSize = 11.sp, color = TorqueEvoColors.TextSecondary)
                    HudGridScale.entries.forEach { scale ->
                        ApexFilterChip(
                            selected = settings.hudGridScale == scale,
                            onClick = { viewModel.setHudGridScale(scale) },
                            label = "${scale.cols}×${scale.rows}",
                            compact = true
                        )
                    }
                }
                ContextMenuButton("+ Add New Blank Page") {
                    viewModel.addBlankPage("Page ${pages.size + 1}") { newIdx ->
                        scope.launch { pagerState.animateScrollToPage(newIdx) }
                    }
                    showContextMenu = false
                }
                if (currentPage != null && currentPage.widgets.isNotEmpty()) {
                    ContextMenuButton("Clear Page Widgets", color = TorqueEvoColors.WarnAmber) {
                        viewModel.clearPageWidgets(currentPage.page.id)
                        selectedWidgetId = null
                        showContextMenu = false
                    }
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

    if (showAppearanceDialog) {
        Dialog(onDismissRequest = { showAppearanceDialog = false }) {
            Column(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .heightIn(max = 365.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xF20E1116))
                    .border(1.dp, hudPalette.accent.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "HUD Theme & Canvas Appearance",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TorqueEvoColors.TextPrimary,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "Done",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TorqueEvoColors.ElectricBlue,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(TorqueEvoColors.CardSurface)
                            .border(1.dp, TorqueEvoColors.SubtleBorder, RoundedCornerShape(8.dp))
                            .clickable { showAppearanceDialog = false }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
                HudAppearanceControlsColumn(
                    selectedTheme = settings.hudTheme,
                    selectedBackdrop = settings.hudCanvasBackdrop,
                    selectedCardStyle = settings.hudCardStyle,
                    showBorders = settings.showWidgetBorders,
                    onSelectTheme = { viewModel.setHudTheme(it) },
                    onSelectBackdrop = { viewModel.setHudCanvasBackdrop(it) },
                    onSelectCardStyle = { viewModel.setHudCardStyle(it) },
                    onToggleBorders = { viewModel.toggleWidgetBorders() },
                    selectedGridScale = settings.hudGridScale,
                    onSelectGridScale = { viewModel.setHudGridScale(it) },
                    selectedSmoothing = settings.gaugeSmoothingMode,
                    onSelectSmoothing = { viewModel.setGaugeSmoothingMode(it) },
                    liveMicroMotion = settings.liveMicroMotion,
                    onToggleLiveMicroMotion = { viewModel.setLiveMicroMotion(!settings.liveMicroMotion) },
                    hudMirror = settings.hudMirror,
                    onToggleHudMirror = { viewModel.toggleHudMirror() }
                )
            }
        }
    }

    if ((!isGpsEnabled || !hasLocationPermission) && !dismissGpsPrompt) {
        Dialog(onDismissRequest = { dismissGpsPrompt = true }) {
            Column(
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xF50E1219))
                    .border(1.5.dp, TorqueEvoColors.WarnAmber, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = if (!hasLocationPermission) "Location Permission Required" else "GPS Location Is Turned Off",
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = TorqueEvoColors.WarnAmber
                )
                Text(
                    text = if (!hasLocationPermission) {
                        "ApexOBD needs Location access to read GPS speed, show the live map, and record your driving route."
                    } else {
                        "Turn on GPS so speed, live navigation map, and route tracking update properly."
                    },
                    fontSize = 12.5.sp,
                    color = TorqueEvoColors.TextPrimary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { dismissGpsPrompt = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Later", color = TorqueEvoColors.TextSecondary)
                    }
                    Button(
                        onClick = enableGpsAction,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TorqueEvoColors.WarnAmber,
                            contentColor = Color.Black
                        ),
                        modifier = Modifier.weight(1.4f)
                    ) {
                        Text(
                            if (!hasLocationPermission) "Allow Location" else "Enable GPS",
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
        }
    }
    }
}

private fun isFixedLayoutSpecialWidget(sensorKey: String): Boolean =
    sensorKey in setOf("minimap", "dtc_checker", "timer_0100", "data_logger", "fuel_ctrl")

/**
 * Flashing top OBD2 status indicator modeled after Torque Pro:
 * pulses while finding or disconnected from the adapter and disappears once live data connects.
 */
@Composable
private fun HudConnectionStatusBanner(
    connectionState: ConnectionState,
    showConnectedToast: Boolean,
    endpoint: String,
    mirrored: Boolean,
    modifier: Modifier = Modifier
) {
    val visible = connectionState !is ConnectionState.Connected || showConnectedToast
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(180)),
        exit = fadeOut(animationSpec = tween(350)),
        modifier = modifier.graphicsLayer { scaleX = if (mirrored) -1f else 1f }
    ) {
        val pulseTransition = rememberInfiniteTransition(label = "obdPulse")
        val pulseAlpha by pulseTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = 0.25f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 600),
                repeatMode = RepeatMode.Reverse
            ),
            label = "obdDotAlpha"
        )
        val (dotCol, borderCol, bgCol, text) = when (connectionState) {
            is ConnectionState.Connected -> listOf(
                TorqueEvoColors.NominalGreen,
                TorqueEvoColors.NominalGreen.copy(alpha = 0.72f),
                Color(0xF5061B10),
                "OBD2 CONNECTED · ${connectionState.handshake.protocolName}"
            )
            is ConnectionState.Connecting -> listOf(
                TorqueEvoColors.ElectricBlue,
                TorqueEvoColors.ElectricBlue.copy(alpha = 0.72f),
                Color(0xF5071320),
                "FINDING OBD2 ADAPTER ($endpoint)..."
            )
            is ConnectionState.Reconnecting -> listOf(
                TorqueEvoColors.WarnAmber,
                TorqueEvoColors.WarnAmber.copy(alpha = 0.75f),
                Color(0xF51A1205),
                "OBD2 RECONNECTING (#${connectionState.attempt})..."
            )
            is ConnectionState.Disconnected -> listOf(
                TorqueEvoColors.CriticalRed,
                TorqueEvoColors.CriticalRed.copy(alpha = 0.72f),
                Color(0xF51C0709),
                "OBD2 NOT CONNECTED · $endpoint"
            )
        }
        val activeDotAlpha = if (connectionState is ConnectionState.Connected) 1f else pulseAlpha
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(99.dp))
                .background(bgCol as Color)
                .border(1.dp, borderCol as Color, RoundedCornerShape(99.dp))
                .padding(horizontal = 12.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .alpha(activeDotAlpha)
                    .clip(CircleShape)
                    .background(dotCol as Color)
            )
            Text(
                text = text as String,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = TorqueEvoColors.TextPrimary,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun rememberStationaryHoldModifier(
    key: Any?,
    enabled: Boolean,
    onHold: () -> Unit
): Modifier {
    val latestOnHold by rememberUpdatedState(onHold)
    if (!enabled) return Modifier
    return Modifier.pointerInput(key, enabled) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val touchSlop = viewConfiguration.touchSlop
            val timeoutMs = viewConfiguration.longPressTimeoutMillis
            var triggered = false
            while (true) {
                val elapsed = android.os.SystemClock.uptimeMillis() - down.uptimeMillis
                val remaining = timeoutMs - elapsed
                if (remaining <= 0L) {
                    latestOnHold()
                    triggered = true
                    break
                }
                val event = withTimeoutOrNull(remaining) {
                    awaitPointerEvent(PointerEventPass.Initial)
                }
                if (event == null) {
                    latestOnHold()
                    triggered = true
                    break
                }
                val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!pointer.pressed) break
                if ((pointer.position - down.position).getDistance() > touchSlop) break
            }
            if (triggered) {
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    ev.changes.forEach { it.consume() }
                    if (ev.changes.none { it.pressed }) break
                }
            }
        }
    }
}

private data class SnapDragPreview(
    val widgetId: Long,
    val col: Int,
    val row: Int,
    val spanCols: Int,
    val spanRows: Int
)

@Composable
private fun ProportionalHudPageCanvas(
    widgets: List<HudWidgetEntity>,
    editMode: Boolean,
    selectedWidgetId: Long?,
    gridSnap: Boolean,
    gridScale: HudGridScale,
    pageIndex: Int,
    viewModel: HudViewModel,
    liveValues: Map<String, Double>,
    historyBuffers: Map<String, List<Double>>,
    onLongPressCanvas: () -> Unit,
    onHoldWidget: (HudWidgetEntity) -> Unit,
    onSelectWidget: (HudWidgetEntity) -> Unit,
    onDraggingChanged: (Boolean) -> Unit,
    onCommitGridPos: (HudWidgetEntity, Int, Int) -> Unit,
    onToggleRecord: () -> Unit,
    onTogglePauseResume: () -> Unit,
    onToggleFuelPause: () -> Unit,
    onScanDtc: () -> Unit,
    onReset0100: () -> Unit,
    onUpdateFreeformPos: (HudWidgetEntity, Float, Float, Float, Float) -> Unit,
    renderContextExtras: Triple<com.apexobd.app.platform.GpsSnapshot, com.apexobd.core.protocol.DiagnosticReport?, com.apexobd.core.scheduler.Timer0100State>,
    extraFlags: Triple<com.apexobd.app.data.prefs.MapProvider, Boolean, Pair<Boolean, Int>>,
    isRecordingPaused: Boolean = false,
    onEnableGps: (() -> Unit)? = null
) {
    val density = LocalDensity.current
    var snapPreview by remember { mutableStateOf<SnapDragPreview?>(null) }

    LaunchedEffect(editMode, gridSnap) {
        if (!editMode || !gridSnap) snapPreview = null
        if (!editMode) onDraggingChanged(false)
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(start = 10.dp, end = 16.dp, top = 10.dp, bottom = 10.dp)
    ) {
        // Background long-press target strictly behind placed widgets so widget holds open Component Menu
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onLongPress = { onLongPressCanvas() })
                }
        )

        val cols = gridScale.cols
        val rows = gridScale.rows
        val gap = if (cols >= 12) 5.dp else 8.dp

        val availW = maxWidth
        val availH = maxHeight
        val cellW = (availW - gap * (cols - 1)) / cols
        val cellH = (availH - gap * (rows - 1)) / rows
        val availWPx = with(density) { availW.toPx() }
        val availHPx = with(density) { availH.toPx() }
        val cellWPx = with(density) { cellW.toPx() }
        val cellHPx = with(density) { cellH.toPx() }
        val gapPx = with(density) { gap.toPx() }

        // Visual snap grid lines ONLY visible during Edit Mode + Grid Snap On
        if (editMode && gridSnap) {
            Canvas(modifier = Modifier.fillMaxSize()) {
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
                snapPreview?.let { sp ->
                    val targetX = sp.col * (cellWPx + gapPx)
                    val targetY = sp.row * (cellHPx + gapPx)
                    val targetW = cellWPx * sp.spanCols + gapPx * (sp.spanCols - 1)
                    val targetH = cellHPx * sp.spanRows + gapPx * (sp.spanRows - 1)
                    drawRoundRect(
                        color = TorqueEvoColors.ElectricBlue.copy(alpha = 0.16f),
                        topLeft = Offset(targetX, targetY),
                        size = Size(targetW, targetH),
                        cornerRadius = CornerRadius(12.dp.toPx())
                    )
                    drawRoundRect(
                        color = TorqueEvoColors.ElectricBlue,
                        topLeft = Offset(targetX, targetY),
                        size = Size(targetW, targetH),
                        cornerRadius = CornerRadius(12.dp.toPx()),
                        style = Stroke(width = 2.dp.toPx())
                    )
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
                    "Blank HUD Dashboard · Page ${pageIndex + 1}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0x73FFFFFF)
                )
                Text(
                    "Hold empty canvas for menu, or hold any component to edit / resize / move / remove",
                    fontSize = 12.sp,
                    color = Color(0x40FFFFFF),
                    textAlign = TextAlign.Center
                )
            }
        }

        val placementMap = remember(widgets, cols, rows, gridSnap) {
            if (gridSnap) resolveGridPlacements(widgets, cols, rows).associateBy { it.widget.id } else emptyMap()
        }

        widgets.forEach { w ->
            key(w.id) {
                val slot = placementMap[w.id]
                val sw = (slot?.spanCols ?: w.spanCols).coerceIn(1, cols)
                val sh = (slot?.spanRows ?: w.spanRows).coerceIn(1, rows)
                val boxW = cellW * sw + gap * (sw - 1)
                val boxH = cellH * sh + gap * (sh - 1)
                val boxWPx = with(density) { boxW.toPx() }
                val boxHPx = with(density) { boxH.toPx() }
                val maxXPx = (availWPx - boxWPx).coerceAtLeast(0f)
                val maxYPx = (availHPx - boxHPx).coerceAtLeast(0f)

                val baseXPx = if (gridSnap && slot != null) {
                    ((cellWPx + gapPx) * slot.col).coerceIn(0f, maxXPx)
                } else {
                    (w.freeformXFraction * availWPx).coerceIn(0f, maxXPx)
                }
                val baseYPx = if (gridSnap && slot != null) {
                    ((cellHPx + gapPx) * slot.row).coerceIn(0f, maxYPx)
                } else {
                    (w.freeformYFraction * availHPx).coerceIn(0f, maxYPx)
                }

                var dragDelta by remember(w.id) { mutableStateOf(Offset.Zero) }
                var isDraggingThis by remember(w.id) { mutableStateOf(false) }
                var droppedOverride by remember(
                    w.id,
                    gridSnap,
                    cols,
                    rows,
                    w.gridCol,
                    w.gridRow,
                    w.freeformXFraction,
                    w.freeformYFraction,
                    availWPx,
                    availHPx
                ) { mutableStateOf<Offset?>(null) }

                val latestWidget by rememberUpdatedState(w)
                val latestBaseX by rememberUpdatedState(droppedOverride?.x ?: baseXPx)
                val latestBaseY by rememberUpdatedState(droppedOverride?.y ?: baseYPx)
                val latestOnSelect by rememberUpdatedState(onSelectWidget)
                val latestOnDragging by rememberUpdatedState(onDraggingChanged)
                val latestOnCommitGrid by rememberUpdatedState(onCommitGridPos)
                val latestOnUpdateFreeform by rememberUpdatedState(onUpdateFreeformPos)

                val anchorX = droppedOverride?.x ?: baseXPx
                val anchorY = droppedOverride?.y ?: baseYPx
                val renderX = (anchorX + dragDelta.x).coerceIn(0f, maxXPx)
                val renderY = (anchorY + dragDelta.y).coerceIn(0f, maxYPx)

                Box(
                    modifier = Modifier
                        .zIndex(if (isDraggingThis) 10f else 0f)
                        .offset { IntOffset(renderX.roundToInt(), renderY.roundToInt()) }
                        .size(width = boxW, height = boxH)
                ) {
                    HudWidgetSlotWrapper(
                        widgetId = w.id,
                        editMode = editMode,
                        isSelected = w.id == selectedWidgetId,
                        isDragging = isDraggingThis,
                        onHold = { onHoldWidget(latestWidget) },
                        dragOverlayModifier = if (editMode) {
                            Modifier
                                .pointerInput(w.id) {
                                    detectTapGestures(onTap = { latestOnSelect(latestWidget) })
                                }
                                .pointerInput(w.id, gridSnap, cols, rows, sw, sh, maxXPx, maxYPx, cellWPx, cellHPx, gapPx) {
                                    val stepX = (cellWPx + gapPx).coerceAtLeast(1f)
                                    val stepY = (cellHPx + gapPx).coerceAtLeast(1f)
                                    detectDragGestures(
                                        onDragStart = {
                                            latestOnSelect(latestWidget)
                                            isDraggingThis = true
                                            latestOnDragging(true)
                                            if (gridSnap) {
                                                val initCol = (latestBaseX / stepX).roundToInt().coerceIn(0, (cols - sw).coerceAtLeast(0))
                                                val initRow = (latestBaseY / stepY).roundToInt().coerceIn(0, (rows - sh).coerceAtLeast(0))
                                                snapPreview = SnapDragPreview(w.id, initCol, initRow, sw, sh)
                                            }
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            val candidateX = (latestBaseX + dragDelta.x + dragAmount.x).coerceIn(0f, maxXPx)
                                            val candidateY = (latestBaseY + dragDelta.y + dragAmount.y).coerceIn(0f, maxYPx)
                                            dragDelta = Offset(candidateX - latestBaseX, candidateY - latestBaseY)
                                            if (gridSnap) {
                                                val targetCol = (candidateX / stepX).roundToInt().coerceIn(0, (cols - sw).coerceAtLeast(0))
                                                val targetRow = (candidateY / stepY).roundToInt().coerceIn(0, (rows - sh).coerceAtLeast(0))
                                                snapPreview = SnapDragPreview(w.id, targetCol, targetRow, sw, sh)
                                            }
                                        },
                                        onDragEnd = {
                                            val finalX = (latestBaseX + dragDelta.x).coerceIn(0f, maxXPx)
                                            val finalY = (latestBaseY + dragDelta.y).coerceIn(0f, maxYPx)
                                            if (gridSnap) {
                                                val targetCol = (finalX / stepX).roundToInt().coerceIn(0, (cols - sw).coerceAtLeast(0))
                                                val targetRow = (finalY / stepY).roundToInt().coerceIn(0, (rows - sh).coerceAtLeast(0))
                                                val snappedX = (stepX * targetCol).coerceIn(0f, maxXPx)
                                                val snappedY = (stepY * targetRow).coerceIn(0f, maxYPx)
                                                droppedOverride = Offset(snappedX, snappedY)
                                                latestOnCommitGrid(latestWidget, targetCol, targetRow)
                                            } else if (availWPx > 1f && availHPx > 1f) {
                                                droppedOverride = Offset(finalX, finalY)
                                                latestOnUpdateFreeform(
                                                    latestWidget,
                                                    finalX / availWPx,
                                                    finalY / availHPx,
                                                    maxXPx / availWPx,
                                                    maxYPx / availHPx
                                                )
                                            }
                                            dragDelta = Offset.Zero
                                            isDraggingThis = false
                                            snapPreview = null
                                            latestOnDragging(false)
                                        },
                                        onDragCancel = {
                                            dragDelta = Offset.Zero
                                            isDraggingThis = false
                                            snapPreview = null
                                            latestOnDragging(false)
                                        }
                                    )
                                }
                        } else Modifier
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
                            isRecordingPaused = isRecordingPaused,
                            sampleCount = extraFlags.third.second,
                            onToggleRecord = onToggleRecord,
                            onTogglePauseResume = onTogglePauseResume,
                            onToggleFuelPause = onToggleFuelPause,
                            onScanDtc = onScanDtc,
                            onReset0100 = onReset0100,
                            onEnableGps = onEnableGps
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HudWidgetSlotWrapper(
    widgetId: Long,
    editMode: Boolean,
    isSelected: Boolean,
    isDragging: Boolean,
    dragOverlayModifier: Modifier,
    onHold: () -> Unit,
    content: @Composable () -> Unit
) {
    val borderColor = when {
        isDragging -> TorqueEvoColors.ElectricBlue
        isSelected -> TorqueEvoColors.ElectricBlue
        else -> TorqueEvoColors.ElectricBlue.copy(alpha = 0.35f)
    }
    val holdModifier = rememberStationaryHoldModifier(widgetId, enabled = !editMode, onHold = onHold)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (editMode) {
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected || isDragging) Color(0x2200A8FF) else Color(0x0800A8FF))
                        .border(if (isSelected || isDragging) 2.dp else 1.dp, borderColor, RoundedCornerShape(12.dp))
                } else holdModifier
            )
    ) {
        content()
        if (editMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(dragOverlayModifier)
            )
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
    isRecordingPaused: Boolean = false,
    sampleCount: Int,
    onToggleRecord: () -> Unit,
    onTogglePauseResume: (() -> Unit)? = null,
    onToggleFuelPause: () -> Unit,
    onScanDtc: () -> Unit,
    onReset0100: () -> Unit,
    onEnableGps: (() -> Unit)? = null
) {
    val type = runCatching { WidgetDisplayType.valueOf(widget.displayType) }.getOrDefault(WidgetDisplayType.DIGITAL)

    when (widget.sensorKey) {
        "minimap" -> {
            val activeSettings by viewModel.settings.collectAsStateWithLifecycle()
            val tripState by viewModel.tripRunMetrics.collectAsStateWithLifecycle()
            val recLabel = if (isRecording) {
                val clock = TripRunCalculator.formatDurationClock(tripState.elapsedSec)
                val (dVal, dUnit) = viewModel.convertForDisplay(
                    tripState.distanceKm,
                    SensorQuantity.DISTANCE_KM,
                    "km"
                )
                val distTxt = dVal?.let { "%.1f %s".format(it, dUnit) } ?: "0.0 $dUnit"
                "${if (isRecordingPaused) "PAUSED" else "REC"} · $clock · $distTxt"
            } else null
            MiniMapWidget(
                gps = gps,
                mapProvider = mapProvider,
                activePmtilesPath = activeSettings.activePmtilesPath,
                mapStylePreset = activeSettings.mapStylePreset,
                mapCameraMode = activeSettings.mapCameraMode,
                mapCursorStyle = activeSettings.mapCursorStyle,
                highContrastRoads = activeSettings.highContrastMapRoads,
                isRecording = isRecording,
                isPaused = isRecordingPaused,
                recordingStatusLabel = recLabel,
                onToggleRecord = onToggleRecord,
                onTogglePauseResume = onTogglePauseResume,
                isGpsEnabled = viewModel.locationTracker.isGpsEnabled.collectAsStateWithLifecycle().value,
                onEnableGps = onEnableGps
            )
        }
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
                WidgetDisplayType.ANALOG_DIAL -> AnalogDialWidget(meta.shortLabel, dispVal, dispUnit, cMin, cMax, warnConv, critConv, sensorKey = widget.sensorKey)
                WidgetDisplayType.SEMI_DIGITAL -> SemiDigitalArcWidget(meta.shortLabel, dispVal, dispUnit, cMin, cMax, warnConv, critConv, sensorKey = widget.sensorKey)
                WidgetDisplayType.BAR -> BarGaugeWidget(meta.shortLabel, dispVal, dispUnit, cMin, cMax, warnConv, critConv, sensorKey = widget.sensorKey)
                WidgetDisplayType.SEGMENTED_BAR -> SegmentedBarGaugeWidget(meta.shortLabel, dispVal, dispUnit, cMin, cMax, warnConv, critConv, sensorKey = widget.sensorKey)
                WidgetDisplayType.CHART -> {
                    val hist = (historyBuffers[widget.sensorKey] ?: emptyList()).map {
                        viewModel.convertForDisplay(it, meta.quantity, meta.unit).first ?: it
                    }
                    SparklineChartWidget(meta.shortLabel, dispVal, dispUnit, cMin, cMax, hist, sensorKey = widget.sensorKey)
                }
                else -> DigitalReadoutWidget(meta.shortLabel, dispVal, dispUnit, cMin, cMax, warnConv, critConv, sensorKey = widget.sensorKey)
            }
        }
    }
}

@Composable
private fun EditActionPill(
    label: String,
    tint: Color,
    bg: Color = Color(0xFF151922),
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(bg)
            .border(1.dp, if (bg == TorqueEvoColors.ElectricBlue) TorqueEvoColors.ElectricBlue else Color(0x3DFFFFFF), RoundedCornerShape(7.dp))
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = tint, maxLines = 1)
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

private val DISPLAY_STYLE_OPTIONS = listOf(
    WidgetDisplayType.ANALOG_DIAL to "Radial",
    WidgetDisplayType.SEMI_DIGITAL to "Semi-Arc",
    WidgetDisplayType.DIGITAL to "Digital",
    WidgetDisplayType.BAR to "Line Bar",
    WidgetDisplayType.SEGMENTED_BAR to "Boxed Bar",
    WidgetDisplayType.CHART to "Chart"
)

@Composable
private fun VisualStyleChipsRow(
    selectedType: WidgetDisplayType,
    onSelect: (WidgetDisplayType) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        DISPLAY_STYLE_OPTIONS.forEach { (dt, lbl) ->
            ApexFilterChip(
                selected = selectedType == dt,
                onClick = { onSelect(dt) },
                label = lbl,
                compact = true
            )
        }
    }
}

@Composable
private fun DynamicTileSizeSelector(
    cols: Int,
    rows: Int,
    maxCols: Int,
    maxRows: Int,
    onSizeChange: (Int, Int) -> Unit
) {
    val safeCols = cols.coerceIn(1, maxCols)
    val safeRows = rows.coerceIn(1, maxRows)
    val latestOnChange by rememberUpdatedState(onSizeChange)
    var matrixSize by remember { mutableStateOf(IntSize.Zero) }

    fun updateFromPoint(offset: Offset) {
        if (matrixSize.width <= 0 || matrixSize.height <= 0) return
        val nextC = ((offset.x / matrixSize.width) * maxCols).toInt().coerceIn(0, maxCols - 1) + 1
        val nextR = ((offset.y / matrixSize.height) * maxRows).toInt().coerceIn(0, maxRows - 1) + 1
        latestOnChange(nextC, nextR)
    }

    val halfCols = (maxCols / 2).coerceAtLeast(1)
    val wideRows = (maxRows / 2).coerceAtLeast(1)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(TorqueEvoColors.PureBlack)
            .border(1.dp, TorqueEvoColors.SubtleBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TileAxisStepper(
                    label = "W",
                    value = safeCols,
                    max = maxCols,
                    onChange = { next -> latestOnChange(next, safeRows) }
                )
                TileAxisStepper(
                    label = "H",
                    value = safeRows,
                    max = maxRows,
                    onChange = { next -> latestOnChange(safeCols, next) }
                )
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                ApexFilterChip(
                    selected = safeCols == 1 && safeRows == 1,
                    onClick = { latestOnChange(1, 1) },
                    label = "1×1",
                    compact = true
                )
                ApexFilterChip(
                    selected = safeCols == halfCols && safeRows == maxRows,
                    onClick = { latestOnChange(halfCols, maxRows) },
                    label = "Half ($halfCols×$maxRows)",
                    compact = true
                )
                ApexFilterChip(
                    selected = safeCols == maxCols && safeRows == wideRows,
                    onClick = { latestOnChange(maxCols, wideRows) },
                    label = "Wide ($maxCols×$wideRows)",
                    compact = true
                )
                ApexFilterChip(
                    selected = safeCols == maxCols && safeRows == maxRows,
                    onClick = { latestOnChange(maxCols, maxRows) },
                    label = "Full",
                    compact = true
                )
            }
        }

        // Interactive tap/drag grid matrix up to full maxCols × maxRows
        Canvas(
            modifier = Modifier
                .width(116.dp)
                .height(60.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF0A0D13))
                .border(1.dp, TorqueEvoColors.SubtleBorder, RoundedCornerShape(6.dp))
                .onSizeChanged { matrixSize = it }
                .pointerInput(maxCols, maxRows) {
                    detectTapGestures { offset -> updateFromPoint(offset) }
                }
                .pointerInput(maxCols, maxRows) {
                    detectDragGestures(
                        onDragStart = { offset -> updateFromPoint(offset) },
                        onDrag = { change, _ ->
                            change.consume()
                            updateFromPoint(change.position)
                        }
                    )
                }
                .padding(3.dp)
        ) {
            val gapPx = 2.dp.toPx()
            val cw = (size.width - gapPx * (maxCols - 1)) / maxCols
            val ch = (size.height - gapPx * (maxRows - 1)) / maxRows
            val radius = CornerRadius(2.dp.toPx())
            for (r in 0 until maxRows) {
                for (c in 0 until maxCols) {
                    val active = c < safeCols && r < safeRows
                    drawRoundRect(
                        color = if (active) TorqueEvoColors.ElectricBlue.copy(alpha = 0.85f) else Color(0xFF1A2230),
                        topLeft = Offset(c * (cw + gapPx), r * (ch + gapPx)),
                        size = Size(cw.coerceAtLeast(1f), ch.coerceAtLeast(1f)),
                        cornerRadius = radius
                    )
                }
            }
        }
    }
}

@Composable
private fun TileAxisStepper(
    label: String,
    value: Int,
    max: Int,
    onChange: (Int) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TorqueEvoColors.TextSecondary)
        StepperBadge("−", enabled = value > 1) { onChange((value - 1).coerceAtLeast(1)) }
        Text(
            text = "$value",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = TorqueEvoColors.ElectricBlue,
            modifier = Modifier.width(18.dp),
            textAlign = TextAlign.Center
        )
        StepperBadge("+", enabled = value < max) { onChange((value + 1).coerceAtMost(max)) }
    }
}

@Composable
private fun StepperBadge(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .alpha(if (enabled) 1f else 0.35f)
            .clip(RoundedCornerShape(6.dp))
            .background(TorqueEvoColors.CardSurface)
            .border(1.dp, TorqueEvoColors.SubtleBorder, RoundedCornerShape(6.dp))
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(symbol, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TorqueEvoColors.TextPrimary)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComponentEditDialog(
    widget: HudWidgetEntity,
    widgetLabel: String,
    gridScale: HudGridScale,
    onSelectDisplayType: (WidgetDisplayType) -> Unit,
    onResize: (Int, Int) -> Unit,
    onEnterMoveMode: () -> Unit,
    onOpenCanvasMenu: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    val currentType = runCatching { WidgetDisplayType.valueOf(widget.displayType) }.getOrDefault(WidgetDisplayType.DIGITAL)
    val canStyle = !isFixedLayoutSpecialWidget(widget.sensorKey)
    val safeCols = widget.spanCols.coerceIn(1, gridScale.cols)
    val safeRows = widget.spanRows.coerceIn(1, gridScale.rows)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xA6000000))
            .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) }
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .heightIn(max = 355.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF0E1116))
                .border(1.dp, TorqueEvoColors.ElectricBlue.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                .pointerInput(Unit) { detectTapGestures { /* absorb card taps */ } }
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = widgetLabel,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TorqueEvoColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Text(
                        text = "${safeCols}×${safeRows}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TorqueEvoColors.ElectricBlue,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(TorqueEvoColors.DimBlue)
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
                Text(
                    "Done",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = TorqueEvoColors.ElectricBlue,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(TorqueEvoColors.CardSurface)
                        .border(1.dp, TorqueEvoColors.SubtleBorder, RoundedCornerShape(8.dp))
                        .clickable { onDismiss() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            if (canStyle) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Display Type", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = TorqueEvoColors.TextSecondary)
                    VisualStyleChipsRow(selectedType = currentType, onSelect = onSelectDisplayType)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Dynamic Tile Size (${gridScale.cols}×${gridScale.rows} Grid)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TorqueEvoColors.TextSecondary
                )
                DynamicTileSizeSelector(
                    cols = safeCols,
                    rows = safeRows,
                    maxCols = gridScale.cols,
                    maxRows = gridScale.rows,
                    onSizeChange = onResize
                )
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                EditActionPill("Drag & Drop", TorqueEvoColors.SkyBlue) { onEnterMoveMode() }
                EditActionPill("Canvas Menu", TorqueEvoColors.TextSecondary) { onOpenCanvasMenu() }
                EditActionPill("Remove Component", TorqueEvoColors.CriticalRed) { onRemove() }
            }
        }
    }
}

@Composable
private fun FullscreenComponentPickerDialog(
    viewModel: HudViewModel,
    gridScale: HudGridScale,
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
    onToggleFuelPause: () -> Unit,
    onScanDtc: () -> Unit,
    onReset0100: () -> Unit,
    onAddComponent: (sensorKey: String, displayType: WidgetDisplayType, spanCols: Int, spanRows: Int) -> Unit,
    onDismiss: () -> Unit
) {
    DisposableEffect(Unit) {
        viewModel.setPickerLivePreview(true)
        onDispose { viewModel.setPickerLivePreview(false) }
    }

    val supportedPidsState by viewModel.supportedPids.collectAsStateWithLifecycle()
    val activeVehicleState by viewModel.activeVehicle.collectAsStateWithLifecycle()
    val sensorOptions = remember(supportedPidsState, activeVehicleState?.supportedPidsCsv) {
        viewModel.pickerSensorsAlphabetical()
    }
    val initialItem = remember(sensorOptions) {
        sensorOptions.firstOrNull { it.available }?.item ?: sensorOptions.first().item
    }
    var selectedItem by remember { mutableStateOf(initialItem) }
    var selectedType by remember { mutableStateOf(WidgetDisplayType.DIGITAL) }
    var selectedCols by remember(gridScale) { mutableIntStateOf(2.coerceIn(1, gridScale.cols)) }
    var selectedRows by remember(gridScale) { mutableIntStateOf(1.coerceIn(1, gridScale.rows)) }

    val resolvedPreviewType = when (selectedItem.key) {
        "minimap" -> WidgetDisplayType.MINI_MAP
        "dtc_checker" -> WidgetDisplayType.DTC_CHECKER
        "timer_0100" -> WidgetDisplayType.PERF_TIMER
        "data_logger" -> WidgetDisplayType.DATA_LOGGER
        else -> selectedType
    }

    val previewWidget = remember(selectedItem.key, resolvedPreviewType, selectedCols, selectedRows) {
        HudWidgetEntity(
            id = -1L,
            pageId = -1L,
            sensorKey = selectedItem.key,
            displayType = resolvedPreviewType.name,
            spanCols = selectedCols,
            spanRows = selectedRows,
            sortOrder = 0
        )
    }

    val areaPct = ((selectedCols * selectedRows * 100f) / (gridScale.cols * gridScale.rows).toFloat()).roundToInt()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TorqueEvoColors.PureBlack)
            .pointerInput(Unit) { detectTapGestures { /* block background touch/hold */ } }
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(14.dp))
                .background(TorqueEvoColors.ElevatedDark)
                .border(1.dp, TorqueEvoColors.SubtleBorder, RoundedCornerShape(14.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Add Component", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TorqueEvoColors.TextPrimary)
                    Text(
                        "Grid ${gridScale.cols}×${gridScale.rows}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TorqueEvoColors.ElectricBlue,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(TorqueEvoColors.DimBlue)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Text(
                    "Close ×",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = TorqueEvoColors.TextSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(TorqueEvoColors.CardSurface)
                        .border(1.dp, TorqueEvoColors.SubtleBorder, RoundedCornerShape(8.dp))
                        .clickable { onDismiss() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            HorizontalDivider(color = TorqueEvoColors.SubtleBorder)

            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // LEFT PANE (~56%): 1) Alphabetical Sensors, 2) Visual Style, 3) Dynamic Tile Size
                Column(
                    modifier = Modifier.weight(0.56f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "1. Sensor (A–Z · Realtime Value)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TorqueEvoColors.TextSecondary
                    )
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 96.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(TorqueEvoColors.PureBlack)
                            .border(1.dp, TorqueEvoColors.SubtleBorder, RoundedCornerShape(10.dp))
                            .padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(sensorOptions, key = { it.item.key }) { opt ->
                            val item = opt.item
                            val isSelected = item.key == selectedItem.key
                            val enabled = opt.available
                            val rawLive = liveValues[item.key]
                            val (dispVal, dispUnit) = viewModel.convertForDisplay(rawLive, item.quantity, item.unit)

                            val valueStr = when {
                                !enabled -> "Unavailable"
                                item.isSpecialWidget -> "Widget"
                                item.quantity == SensorQuantity.DURATION_SEC ->
                                    TripRunCalculator.formatDurationClock((dispVal ?: 0.0).roundToLong())
                                dispVal != null -> {
                                    val numTxt = formatTelemetryValue(dispVal, item.minValue, item.maxValue, unit = dispUnit, sensorKey = item.key)
                                    if (dispUnit.isNotBlank()) "$numTxt $dispUnit" else numTxt
                                }
                                dispUnit.isNotBlank() -> "— $dispUnit"
                                else -> "Ready"
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .alpha(if (enabled) 1f else 0.36f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) Color(0x2600A8FF) else TorqueEvoColors.CardSurface)
                                    .border(
                                        1.dp,
                                        if (isSelected) TorqueEvoColors.ElectricBlue else TorqueEvoColors.SubtleBorder,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .then(
                                        if (enabled) {
                                            Modifier.clickable {
                                                selectedItem = item
                                                if (item.isSpecialWidget) {
                                                    when (item.key) {
                                                        "minimap" -> {
                                                            selectedCols = 2.coerceIn(1, gridScale.cols)
                                                            selectedRows = 2.coerceIn(1, gridScale.rows)
                                                        }
                                                        "dtc_checker" -> {
                                                            selectedCols = 4.coerceIn(1, gridScale.cols)
                                                            selectedRows = 1
                                                        }
                                                        else -> {
                                                            selectedCols = 2.coerceIn(1, gridScale.cols)
                                                            selectedRows = 1
                                                        }
                                                    }
                                                }
                                            }
                                        } else Modifier
                                    )
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = item.label,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = TorqueEvoColors.TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                                )
                                Text(
                                    text = valueStr,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (enabled) TorqueEvoColors.ElectricBlue else TorqueEvoColors.TextSecondary,
                                    style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    if (!selectedItem.isSpecialWidget) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("2. Component Visual", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = TorqueEvoColors.TextSecondary)
                            VisualStyleChipsRow(selectedType = selectedType, onSelect = { selectedType = it })
                        }
                    } else {
                        Text(
                            "2. Visual Style: Built-in Special Widget Layout",
                            fontSize = 11.sp,
                            color = TorqueEvoColors.TextSecondary
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "3. Dynamic Size (${gridScale.cols}×${gridScale.rows} Grid)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TorqueEvoColors.TextSecondary
                        )
                        DynamicTileSizeSelector(
                            cols = selectedCols,
                            rows = selectedRows,
                            maxCols = gridScale.cols,
                            maxRows = gridScale.rows,
                            onSizeChange = { c, r ->
                                selectedCols = c
                                selectedRows = r
                            }
                        )
                    }
                }

                // RIGHT PANE (~44%): Live Interactive Scaled Preview + Confirm Add
                Column(
                    modifier = Modifier.weight(0.44f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Live Preview", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = TorqueEvoColors.TextSecondary)
                        Text(
                            "${selectedCols}×${selectedRows} · $areaPct% canvas",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = TorqueEvoColors.ElectricBlue
                        )
                    }

                    val previewPalette = LocalHudVisualStyle.current
                    BoxWithConstraints(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .hudCanvasBackdrop(previewPalette)
                            .border(1.dp, previewPalette.accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val gridCellRatio = (16f / gridScale.cols.toFloat()) / (9f / gridScale.rows.toFloat())
                        val targetAspect = ((selectedCols.toFloat() / selectedRows.toFloat()) * gridCellRatio).coerceIn(0.32f, 4.5f)
                        val availW = maxWidth
                        val availH = maxHeight
                        val (boxW, boxH) = if (availW / availH > targetAspect) {
                            (availH * targetAspect) to availH
                        } else {
                            availW to (availW / targetAspect)
                        }
                        Box(modifier = Modifier.size(width = boxW, height = boxH)) {
                            RenderWidgetContent(
                                widget = previewWidget,
                                viewModel = viewModel,
                                liveValues = liveValues,
                                historyBuffers = historyBuffers,
                                gps = gps,
                                diagReport = diagReport,
                                timer0100 = timer0100,
                                mapProvider = mapProvider,
                                fuelPaused = fuelPaused,
                                isRecording = isRecording,
                                sampleCount = sampleCount,
                                onToggleRecord = onToggleRecord,
                                onToggleFuelPause = onToggleFuelPause,
                                onScanDtc = onScanDtc,
                                onReset0100 = onReset0100
                            )
                        }
                    }

                    Button(
                        onClick = {
                            onAddComponent(selectedItem.key, resolvedPreviewType, selectedCols, selectedRows)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = TorqueEvoColors.ElectricBlue, contentColor = Color.Black),
                        modifier = Modifier.fillMaxWidth().height(44.dp)
                    ) {
                        Text("+ Add to Canvas", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
