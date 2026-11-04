package com.markedoe.ondo.core.sync

import com.markedoe.ondo.core.HangGuard
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Lists shared with every profile (docs/SYNC.md, "Shared lists"): the shared examples, sharing and taking back a list,
 * concurrent edits, deletion for every profile, Kids profiles, older devices and digests. The same cases as the Swift
 * `SharedListsTests` (Packages/IPTVCore), on the same example files.
 */
class SharedListsTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    private fun fixture(name: String): SyncSnapshot {
        val directory = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "fixtures/sync") }.first { it.isDirectory }
        return requireNotNull(SyncSnapshot.decode(File(directory, name).readBytes())) { name }
    }

    private fun SyncSnapshot.withoutDevice() = copy(device = "", deviceName = null)

    // The shared examples

    @Test
    fun theSharedExamplesMergeInEitherOrder() {
        val a = fixture("shared-lists-a.json")
        val b = fixture("shared-lists-b.json")
        val expected = fixture("shared-lists-merged.json")
        assertEquals(expected, SyncMerge.merge(a, b))
        assertEquals(expected.withoutDevice(), SyncMerge.merge(b, a).withoutDevice())
        assertEquals(expected, SyncMerge.merge(expected, expected))
        assertEquals(expected, SyncMerge.merge(expected, b))
        assertEquals(expected.withoutDevice(), SyncMerge.merge(a, expected).withoutDevice())
    }

    @Test
    fun everyListOfTheSharedExamplesEndsInOneSpace() {
        val merged = fixture("shared-lists-merged.json")
        val ids = merged.household?.lists.orEmpty().map { it.id } + merged.lists.map { it.id } + merged.profileData.flatMap { bucket -> bucket.lists.map { it.id } }
        assertEquals(ids.size, ids.toSet().size)
        // Shared on one device while another added to it in Sam's lists: shared, with that item, and gone from Sam's.
        assertEquals(listOf("m1", "m2", "m3"), shared(LIST, merged)?.items?.map { it.item })
        assertEquals(3050L, merged.profileData.first { it.profile == SAM }.deleted["list:$LIST"])
    }

    // Sharing and taking back

    @Test
    fun sharingMovesTheListWithItsItemsUnderTheSameId() {
        val before = device("a")
        val shared = SyncSharing.share(before, LIST, SAM, removedAt = 3000, placedAt = 3001)
        assertEquals(listOf("household"), places(LIST, shared))
        val list = shared(LIST, shared)
        assertEquals(before.profileData[0].lists[0].items, list?.items)
        assertEquals("Movie night", list?.name)
        assertEquals(3001L, list?.placedAt)
        assertEquals(3001L, list?.updatedAt)
        assertEquals(3000L, shared.profileData[0].deleted["list:$LIST"])
        // Nothing to share: unchanged.
        assertEquals(before, SyncSharing.share(before, "other", SAM, removedAt = 3000, placedAt = 3001))
    }

    @Test
    fun aDeviceThatStillHasTheListInTheProfileDoesNotBringBackADuplicate() {
        val shared = SyncSharing.share(device("a"), LIST, SAM, removedAt = 3000, placedAt = 3001)
        val stale = device("b")
        for (merged in listOf(SyncMerge.merge(shared, stale), SyncMerge.merge(stale, shared))) {
            assertEquals(listOf("household"), places(LIST, merged))
            assertEquals(listOf("m1", "m2"), shared(LIST, merged)?.items?.map { it.item })
        }
    }

    @Test
    fun takingBackMovesTheListIntoTheProfileAndTheOthersLoseIt() {
        val shared = SyncSharing.share(device("a"), LIST, SAM, removedAt = 3000, placedAt = 3001)
        // The main profile takes it back: the household deletes it, the main profile has it, later.
        val back = SyncSharing.unshare(shared, LIST, MAIN, removedAt = 4000, placedAt = 4001)
        assertEquals(listOf("main"), places(LIST, back))
        assertEquals(4000L, back.household?.deleted?.get("list:$LIST"))
        assertEquals(4001L, back.lists.first().placedAt)
        assertEquals(listOf("m1", "m2"), back.lists.first().items.map { it.item })
        // A device that still has it shared (unchanged since) drops it; Sam's old copy stays gone.
        for (merged in listOf(SyncMerge.merge(back, shared), SyncMerge.merge(shared, back), SyncMerge.merge(back, device("c")))) {
            assertEquals(listOf("main"), places(LIST, merged))
        }
        // Nothing shared under that ID: unchanged.
        assertEquals(device("a"), SyncSharing.unshare(device("a"), LIST, MAIN, removedAt = 4000, placedAt = 4001))
    }

    @Test
    fun theItemsTakenOutStayTakenOutWhenTheListMoves() {
        val removal = "listitem:$LIST|$PLAYLIST|movie|m9"
        val withRemoval = device("a").let { it.copy(profileData = listOf(it.profileData[0].copy(deleted = mapOf(removal to 2500L)))) }
        val shared = SyncSharing.share(withRemoval, LIST, SAM, removedAt = 3000, placedAt = 3001)
        assertEquals(2500L, shared.household?.deleted?.get(removal))
        // A device that still has m9 in Sam's list, from before it was taken out, does not bring it back.
        val stale = device("b").editing(SAM) { it.copy(items = it.items + item("m9", 2400), updatedAt = 2400) }
        assertEquals(listOf("m1", "m2"), shared(LIST, SyncMerge.merge(shared, stale))?.items?.map { it.item })
    }

    // Concurrent edits

    @Test
    fun concurrentEditsByTwoProfilesOnTwoDevicesConverge() {
        val shared = SyncSharing.share(device("a"), LIST, SAM, removedAt = 3000, placedAt = 3001)
        val synced = SyncMerge.merge(device("b"), shared)
        // On device a, Sam adds m3 and takes m1 out; on device b, the main profile adds m4 and renames the list.
        val a = shared.editing("household") { list ->
            list.copy(items = list.items.filter { it.item != "m1" } + item("m3", 5000), updatedAt = 5000)
        }.let { it.copy(household = it.household!!.copy(deleted = it.household.deleted + ("listitem:$LIST|$PLAYLIST|movie|m1" to 5000L))) }
        val b = synced.editing("household") { list ->
            list.copy(items = list.items + item("m4", 5100), name = "Friday films", renamedAt = 5100, updatedAt = 5100)
        }
        val ab = SyncMerge.merge(a, b)
        val ba = SyncMerge.merge(b, a)
        assertEquals(ab.withoutDevice(), ba.withoutDevice())
        assertEquals(listOf("m2", "m3", "m4"), shared(LIST, ab)?.items?.map { it.item }?.sorted())
        assertEquals("Friday films", shared(LIST, ab)?.name)
        assertEquals(listOf("household"), places(LIST, ab))
    }

    @Test
    fun anEditInTheProfileAfterTheShareIsMergedIntoTheSharedList() {
        val shared = SyncSharing.share(device("a"), LIST, SAM, removedAt = 3000, placedAt = 3001)
        // Device b, not told yet, adds m3 to Sam's list at 3050.
        val b = device("b").editing(SAM) { it.copy(items = it.items + item("m3", 3050), updatedAt = 3050) }
        for (merged in listOf(SyncMerge.merge(shared, b), SyncMerge.merge(b, shared))) {
            assertEquals(listOf("household"), places(LIST, merged))
            assertEquals(listOf("m1", "m2", "m3"), shared(LIST, merged)?.items?.map { it.item })
            // Sam's copy is deleted at its time: a device that does not share lists drops it too.
            assertEquals(3050L, merged.profileData.first { it.profile == SAM }.deleted["list:$LIST"])
        }
    }

    @Test
    fun takingBackWhileAnotherDeviceEditsTheSharedListKeepsTheEdit() {
        val shared = SyncSharing.share(device("a"), LIST, SAM, removedAt = 3000, placedAt = 3001)
        val back = SyncSharing.unshare(shared, LIST, SAM, removedAt = 4000, placedAt = 4001)
        // Device b adds m5 to the shared list at 4100, before it hears that Sam took it back.
        val b = SyncMerge.merge(device("b"), shared).editing("household") { it.copy(items = it.items + item("m5", 4100), updatedAt = 4100) }
        for (merged in listOf(SyncMerge.merge(back, b), SyncMerge.merge(b, back))) {
            assertEquals(listOf(SAM), places(LIST, merged))
            assertEquals(listOf("m1", "m2", "m5"), merged.profileData.first { it.profile == SAM }.lists.first().items.map { it.item })
        }
    }

    @Test
    fun twoProfilesTakingBackTheSameListAtOnceEndInTheLaterOne() {
        val shared = SyncMerge.merge(device("b"), SyncSharing.share(device("a"), LIST, SAM, removedAt = 3000, placedAt = 3001))
        val bySam = SyncSharing.unshare(shared, LIST, SAM, removedAt = 4000, placedAt = 4001)
        val byMain = SyncSharing.unshare(shared, LIST, MAIN, removedAt = 4002, placedAt = 4003)
        for (merged in listOf(SyncMerge.merge(bySam, byMain), SyncMerge.merge(byMain, bySam))) {
            assertEquals(listOf("main"), places(LIST, merged))
        }
    }

    // Deletion for every profile

    @Test
    fun deletingASharedListDeletesItForEveryProfile() {
        val shared = SyncSharing.share(device("a"), LIST, SAM, removedAt = 3000, placedAt = 3001)
        val deleted = SyncSharing.deleteShared(shared, LIST, 6000)
        assertEquals(emptyList<String>(), places(LIST, deleted))
        // Neither a device that still has it shared, nor one that changed Sam's old copy before the deletion, brings it back.
        val stale = device("c").editing(SAM) { it.copy(items = it.items + item("m3", 5000), updatedAt = 5000) }
        for (other in listOf(shared, device("b"), stale)) {
            assertEquals(emptyList<String>(), places(LIST, SyncMerge.merge(deleted, other)))
            assertEquals(emptyList<String>(), places(LIST, SyncMerge.merge(other, deleted)))
        }
        // A change made after the deletion, on a device not told yet, wins over it (as rule 7): the list comes back.
        val later = SyncMerge.merge(device("d"), shared).editing("household") { it.copy(items = it.items + item("m6", 6100), updatedAt = 6100) }
        assertEquals(listOf("household"), places(LIST, SyncMerge.merge(deleted, later)))
    }

    // Kids

    @Test
    fun aKidsProfileCannotEditSharedLists() {
        assertFalse(SyncSharing.canEdit(kids = true))
        assertTrue(SyncSharing.canEdit(kids = false))
    }

    // Older devices

    @Test
    fun anOlderDeviceUsedAsARelayDestroysNothing() {
        // The main profile shares its list; an older device (with or without profiles) relays the result.
        val a = device("a").let { it.copy(lists = it.profileData[0].lists, profileData = emptyList()) }
        val shared = SyncSharing.share(a, LIST, MAIN, removedAt = 3000, placedAt = 3001)
        for (knowsProfiles in listOf(true, false)) {
            val old = asOlderDevice(SyncMerge.merge(a, shared), knowsProfiles)
            // The older device drops the main profile's copy (the deletion), and has nothing shared.
            assertEquals(emptyList<String>(), places(LIST, old))
            // What it passes on takes nothing from a device that shares lists, in either direction.
            for (merged in listOf(SyncMerge.merge(shared, old), SyncMerge.merge(old, shared))) {
                assertEquals(listOf("household"), places(LIST, merged))
                assertEquals(listOf("m1", "m2"), shared(LIST, merged)?.items?.map { it.item })
            }
        }
    }

    @Test
    fun anEditOnAnOlderDeviceIsMergedIntoTheSharedListAndItsCopyGoes() {
        val a = device("a").let { it.copy(lists = it.profileData[0].lists, profileData = emptyList()) }
        val shared = SyncSharing.share(a, LIST, MAIN, removedAt = 3000, placedAt = 3001)
        // An older device, not told yet, adds m3 to the main profile's list at 3500.
        val old = asOlderDevice(a, knowsProfiles = false).editing("main") { it.copy(items = it.items + item("m3", 3500), updatedAt = 3500) }
        val merged = SyncMerge.merge(shared, old)
        assertEquals(listOf("m1", "m2", "m3"), shared(LIST, merged)?.items?.map { it.item })
        assertEquals(listOf("household"), places(LIST, merged))
        // The main profile's deletion now covers that copy: the older device drops it at the next session.
        assertEquals(3500L, merged.deleted["list:$LIST"])
        val relayed = asOlderDevice(SyncMerge.merge(old, asOlderDevice(merged, knowsProfiles = false)), knowsProfiles = false)
        assertEquals(emptyList<String>(), places(LIST, relayed))
    }

    // Format and digest

    @Test
    fun aSnapshotWithoutSharedListsIsWrittenAsBefore() {
        val snapshot = fixture("profiles-merged.json")
        assertNull(snapshot.household)
        val encoded = snapshot.copy(digest = null).encoded().decodeToString()
        assertFalse("household" in encoded)
        assertFalse("placedAt" in encoded)
        // An empty household reads as none, and changes no digest.
        val empty = snapshot.copy(household = SyncHousehold())
        assertEquals(snapshot.contentDigest(), empty.contentDigest())
        assertNull(empty.normalized().household)
        assertNull(SyncMerge.merge(snapshot, empty).household)
        assertNull(SyncMerge.merge(snapshot, fixture("profiles-a.json")).household)
    }

    @Test
    fun theDigestOfSharedListsIsStableAndFollowsAChange() {
        val a = fixture("shared-lists-a.json")
        val b = fixture("shared-lists-b.json")
        val merged = SyncMerge.merge(a, b)
        val digest = merged.contentDigest()
        assertEquals(digest, SyncMerge.merge(b, a).contentDigest())
        assertEquals(digest, SyncMerge.merge(merged, merged).contentDigest())
        val renamed = merged.copy(household = merged.household!!.copy(lists = merged.household.lists.mapIndexed { index, list -> if (index == 0) list.copy(name = "Other") else list }))
        assertNotEquals(digest, renamed.contentDigest())
    }

    @Test
    fun sharedListsDatedFarAheadAreLeftOutAndTheirTimesCount() {
        val a = fixture("shared-lists-a.json")
        assertEquals(3450L, a.latestTime())
        val bounded = a.withoutRecordsAfter(3050)
        assertEquals(listOf(LIST), bounded.household?.lists?.map { it.id })
        assertNull(bounded.household?.deleted?.get("list:c3c3c3c3-0000-4000-8000-000000000003"))
        // A list placed far ahead is left out too.
        val ahead = a.editing("household") { if (it.id == LIST) it.copy(placedAt = 9000) else it }
        assertFalse(ahead.withoutRecordsAfter(5000).household?.lists.orEmpty().any { it.id == LIST })
        assertEquals(9000L, ahead.latestTime())
    }

    @Test
    fun deletionsAmongSharedListsAreForgottenLikeTheOthers() {
        val a = fixture("shared-lists-a.json")
        val day = 86_400_000L
        val now = 3300 + 181 * day
        assertEquals(emptyMap<String, Long>(), a.pruned(now).household?.deleted.orEmpty())
        // Learned late: kept from when it was learned.
        val learned = a.pruned(now, householdLearned = mapOf("list:c3c3c3c3-0000-4000-8000-000000000003" to now - day))
        assertEquals(mapOf("list:c3c3c3c3-0000-4000-8000-000000000003" to 3300L), learned.household?.deleted)
    }

    @Test
    fun sharedListIdsAreReadInLowercase() {
        val b = fixture("shared-lists-b.json")
        assertTrue(b.household!!.lists.all { it.id == it.id.lowercase() })
        assertTrue(b.profileData.all { bucket -> bucket.lists.all { it.id == it.id.lowercase() } })
    }

    // Three devices, any order

    /**
     * Random moves and edits of one list on three devices, then sessions in a random order until every device has met
     * every other twice: they end alike, the list in one space at most, and merging is the same in either order and
     * changes nothing the second time.
     */
    @Test
    fun randomMovesOnThreeDevicesConverge() {
        for (seed in 0 until 300) {
            val random = SplitMix(seed.toLong())
            val spaces = listOf("household", MAIN, SAM)
            val devices = (0 until 3).map { index ->
                var snapshot = SyncSnapshot(device = "d$index", profiles = listOf(SyncProfile(SAM, "Sam", updatedAt = 1000)))
                var time = 1000L + index
                fun next(): Long {
                    time += 1 + random.int(40)
                    return time
                }
                repeat(1 + random.int(3)) {
                    val place = random.pick(spaces)
                    val items = listOf("m1", "m2", "m3").filter { random.chance(50) }.map { item(it, next()) }
                    val name = random.pick(listOf("A", "B"))
                    val updatedAt = next()
                    val renamedAt = next()
                    val orderedAt = next()
                    val placedAt = if (random.chance(50)) next() else null
                    snapshot = put(SyncList(LIST, name, items, updatedAt = updatedAt, renamedAt = renamedAt, orderedAt = orderedAt, placedAt = placedAt), place, snapshot)
                    if (random.chance(40)) {
                        val at = next()
                        snapshot = delete("list:$LIST", at, random.pick(spaces), snapshot)
                    }
                    if (random.chance(30)) {
                        val at = next()
                        snapshot = delete("listitem:$LIST|$PLAYLIST|movie|m1", at, random.pick(spaces), snapshot)
                    }
                }
                snapshot
            }.toMutableList()
            for (first in devices) {
                for (second in devices) {
                    val ab = SyncMerge.merge(first, second)
                    val ba = SyncMerge.merge(second, first)
                    assertEquals("seed $seed", ab.withoutDevice(), ba.withoutDevice())
                    assertEquals("seed $seed", ab, SyncMerge.merge(ab, ab))
                    assertTrue("seed $seed", places(LIST, ab).size <= 1)
                }
            }
            repeat(12) {
                val client = random.int(3)
                val server = (client + 1 + random.int(2)) % 3
                devices[server] = SyncMerge.merge(devices[server], devices[client])
                devices[client] = SyncMerge.merge(devices[client], devices[server])
            }
            repeat(2) {
                for (client in 0 until 3) {
                    for (server in 0 until 3) {
                        if (server == client) continue
                        devices[server] = SyncMerge.merge(devices[server], devices[client])
                        devices[client] = SyncMerge.merge(devices[client], devices[server])
                    }
                }
            }
            assertEquals("seed $seed", devices[0].withoutDevice(), devices[1].withoutDevice())
            assertEquals("seed $seed", devices[1].withoutDevice(), devices[2].withoutDevice())
        }
    }

    /** SplitMix64, as the Swift `SyncFuzzTests.Random`: the same numbers for a seed on every platform. */
    private class SplitMix(private var state: Long) {
        fun next(): Long {
            state += -0x61c8864680b583ebL
            var z = state
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }

        fun int(bound: Int): Int = java.lang.Long.remainderUnsigned(next(), bound.toLong()).toInt()
        fun chance(percent: Int): Boolean = int(100) < percent
        fun <T> pick(values: List<T>): T = values[int(values.size)]
    }

    companion object {
        const val MAIN = SyncProfile.MAIN
        const val SAM = "11111111-1111-4111-8111-111111111111"
        const val KIDS = "22222222-2222-4222-8222-222222222222"
        const val LIST = "c1c1c1c1-0000-4000-8000-000000000001"
        const val PLAYLIST = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

        fun item(id: String, time: Long) = SyncListItem(PLAYLIST, "movie", id, id.uppercase(), addedAt = time)

        /** A device whose profile Sam has the list "Movie night" (two movies), and the Kids profile. */
        fun device(name: String) = SyncSnapshot(
            device = name,
            playlists = listOf(SyncPlaylist(PLAYLIST, "Home", SyncPlaylist.M3U, "http://example.com/list.m3u", addedAt = 100, updatedAt = 100)),
            profiles = listOf(SyncProfile(SAM, "Sam", updatedAt = 1000), SyncProfile(KIDS, "Kids", kids = true, updatedAt = 1000)),
            profileData = listOf(
                SyncProfileData(
                    SAM,
                    lists = listOf(SyncList(LIST, "Movie night", listOf(item("m1", 2100), item("m2", 2200)), updatedAt = 2200, renamedAt = 2000, orderedAt = 2000)),
                ),
            ),
        )

        /** Where list [id] is in [snapshot]: "household", "main", or a profile's ID, once for each copy. */
        fun places(id: String, snapshot: SyncSnapshot): List<String> =
            snapshot.household?.lists.orEmpty().filter { it.id == id }.map { "household" } +
                snapshot.lists.filter { it.id == id }.map { "main" } +
                snapshot.profileData.flatMap { bucket -> bucket.lists.filter { it.id == id }.map { bucket.profile } }

        fun shared(id: String, snapshot: SyncSnapshot): SyncList? = snapshot.household?.lists?.firstOrNull { it.id == id }

        /** [this] with each list of [place] ("household", "main" or a profile's ID) changed by [edit]. */
        fun SyncSnapshot.editing(place: String, edit: (SyncList) -> SyncList): SyncSnapshot = when (place) {
            "household" -> copy(household = household!!.copy(lists = household.lists.map(edit)))
            "main" -> copy(lists = lists.map(edit))
            else -> copy(profileData = profileData.map { if (it.profile == place) it.copy(lists = it.lists.map(edit)) else it })
        }

        fun put(list: SyncList, place: String, snapshot: SyncSnapshot): SyncSnapshot = when (place) {
            "household" -> snapshot.copy(household = (snapshot.household ?: SyncHousehold()).let { it.copy(lists = it.lists + list) })
            MAIN -> snapshot.copy(lists = snapshot.lists + list)
            else -> if (snapshot.profileData.any { it.profile == place }) {
                snapshot.copy(profileData = snapshot.profileData.map { if (it.profile == place) it.copy(lists = it.lists + list) else it })
            } else {
                snapshot.copy(profileData = snapshot.profileData + SyncProfileData(place, lists = listOf(list)))
            }
        }

        fun delete(key: String, time: Long, place: String, snapshot: SyncSnapshot): SyncSnapshot {
            fun Map<String, Long>.with() = this + (key to maxOf(this[key] ?: time, time))
            return when (place) {
                "household" -> snapshot.copy(household = (snapshot.household ?: SyncHousehold()).let { it.copy(deleted = it.deleted.with()) })
                MAIN -> snapshot.copy(deleted = snapshot.deleted.with())
                else -> if (snapshot.profileData.any { it.profile == place }) {
                    snapshot.copy(profileData = snapshot.profileData.map { if (it.profile == place) it.copy(deleted = it.deleted.with()) else it })
                } else {
                    snapshot.copy(profileData = snapshot.profileData + SyncProfileData(place, deleted = mapOf(key to time)))
                }
            }
        }

        /**
         * What a device that does not share lists keeps and passes on: everything but the household and `placedAt`; one
         * that does not know profiles either keeps only the main profile's data.
         */
        fun asOlderDevice(snapshot: SyncSnapshot, knowsProfiles: Boolean): SyncSnapshot {
            val json = Json.parseToJsonElement(snapshot.encoded().decodeToString()).jsonObject
            fun strip(lists: Any?) = (lists as? JsonArray)?.let { array -> JsonArray(array.map { JsonObject(it.jsonObject - "placedAt") }) }
            val kept = json.filterKeys { it != "household" && (knowsProfiles || (it != "profiles" && it != "profileData")) }.toMutableMap()
            strip(kept["lists"])?.let { kept["lists"] = it }
            (kept["profileData"] as? JsonArray)?.let { buckets ->
                kept["profileData"] = JsonArray(buckets.map { bucket -> JsonObject(bucket.jsonObject.toMutableMap().also { b -> strip(b["lists"])?.let { b["lists"] = it } }) })
            }
            return requireNotNull(SyncSnapshot.decode(JsonObject(kept).toString().encodeToByteArray()))
        }
    }
}
