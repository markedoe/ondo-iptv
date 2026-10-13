package com.markedoe.ondo.core.xtream

import com.markedoe.ondo.core.model.AccountInfo
import com.markedoe.ondo.core.model.CatchupInfo
import com.markedoe.ondo.core.model.Channel
import com.markedoe.ondo.core.model.ContentType
import com.markedoe.ondo.core.model.EpgProgram
import com.markedoe.ondo.core.model.Episode
import com.markedoe.ondo.core.model.HttpHeaders
import com.markedoe.ondo.core.model.MediaCategory
import com.markedoe.ondo.core.model.MediaDetails
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.PlaylistContent
import com.markedoe.ondo.core.model.Season
import com.markedoe.ondo.core.model.Series
import com.markedoe.ondo.core.model.SeriesDetails
import com.markedoe.ondo.core.model.XtreamCredentials
import com.markedoe.ondo.core.model.XtreamOutputFormat
import com.markedoe.ondo.core.net.Gzip
import com.markedoe.ondo.core.net.HttpClient
import com.markedoe.ondo.core.net.HttpException
import com.markedoe.ondo.core.net.Urls
import com.markedoe.ondo.core.nilIfBlank
import com.markedoe.ondo.core.parsing.TitleParser
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Errors specific to Xtream panels, with messages meant for the user. */
sealed class XtreamException(message: String) : Exception(message) {
    /** The panel rejected the login (`auth: 0`). [serverMessage] is the panel's own explanation, if any. */
    class AuthenticationFailed(val serverMessage: String?) :
        XtreamException(serverMessage?.nilIfBlank() ?: "Login failed. Check the server address, username and password.")

    /** The login is valid but the subscription is not active (expired, banned…). */
    class AccountNotActive(val status: String) : XtreamException("The provider reports this account as “$status”.")

    /** The answer is not JSON shaped like an Xtream Codes panel's. */
    class InvalidResponse : XtreamException("The server did not answer like an Xtream Codes panel.")
}

/** Client for the Xtream Codes `player_api.php` API. */
class XtreamClient(
    val credentials: XtreamCredentials,
    private val http: HttpClient,
    val headers: HttpHeaders = HttpHeaders.NONE,
    private val parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val urls: XtreamUrlBuilder = XtreamUrlBuilder(credentials)

    private suspend fun getJson(action: String? = null, parameters: Map<String, String> = emptyMap()): JsonElement {
        val url = urls.apiUrl(action, parameters)
        val raw = http.get(url, headers.toMap())
        return withContext(parsingDispatcher) {
            val data = Gzip.gunzipIfNeeded(raw)
            if (data.isEmpty()) throw HttpException.EmptyResponse()
            try {
                Json.parseToJsonElement(data.decodeToString())
            } catch (e: IllegalArgumentException) { // SerializationException
                throw XtreamException.InvalidResponse()
            }
        }
    }

    // Account

    /** Logs in and returns the account status. Throws [XtreamException] for refused or inactive accounts. */
    suspend fun authenticate(): AccountInfo {
        val root = getJson() as? kotlinx.serialization.json.JsonObject ?: throw XtreamException.InvalidResponse()
        val user = XtreamUserInfo.from(root["user_info"]) ?: throw XtreamException.InvalidResponse()
        if (!user.auth) throw XtreamException.AuthenticationFailed(user.message)
        val status = user.status ?: "Active"
        if (status.lowercase() != "active") throw XtreamException.AccountNotActive(status)
        val server = XtreamServerInfo.from(root["server_info"])
        return AccountInfo(
            username = user.username ?: credentials.username,
            status = status,
            isTrial = user.isTrial,
            expiresAt = user.expDate?.times(1000),
            createdAt = user.createdAt?.times(1000),
            activeConnections = user.activeConnections,
            maxConnections = user.maxConnections,
            allowedOutputFormats = user.allowedOutputFormats,
            message = user.message?.nilIfBlank(),
            serverTimeZone = server?.timezone,
            serverUrl = server?.url,
        )
    }

    // Catalogue

    private suspend fun categories(action: String, type: ContentType): List<MediaCategory> =
        getJson(action).lossyList(XtreamCategoryDto::from)
            .map { MediaCategory("${type.rawValue}-${it.id}", it.name, type) }

    suspend fun liveCategories(): List<MediaCategory> = categories("get_live_categories", ContentType.LIVE)

    suspend fun movieCategories(): List<MediaCategory> = categories("get_vod_categories", ContentType.MOVIE)

    suspend fun seriesCategories(): List<MediaCategory> = categories("get_series_categories", ContentType.SERIES)

    suspend fun liveStreams(format: XtreamOutputFormat): List<Channel> =
        getJson("get_live_streams").lossyList(XtreamLiveStreamDto::from).map { dto ->
            Channel(
                id = "x${dto.streamId}",
                name = dto.name,
                number = dto.number,
                logoUrl = Urls.lenient(dto.icon),
                categoryId = "live-${dto.categoryId ?: "0"}",
                streamUrl = urls.liveUrl(dto.streamId, format),
                epgId = dto.epgChannelId,
                catchup = if (dto.tvArchive) CatchupInfo(CatchupInfo.Mode.XTREAM, maxOf(dto.tvArchiveDuration ?: 1, 1)) else null,
                xtreamStreamId = dto.streamId,
            )
        }

    suspend fun movies(): List<Movie> =
        getJson("get_vod_streams").lossyList(XtreamVodStreamDto::from).map { dto ->
            val (name, yearInTitle) = TitleParser.splitYear(dto.name)
            Movie(
                id = "x${dto.streamId}",
                name = name,
                posterUrl = Urls.lenient(dto.icon),
                categoryId = "movie-${dto.categoryId ?: "0"}",
                streamUrl = urls.movieUrl(dto.streamId, dto.containerExtension),
                rating = dto.rating ?: dto.rating5?.times(2),
                year = dto.year ?: yearInTitle,
                addedAt = dto.added,
                plot = dto.plot,
                xtreamStreamId = dto.streamId,
                containerExtension = dto.containerExtension,
            )
        }

    suspend fun series(): List<Series> =
        getJson("get_series").lossyList(XtreamSeriesDto::from).map { dto ->
            Series(
                id = "x${dto.seriesId}",
                name = dto.name,
                coverUrl = Urls.lenient(dto.cover),
                categoryId = "series-${dto.categoryId ?: "0"}",
                plot = dto.plot,
                rating = dto.rating,
                year = dto.year,
                genre = dto.genre,
                addedAt = dto.lastModified,
                xtreamSeriesId = dto.seriesId,
            )
        }

    /**
     * Downloads the whole catalogue: account first, then the six lists in parallel. A failing section
     * (some panels disable VOD) yields an empty list instead of failing the whole load.
     */
    suspend fun fetchContent(format: XtreamOutputFormat): PlaylistContent {
        val account = authenticate()
        // Respect the panel's allowed formats: fall back to TS when HLS is not offered.
        val allowed = account.allowedOutputFormats.map { it.lowercase() }
        val effectiveFormat = if (allowed.isNotEmpty() && format.rawValue !in allowed) {
            if ("m3u8" in allowed) XtreamOutputFormat.M3U8 else XtreamOutputFormat.TS
        } else {
            format
        }
        return coroutineScope {
            val liveCats = async { orEmpty { liveCategories() } }
            val movieCats = async { orEmpty { movieCategories() } }
            val seriesCats = async { orEmpty { seriesCategories() } }
            val channels = async { orEmpty { liveStreams(effectiveFormat) } }
            val movies = async { orEmpty { movies() } }
            val series = async { orEmpty { series() } }
            val resolvedChannels = channels.await()
            val resolvedMovies = movies.await()
            val resolvedSeries = series.await()
            PlaylistContent(
                channels = resolvedChannels,
                movies = resolvedMovies,
                series = resolvedSeries,
                liveCategories = categoriesWithOrphans(liveCats.await(), resolvedChannels.map { it.categoryId }, ContentType.LIVE),
                movieCategories = categoriesWithOrphans(movieCats.await(), resolvedMovies.map { it.categoryId }, ContentType.MOVIE),
                seriesCategories = categoriesWithOrphans(seriesCats.await(), resolvedSeries.map { it.categoryId }, ContentType.SERIES),
                providerEpgUrls = listOf(urls.xmltvUrl),
                account = account,
                fetchedAt = clock(),
            )
        }
    }

    private suspend fun <T> orEmpty(block: suspend () -> List<T>): List<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    // Details

    suspend fun movieDetails(streamId: Int): MediaDetails {
        val response = XtreamVodInfoResponse.from(getJson("get_vod_info", mapOf("vod_id" to streamId.toString())))
            ?: throw XtreamException.InvalidResponse()
        val info = response.info
        return MediaDetails(
            title = info?.name ?: response.movieData?.name ?: "",
            originalTitle = info?.originalName,
            plot = info?.plot,
            cast = info?.cast,
            director = info?.director,
            genre = info?.genre,
            releaseDate = info?.releaseDate,
            durationSeconds = info?.durationSecs,
            rating = info?.rating,
            posterUrl = Urls.lenient(info?.movieImage ?: info?.cover),
            backdropUrl = Urls.lenient(info?.backdrops?.firstOrNull()),
            trailerYouTubeId = info?.youtubeTrailer,
            tmdbId = info?.tmdbId,
        )
    }

    /** Seasons and episodes of a series; episodes may come as a dictionary by season or as arrays. */
    suspend fun seriesDetails(seriesId: Int): SeriesDetails {
        val response = XtreamSeriesInfoResponse.from(getJson("get_series_info", mapOf("series_id" to seriesId.toString())))
            ?: throw XtreamException.InvalidResponse()
        val info = response.info
        val details = MediaDetails(
            title = info?.name ?: "",
            plot = info?.plot,
            cast = info?.cast,
            director = info?.director,
            genre = info?.genre,
            releaseDate = info?.releaseDate,
            rating = info?.rating,
            posterUrl = Urls.lenient(info?.cover ?: info?.movieImage),
            backdropUrl = Urls.lenient(info?.backdrops?.firstOrNull()),
            trailerYouTubeId = info?.youtubeTrailer,
            tmdbId = info?.tmdbId,
        )
        val seasonMeta = LinkedHashMap<Int, XtreamSeasonDto>()
        for (season in response.seasons) seasonMeta.putIfAbsent(season.seasonNumber, season)
        val seasons = response.episodes.keys.sorted().map { number ->
            val episodes = response.episodes[number].orEmpty().mapIndexed { index, dto ->
                val episodeNumber = dto.episodeNumber ?: (index + 1)
                Episode(
                    id = dto.id,
                    title = dto.title?.let(::cleanEpisodeTitle) ?: "Episode $episodeNumber",
                    season = number,
                    number = episodeNumber,
                    streamUrl = urls.episodeUrl(dto.id, dto.containerExtension),
                    posterUrl = Urls.lenient(dto.info?.movieImage ?: dto.info?.cover),
                    plot = dto.info?.plot,
                    durationSeconds = dto.info?.durationSecs,
                    releaseDate = dto.info?.releaseDate,
                    rating = dto.info?.rating,
                )
            }.sortedBy { it.number }
            val meta = seasonMeta[number]
            Season(
                number = number,
                name = meta?.name ?: "Season $number",
                episodes = episodes,
                coverUrl = Urls.lenient(meta?.cover),
                overview = meta?.overview,
            )
        }
        return SeriesDetails(details, seasons)
    }

    // Guide

    /** Now / next for one channel, without downloading the full XMLTV. */
    suspend fun shortEpg(streamId: Int, epgChannelId: String, limit: Int = 4): List<EpgProgram> =
        listings("get_short_epg", mapOf("stream_id" to streamId.toString(), "limit" to limit.toString()), epgChannelId)

    /** Full archive listing for one channel (used for catch-up when no XMLTV is available). */
    suspend fun fullEpg(streamId: Int, epgChannelId: String): List<EpgProgram> =
        listings("get_simple_data_table", mapOf("stream_id" to streamId.toString()), epgChannelId)

    private suspend fun listings(action: String, parameters: Map<String, String>, epgChannelId: String): List<EpgProgram> {
        val listings = XtreamEpgListingDto.listings(getJson(action, parameters)) ?: throw XtreamException.InvalidResponse()
        return listings.map {
            EpgProgram(
                channelId = epgChannelId,
                start = it.start * 1000,
                end = it.end * 1000,
                title = it.title,
                description = it.description,
            )
        }.sortedBy { it.start }
    }

    companion object {
        /** Items sometimes reference categories the panel does not list: add "Other" buckets for them. */
        internal fun categoriesWithOrphans(categories: List<MediaCategory>, itemCategoryIds: List<String>, type: ContentType): List<MediaCategory> {
            val known = categories.mapTo(HashSet()) { it.id }
            val result = categories.toMutableList()
            val added = LinkedHashSet<String>()
            for (id in itemCategoryIds) {
                if (id in known || !added.add(id)) continue
                result.add(MediaCategory(id, if (added.size == 1) "Other" else "Other ${added.size}", type))
            }
            return result
        }

        /** `"Breaking Bad - S01E01 - Pilot"` -> `"Pilot"`. */
        internal fun cleanEpisodeTitle(title: String): String {
            val parsed = TitleParser.parseEpisode(title)
            return if (parsed != null && !parsed.episodeTitle.startsWith("Episode ")) parsed.episodeTitle else title
        }
    }
}
