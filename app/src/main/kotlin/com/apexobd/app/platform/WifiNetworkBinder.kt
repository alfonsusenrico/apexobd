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
 * Binds ELM327 TCP sockets explicitly to the active Wi-Fi [Network], holds a low-latency
 * [WifiManager.WifiLock] + [PowerManager.PARTIAL_WAKE_LOCK] to disable 802.11 PSM (+102ms DTIM sleep)
 * and suppress off-channel background Wi-Fi scans, and optionally holds a parallel cellular
 * internet [NetworkRequest] so mobile data remains usable while Wi-Fi talks to the OBD adapter.
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

    private val _wifiConnected = MutableStateFlow(false)
    val wifiConnected: StateFlow<Boolean> = _wifiConnected.asStateFlow()

    private val _cellularOnline = MutableStateFlow(false)
    val cellularOnline: StateFlow<Boolean> = _cellularOnline.asStateFlow()

    private var cellularRequested = false
    private val lockGuard = Any()
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            wifiNetwork = network
            _wifiConnected.value = true
        }

        override fun onLost(network: Network) {
            if (wifiNetwork == network) {
                wifiNetwork = null
                _wifiConnected.value = false
            }
        }
    }

    private val cellularCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _cellularOnline.value = true
        }

        override fun onLost(network: Network) {
            _cellularOnline.value = false
        }
    }

    fun register() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        runCatching { connectivityManager.registerNetworkCallback(request, wifiCallback) }
    }

    fun setKeepCellularActive(enabled: Boolean) {
        if (enabled && !cellularRequested) {
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            runCatching {
                connectivityManager.requestNetwork(req, cellularCallback)
                cellularRequested = true
            }
        } else if (!enabled && cellularRequested) {
            runCatching { connectivityManager.unregisterNetworkCallback(cellularCallback) }
            cellularRequested = false
            _cellularOnline.value = false
        }
    }

    @Suppress("DEPRECATION")
    fun setLowLatencyLocks(wifiLockEnabled: Boolean, wakeLockEnabled: Boolean) {
        synchronized(lockGuard) {
            if (wifiLockEnabled) {
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
        setKeepCellularActive(false)
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
