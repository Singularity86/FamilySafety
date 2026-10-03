package com.example.familysafety.billing

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.familysafety.main.MainViewModel
import kotlinx.coroutines.launch
import com.example.familysafety.ui.theme.ButtonShape

/**
 * Settings' "how is this family's access doing" card: current status, a way to subscribe or
 * manage an existing subscription, and a "Have a code?" redeem field for a developer grant.
 */
@Composable
fun MembershipCard(viewModel: MainViewModel, context: Context) {
    val entitlement by viewModel.entitlement.collectAsState()
    val productDetails by viewModel.billingManager.productDetails.collectAsState()
    val subscribeAction = rememberConfirmedSubscribeAction(entitlement, viewModel.billingManager)
    var code by remember { mutableStateOf("") }
    var redeemMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Membership", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = statusText(entitlement),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            when (entitlement) {
                is Entitlement.Subscribed -> {
                    OutlinedButton(
                        shape = ButtonShape,
                        onClick = { context.startActivity(manageSubscriptionIntent(context)) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Manage subscription") }
                }
                is Entitlement.Trial, Entitlement.Expired -> {
                    Button(
                        shape = ButtonShape,
                        onClick = subscribeAction,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Reads the live Play price so a change in Play Console never
                        // requires an app update just to keep this text accurate; falls
                        // back to the plan price only if that hasn't loaded yet.
                        Text("Subscribe" + priceSuffix(productDetails).ifBlank { " — \$4/month" })
                    }
                }
                else -> Unit // Grandfathered / Granted: nothing to manage or buy.
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            Text("Have a code?", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = code,
                onValueChange = { code = it; redeemMessage = null },
                singleLine = true,
                label = { Text("Grant code") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(
                shape = ButtonShape,
                onClick = {
                    scope.launch {
                        val result = viewModel.entitlementRepository.redeemGrantCode(code)
                        redeemMessage = when (result) {
                            is GrantCodeResult.Valid -> { code = ""; "Redeemed — thank you!" }
                            GrantCodeResult.Expired -> "That code has expired."
                            GrantCodeResult.BadSignature -> "That code isn't valid for this family."
                            GrantCodeResult.InvalidFormat -> "That doesn't look like a valid code."
                        }
                    }
                },
                enabled = code.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Redeem") }
            redeemMessage?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun statusText(entitlement: Entitlement): String = when (entitlement) {
    Entitlement.Grandfathered -> "Your family has full access — no charge, ever."
    is Entitlement.Granted -> "Complimentary access" +
        (entitlement.expiresAtEpochMs?.let { " until it expires." } ?: ", no expiration.")
    is Entitlement.Trial -> "${entitlement.daysLeft} ${if (entitlement.daysLeft == 1) "day" else "days"} left in your free trial."
    Entitlement.Subscribed -> "Subscribed. Chat, History and Files are active for your whole family."
    Entitlement.Expired -> "Your free trial has ended. Chat, History and Files are paused."
}

private fun manageSubscriptionIntent(context: Context): Intent {
    val uri = Uri.parse(
        "https://play.google.com/store/account/subscriptions" +
            "?sku=${BillingConfig.SUBSCRIPTION_PRODUCT_ID}&package=${context.packageName}"
    )
    return Intent(Intent.ACTION_VIEW, uri)
}
