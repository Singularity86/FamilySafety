package com.example.familysafety.billing

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * Shown once per cold start of [com.example.familysafety.main.MainScreen] — its visibility
 * lives in `remember { mutableStateOf(true) }` there, which is exactly what makes it reset on
 * every fresh launch rather than every navigation. Says nothing when the family doesn't need
 * to hear anything: [Entitlement.Grandfathered], [Entitlement.Subscribed] and [Entitlement.
 * Granted] all render nothing.
 */
@Composable
fun TrialReminderDialog(
    entitlement: Entitlement,
    onDismiss: () -> Unit,
    onSubscribe: () -> Unit
) {
    when (entitlement) {
        is Entitlement.Trial -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("${entitlement.daysLeft} ${if (entitlement.daysLeft == 1) "day" else "days"} left in your free trial") },
            text = { Text("Chat, location history, and file sharing are free until then. Location sharing itself never stops.") },
            confirmButton = { TextButton(onClick = onSubscribe) { Text("Subscribe now") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } }
        )
        Entitlement.Expired -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Your free trial has ended") },
            text = { Text("\$4/month keeps Chat, History and Files going. Live location sharing, alerts, and crash detection keep working either way.") },
            confirmButton = { TextButton(onClick = onSubscribe) { Text("Subscribe") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } }
        )
        else -> Unit
    }
}

