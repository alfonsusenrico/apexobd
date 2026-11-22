package com.apexobd.app.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.net.SocketFactory

/**
 * Pins the ApexOBD process and ELM327 TCP sockets exclusively to the active Wi-Fi [Network]
 * (without requesting parallel cellular keepalive) and holds a low-latency
 * [WifiManager.WifiLock] + [PowerManager.PARTIAL_WAKE_LOCK] so Android keeps Wi-Fi at maximum
 * low-latency performance without 802.11 PSM (+102ms DTIM sleep) or cellular handover contention.
 */
class WifiNetworkBinder(context: Context) {

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager =
        appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val powerManager =
        appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager

    @Volatile
    private var wifiNetwork: Network? = null

    @Volatile
    private var dedicatedWifiRequested: Boolean = false

    private val _wifiConnected = MutableStateFlow(false)
    val wifiConnected: StateFlow<Boolean> = _wifiConnected.asStateFlow()

    private val lockGuard = Any()
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            wifiNetwork = network
            _wifiConnected.value = true
            if (dedicatedWifiRequested) {
                runCatching { connectivityManager.bindProcessToNetwork(network) }
            }
        }

        override fun onLost(network: Network) {
            if (wifiNetwork == network) {
                wifiNetwork = null
                _wifiConnected.value = false
                if (dedicatedWifiRequested) {
                    runCatching { connectivityManager.bindProcessToNetwork(null) }
                }
            }
        }
    }

    fun register() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        runCatching { connectivityManager.registerNetworkCallback(request, wifiCallback) }
    }

    /**
     * Enables/disables dedicated high-performance Wi-Fi mode:
     * binds the entire app process exclusively to the OBD Wi-Fi network (dropping dual Wi-Fi+Mobile
     * routing contention) and holds [WifiManager.WIFI_MODE_FULL_LOW_LATENCY] + [PowerManager.PARTIAL_WAKE_LOCK].
     */
    @Suppress("DEPRECATION")
    fun setLowLatencyLocks(wifiLockEnabled: Boolean, wakeLockEnabled: Boolean) {
        synchronized(lockGuard) {
            dedicatedWifiRequested = wifiLockEnabled
            if (wifiLockEnabled) {
                val activeWifi = wifiNetwork ?: findActiveWifiNetwork()
                if (activeWifi != null) {
                    wifiNetwork = activeWifi
                    runCatching { connectivityManager.bindProcessToNetwork(activeWifi) }
                }
                val existing = wifiLock
                if (existing == null || !existing.isHeld) {
                    runCatching {
                        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                        } else {
                            WifiManager.WIFI_MODE_FULL_HIGH_PERF
                        }
                        val lock = existing ?: wifiManager?.createWifiLock(mode, "ApexOBD:WifiLowLatency")?.apply {
                            setReferenceCounted(false)
                        }
                        lock?.acquire()
                        wifiLock = lock
                    }
                }
            } else {
                runCatching { connectivityManager.bindProcessToNetwork(null) }
                runCatching {
                    if (wifiLock?.isHeld == true) wifiLock?.release()
                }
            }

            if (wakeLockEnabled) {
                val existing = wakeLock
                if (existing == null || !existing.isHeld) {
                    runCatching {
                        val lock = existing ?: powerManager?.newWakeLock(
                            PowerManager.PARTIAL_WAKE_LOCK,
                            "ApexOBD:ObdPollingLock"
                        )?.apply {
                            setReferenceCounted(false)
                        }
                        lock?.acquire()
                        wakeLock = lock
                    }
                }
            } else {
                runCatching {
                    if (wakeLock?.isHeld == true) wakeLock?.release()
                }
            }
        }
    }

    fun unregister() {
        runCatching { connectivityManager.unregisterNetworkCallback(wifiCallback) }
        setLowLatencyLocks(wifiLockEnabled = false, wakeLockEnabled = false)
    }

    fun socketFactory(): SocketFactory {
        val activeWifi = wifiNetwork ?: findActiveWifiNetwork()
        return activeWifi?.socketFactory ?: SocketFactory.getDefault()
    }

    @Suppress("DEPRECATION")
    private fun findActiveWifiNetwork(): Network? {
        return connectivityManager.allNetworks.firstOrNull { net ->
            connectivityManager.getNetworkCapabilities(net)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }
}
