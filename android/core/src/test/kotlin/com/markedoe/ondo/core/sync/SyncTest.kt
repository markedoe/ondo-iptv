package com.markedoe.ondo.core.sync

import com.markedoe.ondo.core.HangGuard
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertFailsWith
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Local-network sync (docs/SYNC.md): the shared examples, the merge rules, local addresses and frames. */
class SyncTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    /** `fixtures/sync` at the top of the repository, shared with the Swift tests. */
    private fun fixture(name: String): SyncSnapshot {
        val directory = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "fixtures/sync") }.first { it.isDirectory }
        return requireNotNull(SyncSnapshot.decode(File(directory, name).readBytes())) { name }
    }

    private fun SyncSnapshot.withoutDevice() = copy(device = "", deviceName = null)

    @Test
    fun mergingTheSharedExamplesGivesTheSharedResultInEitherOrder() {
        val a = fixture("a.json")
        val b = fixture("b.json")
        val expected = fixture("merged.json")
        assertEquals(expected, SyncMerge.merge(a, b))
        assertEquals(expected.withoutDevice(), SyncMerge.merge(b, a).withoutDevice())
    }

    @Test
    fun mergingAgainChangesNothing() {
        val merged = SyncMerge.merge(fixture("a.json"), fixture("b.json"))
        assertEquals(merged, SyncMerge.merge(merged, merged))
        assertEquals(merged, SyncMerge.merge(merged, fixture("b.json")))
        assertEquals(merged.withoutDevice(), SyncMerge.merge(fixture("a.json"), merged).withoutDevice())
    }

    @Test
    fun theLaterChangeWinsAndADeletionWinsOverWhatWasNotChangedSince() {
        val playlist = SyncPlaylist("p", "Home", SyncPlaylist.M3U, "http://example.com/a.m3u", addedAt = 1, updatedAt = 1)
        val favorite = SyncFavorite("p", "movie", "m1", "Glass City", addedAt = 10)
        val a = SyncSnapshot(device = "a", playlists = listOf(playlist), favorites = listOf(favorite))
        val renamed = SyncSnapshot(device = "b", playlists = listOf(playlist.copy(name = "Living room", updatedAt = 5)))
        assertEquals("Living room", SyncMerge.merge(a, renamed).playlists.single().name)
        // Removed after it was added: gone. Added again after the removal: back.
        val removed = SyncSnapshot(device = "b", deleted = mapOf(favorite.key to 20))
        assertTrue(SyncMerge.merge(a, removed).favorites.isEmpty())
        val addedAgain = a.copy(favorites = listOf(favorite.copy(addedAt = 30)))
        assertEquals(1, SyncMerge.merge(addedAgain, removed).favorites.size)
        // A removal at the same time as the change wins.
        assertTrue(SyncMerge.merge(a, SyncSnapshot(device = "b", deleted = mapOf(favorite.key to 10))).favorites.isEmpty())
    }

    // People kept in My List and in lists

    @Test
    fun peopleMergeLikeTheOtherRecordsOfMyListInEitherOrder() {
        val a = fixture("people-a.json")
        val b = fixture("people-b.json")
        val expected = fixture("people-merged.json")
        assertEquals(expected, SyncMerge.merge(a, b))
        assertEquals(expected.withoutDevice(), SyncMerge.merge(b, a).withoutDevice())
        assertEquals(expected, SyncMerge.merge(expected, expected))
        // The same name in another case is one person; the one with a photo wins at the same time.
        assertEquals("https://example.com/tom.jpg", expected.people.first().image)
    }

    @Test
    fun aPersonRemovedOnOneDeviceIsRemovedOnTheOther() {
        val added = SyncSnapshot(device = "a", people = listOf(SyncPerson("Ada Example", addedAt = 10)))
        val removed = SyncSnapshot(device = "b", deleted = mapOf("person:ADA EXAMPLE" to 20L))
        assertTrue(SyncMerge.merge(added, removed).people.isEmpty())
        assertEquals(mapOf("person:ada example" to 20L), SyncMerge.merge(added, removed).deleted)
        // Added again after the removal: back.
        val again = SyncSnapshot(device = "a", people = listOf(SyncPerson("Ada Example", addedAt = 30)))
        assertEquals(listOf("Ada Example"), SyncMerge.merge(again, removed).people.map { it.name })
    }

    @Test
    fun aDeviceThatDoesNotKnowPeopleRemovesNone() {
        // `a.json` is written by a build without people: they and the people of a list stay.
        val withPeople = fixture("people-a.json")
        val old = withPeople.copy(people = emptyList(), lists = withPeople.lists.map { it.copy(people = emptyList()) })
        for (merged in listOf(SyncMerge.merge(withPeople, old), SyncMerge.merge(old, withPeople), SyncMerge.merge(withPeople, fixture("a.json")))) {
            assertEquals(listOf("Tom Example", "Ada Example"), merged.people.map { it.name })
            assertEquals(listOf("Ada Example"), merged.lists.first { it.id == "d1000000-0000-4000-8000-00000000000d" }.people.map { it.name })
        }
    }

    @Test
    fun peopleFieldsAreReadAndWrittenUnderTheSharedNames() {
        val snapshot = SyncSnapshot(
            device = "a",
            lists = listOf(SyncList("l", "L", people = listOf(SyncPerson("Ada", "https://example.com/a.jpg", 1)), updatedAt = 1)),
            people = listOf(SyncPerson("Tom", addedAt = 2)),
        )
        val json = snapshot.encoded().decodeToString()
        assertTrue(json, "\"people\":[{\"name\":\"Tom\",\"addedAt\":2}]" in json)
        assertTrue(json, "\"people\":[{\"name\":\"Ada\",\"image\":\"https://example.com/a.jpg\",\"addedAt\":1}]" in json)
        assertEquals(snapshot, SyncSnapshot.decode(snapshot.encoded()))
    }

    @Test
    fun onlyAsciiLettersFoldInAPersonKey() {
        assertEquals("person:Élodie example", SyncPerson("ÉLODIE Example", addedAt = 0).key)
        assertEquals("person:ΣΟΦΙΑ ex", lowercasingId("person:ΣΟΦΙΑ Ex"))
    }

    // Probes

    @Test
    fun aProbeGoesWhenNothingChangedSinceTheLastSnapshotSent() {
        val snapshot = fixture("people-a.json")
        val full = snapshot.offer(sentDigest = null, answerDigest = "server-1")
        assertFalse(full.isProbe)
        val digest = requireNotNull(full.digest)
        // Who sends it, when, and the order of its deletions do not change the digest; a record does.
        assertEquals(digest, snapshot.copy(device = "other", deviceName = null, clock = 42, deleted = snapshot.deleted.entries.reversed().associate { it.toPair() }).contentDigest())
        assertTrue(digest != snapshot.copy(people = snapshot.people.dropLast(1)).contentDigest())

        val probe = snapshot.offer(sentDigest = digest, answerDigest = "server-1")
        assertTrue(probe.isProbe)
        assertEquals("server-1", probe.seen)
        assertTrue(probe.people.isEmpty() && probe.lists.isEmpty() && probe.deleted.isEmpty())
        // Without the server's digest (an older version answered), the whole snapshot goes.
        assertFalse(snapshot.offer(sentDigest = digest, answerDigest = null).isProbe)
        // Decoded by an older version, a probe is an empty snapshot: merging it removes nothing.
        assertEquals(SyncMerge.merge(snapshot, snapshot).withoutDevice(), SyncMerge.merge(snapshot, requireNotNull(SyncSnapshot.decode(probe.encoded()))).withoutDevice())
    }

    // Lists, item by item

    @Test
    fun listsMergeItemByItemInEitherOrder() {
        val a = fixture("lists-a.json")
        val b = fixture("lists-b.json")
        val expected = fixture("lists-merged.json")
        assertEquals(expected, SyncMerge.merge(a, b))
        assertEquals(expected.withoutDevice(), SyncMerge.merge(b, a).withoutDevice())
        assertEquals(expected, SyncMerge.merge(expected, expected))
        assertEquals(expected, SyncMerge.merge(expected, b))
    }

    @Test
    fun twoDevicesAddingAndRemovingInTheSameListKeepEveryChange() {
        val m1 = SyncListItem("p", "movie", "m1", "Glass City", addedAt = 10)
        val m2 = SyncListItem("p", "movie", "m2", "Quiet Water", addedAt = 10)
        val start = SyncList("l", "Weekend", items = listOf(m1, m2), updatedAt = 10)
        // At the same moment, one device adds m3 and removes m1, the other adds m4 and a person, and removes m2.
        val one = start.copy(items = listOf(m2, SyncListItem("p", "movie", "m3", "Harbor Lights", addedAt = 20)), updatedAt = 20)
        val other = start.copy(items = listOf(m1, SyncListItem("p", "movie", "m4", "Late Show", addedAt = 20)), people = listOf(SyncPerson("Ada Example", addedAt = 20)), updatedAt = 20)
        val a = SyncSnapshot(device = "a", lists = listOf(one), deleted = mapOf(start.itemKey("p|movie|m1") to 20L))
        val b = SyncSnapshot(device = "b", lists = listOf(other), deleted = mapOf(start.itemKey("p|movie|m2") to 20L))
        val merged = SyncMerge.merge(a, b)
        // In the order of the list put in order last (a tie: the smaller order).
        assertEquals(listOf("m4", "m3"), merged.lists.flatMap { list -> list.items.map { it.item } })
        assertEquals(listOf("Ada Example"), merged.lists.flatMap { list -> list.people.map { it.name } })
        assertEquals(merged.withoutDevice(), SyncMerge.merge(b, a).withoutDevice())
        // An item put back after its removal comes back.
        val back = merged.lists[0].let { it.copy(items = it.items + m1.copy(addedAt = 30), updatedAt = 30) }
        assertEquals(listOf("m1", "m4", "m3"), SyncMerge.merge(merged, SyncSnapshot(device = "b", lists = listOf(back))).lists[0].items.map { it.item })
    }

    @Test
    fun listItemDeletionsAreReadWithTheirIdsInLowercase() {
        assertEquals("listitem:l1|p1|movie|M1", lowercasingId("listitem:L1|P1|movie|M1"))
        assertEquals("listitem:l1|person:ada Élodie", lowercasingId("listitem:L1|person:ADA Élodie"))
    }

    @Test
    fun aDeletionStaysUntilEachDeviceSeenThisYearHasHadIt() {
        val day = 86_400_000L
        val now = 1_000 * day
        val snapshot = SyncSnapshot(device = "a", deleted = mapOf("list:old" to now - 300 * day, "list:older" to now - 250 * day, "list:recent" to now - 10 * day))
        // A device last synced 260 days ago has not had the deletion of 250 days ago: it stays, the older one goes.
        assertEquals(setOf("list:older", "list:recent"), snapshot.pruned(now, sessions = listOf(now - 260 * day, now - day)).deleted.keys)
        // Once it has synced since, both old ones go; a device not seen for over a year holds nothing back.
        assertEquals(setOf("list:recent"), snapshot.pruned(now, sessions = listOf(now - 100 * day)).deleted.keys)
        assertEquals(setOf("list:recent"), snapshot.pruned(now, sessions = listOf(now - 400 * day)).deleted.keys)
    }

    @Test
    fun aDeletionLearnedLateIsKeptFromWhenItWasLearned() {
        val day = 86_400_000L
        val now = 1_000 * day
        val snapshot = SyncSnapshot(device = "a", deleted = mapOf("favorite:p|movie|m1" to now - 250 * day))
        assertTrue("by its date alone, it goes", snapshot.pruned(now, sessions = listOf(now - day)).deleted.isEmpty())
        assertEquals(1, snapshot.pruned(now, sessions = listOf(now - day), learned = mapOf("favorite:p|movie|m1" to now - 2 * day)).deleted.size)
        assertTrue(snapshot.pruned(now, sessions = listOf(now - day), learned = mapOf("favorite:p|movie|m1" to now - 200 * day)).deleted.isEmpty())
    }

    @Test
    fun tiesBetweenRecordsDifferingInAnyFieldEndTheSameWayInEitherOrder() {
        val image1 = SyncSnapshot(device = "a", favorites = listOf(SyncFavorite("p", "movie", "m1", "T", image = "https://a/1.jpg", addedAt = 5)))
        val image2 = SyncSnapshot(device = "b", favorites = listOf(SyncFavorite("p", "movie", "m1", "T", image = "https://a/2.jpg", addedAt = 5)))
        val none = SyncSnapshot(device = "c", favorites = listOf(SyncFavorite("p", "movie", "m1", "T", addedAt = 5)))
        for ((first, second) in listOf(image1 to image2, image1 to none, image2 to none)) {
            assertEquals(SyncMerge.merge(first, second).favorites, SyncMerge.merge(second, first).favorites)
        }
        val long = SyncSnapshot(device = "a", progress = listOf(SyncProgress("p", "movie", "m1", title = "T", position = 10.0, duration = 7_000.0, watchedAt = 5)))
        val short = SyncSnapshot(device = "b", progress = listOf(SyncProgress("p", "movie", "m1", title = "T", subtitle = "S", position = 10.0, duration = 6_900.0, watchedAt = 5)))
        assertEquals(listOf(7_000.0), SyncMerge.merge(long, short).progress.map { it.duration })
        assertEquals(listOf(7_000.0), SyncMerge.merge(short, long).progress.map { it.duration })
        val m3u = SyncPlaylist("p", "Home", SyncPlaylist.M3U, "http://example.com/a.m3u", addedAt = 1, updatedAt = 5)
        val older = m3u.copy(addedAt = 0)
        assertEquals(
            SyncMerge.merge(SyncSnapshot(device = "a", playlists = listOf(m3u)), SyncSnapshot(device = "b", playlists = listOf(older))).playlists,
            SyncMerge.merge(SyncSnapshot(device = "b", playlists = listOf(older)), SyncSnapshot(device = "a", playlists = listOf(m3u))).playlists,
        )
    }

    @Test
    fun listOrderEndsTheSameWhateverTheOrderOfTheMergesOfThreeDevices() {
        fun list(keys: List<String>, added: Long) = SyncSnapshot(
            device = "d",
            lists = listOf(SyncList("l", "L", items = keys.map { SyncListItem("p", "movie", it, it, addedAt = added) }, updatedAt = 10, orderedAt = 10)),
        )
        val a = list(listOf("x", "y"), 1)
        val b = list(listOf("y", "x", "z"), 2)
        val c = list(listOf("w", "x"), 3)
        val left = SyncMerge.merge(SyncMerge.merge(a, b), c)
        assertEquals(left.withoutDevice().lists, SyncMerge.merge(a, SyncMerge.merge(b, c)).withoutDevice().lists)
        assertEquals(left.withoutDevice().lists, SyncMerge.merge(SyncMerge.merge(c, a), b).withoutDevice().lists)
        assertEquals(listOf("w", "x", "y", "z"), left.lists.single().items.map { it.item }.sorted())
    }

    @Test
    fun keysOfOutsideServicesFollowTheLaterSaveAndTheirRemoval() {
        val merged = SyncMerge.merge(fixture("a.json"), fixture("b.json"))
        // The later save wins, a removal after a save drops it, the key of a service this device does not know stays.
        assertEquals(
            listOf(SyncKey("future-service", "example-other-key", 300), SyncKey("tmdb", "example-tmdb-token-b", 2500)),
            merged.keys,
        )
        assertEquals(800L, merged.deleted["key:opensubtitles"])
        // A device that does not sync keys sends none: that removes nothing.
        val mine = SyncSnapshot(device = "a", keys = listOf(SyncKey("tmdb", "example-tmdb-token-a", 1500)))
        assertEquals(mine.keys, SyncMerge.merge(mine, SyncSnapshot(device = "old")).keys)
        assertEquals(mine.keys, SyncMerge.merge(SyncSnapshot(device = "old"), mine).keys)
        // On equal times, the smaller value wins, whatever the order.
        val other = SyncSnapshot(device = "b", keys = listOf(SyncKey("tmdb", "example-tmdb-token-0", 1500)))
        assertEquals("example-tmdb-token-0", SyncMerge.merge(mine, other).keys.single().value)
        assertEquals("example-tmdb-token-0", SyncMerge.merge(other, mine).keys.single().value)
    }

    @Test
    fun theRowOrderOfTheSharedExamplesMergesInEitherOrder() {
        val a = fixture("rows-a.json")
        val b = fixture("rows-b.json")
        val expected = fixture("rows-merged.json")
        assertEquals(expected, SyncMerge.merge(a, b))
        assertEquals(expected.withoutDevice(), SyncMerge.merge(b, a).withoutDevice())
        assertEquals(expected, SyncMerge.merge(expected, b))
    }

    /** Profiles of the shared examples (docs/SYNC.md, "Profiles"): the same result whichever merges, and merging it again. */
    @Test
    fun theProfilesOfTheSharedExamplesMergeInEitherOrder() {
        val a = fixture("profiles-a.json")
        val b = fixture("profiles-b.json")
        val expected = fixture("profiles-merged.json")
        assertEquals(expected, SyncMerge.merge(a, b))
        assertEquals(expected.withoutDevice(), SyncMerge.merge(b, a).withoutDevice())
        assertEquals(expected, SyncMerge.merge(expected, b))
        // The device that still has the deleted profile (and its data) does not bring it back.
        assertEquals(expected, SyncMerge.merge(expected, a))
    }

    /** An older device knows nothing of profiles: its data is the main profile's, and the profiles stay as they are. */
    @Test
    fun anOlderDeviceWithoutProfilesMapsToTheMainProfile() {
        val merged = fixture("profiles-merged.json")
        val playlist = merged.playlists.single()
        val older = SyncSnapshot(
            device = "77777777-7777-4777-8777-777777777777",
            playlists = listOf(playlist),
            favorites = listOf(SyncFavorite(playlist.id, "movie", "m9", "Older device", addedAt = 3_500)),
        )
        val result = SyncMerge.merge(merged, older)
        assertEquals(merged.profiles, result.profiles)
        assertEquals(merged.profileData, result.profileData)
        assertTrue(result.favorites.any { it.item == "m9" })
        assertTrue(result.profileData.none { bucket -> bucket.favorites.any { it.item == "m9" } })
        // And what the older device keeps (without profiles) is the main profile's alone.
        assertEquals(result.favorites, SyncMerge.merge(older, merged).favorites)
    }

    /** A watch on one profile stays in that profile, on whichever device merges. */
    @Test
    fun aWatchOnOneProfileIsNeverAppliedToAnother() {
        val playlist = SyncPlaylist("p", "Home", SyncPlaylist.M3U, "http://example.com/a.m3u", addedAt = 1, updatedAt = 1)
        val sam = SyncProfile("11111111-1111-4111-8111-111111111111", "Sam", updatedAt = 10)
        val ana = SyncProfile("33333333-3333-4333-8333-333333333333", "Ana", updatedAt = 10)
        val watch = SyncProgress("p", "movie", "m1", title = "M1", position = 600.0, duration = 6000.0, watchedAt = 20)
        val device1 = SyncSnapshot(device = "d1", playlists = listOf(playlist), profiles = listOf(sam, ana), profileData = listOf(SyncProfileData(sam.id, progress = listOf(watch))))
        val device2 = SyncSnapshot(device = "d2", playlists = listOf(playlist), profiles = listOf(sam, ana))
        val merged = SyncMerge.merge(device2, device1)
        assertEquals(listOf(watch), merged.profileData.single { it.profile == sam.id }.progress)
        assertTrue(merged.profileData.none { it.profile == ana.id })
        assertTrue(merged.progress.isEmpty())
    }

    /** A profile's PIN never travels: the record has no field for it, and a snapshot that sends one keeps none. */
    @Test
    fun aProfileRecordCarriesNoPin() {
        val json = """{"type":"ondo-sync","version":1,"device":"d","profiles":[{"id":"x","name":"Sam","pinHash":"ab","updatedAt":5}]}"""
        val snapshot = requireNotNull(SyncSnapshot.decode(json.encodeToByteArray()))
        assertFalse(snapshot.encoded().decodeToString().contains("pin", ignoreCase = true))
    }

    /** Two devices put the rows in order at about the same time: both keep the later one, whichever merges. */
    @Test
    fun concurrentRowOrdersConvergeToTheLaterStamp() {
        val clockA = SyncClock(last = 1_000)
        val clockB = SyncClock(last = 1_000)
        val earlier = SyncRows(SyncRows.MOVIES, listOf("all", "cat:movie-1|action"), updatedAt = clockA.stamp(5_000))
        val later = SyncRows(SyncRows.MOVIES, listOf("cat:movie-1|action", "all"), listOf("trending"), updatedAt = clockB.stamp(5_001))
        val a = SyncSnapshot(device = "a", rows = listOf(earlier))
        val b = SyncSnapshot(device = "b", rows = listOf(later))
        assertEquals(listOf(later), SyncMerge.merge(a, b).rows)
        assertEquals(listOf(later), SyncMerge.merge(b, a).rows)
        // A third device that saw neither ends up with the same.
        val c = SyncSnapshot(device = "c")
        assertEquals(SyncMerge.merge(SyncMerge.merge(c, a), b).rows, SyncMerge.merge(SyncMerge.merge(c, b), a).rows)
        // On equal stamps, the smaller tie string, whatever the order.
        val same = later.copy(order = listOf("all"), hidden = emptyList(), updatedAt = earlier.updatedAt)
        val tied = SyncMerge.merge(a, SyncSnapshot(device = "b", rows = listOf(same))).rows
        assertEquals(tied, SyncMerge.merge(SyncSnapshot(device = "b", rows = listOf(same)), a).rows)
        assertEquals(listOf(same), tied)
    }

    /** An older version sends no rows: the merge keeps the device's own, and its snapshot reads as before. */
    @Test
    fun aSnapshotWithoutRowsChangesNone() {
        val mine = SyncSnapshot(device = "a", rows = listOf(SyncRows(SyncRows.SERIES, listOf("mylist", "all"), listOf("new"), 700)))
        val old = SyncSnapshot.decode("""{"type":"ondo-sync","version":1,"device":"old","keys":[]}""".encodeToByteArray())!!
        assertEquals(emptyList<SyncRows>(), old.rows)
        assertEquals(mine.rows, SyncMerge.merge(mine, old).rows)
        assertEquals(mine.rows, SyncMerge.merge(old, mine).rows)
        // And an older version reading this one ignores the field it does not know (`ignoreUnknownKeys`), as the
        // shared examples check with an unknown field.
        assertEquals(mine, SyncSnapshot.decode(mine.encoded()))
    }

    @Test
    fun theDigestOfTheRowsIsStableAndFollowsAChange() {
        val rows = SyncRows(SyncRows.MOVIES, listOf("all", "cat:movie-1|action"), listOf("awards"), 900)
        val snapshot = SyncSnapshot(device = "a", rows = listOf(rows))
        val digest = snapshot.contentDigest()
        assertEquals(digest, snapshot.copy(device = "b", clock = 5).contentDigest())
        assertEquals(digest, SyncMerge.merge(snapshot, snapshot).contentDigest())
        assertEquals(digest, SyncMerge.merge(snapshot, SyncSnapshot(device = "old")).contentDigest())
        assertTrue(digest != snapshot.copy(rows = listOf(rows.copy(order = rows.order.reversed()))).contentDigest())
        assertTrue(digest != snapshot.copy(rows = listOf(rows.copy(hidden = emptyList()))).contentDigest())
    }

    /**
     * A record too large is trimmed as it is read, the same way on every device: 8 tabs (`movie` and `series` first,
     * then by UTF-16 code units), 2000 keys in `order` (the first) and in `hidden` (the smallest), keys of 256 UTF-16
     * units at most; its digest is that of the trimmed record.
     */
    @Test
    fun anOversizedRowsRecordIsTrimmedTheSameWayEachTime() {
        val keys = (0 until 2100).map { "k" + it.toString().padStart(4, '0') }
        val long = "x".repeat(SyncRows.MAX_KEY_LENGTH + 1)
        val tabs = (9 downTo 0).map { "t$it" } + SyncRows.MOVIES + long
        val big = SyncSnapshot(
            device = "a",
            rows = tabs.map { SyncRows(it, listOf(long) + keys + keys.first(), keys.reversed(), updatedAt = 5) },
        )
        val read = requireNotNull(SyncSnapshot.decode(big.encoded()))
        assertEquals(listOf("t6", "t5", "t4", "t3", "t2", "t1", "t0", "movie"), read.rows.map { it.tab })
        read.rows.forEach { rows ->
            assertEquals(keys.take(SyncRows.MAX_KEYS), rows.order)
            assertEquals(keys.take(SyncRows.MAX_KEYS), rows.hidden)
        }
        assertEquals(read, SyncSnapshot.decode(read.encoded()))
        assertEquals(read.contentDigest(), requireNotNull(SyncSnapshot.decode(big.encoded())).contentDigest())
        val once = SyncMerge.merge(read, read)
        assertEquals(once.contentDigest(), SyncMerge.merge(once, big).contentDigest())
        // Two sides of eight tabs each merge into eight.
        val other = SyncSnapshot(device = "b", rows = (0 until 8).map { SyncRows("u$it", listOf("all"), updatedAt = 1) })
        val merged = SyncMerge.merge(read, other)
        assertEquals(listOf("movie", "t0", "t1", "t2", "t3", "t4", "t5", "t6"), merged.rows.map { it.tab })
        assertEquals(merged.rows, SyncMerge.merge(other, read).rows)
    }

    /** A record dated far ahead of the receiver's clock is left out, as any other (docs/SYNC.md, "Clocks"). */
    @Test
    fun rowsDatedFarAheadAreLeftOut() {
        val ahead = SyncSnapshot(device = "b", rows = listOf(SyncRows(SyncRows.MOVIES, listOf("all"), updatedAt = 10_000)))
        assertEquals(emptyList<SyncRows>(), ahead.withoutRecordsAfter(9_999).rows)
        assertEquals(ahead.rows, ahead.withoutRecordsAfter(10_000).rows)
        assertEquals(10_000L, ahead.latestTime())
    }

    @Test
    fun aSnapshotOfAnotherKindOrVersionIsRefused() {
        assertNull(SyncSnapshot.decode("""{"type":"other","version":1,"device":"a"}""".encodeToByteArray()))
        assertNull(SyncSnapshot.decode("""{"type":"ondo-sync","version":2,"device":"a"}""".encodeToByteArray()))
        assertNull(SyncSnapshot.decode("not json".encodeToByteArray()))
        val snapshot = SyncMerge.merge(fixture("a.json"), fixture("b.json"))
        assertEquals(snapshot, SyncSnapshot.decode(snapshot.encoded()))
    }

    /**
     * A device that writes its IDs in uppercase writes them so in its deletions too: they still remove the records,
     * read in lowercase. The item of a favourite or a progress is the provider's ID and keeps its case.
     */
    @Test
    fun deletionsAreReadWithTheirIdsInLowercase() {
        val playlist = SyncPlaylist(
            id = "a0000000-0000-4000-8000-00000000000a", name = "Home", kind = SyncPlaylist.M3U,
            url = "http://example.com/a.m3u", addedAt = 1, updatedAt = 1,
        )
        val other = SyncPlaylist(
            id = "c0000000-0000-4000-8000-00000000000c", name = "Office", kind = SyncPlaylist.M3U,
            url = "http://example.com/c.m3u", addedAt = 1, updatedAt = 1,
        )
        val kept = SyncFavorite(playlist = other.id, kind = "movie", item = "M1", title = "Glass City", addedAt = 10)
        val removed = SyncFavorite(playlist = other.id, kind = "movie", item = "M2", title = "Quiet Water", addedAt = 10)
        val list = SyncList(id = "d0000000-0000-4000-8000-00000000000d", name = "Kids", updatedAt = 10)
        val a = SyncSnapshot(device = "a", playlists = listOf(playlist, other), favorites = listOf(kept, removed), lists = listOf(list))
        val json = """
            {"type":"ondo-sync","version":1,"device":"b","deleted":{
              "playlist:A0000000-0000-4000-8000-00000000000A":20,
              "favorite:C0000000-0000-4000-8000-00000000000C|movie|M2":20,
              "favorite:C0000000-0000-4000-8000-00000000000C|movie|m1":20,
              "list:D0000000-0000-4000-8000-00000000000D":20,
              "list:d0000000-0000-4000-8000-00000000000d":30,
              "key:TMDB":20}}
        """.trimIndent()
        val b = requireNotNull(SyncSnapshot.decode(json.encodeToByteArray()))
        assertEquals(
            mapOf(
                "playlist:a0000000-0000-4000-8000-00000000000a" to 20L,
                "favorite:c0000000-0000-4000-8000-00000000000c|movie|M2" to 20L,
                "favorite:c0000000-0000-4000-8000-00000000000c|movie|m1" to 20L,
                "list:d0000000-0000-4000-8000-00000000000d" to 30L,
                "key:TMDB" to 20L,
            ),
            b.deleted,
        )
        for (merged in listOf(SyncMerge.merge(a, b), SyncMerge.merge(b, a))) {
            assertEquals(listOf(other.id), merged.playlists.map { it.id })
            assertEquals("an item ID keeps its case", listOf("M1"), merged.favorites.map { it.item })
            assertTrue(merged.lists.isEmpty())
        }
    }

    @Test
    fun oldDeletionsAreForgotten() {
        val day = 86_400_000L
        val snapshot = SyncSnapshot(device = "a", deleted = mapOf("list:old" to 0, "list:recent" to 170 * day))
        assertEquals(setOf("list:recent"), snapshot.pruned(now = 200 * day).deleted.keys)
    }

    private fun local(address: String) = SyncNetwork.isLocal(InetAddress.getByName(address))

    @Test
    fun onlyLocalNetworkAddressesAreLocal() {
        // Private, link-local and loopback ranges, IPv4 and IPv6 (and IPv4 mapped in IPv6).
        listOf(
            "10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.254", "192.168.1.20", "169.254.3.4", "127.0.0.1", // leak-guard: allow
            "fd12:3456:789a::1", "fc00::1", "fe80::1", "::1", "::ffff:192.168.1.20", // leak-guard: allow
        ).forEach { assertTrue(it, local(it)) }
        // Everything else, among them the neighbours of each range.
        listOf(
            "8.8.8.8", "9.255.255.255", "11.0.0.1", "172.15.255.255", "172.32.0.1", "192.167.1.1", "192.169.0.1", // leak-guard: allow
            "169.253.0.1", "100.64.0.1", "1.1.1.1", "2001:db8::1", "2a00:1450::1", "fec0::1", "::ffff:8.8.8.8", // leak-guard: allow
        ).forEach { assertFalse(it, local(it)) }
    }

    @Test
    fun aFrameIsItsLengthThenItsBytes() {
        val output = ByteArrayOutputStream()
        SyncNetwork.writeFrame(output, "hello".encodeToByteArray())
        assertEquals(listOf<Byte>(0, 0, 0, 5), output.toByteArray().take(4))
        assertEquals("hello", SyncNetwork.readFrame(ByteArrayInputStream(output.toByteArray())).decodeToString())
        // Too large, or cut short: refused.
        assertFailsWith<IOException> { SyncNetwork.readFrame(ByteArrayInputStream(byteArrayOf(0x7F, 0, 0, 0))) }
        assertFailsWith<IOException> { SyncNetwork.readFrame(ByteArrayInputStream(byteArrayOf(0, 0, 0, 9, 1, 2))) }
    }

    /**
     * A frame's length is only what the other side claims: memory is taken as its bytes arrive. A header claiming
     * almost 16 MiB followed by a thousand bytes and the end fails having taken a few kilobytes, where it took 16 MiB
     * before anything came (up to four sessions at a time, each held for the session's 30 seconds). Measured with the
     * JVM's count of the bytes this thread allocates.
     */
    @Test
    fun aFrameTakesMemoryOnlyForTheBytesThatArrive() {
        val threads = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        assumeTrue("needs the count of allocated bytes", threads != null && threads.isThreadAllocatedMemorySupported)
        threads!!.isThreadAllocatedMemoryEnabled = true
        val claim = byteArrayOf(0, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()) + ByteArray(1_000)
        val before = threads.currentThreadAllocatedBytes
        assertFailsWith<IOException> { SyncNetwork.readFrame(ByteArrayInputStream(claim)) }
        val allocated = threads.currentThreadAllocatedBytes - before
        assertTrue("$allocated bytes allocated for a frame that sent 1,000", allocated < 1024 * 1024)

        // A frame larger than the first piece read still comes whole, without copies piling up as it grows.
        val payload = ByteArray(3 * 1024 * 1024 + 7) { (it * 31).toByte() }
        val output = ByteArrayOutputStream()
        SyncNetwork.writeFrame(output, payload)
        val frame = output.toByteArray()
        val start = threads.currentThreadAllocatedBytes
        assertTrue(payload.contentEquals(SyncNetwork.readFrame(ByteArrayInputStream(frame))))
        val used = threads.currentThreadAllocatedBytes - start
        assertTrue("$used bytes allocated for a frame of ${payload.size}", used < 3L * payload.size)
    }

    private val loopback: InetAddress = InetAddress.getLoopbackAddress()

    // Real sockets and real time: runBlocking, not runTest (whose virtual time would end each session at once).
    @Test
    fun aSessionSendsASnapshotAndBringsBackTheMergedOne() = runBlocking {
        val a = fixture("a.json")
        val b = fixture("b.json")
        val server = ServerSocket(0, 8, loopback)
        var received: SyncSnapshot? = null
        val serving = launch { SyncSession.serve(server) { incoming, _ -> received = incoming; SyncMerge.merge(b, incoming) } }
        val answer = SyncSession.exchange(loopback, server.localPort, a)
        assertEquals(a, received)
        assertEquals(SyncMerge.merge(b, a), answer)
        // Twice in a row: each connection is a session of its own.
        assertEquals(answer, SyncSession.exchange(loopback, server.localPort, a))
        serving.cancelAndJoin()
        assertTrue(server.isClosed)
    }

    @Test
    fun aSessionCountsAsDeliveredOnlyOnceTheClientHasReadTheAnswer() = runBlocking {
        val a = fixture("a.json")
        val server = ServerSocket(0, 8, loopback)
        val delivered = AtomicInteger()
        val serving = launch { SyncSession.serve(server, delivered = { delivered.incrementAndGet() }) { incoming, _ -> incoming } }
        assertEquals(a, SyncSession.exchange(loopback, server.localPort, a))
        withTimeout(5_000) { while (delivered.get() == 0) delay(10) }
        // A client that resets the connection instead of reading the answer: not delivered.
        Socket(loopback, server.localPort).use { socket ->
            SyncNetwork.writeFrame(socket.getOutputStream(), a.encoded())
            socket.setSoLinger(true, 0)
        }
        delay(300)
        assertEquals(1, delivered.get())
        serving.cancelAndJoin()
    }

    @Test
    fun nothingIsSentToOrAcceptedFromAnAddressThatIsNotLocal() = runBlocking {
        val a = fixture("a.json")
        // A server that refuses the address closes the connection before reading anything.
        val server = ServerSocket(0, 8, loopback)
        var merged = false
        val serving = launch { SyncSession.serve(server, isAllowed = { false }) { incoming, _ -> merged = true; incoming } }
        assertNull(SyncSession.exchange(loopback, server.localPort, a))
        assertFalse(merged)
        serving.cancelAndJoin()
        // A client never connects to an address that is not local.
        assertNull(SyncSession.exchange(InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8)), 443, a))
    }

    @Test
    fun aServerAnswersNothingToWhatIsNotASnapshot() = runBlocking {
        val server = ServerSocket(0, 8, loopback)
        val serving = launch { SyncSession.serve(server) { incoming, _ -> incoming } }
        val answer = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            java.net.Socket(loopback, server.localPort).use { socket ->
                SyncNetwork.writeFrame(socket.getOutputStream(), "not json".encodeToByteArray())
                socket.getInputStream().read()
            }
        }
        assertEquals(-1, answer)
        serving.cancelAndJoin()
    }

    /** A snapshot over the frame limit is not sent, not even its length: the session fails, nothing throws. */
    @Test
    fun aSnapshotTooLargeToSendIsNotSent() = runBlocking {
        val huge = fixture("a.json").copy(deviceName = "x".repeat(SyncNetwork.MAX_FRAME))
        val server = ServerSocket(0, 8, loopback)
        val received = AtomicInteger()
        val serving = launch { SyncSession.serve(server) { incoming, _ -> received.incrementAndGet(); incoming } }
        assertNull(SyncSession.exchange(loopback, server.localPort, huge))
        assertEquals(0, received.get())
        serving.cancelAndJoin()
        val output = ByteArrayOutputStream()
        assertFailsWith<IOException> { SyncNetwork.writeFrame(output, ByteArray(SyncNetwork.MAX_FRAME + 1)) }
        assertEquals(0, output.size())
    }

    /** A server whose answer is over the frame limit answers nothing, and goes on serving. */
    @Test
    fun anAnswerTooLargeToSendEndsOnlyItsSession() = runBlocking {
        val a = fixture("a.json")
        val huge = a.copy(deviceName = "x".repeat(SyncNetwork.MAX_FRAME))
        val server = ServerSocket(0, 8, loopback)
        val answerWith = AtomicReference<SyncSnapshot>(huge)
        val serving = launch { SyncSession.serve(server) { _, _ -> answerWith.get() } }
        assertNull(SyncSession.exchange(loopback, server.localPort, a))
        answerWith.set(a)
        assertEquals(a, SyncSession.exchange(loopback, server.localPort, a))
        serving.cancelAndJoin()
    }
}
