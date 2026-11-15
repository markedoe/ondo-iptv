package com.markedoe.ondo.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import com.markedoe.ondo.FiledTwice
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.library.PictureSupport
import com.markedoe.ondo.data.ItemKind
import com.markedoe.ondo.data.LibraryState
import com.markedoe.ondo.data.WatchEntry
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.screens.DetailActions
import com.markedoe.ondo.ui.screens.MovieDetailScreen
import com.markedoe.ondo.ui.screens.MovieDetailUiState
import com.markedoe.ondo.ui.screens.VersionChip
import com.markedoe.ondo.ui.theme.OndoTheme
import com.markedoe.ondo.ui.tv.TelevisionFrame
import com.markedoe.ondo.ui.tv.TvMovieDetailScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A film's versions on its page: a badge each (two of the same tags are two), the one picked selected, a tap picks one. */
@RunWith(RobolectricTestRunner::class)
class VersionChipsTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val compose = createComposeRule()

    private val movie = Fixtures.glassCity
    private val state = MovieDetailUiState(
        item = ShowcaseItem.MovieItem(movie),
        versions = listOf(VersionChip(movie.id, "Standard"), VersionChip("dv", "4K Dolby Vision"), VersionChip("4k", "4K 1"), VersionChip("4k-2", "4K 2")),
        version = movie,
    )

    private fun selected(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected)

    @Test
    fun onAPhoneATapPicksAVersion() {
        val picked = mutableListOf<String>()
        compose.setContent { OndoTheme { MovieDetailScreen(state, DetailActions(onVersion = { picked += it })) } }
        // The page's list (the chips' row scrolls too).
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasTestTag("versions"))
        compose.onNodeWithText("Standard").assertExists()
        compose.onNodeWithText("4K 2").assertExists()
        // A screen reader hears what the row is: the film's versions.
        compose.onNodeWithContentDescription("Version: 4K Dolby Vision").assertExists()
        assertEquals(true, selected("version-${movie.id}"))
        compose.onNodeWithTag("version-dv").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("dv"), picked)
    }

    @Test
    @Config(qualifiers = "w960dp-h2400dp-television-xhdpi")
    fun onATvTheRemotePicksAVersion() {
        val picked = mutableListOf<String>()
        compose.setContent { OndoTheme { TelevisionFrame { TvMovieDetailScreen(state, DetailActions(onVersion = { picked += it })) } } }
        compose.onNodeWithTag("detail").performScrollToNode(hasTestTag("versions"))
        assertEquals(true, selected("version-${movie.id}"))
        compose.onNodeWithContentDescription("Version: 4K Dolby Vision").assertExists()
        compose.onNodeWithTag("version-4k-2").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("4k-2"), picked)
    }

    /**
     * One film a panel files under two categories, next to a 4K Dolby Vision version: the TV page shows two chips,
     * "Standard" and "4K Dolby Vision", and the remote on "Standard" plays its preferred stream (the first, else the one in
     * progress).
     */
    @Test
    @Config(qualifiers = "w960dp-h2400dp-television-xhdpi")
    fun aFilmFiledTwiceShowsTwoChipsOnATv() {
        val shape = FiledTwice
        // A screen without 4K or Dolby Vision, whatever another test left.
        UiMappers.pictureSupport = PictureSupport()
        val watching = WatchEntry(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, shape.again.id, title = shape.again.name, lastWatchedAt = 1)
        val inProgress = LibraryState().recordStart(watching).updateProgress(watching.key, 600.0, 6_000.0, now = 2)
        val pages = listOf(LibraryState() to shape.first.id, inProgress to shape.again.id)
        val picked = mutableListOf<String>()
        var page by mutableStateOf(UiMappers.movieDetail(shape.state(), shape.again))
        compose.setContent { OndoTheme { TelevisionFrame { TvMovieDetailScreen(page, DetailActions(onVersion = { picked += it })) } } }
        try {
            for ((library, plays) in pages) {
                page = UiMappers.movieDetail(shape.state(library), shape.again)
                compose.waitForIdle()
                compose.onNodeWithTag("detail").performScrollToNode(hasTestTag("versions"))
                assertEquals(2, compose.onAllNodes(hasTestTagStartingWith("version-")).fetchSemanticsNodes().size)
                compose.onNodeWithText("Standard").assertExists()
                compose.onNodeWithText("4K Dolby Vision").assertExists()
                assertEquals(true, selected("version-${shape.first.id}"))
                picked.clear()
                compose.onNodeWithTag("version-${shape.first.id}").performSemanticsAction(SemanticsActions.OnClick)
                assertEquals(listOf(plays), picked)
            }
        } finally {
            UiMappers.forgetCatalogue()
        }
    }

    private fun hasTestTagStartingWith(prefix: String) = SemanticsMatcher("test tag starting with $prefix") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }
}
