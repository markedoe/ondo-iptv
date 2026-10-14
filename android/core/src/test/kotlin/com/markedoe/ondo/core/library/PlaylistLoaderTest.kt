package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.Fixtures
import com.markedoe.ondo.core.MockHttpClient
import com.markedoe.ondo.core.epg.XmltvParseException
import com.markedoe.ondo.core.model.Channel
import com.markedoe.ondo.core.model.Playlist
import com.markedoe.ondo.core.model.PlaylistContent
import com.markedoe.ondo.core.model.PlaylistSource
import com.markedoe.ondo.core.model.TimeWindow
import com.markedoe.ondo.core.model.XtreamCredentials
import com.markedoe.ondo.core.net.GzipException
import com.markedoe.ondo.core.net.HttpException
import com.markedoe.ondo.core.utc
import com.markedoe.ondo.core.xtream.XtreamException
import com.markedoe.ondo.core.xtream.XtreamUrlBuilder
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class PlaylistLoaderTest {
    private val clock = { 1_234L }

    @Test
    fun remotePlaylistUsesThePlaylistHeaders() = runTest {
        val http = MockHttpClient()
        http.routes["http://example.com/list.m3u"] = Fixtures.bytes("sample.m3u")
        val playlist = Playlist(name = "P", source = PlaylistSource.Remote("http://example.com/list.m3u"), userAgent = "UA", referrer = "http://ref")
        val content = PlaylistLoader(http, clock = clock).load(playlist)
        assertEquals(7, content.channels.size)
        assertEquals(1_234L, content.fetchedAt)
        assertEquals(mapOf("User-Agent" to "UA", "Referer" to "http://ref"), http.requestHeaders.single())
    }

    @Test
    fun aDownloadedPlaylistCannotPointTheGuideAtDeviceFiles() = runTest {
        val text = "#EXTM3U url-tvg=\"file:///etc/hosts,http://epg.example.com/guide.xml\"\n#EXTINF:-1,News\nhttp://example.com/news.m3u8\n"
        val http = MockHttpClient().apply { routes["http://example.com/list.m3u"] = text.encodeToByteArray() }
        val remote = PlaylistLoader(http).load(Playlist(name = "P", source = PlaylistSource.Remote("http://example.com/list.m3u")))
        assertEquals(listOf("http://epg.example.com/guide.xml"), remote.providerEpgUrls)

        // An imported file may use a guide file next to it.
        val file = Files.createTempFile("ondo", ".m3u").toFile().apply { writeText(text) }
        try {
            val local = PlaylistLoader(MockHttpClient()).load(Playlist(name = "F", source = PlaylistSource.LocalFile(file.path)))
            assertEquals(2, local.providerEpgUrls.size)
        } finally {
            file.delete()
        }
    }

    @Test
    fun localFilesAreRead() = runTest {
        val file = Files.createTempFile("ondo", ".m3u").toFile()
        try {
            file.writeBytes(Fixtures.bytes("sample.xspf"))
            val content = PlaylistLoader(MockHttpClient()).load(Playlist(name = "F", source = PlaylistSource.LocalFile(file.path)))
            assertEquals(listOf("Channel A", "Channel B"), content.channels.map { it.name })
            assertFailsWith<FileNotFoundException> {
                PlaylistLoader(MockHttpClient()).load(Playlist(name = "F", source = PlaylistSource.LocalFile(file.path + ".missing")))
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun xtreamAccountsUseTheApi() = runTest {
        val http = MockHttpClient()
        http.routes["api:"] = Fixtures.bytes("xtream_auth.json")
        http.routes["api:get_live_streams"] = Fixtures.bytes("xtream_live_streams.json")
        val credentials = XtreamCredentials("http://panel.example.com:8080", "demo", "pw")
        val content = PlaylistLoader(http, clock = clock).load(Playlist(name = "X", source = PlaylistSource.Xtream(credentials)))
        assertEquals(4, content.channels.size)
        assertEquals(listOf("Other", "Other 2", "Other 3"), content.liveCategories.map { it.name }, "categories failed: orphans get buckets")
        assertEquals(listOf(XtreamUrlBuilder(credentials).xmltvUrl), content.providerEpgUrls)
        assertEquals(1_234L, content.fetchedAt)
        assertEquals(2, content.account?.maxConnections)

        http.routes["api:"] = Fixtures.bytes("xtream_auth_failed.json")
        assertFailsWith<XtreamException.AuthenticationFailed> {
            PlaylistLoader(http).load(Playlist(name = "X", source = PlaylistSource.Xtream(credentials)))
        }
    }

    @Test
    fun forceM3uWithoutGuideAndFailedLogin() = runTest {
        val credentials = XtreamCredentials("http://panel.example.com:8080", "demo", "pw")
        val urls = XtreamUrlBuilder(credentials)
        val http = MockHttpClient()
        http.routes[urls.m3uUrl(com.markedoe.ondo.core.model.XtreamOutputFormat.M3U8)] =
            "#EXTM3U\n#EXTINF:-1,One\nhttp://panel.example.com:8080/live/demo/pw/1.ts\n".encodeToByteArray()
        val content = PlaylistLoader(http).load(Playlist(name = "X", source = PlaylistSource.Xtream(credentials), xtreamForceM3u = true))
        assertEquals(listOf("One"), content.channels.map { it.name })
        assertNull(content.account, "the API login may fail, the M3U export still loads")
        assertEquals(listOf(urls.xmltvUrl), content.providerEpgUrls)
    }

    @Test
    fun guidesAreMergedFilteredAndFailuresReported() = runTest {
        val http = MockHttpClient()
        http.routes["http://user/epg.xml"] = """
            <tv><channel id="TV1.fr"><display-name>TV1</display-name></channel>
            <programme start="20251009180000 +0000" stop="20251009183000 +0000" channel="TV1.fr"><title>User JT</title></programme></tv>
        """.trimIndent().encodeToByteArray()
        http.routes["http://epg.example.com/guide.xml.gz"] = Fixtures.bytes("epg.xml.gz")
        val playlist = Playlist(
            name = "P",
            source = PlaylistSource.Remote("http://example.com/list.m3u"),
            epgUrls = listOf("http://user/epg.xml", "http://broken/epg.xml"),
            epgTimeShiftHours = 0.0,
            epgChannelOverrides = mapOf("x" to "unused.fr", "y" to ""),
        )
        val content = PlaylistContent(
            channels = listOf(
                Channel(id = "1", name = "TV1 HD", categoryId = "c", streamUrl = "http://h/1", epgId = "TV1.fr"),
                Channel(id = "2", name = "FR: Nation 2", categoryId = "c", streamUrl = "http://h/2"),
            ),
            providerEpgUrls = listOf("http://epg.example.com/guide.xml.gz", "http://user/epg.xml", "http://epg2.example.com/g.xml"),
        )
        val window = TimeWindow(utc(2025, 10, 9, 0), utc(2025, 10, 10, 0))
        val result = PlaylistLoader(http, clock = clock).loadEpg(playlist, content, window)
        assertEquals(
            listOf("http://user/epg.xml", "http://broken/epg.xml", "http://epg.example.com/guide.xml.gz", "http://epg2.example.com/g.xml"),
            http.requested,
            "user sources first, provider sources once",
        )
        assertEquals(setOf("http://broken/epg.xml", "http://epg2.example.com/g.xml"), result.failures.keys)
        assertIs<HttpException.BadStatus>(result.failures["http://broken/epg.xml"])
        assertEquals(listOf("User JT", "Film du soir"), assertNotNull(result.data.programs["TV1.fr"]).map { it.title }, "first source wins on overlaps")
        assertEquals(1, result.data.programs["nation2"]?.size, "matched by name")
        assertEquals(1, result.data.programs["unused.fr"]?.size, "kept because the user picked it by hand")
        assertEquals(1_234L, result.data.fetchedAt)

        val withoutProvider = PlaylistLoader(http).loadEpg(playlist.copy(useProviderEpg = false, epgUrls = emptyList()), content, window)
        assertTrue(withoutProvider.data.programs.isEmpty())
    }
}

class ErrorAttributionTest {
    @Test
    fun classification() {
        assertEquals(ErrorAttribution.PROVIDER, ErrorAttribution.classify(HttpException.BadStatus(403)))
        assertEquals(ErrorAttribution.PROVIDER, ErrorAttribution.classify(XtreamException.InvalidResponse()))
        assertEquals(ErrorAttribution.PROVIDER, ErrorAttribution.classify(PlaylistLoaderException.EmptyPlaylist()))
        assertEquals(ErrorAttribution.PROVIDER, ErrorAttribution.classify(UnknownHostException("nope")))
        assertEquals(ErrorAttribution.PROVIDER, ErrorAttribution.classify(FileNotFoundException("x")))
        assertEquals(ErrorAttribution.NETWORK, ErrorAttribution.classify(SocketTimeoutException()))
        assertEquals(ErrorAttribution.NETWORK, ErrorAttribution.classify(IOException("reset")))
        assertEquals(ErrorAttribution.APP, ErrorAttribution.classify(XmltvParseException("bad")))
        assertEquals(ErrorAttribution.APP, ErrorAttribution.classify(GzipException("bad")))
        assertEquals(ErrorAttribution.APP, ErrorAttribution.classify(IllegalStateException()))
    }
}

class JsonFileStoreTest {
    @Test
    fun envelopeBackupAndMissingDocuments() {
        val directory = Files.createTempDirectory("ondo-store").toFile()
        try {
            val store = JsonFileStore(directory, clock = { 99L })
            assertNull(store.load<List<String>>("missing"))
            store.save(listOf("v1"), "doc")
            store.save(listOf("v2"), "doc")
            val text = store.file("doc").readText()
            assertTrue(text.contains("\"version\":1") && text.contains("\"savedAt\":99"), text)
            assertEquals(listOf("v2"), store.load<List<String>>("doc"))
            assertTrue(java.io.File(directory, "doc.json.bak").readText().contains("v1"), "previous version kept")
            assertEquals(listOf("v1"), run {
                store.file("doc").delete()
                store.load<List<String>>("doc")
            })
            assertEquals(listOf("doc.json.bak"), directory.list()!!.toList(), "no temporary files left behind")
            assertNotNull(store.diskUsage().takeIf { it > 0 })
            assertNull(store.modificationTime("doc"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun concurrentWritesNeverCorruptTheDocument() {
        val directory = Files.createTempDirectory("ondo-store").toFile()
        try {
            val store = JsonFileStore(directory)
            val threads = (1..8).map { n -> Thread { repeat(25) { store.save(List(50) { "item-$n-$it" }, "doc") } } }
            threads.forEach(Thread::start)
            threads.forEach(Thread::join)
            assertEquals(50, assertNotNull(store.load<List<String>>("doc")).size)
            store.save(listOf("short"), "a")
            assertEquals(listOf("short"), store.load<List<String>>("a"), "one-letter document names")
        } finally {
            directory.deleteRecursively()
        }
    }
}
