package com.markedoe.ondo.core.fuzz

import com.markedoe.ondo.core.sync.SyncFavorite
import com.markedoe.ondo.core.sync.SyncList
import com.markedoe.ondo.core.sync.SyncListItem
import com.markedoe.ondo.core.sync.SyncMerge
import com.markedoe.ondo.core.sync.SyncPlaylist
import com.markedoe.ondo.core.sync.SyncProgress
import com.markedoe.ondo.core.sync.SyncSnapshot
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The merge's promises (docs/SYNC.md, "Merge") on generated snapshots: the order of the two snapshots does not
 * matter, merging the result again changes nothing, and devices that sync end with the same data. Devices hold
 * different versions of the same records, deletions, and the same playlist added under several IDs.
 */
class SyncMergeFuzzTest {
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
                    items = List(fuzz.int(3)) { SyncListItem(playlistOfRecords(), "movie", fuzz.pick("m1", "m2"), title = title()) },
                    updatedAt = time,
                )
            }
        }

        private val keys = playlistIds.map { "playlist:$it" } + favorites.values.map { it.first().key } +
            progress.values.map { it.first().key } + listOf("list:l1", "list:l2")
        private val deletions = keys.associateWith { versions { time -> time } }

        /** Of each record, nothing or one of its versions; the ID in the case the device writes it. */
        private fun <T> held(versions: Collection<List<T>>, percent: Int): List<T> =
            versions.filter { fuzz.chance(percent) }.map { fuzz.pick(it) }

        fun snapshot(device: String) = SyncSnapshot(
            device = device,
            playlists = held(playlists.values, 50).map { it.copy(id = id(it.id)) },
            favorites = held(favorites.values, 25).map { it.copy(playlist = id(it.playlist)) },
            progress = held(progress.values, 25).map { it.copy(playlist = id(it.playlist)) },
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
     * One record changed on two devices in the same millisecond: when the tie strings are equal too (a favourite's
     * image, a progress's duration, a list's item titles differ), the first record met wins, so each device keeps its
     * own; and a list's tie string counts the items of deleted playlists, which an earlier merge may have dropped.
     */
    @Ignore("waits for a total order of tied records and list ties without deleted items: docs/SYNC.md, Kotlin and Swift together")
    @Test
    fun twoDevicesAgreeOnChangesMadeInTheSameMillisecond() = checkTwoDevices(simultaneousEdits = true, cases = 3_000)

    @Ignore("waits for a total order of tied records and list ties without deleted items: docs/SYNC.md, Kotlin and Swift together")
    @Test
    fun threeDevicesAgreeOnChangesMadeInTheSameMillisecond() = checkThreeDevices(simultaneousEdits = true, cases = 3_000)
}
