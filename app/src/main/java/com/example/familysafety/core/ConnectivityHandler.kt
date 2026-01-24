package com.example.familysafety.core

import android.content.Context
import android.net.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectivityHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val networkMonitor: NetworkMonitor
) {
    private val connectivityManager = 
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    
    private val _networkQuality = MutableStateFlow<NetworkQuality>(NetworkQuality.Unknown)
    val networkQuality: StateFlow<NetworkQuality> = _networkQuality.asStateFlow()
    
    private val _offlineMode = MutableStateFlow(false)
    val offlineMode: StateFlow<Boolean> = _offlineMode.asStateFlow()

    sealed class NetworkQuality {
        data object Unknown : NetworkQuality()
        data object Excellent : NetworkQuality()
        data object Good : NetworkQuality()
        data object Poor : NetworkQuality()
        data object Offline : NetworkQuality()
    }

    init {
        monitorNetworkQuality()
    }

    private fun monitorNetworkQuality() {
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val quality = assessNetworkQuality(networkCapabilities)
                _networkQuality.value = quality
                Timber.d("Network quality changed to: $quality")
            }

            override fun onLost(network: Network) {
                _networkQuality.value = NetworkQuality.Offline
                Timber.i("Network lost")
            }

            override fun onAvailable(network: Network) {
                Timber.i("Network available")
            }
        }

        connectivityManager.registerNetworkCallback(networkRequest, callback)
    }

    private fun assessNetworkQuality(capabilities: NetworkCapabilities): NetworkQuality {
        val downstreamBandwidth = capabilities.linkDownstreamBandwidthKbps
        val upstreamBandwidth = capabilities.linkUpstreamBandwidthKbps

        return when {
            downstreamBandwidth > 5000 && upstreamBandwidth > 2000 -> NetworkQuality.Excellent
            downstreamBandwidth > 1000 && upstreamBandwidth > 500 -> NetworkQuality.Good
            downstreamBandwidth > 0 && upstreamBandwidth > 0 -> NetworkQuality.Poor
            else -> NetworkQuality.Offline
        }
    }

    fun enableOfflineMode() {
        _offlineMode.value = true
        Timber.i("Offline mode enabled")
    }

    fun disableOfflineMode() {
        _offlineMode.value = false
        Timber.i("Offline mode disabled")
    }

    fun shouldDeferNetworkOperation(): Boolean {
        return when (_networkQuality.value) {
            NetworkQuality.Offline -> true
            NetworkQuality.Poor -> isMeteredConnection()
            else -> false
        }
    }

    private fun isMeteredConnection(): Boolean {
        val network = connectivityManager.activeNetwork ?: return true
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return true
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    fun getRecommendedQos(): Int {
        return when (_networkQuality.value) {
            NetworkQuality.Excellent, NetworkQuality.Good -> 1
            NetworkQuality.Poor -> 0
            NetworkQuality.Offline, NetworkQuality.Unknown -> 0
        }
    }

    fun getRecommendedUpdateIntervalMs(): Long {
        return when (_networkQuality.value) {
            NetworkQuality.Excellent -> 30_000
            NetworkQuality.Good -> 60_000
            NetworkQuality.Poor -> 300_000
            NetworkQuality.Offline, NetworkQuality.Unknown -> 600_000
        }
    }
}
