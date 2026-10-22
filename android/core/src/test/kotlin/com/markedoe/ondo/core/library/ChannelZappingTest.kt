package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.model.Channel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChannelZappingTest {
    private fun channel(id: String, number: Int? = null) = Channel(id = id, name = "Channel $id", number = number, categoryId = "c", streamUrl = "http://x/$id")

    private val numbered = listOf(channel("a", 1), channel("b", 2), channel("c", 7))
    private val unnumbered = listOf(channel("a"), channel("b"), channel("c"))

    @Test
    fun upAndDownMoveToTheNextAndPreviousChannel() {
        assertEquals("b", ChannelZapping.step(numbered, "a", 1)?.id)
        assertEquals("a", ChannelZapping.step(numbered, "b", -1)?.id)
    }

    @Test
    fun theListWrapsAround() {
        assertEquals("a", ChannelZapping.step(numbered, "c", 1)?.id)
        assertEquals("c", ChannelZapping.step(numbered, "a", -1)?.id)
    }

    @Test
    fun aChannelNoLongerListedStartsFromTheEnds() {
        // Its category was hidden meanwhile, or the playlist was refreshed without it.
        assertEquals("a", ChannelZapping.step(numbered, "gone", 1)?.id)
        assertEquals("c", ChannelZapping.step(numbered, "gone", -1)?.id)
    }

    @Test
    fun nothingToZapTo() {
        assertNull(ChannelZapping.step(emptyList(), "a", 1))
        assertNull(ChannelZapping.step(numbered, "a", 0))
    }

    @Test
    fun aTypedNumberFindsTheChannelOfThatNumber() {
        assertEquals("c", ChannelZapping.byNumber(numbered, 7)?.id)
        assertNull(ChannelZapping.byNumber(numbered, 3))
    }

    @Test
    fun withoutNumbersATypedNumberIsThePosition() {
        assertEquals("b", ChannelZapping.byNumber(unnumbered, 2)?.id)
        assertNull(ChannelZapping.byNumber(unnumbered, 4))
        assertNull(ChannelZapping.byNumber(unnumbered, 0))
    }
}
