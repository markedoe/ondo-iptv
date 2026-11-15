package com.markedoe.ondo

import com.markedoe.ondo.core.model.ContentType
import com.markedoe.ondo.core.model.MediaCategory
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.data.AppSettings
import com.markedoe.ondo.data.LibraryState
import com.markedoe.ondo.data.OndoState

/**
 * The shape of a panel seen on a TV, with invented names: one film filed under two categories (the provider's prefix,
 * another letter case and accents, the same tags and container), next to a 4K Dolby Vision version. Its chips read
 * "Standard · DRAME THRILLER", "4K Dolby Vision", "Standard · AJOUTÉS"; one "Standard" chip now (docs/VERSIONS.md).
 */
object FiledTwice {
    val drama = MediaCategory("m-fr-drama", "|FR| DRAME | THRILLER", ContentType.MOVIE)
    val uhdFilms = MediaCategory("m-fr-4k", "|FR| FILMS 4K", ContentType.MOVIE)
    val recent = MediaCategory("m-fr-new", "|FR| AJOUTÉS", ContentType.MOVIE)

    private fun version(id: String, name: String, category: MediaCategory) = Movie(
        id = id, name = name, categoryId = category.id, streamUrl = "http://example.com/movie/$id.mkv", year = 2024,
        containerExtension = "mkv", addedAt = Fixtures.NOW / 1000,
    )

    /** Catalogue order, as on the TV: the copy filed under DRAME first, the 4K one, the copy filed under AJOUTÉS. */
    val first = version("cv-801", "|FR| COPPER VALLÉE", drama)
    val uhd = version("cv-802", "|FR| Copper Vallée 4K DV", uhdFilms)
    val again = version("cv-803", "|FR| Copper Vallee", recent)

    val content = Fixtures.content.copy(
        movies = Fixtures.content.movies + listOf(first, uhd, again),
        movieCategories = Fixtures.content.movieCategories + listOf(drama, uhdFilms, recent),
    )

    fun state(library: LibraryState = LibraryState(), chosen: List<String> = emptyList()): OndoState =
        Fixtures.state(library, content, AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID, groupVersions = true, chosenVersions = chosen))
}
