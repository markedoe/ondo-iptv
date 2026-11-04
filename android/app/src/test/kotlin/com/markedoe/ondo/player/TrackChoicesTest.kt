package com.markedoe.ondo.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.markedoe.ondo.HangGuard
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The tracks named for a person in the app's language: "Anglais" rather than "en", then what tells them apart. */
@RunWith(AndroidJUnit4::class)
class TrackChoicesTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    private val french = TrackWords(
        track = { "Piste $it" },
        forced = "Forcés",
        hearingImpaired = "Pour sourds et malentendants",
        commentary = "Commentaire",
        audioDescription = "Audiodescription",
        mono = "Mono",
        stereo = "Stéréo",
        unsupported = "Ne peut pas s’afficher sur cet appareil",
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

    /**
     * A subtitle track the player cannot render (PGS without its decoder, a format Media3 does not know) is listed as
     * unavailable, with why, rather than hidden: the panel said "This stream has no subtitles" for a stream that has
     * some. An audio track the device cannot play stays out of the list.
     */
    @Test
    fun aSubtitleTrackTheDeviceCannotShowIsListedAsUnavailable() {
        val player = FakeTracksPlayer(
            listOf(
                FakeTracksPlayer.text("en", mimeType = MimeTypes.APPLICATION_SUBRIP),
                FakeTracksPlayer.text("de", mimeType = MimeTypes.APPLICATION_PGS),
                FakeTracksPlayer.audio("en", MimeTypes.AUDIO_TRUEHD, channels = 8),
            ),
            initiallySelected = setOf(1),
            unsupported = setOf(1, 2),
        )
        val tracks = (player as Player).currentTracks
        val subtitles = TrackChoices.of(tracks, C.TRACK_TYPE_TEXT, french, Locale.FRENCH)
        assertEquals(listOf("Anglais", "Allemand"), subtitles.map { it.name })
        assertEquals(listOf(true, false), subtitles.map { it.available })
        assertEquals("PGS · Ne peut pas s’afficher sur cet appareil", subtitles[1].detail)
        assertEquals(listOf(false, false), subtitles.map { it.selected })
        assertEquals(emptyList<String>(), TrackChoices.of(tracks, C.TRACK_TYPE_AUDIO, french, Locale.FRENCH).map { it.name })
    }

    /** A track read into cues while extracting keeps the name of the format it came in. */
    @Test
    fun aSubtitleFormatIsNamedFromWhatItCameIn() {
        val cues = androidx.media3.common.Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES).setCodecs(MimeTypes.APPLICATION_VOBSUB).build()
        assertEquals("VobSub", TrackChoices.subtitleFormat(cues))
        assertEquals("ASS", TrackChoices.subtitleFormat(androidx.media3.common.Format.Builder().setSampleMimeType(MimeTypes.TEXT_SSA).build()))
        assertNull(TrackChoices.subtitleFormat(androidx.media3.common.Format.Builder().setSampleMimeType("application/x-unknown").build()))
    }
}
