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

    @Test
    fun theMoviesAndSeriesTabsOpenWithTheirRowsAndTheirHeaderAtOnce() {
        val opened = surfaces.flatMap { (name, series) ->
            listOf(
                "$name, the catalogue being read" to UiMappers.vod(reading, series = series, title = name, now = Fixtures.NOW),
                "$name, the catalogue read" to UiMappers.vod(read, series = series, title = name, now = Fixtures.NOW, kept = Fixtures.kept()),
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
        }
    }
}
