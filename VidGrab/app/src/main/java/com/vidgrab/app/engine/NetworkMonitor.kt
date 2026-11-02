package com.vidgrab.app.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/** Tells whether the current connection is unmetered (Wi-Fi) and reports changes. */
class NetworkMonitor(context: Context, private val onChange: () -> Unit) {

    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    @Volatile
    private var lastUnmetered = isUnmetered()

    fun isUnmetered(): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    fun register() {
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = notifyIfChanged()
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = notifyIfChanged()
                override fun onLost(network: Network) = notifyIfChanged()
            })
        } catch (e: Exception) {
            // not fatal: the queue simply will not react to network changes
        }
    }

    private fun notifyIfChanged() {
        val now = isUnmetered()
        if (now != lastUnmetered) {
            lastUnmetered = now
            onChange()
        }
    }
}
