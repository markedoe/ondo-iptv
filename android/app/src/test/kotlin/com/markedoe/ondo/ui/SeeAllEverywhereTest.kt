package com.markedoe.ondo.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.library.AwardsCatalogue
import com.markedoe.ondo.core.library.RowOrder
import com.markedoe.ondo.core.library.Selections
import com.markedoe.ondo.core.sync.SyncSelection
import com.markedoe.ondo.core.library.TmdbItem
import com.markedoe.ondo.core.library.TrendingMatch
import com.markedoe.ondo.data.Favorite
import com.markedoe.ondo.data.ItemKind
import com.markedoe.ondo.data.LibraryState
import com.markedoe.ondo.data.WatchEntry
import com.markedoe.ondo.ui.components.LocalCarouselAutoAdvance
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.screens.HomeActions
import com.markedoe.ondo.ui.screens.HomeScreen
import com.markedoe.ondo.ui.screens.VodActions
import com.markedoe.ondo.ui.screens.VodScreen
import com.markedoe.ondo.ui.theme.OndoTheme
import com.markedoe.ondo.ui.tv.TelevisionFrame
import com.markedoe.ondo.ui.tv.TvHomeScreen
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every row of titles that can hold more than it shows ends with See all, on every surface: Home, Movies and Series, on
 * a TV (a See all card, the row's last) and on a phone (See all next to the row's name). See all opens the grid of that
 * row: all its titles, those of the row first and in the row's order. The rows without one are listed with the reason.
 *
 * [SURFACES] lists every surface; each one's rows come from its screen (a row the screen shows that the table does not
 * name fails the test), so a new screen or a new row is added here.
 */
@RunWith(RobolectricTestRunner::class)
class SeeAllEverywhereTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private enum class Screen { HOME, MOVIES, SERIES }

    /** A row of a screen ([tag]), its titles, and what its See all opens: a [Routes.Grid], or [LIVE_TAB]; null: none, [why]. */
    private class Row(val tag: String, val items: List<ShowcaseItem>, val count: Int, val opens: Any?, val why: String? = null)

    private val labels = Labels("Movie", "Series", Locale.US)

    // Every kind of row has titles: something in progress of each type, My List, new titles, trending ones, award winners
    // (films and series).
    private val awards = AwardsCatalogue.parse(
        """
        {"awards":[{"id":"palme-dor","group":"cannes","names":{"en":"Palme d'Or"}}],
         "films":[{"titles":["Glass City"],"year":2024,"awards":[["palme-dor",2024,1]]},
                  {"titles":["The Long Road"],"year":2021,"awards":[["palme-dor",2021,1]]}],
         "seriesAwards":[{"id":"emmy-drama-series","group":"emmys","names":{"en":"Primetime Emmy Award for Outstanding Drama Series"}}],
         "series":[{"titles":["Harbor Lights"],"year":2025,"awards":[["emmy-drama-series",2025,1]]},
                   {"titles":["Blue Horizon"],"year":2023,"awards":[["emmy-drama-series",2023,1]]}]}
        """.trimIndent(),
    )
    private val movieInProgress = WatchEntry(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, Fixtures.silentHarbor.id, title = "Silent Harbor", lastWatchedAt = 1)
    private val episodeInProgress = WatchEntry(Fixtures.PLAYLIST_ID, ItemKind.SERIES, Fixtures.harborLights.id, "s1-1-1", title = "Harbor Lights", lastWatchedAt = 1)
    private val library = LibraryState()
        .recordStart(movieInProgress).updateProgress(movieInProgress.key, 600.0, 6_000.0, now = 2)
        .recordStart(episodeInProgress).updateProgress(episodeInProgress.key, 600.0, 2_700.0, now = 3)
        .toggleFavorite(Favorite(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, Fixtures.glassCity.id, "Glass City", addedAt = 1))
        .toggleFavorite(Favorite(Fixtures.PLAYLIST_ID, ItemKind.SERIES, Fixtures.theArchive.id, "The Archive", addedAt = 2))
        // A selection on every tab: the titles of the 2020s.
        .keepingSelection(SyncSelection(SELECTION, "Twenties", listOf(Selections.decade(2020)), SyncSelection.ANY, listOf(RowOrder.HOME, RowOrder.MOVIES, RowOrder.SERIES), 4))
    private val state = Fixtures.state(library).copy(
        awards = awards,
        trending = listOf(
            TrendingMatch.MovieMatch(Fixtures.longRoad, TmdbItem(1, TmdbItem.MediaType.MOVIE, "The Long Road", year = 2021)),
            TrendingMatch.SeriesMatch(Fixtures.blueHorizon, TmdbItem(2, TmdbItem.MediaType.TV, "Blue Horizon", year = 2023)),
        ),
    )

    private val home = UiMappers.home(state, labels, Fixtures.NOW).copy(hero = emptyList(), tagSuggestion = null)

    private fun vod(series: Boolean) = UiMappers.vod(state, series = series, title = "", now = Fixtures.NOW)

    /** What a row's See all opened, as the app's navigation maps the screens' actions ([OndoApp]). */
    private var opened: Any? = null

    private fun vodActions(series: Boolean) = VodActions(
        onCategory = { opened = Routes.Grid(series = series, categoryId = it?.id) },
        onSeeAllMyList = { opened = Routes.Grid(series = series, myList = true) },
        onSeeAllRow = { opened = Routes.Grid(series = series, source = it.name) },
        onSeeAllSelection = { opened = Routes.Grid(series = series, selection = it, selectionTab = if (series) RowOrder.SERIES else RowOrder.MOVIES) },
    )

    private val homeActions = HomeActions(
        onSeeAllMyList = { opened = Routes.Grid(series = false, myList = true, anyType = true) },
        onSeeAllRow = { source, series -> opened = Routes.Grid(series = series, source = source.name) },
        onSeeAllLive = { opened = LIVE_TAB },
        onSeeAllSelection = { opened = Routes.Grid(series = false, anyType = true, selection = it, selectionTab = RowOrder.HOME) },
    )

    /** The rows of [screen], from the top, with what their See all must open. */
    private fun rows(screen: Screen): List<Row> = when (screen) {
        Screen.HOME -> {
            val (series, movies) = home.continueWatching.partition { it.isSeries }
            listOf(
                Row("shelfResumeMovies", emptyList(), movies.size, null, CONTINUE_WATCHING),
                Row("shelfResumeSeries", emptyList(), series.size, null, CONTINUE_WATCHING),
                Row("shelfMyList", home.myList, home.myList.size, Routes.Grid(series = false, myList = true, anyType = true)),
                Row("shelfNewMovies", home.newMovies, home.newMovies.size, Routes.Grid(series = false, source = GridSource.NEW.name)),
                Row("shelfNewSeries", home.newSeries, home.newSeries.size, Routes.Grid(series = true, source = GridSource.NEW.name)),
                Row("shelfTrending", home.trending, home.trending.size, null, TRENDING),
                Row("shelfChannels", emptyList(), home.channels.size, LIVE_TAB),
                home.selections.getValue(Selections.rowKey(SELECTION)).let { row ->
                    Row("shelfSelection-$SELECTION", row.items, row.items.size, Routes.Grid(series = false, anyType = true, selection = SELECTION, selectionTab = RowOrder.HOME))
                },
            )
        }
        Screen.MOVIES, Screen.SERIES -> {
            val series = screen == Screen.SERIES
            val vod = vod(series)
            // Every kind of row of the tab is there.
            assertEquals(RowOrder.builtIns(series), vod.shownRows.filter { it in RowOrder.builtIns(series) })
            vod.shownRows.map { key ->
                when (key) {
                    RowOrder.ALL -> Row("shelfAll", vod.all, vod.all.size, Routes.Grid(series = series))
                    RowOrder.CONTINUE_WATCHING -> Row("shelfResume", emptyList(), vod.continueWatching.size, null, CONTINUE_WATCHING)
                    RowOrder.MY_LIST -> Row("shelfMyList", vod.myList, vod.myList.size, Routes.Grid(series = series, myList = true))
                    RowOrder.NEW_RELEASES -> Row("shelfNew", vod.newReleases, vod.newReleases.size, Routes.Grid(series = series, source = GridSource.NEW.name))
                    RowOrder.TRENDING -> Row("shelfTrending", vod.trending, vod.trending.size, null, TRENDING)
                    RowOrder.AWARD_WINNERS -> Row("shelfAwards", vod.awardWinners, vod.awardWinners.size, Routes.Grid(series = series, source = GridSource.AWARDS.name))
                    Selections.rowKey(SELECTION) -> vod.selections.getValue(key).let { row ->
                        Row(
                            "shelfSelection-$SELECTION", row.items, row.items.size,
                            Routes.Grid(series = series, selection = SELECTION, selectionTab = if (series) RowOrder.SERIES else RowOrder.MOVIES),
                        )
                    }
                    else -> {
                        val row = vod.rows.first { it.key == key }
                        Row("shelf:${row.category.id}", row.items, row.items.size, Routes.Grid(series = series, categoryId = row.category.id))
                    }
                }
            }
        }
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun onATelevisionEveryRowEndsWithSeeAll() = everySurface(television = true)

    @Test
    fun onAPhoneEveryRowHasSeeAll() = everySurface(television = false)

    private fun everySurface(television: Boolean) {
        var screen by mutableStateOf(SURFACES.first())
        compose.setContent {
            OndoTheme {
                CompositionLocalProvider(LocalCarouselAutoAdvance provides false) {
                    TelevisionFrame {
                        key(screen) {
                            when (screen) {
                                Screen.HOME -> if (television) TvHomeScreen(home, homeActions, autoAdvance = false) else HomeScreen(home, homeActions, autoAdvance = false)
                                Screen.MOVIES -> VodScreen(vod(series = false), vodActions(series = false))
                                Screen.SERIES -> VodScreen(vod(series = true), vodActions(series = true))
                            }
                        }
                    }
                }
            }
        }
        for (surface in SURFACES) {
            compose.runOnIdle { screen = surface }
            compose.waitForIdle()
            val where = "${if (television) "TV" else "phone"} ${surface.name.lowercase()}"
            val rows = rows(surface)
            assertEquals("$where: the rows the screen shows are those listed", rows.map { it.tag }.toSet(), shownRows(surface))
            for (row in rows) check(where, surface, row, television)
        }
    }

    /** The page's list of rows. */
    private fun page(surface: Screen) = compose.onAllNodes(
        hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
            (hasTestTag(pageTag(surface)) or hasAnyAncestor(hasTestTag(pageTag(surface)))),
    ).onFirst()

    private fun pageTag(surface: Screen) = if (surface == Screen.HOME) "home" else "vod"

    /** The tags of every row the page shows, from its top to its end. */
    private fun shownRows(surface: Screen): Set<String> {
        val isRow = SemanticsMatcher("a row") { node -> node.config.getOrNull(SemanticsProperties.TestTag)?.let { it.startsWith("shelf") && it != "shelfRow" } == true }
        val tags = mutableSetOf<String>()
        for (index in 0 until 50) {
            if (runCatching { page(surface).performScrollToIndex(index) }.isFailure) break
            compose.waitForIdle()
            compose.onAllNodes(isRow).fetchSemanticsNodes().mapNotNullTo(tags) { it.config.getOrNull(SemanticsProperties.TestTag) }
        }
        page(surface).performScrollToIndex(0)
        return tags
    }

    private fun seeAll(row: Row) = (hasTestTag("seeAll") or hasTestTag("seeAllAll")) and hasAnyAncestor(hasTestTag(row.tag))

    private fun check(where: String, surface: Screen, row: Row, television: Boolean) {
        page(surface).performScrollToNode(hasTestTag(row.tag))
        compose.waitForIdle()
        if (television) {
            // Along the row to its end, where the See all card is.
            val along = compose.onNode(hasTestTag("shelfRow") and hasAnyAncestor(hasTestTag(row.tag)))
            if (row.opens != null) runCatching { along.performScrollToNode(seeAll(row)) } else along.performScrollToIndex(row.count - 1)
            compose.waitForIdle()
        }
        val found = compose.onAllNodes(seeAll(row)).fetchSemanticsNodes()
        if (row.opens == null) {
            assertTrue("$where: ${row.tag} has no See all (${row.why})", found.isEmpty())
            return
        }
        assertEquals("$where: ${row.tag} has a See all", 1, found.size)
        opened = null
        compose.onNode(seeAll(row)).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals("$where: ${row.tag}'s See all opens", row.opens, opened)
        val grid = row.opens as? Routes.Grid ?: return
        // All the row's titles, in its order, then the rest.
        val titles = UiMappers.grid(state, grid, now = Fixtures.NOW)
        assertTrue("$where: ${row.tag} has titles", row.items.isNotEmpty())
        assertEquals("$where: ${row.tag}'s grid starts with the row's titles", row.items.map { it.key }, titles.take(row.items.size).map { it.key })
    }

    private companion object {
        /** Every surface with rows of titles; each is checked on a TV and on a phone. */
        val SURFACES = listOf(Screen.HOME, Screen.MOVIES, Screen.SERIES)

        /** The selection shown on every tab. */
        const val SELECTION = "twenties"

        /** Home's channel row: See all opens the Live tab. */
        const val LIVE_TAB = "live tab"

        const val CONTINUE_WATCHING =
            "resume cards, not posters: a card resumes the title where it was left, which a poster grid cannot do; a title " +
                "older than the row's 20 resumes from its own page"
        const val TRENDING = "the row already lists every trending title of the catalogue: there is nothing more to see"
    }
}
