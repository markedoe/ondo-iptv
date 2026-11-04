package com.markedoe.ondo.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.markedoe.ondo.core.subtitles.OnlineSubtitle
import com.markedoe.ondo.core.subtitles.SubtitleSearch
import com.markedoe.ondo.data.ChosenSubtitle
import com.markedoe.ondo.data.SubtitleServices
import java.io.File

/** A subtitle downloaded from a service, played beside the video's own tracks (a Media3 side-loaded subtitle). */
@OptIn(UnstableApi::class)
object SideLoadedSubtitle {
    /** The id of its track, to find it among the video's. */
    const val ID = "ondo-online-subtitle"

    fun configuration(chosen: ChosenSubtitle): MediaItem.SubtitleConfiguration =
        MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(File(chosen.path)))
            .setMimeType(mimeType(chosen.format))
            .setLanguage(chosen.language)
            .setLabel(chosen.label)
            .setId(ID)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()

    fun mimeType(format: String): String = when (format) {
        "vtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    /** [parameters] with the downloaded subtitle's track chosen, once it is among [tracks]; null before. */
    fun select(parameters: TrackSelectionParameters, tracks: Tracks): TrackSelectionParameters? {
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            for (index in 0 until group.length) {
                if (group.getTrackFormat(index).id?.endsWith(ID) != true) continue
                val choice = TrackChoice("", null, false, group, index)
                return TrackChoices.select(parameters, C.TRACK_TYPE_TEXT, choice)
            }
        }
        return null
    }
}

/** The search of the player's panel, through the services set up in Settings; a download is kept for [titleKey]. */
class ServicesSource(private val services: SubtitleServices, private val titleKey: String?) : OnlineSubtitleSource {
    override val configured: Boolean get() = services.state.value.any

    override suspend fun search(hint: SubtitleSearchHint, languages: List<String>): SubtitleSearch.Outcome = services.search().search(hint.query(languages))

    override suspend fun download(subtitle: OnlineSubtitle, hint: SubtitleSearchHint, languages: List<String>): ChosenSubtitle =
        services.download(subtitle, hint.query(languages), titleKey)
}
