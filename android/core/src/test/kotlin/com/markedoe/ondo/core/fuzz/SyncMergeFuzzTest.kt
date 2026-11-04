package com.markedoe.ondo.core.fuzz

import com.markedoe.ondo.core.HangGuard
import com.markedoe.ondo.core.sync.SyncClock
import com.markedoe.ondo.core.sync.SyncFavorite
import com.markedoe.ondo.core.sync.SyncList
import com.markedoe.ondo.core.sync.SyncListItem
import com.markedoe.ondo.core.sync.SyncMerge
import com.markedoe.ondo.core.sync.SyncPerson
import com.markedoe.ondo.core.sync.SyncPlaylist
import com.markedoe.ondo.core.sync.SyncProgress
import com.markedoe.ondo.core.sync.SyncSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule

/**
 * The merge's promises (docs/SYNC.md, "Merge") on generated snapshots: the order of the two snapshots does not
 * matter, merging the result again changes nothing, and devices that sync end with the same data. Devices hold
 * different versions of the same records, deletions, and the same playlist added under several IDs.
 */
class SyncMergeFuzzTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    /**
     * Generated snapshots, from a few versions of each record. Unless [simultaneousEdits], each version has a time of
     * its own and devices hold copies of them, as when a change travels from device to device; otherwise devices may
     * also have changed one record in the same millisecond, and records of different playlists may share times.
     */
    private class Snapshots(seed: Long, private val simultaneousEdits: Boolean) {
        private val fuzz = Fuzz(seed)
        private val playlistIds = listOf("p1", "p2", "p3", "p4")
        private val addedAt = mapOf("p1" to 1L, "p2" to 2L, "p3" to 2L, "p4" to 3L)
        private val urls = listOf("http://example.com/a.m3u", " http://EXAMPLE.com/a.m3u", "http://example.com/b.m3u")
        private var clock = 10L

        private fun time(): Long = if (simultaneousEdits) 10L + fuzz.int(4) else (clock + 1 + fuzz.int(3)).also { clock = it }

        private fun id(id: String) = if (fuzz.chance(10)) id.uppercase() else id

        private fun title() = fuzz.pick("T", "U")

        private fun playlistOfRecords() = fuzz.pick(playlistIds + "px")

        /** 1 to 3 versions of a record, each with its time. */
        private fun <T> versions(make: (Long) -> T): List<T> = List(1 + fuzz.int(3)) { make(time()) }

        private val playlists = playlistIds.associateWith { id ->
            versions { time ->
                SyncPlaylist(
                    id = id,
                    name = fuzz.pick("Home", "Sport"),
                    kind = SyncPlaylist.M3U,
                    url = fuzz.pick(urls),
                    // A playlist's addedAt never changes.
                    addedAt = if (simultaneousEdits) 1L + fuzz.int(4) else addedAt.getValue(id),
                    updatedAt = time,
                )
            }
        }

        private val favorites = (playlistIds + "px").flatMap { playlist -> listOf("m1", "m2").map { playlist to it } }.associateWith { (playlist, item) ->
            versions { time -> SyncFavorite(playlist, "movie", item, title(), image = fuzz.pick(null, "i1"), addedAt = time) }
        }

        private val progress = (playlistIds + "px").flatMap { playlist -> listOf("m1", "m2").map { playlist to it } }.associateWith { (playlist, item) ->
            versions { time ->
                SyncProgress(playlist, "movie", item, title = title(), position = fuzz.pick(10.0, 20.0), duration = fuzz.pick(100.0, 200.0), watchedAt = time)
            }
        }

        private val lists = listOf("l1", "l2").associateWith { id ->
            versions { time ->
                SyncList(
                    id = id,
                    name = fuzz.pick("Kids", "Weekend"),
                    // Without a time of its own (an older version) or with one, no later than the list's.
                    items = List(fuzz.int(3)) { SyncListItem(playlistOfRecords(), "movie", fuzz.pick("m1", "m2"), title = title(), addedAt = fuzz.pick(null, time, time - 1)) },
                    people = List(fuzz.int(2)) { SyncPerson(fuzz.pick("Ada", "ADA", "Bea"), image = fuzz.pick(null, "i1"), addedAt = time) },
                    updatedAt = time,
                    renamedAt = fuzz.pick(null, time, time - 1),
                    orderedAt = fuzz.pick(null, time, time - 2),
                )
            }
        }

        // The same person written in two cases is one person.
        private val people = listOf("ada", "bea").associateWith { name ->
            versions { time -> SyncPerson(fuzz.pick(name, name.uppercase()), image = fuzz.pick(null, "i1"), addedAt = time) }
        }

        private val keys = playlistIds.map { "playlist:$it" } + favorites.values.map { it.first().key } +
            progress.values.map { it.first().key } + listOf("list:l1", "list:l2", "person:ada", "person:BEA") +
            listOf("l1", "L2").flatMap { list -> listOf("listitem:$list|${playlistIds.first()}|movie|m1", "listitem:$list|px|movie|m2", "listitem:$list|person:ADA") }
        private val deletions = keys.associateWith { versions { time -> time } }

        /** Of each record, nothing or one of its versions; the ID in the case the device writes it. */
        private fun <T> held(versions: Collection<List<T>>, percent: Int): List<T> =
            versions.filter { fuzz.chance(percent) }.map { fuzz.pick(it) }

        fun snapshot(device: String) = SyncSnapshot(
            device = device,
            playlists = held(playlists.values, 50).map { it.copy(id = id(it.id)) },
            favorites = held(favorites.values, 25).map { it.copy(playlist = id(it.playlist)) },
            progress = held(progress.values, 25).map { it.copy(playlist = id(it.playlist)) },
            people = held(people.values, 25),
            lists = held(lists.values, 50).map { list -> list.copy(id = id(list.id), items = list.items.map { it.copy(playlist = id(it.playlist)) }) },
            deleted = keys.filter { fuzz.chance(10) }.associateWith { fuzz.pick(deletions.getValue(it)) },
        )
    }

    private fun SyncSnapshot.withoutDevice() = copy(device = "", deviceName = null)

    private fun describe(vararg snapshots: SyncSnapshot) = snapshots.joinToString("\n") { it.encoded().decodeToString() }

    /** One session (docs/SYNC.md, "Transport"): the server keeps and answers the merge, the client keeps its own. */
    private fun session(client: SyncSnapshot, server: SyncSnapshot): Pair<SyncSnapshot, SyncSnapshot> {
        val answer = SyncMerge.merge(server, client)
        return SyncMerge.merge(client, answer) to answer
    }

    /** Two devices: the order does not matter, merging again changes nothing, one session leaves both the same. */
    private fun checkTwoDevices(simultaneousEdits: Boolean, cases: Int) {
        for (seed in 0 until cases.toLong()) {
            val snapshots = Snapshots(seed, simultaneousEdits)
            val a = snapshots.snapshot("a")
            val b = snapshots.snapshot("b")
            val merged = SyncMerge.merge(a, b)
            assertEquals(merged.withoutDevice(), SyncMerge.merge(b, a).withoutDevice(), "order, seed $seed:\n${describe(a, b)}")
            assertEquals(merged, SyncMerge.merge(merged, merged), "again, seed $seed:\n${describe(a, b)}")
            assertEquals(merged, SyncMerge.merge(merged, b), "again with b, seed $seed:\n${describe(a, b)}")
            val (client, server) = session(a, b)
            assertEquals(server.withoutDevice(), client.withoutDevice(), "one session, seed $seed:\n${describe(a, b)}")
        }
    }

    /** Three devices, each session between two of them: after one round all have the same data, whatever the order. */
    private fun checkThreeDevices(simultaneousEdits: Boolean, cases: Int) {
        for (seed in 0 until cases.toLong()) {
            val snapshots = Snapshots(seed, simultaneousEdits)
            val start = listOf(snapshots.snapshot("a"), snapshots.snapshot("b"), snapshots.snapshot("c"))
            var devices = start
            for ((first, second) in listOf(0 to 1, 1 to 2, 0 to 1)) {
                val (kept, answered) = session(devices[first], devices[second])
                devices = devices.toMutableList().also { it[first] = kept; it[second] = answered }
            }
            assertEquals(1, devices.map { it.withoutDevice() }.distinct().size, "three devices, seed $seed:\n${describe(*start.toTypedArray())}")
        }
    }

    @Test
    fun twoDevicesAgreeWhateverTheOrder() = checkTwoDevices(simultaneousEdits = false, cases = 5_000)

    @Test
    fun threeDevicesAgreeWhateverTheOrderOfTheirSessions() = checkThreeDevices(simultaneousEdits = false, cases = 5_000)

    /**
     * One record changed on two devices in the same millisecond: ties are broken on every field of a record, and the
     * order of a list is kept as it was set, so the devices agree all the same.
     */
    @Test
    fun twoDevicesAgreeOnChangesMadeInTheSameMillisecond() = checkTwoDevices(simultaneousEdits = true, cases = 3_000)

    @Test
    fun threeDevicesAgreeOnChangesMadeInTheSameMillisecond() = checkThreeDevices(simultaneousEdits = true, cases = 3_000)

    // Three devices with clocks hours apart, syncing in a random order (the Swift `SyncFuzzTests`).

    /** A device's data, dated by its own clock ([skew] ahead of the true time). */
    private fun device(name: String, skew: Long, fuzz: Fuzz): SyncSnapshot {
        fun time() = START + fuzz.int(1_000) + skew
        val items = listOf("m1", "m2", "m3")
        val favorites = items.filter { fuzz.chance(40) }.map { SyncFavorite("p", "movie", it, fuzz.pick("T", "U"), fuzz.pick(null, "i1"), time()) }
        val progress = items.filter { fuzz.chance(40) }.map {
            SyncProgress("p", "movie", it, title = "T", position = fuzz.int(3) * 10.0, duration = 100.0 + fuzz.int(2) * 100, watchedAt = time())
        }
        val people = listOf("Ada", "Bea").filter { fuzz.chance(40) }.map { SyncPerson(it, fuzz.pick(null, "i1"), time()) }
        val lists = if (fuzz.chance(60)) {
            val listTime = time()
            val keys = items.filter { fuzz.chance(50) }.shuffled(java.util.Random(fuzz.int(1_000).toLong()))
            listOf(
                SyncList(
                    "l", fuzz.pick("Kids", "Weekend"),
                    items = keys.map { SyncListItem("p", "movie", it, "T", addedAt = listTime - fuzz.int(5)) },
                    updatedAt = listTime, renamedAt = listTime - fuzz.int(3), orderedAt = listTime - fuzz.int(3),
                ),
            )
        } else {
            emptyList()
        }
        val deleted = HashMap<String, Long>()
        items.filter { fuzz.chance(15) }.forEach { deleted["favorite:p|movie|$it"] = time() }
        items.filter { fuzz.chance(10) }.forEach { deleted["progress:p|movie|$it|"] = time() }
        items.filter { fuzz.chance(10) }.forEach { deleted["listitem:l|p|movie|$it"] = time() }
        if (fuzz.chance(10)) deleted["person:ada"] = time()
        return SyncSnapshot(device = name, favorites = favorites, progress = progress, lists = lists, people = people, deleted = deleted)
    }

    /**
     * One session: what each side receives is left out when dated more than a day after its own clock, taken into its
     * clock, and merged; no time is moved (docs/SYNC.md, "Clocks").
     */
    private fun session(
        client: SyncSnapshot, clientClock: SyncClock, clientWall: Long,
        server: SyncSnapshot, serverClock: SyncClock, serverWall: Long,
    ): Pair<SyncSnapshot, SyncSnapshot> {
        val received = serverClock.receive(client, serverWall)
        val kept = SyncMerge.merge(server, received)
        val answer = clientClock.receive(kept, clientWall)
        return SyncMerge.merge(client, answer) to kept
    }

    /** The snapshot without who holds it. */
    private fun SyncSnapshot.content() = copy(device = "", clock = null)

    @Test
    fun threeDevicesWithSkewedClocksAgreeAndLoseNothing() {
        for (seed in 0 until 2_000L) {
            val fuzz = Fuzz(seed)
            val skews = List(3) { fuzz.pick(-2 * HOUR, 0L, 2 * HOUR) }
            val start = List(3) { device(listOf("a", "b", "c")[it], skews[it], fuzz) }
            val devices = start.toMutableList()
            val clocks = List(3) { SyncClock() }
            var now = START + 10_000
            // Random sessions, then each pair once more both ways: everything has reached everyone.
            val pairs = List(fuzz.int(6)) { fuzz.int(3) to fuzz.int(3) }.filter { it.first != it.second } +
                listOf(0 to 1, 1 to 2, 2 to 0, 1 to 0, 2 to 1, 0 to 2)
            for ((client, server) in pairs) {
                now += 1_000
                val (kept, answered) = session(devices[client], clocks[client], now + skews[client], devices[server], clocks[server], now + skews[server])
                devices[client] = kept
                devices[server] = answered
            }
            val seen = devices.map { it.content() }
            assertEquals(seen[0], seen[1], "seed $seed")
            assertEquals(seen[1], seen[2], "seed $seed")
            assertNothingLost(start.map { it.content() }, seen[0], seed)
        }
    }

    /** Each record of a key is kept unless a deletion of that key is at least as late as its latest version. */
    private fun assertNothingLost(start: List<SyncSnapshot>, result: SyncSnapshot, seed: Long) {
        val deleted = HashMap<String, Long>()
        start.forEach { snapshot -> snapshot.deleted.forEach { (key, time) -> deleted.merge(key, time, ::maxOf) } }
        fun check(key: String, time: Long, present: Boolean, what: String) {
            if (deleted[key]?.let { time > it } == false) return
            assertTrue(present, "seed $seed: $what lost")
        }
        start.flatMap { it.favorites }.forEach { favorite -> check(favorite.key, favorite.addedAt, result.favorites.any { it.key == favorite.key && it.addedAt >= favorite.addedAt }, favorite.key) }
        start.flatMap { it.progress }.forEach { entry -> check(entry.key, entry.watchedAt, result.progress.any { it.key == entry.key && it.watchedAt >= entry.watchedAt }, entry.key) }
        start.flatMap { it.people }.forEach { person -> check(person.key, person.addedAt, result.people.any { it.key == person.key && it.addedAt >= person.addedAt }, person.key) }
        val lists = start.flatMap { it.lists }
        val list = lists.firstOrNull() ?: return
        check(list.key, lists.maxOf { it.updatedAt }, result.lists.isNotEmpty(), "list")
        val kept = result.lists.firstOrNull() ?: return
        for (source in lists) {
            for (item in source.items) {
                val time = item.addedAt ?: source.updatedAt
                check(list.itemKey(item.key), time, kept.items.any { it.key == item.key && (it.addedAt ?: 0) >= time }, "list item ${item.key}")
            }
        }
    }

    private companion object {
        const val START = 1_760_000_000_000L
        const val HOUR = 3_600_000L
    }
}
