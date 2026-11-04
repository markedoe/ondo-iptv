package com.markedoe.ondo.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.library.TmdbEpisode
import com.markedoe.ondo.core.model.Episode
import com.markedoe.ondo.core.model.MediaDetails
import com.markedoe.ondo.core.model.Season
import com.markedoe.ondo.core.model.SeriesDetails
import com.markedoe.ondo.ui.TopLevel
import com.markedoe.ondo.ui.UiMappers
import com.markedoe.ondo.ui.components.LocalCarouselAutoAdvance
import com.markedoe.ondo.ui.screens.DetailActions
import com.markedoe.ondo.ui.theme.OndoTheme
import com.markedoe.ondo.ui.tv.TelevisionFrame
import com.markedoe.ondo.ui.tv.TvNavigationFrame
import com.markedoe.ondo.ui.tv.TvSeriesDetailScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Reference screenshots of a series' page on a TV whose provider names its episodes "|EN| Harbor Lights S03E02" and
 * gives a picture for some of them only: each episode shows its own title (TMDb's name, else "Episode N", never the
 * series' name) and the provider's picture, else TMDb's still, else the drawn placeholder (episode 5). Then the season
 * chips: the remote on season 2 while season 3 is selected, and on season 3, selected. Pictures from the device
 * (`fixtures/showcase/art`), no network. Compared by `verifyRoborazziDebug` with [BASELINES]`/tv/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w960dp-h540dp-land-television-xhdpi")
class TvSeriesScreenshots {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun pictures() = ShowcaseImages.install(ApplicationProvider.getApplicationContext())

    @After
    fun noPictures() = ShowcaseImages.reset()

    private val name = "|EN| Harbor Lights"
    private val series = Fixtures.harborLights.copy(name = name)

    /** The provider's seasons: season 3 with pictures for episodes 1 and 4 only, every episode named by its code. */
    private val provider = SeriesDetails(
        info = MediaDetails(title = name, plot = "A fishing town keeps its secrets.", genre = "Drama"),
        seasons = (1..4).map { number ->
            val episodes = if (number != 3) {
                listOf(Episode("s${number}e1", "$name S0${number}E01", number, 1, "http://stream.example.com/s${number}e1.mp4"))
            } else {
                (1..5).map { episode ->
                    Episode(
                        id = "s3e$episode",
                        title = "$name S03E0$episode",
                        season = 3,
                        number = episode,
                        streamUrl = "http://stream.example.com/s3e$episode.mp4",
                        posterUrl = if (episode == 1 || episode == 4) "http://img.example.com/s-pepper-s1e$episode.jpg" else null,
                        durationSeconds = 2_700,
                    )
                }
            }
            Season(number, "Season $number", episodes)
        },
    )

    /** TMDb's season 3: names for some episodes, stills for the first three (the provider's own picture of the first wins). */
    private val tmdb = listOf(
        TmdbEpisode(1, "Low Tide", "/s-pepper-s1e5.jpg"),
        TmdbEpisode(2, "The Lighthouse Keeper", "/s-pepper-s1e2.jpg"),
        TmdbEpisode(3, "Episode 3", "/s-pepper-s1e3.jpg"),
        TmdbEpisode(4, "Nets", null),
        TmdbEpisode(5, null, null),
    )

    private fun page() = UiMappers.seriesDetail(
        Fixtures.state().let { state ->
            state.copy(
                content = state.content.copy(series = state.content.series.map { if (it.id == series.id) series else it }),
                seriesDetails = state.seriesDetails + (series.id to provider),
                tmdbSeasons = mapOf("s-${series.id}" to mapOf(3 to tmdb)),
            )
        },
        series,
        selectedSeason = 3,
        error = null,
    )

    private var inputModes: InputModeManager? = null

    private fun show() {
        val shown = page()
        assertEquals(
            listOf("Low Tide", "The Lighthouse Keeper", "Episode 3", "Nets", "Episode 5"),
            shown.seasons.single { it.number == 3 }.episodes.map { it.title },
        )
        compose.setContent {
            inputModes = LocalInputModeManager.current
            OndoTheme {
                CompositionLocalProvider(LocalCarouselAutoAdvance provides false) {
                    TelevisionFrame {
                        TvNavigationFrame(TopLevel.SERIES, showBar = false, onSelect = {}, onSearch = {}, onSettings = {}) {
                            TvSeriesDetailScreen(shown, DetailActions())
                        }
                    }
                }
            }
        }
        compose.runOnIdle { inputModes?.requestInputMode(InputMode.Keyboard) }
        compose.mainClock.autoAdvance = false
        advance()
        compose.onNodeWithTag("detail").performScrollToKey("seasons")
        advance()
    }

    private fun advance() {
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    private fun capture(file: String) = compose.onRoot().captureRoboImage("$BASELINES/tv/en-US/$file.png", BaselineOptions)

    @Test
    fun aSeriesEpisodesWithTheirOwnTitlesAndStills() {
        show()
        compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("episodes")))[1].requestFocus()
        advance()
        capture("series-episodes-stills")
    }

    @Test
    fun seasonTwoFocusedWhileSeasonThreeIsSelected() {
        show()
        compose.onNodeWithTag("season-2").requestFocus()
        advance()
        capture("series-season2-focused-season3-selected")
    }

    @Test
    fun seasonThreeFocusedAndSelected() {
        show()
        compose.onNodeWithTag("season-3").requestFocus()
        advance()
        capture("series-season3-focused-and-selected")
    }
}
