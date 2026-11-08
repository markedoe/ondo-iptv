package com.markedoe.ondo.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.markedoe.ondo.core.model.Episode
import com.markedoe.ondo.ui.screens.DetailActions
import com.markedoe.ondo.ui.screens.MovieDetailScreen
import com.markedoe.ondo.ui.screens.SeriesDetailScreen
import com.markedoe.ondo.ui.tv.TelevisionFrame
import com.markedoe.ondo.ui.tv.TvMovieDetailScreen
import com.markedoe.ondo.ui.tv.TvPosterCard
import com.markedoe.ondo.ui.tv.TvSeriesDetailScreen
import org.robolectric.RobolectricTestRunner
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.model.ContentType
import com.markedoe.ondo.core.model.MediaCategory
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.data.AppSettings
import com.markedoe.ondo.data.ItemKind
import com.markedoe.ondo.data.LibraryState
import com.markedoe.ondo.data.WatchEntry
import com.markedoe.ondo.ui.components.LocalWatchedTitles
import com.markedoe.ondo.ui.components.PosterCard
import com.markedoe.ondo.ui.components.WATCHED_BADGE_TAG
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.theme.OndoTheme
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A watched movie (any version of its film) and a series whose episodes are all watched show the watched badge on every
 * thumbnail, from one set ([UiMappers.watchedKeys], [LocalWatchedTitles]), on a phone and a TV (`TvWatchedTest`). Apple
 * has the same test (`WatchedBadgeEverySurfaceTests`). A new screen that shows thumbnails is added to [surfaces] here.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class WatchedBadgeEverySurfaceTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val compose = createComposeRule()

    private val uhdFilms = MediaCategory("m-4k", "4K | Films", ContentType.MOVIE)
    private val uhd = Movie("g-4k", "Glass City 4K", categoryId = uhdFilms.id, streamUrl = "http://example.com/movie/g-4k.mkv", year = 2024, addedAt = Fixtures.NOW / 1000)
    private val content = Fixtures.content.copy(movies = Fixtures.content.movies + uhd, movieCategories = Fixtures.content.movieCategories + uhdFilms)

    private fun watched(itemId: String, kind: ItemKind = ItemKind.MOVIE, episodeId: String? = null) =
        WatchEntry(Fixtures.PLAYLIST_ID, kind, itemId, episodeId, title = itemId, lastWatchedAt = 0)

    @After
    fun forget() = UiMappers.forgetCatalogue()

    @Test
    fun theWatchedKeysAreTheFilmsVersionsAndTheSeriesWatchedToTheirLastEpisode() {
        var library = LibraryState().markingWatched(watched(Fixtures.glassCity.id), now = 1)
        Fixtures.harborLights.episodes.forEachIndexed { index, episode ->
            library = library.markingWatched(watched(Fixtures.harborLights.id, ItemKind.SERIES, episode.id), now = 2L + index)
        }
        library = library.markingWatched(watched(Fixtures.theArchive.id, ItemKind.SERIES, Fixtures.theArchive.episodes.first().id), now = 20)
        val state = Fixtures.state(library, content, AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID, groupVersions = true))
        val keys = UiMappers.watchedKeys(state)
        assertTrue("the film's version marked", "m-${Fixtures.glassCity.id}" in keys)
        assertTrue("its other version too", "m-${uhd.id}" in keys)
        assertFalse("a movie not watched", "m-${Fixtures.northernLights.id}" in keys)
        assertTrue("every episode watched", "s-${Fixtures.harborLights.id}" in keys)
        assertFalse("one episode of four", "s-${Fixtures.theArchive.id}" in keys)
        // Without the provider's seasons, the playlist's own episodes count.
        assertTrue("s-${Fixtures.harborLights.id}" in UiMappers.watchedKeys(state.copy(seriesDetails = emptyMap())))
        // The pages say the same.
        assertTrue(UiMappers.movieDetail(state, uhd).watched)
        assertTrue(UiMappers.seriesDetail(state, Fixtures.harborLights, null, null).watched)
        assertFalse(UiMappers.seriesDetail(state, Fixtures.theArchive, null, null).watched)
        // Unwatched: every version.
        val unwatched = listOf(Fixtures.glassCity.id, uhd.id).fold(library) { changed, id -> changed.markingUnwatched(watched(id).key, now = 30) }
        assertFalse("m-${uhd.id}" in UiMappers.watchedKeys(state.copy(library = unwatched)))
    }

    @Test
    fun thePostersOfAWatchedTitleShowTheBadgeInTheirStartCorner() {
        val movie = ShowcaseItem.MovieItem(Fixtures.glassCity)
        val series = ShowcaseItem.SeriesItem(Fixtures.harborLights)
        val other = ShowcaseItem.MovieItem(Fixtures.northernLights)
        compose.setContent {
            OndoTheme {
                CompositionLocalProvider(LocalWatchedTitles provides setOf(movie.key, series.key)) {
                    Column {
                        Row {
                            PosterCard(movie, 120.dp, onClick = {})
                            PosterCard(other, 120.dp, onClick = {})
                        }
                        Row {
                            PosterCard(series, 120.dp, onClick = {}, showsTitle = true)
                            PosterCard(other, 120.dp, onClick = {}, showsTitle = true, focusKey = "other")
                        }
                    }
                }
            }
        }
        compose.onAllNodesWithTag(WATCHED_BADGE_TAG, useUnmergedTree = true).assertCountEquals(2)
        compose.onAllNodes(hasStateDescription("Watched")).assertCountEquals(2)
        val badge = compose.onAllNodesWithTag(WATCHED_BADGE_TAG, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot
        assertTrue("at the start of the first poster", badge.right < 120.dp.value * compose.density.density / 2)
    }

    @Test
    fun rightToLeftTheBadgeIsAtTheStartToo() {
        val movie = ShowcaseItem.MovieItem(Fixtures.glassCity)
        compose.setContent {
            OndoTheme {
                CompositionLocalProvider(LocalWatchedTitles provides setOf(movie.key), LocalLayoutDirection provides LayoutDirection.Rtl) {
                    PosterCard(movie, 120.dp, onClick = {})
                }
            }
        }
        val poster = compose.onAllNodes(hasStateDescription("Watched")).fetchSemanticsNodes().single().boundsInRoot
        val badge = compose.onAllNodesWithTag(WATCHED_BADGE_TAG, useUnmergedTree = true).fetchSemanticsNodes().single().boundsInRoot
        assertTrue("on the right of the poster", badge.left > poster.center.x)
    }

    /** A movie's page: "Watched" in sight once watched, and its button marks it, or unmarks it. */
    @Test
    fun aMoviesPageShowsItsStateAndMarksIt() {
        val changes = mutableListOf<Boolean>()
        var state by mutableStateOf(UiMappers.movieDetail(Fixtures.state(), Fixtures.glassCity))
        compose.setContent { OndoTheme { MovieDetailScreen(state, DetailActions(onSetWatched = { changes += it })) } }
        compose.onAllNodesWithTag("detailWatchedState", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("detailWatched").performScrollTo().assert(hasStateDescription("Not watched")).assert(hasText("Mark as watched")).performClick()
        assertEquals(listOf(true), changes)
        state = state.copy(watched = true)
        compose.onAllNodesWithTag("detailWatchedState", useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithTag(WATCHED_BADGE_TAG, useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithTag("detailWatched").assert(hasStateDescription("Watched")).assert(hasText("Mark as unwatched")).performClick()
        assertEquals(listOf(true, false), changes)
    }

    /** A series' page: the whole series from its button, each episode from the check mark of its row. */
    @Test
    fun aSeriesPageMarksTheSeriesAndEachEpisode() {
        val series = mutableListOf<Boolean>()
        val episodes = mutableListOf<Pair<Episode, Boolean>>()
        val state = UiMappers.seriesDetail(Fixtures.state(), Fixtures.harborLights, null, null)
        compose.setContent {
            OndoTheme { SeriesDetailScreen(state, DetailActions(onSetWatched = { series += it }, onSetEpisodeWatched = { episode, watched -> episodes += episode to watched })) }
        }
        compose.onNodeWithTag("detailWatched").performScrollTo().performClick()
        assertEquals(listOf(true), series)
        val first = state.seasons.first().episodes.first()
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag("episodeWatched-${first.id}"))
        compose.onNodeWithTag("episodeWatched-${first.id}").assert(hasContentDescription("Mark as watched")).performClick()
        assertEquals(listOf(first to true), episodes)
    }

    companion object {
        /**
         * Every source file that shows movie or series thumbnails, and how many poster cards it draws: each is a
         * [PosterCard] or a `TvPosterCard`, which read the watched badge themselves. A new screen, or a new thumbnail on
         * one, is added here.
         */
        val surfaces = mapOf(
            "ui/screens/BrowseScreen.kt" to 1, // a genre's, a year's or an award's titles
            "ui/screens/CollectionScreens.kt" to 1, // a collection
            "ui/screens/DetailScreens.kt" to 1, // More Like This
            "ui/screens/HomeScreen.kt" to 1, // Home's rows
            "ui/screens/MyListScreen.kt" to 1, // My List
            "ui/screens/PersonScreen.kt" to 1, // an actor's or a director's titles
            "ui/screens/SearchScreen.kt" to 3, // search results, rows and grid
            "ui/screens/VodScreens.kt" to 8, // Movies and Series tabs, a category's grid (a TV's too)
            "ui/tv/TvDetailScreens.kt" to 1, // a TV's More Like This
            "ui/tv/TvHomeScreen.kt" to 5, // a TV's Home rows
            "ui/tv/TvSearchScreen.kt" to 1, // a TV's search
        )

        private val card = Regex("""(?<![A-Za-z.])(PosterCard|TvPosterCard)\(""")

        /** A movie's or a series' picture drawn by another way than the two poster cards (which carry the badge). */
        private val bareThumbnail = Regex("""(Poster|Artwork)\(\s*url = item\.posterUrl""")
    }

    @Test
    fun everySurfaceDrawsItsThumbnailsWithTheBadgedCards() {
        val root = File("src/main/kotlin/com/markedoe/ondo")
        val found = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.mapNotNull { file ->
            val source = file.readText()
            val path = file.relativeTo(root).invariantSeparatorsPath
            if (path != "ui/components/Shelves.kt" && path != "ui/tv/TvDesign.kt") {
                assertFalse("$path draws a poster without the badged cards", bareThumbnail.containsMatchIn(source))
            }
            val calls = card.findAll(source).count { match -> !source.substring(maxOf(0, match.range.first - 4), match.range.first).endsWith("fun ") }
            if (calls == 0) null else path to calls
        }.toMap()
        assertEquals("a surface with thumbnails is added to `surfaces`", surfaces, found)
    }
}

/** On a TV: the posters' badge, and the button of a title's page after Play on the remote's path, Play keeping the focus. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h2400dp-television-xhdpi")
class TvWatchedTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val compose = createComposeRule()

    @Test
    fun aWatchedTitlesPosterShowsTheBadge() {
        val movie = ShowcaseItem.MovieItem(Fixtures.glassCity)
        val other = ShowcaseItem.MovieItem(Fixtures.northernLights)
        compose.setContent {
            OndoTheme {
                TelevisionFrame {
                    CompositionLocalProvider(LocalWatchedTitles provides setOf(movie.key)) {
                        Row {
                            TvPosterCard(movie, onClick = {})
                            TvPosterCard(other, onClick = {})
                        }
                    }
                }
            }
        }
        compose.onAllNodesWithTag(WATCHED_BADGE_TAG, useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodes(hasStateDescription("Watched")).assertCountEquals(1)
    }

    @Test
    fun theWatchedButtonIsRightOfPlay() {
        val changes = mutableListOf<Boolean>()
        val state = UiMappers.movieDetail(Fixtures.state(), Fixtures.glassCity)
        compose.setContent { OndoTheme { TelevisionFrame { TvMovieDetailScreen(state, DetailActions(onSetWatched = { changes += it })) } } }
        compose.waitForIdle()
        compose.onNodeWithTag("detailPlay").requestFocus()
        compose.onNodeWithTag("detailPlay").assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        compose.onNodeWithTag("detailWatched").assertIsFocused().assert(hasStateDescription("Not watched")).performClick()
        assertEquals(listOf(true), changes)
    }

    @Test
    fun aWatchedSeriesSaysSoAndItsButtonUnmarksIt() {
        val changes = mutableListOf<Boolean>()
        var library = LibraryState()
        Fixtures.harborLights.episodes.forEachIndexed { index, episode ->
            library = library.markingWatched(WatchEntry(Fixtures.PLAYLIST_ID, ItemKind.SERIES, Fixtures.harborLights.id, episode.id, "Harbor Lights", lastWatchedAt = 0), now = 1L + index)
        }
        val state = UiMappers.seriesDetail(Fixtures.state(library), Fixtures.harborLights, null, null)
        assertTrue(state.watched)
        compose.setContent { OndoTheme { TelevisionFrame { TvSeriesDetailScreen(state, DetailActions(onSetWatched = { changes += it })) } } }
        compose.waitForIdle()
        compose.onAllNodesWithTag("detailWatchedState", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithTag("detailWatched").assert(hasStateDescription("Watched")).performClick()
        assertEquals(listOf(false), changes)
    }
}
