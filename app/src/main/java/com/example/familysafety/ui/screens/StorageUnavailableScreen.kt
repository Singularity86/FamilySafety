package com.example.familysafety.ui.screens

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.example.familysafety.onboarding.MembershipViewModel.StorageResetResult
import com.example.familysafety.storage.StorageArea
import com.example.familysafety.ui.theme.ButtonShape
import com.example.familysafety.ui.theme.NumericFamily
import com.example.familysafety.ui.theme.Spacing
import com.example.familysafety.ui.theme.statusColors
import kotlinx.coroutines.launch

/**
 * Shown instead of the app when stored data exists but can't be read. The data is most
 * likely intact, so the first thing offered is to try again. Erasing is offered second, and
 * only behind the recovery phrase: someone who can't produce it would lose their identity
 * for good.
 */
@Composable
fun StorageUnavailableScreen(
    areas: List<StorageArea>,
    onRetry: () -> Unit,
    onReset: suspend (String) -> StorageResetResult,
    modifier: Modifier = Modifier
) {
    var showReset by remember { mutableStateOf(false) }
    var phrase by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // The recovery phrase is typed here, so keep it out of screenshots and the recents view.
    val activity = LocalContext.current as? Activity
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.md, vertical = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text(
            text = "Can't read this phone's saved data",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Android isn't letting the app read " +
                areas.joinToString(" or ") { it.description } +
                ". Nothing has been deleted, and this is often temporary. Try again, or " +
                "restart your phone and open the app again.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Button(
            shape = ButtonShape,
            onClick = onRetry,
            enabled = !working,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Try again")
        }

        if (!showReset) {
            TextButton(onClick = { showReset = true }) {
                Text("It still doesn't work")
            }
        } else {
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = "Erase and set up again",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "This erases " + areas.joinToString(" and ") { it.description } +
                    " on this phone and sets it up again from your recovery phrase. " +
                    "Anything only this phone held is lost. Your family's other phones " +
                    "keep their copies, and you can rejoin with the same phrase.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = phrase,
                onValueChange = {
                    phrase = it
                    error = null
                },
                label = { Text("Your 12 recovery words, separated by spaces") },
                textStyle = TextStyle(fontFamily = NumericFamily),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrect = false,
                    keyboardType = KeyboardType.Password
                ),
                minLines = 3,
                enabled = !working,
                modifier = Modifier.fillMaxWidth()
            )
            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColors.dangerText
                )
            }
            OutlinedButton(
                shape = ButtonShape,
                enabled = !working && phrase.isNotBlank(),
                onClick = {
                    working = true
                    scope.launch {
                        when (val result = onReset(phrase)) {
                            StorageResetResult.Done -> onRetry()
                            StorageResetResult.InvalidPhrase -> error =
                                "That isn't a valid recovery phrase. Check the spelling and " +
                                    "the order of the words."
                            StorageResetResult.WrongPhrase -> error =
                                "That's a valid phrase, but not the one for this phone. " +
                                    "Nothing was erased."
                            is StorageResetResult.Failed -> error =
                                "Couldn't erase and set up again (${result.message}). " +
                                    "Restart your phone, then try this again."
                        }
                        working = false
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Erase and set up again")
            }
        }
    }
}
