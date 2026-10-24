package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.MockHttpClient
import com.markedoe.ondo.core.model.Channel
import com.markedoe.ondo.core.model.ContentType
import com.markedoe.ondo.core.model.MediaCategory
import com.markedoe.ondo.core.model.XtreamCredentials
import com.markedoe.ondo.core.model.XtreamOutputFormat
import com.markedoe.ondo.core.xtream.XtreamClient
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest

/** The names the core gives where the provider gives none read in the viewer's words; ids and the provider's names stay. */
class PlaceholderNamesTest {
    private val french = PlaceholderNames(
        uncategorized = "Non classé",
        other = "Autre",
        otherNumbered = "Autre %1\$d",
        channel = "Chaîne %1\$d",
        movie = "Film %1\$d",
        series = "Série %1\$d",
        episode = "Épisode %1\$d",
        locale = Locale.FRENCH,
    )

    @Test
    fun anM3uEntryWithoutGroupOrEpisodeTitle() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1,News 24
            http://stream.example.com/live/news.m3u8
            #EXTINF:-1 group-title="Series",Copper Valley S01E02
            http://stream.example.com/series/cv-s01e02.mkv
            #EXTINF:-1 group-title="Series",Copper Valley S01E03 Pilot
            http://stream.example.com/series/cv-s01e03.mkv
        """.trimIndent()
        val content = PlaylistLoader(MockHttpClient()).parse(playlist.encodeToByteArray())
        assertEquals("Uncategorized", content.liveCategories.single().name, "the core's own word, kept on disk")
        assertEquals("Episode 2", content.series.single().episodes[0].title)

        val shown = french.rename(content)
        assertEquals("Non classé", shown.liveCategories.single().name)
        assertEquals(content.liveCategories.single().id, shown.liveCategories.single().id, "the id stays")
        assertEquals(content.channels.single(), shown.channels.single())
        assertEquals(listOf("Épisode 2", "Pilot"), shown.series.single().episodes.map { it.title })
        assertEquals(content.series.single().episodes.map { it.id }, shown.series.single().episodes.map { it.id })
    }

    @Test
    fun xtreamItemsWithoutNamesAndCategoriesThePanelLeavesOut() = runTest {
        val http = MockHttpClient().apply {
            routes["api:"] = """{"user_info":{"auth":1,"status":"Active"}}""".encodeToByteArray()
            routes["api:get_live_categories"] = """[{"category_id":"1"}]""".encodeToByteArray()
            routes["api:get_live_streams"] = """[{"stream_id":12,"category_id":"1"},{"stream_id":13,"category_id":"8"},{"stream_id":14,"category_id":"9","name":"Nine"}]""".encodeToByteArray()
            routes["api:get_vod_categories"] = "[]".encodeToByteArray()
            routes["api:get_vod_streams"] = """[{"stream_id":7}]""".encodeToByteArray()
            routes["api:get_series_categories"] = "[]".encodeToByteArray()
            routes["api:get_series"] = """[{"series_id":3}]""".encodeToByteArray()
            routes["api:get_series_info"] = """{"info":{"name":"x"},"episodes":{"1":[{"id":"31","episode_num":4},{"id":"32","episode_num":5,"title":"Finale"}]}}""".encodeToByteArray()
        }
        val client = XtreamClient(XtreamCredentials("http://panel.example.com", "demo", "demo"), http)
        val content = client.fetchContent(XtreamOutputFormat.TS)
        assertEquals(listOf("Uncategorized", "Other", "Other 2"), content.liveCategories.map { it.name })

        val shown = french.rename(content)
        assertEquals(listOf("Non classé", "Autre", "Autre 2"), shown.liveCategories.map { it.name })
        assertEquals(content.liveCategories.map { it.id }, shown.liveCategories.map { it.id })
        assertEquals(listOf("Chaîne 12", "Chaîne 13", "Nine"), shown.channels.map { it.name })
        assertEquals(listOf("Film 7"), shown.movies.map { it.name })
        assertEquals(listOf("Série 3"), shown.series.map { it.name })
        assertEquals(content.channels.map { it.id }, shown.channels.map { it.id })

        val details = client.seriesDetails(3)
        assertEquals(listOf("Épisode 4", "Finale"), french.rename(details).seasons.single().episodes.map { it.title })
    }

    @Test
    fun theProvidersOwnNamesStay() {
        val channel = Channel(id = "c1", name = "Channel 5", categoryId = "live-1", streamUrl = "http://stream.example.com/5.m3u8")
        assertSame(channel, french.rename(channel), "a channel of an M3U list named so by its provider")
        val otherNumber = channel.copy(xtreamStreamId = 6)
        assertSame(otherNumber, french.rename(otherNumber), "a name with another number than the stream's")
        for (name in listOf("Other 1", "Other 02", "Other x", "Others", "Uncategorized movies")) {
            val category = MediaCategory("live-1", name, ContentType.LIVE)
            assertSame(category, french.rename(category), name)
        }
    }

    @Test
    fun nothingToRenameKeepsTheSameCatalogue() {
        val content = PlaylistLoader(MockHttpClient()).parse("#EXTM3U\n#EXTINF:-1 group-title=\"News\",News 24\nhttp://stream.example.com/n.m3u8\n".encodeToByteArray())
        assertSame(content, french.rename(content))
        val withPlaceholders = PlaylistLoader(MockHttpClient()).parse("#EXTM3U\n#EXTINF:-1,News 24\nhttp://stream.example.com/n.m3u8\n".encodeToByteArray())
        assertSame(withPlaceholders, PlaceholderNames.ENGLISH.rename(withPlaceholders), "the core's words in English")
    }
}
