package com.markedoe.ondo.core.fuzz

import com.markedoe.ondo.core.epg.EpgIndex
import com.markedoe.ondo.core.model.Channel
import com.markedoe.ondo.core.model.EpgProgram
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What is on now and next, on guides whose programmes touch, leave gaps, start together and overlap (a film around a
 * news flash): the index gives what going through every programme gives.
 */
class EpgIndexFuzzTest {
    private val channel = Channel(id = "x", name = "TV1", categoryId = "c", streamUrl = "http://example.com/1", epgId = "tv1")

    @Test
    fun nowAndNextAreThoseOfEveryProgramme() {
        for (seed in 0 until 3_000L) {
            val fuzz = Fuzz(seed)
            var start = 0L
            val programs = List(1 + fuzz.int(12)) { index ->
                start += fuzz.pick(0L, 0L, 10L, 30L, 60L)
                EpgProgram("tv1", start, start + fuzz.pick(1L, 10L, 30L, 60L, 300L), "P$index")
            }
            val index = EpgIndex(programs)
            // The order the index keeps them in: by start, the order of the list for equal starts.
            val sorted = programs.sortedBy { it.start }
            for (now in -1L..start + 310) {
                val on = sorted.indices.lastOrNull { sorted[it].start <= now && now < sorted[it].end }
                val current = on?.let { sorted[it] }
                val count = 1 + fuzz.int(3)
                val upcoming = if (on != null) {
                    listOf(sorted[on]) + sorted.drop(on + 1).filter { it.end > now }.take(count - 1)
                } else {
                    sorted.filter { it.start > now }.take(count)
                }
                val label = "seed $seed, now $now: $sorted"
                assertEquals(current, index.current(channel, now), label)
                assertEquals(current, index.current("tv1", now), label)
                assertEquals(upcoming, index.upcoming(channel, now, count), label)
            }
        }
    }
}
