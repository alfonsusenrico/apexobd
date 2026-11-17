package com.apexobd.app.platform

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.sin

data class GpsSnapshot(
    val latitude: Double = -6.2088,
    val longitude: Double = 106.8456,
    val speedKmh: Double = 0.0,
    val bearingDeg: Float = 0f,
    val altitudeMeters: Double = 0.0,
    val roadGradePct: Double = 0.0,
    val hasRealFix: Boolean = false,
    val trail: List<Pair<Double, Double>> = emptyList()
)

class LocationTracker(context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    private val _gps = MutableStateFlow(GpsSnapshot())
    val gps: StateFlow<GpsSnapshot> = _gps.asStateFlow()

    private val trailBuffer = ArrayDeque<Pair<Double, Double>>(250)
    private var simTick = 0.0
    private var updatesRunning = false
    private var currentIntervalMs = 500L
    private var lastGradeLoc: Location? = null
    private var lastGradePct = 0.0

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc: Location = result.lastLocation ?: return
            val speedKmh = if (loc.hasSpeed()) loc.speed * 3.6 else 0.0
            if (loc.hasAltitude()) {
                val prev = lastGradeLoc
                if (prev != null && prev.hasAltitude()) {
                    val distM = prev.distanceTo(loc).toDouble()
                    if (distM >= 20.0) {
                        val dAlt = loc.altitude - prev.altitude
                        lastGradePct = ((dAlt / distM) * 100.0).coerceIn(-25.0, 25.0)
                        lastGradeLoc = loc
                    }
                } else {
                    lastGradeLoc = loc
                }
            }

            appendTrailIfMoved(loc.latitude, loc.longitude)
            _gps.value = GpsSnapshot(
                latitude = loc.latitude,
                longitude = loc.longitude,
                speedKmh = speedKmh,
                bearingDeg = if (loc.hasBearing()) loc.bearing else 0f,
                altitudeMeters = if (loc.hasAltitude()) loc.altitude else 0.0,
                roadGradePct = lastGradePct,
                hasRealFix = true,
                trail = trailBuffer.toList()
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun startUpdates(highFrequency: Boolean = true) {
        val desiredInterval = if (highFrequency) 500L else 1500L
        if (updatesRunning && currentIntervalMs == desiredInterval) return
        stopUpdates()

        currentIntervalMs = desiredInterval
        val priority = if (highFrequency) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        val req = LocationRequest.Builder(priority, desiredInterval)
            .setMinUpdateIntervalMillis(desiredInterval / 2)
            .setMinUpdateDistanceMeters(2f)
            .build()
        runCatching {
            client.requestLocationUpdates(req, callback, Looper.getMainLooper())
            updatesRunning = true
        }
    }

    fun stopUpdates() {
        if (!updatesRunning) return
        runCatching { client.removeLocationUpdates(callback) }
        updatesRunning = false
    }

    fun updateSimulatedStep(obdSpeedKmh: Double) {
        if (_gps.value.hasRealFix) return
        simTick += 0.12
        val lat = -6.2088 + (simTick * 0.00018)
        val lon = 106.8456 + sin(simTick * 0.4) * 0.00025
        appendTrailIfMoved(lat, lon)
        _gps.value = GpsSnapshot(
            latitude = lat,
            longitude = lon,
            speedKmh = obdSpeedKmh,
            bearingDeg = 45f,
            hasRealFix = false,
            trail = trailBuffer.toList()
        )
    }

    private fun appendTrailIfMoved(lat: Double, lon: Double) {
        val last = trailBuffer.lastOrNull()
        if (last != null && abs(last.first - lat) < 0.000035 && abs(last.second - lon) < 0.000035) {
            return
        }
        if (trailBuffer.size >= 250) trailBuffer.removeFirst()
        trailBuffer.addLast(lat to lon)
    }

    companion object {
        fun launchExternalNavigation(context: Context, lat: Double, lon: Double) {
            val gmapsNav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lon")).apply {
                setPackage("com.google.android.apps.maps")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val geoFallback = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(gmapsNav) }.onFailure {
                runCatching { context.startActivity(geoFallback) }
            }
        }
    }
}
