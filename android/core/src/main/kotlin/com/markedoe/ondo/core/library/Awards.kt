package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.Series
import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Film awards and festival selections (Palme d'Or, Oscars, Golden Bear…) and TV series awards (Emmys, Golden Globes,
 * BAFTA TV…): the data bundled with the apps (`awards.json`, made from Wikidata by `tools/awards/generate_awards.py`),
 * and which movies and series of a playlist they concern. Nothing is sent anywhere: the file is read on the device.
 * Same rules as the Swift `AwardsCatalogue`.
 *
 * Series sit in keys of their own ([seriesAwards], [series]): the apps released before them read only `awards` and
 * `films`, which a newer file keeps as they were. A film is only matched with films, a series with series.
 */
class AwardsCatalogue(
    /** Film awards and festival selections. */
    val awards: List<Award>,
    val films: List<Film>,
    /** The day the file was made ("2026-10-14"): a newer download replaces the copy bundled with the app. */
    val generated: String? = null,
    /** TV series awards; their ids are not those of any film award. */
    val seriesAwards: List<Award> = emptyList(),
    /** Awarded series ([Film.year]: the year each was first aired). */
    val series: List<Film> = emptyList(),
) {
    /** [group] is the festival or ceremony ("cannes", "oscars"…); [names] by app language ("en", "fr", "zh-Hans"…). */
    data class Award(val id: String, val group: String, val names: Map<String, String>) {
        fun name(language: String): String = names[language] ?: names["en"] ?: id
    }

    /** One award for one film: won, or competed for (festival selection, nomination). */
    data class Entry(val awardId: String, val year: Int, val won: Boolean)

    data class Film(val titles: List<String>, val year: Int?, val tmdb: Int?, val awards: List<Entry>)

    /** A film or a series award. */
    fun award(id: String): Award? = awards.firstOrNull { it.id == id } ?: seriesAwards.firstOrNull { it.id == id }

    /** Whether this catalogue was made after another one (a missing date counts as older). */
    fun isNewerThan(other: AwardsCatalogue): Boolean = (generated ?: "") > (other.generated ?: "")

    /** The festivals and ceremonies, in the order of the file (those of films, then those of series only). */
    val groups: List<String> get() = (awards + seriesAwards).map { it.group }.distinct()

    /**
     * Awards of the playlist's movies, by movie id, wins first then the most recent. A movie is recognised by its
     * TMDb id when known, otherwise by its title and year (one year apart at most); a title without a year only
     * when a single awarded film has that title.
     */
    fun match(movies: List<Movie>, tmdbIds: Map<String, Int> = emptyMap()): Map<String, List<Entry>> =
        match(movies.map { Triple(it.id, it.name, it.year) }, films, tmdbIds)

    /**
     * Awards of the playlist's series, by series id: the same rules as movies, among the awarded series only (a film of
     * the same title is never one of them), by the year the series was first aired and its TMDb TV id.
     */
    fun matchSeries(shows: List<Series>, tmdbIds: Map<String, Int> = emptyMap()): Map<String, List<Entry>> =
        match(shows.map { Triple(it.id, it.name, it.year) }, series, tmdbIds)

    private fun match(titles: List<Triple<String, String, Int?>>, list: List<Film>, tmdbIds: Map<String, Int>): Map<String, List<Entry>> {
        if (list.isEmpty()) return emptyMap()
        val byTitle = HashMap<String, MutableList<Int>>()
        val byTmdb = HashMap<Int, Int>()
        list.forEachIndexed { index, film ->
            film.titles.map(CatalogueMatcher::key).filter { it.isNotEmpty() }.toSet().forEach { byTitle.getOrPut(it) { ArrayList() }.add(index) }
            film.tmdb?.let { byTmdb[it] = index }
        }
        val result = HashMap<String, List<Entry>>()
        for ((id, name, year) in titles) {
            val found = tmdbIds[id]?.let { byTmdb[it] } ?: run {
                val candidates = byTitle[CatalogueMatcher.key(name)].orEmpty()
                if (year != null) candidates.firstOrNull { list[it].year?.let { filmYear -> abs(filmYear - year) <= 1 } == true }
                else candidates.singleOrNull()
            }
            if (found != null) {
                result[id] = list[found].awards.sortedWith(compareByDescending<Entry> { it.won }.thenByDescending { it.year })
            }
        }
        return result
    }

    companion object {
        val EMPTY = AwardsCatalogue(emptyList(), emptyList())

        fun parse(text: String): AwardsCatalogue {
            val root = Json.parseToJsonElement(text).jsonObject
            fun awards(key: String) = root[key]?.jsonArray.orEmpty().map { element ->
                val award = element.jsonObject
                Award(
                    id = award.getValue("id").jsonPrimitive.content,
                    group = award.getValue("group").jsonPrimitive.content,
                    names = award["names"]?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.content },
                )
            }
            fun films(key: String) = root[key]?.jsonArray.orEmpty().map { element ->
                val film = element.jsonObject
                Film(
                    titles = film["titles"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content },
                    year = film["year"]?.jsonPrimitive?.intOrNull,
                    tmdb = film["tmdb"]?.jsonPrimitive?.intOrNull,
                    awards = film["awards"]?.jsonArray.orEmpty().map { entry ->
                        val values = entry as JsonArray
                        Entry(values[0].jsonPrimitive.content, values[1].jsonPrimitive.int, values[2].jsonPrimitive.int == 1)
                    },
                )
            }
            // Files made before series had awards have neither `seriesAwards` nor `series`.
            return AwardsCatalogue(
                awards("awards"), films("films"), root["generated"]?.jsonPrimitive?.contentOrNull,
                seriesAwards = awards("seriesAwards"), series = films("series"),
            )
        }
    }
}
