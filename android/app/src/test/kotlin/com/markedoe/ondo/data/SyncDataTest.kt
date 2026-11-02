package com.markedoe.ondo.data

import com.markedoe.ondo.core.sync.SyncFavorite
import com.markedoe.ondo.core.sync.SyncList
import com.markedoe.ondo.core.sync.SyncListItem
import com.markedoe.ondo.core.sync.SyncMerge
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

    private val sports = "http://provider.example.com/sports.m3u"

    private var time = 1_760_000_000_000L

    private val films = "http://provider.example.com/films.m3u"
    private val twoFilms = """
        #EXTM3U
        #EXTINF:5400 group-title="Movies",Quiet Water (2021)
        http://provider.example.com/quiet.mp4
        #EXTINF:6000 group-title="Movies",Glass City (2019)
        http://provider.example.com/glass.mp4
    """.trimIndent()

    private fun TestScope.device(name: String, http: FakeHttp = FakeHttp(mapOf(list to catalogue, sports to catalogue, films to twoFilms))) =
        OndoRepository(AppStore(folder.newFolder(name)), http, backgroundScope, now = { time }, io = StandardTestDispatcher(testScheduler))

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

    private fun LibraryState.people() = favorites.filter { it.kind == ItemKind.PERSON }.map { it.title to it.imageUrl }

    private fun UserCollection.people() = items.filter { it.kind == ItemKind.PERSON }.map { it.title to it.imageUrl }

    private fun LibraryState.collection(name: String) = collections.single { it.name == name }

    /** Two Android TV boxes, one the client of the other: people in My List and in collections go both ways. */
    @Test
    fun thePeopleKeptAndTheCollectionsReachTheOtherBoxAndRemovalsFollow() = runTest {
        val living = device("living").ready()
        val bedroom = device("bedroom").ready()
        living.addPlaylist("Home", list)
        val movie = living.state.value.content.movies.single()
        time += 1_000
        living.togglePerson("Ada Example", "https://image.example.com/ada.jpg")
        val directors = living.createCollection("Directors")
        living.toggleInCollection(directors, ItemKind.MOVIE, movie.id, movie.name, null)
        living.togglePersonInCollection(directors, "Ada Example", null)
        val actors = living.createCollection("Actors")
        living.togglePersonInCollection(actors, "Tom Example", "https://image.example.com/tom.jpg")

        session(bedroom, living)
        val arrived = bedroom.state.value.library
        assertEquals(listOf("Ada Example" to "https://image.example.com/ada.jpg"), arrived.people())
        assertEquals(setOf(ItemKind.MOVIE, ItemKind.PERSON), arrived.collection("Directors").items.mapTo(HashSet()) { it.kind })
        assertEquals(listOf("Tom Example" to "https://image.example.com/tom.jpg"), arrived.collection("Actors").people())
        // Nothing changes at the next session, on either box.
        val before = living.state.value.library to bedroom.state.value.library
        session(bedroom, living)
        assertEquals(before, living.state.value.library to bedroom.state.value.library)

        // Taken out on the bedroom box: gone from the living room box too.
        time += 1_000
        bedroom.togglePerson("Ada Example", null)
        bedroom.togglePersonInCollection(actors, "Tom Example", null)
        assertEquals(time, bedroom.state.value.library.syncDeleted["person:ada example"])
        session(bedroom, living)
        assertTrue(living.state.value.library.people().isEmpty())
        assertTrue(living.state.value.library.collection("Actors").items.isEmpty())
        assertEquals(1, living.state.value.library.collection("Directors").items.count { it.kind == ItemKind.PERSON })

        // Kept again on the living room box later: back on both.
        time += 1_000
        living.togglePerson("ADA EXAMPLE", null)
        session(bedroom, living)
        assertEquals(listOf("ADA EXAMPLE" to null), bedroom.state.value.library.people())
    }

    /** A box on an older build sends no people: absence removes nothing, only a dated removal does. */
    @Test
    fun aDeviceThatDoesNotKnowPeopleRemovesNone() = runTest {
        val tv = device("tv").ready()
        tv.addPlaylist("Home", list)
        val movie = tv.state.value.content.movies.single()
        time += 1_000
        tv.togglePerson("Ada Example", null)
        val directors = tv.createCollection("Directors")
        tv.toggleInCollection(directors, ItemKind.MOVIE, movie.id, movie.name, null)
        tv.togglePersonInCollection(directors, "Ada Example", null)
        val kept = tv.state.value.library

        // What an older build answers: the same records without `people`, the list at the same time.
        val sent = requireNotNull(tv.syncSnapshot("tv"))
        val old = sent.copy(device = "old", people = emptyList(), lists = sent.lists.map { it.copy(people = emptyList()) })
        tv.mergeSync(SyncSnapshot.decode(old.encoded().decodeToString().replace(",\"people\":[]", "").encodeToByteArray())!!, "tv")
        assertEquals(kept.people(), tv.state.value.library.people())
        assertEquals(kept.collections, tv.state.value.library.collections)
    }

    /** A change made between the answer arriving and its merge is kept: the merge reads the library as it is then. */
    @Test
    fun aChangeMadeWhileTheAnswerIsOnItsWayIsKept() = runTest {
        val tv = device("tv").ready()
        val phone = device("phone").ready()
        tv.addPlaylist("Home", list)
        val movie = tv.state.value.content.movies.single()
        session(phone, tv)
        phone.state.first { it.content.movies.isNotEmpty() }

        time += 1_000
        val answer = requireNotNull(tv.mergeSync(requireNotNull(phone.syncSnapshot("phone")), "tv"))
        time += 1_000
        phone.toggleFavorite(ItemKind.MOVIE, movie.id, movie.name, null)
        phone.togglePerson("Ada Example", null)
        phone.mergeSync(answer, "phone")
        assertTrue(phone.state.value.favorite(movie.id))
        assertEquals(listOf("Ada Example" to null), phone.state.value.library.people())
    }

    /** Two boxes playing two films, with sessions both ways in between: each position moves only forward. */
    @Test
    fun twoBoxesPlayingTwoFilmsKeepBothPositions() = runTest {
        val living = device("living").ready()
        val bedroom = device("bedroom").ready()
        living.addPlaylist("Films", films)
        session(bedroom, living)
        bedroom.state.first { it.content.movies.size == 2 }
        val (quiet, glass) = living.state.value.content.movies.sortedBy { it.name }.let { it[1] to it[0] }
        val quietKey = requireNotNull(living.playRequest(quiet)?.progressKey)
        val glassKey = requireNotNull(bedroom.playRequest(bedroom.state.value.content.movies.single { it.name == glass.name })?.progressKey)
        fun OndoRepository.position(key: String) = state.value.library.progress(key)?.position

        for (minute in 1..4) {
            time += 30_000
            living.recordProgress(quietKey, minute * 60.0, 5_400.0)
            if (minute % 2 == 0) session(bedroom, living) else session(living, bedroom)
            time += 30_000
            bedroom.recordProgress(glassKey, minute * 90.0, 6_000.0)
            if (minute % 2 == 0) session(living, bedroom) else session(bedroom, living)
            for (box in listOf(living, bedroom)) {
                assertEquals(minute * 60.0, box.position(quietKey)!!, 0.0)
                assertEquals(minute * 90.0, box.position(glassKey)!!, 0.0)
            }
        }
    }

    /**
     * A session while a film plays: the catalogue is not downloaded again, and the position the player writes next
     * (later than the other box's) is the one kept.
     */
    @Test
    fun aSessionDuringPlaybackReloadsNothingAndThePlayerKeepsItsPosition() = runTest {
        val tvHttp = FakeHttp(mapOf(list to catalogue))
        val tv = device("tv", tvHttp).ready()
        val phone = device("phone").ready()
        tv.addPlaylist("Home", list)
        session(phone, tv)
        phone.state.first { it.content.movies.isNotEmpty() }
        val movie = tv.state.value.content.movies.single()
        val key = requireNotNull(tv.playRequest(movie)?.progressKey)
        time += 1_000
        tv.recordProgress(key, 600.0, 5_400.0)

        // The phone moved the same film elsewhere meanwhile.
        time += 1_000
        phone.recordProgress(requireNotNull(phone.playRequest(phone.state.value.content.movies.single())?.progressKey), 1_800.0, 5_400.0)
        val downloads = tvHttp.requests.count { it.first == list }
        val active = tv.state.value.activePlaylistId
        session(phone, tv)
        assertEquals(downloads, tvHttp.requests.count { it.first == list })
        assertEquals(active, tv.state.value.activePlaylistId)

        // The player's next report, later than the phone's, wins on both devices.
        time += 1_000
        tv.recordProgress(key, 610.0, 5_400.0)
        session(phone, tv)
        assertEquals(610.0, tv.state.value.library.progress(key)!!.position, 0.0)
        assertEquals(610.0, phone.state.value.library.continueWatching(phone.state.value.activePlaylistId!!).single().position, 0.0)
    }

    /** A box whose clock runs a day ahead cannot date a removal in the future: it is left out until its time comes. */
    @Test
    fun aClockAheadDoesNotWinOverLaterChanges() = runTest {
        val tv = device("tv").ready()
        tv.addPlaylist("Home", list)
        val movie = tv.state.value.content.movies.single()
        time += 1_000
        tv.toggleFavorite(ItemKind.MOVIE, movie.id, movie.name, null)
        val key = SyncKeys.favorite(tv.state.value.library.favorites.single())
        val ahead = SyncSnapshot(device = "ahead", deleted = mapOf(key to time + 86_400_000L))

        time += 1_000
        tv.mergeSync(ahead, "tv")
        assertTrue(tv.state.value.favorite(movie.id))
        assertNull(tv.state.value.library.syncDeleted[key])
        // Its time come, the removal applies like any other.
        time += 86_400_000L
        tv.mergeSync(ahead, "tv")
        assertFalse(tv.state.value.favorite(movie.id))
    }

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

    /** The playlist in use, added on another device first: the one kept is in use, not another playlist of the device. */
    @Test
    fun thePlaylistInUseStaysInUseWhenTheSameOneReplacesIt() = runTest {
        val tv = device("tv").ready()
        val phone = device("phone").ready()
        tv.addPlaylist("Home", list)
        time += 1_000
        phone.addPlaylist("Sports", sports)
        time += 1_000
        phone.addPlaylist("Living room", list)
        assertEquals("Living room", phone.state.value.activePlaylist?.name)

        session(phone, tv)
        val kept = tv.state.value.playlists.single { it.url == list }
        assertEquals(setOf("Sports", "Home"), phone.state.value.playlists.map { it.name }.toSet())
        assertEquals("Home", phone.state.value.activePlaylist?.name)
        assertTrue(kept.id.equals(phone.state.value.activePlaylistId, ignoreCase = true))
    }

    /** The same playlist added on another device first: the categories hidden here stay hidden in the one kept. */
    @Test
    fun aPlaylistReplacedByTheSameOneKeepsItsHiddenCategories() {
        val sports = SavedPlaylist("P1", "Sports", "http://provider.example.com/sports.m3u", addedAt = 1)
        val mine = SavedPlaylist("P2", "Living room", list, addedAt = 3)
        val state = OndoState(
            isReady = true,
            playlists = listOf(sports, mine),
            library = LibraryState(hiddenCategories = setOf("P1|live-a", "P2|live-b")),
        )
        val tv = SyncSnapshot(device = "tv", playlists = listOf(SyncPlaylist("p3", "Home", SyncPlaylist.M3U, list, addedAt = 2, updatedAt = 2)))
        val merged = SyncMerge.merge(SyncData.snapshot(state, "phone", null, now = 10), tv)
        assertEquals(mapOf("p2" to "p3"), merged.replaced)

        val applied = SyncData.apply(state, merged)
        assertEquals(listOf("P1", "p3"), applied.playlists.map { it.id })
        assertEquals(setOf("P1|live-a", "p3|live-b"), applied.library.hiddenCategories)
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
