package com.example.familysafety.routing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class DriveEstimate(
    val distanceMeters: Double,
    val durationSeconds: Double
)

@Singleton
class RoutingService @Inject constructor() {
    private data class CacheEntry(
        val estimate: DriveEstimate,
        val createdAtMs: Long
    )

    private val requestMutex = Mutex()
    private val cache = mutableMapOf<String, CacheEntry>()
    private var lastRequestAtMs = 0L

    suspend fun getDriveEstimate(
        startLatitude: Double,
        startLongitude: Double,
        destinationLatitude: Double,
        destinationLongitude: Double
    ): DriveEstimate {
        val cacheKey = listOf(
            startLatitude,
            startLongitude,
            destinationLatitude,
            destinationLongitude
        ).joinToString(":") { String.format(Locale.US, "%.4f", it) }

        return requestMutex.withLock {
            val lockedNow = System.currentTimeMillis()
            cache[cacheKey]?.takeIf { lockedNow - it.createdAtMs < CACHE_TTL_MS }?.let {
                return@withLock it.estimate
            }

            val waitMs = MIN_REQUEST_INTERVAL_MS - (lockedNow - lastRequestAtMs)
            if (waitMs > 0) delay(waitMs)
            lastRequestAtMs = System.currentTimeMillis()

            val estimate = fetchEstimate(
                startLatitude = startLatitude,
                startLongitude = startLongitude,
                destinationLatitude = destinationLatitude,
                destinationLongitude = destinationLongitude
            )
            cache[cacheKey] = CacheEntry(estimate, System.currentTimeMillis())
            estimate
        }
    }

    private suspend fun fetchEstimate(
        startLatitude: Double,
        startLongitude: Double,
        destinationLatitude: Double,
        destinationLongitude: Double
    ): DriveEstimate = withContext(Dispatchers.IO) {
        val coordinates = String.format(
            Locale.US,
            "%.6f,%.6f;%.6f,%.6f",
            startLongitude,
            startLatitude,
            destinationLongitude,
            destinationLatitude
        )
        val connection = URL(
            "$OSRM_BASE_URL/route/v1/driving/$coordinates?overview=false&steps=false"
        ).openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "FamilySafety Android")

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw RoutingException("Routing service returned ${connection.responseCode}")
            }

            parseDriveEstimate(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val OSRM_BASE_URL = "https://router.project-osrm.org"
        private const val CACHE_TTL_MS = 5 * 60 * 1000L
        private const val MIN_REQUEST_INTERVAL_MS = 1_000L
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 8_000

        internal fun parseDriveEstimate(responseBody: String): DriveEstimate {
            val root = Json.parseToJsonElement(responseBody).jsonObject
            if (root["code"]?.jsonPrimitive?.content != "Ok") {
                throw RoutingException("No driving route was found")
            }
            val route = root["routes"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: throw RoutingException("No driving route was found")
            return DriveEstimate(
                distanceMeters = route.getValue("distance").jsonPrimitive.double,
                durationSeconds = route.getValue("duration").jsonPrimitive.double
            )
        }
    }
}

class RoutingException(message: String) : Exception(message)
