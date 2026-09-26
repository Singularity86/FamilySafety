package com.example.familysafety.billing

import android.app.Activity
import android.content.Context
import android.util.Base64
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import com.example.familysafety.group.GroupStateManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps Play Billing for the one subscription this app sells. There is no server: a purchase
 * is verified on-device against Play's public licensing key (safe to ship — it can only
 * verify, never forge), and once verified this device tells the rest of the family via the
 * ordinary group-sync channel ([EntitlementRepository.recordSubscriptionConfirmed]).
 *
 * [refreshFromPlay] is the only path that ever records a fresh confirmation, and it is called
 * both right after a purchase and roughly daily by [SubscriptionRefreshWorker] — a lapsed,
 * cancelled, or refunded subscription simply stops being re-confirmed and ages out on its own
 * (see [EntitlementCalculator.SUBSCRIPTION_STALENESS_MS]); there is no explicit "revoke".
 */
@Singleton
class BillingManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val entitlementRepository: EntitlementRepository,
    private val groupStateManager: GroupStateManager
) : PurchasesUpdatedListener {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val connectMutex = Mutex()
    @Volatile private var connected = false

    private val billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        // Neither toggle applies to us: this app only sells the one auto-renewing
        // subscription, never a one-time product or a prepaid plan. The Builder still
        // requires *a* PendingPurchasesParams since 8.0.0 removed the no-arg overload.
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().build())
        .build()

    private val _productDetails = MutableStateFlow<ProductDetails?>(null)
    val productDetails: StateFlow<ProductDetails?> = _productDetails.asStateFlow()

    private suspend fun ensureConnected() {
        if (connected) return
        connectMutex.withLock {
            if (connected) return
            val result = suspendCancellableCoroutine { continuation ->
                billingClient.startConnection(object : BillingClientStateListener {
                    override fun onBillingSetupFinished(billingResult: BillingResult) {
                        if (continuation.isActive) continuation.resumeWith(Result.success(billingResult))
                    }
                    override fun onBillingServiceDisconnected() {
                        connected = false
                    }
                })
            }
            connected = result.responseCode == BillingClient.BillingResponseCode.OK
            if (!connected) {
                Timber.w("$TAG: connect failed (${result.responseCode}): ${result.debugMessage}")
            }
        }
    }

    suspend fun loadProductDetails(): ProductDetails? {
        ensureConnected()
        if (!connected) return null

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(BillingConfig.SUBSCRIPTION_PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                )
            )
            .build()

        val result = billingClient.queryProductDetails(params)
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            Timber.w("$TAG: product query failed: ${result.billingResult.debugMessage}")
            return null
        }
        val details = result.productDetailsList?.firstOrNull()
        _productDetails.value = details
        return details
    }

    /** Opens Play's purchase sheet. Must be called from an Activity context. */
    fun launchPurchaseFlow(activity: Activity) {
        scope.launch {
            val details = _productDetails.value ?: loadProductDetails()
            if (details == null) {
                Timber.w("$TAG: no product details — cannot launch purchase flow")
                return@launch
            }
            val offerToken = details.subscriptionOfferDetails?.firstOrNull()?.offerToken
            if (offerToken == null) {
                Timber.w("$TAG: product has no subscription offer to purchase")
                return@launch
            }
            val flowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                    listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(details)
                            .setOfferToken(offerToken)
                            .build()
                    )
                )
                .build()
            billingClient.launchBillingFlow(activity, flowParams)
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        if (billingResult.responseCode != BillingClient.BillingResponseCode.OK || purchases == null) {
            if (billingResult.responseCode != BillingClient.BillingResponseCode.USER_CANCELED) {
                Timber.w("$TAG: purchase update failed (${billingResult.responseCode}): ${billingResult.debugMessage}")
            }
            return
        }
        scope.launch { purchases.forEach { handlePurchase(it) } }
    }

    /**
     * Asks Play whether the subscription is currently active and, if so, verifies and records
     * it. Returns whether an active, verified subscription was found — callers that only
     * care about the family staying entitled don't need to inspect anything further, the
     * recording itself is what keeps [EntitlementCalculator] happy.
     */
    suspend fun refreshFromPlay(): Boolean {
        ensureConnected()
        if (!connected) return false

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        val result = billingClient.queryPurchasesAsync(params)
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            Timber.w("$TAG: purchase query failed: ${result.billingResult.debugMessage}")
            return false
        }

        val active = result.purchasesList.firstOrNull {
            it.purchaseState == Purchase.PurchaseState.PURCHASED &&
                it.products.contains(BillingConfig.SUBSCRIPTION_PRODUCT_ID)
        } ?: return false

        return handlePurchase(active)
    }

    private suspend fun handlePurchase(purchase: Purchase): Boolean {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return false
        if (!verifyPurchaseSignature(purchase)) {
            Timber.w("$TAG: purchase signature failed verification — not trusting it")
            return false
        }

        if (!purchase.isAcknowledged) {
            val ackResult = billingClient.acknowledgePurchase(
                AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
            )
            if (ackResult.responseCode != BillingClient.BillingResponseCode.OK) {
                // Play auto-refunds an unacknowledged purchase after 3 days — log it, but
                // still record the confirmation below since the purchase itself is genuine.
                Timber.w("$TAG: acknowledge failed: ${ackResult.debugMessage}")
            }
        }

        val myMemberId = groupStateManager.localMember.value?.memberId ?: return false
        entitlementRepository.recordSubscriptionConfirmed(myMemberId, System.currentTimeMillis())
        return true
    }

    /**
     * RSA-SHA1 over `purchase.originalJson`, checked against [BillingConfig.
     * PLAY_LICENSE_PUBLIC_KEY_BASE64] — the standard on-device verification Play documents,
     * chosen specifically because it needs no server. It only proves the purchase came from
     * Play for this app; it says nothing about whether it is still active, which is what
     * [refreshFromPlay] asking Play directly is for.
     */
    private fun verifyPurchaseSignature(purchase: Purchase): Boolean {
        return try {
            val keyBytes = Base64.decode(BillingConfig.PLAY_LICENSE_PUBLIC_KEY_BASE64, Base64.DEFAULT)
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes))
            val signature = Signature.getInstance("SHA1withRSA")
            signature.initVerify(publicKey)
            signature.update(purchase.originalJson.toByteArray(Charsets.UTF_8))
            signature.verify(Base64.decode(purchase.signature, Base64.DEFAULT))
        } catch (e: Exception) {
            Timber.e(e, "$TAG: purchase signature verification error")
            false
        }
    }

    companion object {
        private const val TAG = "BillingManager"
    }
}
