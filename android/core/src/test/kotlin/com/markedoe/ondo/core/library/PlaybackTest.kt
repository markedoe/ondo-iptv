package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.DAY
import com.markedoe.ondo.core.model.AccountInfo
import com.markedoe.ondo.core.model.CatchupInfo
import com.markedoe.ondo.core.model.Channel
import com.markedoe.ondo.core.model.ContentType
import com.markedoe.ondo.core.model.EpgProgram
import com.markedoe.ondo.core.model.Episode
import com.markedoe.ondo.core.model.HttpHeaders
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.Playlist
import com.markedoe.ondo.core.model.PlaylistSource
import com.markedoe.ondo.core.model.Series
import com.markedoe.ondo.core.model.XtreamCredentials
import com.markedoe.ondo.core.model.XtreamOutputFormat
import com.markedoe.ondo.core.utc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackTest {
    @Test
    fun numberingUsesExplicitNumbersWhenPresent() {
        val numbers = listOf(101, null, 7, 250)
        assertEquals(2, ChannelNumbering.position(7, numbers))
        assertEquals(3, ChannelNumbering.position(250, numbers))
        assertNull(ChannelNumbering.position(2, numbers), "positions are not used when the list has numbers")
        assertEquals(101, ChannelNumbering.displayNumber(0, numbers))
        assertNull(ChannelNumbering.displayNumber(1, numbers))
    }

    @Test
    fun numberingFallsBackToPositions() {
        val numbers = listOf<Int?>(null, null, null)
        assertEquals(0, ChannelNumbering.position(1, numbers))
        assertEquals(2, ChannelNumbering.position(3, numbers))
        assertNull(ChannelNumbering.position(4, numbers))
        assertNull(ChannelNumbering.position(0, numbers))
        assertEquals(3, ChannelNumbering.displayNumber(2, numbers))
        assertNull(ChannelNumbering.position(1, emptyList()))
        assertNull(ChannelNumbering.displayNumber(5, numbers))
    }

    private val playlist = Playlist(name = "P", source = PlaylistSource.Remote("http://example.com/list.m3u"))

    private fun channel(catchup: CatchupInfo?) =
        Channel(id = "c1", name = "News", categoryId = "x", streamUrl = "http://example.com/news.m3u8", catchup = catchup)

    @Test
    fun restartItemFromAnInstant() {
        val now = utc(2025, 10, 9, 20, 10)
        val start = utc(2025, 10, 9, 20, 0)
        val item = assertNotNull(
            PlaybackFactory(playlist).catchupItem(
                channel(CatchupInfo(CatchupInfo.Mode.SHIFT, 2)),
                start = start,
                end = utc(2025, 10, 9, 21, 0),
                title = "Evening News",
                now = now,
            ),
        )
        assertTrue(item.isCatchup)
        assertFalse(item.isLive)
        assertEquals("Evening News", item.title)
        assertEquals("News", item.subtitle)
        assertEquals(ContentType.LIVE, item.reference.type)
        assertEquals("c1", item.reference.itemId)
        assertEquals("http://example.com/news.m3u8?utc=1760040000&lutc=1760040600", item.streamUrl)
        assertNull(item.headers, "no header to send")
    }

    @Test
    fun noRestartWithoutArchive() {
        val now = utc(2025, 10, 9, 20, 10)
        val factory = PlaybackFactory(playlist)
        assertNull(factory.catchupItem(channel(null), start = now - 600_000, end = now, now = now))
        val archived = channel(CatchupInfo(CatchupInfo.Mode.SHIFT, 1))
        assertNull(factory.catchupItem(archived, start = now - 3 * DAY, end = now, now = now), "older than the archive")
        assertNull(factory.catchupItem(archived, start = now + 60_000, end = now + 600_000, now = now), "future")
    }

    @Test
    fun catchupItemForAnXtreamProgrammeUsesTheServerTimeZone() {
        val credentials = XtreamCredentials("http://p.example.com", "u", "p")
        val xtream = Playlist(name = "X", source = PlaylistSource.Xtream(credentials), xtreamOutput = XtreamOutputFormat.TS)
        val account = AccountInfo(username = "u", status = "Active", serverTimeZone = "Europe/Paris")
        val c = Channel(
            id = "x1",
            name = "TF1",
            categoryId = "live-1",
            streamUrl = "http://p.example.com/live/u/p/1.ts",
            catchup = CatchupInfo(CatchupInfo.Mode.XTREAM, 3),
            xtreamStreamId = 1,
        )
        val program = EpgProgram("TF1.fr", utc(2025, 10, 9, 18), utc(2025, 10, 9, 19), "JT", iconUrl = "http://img/jt.png")
        val item = assertNotNull(PlaybackFactory(xtream, account).catchupItem(c, program, now = utc(2025, 10, 9, 20)))
        assertEquals(
            listOf(
                "http://p.example.com/timeshift/u/p/60/2025-10-09:20-00/1.ts",
                "http://p.example.com/timeshift/u/p/60/2025-10-09:20-00/1.m3u8",
                "http://p.example.com/streaming/timeshift.php?username=u&password=p&stream=1&start=2025-10-09:20-00&duration=60",
            ),
            item.urls,
        )
        assertEquals("JT", item.title)
        assertEquals("http://img/jt.png", item.artworkUrl)
        val badZone = PlaybackFactory(xtream, account.copy(serverTimeZone = "Mars/Olympus"))
        assertTrue(assertNotNull(badZone.catchupItem(c, program, now = utc(2025, 10, 9, 20))).streamUrl.contains("2025-10-09:18-00"), "unknown zones fall back to UTC")
    }

    @Test
    fun movieAndEpisodeItems() {
        val factory = PlaybackFactory(playlist.copy(userAgent = "Global"))
        val movie = Movie(
            id = "m",
            name = "Inception",
            posterUrl = "http://img/m.jpg",
            categoryId = "c",
            streamUrl = "http://x/m.mkv",
            year = 2010,
            headers = HttpHeaders(userAgent = "Item"),
        )
        val movieItem = factory.item(movie, startPosition = 42.0)
        assertEquals("2010", movieItem.subtitle)
        assertEquals(listOf("http://x/m.mkv"), movieItem.urls)
        assertEquals(mapOf("User-Agent" to "Item"), movieItem.headers?.toMap(), "item headers win")
        assertEquals(42.0, movieItem.startPosition)
        assertEquals("${playlist.id}|movie|m|", movieItem.id)

        val episode = Episode(id = "e1", title = "Pilot", season = 1, number = 1, streamUrl = "http://x/e1.mkv")
        val series = Series(id = "s", name = "Breaking Bad", coverUrl = "http://img/s.jpg", categoryId = "c", episodes = listOf(episode))
        val episodeItem = factory.item(episode, series)
        assertEquals("Breaking Bad", episodeItem.title)
        assertEquals("S01E01 · Pilot", episodeItem.subtitle)
        assertEquals("http://img/s.jpg", episodeItem.artworkUrl, "series cover when the episode has none")
        assertEquals("e1", episodeItem.reference.episodeId)
        assertEquals("${playlist.id}|series|s", episodeItem.reference.itemKey)
        assertEquals(mapOf("User-Agent" to "Global"), episodeItem.headers?.toMap())
    }

    @Test
    fun playbackItemNeedsAUrl() {
        assertFailsWith<IllegalArgumentException> {
            com.markedoe.ondo.core.model.PlaybackItem(
                title = "x",
                urls = emptyList(),
                reference = com.markedoe.ondo.core.model.MediaReference("p", ContentType.LIVE, "c"),
                isLive = true,
            )
        }
    }
}
