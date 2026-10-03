package com.example.familysafety.billing

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.android.billingclient.api.ProductDetails
import com.example.familysafety.main.MainViewModel
import com.example.familysafety.ui.theme.ButtonShape

/**
 * Shown in place of Chat, Files, or a member's History once [Entitlement] is [Entitlement.
 * Expired] — the tab stays in the bottom nav, only its content is replaced. [feature] names
 * what the family is missing, so the screen reads as specific rather than a generic block.
 */
@Composable
fun PaywallScreen(feature: String, viewModel: MainViewModel) {
    val productDetails by viewModel.billingManager.productDetails.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Lock,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "$feature needs a subscription",
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Your family's free trial has ended. Live location sharing, place " +
                "and speed alerts, and crash detection keep working either way — " +
                "$feature is the only thing paused.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
        Spacer(Modifier.height(24.dp))
        Button(
            shape = ButtonShape,
            onClick = {
                (context as? Activity)?.let { viewModel.billingManager.launchPurchaseFlow(it) }
            },
            modifier = Modifier.fillMaxWidth(0.8f)
        ) {
            Text("Subscribe" + priceSuffix(productDetails))
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Any family member can subscribe to cover everyone. Already have a " +
                "code? Redeem it in Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

internal fun priceSuffix(productDetails: ProductDetails?): String {
    val phase = productDetails
        ?.subscriptionOfferDetails
        ?.firstOrNull()
        ?.pricingPhases
        ?.pricingPhaseList
        ?.lastOrNull() // the recurring phase, after any introductory/trial phases
        ?: return ""
    return " — ${phase.formattedPrice}/${billingPeriodLabel(phase.billingPeriod)}"
}

/**
 * Renders [content] when the family is entitled, [PaywallScreen] otherwise. The single call
 * site every gated screen goes through, so the rule for what counts as gated lives in one
 * place — [Entitlement.isEntitled].
 */
@Composable
fun GatedFeature(feature: String, entitlement: Entitlement, viewModel: MainViewModel, content: @Composable () -> Unit) {
    if (entitlement.isEntitled) content() else PaywallScreen(feature, viewModel)
}

/** ISO 8601 durations Play uses for billing periods: P1M = monthly, P1Y = yearly, etc. */
private fun billingPeriodLabel(isoPeriod: String): String = when (isoPeriod) {
    "P1W" -> "week"
    "P1M" -> "month"
    "P3M" -> "3 months"
    "P6M" -> "6 months"
    "P1Y" -> "year"
    else -> isoPeriod
}
