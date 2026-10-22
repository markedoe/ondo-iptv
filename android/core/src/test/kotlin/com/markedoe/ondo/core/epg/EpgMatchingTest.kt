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
            "tv1.fr" to EpgChannel("tv1.fr", listOf("TV1")),
            "tv1series.fr" to EpgChannel("tv1series.fr", listOf("TV1 Séries Films")),
            "nation2.fr" to EpgChannel("nation2.fr", listOf("Nation 2")),
            "aria.fr" to EpgChannel("aria.fr", listOf("Aria")),
        ),
        programs = mapOf(
            "tv1.fr" to listOf(program("tv1.fr", "Journal de 20h", 0), program("tv1.fr", "Le Grand Match", 1)),
            "tv1series.fr" to listOf(program("tv1series.fr", "Série policière", 0)),
            "nation2.fr" to listOf(program("nation2.fr", "Journal", 0, subtitle = "Édition du soir")),
            "aria.fr" to listOf(program("aria.fr", "Documentaire", 3)),
        ),
    )

    private val index = EpgIndex(data)

    private fun channel(name: String, id: String = "c", epgId: String? = null) =
        Channel(id = id, name = name, categoryId = "x", streamUrl = "http://example.com/$id", epgId = epgId)

    @Test
    fun overridesWinAndCanDisableTheGuide() {
        val tv1 = channel("FR: TV1 HD", id = "1")
        assertEquals("tv1.fr", index.guideId(tv1), "automatic name match")

        val overridden = index.withOverrides(mapOf("1" to "nation2.fr"))
        assertEquals("nation2.fr", overridden.guideId(tv1))
        assertEquals("tv1.fr", overridden.automaticGuideId(tv1))
        assertEquals("Journal", overridden.current(tv1, start + 60_000)?.title)

        val disabled = index.withOverrides(mapOf("1" to ""))
        assertNull(disabled.guideId(tv1))
        assertTrue(disabled.programs(tv1).isEmpty())
    }

    /**
     * Guide channels sharing a name ("TV1 HD", "TV1 FHD") or an id up to case must not take turns from one
     * launch to the next.
     */
    @Test
    fun matchingIsTheSameAtEveryBuild() {
        repeat(30) {
            val channels = listOf("b.tv1" to "TV1 HD", "a.tv1" to "TV1 FHD", "c.tv1" to "TV1").shuffled()
                .associate { (id, name) -> id to EpgChannel(id, listOf(name)) }
            val programs = listOf("b.tv1", "c.tv1", "a.tv1", "nation2.FR", "Nation2.fr", "aria.hd", "aria-hd").shuffled()
                .associateWith { id -> listOf(program(id, "News", 0)) }
            val index = EpgIndex(EpgData(channels = HashMap(channels), programs = HashMap(programs)))
            assertEquals("a.tv1", index.guideId(channel("FR: TV1 HD")), "same display name: the smallest id")
            assertEquals("Nation2.fr", index.guideId(channel("Nation", epgId = "NATION2.fr")), "same id up to case")
            assertEquals("aria-hd", index.guideId(channel("Aria")), "same name from the id")
        }
    }

    @Test
    fun insertingProgrammesMatchesLikeABuild() {
        var inserted = EpgIndex(EpgData(channels = mapOf("tv1.fr" to EpgChannel("tv1.fr", listOf("TV1")))))
        val tv1 = channel("FR: TV1 HD", id = "1", epgId = "x-tv1")
        assertNull(inserted.guideId(tv1), "a guide channel without programmes is not matched by its name")
        inserted = inserted.inserting(listOf(program("tv1.fr", "Journal", 0)), channel("TV1", id = "2", epgId = "tv1.fr"))
        assertEquals("tv1.fr", inserted.guideId(tv1), "its programmes make its display name count")
        assertEquals("Journal", inserted.current(tv1, start + 60_000)?.title)
        assertEquals("tv1.fr", EpgIndex(inserted.data).guideId(tv1))
    }

    @Test
    fun suggestionsRankSimilarNamesFirst() {
        val suggestions = index.suggestions(channel("FR | TV1 FHD (backup)"))
        assertEquals("tv1.fr", suggestions.firstOrNull()?.id)
        assertTrue(suggestions.any { it.id == "tv1series.fr" })
        assertFalse(suggestions.any { it.id == "aria.fr" })
        assertTrue(index.suggestions(channel("HD")).isEmpty(), "tags alone identify nothing")
    }

    @Test
    fun optionsIncludeChannelsWithoutLoadedProgrammes() {
        val withQ9 = EpgIndex(data.copy(channels = data.channels + ("q9.fr" to EpgChannel("q9.fr", listOf("Q9")))))
        val options = withQ9.guideChannelOptions()
        assertEquals(listOf("Aria", "Nation 2", "Q9", "TV1", "TV1 Séries Films"), options.map { it.name })
        assertEquals(0, options.first { it.id == "q9.fr" }.programCount)
        assertEquals("q9.fr", withQ9.suggestions(channel("Q9 HD")).firstOrNull()?.id, "suggested before its programmes are loaded")
        assertEquals("Journal de 20h", withQ9.current("tv1.fr", start + 60_000)?.title)
    }

    @Test
    fun programmeSearch() {
        val window = TimeWindow.of(start, 2 * HOUR)
        val journal = index.searchPrograms("journal", window)
        assertEquals(setOf("tv1.fr", "nation2.fr"), journal.map { it.guideId }.toSet())
        assertEquals(listOf("nation2.fr"), index.searchPrograms("edition soir", window).map { it.guideId }, "subtitle, accents folded")
        assertTrue(index.searchPrograms("documentaire", window).isEmpty(), "outside the window")
        assertEquals("Le Grand Match", index.searchPrograms("grand match", window).firstOrNull()?.program?.title)
        assertTrue(index.searchPrograms("  ", window).isEmpty())
    }

    @Test
    fun playlistOverridesSurviveEncoding() {
        val playlist = Playlist(name = "P", source = PlaylistSource.Remote("http://example.com/p.m3u"))
            .copy(epgChannelOverrides = mapOf("1" to "tv1.fr", "2" to ""))
        val decoded = CoreJson.decodeFromString(Playlist.serializer(), CoreJson.encodeToString(Playlist.serializer(), playlist))
        assertEquals(mapOf("1" to "tv1.fr", "2" to ""), decoded.epgChannelOverrides)
    }
}
