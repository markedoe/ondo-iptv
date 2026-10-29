package com.markedoe.ondo.data

import android.app.Activity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The optional tips: three one-time products of Google Play, consumed at once, so that a tip can be given again. A tip
 * unlocks nothing: everything in Ondo is there for everyone, with or without one.
 */
enum class Tip(val productId: String) {
    SMALL("tip_small"),
    MEDIUM("tip_medium"),
    LARGE("tip_large"),
}

/** A tip as Google Play sells it: its price in the viewer's currency ("1,99 €"). */
data class TipOffer(val tip: Tip, val price: String)

/** What happened to the last tip, shown once under the tips. */
enum class TipOutcome { THANKS, PENDING, FAILED }

data class TipsState(
    /** Null while Google Play answers, empty when it cannot sell tips here (no Google Play, no products, no network). */
    val offers: List<TipOffer>? = null,
    val outcome: TipOutcome? = null,
) {
    val loading: Boolean get() = offers == null
    val unavailable: Boolean get() = offers?.isEmpty() == true
}

/** A purchase as Google Play reports it, reduced to what the tips need. */
data class TipPurchase(val token: String, val productIds: List<String>, val state: State) {
    enum class State { PURCHASED, PENDING, OTHER }
}

/** A purchase flow's end, as Google Play reports it to the app. */
sealed interface TipUpdate {
    data class Purchased(val purchases: List<TipPurchase>) : TipUpdate
    data object Cancelled : TipUpdate
    data object Failed : TipUpdate
}

/** Google Play Billing, behind what the tips use of it (a fake one in tests). */
interface TipBilling {
    /** Purchase flows that end, as they end. */
    val updates: Flow<TipUpdate>

    /** True once connected to Google Play (false: no Google Play, or it is unreachable). */
    suspend fun connect(): Boolean

    /** The offers of these tips, in Google Play's order of the ids; null when Google Play does not answer. */
    suspend fun offers(tips: List<Tip>): List<TipOffer>?

    /** Purchases Google Play still holds: a tip bought while the app was closed, not consumed yet. */
    suspend fun heldPurchases(): List<TipPurchase>

    /** Consumes a tip's purchase: Google Play keeps the money and the tip can be given again. */
    suspend fun consume(token: String): Boolean

    /** Opens Google Play's purchase sheet; false when it cannot (the tips are not loaded). */
    fun launch(activity: Activity, tip: Tip): Boolean
}

/**
 * The tips' state for Settings: Google Play's offers, and each purchase consumed as soon as it is paid, including one
 * left from an earlier run (Google Play refunds a one-time purchase that is not consumed or acknowledged in 3 days).
 */
class TipJar(private val billing: TipBilling, private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(TipsState())
    val state: StateFlow<TipsState> = mutableState.asStateFlow()
    private val starting = Mutex()
    private var started = false

    /** Connects and loads the offers once; again after a failure, when Settings opens. */
    fun start() {
        scope.launch {
            starting.withLock {
                if (started && !mutableState.value.unavailable) return@launch
                if (!started) {
                    started = true
                    scope.launch { billing.updates.collect(::onUpdate) }
                }
                mutableState.update { it.copy(offers = null) }
                if (!billing.connect()) {
                    mutableState.update { it.copy(offers = emptyList()) }
                    return@launch
                }
                val offers = billing.offers(Tip.entries).orEmpty()
                mutableState.update { it.copy(offers = offers) }
                billing.heldPurchases().forEach { settle(it, announce = false) }
            }
        }
    }

    fun purchase(activity: Activity, tip: Tip) {
        mutableState.update { it.copy(outcome = null) }
        if (!billing.launch(activity, tip)) mutableState.update { it.copy(outcome = TipOutcome.FAILED) }
    }

    /** The message under the tips was read. */
    fun dismissOutcome() = mutableState.update { it.copy(outcome = null) }

    private suspend fun onUpdate(update: TipUpdate) {
        when (update) {
            is TipUpdate.Purchased -> update.purchases.forEach { settle(it, announce = true) }
            TipUpdate.Cancelled -> Unit
            TipUpdate.Failed -> mutableState.update { it.copy(outcome = TipOutcome.FAILED) }
        }
    }

    private suspend fun settle(purchase: TipPurchase, announce: Boolean) {
        val ours = purchase.productIds.any { id -> Tip.entries.any { it.productId == id } }
        if (!ours) return
        when (purchase.state) {
            TipPurchase.State.PURCHASED -> {
                val consumed = billing.consume(purchase.token)
                if (announce) mutableState.update { it.copy(outcome = if (consumed) TipOutcome.THANKS else TipOutcome.FAILED) }
            }
            TipPurchase.State.PENDING -> if (announce) mutableState.update { it.copy(outcome = TipOutcome.PENDING) }
            TipPurchase.State.OTHER -> Unit
        }
    }
}
