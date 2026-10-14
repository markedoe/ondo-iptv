package com.markedoe.ondo.core.parsing

import com.markedoe.ondo.core.Fixtures
import com.markedoe.ondo.core.MockHttpClient
import com.markedoe.ondo.core.library.PlaylistLoader
import com.markedoe.ondo.core.library.PlaylistLoaderException
import com.markedoe.ondo.core.model.CatchupInfo
import com.markedoe.ondo.core.model.ContentType
import com.markedoe.ondo.core.model.PlaylistContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContentBuilderTest {
    private val loader = PlaylistLoader(MockHttpClient())

    private fun content(): PlaylistContent = loader.parse(Fixtures.bytes("sample.m3u"))

    @Test
    fun classification() {
        val content = content()
        assertEquals(listOf("TF1 HD", "France 2", "beIN Sports 1", "Arte", "FIP Radio", "Hot channel", "TF1 HD"), content.channels.map { it.name })
        assertEquals(listOf("Inception", "Mad Max Fury Road"), content.movies.map { it.name })
        assertEquals(2010, content.movies.first().year)
        assertEquals(1, content.series.size)
        assertEquals(2, content.providerEpgUrls.size)
    }

    @Test
    fun categoriesKeepOrderAndAreShared() {
        val content = content()
        assertEquals(listOf("France, TNT", "Sports", "Radio", "XXX Adults"), content.liveCategories.map { it.name })
        val tnt = content.liveCategories[0]
        assertEquals(4, content.channels.count { it.categoryId == tnt.id })
        assertTrue(content.liveCategories[3].looksAdult)
        assertFalse(tnt.looksAdult)
        assertEquals(content.liveCategories, content.categories(ContentType.LIVE))
    }

    @Test
    fun seriesGrouping() {
        val series = assertNotNull(content().series.firstOrNull())
        assertEquals("Breaking Bad", series.name)
        assertEquals("http://img.example.com/bb.jpg", series.coverUrl)
        assertEquals(listOf("S01E01", "S01E02", "S02E01"), series.episodes.map { it.code })
        assertEquals("Pilot", series.episodes[0].title)
        assertEquals("Seven Thirty-Seven", series.episodes[2].title)
    }

    @Test
    fun seriesTakeTheirYearFromTheTitle() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 group-title="Series | Drama",The Archive (2025) S01E01
            http://example.com/a101.mp4
            #EXTINF:-1 group-title="Series | Drama",The Archive (2025) S01E02
            http://example.com/a102.mp4
        """.trimIndent()
        val content = loader.parse(m3u.toByteArray())
        assertEquals(1, content.series.size)
        val series = content.series.first()
        assertEquals("The Archive", series.name)
        assertEquals(2025, series.year)
        assertEquals(listOf("S01E01", "S01E02"), series.episodes.map { it.code })
    }

    @Test
    fun stableAndUniqueIds() {
        val first = content()
        val second = content()
        assertEquals(first.channels.map { it.id }, second.channels.map { it.id }, "ids must survive a reload (favourites)")
        assertEquals(first.channels.size, first.channels.map { it.id }.toSet().size, "duplicate URLs still get unique ids")
    }

    @Test
    fun episodesSharingAUrlGetUniqueIds() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 group-title="Series | Drama",Show S01E01
            http://example.com/same.mp4
            #EXTINF:-1 group-title="Series | Drama",Show S01E02
            http://example.com/same.mp4
        """.trimIndent()
        val episodes = assertNotNull(loader.parse(m3u.encodeToByteArray()).series.firstOrNull()).episodes
        assertEquals(listOf("S01E01", "S01E02"), episodes.map { it.code })
        assertEquals(2, episodes.map { it.id }.toSet().size, "progress is kept per episode")
    }

    @Test
    fun episodesSharingAUrlWithAChannelGetUniqueIdsAcrossTypes() {
        // Adaptation check: ids are unique across live, movie and series entries of a playlist.
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-type="live",Channel
            http://example.com/same.mp4
            #EXTINF:-1 tvg-type="movie",Movie
            http://example.com/same.mp4
            #EXTINF:-1 tvg-type="series",Show S01E01
            http://example.com/same.mp4
            #EXTINF:-1 tvg-type="series",Show S01E02
            http://example.com/same.mp4
        """.trimIndent()
        val content = loader.parse(m3u.encodeToByteArray())
        val ids = content.channels.map { it.id } + content.movies.map { it.id } + content.series.flatMap { s -> s.episodes.map { it.id } }
        assertEquals(4, ids.toSet().size)
        assertEquals(listOf(1, 2), content.series.single().episodes.map { it.number })
    }

    @Test
    fun catchupAttributes() {
        val channels = content().channels
        assertEquals(CatchupInfo(CatchupInfo.Mode.SHIFT, 2), channels[0].catchup, "header defaults apply")
        assertEquals(CatchupInfo.Mode.FLUSSONIC, channels[1].catchup?.mode)
        assertEquals(5, channels[1].catchup?.days)
        assertEquals(CatchupInfo.Mode.DEFAULT, channels[3].catchup?.mode)
        assertEquals("http://archive.example.com/arte?start={utc}&end={utcend}", channels[3].catchup?.source)
    }

    @Test
    fun catchupAttributeVariants() {
        fun info(vararg pairs: Pair<String, String>, url: String? = null) = M3uContentBuilder.catchupInfo(mapOf(*pairs), url)
        assertNull(info())
        assertEquals(CatchupInfo(CatchupInfo.Mode.APPEND, 7), info("catchup" to "append"), "type without days: one week")
        assertEquals(CatchupInfo(CatchupInfo.Mode.SHIFT, 3), info("timeshift" to "3"), "SIPTV timeshift")
        assertEquals(
            CatchupInfo(CatchupInfo.Mode.XTREAM, 3),
            info("tvg-rec" to "3", url = "http://h.example.com/live/u/p/1.ts"),
            "Xtream URLs fall back to Xtream timeshift",
        )
        assertEquals(CatchupInfo(CatchupInfo.Mode.XTREAM, 2), info("catchup-type" to "XC", "catchup-days" to " 2 "))
        assertEquals(CatchupInfo(CatchupInfo.Mode.SHIFT, 7), info("catchup" to "default"), "default without source")
        assertEquals(CatchupInfo(CatchupInfo.Mode.DEFAULT, 0, "http://x/{utc}"), info("catchup-source" to "http://x/{utc}"))
    }

    @Test
    fun xtreamStreamIdDetectedInPlainM3u() {
        val tf1 = content().channels[0]
        assertEquals(101, tf1.xtreamStreamId)
        assertEquals("TF1.fr", tf1.epgId)
        assertEquals("TF1", tf1.epgName)
    }

    @Test
    fun titleParser() {
        assertEquals("The Office (US)", TitleParser.parseEpisode("The Office (US) S03E12 Traveling Salesmen")?.seriesName)
        assertEquals(2, TitleParser.parseEpisode("Lost 2x05")?.season)
        assertEquals(5, TitleParser.parseEpisode("Lost 2x05")?.episode)
        assertEquals(3, TitleParser.parseEpisode("Dix pour cent Saison 2 Episode 3")?.episode)
        assertNull(TitleParser.parseEpisode("TF1 HD"))
        assertEquals("Dune", TitleParser.splitYear("Dune [2021]").name)
        assertEquals(2021, TitleParser.splitYear("Dune [2021]").year)
        assertEquals(2049, TitleParser.splitYear("Blade Runner 2049").year)
        assertNull(TitleParser.splitYear("Matrix").year)
    }

    @Test
    fun titleParserUnicodeAndSeparators() {
        val accented = assertNotNull(TitleParser.parseEpisode("Dix pour cent - saison 2 - épisode 4 - Le final"))
        assertEquals("Dix pour cent", accented.seriesName)
        assertEquals(2, accented.season)
        assertEquals(4, accented.episode)
        assertEquals("Le final", accented.episodeTitle)
        val dotted = assertNotNull(TitleParser.parseEpisode("Show.Name.S1.E2"))
        assertEquals("Show.Name", dotted.seriesName)
        assertEquals("Episode 2", dotted.episodeTitle)
        assertNull(TitleParser.parseEpisode("S01E01"), "a series needs a name")
    }

    @Test
    fun xspfAndJson() {
        val xspf = loader.parse(Fixtures.bytes("sample.xspf"))
        assertEquals(listOf("Channel A", "Channel B"), xspf.channels.map { it.name })
        assertEquals("News", xspf.liveCategories.first().name)

        val json = loader.parse(Fixtures.bytes("sample.json"))
        assertEquals(listOf("JSON One"), json.channels.map { it.name })
        assertEquals("one.fr", json.channels.first().epgId)
        assertEquals(listOf("JSON Movie"), json.movies.map { it.name })
    }

    @Test
    fun plainListAndGzippedPlaylists() {
        val plain = loader.parse("http://a.example.com/1.m3u8\n# comment\n\nhttp://a.example.com/2.m3u8\n".encodeToByteArray())
        assertEquals(listOf("1.m3u8", "2.m3u8"), plain.channels.map { it.name })

        val gzipped = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.GZIPOutputStream(out).use { it.write(Fixtures.bytes("sample.m3u")) }
        }.toByteArray()
        assertEquals(content().channels.map { it.id }, loader.parse(gzipped).channels.map { it.id })
    }

    @Test
    fun parseErrors() {
        assertFailsWith<PlaylistLoaderException.UnsupportedFormat> { loader.parse("<html>Forbidden</html>".encodeToByteArray()) }
        assertFailsWith<PlaylistLoaderException.EmptyPlaylist> { loader.parse("#EXTM3U\n".encodeToByteArray()) }
        assertFailsWith<PlaylistLoaderException.EmptyPlaylist> { loader.parse(ByteArray(0)) }
    }
}
