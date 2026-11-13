package com.markedoe.ondo.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.markedoe.ondo.Fixtures
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.library.InMemoryMetadataStore
import com.markedoe.ondo.core.library.MetadataKey
import com.markedoe.ondo.core.library.TitleMetadata
import com.markedoe.ondo.data.AppSettings
import com.markedoe.ondo.data.AppStore
import com.markedoe.ondo.data.FakeHttp
import com.markedoe.ondo.data.OndoRepository
import com.markedoe.ondo.ui.components.LocalCarouselAutoAdvance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import org.junit.rules.TemporaryFolder
import com.markedoe.ondo.core.library.TmdbItem
import com.markedoe.ondo.core.library.TmdbRating
import com.markedoe.ondo.core.library.TrendingMatch
import com.markedoe.ondo.data.HomeSnapshot
import com.markedoe.ondo.ui.components.ContinueCard
import com.markedoe.ondo.ui.components.LocalPosterScores
import com.markedoe.ondo.ui.components.PosterCard
import com.markedoe.ondo.ui.components.SCORE_BADGE_TAG
import com.markedoe.ondo.ui.model.ResumeItem
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.screens.HomeActions
import com.markedoe.ondo.ui.screens.HomeScreen
import com.markedoe.ondo.ui.screens.HomeUiState
import com.markedoe.ondo.ui.screens.PosterGridScreen
import com.markedoe.ondo.ui.screens.VodActions
import com.markedoe.ondo.ui.screens.VodScreen
import com.markedoe.ondo.ui.theme.OndoTheme
import com.markedoe.ondo.ui.tv.TelevisionFrame
import com.markedoe.ondo.ui.tv.TvPosterCard
import com.markedoe.ondo.ui.tv.TvResumeCard
import java.io.File
import java.time.Duration
import java.util.Locale
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * TMDb's score on the thumbnails (`UiMappers.posterScores`): from what TMDb said this session and what the device kept,
 * the same as a TV's header for every title, nothing while unknown (never the provider's), in the app's language. Apple
 * has none yet.
 */
class PosterScoreMapperTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    private val northern = ShowcaseItem.MovieItem(Fixtures.northernLights)
    private val glass = ShowcaseItem.MovieItem(Fixtures.glassCity)
    private val harbor = ShowcaseItem.SeriesItem(Fixtures.harborLights)

    private fun kept(item: ShowcaseItem, average: Double, votes: Int = 1_200) =
        TitleMetadata(key = "kept-" + item.key, kind = "movie", voteAverage = average, voteCount = votes, fetchedAt = Fixtures.NOW)

    private fun trending(average: Double?, votes: Int?) =
        TrendingMatch.MovieMatch(Fixtures.northernLights, TmdbItem(1, TmdbItem.MediaType.MOVIE, "Northern Lights", voteAverage = average, voteCount = votes))

    /** Each title's badge says what the header says of it: TMDb's rating, or nothing when the header has the provider's. */
    private fun assertSameAsTheHeader(state: com.markedoe.ondo.data.OndoState, kept: Map<String, TitleMetadata>, locale: Locale = Locale.US) {
        val scores = UiMappers.posterScores(state, kept, locale)
        for (item in listOf(northern, glass, harbor)) {
            assertEquals(item.title, UiMappers.titleHeader(state, item, kept[item.key], locale).tmdbRating, scores[item.key])
        }
    }

    @Test
    fun theScoreComesFromTheKeptFactsAndMatchesTheHeader() {
        val state = Fixtures.state()
        val kept = mapOf(northern.key to kept(northern, 7.84), harbor.key to kept(harbor, 6.04))
        val scores = UiMappers.posterScores(state, kept, Locale.US)
        assertEquals("7.8", scores[northern.key])
        assertEquals("6.0", scores[harbor.key])
        // Unknown to TMDb: no badge, never the provider's rating (the header shows that one apart, as "★ 7.1").
        assertNull(scores[glass.key])
        assertEquals("7.1", UiMappers.titleHeader(state, glass, null, Locale.US).providerRating)
        assertSameAsTheHeader(state, kept)
    }

    @Test
    fun theAnswersOfTheSessionComeFirstAsInTheHeader() {
        val kept = mapOf(northern.key to kept(northern, 7.0))
        val answered = Fixtures.state().copy(tmdbRatings = mapOf(northern.key to TmdbRating(8.1, 900)), trending = listOf(trending(9.0, 500)))
        assertEquals("8.1", UiMappers.posterScores(answered, kept, Locale.US)[northern.key])
        assertSameAsTheHeader(answered, kept)
        val trendingOnly = answered.copy(tmdbRatings = emptyMap())
        assertEquals("9.0", UiMappers.posterScores(trendingOnly, kept, Locale.US)[northern.key])
        assertSameAsTheHeader(trendingOnly, kept)
        // A trending match without votes: what the device kept, as the header.
        val unvoted = trendingOnly.copy(trending = listOf(trending(null, null)))
        assertEquals("7.0", UiMappers.posterScores(unvoted, kept, Locale.US)[northern.key])
        assertSameAsTheHeader(unvoted, kept)
    }

    @Test
    fun aZeroScoreIsNoScore() {
        val state = Fixtures.state().copy(tmdbRatings = mapOf(northern.key to TmdbRating(0.0, 0)))
        assertNull(UiMappers.posterScores(state, emptyMap(), Locale.US)[northern.key])
        assertSameAsTheHeader(state, emptyMap())
    }

    @Test
    fun theScoreIsWrittenInTheAppsLanguage() {
        val kept = mapOf(northern.key to kept(northern, 7.84))
        assertEquals("7,8", UiMappers.posterScores(Fixtures.state(), kept, Locale.FRANCE)[northern.key])
        for (locale in listOf(Locale.FRANCE, Locale.forLanguageTag("ar"), Locale.forLanguageTag("hi-IN"), Locale.forLanguageTag("pt-BR"))) {
            assertSameAsTheHeader(Fixtures.state(), kept, locale)
        }
    }

    @Test
    fun onlyTheTitlesAskedFor() {
        val kept = mapOf(northern.key to kept(northern, 7.84), harbor.key to kept(harbor, 6.04))
        assertEquals(mapOf(northern.key to "7.8"), UiMappers.posterScores(Fixtures.state(), kept, Locale.US, only = setOf(northern.key, glass.key)))
    }

    /** Home kept for the next start carries its titles' scores; one kept before them still reads, without. */
    @Test
    fun homeKeptForTheNextStartCarriesItsScores() {
        val state = Fixtures.state()
        val home = HomeUiState(playlistName = "Home", newMovies = listOf(northern, glass), newSeries = listOf(harbor), hasVod = true)
        assertEquals(setOf(northern.key, glass.key, harbor.key), HomeSnapshots.keys(home))
        val scores = UiMappers.posterScores(state, mapOf(northern.key to kept(northern, 7.84)), Locale.US, only = HomeSnapshots.keys(home))
        val snapshot = requireNotNull(HomeSnapshots.of(home, state, Labels(locale = Locale.US), scores))
        assertEquals(mapOf(northern.key to "7.8"), snapshot.scores)
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(snapshot, json.decodeFromString(HomeSnapshot.serializer(), json.encodeToString(HomeSnapshot.serializer(), snapshot)))
        val older = json.decodeFromString(HomeSnapshot.serializer(), """{"playlistId":"p1","profileId":"main","fingerprint":"f","fetchedAt":1,"playlistName":"Home"}""")
        assertTrue(older.scores.isEmpty())
    }
}

/**
 * The score badge on a phone's thumbnails: in the poster's bottom start corner (mirrored right to left), above the
 * progress bar, beside the play icon of a card to continue watching, said by TalkBack with the title; none while unknown.
 * Every surface that draws thumbnails draws them with the cards that carry it ([surfacesDrawTheScoredCards]).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class PosterScoreBadgeTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val compose = createComposeRule()

    private val northern = ShowcaseItem.MovieItem(Fixtures.northernLights)
    private val glass = ShowcaseItem.MovieItem(Fixtures.glassCity)
    private val harbor = ShowcaseItem.SeriesItem(Fixtures.harborLights)

    /** Every title of the catalogue known to TMDb at 7.8. */
    private val everyScore = (Fixtures.content.movies.map { "m-" + it.id } + Fixtures.content.series.map { "s-" + it.id }).associateWith { "7.8" }

    @After
    fun forget() = UiMappers.forgetCatalogue()

    private fun badges() = compose.onAllNodesWithTag(SCORE_BADGE_TAG, useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }

    private fun card(title: String): Rect = compose.onAllNodes(hasContentDescription(title) and hasClickAction()).fetchSemanticsNodes().first().boundsInRoot

    @Test
    fun theBadgeShowsWhereTheScoreIsKnownAndSaysIt() {
        compose.setContent {
            OndoTheme {
                CompositionLocalProvider(LocalPosterScores provides mapOf(northern.key to "7.8", harbor.key to "6.0")) {
                    Column {
                        Row {
                            PosterCard(northern, 120.dp, onClick = {})
                            PosterCard(glass, 120.dp, onClick = {})
                        }
                        Row { PosterCard(harbor, 120.dp, onClick = {}, showsTitle = true) }
                    }
                }
            }
        }
        assertEquals("no badge without TMDb's score", 2, badges().size)
        // TalkBack: the title, then the score as the card's state.
        compose.onAllNodes(hasContentDescription("Northern Lights") and hasStateDescription("TMDb rating 7.8") and hasClickAction()).assertCountEquals(1)
        compose.onAllNodes(hasContentDescription("Harbor Lights") and hasStateDescription("TMDb rating 6.0") and hasClickAction()).assertCountEquals(1)
        val saysAScore = SemanticsMatcher("says a TMDb score") { it.config.getOrNull(SemanticsProperties.StateDescription)?.contains("TMDb rating") == true }
        compose.onAllNodes(saysAScore and hasContentDescription("Glass City")).assertCountEquals(0)
        // The badge adds nothing to the card's description: the tests and TalkBack find a card by its title.
        compose.onAllNodes(hasContentDescription("TMDb rating", substring = true)).assertCountEquals(0)
        val poster = card("Northern Lights")
        val badge = badges().first { poster.contains(it.center) }
        assertTrue("in the start half: $badge in $poster", badge.right < poster.center.x)
        assertTrue("in the bottom half: $badge in $poster", badge.top > poster.center.y)
        assertTrue("inside the poster", badge.left > poster.left && badge.bottom < poster.bottom)
    }

    @Test
    fun rightToLeftTheBadgeIsAtTheStartToo() {
        compose.setContent {
            OndoTheme {
                CompositionLocalProvider(LocalPosterScores provides mapOf(northern.key to "7.8"), LocalLayoutDirection provides LayoutDirection.Rtl) {
                    PosterCard(northern, 120.dp, onClick = {})
                }
            }
        }
        val poster = card("Northern Lights")
        val badge = badges().single()
        assertTrue("on the right of the poster: $badge in $poster", badge.left > poster.center.x)
        assertTrue(badge.top > poster.center.y)
    }

    @Test
    fun theBadgeStaysAboveTheProgressBar() {
        compose.setContent {
            OndoTheme {
                CompositionLocalProvider(LocalPosterScores provides mapOf(northern.key to "7.8", glass.key to "7.1")) {
                    Row {
                        PosterCard(northern, 120.dp, onClick = {}, progress = 0.5f)
                        PosterCard(glass, 120.dp, onClick = {})
                    }
                }
            }
        }
        val density = compose.density.density
        val inProgress = card("Northern Lights")
        val withBar = badges().first { inProgress.contains(it.center) }
        // The bar: 4 dp high, 8 dp from the poster's bottom.
        assertTrue("clear of the bar: $withBar in $inProgress", withBar.bottom <= inProgress.bottom - 12 * density)
        val plain = card("Glass City")
        val without = badges().first { plain.contains(it.center) }
        assertTrue("lower without a bar", plain.bottom - without.bottom < inProgress.bottom - withBar.bottom)
    }

    @Test
    fun aCardToContinueWatchingShowsItsTitlesScoreBesideThePlayIcon() {
        val card = ResumeItem("m-${Fixtures.northernLights.id}", "Northern Lights", "2025", null, progress = 0.4f, minutesLeft = 50, showcase = northern)
        val other = ResumeItem("m-${Fixtures.glassCity.id}", "Glass City", "2024", null, progress = 0.4f, minutesLeft = 50, showcase = glass)
        compose.setContent {
            OndoTheme {
                CompositionLocalProvider(LocalPosterScores provides mapOf(northern.key to "7.8")) {
                    Column {
                        ContinueCard(card, 240.dp, onClick = {})
                        ContinueCard(other, 240.dp, onClick = {})
                    }
                }
            }
        }
        val badge = badges().single()
        val picture = compose.onAllNodes(hasStateDescription("TMDb rating 7.8") and hasClickAction()).fetchSemanticsNodes().single().boundsInRoot
        assertTrue("in the start half: $badge in $picture", badge.right < picture.center.x)
        // The picture is 16:9 of the card's width, its bar at its bottom: the badge ends above the bar.
        val pictureBottom = picture.top + picture.width * 9f / 16f
        assertTrue("above the bar: $badge, picture down to $pictureBottom", badge.bottom < pictureBottom - 3 * compose.density.density)
    }

    @Test
    fun theScreensShowTheBadges() {
        val state = Fixtures.state()
        val movies = Fixtures.content.movies.map { ShowcaseItem.MovieItem(it) }
        val series = Fixtures.content.series.map { ShowcaseItem.SeriesItem(it) }
        val screens: List<Pair<String, @androidx.compose.runtime.Composable () -> Unit>> = listOf(
            "Home" to { HomeScreen(HomeUiState(playlistName = "Home", newMovies = movies, newSeries = series, hasVod = true), HomeActions(), autoAdvance = false) },
            "Movies" to { VodScreen(UiMappers.vod(state, series = false, title = "Movies", now = Fixtures.NOW), VodActions()) },
            "Series" to { VodScreen(UiMappers.vod(state, series = true, title = "Series", now = Fixtures.NOW), VodActions()) },
            "Grid" to { PosterGridScreen("Drama", movies, filter = "", onFilter = {}, onOpen = {}, onBack = {}) },
        )
        var shown by mutableStateOf(0)
        compose.setContent {
            OndoTheme { CompositionLocalProvider(LocalPosterScores provides everyScore) { screens[shown].second() } }
        }
        for (index in screens.indices) {
            compose.runOnIdle { shown = index }
            compose.waitForIdle()
            // Down to the first badge, wherever the page's list is.
            val badge = hasStateDescription("TMDb rating 7.8")
            for (list in compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().indices) {
                if (compose.onAllNodes(badge).fetchSemanticsNodes().isNotEmpty()) break
                runCatching { compose.onAllNodes(hasScrollToNodeAction())[list].performScrollToNode(badge) }
            }
            assertTrue("${screens[index].first} shows the badges", badges().isNotEmpty())
        }
    }

    companion object {
        /** The cards that draw a title's thumbnail, each showing its score: the posters, and the cards to continue watching. */
        val scoredCards = mapOf(
            "ui/components/Shelves.kt" to listOf("PosterCard", "ContinueCard"),
            "ui/tv/TvDesign.kt" to listOf("TvPosterCard", "TvResumeCard"),
        )

        /**
         * Every source file that shows cards to continue watching, and how many: each is a `ContinueCard` or a
         * `TvResumeCard`. A new one is added here; the posters' surfaces are [WatchedBadgeEverySurfaceTest.surfaces].
         */
        val resumeSurfaces = mapOf(
            "ui/screens/HomeScreen.kt" to 1, // Home's Continue watching
            "ui/screens/VodScreens.kt" to 1, // the Movies and Series tabs' Continue watching
            "ui/tv/TvHomeScreen.kt" to 2, // a TV's Home: the movies, the series in progress
        )

        private val resumeCard = Regex("""(?<![A-Za-z.])(ContinueCard|TvResumeCard)\(""")

        private val posterCard = Regex("""(?<![A-Za-z.])(PosterCard|TvPosterCard)\(""")
    }

    /**
     * Every surface with thumbnails, listed: the posters' ([WatchedBadgeEverySurfaceTest.surfaces], the same cards) and the
     * cards to continue watching ([resumeSurfaces]), all drawn by [scoredCards], each of which shows the score.
     */
    @Test
    fun surfacesDrawTheScoredCards() {
        val root = File("src/main/kotlin/com/markedoe/ondo")
        for ((path, cards) in scoredCards) {
            val source = File(root, path).readText()
            for (card in cards) {
                val start = source.indexOf("fun $card(")
                assertTrue("$card is in $path", start >= 0)
                val body = source.substring(start, source.indexOf("\n}\n", start))
                assertTrue("$card shows the score", body.contains("posterScore(") && body.contains("ScoreBadge("))
            }
        }
        fun calls(regex: Regex): Map<String, Int> = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.mapNotNull { file ->
            val source = file.readText()
            val calls = regex.findAll(source).count { match -> !source.substring(maxOf(0, match.range.first - 4), match.range.first).endsWith("fun ") }
            if (calls == 0) null else file.relativeTo(root).invariantSeparatorsPath to calls
        }.toMap()
        assertEquals("a surface with cards to continue watching is added to `resumeSurfaces`", resumeSurfaces, calls(resumeCard))
        // The posters' surfaces, the watched badge's: each draws them with PosterCard or TvPosterCard.
        assertEquals("a surface with posters is added to `WatchedBadgeEverySurfaceTest.surfaces`", WatchedBadgeEverySurfaceTest.surfaces, calls(posterCard))
    }
}

/**
 * On a TV: the badge on the posters and the cards to continue watching, the same corner; a score that arrives (what the
 * device kept, read again) shows without moving the focus or the cards.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h2400dp-television-xhdpi")
class TvPosterScoreTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val compose = createComposeRule()

    private val northern = ShowcaseItem.MovieItem(Fixtures.northernLights)
    private val glass = ShowcaseItem.MovieItem(Fixtures.glassCity)

    private fun badges() = compose.onAllNodesWithTag(SCORE_BADGE_TAG, useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }

    private fun card(title: String): Rect = compose.onAllNodes(hasContentDescription(title) and hasClickAction()).fetchSemanticsNodes().first().boundsInRoot

    @Test
    fun theScoreArrivesWithoutMovingAnything() {
        var scores by mutableStateOf(emptyMap<String, String>())
        compose.setContent {
            OndoTheme {
                TelevisionFrame {
                    CompositionLocalProvider(LocalPosterScores provides scores) {
                        Row {
                            TvPosterCard(northern, onClick = {})
                            TvPosterCard(glass, onClick = {})
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        assertTrue(badges().isEmpty())
        compose.onNode(hasContentDescription("Glass City") and hasClickAction()).requestFocus()
        compose.waitForIdle()
        val before = card("Glass City")
        compose.runOnIdle { scores = mapOf(northern.key to "7.8", glass.key to "7.1") }
        compose.waitForIdle()
        compose.onNode(hasContentDescription("Glass City") and hasClickAction()).assertIsFocused()
        assertEquals("the card stays where it was", before, card("Glass City"))
        assertEquals(2, badges().size)
        compose.onAllNodes(hasStateDescription("TMDb rating 7.1") and hasContentDescription("Glass City")).assertCountEquals(1)
        val poster = card("Northern Lights")
        val badge = badges().first { poster.contains(it.center) }
        assertTrue("bottom start: $badge in $poster", badge.right < poster.center.x && badge.top > poster.center.y)
    }

    @Test
    fun aTvCardToContinueWatchingShowsItsTitlesScore() {
        val card = ResumeItem("m-${Fixtures.northernLights.id}", "Northern Lights", "2025", null, progress = 0.4f, minutesLeft = 50, showcase = northern)
        compose.setContent {
            OndoTheme {
                TelevisionFrame {
                    CompositionLocalProvider(LocalPosterScores provides mapOf(northern.key to "7.8")) { TvResumeCard(card, onClick = {}) }
                }
            }
        }
        compose.waitForIdle()
        val picture = card("Northern Lights")
        val badge = badges().single()
        assertTrue("in the start half: $badge in $picture", badge.right < picture.center.x)
        assertTrue("above the bar: $badge in $picture", badge.bottom < picture.bottom - 4 * compose.density.density)
    }
}

/**
 * In the app: TMDb's score kept on the device reaches the view model ([OndoViewModel.posterScores], worked out off the
 * main thread) and the focused poster of the Movies tab, the same number as the header above it, said as the card's
 * state after its title; a title TMDb's score is unknown of shows none, its header the provider's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class PosterScoreAppTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val folder = TemporaryFolder()

    private val movieTitles = Fixtures.content.movies.map { it.name }.toSet()

    private lateinit var viewModel: OndoViewModel

    /** The app on a TV, TMDb's answer for Northern Lights kept on the device from an earlier launch (7.3). */
    private fun start() {
        val store = AppStore(folder.root)
        store.savePlaylists(listOf(Fixtures.playlist))
        store.saveSettings(AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID))
        store.saveContent(Fixtures.PLAYLIST_ID, Fixtures.content.copy(fetchedAt = System.currentTimeMillis()))
        val northern = Fixtures.northernLights
        val metadata = InMemoryMetadataStore()
        metadata.save(
            listOf(
                TitleMetadata(
                    MetadataKey.title(false, northern.name, northern.year)!!, "movie", tmdbId = 11, voteAverage = 7.3, voteCount = 950,
                    fetchedAt = System.currentTimeMillis(),
                ),
            ),
        )
        val repository = OndoRepository(store, FakeHttp(emptyMap()), CoroutineScope(SupervisorJob() + Dispatchers.Main), metadata = metadata)
        viewModel = OndoViewModel(repository, Labels(), minutes = flowOf(Fixtures.NOW))
        var inputModes: InputModeManager? = null
        compose.setContent {
            inputModes = LocalInputModeManager.current
            OndoTheme { CompositionLocalProvider(LocalCarouselAutoAdvance provides false) { OndoApp(viewModel) } }
        }
        compose.waitUntil(10_000) { exists("home") }
        compose.runOnIdle { inputModes?.requestInputMode(InputMode.Keyboard) }
        compose.mainClock.autoAdvance = false
        settle()
    }

    private fun settle(ms: Long = 300) {
        val deadline = System.currentTimeMillis() + ms
        do {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            compose.mainClock.advanceTimeBy(20)
            compose.waitForIdle()
        } while (System.currentTimeMillis() < deadline)
    }

    private fun waitFor(what: String, timeout: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what; the focus is on ${focused()}, scores ${viewModel.posterScores.value}" }
            settle(60)
        }
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun focusedNode(): SemanticsNode? = compose.onAllNodes(isFocused()).fetchSemanticsNodes().lastOrNull()

    private fun focusedTag(): String = focusedNode()?.config?.getOrNull(SemanticsProperties.TestTag).orEmpty()

    private fun focused(): String = focusedNode()?.let { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ")
            ?: node.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
    } ?: "(nothing)"

    private fun focusedState(): String? = focusedNode()?.config?.getOrNull(SemanticsProperties.StateDescription)

    /** The score badges drawn inside the focused card. */
    private fun focusedBadges() = compose.onAllNodes(hasTestTag(SCORE_BADGE_TAG) and hasAnyAncestor(isFocused()), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun press(key: Key) {
        compose.onAllNodes(isFocused()).onLast().performKeyInput { pressKey(key) }
        settle(150)
    }

    private fun pressUntil(key: Key, max: Int = 14, done: () -> Boolean) {
        repeat(max) {
            if (done()) return
            press(key)
        }
        check(done()) { "never reached with $key; the focus is on ${focused()}" }
    }

    private fun header(tag: String): List<String> =
        compose.onAllNodes(hasTestTag(tag) and hasAnyAncestor(hasTestTag("titleHeader")), useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text } }

    private fun reach(title: String) {
        for (key in listOf(Key.DirectionRight, Key.DirectionLeft, Key.DirectionDown)) {
            repeat(10) {
                if (focused() == title) return
                press(key)
            }
        }
        check(focused() == title) { "never reached $title; the focus is on ${focused()}" }
    }

    @Test
    fun theKeptScoreReachesThePosterAndMatchesTheHeader() {
        start()
        val key = "m-" + Fixtures.northernLights.id
        waitFor("the kept score in the view model") { viewModel.posterScores.value[key] == "7.3" }
        assertEquals("only the title TMDb answered for", setOf(key), viewModel.posterScores.value.keys)
        pressUntil(Key.DirectionUp, max = 20) { focusedTag().startsWith("tab") }
        pressUntil(Key.DirectionRight) { focusedTag() == "tabMovies" }
        press(Key.DirectionCenter)
        waitFor("the Movies tab") { exists("vod") && !focusedTag().startsWith("tab") }
        pressUntil(Key.DirectionDown, max = 20) { focused() in movieTitles }
        reach("Northern Lights")
        waitFor("the header of Northern Lights") { header("headerTitle") == listOf("Northern Lights") && header("headerRating").isNotEmpty() }
        assertEquals("the header says TMDb's", listOf("7.3"), header("headerRating"))
        assertEquals("the poster's badge, the same", 1, focusedBadges())
        assertEquals("said after the title", "TMDb rating 7.3", focusedState())
        // Unknown to TMDb: no badge, the header the provider's.
        reach("Glass City")
        waitFor("the header of Glass City") { header("headerTitle") == listOf("Glass City") }
        assertEquals(listOf("★ 7.1"), header("headerRating"))
        assertEquals(0, focusedBadges())
        assertEquals(null, focusedState())
    }
}
