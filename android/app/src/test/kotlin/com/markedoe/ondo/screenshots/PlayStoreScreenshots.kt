package com.markedoe.ondo.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.data.Favorite
import com.markedoe.ondo.data.ItemKind
import com.markedoe.ondo.data.LibraryState
import com.markedoe.ondo.data.WatchEntry
import com.markedoe.ondo.ui.Labels
import com.markedoe.ondo.ui.OndoNavigationFrame
import com.markedoe.ondo.ui.TopLevel
import com.markedoe.ondo.ui.UiMappers
import com.markedoe.ondo.ui.components.LocalCarouselAutoAdvance
import com.markedoe.ondo.ui.screens.DetailActions
import com.markedoe.ondo.ui.screens.HomeActions
import com.markedoe.ondo.ui.screens.HomeScreen
import com.markedoe.ondo.ui.screens.LiveActions
import com.markedoe.ondo.ui.screens.LiveScreen
import com.markedoe.ondo.ui.screens.MovieDetailScreen
import com.markedoe.ondo.ui.screens.SeriesDetailScreen
import com.markedoe.ondo.ui.screens.VodActions
import com.markedoe.ondo.ui.screens.VodScreen
import com.markedoe.ondo.ui.theme.OndoTheme
import com.markedoe.ondo.ui.tv.TelevisionFrame
import com.markedoe.ondo.ui.tv.TvHomeScreen
import com.markedoe.ondo.ui.tv.TvMovieDetailScreen
import com.markedoe.ondo.ui.tv.TvNavigationFrame
import com.markedoe.ondo.ui.tv.TvPlace
import com.markedoe.ondo.ui.tv.TvSeriesDetailScreen
import com.markedoe.ondo.ui.tv.tvSafeArea
import java.util.Locale
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

/**
 * Google Play listing screenshots, from the fixture catalogue: phone (1080×1920), 7" (1920×1080) and
 * 10" (2560×1440) tablets and TV (1920×1080, with its drawer and its own pages), in English and French. Written to `src/test/screenshots/play/<locale>/<device>/`
 * by `./gradlew :app:recordRoborazziDebug`; the release workflow copies them into the store listing.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayStoreScreenshots(private val device: Device, private val locale: String) {
    enum class Device(val folder: String, val qualifiers: String) {
        PHONE("phoneScreenshots", "w360dp-h640dp-xxhdpi"),
        SEVEN_INCH("sevenInchScreenshots", "w960dp-h540dp-land-xhdpi"),
        TEN_INCH("tenInchScreenshots", "w1280dp-h720dp-land-xhdpi"),
        TV("tvScreenshots", "w960dp-h540dp-land-television-xhdpi"),
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} {1}")
        fun parameters(): List<Array<Any>> = Device.entries.flatMap { device -> listOf("en-US", "fr-FR").map { arrayOf<Any>(device, it) } }
    }

    private val french get() = locale.startsWith("fr")
    private val labels get() = if (french) Labels("Film", "Série", Locale.FRANCE) else Labels("Movie", "Series", Locale.US)

    /** Something in progress, My List and favourite channels, like a catalogue in use. */
    private val library: LibraryState
        get() {
            val movie = Fixtures.glassCity
            val episode = Fixtures.harborLights.episodes[1]
            // Subtitles as the repository writes them: the movie's year, the episode's code and title.
            val watchedMovie = WatchEntry(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, movie.id, title = movie.name, subtitle = movie.year?.toString(), lastWatchedAt = 2)
            val watchedEpisode = WatchEntry(Fixtures.PLAYLIST_ID, ItemKind.SERIES, Fixtures.harborLights.id, episode.id, title = Fixtures.harborLights.name, subtitle = "${episode.code} · ${episode.title}", lastWatchedAt = 1)
            return LibraryState()
                .recordStart(watchedMovie).updateProgress(watchedMovie.key, 2_400.0, 6_000.0, now = 3)
                .recordStart(watchedEpisode).updateProgress(watchedEpisode.key, 900.0, 2_700.0, now = 2)
                .toggleFavorite(Favorite(Fixtures.PLAYLIST_ID, ItemKind.MOVIE, Fixtures.lastSignal.id, Fixtures.lastSignal.name, addedAt = 1))
                .toggleFavorite(Favorite(Fixtures.PLAYLIST_ID, ItemKind.SERIES, Fixtures.theArchive.id, Fixtures.theArchive.name, addedAt = 2))
                .toggleFavorite(Favorite(Fixtures.PLAYLIST_ID, ItemKind.CHANNEL, "c1", "Ondo News", addedAt = 3))
                .toggleFavorite(Favorite(Fixtures.PLAYLIST_ID, ItemKind.CHANNEL, "c3", "Stadium One", addedAt = 4))
        }

    private val state get() = Fixtures.state(library)

    private val tv get() = device == Device.TV

    /** On a TV, the drawer beside the tabs' pages ([drawer]); the pages beyond them take the whole screen. */
    private fun capture(name: String, tab: TopLevel, drawer: Boolean = true, content: @Composable () -> Unit) {
        val language = locale.substringBefore('-')
        val region = locale.substringAfter('-')
        RuntimeEnvironment.setQualifiers("$language-r$region-${device.qualifiers}")
        captureRoboImage(
            filePath = "src/test/screenshots/play/$locale/${device.folder}/$name.png",
            roborazziOptions = RoborazziOptions(recordOptions = RoborazziOptions.RecordOptions(resizeScale = 1.0)),
        ) {
            OndoTheme {
                CompositionLocalProvider(LocalCarouselAutoAdvance provides false) {
                    TelevisionFrame {
                        if (tv) {
                            TvNavigationFrame(TvPlace.Tab(tab), showDrawer = drawer, onSelect = {}, onSearch = {}, onSettings = {}) { content() }
                        } else {
                            OndoNavigationFrame(tab, onSelect = {}) { content() }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun home() = capture("01-home", TopLevel.HOME) {
        val home = UiMappers.home(state, labels, Fixtures.NOW).copy(channels = emptyList())
        if (tv) TvHomeScreen(home, HomeActions()) else HomeScreen(home, HomeActions())
    }

    @Test
    fun movie() = capture("02-movie", TopLevel.MOVIES, drawer = false) {
        val movie = UiMappers.movieDetail(state, Fixtures.glassCity)
        if (tv) TvMovieDetailScreen(movie, DetailActions()) else MovieDetailScreen(movie, DetailActions())
    }

    @Test
    fun series() = capture("03-series", TopLevel.SERIES, drawer = false) {
        val series = UiMappers.seriesDetail(state, Fixtures.harborLights, null, null)
        if (tv) TvSeriesDetailScreen(series, DetailActions()) else SeriesDetailScreen(series, DetailActions())
    }

    @Test
    fun movies() = capture("04-movies", TopLevel.MOVIES) {
        Box(Modifier.tvSafeArea(besideDrawer = true)) {
            VodScreen(UiMappers.vod(state, series = false, title = if (french) "Films" else "Movies"), VodActions())
        }
    }

    @Test
    fun live() = capture("05-live", TopLevel.LIVE) {
        val programmes = if (french) {
            listOf("Le journal" to "Météo", "Grand reportage" to "Le débat", "Finale de la coupe" to "Le résumé", "Tour des Alpes" to "Arrivée")
        } else {
            listOf("Evening News" to "Weather", "World in Focus" to "The Debate", "Cup Final" to "Highlights", "Alpine Tour" to "Stage Finish")
        }
        val live = UiMappers.live(state, null, false, Fixtures.NOW)
        Box(Modifier.tvSafeArea(besideDrawer = true)) {
            LiveScreen(
                live.copy(channels = live.channels.mapIndexed { index, row ->
                    row.copy(nowTitle = programmes[index].first, nowProgress = 0.2f + 0.18f * index, nextTitle = programmes[index].second)
                }),
                LiveActions(),
            )
        }
    }
}
