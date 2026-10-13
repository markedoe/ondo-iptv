package com.markedoe.ondo.core.parsing

import com.markedoe.ondo.core.model.ContentType

/**
 * Decides whether an M3U entry is a live channel, a movie or a series episode.
 * Keeps a per-group cache (big playlists repeat the same few hundred groups), so an instance is
 * meant for one build and is not thread-safe.
 */
class ContentClassifier {
    private class GroupHints(val series: Boolean, val movie: Boolean)

    private val groupCache = HashMap<String, GroupHints>()

    private fun hints(group: String?): GroupHints {
        val key = group.orEmpty()
        return groupCache.getOrPut(key) {
            val lower = key.lowercase()
            GroupHints(
                series = SERIES_GROUP_KEYWORDS.any { lower.contains(it) },
                movie = MOVIE_GROUP_KEYWORDS.any { lower.contains(it) },
            )
        }
    }

    fun classify(entry: M3uEntry): ContentType {
        // Explicit hints.
        val explicit = (entry.attributes["tvg-type"] ?: entry.attributes["type"])?.lowercase()
        if (explicit != null) {
            if (explicit.contains("movie") || explicit == "vod") return ContentType.MOVIE
            if (explicit.contains("series")) return ContentType.SERIES
            if (explicit.contains("live")) return ContentType.LIVE
        }

        // Xtream style paths.
        val url = entry.url
        if (url.contains("/series/")) return ContentType.SERIES
        if (url.contains("/movie/") || url.contains("/movies/")) return ContentType.MOVIE
        if (url.contains("/live/")) return ContentType.LIVE

        val ext = fileExtension(url)
        val group = hints(entry.groupTitle)

        if (ext in VIDEO_EXTENSIONS) {
            return if (group.series || TitleParser.parseEpisode(entry.title) != null) ContentType.SERIES else ContentType.MOVIE
        }
        // Streams (m3u8/ts/extensionless): live unless the group clearly says otherwise.
        if (ext in STREAM_EXTENSIONS) {
            if (group.series && TitleParser.parseEpisode(entry.title) != null) return ContentType.SERIES
            if (group.movie && entry.duration > 0) return ContentType.MOVIE
            return ContentType.LIVE
        }
        if (group.series && TitleParser.parseEpisode(entry.title) != null) return ContentType.SERIES
        if (group.movie) return ContentType.MOVIE
        return ContentType.LIVE
    }

    companion object {
        internal val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "m4v", "wmv", "flv", "webm", "mpg", "mpeg", "divx", "ogv")
        internal val STREAM_EXTENSIONS = setOf("m3u8", "ts", "", "php", "mpd")
        private val MOVIE_GROUP_KEYWORDS = listOf("vod", "movie", "film", "cinema", "cinéma", "peliculas", "películas", "filme")
        private val SERIES_GROUP_KEYWORDS =
            listOf("series", "série", "serie", "tv show", "tvshow", "shows", "saison", "season", "temporada", "staffel")

        /** One-off classification (no group cache). */
        fun classify(entry: M3uEntry): ContentType = ContentClassifier().classify(entry)

        /** Lowercased extension of the URL path (`"m3u8"`), ignoring query and fragment; `""` when none or longer than 5. */
        fun fileExtension(url: String): String {
            var end = url.length
            for (i in url.indices) {
                if (url[i] == '?' || url[i] == '#') {
                    end = i
                    break
                }
            }
            var index = end - 1
            while (index >= 0) {
                val c = url[index]
                if (c == '.') {
                    val length = end - index - 1
                    return if (length > 5) "" else url.substring(index + 1, end).lowercase()
                }
                if (c == '/') return ""
                index--
            }
            return ""
        }
    }
}
