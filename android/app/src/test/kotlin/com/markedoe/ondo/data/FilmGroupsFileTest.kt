package com.markedoe.ondo.data

import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.library.FilmVersions
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.PlaylistContent
import com.markedoe.ondo.ui.UiMappers
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The grouping of a catalogue's films kept between two starts: read back for the very same movies only, so that a start
 * does not read every title of a large catalogue again before Home's rows show.
 */
class FilmGroupsFileTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val folder = TemporaryFolder()

    private fun movie(index: Int, name: String, year: Int? = 2021, tmdb: Int? = null) =
        Movie(id = "m$index", name = name, categoryId = "c", streamUrl = "http://stream.example.com/$index.mp4", year = year, tmdbId = tmdb)

    private val movies = listOf(movie(1, "FR - Quiet Water"), movie(2, "EN - Quiet Water"), movie(3, "Long Road", year = 2020))

    private fun kept(): File = File(folder.root, FilmGroupsFile.FILE_NAME)

    private fun file(build: Int = 7) = FilmGroupsFile(kept(), build)

    @Test
    fun theGroupsKeptAreReadBackForTheSameMovies() {
        val groups = requireNotNull(FilmVersions.of(movies).groups())
        file().write(movies, groups)
        assertArrayEquals(groups, file().read(movies))
        // The same movies read from the device again: other objects, the same groups.
        assertArrayEquals(groups, file().read(movies.map { it.copy() }))
    }

    @Test
    fun otherMoviesAnotherBuildOrABrokenFileAreNotReadBack() {
        val groups = requireNotNull(FilmVersions.of(movies).groups())
        file().write(movies, groups)
        assertNull("another name", file().read(movies.mapIndexed { index, movie -> if (index == 0) movie.copy(name = "FR - Still Water") else movie }))
        assertNull("another year", file().read(movies.mapIndexed { index, movie -> if (index == 2) movie.copy(year = 2019) else movie }))
        assertNull("another TMDb id", file().read(movies.mapIndexed { index, movie -> if (index == 1) movie.copy(tmdbId = 603) else movie }))
        assertNull("another stream", file().read(movies.mapIndexed { index, movie -> if (index == 1) movie.copy(id = "m9") else movie }))
        assertNull("another order", file().read(movies.reversed()))
        assertNull("one more movie", file().read(movies + movie(4, "Harbor")))
        assertNull("another build of the app", file(build = 8).read(movies))
        assertArrayEquals("still there for the same movies", groups, file().read(movies))

        val whole = kept().readBytes()
        kept().writeBytes(whole.copyOf(whole.size - 3))
        assertNull("a file cut short", file().read(movies))
        kept().writeText("not groups")
        assertNull("not a file of groups", file().read(movies))
        kept().delete()
        assertNull("no file", file().read(movies))
    }

    @Test
    fun nothingIsKeptWithoutGroupsOrWithoutTheAppsFolder() {
        file().write(movies, null)
        assertFalse("nothing grouped, nothing kept", kept().exists())
        file().write(movies, intArrayOf(0, 0))
        assertFalse("groups of another count", kept().exists())
        val gone = File(folder.root, "gone")
        FilmGroupsFile(File(gone, FilmGroupsFile.FILE_NAME), 7).write(movies, intArrayOf(0, 0, 1))
        assertFalse("the folder is not made for it", gone.exists())
    }

    /** The mappers group a catalogue once: the same catalogue read again at the next start takes the groups kept. */
    @Test
    fun aCatalogueReadAgainAtTheNextStartIsNotGroupedAgain() {
        UiMappers.filmGroups = file()
        try {
            UiMappers.forgetCatalogue()
            val grouped = UiMappers.filmGroupings.get()
            val restored = UiMappers.filmGroupingsRestored.get()
            val content = PlaylistContent(movies = movies, fetchedAt = 1L)
            val first = UiMappers.films(content)
            assertEquals("grouped by reading its titles", grouped + 1, UiMappers.filmGroupings.get())
            assertTrue("its groups are kept", kept().isFile)

            // The next start: the catalogue read from the device again, nothing left in memory.
            UiMappers.forgetCatalogue()
            val again = PlaylistContent(movies = movies.map { it.copy() }, fetchedAt = 1L)
            val second = UiMappers.films(again)
            assertEquals("grouped again", grouped + 1, UiMappers.filmGroupings.get())
            assertEquals("taken from the groups kept", restored + 1, UiMappers.filmGroupingsRestored.get())
            assertTrue(second.isGrouped)
            assertEquals(first.groupCount, second.groupCount)
            assertEquals(first.distinct(movies).map { it.id }, second.distinct(again.movies).map { it.id })
            for (index in movies.indices) {
                assertEquals(first.versions(movies[index]).map { it.id }, second.versions(again.movies[index]).map { it.id })
            }

            // Another catalogue (a refresh brought a movie): grouped, and its groups kept instead.
            UiMappers.forgetCatalogue()
            val refreshed = PlaylistContent(movies = movies + movie(4, "Harbor"), fetchedAt = 2L)
            UiMappers.films(refreshed)
            assertEquals("the new catalogue is grouped", grouped + 2, UiMappers.filmGroupings.get())
            assertNull("the groups kept are the new catalogue's", file().read(movies))
        } finally {
            UiMappers.filmGroups = null
            UiMappers.forgetCatalogue()
        }
    }
}
