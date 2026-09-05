package com.example.familysafety.ui.components

import android.Manifest
import android.annotation.SuppressLint
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.familysafety.ui.theme.Spacing
import com.example.familysafety.ui.theme.PorchAmber

@SuppressLint("InlinedApi")
@Composable
fun PermissionRationaleCard(
    permission: String,
    title: String,
    rationale: String,
    onRequestPermission: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    coaching: String? = null
) {
    val icon: ImageVector = when (permission) {
        Manifest.permission.CAMERA                     -> Icons.Default.CameraAlt
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION     -> Icons.Default.LocationOn
        Manifest.permission.ACCESS_BACKGROUND_LOCATION -> Icons.Default.MyLocation
        Manifest.permission.POST_NOTIFICATIONS         -> Icons.Default.Notifications
        Manifest.permission.NEARBY_WIFI_DEVICES        -> Icons.Default.Wifi
        else                                           -> Icons.Default.Security
    }

    OutlinedCard(modifier = modifier) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = PorchAmber,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.height(Spacing.md))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = rationale,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (coaching != null) {
                Spacer(modifier = Modifier.height(Spacing.md))
                Surface(
                    color = PorchAmber.copy(alpha = 0.12f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = coaching,
                        style = MaterialTheme.typography.bodySmall,
                        color = PorchAmber,
                        modifier = Modifier.padding(Spacing.sm)
                    )
                }
            }
            Spacer(modifier = Modifier.height(Spacing.lg))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Not Now", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.width(Spacing.sm))
                Button(
                    onClick = onRequestPermission,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PorchAmber,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Continue")
                }
            }
        }
    }
}
