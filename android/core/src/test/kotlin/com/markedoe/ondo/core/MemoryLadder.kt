package com.markedoe.ondo.core

import com.markedoe.ondo.core.epg.EpgFilter
import com.markedoe.ondo.core.epg.XmltvParser
import com.markedoe.ondo.core.library.PlaylistLoader
import com.markedoe.ondo.core.model.Channel
import com.markedoe.ondo.core.model.Playlist
import com.markedoe.ondo.core.model.PlaylistContent
import com.markedoe.ondo.core.model.PlaylistSource
import com.markedoe.ondo.core.model.XtreamCredentials
import java.io.File
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream

/**
 * One load in this JVM, to run under a fixed heap (`java -Xmx…`): prints `OK` and the heap's peak, or `OOM`.
 * Data made by [LargeCatalogue] in the folder given. Cases:
 * - `xtream <folder> <live> <movies> <series>`: the Xtream catalogue, then saved and read back as the app stores it
 * - `m3u <folder> <entries>`
 * - `guide <folder> <channels> <days> <perDay> <matched> <stream|memory>`: `memory` reads the guide whole first,
 *   as before 389294b
 */
@OptIn(ExperimentalSerializationApi::class)
fun main(args: Array<String>) {
    val folder = File(args[1]).apply { mkdirs() }
    val now = System.currentTimeMillis()
    try {
        val detail = runBlocking {
            when (args[0]) {
                "xtream" -> {
                    val routes = LargeCatalogue.xtream(File(folder, "api"), args[2].toInt(), args[3].toInt(), args[4].toInt())
                    resetPeaks()
                    val playlist = Playlist(name = "X", source = PlaylistSource.Xtream(XtreamCredentials("http://panel.example.com", "demo", "demo")))
                    val content = PlaylistLoader(FileHttp(routes)).load(playlist)
                    val loaded = "loaded ${content.channels.size}/${content.movies.size}/${content.series.size}, peak ${peak()} MB"
                    resetPeaks()
                    val saved = File(folder, "content.json")
                    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
                    saved.outputStream().buffered().use { json.encodeToStream(PlaylistContent.serializer(), content, it) }
                    val restored = saved.inputStream().buffered().use { json.decodeFromStream(PlaylistContent.serializer(), it) }
                    "$loaded; saved ${saved.length() / 1_048_576} MB and read back (${restored.movies.size} movies) with the catalogue in memory, peak ${peak()} MB"
                }
                "m3u" -> {
                    val file = LargeCatalogue.m3u(File(folder, "list.m3u"), args[2].toInt())
                    resetPeaks()
                    val content = PlaylistLoader(FileHttp(mapOf("http://provider.example.com/list.m3u" to file)))
                        .load(Playlist(name = "M", source = PlaylistSource.Remote("http://provider.example.com/list.m3u")))
                    "file ${file.length() / 1_048_576} MB, loaded ${content.channels.size}/${content.movies.size}/${content.series.size}, peak ${peak()} MB"
                }
                "guide" -> {
                    val channels = args[2].toInt()
                    val file = LargeCatalogue.xmltv(File(folder, "guide.xml"), channels, args[3].toInt(), args[4].toInt(), now - 86_400_000L)
                    val content = PlaylistContent(
                        channels = (1..args[5].toInt()).map { Channel(id = "c$it", name = "Channel $it", categoryId = "c", streamUrl = "http://stream.example.com/$it", epgId = "channel$it.example") },
                    )
                    val url = "http://epg.example.com/guide.xml"
                    val http = FileHttp(mapOf(url to file))
                    val playlist = Playlist(name = "G", source = PlaylistSource.Remote("http://provider.example.com/list.m3u"), epgUrls = listOf(url))
                    resetPeaks()
                    val programmes = if (args[6] == "memory") {
                        val filter = EpgFilter(
                            window = EpgFilter.standard(now),
                            channelIds = content.channels.mapNotNullTo(HashSet()) { it.epgId },
                            channelNameKeys = content.channels.mapTo(HashSet()) { TextNormalizer.channelMatchKey(it.name) },
                        )
                        XmltvParser().parseGuide(http.get(url), filter, 0).allPrograms.size
                    } else {
                        val result = PlaylistLoader(http, downloadFolder = File(folder, "guides")).loadEpg(playlist, content)
                        result.failures.values.firstOrNull()?.let { throw it }
                        result.data.allPrograms.size
                    }
                    "file ${file.length() / 1_048_576} MB, $programmes programmes kept, peak ${peak()} MB"
                }
                else -> error("unknown case ${args[0]}")
            }
        }
        println("OK $detail")
    } catch (e: OutOfMemoryError) {
        println("OOM ${e.message}")
        exitProcess(3)
    }
}

private fun resetPeaks() {
    System.gc()
    ManagementFactory.getMemoryPoolMXBeans().forEach { it.resetPeakUsage() }
}

/** Highest heap use since [resetPeaks] (garbage not yet collected included). */
private fun peak(): Long = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }.sumOf { it.peakUsage.used } / 1_048_576
