package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.Fixtures
import com.markedoe.ondo.core.model.Movie
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Same cases as the Swift core (`awards_sample.json` in both test suites). */
class AwardsTest {
    private val catalogue = AwardsCatalogue.parse(Fixtures.bytes("awards_sample.json").decodeToString())

    private fun movie(id: String, name: String, year: Int?) = Movie(id = id, name = name, categoryId = "m", streamUrl = "http://example.com/movie/$id.mp4", year = year)

    @Test
    fun moviesAreRecognisedByTitleAndYearInAnyLanguage() {
        val found = catalogue.match(
            listOf(
                movie("1", "FR - Pulp Fiction [MULTI] (1994)", 1994),
                movie("2", "Anatomie d’une chute", 2024),
                movie("3", "Parasite", 2019),
                movie("4", "Parasite", 1982),
                movie("5", "Unknown Film", 2020),
            ),
        )
        assertEquals(AwardsCatalogue.Entry("palme-dor", 1994, true), found["1"]?.first(), "wins first")
        assertEquals(2, found["1"]?.size)
        assertEquals("cesar-best-film", found["2"]?.first()?.awardId, "the French title, one year apart")
        assertEquals(AwardsCatalogue.Entry("oscar-best-picture", 2020, true), found["3"]?.first())
        assertEquals(listOf(AwardsCatalogue.Entry("palme-dor", 1982, false)), found["4"], "the 1982 film, not the 2019 one")
        assertNull(found["5"])
    }

    @Test
    fun aTitleWithoutAYearMustBeUnique() {
        val found = catalogue.match(listOf(movie("1", "Parasite", null), movie("2", "The Third Man", null)))
        assertNull(found["1"], "two awarded films are called Parasite")
        assertEquals("palme-dor", found["2"]?.first()?.awardId)
    }

    @Test
    fun aTmdbIdWinsOverTheTitle() {
        val found = catalogue.match(listOf(movie("1", "Some Other Name", null)), tmdbIds = mapOf("1" to 680))
        assertEquals("palme-dor", found["1"]?.first()?.awardId)
    }

    @Test
    fun awardNamesFallBackToEnglish() {
        assertEquals("Palme d'or", catalogue.award("palme-dor")?.name("fr"))
        assertEquals("César Award for Best Film", catalogue.award("cesar-best-film")?.name("fr"))
        assertEquals(listOf("cannes", "oscars", "cesars"), catalogue.groups)
    }

    @Test
    fun aCatalogueKnowsWhenItWasMade() {
        val made = AwardsCatalogue.parse("""{"generated":"2026-10-14","awards":[],"films":[]}""")
        assertEquals("2026-10-14", made.generated)
        assertTrue(made.isNewerThan(AwardsCatalogue.EMPTY))
        assertFalse(AwardsCatalogue.EMPTY.isNewerThan(made))
    }
}
