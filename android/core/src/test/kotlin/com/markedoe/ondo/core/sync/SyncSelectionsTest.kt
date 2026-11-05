package com.markedoe.ondo.core.sync

import com.markedoe.ondo.core.HangGuard
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Selections in sync (docs/SYNC.md, "Selections"): each one merging on its own, deletions, each profile's own, older
 * devices, the digest, the limits. The same cases as the Swift `SyncSelectionsTests` (Packages/IPTVCore).
 */
class SyncSelectionsTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    private val sam = SyncProfile("c3000000-0000-4000-8000-00000000000c", "Sam", updatedAt = 5)
    private val dramas = SyncSelection("s1", "Dramas", listOf("decade:1990", "genre:drama"), SyncSelection.ANY, listOf("home", "movie"), 100)
    private val anime = SyncSelection("s2", "Anime", listOf("style:manga"), SyncSelection.ALL, listOf("series"), 100)

    /** What a device of an older version keeps of [snapshot]: it reads it ignoring `selections`, its own and each profile's. */
    private fun olderVersion(snapshot: SyncSnapshot): SyncSnapshot {
        fun JsonObject.without(name: String) = JsonObject(filterKeys { it != name })
        val read = Json.parseToJsonElement(snapshot.encoded().decodeToString()).jsonObject.without("selections")
        val kept = JsonObject(read + ("profileData" to JsonArray(read["profileData"]?.jsonArray.orEmpty().map { it.jsonObject.without("selections") })))
        return requireNotNull(SyncSnapshot.decode(kept.toString().encodeToByteArray()))
    }

    @Test
    fun eachSelectionMergesOnItsOwnTheLaterChangeWinning() {
        val renamed = dramas.copy(name = "Nineties dramas", tags = listOf("genre:drama"), match = SyncSelection.ALL, updatedAt = 200)
        val a = SyncSnapshot(device = "a", selections = listOf(renamed))
        val b = SyncSnapshot(device = "b", selections = listOf(dramas, anime))
        val merged = SyncMerge.merge(a, b)
        assertEquals(listOf(renamed, anime), merged.selections)
        assertEquals(merged.copy(device = ""), SyncMerge.merge(b, a).copy(device = ""))
        // Merging again changes nothing.
        assertEquals(merged, SyncMerge.merge(merged, b))
        // The same time on both sides: the tie string decides, whatever the order.
        val other = dramas.copy(name = "Other")
        assertEquals(SyncMerge.merge(SyncSnapshot(device = "a", selections = listOf(dramas)), SyncSnapshot(device = "b", selections = listOf(other))).selections,
            SyncMerge.merge(SyncSnapshot(device = "a", selections = listOf(other)), SyncSnapshot(device = "b", selections = listOf(dramas))).selections)
    }

    @Test
    fun aDeletionWinsOverASelectionNotChangedSince() {
        val deleted = SyncSnapshot(device = "a", deleted = mapOf(dramas.key to 150))
        val b = SyncSnapshot(device = "b", selections = listOf(dramas, anime))
        val merged = SyncMerge.merge(deleted, b)
        assertEquals(listOf(anime), merged.selections)
        assertEquals(150L, merged.deleted["selection:s1"])
        // Changed after it was deleted (on a device that had not heard of it): it stays.
        val changed = SyncSnapshot(device = "b", selections = listOf(dramas.copy(updatedAt = 160)))
        assertEquals(listOf("s1"), SyncMerge.merge(deleted, changed).selections.map { it.id })
        // A deletion's ID is read in lowercase, as the selection's.
        assertEquals(listOf(anime), SyncMerge.merge(SyncSnapshot(device = "a", deleted = mapOf("selection:S1" to 150)), b).selections)
    }

    @Test
    fun eachProfileHasItsOwnSelections() {
        val a = SyncSnapshot(device = "a", selections = listOf(dramas), profiles = listOf(sam), profileData = listOf(SyncProfileData(sam.id, selections = listOf(anime))))
        val b = SyncSnapshot(
            device = "b",
            profiles = listOf(sam),
            profileData = listOf(SyncProfileData(sam.id, deleted = mapOf(anime.key to 300))),
        )
        val merged = SyncMerge.merge(a, b)
        assertEquals(listOf(dramas), merged.selections)
        assertEquals(emptyList<SyncSelection>(), merged.profileData.single().selections)
        assertEquals(300L, merged.profileData.single().deleted[anime.key])
        // Sam's selection does not touch the main profile's of the same ID.
        val samOwn = SyncSnapshot(device = "c", profiles = listOf(sam), profileData = listOf(SyncProfileData(sam.id, selections = listOf(dramas.copy(name = "Sam's", updatedAt = 400)))))
        val both = SyncMerge.merge(a, samOwn)
        assertEquals(listOf(dramas), both.selections)
        assertEquals(listOf("Sam's", "Anime"), both.profileData.single().selections.map { it.name }.sortedDescending())
    }

    @Test
    fun anOlderDeviceLosesNoSelectionsAndTheDigestIsAsBefore() {
        val snapshot = SyncSnapshot(device = "a", rows = listOf(SyncRows(SyncRows.MOVIES, listOf("all", "sel:s1"), updatedAt = 90)))
        val digest = snapshot.contentDigest()
        assertFalse("selections" in snapshot.encoded().decodeToString())
        assertEquals(digest, SyncMerge.merge(snapshot, SyncSnapshot(device = "b")).contentDigest())
        // With selections, its own and a profile's: another digest, read back as written.
        val withSelections = snapshot.copy(selections = listOf(dramas), profiles = listOf(sam), profileData = listOf(SyncProfileData(sam.id, selections = listOf(anime))))
        assertTrue(digest != withSelections.contentDigest())
        assertEquals(withSelections, SyncSnapshot.decode(withSelections.encoded()))
        // An older device keeps none, and removes none when it answers.
        val older = olderVersion(withSelections)
        assertEquals(emptyList<SyncSelection>(), older.selections)
        val back = SyncMerge.merge(withSelections, older)
        assertEquals(listOf(dramas), back.selections)
        assertEquals(listOf(anime), back.profileData.single().selections)
    }

    @Test
    fun selectionsAreKeptAsEveryDeviceKeepsThem() {
        val messy = SyncSelection(
            "S3", "Na\u0007me", listOf("style:manga", "genre:drama", "genre:drama", "mood:dark", " ", "a\nb"), "", listOf("series", "home", "series"), 7,
        )
        val read = requireNotNull(SyncSnapshot.decode(SyncSnapshot(device = "a", selections = listOf(messy, SyncSelection(" ", updatedAt = 1))).encoded()))
        // The ID in lowercase, no control character, tags and tabs each once and sorted (unknown kinds kept), a match.
        assertEquals(
            listOf(SyncSelection("s3", "Name", listOf("genre:drama", "mood:dark", "style:manga"), SyncSelection.ANY, listOf("home", "series"), 7)),
            read.selections,
        )
        // Past the limit, those changed last stay, the same on every device.
        val many = (0 until SyncSelection.MAX_SELECTIONS + 5).map { SyncSelection("id$it", updatedAt = (it % 50).toLong()) }
        val kept = requireNotNull(SyncSnapshot.decode(SyncSnapshot(device = "a", selections = many).encoded())).selections
        assertEquals(SyncSelection.MAX_SELECTIONS, kept.size)
        assertFalse(kept.any { it.id == "id0" })
        assertEquals(kept.map { it.id }.sorted(), SyncMerge.merge(SyncSnapshot(device = "a", selections = many.reversed()), SyncSnapshot(device = "b")).selections.map { it.id }.sorted())
    }

    @Test
    fun selectionsDatedFarAheadAreLeftOut() {
        val snapshot = SyncSnapshot(device = "a", selections = listOf(dramas, anime.copy(updatedAt = 5_000)), deleted = mapOf("selection:x" to 6_000))
        val kept = snapshot.withoutRecordsAfter(1_000)
        assertEquals(listOf(dramas), kept.selections)
        assertEquals(emptyMap<String, Long>(), kept.deleted)
        assertEquals(6_000L, snapshot.latestTime())
    }
}
