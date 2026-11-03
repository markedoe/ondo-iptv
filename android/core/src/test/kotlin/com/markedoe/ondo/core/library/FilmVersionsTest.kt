package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.xtream.XtreamVodStreamDto
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/** The same cases as Apple's `FilmVersionsTests` (IPTVCore). */
class FilmVersionsTest {
    private var next = 0

    private fun movie(name: String, year: Int? = null, tmdb: Int? = null, category: String = "c", id: String? = null) =
        Movie(id = id ?: "m${next++}", name = name, categoryId = category, streamUrl = "http://stream.example.com/${next}", year = year, tmdbId = tmdb)

    private fun groups(versions: FilmVersions): List<List<String>> = (0 until versions.groupCount).map { g -> versions.versions(g).map { it.name } }

    private fun together(versions: FilmVersions, a: Movie, b: Movie) = versions.groupOf(a.id) == versions.groupOf(b.id)

    @Test
    fun aTmdbIdIsAPositiveNumberOnly() {
        assertEquals(603, FilmVersions.tmdbId("603"))
        assertEquals(42, FilmVersions.tmdbId(" 42 "))
        for (raw in listOf(null, "", " ", "0", "000", "abc", "-3", "+3", "12.5", "1e3", "99999999999", "2147483648")) {
            assertNull(FilmVersions.tmdbId(raw), "\"$raw\" is no id")
        }
    }

    @Test
    fun theXtreamListGivesTheTmdbId() {
        val json = """[{"stream_id":1,"name":"A","tmdb":"603"},{"stream_id":2,"name":"B","tmdb":0},{"stream_id":3,"name":"C","tmdb":true},{"stream_id":4,"name":"D","tmdb_id":77},{"stream_id":5,"name":"E","tmdb":""}]"""
        val dtos = (Json.parseToJsonElement(json) as JsonArray).map { XtreamVodStreamDto.from(it)?.tmdbId }
        assertEquals(listOf(603, null, null, 77, null), dtos)
    }

    @Test
    fun ruleAGroupsOneIdWhenTheTitlesAgree() {
        val uhd = movie("|FR| Le Transport 4K DV", 2021, tmdb = 7)
        val hd = movie("Le Transport", 2020, tmdb = 7)
        val longer = movie("Le Transport : Héritage", null, tmdb = 7)
        val matrix = movie("The Matrix", 1999, tmdb = 603)
        val short = movie("Matrix VOSTFR", 1999, tmdb = 603)
        val versions = FilmVersions.of(listOf(uhd, hd, longer, matrix, short))
        assertTrue(together(versions, uhd, hd), "the same id: years are not compared")
        assertTrue(together(versions, uhd, longer), "one title starts with the other")
        assertTrue(together(versions, matrix, short), "half the words shared")
        assertEquals(2, versions.groupCount)
        assertEquals(3, versions.diagnostics.groupedById)
        assertEquals(0, versions.diagnostics.idTitleMismatches)
    }

    @Test
    fun ruleAKeepsApartOneIdWhoseTitlesDisagree() {
        val transport = movie("Le Transport", 2021, tmdb = 7)
        val other = movie("Ocean Drift", 2021, tmdb = 7)
        val matrix = movie("The Matrix", 1999, tmdb = 9)
        val godfather = movie("The Godfather", 1999, tmdb = 9)
        val alien = movie("Alien", 1979, tmdb = 348)
        val aliens = movie("Aliens", 1979, tmdb = 348)
        val versions = FilmVersions.of(listOf(transport, other, matrix, godfather, alien, aliens))
        assertEquals(6, versions.groupCount, "unrelated titles of one id are not grouped")
        assertEquals(3, versions.diagnostics.idTitleMismatches)
        assertFalse(FilmVersions.titlesAgree("the matrix", "the godfather"), "\"the\" is no shared word")
        assertFalse(FilmVersions.titlesAgree("alien", "aliens"), "a word is not a prefix of another")
    }

    @Test
    fun twoIdsAreNeverGrouped() {
        val a = movie("Dune", 2021, tmdb = 438631)
        val b = movie("Dune", 2021, tmdb = 841)
        val versions = FilmVersions.of(listOf(a, b))
        assertFalse(together(versions, a, b))
    }

    @Test
    fun ruleBGroupsTheSameTitleAndYear() {
        val uhd = movie("Sandstorm 4K", 2021)
        val vostfr = movie("[VOSTFR] Sandstorm", 2021)
        val zero = movie("Sandstorm (MULTI)", 2021, tmdb = 0)
        val versions = FilmVersions.of(listOf(uhd, vostfr, zero))
        assertEquals(listOf(listOf("Sandstorm 4K", "[VOSTFR] Sandstorm", "Sandstorm (MULTI)")), groups(versions), "an id of 0 is no id")
        assertEquals(2, versions.diagnostics.groupedByTitleAndYear)
    }

    @Test
    fun ruleBNeedsTheSameTitleAndAYearOnBoth() {
        val cases = listOf(
            listOf(movie("Sandstorm"), movie("Sandstorm 4K")) to "no year",
            listOf(movie("Sandstorm", 2021), movie("Sandstorm 4K")) to "one year",
            listOf(movie("Sandstorm", 2021), movie("Sandstorm", 2022)) to "years one apart",
            listOf(movie("Sandstorm", 2021), movie("Ocean Drift", 2021)) to "other titles",
            listOf(movie("Alien", 1986), movie("Aliens", 1986)) to "near titles",
            listOf(movie("Sandstorm", 2021), movie("Sandstorm 2", 2021)) to "a sequel",
            listOf(movie("Dune", 1984), movie("Dune", 2021)) to "a remake",
            listOf(movie("Dune", 2021, tmdb = 1), movie("Dune", 2021, tmdb = 2)) to "two ids",
        )
        for ((list, case) in cases) assertEquals(2, FilmVersions.of(list).groupCount, case)
    }

    @Test
    fun aStreamWithoutIdJoinsTheOnlyIdOfItsTitle() {
        val withId = movie("Sandstorm 4K", 2021, tmdb = 5)
        val without = movie("Sandstorm VOSTFR", 2021)
        val versions = FilmVersions.of(listOf(without, withId))
        assertTrue(together(versions, withId, without))
        assertEquals(listOf(listOf("Sandstorm VOSTFR", "Sandstorm 4K")), groups(versions), "groups in catalogue order")
    }

    @Test
    fun aStreamWithoutIdNeverBridgesTwoIds() {
        val one = movie("Dune", 2021, tmdb = 1)
        val two = movie("Dune 4K", 2021, tmdb = 2)
        val without = movie("Dune VOSTFR", 2021)
        val alsoWithout = movie("Dune MULTI", 2021)
        val versions = FilmVersions.of(listOf(one, without, two, alsoWithout))
        assertFalse(together(versions, one, two))
        assertFalse(together(versions, one, without))
        assertFalse(together(versions, two, without))
        assertTrue(together(versions, without, alsoWithout), "the streams without an id stay together")
        assertEquals(2, versions.diagnostics.ambiguousTitles)
    }

    @Test
    fun offIsTheCatalogueAsItIs() {
        val list = listOf(movie("Sandstorm 4K", 2021, id = "x"), movie("Sandstorm", 2021, id = "x"), movie("Dune", 2021, tmdb = 1))
        val versions = FilmVersions.of(list, grouped = false)
        assertFalse(versions.isGrouped)
        assertSame(list, versions.distinct(list))
        val ids = setOf("x")
        assertSame(ids, versions.expand(ids))
        assertEquals(listOf(list[1]), versions.versions(list[1]))
        assertEquals(3, versions.groupCount)
        assertFalse(versions.hasVersions(list[0]))
    }

    /** A search index cached on the versions it was built with found a new "nothing grouped" each time, and was built again. */
    @Test
    fun nothingGroupedIsOneInstance() {
        assertSame(FilmVersions.none(), FilmVersions.none())
        assertSame(FilmVersions.NONE, FilmVersions.of(emptyList()))
    }

    @Test
    fun aFilmIsInEveryCategoryOfItsVersions() {
        val action = movie("Sandstorm 4K", 2021, category = "action")
        val other = movie("Ocean Drift", 2020, category = "action")
        val french = movie("Sandstorm VFF", 2021, category = "french")
        val list = listOf(action, other, french)
        val versions = FilmVersions.of(list)
        assertEquals(listOf(action, other), versions.distinct(list))
        assertEquals(listOf(french), versions.distinct(list.filter { it.categoryId == "french" }))
        assertEquals(listOf(action, french), versions.versions(french))
        assertTrue(versions.hasVersions(french))
        assertEquals(setOf(action.id, french.id), versions.expand(setOf(french.id)))
        assertEquals(setOf("unknown"), versions.expand(setOf("unknown")))
    }

    @Test
    fun badgesComeFromTheNameAndTheCategory() {
        assertEquals(listOf(VersionTag.UHD, VersionTag.DOLBY_VISION, VersionTag.VFF), FilmVersions.tags("|FR| Sandstorm 4K DV VFF", null))
        assertEquals(listOf(VersionTag.UHD, VersionTag.HDR, VersionTag.HEVC), FilmVersions.tags("Sandstorm 2160p HDR10 H.265", null))
        assertEquals(listOf(VersionTag.DOLBY_VISION), FilmVersions.tags("Sandstorm Dolby Vision", null))
        assertEquals(listOf(VersionTag.HD, VersionTag.VOSTFR), FilmVersions.tags("Sandstorm", "FILMS 1080p VOSTFR"))
        assertEquals(listOf(VersionTag.UHD), FilmVersions.tags("Sandstorm FHD", "UHD"), "4K leaves HD out")
        assertEquals(emptyList(), FilmVersions.tags("Sandstorm", "Action"))
        val list = listOf(movie("Sandstorm 4K", 2021), movie("Sandstorm", 2021), movie("Sandstorm 4K", 2021), movie("Sandstorm", 2021))
        val badges = FilmVersions.badges(list) { null }
        assertEquals(listOf("4K", null, "4K 2", null), badges.map { it.label })
        assertEquals(listOf(1, 1, 2, 2), badges.map { it.ordinal }, "two versions of the same tags are two badges")
    }

    @Test
    fun theDefaultVersion() {
        val dv = movie("Sandstorm 4K DV", 2021)
        val uhd = movie("Sandstorm 4K", 2021)
        val hd = movie("Sandstorm HD", 2021)
        val vostfr = movie("Sandstorm VOSTFR", 2021)
        val list = listOf(vostfr, dv, uhd, hd)
        val names: (String) -> String? = { null }
        val everything = PictureSupport(hdr = true, dolbyVision = true, uhd = true)
        assertEquals(hd, FilmVersions.defaultVersion(list, hd.id, listOf(dv.id), everything, names), "the one in progress first")
        assertEquals(vostfr, FilmVersions.defaultVersion(list, "elsewhere", listOf("gone", vostfr.id, dv.id), everything, names), "then the one chosen last")
        assertEquals(dv, FilmVersions.defaultVersion(list, null, emptyList(), everything, names), "then the best for the screen")
        assertEquals(uhd, FilmVersions.defaultVersion(list, null, emptyList(), PictureSupport(uhd = true), names), "no Dolby Vision without the screen for it")
        assertEquals(hd, FilmVersions.defaultVersion(list, null, emptyList(), PictureSupport(), names), "HD on an HD screen")
        assertEquals(vostfr, FilmVersions.defaultVersion(listOf(vostfr, movie("Sandstorm VFF", 2021)), null, emptyList(), everything, names), "else the first")
        assertEquals(dv, FilmVersions.defaultVersion(listOf(dv), null, emptyList(), PictureSupport(), names))
        assertNull(FilmVersions.defaultVersion(emptyList(), null, emptyList(), everything, names))
    }

    /** Random catalogues: every stream in exactly one group, never two ids together, every grouping justified by a rule. */
    @Test
    fun randomCataloguesKeepEveryStreamOnce() {
        val titles = listOf("Sandstorm", "Alien", "Aliens", "Le Transport", "The Matrix", "Matrix", "The Godfather", "Dune", "Dune 2", "Ocean Drift", "Up", "[4K]")
        val decorations = listOf("%s", "%s 4K", "%s VOSTFR", "|FR| %s", "%s (MULTI)", "[DV] %s HDR", "FR - %s", "%s 2")
        val tmdbIds = listOf(null, null, 0, -1, 1, 2, 3, 603)
        val years = listOf(null, 2020, 2021)
        repeat(300) { round ->
            val random = Random(round)
            val list = List(random.nextInt(0, 60)) { index ->
                Movie(
                    id = "s$index",
                    name = decorations.random(random).format(titles.random(random)),
                    categoryId = "c${random.nextInt(3)}",
                    streamUrl = "http://stream.example.com/$index",
                    year = years.random(random),
                    tmdbId = tmdbIds.random(random),
                )
            }
            val versions = FilmVersions.of(list)
            val seen = (0 until versions.groupCount).flatMap { g -> versions.versions(g).map { it.id } }
            assertEquals(list.map { it.id }.sorted(), seen.sorted(), "round $round: every stream once")
            for (g in 0 until versions.groupCount) {
                val group = versions.versions(g)
                val ids = group.mapNotNull { it.tmdbId?.takeIf { id -> id > 0 } }.toSet()
                assertTrue(ids.size <= 1, "round $round: two ids in $group")
                if (group.size < 2) continue
                for (a in group) {
                    val justified = group.any { b ->
                        if (b === a) return@any false
                        val aId = a.tmdbId?.takeIf { it > 0 }
                        val bId = b.tmdbId?.takeIf { it > 0 }
                        val byId = aId != null && aId == bId && FilmVersions.titlesAgree(FilmVersions.titleKey(a.name), FilmVersions.titleKey(b.name))
                        val byTitle = (aId == null || bId == null) && a.year != null && a.year == b.year &&
                            FilmVersions.titleKey(a.name).isNotEmpty() && FilmVersions.titleKey(a.name) == FilmVersions.titleKey(b.name)
                        byId || byTitle
                    }
                    assertTrue(justified, "round $round: ${a.name} in $group by no rule")
                }
            }
            val off = FilmVersions.of(list, grouped = false)
            assertSame(list, off.distinct(list), "round $round: off is the catalogue")
            assertEquals(list.size, off.groupCount)
            assertEquals(versions.groupCount, versions.distinct(list).size, "round $round: one card per film")
        }
    }

    @Test
    fun aPanelGivingOneIdToEverythingStaysQuick() {
        val list = List(20_000) { Movie(id = "s$it", name = "T$it", categoryId = "c", streamUrl = "http://stream.example.com/$it", year = 2020, tmdbId = 1) }
        val started = System.nanoTime()
        val versions = FilmVersions.of(list)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 10_000, "linear, not quadratic")
        assertEquals(list.size, versions.groupCount, "titles that disagree stay apart")
        assertEquals(list.size - 1, versions.diagnostics.idTitleMismatches)
    }
}
