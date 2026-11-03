package com.markedoe.ondo.core

import com.markedoe.ondo.core.library.FilmVersions
import com.markedoe.ondo.core.library.PlaylistLoader
import com.markedoe.ondo.core.model.Channel
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.Playlist
import com.markedoe.ondo.core.model.PlaylistContent
import com.markedoe.ondo.core.model.PlaylistSource
import com.markedoe.ondo.core.model.XtreamCredentials
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Large catalogues and guides load within a small heap: run by `:core:memoryTest` under 64 MB (see build.gradle.kts),
 * left out of `test`. Measured with `MemoryLadder` before they were read element by element and from disk: the Xtream
 * catalogue below needed 111 MB, the M3U playlist 81 MB, and a guide about 3.5 times its size (93 MB for 26 MB,
 * 360 MB for 106 MB). An entry-level TV gives an app little more (the device's figures are in the crash report,
 * Settings → About).
 */
class MemoryBudgetTest {
    private val folder = Files.createTempDirectory("memory").toFile()

    @AfterTest
    fun cleanUp() {
        folder.deleteRecursively()
    }

    @Test
    fun anXtreamCatalogueOf30000ItemsLoads() = runBlocking {
        val routes = LargeCatalogue.xtream(folder.resolve("api"), live = 5_000, movies = 20_000, series = 5_000)
        val playlist = Playlist(name = "X", source = PlaylistSource.Xtream(XtreamCredentials("http://panel.example.com", "demo", "demo")))
        val content = PlaylistLoader(FileHttp(routes), downloadFolder = folder.resolve("downloads")).load(playlist)
        assertEquals(listOf(5_000, 20_000, 5_000), listOf(content.channels.size, content.movies.size, content.series.size))
    }

    /** 100,000 films, a third of them in three versions: grouped within the heap, the same counts as Apple's PerformanceTests. */
    @Test
    fun aHundredThousandFilmsAreGrouped() {
        val tags = listOf("|FR| %s 4K DV", "%s VOSTFR", "%s (MULTI)")
        val movies = List(100_000) { i ->
            val film = if (i < 30_000) i / 3 else i
            Movie(
                id = "x$i",
                name = if (i < 30_000) tags[i % 3].format("Film $film") else "Film $film",
                categoryId = "c${i % 40}",
                streamUrl = "http://host.example.com/$i.mkv",
                year = 1950 + film % 70,
                tmdbId = if (i < 30_000 && i % 3 == 1) null else 10_000 + film,
            )
        }
        val started = System.nanoTime()
        val versions = FilmVersions.of(movies)
        println("100k films grouped in ${(System.nanoTime() - started) / 1_000_000} ms: ${versions.diagnostics}")
        assertEquals(80_000, versions.groupCount)
        assertEquals(10_000, versions.diagnostics.groupsWithVersions)
    }

    @Test
    fun anM3uOf50000EntriesLoads() = runBlocking {
        val url = "http://provider.example.com/list.m3u"
        val file = LargeCatalogue.m3u(folder.resolve("list.m3u"), 50_000)
        val content = PlaylistLoader(FileHttp(mapOf(url to file)), downloadFolder = folder.resolve("downloads"))
            .load(Playlist(name = "M", source = PlaylistSource.Remote(url)))
        assertEquals(50_000, content.channels.size + content.movies.size + content.series.sumOf { it.episodes.size })
    }

    /**
     * A guide whose programmes alone would need more than the heap (4,000 channels with three days each: about 130 MB
     * measured kept whole) loads within its budget, a quarter of the heap: what is on and next on every channel.
     */
    @Test
    fun aGuideLargerThanTheHeapKeepsWhatIsOnWithinItsBudget() = runBlocking {
        val url = "http://epg.example.com/guide.xml"
        val now = System.currentTimeMillis()
        val file = LargeCatalogue.xmltv(folder.resolve("big.xml"), channels = 4_000, days = 3, perDay = 24, start = now - 86_400_000L)
        val content = PlaylistContent(
            channels = (1..4_000).map { Channel(id = "c$it", name = "Channel $it", categoryId = "c", streamUrl = "http://stream.example.com/$it", epgId = "channel$it.example") },
        )
        val playlist = Playlist(name = "G", source = PlaylistSource.Remote("http://provider.example.com/list.m3u"), epgUrls = listOf(url))
        val loader = PlaylistLoader(FileHttp(mapOf(url to file)), clock = { now }, downloadFolder = folder.resolve("downloads"), guideBudgetBytes = Runtime.getRuntime().maxMemory() / 4)
        val result = loader.loadEpg(playlist, content)
        assertTrue(result.failures.isEmpty(), "${result.failures}")
        assertEquals(4_000, result.data.programs.size)
        assertTrue(result.data.programs.values.all { list -> list.any { it.start <= now && it.end > now } }, "what is on now, on every channel")
    }

    @Test
    fun aGuideOfTensOfMegabytesLoads() = runBlocking {
        val url = "http://epg.example.com/guide.xml"
        val now = System.currentTimeMillis()
        val file = LargeCatalogue.xmltv(folder.resolve("guide.xml"), channels = 1_000, days = 7, perDay = 24, start = now - 86_400_000L)
        assertTrue(file.length() > 48L * 1024 * 1024, "the guide is most of the heap: ${file.length()} bytes")
        val content = PlaylistContent(
            channels = (1..500).map { Channel(id = "c$it", name = "Channel $it", categoryId = "c", streamUrl = "http://stream.example.com/$it", epgId = "channel$it.example") },
        )
        val playlist = Playlist(name = "G", source = PlaylistSource.Remote("http://provider.example.com/list.m3u"), epgUrls = listOf(url))
        val result = PlaylistLoader(FileHttp(mapOf(url to file)), downloadFolder = folder.resolve("downloads")).loadEpg(playlist, content)
        assertTrue(result.failures.isEmpty(), "${result.failures}")
        assertEquals(500, result.data.programs.size)
    }
}
