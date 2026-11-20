package com.apexobd.app.platform

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
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
    private val appContext = context.applicationContext
    private val client = LocationServices.getFusedLocationProviderClient(appContext)
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _gps = MutableStateFlow(GpsSnapshot())
    val gps: StateFlow<GpsSnapshot> = _gps.asStateFlow()

    private val _isGpsEnabled = MutableStateFlow(readSystemLocationEnabled())
    val isGpsEnabled: StateFlow<Boolean> = _isGpsEnabled.asStateFlow()

    private val trailBuffer = ArrayDeque<Pair<Double, Double>>(250)
    private var trailWasSimulated = false
    private var simTick = 0.0
    private var updatesRunning = false
    private var usingPureGnss = false
    private var preferPureGnss = false
    private var lastHighFrequency = true
    private var currentIntervalMs = 500L
    private var lastGradeLoc: Location? = null
    private var lastGradePct = 0.0
    private var prevBearingLoc: Location? = null
    private var lastValidBearingDeg = 0f
    private var receiverRegistered = false

    private val gnssListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            processLocation(location)
        }

        override fun onProviderEnabled(provider: String) {
            refreshLocationEnabledState()
        }

        override fun onProviderDisabled(provider: String) {
            refreshLocationEnabledState()
        }

        @Deprecated("Deprecated in Android Q")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    private val providerReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            refreshLocationEnabledState()
        }
    }

    init {
        registerProviderReceiver()
    }

    private fun readSystemLocationEnabled(): Boolean =
        runCatching { LocationManagerCompat.isLocationEnabled(locationManager) }.getOrDefault(false)

    private fun registerProviderReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }
        runCatching {
            ContextCompat.registerReceiver(appContext, providerReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
    }

    fun refreshLocationEnabledState(): Boolean {
        val enabledNow = readSystemLocationEnabled()
        val wasEnabled = _isGpsEnabled.value
        _isGpsEnabled.value = enabledNow
        if (!wasEnabled && enabledNow && updatesRunning) {
            restartUpdatesAndFetchCurrent()
        } else if (enabledNow && updatesRunning && !_gps.value.hasRealFix) {
            requestImmediateFix()
        }
        return enabledNow
    }

    private val callback = object : LocationCallback() {
        override fun onLocationAvailability(availability: LocationAvailability) {
            refreshLocationEnabledState()
            if (availability.isLocationAvailable && updatesRunning && !_gps.value.hasRealFix) {
                requestImmediateFix()
            }
        }

        override fun onLocationResult(result: LocationResult) {
            val loc: Location = result.lastLocation ?: return
            processLocation(loc)
        }
    }

    private fun processLocation(loc: Location) {
        _isGpsEnabled.value = true
        val speedKmh = if (loc.hasSpeed()) loc.speed * 3.6 else 0.0
        if (loc.hasBearing() && (speedKmh >= 2.2 || !loc.hasSpeed())) {
            lastValidBearingDeg = loc.bearing
            prevBearingLoc = loc
        } else {
            val prev = prevBearingLoc
            if (prev == null) {
                prevBearingLoc = loc
            } else if (prev.distanceTo(loc) >= 4.0f) {
                lastValidBearingDeg = (prev.bearingTo(loc) + 360f) % 360f
                prevBearingLoc = loc
            }
        }
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

        appendTrailPoint(loc.latitude, loc.longitude, simulated = false)
        _gps.value = GpsSnapshot(
            latitude = loc.latitude,
            longitude = loc.longitude,
            speedKmh = speedKmh,
            bearingDeg = lastValidBearingDeg,
            altitudeMeters = if (loc.hasAltitude()) loc.altitude else 0.0,
            roadGradePct = lastGradePct,
            hasRealFix = true,
            trail = trailBuffer.toList()
        )
    }

    /**
     * When connected to a Wi-Fi OBD adapter, prefer hardware GNSS ([LocationManager.GPS_PROVIDER])
     * over Google Play Services FusedLocationProvider so NLP Wi-Fi assist scans do not pull the
     * phone's single Wi-Fi radio off-channel and stall OBD TCP frames.
     */
    fun setPreferPureGnss(prefer: Boolean) {
        if (preferPureGnss == prefer) return
        preferPureGnss = prefer
        if (updatesRunning) {
            val high = lastHighFrequency
            stopUpdates()
            startUpdates(highFrequency = high)
        }
    }

    @SuppressLint("MissingPermission")
    fun requestImmediateFix() {
        if (preferPureGnss && runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)) {
            runCatching {
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { loc ->
                    if (System.currentTimeMillis() - loc.time <= 60_000L) {
                        processLocation(loc)
                    }
                }
            }
            return
        }
        runCatching {
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { loc ->
                    if (loc != null) processLocation(loc)
                }
        }
    }

    fun restartUpdatesAndFetchCurrent() {
        val high = lastHighFrequency
        stopUpdates()
        startUpdates(highFrequency = high)
        requestImmediateFix()
    }

    fun buildHighAccuracySettingsRequest(): LocationSettingsRequest {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 350L)
            .setMinUpdateIntervalMillis(180L)
            .build()
        return LocationSettingsRequest.Builder()
            .addLocationRequest(req)
            .setAlwaysShow(true)
            .build()
    }

    @SuppressLint("MissingPermission")
    fun startUpdates(highFrequency: Boolean = true) {
        registerProviderReceiver()
        _isGpsEnabled.value = readSystemLocationEnabled()
        val desiredInterval = if (highFrequency) 350L else 1500L
        val wantPureGnss = preferPureGnss &&
            runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
        lastHighFrequency = highFrequency
        if (updatesRunning && currentIntervalMs == desiredInterval && usingPureGnss == wantPureGnss) return
        stopUpdates()

        currentIntervalMs = desiredInterval
        if (wantPureGnss) {
            val startedGnss = runCatching {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    desiredInterval,
                    if (highFrequency) 0.5f else 3f,
                    gnssListener,
                    Looper.getMainLooper()
                )
                true
            }.getOrDefault(false)
            if (startedGnss) {
                updatesRunning = true
                usingPureGnss = true
                if (_isGpsEnabled.value) {
                    requestImmediateFix()
                }
                return
            }
        }

        val priority = if (highFrequency) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        val req = LocationRequest.Builder(priority, desiredInterval)
            .setMinUpdateIntervalMillis(if (highFrequency) 180L else 750L)
            .setMinUpdateDistanceMeters(if (highFrequency) 0.5f else 3f)
            .build()
        runCatching {
            client.requestLocationUpdates(req, callback, Looper.getMainLooper())
            updatesRunning = true
            usingPureGnss = false
            if (_isGpsEnabled.value) {
                requestImmediateFix()
            }
        }
    }

    fun stopUpdates() {
        if (!updatesRunning) return
        if (usingPureGnss) {
            runCatching { locationManager.removeUpdates(gnssListener) }
        } else {
            runCatching { client.removeLocationUpdates(callback) }
        }
        updatesRunning = false
        usingPureGnss = false
    }

    fun dispose() {
        stopUpdates()
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(providerReceiver) }
            receiverRegistered = false
        }
    }

    fun updateSimulatedStep(obdSpeedKmh: Double) {
        if (_gps.value.hasRealFix) return
        simTick += 0.12
        val lat = -6.2088 + (simTick * 0.00018)
        val lon = 106.8456 + sin(simTick * 0.4) * 0.00025
        appendTrailPoint(lat, lon, simulated = true)
        _gps.value = GpsSnapshot(
            latitude = lat,
            longitude = lon,
            speedKmh = obdSpeedKmh,
            bearingDeg = 45f,
            hasRealFix = false,
            trail = trailBuffer.toList()
        )
    }

    fun clearTrail() {
        trailBuffer.clear()
        trailWasSimulated = false
        _gps.value = _gps.value.copy(trail = emptyList())
    }

    private fun appendTrailPoint(lat: Double, lon: Double, simulated: Boolean) {
        val last = trailBuffer.lastOrNull()
        if (shouldResetTrail(last?.first, last?.second, lat, lon, trailWasSimulated, simulated)) {
            trailBuffer.clear()
        } else if (last != null && abs(last.first - lat) < 0.000035 && abs(last.second - lon) < 0.000035) {
            return
        }
        trailWasSimulated = simulated
        if (trailBuffer.size >= 250) trailBuffer.removeFirst()
        trailBuffer.addLast(lat to lon)
    }

    companion object {
        /** Reset stale trails across sim/real source swaps or >~550 m GPS teleports so maps never draw straight cross-town beams. */
        internal fun shouldResetTrail(
            lastLat: Double?,
            lastLon: Double?,
            newLat: Double,
            newLon: Double,
            wasSimulated: Boolean,
            nowSimulated: Boolean
        ): Boolean {
            if (lastLat == null || lastLon == null) return false
            if (wasSimulated != nowSimulated) return true
            return abs(lastLat - newLat) > 0.005 || abs(lastLon - newLon) > 0.005
        }
    }
}
