package com.markedoe.ondo.core.sync

import com.markedoe.ondo.core.HangGuard
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Android and Apple devices sync with each other (fixtures/sync/interop/README.md): for each scenario, an Android device
 * scripted here with the Kotlin core and an iPhone scripted with the Swift core (`SyncInteropTests`, Packages/IPTVCore)
 * each wrote their snapshot as they send it (`android.json`, `apple.json`); the Android device answered the iPhone's
 * session with the Kotlin merge (`answer.json`) and the iPhone kept that answer merged with the Swift merge (`kept.json`).
 *
 * This test makes the Android device and the answer again and finds them as committed (any change to the Kotlin core's
 * format or merge shows here), reads what the Swift core wrote, does the iPhone's half of the session itself and finds
 * what the Swift core kept, and checks that both devices end with the same data and the same canonical digest.
 * `ONDO_WRITE_INTEROP=1` writes the Android files instead (then run the Swift tests with it too, then this one again).
 */
class SyncInteropTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "fixtures/sync/interop") }.first { it.isDirectory }
    private val writing = System.getenv("ONDO_WRITE_INTEROP") == "1"

    @Test
    fun profilesSyncBetweenAndroidAndApple() = check("profiles")

    @Test
    fun sharedListsSyncBetweenAndroidAndApple() = check("shared-lists")

    @Test
    fun deletionsSyncBetweenAndroidAndApple() = check("deletions")

    @Test
    fun hiddenCategoriesSyncBetweenAndroidAndApple() = check("hidden")

    /**
     * An iPhone shares a list; an older Android TV (which does not share lists) relays it to an iPad: the iPad keeps the
     * list where it was, never loses it.
     */
    @Test
    fun aListSharedBehindAnOlderAndroidDeviceIsNotLost() = checkOldRelay("old-relay-share", expectedOnIpad = InteropScenarios.SAM)

    /** An iPhone takes a shared list back; an older Android TV relays it to an iPad: the list stays with that profile. */
    @Test
    fun aListTakenBackBehindAnOlderAndroidDeviceStaysTakenBack() = checkOldRelay("old-relay-take-back", expectedOnIpad = "main")

    /**
     * The older relay scenarios: the Android device is a TV of the previous version (its snapshot and its answer are
     * what that version keeps: no household, no `placedAt`), and `kept.json` is what an iPad that only meets that TV keeps
     * of its answer.
     */
    private fun checkOldRelay(scenario: String, expectedOnIpad: String) {
        val directory = File(root, scenario)
        val tv = SharedListsTest.asOlderDevice(InteropScenarios.base(scenario, InteropScenarios.Script()).copy(device = InteropScenarios.ANDROID_DEVICE, deviceName = "Living room TV"), knowsProfiles = true)
        val androidFile = File(directory, "android.json")
        if (writing) {
            directory.mkdirs()
            androidFile.writeBytes(tv.encoded())
        }
        assertEquals("$scenario: android.json", tv, read(androidFile))
        val appleFile = File(directory, "apple.json")
        if (writing && !File(directory, "kept.json").exists()) {
            if (appleFile.exists()) File(directory, "answer.json").writeBytes(olderAnswer(tv, read(appleFile)).encoded())
            return
        }
        val apple = read(appleFile)
        val answer = olderAnswer(tv, apple)
        if (writing) File(directory, "answer.json").writeBytes(answer.encoded())
        assertEquals("$scenario: answer.json", answer, read(File(directory, "answer.json")))
        // The iPad's half, done here with the Kotlin core: what the Swift core kept is the same, and the list is there.
        val ipad = InteropScenarios.base(scenario, InteropScenarios.Script()).copy(device = InteropScenarios.IPAD_DEVICE, deviceName = "Bedroom iPad")
        val kept = SyncMerge.merge(ipad, InteropScenarios.clock().receive(answer, InteropScenarios.SESSION_WALL))
        val keptBySwift = read(File(directory, "kept.json"))
        assertEquals("$scenario: kept.json", content(kept), content(keptBySwift))
        val id = InteropScenarios.OLD_RELAY_LIST
        assertEquals("$scenario: on the iPad", listOf(expectedOnIpad), SharedListsTest.places(id, keptBySwift).map { if (it == SyncProfile.MAIN) "main" else it })
        // And the iPhone, which keeps the TV's answer, still has it where it put it.
        val iphone = SyncMerge.merge(apple, InteropScenarios.clock().receive(answer, InteropScenarios.SESSION_WALL))
        assertEquals("$scenario: on the iPhone", SharedListsTest.places(id, apple), SharedListsTest.places(id, iphone))
        val digests = mapOf("android" to tv.contentDigest(), "answer" to answer.contentDigest(), "canonical" to canonicalDigest(kept))
        val digestFile = File(directory, "android-digests.json")
        if (writing) digestFile.writeText(Json.encodeToString(JsonObject(digests.mapValues { JsonPrimitive(it.value) })) + "\n")
        assertEquals("$scenario: android-digests.json", digests, readDigests(digestFile))
        assertEquals("$scenario: the same canonical digest on both cores", canonicalDigest(kept), readDigests(File(directory, "apple-digests.json"))["canonical"])
    }

    /** What a TV of the previous version answers: its merge of what it keeps of the iPhone's snapshot, as it keeps it. */
    private fun olderAnswer(tv: SyncSnapshot, apple: SyncSnapshot) = SharedListsTest.asOlderDevice(
        SyncMerge.merge(tv, SharedListsTest.asOlderDevice(InteropScenarios.clock().receive(apple, InteropScenarios.SESSION_WALL), knowsProfiles = true)),
        knowsProfiles = true,
    )

    private fun check(scenario: String) {
        val directory = File(root, scenario)
        val android = InteropScenarios.android(scenario).let { it.copy(digest = it.contentDigest()) }
        val androidFile = File(directory, "android.json")
        if (writing) {
            directory.mkdirs()
            androidFile.writeBytes(android.encoded())
        }
        // The Android device as committed: this core writes the same snapshot (and reads it back the same).
        assertEquals("$scenario: android.json", android, read(androidFile))
        // Writing: the Swift core's files come next, then this test again.
        if (writing && !File(directory, "kept.json").exists()) {
            if (File(directory, "apple.json").exists()) File(directory, "answer.json").writeBytes(answerTo(android, read(File(directory, "apple.json"))).encoded())
            return
        }
        val apple = read(File(directory, "apple.json"))
        // The session: the iPhone (a client) sends its snapshot; the Android device (the server) merges and answers.
        val answer = answerTo(android, apple)
        val answerFile = File(directory, "answer.json")
        if (writing) answerFile.writeBytes(answer.encoded())
        assertEquals("$scenario: answer.json", answer, read(answerFile))
        // The iPhone's half, done here with the Kotlin core: what the Swift core kept is the same.
        val kept = SyncMerge.merge(apple, InteropScenarios.clock().receive(answer, InteropScenarios.SESSION_WALL))
        val keptBySwift = read(File(directory, "kept.json"))
        assertEquals("$scenario: kept.json", content(kept), content(keptBySwift))
        // Both devices end with the same data, whichever merges first.
        assertEquals("$scenario: both devices alike", content(answer), content(keptBySwift))
        assertEquals("$scenario: either order", content(answer), content(SyncMerge.merge(apple, android)))
        // Digests: each core's own (stable from one run to the next), and the canonical one, the same on both cores.
        val digests = mapOf(
            "android" to android.contentDigest(),
            "answer" to answer.contentDigest(),
            "canonical" to canonicalDigest(answer),
        )
        val digestFile = File(directory, "android-digests.json")
        if (writing) digestFile.writeText(Json.encodeToString(JsonObject(digests.mapValues { JsonPrimitive(it.value) })) + "\n")
        assertEquals("$scenario: android-digests.json", digests, readDigests(digestFile))
        assertEquals("$scenario: the same canonical digest on both cores", canonicalDigest(answer), readDigests(File(directory, "apple-digests.json"))["canonical"])
        assertEquals("$scenario: canonical digest of what the iPhone kept", canonicalDigest(answer), canonicalDigest(keptBySwift))
    }

    private fun answerTo(android: SyncSnapshot, apple: SyncSnapshot) =
        SyncMerge.merge(android, InteropScenarios.clock().receive(apple, InteropScenarios.SESSION_WALL)).let { it.copy(digest = it.contentDigest()) }

    private fun read(file: File): SyncSnapshot = requireNotNull(SyncSnapshot.decode(file.readBytes())) { file.path }

    private fun readDigests(file: File): Map<String, String> =
        Json.parseToJsonElement(file.readText()).jsonObject.mapValues { it.value.jsonPrimitive.content }

    /** What both devices must hold alike: the snapshot without who holds it, when, and its digest. */
    private fun content(snapshot: SyncSnapshot) = snapshot.copy(device = "", deviceName = null, clock = null, digest = null)

    companion object {
        private val excluded = setOf("device", "deviceName", "clock", "digest", "seen", "probe", "unchanged")

        /**
         * The digest both cores give the same snapshot (fixtures/sync/interop/README.md): SHA-256 of its canonical JSON,
         * written as each core writes it, then read back and written again with members sorted, empty ones and who sends
         * it left out, whole numbers without a decimal point and strings escaped the same way.
         */
        fun canonicalDigest(snapshot: SyncSnapshot): String {
            val tree = Json.parseToJsonElement(snapshot.encoded().decodeToString()).jsonObject.filterKeys { it !in excluded }
            val canonical = StringBuilder().also { write(JsonObject(tree), it) }.toString()
            return MessageDigest.getInstance("SHA-256").digest(canonical.encodeToByteArray()).joinToString("") { "%02x".format(it) }
        }

        private fun isEmpty(element: JsonElement) =
            element is JsonNull || (element is JsonArray && element.isEmpty()) || (element is JsonObject && element.isEmpty())

        private fun write(element: JsonElement, out: StringBuilder) {
            when (element) {
                is JsonObject -> {
                    out.append('{')
                    element.entries.filter { !isEmpty(it.value) }.sortedBy { it.key }.forEachIndexed { index, (key, value) ->
                        if (index > 0) out.append(',')
                        string(key, out)
                        out.append(':')
                        write(value, out)
                    }
                    out.append('}')
                }
                is JsonArray -> {
                    out.append('[')
                    element.forEachIndexed { index, value ->
                        if (index > 0) out.append(',')
                        write(value, out)
                    }
                    out.append(']')
                }
                is JsonPrimitive -> when {
                    element.isString -> string(element.content, out)
                    element.booleanOrNull != null -> out.append(element.booleanOrNull)
                    element.longOrNull != null -> out.append(element.longOrNull)
                    else -> {
                        val number = element.content.toDouble()
                        if (number == Math.rint(number) && Math.abs(number) < 9.007199254740992E15) out.append(number.toLong()) else out.append(number)
                    }
                }
            }
        }

        private fun string(text: String, out: StringBuilder) {
            out.append('"')
            for (char in text) {
                when {
                    char == '"' -> out.append("\\\"")
                    char == '\\' -> out.append("\\\\")
                    char < ' ' -> out.append("\\u").append("%04x".format(char.code))
                    else -> out.append(char)
                }
            }
            out.append('"')
        }
    }
}

/**
 * The scenarios of the interop test, scripted the same way as the Swift `InteropScenarios` (Packages/IPTVCore): what
 * both devices had in common after an earlier session, then what the Android device changed on its own. Every time is
 * given by a [SyncClock] at a known wall time, as the app dates a change.
 */
object InteropScenarios {
    const val WALL = 1_760_000_000_000L
    const val SESSION_WALL = WALL + 300_000L
    const val ANDROID_DEVICE = "a0a0a0a0-0000-4000-8000-00000000000a"
    const val IPAD_DEVICE = "c0c0c0c0-0000-4000-8000-00000000000c"
    const val MAIN = SyncProfile.MAIN
    const val SAM = "11111111-1111-4111-8111-111111111111"
    const val KIDS = "22222222-2222-4222-8222-222222222222"
    const val GUEST = "33333333-3333-4333-8333-333333333333"
    const val HOME = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    const val SPORTS = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    const val MOVIE_NIGHT = "c1c1c1c1-0000-4000-8000-000000000001"

    /** The list of the older relay scenarios. */
    const val OLD_RELAY_LIST = MOVIE_NIGHT
    const val DOCUMENTARIES = "c2c2c2c2-0000-4000-8000-000000000002"
    const val ROAD_TRIP = "c3c3c3c3-0000-4000-8000-000000000003"
    const val CARTOONS = "c4c4c4c4-0000-4000-8000-000000000004"

    /** A receiving device's clock in a session (`last` is not needed: the times stay within a day of the wall clock). */
    fun clock() = SyncClock()

    /** Dates changes as a device does: each at `max(wall clock, last + 1)`. */
    class Script {
        private val clock = SyncClock()

        /** The time of a change made [seconds] after [WALL]. */
        fun at(seconds: Int): Long = clock.stamp(WALL + seconds * 1000L)
    }

    fun item(playlist: String, kind: String, id: String, title: String, time: Long) = SyncListItem(playlist, kind, id, title, addedAt = time)

    fun list(id: String, name: String, items: List<SyncListItem>, time: Long, placedAt: Long? = null) =
        SyncList(id, name, items, updatedAt = time, renamedAt = time, orderedAt = time, order = items.map { it.key }, placedAt = placedAt)

    /** What both devices had after their last session, for [scenario]. */
    fun base(scenario: String, script: Script): SyncSnapshot {
        val t0 = script.at(0)
        val home = SyncPlaylist(HOME, "Home", SyncPlaylist.M3U, "http://example.com/home.m3u", addedAt = t0, updatedAt = t0)
        val profiles = listOf(
            SyncProfile(SAM, "Sam", colorIndex = 3, symbol = "star.fill", updatedAt = script.at(1)),
            SyncProfile(KIDS, "Kids", colorIndex = 4, symbol = "pawprint.fill", kids = true, updatedAt = script.at(2)),
        )
        return when (scenario) {
            "profiles" -> {
                val guest = SyncProfile(GUEST, "Guest", colorIndex = 6, symbol = "🎬", updatedAt = script.at(3))
                SyncSnapshot(
                    device = "",
                    playlists = listOf(home),
                    favorites = listOf(SyncFavorite(HOME, "movie", "m1", "Northern Lights", "https://example.com/m1.jpg", script.at(10))),
                    people = listOf(SyncPerson("Ada Lovelace", null, script.at(11))),
                    rows = listOf(SyncRows(SyncRows.MOVIES, listOf("continue", "all"), emptyList(), script.at(12))),
                    profiles = profiles + guest,
                    profileData = listOf(
                        SyncProfileData(
                            SAM,
                            favorites = listOf(SyncFavorite(HOME, "movie", "m2", "Glass City", null, script.at(20))),
                            progress = listOf(SyncProgress(HOME, "series", "s1", "e1", "Harbour Lights", "S1 E1", null, 600.5, 2400.0, script.at(21))),
                            rows = listOf(SyncRows(SyncRows.MOVIES, listOf("mylist", "continue", "all"), listOf("trending"), script.at(22))),
                        ),
                        script.at(30).let { t30 -> SyncProfileData(KIDS, lists = listOf(list(CARTOONS, "Cartoons", listOf(item(HOME, "series", "k1", "Paper Boats", t30)), t30))) },
                        SyncProfileData(GUEST, favorites = listOf(SyncFavorite(HOME, "movie", "m3", "Quiet Valley", null, script.at(40)))),
                    ),
                )
            }
            "shared-lists" -> {
                val t10 = script.at(10)
                val documentaries = list(DOCUMENTARIES, "Documentaries", listOf(item(HOME, "movie", "d1", "Deep Sea", t10)), t10)
                val first = item(HOME, "movie", "m1", "Northern Lights", script.at(20))
                val t21 = script.at(21)
                val movieNight = list(MOVIE_NIGHT, "Movie night", listOf(first, item(HOME, "movie", "m2", "Glass City", t21)), t21)
                val r1 = item(HOME, "movie", "r1", "Open Road", script.at(40))
                val t41 = script.at(41)
                val roadTrip = list(ROAD_TRIP, "Road trip", listOf(r1, item(HOME, "series", "r2", "Coastline", t41)), t41, placedAt = script.at(42))
                val t50 = script.at(50)
                val cartoons = list(CARTOONS, "Cartoons", listOf(item(HOME, "series", "k1", "Paper Boats", t50)), t50, placedAt = t50)
                SyncSnapshot(
                    device = "",
                    playlists = listOf(home),
                    lists = listOf(documentaries),
                    profiles = profiles,
                    profileData = listOf(SyncProfileData(SAM, lists = listOf(movieNight))),
                    household = SyncHousehold(lists = listOf(roadTrip, cartoons)),
                )
            }
            "deletions" -> {
                val t5 = script.at(5)
                val sports = SyncPlaylist(SPORTS, "Sports", SyncPlaylist.XTREAM, "http://example.com:8080", "viewer", "secret", null, addedAt = t5, updatedAt = t5)
                SyncSnapshot(
                    device = "",
                    playlists = listOf(home, sports),
                    favorites = listOf(SyncFavorite(HOME, "movie", "p1", "Northern Lights", null, script.at(10))),
                    progress = listOf(SyncProgress(SPORTS, "movie", "q1", null, "Final Whistle", null, null, 1200.0, 5400.0, script.at(11))),
                    profiles = profiles,
                    profileData = listOf(SyncProfileData(SAM, favorites = listOf(SyncFavorite(SPORTS, "live", "c7", "Stadium One", null, script.at(20))))),
                )
            }
            // Each profile's hidden categories, languages and styles.
            "hidden" -> {
                val drama = SyncHidden.category(HOME, "movie-12", script.at(10))
                val portuguese = SyncHidden(SyncHidden.TAG, "PT", script.at(11))
                val manga = SyncHidden(SyncHidden.WORD, "manga", script.at(20))
                val horror = SyncHidden.category(HOME, "movie-horror", script.at(30))
                SyncSnapshot(
                    device = "",
                    playlists = listOf(home),
                    hiddenCategories = listOf(drama, portuguese),
                    profiles = profiles,
                    profileData = listOf(SyncProfileData(SAM, hiddenCategories = listOf(manga)), SyncProfileData(KIDS, hiddenCategories = listOf(horror))),
                )
            }
            // Sam's list, before the iPhone shares it.
            "old-relay-share" -> {
                val first = item(HOME, "movie", "m1", "Northern Lights", script.at(20))
                val t21 = script.at(21)
                SyncSnapshot(
                    device = "",
                    playlists = listOf(home),
                    profiles = profiles,
                    profileData = listOf(SyncProfileData(SAM, lists = listOf(list(MOVIE_NIGHT, "Movie night", listOf(first, item(HOME, "movie", "m2", "Glass City", t21)), t21)))),
                )
            }
            // The same list shared with every profile, before the iPhone takes it back.
            "old-relay-take-back" -> {
                val first = item(HOME, "movie", "m1", "Northern Lights", script.at(20))
                val t21 = script.at(21)
                SyncSnapshot(
                    device = "",
                    playlists = listOf(home),
                    profiles = profiles,
                    household = SyncHousehold(listOf(list(MOVIE_NIGHT, "Movie night", listOf(first, item(HOME, "movie", "m2", "Glass City", t21)), t21, placedAt = script.at(22)))),
                )
            }
            else -> error(scenario)
        }
    }

    /** The Android device of [scenario]: the base, then what it changed on its own (between 100 and 199 seconds). */
    fun android(scenario: String): SyncSnapshot {
        val script = Script()
        var snapshot = base(scenario, script).copy(device = ANDROID_DEVICE, deviceName = "Living room TV")
        when (scenario) {
            "profiles" -> {
                // Sam adds a movie to My List; the main profile reorders the Series tab; the Guest profile is deleted;
                // the Kids profile watches an episode.
                val sam = snapshot.profileData.first { it.profile == SAM }
                val t100 = script.at(100)
                snapshot = snapshot.copy(
                    profileData = snapshot.profileData.map {
                        if (it.profile == SAM) sam.copy(favorites = sam.favorites + SyncFavorite(HOME, "movie", "m4", "Silent Harbour", null, t100)) else it
                    },
                    rows = snapshot.rows + SyncRows(SyncRows.SERIES, listOf("mylist", "all"), listOf("trending"), script.at(110)),
                )
                val t120 = script.at(120)
                snapshot = snapshot.copy(
                    profiles = snapshot.profiles.filter { it.id != GUEST },
                    profileData = snapshot.profileData.filter { it.profile != GUEST },
                    deleted = snapshot.deleted + (SyncProfile.KEY_PREFIX + GUEST to t120),
                )
                val t130 = script.at(130)
                snapshot = snapshot.copy(
                    profileData = snapshot.profileData.map {
                        if (it.profile == KIDS) it.copy(progress = listOf(SyncProgress(HOME, "series", "k1", "k1e2", "Paper Boats", "S1 E2", null, 300.0, 1320.0, t130))) else it
                    },
                )
            }
            "shared-lists" -> {
                // Sam shares Movie night; an item is taken out of Road trip; Cartoons is deleted for every profile.
                snapshot = SyncSharing.share(snapshot, MOVIE_NIGHT, SAM, removedAt = script.at(100), placedAt = script.at(101))
                val t110 = script.at(110)
                val household = snapshot.household!!
                snapshot = snapshot.copy(
                    household = household.copy(
                        lists = household.lists.map { list ->
                            if (list.id == ROAD_TRIP) list.copy(items = list.items.filter { it.item != "r2" }, order = list.order?.filter { !it.endsWith("|r2") }, updatedAt = t110) else list
                        },
                        deleted = household.deleted + ("${SyncList.ITEM_PREFIX}$ROAD_TRIP|$HOME|series|r2" to t110),
                    ),
                )
                snapshot = SyncSharing.deleteShared(snapshot, CARTOONS, script.at(115))
            }
            "hidden" -> {
                // The main profile hides Spanish; Sam shows manga again; a parent hides a live category in the Kids profile.
                snapshot = snapshot.copy(hiddenCategories = snapshot.hiddenCategories + SyncHidden(SyncHidden.TAG, "ES", script.at(100)))
                val t110 = script.at(110)
                snapshot = snapshot.copy(
                    profileData = snapshot.profileData.map {
                        if (it.profile == SAM) it.copy(hiddenCategories = emptyList(), deleted = it.deleted + ("${SyncHidden.KEY_PREFIX}word:manga" to t110)) else it
                    },
                )
                val t120 = script.at(120)
                snapshot = snapshot.copy(
                    profileData = snapshot.profileData.map {
                        if (it.profile == KIDS) it.copy(hiddenCategories = it.hiddenCategories + SyncHidden.category(HOME, "live-7", t120)) else it
                    },
                )
            }
            "deletions" -> {
                // The Sports playlist is deleted: its records go with it, in every profile.
                val t100 = script.at(100)
                snapshot = snapshot.copy(
                    playlists = snapshot.playlists.filter { it.id != SPORTS },
                    progress = snapshot.progress.filter { it.playlist != SPORTS },
                    profileData = snapshot.profileData.map { bucket -> bucket.copy(favorites = bucket.favorites.filter { it.playlist != SPORTS }) },
                    deleted = snapshot.deleted + ("playlist:$SPORTS" to t100),
                )
            }
        }
        return snapshot.copy(clock = script.at(199))
    }
}
