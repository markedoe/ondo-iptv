package com.markedoe.ondo.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.ui.OndoNavigationFrame
import com.markedoe.ondo.ui.TopLevel
import com.markedoe.ondo.ui.UiMappers
import com.markedoe.ondo.ui.components.LocalCarouselAutoAdvance
import com.markedoe.ondo.ui.screens.LiveActions
import com.markedoe.ondo.ui.screens.LiveScreen
import com.markedoe.ondo.ui.theme.OndoTheme
import com.markedoe.ondo.ui.tv.TelevisionFrame
import com.markedoe.ondo.ui.tv.TvNavigationFrame
import com.markedoe.ondo.ui.tv.tvSafeArea
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Reference screenshots of Live TV as it opens, on My List (the demo library's favourite channels): on a TV with the
 * remote on the first channel, then on All (the choice the remote is on, ringed, beside the one shown, white with its
 * check mark); on a phone. Compared by `verifyRoborazziDebug` with [BASELINES]`/tv/` and [BASELINES]`/phone/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w960dp-h540dp-land-television-xhdpi")
class LiveScreenshots {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val catalogue = ShowcaseCatalogue.load(false)
    private val state = catalogue.state()
    private val now = ShowcaseCatalogue.NOW

    @Before
    fun pictures() = ShowcaseImages.install(ApplicationProvider.getApplicationContext())

    @After
    fun noPictures() = ShowcaseImages.reset()

    /** Live TV as the app opens it: nothing chosen yet. */
    private fun show(television: Boolean) {
        val live = UiMappers.live(state, categoryId = null, favoritesOnly = null, now = now)
        check(live.showingFavorites) { "the demo library has favourite channels" }
        var inputModes: InputModeManager? = null
        compose.setContent {
            inputModes = LocalInputModeManager.current
            OndoTheme {
                CompositionLocalProvider(LocalCarouselAutoAdvance provides false) {
                    TelevisionFrame {
                        if (television) {
                            TvNavigationFrame(TopLevel.LIVE, showBar = true, onSelect = {}, onSearch = {}, onSettings = {}) {
                                Box(Modifier.tvSafeArea(tab = true)) { LiveScreen(live, LiveActions()) }
                            }
                        } else {
                            OndoNavigationFrame(TopLevel.LIVE, onSelect = {}) { LiveScreen(live, LiveActions()) }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        if (television) compose.runOnIdle { inputModes?.requestInputMode(InputMode.Keyboard) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun capture(folder: String, name: String) = compose.onRoot().captureRoboImage("$BASELINES/$folder/en-US/$name.png", BaselineOptions)

    @Test
    fun aTvOpensOnMyList() {
        show(television = true)
        capture("tv", "live-my-list")
    }

    @Test
    fun aTvShowsTheChoiceTheRemoteIsOnApartFromTheOneShown() {
        show(television = true)
        compose.onNodeWithTag("liveAll").requestFocus()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        capture("tv", "live-choice-focused")
        compose.onNodeWithTag("liveMyList").requestFocus()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        capture("tv", "live-choice-shown-focused")
    }

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
    fun aPhoneOpensOnMyList() {
        show(television = false)
        capture("phone", "live-my-list")
    }
}
