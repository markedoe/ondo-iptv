package com.markedoe.ondo.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.subtitles.OnlineSubtitle
import com.markedoe.ondo.core.subtitles.SubtitleSearch
import com.markedoe.ondo.core.subtitles.SubtitleService
import com.markedoe.ondo.data.ChosenSubtitle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The online search's state as titles change and the viewer chooses. */
@RunWith(AndroidJUnit4::class)
class OnlineSubtitlesTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    private val subtitle = OnlineSubtitle(SubtitleService.SUBDL, "/subtitle/1-2.zip", "en", "Copper.Valley.S03E03")

    /** A download that ends after another title started is not loaded (it would show over the wrong title). */
    @Test
    fun aDownloadCancelledByAnotherTitleIsNotLoaded() {
        val scope = TestScope(StandardTestDispatcher())
        val gate = CompletableDeferred<Unit>()
        val source = object : OnlineSubtitleSource {
            override val configured = true

            override suspend fun search(hint: SubtitleSearchHint, languages: List<String>) = SubtitleSearch.Outcome(listOf(subtitle), emptyMap())

            override suspend fun download(subtitle: OnlineSubtitle, hint: SubtitleSearchHint, languages: List<String>): ChosenSubtitle {
                gate.await()
                return ChosenSubtitle("/data/subtitles/a.srt", "en", "srt", "SubDL")
            }
        }
        val loaded = mutableListOf<ChosenSubtitle>()
        val online = OnlineSubtitles(scope, source, SubtitleSearchHint("Copper Valley", 2018, 3, 3), listOf("en")) { loaded += it }
        online.choose(subtitle)
        scope.advanceUntilIdle()
        assertEquals(OnlineSubtitles.Phase.DOWNLOADING, online.phase)
        // The next episode starts.
        online.cancel()
        gate.complete(Unit)
        scope.advanceUntilIdle()
        assertTrue(loaded.isEmpty())
        assertEquals(OnlineSubtitles.Phase.IDLE, online.phase)
        // The same title: loaded.
        val again = OnlineSubtitles(scope, source, SubtitleSearchHint("Copper Valley", 2018, 3, 3), listOf("en")) { loaded += it }
        again.choose(subtitle)
        scope.advanceUntilIdle()
        assertEquals(1, loaded.size)
        assertTrue(SideLoadedSubtitle.sameTitle("p|series|7|9001", "p|series|7|9001"))
        assertFalse(SideLoadedSubtitle.sameTitle("p|series|7|9001", "p|series|7|9002"))
    }

    /**
     * After the video is opened again (a retry), the downloaded subtitle is chosen again only if the viewer kept it:
     * Off or another track chosen since stays.
     */
    @Test
    fun theViewersLaterChoiceStays() {
        val online = TrackGroup("1:" + SideLoadedSubtitle.ID, Format.Builder().setId("1:" + SideLoadedSubtitle.ID).setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES).build())
        val embedded = TrackGroup("0:3", Format.Builder().setId("3").setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES).build())
        val base = TrackSelectionParameters.DEFAULT
        assertTrue(SideLoadedSubtitle.isChosen(base.buildUpon().setOverrideForType(TrackSelectionOverride(online, 0)).build()))
        assertFalse(SideLoadedSubtitle.isChosen(base.buildUpon().setOverrideForType(TrackSelectionOverride(embedded, 0)).build()))
        assertFalse(SideLoadedSubtitle.isChosen(TrackChoices.select(base, C.TRACK_TYPE_TEXT, null)))
    }
}
