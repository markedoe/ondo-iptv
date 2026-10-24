package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.TextNormalizer
import com.markedoe.ondo.core.epg.EpgFilter
import com.markedoe.ondo.core.epg.GuideEstimate
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
import java.io.FilterInputStream
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
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
 * Parsing runs on [parsingDispatcher], file reads on [ioDispatcher]. Large answers (guides, Xtream lists) are downloaded
 * into [downloadFolder] (the system's temporary folder when null) and read from there, then deleted.
 */
class PlaylistLoader(
    private val http: HttpClient,
    private val parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val downloadFolder: File? = null,
    /**
     * Memory the programmes of a playlist's guides may take, in bytes (null: no limit). A guide over it is kept within
     * a narrower window around now (see [GuideEstimate]); the app gives a quarter of its memory.
     */
    private val guideBudgetBytes: Long? = null,
) {
    /** Downloads (or reads) the playlist and returns its content. */
    suspend fun load(playlist: Playlist): PlaylistContent = when (val source = playlist.source) {
        is PlaylistSource.Xtream -> {
            val client = XtreamClient(source.credentials, http, playlist.headers, parsingDispatcher, clock, downloadFolder)
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
                val content = parseInBackground(data).withoutDeviceGuides().copy(account = account)
                if (content.providerEpgUrls.isEmpty()) content.copy(providerEpgUrls = listOf(client.urls.xmltvUrl)) else content
            } else {
                val content = client.fetchContent(playlist.xtreamOutput)
                // An account that lists nothing at all is a provider fault: keep the saved catalogue.
                if (content.channels.isEmpty() && content.movies.isEmpty() && content.series.isEmpty()) {
                    throw PlaylistLoaderException.EmptyPlaylist()
                }
                content.copy(fetchedAt = clock())
            }
        }

        is PlaylistSource.Remote -> parseInBackground(http.get(source.url, playlist.headers.toMap())).let { content ->
            // A playlist from the internet cannot make the app read files on the device as its guide.
            if (source.url.startsWith("file:", ignoreCase = true)) content else content.withoutDeviceGuides()
        }

        is PlaylistSource.LocalFile -> parseInBackground(withContext(ioDispatcher) { File(source.path).readBytes() })
    }

    private suspend fun parseInBackground(bytes: ByteArray): PlaylistContent = withContext(parsingDispatcher) { parse(bytes) }

    private fun PlaylistContent.withoutDeviceGuides(): PlaylistContent =
        copy(providerEpgUrls = providerEpgUrls.filterNot { it.startsWith("file:", ignoreCase = true) })

    /**
     * Parses M3U, XSPF, JSON or plain URL lists (gzip compressed or not).
     * @throws PlaylistLoaderException when the data is not a playlist or has no entry
     */
    fun parse(bytes: ByteArray): PlaylistContent {
        val data = Gzip.gunzipIfNeeded(bytes)
        if (data.isEmpty()) throw PlaylistLoaderException.EmptyPlaylist()
        val content = when (PlaylistFormat.detect(data) ?: throw PlaylistLoaderException.UnsupportedFormat()) {
            // Entry by entry into the catalogue: large M3U playlists are the common case.
            PlaylistFormat.M3U -> M3uContentBuilder.build(M3uParser.headerAttributes(data), M3uParser.entries(data))
            PlaylistFormat.XSPF -> M3uContentBuilder.build(XspfParser.parse(data))
            PlaylistFormat.JSON -> M3uContentBuilder.build(JsonPlaylistParser.parse(data))
            PlaylistFormat.PLAIN_LIST -> M3uContentBuilder.build(PlainListParser.parse(data))
        }
        // Every entry becomes a channel, a movie or an episode: nothing in the catalogue means no entry.
        if (content.channels.isEmpty() && content.movies.isEmpty() && content.series.isEmpty()) throw PlaylistLoaderException.EmptyPlaylist()
        return content.copy(fetchedAt = clock())
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
        val sources = ArrayList<String>()
        val seen = HashSet<String>()
        val all = if (playlist.useProviderEpg) playlist.epgUrls + content.providerEpgUrls else playlist.epgUrls
        // The same guide written two ways (the user's copy of the provider's address) is downloaded once.
        for (url in all) if (seen.add(guideKey(url))) sources.add(url)
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
        val now = clock()
        var remaining = guideBudgetBytes
        var merged = EpgData()
        val failures = LinkedHashMap<String, Throwable>()
        for (url in sources) {
            var file: File? = null
            try {
                // On disk, then read while parsed: a large guide (hundreds of megabytes) never sits whole in memory.
                val download = withContext(ioDispatcher) {
                    downloadFolder?.mkdirs()
                    File.createTempFile("guide-", ".xml", downloadFolder)
                }
                file = download
                http.download(url, playlist.headers.toMap(), download)
                // Within the memory left: the guide is sized first, and read within a narrower window when it is over.
                val budget = remaining
                val sourceFilter = if (budget == null) {
                    filter
                } else {
                    val estimate = withContext(parsingDispatcher) { download.inputStream().cancellable(this).use { parser.estimateGuide(it, filter, shift, now) } }
                    if (estimate.totalBytes <= budget) {
                        remaining = budget - estimate.totalBytes
                        filter
                    } else {
                        val selection = estimate.window(budget, filter.window)
                        remaining = (budget - selection.bytes).coerceAtLeast(0)
                        filter.within(selection.window)
                    }
                }
                val parsed = withContext(parsingDispatcher) { download.inputStream().cancellable(this).use { parser.parseGuide(it, sourceFilter, shift) } }
                merged = merged.merged(parsed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures[url] = e
            } catch (e: OutOfMemoryError) {
                // A guide too large for this device is left out; the others and the app carry on.
                failures[url] = e
            } finally {
                file?.let { withContext(NonCancellable + ioDispatcher) { it.delete() } }
            }
        }
        return EpgLoadResult(merged.copy(fetchedAt = clock()), failures)
    }

    /** Stops reading once [scope] is cancelled: a guide nobody waits for any more is not parsed to its end. */
    private fun InputStream.cancellable(scope: CoroutineScope): InputStream = object : FilterInputStream(this) {
        override fun read(): Int {
            scope.ensureActive()
            return super.read()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            scope.ensureActive()
            return super.read(b, off, len)
        }
    }

    internal companion object {
        /** The same address however it is written: scheme, case, trailing slash and order of the parameters aside. */
        fun guideKey(url: String): String {
            val trimmed = url.trim().substringBefore('#')
            val address = trimmed.substringAfter("://", trimmed).substringBefore('?').trimEnd('/').lowercase()
            val query = trimmed.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.map { it.lowercase() }.sorted()
            return if (query.isEmpty()) address else address + "?" + query.joinToString("&")
        }
    }
}
