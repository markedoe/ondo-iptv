package com.markedoe.ondo.data

import com.markedoe.ondo.core.sync.SentSnapshot
import com.markedoe.ondo.core.sync.SyncFavorite
import com.markedoe.ondo.core.sync.SyncList
import com.markedoe.ondo.core.sync.SyncListItem
import com.markedoe.ondo.core.sync.SyncMerge
import com.markedoe.ondo.core.sync.SyncPerson
import com.markedoe.ondo.core.sync.SyncPlaylist
import com.markedoe.ondo.core.sync.SyncProgress
import com.markedoe.ondo.core.sync.SyncSnapshot
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
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

    /** A device whose clock is [offset] ahead of [time] (behind when negative). */
    private fun TestScope.device(
        name: String,
        http: FakeHttp = FakeHttp(mapOf(list to catalogue, sports to catalogue, films to twoFilms)),
        offset: Long = 0,
        merging: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
    ) = OndoRepository(
        AppStore(folder.newFolder(name)), http, backgroundScope, now = { time + offset }, io = StandardTestDispatcher(testScheduler), syncMerging = merging,
    )

    private suspend fun OndoRepository.ready(): OndoRepository {
        bootstrap()
        state.first { it.isReady }
        return this
    }

    /** One session: [client] sends its snapshot, [server] merges it and answers, [client] keeps the answer. */
    private fun session(client: OndoRepository, server: OndoRepository) {
        val answer = requireNotNull(server.mergeSync(requireNotNull(client.syncSnapshot("client")), "server"))
        client.mergeSync(answer, "client")
        // Delivered and merged: each side counts the session.
        client.noteSyncSession(answer.device)
        server.noteSyncSession(requireNotNull(client.syncSnapshot("client")).device)
    }

    /** A dispatcher that runs what it is given only when told to: a merge "in the background" waits until then. */
    private class HeldDispatcher : CoroutineDispatcher() {
        private val held = ArrayDeque<Runnable>()
        val isHolding: Boolean get() = held.isNotEmpty()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            held.addLast(block)
        }
        fun release() {
            while (held.isNotEmpty()) held.removeFirst().run()
        }
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

    /**
     * The merge runs off the main thread; a change made on the main thread meanwhile is kept: the merge is done again
     * from the data as it is then.
     */
    @Test
    fun aChangeMadeDuringABackgroundMergeIsKept() = runTest {
        val merging = HeldDispatcher()
        val tv = device("tv", merging = merging).ready()
        val phone = device("phone").ready()
        tv.addPlaylist("Home", list)
        session(phone, tv)
        phone.state.first { it.content.movies.isNotEmpty() }
        val movie = tv.state.value.content.movies.single()
        time += 1_000
        phone.togglePerson("Ada Example", null)
        val sent = requireNotNull(phone.syncSnapshot("phone"))

        var answer: SyncSnapshot? = null
        val merge = launch { answer = tv.mergeSyncInBackground(sent, "tv") }
        runCurrent()
        // The merge waits on its own thread; the TV changes My List meanwhile.
        assertTrue(merging.isHolding)
        tv.toggleFavorite(ItemKind.MOVIE, movie.id, movie.name, null)
        while (merge.isActive) {
            merging.release()
            runCurrent()
        }
        assertTrue(tv.state.value.favorite(movie.id))
        assertEquals(listOf("Ada Example" to null), tv.state.value.library.people())
        assertTrue(requireNotNull(answer).favorites.any { it.item == movie.id })
    }

    /** When the data keeps changing during every try, the merge gives up until the next session: never on the main thread. */
    @Test
    fun aMergeThatKeepsBeingOvertakenWaitsForTheNextSession() = runTest {
        val merging = HeldDispatcher()
        val tv = device("tv", merging = merging).ready()
        tv.addPlaylist("Home", list)
        val movie = tv.state.value.content.movies.single()
        var answer: SyncSnapshot? = SyncSnapshot(device = "none")
        val merge = launch { answer = tv.mergeSyncInBackground(SyncSnapshot(device = "phone", people = listOf(SyncPerson("Ada Example", addedAt = time))), "tv") }
        runCurrent()
        while (merge.isActive) {
            // A change on the main thread each time the merge runs.
            time += 1_000
            tv.toggleFavorite(ItemKind.MOVIE, movie.id, movie.name, null)
            merging.release()
            runCurrent()
        }
        assertNull(answer)
        assertTrue(tv.state.value.library.people().isEmpty())
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

    /** A box off for more than 180 days still gets a removal made meanwhile: it is kept until that box has had it. */
    @Test
    fun aRemovalWaitsForABoxThatWasAwayForMonths() = runTest {
        val living = device("living").ready()
        val bedroom = device("bedroom").ready()
        living.addPlaylist("Home", list)
        val movie = living.state.value.content.movies.single()
        time += 1_000
        living.toggleFavorite(ItemKind.MOVIE, movie.id, movie.name, null)
        session(bedroom, living)
        assertTrue(bedroom.state.value.library.favorites.any { it.itemId == movie.id })

        time += 1_000
        living.toggleFavorite(ItemKind.MOVIE, movie.id, movie.name, null)
        val key = SyncKeys.favorite(Favorite(living.state.value.activePlaylistId!!, ItemKind.MOVIE, movie.id, movie.name, null, 0))
        time += 200 * 86_400_000L
        assertNotNull(living.syncSnapshot("living")!!.deleted[key])
        session(bedroom, living)
        assertTrue(bedroom.state.value.library.favorites.none { it.itemId == movie.id })
        // Had by every box: now it can go.
        time += 1_000
        assertNull(living.syncSnapshot("living")!!.deleted[key])
    }

    /**
     * When nothing changed on the client since the snapshot it last sent, it sends a probe; the server answers
     * "unchanged" while nothing changed there either, and its data as soon as something did.
     */
    @Test
    fun aClientWithNothingNewSendsAProbeAndStillHearsOfChanges() = runTest {
        val tv = device("tv").ready()
        val phone = device("phone").ready()
        tv.addPlaylist("Home", list)
        var sent: String? = null
        var answered: String? = null
        suspend fun round(): Pair<SyncSnapshot, SyncSnapshot> {
            val offer = requireNotNull(phone.syncSnapshot("phone")).offer(sent, answered)
            val answer = requireNotNull(tv.answerSync(offer, "tv"))
            if (!answer.isUnchanged) {
                requireNotNull(phone.mergeSync(answer, "phone"))
                if (!offer.isProbe) sent = offer.digest
                answered = answer.digest
            }
            return offer to answer
        }
        // The first sessions send everything; once the phone has what the TV has, a probe is enough.
        round()
        phone.state.first { it.content.movies.isNotEmpty() }
        round()
        val (probe, unchanged) = round()
        assertTrue(probe.isProbe)
        assertTrue(probe.encoded().size < 1_024)
        assertTrue(unchanged.isUnchanged)
        assertTrue(unchanged.favorites.isEmpty() && unchanged.playlists.isEmpty())

        // A change on the TV: the next probe gets the TV's data.
        val movie = tv.state.value.content.movies.single()
        time += 1_000
        tv.toggleFavorite(ItemKind.MOVIE, movie.id, movie.name, null)
        val (_, changed) = round()
        assertFalse(changed.isUnchanged)
        assertTrue(phone.state.value.favorite(movie.id))
        // A change on the phone: the whole snapshot goes again.
        time += 1_000
        phone.togglePerson("Ada Example", null)
        val (full, _) = round()
        assertFalse(full.isProbe)
        assertEquals(listOf("Ada Example" to null), tv.state.value.library.people())
    }

    /** Two boxes change the same collection between two sessions: every addition and every removal is kept. */
    @Test
    fun twoBoxesChangingTheSameCollectionKeepEveryChange() = runTest {
        val living = device("living").ready()
        val bedroom = device("bedroom").ready()
        living.addPlaylist("Films", films)
        val (glass, quiet) = living.state.value.content.movies.sortedBy { it.name }.let { it[0] to it[1] }
        val weekend = living.createCollection("Weekend")
        living.toggleInCollection(weekend, ItemKind.MOVIE, glass.id, glass.name, null)
        living.togglePersonInCollection(weekend, "Tom Example", null)
        session(bedroom, living)
        bedroom.state.first { it.content.movies.size == 2 }

        // Meanwhile: the living room box adds a film and takes the person out; the bedroom box adds a person, renames
        // the collection, and takes the first film out.
        time += 1_000
        living.toggleInCollection(weekend, ItemKind.MOVIE, quiet.id, quiet.name, null)
        living.togglePersonInCollection(weekend, "Tom Example", null)
        time += 1_000
        bedroom.togglePersonInCollection(weekend, "Ada Example", null)
        bedroom.renameCollection(weekend, "Weekend films")
        bedroom.toggleInCollection(weekend, ItemKind.MOVIE, glass.id, glass.name, null)
        session(bedroom, living)
        session(living, bedroom)

        for (box in listOf(living, bedroom)) {
            val collection = box.state.value.library.collections.single()
            assertEquals("Weekend films", collection.name)
            assertEquals(listOf(quiet.id), collection.items.filter { it.kind == ItemKind.MOVIE }.map { it.itemId })
            assertEquals(listOf("Ada Example"), collection.items.filter { it.kind == ItemKind.PERSON }.map { it.title })
        }
        // Renamed again on the living room box: only the name changes.
        time += 1_000
        living.renameCollection(weekend, "Films")
        session(bedroom, living)
        assertEquals(living.state.value.library.collections, bedroom.state.value.library.collections)
        assertEquals(2, bedroom.state.value.library.collections.single().items.size)
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
     * A session while a film plays: the catalogue is not downloaded again, and the film's entry stays as the player
     * left it until playback ends; then the later of the two positions applies.
     */
    @Test
    fun aSessionDuringPlaybackReloadsNothingAndParksTheOtherPosition() = runTest {
        val tvHttp = FakeHttp(mapOf(list to catalogue))
        val tv = device("tv", tvHttp).ready()
        val phone = device("phone").ready()
        tv.addPlaylist("Home", list)
        session(phone, tv)
        phone.state.first { it.content.movies.isNotEmpty() }
        val movie = tv.state.value.content.movies.single()
        val key = requireNotNull(tv.playRequest(movie)?.progressKey)
        val phoneKey = requireNotNull(phone.playRequest(phone.state.value.content.movies.single())?.progressKey)
        tv.playbackStarted(key)
        time += 1_000
        tv.recordProgress(key, 600.0, 5_400.0)

        // The phone moved the same film elsewhere meanwhile: the TV's entry does not move, nothing is downloaded.
        time += 1_000
        phone.recordProgress(phoneKey, 1_800.0, 5_400.0)
        val downloads = tvHttp.requests.count { it.first == list }
        val active = tv.state.value.activePlaylistId
        session(phone, tv)
        assertEquals(600.0, tv.state.value.library.progress(key)!!.position, 0.0)
        assertEquals(downloads, tvHttp.requests.count { it.first == list })
        assertEquals(active, tv.state.value.activePlaylistId)

        // The player's next report, later than the phone's, is the one kept when playback ends, on both devices.
        time += 1_000
        tv.recordProgress(key, 610.0, 5_400.0)
        tv.playbackEnded()
        assertEquals(610.0, tv.state.value.library.progress(key)!!.position, 0.0)
        session(phone, tv)
        assertEquals(610.0, phone.state.value.library.progress(phoneKey)!!.position, 0.0)

        // Played again, and moved later on the phone during playback: applied once playback ends.
        tv.playbackStarted(key)
        time += 1_000
        phone.recordProgress(phoneKey, 2_400.0, 5_400.0)
        session(phone, tv)
        assertEquals(610.0, tv.state.value.library.progress(key)!!.position, 0.0)
        tv.playbackEnded()
        assertEquals(2_400.0, tv.state.value.library.progress(key)!!.position, 0.0)
    }

    /**
     * One box two hours ahead of the other, then two hours behind: what each adds reaches the other, and the later
     * position always wins, whichever box's clock is wrong.
     */
    @Test
    fun aBoxHoursAheadOrBehindLosesNothingAndGoesNotBack() = runTest {
        for ((index, offset) in listOf(2 * 3_600_000L, -2 * 3_600_000L).withIndex()) {
            val wrong = device("wrong$index", offset = offset).ready()
            val right = device("right$index").ready()
            wrong.addPlaylist("Films", films)
            session(right, wrong)
            right.state.first { it.content.movies.size == 2 }
            val movie = wrong.state.value.content.movies.first()
            val key = requireNotNull(wrong.playRequest(movie)?.progressKey)
            time += 1_000
            wrong.toggleFavorite(ItemKind.MOVIE, movie.id, movie.name, null)
            wrong.togglePerson("Ada Example", null)
            wrong.recordProgress(key, 60.0, 5_400.0)
            session(right, wrong)
            assertTrue("offset $offset", right.state.value.favorite(movie.id))
            assertEquals(listOf("Ada Example" to null), right.state.value.library.people())
            assertEquals(60.0, right.state.value.library.progress(key)!!.position, 0.0)

            // Each box in turn moves the film on, a minute apart, with sessions both ways.
            for (step in 1..4) {
                time += 60_000
                val (box, other) = if (step % 2 == 0) wrong to right else right to wrong
                box.playRequest(box.state.value.content.movies.single { it.id == movie.id })
                box.recordProgress(key, 60.0 + step * 60, 5_400.0)
                if (step % 2 == 0) session(box, other) else session(other, box)
                for (device in listOf(wrong, right)) assertEquals("offset $offset, step $step", 60.0 + step * 60, device.state.value.library.progress(key)!!.position, 0.0)
            }
            // A removal on the box that is right reaches the other one.
            time += 60_000
            right.toggleFavorite(ItemKind.MOVIE, movie.id, movie.name, null)
            session(wrong, right)
            assertFalse(wrong.state.value.favorite(movie.id))
        }
    }

    /**
     * Twenty sessions between a box two hours ahead and one on time, 40 ms each way: after the first round nothing
     * changes any more on either box (no time creeps), the digests stay the same, and no merge asks for another session.
     */
    @Test
    fun sessionsBetweenSkewedBoxesSettleAndRaiseNoMoreSessions() = runTest {
        val ahead = device("ahead", offset = 2 * 3_600_000L).ready()
        val onTime = device("on-time").ready()
        ahead.addPlaylist("Home", list)
        val movie = ahead.state.value.content.movies.single()
        time += 1_000
        ahead.toggleFavorite(ItemKind.MOVIE, movie.id, movie.name, null)
        ahead.togglePerson("Ada Example", null)
        val raised = mutableListOf<Any>()
        backgroundScope.launch { onTime.localSyncChanges.collect { raised += it } }
        backgroundScope.launch { ahead.localSyncChanges.collect { raised += it } }
        var sent: SentSnapshot? = null
        var answered: String? = null
        val libraries = mutableListOf<Pair<LibraryState, LibraryState>>()
        repeat(20) { round ->
            time += 60_000
            val prepared = requireNotNull(onTime.syncOffer("on-time", sent, answered, full = round % 2 == 0))
            val offer = prepared.snapshot
            time += 40
            val answer = requireNotNull(ahead.answerSync(offer, "ahead"))
            time += 40
            if (!answer.isUnchanged) {
                requireNotNull(onTime.mergeSyncInBackground(answer, "on-time"))
                answered = answer.digest
            }
            sent = prepared.sent
            runCurrent()
            libraries += ahead.state.value.library to onTime.state.value.library
        }
        assertTrue(onTime.state.value.favorite(movie.id))
        val settled = libraries[1]
        for ((round, pair) in libraries.withIndex().drop(1)) {
            assertEquals("round $round", settled.first.copy(syncPeers = emptyMap()), pair.first.copy(syncPeers = emptyMap()))
            assertEquals("round $round", settled.second.copy(syncPeers = emptyMap()), pair.second.copy(syncPeers = emptyMap()))
        }
        assertTrue("no session asked for by a merge: $raised", raised.isEmpty())
    }

    /**
     * A film resumed on the phone after its last full session with the TV: while it plays, the phone's regular sessions
     * are probes, its position (saved every few seconds) being left out on both sides of the comparison.
     */
    @Test
    fun aFilmResumedAfterTheLastFullSessionStillSendsProbes() = runTest {
        val tv = device("tv").ready()
        val phone = device("phone").ready()
        tv.addPlaylist("Home", list)
        session(phone, tv)
        phone.state.first { it.content.movies.isNotEmpty() }
        val movie = phone.state.value.content.movies.single()
        val key = requireNotNull(phone.playRequest(movie)?.progressKey)
        phone.recordProgress(key, 600.0, 5_400.0)
        var sent: SentSnapshot? = null
        var answered: String? = null
        suspend fun round(): SyncSnapshot {
            time += 60_000
            val prepared = requireNotNull(phone.syncOffer("phone", sent, answered, full = false))
            val answer = requireNotNull(tv.answerSync(prepared.snapshot, "tv"))
            if (!answer.isUnchanged) {
                requireNotNull(phone.mergeSyncInBackground(answer, "phone"))
                answered = answer.digest
            }
            sent = prepared.sent
            return prepared.snapshot
        }
        // A full session with nothing playing, then probes.
        assertFalse(round().isProbe)
        assertTrue(round().isProbe)
        // The film is resumed: it starts again, and its position moves every few seconds.
        phone.playRequest(movie)
        phone.playbackStarted(key)
        for (minute in 1..3) {
            phone.recordProgress(key, 600.0 + minute * 60, 5_400.0)
            assertTrue("minute $minute", round().isProbe)
        }
        // Playback ends: the position goes to the TV.
        phone.playbackEnded()
        assertFalse(round().isProbe)
        assertEquals(780.0, tv.state.value.library.progress(key)!!.position, 0.0)
    }

    /**
     * A change made while the TV's clock ran more than a day ahead is left out by the phone, whose own changes stay on
     * its clock; once the TV's clock is right and that time is within a day, the change arrives, and a removal made
     * on the phone after it wins on both.
     */
    @Test
    fun aChangeDatedMoreThanADayAheadWaitsForItsTimeAndALaterRemovalWins() = runTest {
        val tv = device("tv", offset = 0).ready()
        val phone = device("phone").ready()
        tv.addPlaylist("Home", list)
        session(phone, tv)
        val movie = tv.state.value.content.movies.single()
        // The TV's clock ran a day and an hour ahead when the favourite was kept.
        time += 86_400_000L + 3_600_000L
        tv.toggleFavorite(ItemKind.MOVIE, movie.id, movie.name, null)
        time -= 86_400_000L + 3_600_000L
        val stamped = tv.state.value.library.favorites.single().addedAt
        session(phone, tv)
        assertFalse(phone.state.value.favorite(movie.id))
        assertTrue("the TV keeps it, as it was dated", tv.state.value.library.favorites.single().addedAt == stamped)
        time += 1_000
        phone.togglePerson("Ada Example", null)
        assertTrue("the phone did not follow the TV's clock", phone.state.value.library.favorites.single().addedAt < stamped)
        // Its time come within a day: it arrives as it was dated.
        time += 2 * 3_600_000L
        session(phone, tv)
        assertEquals(stamped, phone.state.value.library.favorites.single { it.itemId == movie.id }.addedAt)
        // Taken out on the phone a minute later: dated after it, so gone from the TV too.
        time += 60_000
        phone.toggleFavorite(ItemKind.MOVIE, movie.id, movie.name, null)
        session(phone, tv)
        assertFalse(tv.state.value.favorite(movie.id))
        assertFalse(phone.state.value.favorite(movie.id))
    }

    /** A deletion learned from a box back after months is kept from the day it was learned, not from its own date. */
    @Test
    fun aDeletionLearnedLateIsKeptFromWhenItWasLearned() = runTest {
        val tv = device("tv").ready()
        val phone = device("phone").ready()
        val away = device("away").ready()
        tv.addPlaylist("Home", list)
        session(phone, tv)
        session(away, tv)
        val movie = tv.state.value.content.movies.single()
        time += 1_000
        tv.toggleFavorite(ItemKind.MOVIE, movie.id, movie.name, null)
        session(phone, tv)
        session(away, tv)
        val key = SyncKeys.favorite(tv.state.value.library.favorites.single())
        // The away box takes it out, then is away 250 days; the others keep syncing meanwhile.
        time += 1_000
        away.toggleFavorite(ItemKind.MOVIE, movie.id, movie.name, null)
        repeat(25) {
            time += 10 * 86_400_000L
            session(phone, tv)
        }
        session(away, tv)
        assertFalse(tv.state.value.favorite(movie.id))
        // The next day the TV still sends the deletion to the phone, which has not had it.
        time += 86_400_000L
        assertNotNull(requireNotNull(tv.syncSnapshot("tv")).deleted[key])
        session(phone, tv)
        assertFalse(phone.state.value.favorite(movie.id))
    }

    /**
     * The same account added on two boxes, one of them playing: its catalogue is not reloaded under the player; once
     * playback ends it follows the playlist that replaced its own, from the cache moved to that ID (no download).
     */
    @Test
    fun aReplacedPlaylistDuringPlaybackFollowsAfterItFromTheMovedCache() = runTest {
        val tvHttp = FakeHttp(mapOf(list to catalogue))
        val first = device("first").ready()
        first.addPlaylist("Home", list)
        time += 1_000
        val tv = device("tv", tvHttp).ready()
        tv.addPlaylist("Home", list)
        val own = requireNotNull(tv.state.value.activePlaylistId)
        tv.playbackStarted(requireNotNull(tv.playRequest(tv.state.value.content.movies.single())?.progressKey))
        val downloads = tvHttp.requests.count { it.first == list }
        session(tv, first)
        val replacement = requireNotNull(first.state.value.activePlaylistId)
        assertEquals(replacement, tv.state.value.activePlaylistId)
        runCurrent()
        assertTrue("the catalogue stays under the player", tv.state.value.content.movies.isNotEmpty())
        tv.playbackEnded()
        tv.state.first { !it.isLoading && it.content.movies.isNotEmpty() }
        assertEquals(downloads, tvHttp.requests.count { it.first == list })
        assertTrue(own != replacement)
    }

    /**
     * A box whose clock runs more than a day ahead cannot date a removal in the future: it is left out until its time
     * comes within a day.
     */
    @Test
    fun aClockAheadDoesNotWinOverLaterChanges() = runTest {
        val tv = device("tv").ready()
        tv.addPlaylist("Home", list)
        val movie = tv.state.value.content.movies.single()
        time += 1_000
        tv.toggleFavorite(ItemKind.MOVIE, movie.id, movie.name, null)
        val key = SyncKeys.favorite(tv.state.value.library.favorites.single())
        val ahead = SyncSnapshot(device = "ahead", deleted = mapOf(key to time + 86_400_000L + 3_600_000L))

        time += 1_000
        tv.mergeSync(ahead, "tv")
        assertTrue(tv.state.value.favorite(movie.id))
        assertNull(tv.state.value.library.syncDeleted[key])
        // Its time come within a day, the removal applies like any other.
        time += 3_600_000L
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

    /** Where the end credits start stays on the device that read it: a snapshot applied keeps it and brings none. */
    @Test
    fun theEndCreditsStartStaysOnTheDevice() {
        val online = SavedPlaylist("P1", "Home", "http://provider.example.com/list.m3u", addedAt = 1)
        val state = OndoState(
            isReady = true,
            playlists = listOf(online),
            library = LibraryState(
                history = listOf(
                    WatchEntry("P1", ItemKind.MOVIE, "m1", title = "Quiet Water", position = 60.0, duration = 7_200.0, lastWatchedAt = 8, creditsStart = 6_300.0),
                ),
            ),
        )
        val snapshot = SyncData.snapshot(state, "device", "TV", now = 10)
        val moved = snapshot.progress.map { it.copy(position = 6_300.0, watchedAt = 20) } +
            SyncProgress("p1", "movie", "m2", title = "Glass City", position = 6_300.0, duration = 7_200.0, watchedAt = 21)
        val applied = SyncData.apply(state, SyncSnapshot(device = "other", playlists = snapshot.playlists, progress = moved)).library
        val quiet = applied.history.single { it.itemId == "m1" }
        assertEquals(6_300.0, quiet.creditsStart!!, 0.0)
        assertTrue("watched at the credits read here", quiet.isFinished)
        val glass = applied.history.single { it.itemId == "m2" }
        assertNull(glass.creditsStart)
        assertFalse("15 minutes left, no chapter known here", glass.isFinished)
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
