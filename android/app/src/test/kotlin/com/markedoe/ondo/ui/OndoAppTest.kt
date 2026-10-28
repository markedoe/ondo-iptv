package com.markedoe.ondo.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.data.AppSettings
import com.markedoe.ondo.data.AppStore
import com.markedoe.ondo.data.FakeHttp
import com.markedoe.ondo.data.OndoRepository
import com.markedoe.ondo.ui.components.LocalCarouselAutoAdvance
import com.markedoe.ondo.ui.theme.OndoTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The app's pages with a saved playlist: what they compute in the background reaches the screen. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class OndoAppTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val folder = TemporaryFolder()

    private fun start() {
        val store = AppStore(folder.root)
        store.savePlaylists(listOf(Fixtures.playlist))
        store.saveSettings(AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID))
        // Fresh: restored without downloading.
        store.saveContent(Fixtures.PLAYLIST_ID, Fixtures.content.copy(fetchedAt = System.currentTimeMillis()))
        val repository = OndoRepository(store, FakeHttp(emptyMap()), CoroutineScope(SupervisorJob() + Dispatchers.Main))
        val viewModel = OndoViewModel(repository, Labels(), minutes = flowOf(Fixtures.NOW))
        compose.setContent {
            OndoTheme { CompositionLocalProvider(LocalCarouselAutoAdvance provides false) { OndoApp(viewModel) } }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("hero").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun open(title: String) {
        val home = compose.onNodeWithTag("home")
        home.performScrollToNode(hasContentDescription(title))
        // A row just below the screen is laid out ahead, without a size, which the scroll above takes as in sight:
        // on until the title shows.
        fun shown() = compose.onAllNodesWithContentDescription(title).fetchSemanticsNodes().indexOfFirst { it.boundsInRoot.height > 0f }
        var row = 0
        while (shown() < 0) home.performScrollToIndex(++row)
        compose.onAllNodesWithContentDescription(title)[shown()].performClick()
        // The page is computed in the background: a moment of the loading look, then the page.
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("detail").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    @Test
    fun aMoviePageShowsItsSimilarTitles() {
        start()
        open(Fixtures.northernLights.name)
        compose.onNodeWithText(Fixtures.northernLights.name).assertExists()
        compose.onNodeWithTag("detail").performScrollToNode(hasText("More Like This"))
    }

    @Test
    fun aSeriesPagePlaysItsNextEpisodeAndSwitchesSeasonsAtOnce() {
        start()
        open(Fixtures.harborLights.name)
        compose.onNodeWithTag("detailPlay").assert(hasText("Play S01E01", substring = true))
        compose.onNodeWithTag("detail").performScrollToNode(hasText("Season 2"))
        compose.onNodeWithText("Season 2").performClick()
        compose.onNodeWithText("Season 2").assert(isSelected())
    }
}
