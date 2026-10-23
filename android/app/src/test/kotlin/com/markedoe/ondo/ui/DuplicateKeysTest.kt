package com.markedoe.ondo.ui

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.core.library.AwardsCatalogue
import com.markedoe.ondo.core.model.MediaDetails
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.Series
import com.markedoe.ondo.core.model.XtreamCredentials
import com.markedoe.ondo.core.xtream.XtreamClient
import com.markedoe.ondo.data.FakeHttp
import com.markedoe.ondo.ui.screens.DetailActions
import com.markedoe.ondo.ui.screens.SeriesDetailScreen
import com.markedoe.ondo.ui.theme.OndoTheme
import com.markedoe.ondo.ui.tv.TelevisionFrame
import com.markedoe.ondo.ui.tv.TvMovieDetailScreen
import com.markedoe.ondo.ui.tv.TvSeriesDetailScreen
import java.io.File
import java.util.Locale
import kotlinx.coroutines.runBlocking
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

    private val panel = "http://panel.example.com"
    private val credentials = XtreamCredentials(panel, "viewer", "secret") // leak-guard: allow
    private fun api(action: String? = null, parameters: String = "") =
        "$panel/player_api.php?username=viewer&password=secret" + (action?.let { "&action=$it" } ?: "") + parameters // leak-guard: allow

    /** A panel's series page listing the same episode twice in its season. */
    private val seriesInfo = """
        {"info":{"name":"Harbor Lights"},
         "episodes":{"1":[
           {"id":"101","episode_num":1,"title":"Pilot","container_extension":"mkv"},
           {"id":"101","episode_num":1,"title":"Pilot","container_extension":"mkv"},
           {"id":"102","episode_num":2,"title":"Low Tide","container_extension":"mkv"}]}}
    """.trimIndent()

    private fun seriesWithAnEpisodeTwice(): Pair<Series, com.markedoe.ondo.ui.screens.SeriesDetailUiState> {
        val http = FakeHttp(mapOf(api("get_series_info", "&series_id=7") to seriesInfo))
        val details = runBlocking { XtreamClient(credentials, http).seriesDetails(7) }
        val series = Series(id = "x7", name = "Harbor Lights", categoryId = Fixtures.seriesDrama.id, xtreamSeriesId = 7)
        val state = Fixtures.state(content = Fixtures.content.copy(series = listOf(series))).copy(seriesDetails = mapOf(series.id to details))
        return series to UiMappers.seriesDetail(state, series, null, null)
    }

    @Test
    @Config(qualifiers = PHONE)
    fun aSeriesPageOpensWhenThePanelListsAnEpisodeTwice() {
        val (_, ui) = seriesWithAnEpisodeTwice()
        compose.setContent { OndoTheme { SeriesDetailScreen(ui, DetailActions()) } }
        // The page's list (the first scrollable list in it), down to the season's episodes.
        compose.onAllNodes(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("detail"))).onFirst().performScrollToNode(hasText("2. Low Tide"))
        compose.waitForIdle()
    }

    @Test
    @Config(qualifiers = TV)
    fun aTvSeriesPageOpensWhenThePanelListsAnEpisodeTwice() {
        val (_, ui) = seriesWithAnEpisodeTwice()
        compose.setContent { OndoTheme { TelevisionFrame { TvSeriesDetailScreen(ui, DetailActions()) } } }
        compose.onNodeWithTag("detail").performScrollToNode(hasTestTag("episodes"))
        compose.waitForIdle()
    }

    private companion object {
        const val TV = "w960dp-h540dp-land-television-xhdpi"
        const val PHONE = "w411dp-h891dp-xxhdpi"
    }
}
