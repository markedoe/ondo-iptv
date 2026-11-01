package com.markedoe.ondo.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import com.markedoe.ondo.ui.tv.TvPinnedScrolling
import org.junit.Assert.assertEquals
import org.junit.Test

/** How the rows under a TV's header scroll to the focused card (lengths in pixels). */
@OptIn(ExperimentalFoundationApi::class)
class TvPinnedScrollingTest {
    private val spec = TvPinnedScrolling(lead = 104f)

    @Test
    fun aCardWhollyInViewBelowTheLeadStaysWhereItIs() {
        // The first row below the page's chips: moving along it moves only the focus.
        assertEquals(0f, spec.calculateScrollDistance(offset = 272f, size = 336f, containerSize = 646f), 0f)
        assertEquals(0f, spec.calculateScrollDistance(offset = 104f, size = 336f, containerSize = 646f), 0f)
    }

    @Test
    fun aCardOutOfViewLandsAtTheLead() {
        // The next row, below the bottom: its card comes up to the lead.
        assertEquals(500f - 104f, spec.calculateScrollDistance(offset = 500f, size = 336f, containerSize = 646f), 0f)
        // A row above, under the header: its card comes down to the lead.
        assertEquals(-40f - 104f, spec.calculateScrollDistance(offset = -40f, size = 336f, containerSize = 646f), 0f)
        // Above the lead, though on screen: to the lead too.
        assertEquals(60f - 104f, spec.calculateScrollDistance(offset = 60f, size = 336f, containerSize = 646f), 0f)
    }

    @Test
    fun aCardTallerThanTheRoomBelowTheLeadEndsAtTheBottom() {
        assertEquals(300f - 46f, spec.calculateScrollDistance(offset = 300f, size = 600f, containerSize = 646f), 0f)
    }
}
