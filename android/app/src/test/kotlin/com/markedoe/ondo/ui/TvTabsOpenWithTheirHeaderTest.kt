package com.markedoe.ondo.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.library.CatalogueMatcher
import com.markedoe.ondo.core.library.RowOrder
import com.markedoe.ondo.core.model.PlaylistContent
import com.markedoe.ondo.data.ItemKind
import com.markedoe.ondo.data.LibraryState
import com.markedoe.ondo.data.OndoState
import com.markedoe.ondo.data.WatchEntry
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.model.TitleHeader
import com.markedoe.ondo.ui.screens.VodActions
import com.markedoe.ondo.ui.screens.VodScreen
import com.markedoe.ondo.ui.screens.VodUiState
import com.markedoe.ondo.ui.theme.OndoTheme
import com.markedoe.ondo.ui.tv.LocalTitleHeaders
import com.markedoe.ondo.ui.tv.TelevisionFrame
import com.markedoe.ondo.ui.tv.TitleHeaders
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.ui.model.ResumeItem
import com.markedoe.ondo.ui.screens.CategoryRow
import com.markedoe.ondo.ui.tv.HEADER_DEBOUNCE_MS
import java.util.Locale
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Movies and Series tabs on a TV, opened the first time: their rows and the header above them show together, the
 * header never an empty band over rows already drawn. While the saved catalogue is read (Home kept from the last start
 * shown at once, the viewer already on a tab), the tab has only its titles to continue watching: the header shows the
 * first of them. Once the catalogue is read, the first title of the first row. The header shows what the tab's state
 * holds, at once: nothing it waits for (the title's facts worked out off the main thread) ever comes in this test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TvTabsOpenWithTheirHeaderTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** What the view model would say of a title: never anything, the header shows only what the tab's state holds. */
    private object NothingComes : TitleHeaders {
        override fun header(item: ShowcaseItem): Flow<TitleHeader> = flow { awaitCancellation() }

        override suspend fun load(item: ShowcaseItem) = Unit
    }

    /** A film and an episode in progress: one card to continue watching on each tab. */
    private val library = LibraryState(
        history = listOf(
            WatchEntry(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, Fixtures.northernLights.id, title = Fixtures.northernLights.name, position = 600.0, duration = 6_000.0, lastWatchedAt = Fixtures.NOW - 1_000L),
            WatchEntry(
                Fixtures.PLAYLIST_ID, ItemKind.SERIES, Fixtures.harborLights.id, "${Fixtures.harborLights.id}-1-2", Fixtures.harborLights.name,
                position = 300.0, duration = 2_400.0, lastWatchedAt = Fixtures.NOW - 2_000L,
            ),
        ),
    )

    private val read: OndoState = Fixtures.state(library = library)

    /** The same start, the saved catalogue still being read. */
    private val reading: OndoState = read.copy(content = PlaylistContent(), seriesDetails = emptyMap(), isRestoring = true)

    /** Every tab with a header above its rows: a new one is added here. */
    private val surfaces = mapOf("Movies" to false, "Series" to true)

    private fun texts(tag: String): List<String> =
        compose.onAllNodes(hasTestTag(tag) and hasAnyAncestor(hasTestTag("titleHeader")), useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text } }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** The header worked out with the tab ([VodUiState.header]), as the view model does: a backdrop of its own. */
    private fun withHeader(state: OndoState, vod: VodUiState): VodUiState {
        val item = requireNotNull(vod.headerTitle) { "a title for the header" }
        val header = UiMappers.titleHeader(state, item, Fixtures.kept()[item.key], Locale.ENGLISH)
        return vod.copy(header = header.copy(backdropUrl = "https://img.example.com/backdrop/${item.key}-ready.jpg"))
    }

    @Test
    fun theMoviesAndSeriesTabsOpenWithTheirRowsAndTheirHeaderAtOnce() {
        val opened = surfaces.flatMap { (name, series) ->
            val whole = UiMappers.vod(read, series = series, title = name, now = Fixtures.NOW, kept = Fixtures.kept())
            listOf(
                "$name, the catalogue being read" to UiMappers.vod(reading, series = series, title = name, now = Fixtures.NOW),
                "$name, the catalogue read" to whole,
                "$name, the catalogue read, its header worked out with it" to withHeader(read, whole),
            )
        }
        var shown by mutableStateOf(0)
        compose.setContent {
            OndoTheme {
                TelevisionFrame {
                    CompositionLocalProvider(LocalTitleHeaders provides NothingComes) {
                        // Each opening a first one: nothing kept from the tab shown before.
                        key(shown) { VodScreen(opened[shown].second, VodActions()) }
                    }
                }
            }
        }
        for ((index, entry) in opened.withIndex()) {
            val (what, state) = entry
            compose.runOnIdle { shown = index }
            compose.waitForIdle()
            val first = state.shownRows.firstOrNull()
            assertTrue("$what: rows to show", first != null)
            if (state.continueWatching.isNotEmpty()) assertTrue("$what: the row to continue watching drawn", exists("shelfResume"))
            val expected = if (first == RowOrder.CONTINUE_WATCHING) state.continueWatching.first().title else state.firstTitle(first)?.title
            assertTrue("$what: a title to show in the header", expected != null)
            assertEquals("$what: the header shows the first card's title with the rows", listOf(CatalogueMatcher.displayTitle(expected!!)), texts("headerTitle"))
            val ready = state.header ?: continue
            // What the tab's state holds, at once: nothing else could bring it here.
            assertEquals("$what: its rating at once", 1, texts("headerRating").filter { it.isNotBlank() }.size)
            assertEquals("$what: its synopsis at once", listOf(ready.plot.orEmpty()), texts("headerPlot"))
            assertTrue("$what: a synopsis to show", !ready.plot.isNullOrBlank())
            assertTrue(
                "$what: its backdrop at once",
                compose.onAllNodes(hasTestTag("tvArtworkBackdrop") and hasAnyAncestor(hasTestTag("pageBackdrop")), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
            )
        }
    }

    /** What the view model says of a title: its synopsis and rating, as soon as asked. */
    private object Echo : TitleHeaders {
        override fun header(item: ShowcaseItem): Flow<TitleHeader> =
            flowOf(TitleHeader(item, plot = (item as? ShowcaseItem.MovieItem)?.movie?.plot, providerRating = item.rating?.toString()))

        override suspend fun load(item: ShowcaseItem) = Unit
    }

    /**
     * The card with the focus is of a title made of the history (the catalogue being read); the catalogue is read: the
     * header shows the catalogue's title, the focus stays on that card and nothing scrolls.
     */
    @Test
    fun aFocusedCardsTitleMadeOfTheHistoryGivesWayToTheCataloguesOne() {
        val real = ShowcaseItem.MovieItem(Fixtures.northernLights)
        val standIn = ShowcaseItem.MovieItem(Movie(id = real.movie.id, name = real.movie.name, categoryId = "", streamUrl = ""))
        val others = listOf(Fixtures.summerInLisbon, Fixtures.longRoad).map { ShowcaseItem.MovieItem(it) }
        fun tab(title: ShowcaseItem) = VodUiState(
            title = "Movies",
            categories = listOf(Fixtures.drama),
            continueWatching = listOf(
                ResumeItem("${Fixtures.PLAYLIST_ID}|MOVIE|${real.movie.id}|", real.title, subtitle = null, imageUrl = null, progress = 0.1f, minutesLeft = 90, hasPage = true, showcase = title),
            ),
            rows = listOf(CategoryRow(Fixtures.drama, others)),
        )
        var state by mutableStateOf(tab(standIn))
        var inputModes: InputModeManager? = null
        compose.setContent {
            inputModes = LocalInputModeManager.current
            OndoTheme { TelevisionFrame { CompositionLocalProvider(LocalTitleHeaders provides Echo) { VodScreen(state, VodActions()) } } }
        }
        compose.waitForIdle()
        compose.runOnIdle { inputModes?.requestInputMode(InputMode.Keyboard) }
        compose.mainClock.autoAdvance = false
        advance(500)
        val card = hasClickAction() and hasAnyAncestor(hasTestTag("shelfResume"))
        compose.onAllNodes(card).onFirst().requestFocus()
        compose.waitForIdle()
        advance(HEADER_DEBOUNCE_MS + 50)
        assertTrue("the card has the focus", compose.onAllNodes(isFocused() and hasAnyAncestor(hasTestTag("shelfResume"))).fetchSemanticsNodes().isNotEmpty())
        assertEquals(listOf(real.title), texts("headerTitle"))
        assertTrue("no synopsis yet", texts("headerPlot").none { it.isNotBlank() })
        val row = compose.onNodeWithTag("shelfResume", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

        compose.runOnIdle { state = tab(real) }
        advance(HEADER_DEBOUNCE_MS + 50)
        assertEquals("the catalogue's title", listOf(real.movie.plot.orEmpty()), texts("headerPlot"))
        assertEquals(listOf(real.title), texts("headerTitle"))
        assertTrue("the focus stays on the card", compose.onAllNodes(isFocused() and hasAnyAncestor(hasTestTag("shelfResume"))).fetchSemanticsNodes().isNotEmpty())
        assertEquals("nothing scrolls", row.top, compose.onNodeWithTag("shelfResume", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top, 0.5f)
    }

    private fun advance(ms: Long) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }
}
