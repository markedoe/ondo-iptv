package com.markedoe.ondo.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import com.markedoe.ondo.Fixtures
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
    @get:Rule val compose = createComposeRule()

    private val movie = Fixtures.glassCity
    private val state = MovieDetailUiState(
        item = ShowcaseItem.MovieItem(movie),
        versions = listOf(VersionChip(movie.id, null, 1), VersionChip("dv", "4K DV", 1), VersionChip("4k", "4K", 1), VersionChip("4k-2", "4K 2", 2)),
        version = movie,
    )

    private fun selected(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected)

    @Test
    fun onAPhoneATapPicksAVersion() {
        val picked = mutableListOf<String>()
        compose.setContent { OndoTheme { MovieDetailScreen(state, DetailActions(onVersion = { picked += it })) } }
        // The page's list (the chips' row scrolls too).
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasTestTag("versions"))
        compose.onNodeWithText("Version").assertExists()
        compose.onNodeWithText("4K 2").assertExists()
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
        compose.onNodeWithTag("version-4k-2").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("4k-2"), picked)
    }
}
