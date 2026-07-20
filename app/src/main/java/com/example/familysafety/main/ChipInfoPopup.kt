package com.example.familysafety.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun ChipInfoPopup(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    details: String? = null,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Box(modifier = Modifier.clickable { expanded = true }) {
            content()
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 220.dp, max = 280.dp)
                    .padding(12.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                if (details != null) {
                    TextButton(
                        onClick = {
                            expanded = false
                            showDetails = true
                        },
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Text("Learn more")
                    }
                }
            }
        }
    }

    if (showDetails && details != null) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text(title) },
            text = {
                Text(
                    text = details,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { showDetails = false }) { Text("Got it") }
            }
        )
    }
}
