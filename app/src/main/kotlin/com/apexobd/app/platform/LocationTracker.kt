package com.apexobd.app.platform

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sin

data class GpsSnapshot(
    val latitude: Double = -6.2088,
    val longitude: Double = 106.8456,
    val speedKmh: Double = 0.0,
    val bearingDeg: Float = 0f,
    val hasRealFix: Boolean = false,
    val trail: List<Pair<Double, Double>> = emptyList()
)

class LocationTracker(context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    private val _gps = MutableStateFlow(GpsSnapshot())
    val gps: StateFlow<GpsSnapshot> = _gps.asStateFlow()

    private val trailBuffer = ArrayDeque<Pair<Double, Double>>(120)
    private var simTick = 0.0

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc: Location = result.lastLocation ?: return
            val speedKmh = if (loc.hasSpeed()) loc.speed * 3.6 else 0.0
            appendTrail(loc.latitude, loc.longitude)
            _gps.value = GpsSnapshot(
                latitude = loc.latitude,
                longitude = loc.longitude,
                speedKmh = speedKmh,
                bearingDeg = if (loc.hasBearing()) loc.bearing else 0f,
                hasRealFix = true,
                trail = trailBuffer.toList()
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun startUpdates() {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 500L)
            .setMinUpdateIntervalMillis(250L)
            .build()
        runCatching { client.requestLocationUpdates(req, callback, Looper.getMainLooper()) }
    }

    fun stopUpdates() {
        runCatching { client.removeLocationUpdates(callback) }
    }

    fun updateSimulatedStep(obdSpeedKmh: Double) {
        if (_gps.value.hasRealFix) return
        simTick += 0.12
        val lat = -6.2088 + (simTick * 0.00018)
        val lon = 106.8456 + sin(simTick * 0.4) * 0.00025
        appendTrail(lat, lon)
        _gps.value = GpsSnapshot(
            latitude = lat,
            longitude = lon,
            speedKmh = obdSpeedKmh,
            bearingDeg = 45f,
            hasRealFix = false,
            trail = trailBuffer.toList()
        )
    }

    private fun appendTrail(lat: Double, lon: Double) {
        if (trailBuffer.size >= 120) trailBuffer.removeFirst()
        trailBuffer.addLast(lat to lon)
    }
}
