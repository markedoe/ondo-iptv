package com.markedoe.ondo.ui

import androidx.lifecycle.viewModelScope
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.model.Episode
import com.markedoe.ondo.core.model.Series
import com.markedoe.ondo.data.AppStore
import com.markedoe.ondo.data.FakeHttp
import com.markedoe.ondo.data.ItemKind
import com.markedoe.ondo.data.OndoRepository
import com.markedoe.ondo.data.WatchEntry
import com.markedoe.ondo.player.PlayRequest
import com.markedoe.ondo.ui.model.ResumeItem
import com.markedoe.ondo.ui.model.ShowcaseItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * An episode watched, whichever way it was started (the series page, Continue watching, Up next, a series' Play from
 * search or a row, the TV player's Episodes row), shows on every surface that shows episodes: watched, or how far it
 * went, in the season list (phone and TV) and the player's Episodes row; Continue watching, the series page's Play and
 * Up next go on with the next episode. A new surface or a new way in is added here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WatchedEpisodesEverywhereTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @get:Rule val folder = TemporaryFolder()

    private val list = "http://provider.example.com/list.m3u"
    private val catalogue = """
        #EXTM3U
        #EXTINF:2700 group-title="Series | Drama",Harbor S01E01
        http://provider.example.com/h101.mp4
        #EXTINF:2700 group-title="Series | Drama",Harbor S01E02
        http://provider.example.com/h102.mp4
        #EXTINF:2700 group-title="Series | Drama",Harbor S01E03
        http://provider.example.com/h103.mp4
        #EXTINF:2700 group-title="Series | Drama",Harbor S02E01
        http://provider.example.com/h201.mp4
        #EXTINF:2700 group-title="Series | Drama",Harbor S02E02
        http://provider.example.com/h202.mp4
    """.trimIndent()

    private val models = mutableListOf<OndoViewModel>()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        models.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    private fun TestScope.settle() = repeat(20) { testScheduler.runCurrent() }

    @Test
    fun anEpisodeWatchedAnyWayInShowsOnEverySurface() = runTest {
        val repository = OndoRepository(AppStore(folder.root), FakeHttp(mapOf(list to catalogue)), backgroundScope, io = StandardTestDispatcher(testScheduler))
        val viewModel = OndoViewModel(repository, Labels(), computation = Dispatchers.Unconfined, minutes = flowOf(1_760_000_000_000L)).also { models += it }
        // The view model reads what is saved (bootstrap) as it starts; its state, once read, replaces the playlists and
        // settings: a playlist added before then would lose its place as the active one. As the app, which shows
        // nothing to add one before.
        viewModel.state.first { it.isReady }
        assertTrue(repository.addPlaylist("Home", list).isSuccess)
        val series: Series = viewModel.state.first { it.content.series.isNotEmpty() }.content.series.single()
        // The series page was opened: its seasons are known.
        val episodes: List<Episode> = repository.details(series).seasons.flatMap { it.episodes }
        assertEquals(5, episodes.size)

        fun key(episode: Episode) = WatchEntry(viewModel.state.value.activePlaylistId!!, ItemKind.SERIES, series.id, episode.id, title = "", lastWatchedAt = 0).key
        suspend fun played(start: () -> Unit): PlayRequest {
            start()
            settle()
            return viewModel.playRequests.first()
        }
        // What the player saves at the end of an episode (or when it stops in its end credits).
        fun finish(request: PlayRequest) = repository.recordProgress(request.progressKey!!, 2_700.0, 2_700.0)

        // Every surface that shows how far an episode went: watched, or the share seen.
        val surfaces: Map<String, (Episode) -> Pair<Boolean, Float>> = mapOf(
            "series page's season list (phone and TV)" to { episode ->
                UiMappers.seriesDetail(viewModel.state.value, series, null, null).episodeProgress[episode.id]
                    .let { (it?.finished == true) to (it?.fraction ?: 0f) }
            },
            "TV player's Episodes row" to { episode ->
                repository.episodeProgress(series, episode.id).let { (it?.isFinished == true) to (it?.progress?.toFloat() ?: 0f) }
            },
        )
        // Every surface that goes on with the next episode: its id, null when there is none.
        fun nextOn(playing: PlayRequest): Map<String, String?> {
            val state = viewModel.state.value
            fun card(cards: List<ResumeItem>) = // A card's key: playlist|kind|series|episode.
            cards.singleOrNull()?.key?.split('|')?.getOrNull(3)
            return mapOf(
                "Home's Continue watching" to card(UiMappers.home(state, Labels(), 1_760_000_000_000L).continueWatching),
                "Series tab's Continue watching" to card(UiMappers.vod(state, series = true, title = "").continueWatching),
                "series page's Play" to UiMappers.seriesDetail(state, series, null, null).next?.id,
                "the player's Up next" to repository.nextEpisode(playing)?.second?.id,
            )
        }

        fun check(way: String, playing: PlayRequest, watched: Set<Episode>, next: Episode?, halfway: Episode? = null) {
            for ((surface, shown) in surfaces) {
                for (episode in episodes) {
                    val (finished, fraction) = shown(episode)
                    assertEquals("$way → $surface: ${episode.code} watched", episode in watched, finished)
                    if (episode == halfway) assertEquals("$way → $surface: ${episode.code} a third seen", 1f / 3, fraction, 0.01f)
                }
            }
            for ((surface, id) in nextOn(playing)) {
                // Up next follows the episode playing; the other surfaces the last one watched.
                val expected = if (surface == "the player's Up next") episodes.getOrNull(episodes.indexOfFirst { key(it) == playing.progressKey } + 1) else next
                assertEquals("$way → $surface", expected?.id, id)
            }
        }

        // The series page: an episode chosen in the season list.
        val first = played { viewModel.playEpisode(series.id, episodes[0]) }
        assertEquals(key(episodes[0]), first.progressKey)
        finish(first)
        check("series page", first, setOf(episodes[0]), next = episodes[1])

        // Continue watching: its card is the next episode; stopped a third of the way, then watched to the end.
        val card = UiMappers.home(viewModel.state.value, Labels(), 1_760_000_000_000L).continueWatching.single()
        val resumed = played { viewModel.resume(card.key) }
        assertEquals(key(episodes[1]), resumed.progressKey)
        repository.recordProgress(resumed.progressKey!!, 900.0, 2_700.0)
        check("Continue watching, stopped", resumed, setOf(episodes[0]), next = episodes[1], halfway = episodes[1])
        finish(resumed)
        check("Continue watching", resumed, setOf(episodes[0], episodes[1]), next = episodes[2])

        // Up next: the player plays the episode after the one that ended.
        val (nextSeries, nextEpisode) = repository.nextEpisode(resumed)!!
        val upNext = repository.playRequest(nextSeries, nextEpisode)!!
        assertEquals(key(episodes[2]), upNext.progressKey)
        finish(upNext)
        check("Up next", upNext, episodes.take(3).toSet(), next = episodes[3])

        // A series' Play (a search result, a row, My List, the carousel): its next episode, the next season's first.
        val fromSearch = played { viewModel.play(ShowcaseItem.SeriesItem(series)) }
        assertEquals(key(episodes[3]), fromSearch.progressKey)
        finish(fromSearch)
        check("a series' Play", fromSearch, episodes.take(4).toSet(), next = episodes[4])

        // The first episode watched again from the season list, stopped halfway: still watched everywhere, from its
        // start, and the series still goes on after the last one watched.
        val again = played { viewModel.playEpisode(series.id, episodes[0]) }
        assertEquals(key(episodes[0]), again.progressKey)
        assertEquals(0L, again.startPositionMs)
        repository.recordProgress(again.progressKey!!, 1_350.0, 2_700.0)
        check("watched again", again, episodes.take(4).toSet(), next = episodes[4])

        // The TV player's Episodes row: the episode chosen there; the last one, so the series leaves Continue watching.
        val chosen = repository.playRequest(series, episodes[4])!!
        finish(chosen)
        val all = episodes.toSet()
        for ((surface, shown) in surfaces) for (episode in episodes) assertTrue("Episodes row → $surface: ${episode.code}", shown(episode).first)
        assertTrue(UiMappers.home(viewModel.state.value, Labels(), 1_760_000_000_000L).continueWatching.isEmpty())
        assertTrue(UiMappers.vod(viewModel.state.value, series = true, title = "").continueWatching.isEmpty())
        assertEquals(all, episodes.filter { repository.episodeProgress(series, it.id)?.isFinished == true }.toSet())
    }
}
