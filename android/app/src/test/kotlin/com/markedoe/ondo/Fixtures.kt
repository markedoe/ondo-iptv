package com.markedoe.ondo

import com.markedoe.ondo.core.model.Channel
import com.markedoe.ondo.core.model.ContentType
import com.markedoe.ondo.core.model.Episode
import com.markedoe.ondo.core.model.MediaCategory
import com.markedoe.ondo.core.model.MediaDetails
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.PlaylistContent
import com.markedoe.ondo.core.model.Season
import com.markedoe.ondo.core.model.Series
import com.markedoe.ondo.core.model.SeriesDetails
import com.markedoe.ondo.data.AppSettings
import com.markedoe.ondo.data.LibraryState
import com.markedoe.ondo.data.OndoState
import com.markedoe.ondo.data.SavedPlaylist

/** A small fictional catalogue (the same titles as the iPhone screenshots). */
object Fixtures {
    const val PLAYLIST_ID = "p1"
    const val NOW = 1_760_000_000_000L

    val drama = MediaCategory("m-drama", "Drama", ContentType.MOVIE)
    val sciFi = MediaCategory("m-scifi", "Science Fiction", ContentType.MOVIE)
    val thriller = MediaCategory("m-thriller", "Thriller", ContentType.MOVIE)
    val adultMovies = MediaCategory("m-adult", "Adult XXX", ContentType.MOVIE)
    val seriesDrama = MediaCategory("s-drama", "Drama", ContentType.SERIES)
    val seriesMystery = MediaCategory("s-mystery", "Mystery", ContentType.SERIES)
    val news = MediaCategory("l-news", "News", ContentType.LIVE)
    val sports = MediaCategory("l-sports", "Sports", ContentType.LIVE)

    private fun movie(id: String, name: String, category: MediaCategory, year: Int, rating: Double, addedDaysAgo: Int?) = Movie(
        id = id,
        name = name,
        categoryId = category.id,
        streamUrl = "http://example.com/movie/$id.mp4",
        rating = rating,
        year = year,
        addedAt = addedDaysAgo?.let { NOW / 1000 - it * 86_400L },
        plot = "$name: a fictional film used for tests and screenshots.",
    )

    val northernLights = movie("1", "Northern Lights", drama, 2025, 7.8, 1)
    val glassCity = movie("2", "Glass City", sciFi, 2024, 7.1, 2)
    val silentHarbor = movie("3", "Silent Harbor", thriller, 2023, 6.9, 3)
    val lastSignal = movie("4", "The Last Signal", sciFi, 2025, 8.1, 4)
    val summerInLisbon = movie("5", "Summer in Lisbon", drama, 2022, 7.3, 5)
    val midnightOrchard = movie("6", "Midnight Orchard", thriller, 2024, 6.8, 6)
    val longRoad = movie("7", "The Long Road", drama, 2021, 7.0, 7)
    val adultMovie = movie("8", "Late Show", adultMovies, 2025, 5.0, 0)

    private fun episodes(seriesId: String, seasons: Int, perSeason: Int) = (1..seasons).flatMap { season ->
        (1..perSeason).map { number ->
            Episode(
                id = "$seriesId-$season-$number",
                title = "Episode $number",
                season = season,
                number = number,
                streamUrl = "http://example.com/series/$seriesId/$season/$number.mp4",
                durationSeconds = 2_700,
            )
        }
    }

    val harborLights = Series(
        id = "s1", name = "Harbor Lights", categoryId = seriesDrama.id, plot = "A fishing town keeps its secrets.",
        rating = 8.2, year = 2025, genre = "Drama", addedAt = NOW / 1000 - 86_400, episodes = episodes("s1", 2, 3),
    )
    val theArchive = Series(
        id = "s2", name = "The Archive", categoryId = seriesMystery.id, plot = "Missing pages, missing people.",
        rating = 7.6, year = 2024, genre = "Mystery", addedAt = NOW / 1000 - 2 * 86_400, episodes = episodes("s2", 1, 4),
    )
    val blueHorizon = Series(
        id = "s3", name = "Blue Horizon", categoryId = seriesMystery.id, plot = "A signal from beyond the horizon.",
        rating = 7.2, year = 2023, genre = "Mystery, Science Fiction", addedAt = NOW / 1000 - 3 * 86_400, episodes = episodes("s3", 1, 3),
    )

    private fun channel(id: String, name: String, number: Int, category: MediaCategory) =
        Channel(id = id, name = name, number = number, categoryId = category.id, streamUrl = "http://example.com/live/$id.m3u8", epgId = "$id.test")

    val channels = listOf(
        channel("c1", "Ondo News", 1, news),
        channel("c2", "World Report", 2, news),
        channel("c3", "Stadium One", 3, sports),
        channel("c4", "Cycling TV", 4, sports),
    )

    val content = PlaylistContent(
        channels = channels,
        movies = listOf(northernLights, glassCity, silentHarbor, lastSignal, summerInLisbon, midnightOrchard, longRoad, adultMovie),
        series = listOf(harborLights, theArchive, blueHorizon),
        liveCategories = listOf(news, sports),
        movieCategories = listOf(drama, sciFi, thriller, adultMovies),
        seriesCategories = listOf(seriesDrama, seriesMystery),
        fetchedAt = NOW,
    )

    /** M3U series details, as the repository builds them from the episodes. */
    fun details(series: Series) = SeriesDetails(
        info = MediaDetails(title = series.name, plot = series.plot, genre = series.genre, rating = series.rating),
        seasons = series.episodes.groupBy { it.season }.toSortedMap().map { (number, episodes) -> Season(number, "Season $number", episodes) },
    )

    val playlist = SavedPlaylist(id = PLAYLIST_ID, name = "Home", url = "http://example.com/list.m3u", addedAt = NOW)

    fun state(library: LibraryState = LibraryState(), content: PlaylistContent = this.content, settings: AppSettings = AppSettings(activePlaylistId = PLAYLIST_ID)) = OndoState(
        isReady = true,
        settings = settings,
        playlists = listOf(playlist),
        content = content,
        library = library,
        seriesDetails = content.series.associate { it.id to details(it) },
    )
}
