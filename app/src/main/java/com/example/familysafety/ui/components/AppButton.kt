package com.example.familysafety.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.familysafety.ui.theme.ButtonShape
import com.example.familysafety.ui.theme.RedDanger
import com.example.familysafety.ui.theme.TealPrimary

sealed class ButtonState {
    object Idle : ButtonState()
    object Loading : ButtonState()
    data class Success(val label: String = "Done") : ButtonState()
    data class Error(val label: String = "Try Again") : ButtonState()
}

@Composable
fun AppButton(
    label: String,
    state: ButtonState = ButtonState.Idle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Idle/Loading/Success stay on the single accent (primary emphasis); only the
    // Error state borrows RedDanger, and only because it represents a genuine
    // failure to retry — not a destructive action.
    val containerColor = when (state) {
        is ButtonState.Idle    -> TealPrimary
        is ButtonState.Loading -> TealPrimary
        is ButtonState.Success -> TealPrimary.copy(alpha = 0.8f)
        is ButtonState.Error   -> RedDanger
    }
    val contentColor = when (state) {
        is ButtonState.Error -> MaterialTheme.colorScheme.onError
        else                 -> MaterialTheme.colorScheme.onPrimary
    }

    Button(
        onClick = { if (state is ButtonState.Idle || state is ButtonState.Error) onClick() },
        modifier = modifier.fillMaxWidth(),
        shape = ButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = containerColor,
            disabledContentColor = contentColor
        )
    ) {
        AnimatedContent(
            targetState = state,
            transitionSpec = {
                ContentTransform(
                    targetContentEnter = fadeIn(tween(200)),
                    initialContentExit = fadeOut(tween(200))
                )
            },
            label = "button_state"
        ) { currentState ->
            when (currentState) {
                is ButtonState.Idle -> Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall
                )
                is ButtonState.Loading -> CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
                is ButtonState.Success -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = currentState.label,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                is ButtonState.Error -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = currentState.label,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}
