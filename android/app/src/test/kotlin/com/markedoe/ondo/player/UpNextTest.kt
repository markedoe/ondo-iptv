package com.markedoe.ondo.player

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.markedoe.ondo.core.model.Episode
import com.markedoe.ondo.core.model.Series
import com.markedoe.ondo.ui.theme.OndoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Up next: offered in the last seconds of an episode that has a next one; Play now and the cross answer it. */
@RunWith(RobolectricTestRunner::class)
class UpNextTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val harbor = Series(id = "s1", name = "Harbor", categoryId = "c")
    private val second = Episode(id = "e2", title = "The Tide", season = 1, number = 2, streamUrl = "http://example.com/e2.mp4")

    @Test
    fun theNextEpisodeIsOfferedInTheLastTwentySeconds() {
        assertNull(UpNext.offer(harbor to second, leftMs = 25_000))
        assertEquals(UpNext("Harbor", "S01E02 · The Tide", 20), UpNext.offer(harbor to second, leftMs = 20_000))
        assertEquals(1, UpNext.offer(harbor to second, leftMs = 400)?.seconds)
        assertEquals(0, UpNext.offer(harbor to second, leftMs = 0)?.seconds)
        // The last episode, a movie: nothing to offer.
        assertNull(UpNext.offer(null, leftMs = 5_000))
    }

    @Test
    fun playNowAndTheCrossAnswerIt() {
        var played = 0
        var declined = 0
        compose.setContent {
            OndoTheme { UpNextCard(UpNext("Harbor", "S01E02 · The Tide", 12), television = false, onPlay = { played++ }, onDecline = { declined++ }) }
        }
        compose.onNodeWithText("Harbor").assertExists()
        compose.onNodeWithText("S01E02 · The Tide").assertExists()
        compose.onNodeWithTag("upNextSeconds").assertTextContains("Plays in 12 s")
        compose.onNodeWithTag("upNextPlay").performClick()
        compose.onNodeWithTag("upNextDecline").performClick()
        assertEquals(1, played)
        assertEquals(1, declined)
    }
}
