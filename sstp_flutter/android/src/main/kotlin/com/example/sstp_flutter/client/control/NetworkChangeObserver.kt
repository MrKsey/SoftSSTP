package com.example.sstp_flutter.client.control

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Watches the underlying (non-VPN) networks and reports when the set of
 * available networks changes — for example when Wi-Fi drops and mobile data
 * takes over.
 *
 * The SSTP socket is bound to one underlying network, so a switch breaks it and
 * the tunnel goes dead. Knowing about the change lets the service rebuild the
 * tunnel on the new network instead of staying down until the user reconnects.
 *
 * Only Wi-Fi / cellular / ethernet are watched; the VPN interface itself is
 * deliberately excluded so the observer does not react to our own tunnel.
 */
internal class NetworkChangeObserver(
    context: Context,
    private val onChanged: () -> Unit,
) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val knownNetworks = mutableSetOf<Network>()

    // The callbacks fired right after registration only report the networks that
    // are already up; those are not changes. Flip this once they have arrived.
    @Volatile
    private var primed = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val isNew = synchronized(knownNetworks) { knownNetworks.add(network) }
            if (isNew) notifyChanged()
        }

        override fun onLost(network: Network) {
            val removed = synchronized(knownNetworks) { knownNetworks.remove(network) }
            if (removed) notifyChanged()
        }
    }

    private fun notifyChanged() {
        if (!primed) return
        onChanged()
    }

    internal fun start() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        try {
            manager.registerNetworkCallback(request, callback)
        } catch (_: Exception) {
            // A missing ConnectivityManager must never crash the service.
        }

        handler.postDelayed({ primed = true }, PRIME_DELAY_MS)
    }

    internal fun stop() {
        try {
            manager.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
            // Already unregistered.
        }
        synchronized(knownNetworks) { knownNetworks.clear() }
    }

    private companion object {
        const val PRIME_DELAY_MS = 1_000L
    }
}
