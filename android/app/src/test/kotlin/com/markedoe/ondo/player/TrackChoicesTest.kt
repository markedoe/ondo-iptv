package com.markedoe.ondo.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** The tracks named for a person in the app's language: "Anglais" rather than "en", then what tells them apart. */
@RunWith(AndroidJUnit4::class)
class TrackChoicesTest {
    private val french = TrackWords(
        track = { "Piste $it" },
        forced = "Forcés",
        hearingImpaired = "Pour sourds et malentendants",
        commentary = "Commentaire",
        audioDescription = "Audiodescription",
        mono = "Mono",
        stereo = "Stéréo",
    )

    @Test
    fun aLanguageIsNamedInTheAppsLanguage() {
        assertEquals("Anglais", TrackChoices.languageName("en", Locale.FRENCH))
        assertEquals("Français", TrackChoices.languageName("fr", Locale.FRENCH))
        assertEquals("Portuguese (Brazil)", TrackChoices.languageName("pt-BR", Locale.ENGLISH))
        assertEquals("German", TrackChoices.languageName("de", Locale.ENGLISH))
        // No language, or one Java does not know: no name, the label or "Track 2" instead.
        assertNull(TrackChoices.languageName(null, Locale.ENGLISH))
        assertNull(TrackChoices.languageName(C.LANGUAGE_UNDETERMINED, Locale.ENGLISH))
        assertNull(TrackChoices.languageName("qaa", Locale.ENGLISH))
    }

    @Test
    fun tracksWithoutALanguageTakeTheirLabelOrTheirNumber() {
        val player = FakeTracksPlayer(
            listOf(
                // Media3 turns the stream's three-letter codes into two.
                FakeTracksPlayer.audio("eng", MimeTypes.AUDIO_AC3, channels = 6),
                FakeTracksPlayer.audio(null, MimeTypes.AUDIO_AAC, channels = 2, label = "Original"),
                FakeTracksPlayer.audio("und", MimeTypes.AUDIO_OPUS, channels = 1),
                FakeTracksPlayer.audio("fr", MimeTypes.AUDIO_AAC, channels = 2, label = "Audiodescription", roles = C.ROLE_FLAG_DESCRIBES_VIDEO),
            ),
            initiallySelected = setOf(1),
        )
        val choices = TrackChoices.of((player as Player).currentTracks, C.TRACK_TYPE_AUDIO, french, Locale.FRENCH)
        assertEquals(listOf("Anglais", "Original", "Piste 3", "Français"), choices.map { it.name })
        assertEquals(listOf("5.1 · AC-3", "Stéréo · AAC", "Mono · Opus", "Audiodescription · Stéréo · AAC"), choices.map { it.detail })
        assertEquals(listOf(false, true, false, false), choices.map { it.selected })
    }

    /** A Dolby or DTS track goes as it is when the TV says it takes that format; else it is decoded; AAC is not told. */
    @Test
    fun aSurroundTrackGoesAsItIsOnlyWhenTheTvTakesItsFormat() {
        val attributes = androidx.media3.common.AudioAttributes.DEFAULT
        val eac3 = androidx.media3.common.Format.Builder().setSampleMimeType(MimeTypes.AUDIO_E_AC3).setChannelCount(6).setSampleRate(48_000).build()
        val dts = androidx.media3.common.Format.Builder().setSampleMimeType(MimeTypes.AUDIO_DTS).setChannelCount(6).setSampleRate(48_000).build()
        val aac = androidx.media3.common.Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setChannelCount(2).setSampleRate(48_000).build()
        val dolbyAmplifier = androidx.media3.exoplayer.audio.AudioCapabilities(intArrayOf(C.ENCODING_PCM_16BIT, C.ENCODING_AC3, C.ENCODING_E_AC3), 8)
        val speakersOnly = androidx.media3.exoplayer.audio.AudioCapabilities(intArrayOf(C.ENCODING_PCM_16BIT), 2)
        assertEquals(AudioRoute.AS_IS, TrackChoices.route(eac3, dolbyAmplifier, attributes))
        assertEquals(AudioRoute.DECODED, TrackChoices.route(dts, dolbyAmplifier, attributes))
        assertEquals(AudioRoute.DECODED, TrackChoices.route(eac3, speakersOnly, attributes))
        assertNull(TrackChoices.route(aac, dolbyAmplifier, attributes))
    }
}
