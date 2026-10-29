package com.markedoe.ondo.data

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/** [TipBilling] on Google Play Billing: one-time products, pending purchases accepted, reconnecting by itself. */
class PlayTipBilling(context: Context) : TipBilling {
    private val purchaseUpdates = MutableSharedFlow<TipUpdate>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val updates: Flow<TipUpdate> = purchaseUpdates

    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener { result, purchases ->
            purchaseUpdates.tryEmit(
                when (result.responseCode) {
                    BillingClient.BillingResponseCode.OK -> TipUpdate.Purchased(purchases.orEmpty().map(::tipPurchase))
                    BillingClient.BillingResponseCode.USER_CANCELED -> TipUpdate.Cancelled
                    else -> TipUpdate.Failed
                },
            )
        }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    /** Google Play's own products, kept to open their purchase sheet. */
    private val details = ConcurrentHashMap<String, ProductDetails>()

    override suspend fun connect(): Boolean {
        if (client.isReady) return true
        return suspendCancellableCoroutine { continuation ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (continuation.isActive) continuation.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                }

                override fun onBillingServiceDisconnected() {
                    if (continuation.isActive) continuation.resume(false)
                }
            })
        }
    }

    override suspend fun offers(tips: List<Tip>): List<TipOffer>? {
        val params = QueryProductDetailsParams.newBuilder().setProductList(
            tips.map { QueryProductDetailsParams.Product.newBuilder().setProductId(it.productId).setProductType(BillingClient.ProductType.INAPP).build() },
        ).build()
        val found: List<ProductDetails> = suspendCancellableCoroutine { continuation ->
            client.queryProductDetailsAsync(params) { result, products ->
                val list = if (result.responseCode == BillingClient.BillingResponseCode.OK) products.productDetailsList else null
                if (continuation.isActive) continuation.resume(list ?: emptyList())
            }
        }
        found.forEach { details[it.productId] = it }
        return tips.mapNotNull { tip ->
            val price = details[tip.productId]?.oneTimePurchaseOfferDetails?.formattedPrice ?: return@mapNotNull null
            TipOffer(tip, price)
        }
    }

    override suspend fun heldPurchases(): List<TipPurchase> = suspendCancellableCoroutine { continuation ->
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        client.queryPurchasesAsync(params) { result, purchases ->
            val list = if (result.responseCode == BillingClient.BillingResponseCode.OK) purchases.map(::tipPurchase) else emptyList()
            if (continuation.isActive) continuation.resume(list)
        }
    }

    override suspend fun consume(token: String): Boolean = suspendCancellableCoroutine { continuation ->
        client.consumeAsync(ConsumeParams.newBuilder().setPurchaseToken(token).build()) { result, _ ->
            if (continuation.isActive) continuation.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
        }
    }

    override fun launch(activity: Activity, tip: Tip): Boolean {
        val product = details[tip.productId] ?: return false
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product).build()))
            .build()
        return client.launchBillingFlow(activity, params).responseCode == BillingClient.BillingResponseCode.OK
    }

    private fun tipPurchase(purchase: Purchase) = TipPurchase(
        token = purchase.purchaseToken,
        productIds = purchase.products,
        state = when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> TipPurchase.State.PURCHASED
            Purchase.PurchaseState.PENDING -> TipPurchase.State.PENDING
            else -> TipPurchase.State.OTHER
        },
    )
}
