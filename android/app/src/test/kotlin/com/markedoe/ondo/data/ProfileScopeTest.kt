package com.markedoe.ondo.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.model.Profile
import com.markedoe.ondo.core.net.HttpClient
import com.markedoe.ondo.core.net.HttpException
import com.markedoe.ondo.core.subtitles.CredentialCheck
import com.markedoe.ondo.core.subtitles.SubtitleService
import com.markedoe.ondo.core.sync.SyncKey
import com.markedoe.ondo.core.sync.SyncRows
import com.markedoe.ondo.core.sync.SyncSnapshot
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What is every profile's and what is each profile's own (docs/PROFILES.md), on every surface: one list of each, so that
 * a new shared or per-profile item is added here. A shared item changed or removed in a profile that is not the main one
 * is the same in every profile, here and, once synced, on the other device. A per-profile item changed in one profile
 * leaves the others alone, and syncs to the same profile on the other device (or stays on the device, as docs/SYNC.md
 * says of the hidden categories and languages).
 */
@RunWith(RobolectricTestRunner::class)
class ProfileScopeTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val folder = TemporaryFolder()

    private var time = 1_760_000_000_000L
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val list = "http://provider.example.com/list.m3u"
    private val extra = "http://provider.example.com/extra.m3u"
    private val catalogue = """
        #EXTM3U
        #EXTINF:5400 group-title="Movies | Drama",Quiet Water (2021)
        http://provider.example.com/quiet.mp4
    """.trimIndent()

    /** Every service accepts every key; the playlists answer. */
    private inner class Http : HttpClient {
        override suspend fun get(url: String, headers: Map<String, String>): ByteArray = answer(url)

        override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray, contentType: String): ByteArray = answer(url)

        private fun answer(url: String): ByteArray = when {
            url == list || url == extra -> catalogue
            url.startsWith("https://api.themoviedb.org/3/authentication") -> """{"success":true}"""
            url.startsWith("https://api.subdl.com/") -> """{"status":true,"subtitles":[]}"""
            url.startsWith("https://api.subsource.net/") -> """{"data":[]}"""
            url.startsWith("https://api.opensubtitles.com/api/v1/infos/formats") -> """{"data":{"output_formats":["srt"]}}"""
            url.startsWith("https://api.opensubtitles.com/api/v1/login") -> """{"token":"test-session","base_url":"api.opensubtitles.com"}"""
            else -> throw HttpException.BadStatus(404)
        }.toByteArray()
    }

    private class Device(val repository: OndoRepository, val services: SubtitleServices) {
        val state get() = repository.state.value
        val library get() = state.library

        suspend fun open(profileId: String) {
            repository.switchProfile(profileId)
            repository.state.first { it.activeProfile.id == profileId }
        }

        /** Runs [read] in each profile, the open one again at the end. */
        suspend fun <T> inEveryProfile(read: Device.() -> T): List<T> {
            val opened = state.activeProfile.id
            return state.profiles.map { open(it.id); read() }.also { open(opened) }
        }
    }

    private suspend fun TestScope.device(name: String): Device {
        val prefs = context.getSharedPreferences("subtitle_services_scope_$name", Context.MODE_PRIVATE).apply { edit().clear().commit() }
        val services = SubtitleServices(prefs, Http(), "Ondo test", File(folder.root, "$name-subtitles"), io = Dispatchers.Unconfined)
        val repository = OndoRepository(
            AppStore(folder.newFolder(name)), Http(), backgroundScope, now = { time }, io = StandardTestDispatcher(testScheduler), keyStore = services,
        )
        repository.bootstrap()
        repository.state.first { it.isReady }
        return Device(repository, services)
    }

    private fun session(client: Device, server: Device) {
        val answer = requireNotNull(server.repository.mergeSync(requireNotNull(client.repository.syncSnapshot("client")), "server"))
        client.repository.mergeSync(answer, "client")
    }

    /** Shared by every profile: [set] then [clear] (when it can be removed), [read] null when absent. */
    private class Shared(
        val name: String,
        val syncs: Boolean = true,
        val set: suspend Device.() -> Unit,
        val clear: (suspend Device.() -> Unit)? = null,
        val read: Device.() -> Any?,
    )

    private fun subtitleKey(service: SubtitleService, values: Map<SubtitleCredential, String>) = Shared(
        name = "subtitle key: ${service.name}",
        set = { assertEquals(CredentialCheck.ACCEPTED, services.checkAndSave(service, values)) },
        clear = { services.remove(service) },
        read = { SubtitleCredential.fields(service).map { services.credential(it) }.takeIf { fields -> fields.first() != null } },
    )

    private val shared = listOf(
        Shared(
            name = "playlist added and deleted",
            set = { assertTrue(repository.addPlaylist("Extra", extra).isSuccess) },
            clear = { repository.delete(state.playlists.first { it.name == "Extra" }.id) },
            read = { state.playlists.firstOrNull { it.name == "Extra" }?.let { it.id to it.url } },
        ),
        Shared(
            name = "TMDb token",
            set = { repository.checkAndSaveTmdbToken("GOOD") },
            clear = { repository.removeTmdbToken() },
            read = { state.settings.tmdbToken },
        ),
        subtitleKey(
            SubtitleService.OPEN_SUBTITLES,
            mapOf(
                SubtitleCredential.OPEN_SUBTITLES_KEY to "test-opensubtitles-key",
                SubtitleCredential.OPEN_SUBTITLES_USERNAME to "test-user",
                SubtitleCredential.OPEN_SUBTITLES_PASSWORD to "test password",
            ),
        ),
        subtitleKey(SubtitleService.SUBDL, mapOf(SubtitleCredential.SUBDL_KEY to "test-subdl-key")),
        subtitleKey(SubtitleService.SUBSOURCE, mapOf(SubtitleCredential.SUBSOURCE_KEY to "test-subsource-key")),
        Shared(
            name = "Addic7ed switch",
            set = { services.setAddic7ed(true) },
            clear = { services.setAddic7ed(false) },
            read = { services.state.value.addic7ed.takeIf { it } },
        ),
        // The playback settings are every profile's, and each device's: they do not sync (docs/SYNC.md).
        Shared(
            name = "playback setting",
            syncs = false,
            set = { repository.setLiveBuffer(42) },
            read = { state.settings.liveBuffer.takeIf { it == 42 } },
        ),
    )

    /** Each profile's own: [set] in one profile, [read] null when absent. */
    private class PerProfile(val name: String, val syncs: Boolean = true, val set: suspend Device.() -> Unit, val read: Device.() -> Any?)

    /** The tabs whose rows the viewer orders: a new one is added here. */
    private val rowTabs = listOf(false to SyncRows.MOVIES, true to SyncRows.SERIES)

    private val perProfile = rowTabs.map { (series, tab) ->
        PerProfile(
            name = "row order: $tab",
            set = { repository.setRowOrder(series, listOf("continue", "all"), listOf("new")) },
            read = { library.rowOrders.firstOrNull { it.tab == tab }?.let { it.order to it.hidden } },
        )
    } + listOf(
        // Hidden categories and languages stay on the device (docs/SYNC.md): each profile's there.
        PerProfile(
            name = "hidden categories",
            syncs = false,
            set = { repository.setCategoryHidden("m-drama", true) },
            read = { library.hiddenCategories.takeIf { it.isNotEmpty() } },
        ),
        PerProfile(
            name = "hidden languages",
            syncs = false,
            set = { repository.setTagsHidden(listOf("PT"), true) },
            read = { library.hiddenTags.takeIf { "PT" in it } },
        ),
        PerProfile(
            name = "My List",
            set = { repository.toggleFavorite(ItemKind.MOVIE, "m-test", "Quiet Water", null) },
            read = { library.favorites.firstOrNull { it.kind == ItemKind.MOVIE && it.itemId == "m-test" }?.itemId },
        ),
        PerProfile(
            name = "favourite channels",
            set = { repository.toggleFavorite(ItemKind.CHANNEL, "c-test", "Test News", null) },
            read = { library.favorites.firstOrNull { it.kind == ItemKind.CHANNEL && it.itemId == "c-test" }?.itemId },
        ),
        PerProfile(
            name = "progress",
            set = {
                val movie = state.content.movies.first()
                assertNotNull(repository.playRequest(movie))
                repository.recordProgress("${state.activePlaylistId}|MOVIE|${movie.id}|", 600.0, 6000.0)
                repository.playbackEnded()
            },
            read = { library.history.firstOrNull { it.position > 0 }?.itemId },
        ),
        PerProfile(
            name = "lists",
            set = { repository.createCollection("Weekend") },
            read = { library.collections.firstOrNull { it.name == "Weekend" }?.id },
        ),
    )

    /** Two devices; the first with a playlist and a second profile, open, and the second told of both. */
    private suspend fun TestScope.devices(): Triple<Device, Device, String> {
        val tv = device("tv")
        val phone = device("phone")
        assertTrue(tv.repository.addPlaylist("Home", list).isSuccess)
        tv.repository.state.first { it.content.movies.isNotEmpty() }
        val guest = requireNotNull(tv.repository.addProfile("Guest", 1, "star", isKids = false, pin = null)).id
        session(tv, phone)
        tv.open(guest)
        return Triple(tv, phone, guest)
    }

    @Test
    fun sharedItemsAreEveryProfilesHereAndOnTheOtherDevice() = runTest {
        val (tv, phone, guest) = devices()
        for (item in shared) {
            time += 1_000
            tv.open(guest)
            item.set(tv)
            val value = item.read(tv)
            assertNotNull(item.name, value)
            assertEquals("${item.name}: the same in every profile", tv.state.profiles.map { value }, tv.inEveryProfile(item.read))
            session(tv, phone)
            val there = phone.inEveryProfile(item.read)
            assertEquals("${item.name}: the other device has both profiles", 2, there.size)
            if (item.syncs) {
                assertEquals("${item.name}: synced to every profile", there.map { value }, there)
            } else {
                assertTrue("${item.name}: stays on the device", there.all { it == null })
            }
            val clear = item.clear ?: continue
            time += 1_000
            tv.open(guest)
            clear(tv)
            assertTrue("${item.name}: removed in every profile", tv.inEveryProfile(item.read).all { it == null })
            session(tv, phone)
            assertTrue("${item.name}: still removed after the session", tv.inEveryProfile(item.read).all { it == null })
            assertTrue("${item.name}: removed in every profile of the other device", phone.inEveryProfile(item.read).all { it == null })
        }
    }

    /** The TMDb token removed in a profile that is not the main one: the removal is the snapshot's own and wins. */
    @Test
    fun aTokenRemovedInAnotherProfileStaysRemoved() = runTest {
        val (tv, _, _) = devices()
        tv.repository.checkAndSaveTmdbToken("GOOD")
        time += 1_000
        tv.repository.removeTmdbToken()
        val snapshot = requireNotNull(tv.repository.syncSnapshot("tv"))
        assertNotNull("in the snapshot's own deletions", snapshot.deleted["key:${SyncKey.TMDB}"])
        assertTrue(snapshot.profileData.none { "key:${SyncKey.TMDB}" in it.deleted })
        // A device that still has the token from before: it does not come back.
        tv.repository.mergeSync(SyncSnapshot(device = "old", keys = listOf(SyncKey(SyncKey.TMDB, "GOOD", time - 500))), "tv")
        assertNull(tv.state.settings.tmdbToken)
    }

    @Test
    fun perProfileItemsStayInTheirProfileAndSyncToIt() = runTest {
        val (tv, phone, guest) = devices()
        for (item in perProfile) {
            time += 1_000
            tv.open(guest)
            item.set(tv)
            val value = item.read(tv)
            assertNotNull(item.name, value)
            tv.open(Profile.DEFAULT_ID)
            assertNull("${item.name}: not in the main profile", item.read(tv))
            tv.open(guest)
            session(tv, phone)
            phone.open(guest)
            if (item.syncs) {
                assertEquals("${item.name}: in the same profile there", value, item.read(phone))
            } else {
                assertNull("${item.name}: stays on the device", item.read(phone))
            }
            phone.open(Profile.DEFAULT_ID)
            assertNull("${item.name}: not in the main profile there", item.read(phone))
        }
    }
}
