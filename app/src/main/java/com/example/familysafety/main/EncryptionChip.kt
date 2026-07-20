package com.example.familysafety.main

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.familysafety.ui.theme.TealPrimary

@Composable
fun EncryptionChip(
    modifier: Modifier = Modifier,
    isLocalOnly: Boolean = false
) {
    ChipInfoPopup(
        title = if (isLocalOnly) "Protected local route" else "Protected updates",
        message = if (isLocalOnly) {
            "Working. Your family updates are locked on this device and sent directly across the local network."
        } else {
            "Working. Your family updates are locked on your device and can only be opened by your family's devices."
        },
        details = if (isLocalOnly) {
            "Every location and chat update is scrambled on your device before it's sent, using a key pair " +
                "only your family holds. Right now your devices are on the same network, so updates travel " +
                "directly between phones without touching the internet at all. Even so, they're still fully " +
                "end-to-end encrypted — if a relay were ever used instead, nobody but your family's devices " +
                "could open the message."
        } else {
            "Every location and chat update is scrambled on your device before it ever leaves, using a key " +
                "pair only your family holds. Only a device that holds one of those private keys can unscramble " +
                "it — not the relay server that passes it along, not Jibaro, nobody else. This is called " +
                "end-to-end encryption (E2EE), and it protects every update in this app, all the time."
        },
        modifier = modifier
    ) {
        StatusPill(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.88f),
            borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.9f),
            contentColor = TealPrimary
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = TealPrimary,
                modifier = Modifier.size(12.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (isLocalOnly) "Local E2EE" else "E2EE",
                style = MaterialTheme.typography.labelSmall,
                color = TealPrimary
            )
        }
    }
}
