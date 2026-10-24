package com.markedoe.ondo.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi

/**
 * The viewer's languages, learned from their choices in the player (as the Apple apps learn them): the sound's
 * language, the subtitles' language, and whether they want subtitles. Null for a language never chosen.
 */
data class TrackLanguages(
    val audio: String? = null,
    val subtitles: String? = null,
    val subtitlesOn: Boolean = false,
)

/** The tracks a stream opens with, from the viewer's languages; and what a choice in the player teaches. */
@OptIn(UnstableApi::class)
object TrackPreferences {
    /**
     * [base] with the viewer's sound language first, then the device's ([deviceLanguages]): before the track the
     * provider flags as default, which is often English. Subtitles in the viewer's language once they want subtitles;
     * else none but the forced ones (foreign dialogue), the track flagged default included.
     */
    fun parameters(base: TrackSelectionParameters, languages: TrackLanguages, deviceLanguages: List<String>): TrackSelectionParameters {
        val builder = base.buildUpon()
            .setPreferredAudioLanguages(*(listOfNotNull(languages.audio) + deviceLanguages).distinct().toTypedArray())
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        val subtitles = languages.subtitles
        if (languages.subtitlesOn && subtitles != null) {
            builder.setPreferredTextLanguages(subtitles).setIgnoredTextSelectionFlags(0)
        } else {
            builder.setPreferredTextLanguages().setIgnoredTextSelectionFlags(SUBTITLES_OFF)
        }
        return builder.build()
    }

    /**
     * What [parameters] say of the viewer's choice, set by the panels or Media3's phone controls: an audio track
     * chosen, a subtitle track chosen, or subtitles turned off. What they do not say stays as [languages] had it.
     */
    fun learned(languages: TrackLanguages, parameters: TrackSelectionParameters): TrackLanguages {
        val overrides = parameters.overrides.values
        val audio = overrides.firstOrNull { it.type == C.TRACK_TYPE_AUDIO }?.let { it.mediaTrackGroup.getFormat(it.trackIndices.firstOrNull() ?: 0).language }
        val text = overrides.firstOrNull { it.type == C.TRACK_TYPE_TEXT }?.let { it.mediaTrackGroup.getFormat(it.trackIndices.firstOrNull() ?: 0).language }
        val off = text == null && (C.TRACK_TYPE_TEXT in parameters.disabledTrackTypes ||
            (parameters.ignoredTextSelectionFlags and C.SELECTION_FLAG_DEFAULT != 0 && parameters.preferredTextLanguages.isEmpty()))
        return languages.copy(
            audio = audio?.takeUnless { it == C.LANGUAGE_UNDETERMINED } ?: languages.audio,
            subtitles = text?.takeUnless { it == C.LANGUAGE_UNDETERMINED } ?: languages.subtitles,
            subtitlesOn = when {
                text != null -> true
                off -> false
                else -> languages.subtitlesOn
            },
        )
    }

    /** Subtitles off as Media3's own controls turn them off: every flag but forced ignored, so forced ones still show. */
    const val SUBTITLES_OFF = C.SELECTION_FLAG_FORCED.inv()
}
