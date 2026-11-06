package com.markedoe.ondo.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.markedoe.ondo.R
import com.markedoe.ondo.core.library.AwardsCatalogue
import com.markedoe.ondo.core.library.Genres
import com.markedoe.ondo.core.library.RowOrder
import com.markedoe.ondo.core.library.Selections
import com.markedoe.ondo.core.library.TitleMetadata
import com.markedoe.ondo.core.model.MediaDetails
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.Series
import com.markedoe.ondo.core.model.PlaylistContent
import com.markedoe.ondo.core.model.SeriesDetails
import com.markedoe.ondo.core.sync.SyncSelection
import com.markedoe.ondo.data.ItemKind
import com.markedoe.ondo.data.OndoState
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.screens.awardGroupName
import com.markedoe.ondo.ui.screens.styleName
import java.time.Instant
import java.time.ZoneId

/** A selection's row in a tab: its titles of the day, and what names it. */
data class SelectionRow(
    val id: String,
    val name: String,
    val tags: List<String>,
    /** The names of its genres, by tag (`genre:drama` → "Drama"). */
    val genreNames: Map<String, String>,
    val items: List<ShowcaseItem>,
) {
    val key: String get() = Selections.rowKey(id)
}

/** The tags a new selection can be made of, from the playlist's titles: each group in the order it is offered. */
data class SelectionChoices(
    /** Genre tags with their names, by name. */
    val genres: List<Pair<String, String>> = emptyList(),
    /** Festivals and ceremonies (`award:` tags' groups), in the order of the awards file. */
    val awardGroups: List<String> = emptyList(),
    /** Decades, the latest first. */
    val decades: List<Int> = emptyList(),
    /** Styles of the provider's category names. */
    val styles: List<String> = emptyList(),
) {
    /** Every tag, with a genre's name. */
    val genreNames: Map<String, String> get() = genres.toMap()
}

/**
 * The selections' rows (docs/SYNC.md, "Selections"): each title's tags (its genres, the provider's and TMDb's kept on the
 * device; the festivals and ceremonies of its awards; its year; the styles of its category's name), the titles of a
 * selection in a tab, hidden categories, languages and the movies hidden as watched left out as in every row, in the
 * day's order ([Selections.dailyOrder]).
 */
object SelectionRows {
    /** What each title is tagged with, by poster key ("m-1", "s-2"). */
    class Tagged(val facts: Map<String, Selections.Facts>, val genreNames: Map<String, String>)

    private class Source(
        val content: PlaylistContent,
        val kept: Map<String, TitleMetadata>,
        val awards: AwardsCatalogue,
        val movieDetails: Map<String, MediaDetails>,
        val seriesDetails: Map<String, SeriesDetails>,
    ) {
        fun isFor(state: OndoState, kept: Map<String, TitleMetadata>) = content === state.content && this.kept === kept &&
            awards === state.awards && movieDetails === state.movieDetails && seriesDetails === state.seriesDetails
    }

    /**
     * The last few worked out, the latest first: Home and the tabs, and a selection's grid, read what the device kept at
     * different moments, and one slot would make them work out the whole catalogue again in turn.
     */
    @Volatile private var cached: List<Pair<Source, Tagged>> = emptyList()

    private const val CACHED = 3

    /** The tags of the catalogue's titles; worked out again only when the catalogue or what is known of it changes. */
    fun tagged(state: OndoState, kept: Map<String, TitleMetadata>): Tagged {
        cached.firstOrNull { it.first.isFor(state, kept) }?.let { return it.second }
        val names = HashMap<String, String>()
        fun genres(vararg fields: String?, tmdb: TitleMetadata?): Set<String> {
            val result = HashSet<String>()
            // Each genre's key (Genres): the same genre named in any language is one; TMDb's "Action & Adventure" is two.
            val all = fields.flatMap { Genres.split(it) } + tmdb?.genres.orEmpty().flatMap { Genres.split(it.name) }
            for (genre in all) {
                val keys = Genres.keys(genre)
                // A genre's own name for those TMDb does not have; a name joining genres names none of them.
                if (keys.size == 1) names.putIfAbsent(Selections.GENRE + keys[0], genre)
                result += keys
            }
            return result
        }
        fun groups(entries: List<AwardsCatalogue.Entry>?) = entries.orEmpty().mapNotNullTo(HashSet()) { state.awards.award(it.awardId)?.group }
        val movieStyles = state.content.movieCategories.associate { it.id to Selections.stylesOf(it.name) }
        val seriesStyles = state.content.seriesCategories.associate { it.id to Selections.stylesOf(it.name) }
        val awards = MovieAwards.of(state)
        val seriesAwards = MovieAwards.ofSeries(state)
        val facts = HashMap<String, Selections.Facts>(state.content.movies.size + state.content.series.size)
        for (movie in state.content.movies) {
            val key = ShowcaseItem.MovieItem(movie).key
            facts[key] = Selections.Facts(genres(state.movieDetails[movie.id]?.genre, tmdb = kept[key]), groups(awards[movie.id]), movie.year, movieStyles[movie.categoryId].orEmpty())
        }
        for (show in state.content.series) {
            val key = ShowcaseItem.SeriesItem(show).key
            facts[key] = Selections.Facts(
                genres(show.genre, state.seriesDetails[show.id]?.info?.genre, tmdb = kept[key]), groups(seriesAwards[show.id]), show.year,
                seriesStyles[show.categoryId].orEmpty(),
            )
        }
        val tagged = Tagged(facts, names)
        val source = Source(state.content, kept, state.awards, state.movieDetails, state.seriesDetails)
        // Those of another catalogue go: a TV's memory holds no second copy of it.
        cached = (listOf(source to tagged) + cached.filter { it.first.content === state.content }).take(CACHED)
        return tagged
    }

    /** The day a selection's pick is for: the device's own date ("2026-11-05"). */
    fun day(now: Long, zone: ZoneId = ZoneId.systemDefault()): String = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()

    /** The selections of the open profile that show in [tab] (`home`, `movie`, `series`), by ID. */
    fun of(state: OndoState, tab: String): List<SyncSelection> = state.library.selections.filter { tab in it.tabs }

    /**
     * The titles of [selection] in [tab], the first [limit] of the day's order: movies in Movies, series in Series, both on
     * Home; each film once; hidden categories and languages, and movies hidden as watched, left out; titles watched last.
     */
    fun titles(state: OndoState, selection: SyncSelection, tab: String, now: Long, kept: Map<String, TitleMetadata>, limit: Int): List<ShowcaseItem> {
        if (selection.tags.none(Selections::isKnown)) return emptyList()
        val tagged = tagged(state, kept)
        val matcher = Selections.matcher(selection.tags, selection.matchesAll)
        fun matches(key: String) = tagged.facts[key]?.let(matcher) == true
        val visible = visible(state)
        val candidates = ArrayList<ShowcaseItem>()
        if (tab != RowOrder.SERIES) {
            val watched = UiMappers.hiddenMovieIds(state)
            val movies = visible.movies.filter { it.id !in watched && matches(ShowcaseItem.MovieItem(it).key) }
            UiMappers.films(state).distinct(movies).mapTo(candidates) { ShowcaseItem.MovieItem(it) }
        }
        if (tab != RowOrder.MOVIES) {
            visible.series.filter { matches(ShowcaseItem.SeriesItem(it).key) }.mapTo(candidates) { ShowcaseItem.SeriesItem(it) }
        }
        val playlist = state.activePlaylistId.orEmpty()
        val finishedMovies = state.library.finishedMovies(playlist)
        val seenSeries = state.library.history.filter { it.playlistId == playlist && it.kind == ItemKind.SERIES }.mapTo(HashSet()) { it.itemId }
        val trending = HashMap<String, com.markedoe.ondo.core.library.TmdbRating>()
        for (match in state.trending) match.trending.rating?.let { trending.putIfAbsent(match.id, it) }
        return Selections.dailyOrder(
            candidates.distinctBy { it.key },
            selection.id,
            day(now),
            key = { item -> if (item is ShowcaseItem.MovieItem) "movie:${item.movie.id}" else "series:${(item as ShowcaseItem.SeriesItem).series.id}" },
            rating = { state.tmdbRatings[it.key] ?: kept[it.key]?.rating ?: trending[it.key] },
            watched = { item ->
                when (item) {
                    is ShowcaseItem.MovieItem -> item.movie.id in finishedMovies
                    is ShowcaseItem.SeriesItem -> item.series.id in seenSeries
                }
            },
        ).take(limit)
    }

    /** The rows of the selections that show in [tab], by row key; none when the profile has no selection there. */
    fun rows(state: OndoState, tab: String, now: Long, kept: Map<String, TitleMetadata>, limit: Int): Map<String, SelectionRow> {
        val selections = of(state, tab)
        if (selections.isEmpty()) return emptyMap()
        val names = tagged(state, kept).genreNames
        return selections.associate { selection ->
            Selections.rowKey(selection.id) to SelectionRow(
                selection.id, selection.name, selection.tags,
                // By this version's tags: an earlier version's `genre:erotiques` is named as `genre:erotique`.
                Genres.canonicalTags(selection.tags).mapNotNull { tag -> names[tag]?.let { tag to it } }.toMap(),
                titles(state, selection, tab, now, kept, limit),
            )
        }
    }

    /** The tags a selection can be made of, from the playlist's visible titles. */
    fun choices(state: OndoState, kept: Map<String, TitleMetadata>): SelectionChoices {
        val tagged = tagged(state, kept)
        // The same titles as the rows pick from: a tag only hidden titles have is not offered.
        val visible = visible(state)
        val movies = visible.movies
        val series = visible.series
        val facts = movies.mapNotNull { tagged.facts[ShowcaseItem.MovieItem(it).key] } + series.mapNotNull { tagged.facts[ShowcaseItem.SeriesItem(it).key] }
        val genres = facts.flatMapTo(HashSet()) { it.genres }
        val groups = facts.flatMapTo(HashSet()) { it.awardGroups }
        val styles = facts.flatMapTo(HashSet()) { it.styles }
        val collator = java.text.Collator.getInstance().apply { strength = java.text.Collator.PRIMARY }
        return SelectionChoices(
            // Sorted again by the names shown (TMDb's genres in the viewer's language) on the page.
            genres = genres.map { genre -> (Selections.GENRE + genre).let { tag -> tag to (tagged.genreNames[tag] ?: fallbackGenreName(tag)) } }
                .sortedWith { a, b -> collator.compare(a.second, b.second) },
            awardGroups = state.awards.groups.filter { it in groups },
            decades = Selections.decades((movies.map { it.year } + series.map { it.year })),
            styles = com.markedoe.ondo.core.library.CategoryStyles.order.filter { it in styles },
        )
    }

    /** The titles a selection picks from, and its tags are offered from: as every list shows them. */
    private class Visible(val movies: List<Movie>, val series: List<Series>)

    /**
     * The catalogue's titles but those of hidden categories (a Kids profile's adult ones included) and those whose own
     * name carries a hidden language ("|PT| …" filed under "Concerts"), as every list of titles leaves them out.
     */
    private fun visible(state: OndoState): Visible {
        val hidden = UiMappers.hiddenCategoryIds(state)
        val byName = CategoryFilter.hiddenTitles(state)
        return Visible(
            state.content.movies.filter { it.categoryId !in hidden && it.id !in byName.movies },
            state.content.series.filter { it.categoryId !in hidden && it.id !in byName.series },
        )
    }

    /** A genre's name in a tag, without the names kept: its words, the first in capitals. */
    internal fun fallbackGenreName(tag: String): String = tag.removePrefix(Selections.GENRE).replaceFirstChar { it.titlecase() }
}

/**
 * A tag's name: one of TMDb's genres in the viewer's language, another genre as the titles name it, a festival's or
 * ceremony's, a decade ("1990s"), a style's. A genre tag of an earlier version names the genres it is read as.
 */
@Composable
fun selectionTagName(tag: String, genreNames: Map<String, String>): String = when {
    tag.startsWith(Selections.GENRE) -> genreTagName(tag, genreNames)
    tag.startsWith(Selections.AWARD) -> awardGroupName(tag.removePrefix(Selections.AWARD))
    tag.startsWith(Selections.DECADE) -> stringResource(R.string.selection_decade, tag.removePrefix(Selections.DECADE))
    tag.startsWith(Selections.STYLE) -> styleName(tag.removePrefix(Selections.STYLE))
    else -> tag
}

@Composable
private fun genreTagName(tag: String, genreNames: Map<String, String>): String {
    val tags = Genres.canonicalTags(tag)
    if (tags.size > 1) return tags.map { genreTagName(it, genreNames) }.joinToString(" & ")
    val canonical = tags.first()
    val key = canonical.removePrefix(Selections.GENRE)
    genreNameRes(key)?.let { return stringResource(it) }
    return genreNames[canonical] ?: genreNames[tag] ?: SelectionRows.fallbackGenreName(canonical)
}

/** The name of one of TMDb's genres ([Genres.canonical]) in the viewer's language; null for another genre. */
fun genreNameRes(key: String): Int? = when (key) {
    "action" -> R.string.genre_action
    "adventure" -> R.string.genre_adventure
    "animation" -> R.string.genre_animation
    "comedy" -> R.string.genre_comedy
    "crime" -> R.string.genre_crime
    "documentary" -> R.string.genre_documentary
    "drama" -> R.string.genre_drama
    "family" -> R.string.genre_family
    "fantasy" -> R.string.genre_fantasy
    "history" -> R.string.genre_history
    "horror" -> R.string.genre_horror
    "music" -> R.string.genre_music
    "mystery" -> R.string.genre_mystery
    "romance" -> R.string.genre_romance
    "science fiction" -> R.string.genre_science_fiction
    "thriller" -> R.string.genre_thriller
    "war" -> R.string.genre_war
    "western" -> R.string.genre_western
    "kids" -> R.string.genre_kids
    "news" -> R.string.genre_news
    "reality" -> R.string.genre_reality
    "soap" -> R.string.genre_soap
    "talk" -> R.string.genre_talk
    "politics" -> R.string.genre_politics
    "tv movie" -> R.string.genre_tv_movie
    else -> null
}

/** A selection's name: the one the viewer gave, else its tags' ([Selections.defaultName]). */
@Composable
fun selectionTitle(name: String, tags: List<String>, genreNames: Map<String, String>): String {
    if (name.isNotBlank()) return name
    val names = tags.filter(Selections::isKnown).take(3).map { selectionTagName(it, genreNames) }
    return names.joinToString(" · ").ifEmpty { stringResource(R.string.selection) }
}

/** The name of a selection's row. */
@Composable
fun selectionTitle(row: SelectionRow): String = selectionTitle(row.name, row.tags, row.genreNames)
