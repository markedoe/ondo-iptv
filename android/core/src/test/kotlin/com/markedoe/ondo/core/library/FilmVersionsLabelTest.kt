package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.CoreJson
import com.markedoe.ondo.core.HangGuard
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.PictureRange
import com.markedoe.ondo.core.model.StreamSpecs
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule

/**
 * A film's chips and what each says: the table written by hand in `fixtures/film-versions/labels.json`, the same
 * file Apple's `FilmVersionsLabelTests` reads, so both apps name versions alike. Invented titles only.
 */
class FilmVersionsLabelTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    private val cases = Json.parseToJsonElement(File("../../fixtures/film-versions/labels.json").readText()).jsonObject["cases"]!!.jsonArray

    private fun versions(
        names: List<String>,
        categories: List<String?> = names.map { null },
        extensions: List<String?> = names.map { null },
    ): Pair<List<Movie>, (String) -> String?> {
        val movies = names.mapIndexed { index, name ->
            Movie(id = "v$index", name = name, categoryId = "c$index", streamUrl = "http://stream.example.com/$index", containerExtension = extensions[index])
        }
        val byId = movies.indices.associate { "c$it" to categories[it] }
        return movies to { id: String -> byId[id] }
    }

    @Test
    fun everyCaseReadsAsWritten() {
        assertTrue(cases.isNotEmpty())
        for (case in cases.map { it.jsonObject }) {
            val name = case["name"]!!.jsonPrimitive.content
            val rows = case["rows"]!!.jsonArray.map { it.jsonObject }
            val (movies, categoryName) = versions(
                rows.map { it["name"]!!.jsonPrimitive.content },
                rows.map { it["category"]?.jsonPrimitive?.content },
                rows.map { it["extension"]?.jsonPrimitive?.content },
            )
            val expected = case["chips"]!!.jsonArray.map { it.jsonPrimitive.content }
            val members = case["members"]?.jsonArray?.map { chip -> chip.jsonArray.map { "v" + it.jsonPrimitive.content } }
                ?: movies.map { listOf(it.id) }
            // What is known of each row's stream (measured or the provider's), when the case says.
            val specs = rows.mapIndexed { index, row -> "v$index" to row["specs"]?.let { CoreJson.decodeFromJsonElement(StreamSpecs.serializer(), it) } }.toMap()
            val choices = FilmVersions.choices(movies, categoryName, specs = { specs[it] })
            assertEquals(expected, choices.map { it.label }, name)
            assertEquals(members, choices.map { chip -> chip.members.map { it.id } }, name)
        }
    }

    /** A chip plays the member in progress, else the one chosen last, else its first; the others are its fallbacks. */
    @Test
    fun aChipPlaysItsPreferredMemberAndKeepsTheOthers() {
        val (movies, categoryName) = versions(
            listOf("|FR| COPPER VALLÉE", "|FR| Copper Vallée 4K DV", "|FR| Copper Vallee"),
            listOf("|FR| DRAME | THRILLER", "|FR| FILMS 4K", "|FR| AJOUTÉS"),
            listOf("mkv", "mkv", "mkv"),
        )
        val first = FilmVersions.choices(movies, categoryName).first()
        assertEquals("v0", first.preferred.id)
        assertEquals(listOf("v0", "v2"), first.playOrder.map { it.id })
        val watching = FilmVersions.choices(movies, categoryName, inProgress = listOf("v2"), chosen = listOf("v0")).first()
        assertEquals("v2", watching.preferred.id, "the one in progress")
        assertEquals(listOf("v2", "v0"), watching.playOrder.map { it.id })
        assertEquals("v0", watching.id, "the chip is the same whichever plays")
        assertEquals("v2", FilmVersions.choices(movies, categoryName, chosen = listOf("v1", "v2")).first().preferred.id, "the one chosen last")
        assertEquals(listOf("v1"), FilmVersions.choices(movies, categoryName, inProgress = listOf("v2")).last().playOrder.map { it.id })
    }

    /**
     * A copy filmed in a cinema (its category "Cinema CAM", no tag in its name) was one chip with the proper copy once
     * categories stopped counting, and could play first: its own chip now, after the proper one, and never played first.
     */
    @Test
    fun aCinemaCopyIsItsOwnChipAndNeverPlaysFirst() {
        val (movies, categoryName) = versions(listOf("Sand Harbor", "Sand Harbor"), listOf("Cinema CAM", "Movies"), listOf("mkv", "mkv"))
        assertEquals(listOf("Standard", "Cinema copy (CAM)"), FilmVersions.choices(movies, categoryName).map { it.label })
        for (support in listOf(PictureSupport(), PictureSupport(hdr = true, dolbyVision = true, uhd = true))) {
            val (first, reason) = FilmVersions.defaultChoice(movies, null, emptyList(), support, categoryName)!!
            assertEquals("v1", first.id)
            assertEquals(DefaultReason.BEST_FOR_SCREEN, reason)
        }
        // Even next to a 4K Dolby Vision copy filmed in a cinema.
        val (others, names) = versions(listOf("Sand Harbor 4K DV HDCAM", "Sand Harbor"))
        assertEquals("v1", FilmVersions.defaultVersion(others, null, emptyList(), PictureSupport(true, true, true), names)?.id)
        assertEquals(listOf(VersionTag.CAM), FilmVersions.tags("Sand Harbor", "Cinema CAM"))
        assertEquals(emptyList<VersionTag>(), FilmVersions.tags("The Night Cam", "Cam Corner"), "a word of a title, not a tag")
    }

    /** Whatever a film's versions are, no two chips read the same, none is empty, and every version is on one. */
    @Test
    fun twoChipsNeverReadTheSame() {
        val pool = listOf(
            "Copper Valley", "Copper Valley", "Copper Valley 4K", "Copper Valley 4K", "Copper Valley 4K DV", "Copper Valley HD",
            "Copper Valley VOSTFR", "FR - Copper Valley", "EN - Copper Valley", "Copper Valley MULTI", "Copper Valley Standard",
            "Copper Valley 4K 2", "Copper Valley Version 1", "Copper Valley Extended", "Copper Valley 3D", "COPPER VALLEY (2026)",
        )
        for (a in pool) for (b in pool) for (c in pool) {
            for (names in listOf(listOf(a, b), listOf(a, b, c))) {
                val (movies, categoryName) = versions(names)
                val choices = FilmVersions.choices(movies, categoryName)
                val labels = choices.map { it.label }
                assertEquals(labels.size, labels.distinct().size, "$names -> $labels")
                assertTrue(labels.none { it.isBlank() }, "$names -> $labels")
                assertEquals(movies.map { it.id }.sorted(), choices.flatMap { chip -> chip.members.map { it.id } }.sorted(), "$names")
            }
        }
    }

    /**
     * With what is known of their streams too (measured, the provider's, or nothing): no two chips read the same, none is
     * empty or "Standard" when its specs are known, every version is on one, and versions whose known specs differ are
     * never one chip.
     */
    @Test
    fun twoChipsNeverReadTheSameWithSpecs() {
        val names = listOf("Copper Valley", "Copper Valley", "Copper Valley 4K", "Copper Valley HDR", "Copper Valley VOSTFR", "Copper Valley Extended")
        val known = listOf(
            null,
            StreamSpecs(width = 1920, height = 1080, videoCodec = "H.264", audioCodec = "AAC", channels = 2),
            StreamSpecs(width = 1920, height = 1080, videoCodec = "HEVC", audioCodec = "AAC", channels = 2),
            StreamSpecs(width = 1920, height = 802, range = PictureRange.SDR, audioCodec = "AC-3", channels = 6),
            StreamSpecs(width = 3840, height = 2160, range = PictureRange.DOLBY_VISION, videoCodec = "HEVC", audioCodec = "E-AC-3", channels = 6, atmos = true),
            StreamSpecs(height = 2160, range = PictureRange.HDR10),
        )
        val pool = names.flatMap { name -> known.map { name to it } }
        for (a in pool) for (b in pool) for (c in listOf(null) + pool.filterIndexed { i, _ -> i % 5 == 0 }) {
            val rows = listOfNotNull(a, b, c)
            val (movies, categoryName) = versions(rows.map { it.first })
            val specs = movies.indices.associate { "v$it" to rows[it].second }
            val choices = FilmVersions.choices(movies, categoryName, specs = { specs[it] })
            val labels = choices.map { it.label }
            assertEquals(labels.size, labels.distinct().size, "$rows -> $labels")
            assertTrue(labels.none { it.isBlank() }, "$rows -> $labels")
            assertEquals(movies.map { it.id }.sorted(), choices.flatMap { chip -> chip.members.map { it.id } }.sorted(), "$rows")
            for (chip in choices) {
                val keys = chip.members.mapNotNull { FilmVersions.specKey(specs[it.id]) }.distinct()
                assertTrue(keys.size < 2, "$rows -> one chip for $keys")
                if (choices.size > 1 && keys.isNotEmpty()) assertTrue(chip.label != "Standard", "$rows -> $labels")
            }
        }
    }

    /**
     * A film's page works its chips out on each change (details, specs arriving): with specs on every version of a film
     * a panel lists 60 times, it stays a few milliseconds (generous limit for CI machines).
     */
    @Test
    fun chipsWithSpecsStayQuick() {
        val names = (0 until 60).map { i -> listOf("Copper Valley", "Copper Valley 4K", "Copper Valley VOSTFR", "Copper Valley HDR")[i % 4] }
        val (movies, categoryName) = versions(names, extensions = names.indices.map { if (it % 2 == 0) "mkv" else "mp4" })
        val specs = movies.indices.associate { "v$it" to StreamSpecs(width = 1920 * (1 + it % 2), height = 1080 * (1 + it % 2), audioCodec = "AAC", channels = 2 + it % 7) }
        repeat(5) { FilmVersions.choices(movies, categoryName, specs = { specs[it] }) }
        val start = System.nanoTime()
        repeat(20) { FilmVersions.choices(movies, categoryName, specs = { specs[it] }) }
        val each = (System.nanoTime() - start) / 20 / 1e6
        println("60 versions' chips with specs: ${each}ms")
        assertTrue(each < 50, "took ${each}ms")
    }

    /** A provider's word the chips have no name for, a tag, a language's code: never collapsed with the plain version. */
    @Test
    fun aTagWordAlwaysKeepsItsOwnChip() {
        for (word in TAG_WORDS) {
            val (movies, categoryName) = versions(listOf("Copper Valley ${word.uppercase()}", "Copper Valley"))
            assertEquals(2, FilmVersions.choices(movies, categoryName).size, word)
        }
    }

    /** Whatever a film's versions are, two never read the same, and none is empty. */
    @Test
    fun twoVersionsNeverReadTheSame() {
        val pool = listOf(
            "Copper Valley", "Copper Valley", "Copper Valley 4K", "Copper Valley 4K", "Copper Valley 4K DV", "Copper Valley HD",
            "Copper Valley VOSTFR", "FR - Copper Valley", "EN - Copper Valley", "Copper Valley MULTI", "Copper Valley Standard",
            "Copper Valley 4K 2", "Copper Valley Version 1", "Copper Valley Extended", "Copper Valley 3D",
        )
        for (a in pool) for (b in pool) for (c in pool) {
            for (names in listOf(listOf(a, b), listOf(a, b, c))) {
                val (movies, categoryName) = versions(names)
                val labels = FilmVersions.labels(movies, categoryName)
                assertEquals(labels.size, labels.distinct().size, "$names -> $labels")
                assertTrue(labels.none { it.isBlank() }, "$names -> $labels")
            }
        }
    }

    /**
     * Every word the grouping reads as a provider's tag (`CatalogueMatcher`'s version and quality words) names its version
     * next to a plain one; those that say nothing of the copy ("DUB": dubbed into what?) or name a language the apps have
     * no word for are shown as the name writes them.
     */
    @Test
    fun everyTagWordOfTheGroupingNamesItsVersion() {
        val unnamed = setOf("dub", "dubbed", "sub", "tr", "nl", "pl")
        for (word in TAG_WORDS) {
            val tag = word.uppercase()
            assertTrue(CatalogueMatcher.isTagWord(word), word)
            val (movies, categoryName) = versions(listOf("Copper Valley $tag", "Copper Valley"))
            val labels = FilmVersions.labels(movies, categoryName)
            if (word in unnamed) {
                assertEquals(listOf(tag, "Standard"), labels, word)
            } else {
                assertNotEquals("Standard", labels[0], "$tag -> $labels")
                assertTrue(!labels[0].startsWith("Version"), "$tag -> $labels")
            }
        }
    }

    /** The chip's tags read in words: no "DV", no "VOSTFR" left. */
    @Test
    fun abbreviationsAreSpelledOut() {
        val (movies, categoryName) = versions(listOf("Copper Valley 4K DV", "Copper Valley VOSTFR", "Copper Valley MULTI VFF"))
        val labels = FilmVersions.labels(movies, categoryName)
        for (abbreviation in listOf("DV", "VOSTFR", "MULTI", "VFF")) assertTrue(labels.none { abbreviation in it.split(' ', '·') }, "$abbreviation in $labels")
    }

    private companion object {
        val TAG_WORDS = listOf(
            "vf", "vff", "vfq", "vfi", "vo", "vost", "vostfr", "vosta", "stfr", "subfr", "frsub", "truefrench", "french", "english",
            "subbed", "dubbed", "dub", "sub", "dual", "en", "eng", "fr", "es", "esp", "de", "ger", "ita", "pt", "br", "ar", "tr", "nl", "pl",
            "multi", "4k", "uhd", "fhd", "hd", "sd", "hdr", "dv", "hevc", "x265", "h265", "1080p", "720p", "2160p", "imax",
        )
    }
}
