package com.example.familysafety.onboarding

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.familysafety.ui.theme.*
import com.example.familysafety.util.OemBatteryHelper

/**
 * Explains battery optimization exemption with OEM-specific guidance and launches
 * the system dialog to request it. If already exempted, calls [onAllowed] immediately.
 *
 * Two actions are offered:
 *  - "Allow" — requests the standard Android battery-optimization exemption.
 *  - "Open Battery Settings" — opens the OEM-specific battery manager so the user
 *    can also configure autostart / background restrictions that the standard exemption
 *    dialog does not cover on many manufacturer skins.
 */
@Composable
fun BatteryOptimizationScreen(
    onAllowed: () -> Unit,
    onSkip: () -> Unit
) {
    val context = LocalContext.current

    val isAlreadyExempt = remember {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .isIgnoringBatteryOptimizations(context.packageName)
    }
    if (isAlreadyExempt) {
        LaunchedEffect(Unit) { onAllowed() }
        return
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { onAllowed() }

    val instructions = remember { OemBatteryHelper.getInstructions() }

    Surface(color = Surface1, shape = CardShape) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Icon(
                imageVector = Icons.Default.BatteryAlert,
                contentDescription = null,
                tint = TealPrimary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.height(Spacing.md))
            Text(
                text = "Background Activity",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = "Android can pause FamilySafety to save battery, which stops location " +
                    "sharing. Granting this exemption keeps your location visible to your family " +
                    "even when the screen is off.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(Spacing.md))

            // OEM-specific step-by-step instructions
            Surface(
                color = TealPrimary.copy(alpha = 0.12f),
                shape = MaterialTheme.shapes.small
            ) {
                Column(modifier = Modifier.padding(Spacing.sm)) {
                    instructions.forEachIndexed { index, step ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = "${index + 1}.",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                color = TealPrimary,
                                modifier = Modifier.width(20.dp)
                            )
                            Text(
                                text = step,
                                style = MaterialTheme.typography.bodySmall,
                                color = TealPrimary,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.md))

            // Secondary action: open OEM battery settings directly
            OutlinedButton(
                onClick = { OemBatteryHelper.openBatterySettings(context) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TealPrimary)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(Spacing.xs))
                Text("Open Battery Settings")
            }

            Spacer(modifier = Modifier.height(Spacing.sm))

            // Primary action row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onSkip) {
                    Text("Not Now", color = TextSecondary)
                }
                Spacer(modifier = Modifier.width(Spacing.sm))
                Button(
                    onClick = {
                        try {
                            launcher.launch(
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                            )
                        } catch (_: Exception) {
                            onAllowed()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TealPrimary,
                        contentColor = Black
                    )
                ) {
                    Text("Allow")
                }
            }
        }
    }
}
