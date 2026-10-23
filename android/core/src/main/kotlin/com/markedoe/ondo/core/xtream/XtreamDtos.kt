package com.markedoe.ondo.core.xtream

import com.markedoe.ondo.core.isNewline
import com.markedoe.ondo.core.nilIfBlank
import com.markedoe.ondo.core.net.Urls
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

// Raw payloads of `player_api.php`. Every field is decoded leniently; `from` returns null when a required
// field is missing (the Swift initialisers throw), so lossy lists drop the element.

internal data class XtreamUserInfo(
    val username: String?,
    val auth: Boolean,
    val status: String?,
    val expDate: Long?,
    val createdAt: Long?,
    val isTrial: Boolean,
    val activeConnections: Int?,
    val maxConnections: Int?,
    val allowedOutputFormats: List<String>,
    val message: String?,
) {
    companion object {
        fun from(json: JsonElement?): XtreamUserInfo? {
            val o = json as? JsonObject ?: return null
            return XtreamUserInfo(
                username = o["username"].lenientString(),
                auth = o["auth"].lenientBool() ?: false,
                status = o["status"].lenientString(),
                expDate = o["exp_date"].lenientEpochSeconds(),
                createdAt = o["created_at"].lenientEpochSeconds(),
                isTrial = o["is_trial"].lenientBool() ?: false,
                activeConnections = o["active_cons"].lenientInt(),
                maxConnections = o["max_connections"].lenientInt(),
                allowedOutputFormats = o["allowed_output_formats"].lenientStringArray(),
                message = o["message"].lenientString(),
            )
        }
    }
}

internal data class XtreamServerInfo(val url: String?, val timezone: String?) {
    companion object {
        fun from(json: JsonElement?): XtreamServerInfo? {
            val o = json as? JsonObject ?: return null
            return XtreamServerInfo(url = o["url"].lenientString(), timezone = o["timezone"].lenientString())
        }
    }
}

internal data class XtreamCategoryDto(val id: String, val name: String) {
    companion object {
        fun from(json: JsonElement): XtreamCategoryDto? {
            val o = json as? JsonObject ?: return null
            val id = o["category_id"].lenientString() ?: return null
            return XtreamCategoryDto(id, o["category_name"].lenientString() ?: "Uncategorized")
        }
    }
}

internal data class XtreamLiveStreamDto(
    val streamId: Int,
    val number: Int?,
    val name: String,
    val icon: String?,
    val epgChannelId: String?,
    val categoryId: String?,
    val tvArchive: Boolean,
    val tvArchiveDuration: Int?,
) {
    companion object {
        fun from(json: JsonElement): XtreamLiveStreamDto? {
            val o = json as? JsonObject ?: return null
            val streamId = o["stream_id"].lenientInt() ?: return null
            return XtreamLiveStreamDto(
                streamId = streamId,
                number = o["num"].lenientInt(),
                name = o["name"].lenientString()?.trim() ?: "Channel $streamId",
                icon = o["stream_icon"].lenientString(),
                epgChannelId = o["epg_channel_id"].lenientString()?.nilIfBlank(),
                categoryId = o["category_id"].lenientString() ?: firstIntOfArray(o["category_ids"])?.toString(),
                tvArchive = o["tv_archive"].lenientBool() ?: false,
                tvArchiveDuration = o["tv_archive_duration"].lenientInt(),
            )
        }

        /** `[Int]` decoding: every element must be an integer. */
        private fun firstIntOfArray(json: JsonElement?): Long? {
            val array = json as? JsonArray ?: return null
            val values = array.map { element ->
                val p = element as? kotlinx.serialization.json.JsonPrimitive
                if (p == null || p is JsonNull || p.isString) return null
                p.content.toLongOrNull() ?: return null
            }
            return values.firstOrNull()
        }
    }
}

internal data class XtreamVodStreamDto(
    val streamId: Int,
    val name: String,
    val icon: String?,
    val categoryId: String?,
    val rating: Double?,
    val rating5: Double?,
    val added: Long?,
    val containerExtension: String?,
    val year: Int?,
    val plot: String?,
) {
    companion object {
        fun from(json: JsonElement): XtreamVodStreamDto? {
            val o = json as? JsonObject ?: return null
            val streamId = o["stream_id"].lenientInt() ?: return null
            return XtreamVodStreamDto(
                streamId = streamId,
                name = o["name"].lenientString()?.trim() ?: "Movie $streamId",
                icon = o["stream_icon"].lenientString(),
                categoryId = o["category_id"].lenientString(),
                rating = o["rating"].lenientDouble(),
                rating5 = o["rating_5based"].lenientDouble(),
                added = o["added"].lenientEpochSeconds(),
                containerExtension = o["container_extension"].lenientString()?.nilIfBlank(),
                year = o["year"].lenientInt(),
                plot = o["plot"].lenientString()?.nilIfBlank(),
            )
        }
    }
}

internal data class XtreamSeriesDto(
    val seriesId: Int,
    val name: String,
    val cover: String?,
    val plot: String?,
    val genre: String?,
    val cast: String?,
    val director: String?,
    val releaseDate: String?,
    val rating: Double?,
    val categoryId: String?,
    val lastModified: Long?,
    val year: Int?,
) {
    companion object {
        fun from(json: JsonElement): XtreamSeriesDto? {
            val o = json as? JsonObject ?: return null
            val seriesId = o["series_id"].lenientInt() ?: return null
            val releaseDate = (o["releaseDate"].lenientString() ?: o["release_date"].lenientString())?.nilIfBlank()
            return XtreamSeriesDto(
                seriesId = seriesId,
                name = o["name"].lenientString()?.trim() ?: "Series $seriesId",
                cover = o["cover"].lenientString(),
                plot = o["plot"].lenientString()?.nilIfBlank(),
                genre = o["genre"].lenientString()?.nilIfBlank(),
                cast = (o["cast"].lenientString() ?: o["actors"].lenientString())?.nilIfBlank(),
                director = o["director"].lenientString()?.nilIfBlank(),
                releaseDate = releaseDate,
                rating = o["rating"].lenientDouble(),
                categoryId = o["category_id"].lenientString(),
                lastModified = o["last_modified"].lenientEpochSeconds(),
                year = o["year"].lenientInt() ?: releaseDate?.take(4)?.toIntOrNull(),
            )
        }
    }
}

/** `info` object shared by `get_vod_info` and `get_series_info`. */
internal data class XtreamInfoDto(
    val name: String? = null,
    val originalName: String? = null,
    val plot: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    val durationSecs: Int? = null,
    val rating: Double? = null,
    val movieImage: String? = null,
    val cover: String? = null,
    val backdrops: List<String> = emptyList(),
    val youtubeTrailer: String? = null,
    val tmdbId: String? = null,
) {
    companion object {
        /** `null` when the key is missing or `null`; an empty info for `info: []`. */
        fun fromOptional(json: JsonElement?): XtreamInfoDto? = if (json == null || json is JsonNull) null else from(json)

        fun from(json: JsonElement): XtreamInfoDto {
            val o = json as? JsonObject ?: return XtreamInfoDto()
            return XtreamInfoDto(
                name = o["name"].lenientString()?.nilIfBlank(),
                originalName = o["o_name"].lenientString()?.nilIfBlank(),
                plot = (o["plot"].lenientString() ?: o["description"].lenientString())?.nilIfBlank(),
                cast = (o["cast"].lenientString() ?: o["actors"].lenientString())?.nilIfBlank(),
                director = o["director"].lenientString()?.nilIfBlank(),
                genre = o["genre"].lenientString()?.nilIfBlank(),
                releaseDate = (
                    o["releasedate"].lenientString() ?: o["releaseDate"].lenientString() ?: o["release_date"].lenientString()
                    )?.nilIfBlank(),
                durationSecs = o["duration_secs"].lenientInt(),
                rating = o["rating"].lenientDouble(),
                movieImage = o["movie_image"].lenientString()?.nilIfBlank(),
                cover = (o["cover"].lenientString() ?: o["cover_big"].lenientString())?.nilIfBlank(),
                backdrops = o["backdrop_path"].lenientStringArray().filter { it.isNotEmpty() },
                youtubeTrailer = o["youtube_trailer"].lenientString()?.nilIfBlank(),
                tmdbId = (o["tmdb_id"].lenientString() ?: o["tmdb"].lenientString())?.nilIfBlank(),
            )
        }
    }
}

internal data class XtreamVodInfoResponse(val info: XtreamInfoDto?, val movieData: XtreamVodStreamDto?) {
    companion object {
        fun from(json: JsonElement): XtreamVodInfoResponse? {
            val o = json as? JsonObject ?: return null
            return XtreamVodInfoResponse(
                info = XtreamInfoDto.fromOptional(o["info"]),
                movieData = o["movie_data"]?.let { XtreamVodStreamDto.from(it) },
            )
        }
    }
}

internal data class XtreamSeasonDto(val seasonNumber: Int, val name: String?, val overview: String?, val cover: String?) {
    companion object {
        fun from(json: JsonElement): XtreamSeasonDto? {
            val o = json as? JsonObject ?: return null
            val number = o["season_number"].lenientInt() ?: return null
            return XtreamSeasonDto(
                seasonNumber = number,
                name = o["name"].lenientString()?.nilIfBlank(),
                overview = o["overview"].lenientString()?.nilIfBlank(),
                cover = (o["cover_big"].lenientString() ?: o["cover"].lenientString())?.nilIfBlank(),
            )
        }
    }
}

internal data class XtreamEpisodeDto(
    val id: String,
    val episodeNumber: Int?,
    val title: String?,
    val containerExtension: String?,
    val season: Int?,
    val info: XtreamInfoDto?,
) {
    companion object {
        fun from(json: JsonElement): XtreamEpisodeDto? {
            val o = json as? JsonObject ?: return null
            val id = o["id"].lenientString() ?: return null
            return XtreamEpisodeDto(
                id = id,
                episodeNumber = o["episode_num"].lenientInt(),
                title = o["title"].lenientString()?.nilIfBlank(),
                containerExtension = o["container_extension"].lenientString()?.nilIfBlank(),
                season = o["season"].lenientInt(),
                info = XtreamInfoDto.fromOptional(o["info"]),
            )
        }
    }
}

internal data class XtreamSeriesInfoResponse(
    val seasons: List<XtreamSeasonDto>,
    val info: XtreamInfoDto?,
    /** Season number -> episodes, in the order the panel listed them. */
    val episodes: Map<Int, List<XtreamEpisodeDto>>,
) {
    companion object {
        fun from(json: JsonElement): XtreamSeriesInfoResponse? {
            val o = json as? JsonObject ?: return null
            val result = LinkedHashMap<Int, MutableList<XtreamEpisodeDto>>()
            val seen = HashMap<Int, MutableSet<String>>()
            fun add(season: Int, episode: XtreamEpisodeDto) {
                // An episode listed twice in a season is kept once: the app's list shows one row per id.
                if (seen.getOrPut(season) { HashSet() }.add(episode.id)) result.getOrPut(season) { ArrayList() }.add(episode)
            }
            when (val episodes = o["episodes"]) {
                // {"1": [...], "2": [...]}
                is JsonObject -> for ((key, value) in episodes) {
                    val list = value.lossyList(XtreamEpisodeDto::from)
                    val season = key.toIntOrNull() ?: list.firstOrNull()?.season ?: 1
                    for (episode in list) add(season, episode)
                }
                // [[...], [...]]: one array per season.
                is JsonArray -> if (episodes.any { it is JsonArray }) {
                    for (element in episodes) {
                        for (episode in element.lossyList(XtreamEpisodeDto::from)) add(episode.season ?: 1, episode)
                    }
                } else {
                    // [{...}, {...}]: a flat list (the Swift decoder never reached this branch, see README).
                    for (episode in episodes.lossyList(XtreamEpisodeDto::from)) add(episode.season ?: 1, episode)
                }
                else -> Unit
            }
            return XtreamSeriesInfoResponse(
                seasons = o["seasons"].lossyList(XtreamSeasonDto::from),
                info = XtreamInfoDto.fromOptional(o["info"]),
                episodes = result,
            )
        }
    }
}

internal data class XtreamEpgListingDto(
    val title: String,
    val description: String?,
    /** Epoch seconds. */
    val start: Long,
    /** Epoch seconds. */
    val end: Long,
    val channelId: String?,
    val hasArchive: Boolean,
) {
    companion object {
        fun from(json: JsonElement): XtreamEpgListingDto? {
            val o = json as? JsonObject ?: return null
            val start = o["start_timestamp"].lenientEpochSeconds() ?: return null
            val end = o["stop_timestamp"].lenientEpochSeconds() ?: return null
            return XtreamEpgListingDto(
                // As sent: `listings` decodes them once it knows whether the panel uses base64.
                title = o["title"].lenientString() ?: "",
                description = o["description"].lenientString()?.nilIfBlank(),
                start = start,
                end = end,
                channelId = o["channel_id"].lenientString(),
                hasArchive = o["has_archive"].lenientBool() ?: false,
            )
        }

        /**
         * Short EPG listings response: `{"epg_listings": [...]}`. Panels send every title and description in
         * base64, a few send them all as plain text. Deciding for the whole answer keeps a plain "Film" or "Show",
         * which is valid base64 too, from turning into garbage.
         */
        fun listings(json: JsonElement): List<XtreamEpgListingDto>? {
            val o = json as? JsonObject ?: return null
            val listings = o["epg_listings"].lossyList(::from)
            val texts = listings.flatMap { listOf(it.title, it.description.orEmpty()) }.filter { it.isNotEmpty() }
            if (!texts.all { base64Text(it) != null }) return listings
            return listings.map { listing ->
                listing.copy(
                    title = base64Text(listing.title) ?: listing.title,
                    description = listing.description?.let(::base64Text)?.nilIfBlank(),
                )
            }
        }

        /**
         * The text `value` holds in base64, or `null` when it is not base64 of readable UTF-8 text. Strict: a plain
         * title has spaces or punctuation that base64 does not, and garbage decoded from one often has control
         * characters.
         */
        internal fun base64Text(value: String): String? {
            val bytes = Base64Strict.decode(value.trim().filterNot { it.isNewline() }) ?: return null
            val text = Urls.decodeUtf8Strict(bytes)
            if (text.isNullOrEmpty() || text.any { (it < ' ' && it !in "\t\n\r") || it in '\u007F'..'\u009F' }) return null
            return text
        }
    }
}

/**
 * Base64 decoding like Foundation's `Data(base64Encoded:)`: only the alphabet and `=` padding, with a length
 * that is a multiple of 4.
 */
internal object Base64Strict {
    private val values = IntArray(128) { -1 }.also { table ->
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".forEachIndexed { index, c -> table[c.code] = index }
    }

    fun decode(text: String): ByteArray? {
        val sextets = ArrayList<Int>(text.length)
        var padding = 0
        for (c in text) {
            if (c == '=') {
                padding++
                continue
            }
            val value = if (c.code < 128) values[c.code] else -1
            if (value < 0) return null
            if (padding > 0) return null // data after padding
            sextets.add(value)
        }
        if (padding > 2 || (sextets.size + padding) % 4 != 0) return null
        val out = ByteArray(sextets.size * 3 / 4)
        var bits = 0
        var bitCount = 0
        var index = 0
        for (sextet in sextets) {
            bits = (bits shl 6) or sextet
            bitCount += 6
            if (bitCount >= 8) {
                bitCount -= 8
                out[index++] = (bits shr bitCount).toByte()
                bits = bits and ((1 shl bitCount) - 1)
            }
        }
        return if (index == out.size) out else out.copyOf(index)
    }
}
