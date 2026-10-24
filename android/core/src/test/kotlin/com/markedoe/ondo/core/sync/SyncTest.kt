package com.markedoe.ondo.core.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
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

    @Test
    fun aSnapshotOfAnotherKindOrVersionIsRefused() {
        assertNull(SyncSnapshot.decode("""{"type":"other","version":1,"device":"a"}""".encodeToByteArray()))
        assertNull(SyncSnapshot.decode("""{"type":"ondo-sync","version":2,"device":"a"}""".encodeToByteArray()))
        assertNull(SyncSnapshot.decode("not json".encodeToByteArray()))
        val snapshot = SyncMerge.merge(fixture("a.json"), fixture("b.json"))
        assertEquals(snapshot, SyncSnapshot.decode(snapshot.encoded()))
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
