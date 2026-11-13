package com.markedoe.ondo.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.ui.tv.TvPinnedScrolling
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** How the rows under a TV's header scroll to the focused card (lengths in pixels). */
@OptIn(ExperimentalFoundationApi::class)
class TvPinnedScrollingTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    private val spec = TvPinnedScrolling(lead = 104f)

    @Test
    fun aCardWhollyInViewBelowTheLeadStaysWhereItIs() {
        // The first row, right below the header: moving along it moves only the focus.
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
        // Its frame grown by the focus (4 % past either end, 24 px) still inside: its end at 646 - 24.
        assertEquals(300f - 22f, spec.calculateScrollDistance(offset = 300f, size = 600f, containerSize = 646f), 0.01f)
    }

    @Test
    fun aCardWhoseGrownFrameWouldPassTheBottomComesUpToTheLead() {
        // In its place it ends at 640 of 646, but grown by its focus (13.44 px below) it would be cut.
        assertEquals(304f - 104f, spec.calculateScrollDistance(offset = 304f, size = 336f, containerSize = 646f), 0.01f)
    }

    @Test
    fun theNextCardAlongTheRowStaysThoughItsFocusHasNotGrownItYet() {
        // Movies at 960 × 540 dp, xhdpi: rows 289 dp tall. Down: a 222 dp poster asked into view grown by its focus (1.06).
        val vod = TvPinnedScrolling(lead = 104f)
        val grown = 444f * 1.06f
        val landed = 700f - vod.calculateScrollDistance(offset = 700f, size = grown, containerSize = 578f)
        // Right: the next poster of the row, the same middle, asked into view at its place's size before it grows.
        val middle = landed + grown / 2f
        assertEquals(0f, vod.calculateScrollDistance(offset = middle - 222f, size = 444f, containerSize = 578f), 0f)
    }

    @Test
    fun aListToTheScreensBottomKeepsItsEdgeBelowTheFocusedCard() {
        // Home at 960 × 540 dp, xhdpi: rows 308 dp tall, a 222 dp poster, the 27 dp a TV may crop kept below it.
        val home = TvPinnedScrolling(lead = 100f, edge = 54f)
        // The first row under the frame: its poster would end 20 dp above the screen's bottom, then grow 9 dp: up to the lead.
        assertEquals(132f - 100f, home.calculateScrollDistance(offset = 132f, size = 444f, containerSize = 616f), 0.01f)
        // At the lead, it ends 36 dp up: its growth and the edge below it, it stays.
        assertEquals(0f, home.calculateScrollDistance(offset = 100f, size = 444f, containerSize = 616f), 0f)
        // No room for both: the card keeps clear of the edge, its top no higher than the list's top.
        assertEquals(100f - 42f, home.calculateScrollDistance(offset = 100f, size = 500f, containerSize = 616f), 0.01f)
    }
}
