package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.StableHash
import com.markedoe.ondo.core.TextNormalizer
import com.markedoe.ondo.core.model.Channel
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.PlaylistContent
import com.markedoe.ondo.core.model.Series
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SearchTest {
    private fun makeContent(): PlaylistContent {
        val url = "http://x/1"
        return PlaylistContent(
            channels = listOf(
                Channel(id = "1", name = "FR: Télé Loisirs HD", categoryId = "a", streamUrl = url),
                Channel(id = "2", name = "TV1", categoryId = "a", streamUrl = url),
                Channel(id = "3", name = "TV1 Séries Films", categoryId = "a", streamUrl = url),
                Channel(id = "4", name = "Hidden TV1", categoryId = "hidden", streamUrl = url),
            ),
            movies = listOf(Movie(id = "m", name = "Le Fabuleux Destin d'Amélie Lagarde", categoryId = "m", streamUrl = url)),
            series = listOf(Series(id = "s", name = "Les Voisins", categoryId = "s")),
        )
    }

    @Test
    fun normalisation() {
        assertEquals("tele loisirs hd", TextNormalizer.normalize("  Télé-Loisirs  HD! "))
        assertEquals("tv1", TextNormalizer.channelMatchKey("FR: TV1 FHD"))
        assertEquals("tv1", TextNormalizer.channelMatchKey("TV1 HD"))
        assertEquals("nation2", TextNormalizer.channelMatchKey("Nation 2"))
        assertEquals("fr", TextNormalizer.channelMatchKey("FR"), "a lone country token is kept")
    }

    @Test
    fun normalisationOfWidthLigaturesAndScripts() {
        assertEquals("tv1 hd", TextNormalizer.normalize("ＴＶ１　ＨＤ"), "full-width forms")
        assertEquals("strasse", TextNormalizer.normalize("Straße"))
        assertEquals("cafe creme", TextNormalizer.normalize("CAFÉ_CRÈME"))
        assertEquals("канал 1", TextNormalizer.normalize("Канал-1"))
        assertEquals("", TextNormalizer.normalize(" -!- "))
        assertTrue(TextNormalizer.matches("le grand match", TextNormalizer.tokens("gra mat")))
        assertTrue(TextNormalizer.matches("anything", emptyList()))
    }

    @Test
    fun searchIsAccentAndCaseInsensitiveAndRanked() {
        val engine = SearchEngine(makeContent())
        assertEquals(listOf("1"), engine.search("tele").channels.map { it.id })
        assertEquals(1, engine.search("amelie").movies.size)
        assertEquals(1, engine.search("voisin").series.size)
        assertEquals(listOf("2", "3", "4"), engine.search("tv1").channels.map { it.id }, "exact first, then prefix, then contains")
        assertEquals(listOf("2", "3"), engine.search("tv1", excludedCategoryIds = setOf("hidden")).channels.map { it.id })
        assertEquals(listOf("3"), engine.search("films tv1").channels.map { it.id }, "all words must match")
        assertTrue(engine.search("   ").isEmpty)
        assertEquals(0, engine.search("zzz").totalCount)
    }

    @Test
    fun limitPerType() {
        val content = PlaylistContent(
            channels = (1..400).map { Channel(id = "$it", name = "News $it", categoryId = "a", streamUrl = "http://x/$it") },
        )
        val engine = SearchEngine(content)
        assertEquals(150, engine.search("news").channels.size, "default limit")
        assertEquals(10, engine.search("news", limitPerType = 10).channels.size)
        assertEquals(listOf("1", "2"), engine.search("news", limitPerType = 2).channels.map { it.id })
    }

    @Test
    fun stableHash() {
        assertEquals(0xcbf29ce484222325uL, StableHash.fnv1a(""))
        assertEquals(StableHash.hex("http://x/1"), StableHash.hex("http://x/1"))
        assertNotEquals(StableHash.hex("http://x/1"), StableHash.hex("http://x/2"))
        // Reference values computed with FNV-1a 64 over UTF-8 (identical to the Swift ids).
        assertEquals("af63dc4c8601ec8c", StableHash.hex("a"))
        assertEquals(StableHash.fnv1a("é"), fnv1aReference("é".encodeToByteArray()))
        assertEquals(StableHash.fnv1a("📺 TV1"), fnv1aReference("📺 TV1".encodeToByteArray()))
    }

    private fun fnv1aReference(bytes: ByteArray): ULong {
        var hash = 0xcbf29ce484222325uL
        for (byte in bytes) hash = (hash xor byte.toUByte().toULong()) * 0x100000001b3uL
        return hash
    }
}
