package com.markedoe.ondo.core.epg

import com.markedoe.ondo.core.CoreJson
import com.markedoe.ondo.core.HOUR
import com.markedoe.ondo.core.model.Channel
import com.markedoe.ondo.core.model.EpgChannel
import com.markedoe.ondo.core.model.EpgData
import com.markedoe.ondo.core.model.EpgProgram
import com.markedoe.ondo.core.model.Playlist
import com.markedoe.ondo.core.model.PlaylistSource
import com.markedoe.ondo.core.model.TimeWindow
import com.markedoe.ondo.core.utc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpgMatchingTest {
    private val start = utc(2025, 10, 9, 20, 0)

    private fun program(channel: String, title: String, hour: Int, subtitle: String? = null) = EpgProgram(
        channelId = channel,
        start = start + hour * HOUR,
        end = start + (hour + 1) * HOUR,
        title = title,
        subtitle = subtitle,
    )

    private val data = EpgData(
        channels = mapOf(
            "tf1.fr" to EpgChannel("tf1.fr", listOf("TF1")),
            "tf1series.fr" to EpgChannel("tf1series.fr", listOf("TF1 Séries Films")),
            "france2.fr" to EpgChannel("france2.fr", listOf("France 2")),
            "arte.fr" to EpgChannel("arte.fr", listOf("Arte")),
        ),
        programs = mapOf(
            "tf1.fr" to listOf(program("tf1.fr", "Journal de 20h", 0), program("tf1.fr", "Le Grand Match", 1)),
            "tf1series.fr" to listOf(program("tf1series.fr", "Série policière", 0)),
            "france2.fr" to listOf(program("france2.fr", "Journal", 0, subtitle = "Édition du soir")),
            "arte.fr" to listOf(program("arte.fr", "Documentaire", 3)),
        ),
    )

    private val index = EpgIndex(data)

    private fun channel(name: String, id: String = "c", epgId: String? = null) =
        Channel(id = id, name = name, categoryId = "x", streamUrl = "http://example.com/$id", epgId = epgId)

    @Test
    fun overridesWinAndCanDisableTheGuide() {
        val tf1 = channel("FR: TF1 HD", id = "1")
        assertEquals("tf1.fr", index.guideId(tf1), "automatic name match")

        val overridden = index.withOverrides(mapOf("1" to "france2.fr"))
        assertEquals("france2.fr", overridden.guideId(tf1))
        assertEquals("tf1.fr", overridden.automaticGuideId(tf1))
        assertEquals("Journal", overridden.current(tf1, start + 60_000)?.title)

        val disabled = index.withOverrides(mapOf("1" to ""))
        assertNull(disabled.guideId(tf1))
        assertTrue(disabled.programs(tf1).isEmpty())
    }

    @Test
    fun suggestionsRankSimilarNamesFirst() {
        val suggestions = index.suggestions(channel("FR | TF1 FHD (backup)"))
        assertEquals("tf1.fr", suggestions.firstOrNull()?.id)
        assertTrue(suggestions.any { it.id == "tf1series.fr" })
        assertFalse(suggestions.any { it.id == "arte.fr" })
        assertTrue(index.suggestions(channel("HD")).isEmpty(), "tags alone identify nothing")
    }

    @Test
    fun optionsIncludeChannelsWithoutLoadedProgrammes() {
        val withM6 = EpgIndex(data.copy(channels = data.channels + ("m6.fr" to EpgChannel("m6.fr", listOf("M6")))))
        val options = withM6.guideChannelOptions()
        assertEquals(listOf("Arte", "France 2", "M6", "TF1", "TF1 Séries Films"), options.map { it.name })
        assertEquals(0, options.first { it.id == "m6.fr" }.programCount)
        assertEquals("m6.fr", withM6.suggestions(channel("M6 HD")).firstOrNull()?.id, "suggested before its programmes are loaded")
        assertEquals("Journal de 20h", withM6.current("tf1.fr", start + 60_000)?.title)
    }

    @Test
    fun programmeSearch() {
        val window = TimeWindow.of(start, 2 * HOUR)
        val journal = index.searchPrograms("journal", window)
        assertEquals(setOf("tf1.fr", "france2.fr"), journal.map { it.guideId }.toSet())
        assertEquals(listOf("france2.fr"), index.searchPrograms("edition soir", window).map { it.guideId }, "subtitle, accents folded")
        assertTrue(index.searchPrograms("documentaire", window).isEmpty(), "outside the window")
        assertEquals("Le Grand Match", index.searchPrograms("grand match", window).firstOrNull()?.program?.title)
        assertTrue(index.searchPrograms("  ", window).isEmpty())
    }

    @Test
    fun playlistOverridesSurviveEncoding() {
        val playlist = Playlist(name = "P", source = PlaylistSource.Remote("http://example.com/p.m3u"))
            .copy(epgChannelOverrides = mapOf("1" to "tf1.fr", "2" to ""))
        val decoded = CoreJson.decodeFromString(Playlist.serializer(), CoreJson.encodeToString(Playlist.serializer(), playlist))
        assertEquals(mapOf("1" to "tf1.fr", "2" to ""), decoded.epgChannelOverrides)
    }
}
