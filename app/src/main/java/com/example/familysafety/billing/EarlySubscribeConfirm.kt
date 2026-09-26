package com.example.familysafety.billing

import android.app.Activity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Wraps the Subscribe action so tapping it during an active trial confirms first.
 *
 * There is no Play-side free-trial offer (see `BillingConfig`/PROJECT_STATUS.md item 12) —
 * the 60 free days are tracked entirely on-device by [EntitlementCalculator], and Play has no
 * way to be told "this family still has N days left." That means Play bills the card the
 * moment anyone taps Subscribe, trial or not. Confirming first is what keeps an eager early
 * subscriber from being surprised that today's tap started billing today rather than in N
 * days. Once the trial has already ended this is an ordinary purchase — nothing to confirm.
 *
 * @return an onClick lambda for a Subscribe button. It decides for itself whether to show
 *   the confirmation or launch the purchase flow immediately.
 */
@Composable
fun rememberConfirmedSubscribeAction(
    entitlement: Entitlement,
    billingManager: BillingManager
): () -> Unit {
    val context = LocalContext.current
    var pendingDaysLeft by remember { mutableStateOf<Int?>(null) }

    pendingDaysLeft?.let { daysLeft ->
        AlertDialog(
            onDismissRequest = { pendingDaysLeft = null },
            title = { Text("Subscribe now?") },
            text = {
                Text(
                    "You have $daysLeft ${if (daysLeft == 1) "day" else "days"} left in your " +
                        "free trial. Subscribing now starts billing today instead of waiting " +
                        "for the trial to end."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDaysLeft = null
                    (context as? Activity)?.let { billingManager.launchPurchaseFlow(it) }
                }) { Text("Subscribe now") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDaysLeft = null }) { Text("Wait for trial to end") }
            }
        )
    }

    return click@{
        val trial = entitlement as? Entitlement.Trial
        if (trial != null) {
            pendingDaysLeft = trial.daysLeft
            return@click
        }
        (context as? Activity)?.let { billingManager.launchPurchaseFlow(it) }
    }
}
