package com.example.familysafety.core

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.familysafety.R
import timber.log.Timber

/**
 * Notices that a family member's identity, or this phone's own, is in use on another phone
 * (F15). Local only: every phone in the family spots the same thing for itself.
 */
object IdentityAlerts {
    private const val CHANNEL_ID = "identity_alerts"
    private const val NOTIFICATION_BASE_ID = 4000

    fun memberOnAnotherDevice(context: Context, memberId: String, name: String) = post(
        context, memberId,
        "$name is signed in on another phone",
        "$name's account is active on two phones. If that wasn't them, they should check " +
            "who has their recovery phrase."
    )

    fun selfOnAnotherDevice(context: Context, memberId: String) = post(
        context, memberId,
        "Your account is signed in on another phone",
        "If that isn't you, someone else may have your recovery phrase."
    )

    fun memberRestored(context: Context, memberId: String, name: String) = post(
        context, memberId,
        "$name was restored on a new phone",
        "$name's account was set up again from their recovery phrase. If that wasn't them, " +
            "check with them."
    )

    private fun post(context: Context, memberId: String, title: String, text: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "Account security", NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "When a family member's account is in use on another phone" }
                context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                Timber.w("IdentityAlerts: no notification permission — \"$title\" not shown")
                return
            }
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_BASE_ID + memberId.hashCode(), notification)
        } catch (e: Exception) {
            Timber.e(e, "IdentityAlerts: could not post \"$title\"")
        }
    }
}
