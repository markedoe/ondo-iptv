package com.markedoe.ondo.core.library

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select

/**
 * A grid sorted by rating ranks the titles TMDb rated first ([WeightedRating.ratingOrder]), but TMDb is asked about a
 * title only when it shows somewhere, so most of a category has no TMDb rating yet. With the viewer's TMDb token, the
 * grid asks for the missing ones in the background, a bounded number, at TMDb's pace, and re-sorts in two steps rather
 * than at each answer (the grid would move under the remote's focus). The same rule, constants and tests as the Apple
 * core (`RatingFill.swift`).
 */
object RatingFill {
    /** Titles asked at most each time a grid is sorted by rating (the rest wait for the next time, or their own page). */
    const val LIMIT = 100

    /** Answers with a rating before the first re-sort; the second comes when the fill ends. */
    const val FIRST_BATCH = 20

    /** Requests at the same time (the pace itself is the caller's, shared with the other background requests). */
    const val CONCURRENCY = 2

    /** The titles to ask TMDb about: those it never answered for ([isKnown] false), in the grid's order, at most [limit]. */
    fun <T> toAsk(items: List<T>, isKnown: (T) -> Boolean, limit: Int = LIMIT): List<T> =
        items.asSequence().filterNot(isKnown).take(limit.coerceAtLeast(0)).toList()

    /**
     * Asks [fetch] about each item, [concurrency] at a time, in order. [fetch] answers whether TMDb gave a rating;
     * [resort] is called once [firstBatch] ratings came, then once at the end when more came since. Stops when the
     * caller is cancelled (the grid left), without a last re-sort. Returns how many times it re-sorted.
     */
    suspend fun <T> run(
        items: List<T>,
        concurrency: Int = CONCURRENCY,
        firstBatch: Int = FIRST_BATCH,
        fetch: suspend (T) -> Boolean,
        resort: suspend () -> Unit,
    ): Int {
        var resorts = 0
        var rated = 0
        var sinceResort = 0
        coroutineScope {
            val next = items.iterator()
            val running = ArrayList<kotlinx.coroutines.Deferred<Boolean>>()
            repeat(concurrency.coerceAtLeast(1)) { if (next.hasNext()) next.next().let { item -> running += async { fetch(item) } } }
            while (running.isNotEmpty()) {
                val done = select { running.forEach { job -> job.onAwait { job } } }
                running.remove(done)
                if (done.await()) {
                    rated++
                    sinceResort++
                }
                if (resorts == 0 && rated >= firstBatch && sinceResort > 0) {
                    resort()
                    resorts++
                    sinceResort = 0
                }
                if (next.hasNext()) next.next().let { item -> running += async { fetch(item) } }
            }
        }
        if (sinceResort > 0 && currentCoroutineContext().isActive) {
            resort()
            resorts++
        }
        return resorts
    }
}
