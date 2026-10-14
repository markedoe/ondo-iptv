package com.markedoe.ondo.core

import com.markedoe.ondo.core.net.HttpClient
import com.markedoe.ondo.core.net.HttpException
import com.markedoe.ondo.core.net.Urls
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Test resources copied from the Swift package (`src/test/resources/fixtures`). */
object Fixtures {
    fun bytes(name: String): ByteArray =
        Fixtures::class.java.getResourceAsStream("/fixtures/$name")?.use { it.readBytes() }
            ?: error("Missing fixture $name")
}

/** Serves fixtures based on the Xtream `action` query item (`api:<action>`) or the full URL. */
class MockHttpClient : HttpClient {
    val routes = ConcurrentHashMap<String, ByteArray>()
    val requested = CopyOnWriteArrayList<String>()
    val requestHeaders = CopyOnWriteArrayList<Map<String, String>>()

    /** Errors thrown, one per request, before the route answers (a flaky or busy server). */
    val failures = ConcurrentHashMap<String, MutableList<Exception>>()

    override suspend fun get(url: String, headers: Map<String, String>): ByteArray {
        requested.add(url)
        requestHeaders.add(headers)
        val parsed = Urls.parse(url)
        val key = if (parsed?.path?.endsWith("player_api.php") == true) {
            "api:" + (Urls.queryItems(parsed.rawQuery).firstOrNull { it.first == "action" }?.second ?: "")
        } else {
            url
        }
        synchronized(failures) { failures[key]?.removeFirstOrNull() }?.let { throw it }
        return routes[key] ?: throw HttpException.BadStatus(404)
    }
}

/** Epoch millis of `year-month-day hour:minute` UTC. */
fun utc(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
    LocalDateTime.of(year, month, day, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

const val HOUR = 3_600_000L
const val DAY = 86_400_000L
