package com.markedoe.ondo

import android.content.Context
import android.content.res.Configuration
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.markedoe.ondo.core.library.CategoryStyles
import com.markedoe.ondo.core.library.CategoryTags
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
import com.markedoe.ondo.data.AppStore
import com.markedoe.ondo.ui.UiMappers
import java.time.Duration
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The names the core gives in English where the provider gives none ("Uncategorized", "Other", "Channel 12", "Movie 7",
 * "Series 3", "Episode 4") read in the app's language, in a catalogue saved before as in a new one; what is saved keeps
 * the core's words.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = OndoApplication::class, qualifiers = "fr")
class PlaceholderNamesAppTest {
    private val app: OndoApplication = ApplicationProvider.getApplicationContext()

    private val uncategorized = MediaCategory("live-1a2b", "Uncategorized", ContentType.LIVE)
    private val orphans = MediaCategory("8", "Other", ContentType.LIVE)
    private val unnamed = Channel(id = "x12", name = "Channel 12", categoryId = uncategorized.id, streamUrl = "http://panel.example.com/live/u/p/12.ts", xtreamStreamId = 12)
    private val named = Channel(id = "x14", name = "Channel 5", categoryId = orphans.id, streamUrl = "http://panel.example.com/live/u/p/14.ts", xtreamStreamId = 14)
    private val movie = Movie(id = "m7", name = "Movie 7", categoryId = "9", streamUrl = "http://panel.example.com/movie/u/p/7.mp4", xtreamStreamId = 7)
    private val series = Series(id = "s3", name = "Series 3", categoryId = "10", xtreamSeriesId = 3)
    private val content = PlaylistContent(
        channels = listOf(unnamed, named),
        movies = listOf(movie),
        series = listOf(series),
        liveCategories = listOf(uncategorized, orphans),
    )

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
        }
    }

    @Test
    fun aSavedCatalogueShowsTheCoresNamesInFrench() {
        val store = AppStore(app.filesDir.resolve("ondo"))
        store.savePlaylists(listOf(Fixtures.playlist))
        store.saveSettings(AppSettings(activePlaylistId = Fixtures.PLAYLIST_ID))
        // Fresh: restored without downloading.
        store.saveContent(Fixtures.PLAYLIST_ID, content.copy(fetchedAt = System.currentTimeMillis()))

        val repository = app.repository
        repository.bootstrap()
        waitFor { !repository.state.value.content.isEmpty }

        val shown = repository.state.value.content
        assertEquals(listOf("Non classé", "Autre"), shown.liveCategories.map { it.name })
        assertEquals(listOf(uncategorized.id, orphans.id), shown.liveCategories.map { it.id })
        assertEquals(listOf("Chaîne 12", "Channel 5"), shown.channels.map { it.name })
        assertEquals("Film 7", shown.movies.single().name)
        assertEquals("Série 3", shown.series.single().name)
        assertEquals("the saved catalogue keeps the core's words", content.liveCategories, store.loadContent(Fixtures.PLAYLIST_ID)?.liveCategories)

        // An episode of the provider's details: on its page and once played.
        val episode = Episode(id = "31", title = "Episode 4", season = 1, number = 4, streamUrl = "http://panel.example.com/series/u/p/31.mp4")
        val details = SeriesDetails(MediaDetails(title = "x"), listOf(Season(1, "Season 1", listOf(episode))))
        val state = repository.state.value.copy(seriesDetails = mapOf(series.id to details))
        val page = UiMappers.seriesDetail(state, shown.series.single(), null, null)
        assertEquals(listOf("Épisode 4"), page.seasons.single().episodes.map { it.title })
        assertEquals("Épisode 4", page.next?.title)
        val request = repository.playRequest(shown.series.single(), episode)
        assertEquals("S01E04 · Épisode 4", request?.subtitle)
        assertEquals("S01E04 · Épisode 4", repository.state.value.library.history.single().subtitle)

        // Another app language: the catalogue on screen follows.
        val spanish = app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag("es")) })
        repository.setPlaceholderNames(spanish.resources.placeholderNames())
        assertEquals(listOf("Sin categoría", "Otros"), repository.state.value.content.liveCategories.map { it.name })
        assertEquals("Canal 12", repository.state.value.content.channels.first().name)
    }

    @Test
    fun theWordsOfEveryLanguageAreNoTagStyleOrAdultCategory() {
        val english = PlaylistContent(liveCategories = listOf(uncategorized, orphans, orphans.copy(id = "11", name = "Other 2")))
        for (tag in listOf("en", "fr", "es", "pt", "zh-CN", "ar", "hi")) {
            val context: Context = app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) })
            val names = context.resources.placeholderNames()
            val categories = names.rename(english).liveCategories
            if (tag != "en") assertTrue(tag, categories.none { it.name in listOf("Uncategorized", "Other", "Other 2") })
            assertEquals(tag, 3, categories.map { it.name }.distinct().size)
            for (category in categories) {
                assertNull("$tag ${category.name}", CategoryTags.tagOf(category.name))
                assertFalse("$tag ${category.name}", category.looksAdult)
            }
            assertTrue(tag, CategoryStyles.detect(categories).isEmpty())
            assertNotEquals(tag, "", names.rename(unnamed).name)
        }
    }
}
