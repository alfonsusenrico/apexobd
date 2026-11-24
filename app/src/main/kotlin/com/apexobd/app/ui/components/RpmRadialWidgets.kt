package com.apexobd.app.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.apexobd.app.ui.theme.HudVisualPalette
import com.apexobd.app.ui.theme.LocalHudVisualStyle
import com.apexobd.app.ui.theme.TorqueEvoColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

enum class ShiftLampStage { OFF, GREEN, YELLOW, RED_FLASH }

enum class MetricAlertLevel { NONE, WARN, CRITICAL }

fun isRpmSensorKey(sensorKey: String): Boolean =
    sensorKey == "rpm" || sensorKey == "cluster_rpm"

fun defaultAlertBelowForSensor(sensorKey: String): Boolean =
    sensorKey in setOf("voltage", "fuel_level", "fuel_remaining", "range_dte", "fuel_pressure", "baro")

fun rpmDialMaxForRedline(redlineRpm: Int): Double = when {
    redlineRpm <= 5200 -> 6000.0
    redlineRpm <= 7500 -> 8000.0
    redlineRpm <= 8500 -> 9000.0
    redlineRpm <= 9500 -> 10000.0
    else -> 12000.0
}

/**
 * Computes shift-lamp progression (`OFF -> GREEN -> YELLOW -> RED_FLASH`) purely for the shift dot/LEDs.
 * Remains `OFF` unless the user has explicitly configured [shiftRpm] > 0.
 */
fun computeShiftLampStage(
    rpm: Double?,
    shiftRpm: Double?,
    redlineRpm: Int
): ShiftLampStage {
    if (rpm == null || !rpm.isFinite() || shiftRpm == null || shiftRpm <= 0.0) return ShiftLampStage.OFF
    val safeRedline = redlineRpm.toDouble().coerceAtLeast(1500.0)
    val trigger = minOf(shiftRpm, safeRedline)
    return when {
        rpm >= trigger || rpm >= safeRedline -> ShiftLampStage.RED_FLASH
        rpm >= trigger * 0.80 -> ShiftLampStage.YELLOW
        rpm >= trigger * 0.60 -> ShiftLampStage.GREEN
        else -> ShiftLampStage.OFF
    }
}

/**
 * Evaluates optional user-configured per-widget warning & critical thresholds with explicit direction.
 * Returns [MetricAlertLevel.NONE] whenever both thresholds are `null`.
 */
fun evaluateMetricAlert(
    value: Double?,
    warnThreshold: Double?,
    critThreshold: Double?,
    alertBelow: Boolean
): MetricAlertLevel {
    if (value == null || !value.isFinite()) return MetricAlertLevel.NONE
    if (critThreshold != null && critThreshold.isFinite()) {
        val hit = if (alertBelow) value <= critThreshold else value >= critThreshold
        if (hit) return MetricAlertLevel.CRITICAL
    }
    if (warnThreshold != null && warnThreshold.isFinite()) {
        val hit = if (alertBelow) value <= warnThreshold else value >= warnThreshold
        if (hit) return MetricAlertLevel.WARN
    }
    return MetricAlertLevel.NONE
}

/** Runs a punchy asymmetric strobe pulse (~65% ON duty cycle) ONLY while [active] is true for peak perceived brightness. */
@Composable
fun rememberFlashPulseState(active: Boolean): State<Boolean> {
    val state = remember { mutableStateOf(true) }
    LaunchedEffect(active) {
        if (!active) {
            state.value = true
            return@LaunchedEffect
        }
        var on = true
        while (isActive) {
            state.value = on
            delay(if (on) 130L else 75L)
            on = !on
        }
    }
    return state
}

private fun Modifier.rpmCardChrome(
    palette: HudVisualPalette,
    flashingRed: Boolean,
    radiusDp: Dp = 12.dp
): Modifier {
    val clipped = this.clip(RoundedCornerShape(radiusDp))
    return clipped.drawBehind {
        val r = radiusDp.toPx()
        val cr = CornerRadius(r, r)
        if (palette.showBorders) {
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
        }
        if (flashingRed) {
            drawRoundRect(
                color = Color(0x26FF0000),
                cornerRadius = cr
            )
            drawRoundRect(
                color = Color(0xFFFF1A1A),
                cornerRadius = cr,
                style = Stroke(width = 2.8.dp.toPx())
            )
        } else if (palette.showBorders) {
            drawRoundRect(
                color = palette.cardBorder,
                cornerRadius = cr,
                style = Stroke(width = 1.dp.toPx())
            )
        }
    }
}

/**
 * Wraps any standard metric widget to render an optional steady Amber warning indicator
 * or max-brightness flashing Red critical alert border + top-right LED beacon when user thresholds trigger.
 */
@Composable
fun MetricAlertFrameOverlay(
    valueProvider: () -> Double?,
    warnThreshold: Double?,
    critThreshold: Double?,
    alertBelow: Boolean,
    cornerRadiusDp: Dp = 10.dp,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val hasAnyThreshold = warnThreshold != null || critThreshold != null
    if (!hasAnyThreshold) {
        Box(modifier = modifier.fillMaxSize(), content = content)
        return
    }
    val alertLevel by remember(valueProvider, warnThreshold, critThreshold, alertBelow) {
        derivedStateOf {
            evaluateMetricAlert(valueProvider(), warnThreshold, critThreshold, alertBelow)
        }
    }
    val flashState = rememberFlashPulseState(active = alertLevel == MetricAlertLevel.CRITICAL)

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithContent {
                drawContent()
                when (alertLevel) {
                    MetricAlertLevel.NONE -> Unit
                    MetricAlertLevel.WARN -> {
                        val cr = CornerRadius(cornerRadiusDp.toPx(), cornerRadiusDp.toPx())
                        drawRoundRect(
                            color = SHIFT_YELLOW,
                            cornerRadius = cr,
                            style = Stroke(width = 2.dp.toPx())
                        )
                        val dotR = (minOf(size.width, size.height) * 0.055f).coerceIn(4.dp.toPx(), 7.5.dp.toPx())
                        val center = Offset(size.width - dotR - 7.dp.toPx(), dotR + 7.dp.toPx())
                        drawHighIntensityLed(center = center, radius = dotR, ledColor = SHIFT_YELLOW, rimColor = Color.White, bloomScale = 2.8f)
                    }
                    MetricAlertLevel.CRITICAL -> {
                        val on = flashState.value
                        val cr = CornerRadius(cornerRadiusDp.toPx(), cornerRadiusDp.toPx())
                        if (on) {
                            drawRoundRect(
                                color = Color(0x22FF0000),
                                cornerRadius = cr
                            )
                        }
                        drawRoundRect(
                            color = if (on) Color(0xFFFF1A1A) else Color(0x66FF1A1A),
                            cornerRadius = cr,
                            style = Stroke(width = (if (on) 2.6.dp else 1.5.dp).toPx())
                        )
                        val dotR = (minOf(size.width, size.height) * 0.060f).coerceIn(4.5.dp.toPx(), 8.dp.toPx())
                        val center = Offset(size.width - dotR - 7.dp.toPx(), dotR + 7.dp.toPx())
                        if (on) {
                            drawHighIntensityLed(center = center, radius = dotR, ledColor = SHIFT_RED, rimColor = Color.White, bloomScale = 3.4f)
                        } else {
                            drawCircle(color = SHIFT_RED_DIM, radius = dotR, center = center)
                        }
                    }
                }
            },
        content = content
    )
}

private val SHIFT_GREEN = Color(0xFF00FF5A)
private val SHIFT_GREEN_RIM = Color(0xFFD6FFE5)
private val SHIFT_YELLOW = Color(0xFFFFEE00)
private val SHIFT_YELLOW_RIM = Color(0xFFFFFFCC)
private val SHIFT_RED = Color(0xFFFF1414)
private val SHIFT_RED_DIM = Color(0xFF421014)
private val SHIFT_OFF_FILL = Color(0xFF181F2A)
private val SHIFT_OFF_RIM = Color(0x36FFFFFF)

/**
 * Draws a peak-luminance OLED LED with a multi-stop radial bloom halo and white-hot emitter core
 * so shift & critical flashes read clearly in bright cabin light or peripheral vision.
 */
private fun DrawScope.drawHighIntensityLed(
    center: Offset,
    radius: Float,
    ledColor: Color,
    rimColor: Color,
    bloomScale: Float
) {
    val bloomRadius = (radius * bloomScale).coerceAtLeast(radius + 2f)
    drawCircle(
        brush = Brush.radialGradient(
            0.0f to ledColor.copy(alpha = 0.95f),
            0.35f to ledColor.copy(alpha = 0.65f),
            0.68f to ledColor.copy(alpha = 0.24f),
            1.0f to Color.Transparent,
            center = center,
            radius = bloomRadius
        ),
        radius = bloomRadius,
        center = center
    )
    drawCircle(
        brush = Brush.radialGradient(
            0.0f to Color.White,
            0.42f to Color.White.copy(alpha = 0.92f),
            0.78f to ledColor,
            1.0f to ledColor,
            center = center,
            radius = radius
        ),
        radius = radius,
        center = center
    )
    drawCircle(
        color = rimColor,
        radius = radius,
        center = center,
        style = Stroke(width = (radius * 0.16f).coerceAtLeast(1.4f))
    )
}

private fun DrawScope.drawShiftLampDot(
    center: Offset,
    radius: Float,
    stage: ShiftLampStage,
    flashOn: Boolean
) {
    when (stage) {
        ShiftLampStage.OFF -> {
            drawCircle(color = SHIFT_OFF_FILL, radius = radius, center = center)
            drawCircle(color = SHIFT_OFF_RIM, radius = radius, center = center, style = Stroke(width = (radius * 0.16f).coerceAtLeast(1.3f)))
        }
        ShiftLampStage.GREEN -> {
            drawHighIntensityLed(center = center, radius = radius, ledColor = SHIFT_GREEN, rimColor = SHIFT_GREEN_RIM, bloomScale = 2.9f)
        }
        ShiftLampStage.YELLOW -> {
            drawHighIntensityLed(center = center, radius = radius, ledColor = SHIFT_YELLOW, rimColor = SHIFT_YELLOW_RIM, bloomScale = 3.1f)
        }
        ShiftLampStage.RED_FLASH -> {
            if (flashOn) {
                drawHighIntensityLed(center = center, radius = radius * 1.08f, ledColor = SHIFT_RED, rimColor = Color.White, bloomScale = 3.8f)
            } else {
                drawCircle(color = SHIFT_RED_DIM, radius = radius, center = center)
                drawCircle(color = SHIFT_RED.copy(alpha = 0.50f), radius = radius, center = center, style = Stroke(width = (radius * 0.16f).coerceAtLeast(1.3f)))
            }
        }
    }
}

private fun DrawScope.drawCenteredCanvasText(
    text: String,
    cx: Float,
    cy: Float,
    paint: Paint,
    textSizePx: Float,
    color: Color
) {
    paint.textSize = textSizePx
    paint.color = color.toArgb()
    val fm = paint.fontMetrics
    val baseline = cy - (fm.ascent + fm.descent) / 2f
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawText(text, cx, baseline, paint)
    }
}

private fun DrawScope.drawAccentWithRedlineArc(
    topLeft: Offset,
    arcSize: Size,
    startDeg: Float,
    sweepDeg: Float,
    pct: Float,
    redPct: Float,
    strokeWidth: Float,
    cap: StrokeCap,
    accentColor: Color
) {
    if (pct <= 0.002f) return
    val blueEnd = minOf(pct, redPct)
    if (blueEnd > 0.002f) {
        drawArc(
            color = accentColor,
            startAngle = startDeg,
            sweepAngle = sweepDeg * blueEnd,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = cap)
        )
    }
    if (pct > redPct) {
        drawArc(
            color = TorqueEvoColors.CriticalRed,
            startAngle = startDeg + sweepDeg * redPct,
            sweepAngle = sweepDeg * (pct - redPct),
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = cap)
        )
    }
}

@Composable
private fun rememberCanvasTextPaint(bold: Boolean = true): Paint = remember(bold) {
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
        fontFeatureSettings = "tnum"
    }
}

/**
 * Option A · GT Motorsport Tach (`WidgetDisplayType.RPM_TACH_GT`):
 * 270° numbered 0..N scale, theme-accent sweep + always-red redline, tapered razor needle,
 * integrated 12-o'clock Green -> Yellow -> Flashing Red shift dot (no text label), and crisp bottom digits.
 */
@Composable
fun RpmTachGtWidget(
    valueProvider: () -> Double?,
    redlineRpm: Int,
    shiftRpm: Double?,
    modifier: Modifier = Modifier
) {
    val palette = LocalHudVisualStyle.current
    val maxRpm = remember(redlineRpm) { rpmDialMaxForRedline(redlineRpm) }
    val maxThousands = remember(maxRpm) { (maxRpm / 1000.0).roundToInt().coerceAtLeast(6) }
    val numPaint = rememberCanvasTextPaint(bold = true)
    val subPaint = rememberCanvasTextPaint(bold = true)

    val shiftStage by remember(valueProvider, shiftRpm, redlineRpm) {
        derivedStateOf { computeShiftLampStage(valueProvider(), shiftRpm, redlineRpm) }
    }
    val flashState = rememberFlashPulseState(active = shiftStage == ShiftLampStage.RED_FLASH)
    val flashingBorder = shiftStage == ShiftLampStage.RED_FLASH && flashState.value

    Box(
        modifier = modifier
            .fillMaxSize()
            .rpmCardChrome(palette, flashingRed = flashingBorder, radiusDp = 12.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(5.dp)) {
            val rpm = valueProvider()
            val pct = rpm?.let { (it / maxRpm).toFloat().coerceIn(0f, 1f) } ?: 0f
            val redPct = (redlineRpm / maxRpm).toFloat().coerceIn(0.4f, 0.96f)
            val inRedline = rpm != null && rpm >= redlineRpm
            val stage = shiftStage
            val flashOn = flashState.value

            val startDeg = 135f
            val sweepDeg = 270f
            val r = minOf(size.width / 2.12f, size.height / 1.98f)
            val c = Offset(size.width / 2f, size.height * 0.53f)
            val arcTopLeft = Offset(c.x - r, c.y - r)
            val arcSize = Size(r * 2f, r * 2f)
            val trackW = (r * 0.11f).coerceIn(5f, 18f)

            // Base track + permanent Red redline band + Electric Blue sweep
            drawArc(palette.dialTrack, startDeg, sweepDeg, false, arcTopLeft, arcSize, style = Stroke(trackW, cap = StrokeCap.Round))
            drawArc(
                TorqueEvoColors.CriticalRed.copy(alpha = 0.50f),
                startDeg + sweepDeg * redPct,
                sweepDeg * (1f - redPct),
                false,
                arcTopLeft,
                arcSize,
                style = Stroke(trackW, cap = StrokeCap.Round)
            )
            drawAccentWithRedlineArc(arcTopLeft, arcSize, startDeg, sweepDeg, pct, redPct, trackW, StrokeCap.Round, palette.accent)

            // Subtle amber shift-point notch when shift threshold is configured
            if (shiftRpm != null && shiftRpm > 0.0) {
                val spPct = (shiftRpm / maxRpm).toFloat().coerceIn(0.05f, 0.99f)
                val smRad = (startDeg + sweepDeg * spPct) * PI / 180.0
                drawLine(
                    color = SHIFT_YELLOW,
                    start = Offset(c.x + (r - trackW * 0.65f) * cos(smRad).toFloat(), c.y + (r - trackW * 0.65f) * sin(smRad).toFloat()),
                    end = Offset(c.x + (r + trackW * 0.65f) * cos(smRad).toFloat(), c.y + (r + trackW * 0.65f) * sin(smRad).toFloat()),
                    strokeWidth = (r * 0.025f).coerceIn(2f, 4f),
                    cap = StrokeCap.Round
                )
            }

            // Graduated ticks + 0..maxThousands numerals
            val totalTicks = maxThousands * 4
            val numSize = (r * 0.155f).coerceIn(10.dp.toPx(), 18.dp.toPx())
            for (i in 0..totalTicks) {
                val f = i.toFloat() / totalTicks.toFloat()
                val rad = (startDeg + sweepDeg * f) * PI / 180.0
                val major = i % 4 == 0
                val isRed = f * maxRpm >= redlineRpm
                val r0 = if (major) r * 0.82f else r * 0.88f
                val r1 = r * 0.94f
                drawLine(
                    color = if (isRed) TorqueEvoColors.CriticalRed else if (major) Color(0xDFFFFFFF) else Color(0x4AFFFFFF),
                    start = Offset(c.x + r0 * cos(rad).toFloat(), c.y + r0 * sin(rad).toFloat()),
                    end = Offset(c.x + r1 * cos(rad).toFloat(), c.y + r1 * sin(rad).toFloat()),
                    strokeWidth = if (major) (r * 0.022f).coerceIn(1.6f, 3.6f) else (r * 0.011f).coerceIn(1f, 2f),
                    cap = StrokeCap.Round
                )
                if (major) {
                    val nr = r * 0.69f
                    drawCenteredCanvasText(
                        text = "${i / 4}",
                        cx = c.x + nr * cos(rad).toFloat(),
                        cy = c.y + nr * sin(rad).toFloat(),
                        paint = numPaint,
                        textSizePx = numSize,
                        color = if (isRed) TorqueEvoColors.CriticalRed else palette.labelColor
                    )
                }
            }

            // Tapered motorsport needle
            val nRad = (startDeg + sweepDeg * pct) * PI / 180.0
            val cosN = cos(nRad).toFloat()
            val sinN = sin(nRad).toFloat()
            val perpX = -sinN
            val perpY = cosN
            val halfBase = (r * 0.042f).coerceIn(2.5f, 6.5f)
            val tailLen = r * 0.16f
            val tipLen = r * 0.85f
            val needlePath = Path().apply {
                moveTo(c.x + tipLen * cosN, c.y + tipLen * sinN)
                lineTo(c.x + perpX * halfBase - cosN * tailLen, c.y + perpY * halfBase - sinN * tailLen)
                lineTo(c.x - perpX * halfBase - cosN * tailLen, c.y - perpY * halfBase - sinN * tailLen)
                close()
            }
            drawPath(needlePath, color = if (inRedline) TorqueEvoColors.CriticalRed else Color.White)

            val hubR = (r * 0.12f).coerceIn(6f, 20f)
            drawCircle(color = Color(0xFF0B0F17), radius = hubR, center = c)
            drawCircle(
                color = if (inRedline) TorqueEvoColors.CriticalRed else palette.accent,
                radius = hubR,
                center = c,
                style = Stroke(width = (r * 0.024f).coerceIn(2f, 4f))
            )

            // Integrated 12-o'clock Shift Lamp dot drawn unobstructed above needle when configured
            if (shiftRpm != null && shiftRpm > 0.0) {
                drawShiftLampDot(
                    center = Offset(c.x, c.y - r * 0.41f),
                    radius = (r * 0.105f).coerceIn(5.5.dp.toPx(), 14.dp.toPx()),
                    stage = stage,
                    flashOn = flashOn
                )
            }

            // Digital RPM at bottom opening
            val rpmText = rpm?.roundToInt()?.toString() ?: "—"
            drawCenteredCanvasText(
                text = rpmText,
                cx = c.x,
                cy = c.y + r * 0.58f,
                paint = numPaint,
                textSizePx = (r * 0.28f).coerceIn(13.dp.toPx(), 30.dp.toPx()),
                color = if (inRedline) TorqueEvoColors.CriticalRed else palette.textPrimary
            )
            drawCenteredCanvasText(
                text = "RPM",
                cx = c.x,
                cy = c.y + r * 0.83f,
                paint = subPaint,
                textSizePx = (r * 0.145f).coerceIn(10.dp.toPx(), 17.dp.toPx()),
                color = palette.labelColor.copy(alpha = 0.90f)
            )
        }
    }
}

/**
 * Option B · Sequential F1 Shift LEDs (`WidgetDisplayType.RPM_TACH_F1_LEDS`):
 * Needle-free Electric Blue segmented arc + permanent Red redline + 5 curved top-crown shift LEDs
 * building Green -> Yellow -> Red sequentially and strobe-flashing Red together at shift (no text label).
 */
@Composable
fun RpmTachF1LedsWidget(
    valueProvider: () -> Double?,
    redlineRpm: Int,
    shiftRpm: Double?,
    modifier: Modifier = Modifier
) {
    val palette = LocalHudVisualStyle.current
    val maxRpm = remember(redlineRpm) { rpmDialMaxForRedline(redlineRpm) }
    val maxThousands = remember(maxRpm) { (maxRpm / 1000.0).roundToInt().coerceAtLeast(6) }
    val numPaint = rememberCanvasTextPaint(bold = true)
    val subPaint = rememberCanvasTextPaint(bold = true)

    val shiftStage by remember(valueProvider, shiftRpm, redlineRpm) {
        derivedStateOf { computeShiftLampStage(valueProvider(), shiftRpm, redlineRpm) }
    }
    val flashState = rememberFlashPulseState(active = shiftStage == ShiftLampStage.RED_FLASH)
    val flashingBorder = shiftStage == ShiftLampStage.RED_FLASH && flashState.value

    Box(
        modifier = modifier
            .fillMaxSize()
            .rpmCardChrome(palette, flashingRed = flashingBorder, radiusDp = 12.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(5.dp)) {
            val rpm = valueProvider()
            val pct = rpm?.let { (it / maxRpm).toFloat().coerceIn(0f, 1f) } ?: 0f
            val inRedline = rpm != null && rpm >= redlineRpm
            val stage = shiftStage
            val flashOn = flashState.value
            val hasShift = shiftRpm != null && shiftRpm > 0.0

            val startDeg = 145f
            val sweepDeg = 250f
            val r = minOf(size.width / 2.26f, size.height / 2.08f)
            val c = Offset(size.width / 2f, size.height * (if (hasShift) 0.58f else 0.54f))

            // 5 curved crown F1 shift LEDs (Green -> Green -> Yellow -> Yellow -> Red -> ALL flash Red)
            if (hasShift) {
                val ledAngles = floatArrayOf(-44f, -22f, 0f, 22f, 44f)
                val ledCols = arrayOf(SHIFT_GREEN, SHIFT_GREEN, SHIFT_YELLOW, SHIFT_YELLOW, SHIFT_RED)
                val ledRims = arrayOf(SHIFT_GREEN_RIM, SHIFT_GREEN_RIM, SHIFT_YELLOW_RIM, SHIFT_YELLOW_RIM, Color.White)
                val trigger = minOf(shiftRpm!!, redlineRpm.toDouble().coerceAtLeast(1500.0))
                val seqStart = trigger * 0.56
                val seqProg = ((rpm ?: 0.0) - seqStart) / (trigger - seqStart).coerceAtLeast(200.0)
                val atShift = stage == ShiftLampStage.RED_FLASH
                val ledR = (r * 0.075f).coerceIn(4.dp.toPx(), 10.dp.toPx())
                val ledOrbit = r * 1.15f
                for (i in 0 until 5) {
                    val rad = (-90f + ledAngles[i]) * PI / 180.0
                    val center = Offset(c.x + ledOrbit * cos(rad).toFloat(), c.y + ledOrbit * sin(rad).toFloat())
                    val lit = if (atShift) flashOn else (seqProg >= (i + 0.25) / 5.0)
                    if (lit) {
                        drawHighIntensityLed(
                            center = center,
                            radius = if (atShift) ledR * 1.08f else ledR,
                            ledColor = if (atShift) SHIFT_RED else ledCols[i],
                            rimColor = if (atShift) Color.White else ledRims[i],
                            bloomScale = if (atShift) 3.4f else 2.7f
                        )
                    } else {
                        drawCircle(if (atShift) SHIFT_RED_DIM else SHIFT_OFF_FILL, ledR, center)
                        drawCircle(
                            color = if (atShift) SHIFT_RED.copy(alpha = 0.45f) else SHIFT_OFF_RIM,
                            radius = ledR,
                            center = center,
                            style = Stroke(width = 1.4f)
                        )
                    }
                }
            }

            // Segmented Electric Blue arc (Red only inside redline zone)
            val segs = 32
            val segR = r * 0.92f
            val segTopLeft = Offset(c.x - segR, c.y - segR)
            val segSize = Size(segR * 2f, segR * 2f)
            val segW = (r * 0.145f).coerceIn(6f, 22f)
            val segStep = sweepDeg / segs.toFloat()
            val segSpan = segStep * 0.72f

            for (i in 0 until segs) {
                val f0 = i.toFloat() / segs.toFloat()
                val fMid = (i + 0.36f) / segs.toFloat()
                val active = f0 < pct
                val isRed = fMid * maxRpm >= redlineRpm
                val col = when {
                    active && isRed -> TorqueEvoColors.CriticalRed
                    active -> palette.accent
                    isRed -> TorqueEvoColors.CriticalRed.copy(alpha = 0.34f)
                    else -> palette.dialTrack
                }
                drawArc(
                    color = col,
                    startAngle = startDeg + segStep * i,
                    sweepAngle = segSpan,
                    useCenter = false,
                    topLeft = segTopLeft,
                    size = segSize,
                    style = Stroke(width = segW, cap = StrokeCap.Butt)
                )
            }

            // 0..maxThousands numerals
            val numSize = (r * 0.148f).coerceIn(9.5.dp.toPx(), 17.dp.toPx())
            for (n in 0..maxThousands) {
                val rad = (startDeg + sweepDeg * (n.toFloat() / maxThousands.toFloat())) * PI / 180.0
                val nr = r * 0.71f
                drawCenteredCanvasText(
                    text = "$n",
                    cx = c.x + nr * cos(rad).toFloat(),
                    cy = c.y + nr * sin(rad).toFloat(),
                    paint = numPaint,
                    textSizePx = numSize,
                    color = if (n * 1000 >= redlineRpm) TorqueEvoColors.CriticalRed else palette.textSecondary
                )
            }

            // Bold centered RPM readout
            val rpmText = rpm?.roundToInt()?.toString() ?: "—"
            drawCenteredCanvasText(
                text = rpmText,
                cx = c.x,
                cy = c.y + r * 0.05f,
                paint = numPaint,
                textSizePx = (r * 0.38f).coerceIn(14.dp.toPx(), 36.dp.toPx()),
                color = if (inRedline) TorqueEvoColors.CriticalRed else palette.textPrimary
            )
            drawCenteredCanvasText(
                text = "RPM",
                cx = c.x,
                cy = c.y + r * 0.35f,
                paint = subPaint,
                textSizePx = (r * 0.145f).coerceIn(10.dp.toPx(), 17.dp.toPx()),
                color = palette.labelColor.copy(alpha = 0.90f)
            )
        }
    }
}

/**
 * Option C · Track Chrono (`WidgetDisplayType.RPM_TACH_CHRONO`):
 * GT3 twin-track dial with translucent Electric Blue inner glow wedge, crisp outer rim + permanent
 * Red redline, floating outer-ring needle (never overlaps center digits), and dark open-center
 * chrono ring with the Green -> Yellow -> Flashing Red Shift Dot tucked inside at top-right of RPM.
 */
@Composable
fun RpmTachChronoWidget(
    valueProvider: () -> Double?,
    redlineRpm: Int,
    shiftRpm: Double?,
    modifier: Modifier = Modifier
) {
    val palette = LocalHudVisualStyle.current
    val maxRpm = remember(redlineRpm) { rpmDialMaxForRedline(redlineRpm) }
    val maxThousands = remember(maxRpm) { (maxRpm / 1000.0).roundToInt().coerceAtLeast(6) }
    val numPaint = rememberCanvasTextPaint(bold = true)
    val subPaint = rememberCanvasTextPaint(bold = true)

    val shiftStage by remember(valueProvider, shiftRpm, redlineRpm) {
        derivedStateOf { computeShiftLampStage(valueProvider(), shiftRpm, redlineRpm) }
    }
    val flashState = rememberFlashPulseState(active = shiftStage == ShiftLampStage.RED_FLASH)
    val flashingBorder = shiftStage == ShiftLampStage.RED_FLASH && flashState.value

    Box(
        modifier = modifier
            .fillMaxSize()
            .rpmCardChrome(palette, flashingRed = flashingBorder, radiusDp = 12.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(5.dp)) {
            val rpm = valueProvider()
            val pct = rpm?.let { (it / maxRpm).toFloat().coerceIn(0f, 1f) } ?: 0f
            val redPct = (redlineRpm / maxRpm).toFloat().coerceIn(0.4f, 0.96f)
            val inRedline = rpm != null && rpm >= redlineRpm
            val stage = shiftStage
            val flashOn = flashState.value
            val hasShift = shiftRpm != null && shiftRpm > 0.0
            val activeAccent = if (inRedline) TorqueEvoColors.CriticalRed else palette.accent

            val startDeg = 135f
            val sweepDeg = 270f
            val r = minOf(size.width / 2.12f, size.height / 2.02f)
            val c = Offset(size.width / 2f, size.height * 0.53f)
            val innerR = r * 0.56f
            val midBandR = (r + innerR) * 0.5f
            val bandW = (r - innerR - 3f).coerceAtLeast(4f)

            // Outer bezel hairline
            val outerBezelR = r + (r * 0.035f)
            drawArc(
                color = Color(0x30FFFFFF),
                startAngle = startDeg,
                sweepAngle = sweepDeg,
                useCenter = false,
                topLeft = Offset(c.x - outerBezelR, c.y - outerBezelR),
                size = Size(outerBezelR * 2f, outerBezelR * 2f),
                style = Stroke(width = 1.8f)
            )

            // Translucent Electric Blue inner glow wedge (Red past redline)
            val bandTopLeft = Offset(c.x - midBandR, c.y - midBandR)
            val bandSize = Size(midBandR * 2f, midBandR * 2f)
            val blueEnd = minOf(pct, redPct)
            if (blueEnd > 0.002f) {
                drawArc(
                    color = palette.accent.copy(alpha = 0.22f),
                    startAngle = startDeg,
                    sweepAngle = sweepDeg * blueEnd,
                    useCenter = false,
                    topLeft = bandTopLeft,
                    size = bandSize,
                    style = Stroke(width = bandW, cap = StrokeCap.Butt)
                )
            }
            if (pct > redPct) {
                drawArc(
                    color = TorqueEvoColors.CriticalRed.copy(alpha = 0.28f),
                    startAngle = startDeg + sweepDeg * redPct,
                    sweepAngle = sweepDeg * (pct - redPct),
                    useCenter = false,
                    topLeft = bandTopLeft,
                    size = bandSize,
                    style = Stroke(width = bandW, cap = StrokeCap.Butt)
                )
            }

            // Permanent Red redline rim + crisp active rim
            val rimR = r - (r * 0.015f)
            val rimTopLeft = Offset(c.x - rimR, c.y - rimR)
            val rimSize = Size(rimR * 2f, rimR * 2f)
            val rimW = (r * 0.062f).coerceIn(3.5f, 9f)
            drawArc(
                color = TorqueEvoColors.CriticalRed.copy(alpha = 0.66f),
                startAngle = startDeg + sweepDeg * redPct,
                sweepAngle = sweepDeg * (1f - redPct),
                useCenter = false,
                topLeft = rimTopLeft,
                size = rimSize,
                style = Stroke(width = rimW, cap = StrokeCap.Butt)
            )
            drawAccentWithRedlineArc(rimTopLeft, rimSize, startDeg, sweepDeg, pct, redPct, rimW * 0.85f, StrokeCap.Butt, palette.accent)

            // Shift notch
            if (hasShift) {
                val spPct = (shiftRpm!! / maxRpm).toFloat().coerceIn(0.05f, 0.99f)
                val smRad = (startDeg + sweepDeg * spPct) * PI / 180.0
                drawLine(
                    color = SHIFT_YELLOW,
                    start = Offset(c.x + (r - rimW * 1.4f) * cos(smRad).toFloat(), c.y + (r - rimW * 1.4f) * sin(smRad).toFloat()),
                    end = Offset(c.x + (r + rimW * 0.9f) * cos(smRad).toFloat(), c.y + (r + rimW * 0.9f) * sin(smRad).toFloat()),
                    strokeWidth = (r * 0.022f).coerceIn(1.8f, 3.5f),
                    cap = StrokeCap.Round
                )
            }

            // Precision ticks + 0..maxThousands numerals inside band
            val totalTicks = maxThousands * 5
            val numSize = (r * 0.145f).coerceIn(9.5.dp.toPx(), 17.dp.toPx())
            for (i in 0..totalTicks) {
                val f = i.toFloat() / totalTicks.toFloat()
                val rad = (startDeg + sweepDeg * f) * PI / 180.0
                val major = i % 5 == 0
                val isRed = f * maxRpm >= redlineRpm
                val r0 = if (major) r * 0.86f else r * 0.91f
                val r1 = r * 0.97f
                drawLine(
                    color = if (isRed) TorqueEvoColors.CriticalRed else if (major) Color(0xE0FFFFFF) else Color(0x45FFFFFF),
                    start = Offset(c.x + r0 * cos(rad).toFloat(), c.y + r0 * sin(rad).toFloat()),
                    end = Offset(c.x + r1 * cos(rad).toFloat(), c.y + r1 * sin(rad).toFloat()),
                    strokeWidth = if (major) (r * 0.02f).coerceIn(1.5f, 3f) else 1.1f
                )
                if (major) {
                    val nr = r * 0.74f
                    drawCenteredCanvasText(
                        text = "${i / 5}",
                        cx = c.x + nr * cos(rad).toFloat(),
                        cy = c.y + nr * sin(rad).toFloat(),
                        paint = numPaint,
                        textSizePx = numSize,
                        color = if (isRed) TorqueEvoColors.CriticalRed else palette.labelColor
                    )
                }
            }

            // Floating outer-ring needle (starts at innerR so center RPM is 100% unobstructed)
            val nRad = (startDeg + sweepDeg * pct) * PI / 180.0
            drawLine(
                color = if (inRedline) TorqueEvoColors.CriticalRed else Color.White,
                start = Offset(c.x + (innerR + 2f) * cos(nRad).toFloat(), c.y + (innerR + 2f) * sin(nRad).toFloat()),
                end = Offset(c.x + (r - 2f) * cos(nRad).toFloat(), c.y + (r - 2f) * sin(nRad).toFloat()),
                strokeWidth = (r * 0.036f).coerceIn(2.6f, 5.5f),
                cap = StrokeCap.Round
            )

            // Dark open-center chrono ring
            drawCircle(color = Color(0xFF05080E), radius = innerR, center = c)
            drawCircle(
                color = activeAccent,
                radius = innerR,
                center = c,
                style = Stroke(width = (r * 0.022f).coerceIn(1.8f, 3.2f))
            )

            // Inside center circle: "RPM" + Shift Dot at top-right of RPM value (matching Option D)
            val labelY = c.y - innerR * 0.42f
            val chronoRpmLabelPx = (innerR * 0.27f).coerceIn(9.5.dp.toPx(), 16.dp.toPx())
            if (hasShift) {
                drawCenteredCanvasText(
                    text = "RPM",
                    cx = c.x - innerR * 0.18f,
                    cy = labelY,
                    paint = subPaint,
                    textSizePx = chronoRpmLabelPx,
                    color = palette.labelColor.copy(alpha = 0.90f)
                )
                drawShiftLampDot(
                    center = Offset(c.x + innerR * 0.34f, labelY),
                    radius = (innerR * 0.15f).coerceIn(4.5.dp.toPx(), 10.5.dp.toPx()),
                    stage = stage,
                    flashOn = flashOn
                )
            } else {
                drawCenteredCanvasText(
                    text = "RPM",
                    cx = c.x,
                    cy = labelY,
                    paint = subPaint,
                    textSizePx = chronoRpmLabelPx,
                    color = palette.labelColor.copy(alpha = 0.90f)
                )
            }

            // Bold center RPM digits inside the chrono ring
            val rpmText = rpm?.roundToInt()?.toString() ?: "—"
            drawCenteredCanvasText(
                text = rpmText,
                cx = c.x,
                cy = c.y + innerR * 0.18f,
                paint = numPaint,
                textSizePx = (innerR * 0.56f).coerceIn(13.dp.toPx(), 30.dp.toPx()),
                color = if (inRedline) TorqueEvoColors.CriticalRed else palette.textPrimary
            )
        }
    }
}

/**
 * Option D · Compact OLED Halo (`WidgetDisplayType.RPM_TACH_HALO`):
 * Minimalist twin-ring Electric Blue halo + permanent Red redline + numbered ticks +
 * giant centered RPM readout + integrated Green -> Yellow -> Flashing Red Shift Dot beside "RPM".
 */
@Composable
fun RpmTachHaloWidget(
    valueProvider: () -> Double?,
    redlineRpm: Int,
    shiftRpm: Double?,
    modifier: Modifier = Modifier
) {
    val palette = LocalHudVisualStyle.current
    val maxRpm = remember(redlineRpm) { rpmDialMaxForRedline(redlineRpm) }
    val maxThousands = remember(maxRpm) { (maxRpm / 1000.0).roundToInt().coerceAtLeast(6) }
    val numPaint = rememberCanvasTextPaint(bold = true)
    val subPaint = rememberCanvasTextPaint(bold = true)

    val shiftStage by remember(valueProvider, shiftRpm, redlineRpm) {
        derivedStateOf { computeShiftLampStage(valueProvider(), shiftRpm, redlineRpm) }
    }
    val flashState = rememberFlashPulseState(active = shiftStage == ShiftLampStage.RED_FLASH)
    val flashingBorder = shiftStage == ShiftLampStage.RED_FLASH && flashState.value

    Box(
        modifier = modifier
            .fillMaxSize()
            .rpmCardChrome(palette, flashingRed = flashingBorder, radiusDp = 12.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(5.dp)) {
            val rpm = valueProvider()
            val pct = rpm?.let { (it / maxRpm).toFloat().coerceIn(0f, 1f) } ?: 0f
            val redPct = (redlineRpm / maxRpm).toFloat().coerceIn(0.4f, 0.96f)
            val inRedline = rpm != null && rpm >= redlineRpm
            val stage = shiftStage
            val flashOn = flashState.value
            val hasShift = shiftRpm != null && shiftRpm > 0.0

            val startDeg = 135f
            val sweepDeg = 270f
            val r = minOf(size.width / 2.12f, size.height / 2.00f)
            val c = Offset(size.width / 2f, size.height * 0.53f)

            // Thin outer halo guide
            val outerR = r * 1.03f
            drawArc(
                color = Color(0x28FFFFFF),
                startAngle = startDeg,
                sweepAngle = sweepDeg,
                useCenter = false,
                topLeft = Offset(c.x - outerR, c.y - outerR),
                size = Size(outerR * 2f, outerR * 2f),
                style = Stroke(width = 2f, cap = StrokeCap.Round)
            )

            // Main inner halo track + permanent redline + Electric Blue sweep
            val haloR = r * 0.90f
            val haloTopLeft = Offset(c.x - haloR, c.y - haloR)
            val haloSize = Size(haloR * 2f, haloR * 2f)
            val haloW = (r * 0.135f).coerceIn(6f, 20f)
            drawArc(palette.dialTrack, startDeg, sweepDeg, false, haloTopLeft, haloSize, style = Stroke(haloW, cap = StrokeCap.Round))
            drawArc(
                TorqueEvoColors.CriticalRed.copy(alpha = 0.48f),
                startDeg + sweepDeg * redPct,
                sweepDeg * (1f - redPct),
                false,
                haloTopLeft,
                haloSize,
                style = Stroke(haloW, cap = StrokeCap.Round)
            )
            drawAccentWithRedlineArc(haloTopLeft, haloSize, startDeg, sweepDeg, pct, redPct, haloW, StrokeCap.Round, palette.accent)

            // 0..maxThousands numerals
            val numSize = (r * 0.145f).coerceIn(9.5.dp.toPx(), 17.dp.toPx())
            for (n in 0..maxThousands) {
                val rad = (startDeg + sweepDeg * (n.toFloat() / maxThousands.toFloat())) * PI / 180.0
                val nr = r * 0.69f
                drawCenteredCanvasText(
                    text = "$n",
                    cx = c.x + nr * cos(rad).toFloat(),
                    cy = c.y + nr * sin(rad).toFloat(),
                    paint = numPaint,
                    textSizePx = numSize,
                    color = if (n * 1000 >= redlineRpm) TorqueEvoColors.CriticalRed else palette.textSecondary
                )
            }

            // "RPM" + integrated Shift Dot beside it (no shift text label)
            val labelY = c.y - r * 0.28f
            val haloRpmLabelPx = (r * 0.155f).coerceIn(10.dp.toPx(), 18.dp.toPx())
            if (hasShift) {
                drawCenteredCanvasText(
                    text = "RPM",
                    cx = c.x - r * 0.12f,
                    cy = labelY,
                    paint = subPaint,
                    textSizePx = haloRpmLabelPx,
                    color = palette.labelColor
                )
                drawShiftLampDot(
                    center = Offset(c.x + r * 0.25f, labelY),
                    radius = (r * 0.092f).coerceIn(4.5.dp.toPx(), 11.dp.toPx()),
                    stage = stage,
                    flashOn = flashOn
                )
            } else {
                drawCenteredCanvasText(
                    text = "RPM",
                    cx = c.x,
                    cy = labelY,
                    paint = subPaint,
                    textSizePx = haloRpmLabelPx,
                    color = palette.labelColor
                )
            }

            // Giant centered RPM readout
            val rpmText = rpm?.roundToInt()?.toString() ?: "—"
            drawCenteredCanvasText(
                text = rpmText,
                cx = c.x,
                cy = c.y + r * 0.14f,
                paint = numPaint,
                textSizePx = (r * 0.42f).coerceIn(15.dp.toPx(), 38.dp.toPx()),
                color = if (inRedline) TorqueEvoColors.CriticalRed else palette.textPrimary
            )
        }
    }
}
