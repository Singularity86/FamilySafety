package com.example.familysafety.geofence

import android.content.Context
import android.location.Location
import com.example.familysafety.location.MemberLocation
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

private const val SPEED_ALERT_COOLDOWN_MS = 5 * 60 * 1000L // 5 minutes

@Singleton
class GeofenceMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val geofenceRepository: GeofenceRepository,
    private val notificationHelper: GeofenceNotificationHelper
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Cache geofences eagerly so checkLocation() is non-suspending
    private val geofences = geofenceRepository.geofences
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    // memberId -> set of geofence IDs the member is currently inside
    private val memberGeofenceState = mutableMapOf<String, Set<String>>()

    // memberId -> last time we sent a speed alert
    private val lastSpeedAlertTime = mutableMapOf<String, Long>()

    private val distanceResult = FloatArray(1)

    fun checkLocation(location: MemberLocation, memberDisplayName: String) {
        val activeZones = geofences.value.filter { it.isEnabled }
        if (activeZones.isEmpty()) return

        val currentlyInside = mutableSetOf<String>()
        val previouslyInside = memberGeofenceState[location.memberId] ?: emptySet()

        for (zone in activeZones) {
            // Skip if zone watches specific members and this one isn't included
            if (zone.watchedMemberIds.isNotEmpty() && location.memberId !in zone.watchedMemberIds) continue

            Location.distanceBetween(
                location.latitude, location.longitude,
                zone.latitude, zone.longitude,
                distanceResult
            )
            val distance = distanceResult[0]

            if (distance <= zone.radiusMeters) {
                currentlyInside.add(zone.id)
                if (zone.id !in previouslyInside && zone.alertOnEnter) {
                    Timber.d("GeofenceMonitor: $memberDisplayName entered ${zone.name}")
                    notificationHelper.notifyGeofence(memberDisplayName, zone, isEnter = true)
                }
            } else {
                if (zone.id in previouslyInside && zone.alertOnExit) {
                    Timber.d("GeofenceMonitor: $memberDisplayName exited ${zone.name}")
                    notificationHelper.notifyGeofence(memberDisplayName, zone, isEnter = false)
                }
            }
        }

        memberGeofenceState[location.memberId] = currentlyInside

        // Speed check: location.speed is in m/s (null if unavailable)
        val speedMs = location.speed ?: return
        val speedMph = speedMs * 2.237f
        val thresholdMph = context
            .getSharedPreferences("geofence_prefs", Context.MODE_PRIVATE)
            .getInt("speed_threshold_mph", 90)

        if (speedMph > thresholdMph) {
            val now = System.currentTimeMillis()
            val lastAlert = lastSpeedAlertTime[location.memberId] ?: 0L
            if (now - lastAlert > SPEED_ALERT_COOLDOWN_MS) {
                lastSpeedAlertTime[location.memberId] = now
                Timber.d("GeofenceMonitor: speed alert for $memberDisplayName — ${speedMph.toInt()} mph")
                notificationHelper.notifySpeed(memberDisplayName, speedMph, thresholdMph)
            }
        }
    }
}
