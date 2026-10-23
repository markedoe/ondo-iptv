package com.markedoe.ondo.core.epg

import com.markedoe.ondo.core.FileHttp
import com.markedoe.ondo.core.LargeCatalogue
import com.markedoe.ondo.core.library.PlaylistLoader
import com.markedoe.ondo.core.model.Channel
import com.markedoe.ondo.core.model.Playlist
import com.markedoe.ondo.core.model.PlaylistContent
import com.markedoe.ondo.core.model.PlaylistSource
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** A guide over the memory it may take is kept within a narrower window around now (see [GuideEstimate]). */
class GuideBudgetTest {
    private val folder = Files.createTempDirectory("guides").toFile()
    private val now = 1_760_000_000_000L
    private val url = "http://epg.example.com/guide.xml"
    private val hour = 3_600_000L

    @AfterTest
    fun cleanUp() {
        folder.deleteRecursively()
    }

    private val guide by lazy { LargeCatalogue.xmltv(folder.resolve("guide.xml"), channels = 40, days = 3, perDay = 24, start = now - 86_400_000L) }
    private val content = PlaylistContent(
        channels = (1..40).map { Channel(id = "c$it", name = "Channel $it", categoryId = "c", streamUrl = "http://stream.example.com/$it", epgId = "channel$it.example") },
    )
    private val playlist = Playlist(name = "G", source = PlaylistSource.Remote("http://provider.example.com/list.m3u"), epgUrls = listOf(url))

    private suspend fun load(budget: Long?) =
        PlaylistLoader(FileHttp(mapOf(url to guide)), clock = { now }, downloadFolder = folder.resolve("downloads"), guideBudgetBytes = budget)
            .loadEpg(playlist, content)

    private fun estimate() = guide.inputStream().use { XmltvParser().estimateGuide(it, EpgFilter(EpgFilter.standard(now), content.channels.mapNotNullTo(HashSet()) { it.epgId }), 0, now) }

    @Test
    fun aGuideWithinItsBudgetIsKeptWhole() = runTest {
        val whole = load(budget = null)
        assertEquals(40 * 72, whole.data.allPrograms.size)
        assertEquals(whole.data, load(budget = estimate().totalBytes).data)
    }

    @Test
    fun aGuideOverItsBudgetKeepsWhatIsOnAndNextOnEveryChannel() = runTest {
        val total = estimate().totalBytes
        val result = load(budget = total / 6)
        assertTrue(result.failures.isEmpty(), "${result.failures}")
        val kept = result.data.allPrograms
        assertTrue(kept.size in 40 * 4 until 40 * 72 / 4, "kept ${kept.size} programmes")
        // Every channel has what is on now and the next hours; nothing far from now.
        for (channel in 1..40) {
            val programmes = result.data.programs.getValue("channel$channel.example")
            assertTrue(programmes.any { it.start <= now && it.end > now }, "channel $channel has what is on now")
            assertTrue(programmes.any { it.start >= now + hour }, "channel $channel has what comes next")
        }
        assertTrue(kept.all { it.end >= now - 12 * hour && it.start <= now + 24 * hour }, "only around now")
        // More ahead than behind.
        assertTrue(kept.count { it.start >= now } > kept.count { it.end <= now })
    }

    @Test
    fun aBudgetOfNothingStillKeepsTheCurrentHour() = runTest {
        val result = load(budget = 0)
        for (channel in 1..40) {
            assertTrue(result.data.programs.getValue("channel$channel.example").any { it.start <= now && it.end > now })
        }
    }
}
