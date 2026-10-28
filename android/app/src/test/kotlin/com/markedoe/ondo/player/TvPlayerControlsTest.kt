package com.markedoe.ondo.player

import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.LayoutDirection
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import com.markedoe.ondo.data.VideoScale
import com.markedoe.ondo.ui.tv.OndoTvTheme
import java.util.Date
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

    private lateinit var controls: PlayerControls
    private lateinit var sleep: SleepTimer
    private var scale by mutableStateOf(VideoScale.FIT)
    private var sleepEnds = 0

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

    private var chosen: String? = null
    private var restarted = 0
    private var wentLive = 0

    private var subtitleSize by mutableStateOf(com.markedoe.ondo.data.SubtitleSize.NORMAL)

    private fun start(player: FakeTracksPlayer = movie, info: PlayerInfo = PlayerInfo("Northern Lights", "2025", live = false), rtl: Boolean = false, channels: ChannelList? = null, sizes: Boolean = false) {
        var inputModes: InputModeManager? = null
        compose.setContent {
            inputModes = LocalInputModeManager.current
            val scope = rememberCoroutineScope()
            controls = androidx.compose.runtime.remember { PlayerControls(scope) }
            sleep = androidx.compose.runtime.remember { SleepTimer(scope, now = { NOW }) { sleepEnds++ } }
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                OndoTvTheme {
                    TvPlayerOverlay(
                        player, controls, info, statisticsShown = false, onToggleStatistics = {}, onReportProblem = {},
                        scale = scale, onScale = { scale = it }, sleep = sleep, channels = channels,
                        onRestart = { restarted++ }, onWatchLive = { wentLive++ },
                        subtitles = if (sizes) SubtitleSizing(subtitleSize) { subtitleSize = it } else null,
                    )
                }
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
        // Off as Media3's own controls do it: no subtitle track chosen, only the forced ones may still show.
        assertTrue(off.overrides.values.none { it.type == C.TRACK_TYPE_TEXT })
        assertEquals(TrackPreferences.SUBTITLES_OFF, off.ignoredTextSelectionFlags)
        assertFalse(TrackPreferences.learned(TrackLanguages(subtitles = "en", subtitlesOn = true), off).subtitlesOn)
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

    /** Paused, a time chosen on the bar: OK plays from there, once the player has gone there by itself too. */
    @Test
    fun okOnTheBarPlaysFromTheTimeChosenAndKeepsTheRemoteThere() {
        start()
        press(Key.DirectionCenter)
        assertFalse("paused", movie.playWhenReady)
        press(Key.DirectionUp)
        compose.onNodeWithTag("tvSeekBar").assertIsFocused()
        press(Key.DirectionRight, 2)
        // The remote stops: the player goes to the target by itself, still paused.
        compose.mainClock.advanceTimeBy(PlayerControls.SCRUB_APPLY_MS + 200)
        compose.waitForIdle()
        assertEquals(listOf(620_000L), movie.seeks)
        assertFalse("still paused", movie.playWhenReady)
        press(Key.DirectionCenter)
        assertTrue("OK plays", movie.playWhenReady)
        assertEquals("no other seek", listOf(620_000L), movie.seeks)
        compose.onNodeWithTag("tvSeekBar").assertIsFocused()
        // Paused again, a move not applied yet: OK goes there and plays at once.
        compose.runOnIdle { movie.pause() }
        press(Key.DirectionLeft)
        press(Key.DirectionCenter)
        assertEquals(listOf(620_000L, 610_000L), movie.seeks)
        assertTrue("OK plays", movie.playWhenReady)
        compose.onNodeWithTag("tvSeekBar").assertIsFocused()
        // Playing, OK on the bar does not pause.
        press(Key.DirectionCenter)
        assertTrue("still playing", movie.playWhenReady)
    }

    @Test
    fun aLiveChannelShowsLiveInsteadOfTheBar() {
        val channel = FakeTracksPlayer(listOf(FakeTracksPlayer.audio("de", MimeTypes.AUDIO_MPEG_L2, channels = 2)), initiallySelected = setOf(0), seekable = false)
        start(channel, PlayerInfo("4  Cycling TV", "Tour stage 12", live = true))
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

    /** A row far down a long list is not drawn until the list scrolls to it: the list opens on it. */
    @Test
    fun aLongListOpensOnTheTrackPlaying() {
        val languages = listOf("ar", "bg", "cs", "da", "de", "el", "es", "et", "fi", "fr", "he", "hu", "it", "ja", "ko", "nl", "no", "pl", "pt", "ro", "ru", "sv", "tr", "zh")
        start(FakeTracksPlayer(languages.map { FakeTracksPlayer.text(it) }, initiallySelected = setOf(20)))
        press(Key.DirectionRight, 2)
        press(Key.DirectionCenter)
        row("track-20").assertIsFocused().assertIsSelected().assertTextContains("Russian")
        press(Key.DirectionDown)
        row("track-21").assertIsFocused().assertTextContains("Swedish")
    }

    @Test
    fun aLiveStreamThatCanGoBackKeepsItsBarWithLive() {
        val timeshift = FakeTracksPlayer(listOf(FakeTracksPlayer.audio("de", MimeTypes.AUDIO_AAC, channels = 2)), initiallySelected = setOf(0), seekable = true)
        start(timeshift, PlayerInfo("4  Cycling TV", null, live = true))
        compose.onNodeWithTag("tvSeekBar").assertExists()
        compose.onNodeWithTag("liveBadge").assertExists()
        compose.onNodeWithTag("tvSkipBack").assertExists()
    }

    @Test
    fun theRemotesSubtitlesKeyOpensThePanelOnTheTrackPlaying() {
        start()
        compose.runOnIdle {
            controls.hide()
            controls.open(PlayerPanel.SUBTITLES)
        }
        compose.waitForIdle()
        row("trackOff").assertIsFocused()
        // A media key (Play/Pause) keeps the panel open.
        compose.runOnIdle { controls.show() }
        compose.waitForIdle()
        assertEquals(PlayerPanel.SUBTITLES, controls.panel)
        row("trackOff").assertIsFocused()
        // Back (the activity's) closes the panel: the remote is on Subtitles.
        compose.runOnIdle { assertTrue(controls.back()) }
        compose.waitForIdle()
        compose.onNodeWithTag("tvSubtitles").assertIsFocused()
    }

    /** No audio track yet (a channel just opened) or none at all: the remote is still in the panel, on its line. */
    @Test
    fun anAudioPanelWithoutTracksKeepsTheRemoteInside() {
        val silent = FakeTracksPlayer(listOf(FakeTracksPlayer.text("en")), initiallySelected = emptySet())
        start(silent)
        press(Key.DirectionRight, 3)
        compose.onNodeWithTag("tvAudio").assertIsFocused()
        press(Key.DirectionCenter)
        compose.onNodeWithTag("tracksHint").assertIsFocused().assertTextContains("This stream has no sound.")
        press(Key.DirectionLeft)
        compose.onNodeWithTag("tracksHint").assertIsFocused()
        // The remote's audio key with the controls hidden: the same.
        compose.runOnIdle {
            controls.hide()
            controls.open(PlayerPanel.AUDIO)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("tracksHint").assertIsFocused()
    }

    /** The video's time runs left to right in every language: back is on the left in Arabic too. */
    @Test
    fun aSkipBackShowsItsIconOnTheLeftInArabic() {
        start(rtl = true)
        compose.runOnIdle {
            controls.hide()
            controls.skip(movie, -PlayerControls.SKIP_MS)
        }
        compose.waitForIdle()
        val badge = compose.onNodeWithTag("tvSkipBadge").fetchSemanticsNode().boundsInRoot
        val width = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
        assertTrue("the back icon is on the left (${badge.left} of $width)", badge.right < width / 2)
    }

    /** A film the player cannot seek in (a stream without an index): a skip would start it over, so there is none. */
    @Test
    fun aFilmThatCannotSeekHasNoSkipButtons() {
        val stream = FakeTracksPlayer(listOf(FakeTracksPlayer.audio("en", MimeTypes.AUDIO_AAC, channels = 2)), initiallySelected = setOf(0), seekable = false)
        start(stream)
        compose.onNodeWithTag("tvSkipBack").assertDoesNotExist()
        compose.onNodeWithTag("tvPlayPause").assertIsFocused()
        compose.runOnIdle { controls.skip(stream, PlayerControls.SKIP_MS) }
        compose.waitForIdle()
        assertEquals(emptyList<Long>(), stream.seeks)
    }

    /** Right held down with the controls hidden: the target moves with each repeat, the player goes there once. */
    @Test
    fun skipsPressedInARowMakeOneSeek() {
        start()
        compose.runOnIdle {
            controls.hide()
            repeat(6) { controls.skip(movie, PlayerControls.SKIP_MS) }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        assertEquals("the first at once, then one for the rest", listOf(610_000L, 660_000L), movie.seeks)
    }

    /** More, then Aspect ratio: the three ways the picture fills the screen, the one in use ticked; Back returns to More. */
    @Test
    fun moreSetsTheAspectRatio() {
        start()
        press(Key.DirectionRight, 5)
        compose.onNodeWithTag("tvMore").assertIsFocused()
        press(Key.DirectionCenter)
        row("tvAspect").assertIsFocused().assertTextContains("Aspect ratio").assertTextContains("Fit")
        press(Key.DirectionCenter)
        row("scale-FIT").assertIsFocused().assertIsSelected()
        row("scale-FILL").assertTextContains("Fill (crop)")
        row("scale-STRETCH").assertTextContains("Stretch")
        // Back: More again, on the row the remote came from.
        compose.runOnIdle { assertTrue(controls.back()) }
        compose.waitForIdle()
        assertEquals(PlayerPanel.MORE, controls.panel)
        row("tvAspect").assertIsFocused()
        press(Key.DirectionCenter)
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        assertEquals(VideoScale.FILL, scale)
        // Chosen: the panels close, the remote is back on More, which now says Fill.
        compose.onNodeWithTag("tvTrackPanel").assertDoesNotExist()
        compose.onNodeWithTag("tvMore").assertIsFocused()
        press(Key.DirectionCenter)
        row("tvAspect").assertTextContains("Fill (crop)")
        press(Key.DirectionCenter)
        row("scale-FILL").assertIsFocused().assertIsSelected()
    }

    /** More, then Sleep timer: Off or a number of minutes; the time it ends shows on the row and by the clock. */
    @Test
    fun moreSetsTheSleepTimer() {
        start()
        press(Key.DirectionRight, 5)
        press(Key.DirectionCenter)
        press(Key.DirectionDown)
        row("tvSleep").assertIsFocused().assertTextContains("Sleep timer").assertTextContains("Off")
        press(Key.DirectionCenter)
        row("sleep-off").assertIsFocused().assertIsSelected()
        row("sleep-15").assertTextContains("15 min")
        row("sleep-120").assertExists()
        // Back: More again, on Sleep timer.
        compose.runOnIdle { assertTrue(controls.back()) }
        compose.waitForIdle()
        row("tvSleep").assertIsFocused()
        press(Key.DirectionCenter)
        row("sleep-off").assertIsFocused()
        press(Key.DirectionDown, 2)
        row("sleep-30").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(30, sleep.minutes)
        compose.onNodeWithTag("tvMore").assertIsFocused()
        val end = DateFormat.getTimeFormat(compose.activity).format(Date(NOW + 30 * 60_000L))
        compose.onNodeWithTag("sleepEndsAt").assertTextContains(end)
        press(Key.DirectionCenter)
        row("tvSleep").assertTextContains("Ends at $end")
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        row("sleep-30").assertIsFocused().assertIsSelected()
        press(Key.DirectionUp, 2)
        press(Key.DirectionCenter)
        assertEquals(null, sleep.minutes)
        compose.onNodeWithTag("sleepEndsAt").assertDoesNotExist()
        assertEquals(0, sleepEnds)
    }

    /** A live channel: Channels next to Play/Pause lists the others with what is on now; OK on one zaps to it. */
    @Test
    fun theChannelsPanelIsAMiniGuide() {
        val channel = FakeTracksPlayer(listOf(FakeTracksPlayer.audio("en", MimeTypes.AUDIO_AAC, channels = 2)), initiallySelected = setOf(0), seekable = false)
        val list = ChannelList(
            load = { listOf(ChannelChoice("c1", 1, "World Report", "Evening News", playing = true), ChannelChoice("c2", 2, "Cycling TV", "Mountain Stage", playing = false)) },
            onChoose = { chosen = it },
        )
        start(channel, PlayerInfo("World Report", "Evening News", live = true), channels = list)
        compose.onNodeWithTag("tvPlayPause").assertIsFocused()
        press(Key.DirectionRight)
        compose.onNodeWithTag("tvChannels").assertIsFocused()
        press(Key.DirectionCenter)
        row("channel-c1").assertIsFocused().assertIsSelected().assertTextContains("Evening News")
        press(Key.DirectionDown)
        row("channel-c2").assertIsFocused().assertTextContains("Mountain Stage")
        press(Key.DirectionCenter)
        assertEquals("c2", chosen)
        compose.onNodeWithTag("tvTrackPanel").assertDoesNotExist()
    }

    /** A live channel with the provider's archive: Restart, next to Play/Pause, plays its programme from the start. */
    @Test
    fun aLiveChannelWithAnArchiveRestartsItsProgramme() {
        val channel = FakeTracksPlayer(listOf(FakeTracksPlayer.audio("en", MimeTypes.AUDIO_AAC, channels = 2)), initiallySelected = setOf(0), seekable = false)
        start(channel, PlayerInfo("World Report", "Evening News", live = true, canRestart = true))
        compose.onNodeWithTag("tvPlayPause").assertIsFocused()
        press(Key.DirectionRight)
        compose.onNodeWithTag("tvRestart").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(1, restarted)
        compose.onNodeWithTag("tvWatchLive").assertDoesNotExist()
    }

    /** A replay: its bar says REPLAY, and Back to live comes after the skips. */
    @Test
    fun aReplaySaysSoAndGoesBackToLive() {
        start(info = PlayerInfo("1  World Report", "Morning Show", live = false, replay = true))
        compose.onNodeWithTag("replayBadge").assertExists()
        compose.onNodeWithTag("liveBadge").assertDoesNotExist()
        compose.onNodeWithTag("tvRestart").assertDoesNotExist()
        compose.onNodeWithTag("tvPlayPause").assertIsFocused()
        // After the skips: a replay seeks as a film.
        press(Key.DirectionRight)
        compose.onNodeWithTag("tvSkipForward").assertIsFocused()
        press(Key.DirectionRight)
        compose.onNodeWithTag("tvWatchLive").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(1, wentLive)
    }

    /** More, Subtitle size: the size in use ticked, another one chosen; Back from the sizes is back on their row. */
    @Test
    fun moreChangesTheSubtitleSize() {
        start(sizes = true)
        press(Key.DirectionRight, 5)
        compose.onNodeWithTag("tvMore").assertIsFocused()
        press(Key.DirectionCenter)
        press(Key.DirectionDown)
        row("tvSubtitleSize").assertIsFocused().assertTextContains("Subtitle size").assertTextContains("Normal")
        press(Key.DirectionCenter)
        row("size-NORMAL").assertIsFocused().assertIsSelected()
        press(Key.DirectionDown)
        row("size-LARGE").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(com.markedoe.ondo.data.SubtitleSize.LARGE, subtitleSize)
        compose.onNodeWithTag("tvTrackPanel").assertDoesNotExist()
        compose.onNodeWithTag("tvMore").assertIsFocused()
        press(Key.DirectionCenter)
        press(Key.DirectionDown)
        row("tvSubtitleSize").assertTextContains("Large")
        press(Key.DirectionCenter)
        // Back: More again, on Subtitle size.
        compose.runOnIdle { assertTrue(controls.back()) }
        compose.waitForIdle()
        row("tvSubtitleSize").assertIsFocused()
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
