package com.markedoe.ondo.data

import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.core.model.ContentType
import com.markedoe.ondo.core.model.MediaCategory
import com.markedoe.ondo.core.model.MediaDetails
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.Series
import com.markedoe.ondo.ui.UiMappers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "More like this": the titles of the same category, the closest first (genres, director and actors shared, then the
 * era, then the rating), never the title itself, another copy of it, or the same film twice.
 */
class SimilarTitlesTest {
    private val films = MediaCategory("m-fr", "|FR| Films", ContentType.MOVIE)
    private val other = MediaCategory("m-fr-kids", "|FR| Kids", ContentType.MOVIE)
    private val shows = MediaCategory("s-fr", "|FR| Series", ContentType.SERIES)

    private fun movie(id: String, name: String, year: Int?, rating: Double? = null, category: MediaCategory = films) =
        Movie(id, name, categoryId = category.id, streamUrl = "http://example.com/movie/$id.mp4", year = year, rating = rating)

    private val sandstorm = movie("1", "Sandstorm", 2021, 7.0)
    private val movies = listOf(
        sandstorm,
        movie("2", "Sandstorm VOSTFR", 2021, 7.0),
        movie("3", "FR - Sandstorm (2021) 4K", null, 7.0),
        movie("4", "Sandstorm", 1984, 5.0),
        movie("5", "Desert Run", 2020, 6.1),
        movie("6", "Quiet Garden", 2021, 9.0),
        movie("7", "Night Chase", 2019, 6.5),
        movie("7", "Night Chase", 2019, 6.5),
        movie("8", "Night Chase [MULTI]", 2019, 6.5),
        movie("9", "Old Western", 1960, 8.5),
        movie("10", "Recent Comedy", 2022, 6.0),
        movie("11", "Untitled Feature", null, 9.5),
        movie("12", "Finished Thriller", 2021, 8.0),
        movie("13", "Action Elsewhere", 2021, 9.9, category = other),
    )

    /** What the pages opened say: the genres, director and cast. */
    private val details = mapOf(
        "1" to MediaDetails("Sandstorm", genre = "Action, Thriller", director = "Nora Hale", cast = "Ana Lima, Tom Reyes"),
        "5" to MediaDetails("Desert Run", genre = "Action", director = "Nora Hale"),
        "6" to MediaDetails("Quiet Garden", genre = "Romance"),
        "7" to MediaDetails("Night Chase", genre = "Thriller / Action", cast = "Ana Lima (Mara)"),
        "13" to MediaDetails("Action Elsewhere", genre = "Action, Thriller", director = "Nora Hale"),
    )

    private fun showcase(hidden: Set<String> = emptySet(), library: LibraryState = LibraryState(), hideWatched: Boolean = false) = Showcase(
        Fixtures.content.copy(movies = movies, movieCategories = listOf(films, other), series = series, seriesCategories = listOf(shows)),
        library,
        Fixtures.PLAYLIST_ID,
        hidden,
        hideWatchedMovies = hideWatched,
    )

    private fun similar(showcase: Showcase = showcase(), limit: Int = 15) = showcase.similarMovies(sandstorm, limit) { details[it] }.map { "${it.name} ${it.year}" }

    @Test
    fun theClosestComeFirstAndEachFilmOnce() {
        assertEquals(
            listOf(
                // Genres and an actor shared, then genre and director: before any better rated title.
                "Night Chase 2019",
                "Desert Run 2020",
                // Nothing shared known: the same years first, the best rated first among them.
                "Quiet Garden 2021",
                "Finished Thriller 2021",
                "Recent Comedy 2022",
                // The remake of another era is another film; further back, then a year unknown.
                "Sandstorm 1984",
                "Old Western 1960",
                "Untitled Feature null",
            ),
            similar(),
        )
    }

    @Test
    fun neverTheTitleItsCopiesOrAnotherCategory() {
        val found = similar()
        assertTrue("not itself, nor its copies in another language or quality", found.none { it.startsWith("Sandstorm 2021") || "VOSTFR" in it || "4K" in it })
        assertEquals("a film listed twice, or once more in another version, once", 1, found.count { it.startsWith("Night Chase") })
        assertTrue("another category, however close", found.none { it.startsWith("Action Elsewhere") })
    }

    @Test
    fun theLimitKeepsTheClosest() {
        assertEquals(listOf("Night Chase 2019", "Desert Run 2020", "Quiet Garden 2021"), similar(limit = 3))
    }

    @Test
    fun hiddenCategoriesAndFinishedMoviesStayOut() {
        assertTrue(similar(showcase(hidden = setOf(films.id))).isEmpty())
        val entry = WatchEntry(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, "12", null, title = "Finished Thriller", lastWatchedAt = 1)
        val finished = LibraryState().recordStart(entry).updateProgress(entry.key, 5_900.0, 6_000.0, now = 1)
        assertTrue("finished, Settings hiding them", similar(showcase(library = finished, hideWatched = true)).none { it.startsWith("Finished") })
        assertTrue("finished, Settings showing them", similar(showcase(library = finished)).any { it.startsWith("Finished") })
    }

    private val archive = Series("s1", "The Archive", categoryId = shows.id, year = 2024, rating = 7.0, genre = "Mystery", cast = "Elodie Marceau")
    private val series = listOf(
        archive,
        Series("s2", "The Archive VF", categoryId = shows.id, year = 2024, rating = 7.0, genre = "Mystery"),
        Series("s3", "Cooking Hour", categoryId = shows.id, year = 2024, rating = 9.0, genre = "Reality"),
        Series("s4", "Blue Horizon", categoryId = shows.id, year = 2023, rating = 6.0, genre = "Mystery, Science Fiction"),
        Series("s5", "Harbor Lights", categoryId = shows.id, year = 2010, rating = 5.0, genre = "Drama", cast = "Elodie Marceau"),
    )

    @Test
    fun seriesAreComparedByWhatTheirListSays() {
        val found = showcase().similarSeries(archive).map { it.name }
        assertEquals("a genre, then an actor, then the rest; not its French copy", listOf("Blue Horizon", "Harbor Lights", "Cooking Hour"), found)
    }

    /** The detail pages use them, with the pages already opened. */
    @Test
    fun theTitlePagesListThem() {
        val content = Fixtures.content.copy(movies = movies, movieCategories = listOf(films, other))
        val state = Fixtures.state(content = content).copy(movieDetails = details)
        assertEquals(listOf("Night Chase", "Desert Run"), UiMappers.movieDetail(state, sandstorm).similar.take(2).map { it.title })
        // A page not opened yet: by era and rating only.
        assertEquals("Quiet Garden", UiMappers.movieDetail(state.copy(movieDetails = emptyMap()), sandstorm).similar.first().title)
    }
}
