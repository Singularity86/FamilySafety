package com.example.familysafety.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.familysafety.group.MembershipState
import com.example.familysafety.main.EncryptionChip
import com.example.familysafety.ui.theme.Spacing
import com.example.familysafety.ui.theme.TealPrimary
import com.example.familysafety.ui.theme.TextDisabled
import com.example.familysafety.ui.theme.TextPrimary
import com.example.familysafety.ui.theme.TextSecondary

@Composable
fun PendingMemberScreen(
    state: MembershipState.PendingApproval,
    onCancelRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Suppress back button — this screen is terminal until approved or cancelled.
    BackHandler { }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .scale(scale)
                    .background(color = TealPrimary, shape = CircleShape)
            )

            Spacer(modifier = Modifier.height(Spacing.xl))

            Text(
                text = "Waiting for Approval",
                style = MaterialTheme.typography.displayLarge,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(Spacing.sm))

            Text(
                text = "${state.familyName} is reviewing your request.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(Spacing.xl))

            EncryptionChip(isLocalOnly = true)

            Spacer(modifier = Modifier.height(Spacing.lg))

            TextButton(onClick = onCancelRequest) {
                Text(
                    text = "Cancel Request",
                    color = TextDisabled
                )
            }
        }
    }
}
