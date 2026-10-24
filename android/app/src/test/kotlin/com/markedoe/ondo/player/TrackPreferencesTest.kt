package com.markedoe.ondo.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The tracks a film opens with, as Media3's own track selector picks them with the viewer's languages: their audio
 * language before the provider's default track, then the device's; subtitles in their language once they chose some,
 * else only the forced ones (foreign dialogue). A choice in a panel teaches the next streams.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class TrackPreferencesTest {
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
        val parameters = TrackPreferences.parameters(base, TrackLanguages(), deviceLanguages = listOf("fr"))
        assertEquals("fr", picked(parameters, *film).first)
    }

    @Test
    fun theViewersOwnAudioLanguageComesFirst() {
        val parameters = TrackPreferences.parameters(base, TrackLanguages(audio = "en"), deviceLanguages = listOf("fr"))
        assertEquals("en", picked(parameters, *film).first)
    }

    @Test
    fun subtitlesOffStillShowTheForcedOnesButNotTheDefaultTrack() {
        val parameters = TrackPreferences.parameters(base, TrackLanguages(subtitlesOn = false), deviceLanguages = listOf("fr"))
        // French sound: the forced French subtitles (foreign dialogue), not the English track flagged default.
        assertEquals("fr" to "fr", picked(parameters, *film))
        val english = TrackPreferences.parameters(base, TrackLanguages(audio = "en", subtitlesOn = false), deviceLanguages = listOf("fr"))
        assertEquals("en" to null, picked(english, *film))
    }

    @Test
    fun subtitlesOnComeInTheViewersLanguage() {
        val parameters = TrackPreferences.parameters(base, TrackLanguages(audio = "en", subtitles = "fr", subtitlesOn = true), deviceLanguages = listOf("fr"))
        assertEquals("en" to "fr", picked(parameters, *film))
    }

    /** Subtitles learned on, then Off in a panel: no subtitle track, even one in the language the viewer had. */
    @Test
    fun offTurnsOffSubtitlesThatCameInTheViewersLanguage() {
        val on = TrackPreferences.parameters(base, TrackLanguages(audio = "en", subtitles = "fr", subtitlesOn = true), deviceLanguages = listOf("fr"))
        assertEquals("en" to "fr", picked(on, *film))
        val off = TrackChoices.select(on, C.TRACK_TYPE_TEXT, null)
        assertEquals("en" to null, picked(off, *film))
    }

    @Test
    fun aChoiceInAPanelIsLearnedForTheNextStreams() {
        val french = TrackGroup(audio("fr"))
        val chosen = base.buildUpon().setOverrideForType(TrackSelectionOverride(french, 0)).build()
        assertEquals(TrackLanguages(audio = "fr"), TrackPreferences.learned(TrackLanguages(), chosen))
        val subtitles = TrackGroup(text("es"))
        val on = base.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(subtitles, 0)).build()
        assertEquals(TrackLanguages(audio = "fr", subtitles = "es", subtitlesOn = true), TrackPreferences.learned(TrackLanguages(audio = "fr"), on))
        // Off, as the panels and Media3's own phone controls turn subtitles off: the language stays for next time.
        val off = TrackChoices.select(on, C.TRACK_TYPE_TEXT, null)
        val learned = TrackPreferences.learned(TrackLanguages(audio = "fr", subtitles = "es", subtitlesOn = true), off)
        assertFalse(learned.subtitlesOn)
        assertEquals("es", learned.subtitles)
        assertTrue("Off leaves the forced subtitles", C.TRACK_TYPE_TEXT !in off.disabledTrackTypes)
        // Nothing chosen: nothing learned.
        assertEquals(TrackLanguages(audio = "de"), TrackPreferences.learned(TrackLanguages(audio = "de"), base))
        assertNull(TrackPreferences.learned(TrackLanguages(), base).audio)
    }
}
