package com.markedoe.ondo.ui

import androidx.lifecycle.viewModelScope
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.core.library.AwardsCatalogue
import com.markedoe.ondo.core.library.InMemoryMetadataStore
import com.markedoe.ondo.core.library.MetadataKey
import com.markedoe.ondo.core.library.MetadataTitles
import com.markedoe.ondo.core.library.PlaceholderNames
import com.markedoe.ondo.core.library.RowOrder
import com.markedoe.ondo.core.library.TitleMetadata
import com.markedoe.ondo.core.library.TmdbGenre
import com.markedoe.ondo.core.model.Channel
import com.markedoe.ondo.core.model.ContentType
import com.markedoe.ondo.core.model.MediaCategory
import com.markedoe.ondo.core.model.Movie
import com.markedoe.ondo.core.model.PlaylistContent
import com.markedoe.ondo.core.model.Series
import com.markedoe.ondo.data.AppSettings
import com.markedoe.ondo.data.AppStore
import com.markedoe.ondo.data.FakeHttp
import com.markedoe.ondo.data.Favorite
import com.markedoe.ondo.data.ItemKind
import com.markedoe.ondo.data.LibraryState
import com.markedoe.ondo.data.OndoRepository
import com.markedoe.ondo.data.OndoState
import com.markedoe.ondo.data.SavedPlaylist
import com.markedoe.ondo.data.WatchEntry
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.time.Instant
import java.time.ZoneId
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runners.MethodSorters

/**
 * Measures, does not judge: how long each stage of a cold start takes on a catalogue of a large panel (100,000 movies in
 * 400 categories, 15,000 series, 20,000 channels, what the device kept of TMDb for 5,000 titles). Each stage prints a
 * line starting with `COLDSTART:` (the CI log, the test's output, and `build/reports/coldstart/coldstart.txt`): the
 * first call (cold: the JVM's first, and the catalogue's caches empty where the stage says so), then the median of three
 * more. The stages' limits are only there to keep a hang from passing; the view model's cold open (stage 7) checks its
 * counts always and its time targets in the night's run (see there). It runs on its own (`./gradlew :app:coldStartTest`,
 * a 2 GB heap).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ColdStartTimingTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard(limitSeconds = 300)

    /** The catalogue and what goes with it, built once for the class and dropped after it (most of the test JVM's memory). */
    private class Built(
        val content: PlaylistContent,
        val library: LibraryState,
        val settings: AppSettings,
        /** As the device keeps them: under each title's name and year ([MetadataKey.title]). */
        val keptTitles: List<TitleMetadata>,
        /** By poster key ("m-12"), as [OndoRepository.keptPosterFacts] gives them to the mappers. */
        val kept: Map<String, TitleMetadata>,
        val awards: AwardsCatalogue,
    ) {
        fun state() = OndoState(
            isReady = true,
            settings = settings,
            playlists = listOf(PLAYLIST),
            content = content,
            library = library,
            awards = awards,
        )
    }

    companion object {
        @ClassRule @JvmField val folder = TemporaryFolder()

        private const val PLAYLIST_ID = "big"
        private val PLAYLIST = SavedPlaylist(PLAYLIST_ID, "Panel", "http://panel.example.com", username = "viewer", password = "example", addedAt = 1L)

        private const val MOVIES = 100_000
        private const val FILMS = 60_000
        private const val SERIES = 15_000
        private const val CHANNELS = 20_000
        private const val KEPT_MOVIES = 4_000
        private const val KEPT_SERIES = 1_000

        /** The time of the whole run: release dates are counted back from it. */
        private val NOW = System.currentTimeMillis()

        private val report = File("build/reports/coldstart/coldstart.txt")

        @Volatile private var built: Built? = null

        private fun built(): Built = built ?: generate().also { built = it }

        @BeforeClass @JvmStatic
        fun environment() {
            runCatching { report.parentFile?.mkdirs(); report.delete() }
            val runtime = Runtime.getRuntime()
            say("env maxHeap=${runtime.maxMemory() / 1_048_576} MB cores=${runtime.availableProcessors()} java=${System.getProperty("java.version")}")
        }

        @AfterClass @JvmStatic
        fun dropTheCatalogue() {
            built = null
            UiMappers.forgetCatalogue()
        }

        /** A line in the CI log (the JVM's own error stream reaches it, as HangGuard's), the test's output and the report file. */
        @Synchronized
        fun say(text: String) {
            val line = "COLDSTART: $text"
            println(line)
            runCatching { PrintStream(FileOutputStream(FileDescriptor.err), true).apply { println(line); flush() } }
            runCatching { report.parentFile?.mkdirs(); report.appendText(line + "\n") }
        }

        private val tags = listOf("FR", "EN", "DE", "ES", "IT", "PT", "AR", "TR", "NL", "PL")
        private val movieGenres = listOf(
            "Action", "Comedy", "Drama", "Horror", "Thriller", "Animation", "Documentary", "Family", "Romance", "Sci-Fi",
            "Crime", "Adventure", "Fantasy", "War", "Western", "Music", "History", "Mystery", "Kids", "Classics",
        )
        private val seriesGenres = listOf("Drama", "Comedy", "Crime", "Kids", "Documentary", "Sci-Fi", "Anime", "Reality", "Thriller", "Romance", "History", "Turkish Series")
        private val liveGenres = (1..40).map { "Group $it" }
        private val adjectives = listOf(
            "Silent", "Golden", "Broken", "Hidden", "Last", "Dark", "Bright", "Lost", "Wild", "Frozen", "Burning", "Quiet",
            "Endless", "Northern", "Southern", "Crimson", "Electric", "Hollow", "Iron", "Lonely", "Midnight", "Paper",
            "Rising", "Savage", "Secret", "Shattered", "Silver", "Stolen", "Sweet", "Twisted", "Velvet", "Wandering",
            "Ancient", "Bitter", "Blue", "Cold", "Distant", "Eternal", "Fallen", "Final", "Gentle", "Grand", "Green",
            "Heavy", "Holy", "Little", "Long", "Lucky", "Mad", "Night", "Old", "Pale", "Perfect", "Red", "Royal",
            "Sacred", "Scarlet", "Small", "Strange", "White",
        )
        private val nouns = listOf(
            "River", "Harbor", "Signal", "Orchard", "City", "Road", "Horizon", "Mountain", "Garden", "Island", "Station",
            "Kingdom", "Letter", "Mirror", "Promise", "Shadow", "Storm", "Summer", "Winter", "Valley", "Witness", "Echo",
            "Empire", "Fire", "Forest", "Ghost", "Heart", "Hunter", "Journey", "Knight", "Lake", "Legacy", "Machine",
            "Memory", "Moon", "Ocean", "Planet", "Queen", "Rain", "Secret", "Sky", "Soldier", "Song", "Spirit", "Star",
            "Stranger", "Sun", "Tide", "Tower", "Town", "Train", "Truth", "Voyage", "War", "Water", "Wind", "Wolf",
            "World", "Dream", "Bridge",
        )
        private val thirds = listOf(
            "Rises", "Returns", "Falls", "Awakens", "Begins", "Forever", "Reborn", "Unchained", "Untold", "Again",
            "Divided", "Revealed", "Redemption", "Origins", "Legacy", "Uprising", "Reckoning", "Covenant", "Resurrection",
            "Exodus", "Genesis", "Requiem", "Odyssey", "Prophecy", "Chronicles", "Saga", "Legend", "Story", "Diaries", "Files",
        )
        private val places = (1..50).map { "of ${nouns[(it * 7) % nouns.size]}${if (it > 25) " Bay" else " Hill"}" }

        private fun filmTitle(film: Int): String {
            val two = adjectives[film % adjectives.size] + " " + nouns[(film / adjectives.size) % nouns.size]
            return if (film < 3_600) two else two + " " + thirds[(film / 3_600) % thirds.size]
        }

        private fun seriesTitle(show: Int): String {
            val base = "The " + nouns[show % nouns.size] + " " + places[(show / nouns.size) % places.size]
            return if (show < 3_000) base else adjectives[(show / 3_000) % adjectives.size] + " " + base
        }

        private const val PLOT =
            "A stranger arrives in a quiet town where everyone keeps a secret, and the past comes back one night at a time."

        private fun generate(): Built {
            val start = System.nanoTime()
            val random = java.util.Random(42)
            val nowSeconds = NOW / 1_000
            val fiveYears = 5L * 365 * 86_400
            fun year(): Int = (2026 - (Math.abs(random.nextGaussian()) * 14).toInt()).coerceIn(1950, 2026)

            // 398 movie categories by language and genre (some in 4K), and two that look adult.
            val movieCategories = buildList {
                for (format in 0 until 2) for (tag in tags) for (genre in movieGenres) {
                    if (size >= 398) break
                    add(MediaCategory("v${size + 1}", if (format == 0) "$tag | $genre" else "$tag | $genre 4K", ContentType.MOVIE))
                }
                add(MediaCategory("v399", "XXX | Adult", ContentType.MOVIE))
                add(MediaCategory("v400", "FR | Adulte 18+", ContentType.MOVIE))
            }
            val seriesCategories = buildList {
                for (tag in tags) for (genre in seriesGenres) add(MediaCategory("s${size + 1}", "$tag | $genre", ContentType.SERIES))
            }
            val liveCategories = buildList {
                for (tag in tags) for (genre in liveGenres) add(MediaCategory("l${size + 1}", "$tag | $genre", ContentType.LIVE))
            }

            // Each film once, then 40,000 more versions of them (another language or 4K) in other categories.
            val filmYears = IntArray(FILMS) { year() }
            val movies = ArrayList<Movie>(MOVIES)
            for (index in 0 until MOVIES) {
                val film = if (index < FILMS) index else random.nextInt(FILMS)
                val category = if (index % 1_000 == 999) movieCategories[398 + index % 2] else movieCategories[random.nextInt(398)]
                val tag = category.name.substringBefore(" |")
                val filmYear = filmYears[film]
                val quality = if (category.name.endsWith("4K")) " 4K" else ""
                val id = (200_000 + index).toString()
                movies += Movie(
                    id = id,
                    name = "$tag - ${filmTitle(film)}$quality ($filmYear)",
                    posterUrl = if (index % 20 == 0) null else "https://img.example.com/t/p/w600_and_h900_bestv2/poster$id.jpg",
                    categoryId = category.id,
                    streamUrl = "http://panel.example.com/movie/viewer/example/$id.${if (index % 3 == 0) "mkv" else "mp4"}",
                    rating = if (index % 5 == 0) null else (random.nextInt(90) + 10) / 10.0,
                    year = if (index % 10 == 0) null else filmYear,
                    addedAt = nowSeconds - (random.nextDouble() * fiveYears).toLong(),
                    xtreamStreamId = 200_000 + index,
                    containerExtension = if (index % 3 == 0) "mkv" else "mp4",
                    tmdbId = if (film % 3 == 0) 1_000 + film else null,
                )
            }

            val series = ArrayList<Series>(SERIES)
            for (index in 0 until SERIES) {
                val category = seriesCategories[random.nextInt(seriesCategories.size)]
                val tag = category.name.substringBefore(" |")
                val showYear = year()
                val id = (500_000 + index).toString()
                series += Series(
                    id = id,
                    name = "$tag - ${seriesTitle(index)} ($showYear)",
                    coverUrl = if (index % 25 == 0) null else "https://img.example.com/t/p/w600_and_h900_bestv2/cover$id.jpg",
                    categoryId = category.id,
                    plot = if (index % 3 == 0) null else PLOT,
                    rating = if (index % 4 == 0) null else (random.nextInt(90) + 10) / 10.0,
                    year = if (index % 12 == 0) null else showYear,
                    genre = category.name.substringAfter("| "),
                    cast = if (index % 2 == 0) "Alex Example, Sam Sample, Jo Placeholder" else null,
                    director = if (index % 2 == 0) "Robin Example" else null,
                    addedAt = nowSeconds - (random.nextDouble() * fiveYears).toLong(),
                    xtreamSeriesId = 500_000 + index,
                )
            }

            val channels = ArrayList<Channel>(CHANNELS)
            for (index in 0 until CHANNELS) {
                val category = liveCategories[index % liveCategories.size]
                val tag = category.name.substringBefore(" |")
                channels += Channel(
                    id = (10_000 + index).toString(),
                    name = "$tag: ${nouns[index % nouns.size]} ${adjectives[(index / nouns.size) % adjectives.size]} ${if (index % 2 == 0) "HD" else "FHD"}",
                    number = index + 1,
                    logoUrl = if (index % 6 == 0) null else "https://img.example.com/logos/${10_000 + index}.png",
                    categoryId = category.id,
                    streamUrl = "http://panel.example.com/live/viewer/example/${10_000 + index}.ts",
                    epgId = if (index % 4 == 0) null else "ch${10_000 + index}.example",
                    xtreamStreamId = 10_000 + index,
                )
            }

            val content = PlaylistContent(
                channels = channels,
                movies = movies,
                series = series,
                liveCategories = liveCategories,
                movieCategories = movieCategories,
                seriesCategories = seriesCategories,
                fetchedAt = NOW,
            )

            // What TMDb said and the device kept: 4,000 films (half of them recent) and 1,000 series, a fifth of them
            // released in the last 90 days (the carousel's).
            val today = Instant.ofEpochMilli(NOW).atZone(ZoneId.systemDefault()).toLocalDate()
            val genres = listOf(TmdbGenre(28, "Action"), TmdbGenre(18, "Drama"), TmdbGenre(35, "Comedy"), TmdbGenre(53, "Thriller"))
            val keptTitles = ArrayList<TitleMetadata>()
            val keptFilms = LinkedHashSet<Int>()
            while (keptFilms.size < KEPT_MOVIES) {
                val film = random.nextInt(FILMS)
                if (keptFilms.size < KEPT_MOVIES / 2 && filmYears[film] < 2020) continue
                keptFilms += film
            }
            for ((rank, film) in keptFilms.withIndex()) {
                val movie = movies[film]
                val key = MetadataKey.title(false, movie.name, movie.year) ?: continue
                val recent = rank % 5 == 0
                keptTitles += TitleMetadata(
                    key = key, kind = MetadataKey.kind(false), tmdbId = 1_000 + film,
                    backdropUrl = "https://img.example.com/t/p/w1280/backdrop$film.jpg",
                    voteAverage = 5.0 + random.nextInt(40) / 10.0, voteCount = 10 + random.nextInt(20_000), fetchedAt = NOW,
                    genres = listOf(genres[film % genres.size]),
                    releaseDate = if (recent) today.minusDays(random.nextInt(90).toLong()).toString() else "${filmYears[film]}-0${1 + film % 9}-15",
                )
            }
            for (rank in 0 until KEPT_SERIES) {
                val show = series[(rank * 13) % SERIES]
                val key = MetadataKey.title(true, show.name, show.year) ?: continue
                keptTitles += TitleMetadata(
                    key = key, kind = MetadataKey.kind(true), tmdbId = 90_000 + rank,
                    backdropUrl = "https://img.example.com/t/p/w1280/series$rank.jpg",
                    voteAverage = 5.0 + random.nextInt(40) / 10.0, voteCount = 10 + random.nextInt(5_000), fetchedAt = NOW,
                    genres = listOf(genres[rank % genres.size]),
                    releaseDate = if (rank % 5 == 0) today.minusDays(random.nextInt(30).toLong()).toString() else "${show.year ?: 2015}-03-01",
                )
            }
            val kept = posterFacts(content, keptTitles)

            // A viewer's library: titles in progress, My List, favourite channels; two languages hidden.
            val history = (0 until 25).map { index ->
                val movie = movies[index * 3_001 % MOVIES]
                WatchEntry(PLAYLIST_ID, ItemKind.MOVIE, movie.id, title = movie.name, position = 600.0, duration = 6_000.0, lastWatchedAt = NOW - 1_000L - index * 3_600_000L)
            } + (0 until 10).map { index ->
                val show = series[index * 1_301 % SERIES]
                WatchEntry(PLAYLIST_ID, ItemKind.SERIES, show.id, "e1", show.name, position = 300.0, duration = 2_400.0, lastWatchedAt = NOW - 2_000L - index * 7_200_000L)
            } + (0 until 20).map { index ->
                val channel = channels[index * 997 % CHANNELS]
                WatchEntry(PLAYLIST_ID, ItemKind.CHANNEL, channel.id, title = channel.name, lastWatchedAt = NOW - 3_000L - index * 60_000L)
            }
            val favorites = (0 until 30).map { index ->
                val movie = movies[index * 2_003 % MOVIES]
                Favorite(PLAYLIST_ID, ItemKind.MOVIE, movie.id, movie.name, addedAt = NOW - index * 86_400_000L)
            } + (0 until 10).map { index ->
                val show = series[index * 701 % SERIES]
                Favorite(PLAYLIST_ID, ItemKind.SERIES, show.id, show.name, addedAt = NOW - index * 86_400_000L)
            } + (0 until 15).map { index ->
                val channel = channels[index * 499 % CHANNELS]
                Favorite(PLAYLIST_ID, ItemKind.CHANNEL, channel.id, channel.name, addedAt = NOW - index * 86_400_000L)
            }
            val library = LibraryState(favorites = favorites, history = history, hiddenTags = setOf("AR", "TR"))

            val awards = File("src/main/assets/awards.json").takeIf { it.isFile }?.let { AwardsCatalogue.parse(it.readText()) } ?: AwardsCatalogue.EMPTY
            say(
                "fixture movies=${movies.size} films=$FILMS movieCategories=${movieCategories.size} series=${series.size} " +
                    "seriesCategories=${seriesCategories.size} channels=${channels.size} liveCategories=${liveCategories.size} " +
                    "keptTitles=${keptTitles.size} keptPosterKeys=${kept.size} awardsLoaded=${awards !== AwardsCatalogue.EMPTY} " +
                    "built in ${ms(start)} ms",
            )
            return Built(content, library, AppSettings(activePlaylistId = PLAYLIST_ID), keptTitles, kept, awards)
        }

        /** What [OndoRepository.keptPosterFacts] works out from the kept titles, by poster key. */
        private fun posterFacts(content: PlaylistContent, named: List<TitleMetadata>): Map<String, TitleMetadata> {
            val titles = MetadataTitles(content.movies, content.series)
            val result = HashMap<String, TitleMetadata>()
            for (title in named) {
                if (title.genres.isNullOrEmpty() && title.rating == null && title.releaseDate.isNullOrEmpty()) continue
                for (match in titles.match(listOf(title.key))) result.putIfAbsent(match.id, title)
            }
            return result
        }

        private fun ms(start: Long): String = "%.1f".format(Locale.ROOT, (System.nanoTime() - start) / 1e6)
    }

    /**
     * Times [block] once (the first call: cold), then [runs] more, each after [setup] (outside the time), and prints the
     * first, the median and every run.
     */
    private fun <S, T> stage(name: String, runs: Int = 3, setup: () -> S, block: (S) -> T): T {
        fun once(): Pair<Double, T> {
            val input = setup()
            val start = System.nanoTime()
            val result = block(input)
            return (System.nanoTime() - start) / 1e6 to result
        }
        val (first, result) = once()
        // Printed at once: a stage cut short later still leaves its cold time.
        say("%s first=%.1f ms".format(Locale.ROOT, name, first))
        val times = List(runs) { once().first }
        val median = times.sorted()[runs / 2]
        val all = times.joinToString(", ") { "%.1f".format(Locale.ROOT, it) }
        say("%s first=%.1f ms median=%.1f ms runs=[%s]".format(Locale.ROOT, name, first, median, all))
        assertTrue("$name took $median ms", median < 60_000)
        return result
    }

    private fun <T> stage(name: String, runs: Int = 3, block: () -> T): T = stage(name, runs, setup = {}) { block() }

    /** Forgets what the screens built from a catalogue (as a playlist switch does): the next call works it out again. */
    private val cold: () -> Unit = { UiMappers.forgetCatalogue() }

    @Test
    fun stage1JsonOfTheCatalogue() {
        val content = built().content
        val directory = folder.newFolder("json")
        val store = AppStore(directory)
        stage("1.json.encode (AppStore.saveContent)") { store.saveContent(PLAYLIST_ID, content) }
        val file = File(directory, "content-$PLAYLIST_ID.json")
        say("1.json.size bytes=${file.length()} MB=%.1f".format(Locale.ROOT, file.length() / 1_048_576.0))
        val loaded = stage("1.json.decode (AppStore.loadContent)") { store.loadContent(PLAYLIST_ID) }
        assertNotNull("the catalogue is read back", loaded)
        assertEquals(content.movies.size, loaded!!.movies.size)
        assertEquals(content.series.size, loaded.series.size)
        assertEquals(content.channels.size, loaded.channels.size)
        file.delete()
    }

    @Test
    fun stage2ShownCatalogueAndHiddenCategories() {
        val built = built()
        val content = built.content
        // OndoRepository.shown: the placeholder names in the app's words (the core's English, then French).
        stage("2.shown.rename ENGLISH (OndoRepository.shown)") { PlaceholderNames.ENGLISH.rename(content) }
        val french = PlaceholderNames("Sans catégorie", "Autres", "Autres %1\$d", "Chaîne %1\$d", "Film %1\$d", "Série %1\$d", "Épisode %1\$d", Locale.FRENCH)
        stage("2.shown.rename FRENCH (OndoRepository.shown)") { french.rename(content) }
        val state = built.state()
        val hidden = stage("2.hidden.hiddenCategoryIds", setup = cold) { UiMappers.hiddenCategoryIds(state) }
        say("2.hidden.categories count=${hidden.size}")
        stage("2.hidden.hiddenTitles cold (CategoryFilter.hiddenTitles)", setup = cold) { CategoryFilter.hiddenTitles(state) }
        stage("2.hidden.hiddenTitles warm") { CategoryFilter.hiddenTitles(state) }
        stage("2.hidden.visibleChannels") { UiMappers.visibleChannels(state, hidden) }
    }

    @Test
    fun stage3CatalogueCaches() {
        val built = built()
        val content = built.content
        val versions = stage("3.films cold (UiMappers.films: FilmVersions.of)", setup = cold) { UiMappers.films(content) }
        say("3.films distinctFilms=${versions.distinct(content.movies).size}")
        stage("3.movieAwards.prepare cold (real awards.json)", setup = { MovieAwards.forget() }) { MovieAwards.prepare(content, built.awards) }
        stage("3.keptPosterFacts (MetadataTitles + match of the kept titles)") { posterFacts(content, built.keptTitles) }
        stage("3.metadataTitles only (MetadataTitles of the catalogue)") { MetadataTitles(content.movies, content.series) }
    }

    @Test
    fun stage4Home() {
        val built = built()
        val state = built.state()
        val labels = Labels()
        stage("4.home kept=empty cold (caches forgotten each run)", setup = cold) { UiMappers.home(state, labels, NOW) }
        stage("4.home kept=empty warm") { UiMappers.home(state, labels, NOW) }
        val home = stage("4.home kept=${built.kept.size} cold (caches forgotten each run)", setup = cold) { UiMappers.home(state, labels, NOW, built.kept) }
        stage("4.home kept=${built.kept.size} warm") { UiMappers.home(state, labels, NOW, built.kept) }
        say(
            "4.home result hero=${home.hero.size} continueWatching=${home.continueWatching.size} myList=${home.myList.size} " +
                "newMovies=${home.newMovies.size} newSeries=${home.newSeries.size} selections=${home.selections.size} channels=${home.channels.size}",
        )
        assertTrue("the carousel has titles", home.hero.isNotEmpty())
        assertTrue("new movies", home.newMovies.isNotEmpty())
    }

    @Test
    fun stage5InsideHome() {
        val built = built()
        val state = built.state()
        val kept = built.kept
        UiMappers.films(state.content) // grouped as the view model does when the catalogue comes (timed in stage 3)
        // Showcase's lists are worked out on first use: a new one for each run.
        val fresh = { UiMappers.showcase(state, kept) }
        stage("5.showcase construction (UiMappers.showcase)") { UiMappers.showcase(state, kept) }
        val hero = stage("5.showcase.heroItems (fresh showcase)", setup = fresh) { it.heroItems(NOW) }
        say("5.showcase.heroItems count=${hero.size}")
        stage("5.showcase.heroItems kept=empty (fresh showcase)", setup = { UiMappers.showcase(state) }) { it.heroItems(NOW) }
        stage("5.showcase.newMovies (fresh showcase)", setup = fresh) { it.newMovies(NOW) }
        stage("5.showcase.newSeries (fresh showcase)", setup = fresh) { it.newSeries(NOW) }
        stage("5.showcase.continueWatching (fresh showcase)", setup = fresh) { it.continueWatching() }
        stage("5.showcase.myList (fresh showcase)", setup = fresh) { it.myList() }
        stage("5.showcase.trending (fresh showcase)", setup = fresh) { it.trending() }
        stage("5.showcase.all of home's lists (one fresh showcase)", setup = fresh) { showcase ->
            showcase.heroItems(NOW)
            showcase.continueWatching()
            showcase.myList()
            showcase.newMovies(NOW)
            showcase.newSeries(NOW)
            showcase.trending()
        }
        stage("5.selectionRows.rows HOME cold (caches forgotten)", setup = cold) { SelectionRows.rows(state, RowOrder.HOME, NOW, kept, 20) }
        stage("5.selectionRows.rows HOME warm") { SelectionRows.rows(state, RowOrder.HOME, NOW, kept, 20) }
        stage("5.categoryFilter.suggestion") { CategoryFilter.suggestion(state, Labels().viewerCodes, Labels().locale) }
    }

    @Test
    fun stage6MoviesAndSeriesTabs() {
        val built = built()
        val state = built.state()
        val kept = built.kept
        val movies = stage("6.vod movies cold (caches forgotten each run)", setup = cold) { UiMappers.vod(state, series = false, title = "", now = NOW, kept = kept) }
        stage("6.vod movies warm") { UiMappers.vod(state, series = false, title = "", now = NOW, kept = kept) }
        val series = stage("6.vod series cold (caches forgotten each run)", setup = cold) { UiMappers.vod(state, series = true, title = "", now = NOW, kept = kept) }
        stage("6.vod series warm") { UiMappers.vod(state, series = true, title = "", now = NOW, kept = kept) }
        say("6.vod result movieRows=${movies.rows.size} movieAll=${movies.allCount} seriesRows=${series.rows.size} seriesAll=${series.allCount}")
        assertTrue(movies.rows.isNotEmpty())
    }

    /** What one cold open measured, in milliseconds from the view model's creation (-1: never). */
    private class ColdOpen(
        val firstHome: Long,
        val firstHero: Long,
        /** The first carousel of Home worked out anew (not the one kept from the last start). */
        val firstWorkedOutHero: Long,
        val computations: Int,
        /** How many times Home was the one kept from the last start. */
        val savedHomesShown: Int,
        /** From the Movies tab's opening (once Home is ready and the tabs worked out in the background) to its first state. */
        val moviesTab: Long,
    )

    /**
     * The app's cold start: a repository and a view model on the saved catalogue, as a launch makes them, with Home
     * watched as the screen does; first without Home kept from a last start, then with it (the first start kept it).
     * Prints each state the repository emits (what changed in it, when), each Home state, how many times Home was worked
     * out and how long that took, then the Movies tab's first opening. Always: Home kept is shown at the second start, and
     * Home is worked out 3 times at most without it and once with it (see the counts). Targets, held in the night's run
     * (`ONDO_COLDSTART_STRICT`) and only ten times over them otherwise (a hang): with Home kept, its carousel within 300 ms;
     * without, within 2.5 s; the Movies tab, opened once Home is ready, within 100 ms.
     */
    @Test
    fun stage7ViewModelColdOpen() {
        val directory = folder.newFolder("app")
        val store = AppStore(directory)
        val metadata = InMemoryMetadataStore()
        val awardsText = File("src/main/assets/awards.json").takeIf { it.isFile }?.readText()
        run {
            val built = built()
            store.savePlaylists(listOf(PLAYLIST))
            store.saveSettings(built.settings)
            store.saveLibrary(built.library)
            store.saveContent(PLAYLIST_ID, built.content.copy(fetchedAt = System.currentTimeMillis()))
            metadata.save(built.keptTitles)
        }
        // From here on, only the catalogue the repository reads is in memory, as in the app.
        built = null
        UiMappers.forgetCatalogue()
        System.gc()

        val kept = File(directory, "home-$PLAYLIST_ID.json")
        assertTrue("no Home kept before the first start", !kept.exists())
        val first = coldOpen(store, metadata, awardsText, "7.vm", waitForKeptHome = kept)
        assertTrue("Home kept for the next start", kept.isFile)
        say("7.vm.keptHome bytes=${kept.length()}")
        UiMappers.forgetCatalogue()
        System.gc()
        val second = coldOpen(store, metadata, awardsText, "7.vm.kept", waitForKeptHome = null)

        // Counts, always. What reaches Home in a cold open: the state before the saved playlists are read (S1), then read
        // with the catalogue being read (S2), the awards (S3), the catalogue (S4); what the device kept of TMDb, empty at
        // first (K0), read for the empty catalogue (K1), then for the catalogue (K2). Before Home was kept, each could
        // work Home out (S1 with K0, K1, S2, S3, S4, K2: six; 4 to 6 as they came together, 6 in CI). Now:
        //  - without Home kept: S2 (Home without its catalogue, its rows to continue watching); S4 (the catalogue's rows,
        //    TMDb's facts not read for it yet); K2 (its carousel). S1 is not worked out (nothing shows Home before the
        //    playlists are read), K1 and S3 change nothing without a catalogue (the Home of S2 again). 3 at most.
        //  - with Home kept: K2 only (Home kept shows from S2 until TMDb's facts are read for the catalogue). 1 at most.
        assertTrue("without Home kept, Home came", first.firstHome >= 0)
        assertEquals("without Home kept, none shown", 0, first.savedHomesShown)
        assertTrue("without Home kept, Home worked out 3 times at most: ${first.computations}", first.computations <= 3)
        assertTrue("with Home kept, it is shown: ${second.savedHomesShown}", second.savedHomesShown > 0)
        assertTrue("with Home kept, Home worked out anew with its carousel: ${second.firstWorkedOutHero} ms", second.firstWorkedOutHero >= 0)
        assertTrue("with Home kept, Home worked out once at most: ${second.computations}", second.computations <= 1)
        // Times: the targets in the night's run (ONDO_COLDSTART_STRICT); ten times them otherwise, only to catch a hang.
        within("without Home kept, the carousel", first.firstHero, 2_500)
        within("with Home kept, the carousel", second.firstHero, 300)
        within("the Movies tab, from its opening", first.moviesTab, 100)
        within("the Movies tab, from its opening, Home kept", second.moviesTab, 100)
    }

    /** [value] (ms, -1: never) within [targetMs] in the night's strict run, else within ten times it; printed either way. */
    private fun within(what: String, value: Long, targetMs: Long) {
        val strict = System.getProperty("ondo.coldStart.strict") == "true"
        val limit = if (strict) targetMs else targetMs * 10
        say("7.limit $what=$value ms target=$targetMs ms ${if (value in 0 until targetMs) "met" else "MISSED"}${if (strict) " (strict)" else ""}")
        assertTrue("$what: $value ms, over ${limit} ms", value in 0 until limit)
    }

    private fun coldOpen(store: AppStore, metadata: InMemoryMetadataStore, awardsText: String?, name: String, waitForKeptHome: File?): ColdOpen {
        val mainExecutor = Executors.newSingleThreadExecutor { Thread(it, "main").apply { isDaemon = true } }
        val main = mainExecutor.asCoroutineDispatcher()
        Dispatchers.setMain(main)
        val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val watching = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var model: OndoViewModel? = null
        try {
            val start = System.nanoTime()
            fun at(): Long = (System.nanoTime() - start) / 1_000_000
            val events = Collections.synchronizedList(mutableListOf<String>())
            val lastActivity = AtomicLong(0L)
            val repository = OndoRepository(store, FakeHttp(emptyMap()), repositoryScope, metadata = metadata, awardsSource = { awardsText })
            val viewModel = runBlocking(main) { OndoViewModel(repository, Labels(), minutes = flowOf(NOW)) }
            model = viewModel

            var previous: OndoState? = null
            val stateEmissions = AtomicInteger()
            watching.launch {
                viewModel.state.collect { state ->
                    val number = stateEmissions.incrementAndGet()
                    lastActivity.set(at())
                    events += "$name.state #$number t=${at()} ms changed=${changes(previous, state)} movies=${state.content.movies.size} restoring=${state.isRestoring} loading=${state.isLoading}"
                    previous = state
                }
            }
            val homeEmissions = AtomicInteger()
            val firstHome = AtomicLong(-1L)
            val firstHero = AtomicLong(-1L)
            val firstWorkedOutHero = AtomicLong(-1L)
            val firstVod = AtomicLong(-1L)
            watching.launch(main) {
                viewModel.home.collect { home ->
                    val number = homeEmissions.incrementAndGet()
                    val computations = viewModel.homeComputations.get()
                    lastActivity.set(at())
                    if (home != null) firstHome.compareAndSet(-1L, at())
                    if (home != null && home.hasVod) firstVod.compareAndSet(-1L, at())
                    if (home != null && home.hero.isNotEmpty()) firstHero.compareAndSet(-1L, at())
                    if (home != null && home.hero.isNotEmpty() && computations > 0 && !home.isRestoring) firstWorkedOutHero.compareAndSet(-1L, at())
                    events += "$name.home #$number t=${at()} ms null=${home == null} hero=${home?.hero?.size} newMovies=${home?.newMovies?.size} restoring=${home?.isRestoring} computations=$computations"
                }
            }

            // Until the carousel has come, no computation of Home is running and nothing has happened for three seconds
            // (two minutes at most).
            var computations = -1
            var quiet = false
            val deadline = System.currentTimeMillis() + 120_000
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(100)
                val now = viewModel.homeComputations.get()
                if (now != computations) {
                    computations = now
                    lastActivity.set(at())
                }
                val state = viewModel.state.value
                val idle = viewModel.homeStarted.get() == viewModel.homeComputations.get()
                if (state.content.movies.isNotEmpty() && !state.isRestoring && firstWorkedOutHero.get() >= 0 && idle && at() - lastActivity.get() > 3_000) {
                    quiet = true
                    break
                }
            }
            val homeTotal = viewModel.homeNanos.get() / 1_000_000
            synchronized(events) { events.forEach { say(it) } }
            events.clear()
            say("$name.wait ended=${if (quiet) "quiet" else "deadline"} at=${at()} ms")
            say(
                "$name.home firstNonNull=${firstHome.get()} ms firstWithCatalogue=${firstVod.get()} ms firstHero=${firstHero.get()} ms " +
                    "firstWorkedOutHero=${firstWorkedOutHero.get()} ms (from the view model's creation)",
            )
            say("$name.home started=${viewModel.homeStarted.get()} computations=${viewModel.homeComputations.get()} totalComputeTime=$homeTotal ms stateEmissions=${stateEmissions.get()} homeEmissions=${homeEmissions.get()}")
            val keptAtEnd = runBlocking(main) { repository.keptPosterFacts().size }
            say("$name.keptPosterFacts size=$keptAtEnd")

            // The Movies tab, worked out in the background once Home was ready, then opened.
            val warmDeadline = System.currentTimeMillis() + 120_000
            while (viewModel.moviesComputations.get() == 0 && System.currentTimeMillis() < warmDeadline) Thread.sleep(20)
            say("$name.moviesTab workedOutInTheBackground computations=${viewModel.moviesComputations.get()} totalComputeTime=${viewModel.moviesNanos.get() / 1_000_000} ms at=${at()} ms")
            val tabStart = System.nanoTime()
            val firstMovies = AtomicLong(-1L)
            watching.launch(main) {
                viewModel.movies.collect { movies ->
                    if (movies != null) firstMovies.compareAndSet(-1L, (System.nanoTime() - tabStart) / 1_000_000)
                }
            }
            val tabDeadline = System.currentTimeMillis() + 120_000
            while (firstMovies.get() < 0 && System.currentTimeMillis() < tabDeadline) Thread.sleep(5)
            Thread.sleep(2_000)
            say(
                "$name.moviesTab firstNonNull=${firstMovies.get()} ms computations=${viewModel.moviesComputations.get()} " +
                    "totalComputeTime=${viewModel.moviesNanos.get() / 1_000_000} ms",
            )
            // Home kept for the next start: written in the background (ten seconds at most).
            if (waitForKeptHome != null) {
                val keptDeadline = System.currentTimeMillis() + 10_000
                while (!waitForKeptHome.isFile && System.currentTimeMillis() < keptDeadline) Thread.sleep(20)
            }
            assertTrue("Home came", firstHome.get() >= 0)
            assertTrue("the Movies tab came", firstMovies.get() >= 0)
            return ColdOpen(firstHome.get(), firstHero.get(), firstWorkedOutHero.get(), viewModel.homeComputations.get(), viewModel.savedHomesShown.get(), firstMovies.get())
        } finally {
            watching.cancel()
            model?.let { runBlocking(main) { it.viewModelScope.cancel() } }
            repositoryScope.cancel()
            Dispatchers.resetMain()
            main.close()
            UiMappers.forgetCatalogue()
        }
    }

    /** What changed from [old] to [new], by field (the same object or not). */
    private fun changes(old: OndoState?, new: OndoState): String {
        if (old == null) return "[first]"
        val names = buildList {
            if (old.isReady != new.isReady) add("isReady")
            if (old.settings !== new.settings) add("settings")
            if (old.playlists !== new.playlists) add("playlists")
            if (old.content !== new.content) add("content")
            if (old.library !== new.library) add("library")
            if (old.household !== new.household) add("household")
            if (old.profiles !== new.profiles) add("profiles")
            if (old.guide !== new.guide) add("guide")
            if (old.isLoading != new.isLoading) add("isLoading")
            if (old.isRestoring != new.isRestoring) add("isRestoring")
            if (old.error != new.error) add("error")
            if (old.movieDetails !== new.movieDetails) add("movieDetails")
            if (old.seriesDetails !== new.seriesDetails) add("seriesDetails")
            if (old.awards !== new.awards) add("awards")
            if (old.placeholderNames != new.placeholderNames) add("placeholderNames")
            if (old.trending !== new.trending) add("trending")
            if (old.tmdbRatings !== new.tmdbRatings) add("tmdbRatings")
            if (old.tmdbBackdrops !== new.tmdbBackdrops) add("tmdbBackdrops")
            if (old.lastCrash != new.lastCrash) add("lastCrash")
        }
        return names.ifEmpty { listOf("other") }.joinToString(",", "[", "]")
    }
}
