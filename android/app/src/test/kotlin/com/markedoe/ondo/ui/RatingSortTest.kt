package com.markedoe.ondo.ui

import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.library.TitleMetadata
import com.markedoe.ondo.core.library.TmdbRating
import com.markedoe.ondo.core.model.MediaDetails
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.Series
import com.markedoe.ondo.core.sync.SyncKey
import com.markedoe.ondo.data.Favorite
import com.markedoe.ondo.data.ItemKind
import com.markedoe.ondo.data.LibraryState
import com.markedoe.ondo.data.OndoState
import com.markedoe.ondo.data.PosterSort
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.screens.BrowseKind
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * The pages sorted by rating: TMDb's weighted rating first with the viewer's token, the provider's otherwise. The order
 * itself is tested in the core (`WeightedRatingTest`), the background fill there too (`RatingFillTest`) and in
 * [OndoViewModelTest]. The Apple app has the same tests (`RatingSortTests.swift`).
 */
class RatingSortTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    private fun key(movie: Movie) = ShowcaseItem.MovieItem(movie).key

    private fun key(series: Series) = ShowcaseItem.SeriesItem(series).key

    private fun votes(key: String, average: Double, count: Int, series: Boolean = false) =
        key to TitleMetadata(key, if (series) "series" else "movie", tmdbId = 1, voteAverage = average, voteCount = count, fetchedAt = 0)

    /**
     * What TMDb said and the device kept: The Long Road is TMDb's best, Summer in Lisbon has four votes (behind it
     * whatever their average), Northern Lights none (after both, though the provider rates it best of the three).
     */
    private val kept = mapOf(
        votes(key(Fixtures.longRoad), 8.0, 4_000),
        votes(key(Fixtures.summerInLisbon), 9.8, 4),
        votes(key(Fixtures.midnightOrchard), 7.4, 3_000),
        votes(key(Fixtures.blueHorizon), 7.5, 2_000, series = true),
    )

    private fun state(token: Boolean): OndoState {
        val base = Fixtures.state()
        val library = listOf(Fixtures.northernLights, Fixtures.summerInLisbon, Fixtures.longRoad).fold(LibraryState()) { library, movie ->
            library.toggleFavorite(Favorite(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, movie.id, movie.name, addedAt = 1))
        }
        return base.copy(
            library = library,
            settings = if (token) base.settings.copy(serviceKeys = listOf(SyncKey(SyncKey.TMDB, "example-token", 1))) else base.settings,
            movieDetails = listOf(Fixtures.northernLights, Fixtures.summerInLisbon, Fixtures.longRoad)
                .associate { it.id to MediaDetails(title = it.name, genre = "Drama") },
        )
    }

    /** Every page a viewer can sort by rating, with its titles in that order. A new page sorted by rating is added here. */
    private fun surfaces(state: OndoState, facts: Map<String, TitleMetadata>): Map<String, List<String>> {
        val sort = PosterSort.RATING
        fun grid(series: Boolean, category: String?, myList: Boolean = false, anyType: Boolean = false) =
            UiMappers.grid(state, series, category, myList, "", anyType, sort, facts).map { it.title }
        fun browse(kind: BrowseKind, value: String) = UiMappers.browse(state, kind, value, Locale.US, emptyList(), sort, facts)
        return mapOf(
            "All Movies" to grid(series = false, category = null).filter { it in drama },
            "a movie category" to grid(series = false, category = Fixtures.drama.id),
            "My List" to grid(series = false, category = null, myList = true),
            "My List, any type" to grid(series = false, category = null, myList = true, anyType = true),
            "a genre's page" to browse(BrowseKind.GENRE, "Drama").movies.map { it.title },
            "a year's page" to browse(BrowseKind.YEAR, "2024").movies.map { it.title },
            "All Series" to grid(series = true, category = null),
            "a series category" to grid(series = true, category = Fixtures.seriesMystery.id),
        )
    }

    private val drama = setOf("Northern Lights", "Summer in Lisbon", "The Long Road")

    @Test
    fun everyPageSortedByRatingPutsTmdbsWeightedRatingFirst() {
        val tmdbOrder = listOf("The Long Road", "Summer in Lisbon", "Northern Lights")
        assertEquals(
            mapOf(
                "All Movies" to tmdbOrder,
                "a movie category" to tmdbOrder,
                "My List" to tmdbOrder,
                "My List, any type" to tmdbOrder,
                "a genre's page" to tmdbOrder,
                "a year's page" to listOf("Midnight Orchard", "Glass City"),
                "All Series" to listOf("Blue Horizon", "Harbor Lights", "The Archive"),
                "a series category" to listOf("Blue Horizon", "The Archive"),
            ),
            surfaces(state(token = true), kept),
        )
    }

    @Test
    fun withoutATokenEveryPageKeepsTheProvidersRating() {
        val providerOrder = listOf("Northern Lights", "Summer in Lisbon", "The Long Road")
        assertEquals(
            mapOf(
                "All Movies" to providerOrder,
                "a movie category" to providerOrder,
                "My List" to providerOrder,
                "My List, any type" to providerOrder,
                "a genre's page" to providerOrder,
                "a year's page" to listOf("Glass City", "Midnight Orchard"),
                "All Series" to listOf("Harbor Lights", "The Archive", "Blue Horizon"),
                "a series category" to listOf("The Archive", "Blue Horizon"),
            ),
            surfaces(state(token = false), kept),
        )
        assertNull(UiMappers.sortRatings(state(token = false), PosterSort.RATING, kept))
        assertNull("another order needs no rating", UiMappers.sortRatings(state(token = true), PosterSort.NAME, kept))
    }

    /**
     * The answers of this session reach the order through the device's store at the next read, not as they come (the
     * poster with the remote's focus would move); nothing known: the provider's rating.
     */
    @Test
    fun answersAsTheyComeDoNotMoveThePosters() {
        val state = state(token = true)
        val session = state.copy(tmdbRatings = mapOf(key(Fixtures.northernLights) to TmdbRating(8.9, 10_000)))
        assertEquals(listOf("The Long Road", "Summer in Lisbon", "Northern Lights"), surfaces(session, kept)["a movie category"])
        val stored = kept + votes(key(Fixtures.northernLights), 8.9, 10_000)
        assertEquals(listOf("Northern Lights", "The Long Road", "Summer in Lisbon"), surfaces(session, stored)["a movie category"])
        assertEquals(listOf("Northern Lights", "Summer in Lisbon", "The Long Road"), surfaces(state, emptyMap())["a movie category"])
    }
}
