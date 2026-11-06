package com.markedoe.ondo.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.library.TmdbRating
import com.markedoe.ondo.ui.components.LocalCarouselAutoAdvance
import com.markedoe.ondo.ui.screens.HomeActions
import com.markedoe.ondo.ui.screens.HomeScreen
import com.markedoe.ondo.ui.theme.OndoTheme
import com.markedoe.ondo.ui.tv.OndoTvTheme
import com.markedoe.ondo.ui.tv.TvHomeScreen
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression guard for the carousel, on every surface that shows it: it goes by release date (a movie's release, a
 * series' latest episode), not by the day the provider added a title. An old series with a new season is in; an old
 * series with nothing new, old films (however well rated or trending), a release to come and a hidden category are out;
 * the best weighted rating comes first, and the facts say when it came out (a series: "New episode"). The Apple apps have the same test (`CarouselReleasesEverySurfaceTests`). A new
 * screen that shows the carousel is added to [surfaces] here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class CarouselReleasesEverySurfaceTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val votes = mapOf(
        "m-4" to TmdbRating(8.0, 4_000),
        "s-s1" to TmdbRating(7.9, 800),
        "s-s3" to TmdbRating(7.9, 800),
        "m-1" to TmdbRating(7.3, 12_345),
        "m-3" to TmdbRating(8.0, 50),
        "m-2" to TmdbRating(10.0, 10),
        // Old, or out of the window, or hidden, whatever their votes.
        "s-s2" to TmdbRating(9.5, 50_000),
        "m-5" to TmdbRating(9.5, 50_000),
        "m-7" to TmdbRating(9.0, 50_000),
        "m-6" to TmdbRating(9.0, 9_000),
        "m-8" to TmdbRating(9.0, 9_000),
    )

    private val home by lazy {
        val state = Fixtures.state().copy(tmdbRatings = votes)
        UiMappers.home(state, Labels("Movie", "Series", Locale.US), Fixtures.NOW, Fixtures.kept())
    }

    /** Every screen that shows Home's carousel, with the tag of its frame. */
    private val surfaces: List<Pair<String, @Composable () -> Unit>> = listOf(
        "hero" to { OndoTheme { HomeScreen(home, HomeActions(), autoAdvance = false) } },
        "heroFrame" to { OndoTvTheme { TvHomeScreen(home, HomeActions(), autoAdvance = false) } },
    )

    private fun shows(title: String, frame: String): Boolean =
        compose.onAllNodes((hasText(title, substring = true) or hasContentDescription(title, substring = true)) and hasAnyAncestor(hasTestTag(frame)), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()

    @Test
    fun everyCarouselGoesByReleaseDate() {
        assertEquals(
            "the list every surface draws",
            listOf("The Last Signal", "Harbor Lights", "Northern Lights", "Blue Horizon", "Silent Harbor", "Glass City"),
            home.hero.map { it.item.title },
        )
        // The facts say when it came out, and why a series is there (its latest episode, not the year it started).
        assertEquals("Movie · 7 Oct · ★ 8.1 · TMDb 8.0 (4,000)", home.hero.first().meta)
        assertEquals("Series · New episode · 5 Oct · Mystery · ★ 7.2 · TMDb 7.9 (800)", home.hero.first { it.item.title == "Blue Horizon" }.meta)
        var shown by mutableIntStateOf(0)
        compose.setContent { CompositionLocalProvider(LocalCarouselAutoAdvance provides false) { surfaces[shown].second() } }
        for ((index, surface) in surfaces.withIndex()) {
            shown = index
            compose.waitForIdle()
            val frame = surface.first
            assertTrue("$frame: the best rated recent release first", shows("The Last Signal", frame))
            assertTrue("$frame: the day it came out", shows("7 Oct", frame))
            for (title in listOf("The Archive", "Summer in Lisbon", "The Long Road", "Midnight Orchard", "Late Show")) {
                assertTrue("$frame shows $title", !shows(title, frame))
            }
        }
    }
}
