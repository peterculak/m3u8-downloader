package com.ta3.downloader

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Logs connectivity changes (wifi/cellular connect, disconnect, capability changes)
 * while the process is alive, so background-download behaviour can be diagnosed from logs.
 */
object NetworkLogger {
    private const val TAG = "Network"
    private var started = false

    fun describe(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return "none"
        return describe(cm.getNetworkCapabilities(net))
    }

    private fun describe(caps: NetworkCapabilities?): String {
        if (caps == null) return "unknown"
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "OTHER"
        }
        val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return "$type metered=$metered validated=$validated"
    }

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        AppLogger.i(TAG, "Initial network: ${describe(context)}")
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                private var last: String? = null
                override fun onAvailable(network: Network) {
                    AppLogger.i(TAG, "Default network available: $network")
                }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val d = describe(caps)
                    if (d != last) {
                        last = d
                        AppLogger.i(TAG, "Network changed: $d ($network)")
                    }
                }
                override fun onLost(network: Network) {
                    last = null
                    AppLogger.i(TAG, "Default network lost: $network")
                }
            })
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to register network callback", e)
        }
    }
}
