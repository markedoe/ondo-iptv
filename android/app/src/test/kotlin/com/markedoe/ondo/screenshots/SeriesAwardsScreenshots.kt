package com.markedoe.ondo.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.requestFocus
import com.github.takahirom.roborazzi.captureRoboImage
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.library.AwardsCatalogue
import com.markedoe.ondo.ui.TopLevel
import com.markedoe.ondo.ui.UiMappers
import com.markedoe.ondo.ui.components.LocalCarouselAutoAdvance
import com.markedoe.ondo.ui.screens.DetailActions
import com.markedoe.ondo.ui.screens.SeriesDetailScreen
import com.markedoe.ondo.ui.screens.VodActions
import com.markedoe.ondo.ui.screens.VodScreen
import com.markedoe.ondo.ui.theme.OndoTheme
import com.markedoe.ondo.ui.tv.TelevisionFrame
import com.markedoe.ondo.ui.tv.TvNavigationFrame
import com.markedoe.ondo.ui.tv.TvSeriesDetailScreen
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Reference screenshots of TV series awards, on the test catalogue's fictional titles with a fictional awards file: the
 * Series tab's Award winners row (TV and phone) and a series' page with its awards (TV: the first above its title and
 * the row of all of them; phone: the block after the episodes). Compared by `verifyRoborazziDebug` with
 * [BASELINES]`/tv/` and [BASELINES]`/phone/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w960dp-h540dp-land-television-xhdpi")
class SeriesAwardsScreenshots {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    // Fictional: "Harbor Lights" won, "The Archive" was nominated.
    private val state = Fixtures.state().copy(
        awards = AwardsCatalogue.parse(
            """
            {"awards":[],"films":[],
             "seriesAwards":[{"id":"emmy-drama-series","group":"emmys","names":{"en":"Primetime Emmy Award for Outstanding Drama Series"}},
                             {"id":"golden-globe-tv-drama","group":"golden-globes","names":{"en":"Golden Globe Award for Best Television Series – Drama"}}],
             "series":[{"titles":["Harbor Lights"],"year":2025,"awards":[["emmy-drama-series",2025,1],["golden-globe-tv-drama",2026,0]]},
                       {"titles":["The Archive"],"year":2024,"awards":[["golden-globe-tv-drama",2025,1]]}]}
            """.trimIndent(),
        ),
    )

    private fun show(television: Boolean, content: @Composable () -> Unit) {
        var inputModes: InputModeManager? = null
        compose.setContent {
            inputModes = LocalInputModeManager.current
            OndoTheme {
                CompositionLocalProvider(LocalCarouselAutoAdvance provides false) {
                    // On a phone, the app's own background (the app's scaffold gives it to every page).
                    if (television) TelevisionFrame(content)
                    else Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
                }
            }
        }
        compose.waitForIdle()
        if (television) compose.runOnIdle { inputModes?.requestInputMode(InputMode.Keyboard) }
        compose.mainClock.autoAdvance = false
        advance()
    }

    private fun advance() {
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    private fun capture(folder: String, name: String) = compose.onRoot().captureRoboImage("$BASELINES/$folder/en-US/$name.png", BaselineOptions)

    @Test
    fun theSeriesTabsAwardWinnersRow() {
        val vod = UiMappers.vod(state, series = true, title = "Series", now = Fixtures.NOW)
        assertTrue(vod.awardWinners.isNotEmpty())
        show(television = true) {
            TvNavigationFrame(TopLevel.SERIES, showBar = true, tabPage = true, onSelect = {}, onSearch = {}, onSettings = {}) { VodScreen(vod, VodActions()) }
        }
        compose.onNodeWithTag("vod").performScrollToKey("vod:awards")
        advance()
        compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("shelfAwards"))).onFirst().requestFocus()
        advance()
        capture("tv", "series-award-winners")
    }

    @Test
    fun aSeriesPageWithItsAwards() {
        val page = UiMappers.seriesDetail(state, Fixtures.harborLights, null, null, Locale.US)
        assertTrue(page.awards.size == 2)
        show(television = true) {
            TvNavigationFrame(TopLevel.SERIES, showBar = false, onSelect = {}, onSearch = {}, onSettings = {}) { TvSeriesDetailScreen(page, DetailActions()) }
        }
        capture("tv", "series-award-header")
        compose.onNodeWithTag("detail").performScrollToKey("awards")
        advance()
        capture("tv", "series-awards")
    }

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
    fun phoneSeriesTab() {
        val vod = UiMappers.vod(state, series = true, title = "Series", now = Fixtures.NOW)
        show(television = false) { VodScreen(vod, VodActions()) }
        compose.onNodeWithTag("vod").performScrollToKey("vod:awards")
        advance()
        capture("phone", "series-award-winners")
    }

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
    fun phoneSeriesPage() {
        val page = UiMappers.seriesDetail(state, Fixtures.harborLights, null, null, Locale.US)
        show(television = false) { SeriesDetailScreen(page, DetailActions()) }
        // The page itself, the first list (the seasons row scrolls too), the clock running while it scrolls.
        compose.mainClock.autoAdvance = true
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasTestTag("awards"))
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        advance()
        capture("phone", "series-awards")
    }
}
