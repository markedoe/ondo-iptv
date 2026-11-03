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
        val hd = movie("Le Transport", null, tmdb = 7)
        val longer = movie("Le Transport : Héritage", 2021, tmdb = 7)
        val matrix = movie("The Matrix", 1999, tmdb = 603)
        val short = movie("Matrix VOSTFR", 1999, tmdb = 603)
        val versions = FilmVersions.of(listOf(uhd, hd, longer, matrix, short))
        assertTrue(together(versions, uhd, hd), "the same title, one year missing")
        assertTrue(together(versions, uhd, longer), "one title starts with the other, the same year on both")
        assertTrue(together(versions, matrix, short), "the same words, \"the\" left out")
        assertEquals(2, versions.groupCount)
        assertEquals(3, versions.diagnostics.groupedById)
        assertEquals(0, versions.diagnostics.idTitleMismatches)
    }

    /** The review's probes: under one id, a remake, a sequel or a saga's next film is another film. */
    @Test
    fun franchisesUnderOneIdStayApart() {
        val pairs = listOf(
            Pair("Dune" to 1984, "Dune" to 2021), Pair("Dune" to 2021, "Dune 2" to 2024), Pair("Dune" to null, "Dune 2" to null),
            Pair("Dune" to 2024, "Dune: Part Two" to 2024), Pair("Alien" to 1979, "Alien: Covenant" to 2017), Pair("Alien" to null, "Alien: Covenant" to null),
            Pair("Toy Story" to 1995, "Toy Story 3" to 1995), Pair("It" to 2017, "It Chapter Two" to 2017),
            Pair("The Matrix" to 1999, "The Matrix Reloaded" to 2003), Pair("Matrix" to null, "Matrix Resurrections" to null),
            Pair("Harry Potter and the Chamber of Secrets" to 2002, "Harry Potter and the Prisoner of Azkaban" to 2002),
            Pair("Harry Potter and the Chamber of Secrets" to null, "Harry Potter and the Goblet of Fire" to null),
            Pair("Star Wars Episode IV" to null, "Star Wars Episode V" to null), Pair("Spider-Man" to 2002, "Spider-Man 2" to 2002),
            Pair("Mission Impossible" to null, "Mission Impossible Fallout" to null), Pair("Halloween" to 2018, "Halloween Kills" to 2021),
            Pair("Scream" to 2022, "Scream VI" to 2022), Pair("Rocky" to 1976, "Rocky Balboa" to 2006), Pair("John Wick" to 2014, "John Wick Chapter 4" to 2014),
            Pair("Fast and Furious 7" to 2015, "Fast and Furious 8" to 2015), Pair("Le Transport" to 2015, "Le Transport 2" to 2015),
            Pair("Le Transport" to null, "Le Transport : Héritage" to null), Pair("Le Film" to 2010, "Le Film deux" to 2010),
        )
        for ((a, b) in pairs) {
            assertFalse(FilmVersions.titlesAgree(FilmVersions.titleKey(a.first), a.second, FilmVersions.titleKey(b.first), b.second), "${a.first} / ${b.first}")
        }
        val saga = listOf(
            "Harry Potter and the Chamber of Secrets", "Harry Potter and the Prisoner of Azkaban", "Harry Potter and the Goblet of Fire",
            "Harry Potter and the Order of the Phoenix", "Harry Potter and the Half-Blood Prince",
        ).mapIndexed { index, title -> movie(title, 2002 + index * 2, tmdb = 1241) }
        assertEquals(5, FilmVersions.of(saga).groupCount, "a saga under one collection id")
        val remake = listOf(movie("Dune", 1984, tmdb = 438631), movie("Dune", 2021, tmdb = 438631), movie("Dune 2", 2024, tmdb = 438631))
        val versions = FilmVersions.of(remake)
        assertEquals(3, versions.groupCount)
        assertEquals(setOf(remake[1].id), versions.expand(setOf(remake[1].id)), "watching one does not hide the others")
    }

    /** A year at the end of a name is the stream's year; a title that is a number stays one. */
    @Test
    fun aYearAtTheEndOfTheNameIsTheYear() {
        assertEquals("dune" to 2021, FilmVersions.keyAndYear("Dune 2021", 2021, latest = 2027))
        assertEquals("dune" to 2021, FilmVersions.keyAndYear("Dune - 2021", null, latest = 2027))
        assertEquals("1917" to 2019, FilmVersions.keyAndYear("1917", 2019, latest = 2027))
        assertEquals("blade runner 2049" to 2017, FilmVersions.keyAndYear("Blade Runner 2049", 2017, latest = 2027))
        assertEquals("blade runner 2049" to null, FilmVersions.keyAndYear("Blade Runner 2049", null, latest = 2027))
        assertEquals("space 1999" to 1975, FilmVersions.keyAndYear("Space 1999", 1975, latest = 2027), "another year: a number of the title")
        assertTrue(FilmVersions.isNumber("二") && FilmVersions.isNumber("٢") && FilmVersions.isNumber("2"))
        assertFalse(FilmVersions.isNumber("地球"))
    }

    /** A stream without a year never brings two years into one group. */
    @Test
    fun oneGroupHoldsOneYear() {
        val none = movie("Dune", null, tmdb = 1)
        val first = movie("Dune", 1984, tmdb = 1)
        val second = movie("Dune", 2021, tmdb = 1)
        val versions = FilmVersions.of(listOf(none, first, second))
        assertTrue(together(versions, none, first))
        assertFalse(together(versions, first, second))
    }

    /** Names made of the providers' tags only, or a bare number, never group. */
    @Test
    fun tagOnlyNamesNeverGroup() {
        val list = listOf(
            movie("4K", 2020), movie("4K", 2020), movie("[FR] 4K", 2020), movie("FR - 2021", 2021), movie("FR - 2021", 2021),
            movie("MULTI", 2020, tmdb = 9), movie("MULTI 4K", 2020, tmdb = 9),
        )
        assertEquals(list.size, FilmVersions.of(list).groupCount)
        assertFalse(FilmVersions.groupable("fr 4k"))
        assertTrue(FilmVersions.groupable("1917"), "a title of digits is a title: rule A may group it")
    }

    /** "French Kiss" is not "Kiss", "Johnny English" not "Johnny". */
    @Test
    fun wordsThatAreAlsoTagsStayWords() {
        assertEquals("french kiss", FilmVersions.titleKey("French Kiss"))
        assertEquals("johnny english", FilmVersions.titleKey("Johnny English"))
        assertEquals(2, FilmVersions.of(listOf(movie("French Kiss", 1995), movie("Kiss", 1995))).groupCount)
    }

    /** A stream a panel lists twice (two categories) keeps each row in its own group. */
    @Test
    fun aStreamListedTwiceKeepsEachRow() {
        val a = movie("Sandstorm", 2021, tmdb = 5, category = "x", id = "dup")
        val b = movie("Ocean Drift", 2020, tmdb = 9, category = "y", id = "dup")
        val c = movie("Ocean Drift 4K", 2020, tmdb = 9, category = "y")
        val versions = FilmVersions.of(listOf(a, b, c))
        assertEquals(listOf(b), versions.distinct(listOf(b, c)))
        assertEquals(listOf(b, c), versions.versions(b))
        assertEquals(listOf(a), versions.versions(a))
        assertEquals(setOf("dup", c.id), versions.expand(setOf("dup")))
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
        assertFalse(FilmVersions.titlesAgree("the matrix", 1999, "the godfather", 1999), "\"the\" is no shared word")
        assertFalse(FilmVersions.titlesAgree("alien", 1979, "aliens", 1979), "a word is not a prefix of another")
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
        // French subtitles however a provider writes them.
        for (name in listOf("STFR| Sandstorm", "ST FR - Sandstorm", "[SUBFR] Sandstorm", "FR SUB | Sandstorm", "Sandstorm SUB FR")) {
            assertEquals(listOf(VersionTag.VOSTFR), FilmVersions.tags(name, null), name)
        }
        assertEquals(emptyList<VersionTag>(), FilmVersions.tags("St Francis", null))
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
                    id = if (index % 7 == 6) "s${index - 1}" else "s$index", // a stream listed twice now and then
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
                if (group.size > 1) {
                    // Forbidden whatever the code: two years, or two different numbers, in one group.
                    assertTrue(group.mapNotNull { it.year }.toSet().size <= 1, "round $round: two years in $group")
                    val numbers = group.map { FilmVersions.titleKey(it.name).split(' ').filter { FilmVersions.isNumber(it) }.sorted() }.toSet()
                    assertEquals(1, numbers.size, "round $round: two numbers in $group")
                    // A stream without an id is with an id only when no other id has its title and year.
                    ids.firstOrNull()?.let { groupId ->
                        for (member in group.filter { (it.tmdbId ?: 0) <= 0 }) {
                            val key = FilmVersions.titleKey(member.name)
                            val others = list.filter { other ->
                                val id = other.tmdbId ?: 0
                                id > 0 && id != groupId && other.year == member.year && FilmVersions.titleKey(other.name) == key
                            }
                            assertTrue(others.isEmpty(), "round $round: ${member.name} joined id $groupId though another id has its title")
                        }
                    }
                }
                if (group.size < 2) continue
                for (a in group) {
                    val justified = group.any { b ->
                        if (b === a) return@any false
                        val aId = a.tmdbId?.takeIf { it > 0 }
                        val bId = b.tmdbId?.takeIf { it > 0 }
                        val byId = aId != null && aId == bId && (
                            FilmVersions.titleKey(a.name) == FilmVersions.titleKey(b.name) ||
                                FilmVersions.titlesAgree(FilmVersions.titleKey(a.name), a.year, FilmVersions.titleKey(b.name), b.year)
                            )
                        val byTitle = (aId == null || bId == null) && a.year != null && a.year == b.year &&
                            FilmVersions.titleKey(a.name).isNotEmpty() && FilmVersions.titleKey(a.name) == FilmVersions.titleKey(b.name)
                        byId || byTitle
                    }
                    assertTrue(justified, "round $round: ${a.name} in $group by no rule")
                }
            }
            // A category's list shows each film it has exactly once.
            for (category in list.map { it.categoryId }.toSet()) {
                val sub = list.filter { it.categoryId == category }
                assertEquals(sub.map { versions.groupOf(it) }.toSet().size, versions.distinct(sub).size, "round $round: category $category")
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
