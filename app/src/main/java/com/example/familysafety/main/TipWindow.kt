package com.example.familysafety.main

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.familysafety.ui.GlossaryTerm
import com.example.familysafety.ui.glossaryDefinitions

private data class Tip(
    val body: String,
    /** Optional single glossary term embedded in this tip. */
    val glossaryTerm: String? = null
)

private val tips = listOf(
    Tip(
        body = "Tap the ↓ button on the map to download tiles for offline use — the map works even without internet.",
        glossaryTerm = "offline maps"
    ),
    Tip(
        body = "All location data is end-to-end encrypted. Nobody — not even us — can see where your family is.",
        glossaryTerm = "end-to-end encrypted"
    ),
    Tip(
        body = "On the same WiFi network, your devices use local WiFi mode and communicate directly. No internet required.",
        glossaryTerm = "local WiFi mode"
    ),
    Tip(
        body = "Crash Detection monitors your phone's motion sensor while you're driving. Enable it in Settings.",
        glossaryTerm = "crash detection"
    ),
    Tip(
        body = "Speed alerts notify your family when someone goes above a set speed. Configure it in Settings."
    ),
    Tip(
        body = "Tap a family member's name on the Members screen to see their location history."
    ),
    Tip(
        body = "Press and hold any spot on the map to create a geofence zone — get notified when family members enter or leave.",
        glossaryTerm = "geofence zones"
    ),
    Tip(
        body = "Chat messages between family members are end-to-end encrypted — just like location data.",
        glossaryTerm = "end-to-end encrypted"
    ),
    Tip(
        body = "Your recovery phrase is the only way to restore your account. Store it somewhere safe and offline.",
        glossaryTerm = "recovery phrase"
    ),
    Tip(
        body = "The app keeps sharing your location even when your screen is off, using a background service."
    ),
    Tip(
        body = "Enjoying the app? There's a \"Support This App\" tip jar near the bottom of Settings — completely optional, any amount."
    )
)

fun shouldShowTip(context: Context): Boolean {
    val prefs = context.getSharedPreferences("familysafety_prefs", Context.MODE_PRIVATE)
    val tipsEnabled = prefs.getBoolean("tips_enabled", true)
    val sessionCount = prefs.getInt("app_session_count", 0)
    return tipsEnabled && sessionCount > 1
}

fun incrementSessionCount(context: Context) {
    val prefs = context.getSharedPreferences("familysafety_prefs", Context.MODE_PRIVATE)
    prefs.edit().putInt("app_session_count", prefs.getInt("app_session_count", 0) + 1).apply()
}

fun disableTips(context: Context) {
    context.getSharedPreferences("familysafety_prefs", Context.MODE_PRIVATE)
        .edit().putBoolean("tips_enabled", false).apply()
}

private fun nextTip(context: Context): Tip {
    val prefs = context.getSharedPreferences("familysafety_prefs", Context.MODE_PRIVATE)
    val idx = prefs.getInt("tips_last_index", 0)
    val tip = tips[idx % tips.size]
    prefs.edit().putInt("tips_last_index", (idx + 1) % tips.size).apply()
    return tip
}

@Composable
fun TipWindow(
    onDismiss: () -> Unit,
    onDisableTips: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val tip = remember { nextTip(context) }
    var showGlossary by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "💡 Did you know?",
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = tip.body,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Start
                )
                val term = tip.glossaryTerm
                if (term != null && glossaryDefinitions.containsKey(term.lowercase())) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Learn more: ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        GlossaryTerm(term = term)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Got it") }
        },
        dismissButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(
                    onClick = {
                        onDisableTips()
                        onOpenSettings()
                    }
                ) {
                    Text(
                        text = "Disable tips",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    )
}
