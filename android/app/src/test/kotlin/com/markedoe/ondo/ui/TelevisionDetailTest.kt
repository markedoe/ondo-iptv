package com.markedoe.ondo.ui

import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.core.model.MediaDetails
import com.markedoe.ondo.ui.screens.DetailActions
import com.markedoe.ondo.ui.screens.MovieDetailScreen
import com.markedoe.ondo.ui.screens.SeriesDetailScreen
import com.markedoe.ondo.ui.theme.OndoTheme
import com.markedoe.ondo.ui.tv.TelevisionFrame
import com.markedoe.ondo.ui.tv.TvMovieDetailScreen
import com.markedoe.ondo.ui.tv.TvSeriesDetailScreen
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A title's page on a TV: the genres and year come before the cast, and the provider's trailer opens on YouTube from
 * a button (on a phone too). The screen is tall enough for every row to be laid out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h2400dp-television-xhdpi")
class TelevisionDetailTest {
    @get:Rule val compose = createComposeRule()

    private val movie = Fixtures.northernLights
    private val series = Fixtures.harborLights
    private val details = MediaDetails(
        title = movie.name,
        genre = "Drama, Mystery",
        director = "Kim Park",
        cast = "Ana Lima, José Silva",
        trailerYouTubeId = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=12",
    )

    private fun top(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top

    private fun movieState(details: MediaDetails = this.details) =
        UiMappers.movieDetail(Fixtures.state().copy(movieDetails = mapOf(movie.id to details)), movie, Locale.ENGLISH)

    private fun seriesState(details: MediaDetails = this.details): com.markedoe.ondo.ui.screens.SeriesDetailUiState {
        val state = Fixtures.state()
        val withInfo = state.seriesDetails.getValue(series.id).copy(info = details.copy(title = series.name))
        return UiMappers.seriesDetail(state.copy(seriesDetails = state.seriesDetails + (series.id to withInfo)), series, null, null)
    }

    @Test
    fun aMoviesGenresAndYearComeBeforeItsCast() {
        compose.setContent { OndoTheme { TelevisionFrame { TvMovieDetailScreen(movieState(), DetailActions()) } } }
        compose.waitForIdle()
        assertTrue("genres ${top("explore")} above the cast ${top("people")}", top("explore") < top("people"))
    }

    @Test
    fun aSeriesGenresAndYearComeBeforeItsCast() {
        compose.setContent { OndoTheme { TelevisionFrame { TvSeriesDetailScreen(seriesState(), DetailActions()) } } }
        compose.waitForIdle()
        assertTrue("genres ${top("explore")} above the cast ${top("people")}", top("explore") < top("people"))
    }

    /** TMDb's photo on the cards of the people it has one of; the initials on the others. */
    @Test
    fun theCastShowsTmdbsPhotosWhereItHasThem() {
        val state = Fixtures.state().copy(
            movieDetails = mapOf(movie.id to details),
            tmdbFaces = mapOf("m-${movie.id}" to mapOf("jose silva" to "https://image.tmdb.org/t/p/w185/jose.jpg")),
        )
        compose.setContent { OndoTheme { TelevisionFrame { TvMovieDetailScreen(UiMappers.movieDetail(state, movie, Locale.ENGLISH), DetailActions()) } } }
        compose.onAllNodesWithTag("personPhoto", useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithText("AL", useUnmergedTree = true).assertCountEquals(1)
    }

    /** Its click (what OK does): Compose for TV buttons take no touch. */
    @Test
    fun theProvidersTrailerOpensFromItsButton() {
        val opened = mutableListOf<String>()
        compose.setContent { OndoTheme { TelevisionFrame { TvMovieDetailScreen(movieState(), DetailActions(onTrailer = { opened += it })) } } }
        compose.onNodeWithTag("detailTrailer").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("dQw4w9WgXcQ"), opened)
    }

    @Test
    fun aSeriesTrailerOpensFromItsButton() {
        val opened = mutableListOf<String>()
        compose.setContent { OndoTheme { TelevisionFrame { TvSeriesDetailScreen(seriesState(), DetailActions(onTrailer = { opened += it })) } } }
        compose.onNodeWithTag("detailTrailer").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("dQw4w9WgXcQ"), opened)
    }

    @Test
    fun noTrailerNoButton() {
        compose.setContent { OndoTheme { TelevisionFrame { TvMovieDetailScreen(movieState(details.copy(trailerYouTubeId = "not a trailer")), DetailActions()) } } }
        compose.onAllNodesWithTag("detailTrailer").assertCountEquals(0)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun onAPhoneTheTrailerIsOneOfTheRoundButtons() {
        val opened = mutableListOf<String>()
        compose.setContent { OndoTheme { MovieDetailScreen(movieState(), DetailActions(onTrailer = { opened += it })) } }
        compose.onNodeWithText("Trailer\nYouTube").performClick()
        assertEquals(listOf("dQw4w9WgXcQ"), opened)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun onAPhoneASeriesWithoutTrailerHasNoTrailerButton() {
        compose.setContent { OndoTheme { SeriesDetailScreen(seriesState(details.copy(trailerYouTubeId = null)), DetailActions()) } }
        compose.onAllNodesWithText("Trailer", substring = true).assertCountEquals(0)
    }

    /** On a TV, the video's watch address (YouTube's own link opens the TV app on its home, not on the video). */
    @Test
    fun onATvATrailerOpensAtItsWatchAddress() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertTrue(app.openTrailer("dQw4w9WgXcQ"))
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", intent.dataString)
    }

    /** The TV's YouTube app by name when it is there; on a phone the YouTube app's link first, then youtube.com. */
    @Test
    fun theWaysATrailerOpensInOrder() {
        val tv = trailerIntents("dQw4w9WgXcQ", television = true, hasTvApp = true)
        assertEquals(YOUTUBE_TV_PACKAGE, tv.first().`package`)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", tv.first().dataString)
        assertEquals(2, trailerIntents("dQw4w9WgXcQ", television = true, hasTvApp = false).size)
        assertEquals(
            listOf("vnd.youtube:dQw4w9WgXcQ", "https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
            trailerIntents("dQw4w9WgXcQ", television = false, hasTvApp = false).map { it.dataString },
        )
    }

    @Test
    fun aTrailerIsTheProvidersYouTubeIdOrAddress() {
        for (given in listOf(
            "dQw4w9WgXcQ",
            " dQw4w9WgXcQ ",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://www.youtube.com/watch?feature=share&v=dQw4w9WgXcQ",
            "http://youtube.com/watch?v=dQw4w9WgXcQ&t=12",
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ",
            "https://www.youtube.com/embed/dQw4w9WgXcQ?autoplay=1",
            "https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ",
            "youtube.com/shorts/dQw4w9WgXcQ",
        )) {
            assertEquals(given, "dQw4w9WgXcQ", UiMappers.youTubeId(given))
        }
        for (given in listOf(null, "", "  ", "trailer", "dQw4w9WgXcQx", "https://example.com/watch?v=dQw4w9WgXcQ", "https://www.youtube.com/watch?v=short", "https://youtu.be/dQw4w9WgXcQxyz")) {
            assertNull(given, UiMappers.youTubeId(given))
        }
    }
}
