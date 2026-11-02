package com.markedoe.ondo.core.sync

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hybrid logical clock of sync (docs/SYNC.md, "Clocks") on devices whose clocks are wrong, with sessions whose
 * latency varies: no time is ever moved, nothing loops and nothing is lost. The same cases as the Swift
 * `SyncClockTests`.
 */
class SyncClockTest {
    // The clock

    @Test
    fun aStampIsLaterThanEverythingGivenOrSeen() {
        val clock = SyncClock()
        assertEquals(START, clock.stamp(START))
        assertEquals("the same millisecond: one later", START + 1, clock.stamp(START))
        // A wall clock gone back (a box reset to 1970): still after what it gave.
        assertEquals(START + 2, clock.stamp(5_000))
        // A time received, later than the clock: the next stamp follows it.
        clock.observe(START + HOUR, START)
        assertEquals(START + HOUR + 1, clock.stamp(START + 10))
        assertEquals("now never goes back", START + HOUR + 1, clock.now(START))
        assertEquals(START + 2 * HOUR, clock.now(START + 2 * HOUR))
    }

    @Test
    fun aTimeMoreThanADayAheadIsNotTakenIn() {
        val clock = SyncClock(START)
        clock.observe(START + DAY + 1, START)
        assertEquals(START, clock.last)
        clock.observe(START + DAY, START)
        assertEquals("a day ahead at most", START + DAY, clock.last)
        // A clock that is not set (1970) has no idea of the time: it takes everything in.
        val unset = SyncClock()
        unset.observe(START + 365 * DAY, 1_000)
        assertEquals(START + 365 * DAY, unset.last)
        // At the ends of the range: no overflow.
        assertEquals(Long.MAX_VALUE, SyncClock(Long.MAX_VALUE).stamp(Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, SyncClock.latestAccepted(Long.MAX_VALUE))
    }

    @Test
    fun theSendersClockFieldIsIgnoredAndTimesAreNeverMoved() {
        for (skew in listOf(2 * HOUR, -2 * HOUR, -START)) {
            val sent = SyncSnapshot(
                device = "box",
                favorites = listOf(SyncFavorite("p", "movie", "m1", "Glass City", addedAt = START + skew - 60_000)),
                people = listOf(SyncPerson("Tom", addedAt = START + skew)),
                deleted = mapOf("favorite:p|movie|m2" to START + skew - 5),
                clock = START + skew,
            )
            assertEquals("skew $skew", sent, sent.withoutRecordsAhead(START))
        }
    }

    @Test
    fun whatIsDatedMoreThanADayAheadOfTheReceiverIsLeftOut() {
        val ahead = START + SyncClock.MAX_AHEAD_MS + 1
        val near = START + SyncClock.MAX_AHEAD_MS
        val snapshot = SyncSnapshot(
            device = "box",
            playlists = listOf(
                SyncPlaylist("p", "Home", SyncPlaylist.M3U, "http://example.com/a.m3u", addedAt = 1, updatedAt = ahead),
                SyncPlaylist("q", "News", SyncPlaylist.M3U, "http://example.com/b.m3u", addedAt = 1, updatedAt = near),
            ),
            favorites = listOf(SyncFavorite("p", "movie", "m1", "Glass City", addedAt = ahead)),
            progress = listOf(SyncProgress("p", "movie", "m1", title = "Glass City", position = 1.0, duration = 2.0, watchedAt = near)),
            lists = listOf(
                SyncList("l", "L", updatedAt = ahead),
                SyncList("k", "K", items = listOf(SyncListItem("p", "movie", "m1", "T", addedAt = ahead)), updatedAt = near),
            ),
            people = listOf(SyncPerson("Tom", addedAt = ahead), SyncPerson("Ada", addedAt = START)),
            keys = listOf(SyncKey(SyncKey.TMDB, "k", updatedAt = ahead)),
            deleted = mapOf("favorite:p|movie|m2" to ahead, "favorite:p|movie|m3" to 5L),
        )
        val received = snapshot.withoutRecordsAhead(START)
        assertEquals(listOf("q"), received.playlists.map { it.id })
        assertEquals("a day ahead at most: kept as it is", listOf(near), received.progress.map { it.watchedAt })
        assertEquals(listOf("Ada"), received.people.map { it.name })
        assertEquals(listOf("k"), received.lists.map { it.id })
        assertEquals(emptyList<SyncListItem>(), received.lists.single().items)
        assertTrue(received.favorites.isEmpty() && received.keys.isEmpty())
        assertEquals(mapOf("favorite:p|movie|m3" to 5L), received.deleted)
        assertEquals("a clock not set leaves nothing out", snapshot, snapshot.withoutRecordsAhead(1_000))
        assertEquals(mapOf("k" to Long.MAX_VALUE), SyncSnapshot(device = "box", deleted = mapOf("k" to Long.MAX_VALUE)).withoutRecordsAhead(Long.MAX_VALUE - 1).deleted)
    }

    // Simulated devices

    /** A device: its wall clock ([offset] from the true time), its hybrid clock and its data. */
    private class Device(val name: String, var offset: Long) {
        val clock = SyncClock()
        var data = SyncSnapshot(device = name)

        fun wall(now: Long) = now + offset
    }

    /** Each removal of a record the device held: its key, its time, and the time of the record it removed. */
    private data class Removal(val key: String, val at: Long, val removed: Long)

    /** Devices that edit and sync, and what every edit gave: each time given, and each edit as a snapshot of its own. */
    private class World(val devices: List<Device>, seed: Long) {
        var now = START
        val random = Random(seed)
        val made = HashSet<Long>()
        val edits = mutableListOf<SyncSnapshot>()
        /** The latest time of each record seen on each device (by device, then record): it never goes back. */
        private val stored = List(devices.size) { HashMap<String, Long>() }
        val removals = mutableListOf<Removal>()
        private val positions = List(devices.size) { HashMap<String, Double>() }
        private val lastWatcher = HashMap<String, Int>()
        private val items = listOf("m1", "m2", "m3", "m4")

        fun int(bound: Int) = random.nextInt(bound)

        private fun chance(percent: Int) = random.nextInt(100) < percent

        private fun <T> pick(values: List<T>) = values[random.nextInt(values.size)]

        /** The expected data: every edit merged. */
        val expected: SyncSnapshot get() = edits.fold(SyncSnapshot(device = "expected"), SyncMerge::merge)

        /** A change made on [index] now, dated by its clock and kept there. */
        fun edit(index: Int, make: (Long, SyncSnapshot) -> SyncSnapshot): Long {
            val device = devices[index]
            val stamp = device.clock.stamp(device.wall(now))
            made += stamp
            val edit = make(stamp, device.data)
            edits += edit
            device.data = SyncMerge.merge(device.data, edit)
            check(index)
            // The change shows at once on the device where it is made: its time is later than all it holds.
            val held = records(device.data)
            for ((key, time) in records(edit).filter { it.second == stamp }) {
                assertTrue("${device.name}: its own change $key lost at once", (key to time) in held)
                if (key.startsWith("deleted ")) {
                    val removed = key.removePrefix("deleted ")
                    assertFalse("${device.name}: $removed still there after its removal", held.any { it.first == removed })
                }
            }
            return stamp
        }

        /** A random change on a random device: My List, a person, a list, or where a film was left. */
        fun randomEdit() {
            val index = int(devices.size)
            val item = pick(items)
            when (int(5)) {
                0 -> {
                    val key = "favorite:p|movie|$item"
                    val held = devices[index].data.favorites.firstOrNull { it.key == key }
                    if (held != null) {
                        val at = edit(index) { stamp, _ -> SyncSnapshot(device = "edit", deleted = mapOf(key to stamp)) }
                        removals += Removal(key, at, held.addedAt)
                    } else {
                        val title = pick(listOf("Glass City", "Quiet Water"))
                        edit(index) { stamp, _ -> SyncSnapshot(device = "edit", favorites = listOf(SyncFavorite("p", "movie", item, title, addedAt = stamp))) }
                    }
                }
                1 -> {
                    val name = pick(listOf("Ada", "Bea"))
                    val held = devices[index].data.people.firstOrNull { it.name == name }
                    if (held != null) {
                        val at = edit(index) { stamp, _ -> SyncSnapshot(device = "edit", deleted = mapOf("person:${name.lowercase()}" to stamp)) }
                        removals += Removal("person:${name.lowercase()}", at, held.addedAt)
                    } else {
                        edit(index) { stamp, _ -> SyncSnapshot(device = "edit", people = listOf(SyncPerson(name, addedAt = stamp))) }
                    }
                }
                2 -> {
                    val rename = chance(30)
                    val name = pick(listOf("Weekend", "Kids"))
                    edit(index) { stamp, data ->
                        var list = (data.lists.firstOrNull { it.id == "l" } ?: SyncList("l", name, updatedAt = stamp, renamedAt = stamp, orderedAt = stamp))
                            .copy(updatedAt = stamp, order = null)
                        val deleted = HashMap<String, Long>()
                        val at = list.items.indexOfFirst { it.item == item }
                        when {
                            rename -> list = list.copy(name = name, renamedAt = stamp)
                            at >= 0 -> {
                                deleted[list.itemKey(list.items[at].key)] = stamp
                                list = list.copy(items = list.items.filterIndexed { position, _ -> position != at })
                            }
                            else -> list = list.copy(items = list.items + SyncListItem("p", "movie", item, item, addedAt = stamp))
                        }
                        SyncSnapshot(device = "edit", lists = listOf(list), deleted = deleted)
                    }
                }
                else -> watch(index, item)
            }
        }

        /**
         * The viewer goes on with [item] on [index]: that device first syncs with the one it was watched on last, as the
         * app does at launch and every minute, so it carries on from there.
         */
        fun watch(index: Int, item: String) {
            lastWatcher[item]?.takeIf { it != index }?.let { session(index, it) }
            val key = "progress:p|movie|$item|"
            val position = (devices[index].data.progress.firstOrNull { it.key == key }?.position ?: 0.0) + 10 + int(300)
            edit(index) { stamp, _ ->
                SyncSnapshot(device = "edit", progress = listOf(SyncProgress("p", "movie", item, title = item, position = position, duration = 100_000.0, watchedAt = stamp)))
            }
            lastWatcher[item] = index
        }

        /**
         * One session: the client's snapshot reaches the server after some latency, the server keeps the merge and
         * answers with it, and the answer reaches the client after some more. Each side leaves out what is dated more
         * than a day after its own clock, takes the rest into its clock, and merges.
         */
        fun session(client: Int, server: Int) {
            val offer = devices[client].data
            now += 20 + int(481)
            receive(offer, server)
            val answer = devices[server].data
            now += 20 + int(481)
            receive(answer, client)
        }

        private fun receive(snapshot: SyncSnapshot, index: Int) {
            val device = devices[index]
            val wall = device.wall(now)
            val received = snapshot.withoutRecordsAhead(wall)
            device.clock.observe(received, wall)
            device.data = SyncMerge.merge(device.data, received)
            check(index)
        }

        /** No time on the device is one an edit did not give, none goes back, and no position goes back. */
        private fun check(index: Int) {
            val device = devices[index]
            for ((key, time) in records(device.data)) {
                assertTrue("${device.name}: $key at $time, a time no edit gave", time in made)
                stored[index][key]?.let { before -> assertTrue("${device.name}: $key went back", time >= before) }
                stored[index][key] = time
            }
            for (progress in device.data.progress) {
                positions[index][progress.key]?.let { before -> assertTrue("${device.name}: ${progress.key} went back", progress.position >= before) }
                positions[index][progress.key] = progress.position
            }
        }

        fun digest(index: Int) = devices[index].data.contentDigest()

        /** Sessions between every pair, both ways, until every device has everything. */
        fun settle() {
            repeat(3) {
                for (client in devices.indices) {
                    for (server in devices.indices) {
                        if (server == client) continue
                        now += MINUTE
                        session(client, server)
                    }
                }
            }
        }
    }

    /**
     * Three devices two hours apart (behind, on time, ahead) and a box whose clock reads 1970, 500 sessions with latency
     * from 20 to 500 ms and random edits: no time moves, nothing is lost, no position goes back, a removal made after an
     * add wins everywhere, and once the edits stop the data stays the same at every session.
     */
    @Test
    fun wrongClocksAndVaryingLatencyMoveNoTimeAndLoseNothing() {
        for (seed in 0 until 4L) {
            val world = World(
                listOf(Device("behind", -2 * HOUR), Device("on-time", 0), Device("ahead", 2 * HOUR), Device("1970", 1_000 - START)),
                seed,
            )
            repeat(500) {
                world.now += MINUTE - 5_000 + world.int(10_000)
                repeat(world.int(3)) { world.randomEdit() }
                val client = world.int(4)
                val server = (client + 1 + world.int(3)) % 4
                world.session(client, server)
            }
            world.settle()
            val expected = world.expected.contentDigest()
            for (index in world.devices.indices) {
                assertEquals("seed $seed: ${world.devices[index].name} has every change, and only those", expected, world.digest(index))
            }
            // A removal made on a device that had the record (after its add, in causal order) is dated after it, and the
            // record it removed, or an older one, is back nowhere (only a later add brings the key back).
            for (removal in world.removals) {
                assertTrue("seed $seed: ${removal.key} removed before its add", removal.at > removal.removed)
                for (device in world.devices) {
                    assertFalse("seed $seed: ${removal.key} is back on ${device.name}", records(device.data).any { it.first == removal.key && it.second <= removal.at })
                }
            }
            // Quiet: sessions change nothing on either side.
            repeat(100) {
                world.now += MINUTE
                val client = world.int(4)
                val server = (client + 1 + world.int(3)) % 4
                val before = world.digest(client) to world.digest(server)
                world.session(client, server)
                assertEquals("seed $seed: a quiet session changed the client", before.first, world.digest(client))
                assertEquals("seed $seed: a quiet session changed the server", before.second, world.digest(server))
            }
            assertTrue(world.edits.size > 300)
        }
    }

    /**
     * A removal made on a box hours behind, after it saw the add made on a box hours ahead, wins on both: the box behind
     * dates it after the add it saw.
     */
    @Test
    fun aRemovalOnABoxBehindAfterAnAddOnABoxAheadWins() {
        val world = World(listOf(Device("ahead", 2 * HOUR), Device("behind", -2 * HOUR), Device("1970", 1_000 - START)), 7)
        val key = "favorite:p|movie|m1"
        val add = { stamp: Long, _: SyncSnapshot -> SyncSnapshot(device = "edit", favorites = listOf(SyncFavorite("p", "movie", "m1", "T", addedAt = stamp))) }
        world.edit(0, add)
        for (remover in listOf(1, 2)) {
            world.now += MINUTE
            world.session(remover, 0)
            assertTrue(world.devices[remover].data.favorites.any { it.key == key })
            world.edit(remover) { stamp, _ -> SyncSnapshot(device = "edit", deleted = mapOf(key to stamp)) }
            world.settle()
            for (device in world.devices) assertFalse("back on ${device.name}", device.data.favorites.any { it.key == key })
            world.edit(0, add)
        }
    }

    /**
     * The review's probe: a client 5 minutes and 200 ms ahead (and one 10 minutes behind), 500 sessions with latency from
     * 20 to 500 ms: after the first session no time moves and nothing changes on either side.
     */
    @Test
    fun skewedClientsWithVaryingLatencySettleAtOnce() {
        for (offset in listOf(5 * MINUTE + 200, -10 * MINUTE)) {
            val world = World(listOf(Device("client", offset), Device("server", 0)), 3)
            world.edit(0) { stamp, _ -> SyncSnapshot(device = "edit", favorites = listOf(SyncFavorite("p", "movie", "m1", "T", addedAt = stamp))) }
            world.edit(1) { stamp, _ ->
                SyncSnapshot(device = "edit", progress = listOf(SyncProgress("p", "movie", "m2", title = "T", position = 600.0, duration = 6_000.0, watchedAt = stamp)))
            }
            world.session(0, 1)
            val settled = world.digest(0) to world.digest(1)
            repeat(500) { round ->
                world.now += MINUTE
                world.session(0, 1)
                assertEquals("offset $offset, round $round", settled.first, world.digest(0))
                assertEquals("offset $offset, round $round", settled.second, world.digest(1))
            }
            assertEquals(world.expected.contentDigest(), world.digest(0))
        }
    }

    /**
     * A box a day and an hour ahead: the others leave out what it dates (and their clocks do not follow it), while it
     * gets theirs. Once its clock is right again, its changes reach them as soon as their time is within a day.
     */
    @Test
    fun aDeviceMoreThanADayAheadIsLeftOutUntilItsTimesComeWithin() {
        val world = World(listOf(Device("tv", 0), Device("phone", 0), Device("ahead", DAY + HOUR)), 11)
        world.edit(2) { stamp, _ -> SyncSnapshot(device = "edit", favorites = listOf(SyncFavorite("p", "movie", "m1", "T", addedAt = stamp))) }
        world.edit(2) { stamp, _ -> SyncSnapshot(device = "edit", people = listOf(SyncPerson("Ada", addedAt = stamp))) }
        world.edit(0) { stamp, _ -> SyncSnapshot(device = "edit", favorites = listOf(SyncFavorite("p", "movie", "m2", "T", addedAt = stamp))) }
        val aheadStamps = records(world.devices[2].data).map { it.second }
        repeat(10) {
            world.now += MINUTE
            world.session(2, 0)
            world.session(1, 0)
            world.session(2, 1)
        }
        for (device in world.devices.take(2)) {
            assertEquals(device.name, listOf("m2"), device.data.favorites.map { it.item })
            assertTrue(device.name, device.data.people.isEmpty())
            assertTrue("${device.name}'s clock did not follow", device.clock.last < START + HOUR)
            assertTrue(device.clock.last < aheadStamps.min())
        }
        assertEquals("it gets the others' changes", setOf("m1", "m2"), world.devices[2].data.favorites.mapTo(HashSet()) { it.item })

        // Its clock is put right; a change it makes then is still dated after its own.
        world.devices[2].offset = 0
        world.edit(2) { stamp, _ -> SyncSnapshot(device = "edit", favorites = listOf(SyncFavorite("p", "movie", "m3", "T", addedAt = stamp))) }
        // Two hours of sessions every minute: past the first hour, its times are within a day of the others' clocks.
        repeat(120) {
            world.now += MINUTE
            world.session(2, 0)
            world.session(1, 0)
        }
        val expected = world.expected.contentDigest()
        for (index in world.devices.indices) assertEquals(world.devices[index].name, expected, world.digest(index))
        assertEquals(setOf("m1", "m2", "m3"), world.devices[1].data.favorites.mapTo(HashSet()) { it.item })
    }

    private companion object {
        const val START = 1_760_000_000_000L
        const val MINUTE = 60_000L
        const val HOUR = 3_600_000L
        const val DAY = 86_400_000L

        /** Each time of a snapshot, by what it dates. */
        fun records(data: SyncSnapshot): List<Pair<String, Long>> =
            data.favorites.map { it.key to it.addedAt } + data.progress.map { it.key to it.watchedAt } +
                data.people.map { it.key to it.addedAt } + data.deleted.map { "deleted ${it.key}" to it.value } +
                data.lists.flatMap { list ->
                    listOf(list.key to list.updatedAt, "name ${list.key}" to list.nameTime, "order ${list.key}" to list.orderTime) +
                        list.items.map { list.itemKey(it.key) to (it.addedAt ?: list.updatedAt) }
                }
    }
}
