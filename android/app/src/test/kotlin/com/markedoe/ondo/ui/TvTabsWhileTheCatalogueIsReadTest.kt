package com.markedoe.ondo.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.R
import com.markedoe.ondo.core.model.PlaylistContent
import com.markedoe.ondo.data.ItemKind
import com.markedoe.ondo.data.LibraryState
import com.markedoe.ondo.data.OndoState
import com.markedoe.ondo.data.WatchEntry
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.model.TitleHeader
import com.markedoe.ondo.ui.screens.VodActions
import com.markedoe.ondo.ui.screens.VodScreen
import com.markedoe.ondo.ui.theme.OndoTheme
import com.markedoe.ondo.ui.tv.LocalFocusMemory
import com.markedoe.ondo.ui.tv.LocalTitleHeaders
import com.markedoe.ondo.ui.tv.TelevisionFrame
import com.markedoe.ondo.ui.tv.TitleHeaders
import com.markedoe.ondo.ui.tv.rememberFocusMemory
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
 * The Movies and Series tabs on a TV while the saved catalogue is read (Home kept from the last start, the viewer
 * already on a tab, Continue watching made of the history, no backdrop known for the header's title): the page's
 * picture is the poster blurred edge to edge, never a sharp poster card over the header, and no "Nothing here yet"
 * under the rows. Then the catalogue is read: its rows arrive without moving the focus or anything on screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TvTabsWhileTheCatalogueIsReadTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** Nothing more ever comes of a title: its backdrop stays unknown. */
    private object NothingComes : TitleHeaders {
        override fun header(item: ShowcaseItem): Flow<TitleHeader> = flow { awaitCancellation() }

        override suspend fun load(item: ShowcaseItem) = Unit
    }

    /** A film and an episode in progress, with the poster the history kept of each. */
    private val library = LibraryState(
        history = listOf(
            WatchEntry(
                Fixtures.PLAYLIST_ID, ItemKind.MOVIE, Fixtures.northernLights.id, title = Fixtures.northernLights.name,
                imageUrl = "https://img.example.com/poster/movie.jpg", position = 600.0, duration = 6_000.0, lastWatchedAt = Fixtures.NOW - 1_000L,
            ),
            WatchEntry(
                Fixtures.PLAYLIST_ID, ItemKind.SERIES, Fixtures.harborLights.id, "${Fixtures.harborLights.id}-1-2", Fixtures.harborLights.name,
                imageUrl = "https://img.example.com/poster/series.jpg", position = 300.0, duration = 2_400.0, lastWatchedAt = Fixtures.NOW - 2_000L,
            ),
        ),
    )

    private val read: OndoState = Fixtures.state(library = library)

    /** The same start, the saved catalogue still being read. */
    private val reading: OndoState = read.copy(content = PlaylistContent(), seriesDetails = emptyMap(), isRestoring = true)

    /** Every tab with a header above its rows (each runs in a test of its own below): a new one is added here. */
    private val surfaces = mapOf("Movies" to false, "Series" to true)

    @Test
    fun moviesWhileTheCatalogueIsRead() = whileRead("Movies")

    @Test
    fun seriesWhileTheCatalogueIsRead() = whileRead("Series")

    private fun whileRead(name: String) {
        val series = surfaces.getValue(name)
        val before = UiMappers.vod(reading, series = series, title = name, now = Fixtures.NOW)
        val after = UiMappers.vod(read, series = series, title = name, now = Fixtures.NOW, kept = Fixtures.kept())
        assertTrue("$name: being read", before.reading && before.rows.isEmpty() && before.continueWatching.isNotEmpty())
        assertTrue("$name: read", !after.reading && after.rows.isNotEmpty())
        var state by mutableStateOf(before)
        var inputModes: InputModeManager? = null
        compose.setContent {
            inputModes = LocalInputModeManager.current
            OndoTheme {
                TelevisionFrame {
                    CompositionLocalProvider(LocalFocusMemory provides rememberFocusMemory(), LocalTitleHeaders provides NothingComes) {
                        VodScreen(state, VodActions())
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { inputModes?.requestInputMode(InputMode.Keyboard) }
        compose.mainClock.autoAdvance = false
        advance(500)
        // The focus lands on the first card by itself, as on a cold start (the tab's first row: Continue watching).
        advance(1_000)
        val cards = compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("shelfResume")))
        cards[0].assert(isFocused())

        assertPageBackdropOnly("$name, being read")
        assertTrue("$name, being read: no empty state", empty().isEmpty())

        val header = bounds("titleHeader")
        val row = bounds("shelfResume")
        val card = cards[0].fetchSemanticsNode().boundsInRoot

        // The catalogue read: applied at once, as the view model's state reaching the screen is.
        compose.runOnIdle { Snapshot.withMutableSnapshot { state = after } }
        advance(1_000)
        cards[0].assert(isFocused())
        assertSame("$name: the header stays", header, bounds("titleHeader"))
        assertEquals("$name: nothing scrolls", row.top, bounds("shelfResume").top, 0.5f)
        assertSame("$name: the card stays where it was", card, cards[0].fetchSemanticsNode().boundsInRoot)
        assertTrue("$name, read: no empty state with rows", empty().isEmpty())
    }

    /**
     * The only artwork behind the page is the page's own picture, filling it: no poster-sized artwork within the
     * header's bounds, no poster card.
     */
    private fun assertPageBackdropOnly(what: String) {
        val page = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val header = bounds("titleHeader")
        val arts = compose.onAllNodes(hasTestTag("tvArtwork"), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("$what: the page's picture is drawn", arts.isNotEmpty())
        for (art in arts) {
            assertSame("$what: the picture fills the page", page, art.boundsInRoot)
        }
        val inBackdrop = compose.onAllNodes(hasAnyAncestor(hasTestTag("pageBackdrop")), useUnmergedTree = true).fetchSemanticsNodes()
        for (node in inBackdrop) {
            val bounds = node.boundsInRoot
            assertTrue("$what: no picture smaller than the page within the header ($bounds)", same(bounds, page) || !bounds.overlaps(header))
        }
        assertTrue(
            "$what: no poster card",
            compose.onAllNodes(hasTestTag("tvArtworkPoster"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
        )
    }

    private fun empty() = compose.onAllNodes(
        hasText(compose.activity.getString(R.string.nothing_here)) or hasTestTag("vodEmpty"),
        useUnmergedTree = true,
    ).fetchSemanticsNodes()

    private fun bounds(tag: String): Rect = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot

    private fun same(a: Rect, b: Rect): Boolean =
        listOf(a.left - b.left, a.top - b.top, a.right - b.right, a.bottom - b.bottom).all { kotlin.math.abs(it) <= 1f }

    private fun assertSame(what: String, expected: Rect, actual: Rect) = assertTrue("$what: expected $expected, was $actual", same(expected, actual))

    private fun advance(ms: Long) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }
}
