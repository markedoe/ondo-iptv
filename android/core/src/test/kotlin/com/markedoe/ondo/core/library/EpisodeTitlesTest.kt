package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.HangGuard
import com.markedoe.ondo.core.MockHttpClient
import com.markedoe.ondo.core.model.Episode
import com.markedoe.ondo.core.model.MediaDetails
import com.markedoe.ondo.core.model.Season
import com.markedoe.ondo.core.model.SeriesDetails
import com.markedoe.ondo.core.model.XtreamCredentials
import com.markedoe.ondo.core.xtream.XtreamClient
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Rule

/** An episode's own title on every screen, and TMDb's stills and names for the episodes a provider leaves bare. */
class EpisodeTitlesTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    private val jackRyan = "|EN| Tom Clancy's Jack Ryan"

    @Test
    fun aProvidersNameWithOnlyTheSeriesAndCodeIsNoTitle() {
        // Before: the provider's whole name ("1. |EN| Tom Clancy's Jack …" on the page).
        assertEquals("Episode 2", EpisodeTitles.clean("|EN| Tom Clancy's Jack Ryan S03E02", jackRyan, 2))
        assertEquals("Episode 1", EpisodeTitles.clean("|EN| Tom Clancy's Jack Ryan S03E01", null, 1))
        assertEquals("Episode 4", EpisodeTitles.clean("|EN| Tom Clancy's Jack Ryan", jackRyan, 4), "the series' name alone")
        assertEquals("Episode 5", EpisodeTitles.clean("", jackRyan, 5))
        assertEquals("Episode 3", EpisodeTitles.clean("Episode 03", jackRyan, 3))
        assertEquals("Episode 3", EpisodeTitles.clean("E03", jackRyan, 3))
    }

    @Test
    fun anEpisodesOwnTitleKeepsWithoutTheSeriesNameOrTags() {
        assertEquals("Pilot", EpisodeTitles.clean("Copper Valley - S01E01 - Pilot", "Copper Valley", 1))
        assertEquals("The Big Fish", EpisodeTitles.clean("|EN| Tom Clancy's Jack Ryan S01E01 The Big Fish", jackRyan, 1))
        assertEquals("The Big Fish", EpisodeTitles.clean("Tom Clancy's Jack Ryan - The Big Fish", jackRyan, 1))
        assertEquals("The Big Fish", EpisodeTitles.clean("[EN] The Big Fish", jackRyan, 1))
        assertEquals("Cat's in the Bag...", EpisodeTitles.clean("Cat's in the Bag...", "Copper Valley", 2))
        // A title that only starts like the series' name is the episode's own.
        assertEquals("Copper Valleys", EpisodeTitles.clean("Copper Valleys", "Copper Valley", 1))
    }

    /** "Episode N" is the core's name: the app shows it in its language, "Épisode 2" in French. */
    @Test
    fun anUntitledEpisodeReadsInTheAppsLanguage() {
        val french = PlaceholderNames("Sans catégorie", "Autres", "Autres %1\$d", "Chaîne %1\$d", "Film %1\$d", "Série %1\$d", "Épisode %1\$d", Locale.FRANCE)
        val episode = Episode("e", EpisodeTitles.clean("|EN| Tom Clancy's Jack Ryan S03E02", jackRyan, 2), 3, 2, "http://example.com/e.mp4")
        assertEquals("Épisode 2", french.rename(episode).title)
    }

    /** The regression on Xtream panels: the details' episodes come without the series' name and code. */
    @Test
    fun xtreamEpisodesNamedOnlyByTheirCodeReadAsTheirNumber() = runTest {
        val http = MockHttpClient()
        http.routes["api:get_series_info"] = (
            """{"info":{"name":"|EN| Tom Clancy's Jack Ryan"},"episodes":{"3":[""" +
                """{"id":"1","episode_num":1,"title":"|EN| Tom Clancy's Jack Ryan S03E01","container_extension":"mp4"},""" +
                """{"id":"2","episode_num":2,"title":"|EN| Tom Clancy's Jack Ryan S03E02 - Falcon","container_extension":"mp4"}]}}"""
            ).encodeToByteArray()
        val details = XtreamClient(XtreamCredentials("http://panel.example.com", "viewer", "example"), http).seriesDetails(7)
        assertEquals(listOf("Episode 1", "Falcon"), details.seasons.single().episodes.map { it.title })
    }

    @Test
    fun tmdbFillsTheStillsAndNamesTheProviderLeavesOut() {
        fun episode(number: Int, poster: String?, title: String = "$jackRyan S03E0$number") =
            Episode("e$number", title, 3, number, "http://example.com/$number.mp4", posterUrl = poster)
        val details = SeriesDetails(
            MediaDetails(title = jackRyan),
            listOf(
                Season(2, "Season 2", listOf(Episode("s2e1", "Pilot", 2, 1, "http://example.com/s2e1.mp4"))),
                Season(
                    3,
                    "Season 3",
                    listOf(
                        episode(1, "http://example.com/provider-1.jpg"),
                        episode(2, null),
                        episode(3, ""),
                        episode(4, "http://example.com/provider-4.jpg", title = "Own Title"),
                        episode(5, null),
                    ),
                ),
            ),
        )
        val tmdb = mapOf(
            3 to listOf(
                TmdbEpisode(1, "Falcon", "/s1.jpg"),
                TmdbEpisode(2, "Paralysis", "/s2.jpg"),
                TmdbEpisode(3, "Episode 3", "/s3.jpg", overview = "TMDb's plot"),
                TmdbEpisode(4, null, "/s4.jpg"),
                // Episode 5: TMDb has no still either.
                TmdbEpisode(5, null, null),
            ),
        )
        val merged = EpisodeTitles.merge(details, jackRyan, tmdb).seasons.single { it.number == 3 }.episodes
        assertEquals(
            listOf(
                "http://example.com/provider-1.jpg",
                "https://image.tmdb.org/t/p/w300/s2.jpg",
                "https://image.tmdb.org/t/p/w300/s3.jpg",
                "http://example.com/provider-4.jpg",
                null,
            ),
            merged.map { it.posterUrl },
            "the provider's picture first, TMDb's still where it gave none, the placeholder when neither has one",
        )
        assertEquals(listOf("Falcon", "Paralysis", "Episode 3", "Own Title", "Episode 5"), merged.map { it.title })
        assertEquals("TMDb's plot", merged[2].plot)
        // Matched by number in the same season, never by position or another season's.
        assertEquals("Pilot", EpisodeTitles.merge(details, jackRyan, tmdb).seasons.first().episodes.single().title)
        val clean = SeriesDetails(MediaDetails(title = "x"), listOf(Season(1, "Season 1", listOf(Episode("a", "Pilot", 1, 1, "http://example.com/a.mp4", posterUrl = "http://example.com/a.jpg")))))
        assertSame(clean, EpisodeTitles.merge(clean, "x", emptyMap()), "nothing to change: the same instance")
    }

    @Test
    fun aSeasonsEpisodesComeInOneRequest() = runTest {
        val http = MockHttpClient()
        http.routes["https://api.themoviedb.org/3/tv/73375/season/3?language=fr-FR&api_key=KEY"] = (
            """{"id":1,"season_number":3,"episodes":[{"episode_number":1,"name":"Falcon","still_path":"/s1.jpg","overview":"x"},""" +
                """{"episode_number":2,"name":"Paralysie","still_path":null},{"name":"no number"}]}"""
            ).encodeToByteArray()
        val episodes = TmdbClient("KEY", http, language = "fr-FR").seasonEpisodes(73375, 3)
        assertEquals(listOf(TmdbEpisode(1, "Falcon", "/s1.jpg", "x"), TmdbEpisode(2, "Paralysie", null, null)), episodes)
        assertEquals(1, http.requested.size, "one request for the whole season")
        assertTrue(TmdbClient("KEY", http).seasonEpisodes(73375, 9).isEmpty(), "a season TMDb does not have: none")
    }

    /**
     * A series' cast on TMDb is every season's (`aggregate_credits`), not only its latest season's (`credits`), and its
     * makers are its creators (`created_by`), with their photos.
     */
    @Test
    fun aSeriesCreditsAreEverySeasonsAndItsCreators() = runTest {
        val http = MockHttpClient()
        http.routes["https://api.themoviedb.org/3/tv/73375?append_to_response=aggregate_credits,videos,recommendations,images&include_video_language=en,null&include_image_language=en,null&language=en-US&api_key=KEY"] = (
            """{"id":73375,"name":"Tom Clancy's Jack Ryan","created_by":[{"id":1,"name":"Carlton Cuse","profile_path":"/cc.jpg"},""" +
                """{"id":2,"name":"Graham Roland","profile_path":"/gr.jpg"}],""" +
                """"aggregate_credits":{"cast":[{"id":4,"name":"Nina Hoss","profile_path":"/nh.jpg","order":7},""" +
                """{"id":3,"name":"John Krasinski","profile_path":"/jk.jpg","order":0}],""" +
                """"crew":[{"id":5,"name":"Episode Director","jobs":[{"job":"Director"}]}]}}"""
            ).encodeToByteArray()
        val info = TmdbClient("KEY", http).titleInfo(series = true, tmdbId = "73375", title = "Tom Clancy's Jack Ryan", year = null)!!
        assertEquals(listOf("Carlton Cuse", "Graham Roland"), info.directors.map { it.name })
        assertEquals(listOf("https://image.tmdb.org/t/p/w185/cc.jpg", "https://image.tmdb.org/t/p/w185/gr.jpg"), info.directors.map { it.profileUrl })
        assertEquals(listOf("John Krasinski", "Nina Hoss"), info.cast.map { it.name }, "in their billing order")
        val faces = CreditRecord.of(info).associate { it.personKey to it.photoUrl }
        assertEquals("https://image.tmdb.org/t/p/w185/nh.jpg", faces["nina hoss"])
        assertEquals("https://image.tmdb.org/t/p/w185/cc.jpg", faces["carlton cuse"])
    }
}
