package com.markedoe.ondo.core.parsing

import com.markedoe.ondo.core.StableHash
import com.markedoe.ondo.core.TextNormalizer
import com.markedoe.ondo.core.model.CatchupInfo
import com.markedoe.ondo.core.model.Channel
import com.markedoe.ondo.core.model.ContentType
import com.markedoe.ondo.core.model.Episode
import com.markedoe.ondo.core.model.MediaCategory
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.PlaylistContent
import com.markedoe.ondo.core.model.Series
import com.markedoe.ondo.core.nilIfBlank
import com.markedoe.ondo.core.xtream.XtreamUrlBuilder

/**
 * Turns a flat M3U list into Live / Movies / Series with categories.
 *
 * Ids are stable across reloads (hash of the stream URL, or of category + series name) and unique within a
 * playlist: entries sharing a URL get `-2`, `-3`… suffixes, episodes included, so progress is kept per episode.
 */
object M3uContentBuilder {
    fun build(playlist: M3uPlaylist): PlaylistContent = build(playlist.headerAttributes, playlist.entries.asSequence())

    /** The catalogue of [entries], read once in order (see `M3uParser.entries`). */
    fun build(headerAttributes: Map<String, String>, entries: Sequence<M3uEntry>): PlaylistContent {
        val defaultCatchup = catchupInfo(headerAttributes, url = null)

        val liveCategories = OrderedCategories(ContentType.LIVE)
        val movieCategories = OrderedCategories(ContentType.MOVIE)
        val seriesCategories = OrderedCategories(ContentType.SERIES)
        val channels = ArrayList<Channel>()
        val movies = ArrayList<Movie>()
        val seriesByKey = LinkedHashMap<String, SeriesAccumulator>()
        val usedIds = HashSet<String>()
        val lastSuffix = HashMap<String, Int>()
        val normalizedSeriesNames = HashMap<String, String>()

        fun uniqueId(base: String): String {
            if (usedIds.add(base)) return base
            // Entries sharing a URL go on from the last suffix of that base: trying `-2`, `-3`… again for each one
            // took quadratic time (a suffixed id has a `-`, a base never has one, so only that base used them).
            var counter = lastSuffix[base] ?: 1
            var candidate: String
            do {
                counter++
                candidate = "$base-$counter"
            } while (!usedIds.add(candidate))
            lastSuffix[base] = counter
            return candidate
        }

        val classifier = ContentClassifier()
        for (entry in entries) {
            val headers = if (entry.headers.isEmpty) null else entry.headers
            when (classifier.classify(entry)) {
                ContentType.LIVE -> {
                    val category = liveCategories.category(entry.groupTitle)
                    var catchup = catchupInfo(entry.attributes, entry.url) ?: defaultCatchup
                    if (catchup?.days == 0) catchup = null
                    channels.add(
                        Channel(
                            id = uniqueId("c" + StableHash.hex(entry.url)),
                            name = entry.title,
                            number = entry.channelNumber,
                            logoUrl = entry.logo,
                            categoryId = category.id,
                            streamUrl = entry.url,
                            epgId = entry.tvgId,
                            epgName = entry.tvgName,
                            catchup = catchup,
                            headers = headers,
                            xtreamStreamId = XtreamUrlBuilder.streamIdFromLiveUrl(entry.url),
                        ),
                    )
                }

                ContentType.MOVIE -> {
                    val category = movieCategories.category(entry.groupTitle)
                    val (cleanName, year) = TitleParser.splitYear(entry.title)
                    movies.add(
                        Movie(
                            id = uniqueId("m" + StableHash.hex(entry.url)),
                            name = cleanName,
                            posterUrl = entry.logo,
                            categoryId = category.id,
                            streamUrl = entry.url,
                            year = year,
                            headers = headers,
                            containerExtension = ContentClassifier.fileExtension(entry.url).nilIfBlank(),
                        ),
                    )
                }

                ContentType.SERIES -> {
                    val category = seriesCategories.category(entry.groupTitle)
                    val parsed = TitleParser.parseEpisode(entry.title)
                    val seriesName = parsed?.seriesName ?: entry.tvgName ?: entry.title
                    val normalizedName = normalizedSeriesNames.getOrPut(seriesName) { TextNormalizer.normalize(seriesName) }
                    val key = category.id + "|" + normalizedName
                    val series = seriesByKey.getOrPut(key) {
                        // The key (and so the id) keeps the full name; the year in it is shown apart.
                        val (cleanName, year) = TitleParser.splitYear(seriesName)
                        SeriesAccumulator("s" + StableHash.hex(key), cleanName, year, entry.logo, category.id)
                    }
                    val season = parsed?.season ?: 1
                    val inSeason = series.episodesPerSeason[season] ?: 0
                    series.episodesPerSeason[season] = inSeason + 1
                    val number = parsed?.episode ?: (inSeason + 1)
                    series.episodes.add(
                        Episode(
                            id = uniqueId("e" + StableHash.hex(entry.url)),
                            title = parsed?.episodeTitle ?: entry.title,
                            season = season,
                            number = number,
                            streamUrl = entry.url,
                            posterUrl = entry.logo,
                            headers = headers,
                        ),
                    )
                    if (series.coverUrl == null) series.coverUrl = entry.logo
                }
            }
        }

        val series = seriesByKey.values.map { accumulator ->
            Series(
                id = accumulator.id,
                name = accumulator.name,
                coverUrl = accumulator.coverUrl,
                categoryId = accumulator.categoryId,
                year = accumulator.year,
                episodes = accumulator.episodes.sortedWith(compareBy({ it.season }, { it.number })),
            )
        }

        return PlaylistContent(
            channels = channels,
            movies = movies,
            series = series,
            liveCategories = liveCategories.all,
            movieCategories = movieCategories.all,
            seriesCategories = seriesCategories.all,
            providerEpgUrls = M3uPlaylist(headerAttributes, emptyList()).epgUrls,
        )
    }

    private class SeriesAccumulator(val id: String, val name: String, val year: Int?, var coverUrl: String?, val categoryId: String) {
        val episodes = ArrayList<Episode>()

        /** Episodes added so far per season (counting them again for each new one took quadratic time). */
        val episodesPerSeason = HashMap<Int, Int>()
    }

    /** Reads `catchup`, `catchup-type`, `catchup-days`, `catchup-source`, `timeshift`, `tvg-rec`, `tv_archive_duration`. */
    internal fun catchupInfo(attributes: Map<String, String>, url: String?): CatchupInfo? {
        val typeValue = (attributes["catchup"] ?: attributes["catchup-type"])?.lowercase()?.nilIfBlank()
        val daysValue = attributes["catchup-days"] ?: attributes["timeshift"] ?: attributes["tvg-rec"]
            ?: attributes["tv_archive_duration"]
        val source = attributes["catchup-source"]?.nilIfBlank()

        if (typeValue == null && daysValue == null && source == null) return null
        val days = daysValue?.trim()?.toIntOrNull() ?: if (typeValue != null) 7 else 0

        val mode = when (typeValue) {
            "append" -> CatchupInfo.Mode.APPEND
            "shift", "timeshift" -> CatchupInfo.Mode.SHIFT
            "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> CatchupInfo.Mode.FLUSSONIC
            "xc", "xtream" -> CatchupInfo.Mode.XTREAM
            "default" -> if (source == null) fallbackMode(url) else CatchupInfo.Mode.DEFAULT
            // Only `timeshift=` / `tvg-rec=`: the usual meaning is "shift", unless it is an Xtream URL.
            else -> if (source != null) CatchupInfo.Mode.DEFAULT else fallbackMode(url)
        }
        return CatchupInfo(mode, days, source)
    }

    private fun fallbackMode(url: String?): CatchupInfo.Mode =
        if (url != null && XtreamUrlBuilder.parseLiveUrl(url) != null) CatchupInfo.Mode.XTREAM else CatchupInfo.Mode.SHIFT
}

/** Keeps categories in first-seen order. */
internal class OrderedCategories(private val type: ContentType) {
    private val byName = LinkedHashMap<String, MediaCategory>()

    val all: List<MediaCategory>
        get() = byName.values.toList()

    fun category(rawName: String?): MediaCategory {
        val name = rawName?.nilIfBlank() ?: "Uncategorized"
        return byName.getOrPut(name) { MediaCategory("${type.rawValue}-" + StableHash.hex(name), name, type) }
    }
}
