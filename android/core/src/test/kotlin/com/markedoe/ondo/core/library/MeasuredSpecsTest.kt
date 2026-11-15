package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.HangGuard
import com.markedoe.ondo.core.model.PictureRange
import com.markedoe.ondo.core.model.SpecSource
import com.markedoe.ondo.core.model.StreamSpecs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule

/** What the player measured, kept by stream: bounded, written only on change, read back. The same as Apple's MeasuredSpecsTests. */
class MeasuredSpecsTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    private val hd = StreamSpecs(width = 1920, height = 1080, range = PictureRange.SDR, videoCodec = "H.264", audioCodec = "AAC", channels = 2)

    @Test
    fun recordsOnlyWhatChanged() {
        val store = MeasuredSpecs()
        val key = MeasuredSpecs.key("p1", "42")
        assertTrue(store.record(key, hd))
        assertEquals(SpecSource.MEASURED, store[key]?.source)
        assertFalse(store.record(key, hd), "the same specs: nothing to save")
        assertFalse(store.record(key, StreamSpecs()), "nothing known: nothing kept")
        // A later reading that lacks the sound keeps the sound read before; a new size replaces the old one.
        assertTrue(store.record(key, StreamSpecs(width = 3840, height = 2160)))
        assertEquals("4K", store[key]?.resolution)
        assertEquals("2.0 AAC", store[key]?.audioLabel)
        assertNull(store[MeasuredSpecs.key("p2", "42")], "another playlist's stream 42 is another stream")
    }

    @Test
    fun keepsTheStreamsPlayedLast() {
        val store = MeasuredSpecs(capacity = 3)
        for (i in 1..4) store.record("p|$i", hd.copy(height = 1000 + i))
        assertEquals(3, store.size())
        assertNull(store["p|1"], "the oldest is forgotten")
        // Played again: kept longer than one played after it.
        store.record("p|2", hd.copy(height = 1002))
        store.record("p|5", hd)
        assertEquals(listOf("p|2", "p|4", "p|5").sorted(), store.snapshot().keys.sorted())
        assertEquals(listOf("p|4", "p|2", "p|5"), store.snapshot().keys.toList())
    }

    @Test
    fun readsBackWhatItWrote() {
        val store = MeasuredSpecs(capacity = 2000)
        for (i in 0 until 2100) store.record("p|$i", hd.copy(height = 700 + i % 5))
        assertEquals(MeasuredSpecs.CAPACITY, store.size())
        val read = MeasuredSpecs.decode(store.encode())
        assertEquals(store.snapshot(), read.snapshot())
        assertEquals(store.snapshot().keys.toList(), read.snapshot().keys.toList())
        // A smaller bound keeps the latest.
        assertEquals(listOf("p|2099"), MeasuredSpecs.decode(store.encode(), capacity = 1).snapshot().keys.toList())
        assertEquals(0, MeasuredSpecs.decode("not json").size())
        assertEquals(0, MeasuredSpecs.decode(null).size())
    }
}
