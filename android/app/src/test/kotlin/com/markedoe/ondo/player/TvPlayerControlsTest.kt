package com.markedoe.ondo.player

import androidx.activity.ComponentActivity
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import com.markedoe.ondo.ui.tv.OndoTvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The TV player's controls with a player that has tracks: the Subtitles and Audio panels name them by language in the
 * app's words, with what tells them apart, the one playing ticked; the remote chooses one, or Off, and the player's
 * track selection follows. The buttons and the progress bar move through the video.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TvPlayerControlsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var controls: TvControls

    private val movie = FakeTracksPlayer(
        listOf(
            FakeTracksPlayer.audio("en", MimeTypes.AUDIO_E_AC3, channels = 6),
            FakeTracksPlayer.audio("fr", MimeTypes.AUDIO_AAC, channels = 2),
            FakeTracksPlayer.audio("en", MimeTypes.AUDIO_AAC, channels = 2, label = "Director's commentary", roles = C.ROLE_FLAG_COMMENTARY),
            FakeTracksPlayer.text("en", label = "English", roles = C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND),
            FakeTracksPlayer.text("fr", flags = C.SELECTION_FLAG_FORCED),
            FakeTracksPlayer.text(null),
        ),
        initiallySelected = setOf(0),
    )

    private fun start(player: FakeTracksPlayer = movie, info: TvPlayerInfo = TvPlayerInfo("Northern Lights", "2025", live = false)) {
        var inputModes: InputModeManager? = null
        compose.setContent {
            inputModes = LocalInputModeManager.current
            val scope = rememberCoroutineScope()
            controls = androidx.compose.runtime.remember { TvControls(scope) }
            OndoTvTheme {
                TvPlayerOverlay(player, controls, info, statisticsShown = false, onToggleStatistics = {}, onReportProblem = {})
            }
        }
        // A TV has no touch screen: the remote's keys put it in keyboard mode.
        compose.runOnIdle { inputModes?.requestInputMode(InputMode.Keyboard) }
        compose.runOnIdle { controls.show() }
        compose.waitForIdle()
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun row(tag: String) = compose.onNode(hasTestTag(tag), useUnmergedTree = false)

    @Test
    fun theSubtitlesPanelNamesTheTracksByLanguageAndOffTurnsThemOff() {
        start()
        compose.onNodeWithTag("tvPlayPause").assertIsFocused()
        press(Key.DirectionRight, 2)
        compose.onNodeWithTag("tvSubtitles").assertIsFocused()
        press(Key.DirectionCenter)
        // Nothing plays yet: Off is ticked and has the focus.
        row("trackOff").assertIsFocused().assertIsSelected()
        row("track-0").assertTextContains("English").assertTextContains("For the deaf and hard of hearing")
        row("track-1").assertTextContains("French").assertTextContains("Forced")
        row("track-2").assertTextContains("Track 3")
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        val english = movie.trackParameters
        assertFalse("subtitles on", C.TRACK_TYPE_TEXT in english.disabledTrackTypes)
        assertEquals(listOf("en"), english.overrides.values.filter { it.type == C.TRACK_TYPE_TEXT }.map { it.mediaTrackGroup.getFormat(0).language })
        // The panel closes, the remote is back on Subtitles; open again, the English track is ticked and focused.
        compose.onNodeWithTag("tvTrackPanel").assertDoesNotExist()
        compose.onNodeWithTag("tvSubtitles").assertIsFocused()
        press(Key.DirectionCenter)
        row("track-0").assertIsFocused().assertIsSelected()
        press(Key.DirectionUp)
        row("trackOff").assertIsFocused()
        press(Key.DirectionCenter)
        val off = movie.trackParameters
        assertTrue("subtitles off", C.TRACK_TYPE_TEXT in off.disabledTrackTypes)
        assertTrue(off.overrides.values.none { it.type == C.TRACK_TYPE_TEXT })
    }

    @Test
    fun theAudioPanelShowsTheChannelsAndTheCodecAndChoosesATrack() {
        start()
        press(Key.DirectionRight, 3)
        compose.onNodeWithTag("tvAudio").assertIsFocused()
        press(Key.DirectionCenter)
        row("track-0").assertIsFocused().assertIsSelected().assertTextContains("English").assertTextContains("5.1 · E-AC-3")
        row("track-1").assertTextContains("French").assertTextContains("Stereo · AAC")
        // The provider's label, without saying "Commentary" again.
        row("track-2").assertTextContains("English").assertTextContains("Director's commentary · Stereo · AAC")
        // The remote stays in the panel.
        press(Key.DirectionLeft)
        row("track-0").assertIsFocused()
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        val audio = movie.trackParameters.overrides.values.single { it.type == C.TRACK_TYPE_AUDIO }
        assertEquals("fr", audio.mediaTrackGroup.getFormat(0).language)
        compose.onNodeWithTag("tvAudio").assertIsFocused()
    }

    @Test
    fun theButtonsSkipTenSecondsAndTheBarMovesToWhereTheRemoteStops() {
        start()
        press(Key.DirectionLeft)
        compose.onNodeWithTag("tvSkipBack").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf(590_000L), movie.seeks)
        press(Key.DirectionUp)
        compose.onNodeWithTag("tvSeekBar").assertIsFocused()
        // Three steps of 10 s: the target shows, the player goes there on OK (one seek, not three).
        press(Key.DirectionRight, 3)
        compose.onNodeWithTag("seekTarget").assertTextContains("10:20")
        assertEquals(1, movie.seeks.size)
        press(Key.DirectionCenter)
        assertEquals(listOf(590_000L, 620_000L), movie.seeks)
        compose.onNodeWithTag("seekTarget").assertDoesNotExist()
    }

    @Test
    fun aLiveChannelShowsLiveInsteadOfTheBar() {
        val channel = FakeTracksPlayer(listOf(FakeTracksPlayer.audio("de", MimeTypes.AUDIO_MPEG_L2, channels = 2)), initiallySelected = setOf(0), seekable = false)
        start(channel, TvPlayerInfo("4  Cycling TV", "Tour stage 12", live = true))
        compose.onNodeWithTag("liveBadge").assertExists()
        compose.onNodeWithTag("tvSeekBar").assertDoesNotExist()
        compose.onNodeWithTag("tvSkipBack").assertDoesNotExist()
        compose.onNodeWithTag("tvSpeed").assertDoesNotExist()
        compose.onNodeWithTag("tvPlayPause").assertIsFocused()
        press(Key.DirectionRight)
        compose.onNodeWithTag("tvSubtitles").assertIsFocused()
        press(Key.DirectionCenter)
        compose.onNodeWithTag("tracksHint").assertTextContains("This stream has no subtitles.")
    }
}
