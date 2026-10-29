package com.markedoe.ondo.data

import android.app.Activity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** The tips: Google Play's prices, every paid tip consumed (now or at the next start), and a word for each outcome. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TipJarTest {
    private class FakeBilling(
        var connects: Boolean = true,
        var prices: Map<Tip, String> = mapOf(Tip.SMALL to "1,99 €", Tip.MEDIUM to "4,99 €", Tip.LARGE to "9,99 €"),
        var held: List<TipPurchase> = emptyList(),
        var consumes: Boolean = true,
    ) : TipBilling {
        override val updates = MutableSharedFlow<TipUpdate>(extraBufferCapacity = 4)
        val consumed = mutableListOf<String>()
        val launched = mutableListOf<Tip>()
        var connections = 0

        override suspend fun connect(): Boolean {
            connections++
            return connects
        }

        override suspend fun offers(tips: List<Tip>) = tips.mapNotNull { tip -> prices[tip]?.let { TipOffer(tip, it) } }
        override suspend fun heldPurchases() = held

        override suspend fun consume(token: String): Boolean {
            consumed += token
            return consumes
        }

        override fun launch(activity: Activity, tip: Tip): Boolean {
            launched += tip
            return prices.containsKey(tip)
        }
    }

    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).get()

    @Test
    fun theThreeTipsShowGooglePlaysPricesInOrder() = runTest {
        val billing = FakeBilling()
        val jar = TipJar(billing, backgroundScope)
        assertTrue(jar.state.value.loading)
        jar.start()
        advanceUntilIdle()
        assertEquals(listOf("1,99 €", "4,99 €", "9,99 €"), jar.state.value.offers?.map { it.price })
        assertEquals(Tip.entries, jar.state.value.offers?.map { it.tip })
    }

    @Test
    fun withoutGooglePlayTheTipsSaySoAndTryAgainNextTime() = runTest {
        val billing = FakeBilling(connects = false)
        val jar = TipJar(billing, backgroundScope)
        jar.start()
        advanceUntilIdle()
        assertTrue(jar.state.value.unavailable)
        billing.connects = true
        jar.start()
        advanceUntilIdle()
        assertEquals(3, jar.state.value.offers?.size)
        assertEquals(2, billing.connections)
    }

    @Test
    fun productsGooglePlayDoesNotSellLeaveTheTipsUnavailable() = runTest {
        val jar = TipJar(FakeBilling(prices = emptyMap()), backgroundScope)
        jar.start()
        advanceUntilIdle()
        assertTrue(jar.state.value.unavailable)
    }

    @Test
    fun aPaidTipIsConsumedAndThanked() = runTest {
        val billing = FakeBilling()
        val jar = TipJar(billing, backgroundScope)
        jar.start()
        advanceUntilIdle()
        jar.purchase(activity, Tip.MEDIUM)
        assertEquals(listOf(Tip.MEDIUM), billing.launched)
        billing.updates.emit(TipUpdate.Purchased(listOf(TipPurchase("t1", listOf("tip_medium"), TipPurchase.State.PURCHASED))))
        advanceUntilIdle()
        assertEquals(listOf("t1"), billing.consumed)
        assertEquals(TipOutcome.THANKS, jar.state.value.outcome)
        jar.dismissOutcome()
        assertNull(jar.state.value.outcome)
    }

    @Test
    fun aPendingTipIsNotConsumedYet() = runTest {
        val billing = FakeBilling()
        val jar = TipJar(billing, backgroundScope)
        jar.start()
        advanceUntilIdle()
        billing.updates.emit(TipUpdate.Purchased(listOf(TipPurchase("t2", listOf("tip_small"), TipPurchase.State.PENDING))))
        advanceUntilIdle()
        assertTrue(billing.consumed.isEmpty())
        assertEquals(TipOutcome.PENDING, jar.state.value.outcome)
    }

    @Test
    fun aTipPaidWhileTheAppWasClosedIsConsumedAtTheNextStart() = runTest {
        val billing = FakeBilling(held = listOf(
            TipPurchase("old", listOf("tip_large"), TipPurchase.State.PURCHASED),
            TipPurchase("waiting", listOf("tip_small"), TipPurchase.State.PENDING),
            TipPurchase("other", listOf("something_else"), TipPurchase.State.PURCHASED),
        ))
        val jar = TipJar(billing, backgroundScope)
        jar.start()
        advanceUntilIdle()
        assertEquals(listOf("old"), billing.consumed)
        assertNull(jar.state.value.outcome)
    }

    @Test
    fun aCancelledTipSaysNothingAndAFailedOneSaysSo() = runTest {
        val billing = FakeBilling()
        val jar = TipJar(billing, backgroundScope)
        jar.start()
        advanceUntilIdle()
        billing.updates.emit(TipUpdate.Cancelled)
        advanceUntilIdle()
        assertNull(jar.state.value.outcome)
        billing.updates.emit(TipUpdate.Failed)
        advanceUntilIdle()
        assertEquals(TipOutcome.FAILED, jar.state.value.outcome)
    }

    @Test
    fun aTipThatCannotBeConsumedIsNotThanked() = runTest {
        val billing = FakeBilling(consumes = false)
        val jar = TipJar(billing, backgroundScope)
        jar.start()
        advanceUntilIdle()
        billing.updates.emit(TipUpdate.Purchased(listOf(TipPurchase("t3", listOf("tip_small"), TipPurchase.State.PURCHASED))))
        advanceUntilIdle()
        assertEquals(TipOutcome.FAILED, jar.state.value.outcome)
    }

    @Test
    fun aTipThatCannotOpenGooglePlaySaysSo() = runTest {
        val billing = FakeBilling()
        val jar = TipJar(billing, backgroundScope)
        jar.start()
        advanceUntilIdle()
        billing.prices = emptyMap()
        jar.purchase(activity, Tip.LARGE)
        assertEquals(TipOutcome.FAILED, jar.state.value.outcome)
    }
}
