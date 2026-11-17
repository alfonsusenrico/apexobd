package com.apexobd.app.ui.components

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

@Composable
fun DualMapCanvas(
    mapProvider: MapProvider,
    latitude: Double,
    longitude: Double,
    trail: List<Pair<Double, Double>>,
    modifier: Modifier = Modifier,
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
            MapLibreLayer(latitude, longitude, trail, interactive, modifier)
        }
    }
}

@Composable
private fun MapLibreLayer(
    latitude: Double,
    longitude: Double,
    trail: List<Pair<Double, Double>>,
    interactive: Boolean,
    modifier: Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }

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
            mlMap.setStyle(OfflineMapManager.OPENFREEMAP_DARK_STYLE) { style ->
                style.addSource(GeoJsonSource("apex-route-src"))
                style.addSource(GeoJsonSource("apex-puck-src"))
                style.addLayer(
                    LineLayer("apex-route-layer", "apex-route-src").withProperties(
                        PropertyFactory.lineColor("#00A8FF"),
                        PropertyFactory.lineWidth(3.5f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                    )
                )
                style.addLayer(
                    CircleLayer("apex-puck-layer", "apex-puck-src").withProperties(
                        PropertyFactory.circleColor("#00A8FF"),
                        PropertyFactory.circleRadius(5.5f),
                        PropertyFactory.circleStrokeWidth(2f),
                        PropertyFactory.circleStrokeColor("#FFFFFF")
                    )
                )
                styleReady = true
            }
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onStop()
            mapView.onDestroy()
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
