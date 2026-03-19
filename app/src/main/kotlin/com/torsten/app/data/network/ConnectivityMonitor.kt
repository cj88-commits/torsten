package com.torsten.app.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Observes network connectivity and exposes it as [StateFlow]s.
 *
 * Requires [android.Manifest.permission.ACCESS_NETWORK_STATE].
 */
class ConnectivityMonitor(context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** True while at least one validated network is available. */
    val isOnline: StateFlow<Boolean>

    /** True while the active network uses Wi-Fi transport. */
    val isOnWifi: StateFlow<Boolean>

    private val _isOnline = MutableStateFlow(queryIsOnline())
    private val _isOnWifi = MutableStateFlow(queryIsOnWifi())

    init {
        isOnline = _isOnline.asStateFlow()
        isOnWifi = _isOnWifi.asStateFlow()
        startListening()
    }

    private fun startListening() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val online = queryIsOnline()
                val wifi = queryIsOnWifi()
                Timber.tag("[Sync]").d("Network available — online=%b wifi=%b", online, wifi)
                _isOnline.value = online
                _isOnWifi.value = wifi
            }

            override fun onLost(network: Network) {
                val online = queryIsOnline()
                val wifi = queryIsOnWifi()
                Timber.tag("[Sync]").d("Network lost — online=%b wifi=%b", online, wifi)
                _isOnline.value = online
                _isOnWifi.value = wifi
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                val online = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                _isOnline.value = online
                _isOnWifi.value = wifi
            }
        }

        runCatching {
            connectivityManager.registerNetworkCallback(request, callback)
        }.onFailure {
            Timber.tag("[Sync]").e(it, "Failed to register network callback")
        }
    }

    private fun queryIsOnline(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun queryIsOnWifi(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
