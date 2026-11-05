package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.HangGuard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Rule

/** The background fill of a grid sorted by rating, with a fake TMDb. The Apple core has the same tests (`RatingFillTests.swift`). */
class RatingFillTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    /** A fake TMDb: answers after [answerMs], with a rating for the titles [rated] accepts; counts the requests at once. */
    private class FakeTmdb(val answerMs: Long = 10, val rated: (String) -> Boolean = { true }) {
        val asked = ArrayList<String>()
        var running = 0
        var mostAtOnce = 0

        suspend fun fetch(id: String): Boolean {
            asked += id
            running++
            mostAtOnce = maxOf(mostAtOnce, running)
            try {
                delay(answerMs)
            } finally {
                running--
            }
            return rated(id)
        }
    }

    private val ids = (1..50).map { "t$it" }

    @Test
    fun onlyTheTitlesWithoutAnAnswerAreAskedInTheGridsOrderAndBounded() {
        val known = setOf("t1", "t3")
        assertEquals(listOf("t2", "t4", "t5"), RatingFill.toAsk(ids, { it in known }, limit = 3))
        assertEquals(RatingFill.LIMIT, RatingFill.toAsk((1..500).map { "t$it" }, { false }).size)
        assertTrue(RatingFill.toAsk(ids, { true }).isEmpty(), "everything known: nothing asked")
        assertEquals(100, RatingFill.LIMIT)
        assertEquals(20, RatingFill.FIRST_BATCH)
        assertEquals(2, RatingFill.CONCURRENCY)
    }

    @Test
    fun theGridIsResortedTwiceNotAtEachAnswer() = runTest {
        val tmdb = FakeTmdb()
        val resortedAfter = ArrayList<Int>()
        val resorts = RatingFill.run(ids, fetch = tmdb::fetch, resort = { resortedAfter += tmdb.asked.size })
        assertEquals(ids, tmdb.asked, "each title once, in order")
        assertEquals(2, resorts)
        assertTrue(resortedAfter.first() in RatingFill.FIRST_BATCH..RatingFill.FIRST_BATCH + RatingFill.CONCURRENCY, "first after a batch: $resortedAfter")
        assertEquals(ids.size, resortedAfter.last())
        assertTrue(tmdb.mostAtOnce <= RatingFill.CONCURRENCY, "at most ${RatingFill.CONCURRENCY} requests at once: ${tmdb.mostAtOnce}")
    }

    @Test
    fun withoutNewRatingsTheGridDoesNotMove() = runTest {
        val tmdb = FakeTmdb(rated = { false })
        assertEquals(0, RatingFill.run(ids, fetch = tmdb::fetch, resort = { error("no rating came: no re-sort") }))
        // Fewer than a batch: one re-sort, at the end.
        val few = FakeTmdb(rated = { it in setOf("t1", "t2", "t3") })
        assertEquals(1, RatingFill.run(ids, fetch = few::fetch, resort = {}))
    }

    @Test
    fun leavingTheGridStopsTheFill() = runTest {
        val tmdb = FakeTmdb(answerMs = 100)
        var resorts = 0
        val fill = launch { RatingFill.run(ids, fetch = tmdb::fetch, resort = { resorts++ }) }
        advanceTimeBy(350)
        fill.cancel()
        fill.join()
        assertTrue(tmdb.asked.size < ids.size, "stopped: ${tmdb.asked.size}")
        assertEquals(0, resorts, "no re-sort once the grid is left")
    }
}
