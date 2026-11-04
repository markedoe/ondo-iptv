package com.markedoe.ondo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.library.RowOrder
import com.markedoe.ondo.core.sync.SyncRows
import com.markedoe.ondo.data.LibraryState
import com.markedoe.ondo.ui.model.ResumeItem
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.screens.HomeActions
import com.markedoe.ondo.ui.screens.HomeScreen
import com.markedoe.ondo.ui.screens.HomeUiState
import com.markedoe.ondo.ui.theme.OndoTheme
import com.markedoe.ondo.ui.tv.OndoTvTheme
import com.markedoe.ondo.ui.tv.TvHomeScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Home's rows in the viewer's order (Settings → Row order, docs/SYNC.md "Rows"): the order saved applies, rows it does
 * not name keep their default place, and a row hidden never shows on any Home. The carousel stays at the top.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w480dp-h2400dp-mdpi")
class HomeRowOrderTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val compose = createComposeRule()

    private val labels = Labels("Movie", "Series")

    /** A Home and how it tags each of its rows, by key ([RowOrder.homeBuiltIns]). */
    private class Surface(val name: String, val tags: Map<String, String>, val content: @Composable (HomeUiState) -> Unit)

    /**
     * Every Home of the Android app: a phone's or a tablet's, and a TV's. A new Home goes here. (Apple's iPhone, iPad
     * and Mac share one Home: AppTests `HomeRowOrderTests`.)
     */
    private val surfaces = listOf(
        Surface("phone", RowOrder.homeBuiltIns(hasVod = true, collections = false).associateWith { "homeRow-$it" }) { state ->
            OndoTheme { HomeScreen(state, HomeActions(), autoAdvance = false) }
        },
        Surface(
            "tv",
            mapOf(
                RowOrder.HOME_CONTINUE_MOVIES to "shelfResumeMovies",
                RowOrder.HOME_CONTINUE_SERIES to "shelfResumeSeries",
                RowOrder.MY_LIST to "shelfMyList",
                RowOrder.HOME_NEW_MOVIES to "shelfNewMovies",
                RowOrder.HOME_NEW_SERIES to "shelfNewSeries",
                RowOrder.TRENDING to "shelfTrending",
                RowOrder.HOME_CHANNELS to "shelfChannels",
            ),
        ) { state -> OndoTvTheme { TvHomeScreen(state, HomeActions(), autoAdvance = false) } },
    )

    private fun movie(movie: com.markedoe.ondo.core.model.Movie) = ShowcaseItem.MovieItem(movie)

    @Test
    fun homeFollowsTheOrderSavedAndARowItDoesNotNameKeepsItsDefaultPlace() {
        val byDefault = UiMappers.home(Fixtures.state(), labels, Fixtures.NOW)
        assertEquals(RowOrder.homeBuiltIns(byDefault.hasVod, collections = false), byDefault.order)
        // Saved on an Apple device (with its collections), Trending hidden; the rest after, in their default order.
        val saved = SyncRows(SyncRows.HOME, listOf("channels", "collections", "new-movies", "mylist"), listOf("trending"), updatedAt = 1)
        val home = UiMappers.home(Fixtures.state(library = LibraryState(rowOrders = listOf(saved))), labels, Fixtures.NOW)
        assertEquals(listOf("channels", "new-movies", "mylist", "continue-movies", "continue-series", "new-series"), home.order)
        // The Movies and Series tabs keep their own orders.
        assertEquals(RowOrder.ALL, UiMappers.vod(Fixtures.state(library = LibraryState(rowOrders = listOf(saved))), series = false, title = "", now = Fixtures.NOW).shownRows.first())
        // Settings → Row order lists Home first, every row of Android's Home, the hidden one switched off.
        val settings = UiMappers.rowOrder(Fixtures.state(library = LibraryState(rowOrders = listOf(saved))))
        assertEquals(listOf("channels", "new-movies", "mylist", "continue-movies", "continue-series", "trending", "new-series"), settings.home.map { it.key })
        assertEquals(listOf("trending"), settings.home.filter { it.hidden }.map { it.key })
    }

    /**
     * A row hidden never shows, and the others show in the order saved, on every Home (the list of [surfaces]). Each
     * surface tags every row Home can show, so a new row gets checked too.
     */
    @Test
    fun aHiddenRowStaysHiddenOnEveryHomeAndTheOthersFollowTheOrder() {
        val keys = RowOrder.homeBuiltIns(hasVod = true, collections = false).toSet()
        val resume = ResumeItem("resume-1", "Northern Lights", null, null, 0.4f, 30, showcase = movie(Fixtures.northernLights))
        val state = HomeUiState(
            playlistName = "Example",
            continueWatching = listOf(resume),
            myList = listOf(movie(Fixtures.glassCity)),
            newMovies = listOf(movie(Fixtures.lastSignal)),
            trending = listOf(movie(Fixtures.silentHarbor)),
            hasVod = true,
            // As the mapper gives it: My List hidden, Trending moved first.
            order = listOf(RowOrder.TRENDING, RowOrder.HOME_NEW_MOVIES, RowOrder.HOME_CONTINUE_MOVIES, RowOrder.HOME_CONTINUE_SERIES, RowOrder.HOME_NEW_SERIES, RowOrder.HOME_CHANNELS),
        )
        assertEquals(listOf(RowOrder.TRENDING, RowOrder.HOME_NEW_MOVIES, RowOrder.HOME_CONTINUE_MOVIES), state.shownRows)
        var shown by mutableIntStateOf(0)
        compose.setContent { surfaces[shown].content(state) }
        surfaces.forEachIndexed { index, surface ->
            assertEquals(surface.name, keys, surface.tags.keys)
            compose.runOnIdle { shown = index }
            compose.waitForIdle()
            assertEquals("${surface.name}: My List hidden", 0, compose.onAllNodesWithTag(surface.tags.getValue(RowOrder.MY_LIST)).fetchSemanticsNodes().size)
            val tops = state.shownRows.map { key -> compose.onNodeWithTag(surface.tags.getValue(key)).fetchSemanticsNode().boundsInRoot.top }
            assertEquals("${surface.name}: in the order saved", tops.sorted(), tops)
        }
    }
}
