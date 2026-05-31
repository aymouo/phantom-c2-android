package com.openaccess.sdk.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

internal class NetworkManager(
    private val ctx: Context,
    private val onAvailable: () -> Unit,
    private val onLost: () -> Unit,
    private val onCapabilitiesLost: () -> Unit,
    private val onUnavailable: () -> Unit,
) {
    var callback: ConnectivityManager.NetworkCallback? = null
        private set

    fun register() {
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    onAvailable()
                }

                override fun onLost(network: Network) {
                    super.onLost(network)
                    onLost()
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    super.onCapabilitiesChanged(network, capabilities)
                    val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    if (!hasInternet || !validated) {
                        onCapabilitiesLost()
                    }
                }

                override fun onUnavailable() {
                    super.onUnavailable()
                    onUnavailable()
                }
            }
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, callback!!)
        } catch (_: Exception) {}
    }

    fun unregister() {
        try {
            callback?.let {
                (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(it)
            }
        } catch (_: Exception) {}
        callback = null
    }
}
