package com.markedoe.ondo.player

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.media3.common.MimeTypes
import com.github.takahirom.roborazzi.captureRoboImage
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.subtitles.OnlineSubtitle
import com.markedoe.ondo.core.subtitles.SubtitleSearch
import com.markedoe.ondo.core.subtitles.SubtitleSearchException
import com.markedoe.ondo.core.subtitles.SubtitleService
import com.markedoe.ondo.data.ChosenSubtitle
import com.markedoe.ondo.data.VideoScale
import com.markedoe.ondo.screenshots.BASELINES
import com.markedoe.ondo.screenshots.BaselineOptions
import com.markedoe.ondo.ui.tv.OndoTvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The TV player's Subtitles panel → Search online…, with the remote's keys: the services' results for the episode
 * playing, the viewer's language first; OK on one downloads it and the player loads it; the language searched changes
 * from its row. With a reference screenshot of the results.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w960dp-h540dp-land-television-xhdpi")
class OnlineSubtitlesPanelTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val results = listOf(
        OnlineSubtitle(SubtitleService.SUBDL, "/subtitle/1-2.zip", "en", "Copper.Valley.S03E03.1080p.WEB.H264", hearingImpaired = false),
        OnlineSubtitle(SubtitleService.SUBSOURCE, "8001", "en", "Copper.Valley.S03E03.720p.WEB", downloads = 900, hearingImpaired = true),
        OnlineSubtitle(SubtitleService.SUBDL, "/subtitle/1-3.zip", "fr", "Copper.Valley.S03E03.MULTi.1080p", hearingImpaired = false),
    )

    /** The services as the fake answers them: what was searched, and the subtitle downloaded. */
    private inner class Source(override val configured: Boolean = true) : OnlineSubtitleSource {
        val searched = mutableListOf<List<String>>()
        var downloaded: OnlineSubtitle? = null

        override suspend fun search(hint: SubtitleSearchHint, languages: List<String>): SubtitleSearch.Outcome {
            searched += languages
            return SubtitleSearch.Outcome(results, mapOf(SubtitleService.OPEN_SUBTITLES to SubtitleSearchException.QuotaReached(SubtitleService.OPEN_SUBTITLES)))
        }

        override suspend fun download(subtitle: OnlineSubtitle, hint: SubtitleSearchHint, languages: List<String>): ChosenSubtitle {
            downloaded = subtitle
            return ChosenSubtitle("/data/subtitles/a.srt", subtitle.language, "srt", subtitle.service.displayName)
        }
    }

    private lateinit var controls: PlayerControls
    private var loaded: ChosenSubtitle? = null

    private val episode = FakeTracksPlayer(
        listOf(
            FakeTracksPlayer.audio("en", MimeTypes.AUDIO_E_AC3, channels = 6),
            FakeTracksPlayer.text("de", mimeType = MimeTypes.APPLICATION_PGS),
        ),
        initiallySelected = setOf(0),
        unsupported = setOf(1),
    )

    private fun start(source: Source) {
        var inputModes: InputModeManager? = null
        compose.setContent {
            inputModes = LocalInputModeManager.current
            val scope = rememberCoroutineScope()
            controls = remember { PlayerControls(scope) }
            val sleep = remember { SleepTimer(scope, now = { 0L }) {} }
            var scale by remember { mutableStateOf(VideoScale.FIT) }
            val online = remember { OnlineSubtitles(scope, source, SubtitleSearchHint("Copper Valley", 2018, 3, 3), listOf("en", "fr")) { loaded = it } }
            OndoTvTheme {
                TvPlayerOverlay(
                    episode, controls, PlayerInfo("Copper Valley", "S03E03 · Lights Out", live = false), statisticsShown = false, onToggleStatistics = {}, onReportProblem = {},
                    scale = scale, onScale = { scale = it }, sleep = sleep, online = online,
                )
            }
        }
        compose.runOnIdle { inputModes?.requestInputMode(InputMode.Keyboard) }
        compose.runOnIdle { controls.show() }
        compose.waitForIdle()
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /** Subtitles, then Down past Off and the stream's own track to Search online…, and OK. */
    private fun openSearch() {
        press(Key.DirectionRight, 2)
        compose.onNodeWithTag("tvSubtitles").assertIsFocused()
        press(Key.DirectionCenter)
        compose.onNodeWithTag("trackOff").assertIsFocused()
        press(Key.DirectionDown, 2)
        compose.onNodeWithTag("searchOnline").assertIsFocused().assertTextContains("Copper Valley · S03E03")
        press(Key.DirectionCenter)
    }

    @Test
    fun theRemoteSearchesOnlineAndLoadsTheSubtitleChosen() {
        val source = Source()
        start(source)
        openSearch()
        assertEquals(listOf(listOf("en", "fr")), source.searched)
        compose.onNodeWithTag("onlineLanguages").assertIsFocused().assertTextContains("English, French")
        compose.onNodeWithTag("onlineResult-0").assertTextContains("English").assertTextContains("Copper.Valley.S03E03.1080p.WEB.H264 · SubDL")
        compose.onNodeWithTag("onlineResult-1").assertTextContains("Copper.Valley.S03E03.720p.WEB · SubSource · For the deaf and hard of hearing")
        compose.onNodeWithTag("onlineFailure-OPEN_SUBTITLES").assertTextContains("OpenSubtitles: limit reached for now. Signing in, or a paid plan with the service, raises it.")
        compose.onRoot().captureRoboImage("$BASELINES/tv/en-US/player-online-subtitles.png", BaselineOptions)
        // Down to the SubSource subtitle, OK: downloaded, loaded, the panel closed by the player.
        press(Key.DirectionDown, 2)
        compose.onNodeWithTag("onlineResult-1").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(results[1], source.downloaded)
        assertEquals("SubSource", loaded?.label)
        // Back (the activity's) returns to the Subtitles panel, the remote in it.
        compose.runOnIdle { controls.back() }
        compose.waitForIdle()
        assertEquals(PlayerPanel.SUBTITLES, controls.panel)
        compose.onNodeWithTag("trackOff").assertExists()
    }

    @Test
    fun theLanguageSearchedChangesFromItsRow() {
        val source = Source()
        start(source)
        openSearch()
        press(Key.DirectionCenter)
        // The list opens on the language searched; the next one down is chosen.
        compose.onNodeWithTag("onlineLanguage-en").assertIsFocused()
        press(Key.DirectionDown)
        compose.onNodeWithTag("onlineLanguage-fi").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf("fi", "en"), source.searched.last())
        compose.onNodeWithTag("onlineLanguages").assertIsFocused().assertTextContains("Finnish, English")
    }

    @Test
    fun withoutAServiceThePanelSaysWhereToAddAKey() {
        start(Source(configured = false))
        openSearch()
        compose.onNodeWithTag("onlineHint").assertIsFocused().assertTextContains("Add your OpenSubtitles, SubDL or SubSource API key in Settings → Subtitle services, or turn on Addic7ed there.")
    }
}
