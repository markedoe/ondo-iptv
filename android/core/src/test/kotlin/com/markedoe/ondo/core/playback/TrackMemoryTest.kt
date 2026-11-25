package com.markedoe.ondo.core.playback

import com.markedoe.ondo.core.HangGuard
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.io.File

/** The tracks kept per film and series (`fixtures/track-memory`, the same cases as the Swift `TrackMemoryTests`). */
class TrackMemoryTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    private val json = Json { ignoreUnknownKeys = true }
    private val cases: JsonObject = Json.parseToJsonElement(File("../../fixtures/track-memory/cases.json").readText()).jsonObject

    private fun string(element: JsonElement?): String? = element?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
    private fun int(element: JsonElement?): Int? = element?.takeIf { it !is JsonNull }?.jsonPrimitive?.intOrNull
    private fun flag(element: JsonElement?): Boolean = element?.takeIf { it !is JsonNull }?.jsonPrimitive?.booleanOrNull ?: false

    private fun track(element: JsonElement): StreamTrack = element.jsonObject.let {
        StreamTrack(string(it["language"]), string(it["label"]), flag(it["forced"]), flag(it["hearingImpaired"]), string(it["codec"]))
    }

    private fun tracks(element: JsonElement?): List<StreamTrack> = element?.jsonArray?.map { track(it) }.orEmpty()
    private fun pick(element: JsonElement?): TrackPick? = element?.takeIf { it !is JsonNull }?.let { json.decodeFromJsonElement(TrackPick.serializer(), it) }
    private fun memory(element: JsonElement?): TitleTracks? = element?.takeIf { it !is JsonNull }?.let { json.decodeFromJsonElement(TitleTracks.serializer(), it) }
    private fun defaults(element: JsonElement?): TrackDefaults? = element?.takeIf { it !is JsonNull }?.let { json.decodeFromJsonElement(TrackDefaults.serializer(), it) }

    @Test
    fun languagesAreComparedAsBothCoresDo() {
        for (case in cases.getValue("languages").jsonArray) {
            val (code, expected) = case.jsonArray.map { string(it) }
            assertEquals("$code", expected, TrackMemory.language(code))
        }
    }

    @Test
    fun aChoiceFindsItsTrackByLanguageAndKind() {
        for (case in cases.getValue("matches").jsonArray.map { it.jsonObject }) {
            val found = TrackMemory.match(tracks(case["tracks"]), pick(case["pick"])!!, int(case["current"]))
            assertEquals(string(case["name"]), int(case["expected"]), found)
        }
    }

    @Test
    fun aStreamStartsWithTheTitlesTracksThenTheProfilesDefaults() {
        for (case in cases.getValue("plans").jsonArray.map { it.jsonObject }) {
            val plan = TrackMemory.plan(tracks(case["audio"]), tracks(case["subtitles"]), memory(case["memory"]), defaults(case["defaults"]))
            val expected = case.getValue("expected").jsonObject
            assertEquals(string(case["name"]), TrackPlan(int(expected["audio"]), int(expected["subtitles"])), plan)
        }
    }

    @Test
    fun aChoiceIsRememberedForItsTitle() {
        for (case in cases.getValue("remember").jsonArray.map { it.jsonObject }) {
            val memories = case.getValue("memories").jsonArray.map { memory(it)!! }
            val keys = case.getValue("keys").jsonArray.map { string(it)!! }
            val at = case.getValue("at").jsonPrimitive.longOrNull!!
            val result = TrackMemory.remember(memories, keys, pick(case["audio"]), pick(case["subtitles"]), flag(case["subtitlesOff"]), at)
            assertEquals(string(case["name"]), case.getValue("expected").jsonArray.map { memory(it)!! }, result)
        }
    }

    @Test
    fun subtitlesAreOffOnlyWhenTheTitleOrTheProfileSaysSo() {
        for (case in cases.getValue("subtitlesOff").jsonArray.map { it.jsonObject }) {
            assertEquals(string(case["name"]), flag(case["expected"]), TrackMemory.subtitlesOff(memory(case["memory"]), defaults(case["defaults"])))
        }
    }

    @Test
    fun aSeriesKeepsItsTracksForAllItsEpisodes() {
        for (case in cases.getValue("titleKeys").jsonArray) {
            val (progress, expected) = case.jsonArray.map { string(it) }
            assertEquals("$progress", expected, TrackMemory.titleKey(progress))
        }
    }

    /** French sound and English subtitles chosen on episode 1: episode 2, whose tracks come in another order, starts with them. */
    @Test
    fun episodeTwoStartsWithWhatWasChosenOnEpisodeOne() {
        val episode1Audio = listOf(StreamTrack("eng"), StreamTrack("fre"))
        val episode1Subtitles = listOf(StreamTrack("fre"), StreamTrack("eng"))
        var memories = TrackMemory.remember(emptyList(), listOf("p|SERIES|s1"), audio = TrackPick.of(episode1Audio[1]), at = 1)
        memories = TrackMemory.remember(memories, listOf("p|SERIES|s1"), subtitles = TrackPick.of(episode1Subtitles[1]), at = 2)
        val episode2Audio = listOf(StreamTrack("fr"), StreamTrack("en"), StreamTrack("de"))
        val episode2Subtitles = listOf(StreamTrack("de"), StreamTrack("en"), StreamTrack("fr"))
        val key = TrackMemory.titleKey("p|SERIES|s1|e2")!!
        val plan = TrackMemory.plan(episode2Audio, episode2Subtitles, TrackMemory.remembered(memories, listOf(key)), TrackDefaults(audio = "en"))
        assertEquals(TrackPlan(audio = 0, subtitles = 1), plan)
    }

    @Test
    fun onlyTheLatestTitlesAreKept() {
        var memories = emptyList<TitleTracks>()
        for (n in 0..TrackMemory.MAX_TITLES) memories = TrackMemory.remember(memories, listOf("p|MOVIE|$n"), audio = TrackPick("fr"), at = n.toLong())
        assertEquals(TrackMemory.MAX_TITLES, memories.size)
        assertEquals("p|MOVIE|${TrackMemory.MAX_TITLES}", memories.first().key)
        assertNull(TrackMemory.remembered(memories, listOf("p|MOVIE|0")))
    }
}
