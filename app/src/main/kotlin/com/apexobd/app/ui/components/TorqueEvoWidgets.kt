package com.apexobd.app.ui.components

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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apexobd.app.data.prefs.MapCameraMode
import com.apexobd.app.data.prefs.MapCursorStyle
import com.apexobd.app.data.prefs.MapProvider
import com.apexobd.app.data.prefs.MapStylePreset
import com.apexobd.app.platform.GpsSnapshot
import com.apexobd.app.ui.theme.HudVisualPalette
import com.apexobd.app.ui.theme.LocalHudVisualStyle
import com.apexobd.app.ui.theme.TorqueEvoColors
import com.apexobd.core.protocol.DiagnosticReport
import com.apexobd.core.protocol.TripRunCalculator
import com.apexobd.core.scheduler.Timer0100State
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong
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

fun formatTelemetryValueAndUnit(
    value: Double?,
    unit: String,
    min: Double,
    max: Double
): Pair<String, String> {
    if (unit == "mm:ss") {
        return TripRunCalculator.formatDurationClock((value ?: 0.0).roundToLong()) to ""
    }
    return formatTelemetryValue(value, min, max) to unit
}

internal fun fitDigitsFontSizeSp(
    availWidthDp: Float,
    text: String,
    unit: String,
    unitSp: Float,
    minSp: Float = 12f,
    maxSp: Float = 30f
): Float {
    val unitReserve = if (unit.isBlank()) 0f else (unit.length * unitSp * 0.62f + 4f)
    val availForDigits = (availWidthDp - unitReserve).coerceAtLeast(22f)
    val effectiveChars = text.sumOf { ch ->
        when (ch) {
            '.', ':' -> 0.34
            ',' -> 0.30
            '1' -> 0.56
            else -> 0.64
        }
    }.toFloat().coerceAtLeast(1.6f)
    return (availForDigits / effectiveChars).coerceIn(minSp, maxSp)
}

internal fun fitLabelFontSizeSp(
    availWidthDp: Float,
    label: String,
    minSp: Float = 7.5f,
    maxSp: Float = 13f
): Float {
    // Uppercase bold condensed-width estimate so compact 1x1 HUD labels fit without ellipses.
    val chars = label.uppercase().sumOf { ch ->
        when (ch) {
            ' ', '.', '·', '-' -> 0.40
            'I', '1', 'L', 'T' -> 0.62
            'M', 'W' -> 0.94
            else -> 0.78
        }
    }.toFloat().coerceAtLeast(2f)
    return (availWidthDp / chars).coerceIn(minSp, maxSp)
}

private fun thresholdColor(
    value: Double?,
    warnThreshold: Double?,
    critThreshold: Double?,
    pct: Float,
    baseAccent: Color = TorqueEvoColors.ElectricBlue
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
        else -> baseAccent
    }
}

private fun Modifier.hudTelemetryCardChrome(
    palette: HudVisualPalette,
    radiusDp: Dp = 10.dp
): Modifier {
    val clipped = this.clip(RoundedCornerShape(radiusDp))
    if (!palette.showBorders) return clipped
    return clipped.drawBehind {
        val r = radiusDp.toPx()
        val cr = CornerRadius(r, r)
        drawRoundRect(color = palette.cardFill, cornerRadius = cr)
        palette.cardSheen?.let { sheen ->
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(sheen, Color.Transparent),
                    startY = 0f,
                    endY = size.height * 0.48f
                ),
                cornerRadius = cr
            )
        }
        drawRoundRect(
            color = palette.cardBorder,
            cornerRadius = cr,
            style = Stroke(width = 1.dp.toPx())
        )
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
    val palette = LocalHudVisualStyle.current
    val span = (max - min).coerceAtLeast(1.0)
    val pct = value?.let { ((it - min) / span).toFloat().coerceIn(0f, 1f) } ?: 0f
    val accent = thresholdColor(value, warnThreshold, critThreshold, pct, palette.accent)
    val (displayVal, displayUnit) = formatTelemetryValueAndUnit(value, unit, min, max)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .hudTelemetryCardChrome(palette, radiusDp = 12.dp)
    ) {
        val availW = (maxWidth - 8.dp).coerceAtLeast(48.dp)
        val availH = (maxHeight - 8.dp).coerceAtLeast(48.dp)
        // Solve radius directly against the 250-degree arc envelope (height 1.72r vs width 2.12r)
        // and shift center down into symmetrical headroom so rectangular tiles avoid empty bottom slack.
        val rDp = minOf(availW / 2.12f, availH / 1.72f).coerceAtLeast(22.dp)
        val dialD = rDp * 2f
        val cyDp = 4.dp + (availH * 0.5f) + (rDp * 0.20f)
        val labelSp = (dialD.value * 0.080f).coerceIn(10f, 15.5f).sp
        val unitSpVal = (dialD.value * 0.056f).coerceIn(9f, 12.5f)
        val valSp = fitDigitsFontSizeSp(
            availWidthDp = (dialD * 0.68f).value,
            text = displayVal,
            unit = displayUnit,
            unitSp = unitSpVal,
            minSp = 13f,
            maxSp = (dialD.value * 0.145f).coerceIn(15f, 28f)
        ).sp

        Canvas(modifier = Modifier.fillMaxSize().padding(4.dp)) {
            val r = minOf(size.width / 2.12f, size.height / 1.72f)
            val c = Offset(size.width / 2f, size.height * 0.5f + r * 0.20f)
            val arcTopLeft = Offset(c.x - r, c.y - r)
            val arcSize = Size(r * 2f, r * 2f)

            // Graduated outer ticks
            for (i in 0..24) {
                val deg = 145.0 + (i / 24.0) * 250.0
                val rad = deg * PI / 180.0
                val innerR = if (i % 4 == 0) r * 0.90f else r * 0.95f
                val outerR = r * 1.04f
                val tickCol = if (i >= 20) Color(0x66EF4444) else Color(0x30FFFFFF)
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
                color = palette.dialTrack,
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

        // Upper arc title & lower arc opening readout aligned relative to the circle center/radius
        Text(
            text = shortLabel.uppercase(),
            fontSize = labelSp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.8.sp,
            color = palette.labelColor,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (cyDp - rDp * 0.56f).coerceAtLeast(6.dp))
        )

        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (cyDp + rDp * 0.34f))
        ) {
            Text(
                text = displayVal,
                fontSize = valSp,
                fontWeight = FontWeight.Bold,
                color = palette.textPrimary,
                style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                maxLines = 1,
                softWrap = false
            )
            if (displayUnit.isNotBlank()) {
                Text(
                    text = displayUnit,
                    fontSize = unitSpVal.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.textSecondary,
                    modifier = Modifier.padding(bottom = 2.dp),
                    maxLines = 1,
                    softWrap = false
                )
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
    val palette = LocalHudVisualStyle.current
    val span = (max - min).coerceAtLeast(1.0)
    val pct = value?.let { ((it - min) / span).toFloat().coerceIn(0f, 1f) } ?: 0f
    val accent = thresholdColor(value, warnThreshold, critThreshold, pct, palette.accent)
    val (displayVal, displayUnit) = formatTelemetryValueAndUnit(value, unit, min, max)

    Box(
        modifier = modifier
            .fillMaxSize()
            .hudTelemetryCardChrome(palette, radiusDp = 12.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(
            text = shortLabel.uppercase(),
            fontSize = 10.5.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.9.sp,
            color = palette.labelColor,
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
                color = palette.dialTrack,
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
                text = displayVal,
                fontSize = 23.sp,
                fontWeight = FontWeight.Bold,
                color = palette.textPrimary,
                style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                maxLines = 1
            )
            if (displayUnit.isNotBlank()) {
                Text(
                    text = displayUnit,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.textSecondary,
                    modifier = Modifier.padding(bottom = 3.dp),
                    maxLines = 1
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("${min.roundToInt()}", fontSize = 8.sp, color = palette.textSecondary)
            Text("${max.roundToInt()}", fontSize = 8.sp, color = palette.textSecondary)
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
    val palette = LocalHudVisualStyle.current
    val span = (max - min).coerceAtLeast(1.0)
    val pct = value?.let { ((it - min) / span).toFloat().coerceIn(0f, 1f) } ?: 0f
    val accent = thresholdColor(value, warnThreshold, critThreshold, pct, palette.accent)
    val (displayVal, displayUnit) = formatTelemetryValueAndUnit(value, unit, min, max)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .hudTelemetryCardChrome(palette, radiusDp = 10.dp)
    ) {
        val compact = maxWidth < 105.dp || maxHeight < 82.dp
        val padH = if (compact) 4.5.dp else 10.dp
        val padV = if (compact) 4.dp else 7.dp
        val availInnerW = (maxWidth.value - padH.value * 2f).coerceAtLeast(36f)
        val dotReserve = if (compact) 7.dp else 10.dp
        val labelSp = fitLabelFontSizeSp(
            availWidthDp = (availInnerW - dotReserve.value).coerceAtLeast(26f),
            label = shortLabel,
            minSp = 7.5f,
            maxSp = if (compact) 12f else 13f
        ).sp
        val unitSpVal = if (compact) 8.5.sp else 11.5.sp
        val valSp = fitDigitsFontSizeSp(
            availWidthDp = availInnerW,
            text = displayVal,
            unit = displayUnit,
            unitSp = unitSpVal.value,
            minSp = 13f,
            maxSp = if (maxWidth > 165.dp && maxHeight > 74.dp) 32f else if (compact) 23f else 28f
        ).sp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = padH, vertical = padV),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = shortLabel.uppercase(),
                    fontSize = labelSp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = if (compact) 0.1.sp else 0.45.sp,
                    color = palette.labelColor,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = if (compact) 3.dp else 5.dp)
                )
                Box(
                    modifier = Modifier
                        .size(if (compact) 4.dp else 5.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp)
            ) {
                Text(
                    text = displayVal,
                    fontSize = valSp,
                    fontWeight = FontWeight.Bold,
                    color = if (accent == TorqueEvoColors.CriticalRed) TorqueEvoColors.CriticalRed else palette.textPrimary,
                    style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                    maxLines = 1,
                    softWrap = false
                )
                if (displayUnit.isNotBlank()) {
                    Text(
                        text = displayUnit,
                        fontSize = unitSpVal,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.textSecondary,
                        modifier = Modifier.padding(bottom = 2.dp),
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
            Spacer(Modifier.height(1.dp))
        }
    }
}

/** Previous sleek continuous gradient bar style (`WidgetDisplayType.BAR`). */
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
    val palette = LocalHudVisualStyle.current
    val span = (max - min).coerceAtLeast(1.0)
    val pct = value?.let { ((it - min) / span).toFloat().coerceIn(0f, 1f) } ?: 0f
    val accent = thresholdColor(value, warnThreshold, critThreshold, pct, palette.accent)
    val (displayVal, displayUnit) = formatTelemetryValueAndUnit(value, unit, min, max)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .hudTelemetryCardChrome(palette, radiusDp = 8.dp)
    ) {
        val compact = maxWidth < 145.dp
        val padH = if (compact) 6.dp else 10.dp
        val innerW = (maxWidth.value - 3f - padH.value * 2f).coerceAtLeast(34f)
        val unitSpVal = if (compact) 9.sp else 10.5.sp
        val compactValSp = fitDigitsFontSizeSp(
            availWidthDp = innerW,
            text = displayVal,
            unit = displayUnit,
            unitSp = unitSpVal.value,
            minSp = 12.5f,
            maxSp = 21f
        ).sp
        val wideLabelSp = 12.sp

        Row(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxHeight().size(width = 3.dp, height = 100.dp).background(accent))
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = padH, vertical = if (compact) 4.dp else 6.dp),
                verticalArrangement = if (compact) Arrangement.SpaceBetween else Arrangement.Center
            ) {
                if (compact) {
                    val compactLabelSp = fitLabelFontSizeSp(innerW, shortLabel, minSp = 7.5f, maxSp = 12f).sp
                    Text(
                        text = shortLabel.uppercase(),
                        fontSize = compactLabelSp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.1.sp,
                        color = palette.labelColor,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = displayVal,
                            fontSize = compactValSp,
                            fontWeight = FontWeight.Bold,
                            color = palette.textPrimary,
                            style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                            maxLines = 1,
                            softWrap = false
                        )
                        if (displayUnit.isNotBlank()) {
                            Text(displayUnit, fontSize = unitSpVal, color = palette.textSecondary, modifier = Modifier.padding(bottom = 1.dp), maxLines = 1, softWrap = false)
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
                            fontSize = wideLabelSp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.5.sp,
                            color = palette.labelColor,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(end = 6.dp)
                        )
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = displayVal,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = palette.textPrimary,
                                style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                                maxLines = 1,
                                softWrap = false
                            )
                            if (displayUnit.isNotBlank()) {
                                Text(displayUnit, fontSize = unitSpVal, color = palette.textSecondary, modifier = Modifier.padding(bottom = 2.dp), maxLines = 1, softWrap = false)
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
                        .background(palette.dialTrack)
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

/** Smooth Blue -> Green -> Yellow (mid) -> Orange -> Red spectrum for discrete boxed LED bars. */
internal fun segmentedSpectrumColor(fraction: Float): Color {
    val t = fraction.coerceIn(0f, 1f)
    val cBlue = Color(0xFF00A8FF)
    val cGreen = Color(0xFF22C55E)
    val cYellow = Color(0xFFFACC15)
    val cOrange = Color(0xFFF97316)
    val cRed = Color(0xFFEF4444)
    return when {
        t <= 0.25f -> lerp(cBlue, cGreen, t / 0.25f)
        t <= 0.50f -> lerp(cGreen, cYellow, (t - 0.25f) / 0.25f)
        t <= 0.75f -> lerp(cYellow, cOrange, (t - 0.50f) / 0.25f)
        else -> lerp(cOrange, cRed, (t - 0.75f) / 0.25f)
    }
}

/** Returns the 0..1 filled fraction for segment [segmentIndex] so wide scales animate smoothly per pixel. */
internal fun segmentedBoxFillFraction(
    pct: Float,
    segmentIndex: Int,
    segmentCount: Int
): Float {
    if (segmentCount <= 0 || pct <= 0.002f) return 0f
    val exactSegments = pct.coerceIn(0f, 1f) * segmentCount.toFloat()
    return (exactSegments - segmentIndex.toFloat()).coerceIn(0f, 1f)
}

/** Discrete boxed LED segmented bar style (`WidgetDisplayType.SEGMENTED_BAR`) — empty neutral boxes until lit. */
@Composable
fun SegmentedBarGaugeWidget(
    shortLabel: String,
    value: Double?,
    unit: String,
    min: Double,
    max: Double,
    warnThreshold: Double?,
    critThreshold: Double?,
    modifier: Modifier = Modifier
) {
    val palette = LocalHudVisualStyle.current
    val span = (max - min).coerceAtLeast(1.0)
    val pct = value?.let { ((it - min) / span).toFloat().coerceIn(0f, 1f) } ?: 0f
    val hasActiveFill = value != null && pct > 0.002f
    val activeAccent = if (!hasActiveFill) {
        palette.dialTrack
    } else {
        thresholdColor(value, warnThreshold, critThreshold, pct, segmentedSpectrumColor(pct))
    }
    val (displayVal, displayUnit) = formatTelemetryValueAndUnit(value, unit, min, max)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .hudTelemetryCardChrome(palette, radiusDp = 8.dp)
    ) {
        val compact = maxWidth < 145.dp
        val segments = if (compact) 12 else 18
        val padH = if (compact) 5.5.dp else 10.dp
        val innerW = (maxWidth.value - 3f - padH.value * 2f).coerceAtLeast(34f)
        val unitSpVal = if (compact) 8.5.sp else 10.sp
        val fittedValSp = fitDigitsFontSizeSp(
            availWidthDp = if (compact) innerW * 0.54f else innerW * 0.44f,
            text = displayVal,
            unit = displayUnit,
            unitSp = unitSpVal.value,
            minSp = 12f,
            maxSp = if (compact) 16f else 19f
        ).sp

        Row(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxHeight().size(width = 3.dp, height = 100.dp).background(activeAccent))
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = padH, vertical = if (compact) 4.dp else 6.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                val segLabelSp = fitLabelFontSizeSp((innerW * if (compact) 0.50f else 0.56f), shortLabel, minSp = 7.5f, maxSp = 12.5f).sp
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = shortLabel.uppercase(),
                        fontSize = segLabelSp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = if (compact) 0.1.sp else 0.35.sp,
                        color = palette.labelColor,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 4.dp)
                    )
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = displayVal,
                            fontSize = fittedValSp,
                            fontWeight = FontWeight.Bold,
                            color = if (activeAccent == TorqueEvoColors.CriticalRed && hasActiveFill) TorqueEvoColors.CriticalRed else palette.textPrimary,
                            style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                            maxLines = 1,
                            softWrap = false
                        )
                        if (displayUnit.isNotBlank()) {
                            Text(displayUnit, fontSize = unitSpVal, color = palette.textSecondary, modifier = Modifier.padding(bottom = 1.dp), maxLines = 1, softWrap = false)
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (compact) 10.dp else 13.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 3.dp)
                ) {
                    val lastIdx = (segments - 1).coerceAtLeast(1)
                    for (idx in 0 until segments) {
                        val boxFill = if (value == null) 0f else segmentedBoxFillFraction(pct, idx, segments)
                        val frac = idx.toFloat() / lastIdx.toFloat()
                        val litColor = segmentedSpectrumColor(frac)
                        val boxShape = RoundedCornerShape(2.5.dp)
                        val boxMod = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(boxShape)
                        when {
                            boxFill >= 0.99f -> {
                                Box(modifier = boxMod.background(litColor))
                            }
                            boxFill <= 0.01f -> {
                                Box(
                                    modifier = boxMod
                                        .background(Color(0x14FFFFFF))
                                        .border(0.6.dp, Color(0x1EFFFFFF), boxShape)
                                )
                            }
                            else -> {
                                Box(
                                    modifier = boxMod
                                        .background(Color(0x14FFFFFF))
                                        .border(0.6.dp, Color(0x1EFFFFFF), boxShape)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .fillMaxWidth(boxFill)
                                            .background(litColor)
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val mid = (min + max) / 2.0
                    Text("${min.roundToInt()}", fontSize = 8.sp, color = palette.textSecondary)
                    if (!compact && unit != "mm:ss") {
                        Text("${mid.roundToInt()}", fontSize = 8.sp, color = palette.textSecondary)
                    }
                    Text(
                        text = if (unit == "mm:ss") "3h" else "${max.roundToInt()}",
                        fontSize = 8.sp,
                        color = if (hasActiveFill && pct > 0.85f) TorqueEvoColors.CriticalRed else palette.textSecondary
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
    val palette = LocalHudVisualStyle.current
    val (displayVal, displayUnit) = formatTelemetryValueAndUnit(value, unit, min, max)
    val span = (max - min).coerceAtLeast(1.0)
    Column(
        modifier = modifier
            .fillMaxSize()
            .hudTelemetryCardChrome(palette, radiusDp = 10.dp)
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
                fontSize = 11.5.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.5.sp,
                color = palette.labelColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 6.dp)
            )
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = displayVal,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.textPrimary,
                    style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                    maxLines = 1,
                    softWrap = false
                )
                if (displayUnit.isNotBlank()) {
                    Text(displayUnit, fontSize = 10.sp, color = palette.textSecondary, modifier = Modifier.padding(bottom = 1.dp), maxLines = 1, softWrap = false)
                }
            }
        }

        Canvas(modifier = Modifier.fillMaxSize().padding(top = 5.dp)) {
            drawLine(
                color = palette.dialTrack.copy(alpha = 0.65f),
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
                    listOf(palette.accent.copy(alpha = 0.32f), Color.Transparent)
                )
            )
            drawPath(
                path = linePath,
                color = palette.accent,
                style = Stroke(width = 3f, cap = StrokeCap.Round)
            )
            drawCircle(color = Color.White, radius = 4.5f, center = lastPt)
            drawCircle(color = palette.accent, radius = 4.5f, center = lastPt, style = Stroke(2.2f))
        }
    }
}

@Composable
fun MiniMapWidget(
    gps: GpsSnapshot,
    mapProvider: MapProvider,
    activePmtilesPath: String? = null,
    mapStylePreset: MapStylePreset = MapStylePreset.DARK_CONTRAST,
    mapCameraMode: MapCameraMode = MapCameraMode.NAV_3D_45,
    mapCursorStyle: MapCursorStyle = MapCursorStyle.SPORT_ARROW,
    highContrastRoads: Boolean = true,
    isRecording: Boolean,
    isPaused: Boolean = false,
    recordingStatusLabel: String? = null,
    onToggleRecord: () -> Unit,
    onTogglePauseResume: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val palette = LocalHudVisualStyle.current
    val baseMod = modifier
        .fillMaxSize()
        .clip(RoundedCornerShape(12.dp))
        .background(Color(mapStylePreset.bgHex))
    Box(
        modifier = if (palette.showBorders) {
            baseMod.border(1.dp, palette.cardBorder, RoundedCornerShape(12.dp))
        } else {
            baseMod
        }
    ) {
        DualMapCanvas(
            mapProvider = mapProvider,
            latitude = gps.latitude,
            longitude = gps.longitude,
            trail = if (isRecording) gps.trail else emptyList(),
            activePmtilesPath = activePmtilesPath,
            bearingDeg = gps.bearingDeg,
            followNavigation = true,
            mapStylePreset = mapStylePreset,
            mapCameraMode = mapCameraMode,
            mapCursorStyle = mapCursorStyle,
            highContrastRoads = highContrastRoads,
            modifier = Modifier.fillMaxSize()
        )
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    when {
                        isRecording && isPaused -> Color(0xDC261704)
                        isRecording -> Color(0xCC1A0808)
                        else -> Color(0xBF000000)
                    }
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isRecording) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (isPaused) TorqueEvoColors.WarnAmber else Color(0xFFEF4444))
                )
            }
            Text(
                text = if (isRecording) (recordingStatusLabel ?: if (isPaused) "PAUSED" else "REC") else "Live GPS",
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                color = when {
                    isRecording && isPaused -> Color(0xFFFCD34D)
                    isRecording -> Color(0xFFFCA5A5)
                    else -> TorqueEvoColors.TextPrimary
                }
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isRecording && onTogglePauseResume != null) {
                val pauseColor = if (isPaused) palette.accent else TorqueEvoColors.WarnAmber
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (isPaused) Color(0xE0091D30) else Color(0xE01F1405))
                        .border(width = 2.dp, color = pauseColor, shape = CircleShape)
                        .semantics {
                            contentDescription = if (isPaused) "Resume Run" else "Pause Run"
                        }
                        .clickable(role = Role.Button, onClick = onTogglePauseResume),
                    contentAlignment = Alignment.Center
                ) {
                    if (isPaused) {
                        Canvas(modifier = Modifier.size(14.dp)) {
                            val p = Path().apply {
                                moveTo(size.width * 0.25f, size.height * 0.14f)
                                lineTo(size.width * 0.25f, size.height * 0.86f)
                                lineTo(size.width * 0.88f, size.height * 0.50f)
                                close()
                            }
                            drawPath(p, pauseColor)
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(width = 3.5.dp, height = 12.dp)
                                    .clip(RoundedCornerShape(1.5.dp))
                                    .background(TorqueEvoColors.WarnAmber)
                            )
                            Box(
                                modifier = Modifier
                                    .size(width = 3.5.dp, height = 12.dp)
                                    .clip(RoundedCornerShape(1.5.dp))
                                    .background(TorqueEvoColors.WarnAmber)
                            )
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (isRecording) Color(0xE0180909) else Color(0xD90A0D14))
                    .border(
                        width = 2.dp,
                        color = if (isRecording) Color(0xFFEF4444) else Color(0xE6FFFFFF),
                        shape = CircleShape
                    )
                    .semantics {
                        contentDescription = if (isRecording) "End Recording" else "Record Run"
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
    val palette = LocalHudVisualStyle.current
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .hudTelemetryCardChrome(palette, radiusDp = 10.dp)
    ) {
        val compact = maxHeight < 72.dp || maxWidth < 155.dp
        val padH = if (compact) 7.dp else 10.dp
        val padV = if (compact) 4.dp else 8.dp
        val btnSize = if (compact) 30.dp else 36.dp
        val btnGap = 6.dp
        val textMaxW = (maxWidth.value - padH.value * 2f - btnSize.value - btnGap.value).coerceAtLeast(44f)
        val kplText = formatTelemetryValue(kplValue, 0.0, 30.0)
        val labelSpVal = fitLabelFontSizeSp(textMaxW, "KPL · AVG", minSp = 8.5f, maxSp = if (compact) 10.5f else 12f)
        val unitSpVal = if (compact) 8.5.sp else 10.5.sp
        val heightBoundMaxSp = ((maxHeight.value - padV.value * 2f - labelSpVal * 1.3f) * 0.72f)
            .coerceIn(13f, if (compact) 20f else 24f)
        val valSp = fitDigitsFontSizeSp(
            availWidthDp = textMaxW,
            text = kplText,
            unit = kplUnit,
            unitSp = unitSpVal.value,
            minSp = 13f,
            maxSp = heightBoundMaxSp
        ).sp

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = padH, vertical = padV),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(end = btnGap),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "KPL · AVG",
                    fontSize = labelSpVal.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = if (compact) 0.2.sp else 0.6.sp,
                    color = palette.labelColor,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(2.5.dp)
                ) {
                    Text(
                        text = kplText,
                        fontSize = valSp,
                        fontWeight = FontWeight.Bold,
                        color = if (paused) palette.textSecondary else palette.textPrimary,
                        style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        text = kplUnit,
                        fontSize = unitSpVal,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.textSecondary,
                        modifier = Modifier.padding(bottom = if (compact) 1.dp else 2.dp),
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            val ringColor = if (paused) TorqueEvoColors.WarnAmber else palette.accent
            Box(
                modifier = Modifier
                    .align(Alignment.Bottom)
                    .size(btnSize)
                    .clip(CircleShape)
                    .background(if (paused) Color(0xE01A1205) else Color(0xD90A0D14))
                    .border(width = 2.dp, color = ringColor, shape = CircleShape)
                    .semantics {
                        contentDescription = if (paused) "Resume KPL Tracking" else "Pause Idle KPL"
                    }
                    .clickable(role = Role.Button, onClick = onTogglePause),
                contentAlignment = Alignment.Center
            ) {
                if (paused) {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Box(
                            modifier = Modifier
                                .size(width = if (compact) 3.dp else 4.dp, height = if (compact) 10.dp else 13.dp)
                                .clip(RoundedCornerShape(1.5.dp))
                                .background(TorqueEvoColors.WarnAmber)
                        )
                        Box(
                            modifier = Modifier
                                .size(width = if (compact) 3.dp else 4.dp, height = if (compact) 10.dp else 13.dp)
                                .clip(RoundedCornerShape(1.5.dp))
                                .background(TorqueEvoColors.WarnAmber)
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(if (compact) 11.dp else 14.dp)
                            .clip(CircleShape)
                            .background(ringColor)
                    )
                }
            }
        }
    }
}

@Composable
fun DtcCheckerWidget(
    report: DiagnosticReport?,
    onScan: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalHudVisualStyle.current
    val count = (report?.confirmed?.size ?: 0) + (report?.pending?.size ?: 0)
    Row(
        modifier = modifier
            .fillMaxSize()
            .hudTelemetryCardChrome(palette, radiusDp = 10.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = "FAULT CHECK (03/07)",
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.5.sp,
                color = palette.labelColor,
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
                    report == null -> palette.textPrimary
                    count == 0 -> TorqueEvoColors.NominalGreen
                    else -> TorqueEvoColors.CriticalRed
                }
            )
        }
        Text(
            text = "Scan",
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = palette.textPrimary,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, palette.cardBorder, RoundedCornerShape(6.dp))
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
    val palette = LocalHudVisualStyle.current
    val showSecondsSuffix = timer.running || timer.bestRunSec != null
    Row(
        modifier = modifier
            .fillMaxSize()
            .hudTelemetryCardChrome(palette, radiusDp = 10.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = "0–100 KM/H SPRINT",
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                color = palette.labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = if (timer.running) "%.2f".format(timer.currentSec) else timer.bestRunSec?.let { "%.2f".format(it) } ?: "ARMED",
                    fontSize = if (showSecondsSuffix) 20.sp else 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (timer.running) palette.accent else palette.textPrimary,
                    style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                    maxLines = 1
                )
                if (showSecondsSuffix) {
                    Text("s", fontSize = 10.sp, color = palette.textSecondary, modifier = Modifier.padding(bottom = 2.dp))
                }
            }
        }
        Text(
            text = "Reset",
            fontSize = 10.sp,
            color = palette.textSecondary,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, palette.cardBorder, RoundedCornerShape(6.dp))
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
    val palette = LocalHudVisualStyle.current
    Row(
        modifier = modifier
            .fillMaxSize()
            .hudTelemetryCardChrome(palette, radiusDp = 10.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = "RUN LOGGER",
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                color = palette.labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (isRecording) "REC ($latestSampleCount pts)" else "Ready",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isRecording) TorqueEvoColors.NominalGreen else palette.textPrimary,
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
