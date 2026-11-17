package com.apexobd.app.ui.components

import android.annotation.SuppressLint
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.apexobd.app.BuildConfig
import com.apexobd.app.data.prefs.MapProvider
import com.apexobd.app.platform.OfflineMapManager
import com.apexobd.app.ui.theme.TorqueEvoColors
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
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
    interactive: Boolean = false
) {
    when {
        mapProvider == MapProvider.TRACK_ONLY -> {
            VectorTrackFallback(latitude, longitude, trail, modifier)
        }
        mapProvider == MapProvider.GOOGLE_MAPS && BuildConfig.MAPS_API_KEY.isNotBlank() -> {
            GoogleMapLayer(latitude, longitude, trail, interactive, modifier)
        }
        else -> {
            MapLibreLayer(latitude, longitude, trail, activePmtilesPath, interactive, modifier)
        }
    }
}

private fun attachRouteAndPuckLayers(style: Style) {
    if (style.getSource("apex-route-src") == null) {
        style.addSource(GeoJsonSource("apex-route-src"))
    }
    if (style.getSource("apex-puck-src") == null) {
        style.addSource(GeoJsonSource("apex-puck-src"))
    }
    if (style.getLayer("apex-route-layer") == null) {
        style.addLayer(
            LineLayer("apex-route-layer", "apex-route-src").withProperties(
                PropertyFactory.lineColor("#00A8FF"),
                PropertyFactory.lineWidth(3.5f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
    }
    if (style.getLayer("apex-puck-layer") == null) {
        style.addLayer(
            CircleLayer("apex-puck-layer", "apex-puck-src").withProperties(
                PropertyFactory.circleColor("#00A8FF"),
                PropertyFactory.circleRadius(5.5f),
                PropertyFactory.circleStrokeWidth(2f),
                PropertyFactory.circleStrokeColor("#FFFFFF")
            )
        )
    }
}

@Composable
private fun MapLibreLayer(
    latitude: Double,
    longitude: Double,
    trail: List<Pair<Double, Double>>,
    activePmtilesPath: String?,
    interactive: Boolean,
    modifier: Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }

    val validPmtiles = remember(activePmtilesPath) {
        activePmtilesPath?.takeIf { File(it).exists() }
    }

    val mapView = remember {
        runCatching { MapLibre.getInstance(context.applicationContext) }
        MapView(context).apply { onCreate(null) }
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

    LaunchedEffect(mapLibreMap, validPmtiles) {
        val mlMap = mapLibreMap ?: return@LaunchedEffect
        styleReady = false
        val builder = if (validPmtiles != null) {
            Style.Builder().fromJson(OfflineMapManager.buildDarkPmtilesStyleJson(validPmtiles))
        } else {
            Style.Builder().fromUri(OfflineMapManager.OPENFREEMAP_DARK_STYLE)
        }
        mlMap.setStyle(builder) { style ->
            attachRouteAndPuckLayers(style)
            styleReady = true
        }
    }

    LaunchedEffect(styleReady, latitude, longitude, trail.size) {
        val mlMap = mapLibreMap ?: return@LaunchedEffect
        val style: Style = mlMap.style ?: return@LaunchedEffect
        if (!style.isFullyLoaded) return@LaunchedEffect

        val target = org.maplibre.android.geometry.LatLng(latitude, longitude)
        mlMap.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(target, 14.5))

        val points = trail.map { Point.fromLngLat(it.second, it.first) }
        val routeCollection = if (points.size >= 2) {
            FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(points)))
        } else {
            FeatureCollection.fromFeatures(emptyList())
        }
        style.getSourceAs<GeoJsonSource>("apex-route-src")?.setGeoJson(routeCollection)

        val puckCollection = FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(longitude, latitude)))
        style.getSourceAs<GeoJsonSource>("apex-puck-src")?.setGeoJson(puckCollection)
    }

    Box(modifier = modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        if (!styleReady) {
            VectorTrackFallback(latitude, longitude, trail, Modifier.fillMaxSize())
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
    var styleReady by remember { mutableStateOf(false) }

    val mapView = remember {
        runCatching { MapLibre.getInstance(context.applicationContext) }
        MapView(context).apply {
            onCreate(null)
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
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

        fun emitBounds(mlMap: MapLibreMap) {
            val vb = mlMap.projection.visibleRegion.latLngBounds
            val box = OfflineMapManager.insetBounds(
                north = vb.latitudeNorth,
                east = vb.longitudeEast,
                south = vb.latitudeSouth,
                west = vb.longitudeWest,
                marginRatio = 0.08
            )
            onFrameBoundsChanged(box)
        }

        mapView.getMapAsync { mlMap ->
            mlMap.uiSettings.apply {
                isScrollGesturesEnabled = true
                isZoomGesturesEnabled = true
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
            emitBounds(mlMap)
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
    }
}

@Composable
private fun GoogleMapLayer(
    latitude: Double,
    longitude: Double,
    trail: List<Pair<Double, Double>>,
    interactive: Boolean,
    modifier: Modifier
) {
    val cameraState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(latitude, longitude), 15f)
    }
    LaunchedEffect(latitude, longitude) {
        cameraState.animate(
            CameraUpdateFactory.newLatLngZoom(LatLng(latitude, longitude), 15f),
            durationMs = 350
        )
    }
    val darkJson = remember {
        """
        [
          {"elementType":"geometry","stylers":[{"color":"#080b10"}]},
          {"elementType":"labels.text.fill","stylers":[{"color":"#5c677d"}]},
          {"elementType":"labels.text.stroke","stylers":[{"color":"#080b10"}]},
          {"featureType":"road","elementType":"geometry","stylers":[{"color":"#162233"}]},
          {"featureType":"road.highway","elementType":"geometry","stylers":[{"color":"#1f3652"}]},
          {"featureType":"water","elementType":"geometry","stylers":[{"color":"#04080f"}]}
        ]
        """.trimIndent()
    }
    GoogleMap(
        modifier = modifier,
        cameraPositionState = cameraState,
        properties = MapProperties(
            isMyLocationEnabled = false,
            mapStyleOptions = MapStyleOptions(darkJson)
        ),
        uiSettings = MapUiSettings(
            zoomControlsEnabled = false,
            compassEnabled = false,
            myLocationButtonEnabled = false,
            scrollGesturesEnabled = interactive,
            zoomGesturesEnabled = interactive,
            mapToolbarEnabled = false
        )
    ) {
        if (trail.size >= 2) {
            Polyline(
                points = trail.map { LatLng(it.first, it.second) },
                color = TorqueEvoColors.ElectricBlue,
                width = 8f
            )
        }
    }
}

@Composable
fun VectorTrackFallback(
    latitude: Double,
    longitude: Double,
    trail: List<Pair<Double, Double>>,
    modifier: Modifier = Modifier
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
                val endPt = toOffset(trail.last().first, trail.last().second)
                drawCircle(TorqueEvoColors.NominalGreen, radius = 6f, center = startPt)
                drawCircle(TorqueEvoColors.ElectricBlue, radius = 7f, center = endPt)
            } else {
                drawCircle(TorqueEvoColors.ElectricBlue, radius = 7f, center = center)
                drawCircle(Color(0x3300A8FF), radius = 18f, center = center)
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
