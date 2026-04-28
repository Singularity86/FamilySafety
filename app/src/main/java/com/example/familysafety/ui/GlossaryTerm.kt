package com.example.familysafety.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/**
 * Inline tappable term that opens a plain-language explanation dialog.
 * Use inside AnnotatedString.Builder via [glossaryTermAnnotated], or as a
 * standalone clickable Text via [GlossaryTerm].
 */
@Composable
fun GlossaryTerm(
    term: String,
    modifier: Modifier = Modifier
) {
    val definition = glossaryDefinitions[term.lowercase()] ?: return
    var showDialog by remember { mutableStateOf(false) }

    Text(
        text = term,
        color = MaterialTheme.colorScheme.primary,
        style = LocalTextStyle.current.copy(textDecoration = TextDecoration.Underline),
        modifier = modifier.clickable { showDialog = true }
    )

    if (showDialog) {
        GlossaryDialog(term = term, definition = definition, onDismiss = { showDialog = false })
    }
}

@Composable
fun GlossaryDialog(term: String, definition: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(term) },
        text = {
            Text(
                text = definition,
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Got it") }
        }
    )
}

/**
 * Map of lowercase term → plain-language definition shown in the dialog.
 */
val glossaryDefinitions: Map<String, String> = mapOf(
    "end-to-end encrypted" to
        "Your data is scrambled on your device before it leaves. Only your family's devices have the key to unscramble it. Not even the relay server can read it — it just passes the locked box along.",

    "end-to-end encryption" to
        "Your data is scrambled on your device before it leaves. Only your family's devices have the key to unscramble it. Not even the relay server can read it — it just passes the locked box along.",

    "recovery phrase" to
        "A set of 12 ordinary words that acts as the master key to your account. If you lose your phone, these words let you restore everything. Write them down and keep them somewhere safe offline — like a drawer at home or your wallet. We don't store them anywhere.",

    "geofence zones" to
        "A virtual boundary you draw on the map. The app notifies you when a family member enters or leaves that area — useful for school, home, or any place that matters.",

    "offline maps" to
        "Map images downloaded directly to your device. Once downloaded, the map works even without an internet connection — useful when travelling or in areas with poor signal.",

    "local wifi mode" to
        "When all your devices are on the same WiFi network, they communicate directly with each other instead of going through the internet. This is faster, uses less data, and works even if the internet is down.",

    "crash detection" to
        "The app monitors your phone's motion sensor while you're driving. If it detects a sudden impact above a speed threshold, it sends an alert to your family so they know to check on you.",

    "mqtt broker" to
        "A relay server that routes encrypted messages between devices. It passes the locked box from one device to another but can't open it — your keys never leave your devices."
)
