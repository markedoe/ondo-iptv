package com.markedoe.ondo

import com.markedoe.ondo.core.library.InMemoryMetadataStore
import com.markedoe.ondo.core.library.MetadataKey
import com.markedoe.ondo.core.library.MetadataStore
import com.markedoe.ondo.core.library.TitleMetadata
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
import java.time.Instant
import java.time.ZoneId

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

    /** The day [daysAgo] days before [NOW] where the tests run, as TMDb writes a date ("2025-10-06"). */
    fun releasedDaysAgo(daysAgo: Long): String = Instant.ofEpochMilli(NOW).atZone(ZoneId.systemDefault()).toLocalDate().minusDays(daysAgo).toString()

    /**
     * When TMDb says the titles came out, in days before [NOW] (a series: its latest episode), by poster key: the carousel
     * goes by it ([com.markedoe.ondo.data.Showcase.heroItems]). Released lately: Harbor Lights (yesterday), The Last
     * Signal (2 days), Northern Lights (3), Blue Horizon (a 2023 series with a new season 4 days ago), Glass City (5),
     * Silent Harbor (20). Not: The Archive (nothing new for over a year), Summer in Lisbon (2022), The Long Road (2021),
     * Midnight Orchard (in ten days); Late Show (yesterday) is in the hidden adult category.
     */
    val releaseDaysAgo: Map<String, Long> = mapOf(
        "s-s1" to 1L, "m-4" to 2L, "m-1" to 3L, "s-s3" to 4L, "m-2" to 5L, "m-3" to 20L,
        "s-s2" to 400L, "m-5" to 1_200L, "m-7" to 1_500L, "m-6" to -10L, "m-8" to 1L,
    )

    /**
     * What TMDb said of [content]'s titles, as the device keeps it, by poster key: their release ([releaseDaysAgo]) and a
     * backdrop, no votes (a test adds them). Kept under each title's name and year ([MetadataKey.title]).
     */
    fun kept(content: PlaylistContent = this.content): Map<String, TitleMetadata> {
        val titles = content.movies.map { Triple("m-" + it.id, MetadataKey.title(false, it.name, it.year), false) } +
            content.series.map { Triple("s-" + it.id, MetadataKey.title(true, it.name, it.year), true) }
        return titles.mapNotNull { (key, storeKey, series) ->
            val days = releaseDaysAgo[key] ?: return@mapNotNull null
            key to TitleMetadata(
                key = storeKey ?: return@mapNotNull null, kind = MetadataKey.kind(series), fetchedAt = NOW, genres = emptyList(),
                backdropUrl = "https://img.example.com/backdrop/$key.jpg", releaseDate = releasedDaysAgo(days),
            )
        }.toMap()
    }

    /** A store that keeps [kept]: the app's Home finds its carousel's release dates in it, as after TMDb answered. */
    fun metadata(content: PlaylistContent = this.content): InMemoryMetadataStore = InMemoryMetadataStore().apply { save(kept(content).values.toList()) }

    /**
     * What TMDb said of every title of [content], by poster key: released lately (the playlist's first title on the day of
     * [now], each next one a day earlier, back to a month), with a backdrop, no votes. Any catalogue's carousel then
     * features its titles, the newest releases first.
     */
    fun released(content: PlaylistContent, now: Long = NOW): Map<String, TitleMetadata> {
        val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        val titles = content.movies.map { Triple("m-" + it.id, MetadataKey.title(false, it.name, it.year), false) } +
            content.series.map { Triple("s-" + it.id, MetadataKey.title(true, it.name, it.year), true) }
        return titles.withIndex().mapNotNull { (index, title) ->
            val (key, storeKey, series) = title
            key to TitleMetadata(
                key = storeKey ?: return@mapNotNull null, kind = MetadataKey.kind(series), fetchedAt = now, genres = emptyList(),
                backdropUrl = "https://img.example.com/backdrop/$key.jpg", releaseDate = today.minusDays(index % 30L).toString(),
            )
        }.toMap()
    }

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

/**
 * A store where TMDb already answered for every title of the catalogue on screen ([content], read when Home reads the
 * store), as [Fixtures.released] at [now]: Home's carousel, which goes by release date, features them.
 */
class ReleasedLately(
    private val now: () -> Long = { Fixtures.NOW },
    private val base: InMemoryMetadataStore = InMemoryMetadataStore(),
    private val content: () -> PlaylistContent,
) : MetadataStore by base {
    override fun namedTitles(): List<TitleMetadata> = base.namedTitles() + Fixtures.released(content(), now()).values
}
