package com.markedoe.ondo.core.playback

import com.markedoe.ondo.core.HangGuard
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule

/**
 * The player's title data never shows an address, a login, a password or a server: the table of
 * `fixtures/title-data/redaction.json`, the same file Apple's `RedactionTests` reads. Invented values only.
 */
class RedactionTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    private val table = Json.parseToJsonElement(File("../../fixtures/title-data/redaction.json").readText()).jsonObject

    @Test
    fun everyCaseReadsAsWritten() {
        val cases = table["cases"]!!.jsonArray
        assertTrue(cases.isNotEmpty())
        for (case in cases.map { it.jsonObject }) {
            val playlist = case["playlist"]?.jsonObject
            val secrets = case["secrets"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty() + Redaction.secretsOf(
                playlist?.get("addresses")?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
                playlist?.get("login")?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
            )
            assertEquals(case["out"]!!.jsonPrimitive.content, Redaction.clean(case["in"]!!.jsonPrimitive.content, secrets), case["name"]!!.jsonPrimitive.content)
        }
    }

    /** A provider's number shows; the M3U reader's hash of an address (its login and password in it) never does. */
    @Test
    fun onlyAProvidersIdShows() {
        val ids = table["ids"]!!.jsonArray.map { it.jsonObject }
        assertTrue(ids.isNotEmpty())
        for (row in ids) {
            val id = row["id"]!!.jsonPrimitive.content
            val shown = row["shown"]!!.jsonPrimitive.content.toBoolean()
            assertEquals(if (shown) id else null, Redaction.shownId(id), id)
        }
    }

    /** Whatever surrounds them, the playlist's password and server never come out. */
    @Test
    fun theLoginNeverComesOut() {
        val secrets = listOf("demouser", "s3cret-pass", "panel.example.com")
        for (text in listOf(
            "http://panel.example.com:8080/movie/demouser/s3cret-pass/12.mkv",
            "Copper Valley [demouser:s3cret-pass@panel.example.com]",
            "get.php?username=demouser&password=s3cret-pass",
            "PANEL.EXAMPLE.COM Copper Valley",
        )) {
            val shown = Redaction.clean(text, secrets)
            for (secret in secrets) assertTrue(secret !in shown.lowercase(), "$secret in $shown")
        }
    }
}
