package com.example.familysafety.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monitors network connectivity state
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val connectivityManager = 
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * Flow of network availability
     */
    val isNetworkAvailable: Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            private val networks = mutableSetOf<Network>()

            override fun onAvailable(network: Network) {
                networks.add(network)
                trySend(networks.isNotEmpty())
            }

            override fun onLost(network: Network) {
                networks.remove(network)
                trySend(networks.isNotEmpty())
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, callback)

        // Send initial state
        trySend(isCurrentlyConnected())

        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }

    /**
     * Check if currently connected
     */
    fun isCurrentlyConnected(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Check if connected via WiFi
     */
    fun isWifiConnected(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * Check if on metered connection
     */
    fun isMeteredConnection(): Boolean {
        return !connectivityManager.isActiveNetworkMetered
    }
}
