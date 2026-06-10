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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.familysafety.ui.theme.Spacing
import com.example.familysafety.ui.theme.Surface2
import com.example.familysafety.ui.theme.TealPrimary
import com.example.familysafety.ui.theme.TextDisabled
import com.example.familysafety.ui.theme.TextSecondary

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
            PrivacyItem(Icons.Default.Schedule,      "Location history (last 24 hours)")
            PrivacyItem(Icons.Default.Notifications, "Alert history")

            HorizontalDivider(
                modifier = Modifier.padding(vertical = Spacing.md),
                color = Surface2
            )

            PrivacySectionHeader("Transmitted Over Encrypted Channel")
            PrivacyItem(Icons.Default.LocationOn,           "Location updates (to family circle only)")
            PrivacyItem(Icons.Default.Warning,              "Safety alerts")
            PrivacyItem(Icons.Default.CheckCircle,          "Delivery confirmations")

            HorizontalDivider(
                modifier = Modifier.padding(vertical = Spacing.md),
                color = Surface2
            )

            PrivacySectionHeader("Never Transmitted")
            PrivacyItem(Icons.Default.Lock,    "Your encryption keys")
            PrivacyItem(Icons.Default.Chat,    "Message content")
            PrivacyItem(Icons.Default.Person,  "Contact list")
            PrivacyItem(Icons.Default.Block,   "Any data not listed above")

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

            Spacer(modifier = Modifier.height(Spacing.md))
        }
    }
}

@Composable
private fun PrivacySectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
        color = TealPrimary,
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
            tint = TextSecondary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(Spacing.sm))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
    }
}
