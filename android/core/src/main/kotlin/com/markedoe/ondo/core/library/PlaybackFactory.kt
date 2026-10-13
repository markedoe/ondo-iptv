package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.catchup.CatchupContext
import com.markedoe.ondo.core.catchup.CatchupUrlBuilder
import com.markedoe.ondo.core.model.AccountInfo
import com.markedoe.ondo.core.model.Channel
import com.markedoe.ondo.core.model.ContentType
import com.markedoe.ondo.core.model.EpgProgram
import com.markedoe.ondo.core.model.Episode
import com.markedoe.ondo.core.model.HttpHeaders
import com.markedoe.ondo.core.model.MediaReference
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.PlaybackItem
import com.markedoe.ondo.core.model.Playlist
import com.markedoe.ondo.core.model.Series
import com.markedoe.ondo.core.xtream.XtreamUrlBuilder
import java.time.DateTimeException
import java.time.ZoneId

/** Builds [PlaybackItem]s (with fallback URLs and headers) for anything playable in a playlist. */
class PlaybackFactory(
    val playlist: Playlist,
    /** Xtream account (its server time zone is needed for catch-up URLs). */
    val account: AccountInfo? = null,
) {
    /**
     * Live playback of `channel`. Xtream style URLs get their other container as fallback (`.ts` <-> `.m3u8`);
     * `alternates` are other versions of the same channel (HD/FHD/backup) tried when the stream is dead.
     */
    fun item(channel: Channel, currentProgram: EpgProgram? = null, alternates: List<Channel> = emptyList()): PlaybackItem {
        val urls = ArrayList<String>()
        urls.add(channel.streamUrl)
        XtreamUrlBuilder.alternateFormatUrl(channel.streamUrl)?.let(urls::add)
        for (other in alternates.take(3)) {
            if (other.id != channel.id && other.streamUrl != channel.streamUrl) urls.add(other.streamUrl)
        }
        return PlaybackItem(
            title = channel.name,
            subtitle = currentProgram?.title,
            urls = urls.distinct(),
            headers = headersFor(channel.headers),
            reference = MediaReference(playlist.id, ContentType.LIVE, channel.id),
            isLive = true,
            artworkUrl = channel.logoUrl,
        )
    }

    /** Replay of a past programme, or `null` when it is outside the channel's archive. */
    fun catchupItem(channel: Channel, program: EpgProgram, now: Long = System.currentTimeMillis()): PlaybackItem? =
        catchupItem(channel, program.start, program.end, program.title, program.iconUrl, now)

    /**
     * Replays `channel` from `start` (restart of the current programme, live pause…).
     * `null` when the channel has no catch-up or `start` is outside its archive.
     */
    fun catchupItem(
        channel: Channel,
        start: Long,
        end: Long,
        title: String? = null,
        artworkUrl: String? = null,
        now: Long = System.currentTimeMillis(),
    ): PlaybackItem? {
        if (!CatchupUrlBuilder.isAvailable(channel, start, now)) return null
        val context = CatchupContext(
            xtream = playlist.source.xtreamCredentials,
            serverTimeZone = account?.serverTimeZone?.let(::zoneOrNull),
            outputFormat = playlist.xtreamOutput,
        )
        val urls = CatchupUrlBuilder.urls(channel, start, maxOf(end, start + 60_000), now, context)
        if (urls.isEmpty()) return null
        return PlaybackItem(
            title = title ?: channel.name,
            subtitle = channel.name,
            urls = urls.distinct(),
            headers = headersFor(channel.headers),
            reference = MediaReference(playlist.id, ContentType.LIVE, channel.id),
            isLive = false,
            artworkUrl = artworkUrl ?: channel.logoUrl,
            isCatchup = true,
        )
    }

    fun item(movie: Movie, startPosition: Double? = null): PlaybackItem = PlaybackItem(
        title = movie.name,
        subtitle = movie.year?.toString(),
        urls = listOf(movie.streamUrl),
        headers = headersFor(movie.headers),
        reference = MediaReference(playlist.id, ContentType.MOVIE, movie.id),
        isLive = false,
        artworkUrl = movie.posterUrl,
        startPosition = startPosition,
    )

    fun item(episode: Episode, series: Series, startPosition: Double? = null): PlaybackItem = PlaybackItem(
        title = series.name,
        subtitle = "${episode.code} · ${episode.title}",
        urls = listOf(episode.streamUrl),
        headers = headersFor(episode.headers),
        reference = MediaReference(playlist.id, ContentType.SERIES, series.id, episode.id),
        isLive = false,
        artworkUrl = episode.posterUrl ?: series.coverUrl,
        startPosition = startPosition,
    )

    /** Playlist headers overridden by the item's; `null` when there is nothing to send. */
    private fun headersFor(itemHeaders: HttpHeaders?): HttpHeaders? =
        playlist.headers.merging(itemHeaders).takeUnless { it.isEmpty }

    private fun zoneOrNull(id: String): ZoneId? = try {
        ZoneId.of(id)
    } catch (_: DateTimeException) {
        null
    }
}
