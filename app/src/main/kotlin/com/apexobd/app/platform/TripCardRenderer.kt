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
import com.apexobd.app.data.prefs.ShareCardAspectRatio
import com.apexobd.app.data.prefs.ShareCardBackgroundMode
import com.apexobd.app.data.prefs.ShareCardMetric
import com.apexobd.app.data.prefs.TripCardConfig
import com.apexobd.app.ui.components.ApexBrandLogo
import com.apexobd.core.model.FuelType
import com.apexobd.core.model.UnitPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Generates high-resolution Strava-style PNG route cards overlaying GPS track,
 * optional surrounding map road network with seamless fade and adjustable opacity,
 * telemetry metrics, and vehicle identity across `1:1`, `4:3`, and `9:16` layouts.
 */
object TripCardRenderer {

    data class RoadContextLayer(
        val roadsAlphaBitmap: Bitmap,
        val projectedPoints: List<Pair<Float, Float>>,
        val boxWidth: Int,
        val boxHeight: Int,
        val mainRoadsOnly: Boolean = true
    )

    private data class CardBox(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
        fun toRectF(): RectF = RectF(left, top, right, bottom)
    }

    private data class CardLayoutGeometry(
        val aspectRatio: ShareCardAspectRatio,
        val width: Int,
        val height: Int,
        val padX: Float,
        val padTop: Float,
        val padBottom: Float,
        val routeBox: CardBox,
        val metricsRect: CardBox,
        val rowCount: Int
    )

    fun copyVehiclePhotoToInternal(context: Context, sourceUri: Uri, vehicleTag: String): String? =
        copyImageToInternalDir(context, sourceUri, folderName = "vehicle_photos", filePrefix = "car_$vehicleTag")

    fun copyCardBackgroundPhotoToInternal(context: Context, sourceUri: Uri): String? =
        copyImageToInternalDir(context, sourceUri, folderName = "card_backgrounds", filePrefix = "card_bg")

    private fun copyImageToInternalDir(
        context: Context,
        sourceUri: Uri,
        folderName: String,
        filePrefix: String
    ): String? {
        return runCatching {
            val resolver = context.contentResolver
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(sourceUri)?.use { BitmapFactory.decodeStream(it, null, boundsOpts) }
            val maxDim = max(boundsOpts.outWidth, boundsOpts.outHeight).coerceAtLeast(1)
            val sampleSize = if (maxDim > 1800) (maxDim / 1400).coerceAtLeast(1) else 1

            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bmp = resolver.openInputStream(sourceUri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
                ?: return null

            val outDir = File(context.filesDir, folderName).apply { mkdirs() }
            val outFile = File(outDir, "${filePrefix}_${System.currentTimeMillis()}.jpg")
            FileOutputStream(outFile).use { fos ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 92, fos)
            }
            bmp.recycle()
            outFile.absolutePath
        }.getOrNull()
    }

    /** Computes exact `(width, height)` of the route canvas region for caching surrounding map snapshots. */
    fun routeCanvasSizeForConfig(config: TripCardConfig, metricCount: Int = config.selectedMetrics.size): Pair<Int, Int> {
        val geom = computeLayoutGeometry(config, hasMetrics = config.showMetrics && metricCount > 0, metricCount = metricCount)
        return geom.routeBox.width.toInt().coerceAtLeast(160) to geom.routeBox.height.toInt().coerceAtLeast(160)
    }

    /**
     * Captures a high-contrast surrounding road network snapshot fitted to the tracked route bounds
     * and converts luminance into `60%` max opacity alpha with soft border feathering so it overlays
     * cleanly onto photos, dark canvas, or transparent backgrounds.
     */
    suspend fun captureSurroundingRoadsLayer(
        context: Context,
        samples: List<TelemetrySampleEntity>,
        boxWidth: Int,
        boxHeight: Int,
        activePmtilesPath: String?,
        mainRoadsOnly: Boolean = true
    ): RoadContextLayer? {
        val allPts = extractValidGpsPoints(samples)
        if (allPts.size < 2 || boxWidth < 120 || boxHeight < 120) return null
        val sampledPts = downsamplePoints(allPts, maxPoints = 900)

        val minLat = sampledPts.minOf { it.first }
        val maxLat = sampledPts.maxOf { it.first }
        val minLon = sampledPts.minOf { it.second }
        val maxLon = sampledPts.maxOf { it.second }

        val latSpan = (maxLat - minLat).coerceAtLeast(0.0028)
        val lonSpan = (maxLon - minLon).coerceAtLeast(0.0028)
        val latPad = latSpan * 0.20
        val lonPad = lonSpan * 0.20
        val bounds = LatLngBounds.Builder()
            .include(LatLng((minLat - latPad).coerceIn(-85.0, 85.0), (minLon - lonPad).coerceIn(-180.0, 180.0)))
            .include(LatLng((maxLat + latPad).coerceIn(-85.0, 85.0), (maxLon + lonPad).coerceIn(-180.0, 180.0)))
            .build()

        val styleJson = OfflineMapManager.buildRoadsMaskStyleJson(activePmtilesPath, mainRoadsOnly = mainRoadsOnly)

        val rawCapture = withTimeoutOrNull(4500L) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<Pair<Bitmap, List<Pair<Float, Float>>>?> { cont ->
                    runCatching { MapLibre.getInstance(context.applicationContext) }
                    val snapshotter = runCatching {
                        val options = MapSnapshotter.Options(boxWidth, boxHeight)
                            .withLogo(false)
                            .withAttribution(false)
                            .withPixelRatio(1f)
                            .withPadding(18, 18, 18, 18)
                            .withRegion(bounds)
                            .withStyleBuilder(Style.Builder().fromJson(styleJson))
                        MapSnapshotter(context.applicationContext, options)
                    }.getOrNull()

                    if (snapshotter == null) {
                        if (cont.isActive) cont.resume(null)
                        return@suspendCancellableCoroutine
                    }

                    cont.invokeOnCancellation { runCatching { snapshotter.cancel() } }

                    snapshotter.start(
                        { snapshot ->
                            val projected = sampledPts.map { (lat, lon) ->
                                val pt = snapshot.pixelForLatLng(LatLng(lat, lon))
                                pt.x to pt.y
                            }
                            if (cont.isActive) cont.resume(snapshot.bitmap to projected)
                        },
                        { _ ->
                            if (cont.isActive) cont.resume(null)
                        }
                    )
                }
            }
        } ?: return null

        return withContext(Dispatchers.Default) {
            val (rawBmp, projectedPts) = rawCapture
            val alphaBmp = convertRoadsMaskToAlphaBitmap(rawBmp)
            if (rawBmp !== alphaBmp && !rawBmp.isRecycled) {
                rawBmp.recycle()
            }
            RoadContextLayer(
                roadsAlphaBitmap = alphaBmp,
                projectedPoints = projectedPts,
                boxWidth = alphaBmp.width,
                boxHeight = alphaBmp.height,
                mainRoadsOnly = mainRoadsOnly
            )
        }
    }

    private fun convertRoadsMaskToAlphaBitmap(src: Bitmap): Bitmap {
        val w = src.width.coerceAtLeast(1)
        val h = src.height.coerceAtLeast(1)
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val cx = (w - 1) * 0.5f
        val cy = (h - 1) * 0.5f
        for (y in 0 until h) {
            val ny = if (cy > 0f) abs(y - cy) / cy else 0f
            val rowOff = y * w
            for (x in 0 until w) {
                val c = pixels[rowOff + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val luma = max(r, max(g, b))
                if (luma <= 10) {
                    pixels[rowOff + x] = 0
                } else {
                    val nx = if (cx > 0f) abs(x - cx) / cx else 0f
                    val radial = hypot(nx, ny)
                    val t = ((radial - 0.34f) / (0.96f - 0.34f)).coerceIn(0f, 1f)
                    val smoothFade = 1f - (t * t * (3f - 2f * t))
                    val vignette = smoothFade * smoothFade
                    // Full 0..255 luma mask with totally seamless elliptical vignette; user opacity scales at draw time
                    val alpha = ((luma / 255f) * vignette * 255f).toInt().coerceIn(0, 255)
                    pixels[rowOff + x] = Color.argb(alpha, r, g, b)
                }
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
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
        precomputedStats: TripShareStatsCalculator.AggregatedStats? = null,
        roadContextLayer: RoadContextLayer? = null
    ): File {
        val bmp = renderTripCardBitmap(
            session = session,
            samples = samples,
            vehicle = vehicle,
            unitPrefs = unitPrefs,
            fuelType = fuelType,
            selectedMetrics = config.selectedMetrics,
            config = config,
            precomputedStats = precomputedStats,
            roadContextLayer = roadContextLayer
        )
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
        precomputedStats: TripShareStatsCalculator.AggregatedStats? = null,
        roadContextLayer: RoadContextLayer? = null
    ): Bitmap {
        val renderedMetrics = if (config.showMetrics) {
            if (precomputedStats != null) {
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
        } else {
            emptyList()
        }

        val geom = computeLayoutGeometry(
            config = config,
            hasMetrics = renderedMetrics.isNotEmpty(),
            metricCount = renderedMetrics.size
        )

        val bmp = Bitmap.createBitmap(geom.width, geom.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val photoPath = when (config.backgroundMode) {
            ShareCardBackgroundMode.CUSTOM_PHOTO -> config.customPhotoPath ?: vehicle?.photoUri
            ShareCardBackgroundMode.CAR_PHOTO -> vehicle?.photoUri
            else -> null
        }

        drawBackground(canvas, geom, photoPath, config.backgroundMode)
        drawTopHeader(canvas, geom, session, vehicle, config)
        drawRouteTrace(canvas, geom, samples, config, roadContextLayer)
        if (renderedMetrics.isNotEmpty()) {
            drawBottomMetricsCard(canvas, geom, renderedMetrics, config)
        }

        return bmp
    }

    private fun computeLayoutGeometry(
        config: TripCardConfig,
        hasMetrics: Boolean,
        metricCount: Int
    ): CardLayoutGeometry {
        val w = config.aspectRatio.widthPx
        val h = config.aspectRatio.heightPx
        val padX = when (config.aspectRatio) {
            ShareCardAspectRatio.LANDSCAPE_4_3 -> 68f
            ShareCardAspectRatio.STORY_9_16 -> 60f
            ShareCardAspectRatio.SQUARE_1_1 -> 56f
        }
        val padTop = if (config.aspectRatio == ShareCardAspectRatio.STORY_9_16) 116f else 48f
        val padBot = if (config.aspectRatio == ShareCardAspectRatio.STORY_9_16) 104f else 46f

        val hasTopRow = config.showAppMark || config.showDate || config.showTime
        var headerBottom = padTop
        if (hasTopRow) headerBottom += 48f
        if (config.showVehicleName) headerBottom += if (hasTopRow) 62f else 54f
        if (config.showVehicleSpecs) headerBottom += 36f
        if (!hasTopRow && !config.showVehicleName && !config.showVehicleSpecs) {
            headerBottom = padTop
        }

        val rows = if (metricCount <= 3) 1 else 2
        val panelHeight = if (!hasMetrics) {
            0f
        } else {
            when (config.aspectRatio) {
                ShareCardAspectRatio.STORY_9_16 -> if (rows == 1) 162f else 296f
                ShareCardAspectRatio.LANDSCAPE_4_3 -> if (rows == 1) 138f else 248f
                ShareCardAspectRatio.SQUARE_1_1 -> if (rows == 1) 144f else 258f
            }
        }
        val metricsTop = h - padBot - panelHeight
        val metricsRect = CardBox(padX, metricsTop, w - padX, h - padBot)

        val routeTop = (headerBottom + 18f).coerceAtMost(h * 0.45f)
        val routeBot = if (hasMetrics) (metricsTop - 22f) else (h - padBot - 20f)
        val routeInset = if (config.aspectRatio == ShareCardAspectRatio.LANDSCAPE_4_3) 84f else 72f
        val routeBox = CardBox(routeInset, routeTop, w - routeInset, max(routeTop + 140f, routeBot))

        return CardLayoutGeometry(
            aspectRatio = config.aspectRatio,
            width = w,
            height = h,
            padX = padX,
            padTop = padTop,
            padBottom = padBot,
            routeBox = routeBox,
            metricsRect = metricsRect,
            rowCount = rows
        )
    }

    private fun drawBackground(
        canvas: Canvas,
        geom: CardLayoutGeometry,
        photoPath: String?,
        backgroundMode: ShareCardBackgroundMode
    ) {
        if (backgroundMode == ShareCardBackgroundMode.TRANSPARENT) {
            return
        }
        val w = geom.width.toFloat()
        val h = geom.height.toFloat()
        val usePhoto = backgroundMode == ShareCardBackgroundMode.CAR_PHOTO ||
            backgroundMode == ShareCardBackgroundMode.CUSTOM_PHOTO
        val photoBmp = if (usePhoto) {
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
                color = Color.argb(28, 0, 168, 255)
                strokeWidth = 2f
            }
            var gx = 90f
            while (gx < w) {
                canvas.drawLine(gx, 0f, gx, h, gridPaint)
                gx += 108f
            }
            var gy = 108f
            while (gy < h) {
                canvas.drawLine(0f, gy, w, gy, gridPaint)
                gy += 108f
            }
        }
    }

    private fun drawTopHeader(
        canvas: Canvas,
        geom: CardLayoutGeometry,
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

        val compact = geom.aspectRatio != ShareCardAspectRatio.STORY_9_16
        val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(203, 213, 225)
            textSize = if (compact) 24f else 26f
            textAlign = Paint.Align.RIGHT
            if (isTrans) setShadowLayer(8f, 0f, 2f, Color.argb(210, 0, 0, 0))
        }
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = if (compact) 46f else 54f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            if (isTrans) setShadowLayer(10f, 0f, 2f, Color.argb(220, 0, 0, 0))
        }
        val specPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(147, 197, 253)
            textSize = if (compact) 26f else 30f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            if (isTrans) setShadowLayer(8f, 0f, 2f, Color.argb(210, 0, 0, 0))
        }

        if (config.showAppMark) {
            ApexBrandLogo.drawOnAndroidCanvas(
                canvas,
                left = geom.padX,
                top = geom.padTop,
                height = if (compact) 36f else 42f
            )
        }
        if (dateTimeStr.isNotEmpty()) {
            canvas.drawText(dateTimeStr, geom.width - geom.padX, geom.padTop + 28f, datePaint)
        }

        var nextY = if (config.showAppMark || dateTimeStr.isNotEmpty()) geom.padTop + 98f else geom.padTop + 44f
        if (config.showVehicleName) {
            canvas.drawText(vehicle?.name ?: "My Vehicle", geom.padX, nextY, titlePaint)
            nextY += if (compact) 38f else 44f
        }
        if (config.showVehicleSpecs && vehicle != null) {
            canvas.drawText(vehicle.specSummaryLine(), geom.padX, nextY, specPaint)
        }
    }

    private fun drawRouteTrace(
        canvas: Canvas,
        geom: CardLayoutGeometry,
        samples: List<TelemetrySampleEntity>,
        config: TripCardConfig,
        roadContextLayer: RoadContextLayer?
    ) {
        if (!config.showRouteLine && !config.showStartEndMarkers && !config.showSurroundingRoads) return

        val box = geom.routeBox
        val pts = downsamplePoints(extractValidGpsPoints(samples), maxPoints = 900)
        if (pts.size < 2) {
            val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(140, 203, 213, 225)
                textSize = 28f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("GPS trail unavailable for this session", box.centerX, box.centerY, emptyPaint)
            return
        }

        // Draw 60%-opacity surrounding area road map if captured and enabled
        val useContextCoords = config.showSurroundingRoads &&
            roadContextLayer != null &&
            !roadContextLayer.roadsAlphaBitmap.isRecycled &&
            roadContextLayer.projectedPoints.size == pts.size &&
            roadContextLayer.boxWidth > 0 &&
            roadContextLayer.boxHeight > 0

        if (config.showSurroundingRoads && roadContextLayer != null && !roadContextLayer.roadsAlphaBitmap.isRecycled) {
            val roadAlpha = ((config.surroundingRoadsOpacityPct.coerceIn(10, 60) / 100f) * 255f)
                .toInt()
                .coerceIn(25, 160)
            val roadPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = roadAlpha }
            canvas.drawBitmap(roadContextLayer.roadsAlphaBitmap, null, box.toRectF(), roadPaint)
        }

        val screenPoints: List<Pair<Float, Float>> = if (useContextCoords) {
            val sx = box.width / roadContextLayer!!.boxWidth.toFloat()
            val sy = box.height / roadContextLayer.boxHeight.toFloat()
            roadContextLayer.projectedPoints.map { (px, py) ->
                (box.left + px * sx) to (box.top + py * sy)
            }
        } else {
            val minLat = pts.minOf { it.first }
            val maxLat = pts.maxOf { it.first }
            val minLon = pts.minOf { it.second }
            val maxLon = pts.maxOf { it.second }
            val midLatRad = Math.toRadians((minLat + maxLat) / 2.0)
            val lonCorrection = cos(midLatRad).coerceIn(0.3, 1.0)

            val latSpan = (maxLat - minLat).coerceAtLeast(0.0005)
            val lonSpan = ((maxLon - minLon) * lonCorrection).coerceAtLeast(0.0005)
            val scale = min(box.width / lonSpan, box.height / latSpan)
            val drawW = (lonSpan * scale).toFloat()
            val drawH = (latSpan * scale).toFloat()
            val offX = box.left + (box.width - drawW) / 2f
            val offY = box.top + (box.height - drawH) / 2f

            pts.map { (lat, lon) ->
                val x = offX + (((lon - minLon) * lonCorrection) * scale).toFloat()
                val y = offY + drawH - ((lat - minLat) * scale).toFloat()
                x to y
            }
        }

        if (config.showRouteLine && screenPoints.size >= 2) {
            val path = Path()
            screenPoints.forEachIndexed { idx, (px, py) ->
                if (idx == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }

            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = Color.argb(205, 0, 168, 255)
                strokeWidth = 22f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                maskFilter = BlurMaskFilter(13f, BlurMaskFilter.Blur.NORMAL)
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

        if (config.showStartEndMarkers && screenPoints.size >= 2) {
            val (sx, sy) = screenPoints.first()
            val (ex, ey) = screenPoints.last()
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
        geom: CardLayoutGeometry,
        rendered: List<RenderedShareMetric>,
        config: TripCardConfig
    ) {
        val cardBox = geom.metricsRect
        val cardRectF = cardBox.toRectF()
        if (config.showMetricsPanelBg) {
            val cardBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(214, 7, 11, 18) }
            val cardBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = Color.argb(95, 255, 255, 255)
                strokeWidth = 2.5f
            }
            canvas.drawRoundRect(cardRectF, 26f, 26f, cardBg)
            canvas.drawRoundRect(cardRectF, 26f, 26f, cardBorder)
        }

        val addShadow = !config.showMetricsPanelBg || config.backgroundMode == ShareCardBackgroundMode.TRANSPARENT
        val compactPanel = geom.aspectRatio == ShareCardAspectRatio.LANDSCAPE_4_3 || geom.aspectRatio == ShareCardAspectRatio.SQUARE_1_1
        val lblPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (addShadow) Color.rgb(203, 213, 225) else Color.rgb(148, 163, 184)
            textSize = if (compactPanel) 20f else 23f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.05f
            if (addShadow) setShadowLayer(8f, 0f, 2f, Color.argb(220, 0, 0, 0))
        }
        val valPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = if (compactPanel) 32f else 36f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            if (addShadow) setShadowLayer(10f, 0f, 2f, Color.argb(230, 0, 0, 0))
        }

        val cols = when (rendered.size) {
            1 -> 1
            2, 4 -> 2
            else -> 3
        }
        val colW = (cardBox.width - 60f) / cols.toFloat()
        val rowStep = if (geom.rowCount == 1) cardBox.height else (cardBox.height - 24f) / 2f
        val topOffset = if (compactPanel) 46f else 56f
        val valGap = if (compactPanel) 42f else 48f
        val baseValSize = if (compactPanel) 32f else 36f

        rendered.forEachIndexed { idx, item ->
            val col = idx % cols
            val row = idx / cols
            val x = cardBox.left + 34f + col * colW
            val yLabel = cardBox.top + topOffset + row * rowStep
            canvas.drawText(item.label, x, yLabel, lblPaint)
            val valColor = when (item.metric) {
                ShareCardMetric.DISTANCE -> Color.rgb(56, 189, 248)
                ShareCardMetric.AVG_ECONOMY,
                ShareCardMetric.MOVING_KPL,
                ShareCardMetric.TRIP_EFFICIENCY -> Color.rgb(74, 222, 128)
                else -> Color.WHITE
            }
            valPaint.color = valColor
            var fittedSize = baseValSize
            valPaint.textSize = fittedSize
            val maxTextW = colW - 20f
            while (fittedSize > 22f && valPaint.measureText(item.value) > maxTextW) {
                fittedSize -= 1.5f
                valPaint.textSize = fittedSize
            }
            canvas.drawText(item.value, x, yLabel + valGap, valPaint)
        }
    }

    fun extractValidGpsPoints(samples: List<TelemetrySampleEntity>): List<Pair<Double, Double>> {
        val raw = samples.mapNotNull { s ->
            val lat = s.latitude
            val lon = s.longitude
            if (lat != null && lon != null && (abs(lat) > 1e-4 || abs(lon) > 1e-4)) {
                lat to lon
            } else null
        }
        if (raw.isEmpty()) return emptyList()

        // Strip legacy unfixed fallback sentinel coordinates (-6.2088, 106.8456) whenever real fixes exist elsewhere
        val hasRemoteFixes = raw.any { (lat, lon) ->
            abs(lat - (-6.2088)) > 0.05 || abs(lon - 106.8456) > 0.05
        }
        val withoutSentinel = if (hasRemoteFixes) {
            raw.filterNot { (lat, lon) ->
                abs(lat - (-6.2088)) < 1e-3 && abs(lon - 106.8456) < 1e-3
            }
        } else raw

        if (withoutSentinel.size < 20) return withoutSentinel

        // Guard against impossible cross-region GPS jumps (>0.15 deg) leaving minority unfixed/stale clusters
        val clusters = ArrayList<MutableList<Pair<Double, Double>>>()
        var current = mutableListOf(withoutSentinel.first())
        for (i in 1 until withoutSentinel.size) {
            val prev = withoutSentinel[i - 1]
            val cur = withoutSentinel[i]
            if (abs(cur.first - prev.first) > 0.15 || abs(cur.second - prev.second) > 0.15) {
                clusters += current
                current = mutableListOf(cur)
            } else {
                current += cur
            }
        }
        clusters += current
        if (clusters.size <= 1) return withoutSentinel

        val primary = clusters.maxByOrNull { seg ->
            seg.map { ((it.first * 10000).toInt() to (it.second * 10000).toInt()) }.toSet().size
        } ?: withoutSentinel
        return primary
    }

    internal fun downsamplePoints(pts: List<Pair<Double, Double>>, maxPoints: Int = 900): List<Pair<Double, Double>> {
        if (pts.size <= maxPoints || maxPoints < 2) return pts
        val out = ArrayList<Pair<Double, Double>>(maxPoints)
        val step = (pts.size - 1).toDouble() / (maxPoints - 1).toDouble()
        for (i in 0 until maxPoints - 1) {
            out.add(pts[(i * step).toInt()])
        }
        out.add(pts.last())
        return out
    }
}
