package com.markedoe.ondo.ui

import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.model.Episode
import com.markedoe.ondo.core.model.MediaDetails
import com.markedoe.ondo.core.model.Season
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.screens.DetailActions
import com.markedoe.ondo.ui.screens.SeriesDetailUiState
import com.markedoe.ondo.ui.theme.OndoTheme
import com.markedoe.ondo.ui.tv.TelevisionFrame
import com.markedoe.ondo.ui.tv.TvFocusRing
import com.markedoe.ondo.ui.tv.TvSeriesDetailScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * On a TV, the season the page shows (selected: a white chip) and the season the remote is on (focused: a white ring
 * around the chip, which grows) never look alike: as the remote moves along the season chips, the chip it is on has
 * the focus ring and no other does, and the selected season keeps its selection whether the remote is on it or not.
 * The other chip rows (versions, genres and year, Search's filters, the player's seasons) are the same [TvChip].
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TvChipFocusTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val compose = createComposeRule()

    private val series = Fixtures.harborLights

    private val state = SeriesDetailUiState(
        item = ShowcaseItem.SeriesItem(series),
        details = MediaDetails(title = series.name),
        seasons = (1..4).map { number ->
            Season(number, "Season $number", listOf(Episode("e$number", "Pilot", number, 1, "http://example.com/$number.mp4")))
        },
        selectedSeason = 3,
    )

    private fun chip(number: Int) = compose.onNodeWithTag("season-$number").fetchSemanticsNode().config

    private fun ring(number: Int) = chip(number).getOrNull(TvFocusRing) == true

    private fun selected(number: Int) = chip(number).getOrNull(SemanticsProperties.Selected) == true

    /** Whether the remote is on the chip (the focus is on its node or inside it). */
    private fun focused(number: Int) = compose.onAllNodes(
        isFocused() and (hasTestTag("season-$number") or hasAnyAncestor(hasTestTag("season-$number"))),
    ).fetchSemanticsNodes().isNotEmpty()

    /** The season the remote is on, as the screen shows it: its ring, on that chip alone, the third still selected. */
    private fun assertRemoteOn(number: Int) {
        assertEquals("the remote is on season $number", listOf(number), (1..4).filter(::focused))
        assertEquals("the ring is on season $number alone", listOf(number), (1..4).filter(::ring))
        assertEquals("season 3 stays selected", listOf(3), (1..4).filter(::selected))
    }

    @Test
    fun theRemotesSeasonAndTheSelectedSeasonLookApart() {
        var inputModes: InputModeManager? = null
        compose.setContent {
            inputModes = LocalInputModeManager.current
            OndoTheme { TelevisionFrame { TvSeriesDetailScreen(state, DetailActions()) } }
        }
        compose.runOnIdle { inputModes?.requestInputMode(InputMode.Keyboard) }
        compose.onNodeWithTag("season-1").requestFocus()
        compose.waitForIdle()
        assertRemoteOn(1)
        for (number in 2..4) {
            compose.onAllNodes(isFocused()).onLast().performKeyInput { pressKey(Key.DirectionRight) }
            compose.waitForIdle()
            assertRemoteOn(number)
        }
        // Back on the selected season: both at once.
        compose.onAllNodes(isFocused()).onLast().performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitForIdle()
        assertRemoteOn(3)
        assertEquals(true, ring(3) && selected(3))
    }
}
