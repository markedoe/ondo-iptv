package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.Fixtures
import com.markedoe.ondo.core.HangGuard
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.Series
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule

/** TV series awards. Same cases as the Swift core (`awards_series_sample.json` in both test suites). */
class SeriesAwardsTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    private val catalogue = AwardsCatalogue.parse(Fixtures.bytes("awards_series_sample.json").decodeToString())

    private fun movie(id: String, name: String, year: Int?) = Movie(id = id, name = name, categoryId = "m", streamUrl = "http://example.com/movie/$id.mp4", year = year)

    private fun show(id: String, name: String, year: Int?) = Series(id = id, name = name, categoryId = "s", year = year)

    @Test
    fun seriesAreRecognisedByTitleAndFirstAirYear() {
        val found = catalogue.matchSeries(
            listOf(
                show("1", "EN - Breaking Bad (2008)", 2008),
                show("2", "Breaking Bad", 2009),
                show("3", "Breaking Bad", 2011),
                show("4", "The Office", 2005),
                show("5", "The Office", null),
                show("6", "Unknown Show", 2020),
            ),
        )
        assertEquals(AwardsCatalogue.Entry("emmy-drama-series", 2014, true), found["1"]?.first(), "wins first, the most recent")
        assertEquals(3, found["1"]?.size)
        assertNotNull(found["2"], "one year apart")
        assertNull(found["3"], "three years apart")
        assertEquals(listOf(AwardsCatalogue.Entry("golden-globe-tv-drama", 2006, false)), found["4"], "the 2005 series")
        assertNull(found["5"], "two awarded series are called The Office")
        assertNull(found["6"])
    }

    @Test
    fun aFilmAndASeriesOfTheSameTitleNeverCrossMatch() {
        val movies = catalogue.match(listOf(movie("m1", "Fargo", 1996), movie("m2", "Fargo", 2014), movie("m3", "Breaking Bad", 2008)))
        assertEquals(listOf(AwardsCatalogue.Entry("palme-dor", 1996, false)), movies["m1"], "the film's award only")
        assertNull(movies["m2"], "a movie is never matched with a series")
        assertNull(movies["m3"], "a movie is never matched with a series")
        val shows = catalogue.matchSeries(listOf(show("s1", "Fargo", 2014), show("s2", "Fargo", 1996), show("s3", "The Third Man", null)))
        assertEquals(listOf("emmy-limited-series", "golden-globe-tv-drama"), shows["s1"]?.map { it.awardId }, "the series' awards only")
        assertNull(shows["s2"], "a series is never matched with a film")
        assertNull(shows["s3"], "a series is never matched with a film")
        // By TMDb id: a series' TV id is looked up among series only.
        assertNull(catalogue.match(listOf(movie("m4", "Other", null)), tmdbIds = mapOf("m4" to 60622))["m4"])
        assertEquals("emmy-limited-series", catalogue.matchSeries(listOf(show("s4", "Other", null)), tmdbIds = mapOf("s4" to 60622))["s4"]?.first()?.awardId)
        assertNull(catalogue.matchSeries(listOf(show("s5", "Other", null)), tmdbIds = mapOf("s5" to 275))["s5"])
    }

    @Test
    fun seriesAwardsAreNamedAndGrouped() {
        assertEquals("Primetime Emmy Award de la meilleure série télévisée dramatique", catalogue.award("emmy-drama-series")?.name("fr"))
        assertEquals("Golden Globe Award for Best Television Series – Drama", catalogue.award("golden-globe-tv-drama")?.name("fr"))
        assertEquals("cannes", catalogue.award("palme-dor")?.group)
        assertEquals(listOf("cannes", "golden-globes", "emmys"), catalogue.groups, "films' groups, then those of series only")
    }

    @Test
    fun aFileWithoutSeriesStillReads() {
        val old = AwardsCatalogue.parse(Fixtures.bytes("awards_sample.json").decodeToString())
        assertTrue(old.series.isEmpty())
        assertTrue(old.seriesAwards.isEmpty())
        assertTrue(old.matchSeries(listOf(show("1", "Parasite", 2019))).isEmpty())
    }

    /** The parser of the apps released before series had awards, as it was (only `awards`, `films`, `generated`). */
    private fun releasedParse(text: String): AwardsCatalogue {
        val root = Json.parseToJsonElement(text).jsonObject
        val awards = root["awards"]?.jsonArray.orEmpty().map { element ->
            val award = element.jsonObject
            AwardsCatalogue.Award(
                id = award.getValue("id").jsonPrimitive.content,
                group = award.getValue("group").jsonPrimitive.content,
                names = award["names"]?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.content },
            )
        }
        val films = root["films"]?.jsonArray.orEmpty().map { element ->
            val film = element.jsonObject
            AwardsCatalogue.Film(
                titles = film["titles"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content },
                year = film["year"]?.jsonPrimitive?.intOrNull,
                tmdb = film["tmdb"]?.jsonPrimitive?.intOrNull,
                awards = film["awards"]?.jsonArray.orEmpty().map { entry ->
                    val values = entry as JsonArray
                    AwardsCatalogue.Entry(values[0].jsonPrimitive.content, values[1].jsonPrimitive.int, values[2].jsonPrimitive.int == 1)
                },
            )
        }
        return AwardsCatalogue(awards, films, root["generated"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun theReleasedParserReadsTheSameFilmsAndNoSeries() {
        // The bundled file, the one the "Awards data" workflow publishes for every app, released or not.
        val text = File("../app/src/main/assets/awards.json").readText()
        val released = releasedParse(text)
        val current = AwardsCatalogue.parse(text)
        assertTrue(released.films.size >= 1_000, "the released apps keep a download of 1,000 films or more")
        assertTrue(current.series.isNotEmpty(), "the file has series")
        assertEquals(current.awards, released.awards, "the film awards, as before")
        assertEquals(current.films, released.films, "the same films")
        assertTrue(released.seriesAwards.isEmpty() && released.series.isEmpty())
        // No series award reaches a released app: none is among its awards, none among its films' entries.
        val seriesAwardIds = current.seriesAwards.map { it.id }.toSet()
        assertTrue(released.awards.none { it.id in seriesAwardIds })
        assertFalse(released.films.any { film -> film.awards.any { it.awardId in seriesAwardIds } })
        // And the series are not in its films: a series listed as a film would be shown as one.
        val breakingBad = current.series.first { "Breaking Bad" in it.titles }
        assertFalse(released.films.any { "Breaking Bad" in it.titles && it.year == breakingBad.year })
    }
}
