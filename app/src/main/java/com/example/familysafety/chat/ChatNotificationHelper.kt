package com.example.familysafety.chat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.familysafety.MainActivity
import com.example.familysafety.R
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatNotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val CHAT_CHANNEL_ID = "chat_messages"
        private const val NOTIFICATION_GROUP = "com.example.familysafety.CHAT"
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createChannel()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHAT_CHANNEL_ID,
            "Chat Messages",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications for new chat messages from family members"
            enableVibration(true)
            enableLights(true)
        }
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Post (or update) a notification for a new incoming chat message.
     * [conversationRoute] is the Compose nav route to open when tapped,
     * e.g. "chat/group" or "chat/conversation/{memberId}".
     */
    fun notifyNewMessage(
        senderName: String,
        senderMemberId: String,
        messagePreview: String,
        conversationRoute: String
    ) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", conversationRoute)
        }
        // Keyed by conversation, not sender: someone writing both privately and in the
        // family chat used to have one notification overwrite the other, and the tap then
        // opened whichever thread happened to be last.
        val notificationId = conversationRoute.hashCode()
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHAT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(senderName)
            .setContentText(messagePreview)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(NOTIFICATION_GROUP)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()

        try {
            notificationManager.notify(notificationId, notification)
        } catch (e: Exception) {
            Timber.w(e, "ChatNotificationHelper: failed to post notification")
        }
    }
}
