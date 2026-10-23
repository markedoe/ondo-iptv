package com.markedoe.ondo.ui

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.core.library.AwardsCatalogue
import com.markedoe.ondo.core.model.MediaDetails
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.ui.screens.DetailActions
import com.markedoe.ondo.ui.theme.OndoTheme
import com.markedoe.ondo.ui.tv.TelevisionFrame
import com.markedoe.ondo.ui.tv.TvMovieDetailScreen
import com.markedoe.ondo.ui.tv.TvSeriesDetailScreen
import java.io.File
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Lists whose rows are keyed: the same key twice stops the app ("Key … was already used"). Each test feeds a list the
 * data that once held a key twice — the awards file bundled with the app, a provider's catalogue or series page — and
 * shows the screen.
 */
@RunWith(AndroidJUnit4::class)
class DuplicateKeysTest {
    @get:Rule val compose = createComposeRule()

    /** The awards file bundled with the app (Gradle runs unit tests from the module's folder). */
    private val bundledAwards by lazy { AwardsCatalogue.parse(File("src/main/assets/awards.json").readText()) }

    private fun movieState(movie: Movie) =
        Fixtures.state(content = Fixtures.content.copy(movies = listOf(movie) + Fixtures.content.movies)).copy(awards = bundledAwards)

    private fun showTvMovie(movie: Movie, details: MediaDetails? = null) {
        val state = movieState(movie).let { if (details != null) it.copy(movieDetails = mapOf(movie.id to details)) else it }
        val ui = UiMappers.movieDetail(state, movie, Locale.ENGLISH)
        compose.setContent { OndoTheme { TelevisionFrame { TvMovieDetailScreen(ui, DetailActions()) } } }
        compose.waitForIdle()
    }

    /** "Grand Hotel" (1932) is in the bundled file twice for the same Oscar: won, and nominated. */
    @Test
    @Config(qualifiers = TV)
    fun aTvMoviePageOpensWhenTheAwardsFileListsAnAwardTwice() {
        showTvMovie(Movie(id = "x33680", name = "Grand Hotel", categoryId = Fixtures.drama.id, streamUrl = "http://example.com/movie/33680.mkv", year = 1932))
        compose.onNodeWithTag("detail").performScrollToNode(hasTestTag("awards"))
        compose.waitForIdle()
    }

    /** "Poor Things" (2023): the Golden Globe is listed won and nominated, with three other awards between. */
    @Test
    @Config(qualifiers = TV)
    fun aTvMoviePageOpensForPoorThings() {
        showTvMovie(Movie(id = "x792307", name = "Poor Things", categoryId = Fixtures.drama.id, streamUrl = "http://example.com/movie/792307.mkv", year = 2023))
        compose.onNodeWithTag("detail").performScrollToNode(hasTestTag("awards"))
        compose.waitForIdle()
    }

    /** A provider's genre naming the same genre twice. */
    @Test
    @Config(qualifiers = TV)
    fun aTvMoviePageOpensWhenItsGenreRepeats() {
        showTvMovie(Fixtures.northernLights, MediaDetails(title = "Northern Lights", genre = "Drama, Drama"))
        compose.onNodeWithTag("detail").performScrollToNode(hasTestTag("explore"))
        compose.waitForIdle()
    }

    /** The genre of a series comes with the catalogue (Xtream `get_series`): "Action & Adventure, Adventure". */
    @Test
    @Config(qualifiers = TV)
    fun aTvSeriesPageOpensWhenItsGenreRepeats() {
        val series = Fixtures.harborLights.copy(genre = "Action & Adventure, Adventure")
        val state = Fixtures.state(content = Fixtures.content.copy(series = listOf(series) + Fixtures.content.series.drop(1)))
        val ui = UiMappers.seriesDetail(state, series, null, null)
        compose.setContent { OndoTheme { TelevisionFrame { TvSeriesDetailScreen(ui, DetailActions()) } } }
        compose.onNodeWithTag("detail").performScrollToNode(hasTestTag("explore"))
        compose.waitForIdle()
    }

    private companion object {
        const val TV = "w960dp-h540dp-land-television-xhdpi"
        const val PHONE = "w411dp-h891dp-xxhdpi"
    }
}
