package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.CoreJson
import com.markedoe.ondo.core.Fixtures
import com.markedoe.ondo.core.MockHttpClient
import com.markedoe.ondo.core.model.ContentType
import com.markedoe.ondo.core.model.HistoryEntry
import com.markedoe.ondo.core.model.MediaCategory
import com.markedoe.ondo.core.model.MediaReference
import com.markedoe.ondo.core.model.PlaybackItem
import com.markedoe.ondo.core.model.Playlist
import com.markedoe.ondo.core.model.PlaylistContent
import com.markedoe.ondo.core.model.PlaylistSource
import com.markedoe.ondo.core.model.XtreamCredentials
import com.markedoe.ondo.core.model.XtreamOutputFormat
import com.markedoe.ondo.core.xtream.XtreamUrlBuilder
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class LibraryTest {
    private val playlistId = UUID.randomUUID().toString()

    private fun ref(type: ContentType, id: String, episode: String? = null) = MediaReference(playlistId, type, id, episode)

    @Test
    fun favoritesToggleAndOrder() {
        var library = UserLibrary()
        library = library.toggleFavorite(ref(ContentType.LIVE, "a"), "A", null)
        library = library.toggleFavorite(ref(ContentType.LIVE, "b"), "B", null)
        library = library.toggleFavorite(ref(ContentType.MOVIE, "m"), "M", null)
        assertTrue(library.isFavorite(ref(ContentType.LIVE, "a")))
        assertEquals(listOf("A", "B"), library.favorites(playlistId, ContentType.LIVE).map { it.title })

        library = library.moveFavorites(playlistId, ContentType.LIVE, fromIndices = setOf(1), toIndex = 0)
        assertEquals(listOf("B", "A"), library.favorites(playlistId, ContentType.LIVE).map { it.title })

        library = library.toggleFavorite(ref(ContentType.LIVE, "a"), "A", null)
        assertFalse(library.isFavorite(ref(ContentType.LIVE, "a")))
        assertEquals(2, library.favorites(playlistId).size)
    }

    @Test
    fun moveFavoritesDownwardsAndSeveralAtOnce() {
        var library = UserLibrary()
        for (name in listOf("A", "B", "C", "D")) library = library.toggleFavorite(ref(ContentType.LIVE, name), name, null)
        library = library.moveFavorites(playlistId, null, fromIndices = setOf(0), toIndex = 3)
        assertEquals(listOf("B", "C", "A", "D"), library.favorites(playlistId).map { it.title }, "SwiftUI move semantics")
        library = library.moveFavorites(playlistId, null, fromIndices = listOf(3, 0), toIndex = 2)
        assertEquals(listOf("C", "B", "D", "A"), library.favorites(playlistId).map { it.title })
    }

    @Test
    fun seriesFavoriteIsSeriesLevel() {
        val library = UserLibrary().toggleFavorite(ref(ContentType.SERIES, "s", episode = "e1"), "S", null)
        assertTrue(library.isFavorite(ref(ContentType.SERIES, "s")))
        assertTrue(library.isFavorite(ref(ContentType.SERIES, "s", episode = "e2")))
        assertNull(library.favorites.single().reference.episodeId)
    }

    @Test
    fun historyAndContinueWatching() {
        var library = UserLibrary()
        val t0 = 1_000_000_000L
        library = library.recordPlayback(HistoryEntry(ref(ContentType.MOVIE, "m1"), "M1", lastWatchedAt = t0))
        library = library.updateProgress(ref(ContentType.MOVIE, "m1"), 600.0, 6000.0, now = t0)
        library = library.recordPlayback(HistoryEntry(ref(ContentType.SERIES, "s", "e1"), "S", lastWatchedAt = t0 + 10_000))
        library = library.updateProgress(ref(ContentType.SERIES, "s", "e1"), 1000.0, 2000.0, now = t0 + 10_000)
        library = library.recordPlayback(HistoryEntry(ref(ContentType.SERIES, "s", "e2"), "S", lastWatchedAt = t0 + 20_000))
        library = library.updateProgress(ref(ContentType.SERIES, "s", "e2"), 100.0, 2000.0, now = t0 + 20_000)
        library = library.recordPlayback(HistoryEntry(ref(ContentType.MOVIE, "done"), "Done", lastWatchedAt = t0))
        library = library.updateProgress(ref(ContentType.MOVIE, "done"), 5900.0, 6000.0, now = t0 + 30_000)
        library = library.recordPlayback(HistoryEntry(ref(ContentType.LIVE, "c1"), "C1", lastWatchedAt = t0 + 40_000))

        val resume = library.continueWatching(playlistId)
        assertEquals(
            listOf(ref(ContentType.SERIES, "s", "e2").playbackKey, ref(ContentType.MOVIE, "m1").playbackKey),
            resume.map { it.reference.playbackKey },
            "one entry per series (latest episode), finished and live excluded",
        )
        assertEquals(listOf("C1"), library.recentChannels(playlistId).map { it.title })
        assertEquals("C1", library.lastChannel(playlistId)?.title)
        assertEquals(600.0, library.progress(ref(ContentType.MOVIE, "m1"))?.position)

        // Re-opening keeps the resume position.
        library = library.recordPlayback(HistoryEntry(ref(ContentType.MOVIE, "m1"), "M1", lastWatchedAt = t0 + 50_000))
        assertEquals(600.0, library.progress(ref(ContentType.MOVIE, "m1"))?.position)
        assertEquals("M1", library.history.first().title)
    }

    @Test
    fun recordStartFromPlaybackItem() {
        val movie = PlaybackItem(
            title = "Movie",
            subtitle = "2010",
            urls = listOf("http://x/m.mkv"),
            reference = ref(ContentType.MOVIE, "m"),
            isLive = false,
            artworkUrl = "http://img/m.jpg",
            startPosition = 120.0,
        )
        var library = UserLibrary().recordStart(movie, now = 5_000)
        val entry = library.history.single()
        assertEquals("Movie", entry.title)
        assertEquals("2010", entry.subtitle)
        assertEquals("http://img/m.jpg", entry.imageUrl)
        assertEquals(5_000, entry.lastWatchedAt)
        assertEquals(120.0, entry.position)

        val live = movie.copy(reference = ref(ContentType.LIVE, "c"), isLive = true, startPosition = 300.0)
        library = library.recordStart(live, now = 6_000)
        assertEquals(0.0, library.progress(live.reference)?.position, "live channels never keep a position")

        library = library.updateProgress(movie.reference, 900.0, Double.NaN, now = 7_000)
        assertEquals(0.0, library.progress(movie.reference)?.duration, "invalid durations are ignored")
        library = library.recordStart(movie.copy(startPosition = null), now = 8_000)
        assertEquals(900.0, library.progress(movie.reference)?.position, "restarting keeps the known position")
        assertEquals(listOf("Movie", "Movie"), library.history.map { it.title })
        assertEquals(UserLibrary(), UserLibrary().updateProgress(movie.reference, 1.0, 2.0), "unknown items are not added")
    }

    @Test
    fun historyEntryProgressFlags() {
        val movie = HistoryEntry(ref(ContentType.MOVIE, "m"), "M", position = 31.0, duration = 100.0)
        assertTrue(movie.isResumable)
        assertFalse(movie.isFinished)
        assertEquals(0.31, movie.progress, 1e-9)
        assertFalse(movie.copy(position = 30.0).isResumable, "barely started")
        assertTrue(movie.copy(position = 95.0).isFinished)
        assertEquals(1.0, movie.copy(position = 500.0).progress)
        assertEquals(0.0, movie.copy(duration = 0.0).progress)
        assertFalse(HistoryEntry(ref(ContentType.LIVE, "c"), "C", position = 50.0, duration = 100.0).isResumable)
    }

    @Test
    fun historyCap() {
        var library = UserLibrary()
        for (i in 0 until UserLibrary.MAX_HISTORY + 20) {
            library = library.recordPlayback(HistoryEntry(ref(ContentType.LIVE, "c$i"), "C$i"))
        }
        assertEquals(UserLibrary.MAX_HISTORY, library.history.size)
        assertEquals("C${UserLibrary.MAX_HISTORY + 19}", library.history.first().title)
    }

    @Test
    fun removeAndClearHistory() {
        var library = UserLibrary()
            .recordPlayback(HistoryEntry(ref(ContentType.MOVIE, "a"), "A", lastWatchedAt = 1))
            .recordPlayback(HistoryEntry(ref(ContentType.MOVIE, "b"), "B", lastWatchedAt = 2))
        library = library.removeHistory(library.history.first { it.title == "A" }, now = 10)
        assertEquals(listOf("B"), library.history.map { it.title })
        assertEquals(10L, library.historyTombstones[ref(ContentType.MOVIE, "a").playbackKey])
        library = library.clearHistory(now = 20)
        assertTrue(library.history.isEmpty())
        assertTrue(library.historyTombstones.isEmpty())
        assertEquals(20L, library.historyClearedAt)
    }

    @Test
    fun categoriesAndCleanup() {
        var library = UserLibrary().setCategoryHidden(true, playlistId, "live-1")
        assertTrue(library.isCategoryHidden(playlistId, "live-1"))
        val content = PlaylistContent(
            liveCategories = listOf(
                MediaCategory("live-xxx", "XXX | Adult", ContentType.LIVE),
                MediaCategory("live-news", "News", ContentType.LIVE),
            ),
        )
        library = library.lockAdultCategories(playlistId, content)
        assertTrue(library.isCategoryLocked(playlistId, "live-xxx"))
        assertFalse(library.isCategoryLocked(playlistId, "live-news"))
        library = library.setCategoryLocked(false, playlistId, "live-xxx")
        assertFalse(library.isCategoryLocked(playlistId, "live-xxx"))
        library = library.setCategoryLocked(true, playlistId, "live-xxx")

        library = library.toggleFavorite(ref(ContentType.LIVE, "a"), "A", null)
        library = library.recordPlayback(HistoryEntry(ref(ContentType.LIVE, "a"), "A"))
        val other = MediaReference(UUID.randomUUID().toString(), ContentType.LIVE, "z")
        library = library.toggleFavorite(other, "Z", null)
        library = library.removeData(playlistId)
        assertEquals(listOf("Z"), library.favorites.map { it.title })
        assertTrue(library.history.isEmpty())
        assertTrue(library.hiddenCategoryKeys.isEmpty())
        assertTrue(library.lockedCategoryKeys.isEmpty())
    }

    @Test
    fun fileStoreRoundTripAndBackup() {
        val directory = Files.createTempDirectory("ondo-store").toFile()
        try {
            val store = JsonFileStore(directory)
            val library = UserLibrary().toggleFavorite(ref(ContentType.LIVE, "a"), "A", "http://img/a.png")
            store.save(library, "library")
            assertEquals(library, store.load<UserLibrary>("library"))

            // A corrupted main file falls back to the previous version.
            store.save(library, "library")
            store.file("library").writeText("{corrupt")
            assertEquals(library, store.load<UserLibrary>("library"))

            store.remove("library")
            assertNull(store.load<UserLibrary>("library"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun aDamagedFileIsKeptAsideAndNeverRotatedOverTheBackup() {
        val directory = Files.createTempDirectory("ondo-store").toFile()
        try {
            val store = JsonFileStore(directory, clock = { 1_800_000_000_000L })
            val library = UserLibrary().toggleFavorite(ref(ContentType.LIVE, "a"), "A", null)
            store.save(library, "library")
            store.save(library, "library")
            store.file("library").writeText("{corrupt")

            assertEquals(library, store.load<UserLibrary>("library"))
            assertEquals("{corrupt", File(directory, "library.json.corrupt-1800000000.json").readText())

            // The next save does not rotate the damaged file into `.bak`: the good version is still there.
            store.save(UserLibrary(), "library")
            store.file("library").delete()
            assertEquals(library, store.load<UserLibrary>("library"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun withoutAnyReadableVersionNothingIsLost() {
        val directory = Files.createTempDirectory("ondo-store").toFile()
        try {
            val store = JsonFileStore(directory, clock = { 7_000L })
            store.file("playlists").writeText("garbage")
            assertNull(store.load<List<Playlist>>("playlists"))
            store.save(emptyList<Playlist>(), "playlists")
            assertEquals("garbage", File(directory, "playlists.json.corrupt-7.json").readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun backupArchive() {
        val playlist = Playlist(name = "Mine", source = PlaylistSource.Xtream(XtreamCredentials("http://p", "u", "")))
        val library = UserLibrary().toggleFavorite(ref(ContentType.LIVE, "a"), "A", null)
        val decoded = BackupArchive.decode(BackupArchive(exportedAt = 1L, playlists = listOf(playlist), library = library).encoded())
        assertEquals(listOf(playlist), decoded.playlists)
        assertEquals(library, decoded.library)
        assertEquals(1, decoded.version)
    }

    @Test
    fun playlistRefreshPolicy() {
        val now = System.currentTimeMillis()
        var playlist = Playlist(name = "P", source = PlaylistSource.Remote("http://x"), autoRefreshHours = 12)
        assertTrue(playlist.needsRefresh())
        playlist = playlist.copy(lastRefreshedAt = now - 3_600_000)
        assertFalse(playlist.needsRefresh(now))
        playlist = playlist.copy(lastRefreshedAt = now - 13 * 3_600_000L)
        assertTrue(playlist.needsRefresh(now))
        playlist = playlist.copy(autoRefreshHours = 0)
        assertFalse(playlist.needsRefresh(now))
    }
}

class PlaylistDecodingTest {
    @Test
    fun oldPlaylistJsonWithoutNewFieldsStillDecodes() {
        val json = """{"id":"7C1C2B8E-3E0B-4F65-9A57-3C0E2D7A1F00","name":"Old","source":{"type":"remote","url":"http://x.example.com/list.m3u"},"unknown":1}"""
        val playlist = CoreJson.decodeFromString(Playlist.serializer(), json)
        assertEquals("Old", playlist.name)
        assertEquals("7C1C2B8E-3E0B-4F65-9A57-3C0E2D7A1F00", playlist.id)
        assertEquals(PlaylistSource.Remote("http://x.example.com/list.m3u"), playlist.source)
        assertEquals(24, playlist.autoRefreshHours)
        assertFalse(playlist.xtreamForceM3u)
        assertTrue(playlist.useProviderEpg)
        assertEquals(XtreamOutputFormat.M3U8, playlist.xtreamOutput)
    }

    @Test
    fun playlistWithoutIdIsRejectedAndUnknownEnumsFallBack() {
        val withoutId = """{"name":"Old","source":{"type":"local","path":"/data/p.m3u"}}"""
        assertTrue(runCatching { CoreJson.decodeFromString(Playlist.serializer(), withoutId) }.isFailure, "the id is required")
        val unknownFormat = """{"id":"1","name":"P","source":{"type":"local","path":"/p.m3u"},"xtreamOutput":"rtmp"}"""
        assertEquals(XtreamOutputFormat.M3U8, CoreJson.decodeFromString(Playlist.serializer(), unknownFormat).xtreamOutput)
    }

    @Test
    fun secrets() {
        val xtream: PlaylistSource = PlaylistSource.Xtream(XtreamCredentials("http://p", "u", "pw"))
        assertEquals("pw", xtream.secret)
        assertEquals("new", xtream.withSecret("new").secret)
        assertNull(PlaylistSource.Remote("http://x").secret)
        assertEquals(PlaylistSource.LocalFile("/f"), PlaylistSource.LocalFile("/f").withSecret("ignored"))
        assertEquals("Xtream Codes", xtream.kindLabel)
    }

    @Test
    fun roundTrip() {
        val playlist = Playlist(
            name = "P",
            source = PlaylistSource.Xtream(XtreamCredentials("http://p", "u", "p")),
            xtreamForceM3u = true,
            userAgent = "UA",
            referrer = "http://ref",
            extraHeaders = mapOf("X-Key" to "1"),
            epgUrls = listOf("http://epg"),
        )
        val decoded = CoreJson.decodeFromString(Playlist.serializer(), CoreJson.encodeToString(Playlist.serializer(), playlist))
        assertEquals(playlist, decoded)
        assertEquals(mapOf("X-Key" to "1", "User-Agent" to "UA", "Referer" to "http://ref"), decoded.headers.toMap())
    }

    @Test
    fun forceM3uUsesGetPhp() = runTest {
        val credentials = XtreamCredentials("http://panel.example.com:8080", "demo", "pw")
        val http = MockHttpClient()
        http.routes["api:"] = Fixtures.bytes("xtream_auth.json")
        http.routes[XtreamUrlBuilder(credentials).m3uUrl(XtreamOutputFormat.M3U8)] = Fixtures.bytes("sample.m3u")
        val playlist = Playlist(name = "P", source = PlaylistSource.Xtream(credentials), xtreamForceM3u = true)
        val content = PlaylistLoader(http).load(playlist)
        assertEquals(7, content.channels.size)
        assertEquals(2, content.account?.maxConnections)
    }
}
