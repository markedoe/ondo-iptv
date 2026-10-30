package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.TextNormalizer
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.Series
import com.markedoe.ondo.core.parsing.TitleParser

// What TMDb answered about titles, kept on the device between launches (a SQLite file in the app): pages show it at
// once, and TMDb is only asked again for what is missing or old. Its credits are also a local index of people (who
// played in or made which title), and it keeps the image addresses that led nowhere. Everything in it can be fetched
// again: it is a cache. The same keys and lifetimes as on Apple devices.

/**
 * The keys of the store. A title's key does not depend on the playlist, so what TMDb said survives a change of
 * playlist or provider: `movie:603` when the provider gives the title's TMDb id, else the words of its title and its
 * year, `movie:sandstorm:2021` (the words [CatalogueMatcher] matches trending titles with).
 */
object MetadataKey {
    fun kind(series: Boolean): String = if (series) "series" else "movie"

    /** "movie:603", when [id] is a TMDb id. */
    fun tmdb(series: Boolean, id: String?): String? = id?.trim()?.toIntOrNull()?.takeIf { it > 0 }?.let { tmdb(series, it) }

    fun tmdb(series: Boolean, id: Int): String = kind(series) + ":" + id

    /**
     * "movie:sandstorm:2021" for "FR - Sandstorm (2021) 4K"; "movie:sandstorm:" without a year. The year is the list's,
     * else the one in the name. Null for a name without a word.
     */
    fun title(series: Boolean, name: String, year: Int?): String? {
        val words = CatalogueMatcher.key(name)
        if (words.isEmpty()) return null
        val known = year ?: TitleParser.splitYear(name).year
        return kind(series) + ":" + words + ":" + (known?.toString() ?: "")
    }

    /** The keys a title is read under (the first one found wins) and written under: its TMDb id's, then its title's. */
    fun all(series: Boolean, tmdbId: String?, name: String, year: Int?): List<String> = listOfNotNull(tmdb(series, tmdbId), title(series, name, year))

    /** A person's key: the name without case or accents, as the cast fields are matched. */
    fun person(name: String): String = TextNormalizer.normalize(name)
}

/** How long what TMDb said is trusted. An older entry still shows while it is fetched again. */
object MetadataFreshness {
    /** A title TMDb knows. */
    const val FOUND_MS = 30L * 24 * 60 * 60 * 1000

    /** A title TMDb does not know: asked again sooner (TMDb grows, a provider fixes a title). */
    const val NOT_FOUND_MS = 7L * 24 * 60 * 60 * 1000

    /** An image address that led nowhere. */
    const val DEAD_IMAGE_MS = 7L * 24 * 60 * 60 * 1000

    /** A title kept before genres and recommendations were kept with it is asked again. */
    fun isFresh(title: TitleMetadata, now: Long): Boolean =
        !(title.found && title.genres == null) && isFresh(title.fetchedAt, if (title.found) FOUND_MS else NOT_FOUND_MS, now)

    /** Fetched less than [maxAge] ago. A date in the future (the clock was changed) is not trusted. */
    fun isFresh(fetchedAt: Long, maxAge: Long, now: Long): Boolean = now - fetchedAt in 0 until maxAge
}

/** What TMDb said about a movie or a series; [found] false when TMDb answered without such a title. */
data class TitleMetadata(
    val key: String,
    /** "movie" or "series" ([MetadataKey.kind]). */
    val kind: String,
    val tmdbId: Int? = null,
    val backdropUrl: String? = null,
    /** The YouTube id of its trailer. */
    val trailerKey: String? = null,
    val voteAverage: Double? = null,
    val voteCount: Int? = null,
    val fetchedAt: Long,
    val found: Boolean = true,
    /** Its genres on TMDb, in the viewer's language; null when kept before genres were. */
    val genres: List<TmdbGenre>? = null,
    /** The titles TMDb recommends with it, in TMDb's order; null when kept before they were. */
    val related: List<RelatedTitle>? = null,
) {
    /** "TMDb 7.3 (12,345)" needs both: a rating TMDb's users gave, and how many gave it. */
    val rating: TmdbRating?
        get() {
            if (!found) return null
            val average = voteAverage?.takeIf { it > 0 } ?: return null
            val votes = voteCount?.takeIf { it > 0 } ?: return null
            return TmdbRating(average, votes)
        }

    companion object {
        /** TMDb's answer under [key]; a null [info] is "not found". */
        fun of(key: String, series: Boolean, info: TmdbTitleInfo?, fetchedAt: Long) = TitleMetadata(
            key = key,
            kind = MetadataKey.kind(series),
            tmdbId = info?.tmdbId,
            backdropUrl = info?.backdropUrl,
            trailerKey = info?.trailerKey,
            voteAverage = info?.rating?.average,
            voteCount = info?.rating?.votes,
            fetchedAt = fetchedAt,
            found = info != null,
            genres = info?.genres,
            related = info?.related?.map { RelatedTitle(it.mediaType == TmdbItem.MediaType.TV, it.id, it.title, it.year) },
        )
    }
}

/** A title TMDb recommends with another: enough to find it in a playlist ([MetadataTitles.match] of [keys]). */
data class RelatedTitle(val series: Boolean, val tmdbId: Int, val title: String, val year: Int?) {
    val keys: List<String> get() = MetadataKey.all(series, tmdbId.toString(), title, year)

    companion object {
        /** One line per title: "series<TAB>id<TAB>year<TAB>title", as the store keeps them. */
        fun encode(titles: List<RelatedTitle>): String =
            titles.joinToString("\n") { listOf(if (it.series) "tv" else "movie", it.tmdbId, it.year ?: "", it.title.replace('\t', ' ').replace('\n', ' ')).joinToString("\t") }

        fun decode(text: String?): List<RelatedTitle>? = text?.let {
            it.lines().mapNotNull { line ->
                val parts = line.split('\t', limit = 4)
                if (parts.size != 4) return@mapNotNull null
                val id = parts[1].toIntOrNull() ?: return@mapNotNull null
                RelatedTitle(parts[0] == "tv", id, parts[3], parts[2].toIntOrNull())
            }
        }
    }
}

/** Genres as the store keeps them: one "id<TAB>name" per line. */
object GenreText {
    fun encode(genres: List<TmdbGenre>): String = genres.joinToString("\n") { "${it.id}\t${it.name.replace('\t', ' ').replace('\n', ' ')}" }

    fun decode(text: String?): List<TmdbGenre>? = text?.let {
        it.lines().mapNotNull { line ->
            val parts = line.split('\t', limit = 2)
            val id = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            parts.getOrNull(1)?.takeIf(String::isNotBlank)?.let { name -> TmdbGenre(id, name) }
        }
    }
}

/** A director or an actor of a title, in its order. */
data class CreditRecord(
    val personKey: String,
    val name: String,
    val role: Role,
    val position: Int,
    val photoUrl: String? = null,
) {
    enum class Role { DIRECTOR, CAST }

    companion object {
        /** TMDb's directors then its cast, in their order. */
        fun of(info: TmdbTitleInfo): List<CreditRecord> {
            fun records(people: List<TmdbPerson>, role: Role) = people.mapIndexedNotNull { position, person ->
                val key = MetadataKey.person(person.name)
                if (key.isEmpty()) null else CreditRecord(key, person.name, role, position, person.profileUrl)
            }
            return records(info.directors, Role.DIRECTOR) + records(info.cast, Role.CAST)
        }
    }
}

/** A person of the local index, with a photo when TMDb gave one and how many titles credit them. */
data class IndexedPerson(val name: String, val photoUrl: String?, val titleCount: Int)

/** Where [TitleMetadata], credits and dead image addresses are kept. Calls block: they run off the main thread. */
interface MetadataStore {
    /** The titles kept under these keys. */
    fun titles(keys: List<String>): Map<String, TitleMetadata>

    fun save(titles: List<TitleMetadata>)

    /** A title's directors then its cast, in their order. */
    fun credits(titleKey: String): List<CreditRecord>

    /** Replaces the credits kept under each key. */
    fun saveCredits(credits: List<CreditRecord>, titleKeys: List<String>)

    /** The keys of the titles that credit this person. */
    fun titleKeys(personKey: String): List<String>

    /** The titles of a genre (its name, [TextNormalizer.normalize]d), movies or series. */
    fun titlesOfGenre(genre: String, series: Boolean): List<TitleMetadata>

    /** People whose name has every word of [query] starting one of its words, those credited most first. */
    fun people(query: String, limit: Int): List<IndexedPerson>

    /** Image addresses that led nowhere since [since]. */
    fun deadImages(since: Long): Set<String>

    fun markDeadImage(url: String, at: Long)

    fun removeAll()

    companion object {
        /** The words a people search looks for: none under three letters. */
        fun tokens(query: String): List<String>? {
            val normalized = TextNormalizer.normalize(query)
            val tokens = normalized.split(' ').filter { it.isNotEmpty() }
            return if (normalized.length < 3 || tokens.isEmpty()) null else tokens
        }

        /** Whether every token starts one of the words of a person's key. */
        fun matches(personKey: String, tokens: List<String>): Boolean {
            val words = personKey.split(' ')
            return tokens.all { token -> words.any { it.startsWith(token) } }
        }
    }
}

/** A store in memory (tests, or when the file cannot be opened: TMDb is then asked again at each launch). */
class InMemoryMetadataStore : MetadataStore {
    private val titles = HashMap<String, TitleMetadata>()
    private val credits = HashMap<String, List<CreditRecord>>()
    private val dead = HashMap<String, Long>()

    @Synchronized override fun titles(keys: List<String>): Map<String, TitleMetadata> = keys.mapNotNull { key -> titles[key]?.let { key to it } }.toMap()

    @Synchronized override fun save(titles: List<TitleMetadata>) {
        for (title in titles) this.titles[title.key] = title
    }

    @Synchronized override fun credits(titleKey: String): List<CreditRecord> = credits[titleKey].orEmpty()

    @Synchronized override fun saveCredits(credits: List<CreditRecord>, titleKeys: List<String>) {
        for (key in titleKeys) this.credits[key] = credits
    }

    @Synchronized override fun titleKeys(personKey: String): List<String> =
        credits.filterValues { list -> list.any { it.personKey == personKey } }.keys.sorted()

    @Synchronized override fun titlesOfGenre(genre: String, series: Boolean): List<TitleMetadata> =
        titles.values.filter { title -> title.kind == MetadataKey.kind(series) && title.genres.orEmpty().any { TextNormalizer.normalize(it.name) == genre } }

    @Synchronized override fun people(query: String, limit: Int): List<IndexedPerson> {
        val tokens = MetadataStore.tokens(query) ?: return emptyList()
        return credits.entries.flatMap { (title, list) -> list.map { title to it } }
            .filter { MetadataStore.matches(it.second.personKey, tokens) }
            .groupBy { it.second.personKey }
            .map { (_, entries) ->
                val first = entries.first().second
                IndexedPerson(first.name, entries.firstNotNullOfOrNull { it.second.photoUrl }, entries.map { it.first }.distinct().size)
            }
            .sortedWith(compareByDescending<IndexedPerson> { it.titleCount }.thenBy { it.name })
            .take(limit)
    }

    @Synchronized override fun deadImages(since: Long): Set<String> = dead.filterValues { it >= since }.keys

    @Synchronized override fun markDeadImage(url: String, at: Long) {
        dead[url] = at
    }

    @Synchronized override fun removeAll() {
        titles.clear()
        credits.clear()
        dead.clear()
    }
}

/**
 * The catalogue's titles by the words of their names, to find those that title keys ([MetadataKey.title]) stand for:
 * the titles the store credits a person with. Built once per catalogue.
 */
class MetadataTitles(movies: List<Movie>, series: List<Series>) {
    private val movies: Map<String, List<Movie>> = movies.groupBy { CatalogueMatcher.key(it.name) }
    private val series: Map<String, List<Series>> = series.groupBy { CatalogueMatcher.key(it.name) }

    /** The movies and series these keys stand for, each once; a movie's year one apart at most when both are known. */
    fun match(keys: Collection<String>): List<TrendingMatch> {
        val result = LinkedHashMap<String, TrendingMatch>()
        for (key in keys) {
            val wanted = parse(key) ?: continue
            if (wanted.series) {
                for (show in series[wanted.words].orEmpty()) {
                    result.getOrPut("s" + show.id) { TrendingMatch.SeriesMatch(show, TmdbItem(0, TmdbItem.MediaType.TV, show.name, year = show.year)) }
                }
            } else {
                for (movie in movies[wanted.words].orEmpty()) {
                    val year = movie.year ?: TitleParser.splitYear(movie.name).year
                    if (wanted.year != null && year != null && !CatalogueMatcher.closeYears(wanted.year, year)) continue
                    result.getOrPut("m" + movie.id) { TrendingMatch.MovieMatch(movie, TmdbItem(0, TmdbItem.MediaType.MOVIE, movie.name, year = year)) }
                }
            }
        }
        return result.values.toList()
    }

    private class Wanted(val series: Boolean, val words: String, val year: Int?)

    /** "movie:sandstorm:2021" -> movie "sandstorm" 2021; a TMDb id key ("movie:603") stands for no catalogue title. */
    private fun parse(key: String): Wanted? {
        val parts = key.split(':')
        if (parts.size != 3 || parts[1].isEmpty()) return null
        val series = when (parts[0]) {
            "movie" -> false
            "series" -> true
            else -> return null
        }
        return Wanted(series, parts[1], parts[2].toIntOrNull())
    }
}
