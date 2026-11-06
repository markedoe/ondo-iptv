package com.markedoe.ondo.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.github.takahirom.roborazzi.captureRoboImage
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.model.Profile
import com.markedoe.ondo.ui.TopLevel
import com.markedoe.ondo.ui.theme.OndoTheme
import com.markedoe.ondo.ui.tv.TelevisionFrame
import com.markedoe.ondo.ui.tv.TvNavigationFrame
import com.markedoe.ondo.ui.tv.TvPlaylistStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Reference screenshots of a TV's top bar with the playlist's status before Search: updated (a check and "5 min"),
 * being updated (a spinner), failed (a warning sign), in English, Arabic (right to left) and Hindi. Compared by
 * `verifyRoborazziDebug` with [BASELINES]`/tv/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w960dp-h540dp-land-television-xhdpi")
class TvPlaylistStatusScreenshots {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = 1_760_000_000_000L

    private fun capture(locale: String, name: String, status: TvPlaylistStatus) {
        var inputModes: InputModeManager? = null
        compose.setContent {
            inputModes = LocalInputModeManager.current
            OndoTheme {
                TelevisionFrame {
                    TvNavigationFrame(TopLevel.HOME, showBar = true, onSelect = {}, onSearch = {}, onSettings = {}, profile = Profile.main("Me"), playlist = status) {
                        Box(Modifier.fillMaxSize())
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { inputModes?.requestInputMode(InputMode.Keyboard) }
        // The spinner turns: one still frame of it.
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("topBar").captureRoboImage("$BASELINES/tv/$locale/$name.png", BaselineOptions)
    }

    private fun updated(minutesAgo: Long, refreshing: Boolean = false, failed: Boolean = false) =
        TvPlaylistStatus("Example Playlist", updatedAt = now - minutesAgo * 60_000L, now = now, refreshing = refreshing, failed = failed)

    @Test
    fun updated() = capture("en-US", "top-bar-playlist-updated", updated(5))

    @Test
    fun refreshing() = capture("en-US", "top-bar-playlist-refreshing", updated(5, refreshing = true))

    @Test
    fun failed() = capture("en-US", "top-bar-playlist-failed", updated(3 * 60, failed = true))

    @Test
    @Config(qualifiers = "ar-w960dp-h540dp-land-television-xhdpi")
    fun arabic() = capture("ar", "top-bar-playlist-updated", updated(5))

    @Test
    @Config(qualifiers = "hi-rIN-w960dp-h540dp-land-television-xhdpi")
    fun hindi() = capture("hi-IN", "top-bar-playlist-updated", updated(23 * 60))
}
