package com.example.familysafety.crash

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.familysafety.chat.ChatRepository
import com.example.familysafety.group.GroupStateManager
import com.example.familysafety.location.LocationRepository
import com.example.familysafety.storage.EmergencyOutboxEntity
import com.example.familysafety.storage.EmergencyRetrySchedule
import com.example.familysafety.ui.theme.FamilySafetyTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import com.example.familysafety.ui.theme.ButtonShape

@AndroidEntryPoint
class CrashAlertActivity : ComponentActivity() {

    @Inject lateinit var chatRepository: ChatRepository
    @Inject lateinit var locationRepository: LocationRepository
    @Inject lateinit var groupStateManager: GroupStateManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show above the lock screen and turn the screen on immediately
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Ask the keyguard to step aside so the user can tap right away
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
                .requestDismissKeyguard(this, null)
        }

        // Play the default alarm sound to wake the user
        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val ringtone = RingtoneManager.getRingtone(this, alarmUri)
        ringtone?.play()

        setContent {
            FamilySafetyTheme {
                var alertId by remember { mutableStateOf<String?>(null) }
                val sentId = alertId
                if (sentId == null) {
                    CrashAlertScreen(
                        onImOkay = {
                            ringtone?.stop()
                            cancelNotification()
                            Timber.i("CrashAlert: user confirmed they are okay")
                            finish()
                        },
                        onCountdownExpired = {
                            ringtone?.stop()
                            cancelNotification()
                            alertId = sendCrashAlertToFamily()
                            // Nothing to show if the alert couldn't be raised at all.
                            if (alertId == null) finish()
                        }
                    )
                } else {
                    // Stays open so the person (or whoever finds the phone) can see who has
                    // the alert. It used to close at once, which also cancelled the send.
                    val deliveriesFlow = remember(sentId) { chatRepository.observeEmergencyAlert(sentId) }
                    AlertDeliveryScreen(
                        deliveries = deliveriesFlow.collectAsState(initial = emptyList()).value,
                        nameOf = { id ->
                            groupStateManager.groupDefinition.value?.findMemberById(id)?.displayName
                                ?: "Former member"
                        },
                        onClose = { finish() }
                    )
                }
            }
        }
    }

    private fun cancelNotification() {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(CrashDetectionMonitor.NOTIFICATION_ID)
    }

    /**
     * Hands the alert to the durable outbox and returns its id. The outbox sends it on its
     * own scope and keeps retrying until each member's phone confirms; [EmergencyAlertWorker]
     * picks it up again if this process is killed.
     */
    private fun sendCrashAlertToFamily(): String? {
        val memberName = groupStateManager.localMember.value?.displayName ?: "A family member"
        val location = locationRepository.myLocation.value
        val locationText = if (location != null) {
            " https://maps.google.com/?q=${location.latitude},${location.longitude}"
        } else ""
        Timber.w("CrashAlert: no response — sending emergency alert to every family member")
        val alertId = chatRepository.raiseEmergencyAlert(
            "🚨 $memberName may have been in an accident and did not respond.$locationText"
        ) ?: return null
        EmergencyAlertWorker.schedule(applicationContext)
        return alertId
    }
}

@Composable
private fun AlertDeliveryScreen(
    deliveries: List<EmergencyOutboxEntity>,
    nameOf: (String) -> String,
    onClose: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFB71C1C)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Text(
                text = "Your family is being alerted",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "The alert keeps being sent until each phone confirms it, even without " +
                    "signal right now. You can close this screen.",
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 16.sp
            )
            deliveries.sortedBy { nameOf(it.recipientId) }.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(nameOf(row.recipientId), color = Color.White, fontSize = 18.sp)
                    Text(
                        text = when {
                            row.deliveredAt != null -> "Received"
                            // Never retried once they're gone; see ChatRepository.flushEmergencyAlerts.
                            row.nextAttemptAt == Long.MAX_VALUE -> "Left the family"
                            // Retries have stopped, so "waiting" would no longer be true.
                            System.currentTimeMillis() - row.createdAt >
                                EmergencyRetrySchedule.GIVE_UP_AFTER_MS -> "Not reached"
                            row.attemptCount == 0 -> "Sending…"
                            else -> "Waiting for their phone"
                        },
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = if (row.deliveredAt != null) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
            Button(
                onClick = onClose,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color(0xFFB71C1C)
                ),
                shape = ButtonShape
            ) {
                Text("Close", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun CrashAlertScreen(
    onImOkay: () -> Unit,
    onCountdownExpired: () -> Unit
) {
    var secondsLeft by remember { mutableIntStateOf(60) }

    LaunchedEffect(Unit) {
        while (secondsLeft > 0) {
            delay(1000)
            secondsLeft--
        }
        onCountdownExpired()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFB71C1C)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Text(text = "⚠️", fontSize = 72.sp)

            Text(
                text = "Possible Crash Detected",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Text(
                text = "Are you okay?",
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 20.sp,
                textAlign = TextAlign.Center
            )

            // Countdown ring
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { secondsLeft / 60f },
                    modifier = Modifier.size(100.dp),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.3f),
                    strokeWidth = 6.dp
                )
                Text(
                    text = "$secondsLeft",
                    color = Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = "Your family will be notified if you don't respond",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )

            Button(
                onClick = onImOkay,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color(0xFFB71C1C)
                ),
                shape = ButtonShape
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "I'm Okay",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
