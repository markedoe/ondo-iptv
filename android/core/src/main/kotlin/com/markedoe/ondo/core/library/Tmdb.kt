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

/** TMDb's rating of a title: its viewers' average vote out of 10, and how many voted. */
data class TmdbRating(val average: Double, val votes: Int)

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
    val voteCount: Int? = null,
) {
    enum class MediaType { MOVIE, TV, PERSON, UNKNOWN }

    /** The average with the number of votes it comes from; null when nobody voted. */
    val rating: TmdbRating?
        get() {
            val average = voteAverage?.takeIf { it > 0 } ?: return null
            val votes = voteCount?.takeIf { it > 0 } ?: return null
            return TmdbRating(average, votes)
        }

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
                voteCount = (o["vote_count"] as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.content?.toIntOrNull(),
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
     * Everything the app shows of a title from TMDb, in one call: its backdrop, trailer ([TmdbVideo.best]), rating, directors
     * and first billed cast. By the TMDb id the provider gave, else the first search result with the same title (and a year
     * one apart at most, when known). Null when TMDb has no such title; a failed request throws.
     */
    suspend fun titleInfo(series: Boolean, tmdbId: String?, title: String, year: Int?): TmdbTitleInfo? {
        val kind = if (series) "tv" else "movie"
        tmdbId?.trim()?.toIntOrNull()?.takeIf { it > 0 }?.let { id ->
            try {
                return infoOf(kind, id)
            } catch (e: HttpException.BadStatus) {
                // An id TMDb does not know (a provider's mistake): searched by title instead.
                if (e.code != 404) throw e
            } catch (e: SerializationException) {
                // Not a title's page: searched by title instead.
            }
        }
        val match = sameTitle(kind, title, year).firstOrNull() ?: return null
        return infoOf(kind, match.id).let { it.copy(backdropUrl = it.backdropUrl ?: match.backdropUrl, rating = it.rating ?: match.rating) }
    }

    private suspend fun infoOf(kind: String, id: Int): TmdbTitleInfo {
        val viewer = language.substringBefore('-').lowercase()
        // Videos in the viewer's language, in English, and without a language.
        val languages = listOf(viewer, "en", "null").distinct().joinToString(",")
        // Credits, videos and the titles TMDb recommends with it: one request.
        val details = get("$kind/$id", listOf("append_to_response" to "credits,videos,recommendations", "include_video_language" to languages)) as? JsonObject
            ?: throw SerializationException("Not a TMDb answer")
        val item = TmdbItem.from(details) ?: throw SerializationException("TMDb title without id")
        val credits = details["credits"] as? JsonObject
        fun part(name: String) = (credits?.get(name) as? JsonArray).orEmpty()
        val directors = part("crew").filter { ((it as? JsonObject)?.get("job") as? JsonPrimitive)?.content == "Director" }
            .mapNotNull { TmdbPerson.from(it) }.filter { it.name.isNotBlank() }.distinctBy { it.id }
        val cast = part("cast").mapNotNull { TmdbPerson.from(it) }.filter { it.name.isNotBlank() }.distinctBy { it.id }.take(TmdbTitleInfo.CAST_LIMIT)
        val videos = ((details["videos"] as? JsonObject)?.get("results") as? JsonArray).orEmpty().mapNotNull { TmdbVideo.from(it) }
        val genres = (details["genres"] as? JsonArray).orEmpty().mapNotNull { TmdbGenre.from(it) }
        val ownType = if (kind == "tv") TmdbItem.MediaType.TV else TmdbItem.MediaType.MOVIE
        // A recommendation without its type is of the title's own.
        val related = (((details["recommendations"] as? JsonObject)?.get("results")) as? JsonArray).orEmpty()
            .mapNotNull { TmdbItem.from(it)?.let { found -> if (found.mediaType == TmdbItem.MediaType.UNKNOWN) found.copy(mediaType = ownType) else found } }
            .filter { (it.mediaType == TmdbItem.MediaType.MOVIE || it.mediaType == TmdbItem.MediaType.TV) && it.title.isNotBlank() }
            .take(TmdbTitleInfo.RELATED_LIMIT)
        return TmdbTitleInfo(item.id, item.backdropUrl, TmdbVideo.best(videos, viewer)?.key, item.rating, directors, cast, genres, related)
    }

    /** TMDb's genres of movies or series, in the viewer's language. */
    suspend fun genres(series: Boolean): List<TmdbGenre> {
        val page = get(if (series) "genre/tv/list" else "genre/movie/list") as? JsonObject ?: throw SerializationException("Not a TMDb answer")
        return (page["genres"] as? JsonArray).orEmpty().mapNotNull { TmdbGenre.from(it) }
    }

    /** The most popular movies or series of a genre, a page (20) at a time. */
    suspend fun discover(series: Boolean, genreId: Int, page: Int): List<TmdbItem> {
        val type = if (series) TmdbItem.MediaType.TV else TmdbItem.MediaType.MOVIE
        return results(get(if (series) "discover/tv" else "discover/movie", listOf("with_genres" to genreId.toString(), "sort_by" to "popularity.desc", "page" to page.toString())))
            .map { if (it.mediaType == TmdbItem.MediaType.UNKNOWN) it.copy(mediaType = type) else it }
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

/** What TMDb says about a title ([TmdbClient.titleInfo]). */
data class TmdbTitleInfo(
    val tmdbId: Int,
    val backdropUrl: String?,
    /** The YouTube key of its trailer. */
    val trailerKey: String?,
    val rating: TmdbRating?,
    val directors: List<TmdbPerson>,
    /** The first billed, [CAST_LIMIT] at most. */
    val cast: List<TmdbPerson>,
    /** Its genres, named in the viewer's language. */
    val genres: List<TmdbGenre> = emptyList(),
    /** The titles TMDb recommends with it, [RELATED_LIMIT] at most. */
    val related: List<TmdbItem> = emptyList(),
) {
    companion object {
        const val CAST_LIMIT = 20
        const val RELATED_LIMIT = 20
    }
}

/** A genre on TMDb: its id, and its name in the viewer's language. */
data class TmdbGenre(val id: Int, val name: String) {
    companion object {
        internal fun from(json: JsonElement): TmdbGenre? {
            val o = json as? JsonObject ?: return null
            val id = (o["id"] as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.content?.toIntOrNull() ?: return null
            val name = (o["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
            return if (name.isEmpty()) null else TmdbGenre(id, name)
        }
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

    /** A provider's tag between bars, before or after the name: "|FR-4K DV| MobLand", "MobLand |MULTI|". */
    private val barred = Regex("""\|[^|]{1,24}\|""")
    private val parenthesized = Regex("""\([^)]*\)""")

    internal fun key(title: String): String = words(title).joinToString("")

    /** The words to search a title with: `"FR - Sandstorm (2021) 4K"` -> `"sandstorm"`. */
    internal fun searchQuery(title: String): String = words(title).joinToString(" ")

    /** Release years one apart at most (release dates differ between countries), however absurd the one a panel sent. */
    internal fun closeYears(a: Int, b: Int): Boolean = abs(a.toLong() - b.toLong()) <= 1

    /**
     * Words after a title that name a copy's language or version, not the film ("Sandstorm VF", "Sandstorm VOSTFR",
     * "Sandstorm EN", "EN - Sandstorm"). Only at either end, and never the whole title ("Vost" stays "Vost").
     */
    private val versionWords = setOf(
        "vf", "vff", "vfq", "vfi", "vo", "vost", "vostfr", "vosta", "truefrench", "french", "english", "subbed", "dubbed", "dub",
        "sub", "dual", "en", "eng", "fr", "es", "esp", "de", "ger", "ita", "pt", "br", "ar", "tr", "nl", "pl",
    )

    /**
     * The film a catalogue title stands for, whatever its copy: `"FR - Sandstorm (2021) 4K"`, `"Sandstorm VOSTFR"` and
     * `"Sandstorm [MULTI]"` -> `"sandstorm"`. Empty when nothing is left of the title.
     */
    fun filmKey(title: String): String {
        val words = words(title).toMutableList()
        while (words.size > 1 && words.last() in versionWords) words.removeAt(words.size - 1)
        while (words.size > 1 && words.first() in versionWords) words.removeAt(0)
        return words.joinToString("")
    }

    /**
     * Whether two catalogue titles are copies of one film (another language, another quality): the same [filmKey] and
     * release years one apart at most, when both are known (from the field, else from the title). A remake of another
     * year is another film.
     */
    fun isSameFilm(a: String, aYear: Int?, b: String, bYear: Int?): Boolean {
        val key = filmKey(a)
        if (key.isEmpty() || key != filmKey(b)) return false
        val first = aYear ?: TitleParser.splitYear(a).year
        val second = bYear ?: TitleParser.splitYear(b).year
        return first == null || second == null || closeYears(first, second)
    }

    private fun words(title: String): List<String> {
        // Remove bracketed tags: [MULTI], (VOSTFR)…
        var name = title.replace(bracketed, " ").replace(barred, " ")
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

/** A video TMDb lists for a movie or a series (trailers, teasers, clips…). */
data class TmdbVideo(
    val key: String,
    val site: String,
    val type: String,
    val official: Boolean = false,
    /** ISO 639-1 language of the video, when TMDb gives one. */
    val language: String? = null,
) {
    companion object {
        private val YOUTUBE_KEY = Regex("[A-Za-z0-9_-]{11}")

        internal fun from(json: JsonElement): TmdbVideo? {
            val o = json as? JsonObject ?: return null
            fun string(name: String) = (o[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
            return TmdbVideo(
                key = string("key") ?: return null,
                site = string("site").orEmpty(),
                type = string("type").orEmpty(),
                official = (o["official"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false,
                language = string("iso_639_1")?.lowercase(),
            )
        }

        /**
         * The trailer to show: YouTube only, a trailer before a teaser, in the viewer's [language] before English before
         * the others, an official one first. Null when there is no trailer or teaser on YouTube.
         */
        fun best(videos: List<TmdbVideo>, language: String): TmdbVideo? = videos
            .filter { it.site.equals("YouTube", ignoreCase = true) && it.type in TRAILER_TYPES && YOUTUBE_KEY.matches(it.key) }
            .sortedWith(
                compareBy<TmdbVideo> { if (it.type == "Trailer") 0 else 1 }
                    .thenBy { when (it.language) { language -> 0; "en" -> 1; else -> 2 } }
                    .thenBy { if (it.official) 0 else 1 },
            )
            .firstOrNull()

        private val TRAILER_TYPES = setOf("Trailer", "Teaser")
    }
}
