package com.markedoe.ondo.core.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertFailsWith
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Local-network sync (docs/SYNC.md): the shared examples, the merge rules, local addresses and frames. */
class SyncTest {
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

    @Test
    fun whatIsDatedAheadOfTheReceivingClockIsLeftOut() {
        val now = 1_000_000L
        val ahead = now + SyncSnapshot.FUTURE_MARGIN_MS + 1
        val near = now + SyncSnapshot.FUTURE_MARGIN_MS
        val snapshot = SyncSnapshot(
            device = "box",
            playlists = listOf(
                SyncPlaylist("p", "Home", SyncPlaylist.M3U, "http://example.com/a.m3u", addedAt = 1, updatedAt = ahead),
                SyncPlaylist("q", "News", SyncPlaylist.M3U, "http://example.com/b.m3u", addedAt = 1, updatedAt = near),
            ),
            favorites = listOf(SyncFavorite("p", "movie", "m1", "Glass City", addedAt = ahead)),
            progress = listOf(SyncProgress("p", "movie", "m1", title = "Glass City", position = 1.0, duration = 2.0, watchedAt = near)),
            lists = listOf(SyncList("l", "L", updatedAt = ahead)),
            people = listOf(SyncPerson("Tom", addedAt = ahead), SyncPerson("Ada", addedAt = now)),
            keys = listOf(SyncKey(SyncKey.TMDB, "k", updatedAt = ahead)),
            deleted = mapOf("favorite:p|movie|m2" to ahead, "favorite:p|movie|m3" to 5L),
        ).withoutFutureRecords(now)
        // Within the margin: kept as it is.
        assertEquals(listOf("q"), snapshot.playlists.map { it.id })
        assertEquals(listOf(near), snapshot.progress.map { it.watchedAt })
        assertEquals(listOf("Ada"), snapshot.people.map { it.name })
        assertTrue(snapshot.favorites.isEmpty() && snapshot.lists.isEmpty() && snapshot.keys.isEmpty())
        assertEquals(mapOf("favorite:p|movie|m3" to 5L), snapshot.deleted)
        assertEquals(mapOf("k" to Long.MAX_VALUE), SyncSnapshot(device = "box", deleted = mapOf("k" to Long.MAX_VALUE)).withoutFutureRecords(Long.MAX_VALUE - 1).deleted)
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
        assertEquals(listOf("m4", "m3", "m1"), SyncMerge.merge(merged, SyncSnapshot(device = "b", lists = listOf(back))).lists[0].items.map { it.item })
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
    fun timesMoveByTheSendersClockDifference() {
        val now = 1_760_000_000_000L
        val hours = 2 * 3_600_000L
        for (skew in listOf(hours, -hours)) {
            val sent = SyncSnapshot(
                device = "box",
                favorites = listOf(SyncFavorite("p", "movie", "m1", "Glass City", addedAt = now + skew - 60_000)),
                lists = listOf(SyncList("l", "L", people = listOf(SyncPerson("Ada", addedAt = now + skew - 1)), updatedAt = now + skew - 2)),
                people = listOf(SyncPerson("Tom", addedAt = now + skew)),
                deleted = mapOf("favorite:p|movie|m2" to now + skew - 5),
                clock = now + skew,
            ).onReceiverClock(now)
            assertEquals("skew $skew", listOf(now - 60_000), sent.favorites.map { it.addedAt })
            assertEquals(listOf(now - 2, now - 1), sent.lists.map { it.updatedAt } + sent.lists.flatMap { list -> list.people.map { it.addedAt } })
            assertEquals(listOf(now), sent.people.map { it.addedAt })
            assertEquals(mapOf("favorite:p|movie|m2" to now - 5), sent.deleted)
        }
        // Within the margin, or from a version without a clock: as sent.
        val close = SyncSnapshot(device = "box", people = listOf(SyncPerson("Tom", addedAt = now + 60_000)), clock = now + 60_000)
        assertEquals(listOf(now + 60_000), close.onReceiverClock(now).people.map { it.addedAt })
        val old = SyncSnapshot(device = "box", people = listOf(SyncPerson("Tom", addedAt = now - 5)))
        assertEquals(listOf(now - 5), old.onReceiverClock(now).people.map { it.addedAt })
        // A clock gone back after a time stamped right: that time is kept rather than moved into the future.
        val reset = SyncSnapshot(device = "box", people = listOf(SyncPerson("Tom", addedAt = now - 1_000), SyncPerson("Ada", addedAt = 10)), clock = 0)
        assertEquals(listOf(now - 1_000, now + 10), reset.onReceiverClock(now).people.map { it.addedAt })
        // At the ends of the range: no overflow, and still ahead, so left out.
        assertEquals(emptyMap<String, Long>(), SyncSnapshot(device = "box", deleted = mapOf("k" to Long.MAX_VALUE), clock = Long.MIN_VALUE).onReceiverClock(now).deleted)
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
