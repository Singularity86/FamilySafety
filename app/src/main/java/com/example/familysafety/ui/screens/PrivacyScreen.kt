package com.example.familysafety.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.familysafety.ui.theme.Spacing
import com.example.familysafety.ui.theme.TextDisabled

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(navController: NavController) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Privacy & Data") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.md)
        ) {
            PrivacySectionHeader("Stored On This Device")
            PrivacyItem(Icons.Default.Lock,          "Your encryption keys")
            PrivacyItem(Icons.Default.People,        "Family member profiles")
            PrivacyItem(Icons.Default.Schedule,      "Location history (last 30 days)")
            PrivacyItem(Icons.Default.Notifications, "Alert history")

            HorizontalDivider(
                modifier = Modifier.padding(vertical = Spacing.md),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            PrivacySectionHeader("Transmitted Over Encrypted Channel")
            PrivacyItem(Icons.Default.LocationOn,           "Location updates (to family circle only)")
            PrivacyItem(Icons.Default.Warning,              "Safety alerts")
            PrivacyItem(Icons.Default.CheckCircle,          "Delivery confirmations")
            PrivacyItem(Icons.Default.Chat,                 "Chat messages and files (encrypted; only your family can read them)")

            HorizontalDivider(
                modifier = Modifier.padding(vertical = Spacing.md),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            PrivacySectionHeader("Sent To Outside Services")
            PrivacyItem(Icons.Default.Map,           "Map tiles: the area you are viewing and your IP address go to OpenStreetMap")
            PrivacyItem(Icons.Default.DirectionsCar, "Drive-time estimates, only when you tap one: your location and that member's location go to the public OSRM routing service")

            HorizontalDivider(
                modifier = Modifier.padding(vertical = Spacing.md),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            PrivacySectionHeader("Never Transmitted")
            PrivacyItem(Icons.Default.Lock,    "Your encryption keys")
            PrivacyItem(Icons.Default.Chat,    "Readable message content (messages are always encrypted first)")
            PrivacyItem(Icons.Default.Person,  "Contact list")

            Spacer(modifier = Modifier.height(Spacing.lg))

            Text(
                text = "This app has no backend servers. Devices talk directly when they can, and use your relay when they are apart.",
                style = MaterialTheme.typography.labelSmall,
                color = TextDisabled,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md)
            )

            Spacer(modifier = Modifier.height(Spacing.sm))

            val uriHandler = LocalUriHandler.current
            TextButton(
                onClick = { uriHandler.openUri("https://singularity86.github.io/FamilySafety/privacy.html") },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("Read the full privacy policy")
            }

            Spacer(modifier = Modifier.height(Spacing.md))
        }
    }
}

@Composable
private fun PrivacySectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = Spacing.sm)
    )
}

@Composable
private fun PrivacyItem(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(Spacing.sm))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
