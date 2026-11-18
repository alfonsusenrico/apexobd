package com.apexobd.app.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.net.SocketFactory

/**
 * Binds ELM327 TCP sockets explicitly to the active Wi-Fi [Network] and optionally holds a
 * parallel cellular internet [NetworkRequest] so mobile data remains usable while Wi-Fi
 * talks to the no-internet OBD adapter (`192.168.0.10:35000`).
 */
class WifiNetworkBinder(context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile
    private var wifiNetwork: Network? = null

    private val _wifiConnected = MutableStateFlow(false)
    val wifiConnected: StateFlow<Boolean> = _wifiConnected.asStateFlow()

    private val _cellularOnline = MutableStateFlow(false)
    val cellularOnline: StateFlow<Boolean> = _cellularOnline.asStateFlow()

    private var cellularRequested = false

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

    fun unregister() {
        runCatching { connectivityManager.unregisterNetworkCallback(wifiCallback) }
        setKeepCellularActive(false)
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
