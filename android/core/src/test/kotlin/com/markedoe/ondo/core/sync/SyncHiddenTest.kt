package com.markedoe.ondo.core.sync

import com.markedoe.ondo.core.HangGuard
import java.io.File
import java.security.MessageDigest
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
 * Hidden categories, languages and styles in sync (docs/SYNC.md, "Hidden categories"): the shared examples, each one merging
 * on its own, older devices, the digest, the limits. The same cases as the Swift `SyncHiddenTests` (Packages/IPTVCore).
 */
class SyncHiddenTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    private val playlistId = "a1000000-0000-4000-8000-00000000000a"
    private val playlist = SyncPlaylist(playlistId, "Home", SyncPlaylist.M3U, "http://example.com/home.m3u", addedAt = 1, updatedAt = 1)
    private val sam = SyncProfile("c3000000-0000-4000-8000-00000000000c", "Sam", updatedAt = 5)

    /** `fixtures/sync` at the top of the repository, shared with the Swift tests. */
    private fun fixture(name: String): SyncSnapshot {
        val directory = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "fixtures/sync") }.first { it.isDirectory }
        return requireNotNull(SyncSnapshot.decode(File(directory, name).readBytes())) { name }
    }

    private fun SyncSnapshot.withoutDevice() = copy(device = "", deviceName = null)

    /**
     * What a device of an older version keeps of [snapshot] and sends on: it reads it ignoring the fields it does not know
     * (`hiddenCategories`; before profiles, `profiles` and `profileData` too), and keeps every key of `deleted` as it comes
     * (Android's `syncDeleted`, Apple's `sync.json`).
     */
    private fun olderVersion(snapshot: SyncSnapshot, knowsProfiles: Boolean): SyncSnapshot {
        fun JsonObject.without(vararg names: String) = JsonObject(filterKeys { it !in names })
        val read = Json.parseToJsonElement(snapshot.encoded().decodeToString()).jsonObject.without("hiddenCategories")
        val kept = if (knowsProfiles) {
            JsonObject(read + ("profileData" to JsonArray(read["profileData"]?.jsonArray.orEmpty().map { it.jsonObject.without("hiddenCategories") })))
        } else {
            read.without("profiles", "profileData")
        }
        return requireNotNull(SyncSnapshot.decode(kept.toString().encodeToByteArray()))
    }

    @Test
    fun mergingTheHiddenExamplesGivesTheSharedResultInEitherOrder() {
        val a = fixture("hidden-a.json")
        val b = fixture("hidden-b.json")
        val expected = fixture("hidden-merged.json")
        assertEquals(expected, SyncMerge.merge(a, b))
        assertEquals(expected.withoutDevice(), SyncMerge.merge(b, a).withoutDevice())
        // Merging again changes nothing.
        val merged = SyncMerge.merge(a, b)
        assertEquals(merged, SyncMerge.merge(merged, merged))
        assertEquals(merged, SyncMerge.merge(merged, b))
    }

    /**
     * Each hidden category, language and style merges on its own: showing one again later wins over hiding it, hiding it
     * again later wins over that, and the others stay as they are; on equal times, the showing again wins.
     */
    @Test
    fun eachHiddenItemMergesOnItsOwnTheLaterChangeWinning() {
        val tvClock = SyncClock(last = 1_000)
        val drama = SyncHidden.category(playlistId, "movie-12", tvClock.stamp(0))
        val portuguese = SyncHidden(SyncHidden.TAG, "PT", tvClock.stamp(0))
        val tv = SyncSnapshot(device = "tv", playlists = listOf(playlist), hiddenCategories = listOf(drama, portuguese))
        // The phone saw the TV's hides, then showed the drama category again.
        val phoneClock = SyncClock().also { it.receive(tv, wall = 0) }
        val shownAt = phoneClock.stamp(0)
        val phone = SyncSnapshot(device = "phone", playlists = listOf(playlist), hiddenCategories = listOf(portuguese), deleted = mapOf(drama.key to shownAt))
        for (merged in listOf(SyncMerge.merge(tv, phone), SyncMerge.merge(phone, tv))) {
            assertEquals(listOf(portuguese), merged.hiddenCategories)
        }
        // Hidden again on the TV after it heard of that: hidden everywhere again.
        tvClock.receive(phone, wall = 0)
        val again = drama.copy(hiddenAt = tvClock.stamp(0))
        val tvAgain = tv.copy(hiddenCategories = listOf(again, portuguese))
        for (merged in listOf(SyncMerge.merge(tvAgain, phone), SyncMerge.merge(phone, tvAgain))) {
            assertEquals(listOf(again, portuguese), merged.hiddenCategories)
        }
        // On equal times, the showing again wins.
        assertEquals(listOf(portuguese), SyncMerge.merge(tv, phone.copy(deleted = mapOf(drama.key to drama.hiddenAt))).hiddenCategories)
        // A language shown again leaves the categories hidden one by one, and the other way round.
        val showPortuguese = SyncSnapshot(device = "phone", deleted = mapOf(portuguese.key to shownAt))
        assertEquals(listOf(drama), SyncMerge.merge(tv, showPortuguese).hiddenCategories)
    }

    /** One profile's hidden categories never touch another's: Sam keeps his while the main profile shows its own again. */
    @Test
    fun eachProfileHasItsOwnHiddenCategories() {
        val drama = SyncHidden.category(playlistId, "movie-12", 10)
        val tv = SyncSnapshot(
            device = "tv", playlists = listOf(playlist), hiddenCategories = listOf(drama), profiles = listOf(sam),
            profileData = listOf(SyncProfileData(sam.id, hiddenCategories = listOf(drama))),
        )
        val phone = SyncSnapshot(device = "phone", playlists = listOf(playlist), profiles = listOf(sam), deleted = mapOf(drama.key to 20))
        for (merged in listOf(SyncMerge.merge(tv, phone), SyncMerge.merge(phone, tv))) {
            assertTrue(merged.hiddenCategories.isEmpty())
            assertEquals(listOf(drama), merged.profileData.single().hiddenCategories)
        }
        // Hidden in Sam's profile on the phone reaches Sam's profile only.
        val anime = SyncHidden(SyncHidden.WORD, "anime", 30)
        val phoneSam = phone.copy(profileData = listOf(SyncProfileData(sam.id, hiddenCategories = listOf(anime))))
        val merged = SyncMerge.merge(tv, phoneSam)
        assertEquals(listOf(drama, anime), merged.profileData.single().hiddenCategories)
        assertTrue(merged.hiddenCategories.isEmpty())
    }

    /**
     * A device of the current release, or of the one before profiles, knows nothing of hidden categories: what it keeps and
     * sends back has none, and a newer device that merges it loses none of its own. A showing again is a deletion, which it
     * keeps and passes on: a device that meets only it still hears of it. (A hide made on one newer device does not reach
     * another through it: nothing is lost, it travels once they meet a newer device.)
     */
    @Test
    fun anOlderDeviceLosesNoHiddenCategoryAndPassesOnShowingsAgain() {
        val drama = SyncHidden.category(playlistId, "movie-12", 11)
        val portuguese = SyncHidden(SyncHidden.TAG, "PT", 10)
        val spanish = SyncHidden(SyncHidden.TAG, "ES", 12)
        val tv = SyncSnapshot(
            device = "tv", playlists = listOf(playlist), hiddenCategories = listOf(drama, portuguese), profiles = listOf(sam),
            profileData = listOf(SyncProfileData(sam.id, hiddenCategories = listOf(spanish))),
        )
        for (knowsProfiles in listOf(true, false)) {
            val older = SyncSnapshot(device = "old", playlists = listOf(playlist))
            val kept = olderVersion(SyncMerge.merge(older, olderVersion(tv, knowsProfiles)), knowsProfiles)
            assertTrue(kept.hiddenCategories.isEmpty())
            // The TV merges the older device's answer: every hide stays, in each profile.
            val back = SyncMerge.merge(tv, kept)
            assertEquals(tv.hiddenCategories, back.hiddenCategories)
            assertEquals(listOf(spanish), back.profileData.single { it.profile == sam.id }.hiddenCategories)

            // Shown again on the TV, passed on by the older device to a phone that met only it.
            val shown = tv.copy(
                hiddenCategories = listOf(portuguese), deleted = mapOf(drama.key to 20),
                profileData = listOf(SyncProfileData(sam.id, deleted = mapOf(spanish.key to 21))),
            )
            val relayed = olderVersion(SyncMerge.merge(kept, olderVersion(shown, knowsProfiles)), knowsProfiles)
            assertEquals(20L, relayed.deleted[drama.key])
            val laterPortuguese = portuguese.copy(hiddenAt = 30)
            val phone = SyncSnapshot(
                device = "phone", playlists = listOf(playlist), hiddenCategories = listOf(drama, laterPortuguese), profiles = listOf(sam),
                profileData = listOf(SyncProfileData(sam.id, hiddenCategories = listOf(spanish))),
            )
            val after = SyncMerge.merge(phone, relayed)
            assertEquals("the drama category shown again; Portuguese hidden later stays", listOf(laterPortuguese), after.hiddenCategories)
            val samOnPhone = after.profileData.single { it.profile == sam.id }.hiddenCategories
            // Profiles do not travel through a device older than them: Sam keeps his, nothing lost.
            assertEquals(if (knowsProfiles) emptyList() else listOf(spanish), samOnPhone)
        }
    }

    /**
     * A snapshot without hidden categories is written byte for byte as before they synced: an older device and a newer one
     * holding the same data give it the same digest, and a client keeps sending probes across the update.
     */
    @Test
    fun aSnapshotWithoutHiddenCategoriesIsWrittenAndDigestedAsBefore() {
        val snapshot = SyncSnapshot(
            device = "a",
            rows = listOf(SyncRows(SyncRows.MOVIES, listOf("all"), listOf("new"), 900)),
            profiles = listOf(SyncProfile("p", updatedAt = 1)),
            profileData = listOf(SyncProfileData("p", rows = listOf(SyncRows(SyncRows.SERIES, updatedAt = 5)))),
            deleted = mapOf("list:x" to 3),
        )
        // What the version before hidden categories wrote for it: its fields in their order, the defaults written.
        val before = """{"type":"ondo-sync","version":1,"device":"","playlists":[],"favorites":[],"progress":[],"lists":[],"people":[],""" +
            """"keys":[],"rows":[{"tab":"movie","order":["all"],"hidden":["new"],"updatedAt":900}],""" +
            """"profiles":[{"id":"p","name":"","colorIndex":0,"symbol":"initial","kids":false,"updatedAt":1}],""" +
            """"profileData":[{"profile":"p","favorites":[],"progress":[],"lists":[],"people":[],""" +
            """"rows":[{"tab":"series","order":[],"hidden":[],"updatedAt":5}],"deleted":{}}],"deleted":{"list:x":3},"replaced":{}}"""
        val digest = MessageDigest.getInstance("SHA-256").digest(before.encodeToByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(digest, snapshot.contentDigest())
        assertFalse("hiddenCategories" in snapshot.encoded().decodeToString())
        // Merged with a device that hides nothing: the same.
        assertEquals(digest, SyncMerge.merge(snapshot, SyncSnapshot(device = "b")).contentDigest())
        assertEquals(digest, SyncMerge.merge(snapshot, olderVersion(snapshot, knowsProfiles = true)).contentDigest())
        // A hidden category changes it, in the snapshot's records as in a profile's, and reads back as it was written.
        val hidden = SyncHidden(SyncHidden.TAG, "PT", 7)
        val withHidden = snapshot.copy(hiddenCategories = listOf(hidden))
        val inProfile = snapshot.copy(profileData = listOf(snapshot.profileData.single().copy(hiddenCategories = listOf(hidden))))
        assertTrue(digest != withHidden.contentDigest())
        assertTrue(digest != inProfile.contentDigest())
        assertTrue(withHidden.contentDigest() != inProfile.contentDigest())
        assertEquals(withHidden, SyncSnapshot.decode(withHidden.encoded()))
        assertEquals(inProfile, SyncSnapshot.decode(inProfile.encoded()))
    }

    /**
     * Too many hidden records are trimmed as they are read, the same way on every device: the [SyncHidden.MAX_RECORDS]
     * hidden last (on equal times the smaller key, in UTF-16 code units); a kind or a value longer than
     * [SyncHidden.MAX_LENGTH], blank or with a control character is dropped. A merge keeps the same, whatever the order.
     */
    @Test
    fun tooManyHiddenRecordsAreTrimmedTheSameWayOnEveryDevice() {
        val max = SyncHidden.MAX_RECORDS
        val records = (0 until max + 100).map { SyncHidden(SyncHidden.TAG, "T" + it.toString().padStart(5, '0'), hiddenAt = (it / 3).toLong()) }
        val invalid = listOf(
            SyncHidden(SyncHidden.WORD, "x".repeat(SyncHidden.MAX_LENGTH + 1), 1_000_000),
            SyncHidden(SyncHidden.WORD, "a\u0007b", 1_000_000),
            SyncHidden(SyncHidden.WORD, " ", 1_000_000),
            SyncHidden("a:b", "x", 1_000_000),
            SyncHidden(SyncHidden.CATEGORY, "no playlist", 1_000_000),
        )
        val big = SyncSnapshot(device = "a", hiddenCategories = records.reversed() + invalid)
        val read = requireNotNull(SyncSnapshot.decode(big.encoded()))
        val expected = records.sortedWith(compareByDescending<SyncHidden> { it.hiddenAt }.thenBy { it.key }).take(max).mapTo(HashSet()) { it.key }
        assertEquals(max, read.hiddenCategories.size)
        assertEquals(expected, read.hiddenCategories.mapTo(HashSet()) { it.key })
        assertTrue("on equal times, the smaller key", "hide:tag:T00099" in expected && "hide:tag:T00101" !in expected)
        assertEquals(read.contentDigest(), requireNotNull(SyncSnapshot.decode(read.encoded())).contentDigest())
        // Hidden later on another device: in, whatever the order of the merge, in a profile's records too.
        val later = records.take(50).map { it.copy(hiddenAt = it.hiddenAt + 10_000) }
        val other = SyncSnapshot(device = "b", hiddenCategories = later)
        val merged = SyncMerge.merge(read, other)
        assertEquals(merged.hiddenCategories, SyncMerge.merge(other, read).hiddenCategories)
        assertEquals(max, merged.hiddenCategories.size)
        assertTrue(merged.hiddenCategories.containsAll(later))
        val inProfile = SyncSnapshot(device = "a", profiles = listOf(sam), profileData = listOf(SyncProfileData(sam.id, hiddenCategories = records + invalid)))
        val profileRead = requireNotNull(SyncSnapshot.decode(inProfile.encoded()))
        assertEquals(expected, profileRead.profileData.single().hiddenCategories.mapTo(HashSet()) { it.key })
    }

    /** Hidden records dated far ahead of the receiver's clock are left out, as any other (docs/SYNC.md, "Clocks"). */
    @Test
    fun hiddenCategoriesDatedFarAheadAreLeftOut() {
        val ahead = SyncHidden(SyncHidden.TAG, "PT", 10_000)
        val snapshot = SyncSnapshot(device = "b", hiddenCategories = listOf(ahead), profiles = listOf(sam),
            profileData = listOf(SyncProfileData(sam.id, hiddenCategories = listOf(ahead.copy(hiddenAt = 20_000)))))
        assertEquals(20_000L, snapshot.latestTime())
        val taken = snapshot.withoutRecordsAfter(9_999)
        assertTrue(taken.hiddenCategories.isEmpty())
        assertTrue(taken.profileData.single().hiddenCategories.isEmpty())
        assertEquals(listOf(ahead), snapshot.withoutRecordsAfter(10_000).hiddenCategories)
    }

    /**
     * The deletion of a hidden category is read with its playlist ID in lowercase; its category ID keeps its case (the
     * provider's), as do a tag and a word. A category of a replaced playlist becomes the kept playlist's.
     */
    @Test
    fun hiddenKeysAreReadWithTheirPlaylistInLowercaseAndFollowAReplacedPlaylist() {
        val json = """
            {"type":"ondo-sync","version":1,"device":"b","deleted":{
              "hide:category:A1000000-0000-4000-8000-00000000000A|Movie-12":20,
              "hide:tag:PT":20,"hide:word:Manga":20}}
        """.trimIndent()
        val b = requireNotNull(SyncSnapshot.decode(json.encodeToByteArray()))
        assertEquals(setOf("hide:category:$playlistId|Movie-12", "hide:tag:PT", "hide:word:Manga"), b.deleted.keys)
        val replacedId = "d4000000-0000-4000-8000-00000000000d"
        val same = playlist.copy(id = replacedId, addedAt = 2, updatedAt = 2)
        val a = SyncSnapshot(device = "a", playlists = listOf(playlist, same), hiddenCategories = listOf(SyncHidden.category(replacedId, "live-3", 5)))
        assertEquals(listOf(SyncHidden.category(playlistId, "live-3", 5)), SyncMerge.merge(a, SyncSnapshot(device = "c")).hiddenCategories)
    }
}
