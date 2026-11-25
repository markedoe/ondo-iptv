package com.markedoe.ondo.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.playback.TitleTracks
import com.markedoe.ondo.core.playback.TrackDefaults
import com.markedoe.ondo.core.playback.TrackPick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The tracks a film opens with (docs/TRACKS.md), as Media3's own track selector picks them with the title's and the
 * profile's languages: before the provider's default track, then the device's; subtitles in their language when there is
 * one, else only the forced ones (foreign dialogue). Then the title's tracks by language and kind once the stream lists
 * them, and what a choice in a panel says for the title.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class TrackPreferencesTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun audio(language: String, default: Boolean = false) =
        Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setChannelCount(2).setSampleRate(48_000).setLanguage(language)
            .setSelectionFlags(if (default) C.SELECTION_FLAG_DEFAULT else 0).build()

    private fun text(language: String, default: Boolean = false, forced: Boolean = false) =
        Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).setLanguage(language)
            .setSelectionFlags((if (default) C.SELECTION_FLAG_DEFAULT else 0) or (if (forced) C.SELECTION_FLAG_FORCED else 0)).build()

    /** A renderer that plays every format of its type. */
    private class Plays(private val type: Int) : RendererCapabilities {
        override fun getName() = "plays-$type"
        override fun getTrackType() = type
        override fun supportsFormat(format: Format): Int =
            RendererCapabilities.create(if (MimeTypes.getTrackType(format.sampleMimeType) == type) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE)
        override fun supportsMixedMimeTypeAdaptation(): Int = RendererCapabilities.ADAPTIVE_NOT_SUPPORTED
    }

    /** The languages Media3 picks for an English (default) + French film with [parameters]: audio, then subtitles. */
    private fun picked(parameters: TrackSelectionParameters, vararg formats: Format): Pair<String?, String?> {
        val selector = DefaultTrackSelector(context)
        selector.init({}, DefaultBandwidthMeter.getSingletonInstance(context))
        selector.setParameters(parameters)
        // As the player does once the parameters reach its playback thread: Media3 selects with the active ones.
        selector.onParametersActivated(selector.parameters)
        val groups = TrackGroupArray(*formats.map { TrackGroup(it) }.toTypedArray())
        val result = selector.selectTracks(arrayOf(Plays(C.TRACK_TYPE_AUDIO), Plays(C.TRACK_TYPE_TEXT)), groups, MediaSource.MediaPeriodId(Any()), Timeline.EMPTY)
        val languages = result.selections.map { it?.getFormat(0)?.language }
        return languages[0] to languages[1]
    }

    private val base = TrackSelectionParameters.Builder().build()
    private val film = arrayOf(audio("en", default = true), audio("fr"), text("en", default = true), text("fr", forced = true))

    @Test
    fun aFrenchTvPlaysTheFrenchSoundRatherThanTheProvidersDefault() {
        val parameters = TrackPreferences.parameters(base, null, null, deviceLanguages = listOf("fr"))
        assertEquals("fr", picked(parameters, *film).first)
    }

    @Test
    fun theTitlesSoundComesFirstThenTheProfiles() {
        val title = TitleTracks("p|MOVIE|m1", audio = TrackPick(language = "en"))
        assertEquals("en", picked(TrackPreferences.parameters(base, title, TrackDefaults(audio = "fr"), listOf("fr")), *film).first)
        assertEquals("en", picked(TrackPreferences.parameters(base, null, TrackDefaults(audio = "en"), listOf("fr")), *film).first)
    }

    @Test
    fun subtitlesOffStillShowTheForcedOnesButNotTheDefaultTrack() {
        val parameters = TrackPreferences.parameters(base, null, null, deviceLanguages = listOf("fr"))
        // French sound: the forced French subtitles (foreign dialogue), not the English track flagged default.
        assertEquals("fr" to "fr", picked(parameters, *film))
        val english = TrackPreferences.parameters(base, null, TrackDefaults(audio = "en"), deviceLanguages = listOf("fr"))
        assertEquals("en" to null, picked(english, *film))
    }

    @Test
    fun subtitlesComeInTheTitlesLanguage() {
        val title = TitleTracks("p|SERIES|s1", audio = TrackPick(language = "en"), subtitles = TrackPick(language = "fr"))
        assertEquals("en" to "fr", picked(TrackPreferences.parameters(base, title, null, listOf("fr")), *film))
    }

    /** Subtitles on for the title, then Off in a panel: no subtitle track, even one in the title's language. */
    @Test
    fun offTurnsOffSubtitlesThatCameInTheTitlesLanguage() {
        val on = TrackPreferences.parameters(base, null, TrackDefaults(audio = "en", subtitles = "fr"), deviceLanguages = listOf("fr"))
        assertEquals("en" to "fr", picked(on, *film))
        val off = TrackChoices.select(on, C.TRACK_TYPE_TEXT, null)
        assertEquals("en" to null, picked(off, *film))
        assertTrue(TrackPreferences.chosen(on, off).subtitlesOff)
    }

    /** The tracks of a stream, the first audio and no subtitles selected, as the player lists them. */
    private fun tracks(vararg formats: Format): Tracks = Tracks(formats.mapIndexed { n, format ->
        val selected = n == 0 && MimeTypes.getTrackType(format.sampleMimeType) == C.TRACK_TYPE_AUDIO
        Tracks.Group(TrackGroup(format), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(selected))
    })

    private fun chosenLanguage(parameters: TrackSelectionParameters, type: Int): String? =
        parameters.overrides.values.firstOrNull { it.type == type }?.let { it.mediaTrackGroup.getFormat(it.trackIndices.first()).language }

    /** French sound and English subtitles chosen on episode 1: episode 2, its tracks in another order, starts with them. */
    @Test
    fun episodeTwoStartsWithTheTracksChosenOnEpisodeOne() {
        val episode1 = tracks(audio("en"), audio("fr"), text("fr"), text("en"))
        val start = TrackPreferences.parameters(base, null, null, listOf("en"))
        val french = start.buildUpon().setOverrideForType(TrackSelectionOverride(episode1.groups[1].mediaTrackGroup, 0)).build()
        val chosenSound = TrackPreferences.chosen(start, french)
        assertEquals("fr", chosenSound.audio?.language)
        assertNull("the subtitles were not chosen", chosenSound.subtitles)
        val english = TrackChoices.select(french, C.TRACK_TYPE_TEXT, TrackChoice("English", null, false, episode1.groups[3], 0))
        val chosenText = TrackPreferences.chosen(french, english)
        assertNull("the sound did not change", chosenText.audio)
        assertEquals("en", chosenText.subtitles?.language)
        val title = TitleTracks("p|SERIES|s1", audio = chosenSound.audio, subtitles = chosenText.subtitles)

        val episode2 = tracks(audio("de"), audio("en"), audio("fr"), text("en"), text("de"))
        val opened = TrackPreferences.parameters(base, title, null, listOf("en"))
        val planned = requireNotNull(TrackPreferences.planned(opened, episode2, title, null))
        assertEquals("fr", chosenLanguage(planned, C.TRACK_TYPE_AUDIO))
        assertEquals("en", chosenLanguage(planned, C.TRACK_TYPE_TEXT))
        assertFalse("what the app sets is no choice of the viewer's", TrackPreferences.chosen(planned, planned).audio != null)
    }

    @Test
    fun aLanguageTheStreamLacksFallsBackToTheProfilesThenToTheStreamsOwn() {
        val title = TitleTracks("p|MOVIE|m1", audio = TrackPick(language = "it"))
        val stream = tracks(audio("en"), audio("de"))
        val planned = TrackPreferences.planned(base, stream, title, TrackDefaults(audio = "de"))
        assertEquals("de", chosenLanguage(requireNotNull(planned), C.TRACK_TYPE_AUDIO))
        assertNull("the stream's own sound plays", TrackPreferences.planned(base, stream, title, TrackDefaults(audio = "ja")))
    }

    @Test
    fun nothingChosenAndNoLanguagesLeaveMedia3sSelection() {
        assertNull(TrackPreferences.planned(base, tracks(audio("en"), audio("fr")), null, null))
        assertTrue(TrackPreferences.chosen(base, base).isEmpty)
    }
}
