package com.markedoe.ondo.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.library.TmdbRating
import com.markedoe.ondo.data.OndoState
import com.markedoe.ondo.ui.TopLevel
import com.markedoe.ondo.ui.UiMappers
import com.markedoe.ondo.ui.components.LocalCarouselAutoAdvance
import com.markedoe.ondo.ui.components.LocalPosterScores
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.model.TitleHeader
import com.markedoe.ondo.ui.screens.PosterGridScreen
import com.markedoe.ondo.ui.screens.VodActions
import com.markedoe.ondo.ui.screens.VodScreen
import com.markedoe.ondo.ui.theme.OndoTheme
import com.markedoe.ondo.ui.tv.LocalTitleHeaders
import com.markedoe.ondo.ui.tv.TelevisionFrame
import com.markedoe.ondo.ui.tv.TitleHeaders
import com.markedoe.ondo.ui.tv.TvNavigationFrame
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Reference screenshots of TMDb's score on the thumbnails (docs/ANDROID.md, "TMDb score on the thumbnails"), from the
 * demo catalogue with TMDb's rating known for its titles: a TV's Movies tab (the header above the rows saying the same
 * score as the focused poster), and a category's grid on a phone, in English and in French ("7,8").
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PosterScoreScreenshots {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun pictures() = ShowcaseImages.install(ApplicationProvider.getApplicationContext())

    @After
    fun noPictures() = ShowcaseImages.reset()

    /** The demo catalogue, TMDb's rating of each title known (the provider's, a little apart, so that both are told apart). */
    private fun rated(catalogue: ShowcaseCatalogue): OndoState {
        val state = catalogue.state()
        val ratings = state.content.movies.mapNotNull { movie -> movie.rating?.takeIf { it > 0 }?.let { "m-" + movie.id to TmdbRating((it + 0.3).coerceAtMost(9.6), 2_400) } } +
            state.content.series.mapNotNull { show -> show.rating?.takeIf { it > 0 }?.let { "s-" + show.id to TmdbRating((it + 0.3).coerceAtMost(9.6), 1_800) } }
        return state.copy(tmdbRatings = ratings.toMap())
    }

    @Test
    @Config(qualifiers = "en-rUS-w960dp-h540dp-land-television-xhdpi")
    fun tvMoviesTabWithScores() {
        val state = rated(ShowcaseCatalogue.load(french = false))
        val scores = UiMappers.posterScores(state, emptyMap(), Locale.US)
        val headers = object : TitleHeaders {
            override fun header(item: ShowcaseItem): Flow<TitleHeader> = flowOf(UiMappers.titleHeader(state, item, null, Locale.US))

            override suspend fun load(item: ShowcaseItem) = Unit
        }
        val vod = UiMappers.vod(state, series = false, title = "Movies", now = ShowcaseCatalogue.NOW)
        compose.setContent {
            OndoTheme {
                CompositionLocalProvider(LocalCarouselAutoAdvance provides false) {
                    TelevisionFrame {
                        TvNavigationFrame(TopLevel.MOVIES, showBar = true, tabPage = true, onSelect = {}, onSearch = {}, onSettings = {}) {
                            CompositionLocalProvider(LocalTitleHeaders provides headers, LocalPosterScores provides scores) { VodScreen(vod, VodActions()) }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$BASELINES/tv/en-US/movies-tmdb-scores.png", BaselineOptions)
    }

    private fun phoneGrid(french: Boolean, locale: Locale, folder: String) {
        val state = rated(ShowcaseCatalogue.load(french))
        val scores = UiMappers.posterScores(state, emptyMap(), locale)
        val category = state.content.movieCategories.maxBy { c -> state.content.movies.count { it.categoryId == c.id } }
        val items = UiMappers.grid(state, series = false, categoryId = category.id, myList = false, filter = "")
        compose.setContent {
            OndoTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    CompositionLocalProvider(LocalPosterScores provides scores) {
                        PosterGridScreen(category.name, items, filter = "", onFilter = {}, onOpen = {}, onBack = {})
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$BASELINES/phone/$folder/grid-tmdb-scores.png", BaselineOptions)
    }

    @Test
    @Config(qualifiers = "en-rUS-w360dp-h640dp-xxhdpi")
    fun phoneGridWithScores() = phoneGrid(french = false, Locale.US, "en-US")

    @Test
    @Config(qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
    fun phoneGridWithScoresInFrench() = phoneGrid(french = true, Locale.FRANCE, "fr-FR")
}
