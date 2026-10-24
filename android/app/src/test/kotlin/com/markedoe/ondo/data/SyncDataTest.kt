package com.markedoe.ondo.data

import com.markedoe.ondo.core.sync.SyncFavorite
import com.markedoe.ondo.core.sync.SyncList
import com.markedoe.ondo.core.sync.SyncListItem
import com.markedoe.ondo.core.sync.SyncPlaylist
import com.markedoe.ondo.core.sync.SyncProgress
import com.markedoe.ondo.core.sync.SyncSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Sync between two devices' repositories (docs/SYNC.md): what arrives, what follows, what stays on each device. */
class SyncDataTest {
    @get:Rule val folder = TemporaryFolder()

    private val list = "http://provider.example.com/list.m3u"
    private val catalogue = """
        #EXTM3U
        #EXTINF:-1 tvg-id="news.test" group-title="News",Test News
        http://provider.example.com/news.m3u8
        #EXTINF:5400 group-title="Movies | Drama",Quiet Water (2021)
        http://provider.example.com/quiet.mp4
    """.trimIndent()

    private var time = 1_760_000_000_000L

    private fun TestScope.device(name: String) =
        OndoRepository(AppStore(folder.newFolder(name)), FakeHttp(mapOf(list to catalogue)), backgroundScope, now = { time }, io = StandardTestDispatcher(testScheduler))

    private suspend fun OndoRepository.ready(): OndoRepository {
        bootstrap()
        state.first { it.isReady }
        return this
    }

    /** One session: [client] sends its snapshot, [server] merges it and answers, [client] keeps the answer. */
    private fun session(client: OndoRepository, server: OndoRepository) {
        val answer = requireNotNull(server.mergeSync(requireNotNull(client.syncSnapshot("client")), "server"))
        client.mergeSync(answer, "client")
    }

    private fun OndoState.favorite(itemId: String) = library.isFavorite(activePlaylistId.orEmpty(), ItemKind.MOVIE, itemId)

    @Test
    fun aPlaylistAndItsLibraryReachTheOtherDeviceAndDeletionsFollow() = runTest {
        val tv = device("tv").ready()
        val phone = device("phone").ready()
        tv.addPlaylist("Home", list)
        val movie = tv.state.value.content.movies.single()
        time += 1_000
        tv.toggleFavorite(ItemKind.MOVIE, movie.id, movie.name, null)
        tv.recordProgress(tv.playRequest(movie)!!.progressKey!!, 600.0, 5_400.0)
        val weekend = tv.createCollection("Weekend")
        tv.toggleInCollection(weekend, ItemKind.MOVIE, movie.id, movie.name, null)

        session(phone, tv)
        val arrived = phone.state.first { it.content.movies.isNotEmpty() }
        assertEquals("Home", arrived.activePlaylist?.name)
        assertEquals(tv.state.value.activePlaylistId, arrived.activePlaylistId)
        assertTrue(arrived.favorite(movie.id))
        assertEquals(600.0, arrived.library.continueWatching(arrived.activePlaylistId!!).single().position, 0.0)
        assertEquals(listOf("Weekend"), arrived.library.collections.map { it.name })
        assertEquals(listOf(movie.id), arrived.library.collections.single().items.map { it.itemId })

        // Taken out of My List on the phone: gone from the TV too. Put back on the TV later: back on both.
        time += 1_000
        phone.toggleFavorite(ItemKind.MOVIE, movie.id, movie.name, null)
        session(phone, tv)
        assertFalse(tv.state.value.favorite(movie.id))
        time += 1_000
        tv.toggleFavorite(ItemKind.MOVIE, movie.id, movie.name, null)
        session(phone, tv)
        assertTrue(phone.state.value.favorite(movie.id))

        // Progress made on the phone reaches the TV; a collection deleted there goes too.
        time += 1_000
        phone.recordProgress(phone.playRequest(movie)!!.progressKey!!, 1_800.0, 5_400.0)
        phone.deleteCollection(phone.state.value.library.collections.single().id)
        session(phone, tv)
        assertEquals(1_800.0, tv.state.value.library.continueWatching(tv.state.value.activePlaylistId!!).single().position, 0.0)
        assertTrue(tv.state.value.library.collections.isEmpty())

        // The playlist deleted on the TV: gone from the phone, with what belonged to it.
        time += 1_000
        tv.delete(tv.state.value.activePlaylistId!!)
        session(phone, tv)
        assertTrue(phone.state.value.playlists.isEmpty())
        assertTrue(phone.state.value.library.favorites.isEmpty())
        assertTrue(phone.state.value.library.history.isEmpty())
        assertNull(phone.state.value.activePlaylist)
        // Nothing comes back at the next session.
        session(phone, tv)
        assertTrue(phone.state.value.playlists.isEmpty() && tv.state.value.playlists.isEmpty())
    }

    @Test
    fun theSamePlaylistAddedOnBothDevicesBecomesOne() = runTest {
        val tv = device("tv").ready()
        val phone = device("phone").ready()
        tv.addPlaylist("Home", list)
        time += 1_000
        phone.addPlaylist("Living room", list)
        val movie = phone.state.value.content.movies.single()
        phone.toggleFavorite(ItemKind.MOVIE, movie.id, movie.name, null)

        session(phone, tv)
        val kept = tv.state.value.playlists.single()
        assertEquals("Home", kept.name)
        val onPhone = phone.state.first { it.activePlaylistId == kept.id && it.content.movies.isNotEmpty() }
        assertEquals(listOf(kept.id), onPhone.playlists.map { it.id })
        // The phone's favourite followed its playlist to the one kept, on both devices.
        assertTrue(onPhone.favorite(movie.id))
        assertTrue(tv.state.value.favorite(movie.id))
    }

    @Test
    fun watchedChannelsAndFilePlaylistsStayOnTheDevice() {
        val online = SavedPlaylist("P1", "Home", "http://provider.example.com/list.m3u", addedAt = 1)
        val file = SavedPlaylist("f1", "From a file", "content://media/list.m3u", addedAt = 2)
        val state = OndoState(
            isReady = true,
            playlists = listOf(online, file),
            library = LibraryState(
                favorites = listOf(Favorite("P1", ItemKind.MOVIE, "m1", "Quiet Water", addedAt = 5), Favorite("f1", ItemKind.MOVIE, "m2", "Long Road", addedAt = 6)),
                history = listOf(
                    WatchEntry("P1", ItemKind.CHANNEL, "c1", title = "Test News", lastWatchedAt = 7),
                    WatchEntry("P1", ItemKind.MOVIE, "m1", title = "Quiet Water", position = 60.0, duration = 5_400.0, lastWatchedAt = 8),
                ),
            ),
        )
        val snapshot = SyncData.snapshot(state, "device", "TV", now = 10)
        assertEquals(listOf("p1"), snapshot.playlists.map { it.id })
        assertEquals(listOf("m1"), snapshot.favorites.map { it.item })
        assertEquals(listOf("m1"), snapshot.progress.map { it.item })

        // Applying a snapshot that knows none of it keeps the file playlist's records and the channels watched.
        val applied = SyncData.apply(state, SyncSnapshot(device = "other", playlists = snapshot.playlists, favorites = snapshot.favorites, progress = snapshot.progress))
        assertEquals(listOf("P1", "f1"), applied.playlists.map { it.id })
        assertEquals(setOf("m1", "m2"), applied.library.favorites.map { it.itemId }.toSet())
        assertEquals(setOf("c1", "m1"), applied.library.history.map { it.itemId }.toSet())
        // The device's own playlist ID keeps its case.
        assertTrue(applied.library.favorites.any { it.playlistId == "P1" && it.itemId == "m1" })
    }

    /**
     * A snapshot of many lists, or of many records of playlists this device does not know, is applied and sent again
     * in one pass: sync runs on the main thread, and the next session works on everything the first one kept.
     */
    @Test(timeout = 5_000)
    fun manyListsAreAppliedAgainWithoutAQuadraticPass() {
        val lists = (0 until 40_000).map { SyncList(java.util.UUID(0, it.toLong()).toString(), "List $it", updatedAt = time) }
        val merged = SyncSnapshot(device = "other", lists = lists)
        val kept = SyncData.apply(OndoState(isReady = true), merged).library
        // The next session: the same lists, onto the device that kept them.
        assertEquals(lists.size, SyncData.apply(OndoState(isReady = true, library = kept), merged).library.collections.size)
    }

    @Test(timeout = 5_000)
    fun manyRecordsOfUnknownPlaylistsAreSentWithoutAQuadraticPass() {
        val playlists = (0 until 30_000).map { SavedPlaylist(java.util.UUID(0, it.toLong()).toString(), "P$it", "http://provider.example.com/$it.m3u", addedAt = 1) }
        val favorites = (0 until 30_000).map { Favorite(java.util.UUID(1, it.toLong()).toString(), ItemKind.MOVIE, "m$it", "Movie $it", addedAt = 2) }
        val state = OndoState(isReady = true, playlists = playlists, library = LibraryState(favorites = favorites))
        assertEquals(favorites.size, SyncData.snapshot(state, "device", "TV", now = 10).favorites.size)
    }

    /** What another device sends that this one cannot use changes nothing and breaks nothing. */
    @Test
    fun anOddSnapshotIsKeptOutWithoutHarm() = runTest {
        val tv = device("tv").ready()
        tv.addPlaylist("Home", list)
        val home = tv.state.value.playlists.single()
        val odd = SyncSnapshot(
            device = "other",
            playlists = listOf(
                SyncPlaylist("", "No ID", SyncPlaylist.M3U, "http://provider.example.com/a.m3u", addedAt = 1, updatedAt = 1),
                SyncPlaylist("p2", "No address", SyncPlaylist.M3U, " ", addedAt = 1, updatedAt = 1),
                SyncPlaylist("p3", "Another kind", "stalker", "http://provider.example.com", addedAt = 1, updatedAt = 1),
                SyncPlaylist("p4", "No login", SyncPlaylist.XTREAM, "http://provider.example.com", addedAt = 1, updatedAt = 1),
                // A file of this device as a playlist: file playlists never sync, and the app would read it from disk.
                SyncPlaylist("p5", "A file here", SyncPlaylist.M3U, "file:///data/data/com.markedoe.ondo/files/ondo/library.json", addedAt = 1, updatedAt = 1),
                SyncPlaylist("p6", "Another file", SyncPlaylist.M3U, " FILE:/dev/zero", addedAt = 1, updatedAt = 1),
                SyncPlaylist("p7", "A document", SyncPlaylist.M3U, "content://media/list.m3u", addedAt = 1, updatedAt = 1),
                SyncPlaylist("p8", "A path", SyncPlaylist.M3U, "/sdcard/list.m3u", addedAt = 1, updatedAt = 1),
                // The playlist in use, renamed into another kind: kept as it is.
                SyncPlaylist(home.id, "Changed", "other", home.url, addedAt = home.addedAt, updatedAt = time + 1),
            ),
            favorites = listOf(SyncFavorite("nobody", "podcast", "x", "", addedAt = 1)),
            progress = listOf(SyncProgress("nobody", "live", "c", title = "", position = -1.0, duration = 0.0, watchedAt = 1)),
            lists = listOf(SyncList("l1", "", items = listOf(SyncListItem("nobody", "radio", "m", "")), updatedAt = 1)),
            deleted = mapOf("playlist:nobody" to 5, "something:else" to -1),
        )
        assertNotNull(tv.mergeSync(odd, "tv"))
        val state = tv.state.value
        assertEquals(listOf(home), state.playlists)
        assertEquals("Home", state.activePlaylist?.name)
        assertTrue(state.library.favorites.isEmpty())
        assertTrue(state.library.history.isEmpty())
        assertTrue(state.library.collections.single().items.isEmpty())
    }
}
