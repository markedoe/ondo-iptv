package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.model.Movie
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
 * Film awards and festival selections (Palme d'Or, Oscars, Golden Bear…): the data bundled with the apps
 * (`awards.json`, made from Wikidata by `tools/awards/generate_awards.py`), and which movies of a playlist they
 * concern. Nothing is sent anywhere: the file is read on the device. Same rules as the Swift `AwardsCatalogue`.
 */
class AwardsCatalogue(
    val awards: List<Award>,
    val films: List<Film>,
    /** The day the file was made ("2026-10-14"): a newer download replaces the copy bundled with the app. */
    val generated: String? = null,
) {
    /** [group] is the festival or ceremony ("cannes", "oscars"…); [names] by app language ("en", "fr", "zh-Hans"…). */
    data class Award(val id: String, val group: String, val names: Map<String, String>) {
        fun name(language: String): String = names[language] ?: names["en"] ?: id
    }

    /** One award for one film: won, or competed for (festival selection, nomination). */
    data class Entry(val awardId: String, val year: Int, val won: Boolean)

    data class Film(val titles: List<String>, val year: Int?, val tmdb: Int?, val awards: List<Entry>)

    fun award(id: String): Award? = awards.firstOrNull { it.id == id }

    /** Whether this catalogue was made after another one (a missing date counts as older). */
    fun isNewerThan(other: AwardsCatalogue): Boolean = (generated ?: "") > (other.generated ?: "")

    /** The festivals and ceremonies, in the order of the file. */
    val groups: List<String> get() = awards.map { it.group }.distinct()

    /**
     * Awards of the playlist's movies, by movie id, wins first then the most recent. A movie is recognised by its
     * TMDb id when known, otherwise by its title and year (one year apart at most); a title without a year only
     * when a single awarded film has that title.
     */
    fun match(movies: List<Movie>, tmdbIds: Map<String, Int> = emptyMap()): Map<String, List<Entry>> {
        if (films.isEmpty()) return emptyMap()
        val byTitle = HashMap<String, MutableList<Int>>()
        val byTmdb = HashMap<Int, Int>()
        films.forEachIndexed { index, film ->
            film.titles.map(CatalogueMatcher::key).filter { it.isNotEmpty() }.toSet().forEach { byTitle.getOrPut(it) { ArrayList() }.add(index) }
            film.tmdb?.let { byTmdb[it] = index }
        }
        val result = HashMap<String, List<Entry>>()
        for (movie in movies) {
            val found = tmdbIds[movie.id]?.let { byTmdb[it] } ?: run {
                val candidates = byTitle[CatalogueMatcher.key(movie.name)].orEmpty()
                val year = movie.year
                if (year != null) candidates.firstOrNull { films[it].year?.let { filmYear -> abs(filmYear - year) <= 1 } == true }
                else candidates.singleOrNull()
            }
            if (found != null) {
                result[movie.id] = films[found].awards.sortedWith(compareByDescending<Entry> { it.won }.thenByDescending { it.year })
            }
        }
        return result
    }

    companion object {
        val EMPTY = AwardsCatalogue(emptyList(), emptyList())

        fun parse(text: String): AwardsCatalogue {
            val root = Json.parseToJsonElement(text).jsonObject
            val awards = root["awards"]?.jsonArray.orEmpty().map { element ->
                val award = element.jsonObject
                Award(
                    id = award.getValue("id").jsonPrimitive.content,
                    group = award.getValue("group").jsonPrimitive.content,
                    names = award["names"]?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.content },
                )
            }
            val films = root["films"]?.jsonArray.orEmpty().map { element ->
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
            return AwardsCatalogue(awards, films, root["generated"]?.jsonPrimitive?.contentOrNull)
        }
    }
}
