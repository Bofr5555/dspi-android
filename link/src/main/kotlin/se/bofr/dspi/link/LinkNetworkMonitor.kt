package se.bofr.dspi.link

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * Calls [onAvailable] when a local network (Wi-Fi or Ethernet) comes up, so a
 * waiting reconnect runs at once instead of after its backoff (the Mac hub
 * vanishes when the Mac sleeps; the phone drops Wi-Fi in its pocket).
 * Needs ACCESS_NETWORK_STATE.
 */
class LinkNetworkMonitor(context: Context, private val onAvailable: () -> Unit) {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        if (callback != null || cm == null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onAvailable()
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        runCatching { cm.registerNetworkCallback(request, cb) }.onSuccess { callback = cb }
    }

    fun stop() {
        callback?.let { runCatching { cm?.unregisterNetworkCallback(it) } }
        callback = null
    }
}
