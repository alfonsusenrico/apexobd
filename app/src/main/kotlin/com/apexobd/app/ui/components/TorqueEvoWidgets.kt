package com.apexobd.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apexobd.app.BuildConfig
import com.apexobd.app.data.db.HudWidgetEntity
import com.apexobd.app.data.db.WidgetDisplayType
import com.apexobd.app.platform.GpsSnapshot
import com.apexobd.app.ui.SensorCatalogItem
import com.apexobd.app.ui.theme.TabularHeroStyle
import com.apexobd.app.ui.theme.TorqueEvoColors
import com.apexobd.core.protocol.DiagnosticReport
import com.apexobd.core.scheduler.Timer0100State
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun TorqueEvoWidgetCard(
    widget: HudWidgetEntity,
    catalogItem: SensorCatalogItem,
    rawValue: Double?,
    history: List<Double>,
    gps: GpsSnapshot,
    diagnosticReport: DiagnosticReport?,
    timer0100: Timer0100State,
    isRecording: Boolean,
    imperialUnits: Boolean,
    editMode: Boolean,
    onScanDtcs: () -> Unit,
    onClearDtcs: () -> Unit,
    onToggleRecord: () -> Unit,
    onReset0100: () -> Unit,
    onCycleType: () -> Unit,
    onCycleSize: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val displayType = runCatching { WidgetDisplayType.valueOf(widget.displayType) }
        .getOrDefault(WidgetDisplayType.DIGITAL)

    val (displayVal, unit) = convertDisplayUnit(rawValue, catalogItem.unit, imperialUnits)
    val valueText = formatSensorValue(displayVal, catalogItem.key)
    val accent = resolveThresholdColor(rawValue, widget.warnThreshold, widget.critThreshold)

    val editModifier = if (editMode) {
        modifier
            .border(1.dp, TorqueEvoColors.ElectricBlue.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
            .padding(4.dp)
    } else {
        modifier.padding(4.dp)
    }

    Box(modifier = editModifier) {
        when (displayType) {
            WidgetDisplayType.MINI_MAP -> MiniMapWidget(gps = gps, accent = TorqueEvoColors.ElectricBlue)
            WidgetDisplayType.DTC_CHECKER -> DtcCheckerWidget(
                report = diagnosticReport,
                onScan = onScanDtcs,
                onClear = onClearDtcs
            )
            WidgetDisplayType.PERF_TIMER -> PerformanceTimerWidget(
                state = timer0100,
                onReset = onReset0100
            )
            WidgetDisplayType.DATA_LOGGER -> DataLoggerWidget(
                isRecording = isRecording,
                onToggle = onToggleRecord
            )
            WidgetDisplayType.ANALOG_DIAL -> AnalogDialWidget(
                label = catalogItem.shortLabel,
                valueText = valueText,
                unit = unit,
                progress = normalize(rawValue, catalogItem.minValue, catalogItem.maxValue),
                accent = accent
            )
            WidgetDisplayType.SEMI_DIGITAL -> SemiDigitalArcWidget(
                label = catalogItem.shortLabel,
                valueText = valueText,
                unit = unit,
                progress = normalize(rawValue, catalogItem.minValue, catalogItem.maxValue),
                accent = accent
            )
            WidgetDisplayType.BAR -> BarGaugeWidget(
                label = catalogItem.shortLabel,
                valueText = valueText,
                unit = unit,
                progress = normalize(rawValue, catalogItem.minValue, catalogItem.maxValue),
                accent = accent
            )
            WidgetDisplayType.CHART -> SparklineChartWidget(
                label = catalogItem.shortLabel,
                valueText = valueText,
                unit = unit,
                history = history,
                minVal = catalogItem.minValue,
                maxVal = catalogItem.maxValue,
                accent = accent
            )
            WidgetDisplayType.DIGITAL -> DigitalReadoutWidget(
                label = catalogItem.shortLabel,
                valueText = valueText,
                unit = unit,
                accent = accent
            )
        }

        if (editMode) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .background(Color(0xCC060A12), RoundedCornerShape(8.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                MiniEditChip("◀", onClick = onMoveLeft)
                MiniEditChip("▶", onClick = onMoveRight)
                MiniEditChip("◐", onClick = onCycleType)
                MiniEditChip("⤢", onClick = onCycleSize)
                MiniEditChip("×", color = TorqueEvoColors.CriticalRed, onClick = onDelete)
            }
        }
    }
}

@Composable
private fun MiniEditChip(text: String, color: Color = TorqueEvoColors.TextPrimary, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF101826))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun AnalogDialWidget(
    label: String,
    valueText: String,
    unit: String,
    progress: Float,
    accent: Color
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = label.uppercase(),
            color = accent,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Box(modifier = Modifier.size(120.dp), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                val strokeW = 8.dp.toPx()
                val arcSize = Size(size.width, size.height)
                drawArc(
                    color = accent.copy(alpha = 0.16f),
                    startAngle = 145f,
                    sweepAngle = 250f,
                    useCenter = false,
                    topLeft = Offset.Zero,
                    size = arcSize,
                    style = Stroke(width = strokeW, cap = StrokeCap.Round)
                )
                drawArc(
                    color = accent,
                    startAngle = 145f,
                    sweepAngle = 250f * progress,
                    useCenter = false,
                    topLeft = Offset.Zero,
                    size = arcSize,
                    style = Stroke(width = strokeW, cap = StrokeCap.Round)
                )
                val center = Offset(size.width / 2f, size.height / 2f)
                val angleRad = (145f + 250f * progress) * (PI / 180.0)
                val needleLen = size.minDimension / 2f - 8.dp.toPx()
                val tip = Offset(
                    x = center.x + (cos(angleRad) * needleLen).toFloat(),
                    y = center.y + (sin(angleRad) * needleLen).toFloat()
                )
                drawLine(
                    color = Color.White,
                    start = center,
                    end = tip,
                    strokeWidth = 2.6.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawCircle(color = accent, radius = 4.dp.toPx(), center = center)
            }
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = valueText, style = TabularHeroStyle.copy(fontSize = 20.sp))
                if (unit.isNotEmpty()) {
                    Text(text = unit, color = TorqueEvoColors.TextSecondary, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
fun SemiDigitalArcWidget(
    label: String,
    valueText: String,
    unit: String,
    progress: Float,
    accent: Color
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(label.uppercase(), color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Box(modifier = Modifier.size(124.dp, 86.dp), contentAlignment = Alignment.BottomCenter) {
            Canvas(modifier = Modifier.fillMaxSize().padding(6.dp)) {
                val stroke = 9.dp.toPx()
                val arcSize = Size(size.width, size.height * 1.45f)
                drawArc(
                    color = Color.White.copy(alpha = 0.12f),
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset.Zero,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
                drawArc(
                    color = accent,
                    startAngle = 180f,
                    sweepAngle = 180f * progress,
                    useCenter = false,
                    topLeft = Offset.Zero,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = valueText, style = TabularHeroStyle.copy(fontSize = 26.sp))
                if (unit.isNotEmpty()) {
                    Text(text = unit, color = TorqueEvoColors.TextSecondary, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun DigitalReadoutWidget(
    label: String,
    valueText: String,
    unit: String,
    accent: Color
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = label.uppercase(),
            color = accent,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(text = valueText, style = TabularHeroStyle.copy(fontSize = 23.sp))
            if (unit.isNotEmpty()) {
                Spacer(Modifier.size(3.dp))
                Text(
                    text = unit,
                    color = TorqueEvoColors.TextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 3.dp)
                )
            }
        }
    }
}

@Composable
fun BarGaugeWidget(
    label: String,
    valueText: String,
    unit: String,
    progress: Float,
    accent: Color
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(label.uppercase(), color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(
                text = "$valueText $unit".trim(),
                style = TabularHeroStyle.copy(fontSize = 16.sp)
            )
        }
        Spacer(Modifier.height(6.dp))
        Canvas(modifier = Modifier.fillMaxWidth().height(7.dp)) {
            drawRoundRect(
                color = Color.White.copy(alpha = 0.12f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(99f, 99f)
            )
            drawRoundRect(
                color = accent,
                size = Size(size.width * progress, size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(99f, 99f)
            )
        }
    }
}

@Composable
fun SparklineChartWidget(
    label: String,
    valueText: String,
    unit: String,
    history: List<Double>,
    minVal: Double,
    maxVal: Double,
    accent: Color
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "${label.uppercase()} • $valueText $unit".trim(),
            color = accent,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        Spacer(Modifier.height(4.dp))
        Canvas(modifier = Modifier.fillMaxWidth().height(34.dp)) {
            if (history.size < 2) return@Canvas
            val range = (maxVal - minVal).takeIf { it > 0.001 } ?: 1.0
            val path = Path()
            history.forEachIndexed { idx, v ->
                val x = (idx.toFloat() / (history.size - 1)) * size.width
                val norm = ((v - minVal) / range).toFloat().coerceIn(0f, 1f)
                val y = size.height - (norm * size.height)
                if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path = path, color = accent, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

@Composable
fun MiniMapWidget(gps: GpsSnapshot, accent: Color) {
    val hasMapsKey = BuildConfig.MAPS_API_KEY.isNotBlank()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF07111E))
    ) {
        if (hasMapsKey) {
            val pos = LatLng(gps.latitude, gps.longitude)
            val cameraState = rememberCameraPositionState {
                position = CameraPosition.fromLatLngZoom(pos, 15.5f)
            }
            val mapStyle = remember { MapStyleOptions(DARK_AUTOMOTIVE_MAP_STYLE) }
            val uiSettings = remember {
                MapUiSettings(
                    zoomControlsEnabled = false,
                    compassEnabled = false,
                    myLocationButtonEnabled = false,
                    mapToolbarEnabled = false
                )
            }
            LaunchedEffect(gps.latitude, gps.longitude) {
                cameraState.animate(CameraUpdateFactory.newLatLng(pos), durationMs = 350)
            }
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraState,
                properties = MapProperties(mapStyleOptions = mapStyle),
                uiSettings = uiSettings
            ) {
                if (gps.trail.size >= 2) {
                    Polyline(
                        points = gps.trail.map { LatLng(it.first, it.second) },
                        color = accent,
                        width = 8f
                    )
                }
            }
        } else {
            // Keyless dark GPS trail vector fallback when MAPS_API_KEY is not yet configured
            Canvas(modifier = Modifier.fillMaxSize()) {
                val trail = gps.trail
                if (trail.size >= 2) {
                    val lats = trail.map { it.first }
                    val lons = trail.map { it.second }
                    val minLat = lats.minOrNull() ?: 0.0
                    val maxLat = (lats.maxOrNull() ?: 1.0).let { if (it == minLat) it + 0.001 else it }
                    val minLon = lons.minOrNull() ?: 0.0
                    val maxLon = (lons.maxOrNull() ?: 1.0).let { if (it == minLon) it + 0.001 else it }

                    val path = Path()
                    var lastPt = Offset(size.width / 2f, size.height / 2f)
                    trail.forEachIndexed { idx, (lat, lon) ->
                        val x = (((lon - minLon) / (maxLon - minLon)).toFloat() * 0.7f + 0.15f) * size.width
                        val y = (1f - (((lat - minLat) / (maxLat - minLat)).toFloat() * 0.7f + 0.15f)) * size.height
                        lastPt = Offset(x, y)
                        if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, color = accent, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
                    drawCircle(color = accent.copy(alpha = 0.25f), radius = 12.dp.toPx(), center = lastPt)
                    drawCircle(color = accent, radius = 5.dp.toPx(), center = lastPt)
                } else {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    drawCircle(color = accent.copy(alpha = 0.25f), radius = 14.dp.toPx(), center = center)
                    drawCircle(color = accent, radius = 5.dp.toPx(), center = center)
                }
            }
        }

        Text(
            text = if (gps.hasRealFix) "Google Mini-Map • GPS" else "Mini-Map • Sim GPS",
            color = TorqueEvoColors.SkyBlue,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .background(Color(0x99000000), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun DtcCheckerWidget(
    report: DiagnosticReport?,
    onScan: () -> Unit,
    onClear: () -> Unit
) {
    var confirmClear by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TorqueEvoColors.ElevatedDark, RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "ECU FAULT CHECKER (03 / 07 / 0A)",
                color = TorqueEvoColors.ElectricBlue,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onScan) { Text("Scan", color = TorqueEvoColors.SkyBlue, fontSize = 11.sp) }
                TextButton(onClick = { confirmClear = true }) {
                    Text("Clear (04)", color = TorqueEvoColors.CriticalRed, fontSize = 11.sp)
                }
            }
        }

        if (report == null) {
            Text("Tap Scan to query Confirmed, Pending & Permanent DTCs.", color = TorqueEvoColors.TextSecondary, fontSize = 12.sp)
        } else {
            val all = report.confirmed + report.pending + report.permanent
            if (all.isEmpty()) {
                Text("✓ 0 Fault Codes • MIL OFF", color = TorqueEvoColors.NominalGreen, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    all.take(2).forEach { dtc ->
                        Text(
                            text = "${dtc.code} (${dtc.category.name}) — ${dtc.description}",
                            color = TorqueEvoColors.TextPrimary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            val readyCount = report.readiness?.monitors?.count { it.ready } ?: 0
            val totalMon = report.readiness?.monitors?.size ?: 0
            Text(
                text = "Readiness: $readyCount/$totalMon Ready",
                color = TorqueEvoColors.TextSecondary,
                fontSize = 11.sp
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear DTCs (Mode 04)?") },
            text = { Text("This erases confirmed/pending fault codes, freeze frame data, and resets emissions readiness monitors. Use with ignition ON, engine OFF.") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmClear = false
                        onClear()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TorqueEvoColors.CriticalRed)
                ) { Text("Confirm Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun PerformanceTimerWidget(state: Timer0100State, onReset: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(TorqueEvoColors.ElevatedDark, RoundedCornerShape(16.dp))
            .clickable(onClick = onReset)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("0–100 KM/H", color = TorqueEvoColors.ElectricBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            val valStr = when {
                state.running -> "%.2f s".format(state.currentSec)
                state.bestRunSec != null -> "%.2f s".format(state.bestRunSec)
                else -> "--.-- s"
            }
            Text(valStr, style = TabularHeroStyle.copy(fontSize = 22.sp))
        }
        val statusText = when {
            state.running -> "RUNNING"
            state.armed -> "ARMED"
            else -> "TAP RESET"
        }
        Text(
            text = statusText,
            color = if (state.running) TorqueEvoColors.WarnAmber else TorqueEvoColors.NominalGreen,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun DataLoggerWidget(isRecording: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(TorqueEvoColors.ElevatedDark, RoundedCornerShape(16.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("DATA LOGGER", color = TorqueEvoColors.ElectricBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(
                text = if (isRecording) "● Recording GPS+OBD" else "Tap to Record",
                color = if (isRecording) TorqueEvoColors.CriticalRed else TorqueEvoColors.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(if (isRecording) TorqueEvoColors.CriticalRed else TorqueEvoColors.TextSecondary)
        )
    }
}

private fun normalize(value: Double?, min: Double, max: Double): Float {
    if (value == null || max <= min) return 0f
    return ((value - min) / (max - min)).toFloat().coerceIn(0f, 1f)
}

private fun convertDisplayUnit(raw: Double?, unit: String, imperial: Boolean): Pair<Double?, String> {
    if (raw == null || !imperial) return raw to unit
    return when (unit) {
        "km/h" -> (raw * 0.621371) to "mph"
        "°C" -> ((raw * 9.0 / 5.0) + 32.0) to "°F"
        "bar" -> (raw * 14.5038) to "psi"
        "kPa" -> (raw * 0.145038) to "psi"
        else -> raw to unit
    }
}

private fun formatSensorValue(value: Double?, key: String): String {
    if (value == null) return "--"
    return when (key) {
        "rpm", "obd_speed", "gps_speed", "hp", "torque" -> "%.0f".format(value)
        "voltage" -> "%.1f".format(value)
        "maf", "timing_advance", "boost_bar", "stft_b1", "ltft_b1" -> "%.1f".format(value)
        else -> "%.0f".format(value)
    }
}

private fun resolveThresholdColor(value: Double?, warn: Double?, crit: Double?): Color {
    if (value == null) return TorqueEvoColors.ElectricBlue
    if (crit != null && ((warn == null || crit >= warn) && value >= crit || (warn != null && crit < warn && value <= crit))) {
        return TorqueEvoColors.CriticalRed
    }
    if (warn != null && ((crit == null || warn <= crit) && value >= warn || (crit != null && warn > crit && value <= warn))) {
        return TorqueEvoColors.WarnAmber
    }
    return TorqueEvoColors.ElectricBlue
}

private const val DARK_AUTOMOTIVE_MAP_STYLE = """
[
  {"elementType":"geometry","stylers":[{"color":"#0b1220"}]},
  {"elementType":"labels.text.fill","stylers":[{"color":"#7c8da6"}]},
  {"elementType":"labels.text.stroke","stylers":[{"color":"#0b1220"}]},
  {"featureType":"road","elementType":"geometry","stylers":[{"color":"#1b283f"}]},
  {"featureType":"road.highway","elementType":"geometry","stylers":[{"color":"#243b5e"}]},
  {"featureType":"water","elementType":"geometry","stylers":[{"color":"#050a14"}]}
]
"""
