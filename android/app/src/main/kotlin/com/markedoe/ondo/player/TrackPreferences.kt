package com.markedoe.ondo.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.markedoe.ondo.core.playback.StreamTrack
import com.markedoe.ondo.core.playback.TitleTracks
import com.markedoe.ondo.core.playback.TrackDefaults
import com.markedoe.ondo.core.playback.TrackMemory
import com.markedoe.ondo.core.playback.TrackPick

/** A choice the viewer made in the player, per type of track: a sound track, a subtitle track, or subtitles turned off. */
data class TracksChosen(val audio: TrackPick? = null, val subtitles: TrackPick? = null, val subtitlesOff: Boolean = false) {
    val isEmpty: Boolean get() = audio == null && subtitles == null && !subtitlesOff
}

/**
 * The tracks a stream opens with, from what was chosen for its title and the profile's languages (`TrackMemory`,
 * docs/TRACKS.md); and what a choice in the player says.
 */
@OptIn(UnstableApi::class)
object TrackPreferences {
    /**
     * [base] asking Media3 for the title's sound language, then the profile's, then the device's ([deviceLanguages]):
     * before the track the provider flags as default, which is often English. Subtitles in the title's or the profile's
     * language when it has one; else none but the forced ones (foreign dialogue). A language the stream lacks shows no
     * subtitles rather than the track flagged default.
     */
    fun parameters(base: TrackSelectionParameters, memory: TitleTracks?, defaults: TrackDefaults?, deviceLanguages: List<String>): TrackSelectionParameters {
        val builder = base.buildUpon()
            .clearOverrides()
            .setPreferredAudioLanguages(*(TrackMemory.audioLanguages(memory, defaults) + deviceLanguages).distinct().toTypedArray())
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setIgnoredTextSelectionFlags(SUBTITLES_OFF)
        val subtitles = TrackMemory.subtitleLanguages(memory, defaults)
        builder.setPreferredTextLanguages(*subtitles.toTypedArray())
        return builder.build()
    }

    /** One playable track of [tracks] of [type] (audio or text), where it is, and as the memory compares tracks. */
    class Playable(val group: Tracks.Group, val index: Int, val track: StreamTrack) {
        val selected: Boolean get() = group.isTrackSelected(index)
    }

    fun playable(tracks: Tracks, type: Int): List<Playable> = buildList {
        for (group in tracks.groups) {
            if (group.type != type) continue
            for (index in 0 until group.length) {
                if (group.isTrackSupported(index)) add(Playable(group, index, streamTrack(group.getTrackFormat(index))))
            }
        }
    }

    fun streamTrack(format: Format) = StreamTrack(
        language = format.language,
        label = format.label,
        forced = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
        hearingImpaired = format.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND != 0,
        codec = format.sampleMimeType,
    )

    /**
     * [parameters] with the tracks the title's memory and the profile's languages call for among [tracks]
     * (`TrackMemory.plan`: by language and kind, never by place), once the stream has listed them; null when Media3's
     * own selection is already them.
     */
    fun planned(parameters: TrackSelectionParameters, tracks: Tracks, memory: TitleTracks?, defaults: TrackDefaults?): TrackSelectionParameters? {
        val audio = playable(tracks, C.TRACK_TYPE_AUDIO)
        val text = playable(tracks, C.TRACK_TYPE_TEXT)
        if (audio.isEmpty() && text.isEmpty()) return null
        val currentAudio = audio.indexOfFirst { it.selected }.takeIf { it >= 0 }
        val currentText = text.indexOfFirst { it.selected }.takeIf { it >= 0 }
        val plan = TrackMemory.plan(audio.map { it.track }, text.map { it.track }, memory, defaults, currentAudio, currentText)
        val builder = parameters.buildUpon()
        var changed = false
        plan.audio?.takeIf { it != currentAudio }?.let { audio[it] }?.let { chosen ->
            builder.setOverrideForType(TrackSelectionOverride(chosen.group.mediaTrackGroup, chosen.index))
            changed = true
        }
        plan.subtitles?.takeIf { it != currentText }?.let { text[it] }?.let { chosen ->
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(chosen.group.mediaTrackGroup, chosen.index))
                .setIgnoredTextSelectionFlags(0)
            changed = true
        }
        return if (changed) builder.build() else null
    }

    /**
     * What the viewer chose between [previous] and [parameters], set by the panels or Media3's phone controls: an audio
     * track chosen, a subtitle track chosen, or subtitles turned off. A type of track that did not change says nothing.
     */
    fun chosen(previous: TrackSelectionParameters, parameters: TrackSelectionParameters): TracksChosen {
        val audio = override(parameters, C.TRACK_TYPE_AUDIO)
        val text = override(parameters, C.TRACK_TYPE_TEXT)
        val off = text == null && isOff(parameters)
        val wasOff = override(previous, C.TRACK_TYPE_TEXT) == null && isOff(previous)
        return TracksChosen(
            audio = audio?.takeIf { it != override(previous, C.TRACK_TYPE_AUDIO) }?.let { pick(it) },
            subtitles = text?.takeIf { it != override(previous, C.TRACK_TYPE_TEXT) }?.let { pick(it) },
            subtitlesOff = off && !wasOff,
        )
    }

    private fun override(parameters: TrackSelectionParameters, type: Int): TrackSelectionOverride? =
        parameters.overrides.values.firstOrNull { it.type == type }

    private fun pick(override: TrackSelectionOverride): TrackPick =
        TrackPick.of(streamTrack(override.mediaTrackGroup.getFormat(override.trackIndices.firstOrNull() ?: 0)))

    /** Subtitles off: disabled, or no language asked and the track flagged default ignored (as Media3's own controls do). */
    private fun isOff(parameters: TrackSelectionParameters): Boolean =
        C.TRACK_TYPE_TEXT in parameters.disabledTrackTypes ||
            (parameters.ignoredTextSelectionFlags and C.SELECTION_FLAG_DEFAULT != 0 && parameters.preferredTextLanguages.isEmpty())

    /** Subtitles off as Media3's own controls turn them off: every flag but forced ignored, so forced ones still show. */
    const val SUBTITLES_OFF = C.SELECTION_FLAG_FORCED.inv()
}
