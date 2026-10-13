package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.TextNormalizer
import com.markedoe.ondo.core.epg.EpgFilter
import com.markedoe.ondo.core.epg.XmltvParser
import com.markedoe.ondo.core.model.EpgData
import com.markedoe.ondo.core.model.Playlist
import com.markedoe.ondo.core.model.PlaylistContent
import com.markedoe.ondo.core.model.PlaylistSource
import com.markedoe.ondo.core.model.TimeWindow
import com.markedoe.ondo.core.net.Gzip
import com.markedoe.ondo.core.net.HttpClient
import com.markedoe.ondo.core.parsing.JsonPlaylistParser
import com.markedoe.ondo.core.parsing.M3uContentBuilder
import com.markedoe.ondo.core.parsing.M3uParser
import com.markedoe.ondo.core.parsing.PlainListParser
import com.markedoe.ondo.core.parsing.PlaylistFormat
import com.markedoe.ondo.core.parsing.XspfParser
import com.markedoe.ondo.core.xtream.XtreamClient
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The downloaded data is not a usable playlist. */
sealed class PlaylistLoaderException(message: String) : Exception(message) {
    class UnsupportedFormat : PlaylistLoaderException("This file is not a playlist the app understands (M3U, XSPF or JSON).")

    class EmptyPlaylist : PlaylistLoaderException("The playlist was downloaded but contains no channels.")
}

/** Merged guide of a playlist, and the sources that could not be loaded (by URL). */
data class EpgLoadResult(val data: EpgData, val failures: Map<String, Throwable>)

/**
 * Downloads and parses any playlist source into [PlaylistContent].
 * Parsing runs on [parsingDispatcher], file reads on [ioDispatcher].
 */
class PlaylistLoader(
    private val http: HttpClient,
    private val parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Downloads (or reads) the playlist and returns its content. */
    suspend fun load(playlist: Playlist): PlaylistContent = when (val source = playlist.source) {
        is PlaylistSource.Xtream -> {
            val client = XtreamClient(source.credentials, http, playlist.headers, parsingDispatcher, clock)
            if (playlist.xtreamForceM3u) {
                // Some panels have a broken JSON API but a working M3U export.
                val account = try {
                    client.authenticate()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                val data = http.get(client.urls.m3uUrl(playlist.xtreamOutput), playlist.headers.toMap())
                val content = parseInBackground(data).copy(account = account)
                if (content.providerEpgUrls.isEmpty()) content.copy(providerEpgUrls = listOf(client.urls.xmltvUrl)) else content
            } else {
                client.fetchContent(playlist.xtreamOutput).copy(fetchedAt = clock())
            }
        }

        is PlaylistSource.Remote -> parseInBackground(http.get(source.url, playlist.headers.toMap()))

        is PlaylistSource.LocalFile -> parseInBackground(withContext(ioDispatcher) { File(source.path).readBytes() })
    }

    private suspend fun parseInBackground(bytes: ByteArray): PlaylistContent = withContext(parsingDispatcher) { parse(bytes) }

    /**
     * Parses M3U, XSPF, JSON or plain URL lists (gzip compressed or not).
     * @throws PlaylistLoaderException when the data is not a playlist or has no entry
     */
    fun parse(bytes: ByteArray): PlaylistContent {
        val data = Gzip.gunzipIfNeeded(bytes)
        if (data.isEmpty()) throw PlaylistLoaderException.EmptyPlaylist()
        val parsed = when (PlaylistFormat.detect(data) ?: throw PlaylistLoaderException.UnsupportedFormat()) {
            PlaylistFormat.M3U -> M3uParser.parse(data)
            PlaylistFormat.XSPF -> XspfParser.parse(data)
            PlaylistFormat.JSON -> JsonPlaylistParser.parse(data)
            PlaylistFormat.PLAIN_LIST -> PlainListParser.parse(data)
        }
        if (parsed.entries.isEmpty()) throw PlaylistLoaderException.EmptyPlaylist()
        return M3uContentBuilder.build(parsed).copy(fetchedAt = clock())
    }

    /**
     * Loads, merges and filters every guide of a playlist: the user's sources first (they win on overlaps),
     * then the provider's when [Playlist.useProviderEpg]. Individual source failures are reported, not thrown.
     */
    suspend fun loadEpg(
        playlist: Playlist,
        content: PlaylistContent,
        window: TimeWindow = EpgFilter.standard(clock()),
    ): EpgLoadResult {
        val sources = playlist.epgUrls.toMutableList()
        if (playlist.useProviderEpg) {
            for (url in content.providerEpgUrls) if (url !in sources) sources.add(url)
        }
        val filter = EpgFilter(
            window = window,
            // Guide channels picked by hand are kept even when nothing else matches them.
            channelIds = content.channels.mapNotNullTo(HashSet()) { it.epgId } + playlist.epgChannelOverrides.values.filter { it.isNotEmpty() },
            channelNameKeys = content.channels
                .flatMap { listOfNotNull(it.name, it.epgName) }
                .mapTo(HashSet(), TextNormalizer::channelMatchKey),
        )
        val shift = (playlist.epgTimeShiftHours * 3_600_000).toLong()
        val parser = XmltvParser()
        var merged = EpgData()
        val failures = LinkedHashMap<String, Throwable>()
        for (url in sources) {
            try {
                val raw = http.get(url, playlist.headers.toMap())
                val parsed = withContext(parsingDispatcher) { parser.parseGuide(raw, filter, shift) }
                merged = merged.merged(parsed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures[url] = e
            }
        }
        return EpgLoadResult(merged.copy(fetchedAt = clock()), failures)
    }
}
