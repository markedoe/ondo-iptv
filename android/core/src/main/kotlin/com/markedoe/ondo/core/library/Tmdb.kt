package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.TextNormalizer
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.Series
import com.markedoe.ondo.core.net.HttpClient
import com.markedoe.ondo.core.net.HttpException
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

    /** GET on the v3 API: the key goes in the query, a v4 token in the Authorization header (never in the address). */
    private suspend fun get(path: String, query: List<Pair<String, String>> = emptyList()): JsonElement {
        val items = query + ("language" to language) + if (usesBearerToken) emptyList() else listOf("api_key" to credential)
        val url = "https://api.themoviedb.org/3/$path?" + Urls.query(items)
        val headers = if (usesBearerToken) {
            mapOf("Authorization" to "Bearer $credential", "Accept" to "application/json")
        } else {
            mapOf("Accept" to "application/json")
        }
        return try {
            Json.parseToJsonElement(http.get(url, headers).decodeToString())
        } catch (e: IllegalArgumentException) {
            throw SerializationException("Not a TMDb answer: ${e.message}")
        }
    }

    /**
     * Asks TMDb whether it accepts the key or token (`/authentication`), before it is saved: false when TMDb refuses it
     * (401, or its own `{"success": false}`); a network failure, or an answer that is not TMDb's, throws.
     */
    suspend fun isAccepted(): Boolean {
        val answer = try {
            get("authentication")
        } catch (e: HttpException.BadStatus) {
            if (e.code == 401) return false
            throw e
        }
        val success = ((answer as? JsonObject)?.get("success") as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }
            ?: throw SerializationException("Not a TMDb answer")
        return success.content.toBooleanStrictOrNull() ?: throw SerializationException("Not a TMDb answer")
    }

    /** Trending movies and series (people are filtered out). */
    suspend fun trending(window: String = "week"): List<TmdbItem> =
        results(get("trending/all/$window")).filter { it.mediaType == TmdbItem.MediaType.MOVIE || it.mediaType == TmdbItem.MediaType.TV }

    /**
     * The backdrop TMDb has for a movie or a series whose provider gave none that loads: by its TMDb id when the provider
     * gave one, otherwise the search result with the same title (and a year one apart at most, when known). Null rather
     * than another title's picture.
     */
    suspend fun backdropUrl(series: Boolean, tmdbId: String?, title: String, year: Int?): String? {
        val kind = if (series) "tv" else "movie"
        tmdbId?.trim()?.toIntOrNull()?.let { id ->
            runCatching { TmdbItem.from(get("$kind/$id"))?.backdropUrl }.getOrNull()?.let { return it }
        }
        return sameTitle(kind, title, year).firstNotNullOfOrNull { it.backdropUrl }
    }

    /** TMDb's search results with this title (and a year one apart at most, when known), in TMDb's order. */
    private suspend fun sameTitle(kind: String, title: String, year: Int?): Sequence<TmdbItem> {
        val key = CatalogueMatcher.key(title)
        if (key.isEmpty()) return emptySequence()
        return results(get("search/$kind", listOf("query" to CatalogueMatcher.searchQuery(title))))
            .asSequence()
            .filter { item ->
                listOfNotNull(item.title, item.originalTitle).map(CatalogueMatcher::key).contains(key) &&
                    (year == null || item.year == null || CatalogueMatcher.closeYears(year, item.year))
            }
    }

    /**
     * The cast and the directors TMDb lists for a movie or a series, with their photos: by the TMDb id the provider
     * gave, else the first search result with the same title (and a year one apart at most, when known). Empty rather
     * than another title's people.
     */
    suspend fun cast(series: Boolean, tmdbId: String?, title: String, year: Int?): List<TmdbPerson> {
        val kind = if (series) "tv" else "movie"
        tmdbId?.trim()?.toIntOrNull()?.let { id ->
            runCatching { castOf(kind, id) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        val id = sameTitle(kind, title, year).firstOrNull()?.id ?: return emptyList()
        return castOf(kind, id)
    }

    private suspend fun castOf(kind: String, id: Int): List<TmdbPerson> {
        val credits = get("$kind/$id/credits") as? JsonObject ?: throw SerializationException("Not a TMDb answer")
        fun part(name: String) = (credits[name] as? JsonArray).orEmpty()
        val directors = part("crew").filter { ((it as? JsonObject)?.get("job") as? JsonPrimitive)?.content == "Director" }
        return (directors + part("cast")).mapNotNull { TmdbPerson.from(it) }.filter { it.name.isNotBlank() }
    }

    /** The people TMDb finds for a search (actors, directors…), the most popular first. */
    suspend fun people(query: String): List<TmdbPerson> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        return persons(get("search/person", listOf("query" to trimmed))).sortedByDescending { it.popularity }
    }

    /** The person TMDb knows best by this name (its most popular match), if any. */
    suspend fun person(name: String): TmdbPerson? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        return persons(get("search/person", listOf("query" to trimmed))).maxByOrNull { it.popularity }
    }

    /** Movies and series a person played in or worked on (directing, writing…), newest first, each once. */
    suspend fun credits(personId: Int): List<TmdbItem> {
        val credits = get("person/$personId/combined_credits") as? JsonObject ?: throw SerializationException("Not a TMDb answer")
        fun part(name: String) = (credits[name] as? JsonArray)?.map { TmdbItem.from(it) ?: throw SerializationException("TMDb item without id") }.orEmpty()
        val seen = HashSet<String>()
        return (part("cast") + part("crew"))
            .filter { (it.mediaType == TmdbItem.MediaType.MOVIE || it.mediaType == TmdbItem.MediaType.TV) && seen.add("${it.mediaType}-${it.id}") }
            .sortedByDescending { it.year ?: Int.MIN_VALUE }
    }

    private fun results(page: JsonElement): List<TmdbItem> {
        val results = (page as? JsonObject)?.get("results") as? JsonArray ?: throw SerializationException("TMDb response without results")
        return results.map { TmdbItem.from(it) ?: throw SerializationException("TMDb item without id") }
    }

    private fun persons(page: JsonElement): List<TmdbPerson> {
        val results = (page as? JsonObject)?.get("results") as? JsonArray ?: throw SerializationException("TMDb response without results")
        return results.map { TmdbPerson.from(it) ?: throw SerializationException("TMDb person without id") }
    }
}

/** A person on TMDb (actor, director…). */
data class TmdbPerson(
    val id: Int,
    val name: String,
    /** "Acting", "Directing"… */
    val department: String? = null,
    val profilePath: String? = null,
    val popularity: Double = 0.0,
) {
    val profileUrl: String?
        get() = profilePath?.let { "https://image.tmdb.org/t/p/w185$it" }?.takeIf(Urls::isValidAbsolute)

    companion object {
        internal fun from(json: JsonElement): TmdbPerson? {
            val o = json as? JsonObject ?: return null
            val id = (o["id"] as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.content?.toIntOrNull() ?: return null
            fun string(key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            return TmdbPerson(
                id = id,
                name = string("name").orEmpty(),
                department = string("known_for_department"),
                profilePath = string("profile_path"),
                popularity = (o["popularity"] as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.content?.toDoubleOrNull() ?: 0.0,
            )
        }
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

    internal fun key(title: String): String = words(title).joinToString("")

    /** The words to search a title with: `"FR - Sandstorm (2021) 4K"` -> `"sandstorm"`. */
    internal fun searchQuery(title: String): String = words(title).joinToString(" ")

    /** Release years one apart at most (release dates differ between countries), however absurd the one a panel sent. */
    internal fun closeYears(a: Int, b: Int): Boolean = abs(a.toLong() - b.toLong()) <= 1

    private fun words(title: String): List<String> {
        // Remove bracketed tags: [MULTI], (VOSTFR)…
        var name = title.replace(bracketed, " ")
        name = TitleParser.splitYear(name).name
        name = name.replace(parenthesized, " ")
        return TextNormalizer.channelMatchWords(name)
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
                        a == null || b == null || closeYears(a, b)
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
