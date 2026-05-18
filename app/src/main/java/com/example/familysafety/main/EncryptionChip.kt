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
    StatusPill(
        modifier = modifier,
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
