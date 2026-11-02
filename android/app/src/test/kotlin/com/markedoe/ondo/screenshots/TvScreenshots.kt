package com.markedoe.ondo.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.markedoe.ondo.ui.Labels
import com.markedoe.ondo.ui.TopLevel
import com.markedoe.ondo.ui.UiMappers
import com.markedoe.ondo.ui.components.LocalCarouselAutoAdvance
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.model.TitleHeader
import com.markedoe.ondo.ui.screens.HomeActions
import com.markedoe.ondo.ui.screens.VodActions
import com.markedoe.ondo.ui.screens.VodScreen
import com.markedoe.ondo.ui.theme.OndoTheme
import com.markedoe.ondo.ui.tv.LocalTitleHeaders
import com.markedoe.ondo.ui.tv.PlaylistUpdate
import com.markedoe.ondo.ui.tv.TelevisionFrame
import com.markedoe.ondo.ui.tv.TitleHeaders
import com.markedoe.ondo.ui.tv.TvHomeScreen
import com.markedoe.ondo.ui.tv.TvNavigationFrame
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Reference screenshots of a TV's tab pages as the remote leaves them, from the demo catalogue: the header above the
 * Movies tab (its backdrop from the screen's top, the playlist's line in the bar), with a card of Continue watching
 * focused, with the chips focused, and Home with the remote in its rows. Compared by `verifyRoborazziDebug` with
 * [BASELINES]`/tv/`; the store listing has none of them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w960dp-h540dp-land-television-xhdpi")
class TvScreenshots {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val catalogue = ShowcaseCatalogue.load(french = false)
    private val state = catalogue.state()
    private val now = ShowcaseCatalogue.NOW
    private val labels = Labels("Movie", "Series", Locale.US)
    private val playlist = PlaylistUpdate(state.activePlaylist?.name.orEmpty(), state.content.fetchedAt, now)

    /** What the app's view model would show of a title in the header: the demo's details. */
    private val headers = object : TitleHeaders {
        override fun header(item: ShowcaseItem): Flow<TitleHeader> = flowOf(UiMappers.titleHeader(state, item, null, Locale.US))

        override suspend fun load(item: ShowcaseItem) = Unit
    }

    @Before
    fun pictures() = ShowcaseImages.install(ApplicationProvider.getApplicationContext())

    @After
    fun noPictures() = ShowcaseImages.reset()

    private fun show(tab: TopLevel, content: @Composable () -> Unit) {
        var inputModes: InputModeManager? = null
        compose.setContent {
            inputModes = LocalInputModeManager.current
            OndoTheme {
                CompositionLocalProvider(LocalCarouselAutoAdvance provides false) {
                    TelevisionFrame {
                        TvNavigationFrame(tab, showBar = true, onSelect = {}, onSearch = {}, onSettings = {}, playlist = playlist) {
                            CompositionLocalProvider(LocalTitleHeaders provides headers, content = content)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { inputModes?.requestInputMode(InputMode.Keyboard) }
        compose.mainClock.autoAdvance = false
        advance()
    }

    /** Past the header's wait and its animations. */
    private fun advance() {
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    /** The first card of the first of [shelves] the page has. */
    private fun focusFirstCard(vararg shelves: String) {
        val shelf = shelves.first { compose.onAllNodes(hasTestTag(it)).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag(shelf))).onFirst().requestFocus()
        advance()
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage("$BASELINES/tv/en-US/$name.png", BaselineOptions)

    private fun moviesTab() = show(TopLevel.MOVIES) { VodScreen(UiMappers.vod(state, series = false, title = "Movies"), VodActions()) }

    @Test
    fun moviesWithContinueWatchingFocused() {
        moviesTab()
        focusFirstCard("shelfResume")
        capture("movies-continue-watching")
    }

    @Test
    fun moviesWithMyListFocused() {
        moviesTab()
        // My List is below Continue watching, off the screen: the remote goes down to it.
        focusFirstCard("shelfResume")
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionDown) }
        advance()
        assertTrue("a card of My List has the focus", compose.onAllNodes(isFocused() and hasAnyAncestor(hasTestTag("shelfMyList"))).fetchSemanticsNodes().isNotEmpty())
        capture("movies-my-list")
    }

    @Test
    fun moviesWithTheChipsFocused() {
        moviesTab()
        compose.onNodeWithTag("categoryFilter").requestFocus()
        advance()
        capture("movies-chips")
    }

    @Test
    fun homeWithTheRowsFocused() {
        show(TopLevel.HOME) { TvHomeScreen(UiMappers.home(state, labels, now).copy(channels = emptyList()), HomeActions(), autoAdvance = false) }
        focusFirstCard("shelfResumeMovies", "shelfResumeSeries", "shelfMyList", "shelfNewMovies")
        capture("home-rows")
    }
}
