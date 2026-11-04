package com.markedoe.ondo.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.net.HttpClient
import com.markedoe.ondo.core.net.HttpException
import com.markedoe.ondo.core.subtitles.CredentialCheck
import com.markedoe.ondo.core.subtitles.SubtitleService
import com.markedoe.ondo.core.sync.SyncKey
import com.markedoe.ondo.core.sync.SyncSnapshot
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The subtitle services' keys travel by sync as the TMDb token does (docs/SYNC.md, "Key"): each field on its own, kept in
 * the services' private file on the other device without a check, removals following, never in the settings.
 */
@RunWith(RobolectricTestRunner::class)
class SubtitleKeysSyncTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val folder = TemporaryFolder()

    private var time = 1_760_000_000_000L
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Accepts every key; [held] keeps SubDL's answer until completed. */
    private class Http : HttpClient {
        var held: CompletableDeferred<Unit>? = null

        override suspend fun get(url: String, headers: Map<String, String>): ByteArray {
            if (url.startsWith("https://api.subdl.com/")) {
                held?.await()
                return """{"status":true,"subtitles":[]}""".toByteArray()
            }
            throw HttpException.BadStatus(404)
        }
    }

    private class Device(val repository: OndoRepository, val services: SubtitleServices)

    private fun TestScope.device(name: String, http: Http = Http()): Device {
        val prefs = context.getSharedPreferences("subtitle_services_$name", Context.MODE_PRIVATE).apply { edit().clear().commit() }
        val services = SubtitleServices(prefs, http, "Ondo test", File(folder.root, "$name-subtitles"), io = Dispatchers.Unconfined)
        val repository = OndoRepository(
            AppStore(folder.newFolder(name)), http, backgroundScope, now = { time }, io = StandardTestDispatcher(testScheduler), keyStore = services,
        )
        return Device(repository, services)
    }

    private suspend fun Device.ready(): Device {
        repository.bootstrap()
        repository.state.first { it.isReady }
        return this
    }

    private fun session(client: Device, server: Device) {
        val answer = requireNotNull(server.repository.mergeSync(requireNotNull(client.repository.syncSnapshot("client")), "server"))
        client.repository.mergeSync(answer, "client")
    }

    private fun fixture(name: String): SyncSnapshot {
        val directory = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "fixtures/sync") }.first { it.isDirectory }
        return requireNotNull(SyncSnapshot.decode(File(directory, name).readBytes())) { name }
    }

    @Test
    fun subtitleKeysTravelAndSoDoesTheirRemoval() = runTest {
        val phone = device("phone").ready()
        val tv = device("tv").ready()
        assertEquals(CredentialCheck.ACCEPTED, phone.services.checkAndSave(SubtitleService.SUBDL, mapOf(SubtitleCredential.SUBDL_KEY to " test-subdl-key ")))
        phone.services.setAddic7ed(true)
        val sent = phone.repository.syncSnapshot("phone")!!.keys
        assertEquals(listOf(SyncKey.SUBDL, SyncKey.ADDIC7ED), sent.map { it.service })
        assertEquals("test-subdl-key", sent.first().value)
        assertTrue(sent.all { it.updatedAt > 0 })

        session(phone, tv)
        assertEquals("test-subdl-key", tv.services.credential(SubtitleCredential.SUBDL_KEY))
        assertTrue(tv.services.state.value.addic7ed)
        assertEquals(setOf(SubtitleService.SUBDL), tv.services.state.value.saved)
        assertTrue("kept in the services' file only", tv.repository.state.value.settings.serviceKeys.isEmpty())
        assertEquals("sent on with the times they were saved", sent, tv.repository.syncSnapshot("tv")!!.keys)

        // A device that does not sync keys answers without them: they stay.
        tv.repository.mergeSync(SyncSnapshot(device = "old"), "tv")
        assertEquals("test-subdl-key", tv.services.credential(SubtitleCredential.SUBDL_KEY))

        // Removed on the TV later, Addic7ed turned off: the same on the phone, and nothing comes back.
        time += 1_000
        tv.services.remove(SubtitleService.SUBDL)
        tv.services.setAddic7ed(false)
        assertEquals(listOf("key:${SyncKey.SUBDL}"), tv.repository.syncSnapshot("tv")!!.deleted.keys.filter { it.startsWith("key:") })
        session(phone, tv)
        assertNull(phone.services.credential(SubtitleCredential.SUBDL_KEY))
        assertFalse(phone.services.state.value.addic7ed)
        session(phone, tv)
        assertNull(tv.services.credential(SubtitleCredential.SUBDL_KEY))
    }

    /** Two devices changed the subtitle keys apart (the shared example `keys-a.json` and `keys-b.json`): field by field. */
    @Test
    fun concurrentEditsMergeFieldByField() = runTest {
        val tv = device("tv").ready()
        tv.repository.mergeSync(fixture("keys-a.json"), "tv")
        assertEquals("test-user", tv.services.credential(SubtitleCredential.OPEN_SUBTITLES_USERNAME))
        tv.repository.mergeSync(fixture("keys-b.json"), "tv")
        val services = tv.services
        assertEquals("test-opensubtitles-key-b", services.credential(SubtitleCredential.OPEN_SUBTITLES_KEY))
        assertNull("removed after it was saved", services.credential(SubtitleCredential.OPEN_SUBTITLES_USERNAME))
        assertEquals("test-password-b", services.credential(SubtitleCredential.OPEN_SUBTITLES_PASSWORD))
        assertEquals("saved later than the other", "test-subdl-key-a", services.credential(SubtitleCredential.SUBDL_KEY))
        assertEquals("saved after its removal", "test-subsource-key", services.credential(SubtitleCredential.SUBSOURCE_KEY))
        assertFalse(services.state.value.addic7ed)
        val settings = tv.repository.state.value.settings
        assertEquals("test-tmdb-token", settings.tmdbToken)
        assertEquals(listOf(SyncKey.TMDB, "future-service"), settings.serviceKeys.map { it.service }.sortedDescending())
        assertEquals(fixture("keys-merged.json").keys, tv.repository.syncSnapshot("tv")!!.keys.sortedBy { it.service })
    }

    /** A password from another device keeps its spaces; a key with one, or with a control character, is not used. */
    @Test
    fun aPasswordKeepsItsSpacesAndAnUnusableKeyIsLeftOut() = runTest {
        val tv = device("tv").ready()
        tv.repository.mergeSync(
            SyncSnapshot(
                device = "phone",
                keys = listOf(
                    SyncKey(SyncKey.OPEN_SUBTITLES_PASSWORD, "test password", 1_000),
                    SyncKey(SyncKey.SUBSOURCE, "test key", 1_000),
                    SyncKey(SyncKey.SUBDL, "test-key\u0007", 1_000),
                ),
            ),
            "tv",
        )
        assertEquals("test password", tv.services.credential(SubtitleCredential.OPEN_SUBTITLES_PASSWORD))
        assertNull(tv.services.credential(SubtitleCredential.SUBSOURCE_KEY))
        assertNull(tv.services.credential(SubtitleCredential.SUBDL_KEY))
    }

    /** While a key is being checked, sync leaves it alone; the key saved once accepted is the one that travels. */
    @Test
    fun syncLeavesAKeyBeingCheckedAlone() = runTest {
        val http = Http().apply { held = CompletableDeferred() }
        val tv = device("tv", http).ready()
        val check = async { tv.services.checkAndSave(SubtitleService.SUBDL, mapOf(SubtitleCredential.SUBDL_KEY to "test-typed-key")) }
        runCurrent()
        time += 1_000
        tv.repository.mergeSync(SyncSnapshot(device = "phone", keys = listOf(SyncKey(SyncKey.SUBDL, "test-other-key", time))), "tv")
        assertNull(tv.services.credential(SubtitleCredential.SUBDL_KEY))
        http.held?.complete(Unit)
        assertEquals(CredentialCheck.ACCEPTED, check.await())
        assertEquals("test-typed-key", tv.services.credential(SubtitleCredential.SUBDL_KEY))
        val sent = tv.repository.syncSnapshot("tv")!!.keys.single { it.service == SyncKey.SUBDL }
        assertEquals("test-typed-key", sent.value)
        assertTrue("dated after what sync brought", sent.updatedAt > time)
    }

    /** A key saved before sync carried them is dated once when the app starts, so that it travels. */
    @Test
    fun anUndatedKeyIsDatedAtLaunch() = runTest {
        val prefs = context.getSharedPreferences("subtitle_services_mac", Context.MODE_PRIVATE).apply { edit().clear().commit() }
        prefs.edit().putString(SubtitleCredential.SUBSOURCE_KEY.key, "test-subsource-key").commit()
        val services = SubtitleServices(prefs, Http(), "Ondo test", File(folder.root, "subtitles"), io = Dispatchers.Unconfined)
        val repository = OndoRepository(
            AppStore(folder.newFolder("mac")), Http(), backgroundScope, now = { time }, io = StandardTestDispatcher(testScheduler), keyStore = services,
        )
        Device(repository, services).ready()
        val key = repository.syncSnapshot("mac")!!.keys.single()
        assertEquals(SyncKey.SUBSOURCE, key.service)
        assertTrue(key.updatedAt >= time)
        // A removal made earlier on another device does not take it.
        repository.mergeSync(SyncSnapshot(device = "tv", deleted = mapOf("key:${SyncKey.SUBSOURCE}" to time - 3_600_000)), "mac")
        assertEquals("test-subsource-key", services.credential(SubtitleCredential.SUBSOURCE_KEY))
    }
}
