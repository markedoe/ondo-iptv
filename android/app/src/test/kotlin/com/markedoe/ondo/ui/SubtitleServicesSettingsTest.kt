package com.markedoe.ondo.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.net.HttpClient
import com.markedoe.ondo.core.net.HttpException
import com.markedoe.ondo.core.subtitles.SubtitleService
import com.markedoe.ondo.data.SubtitleCredential
import com.markedoe.ondo.data.SubtitleServices
import com.markedoe.ondo.ui.components.SubtitleServicesSettings
import com.markedoe.ondo.ui.theme.OndoTheme
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Settings → Subtitle services: each key is checked with its service when saved, and the screen says what the service
 * answered (accepted, refused, out of reach); only an accepted key is kept, on this device only.
 */
@RunWith(RobolectricTestRunner::class)
class SubtitleServicesSettingsTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** Each service's answer, by the address without its query. */
    private class Http : HttpClient {
        val answers = HashMap<String, Any>()
        val asked = ArrayList<String>()

        private fun answer(url: String): ByteArray {
            asked += url
            val answer = answers[url.substringBefore('?')] ?: throw HttpException.BadStatus(404)
            if (answer is Throwable) throw answer
            return (answer as String).toByteArray()
        }

        override suspend fun get(url: String, headers: Map<String, String>) = answer(url)

        override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray, contentType: String) = answer(url)
    }

    private val http = Http()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences(SubtitleServices.FILE, Context.MODE_PRIVATE).apply { edit().clear().commit() }
    private val services = SubtitleServices(prefs, http, "Ondo test", context.filesDir.resolve("subtitles"), io = Dispatchers.Unconfined)

    private fun start() {
        compose.setContent {
            OndoTheme {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) { SubtitleServicesSettings(services) }
            }
        }
        compose.waitForIdle()
    }

    private fun type(field: SubtitleCredential, text: String) {
        compose.onNodeWithTag("subtitleField-${field.name}").performScrollTo().performTextInput(text)
    }

    private fun checkAndSave(service: SubtitleService) {
        compose.onNodeWithTag("subtitleCheck-${service.name}").performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun status(service: SubtitleService) = compose.onNodeWithTag("subtitleStatus-${service.name}")

    @Test
    fun aKeyTheServiceAcceptsIsSavedAndSaidSo() {
        http.answers["https://api.subdl.com/api/v1/subtitles"] = """{"status":true,"subtitles":[]}"""
        start()
        type(SubtitleCredential.SUBDL_KEY, " example-subdl-key ")
        checkAndSave(SubtitleService.SUBDL)
        status(SubtitleService.SUBDL).assertTextEquals("Accepted by SubDL.")
        assertEquals("example-subdl-key", services.credential(SubtitleCredential.SUBDL_KEY))
        assertTrue(SubtitleService.SUBDL in services.state.value.saved)
        // Asked with the key typed, once.
        assertTrue(http.asked.single().contains("api_key=example-subdl-key"))
        // Remove takes it away.
        compose.onNodeWithTag("subtitleRemove-SUBDL").performScrollTo().performClick()
        compose.waitForIdle()
        assertNull(services.credential(SubtitleCredential.SUBDL_KEY))
    }

    @Test
    fun aKeyTheServiceRefusesIsNotSaved() {
        http.answers["https://api.subdl.com/api/v1/subtitles"] = """{"status":false,"error":"Invalid API key"}"""
        http.answers["https://api.subsource.net/api/v1/movies/search"] = HttpException.BadStatus(401)
        start()
        type(SubtitleCredential.SUBDL_KEY, "wrong")
        checkAndSave(SubtitleService.SUBDL)
        status(SubtitleService.SUBDL).assertTextEquals("SubDL refused the API key, so nothing was saved. Copy it again from your account.")
        type(SubtitleCredential.SUBSOURCE_KEY, "wrong")
        checkAndSave(SubtitleService.SUBSOURCE)
        status(SubtitleService.SUBSOURCE).assertTextEquals("SubSource refused the API key, so nothing was saved. Copy it again from your account.")
        assertNull(services.credential(SubtitleCredential.SUBDL_KEY))
        assertNull(services.credential(SubtitleCredential.SUBSOURCE_KEY))
        assertFalse(services.state.value.any)
    }

    @Test
    fun anOpenSubtitlesAccountIsCheckedWithItsKey() {
        http.answers["https://api.opensubtitles.com/api/v1/infos/formats"] = """{"data":{"output_formats":["srt"]}}"""
        http.answers["https://api.opensubtitles.com/api/v1/login"] = HttpException.BadStatus(401)
        start()
        type(SubtitleCredential.OPEN_SUBTITLES_KEY, "example-api-key")
        type(SubtitleCredential.OPEN_SUBTITLES_USERNAME, "example-user")
        type(SubtitleCredential.OPEN_SUBTITLES_PASSWORD, "wrong")
        checkAndSave(SubtitleService.OPEN_SUBTITLES)
        status(SubtitleService.OPEN_SUBTITLES).assertTextEquals("OpenSubtitles refused the API key or the username and password, so nothing was saved.")
        assertNull(services.credential(SubtitleCredential.OPEN_SUBTITLES_KEY))
        // The right password: key and account saved together.
        http.answers["https://api.opensubtitles.com/api/v1/login"] = """{"token":"example-session-token","base_url":"api.opensubtitles.com"}"""
        checkAndSave(SubtitleService.OPEN_SUBTITLES)
        status(SubtitleService.OPEN_SUBTITLES).assertTextEquals("Accepted by OpenSubtitles.")
        assertEquals("example-user", services.credential(SubtitleCredential.OPEN_SUBTITLES_USERNAME))
        assertEquals("wrong", services.credential(SubtitleCredential.OPEN_SUBTITLES_PASSWORD))
    }

    @Test
    fun aServiceOutOfReachSavesNothing() {
        http.answers["https://api.subsource.net/api/v1/movies/search"] = IOException("offline")
        start()
        type(SubtitleCredential.SUBSOURCE_KEY, "example-subsource-key")
        checkAndSave(SubtitleService.SUBSOURCE)
        status(SubtitleService.SUBSOURCE).assertTextEquals("SubSource could not be reached, so nothing was saved. Check the connection and try again.")
        assertNull(services.credential(SubtitleCredential.SUBSOURCE_KEY))
    }

    @Test
    fun addic7edIsOffUntilTurnedOn() {
        start()
        assertFalse(services.state.value.addic7ed)
        compose.onNodeWithTag("addic7edSwitch").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(services.state.value.addic7ed)
        assertEquals(listOf(SubtitleService.ADDIC7ED), services.search().providers.map { it.service })
    }

    /** With a key saved, a username and a password alone are checked with that key, and saved with it. */
    @Test
    fun anAccountIsAddedToASavedKey() {
        prefs.edit().putString(SubtitleCredential.OPEN_SUBTITLES_KEY.key, "example-api-key").commit()
        http.answers["https://api.opensubtitles.com/api/v1/infos/formats"] = """{"data":{"output_formats":["srt"]}}"""
        http.answers["https://api.opensubtitles.com/api/v1/login"] = """{"token":"example-session-token","base_url":"api.opensubtitles.com"}"""
        val services = SubtitleServices(prefs, http, "Ondo test", context.filesDir.resolve("subtitles"), io = Dispatchers.Unconfined)
        compose.setContent {
            OndoTheme {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) { SubtitleServicesSettings(services) }
            }
        }
        compose.waitForIdle()
        type(SubtitleCredential.OPEN_SUBTITLES_USERNAME, "example-user")
        type(SubtitleCredential.OPEN_SUBTITLES_PASSWORD, "example-password")
        checkAndSave(SubtitleService.OPEN_SUBTITLES)
        status(SubtitleService.OPEN_SUBTITLES).assertTextEquals("Accepted by OpenSubtitles.")
        assertTrue(http.asked.any { it.endsWith("/login") })
        assertEquals("example-api-key", services.credential(SubtitleCredential.OPEN_SUBTITLES_KEY))
        assertEquals("example-user", services.credential(SubtitleCredential.OPEN_SUBTITLES_USERNAME))
        assertEquals("example-password", services.credential(SubtitleCredential.OPEN_SUBTITLES_PASSWORD))
    }

    /** A username without its password is not saved unchecked: the screen asks for both, the service is not asked. */
    @Test
    fun aUsernameWithoutItsPasswordIsNotSaved() {
        start()
        type(SubtitleCredential.OPEN_SUBTITLES_KEY, "example-api-key")
        type(SubtitleCredential.OPEN_SUBTITLES_USERNAME, "example-user")
        checkAndSave(SubtitleService.OPEN_SUBTITLES)
        status(SubtitleService.OPEN_SUBTITLES).assertTextEquals("OpenSubtitles: enter both the username and the password, or neither.")
        assertTrue(http.asked.isEmpty())
        assertNull(services.credential(SubtitleCredential.OPEN_SUBTITLES_KEY))
        assertNull(services.credential(SubtitleCredential.OPEN_SUBTITLES_USERNAME))
    }

    /** A service that answers with its limit says nothing of the key: not "refused", nothing saved. */
    @Test
    fun aLimitReachedIsNotARefusedKey() {
        http.answers["https://api.subdl.com/api/v1/subtitles"] = """{"status":false,"error":"API rate limit exceeded"}"""
        start()
        type(SubtitleCredential.SUBDL_KEY, "example-subdl-key")
        checkAndSave(SubtitleService.SUBDL)
        status(SubtitleService.SUBDL).assertTextEquals("SubDL’s limit of requests is reached for now, so nothing was saved. Try again later.")
        assertNull(services.credential(SubtitleCredential.SUBDL_KEY))
    }
}
