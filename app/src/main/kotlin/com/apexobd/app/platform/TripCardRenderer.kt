package com.apexobd.app.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import com.apexobd.app.data.db.TelemetrySampleEntity
import com.apexobd.app.data.db.TripSessionEntity
import com.apexobd.app.data.db.VehicleProfileEntity
import com.apexobd.app.data.prefs.ShareCardBackgroundMode
import com.apexobd.app.data.prefs.ShareCardMetric
import com.apexobd.app.data.prefs.TripCardConfig
import com.apexobd.app.ui.components.ApexBrandLogo
import com.apexobd.core.model.FuelType
import com.apexobd.core.model.UnitPreferences
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Generates high-resolution Strava-style PNG route share cards overlaying GPS track,
 * key trip telemetry, and vehicle identity onto an attached local car photo, plain OLED dark,
 * or true alpha-transparent canvas.
 */
object TripCardRenderer {

    private const val CARD_W = 1080
    private const val CARD_H = 1350

    fun copyVehiclePhotoToInternal(context: Context, sourceUri: Uri, vehicleTag: String): String? {
        return runCatching {
            val resolver = context.contentResolver
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(sourceUri)?.use { BitmapFactory.decodeStream(it, null, boundsOpts) }
            val maxDim = max(boundsOpts.outWidth, boundsOpts.outHeight).coerceAtLeast(1)
            val sampleSize = if (maxDim > 1600) (maxDim / 1200).coerceAtLeast(1) else 1

            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bmp = resolver.openInputStream(sourceUri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
                ?: return null

            val photosDir = File(context.filesDir, "vehicle_photos").apply { mkdirs() }
            val outFile = File(photosDir, "car_${vehicleTag}_${System.currentTimeMillis()}.jpg")
            FileOutputStream(outFile).use { fos ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, fos)
            }
            bmp.recycle()
            outFile.absolutePath
        }.getOrNull()
    }

    fun renderTripCardToPngFile(
        context: Context,
        session: TripSessionEntity,
        samples: List<TelemetrySampleEntity>,
        vehicle: VehicleProfileEntity?,
        unitPrefs: UnitPreferences,
        fuelType: FuelType,
        selectedMetrics: List<ShareCardMetric> = ShareCardMetric.DEFAULT_SELECTION,
        config: TripCardConfig = TripCardConfig(selectedMetrics = selectedMetrics),
        precomputedStats: TripShareStatsCalculator.AggregatedStats? = null
    ): File {
        val bmp = renderTripCardBitmap(session, samples, vehicle, unitPrefs, fuelType, config.selectedMetrics, config, precomputedStats)
        val outFile = TripExporter.exportCacheFile(context, "apex_route_${session.id}.png")
        FileOutputStream(outFile).use { fos ->
            bmp.compress(Bitmap.CompressFormat.PNG, 95, fos)
        }
        bmp.recycle()
        return outFile
    }

    fun renderTripCardBitmap(
        session: TripSessionEntity,
        samples: List<TelemetrySampleEntity>,
        vehicle: VehicleProfileEntity?,
        unitPrefs: UnitPreferences,
        fuelType: FuelType,
        selectedMetrics: List<ShareCardMetric> = ShareCardMetric.DEFAULT_SELECTION,
        config: TripCardConfig = TripCardConfig(selectedMetrics = selectedMetrics),
        precomputedStats: TripShareStatsCalculator.AggregatedStats? = null
    ): Bitmap {
        val bmp = Bitmap.createBitmap(CARD_W, CARD_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        drawBackground(canvas, vehicle?.photoUri, config.backgroundMode)
        drawTopHeader(canvas, session, vehicle, config)
        drawRouteTrace(canvas, samples, config)
        drawBottomMetricsCard(canvas, session, samples, unitPrefs, fuelType, config, precomputedStats)

        return bmp
    }

    private fun drawBackground(
        canvas: Canvas,
        photoPath: String?,
        backgroundMode: ShareCardBackgroundMode
    ) {
        if (backgroundMode == ShareCardBackgroundMode.TRANSPARENT) {
            return
        }
        val w = CARD_W.toFloat()
        val h = CARD_H.toFloat()
        val photoBmp = if (backgroundMode == ShareCardBackgroundMode.CAR_PHOTO) {
            photoPath?.takeIf { File(it).exists() }?.let { path ->
                runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
            }
        } else null

        if (photoBmp != null) {
            val scale = max(w / photoBmp.width.toFloat(), h / photoBmp.height.toFloat())
            val dx = (w - photoBmp.width * scale) / 2f
            val dy = (h - photoBmp.height * scale) / 2f
            val matrix = Matrix().apply {
                setScale(scale, scale)
                postTranslate(dx, dy)
            }
            canvas.drawBitmap(photoBmp, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
            photoBmp.recycle()

            val scrimPaint = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, h,
                    intArrayOf(Color.argb(215, 4, 7, 13), Color.argb(95, 5, 9, 16), Color.argb(235, 3, 6, 11)),
                    floatArrayOf(0.0f, 0.45f, 1.0f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, w, h, scrimPaint)
        } else {
            val bgPaint = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, h,
                    intArrayOf(Color.rgb(5, 8, 14), Color.rgb(9, 16, 28), Color.rgb(4, 6, 10)),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, w, h, bgPaint)

            val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(32, 0, 168, 255)
                strokeWidth = 2f
            }
            var gx = 90f
            while (gx < w) {
                canvas.drawLine(gx, 0f, gx, h, gridPaint)
                gx += 108f
            }
            var gy = 135f
            while (gy < h) {
                canvas.drawLine(0f, gy, w, gy, gridPaint)
                gy += 108f
            }
        }
    }

    private fun drawTopHeader(
        canvas: Canvas,
        session: TripSessionEntity,
        vehicle: VehicleProfileEntity?,
        config: TripCardConfig
    ) {
        val isTrans = config.backgroundMode == ShareCardBackgroundMode.TRANSPARENT
        val dateOnly = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(session.startedAtMs))
        val timeOnly = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(session.startedAtMs))
        val dateTimeStr = when {
            config.showDate && config.showTime -> "$dateOnly · $timeOnly"
            config.showDate -> dateOnly
            config.showTime -> timeOnly
            else -> ""
        }

        val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(203, 213, 225)
            textSize = 26f
            textAlign = Paint.Align.RIGHT
            if (isTrans) setShadowLayer(8f, 0f, 2f, Color.argb(210, 0, 0, 0))
        }
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 54f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            if (isTrans) setShadowLayer(10f, 0f, 2f, Color.argb(220, 0, 0, 0))
        }
        val specPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(147, 197, 253)
            textSize = 30f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            if (isTrans) setShadowLayer(8f, 0f, 2f, Color.argb(210, 0, 0, 0))
        }

        if (config.showAppMark) {
            ApexBrandLogo.drawOnAndroidCanvas(canvas, left = 64f, top = 56f, height = 42f)
        }
        if (dateTimeStr.isNotEmpty()) {
            canvas.drawText(dateTimeStr, CARD_W - 64f, 88f, datePaint)
        }

        var nextY = if (config.showAppMark || dateTimeStr.isNotEmpty()) 166f else 108f
        if (config.showVehicleName) {
            canvas.drawText(vehicle?.name ?: "My Vehicle", 64f, nextY, titlePaint)
            nextY += 46f
        }
        if (config.showVehicleSpecs && vehicle != null) {
            canvas.drawText(vehicle.specSummaryLine(), 64f, nextY, specPaint)
        }
    }

    private fun drawRouteTrace(
        canvas: Canvas,
        samples: List<TelemetrySampleEntity>,
        config: TripCardConfig
    ) {
        if (!config.showRouteLine && !config.showStartEndMarkers) return

        val pts = samples.mapNotNull { s ->
            if (s.latitude != null && s.longitude != null && s.latitude != 0.0 && s.longitude != 0.0) {
                s.latitude to s.longitude
            } else null
        }

        val hasTop = config.showAppMark || config.showVehicleName || config.showVehicleSpecs || config.showDate || config.showTime
        val topY = if (hasTop) 270f else 120f
        val botY = if (config.showMetrics) 900f else (CARD_H - 120f)
        val box = RectF(100f, topY, CARD_W - 100f, botY)
        if (pts.size < 2) {
            val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(140, 203, 213, 225)
                textSize = 30f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("GPS trail unavailable for this session", box.centerX(), box.centerY(), emptyPaint)
            return
        }

        val minLat = pts.minOf { it.first }
        val maxLat = pts.maxOf { it.first }
        val minLon = pts.minOf { it.second }
        val maxLon = pts.maxOf { it.second }
        val midLatRad = Math.toRadians((minLat + maxLat) / 2.0)
        val lonCorrection = cos(midLatRad).coerceIn(0.3, 1.0)

        val latSpan = (maxLat - minLat).coerceAtLeast(0.0005)
        val lonSpan = ((maxLon - minLon) * lonCorrection).coerceAtLeast(0.0005)
        val scale = min(box.width() / lonSpan, box.height() / latSpan)
        val drawW = (lonSpan * scale).toFloat()
        val drawH = (latSpan * scale).toFloat()
        val offX = box.left + (box.width() - drawW) / 2f
        val offY = box.top + (box.height() - drawH) / 2f

        fun toPoint(lat: Double, lon: Double): Pair<Float, Float> {
            val x = offX + (((lon - minLon) * lonCorrection) * scale).toFloat()
            val y = offY + drawH - ((lat - minLat) * scale).toFloat()
            return x to y
        }

        if (config.showRouteLine) {
            val path = Path()
            pts.forEachIndexed { idx, (lat, lon) ->
                val (px, py) = toPoint(lat, lon)
                if (idx == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }

            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = Color.argb(190, 0, 168, 255)
                strokeWidth = 22f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                maskFilter = BlurMaskFilter(12f, BlurMaskFilter.Blur.NORMAL)
            }
            val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = Color.WHITE
                strokeWidth = 10f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            canvas.drawPath(path, glowPaint)
            canvas.drawPath(path, corePaint)
        }

        if (config.showStartEndMarkers) {
            val (sx, sy) = toPoint(pts.first().first, pts.first().second)
            val (ex, ey) = toPoint(pts.last().first, pts.last().second)
            val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = Color.WHITE
                strokeWidth = 5f
            }
            canvas.drawCircle(sx, sy, 13f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(34, 197, 94) })
            canvas.drawCircle(sx, sy, 13f, ringPaint)
            canvas.drawCircle(ex, ey, 14f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 168, 255) })
            canvas.drawCircle(ex, ey, 14f, ringPaint)
        }
    }

    private fun drawBottomMetricsCard(
        canvas: Canvas,
        session: TripSessionEntity,
        samples: List<TelemetrySampleEntity>,
        unitPrefs: UnitPreferences,
        fuelType: FuelType,
        config: TripCardConfig,
        precomputedStats: TripShareStatsCalculator.AggregatedStats?
    ) {
        if (!config.showMetrics) return

        val rendered = if (precomputedStats != null) {
            TripShareStatsCalculator.computeMetricsFromStats(
                selectedMetrics = config.selectedMetrics,
                session = session,
                stats = precomputedStats,
                unitPrefs = unitPrefs,
                fuelType = fuelType
            )
        } else {
            TripShareStatsCalculator.computeMetrics(
                selectedMetrics = config.selectedMetrics,
                session = session,
                samples = samples,
                unitPrefs = unitPrefs,
                fuelType = fuelType
            )
        }
        if (rendered.isEmpty()) return

        val rows = if (rendered.size <= 3) 1 else 2
        val cardTop = if (rows == 1) 1094f else 956f
        val cardRect = RectF(56f, cardTop, CARD_W - 56f, CARD_H - 56f)

        if (config.showMetricsPanelBg) {
            val cardBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(212, 7, 11, 18) }
            val cardBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = Color.argb(95, 255, 255, 255)
                strokeWidth = 2.5f
            }
            canvas.drawRoundRect(cardRect, 28f, 28f, cardBg)
            canvas.drawRoundRect(cardRect, 28f, 28f, cardBorder)
        }

        val addShadow = !config.showMetricsPanelBg || config.backgroundMode == ShareCardBackgroundMode.TRANSPARENT
        val lblPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (addShadow) Color.rgb(203, 213, 225) else Color.rgb(148, 163, 184)
            textSize = 23f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.05f
            if (addShadow) setShadowLayer(8f, 0f, 2f, Color.argb(220, 0, 0, 0))
        }
        val valPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 36f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            if (addShadow) setShadowLayer(10f, 0f, 2f, Color.argb(230, 0, 0, 0))
        }

        val cols = when (rendered.size) {
            1 -> 1
            2, 4 -> 2
            else -> 3
        }
        val colW = (cardRect.width() - 64f) / cols.toFloat()
        rendered.forEachIndexed { idx, item ->
            val col = idx % cols
            val row = idx / cols
            val x = cardRect.left + 38f + col * colW
            val yLabel = cardRect.top + 64f + row * 142f
            canvas.drawText(item.label, x, yLabel, lblPaint)
            val valColor = when (item.metric) {
                ShareCardMetric.DISTANCE -> Color.rgb(56, 189, 248)
                ShareCardMetric.AVG_ECONOMY,
                ShareCardMetric.MOVING_KPL,
                ShareCardMetric.TRIP_EFFICIENCY -> Color.rgb(74, 222, 128)
                else -> Color.WHITE
            }
            valPaint.color = valColor
            var fittedSize = 36f
            valPaint.textSize = fittedSize
            val maxTextW = colW - 22f
            while (fittedSize > 25f && valPaint.measureText(item.value) > maxTextW) {
                fittedSize -= 1.5f
                valPaint.textSize = fittedSize
            }
            canvas.drawText(item.value, x, yLabel + 54f, valPaint)
        }
    }
}
