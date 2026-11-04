package com.markedoe.ondo.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.library.RowOrder
import com.markedoe.ondo.core.sync.SyncRows
import com.markedoe.ondo.data.AppSettings
import com.markedoe.ondo.data.LibraryState
import com.markedoe.ondo.ui.screens.RowOrderActions
import com.markedoe.ondo.ui.screens.RowOrderScreen
import com.markedoe.ondo.ui.screens.RowOrderUiState
import com.markedoe.ondo.ui.screens.VodActions
import com.markedoe.ondo.ui.screens.VodScreen
import com.markedoe.ondo.ui.theme.OndoTheme
import com.markedoe.ondo.ui.tv.TelevisionFrame
import com.markedoe.ondo.ui.tv.TvRowOrderScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings → Row order and the rows it orders: the Movies tab follows the order saved, All movies opens the whole
 * catalogue, a hidden category never shows whatever the order, and on a TV the remote moves a row (OK, Up and Down,
 * OK; Back puts it back).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class RowOrderAppTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val drama = RowOrder.categoryKey(Fixtures.drama.id, Fixtures.drama.name)
    private val sciFi = RowOrder.categoryKey(Fixtures.sciFi.id, Fixtures.sciFi.name)
    private val thriller = RowOrder.categoryKey(Fixtures.thriller.id, Fixtures.thriller.name)

    private fun settings(vararg rows: SyncRows) = AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID, rowOrders = rows.toList())

    @Test
    fun theMoviesTabFollowsTheOrderSavedAndAllMoviesComesFirstByDefault() {
        val byDefault = UiMappers.vod(Fixtures.state(), series = false, title = "", now = Fixtures.NOW)
        assertEquals(RowOrder.ALL, byDefault.shownRows.first())
        // Each film once, the latest addition first; the count is the whole grid's.
        assertEquals(Fixtures.northernLights.id, (byDefault.all.first() as com.markedoe.ondo.ui.model.ShowcaseItem.MovieItem).movie.id)
        assertEquals(UiMappers.grid(Fixtures.state(), series = false, categoryId = null, myList = false, filter = "").size, byDefault.allCount)
        val saved = SyncRows(SyncRows.MOVIES, listOf(thriller, "all", drama), listOf(sciFi, "new"), updatedAt = 1)
        val ordered = UiMappers.vod(Fixtures.state(settings = settings(saved)), series = false, title = "", now = Fixtures.NOW)
        assertEquals(listOf(thriller, "all", drama), ordered.shownRows.take(3))
        assertFalse(sciFi in ordered.shownRows)
        assertFalse("new" in ordered.shownRows)
        // The series tab keeps its own order.
        val series = UiMappers.vod(Fixtures.state(settings = settings(saved)), series = true, title = "", now = Fixtures.NOW)
        assertEquals(RowOrder.ALL, series.shownRows.first())
    }

    /** The order travels with sync as one record per tab, and the merged one is kept (docs/SYNC.md, "Rows"). */
    @Test
    fun theOrderTravelsWithSyncAndTheLaterOneIsKept() {
        val mine = SyncRows(SyncRows.MOVIES, listOf(drama, "all"), updatedAt = 10)
        val state = Fixtures.state(settings = settings(mine))
        val sent = com.markedoe.ondo.data.SyncData.snapshot(state, "a", null, now = 20)
        assertEquals(listOf(mine), sent.rows)
        val later = SyncRows(SyncRows.MOVIES, listOf("all", drama), listOf("trending"), updatedAt = 15)
        val merged = com.markedoe.ondo.core.sync.SyncMerge.merge(sent, com.markedoe.ondo.core.sync.SyncSnapshot(device = "b", rows = listOf(later)))
        assertEquals(listOf(later), com.markedoe.ondo.data.SyncData.apply(state, merged).rows)
        // An older version sends none: the device keeps its own.
        val old = com.markedoe.ondo.core.sync.SyncMerge.merge(sent, com.markedoe.ondo.core.sync.SyncSnapshot(device = "old"))
        assertEquals(listOf(mine), com.markedoe.ondo.data.SyncData.apply(state, old).rows)
        // Both tabs saved: a merge that changes nothing gives them back as they are kept (nothing to write again).
        val both = listOf(mine, SyncRows(SyncRows.SERIES, listOf("all"), updatedAt = 11))
        val kept = Fixtures.state(settings = AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID, rowOrders = both))
        val again = com.markedoe.ondo.core.sync.SyncMerge.merge(com.markedoe.ondo.data.SyncData.snapshot(kept, "a", null, now = 20), com.markedoe.ondo.core.sync.SyncSnapshot(device = "b"))
        assertEquals(kept.settings.rowOrders, com.markedoe.ondo.data.SyncData.apply(kept, again).rows)
    }

    /** A category hidden in Settings (here with its language) never shows, whatever the order saved says. */
    @Test
    fun aHiddenCategoryNeverShowsWhateverTheOrder() {
        val saved = SyncRows(SyncRows.MOVIES, listOf(drama, "all"), updatedAt = 1)
        val state = Fixtures.state(
            library = LibraryState(hiddenCategories = setOf("${Fixtures.PLAYLIST_ID}|${Fixtures.drama.id}")),
            settings = settings(saved),
        )
        val vod = UiMappers.vod(state, series = false, title = "", now = Fixtures.NOW)
        assertFalse(drama in vod.shownRows)
        assertEquals(RowOrder.ALL, vod.shownRows.first())
        assertTrue(vod.all.none { (it as com.markedoe.ondo.ui.model.ShowcaseItem.MovieItem).movie.categoryId == Fixtures.drama.id })
        assertFalse(UiMappers.rowOrder(state).movies.any { it.key == drama })
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun theRowsShowInTheirOrderAndAllMoviesSeeAllOpensTheWholeCatalogue() {
        val saved = SyncRows(SyncRows.MOVIES, listOf(sciFi, "all"), updatedAt = 1)
        val ui = UiMappers.vod(Fixtures.state(settings = settings(saved)), series = false, title = "Movies", now = Fixtures.NOW)
        var opened: Any? = "nothing"
        var inputModes: InputModeManager? = null
        compose.setContent {
            inputModes = LocalInputModeManager.current
            OndoTheme { TelevisionFrame { VodScreen(ui, VodActions(onCategory = { opened = it })) } }
        }
        compose.runOnIdle { inputModes?.requestInputMode(InputMode.Keyboard) }
        compose.waitForIdle()
        val top = { tag: String -> compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top }
        assertTrue(top("shelf:${Fixtures.sciFi.id}") < top("shelfAll"))
        // At the end of the row, after its titles.
        compose.onNode(hasScrollAction() and hasAnyAncestor(hasTestTag("shelfAll"))).performScrollToNode(hasTestTag("seeAllAll"))
        // The remote's OK on it.
        compose.onNodeWithTag("seeAllAll").requestFocus()
        compose.waitForIdle()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        // null: every category, the grid of the whole catalogue.
        assertNull(opened)
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun theRemotePicksUpMovesAndPutsDownARowAndBackCancels() {
        var state by mutableStateOf(UiMappers.rowOrder(Fixtures.state()))
        val saves = mutableListOf<List<String>>()
        val actions = RowOrderActions(onSave = { series, arranged, hidden ->
            if (!series) saves += arranged
            val rows = (if (series) state.series else state.movies).associateBy { it.key }
            val next = arranged.map { rows.getValue(it).copy(hidden = it in hidden) }
            state = if (series) state.copy(series = next) else state.copy(movies = next)
        })
        var inputModes: InputModeManager? = null
        compose.setContent {
            inputModes = LocalInputModeManager.current
            OndoTheme { TelevisionFrame { TvRowOrderScreen(state, actions) } }
        }
        compose.runOnIdle { inputModes?.requestInputMode(InputMode.Keyboard) }
        compose.onNodeWithTag("row-movies-all").requestFocus()
        compose.waitForIdle()
        fun press(key: Key) {
            compose.onRoot().performKeyInput { pressKey(key) }
            compose.waitForIdle()
        }
        // OK picks All movies up; Down twice moves it below Continue Watching and My List; OK puts it down.
        press(Key.DirectionCenter)
        compose.onNodeWithTag("row-movies-all").assertIsSelected()
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        compose.onNodeWithTag("row-movies-all").assertIsFocused()
        assertTrue(saves.isEmpty())
        press(Key.DirectionCenter)
        assertEquals(listOf("continue", "mylist", "all"), saves.single().take(3))
        assertEquals(listOf("continue", "mylist", "all"), state.movies.map { it.key }.take(3))
        // Picked up again, moved, then Back: it goes back where it was, and nothing is saved.
        press(Key.DirectionCenter)
        press(Key.DirectionUp)
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(1, saves.size)
        assertEquals(listOf("continue", "mylist", "all"), state.movies.map { it.key }.take(3))
        compose.onNodeWithTag("row-movies-all").assertIsNotSelected()
        // Right reaches the switch: OK hides the row.
        press(Key.DirectionRight)
        val tag = compose.onNode(isFocused()).fetchSemanticsNode().config.getOrNull(SemanticsProperties.TestTag)
        assertEquals("rowSwitch-movies-all", tag)
        press(Key.DirectionCenter)
        assertTrue(state.movies.first { it.key == "all" }.hidden)
    }

    /** On a phone, TalkBack's Move up action moves a row, and the switch hides it. */
    @Test
    fun aPhoneMovesARowAndHidesIt() {
        var state by mutableStateOf(UiMappers.rowOrder(Fixtures.state()))
        val actions = RowOrderActions(onSave = { series, arranged, hidden ->
            val rows = (if (series) state.series else state.movies).associateBy { it.key }
            val next = arranged.map { rows.getValue(it).copy(hidden = it in hidden) }
            state = if (series) state.copy(series = next) else state.copy(movies = next)
        })
        compose.setContent { OndoTheme { RowOrderScreen(state, actions) } }
        compose.waitForIdle()
        val moveUp = compose.onNodeWithTag("row-movies-continue").fetchSemanticsNode().config[SemanticsActions.CustomActions].first()
        compose.runOnIdle { moveUp.action() }
        compose.waitForIdle()
        assertEquals(listOf("continue", "all"), state.movies.map { it.key }.take(2))
        compose.onNodeWithTag("rowSwitch-movies-all").performClick()
        compose.waitForIdle()
        assertTrue(state.movies.first { it.key == "all" }.hidden)
        assertEquals(1, compose.onAllNodesWithTag("rowOrderDefault-movies").fetchSemanticsNodes().size)
    }
}
