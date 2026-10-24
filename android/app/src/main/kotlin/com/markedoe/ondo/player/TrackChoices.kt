package com.markedoe.ondo.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import java.util.Locale

/** A track of the stream as the Subtitles and Audio panels list it: its language, then what tells it apart. */
data class TrackChoice(
    /** "English", in the app's language; the track's own label, else "Track 2", when it names no language. */
    val name: String,
    /** "5.1 · E-AC-3", "Forced", the provider's label…; null when there is nothing more to say. */
    val detail: String?,
    val selected: Boolean,
    val group: Tracks.Group,
    val index: Int,
)

/** The words of the track rows, in the app's language. */
data class TrackWords(
    val track: (Int) -> String,
    val forced: String,
    val hearingImpaired: String,
    val commentary: String,
    val audioDescription: String,
    val mono: String,
    val stereo: String,
)

/**
 * The tracks the player can play of one type (audio or text), named for a person: the language's name in the app's
 * language rather than its code, then the channels, the codec and the roles. Choosing one is a track selection
 * override; subtitles off leaves only the forced ones.
 */
@OptIn(UnstableApi::class)
object TrackChoices {
    fun of(tracks: Tracks, type: Int, words: TrackWords, locale: Locale): List<TrackChoice> {
        val choices = ArrayList<TrackChoice>()
        for (group in tracks.groups) {
            if (group.type != type) continue
            for (index in 0 until group.length) {
                if (!group.isTrackSupported(index)) continue
                val format = group.getTrackFormat(index)
                val language = languageName(format.language, locale)
                val name = language ?: format.label?.takeIf { it.isNotBlank() } ?: words.track(choices.size + 1)
                // The provider's own name, when the language does not say it already ("Director's commentary").
                val label = format.label?.takeIf { language != null && it.isNotBlank() && !namesLanguage(it, format.language, locale) }
                // What the label says already is not said again ("English Stereo", "Commentary").
                val details = listOfNotNull(label) + buildList {
                    if (type == C.TRACK_TYPE_AUDIO) {
                        channels(format.channelCount, words)?.let(::add)
                        codec(format.sampleMimeType)?.let(::add)
                    }
                    if (format.selectionFlags and C.SELECTION_FLAG_FORCED != 0) add(words.forced)
                    if (format.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND != 0) add(words.hearingImpaired)
                    if (format.roleFlags and C.ROLE_FLAG_COMMENTARY != 0) add(words.commentary)
                    if (format.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO != 0) add(words.audioDescription)
                }.filterNot { label?.contains(it, ignoreCase = true) == true }
                choices += TrackChoice(name, details.joinToString(" · ").ifEmpty { null }, group.isTrackSelected(index), group, index)
            }
        }
        return choices
    }

    /** Whether no track of [type] plays: subtitles off. */
    fun noneSelected(tracks: Tracks, type: Int): Boolean = tracks.groups.none { it.type == type && it.isSelected }

    /**
     * The selection with [choice] for its type; subtitles off when it is null, as Media3's own controls turn them off:
     * the forced ones (foreign dialogue) still show.
     */
    fun select(parameters: TrackSelectionParameters, type: Int, choice: TrackChoice?): TrackSelectionParameters {
        val builder = parameters.buildUpon().setTrackTypeDisabled(type, false)
        if (choice == null) {
            builder.clearOverridesOfType(type)
            if (type == C.TRACK_TYPE_TEXT) builder.setPreferredTextLanguages().setIgnoredTextSelectionFlags(TrackPreferences.SUBTITLES_OFF)
        } else {
            builder.setOverrideForType(TrackSelectionOverride(choice.group.mediaTrackGroup, choice.index))
            if (type == C.TRACK_TYPE_TEXT) builder.setIgnoredTextSelectionFlags(0)
        }
        return builder.build()
    }

    /** "English" (or "Portuguese (Brazil)") in [locale]'s words; null for no language or one Java does not know. */
    fun languageName(language: String?, locale: Locale): String? {
        if (language.isNullOrBlank() || language == C.LANGUAGE_UNDETERMINED) return null
        val tag = Locale.forLanguageTag(language)
        if (tag.language.isEmpty()) return null
        val name = if (tag.country.isNotEmpty() || tag.script.isNotEmpty()) tag.getDisplayName(locale) else tag.getDisplayLanguage(locale)
        // An unknown code comes back as it is.
        if (name.isBlank() || name.equals(tag.language, ignoreCase = true) || name.equals(language, ignoreCase = true)) return null
        return name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }

    /** A label that is only the language's name, in the app's language, in English or in the language itself. */
    private fun namesLanguage(label: String, language: String?, locale: Locale): Boolean {
        val tag = language?.let(Locale::forLanguageTag) ?: return false
        return listOf(locale, Locale.ENGLISH, tag).any { label.trim().equals(tag.getDisplayLanguage(it), ignoreCase = true) || label.trim().equals(tag.getDisplayName(it), ignoreCase = true) }
    }

    private fun channels(count: Int, words: TrackWords): String? = when (count) {
        1 -> words.mono
        2 -> words.stereo
        6 -> "5.1"
        8 -> "7.1"
        else -> null
    }

    /** The codec's usual name: "AC-3", "E-AC-3", "AAC"… */
    fun codec(mimeType: String?): String? = when (mimeType) {
        MimeTypes.AUDIO_AC3 -> "AC-3"
        MimeTypes.AUDIO_E_AC3 -> "E-AC-3"
        MimeTypes.AUDIO_E_AC3_JOC -> "E-AC-3 Atmos"
        MimeTypes.AUDIO_AC4 -> "AC-4"
        MimeTypes.AUDIO_TRUEHD -> "TrueHD"
        MimeTypes.AUDIO_DTS -> "DTS"
        MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS -> "DTS-HD"
        MimeTypes.AUDIO_DTS_X -> "DTS:X"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_MPEG -> "MP3"
        MimeTypes.AUDIO_MPEG_L2 -> "MP2"
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_VORBIS -> "Vorbis"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        else -> null
    }
}
