package com.apexobd.app.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import javax.net.SocketFactory

/**
 * Provides a [SocketFactory] bound to the active Wi-Fi [Network] so Android routes
 * ELM327 TCP sockets (`192.168.0.10:35000`) over Wi-Fi even when the OBD AP has no
 * internet and cellular data is enabled.
 */
class WifiNetworkBinder(context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile
    private var wifiNetwork: Network? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            wifiNetwork = network
        }

        override fun onLost(network: Network) {
            if (wifiNetwork == network) {
                wifiNetwork = null
            }
        }
    }

    fun register() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        runCatching { connectivityManager.registerNetworkCallback(request, callback) }
    }

    fun unregister() {
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
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
