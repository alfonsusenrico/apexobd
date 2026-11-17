package com.apexobd.app.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apexobd.app.data.prefs.MapProvider
import com.apexobd.app.platform.GpsSnapshot
import com.apexobd.app.ui.theme.TorqueEvoColors
import com.apexobd.core.protocol.DiagnosticReport
import com.apexobd.core.scheduler.Timer0100State
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

fun formatTelemetryValue(value: Double?, min: Double, max: Double): String {
    if (value == null) return "—"
    return when {
        max - min <= 5.0 -> "%.2f".format(value)
        max <= 30.0 -> "%.1f".format(value)
        value >= 100.0 || value == value.roundToInt().toDouble() -> "${value.roundToInt()}"
        else -> "%.1f".format(value)
    }
}

private fun thresholdColor(
    value: Double?,
    warnThreshold: Double?,
    critThreshold: Double?,
    pct: Float
): Color {
    if (value != null && critThreshold != null) {
        val crit = if (critThreshold < 12.5) value <= critThreshold else value >= critThreshold
        if (crit) return TorqueEvoColors.CriticalRed
    }
    if (value != null && warnThreshold != null) {
        val warn = if (warnThreshold < 12.5) value <= warnThreshold else value >= warnThreshold
        if (warn) return TorqueEvoColors.WarnAmber
    }
    return when {
        pct > 0.85f -> TorqueEvoColors.WarnAmber
        else -> TorqueEvoColors.ElectricBlue
    }
}

@Composable
fun AnalogDialWidget(
    shortLabel: String,
    value: Double?,
    unit: String,
    min: Double,
    max: Double,
    warnThreshold: Double?,
    critThreshold: Double?,
    modifier: Modifier = Modifier
) {
    val span = (max - min).coerceAtLeast(1.0)
    val rawPct = value?.let { ((it - min) / span).toFloat().coerceIn(0f, 1f) } ?: 0f
    val pct by animateFloatAsState(
        targetValue = rawPct,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.8f),
        label = "radialPct"
    )
    val accent = thresholdColor(value, warnThreshold, critThreshold, pct)

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize().padding(4.dp)) {
            val d = minOf(size.width, size.height)
            val r = d * 0.43f
            val c = Offset(size.width / 2f, size.height * 0.50f)
            val arcTopLeft = Offset(c.x - r, c.y - r)
            val arcSize = Size(r * 2f, r * 2f)

            // Graduated outer ticks
            for (i in 0..24) {
                val deg = 145.0 + (i / 24.0) * 250.0
                val rad = deg * PI / 180.0
                val innerR = if (i % 4 == 0) r * 0.90f else r * 0.95f
                val outerR = r * 1.04f
                val tickCol = if (i >= 20) Color(0x66EF4444) else Color(0x28FFFFFF)
                drawLine(
                    color = tickCol,
                    start = Offset(c.x + (innerR * cos(rad)).toFloat(), c.y + (innerR * sin(rad)).toFloat()),
                    end = Offset(c.x + (outerR * cos(rad)).toFloat(), c.y + (outerR * sin(rad)).toFloat()),
                    strokeWidth = if (i % 4 == 0) 2.2f else 1.2f,
                    cap = StrokeCap.Round
                )
            }

            // Base track + redline zone
            drawArc(
                color = Color(0x18FFFFFF),
                startAngle = 145f,
                sweepAngle = 250f,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = 7f, cap = StrokeCap.Round)
            )
            drawArc(
                color = Color(0x45EF4444),
                startAngle = 350f,
                sweepAngle = 45f,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = 7f, cap = StrokeCap.Round)
            )
            if (pct > 0f) {
                drawArc(
                    color = accent,
                    startAngle = 145f,
                    sweepAngle = 250f * pct,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = 7.5f, cap = StrokeCap.Round)
                )
            }

            // Crisp tapered needle with dark hub center
            val needleRad = (145.0 + 250.0 * pct) * PI / 180.0
            val tip = Offset(c.x + (r * 0.80f * cos(needleRad)).toFloat(), c.y + (r * 0.80f * sin(needleRad)).toFloat())
            val tail = Offset(c.x - (r * 0.14f * cos(needleRad)).toFloat(), c.y - (r * 0.14f * sin(needleRad)).toFloat())
            drawLine(color = Color.White, start = tail, end = tip, strokeWidth = 4f, cap = StrokeCap.Round)
            drawCircle(color = Color(0xFF090D14), radius = r * 0.13f, center = c)
            drawCircle(color = accent, radius = r * 0.13f, center = c, style = Stroke(width = 2.5f))
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = shortLabel.uppercase(),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = TorqueEvoColors.TextSecondary,
                maxLines = 1,
                modifier = Modifier.padding(top = 26.dp)
            )
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.padding(bottom = 2.dp)
            ) {
                Text(
                    text = formatTelemetryValue(value, min, max),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TorqueEvoColors.TextPrimary,
                    style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")
                )
                if (unit.isNotBlank()) {
                    Text(
                        text = unit,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TorqueEvoColors.TextSecondary,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun SemiDigitalArcWidget(
    shortLabel: String,
    value: Double?,
    unit: String,
    min: Double,
    max: Double,
    warnThreshold: Double?,
    critThreshold: Double?,
    modifier: Modifier = Modifier
) {
    val span = (max - min).coerceAtLeast(1.0)
    val rawPct = value?.let { ((it - min) / span).toFloat().coerceIn(0f, 1f) } ?: 0f
    val pct by animateFloatAsState(
        targetValue = rawPct,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.85f),
        label = "semiPct"
    )
    val accent = thresholdColor(value, warnThreshold, critThreshold, pct)

    Box(modifier = modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp)) {
        Text(
            text = shortLabel.uppercase(),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.9.sp,
            color = TorqueEvoColors.TextSecondary,
            maxLines = 1,
            modifier = Modifier.align(Alignment.TopCenter)
        )
        Canvas(modifier = Modifier.fillMaxSize().padding(top = 12.dp, bottom = 10.dp)) {
            val d = minOf(size.width * 0.88f, size.height * 1.25f)
            val r = d / 2f
            val cx = size.width / 2f
            val cy = size.height * 0.72f
            val topLeft = Offset(cx - r, cy - r)
            val arcSize = Size(r * 2f, r * 2f)

            drawArc(
                color = Color(0x18FFFFFF),
                startAngle = 175f,
                sweepAngle = 190f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = 9f, cap = StrokeCap.Round)
            )
            if (pct > 0f) {
                drawArc(
                    color = accent,
                    startAngle = 175f,
                    sweepAngle = 190f * pct,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = 9f, cap = StrokeCap.Round)
                )
            }
        }
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp)
        ) {
            Text(
                text = formatTelemetryValue(value, min, max),
                fontSize = 23.sp,
                fontWeight = FontWeight.Bold,
                color = TorqueEvoColors.TextPrimary,
                style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                maxLines = 1
            )
            if (unit.isNotBlank()) {
                Text(
                    text = unit,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TorqueEvoColors.TextSecondary,
                    modifier = Modifier.padding(bottom = 3.dp),
                    maxLines = 1
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("${min.roundToInt()}", fontSize = 8.sp, color = Color(0xFF5B6474))
            Text("${max.roundToInt()}", fontSize = 8.sp, color = Color(0xFF5B6474))
        }
    }
}

@Composable
fun DigitalReadoutWidget(
    shortLabel: String,
    value: Double?,
    unit: String,
    min: Double,
    max: Double,
    warnThreshold: Double?,
    critThreshold: Double?,
    modifier: Modifier = Modifier
) {
    val span = (max - min).coerceAtLeast(1.0)
    val rawPct = value?.let { ((it - min) / span).toFloat().coerceIn(0f, 1f) } ?: 0f
    val pct by animateFloatAsState(targetValue = rawPct, label = "digPct")
    val accent = thresholdColor(value, warnThreshold, critThreshold, pct)

    Column(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(9.dp))
            .background(Color(0x0AFFFFFF))
            .border(1.dp, Color(0x16FFFFFF), RoundedCornerShape(9.dp))
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = shortLabel.uppercase(),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
                color = TorqueEvoColors.TextSecondary,
                maxLines = 1
            )
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
        }

        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = formatTelemetryValue(value, min, max),
                fontSize = 23.sp,
                fontWeight = FontWeight.Bold,
                color = if (accent == TorqueEvoColors.CriticalRed) TorqueEvoColors.CriticalRed else TorqueEvoColors.TextPrimary,
                style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                maxLines = 1
            )
            if (unit.isNotBlank()) {
                Text(
                    text = unit,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TorqueEvoColors.TextSecondary,
                    modifier = Modifier.padding(bottom = 2.dp),
                    maxLines = 1
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0x16FFFFFF))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(pct.coerceAtLeast(0.03f))
                    .fillMaxHeight()
                    .background(accent)
            )
        }
    }
}

@Composable
fun BarGaugeWidget(
    shortLabel: String,
    value: Double?,
    unit: String,
    min: Double,
    max: Double,
    warnThreshold: Double?,
    critThreshold: Double?,
    modifier: Modifier = Modifier
) {
    val span = (max - min).coerceAtLeast(1.0)
    val rawPct = value?.let { ((it - min) / span).toFloat().coerceIn(0f, 1f) } ?: 0f
    val pct by animateFloatAsState(targetValue = rawPct, label = "barPct")
    val accent = thresholdColor(value, warnThreshold, critThreshold, pct)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x09FFFFFF))
    ) {
        val compact = maxWidth < 145.dp
        Row(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxHeight().size(width = 2.5.dp, height = 100.dp).background(accent))
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = if (compact) 8.dp else 10.dp, vertical = 6.dp),
                verticalArrangement = if (compact) Arrangement.SpaceBetween else Arrangement.Center
            ) {
                if (compact) {
                    Text(
                        text = shortLabel.uppercase(),
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        color = TorqueEvoColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = formatTelemetryValue(value, min, max),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = TorqueEvoColors.TextPrimary,
                            style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                            maxLines = 1
                        )
                        if (unit.isNotBlank()) {
                            Text(unit, fontSize = 9.sp, color = TorqueEvoColors.TextSecondary, modifier = Modifier.padding(bottom = 2.dp), maxLines = 1)
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Text(
                            text = shortLabel.uppercase(),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.6.sp,
                            color = TorqueEvoColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(end = 6.dp)
                        )
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = formatTelemetryValue(value, min, max),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = TorqueEvoColors.TextPrimary,
                                style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                                maxLines = 1
                            )
                            if (unit.isNotBlank()) {
                                Text(unit, fontSize = 10.sp, color = TorqueEvoColors.TextSecondary, modifier = Modifier.padding(bottom = 2.dp), maxLines = 1)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0x18FFFFFF))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(pct)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(accent.copy(alpha = 0.7f), accent)
                                )
                            )
                    )
                }
            }
        }
    }
}

@Composable
fun SparklineChartWidget(
    shortLabel: String,
    value: Double?,
    unit: String,
    min: Double,
    max: Double,
    history: List<Double>,
    modifier: Modifier = Modifier
) {
    val span = (max - min).coerceAtLeast(1.0)
    Column(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x08FFFFFF))
            .border(1.dp, Color(0x16FFFFFF), RoundedCornerShape(10.dp))
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = shortLabel.uppercase(),
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
                color = TorqueEvoColors.TextSecondary
            )
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = formatTelemetryValue(value, min, max),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TorqueEvoColors.TextPrimary,
                    style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")
                )
                if (unit.isNotBlank()) {
                    Text(unit, fontSize = 9.5.sp, color = TorqueEvoColors.TextSecondary, modifier = Modifier.padding(bottom = 1.dp))
                }
            }
        }

        Canvas(modifier = Modifier.fillMaxSize().padding(top = 5.dp)) {
            drawLine(
                color = Color(0x14FFFFFF),
                start = Offset(0f, size.height * 0.5f),
                end = Offset(size.width, size.height * 0.5f),
                strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
            )
            val pts = if (history.size >= 2) history else listOf(min, value ?: min)
            val linePath = Path()
            val fillPath = Path()
            var lastPt = Offset(0f, size.height)

            pts.forEachIndexed { idx, v ->
                val x = (idx.toFloat() / (pts.size - 1).coerceAtLeast(1)) * size.width
                val p = ((v - min) / span).toFloat().coerceIn(0f, 1f)
                val y = size.height - (p * (size.height - 8f)) - 4f
                val offset = Offset(x, y)
                lastPt = offset
                if (idx == 0) {
                    linePath.moveTo(x, y)
                    fillPath.moveTo(x, size.height)
                    fillPath.lineTo(x, y)
                } else {
                    linePath.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }
            fillPath.lineTo(lastPt.x, size.height)
            fillPath.close()

            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    listOf(TorqueEvoColors.ElectricBlue.copy(alpha = 0.32f), Color.Transparent)
                )
            )
            drawPath(
                path = linePath,
                color = TorqueEvoColors.ElectricBlue,
                style = Stroke(width = 3f, cap = StrokeCap.Round)
            )
            drawCircle(color = Color.White, radius = 4.5f, center = lastPt)
            drawCircle(color = TorqueEvoColors.ElectricBlue, radius = 4.5f, center = lastPt, style = Stroke(2.2f))
        }
    }
}

@Composable
fun MiniMapWidget(
    gps: GpsSnapshot,
    mapProvider: MapProvider,
    activePmtilesPath: String? = null,
    isRecording: Boolean,
    onToggleRecord: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(TorqueEvoColors.MapFallbackBg)
            .border(1.dp, Color(0x1EFFFFFF), RoundedCornerShape(12.dp))
    ) {
        DualMapCanvas(
            mapProvider = mapProvider,
            latitude = gps.latitude,
            longitude = gps.longitude,
            trail = if (isRecording) gps.trail else emptyList(),
            activePmtilesPath = activePmtilesPath,
            modifier = Modifier.fillMaxSize()
        )
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Color(0xBF000000))
                .padding(horizontal = 7.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isRecording) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFEF4444))
                )
            }
            Text(
                text = if (isRecording) "Recording Run" else "Live GPS",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = TorqueEvoColors.TextPrimary
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
                .size(38.dp)
                .clip(CircleShape)
                .background(if (isRecording) Color(0xE0180909) else Color(0xD90A0D14))
                .border(
                    width = 2.dp,
                    color = if (isRecording) Color(0xFFEF4444) else Color(0xE6FFFFFF),
                    shape = CircleShape
                )
                .semantics {
                    contentDescription = if (isRecording) "Stop Recording" else "Record Run"
                }
                .clickable(role = Role.Button, onClick = onToggleRecord),
            contentAlignment = Alignment.Center
        ) {
            if (isRecording) {
                Box(
                    modifier = Modifier
                        .size(13.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xFFEF4444))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFEF4444))
                )
            }
        }
    }
}

@Composable
fun FuelControlWidget(
    kplValue: Double?,
    kplUnit: String,
    estRangeValue: Double?,
    estRangeUnit: String,
    paused: Boolean,
    onTogglePause: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x09FFFFFF))
            .border(1.dp, Color(0x1EFFFFFF), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            val dteText = estRangeValue?.let { "${it.roundToInt()} $estRangeUnit DTE" } ?: "ALL TIME AVG"
            Text(
                text = "KPL · $dteText",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                color = TorqueEvoColors.TextSecondary
            )
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = formatTelemetryValue(kplValue, 0.0, 30.0),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TorqueEvoColors.TextPrimary,
                    style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")
                )
                Text(kplUnit, fontSize = 10.sp, color = TorqueEvoColors.TextSecondary, modifier = Modifier.padding(bottom = 2.dp))
            }
        }
        val btnCol = if (paused) TorqueEvoColors.WarnAmber else TorqueEvoColors.ElectricBlue
        Text(
            text = if (paused) "Paused" else "Pause Idle",
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = btnCol,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, btnCol, RoundedCornerShape(6.dp))
                .clickable { onTogglePause() }
                .padding(horizontal = 8.dp, vertical = 5.dp)
        )
    }
}

@Composable
fun DtcCheckerWidget(
    report: DiagnosticReport?,
    onScan: () -> Unit,
    modifier: Modifier = Modifier
) {
    val count = (report?.confirmed?.size ?: 0) + (report?.pending?.size ?: 0)
    Row(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x09FFFFFF))
            .border(1.dp, Color(0x1EFFFFFF), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = "FAULT CHECK (03/07)",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                color = TorqueEvoColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (report == null) "Tap Scan" else if (count == 0) "0 Faults · ECU Ready" else "$count Fault(s) Found",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = when {
                    report == null -> TorqueEvoColors.TextPrimary
                    count == 0 -> TorqueEvoColors.NominalGreen
                    else -> TorqueEvoColors.CriticalRed
                }
            )
        }
        Text(
            text = "Scan",
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = TorqueEvoColors.TextPrimary,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, Color(0x28FFFFFF), RoundedCornerShape(6.dp))
                .clickable { onScan() }
                .padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
fun PerfTimerWidget(
    timer: Timer0100State,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    val showSecondsSuffix = timer.running || timer.bestRunSec != null
    Row(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x09FFFFFF))
            .border(1.dp, Color(0x1EFFFFFF), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = "0–100 KM/H SPRINT",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = TorqueEvoColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = if (timer.running) "%.2f".format(timer.currentSec) else timer.bestRunSec?.let { "%.2f".format(it) } ?: "ARMED",
                    fontSize = if (showSecondsSuffix) 20.sp else 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (timer.running) TorqueEvoColors.ElectricBlue else TorqueEvoColors.TextPrimary,
                    style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                    maxLines = 1
                )
                if (showSecondsSuffix) {
                    Text("s", fontSize = 10.sp, color = TorqueEvoColors.TextSecondary, modifier = Modifier.padding(bottom = 2.dp))
                }
            }
        }
        Text(
            text = "Reset",
            fontSize = 10.sp,
            color = TorqueEvoColors.TextSecondary,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, Color(0x28FFFFFF), RoundedCornerShape(6.dp))
                .clickable { onReset() }
                .padding(horizontal = 8.dp, vertical = 5.dp)
        )
    }
}

@Composable
fun DataLoggerWidget(
    isRecording: Boolean,
    latestSampleCount: Int,
    onToggleRecord: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x09FFFFFF))
            .border(1.dp, Color(0x1EFFFFFF), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = "RUN LOGGER",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = TorqueEvoColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (isRecording) "REC ($latestSampleCount pts)" else "Ready",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isRecording) TorqueEvoColors.NominalGreen else TorqueEvoColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = if (isRecording) "Stop" else "Start Run",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (isRecording) Color(0xFFDC2626) else Color(0xFF16A34A))
                .clickable { onToggleRecord() }
                .padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}
