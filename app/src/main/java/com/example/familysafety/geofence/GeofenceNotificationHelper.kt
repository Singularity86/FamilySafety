package com.example.familysafety.geofence

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeofenceNotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val GEOFENCE_CHANNEL_ID = "geofence_alerts"
        const val SPEED_CHANNEL_ID = "speed_alerts"
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createChannels()
    }

    private fun createChannels() {
        // Silent channel — we play sound manually so each zone can use a custom sound
        val geofenceChannel = NotificationChannel(
            GEOFENCE_CHANNEL_ID,
            "Zone Alerts",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Alerts when family members enter or exit geofence zones"
            setSound(null, null)
        }

        val speedChannel = NotificationChannel(
            SPEED_CHANNEL_ID,
            "Speed Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts when a family member is driving over the speed threshold"
        }

        notificationManager.createNotificationChannel(geofenceChannel)
        notificationManager.createNotificationChannel(speedChannel)
    }

    fun notifyGeofence(memberName: String, zone: GeofenceZone, isEnter: Boolean) {
        val action = if (isEnter) "entered" else "left"
        val soundUri: Uri = if (isEnter) {
            zone.enterSoundUri?.let { Uri.parse(it) }
        } else {
            zone.exitSoundUri?.let { Uri.parse(it) }
        } ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val notification = NotificationCompat.Builder(context, GEOFENCE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("Zone Alert")
            .setContentText("$memberName $action ${zone.name}")
            .setAutoCancel(true)
            .build()

        val notifId = ("geofence_${zone.id}_$memberName").hashCode()
        notificationManager.notify(notifId, notification)

        // Play custom sound manually (channel is silent for per-zone sound support on Android 8+)
        try {
            RingtoneManager.getRingtone(context, soundUri)?.play()
        } catch (e: Exception) {
            Timber.w(e, "GeofenceNotificationHelper: failed to play sound")
        }
    }

    fun notifySpeed(memberName: String, speedMph: Float, thresholdMph: Int) {
        val notification = NotificationCompat.Builder(context, SPEED_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Speed Alert")
            .setContentText("$memberName is driving at ${speedMph.toInt()} mph (limit: $thresholdMph mph)")
            .setAutoCancel(true)
            .build()

        notificationManager.notify(("speed_$memberName").hashCode(), notification)
    }
}
