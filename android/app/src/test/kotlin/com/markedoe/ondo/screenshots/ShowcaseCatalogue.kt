package com.markedoe.ondo.screenshots

import com.markedoe.ondo.core.epg.EpgIndex
import com.markedoe.ondo.core.model.Channel
import com.markedoe.ondo.core.model.ContentType
import com.markedoe.ondo.core.model.Episode
import com.markedoe.ondo.core.model.EpgProgram
import com.markedoe.ondo.core.model.MediaCategory
import com.markedoe.ondo.core.model.MediaDetails
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.PlaylistContent
import com.markedoe.ondo.core.model.Season
import com.markedoe.ondo.core.model.Series
import com.markedoe.ondo.core.model.SeriesDetails
import com.markedoe.ondo.data.AppSettings
import com.markedoe.ondo.data.Favorite
import com.markedoe.ondo.data.ItemKind
import com.markedoe.ondo.data.LibraryState
import com.markedoe.ondo.data.OndoState
import com.markedoe.ondo.data.SavedPlaylist
import com.markedoe.ondo.data.WatchEntry
import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The demo catalogue of the store screenshots (`fixtures/showcase/catalogue.json`): open films and series whose
 * pictures may be shown (credits in `fixtures/showcase/CREDITS.md`), fictional channels and their guide, and a
 * library in use. Built as a provider would send it: categories with language tags, addresses on example.com (the
 * pictures are answered from the repository by [ShowcaseImages]).
 */
class ShowcaseCatalogue private constructor(private val file: CatalogueFile, private val french: Boolean) {
    companion object {
        const val PLAYLIST_ID = "showcase"

        /** Screenshots are taken at this moment (the guide and "updated … ago" are relative to it). */
        const val NOW = 1_760_000_000_000L

        private val json = Json { ignoreUnknownKeys = true }
        private val source: CatalogueFile by lazy {
            json.decodeFromString(CatalogueFile.serializer(), File("../../fixtures/showcase/catalogue.json").readText())
        }

        fun load(french: Boolean) = ShowcaseCatalogue(source, french)

        private const val PICTURES = "https://example.com/showcase/"
    }

    @Serializable
    data class Text(val en: String, val fr: String)

    @Serializable
    data class CatalogueCategory(val id: String, val type: String, val name: String)

    @Serializable
    data class CatalogueEpisode(val title: String, val minutes: Int, val still: String? = null, val plot: Text? = null)

    @Serializable
    data class CatalogueSeason(val number: Int, val episodes: List<CatalogueEpisode>)

    @Serializable
    data class CatalogueTitle(
        val id: String,
        val title: String,
        val year: Int,
        val category: String,
        val genre: String,
        val rating: Double,
        val minutes: Int = 0,
        val director: String? = null,
        val cast: String? = null,
        val plot: Text,
        val poster: String? = null,
        val backdrop: String? = null,
        @SerialName("added_days_ago") val addedDaysAgo: Int = 30,
        val container: String = "mp4",
        val seasons: List<CatalogueSeason> = emptyList(),
    )

    @Serializable
    data class CatalogueChannel(val id: String, val number: Int, val name: String, val category: String, val logo: String? = null, val programmes: List<Text>)

    @Serializable
    data class Watching(val id: String, val progress: Double, val season: Int = 1, val episode: Int = 1)

    @Serializable
    data class CatalogueLibrary(
        val watching: List<Watching> = emptyList(),
        @SerialName("my_list") val myList: List<String> = emptyList(),
        @SerialName("favorite_channels") val favoriteChannels: List<String> = emptyList(),
    )

    /** What each screenshot shows: a movie's page, a series' page, a search, a person's page. */
    @Serializable
    data class Screens(val movie: String, val series: String, val search: Text, val person: String)

    @Serializable
    data class CatalogueFile(
        @SerialName("playlist_name") val playlistName: Text,
        @SerialName("updated_minutes_ago") val updatedMinutesAgo: Int = 12,
        @SerialName("programme_minutes") val programmeMinutes: Int = 60,
        val categories: List<CatalogueCategory>,
        val movies: List<CatalogueTitle>,
        val series: List<CatalogueTitle>,
        val channels: List<CatalogueChannel>,
        val library: CatalogueLibrary = CatalogueLibrary(),
        val screens: Screens,
    )

    private fun text(text: Text) = if (french) text.fr else text.en

    private fun picture(name: String?) = name?.let { PICTURES + it }

    private fun type(raw: String) = when (raw) {
        "movie" -> ContentType.MOVIE
        "series" -> ContentType.SERIES
        else -> ContentType.LIVE
    }

    private fun addedAt(daysAgo: Int) = NOW / 1000 - daysAgo * 86_400L

    val movies: List<Movie> = file.movies.mapIndexed { index, movie ->
        Movie(
            id = movie.id,
            name = movie.title,
            posterUrl = picture(movie.poster),
            categoryId = movie.category,
            streamUrl = "https://example.com/movie/${movie.id}.${movie.container}",
            rating = movie.rating,
            year = movie.year,
            addedAt = addedAt(movie.addedDaysAgo) - index,
            plot = text(movie.plot),
            containerExtension = movie.container,
        )
    }

    private fun episodes(title: CatalogueTitle): List<Episode> = title.seasons.flatMap { season ->
        season.episodes.mapIndexed { index, episode ->
            Episode(
                id = "${title.id}-${season.number}-${index + 1}",
                title = episode.title,
                season = season.number,
                number = index + 1,
                streamUrl = "https://example.com/series/${title.id}/${season.number}/${index + 1}.${title.container}",
                posterUrl = picture(episode.still),
                plot = episode.plot?.let(::text),
                durationSeconds = episode.minutes * 60,
            )
        }
    }

    val series: List<Series> = file.series.mapIndexed { index, show ->
        Series(
            id = show.id,
            name = show.title,
            coverUrl = picture(show.poster),
            categoryId = show.category,
            plot = text(show.plot),
            rating = show.rating,
            year = show.year,
            genre = show.genre,
            cast = show.cast,
            director = show.director,
            addedAt = addedAt(show.addedDaysAgo) - index,
            episodes = episodes(show),
        )
    }

    val channels: List<Channel> = file.channels.map { channel ->
        Channel(
            id = channel.id,
            name = channel.name,
            number = channel.number,
            logoUrl = picture(channel.logo),
            categoryId = channel.category,
            streamUrl = "https://example.com/live/${channel.id}.m3u8",
            epgId = "${channel.id}.demo",
        )
    }

    private val categories = file.categories.map { MediaCategory(it.id, it.name, type(it.type)) }

    val content = PlaylistContent(
        channels = channels,
        movies = movies,
        series = series,
        liveCategories = categories.filter { it.type == ContentType.LIVE },
        movieCategories = categories.filter { it.type == ContentType.MOVIE },
        seriesCategories = categories.filter { it.type == ContentType.SERIES },
        fetchedAt = NOW - file.updatedMinutesAgo * 60_000L,
    )

    /** The pages already opened: what the provider's details give (cast, director, backdrop…). */
    val movieDetails: Map<String, MediaDetails> = file.movies.associate { movie ->
        movie.id to MediaDetails(
            title = movie.title,
            plot = text(movie.plot),
            cast = movie.cast,
            director = movie.director,
            genre = movie.genre,
            releaseDate = "${movie.year}-01-01",
            durationSeconds = movie.minutes * 60,
            rating = movie.rating,
            posterUrl = picture(movie.poster),
            backdropUrl = picture(movie.backdrop),
        )
    }

    val seriesDetails: Map<String, SeriesDetails> = file.series.associate { show ->
        val list = episodes(show)
        show.id to SeriesDetails(
            info = MediaDetails(
                title = show.title,
                plot = text(show.plot),
                cast = show.cast,
                director = show.director,
                genre = show.genre,
                releaseDate = "${show.year}-01-01",
                rating = show.rating,
                posterUrl = picture(show.poster),
                backdropUrl = picture(show.backdrop),
            ),
            seasons = list.groupBy { it.season }.toSortedMap().map { (number, episodes) ->
                Season(number, (if (french) "Saison " else "Season ") + number, episodes)
            },
        )
    }

    /** A guide around [NOW]: each channel's programmes one after the other, the current one started a while ago. */
    val guide: EpgIndex = EpgIndex(
        file.channels.flatMapIndexed { index, channel ->
            val length = file.programmeMinutes * 60_000L
            // The current programme started between 10 and 50 minutes ago, depending on the channel.
            val firstStart = NOW - (10 + index * 13 % 40) * 60_000L - length
            channel.programmes.mapIndexed { slot, title ->
                val start = firstStart + slot * length
                EpgProgram(channelId = "${channel.id}.demo", start = start, end = start + length, title = text(title))
            }
        },
    )

    val playlist = SavedPlaylist(id = PLAYLIST_ID, name = text(file.playlistName), url = "https://example.com/playlist.m3u", addedAt = NOW)

    /** Titles in progress, My List and favourite channels, like a catalogue in use. */
    val library: LibraryState = run {
        var library = LibraryState()
        file.library.watching.forEachIndexed { index, watching ->
            val time = (file.library.watching.size - index).toLong()
            val movie = file.movies.firstOrNull { it.id == watching.id }
            if (movie != null) {
                val entry = WatchEntry(PLAYLIST_ID, ItemKind.MOVIE, movie.id, title = movie.title, subtitle = movie.year.toString(), lastWatchedAt = time)
                val duration = movie.minutes * 60.0
                library = library.recordStart(entry).updateProgress(entry.key, duration * watching.progress, duration, now = time)
            } else {
                val show = series.first { it.id == watching.id }
                val episode = show.episodes.first { it.season == watching.season && it.number == watching.episode }
                val entry = WatchEntry(PLAYLIST_ID, ItemKind.SERIES, show.id, episode.id, title = show.name, subtitle = "${episode.code} · ${episode.title}", lastWatchedAt = time)
                val duration = (episode.durationSeconds ?: 1_800).toDouble()
                library = library.recordStart(entry).updateProgress(entry.key, duration * watching.progress, duration, now = time)
            }
        }
        file.library.myList.forEachIndexed { index, id ->
            val movie = movies.firstOrNull { it.id == id }
            library = if (movie != null) {
                library.toggleFavorite(Favorite(PLAYLIST_ID, ItemKind.MOVIE, id, movie.name, movie.posterUrl, addedAt = index + 1L))
            } else {
                val show = series.first { it.id == id }
                library.toggleFavorite(Favorite(PLAYLIST_ID, ItemKind.SERIES, id, show.name, show.coverUrl, addedAt = index + 1L))
            }
        }
        file.library.favoriteChannels.forEachIndexed { index, id ->
            val channel = channels.first { it.id == id }
            library = library.toggleFavorite(Favorite(PLAYLIST_ID, ItemKind.CHANNEL, id, channel.name, channel.logoUrl, addedAt = 100L + index))
        }
        library
    }

    fun state(settings: AppSettings = AppSettings(activePlaylistId = PLAYLIST_ID)) = OndoState(
        isReady = true,
        settings = settings,
        playlists = listOf(playlist),
        content = content,
        library = library,
        guide = guide,
        movieDetails = movieDetails,
        seriesDetails = seriesDetails,
    )

    val screens: Screens get() = file.screens

    /** The words typed in the search screenshot, in the screenshot's language. */
    val searchQuery: String get() = text(file.screens.search)

    fun movie(id: String) = movies.first { it.id == id }

    fun series(id: String) = series.first { it.id == id }
}
