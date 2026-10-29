package com.markedoe.ondo.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.markedoe.ondo.data.ItemKind
import com.markedoe.ondo.ui.Labels
import com.markedoe.ondo.ui.OndoNavigationFrame
import com.markedoe.ondo.ui.TopLevel
import com.markedoe.ondo.ui.UiMappers
import com.markedoe.ondo.ui.components.LocalCarouselAutoAdvance
import com.markedoe.ondo.ui.screens.DetailActions
import com.markedoe.ondo.ui.screens.GuideActions
import com.markedoe.ondo.ui.screens.GuideScreen
import com.markedoe.ondo.ui.screens.HomeActions
import com.markedoe.ondo.ui.screens.HomeScreen
import com.markedoe.ondo.ui.screens.LiveActions
import com.markedoe.ondo.ui.screens.LiveScreen
import com.markedoe.ondo.ui.screens.MovieDetailScreen
import com.markedoe.ondo.ui.screens.PersonScreen
import com.markedoe.ondo.ui.screens.SearchScreen
import com.markedoe.ondo.ui.screens.SeriesDetailScreen
import com.markedoe.ondo.ui.screens.VodActions
import com.markedoe.ondo.ui.screens.VodScreen
import com.markedoe.ondo.ui.theme.OndoTheme
import com.markedoe.ondo.ui.tv.TelevisionFrame
import com.markedoe.ondo.ui.tv.TvHomeScreen
import com.markedoe.ondo.ui.tv.TvMovieDetailScreen
import com.markedoe.ondo.ui.tv.TvNavigationFrame
import com.markedoe.ondo.ui.tv.TvSearchScreen
import com.markedoe.ondo.ui.tv.TvSeriesDetailScreen
import com.markedoe.ondo.ui.tv.tvSafeArea
import java.util.Locale
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

/**
 * Google Play listing screenshots, from the demo catalogue ([ShowcaseCatalogue]: open films with their pictures,
 * fictional channels and their guide, a library in use): phone (1080×1920), 7" (1920×1080) and 10" (2560×1440)
 * tablets and TV (1920×1080, with its top bar and its own pages), in English and French. Written to
 * `src/test/screenshots/play/<locale>/<device>/` by `./gradlew :app:recordRoborazziDebug`; the release workflow copies
 * them into the store listing.
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

    private val catalogue get() = ShowcaseCatalogue.load(french)
    private val state get() = catalogue.state()
    private val now = ShowcaseCatalogue.NOW

    private val tv get() = device == Device.TV

    @Before
    fun pictures() = ShowcaseImages.install(ApplicationProvider.getApplicationContext())

    @After
    fun noPictures() = ShowcaseImages.reset()

    /** On a TV, the top bar above the tabs' pages ([bar]); the pages beyond them take the whole screen. */
    private fun capture(name: String, tab: TopLevel, bar: Boolean = true, content: @Composable () -> Unit) {
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
                            TvNavigationFrame(tab, showBar = bar, onSelect = {}, onSearch = {}, onSettings = {}) { content() }
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
        val home = UiMappers.home(state, labels, now).copy(channels = emptyList())
        if (tv) TvHomeScreen(home, HomeActions()) else HomeScreen(home, HomeActions())
    }

    @Test
    fun movie() = capture("02-movie", TopLevel.MOVIES, bar = false) {
        val movie = UiMappers.movieDetail(state, catalogue.movie(catalogue.screens.movie))
        if (tv) TvMovieDetailScreen(movie, DetailActions()) else MovieDetailScreen(movie, DetailActions())
    }

    @Test
    fun series() = capture("03-series", TopLevel.SERIES, bar = false) {
        val series = UiMappers.seriesDetail(state, catalogue.series(catalogue.screens.series), null, null)
        if (tv) TvSeriesDetailScreen(series, DetailActions()) else SeriesDetailScreen(series, DetailActions())
    }

    @Test
    fun movies() = capture("04-movies", TopLevel.MOVIES) {
        Box(Modifier.tvSafeArea(tab = true)) {
            // Without the movies in progress, so that My List shows above the fold of a TV.
            val listed = if (tv) state.copy(library = state.library.copy(history = state.library.history.filter { it.kind != ItemKind.MOVIE })) else state
            VodScreen(UiMappers.vod(listed, series = false, title = if (french) "Films" else "Movies"), VodActions())
        }
    }

    @Test
    fun live() = capture("05-live", TopLevel.LIVE) {
        Box(Modifier.tvSafeArea(tab = true)) {
            LiveScreen(UiMappers.live(state, null, false, now), LiveActions())
        }
    }

    @Test
    fun guide() = capture("06-guide", TopLevel.LIVE, bar = false) {
        Box(Modifier.tvSafeArea()) {
            GuideScreen(UiMappers.guide(state, null, false, now), GuideActions())
        }
    }

    @Test
    fun search() = capture("07-search", TopLevel.HOME, bar = false) {
        val index = UiMappers.SearchIndex.of(state.content, UiMappers.hiddenCategoryIds(state))
        val results = UiMappers.search(index, catalogue.searchQuery, state, now)
        Box(Modifier.tvSafeArea()) {
            if (tv) TvSearchScreen(results, {}, {}, {}, {}, {}) else SearchScreen(results, onQuery = {}, onOpen = {}, onPlayChannel = {})
        }
    }

    @Test
    fun person() = capture("08-person", TopLevel.HOME, bar = false) {
        Box(Modifier.tvSafeArea()) {
            PersonScreen(UiMappers.person(state, catalogue.screens.person), onOpen = {}, onBack = {})
        }
    }
}
