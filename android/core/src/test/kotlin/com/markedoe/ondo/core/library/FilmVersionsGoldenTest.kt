package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.model.Movie
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The groups written by hand in `fixtures/film-versions/golden.json`, the same file Apple's `FilmVersionsGoldenTests`
 * reads: both cores make the same groups of the same catalogues, and the rules are judged by the expected groups, not
 * by the code that makes them.
 */
class FilmVersionsGoldenTest {
    private val cases = Json.parseToJsonElement(File("../../fixtures/film-versions/golden.json").readText()).jsonObject["cases"]!!.jsonArray

    @Test
    fun everyCaseMakesItsExpectedGroups() {
        for (case in cases.map { it.jsonObject }) {
            val name = case["name"]!!.jsonPrimitive.content
            val rows = case["rows"]!!.jsonArray.mapIndexed { index, element ->
                val row = element.jsonObject
                Movie(
                    id = row["id"]!!.jsonPrimitive.content,
                    name = row["name"]!!.jsonPrimitive.content,
                    categoryId = row["category"]!!.jsonPrimitive.content,
                    streamUrl = "http://stream.example.com/$index",
                    year = row["year"]?.jsonPrimitive?.intOrNull,
                    tmdbId = row["tmdb"]?.jsonPrimitive?.intOrNull,
                )
            }
            fun rowOf(movie: Movie) = rows.indexOfFirst { it === movie }
            val versions = FilmVersions.of(rows)
            val groups = (0 until versions.groupCount).map { group -> versions.versions(group).map(::rowOf) }
            assertEquals(indices(case["groups"]!!), groups, name)
            for (sublist in (case["sublists"] as? JsonArray).orEmpty().map { it.jsonObject }) {
                val category = sublist["category"]!!.jsonPrimitive.content
                val shown = versions.distinct(rows.filter { it.categoryId == category }).map(::rowOf)
                assertEquals(sublist["distinct"]!!.jsonArray.map { it.jsonPrimitive.int }, shown, "$name, category $category")
            }
            (case["diagnostics"] as? JsonObject)?.forEach { (key, value) ->
                val actual = when (key) {
                    "idTitleMismatches" -> versions.diagnostics.idTitleMismatches
                    "prefixWithoutYears" -> versions.diagnostics.prefixWithoutYears
                    "ambiguousTitles" -> versions.diagnostics.ambiguousTitles
                    else -> error("unknown diagnostic $key")
                }
                assertEquals(value.jsonPrimitive.int, actual, "$name, $key")
            }
            // Every row in exactly one group.
            assertEquals(rows.indices.toList(), groups.flatten().sorted(), name)
        }
    }

    private fun indices(element: kotlinx.serialization.json.JsonElement) = element.jsonArray.map { group -> group.jsonArray.map { it.jsonPrimitive.int } }
}
