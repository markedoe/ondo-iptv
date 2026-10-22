package com.markedoe.ondo.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScope
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.markedoe.ondo.BuildConfig
import com.markedoe.ondo.R
import com.markedoe.ondo.core.library.ErrorAttribution
import com.markedoe.ondo.core.model.ContentType
import com.markedoe.ondo.data.LoadError
import com.markedoe.ondo.player.PlayerActivity
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.screens.AddPlaylistScreen
import com.markedoe.ondo.ui.screens.BrowseKind
import com.markedoe.ondo.ui.screens.BrowseScreen
import com.markedoe.ondo.ui.screens.BrowseUiState
import com.markedoe.ondo.ui.screens.CategoriesActions
import com.markedoe.ondo.ui.screens.CategoriesScreen
import com.markedoe.ondo.ui.screens.CollectionActions
import com.markedoe.ondo.ui.screens.CollectionScreen
import com.markedoe.ondo.ui.screens.CollectionUiState
import com.markedoe.ondo.ui.screens.DetailActions
import com.markedoe.ondo.ui.screens.GuideActions
import com.markedoe.ondo.ui.screens.GuideScreen
import com.markedoe.ondo.ui.screens.HelpScreen
import com.markedoe.ondo.ui.screens.HomeActions
import com.markedoe.ondo.ui.screens.HomeScreen
import com.markedoe.ondo.ui.screens.LiveActions
import com.markedoe.ondo.ui.screens.LiveScreen
import com.markedoe.ondo.ui.screens.MovieDetailScreen
import com.markedoe.ondo.ui.screens.MovieDetailUiState
import com.markedoe.ondo.ui.screens.MyListActions
import com.markedoe.ondo.ui.screens.MyListKind
import com.markedoe.ondo.ui.screens.MyListScreen
import com.markedoe.ondo.ui.screens.PersonScreen
import com.markedoe.ondo.ui.screens.PersonUiState
import com.markedoe.ondo.ui.screens.PickedFile
import com.markedoe.ondo.ui.screens.PosterGridScreen
import com.markedoe.ondo.ui.screens.RemindersScreen
import com.markedoe.ondo.ui.screens.SearchScreen
import com.markedoe.ondo.ui.screens.SeriesDetailScreen
import com.markedoe.ondo.ui.screens.SeriesDetailUiState
import com.markedoe.ondo.ui.screens.SettingsActions
import com.markedoe.ondo.ui.screens.SettingsScreen
import com.markedoe.ondo.ui.screens.VodActions
import com.markedoe.ondo.ui.screens.VodScreen
import com.markedoe.ondo.ui.screens.WebPageScreen
import com.markedoe.ondo.ui.screens.WelcomeScreen
import com.markedoe.ondo.ui.tv.FocusEntry
import com.markedoe.ondo.ui.tv.FocusMemory
import com.markedoe.ondo.ui.tv.LocalFocusMemory
import com.markedoe.ondo.ui.tv.TelevisionFrame
import com.markedoe.ondo.ui.tv.focusFrame
import com.markedoe.ondo.ui.tv.isTelevision
import com.markedoe.ondo.ui.tv.rememberFocusMemory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

const val PRIVACY_URL = "https://markedoe.github.io/ondo-iptv/privacy.html"
const val SUPPORT_URL = "https://markedoe.github.io/ondo-iptv/support.html"

private const val PAGE_FADE_MS = 220
private const val FILTER_DEBOUNCE_MS = 150L

/** The whole app: welcome until a playlist exists, then the tabs and their pages. */
@Composable
fun OndoApp(viewModel: OndoViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.playRequests.collect { request -> context.startActivity(PlayerActivity.intent(context, request)) }
    }
    TelevisionFrame {
        when {
            !state.isReady -> Box(Modifier.fillMaxSize())
            state.playlists.isEmpty() -> FocusEntry { Onboarding(viewModel) }
            else -> MainTabs(viewModel)
        }
    }
}

@Composable
private fun Onboarding(viewModel: OndoViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    val demoName = stringResource(R.string.demo)
    if (adding) {
        var picked by remember { mutableStateOf<PickedFile?>(null) }
        // System back returns to the welcome screen (without it, back closed the app).
        BackHandler { adding = false; viewModel.clearError() }
        AddPlaylistScreen(
            onSave = { form -> viewModel.addPlaylist(form) { adding = false } },
            onBack = { adding = false; viewModel.clearError() },
            isLoading = state.isLoading,
            error = state.error?.let { loadErrorText(it) },
            onPickFile = rememberPlaylistFilePicker { picked = it },
            pickedFile = picked,
        )
    } else {
        Box(Modifier.fillMaxSize()) {
            WelcomeScreen(onAddPlaylist = { adding = true }, onTryDemo = { viewModel.addDemo(demoName) }, isLoading = state.isLoading)
            ErrorSnackbar(viewModel, Modifier.align(Alignment.BottomCenter), onRetry = { viewModel.addDemo(demoName) })
        }
    }
}

@Composable
private fun MainTabs(viewModel: OndoViewModel) {
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    var selected by rememberSaveable { mutableStateOf(TopLevel.HOME) }
    LaunchedEffect(entry) {
        val destination = entry?.destination ?: return@LaunchedEffect
        TopLevel.entries.firstOrNull { destination.hasRoute(it.route::class) }?.let { selected = it }
    }
    OndoNavigationFrame(selected, onSelect = navController::openTab) {
        Box(Modifier.fillMaxSize()) {
            // A short fade between pages (the default crossfade lasts 700 ms).
            NavHost(
                navController,
                startDestination = Routes.Home,
                enterTransition = { fadeIn(tween(PAGE_FADE_MS)) },
                exitTransition = { fadeOut(tween(PAGE_FADE_MS)) },
                popEnterTransition = { fadeIn(tween(PAGE_FADE_MS)) },
                popExitTransition = { fadeOut(tween(PAGE_FADE_MS)) },
            ) {
                page<Routes.Home> { HomeDestination(viewModel, navController) }
                page<Routes.Movies> { VodDestination(viewModel, navController, series = false) }
                page<Routes.Series> { VodDestination(viewModel, navController, series = true) }
                page<Routes.Live> { LiveDestination(viewModel, navController) }
                page<Routes.Search> { SearchDestination(viewModel, navController) }
                page<Routes.MyList> { MyListDestination(viewModel, navController) }
                page<Routes.MovieDetail> { MovieDestination(viewModel, navController, it.toRoute<Routes.MovieDetail>().id) }
                page<Routes.SeriesDetail> { SeriesDestination(viewModel, navController, it.toRoute<Routes.SeriesDetail>().id) }
                page<Routes.Person> { PersonDestination(viewModel, navController, it.toRoute<Routes.Person>().name) }
                page<Routes.Browse> { BrowseDestination(viewModel, navController, it.toRoute()) }
                page<Routes.Guide> { GuideDestination(viewModel, navController) }
                page<Routes.Reminders> { RemindersDestination(viewModel, navController) }
                page<Routes.Collection> { CollectionDestination(viewModel, navController, it.toRoute<Routes.Collection>().id) }
                page<Routes.Grid> { GridDestination(viewModel, navController, it.toRoute()) }
                page<Routes.Settings> { SettingsDestination(viewModel, navController) }
                page<Routes.Categories> { CategoriesDestination(viewModel, navController, it.toRoute()) }
                page<Routes.Help> {
                    val open = rememberPageOpener(navController)
                    HelpScreen(onBack = { navController.popBackStack() }, onSupport = { open(SUPPORT_URL) })
                }
                page<Routes.Web> {
                    WebPageScreen(it.toRoute<Routes.Web>().url, allowed = setOf(PRIVACY_URL, SUPPORT_URL), onBack = { navController.popBackStack() })
                }
                page<Routes.AddPlaylist> {
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    var picked by remember { mutableStateOf<PickedFile?>(null) }
                    AddPlaylistScreen(
                        onSave = { form -> viewModel.addPlaylist(form) { navController.popBackStack() } },
                        onBack = { viewModel.clearError(); navController.popBackStack() },
                        isLoading = state.isLoading,
                        error = state.error?.let { loadErrorText(it) },
                        onPickFile = rememberPlaylistFilePicker { picked = it },
                        pickedFile = picked,
                    )
                }
            }
            if (entry?.destination?.hasRoute(Routes.AddPlaylist::class) != true) {
                ErrorSnackbar(viewModel, Modifier.align(Alignment.BottomCenter), onRetry = viewModel::refresh)
            }
        }
    }
}

/** The tabs around the pages: a bottom bar on phones, a rail on tablets, Chromebooks and TVs. */
@Composable
fun OndoNavigationFrame(selected: TopLevel, onSelect: (TopLevel) -> Unit, content: @Composable () -> Unit) {
    val television = isTelevision()
    // On a TV, a frame shows which tab the remote is on (the selected one keeps its pill).
    val frame = if (television) Modifier.focusFrame(RoundedCornerShape(16.dp), grow = 1f) else Modifier
    val items: NavigationSuiteScope.() -> Unit = {
        TopLevel.entries.forEach { tab ->
            item(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(stringResource(tab.label)) },
                modifier = frame.testTag(tab.tag),
            )
        }
    }
    if (television) {
        // A rail whatever the window's size class, on the page's own background.
        NavigationSuiteScaffold(
            navigationSuiteItems = items,
            layoutType = NavigationSuiteType.NavigationRail,
            navigationSuiteColors = NavigationSuiteDefaults.colors(navigationRailContainerColor = Color.Transparent),
            content = content,
        )
    } else {
        NavigationSuiteScaffold(navigationSuiteItems = items, content = content)
    }
}

/** Loading failures (the cached catalogue stays on screen), with a retry. */
@Composable
private fun ErrorSnackbar(viewModel: OndoViewModel, modifier: Modifier, onRetry: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val host = remember { SnackbarHostState() }
    val message = state.error?.let { stringResource(R.string.error_playlist, loadErrorText(it)) }
    val retry = stringResource(R.string.try_again)
    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        val result = host.showSnackbar(message, actionLabel = retry, withDismissAction = true, duration = SnackbarDuration.Long)
        viewModel.clearError()
        if (result == SnackbarResult.ActionPerformed) onRetry()
    }
    SnackbarHost(host, modifier.testTag("errorSnackbar"))
}

/** A page of the app, which keeps where the remote's focus was while another page is open (see [FocusMemory]). */
private inline fun <reified T : Any> NavGraphBuilder.page(noinline content: @Composable (NavBackStackEntry) -> Unit) {
    composable<T> { entry ->
        CompositionLocalProvider(LocalFocusMemory provides rememberFocusMemory()) { FocusEntry { content(entry) } }
    }
}

/** Each tab keeps its own back stack, like the system apps. */
private fun NavHostController.openTab(tab: TopLevel) = navigate(tab.route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

private fun NavHostController.open(item: ShowcaseItem) = when (item) {
    is ShowcaseItem.MovieItem -> navigate(Routes.MovieDetail(item.movie.id))
    is ShowcaseItem.SeriesItem -> navigate(Routes.SeriesDetail(item.series.id))
}

@Composable
private fun Loading() {
    val label = stringResource(R.string.loading)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.semantics { contentDescription = label })
    }
}

@Composable
private fun HomeDestination(viewModel: OndoViewModel, navController: NavHostController) {
    val home by viewModel.home.collectAsStateWithLifecycle()
    val preparing by viewModel.preparing.collectAsStateWithLifecycle()
    val state = home ?: return Loading()
    // The series of the carousel and of Continue watching are fetched ahead while Home is shown: Play starts at once.
    LaunchedEffect(state.hero.map { it.item.key }, state.continueWatching.map { it.key }) { viewModel.prefetchSeries(state) }
    HomeScreen(
        state = state,
        preparingKey = preparing,
        actions = HomeActions(
            onOpen = navController::open,
            onPlay = viewModel::play,
            onToggleMyList = viewModel::toggleMyList,
            onResume = { viewModel.resume(it.key) },
            onSeeAllMyList = { navController.navigate(Routes.Grid(series = false, myList = true, anyType = true)) },
            onSeeAllLive = { navController.openTab(TopLevel.LIVE) },
            onPlayChannel = viewModel::playChannel,
            onPlaylists = { navController.navigate(Routes.Settings) },
            onSettings = { navController.navigate(Routes.Settings) },
            onSearch = { navController.navigate(Routes.Search) },
            onAcceptSuggestion = viewModel::acceptSuggestion,
            onChooseCategories = {
                viewModel.dismissTagSuggestion()
                navController.navigate(Routes.Categories())
            },
            onDismissSuggestion = viewModel::dismissTagSuggestion,
        ),
    )
}

@Composable
private fun VodDestination(viewModel: OndoViewModel, navController: NavHostController, series: Boolean) {
    val flow = if (series) viewModel.series else viewModel.movies
    val vod by flow.collectAsStateWithLifecycle()
    val state = vod ?: return Loading()
    val title = stringResource(if (series) R.string.series else R.string.movies)
    VodScreen(
        state = state.copy(title = title),
        actions = VodActions(
            onOpen = navController::open,
            onResume = { viewModel.resume(it.key) },
            onCategory = { category -> navController.navigate(Routes.Grid(series = series, categoryId = category?.id)) },
            onSeeAllMyList = { navController.navigate(Routes.Grid(series = series, myList = true)) },
            onSearch = { navController.navigate(Routes.Search) },
            onCategories = { navController.navigate(Routes.Categories(if (series) "series" else "movie")) },
        ),
    )
}

@Composable
private fun LiveDestination(viewModel: OndoViewModel, navController: NavHostController) {
    val live by viewModel.live.collectAsStateWithLifecycle()
    val filter by viewModel.liveFilter.collectAsStateWithLifecycle()
    val state = live ?: return Loading()
    LiveScreen(
        state = state,
        actions = LiveActions(
            onCategory = { viewModel.setLiveFilter(LiveFilter(categoryId = it)) },
            onFavorites = { viewModel.setLiveFilter(LiveFilter(favoritesOnly = !filter.favoritesOnly)) },
            onPlay = viewModel::playChannel,
            onToggleFavorite = viewModel::toggleFavorite,
            onCategories = { navController.navigate(Routes.Categories("live")) },
            onSearch = { navController.navigate(Routes.Search) },
            onGuide = { navController.navigate(Routes.Guide) },
        ),
    )
}

@Composable
private fun SearchDestination(viewModel: OndoViewModel, navController: NavHostController) {
    val results by viewModel.search.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filters by viewModel.searchFilters.collectAsStateWithLifecycle()
    SearchScreen(
        state = results.copy(query = query, filters = filters),
        onQuery = viewModel::setQuery,
        onFilters = viewModel::setSearchFilters,
        onOpen = navController::open,
        onPlayChannel = viewModel::playChannel,
        onPerson = { navController.navigate(Routes.Person(it)) },
    )
}

@Composable
private fun MovieDestination(viewModel: OndoViewModel, navController: NavHostController, id: String) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(id) { viewModel.loadMovie(id) }
    val locale = LocalConfiguration.current.locales[0]
    // Off the main thread: finding the movie, its similar titles, awards and hidden categories go through the whole
    // catalogue. A change (My List, details loaded) keeps the page on screen until the new one is ready.
    val detail by produceState<MovieDetailUiState?>(null, state, id, locale) {
        value = withContext(Dispatchers.Default) { UiMappers.movie(state, id)?.let { UiMappers.movieDetail(state, it, locale) } }
    }
    val ui = detail ?: return Loading()
    val movie = ui.item.movie
    MovieDetailScreen(
        state = ui,
        actions = DetailActions(
            onBack = { navController.popBackStack() },
            onPlay = { viewModel.playMovie(id) },
            onPlayFromStart = { viewModel.playMovie(id, fromStart = true) },
            onToggleMyList = { viewModel.toggleMyList(ShowcaseItem.MovieItem(movie)) },
            onOpen = navController::open,
            onPerson = { navController.navigate(Routes.Person(it)) },
            onBrowse = { kind, value -> navController.navigate(Routes.Browse(kind.name, value)) },
            onToggleCollection = { viewModel.toggleInCollection(it, ShowcaseItem.MovieItem(movie)) },
            onNewCollection = { viewModel.newCollection(it, ShowcaseItem.MovieItem(movie)) },
        ),
    )
}

@Composable
private fun SeriesDestination(viewModel: OndoViewModel, navController: NavHostController, id: String) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val errors by viewModel.seriesErrors.collectAsStateWithLifecycle()
    var season by rememberSaveable { mutableStateOf<Int?>(null) }
    LaunchedEffect(id) { viewModel.loadSeries(id) }
    // Off the main thread: finding the series, its similar titles and hidden categories go through the whole catalogue.
    val detail by produceState<SeriesDetailUiState?>(null, state, id) {
        value = withContext(Dispatchers.Default) { UiMappers.series(state, id)?.let { UiMappers.seriesDetail(state, it, null, null) } }
    }
    val computed = detail ?: return Loading()
    // The season chosen and a loading error show at once, without computing the page again.
    val ui = computed.copy(selectedSeason = season ?: computed.selectedSeason, error = errors[id]?.let { loadErrorText(it) })
    SeriesDetailScreen(
        state = ui,
        actions = DetailActions(
            onBack = { navController.popBackStack() },
            onPlay = { ui.next?.let { viewModel.playEpisode(id, it) } },
            onToggleMyList = { viewModel.toggleMyList(ui.item) },
            onOpen = navController::open,
            onPerson = { navController.navigate(Routes.Person(it)) },
            onBrowse = { kind, value -> navController.navigate(Routes.Browse(kind.name, value)) },
            onSeason = { season = it },
            onPlayEpisode = { viewModel.playEpisode(id, it) },
            onToggleCollection = { viewModel.toggleInCollection(it, ui.item) },
            onNewCollection = { viewModel.newCollection(it, ui.item) },
        ),
    )
}

@Composable
private fun GuideDestination(viewModel: OndoViewModel, navController: NavHostController) {
    val guide by viewModel.guide.collectAsStateWithLifecycle()
    val state = guide ?: return Loading()
    GuideScreen(
        state = state,
        actions = GuideActions(
            onBack = { navController.popBackStack() },
            onCategory = { viewModel.setLiveFilter(LiveFilter(categoryId = it)) },
            onFavorites = { viewModel.setLiveFilter(LiveFilter(favoritesOnly = true)) },
            onPlay = { channel -> state.rows.firstOrNull { it.channel.id == channel.id }?.let { viewModel.playChannel(UiMappers.channelRow(it.channel, viewModel.state.value, state.now)) } },
            onToggleReminder = viewModel::toggleReminder,
            onReminders = { navController.navigate(Routes.Reminders) },
        ),
    )
}

@Composable
private fun RemindersDestination(viewModel: OndoViewModel, navController: NavHostController) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val reminders = remember(state.library.reminders, state.activePlaylistId) { viewModel.upcomingReminders() }
    RemindersScreen(reminders, onRemove = viewModel::removeReminder, onBack = { navController.popBackStack() })
}

@Composable
private fun CollectionDestination(viewModel: OndoViewModel, navController: NavHostController, id: String) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val exists = state.library.collections.any { it.id == id }
    // Deleted (here or by a restore): back to My List.
    LaunchedEffect(exists) { if (!exists) navController.popBackStack() }
    if (!exists) return
    // Off the main thread: it looks through the whole catalogue.
    val collection by produceState<CollectionUiState?>(null, state, id) {
        value = withContext(Dispatchers.Default) { UiMappers.collection(state, id, System.currentTimeMillis()) }
    }
    CollectionScreen(
        state = collection ?: return Loading(),
        actions = CollectionActions(
            onBack = { navController.popBackStack() },
            onOpen = navController::open,
            onPlayChannel = viewModel::playChannel,
            onRename = { viewModel.renameCollection(id, it) },
            onDelete = { viewModel.deleteCollection(id) },
        ),
    )
}

@Composable
private fun BrowseDestination(viewModel: OndoViewModel, navController: NavHostController, route: Routes.Browse) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val kind = BrowseKind.valueOf(route.kind)
    val locale = LocalConfiguration.current.locales[0]
    // Off the main thread: it looks through the whole catalogue.
    val browse by produceState(BrowseUiState(kind, route.value), state, route, locale) {
        value = withContext(Dispatchers.Default) { UiMappers.browse(state, kind, route.value, locale) }
    }
    BrowseScreen(
        browse,
        onOpen = navController::open,
        onFestival = { navController.navigate(Routes.Browse(BrowseKind.FESTIVAL.name, it)) },
        onBack = { navController.popBackStack() },
    )
}

@Composable
private fun PersonDestination(viewModel: OndoViewModel, navController: NavHostController, name: String) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Off the main thread: it looks through the whole catalogue.
    val works by produceState(PersonUiState(name), state, name) {
        value = withContext(Dispatchers.Default) { UiMappers.person(state, name) }
    }
    PersonScreen(works, onOpen = navController::open, onBack = { navController.popBackStack() })
}

@Composable
private fun GridDestination(viewModel: OndoViewModel, navController: NavHostController, route: Routes.Grid) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf("") }
    val title = when {
        route.myList -> stringResource(R.string.my_list)
        route.categoryId != null -> UiMappers.categoryName(state, route.series, route.categoryId).orEmpty()
        route.series -> stringResource(R.string.all_series)
        else -> stringResource(R.string.all_movies)
    }
    val sort = state.settings.posterSort
    // The filter applies once typing pauses: each key no longer looks through the whole catalogue.
    val query by produceState(filter, filter) {
        if (value != filter) {
            delay(FILTER_DEBOUNCE_MS)
            value = filter
        }
    }
    // Off the main thread: sorting a hundred thousand titles must not freeze the screen while typing.
    val items by produceState(emptyList<ShowcaseItem>(), state, route, query, sort) {
        value = withContext(Dispatchers.Default) {
            UiMappers.grid(state, route.series, route.categoryId, route.myList, query, route.anyType, sort)
        }
    }
    PosterGridScreen(
        title = title,
        items = items,
        filter = filter,
        onFilter = { filter = it },
        onOpen = navController::open,
        onBack = { navController.popBackStack() },
        sort = sort,
        onSort = viewModel::setPosterSort,
    )
}

/** The app's version; a build for a Google Play test track says it is a beta. */
@Composable
private fun appVersion(): String =
    if (BuildConfig.BETA) "${BuildConfig.VERSION_NAME} · ${stringResource(R.string.beta)}" else BuildConfig.VERSION_NAME

@Composable
private fun SettingsDestination(viewModel: OndoViewModel, navController: NavHostController) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val m3u = stringResource(R.string.kind_m3u)
    val xtream = stringResource(R.string.kind_xtream)
    val browse = rememberPageOpener(navController)
    SettingsScreen(
        state = UiMappers.settings(state, appVersion()) { isXtream -> if (isXtream) xtream else m3u },
        actions = SettingsActions(
            onBack = { navController.popBackStack() },
            onAddPlaylist = { navController.navigate(Routes.AddPlaylist) },
            onActivate = viewModel::activate,
            onRefresh = viewModel::refresh,
            onDelete = viewModel::delete,
            onAppearance = viewModel::setAppearance,
            onShowAdultCategories = viewModel::setShowAdultCategories,
            onUpdateAwards = viewModel::setUpdateAwards,
            onLiveBuffer = viewModel::setLiveBuffer,
            onVodBuffer = viewModel::setVodBuffer,
            onCategories = { navController.navigate(Routes.Categories()) },
            onAppLanguage = { context.openAppLanguageSettings() },
            onHelp = { navController.navigate(Routes.Help) },
            onPrivacy = { browse(PRIVACY_URL) },
            onSupport = { browse(SUPPORT_URL) },
        ),
    )
}

@Composable
private fun CategoriesDestination(viewModel: OndoViewModel, navController: NavHostController, route: Routes.Categories) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var type by rememberSaveable { mutableStateOf(ContentType.fromRawValue(route.type) ?: ContentType.MOVIE) }
    var filter by rememberSaveable { mutableStateOf("") }
    val locale = LocalConfiguration.current.locales[0]
    // Off the main thread: each key typed in the filter counts the whole catalogue's titles by category.
    val categories by produceState<CategoriesUiState?>(null, state, type, filter, locale) {
        value = withContext(Dispatchers.Default) { CategoryFilter.categories(state, type, filter, locale) }
    }
    val ui = categories ?: return Loading()
    CategoriesScreen(
        state = ui,
        type = type,
        filter = filter,
        actions = CategoriesActions(
            onBack = { navController.popBackStack() },
            onType = { type = it },
            onFilter = { filter = it },
            onTag = { tag, hidden -> viewModel.setTagsHidden(listOf(tag), hidden) },
            onWord = viewModel::setWordHidden,
            onCategory = viewModel::setCategoryHidden,
            onAll = { hidden -> ui.categories.filter { it.hiddenWith == null }.forEach { viewModel.setCategoryHidden(it.id, hidden) } },
        ),
    )
}

/** Opens a web page in the browser (nothing happens on a device without one). */
/**
 * Opens one of Ondo's own pages (privacy policy, support): in the browser, or inside the app on a TV, where Google
 * wants no browser opened (TV-WB).
 */
@Composable
private fun rememberPageOpener(navController: NavHostController): (String) -> Unit {
    val context = LocalContext.current
    val television = isTelevision()
    return remember(navController, television) {
        { url -> if (television) navController.navigate(Routes.Web(url)) else context.browse(url) }
    }
}

private fun Context.browse(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (_: ActivityNotFoundException) {
        // No browser on the device: nothing to open.
    }
}

/** The system page where Ondo's language is chosen (Android 13+), else the device's languages. */
private fun Context.openAppLanguageSettings() {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", packageName, null))
    } else {
        Intent(Settings.ACTION_LOCALE_SETTINGS)
    }
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}

/** The reason a playlist could not be loaded, in the viewer's language. */
@Composable
fun loadErrorText(error: LoadError): String = when {
    error.status == 401 || error.status == 403 -> stringResource(R.string.error_load_denied, error.status)
    error.kind == ErrorAttribution.PROVIDER -> stringResource(R.string.error_load_provider)
    error.kind == ErrorAttribution.NETWORK -> stringResource(R.string.error_network)
    else -> stringResource(R.string.error_load_format)
}

@Composable
private fun MyListDestination(viewModel: OndoViewModel, navController: NavHostController) {
    val myList by viewModel.myList.collectAsStateWithLifecycle()
    val state = myList ?: return Loading()
    // Opens on a kind that has something, movies first.
    var kind by rememberSaveable {
        mutableStateOf(
            when {
                state.movies.isNotEmpty() -> MyListKind.MOVIES
                state.series.isNotEmpty() -> MyListKind.SERIES
                state.channels.isNotEmpty() -> MyListKind.CHANNELS
                else -> MyListKind.MOVIES
            },
        )
    }
    MyListScreen(
        state = state,
        kind = kind,
        actions = MyListActions(
            onKind = { kind = it },
            onOpen = navController::open,
            onPlayChannel = viewModel::playChannel,
            onSearch = { navController.navigate(Routes.Search) },
            onOpenCollection = { navController.navigate(Routes.Collection(it)) },
            onNewCollection = { viewModel.newCollection(it) },
        ),
    )
}
