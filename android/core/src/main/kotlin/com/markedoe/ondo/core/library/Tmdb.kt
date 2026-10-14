package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.TextNormalizer
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.Series
import com.markedoe.ondo.core.net.HttpClient
import com.markedoe.ondo.core.net.Urls
import com.markedoe.ondo.core.parsing.TitleParser
import kotlin.math.abs
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One entry of TMDb's trending list. */
data class TmdbItem(
    val id: Int,
    val mediaType: MediaType,
    val title: String,
    val originalTitle: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val overview: String? = null,
    val year: Int? = null,
    val voteAverage: Double? = null,
) {
    enum class MediaType { MOVIE, TV, PERSON, UNKNOWN }

    val posterUrl: String?
        get() = posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }?.takeIf(Urls::isValidAbsolute)

    val backdropUrl: String?
        get() = backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" }?.takeIf(Urls::isValidAbsolute)

    companion object {
        /** `null` when the item has no integer `id` (the whole page is then rejected, as on Apple platforms). */
        internal fun from(json: JsonElement): TmdbItem? {
            val o = json as? JsonObject ?: return null
            val id = (o["id"] as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.content?.toIntOrNull() ?: return null
            val mediaType = when (string(o["media_type"])) {
                "movie" -> MediaType.MOVIE
                "tv" -> MediaType.TV
                "person" -> MediaType.PERSON
                else -> MediaType.UNKNOWN
            }
            val date = string(o["release_date"]) ?: string(o["first_air_date"])
            return TmdbItem(
                id = id,
                mediaType = mediaType,
                title = string(o["title"]) ?: string(o["name"]) ?: "",
                originalTitle = string(o["original_title"]) ?: string(o["original_name"]),
                posterPath = string(o["poster_path"]),
                backdropPath = string(o["backdrop_path"]),
                overview = string(o["overview"]),
                year = date?.take(4)?.toIntOrNull(),
                voteAverage = (o["vote_average"] as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.content?.toDoubleOrNull(),
            )
        }

        private fun string(element: JsonElement?): String? =
            (element as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

/** Minimal TMDb client. Accepts a v3 API key or a v4 read access token (longer than 40 characters). */
class TmdbClient(
    credential: String,
    private val http: HttpClient,
    val language: String = "en-US",
) {
    val credential: String = credential.trim()

    private val usesBearerToken: Boolean
        get() = credential.length > 40

    /** Trending movies and series (people are filtered out). */
    suspend fun trending(window: String = "week"): List<TmdbItem> {
        val items = mutableListOf("language" to language)
        if (!usesBearerToken) items.add("api_key" to credential)
        val url = "https://api.themoviedb.org/3/trending/all/$window?" + Urls.query(items)
        val headers = if (usesBearerToken) {
            mapOf("Authorization" to "Bearer $credential", "Accept" to "application/json")
        } else {
            mapOf("Accept" to "application/json")
        }
        val page = Json.parseToJsonElement(http.get(url, headers).decodeToString()) as? JsonObject
            ?: throw SerializationException("Unexpected TMDb response")
        val results = page["results"] as? JsonArray ?: throw SerializationException("TMDb response without results")
        return results
            .map { TmdbItem.from(it) ?: throw SerializationException("TMDb item without id") }
            .filter { it.mediaType == TmdbItem.MediaType.MOVIE || it.mediaType == TmdbItem.MediaType.TV }
    }
}

/** A trending title found in the user's catalogue. */
sealed interface TrendingMatch {
    val trending: TmdbItem
    val id: String

    data class MovieMatch(val movie: Movie, override val trending: TmdbItem) : TrendingMatch {
        override val id: String
            get() = "m-" + movie.id
    }

    data class SeriesMatch(val series: Series, override val trending: TmdbItem) : TrendingMatch {
        override val id: String
            get() = "s-" + series.id
    }
}

/** Finds which trending titles exist in the user's catalogue (`"FR - Sandstorm (2021) 4K"` <-> `"Sandstorm"`). */
object CatalogueMatcher {
    private val bracketed = Regex("""\[[^\]]*]""")
    private val parenthesized = Regex("""\([^)]*\)""")

    internal fun key(title: String): String {
        // Remove bracketed tags: [MULTI], (VOSTFR)…
        var name = title.replace(bracketed, " ")
        name = TitleParser.splitYear(name).name
        name = name.replace(parenthesized, " ")
        return TextNormalizer.channelMatchKey(name)
    }

    /** Matches in trending order; a catalogue item is used once. Movies must be within one year when both are known. */
    fun match(trending: List<TmdbItem>, movies: List<Movie>, series: List<Series>): List<TrendingMatch> {
        val movieIndex = HashMap<String, MutableList<Movie>>()
        for (movie in movies) movieIndex.getOrPut(key(movie.name)) { ArrayList() }.add(movie)
        val seriesIndex = HashMap<String, Series>()
        for (show in series) seriesIndex.putIfAbsent(key(show.name), show)

        val result = ArrayList<TrendingMatch>()
        val used = HashSet<String>()
        for (item in trending) {
            val keys = listOfNotNull(item.title, item.originalTitle).map(::key).filter { it.isNotEmpty() }.toCollection(LinkedHashSet())
            when (item.mediaType) {
                TmdbItem.MediaType.MOVIE -> {
                    val best = keys.flatMap { movieIndex[it].orEmpty() }.firstOrNull { movie ->
                        val a = movie.year
                        val b = item.year
                        a == null || b == null || abs(a - b) <= 1
                    }
                    if (best != null && used.add("m" + best.id)) result.add(TrendingMatch.MovieMatch(best, item))
                }

                TmdbItem.MediaType.TV -> {
                    val show = keys.firstNotNullOfOrNull { seriesIndex[it] }
                    if (show != null && used.add("s" + show.id)) result.add(TrendingMatch.SeriesMatch(show, item))
                }

                else -> Unit
            }
        }
        return result
    }
}
