package com.apexobd.app.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.RectF
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.apexobd.app.data.prefs.MapCameraMode
import com.apexobd.app.data.prefs.MapCursorStyle
import com.apexobd.app.data.prefs.MapProvider
import com.apexobd.app.data.prefs.MapStylePreset
import com.apexobd.app.platform.OfflineMapManager
import com.apexobd.app.ui.theme.TorqueEvoColors
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.io.File

@Composable
fun DualMapCanvas(
    mapProvider: MapProvider,
    latitude: Double,
    longitude: Double,
    trail: List<Pair<Double, Double>>,
    modifier: Modifier = Modifier,
    activePmtilesPath: String? = null,
    interactive: Boolean = false,
    bearingDeg: Float = 0f,
    followNavigation: Boolean = false,
    mapStylePreset: MapStylePreset = MapStylePreset.DARK_CONTRAST,
    mapCameraMode: MapCameraMode = MapCameraMode.NAV_3D_45,
    mapCursorStyle: MapCursorStyle = MapCursorStyle.SPORT_ARROW,
    highContrastRoads: Boolean = true
) {
    if (mapProvider == MapProvider.TRACK_ONLY || mapStylePreset == MapStylePreset.OLED_TRACK) {
        VectorTrackFallback(
            latitude = latitude,
            longitude = longitude,
            trail = trail,
            modifier = modifier,
            bearingDeg = bearingDeg,
            mapCursorStyle = mapCursorStyle
        )
    } else {
        MapLibreLayer(
            latitude = latitude,
            longitude = longitude,
            trail = trail,
            activePmtilesPath = activePmtilesPath,
            interactive = interactive,
            bearingDeg = bearingDeg,
            followNavigation = followNavigation,
            mapStylePreset = mapStylePreset,
            mapCameraMode = mapCameraMode,
            mapCursorStyle = mapCursorStyle,
            highContrastRoads = highContrastRoads,
            modifier = modifier
        )
    }
}

private fun applyStyleContrastForPreset(
    style: Style,
    preset: MapStylePreset,
    highContrastRoads: Boolean
) {
    if (!highContrastRoads || !preset.isDark) return
    val majorColor = if (preset == MapStylePreset.FIORD_NAVY) "#93B7E8" else "#6388B5"
    val minorColor = if (preset == MapStylePreset.FIORD_NAVY) "#5B7399" else "#3C5574"
    val labelColor = if (preset == MapStylePreset.FIORD_NAVY) "#E8F0FA" else "#D2DFEF"
    val haloColor = if (preset == MapStylePreset.FIORD_NAVY) "#1B2436" else "#070B12"
    style.layers.forEach { layer ->
        val id = layer.id.lowercase()
        when (layer) {
            is LineLayer -> {
                val isMajor = "motorway" in id || "trunk" in id || "primary" in id || "highway" in id || "major" in id
                val isRoad = isMajor || "road" in id || "street" in id || "secondary" in id || "tertiary" in id || "path" in id || "link" in id || "bridge" in id
                if (isRoad && !id.startsWith("apex-")) {
                    layer.setProperties(
                        PropertyFactory.lineColor(if (isMajor) majorColor else minorColor),
                        PropertyFactory.lineOpacity(1.0f)
                    )
                }
            }
            is SymbolLayer -> {
                if ("road" in id || "street" in id || "highway" in id || "label" in id || "place" in id) {
                    layer.setProperties(
                        PropertyFactory.textColor(labelColor),
                        PropertyFactory.textHaloColor(haloColor),
                        PropertyFactory.textHaloWidth(1.5f),
                        PropertyFactory.textOpacity(0.95f)
                    )
                }
            }
        }
    }
}

private const val IMG_CURSOR_ARROW = "apex-cursor-arrow"
private const val IMG_CURSOR_CAR = "apex-cursor-car"

private fun createArrowCursorBitmap(): Bitmap {
    val size = 96
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = AndroidCanvas(bmp)
    val cx = size / 2f
    val cy = size / 2f

    c.drawCircle(cx, cy, 40f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.argb(56, 0, 168, 255) })

    val arrow = AndroidPath().apply {
        moveTo(cx, cy - 30f)
        lineTo(cx + 23f, cy + 22f)
        lineTo(cx, cy + 12f)
        lineTo(cx - 23f, cy + 22f)
        close()
    }
    c.drawPath(
        arrow,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 7f
            strokeJoin = Paint.Join.ROUND
        }
    )
    c.drawPath(arrow, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.rgb(0, 168, 255) })
    return bmp
}

private fun createCarCursorBitmap(): Bitmap {
    val size = 104
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = AndroidCanvas(bmp)
    val cx = size / 2f
    val cy = size / 2f

    c.drawCircle(cx, cy, 44f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.argb(46, 0, 168, 255) })

    val bodyRect = RectF(cx - 19f, cy - 34f, cx + 19f, cy + 34f)
    // Dark outline shadow for contrast on bright maps
    c.drawRoundRect(
        RectF(bodyRect.left - 3f, bodyRect.top - 3f, bodyRect.right + 3f, bodyRect.bottom + 3f),
        14f,
        14f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.argb(200, 6, 10, 16) }
    )
    // Metallic silver-white body shell
    c.drawRoundRect(bodyRect, 12f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.rgb(232, 240, 248) })
    // Subtle hood crease & cabin glass
    c.drawRoundRect(
        RectF(cx - 14f, cy - 16f, cx + 14f, cy + 20f),
        7f,
        7f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.rgb(15, 23, 36) }
    )
    // Roof panel highlight
    c.drawRoundRect(
        RectF(cx - 11f, cy - 5f, cx + 11f, cy + 12f),
        4f,
        4f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.rgb(203, 213, 225) }
    )
    // Twin cyan front headlights (top = forward)
    val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.rgb(0, 184, 255) }
    c.drawRoundRect(RectF(cx - 15f, cy - 33f, cx - 6f, cy - 28f), 2f, 2f, headPaint)
    c.drawRoundRect(RectF(cx + 6f, cy - 33f, cx + 15f, cy - 28f), 2f, 2f, headPaint)
    // Red rear taillights (bottom)
    val tailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.rgb(239, 68, 68) }
    c.drawRoundRect(RectF(cx - 15f, cy + 28f, cx - 6f, cy + 33f), 2f, 2f, tailPaint)
    c.drawRoundRect(RectF(cx + 6f, cy + 28f, cx + 15f, cy + 33f), 2f, 2f, tailPaint)
    return bmp
}

private fun applyCursorStyleToStyle(style: Style, cursorStyle: MapCursorStyle) {
    val dotLayer = style.getLayer("apex-puck-layer")
    val symLayer = style.getLayer("apex-puck-symbol")
    when (cursorStyle) {
        MapCursorStyle.CLASSIC_DOT -> {
            dotLayer?.setProperties(PropertyFactory.visibility(Property.VISIBLE))
            symLayer?.setProperties(PropertyFactory.visibility(Property.NONE))
        }
        MapCursorStyle.SPORT_ARROW -> {
            dotLayer?.setProperties(PropertyFactory.visibility(Property.NONE))
            symLayer?.setProperties(
                PropertyFactory.visibility(Property.VISIBLE),
                PropertyFactory.iconImage(IMG_CURSOR_ARROW),
                PropertyFactory.iconSize(0.75f)
            )
        }
        MapCursorStyle.TOP_DOWN_CAR -> {
            dotLayer?.setProperties(PropertyFactory.visibility(Property.NONE))
            symLayer?.setProperties(
                PropertyFactory.visibility(Property.VISIBLE),
                PropertyFactory.iconImage(IMG_CURSOR_CAR),
                PropertyFactory.iconSize(0.78f)
            )
        }
    }
}

private fun attachRouteAndPuckLayers(
    style: Style,
    preset: MapStylePreset,
    cursorStyle: MapCursorStyle,
    highContrastRoads: Boolean
) {
    applyStyleContrastForPreset(style, preset, highContrastRoads)
    if (style.getImage(IMG_CURSOR_ARROW) == null) {
        style.addImage(IMG_CURSOR_ARROW, createArrowCursorBitmap())
    }
    if (style.getImage(IMG_CURSOR_CAR) == null) {
        style.addImage(IMG_CURSOR_CAR, createCarCursorBitmap())
    }
    val routeCasing = if (preset.isDark) "#002A45" else "#0F2942"
    val routeCore = if (preset.isDark) "#00C2FF" else "#0084FF"
    if (style.getSource("apex-route-src") == null) {
        style.addSource(GeoJsonSource("apex-route-src"))
    }
    if (style.getSource("apex-puck-src") == null) {
        style.addSource(GeoJsonSource("apex-puck-src"))
    }
    if (style.getLayer("apex-route-casing") == null) {
        style.addLayer(
            LineLayer("apex-route-casing", "apex-route-src").withProperties(
                PropertyFactory.lineColor(routeCasing),
                PropertyFactory.lineWidth(7.5f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
    }
    if (style.getLayer("apex-route-layer") == null) {
        style.addLayer(
            LineLayer("apex-route-layer", "apex-route-src").withProperties(
                PropertyFactory.lineColor(routeCore),
                PropertyFactory.lineWidth(4.5f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
    }
    if (style.getLayer("apex-puck-layer") == null) {
        style.addLayer(
            CircleLayer("apex-puck-layer", "apex-puck-src").withProperties(
                PropertyFactory.circleColor("#00B4FF"),
                PropertyFactory.circleRadius(6.5f),
                PropertyFactory.circleStrokeWidth(2.5f),
                PropertyFactory.circleStrokeColor("#FFFFFF")
            )
        )
    }
    if (style.getLayer("apex-puck-symbol") == null) {
        style.addLayer(
            SymbolLayer("apex-puck-symbol", "apex-puck-src").withProperties(
                PropertyFactory.iconImage(IMG_CURSOR_ARROW),
                PropertyFactory.iconRotate(Expression.get("bearing")),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER)
            )
        )
    }
    applyCursorStyleToStyle(style, cursorStyle)
}

@Composable
private fun MapLibreLayer(
    latitude: Double,
    longitude: Double,
    trail: List<Pair<Double, Double>>,
    activePmtilesPath: String?,
    interactive: Boolean,
    bearingDeg: Float,
    followNavigation: Boolean,
    mapStylePreset: MapStylePreset,
    mapCameraMode: MapCameraMode,
    mapCursorStyle: MapCursorStyle,
    highContrastRoads: Boolean,
    modifier: Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }

    val targetLatRef = rememberUpdatedState(latitude)
    val targetLonRef = rememberUpdatedState(longitude)
    val targetBearingRef = rememberUpdatedState(bearingDeg)

    val validPmtiles = remember(activePmtilesPath) {
        activePmtilesPath?.takeIf { File(it).exists() }
    }

    val interactiveRef = rememberUpdatedState(interactive)
    val mapView = remember {
        runCatching { MapLibre.getInstance(context.applicationContext) }
        val options = MapLibreMapOptions.createFromAttributes(context).textureMode(true)
        object : MapView(context, options) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean =
                if (interactiveRef.value) super.dispatchTouchEvent(ev) else false
        }.apply { onCreate(null) }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        mapView.getMapAsync { mlMap ->
            mapLibreMap = mlMap
            mlMap.uiSettings.apply {
                isScrollGesturesEnabled = interactive
                isZoomGesturesEnabled = interactive
                isRotateGesturesEnabled = interactive
                isTiltGesturesEnabled = false
                isCompassEnabled = false
                isAttributionEnabled = false
                isLogoEnabled = false
            }
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapLibreMap, validPmtiles, mapStylePreset, highContrastRoads) {
        val mlMap = mapLibreMap ?: return@LaunchedEffect
        styleReady = false
        val remoteUrl = mapStylePreset.styleUrl ?: OfflineMapManager.OPENFREEMAP_DARK_STYLE
        val builder = if (validPmtiles != null) {
            Style.Builder().fromJson(OfflineMapManager.buildPmtilesStyleJson(validPmtiles, mapStylePreset))
        } else {
            Style.Builder().fromUri(remoteUrl)
        }
        mlMap.setStyle(builder) { style ->
            attachRouteAndPuckLayers(style, mapStylePreset, mapCursorStyle, highContrastRoads)
            styleReady = true
        }
    }

    LaunchedEffect(styleReady, mapCursorStyle) {
        if (!styleReady) return@LaunchedEffect
        val style = mapLibreMap?.style ?: return@LaunchedEffect
        if (style.isFullyLoaded) {
            applyCursorStyleToStyle(style, mapCursorStyle)
        }
    }

    LaunchedEffect(styleReady, trail) {
        if (!styleReady) return@LaunchedEffect
        val style: Style = mapLibreMap?.style ?: return@LaunchedEffect
        if (!style.isFullyLoaded) return@LaunchedEffect
        val points = trail.map { Point.fromLngLat(it.second, it.first) }
        val routeCollection = if (points.size >= 2) {
            FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(points)))
        } else {
            FeatureCollection.fromFeatures(emptyList())
        }
        style.getSourceAs<GeoJsonSource>("apex-route-src")?.setGeoJson(routeCollection)
    }

    // Lockstep 60 Hz puck + camera interpolator so movement & heading rotation glide smoothly without animateCamera restart stutter.
    LaunchedEffect(styleReady, followNavigation, mapCameraMode) {
        if (!styleReady) return@LaunchedEffect
        val mlMap = mapLibreMap ?: return@LaunchedEffect
        if (!followNavigation) {
            val style: Style = mlMap.style ?: return@LaunchedEffect
            val target = org.maplibre.android.geometry.LatLng(targetLatRef.value, targetLonRef.value)
            val cam = CameraPosition.Builder()
                .target(target)
                .zoom(14.8)
                .tilt(0.0)
                .bearing(0.0)
                .padding(0.0, 0.0, 0.0, 0.0)
                .build()
            mlMap.moveCamera(CameraUpdateFactory.newCameraPosition(cam))
            val puckFeat = Feature.fromGeometry(Point.fromLngLat(targetLonRef.value, targetLatRef.value)).apply {
                addNumberProperty("bearing", targetBearingRef.value)
            }
            style.getSourceAs<GeoJsonSource>("apex-puck-src")?.setGeoJson(FeatureCollection.fromFeature(puckFeat))
            return@LaunchedEffect
        }

        var dispLat = targetLatRef.value
        var dispLon = targetLonRef.value
        var dispBearing = targetBearingRef.value
        var lastNanos = 0L

        while (true) {
            withFrameNanos { frameNanos ->
                val style = mlMap.style
                if (style == null || !style.isFullyLoaded) {
                    lastNanos = frameNanos
                    return@withFrameNanos
                }
                val dtMs = if (lastNanos == 0L) 16.0 else ((frameNanos - lastNanos) / 1_000_000.0).coerceIn(4.0, 64.0)
                lastNanos = frameNanos

                val tLat = targetLatRef.value
                val tLon = targetLonRef.value
                val tBearing = targetBearingRef.value

                // If large jump (>500m), snap directly instead of panning across city
                if (kotlin.math.abs(tLat - dispLat) > 0.005 || kotlin.math.abs(tLon - dispLon) > 0.005) {
                    dispLat = tLat
                    dispLon = tLon
                    dispBearing = tBearing
                } else {
                    val posAlpha = (1.0 - kotlin.math.exp(-dtMs / 160.0)).coerceIn(0.04, 1.0)
                    val rotAlpha = (1.0 - kotlin.math.exp(-dtMs / 190.0)).toFloat().coerceIn(0.04f, 1.0f)
                    dispLat += (tLat - dispLat) * posAlpha
                    dispLon += (tLon - dispLon) * posAlpha
                    val shortestTurn = ((tBearing - dispBearing + 540f) % 360f) - 180f
                    dispBearing = (dispBearing + shortestTurn * rotAlpha + 360f) % 360f
                }

                val topPad = if (mapView.height > 80 && mapCameraMode.tiltDeg > 5.0) mapView.height * 0.22 else 0.0
                val cam = CameraPosition.Builder()
                    .target(org.maplibre.android.geometry.LatLng(dispLat, dispLon))
                    .zoom(mapCameraMode.zoomLevel)
                    .tilt(mapCameraMode.tiltDeg)
                    .bearing(dispBearing.toDouble())
                    .padding(0.0, topPad, 0.0, 0.0)
                    .build()
                mlMap.moveCamera(CameraUpdateFactory.newCameraPosition(cam))
                val puckFeat = Feature.fromGeometry(Point.fromLngLat(dispLon, dispLat)).apply {
                    addNumberProperty("bearing", dispBearing)
                }
                style.getSourceAs<GeoJsonSource>("apex-puck-src")?.setGeoJson(FeatureCollection.fromFeature(puckFeat))
            }
        }
    }

    LaunchedEffect(styleReady, followNavigation, latitude, longitude) {
        if (!styleReady || followNavigation) return@LaunchedEffect
        val mlMap = mapLibreMap ?: return@LaunchedEffect
        val style: Style = mlMap.style ?: return@LaunchedEffect
        if (!style.isFullyLoaded) return@LaunchedEffect
        val cam = CameraPosition.Builder()
            .target(org.maplibre.android.geometry.LatLng(latitude, longitude))
            .zoom(14.8)
            .tilt(0.0)
            .bearing(0.0)
            .padding(0.0, 0.0, 0.0, 0.0)
            .build()
        mlMap.moveCamera(CameraUpdateFactory.newCameraPosition(cam))
        val puckFeat = Feature.fromGeometry(Point.fromLngLat(longitude, latitude)).apply {
            addNumberProperty("bearing", bearingDeg)
        }
        style.getSourceAs<GeoJsonSource>("apex-puck-src")?.setGeoJson(FeatureCollection.fromFeature(puckFeat))
    }

    Box(modifier = modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        if (!styleReady) {
            VectorTrackFallback(
                latitude = latitude,
                longitude = longitude,
                trail = trail,
                modifier = Modifier.fillMaxSize(),
                bearingDeg = bearingDeg,
                mapCursorStyle = mapCursorStyle
            )
        }
    }
}

/**
 * Interactive Google-Maps-style offline region selector frame: pinch/pan to resize what's inside
 * the #00A8FF frame box and compute live visible geographic bounds before downloading.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun OfflineFramePickerMap(
    initialLat: Double,
    initialLon: Double,
    activePmtilesPath: String?,
    onFrameBoundsChanged: (OfflineMapManager.BoundsBox) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnBounds by rememberUpdatedState(onFrameBoundsChanged)
    var styleReady by remember { mutableStateOf(false) }
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var cameraZoom by remember { mutableDoubleStateOf(10.5) }

    val mapView = remember {
        runCatching { MapLibre.getInstance(context.applicationContext) }
        MapView(context).apply {
            onCreate(null)
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_DOWN ->
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
        }
    }

    fun emitBounds(mlMap: MapLibreMap) {
        val vb = mlMap.projection.visibleRegion.latLngBounds
        cameraZoom = mlMap.cameraPosition.zoom
        val dLat = kotlin.math.abs(vb.latitudeNorth - vb.latitudeSouth)
        val dLon = kotlin.math.abs(vb.longitudeEast - vb.longitudeWest)
        if (dLat > 0.0001 && dLon > 0.0001) {
            val box = OfflineMapManager.insetBounds(
                north = vb.latitudeNorth,
                east = vb.longitudeEast,
                south = vb.latitudeSouth,
                west = vb.longitudeWest,
                marginRatio = 0.08
            )
            latestOnBounds(box)
        }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        mapView.addOnDidFailLoadingMapListener {
            mapRef?.let { ml ->
                val bgOnly = """{"version":8,"name":"ApexDark","layers":[{"id":"bg","type":"background","paint":{"background-color":"#07090D"}}]}"""
                ml.setStyle(Style.Builder().fromJson(bgOnly)) {
                    styleReady = true
                    emitBounds(ml)
                }
            }
        }

        mapView.getMapAsync { mlMap ->
            mapRef = mlMap
            mlMap.uiSettings.apply {
                isScrollGesturesEnabled = true
                isZoomGesturesEnabled = true
                isDoubleTapGesturesEnabled = true
                isQuickZoomGesturesEnabled = true
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
                isAttributionEnabled = false
                isLogoEnabled = false
            }
            mlMap.setMinZoomPreference(4.0)
            mlMap.setMaxZoomPreference(16.0)
            mlMap.moveCamera(
                org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(
                    org.maplibre.android.geometry.LatLng(initialLat, initialLon),
                    10.5
                )
            )
            mlMap.addOnCameraMoveListener { emitBounds(mlMap) }
            mlMap.addOnCameraIdleListener { emitBounds(mlMap) }

            val validPmtiles = activePmtilesPath?.takeIf { File(it).exists() }
            val builder = if (validPmtiles != null) {
                Style.Builder().fromJson(OfflineMapManager.buildDarkPmtilesStyleJson(validPmtiles))
            } else {
                Style.Builder().fromUri(OfflineMapManager.OPENFREEMAP_DARK_STYLE)
            }
            mlMap.setStyle(builder) {
                styleReady = true
                emitBounds(mlMap)
            }
            mapView.post { emitBounds(mlMap) }
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    Box(modifier = modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        if (!styleReady) {
            VectorTrackFallback(initialLat, initialLon, emptyList(), Modifier.fillMaxSize())
        }
        // Corner-bracket frame overlay matching the 8% inset downloaded bounding box
        Canvas(modifier = Modifier.fillMaxSize()) {
            val mx = size.width * 0.08f
            val my = size.height * 0.08f
            val fw = size.width - mx * 2f
            val fh = size.height - my * 2f
            val corner = 18.dp.toPx()

            // Subtle dark outer mask
            drawRect(Color(0x55000000), Offset.Zero, Size(size.width, my))
            drawRect(Color(0x55000000), Offset(0f, my + fh), Size(size.width, my))
            drawRect(Color(0x55000000), Offset(0f, my), Size(mx, fh))
            drawRect(Color(0x55000000), Offset(mx + fw, my), Size(mx, fh))

            // Thin frame border
            drawRect(
                color = TorqueEvoColors.ElectricBlue.copy(alpha = 0.45f),
                topLeft = Offset(mx, my),
                size = Size(fw, fh),
                style = Stroke(width = 1.2.dp.toPx())
            )
            // Bright corner brackets
            val strokeW = 2.8.dp.toPx()
            val cCol = TorqueEvoColors.ElectricBlue
            // Top-Left
            drawLine(cCol, Offset(mx, my), Offset(mx + corner, my), strokeW, StrokeCap.Round)
            drawLine(cCol, Offset(mx, my), Offset(mx, my + corner), strokeW, StrokeCap.Round)
            // Top-Right
            drawLine(cCol, Offset(mx + fw - corner, my), Offset(mx + fw, my), strokeW, StrokeCap.Round)
            drawLine(cCol, Offset(mx + fw, my), Offset(mx + fw, my + corner), strokeW, StrokeCap.Round)
            // Bottom-Left
            drawLine(cCol, Offset(mx, my + fh - corner), Offset(mx, my + fh), strokeW, StrokeCap.Round)
            drawLine(cCol, Offset(mx, my + fh), Offset(mx + corner, my + fh), strokeW, StrokeCap.Round)
            // Bottom-Right
            drawLine(cCol, Offset(mx + fw - corner, my + fh), Offset(mx + fw, my + fh), strokeW, StrokeCap.Round)
            drawLine(cCol, Offset(mx + fw, my + fh - corner), Offset(mx + fw, my + fh), strokeW, StrokeCap.Round)
        }

        // Camera zoom readout badge
        Text(
            text = "Zoom %.1f".format(cameraZoom),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = TorqueEvoColors.TextPrimary,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(14.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xCC07090D))
                .border(1.dp, TorqueEvoColors.SubtleBorder, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )

        // Explicit + / - zoom controls alongside pinch-to-zoom
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            PickerZoomButton("+") {
                mapRef?.let { ml ->
                    val nextZoom = (ml.cameraPosition.zoom + 1.0).coerceAtMost(15.5)
                    ml.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.zoomTo(nextZoom), 180)
                }
            }
            PickerZoomButton("−") {
                mapRef?.let { ml ->
                    val nextZoom = (ml.cameraPosition.zoom - 1.0).coerceAtLeast(4.0)
                    ml.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.zoomTo(nextZoom), 180)
                }
            }
        }
    }
}

@Composable
private fun PickerZoomButton(symbol: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xEB0D1016))
            .border(1.dp, TorqueEvoColors.ElectricBlue.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(symbol, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TorqueEvoColors.TextPrimary)
    }
}

@Composable
fun VectorTrackFallback(
    latitude: Double,
    longitude: Double,
    trail: List<Pair<Double, Double>>,
    modifier: Modifier = Modifier,
    bearingDeg: Float = 0f,
    mapCursorStyle: MapCursorStyle = MapCursorStyle.SPORT_ARROW
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TorqueEvoColors.MapFallbackBg)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stepX = size.width / 5f
            val stepY = size.height / 4f
            for (i in 1..4) {
                drawLine(Color(0xFF132030), Offset(stepX * i, 0f), Offset(stepX * i, size.height), strokeWidth = 2f)
            }
            for (j in 1..3) {
                drawLine(Color(0xFF132030), Offset(0f, stepY * j), Offset(size.width, stepY * j), strokeWidth = 2f)
            }
            val puckCenter: Offset
            if (trail.size >= 2) {
                val minLat = trail.minOf { it.first }
                val maxLat = trail.maxOf { it.first }
                val minLon = trail.minOf { it.second }
                val maxLon = trail.maxOf { it.second }
                val latSpan = (maxLat - minLat).coerceAtLeast(0.0005)
                val lonSpan = (maxLon - minLon).coerceAtLeast(0.0005)
                val pad = 18.dp.toPx()

                fun toOffset(lat: Double, lon: Double): Offset {
                    val x = pad + (((lon - minLon) / lonSpan) * (size.width - pad * 2)).toFloat()
                    val y = size.height - pad - (((lat - minLat) / latSpan) * (size.height - pad * 2)).toFloat()
                    return Offset(x, y)
                }

                val path = Path()
                trail.forEachIndexed { idx, p ->
                    val off = toOffset(p.first, p.second)
                    if (idx == 0) path.moveTo(off.x, off.y) else path.lineTo(off.x, off.y)
                }
                drawPath(path, TorqueEvoColors.ElectricBlue, style = Stroke(width = 6f, cap = StrokeCap.Round))
                val startPt = toOffset(trail.first().first, trail.first().second)
                puckCenter = toOffset(trail.last().first, trail.last().second)
                drawCircle(TorqueEvoColors.NominalGreen, radius = 6f, center = startPt)
            } else {
                puckCenter = center
            }

            when (mapCursorStyle) {
                MapCursorStyle.CLASSIC_DOT -> {
                    drawCircle(Color(0x3300A8FF), radius = 16f, center = puckCenter)
                    drawCircle(Color.White, radius = 9f, center = puckCenter)
                    drawCircle(TorqueEvoColors.ElectricBlue, radius = 6.5f, center = puckCenter)
                }
                MapCursorStyle.SPORT_ARROW -> {
                    drawCircle(Color(0x3300A8FF), radius = 18f, center = puckCenter)
                    rotate(bearingDeg, pivot = puckCenter) {
                        val p = Path().apply {
                            moveTo(puckCenter.x, puckCenter.y - 14f)
                            lineTo(puckCenter.x + 11f, puckCenter.y + 10f)
                            lineTo(puckCenter.x, puckCenter.y + 5f)
                            lineTo(puckCenter.x - 11f, puckCenter.y + 10f)
                            close()
                        }
                        drawPath(p, Color.White, style = Stroke(width = 4f))
                        drawPath(p, TorqueEvoColors.ElectricBlue)
                    }
                }
                MapCursorStyle.TOP_DOWN_CAR -> {
                    drawCircle(Color(0x2800A8FF), radius = 20f, center = puckCenter)
                    rotate(bearingDeg, pivot = puckCenter) {
                        drawRoundRect(
                            color = Color(0xFFE8F0F8),
                            topLeft = Offset(puckCenter.x - 8f, puckCenter.y - 14f),
                            size = Size(16f, 28f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(5f, 5f)
                        )
                        drawRoundRect(
                            color = Color(0xFF0F1724),
                            topLeft = Offset(puckCenter.x - 5.5f, puckCenter.y - 7f),
                            size = Size(11f, 15f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f)
                        )
                        drawRect(TorqueEvoColors.ElectricBlue, Offset(puckCenter.x - 6f, puckCenter.y - 14f), Size(4f, 2.5f))
                        drawRect(TorqueEvoColors.ElectricBlue, Offset(puckCenter.x + 2f, puckCenter.y - 14f), Size(4f, 2.5f))
                        drawRect(Color(0xFFEF4444), Offset(puckCenter.x - 6f, puckCenter.y + 11.5f), Size(4f, 2.5f))
                        drawRect(Color(0xFFEF4444), Offset(puckCenter.x + 2f, puckCenter.y + 11.5f), Size(4f, 2.5f))
                    }
                }
            }
        }
        if (trail.size < 2) {
            Text(
                text = "%.4f, %.4f".format(latitude, longitude),
                fontSize = 9.sp,
                color = TorqueEvoColors.TextSecondary,
                modifier = Modifier.align(Alignment.BottomStart)
            )
        }
    }
}
