package com.markedoe.ondo.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.markedoe.ondo.BuildConfig
import com.markedoe.ondo.R
import com.markedoe.ondo.player.PlayerActivity
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.screens.AddPlaylistScreen
import com.markedoe.ondo.ui.screens.DetailActions
import com.markedoe.ondo.ui.screens.HomeActions
import com.markedoe.ondo.ui.screens.HomeScreen
import com.markedoe.ondo.ui.screens.LiveActions
import com.markedoe.ondo.ui.screens.LiveScreen
import com.markedoe.ondo.ui.screens.MovieDetailScreen
import com.markedoe.ondo.ui.screens.PosterGridScreen
import com.markedoe.ondo.ui.screens.SearchScreen
import com.markedoe.ondo.ui.screens.SeriesDetailScreen
import com.markedoe.ondo.ui.screens.SettingsActions
import com.markedoe.ondo.ui.screens.SettingsScreen
import com.markedoe.ondo.ui.screens.VodActions
import com.markedoe.ondo.ui.screens.VodScreen
import com.markedoe.ondo.ui.screens.WelcomeScreen

const val PRIVACY_URL = "https://markedoe.github.io/ondo-iptv/privacy.html"
const val SUPPORT_URL = "https://markedoe.github.io/ondo-iptv/support.html"

/** The whole app: welcome until a playlist exists, then the tabs and their pages. */
@Composable
fun OndoApp(viewModel: OndoViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.playRequests.collect { request -> context.startActivity(PlayerActivity.intent(context, request)) }
    }
    when {
        !state.isReady -> Box(Modifier.fillMaxSize())
        state.playlists.isEmpty() -> Onboarding(viewModel)
        else -> MainTabs(viewModel)
    }
}

@Composable
private fun Onboarding(viewModel: OndoViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    val demoName = stringResource(R.string.demo)
    if (adding) {
        AddPlaylistScreen(
            onSave = { form -> viewModel.addPlaylist(form) { adding = false } },
            onBack = { adding = false; viewModel.clearError() },
            isLoading = state.isLoading,
            error = state.error,
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
            NavHost(navController, startDestination = Routes.Home) {
                composable<Routes.Home> { HomeDestination(viewModel, navController) }
                composable<Routes.Movies> { VodDestination(viewModel, navController, series = false) }
                composable<Routes.Series> { VodDestination(viewModel, navController, series = true) }
                composable<Routes.Live> { LiveDestination(viewModel) }
                composable<Routes.Search> { SearchDestination(viewModel, navController) }
                composable<Routes.MovieDetail> { MovieDestination(viewModel, navController, it.toRoute<Routes.MovieDetail>().id) }
                composable<Routes.SeriesDetail> { SeriesDestination(viewModel, navController, it.toRoute<Routes.SeriesDetail>().id) }
                composable<Routes.Grid> { GridDestination(viewModel, navController, it.toRoute()) }
                composable<Routes.Settings> { SettingsDestination(viewModel, navController) }
                composable<Routes.AddPlaylist> {
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    AddPlaylistScreen(
                        onSave = { form -> viewModel.addPlaylist(form) { navController.popBackStack() } },
                        onBack = { viewModel.clearError(); navController.popBackStack() },
                        isLoading = state.isLoading,
                        error = state.error,
                    )
                }
            }
            if (entry?.destination?.hasRoute(Routes.AddPlaylist::class) != true) {
                ErrorSnackbar(viewModel, Modifier.align(Alignment.BottomCenter), onRetry = viewModel::refresh)
            }
        }
    }
}

/** The tabs around the pages: a bottom bar on phones, a rail on tablets and Chromebooks. */
@Composable
fun OndoNavigationFrame(selected: TopLevel, onSelect: (TopLevel) -> Unit, content: @Composable () -> Unit) {
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            TopLevel.entries.forEach { tab ->
                item(
                    selected = tab == selected,
                    onClick = { onSelect(tab) },
                    icon = { Icon(tab.icon, contentDescription = null) },
                    label = { Text(stringResource(tab.label)) },
                    modifier = Modifier.testTag(tab.tag),
                )
            }
        },
        content = content,
    )
}

/** Loading failures (the cached catalogue stays on screen), with a retry. */
@Composable
private fun ErrorSnackbar(viewModel: OndoViewModel, modifier: Modifier, onRetry: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val host = remember { SnackbarHostState() }
    val message = state.error?.let { stringResource(R.string.error_playlist, it) }
    val retry = stringResource(R.string.try_again)
    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        val result = host.showSnackbar(message, actionLabel = retry, withDismissAction = true, duration = SnackbarDuration.Long)
        viewModel.clearError()
        if (result == SnackbarResult.ActionPerformed) onRetry()
    }
    SnackbarHost(host, modifier.testTag("errorSnackbar"))
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
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun HomeDestination(viewModel: OndoViewModel, navController: NavHostController) {
    val home by viewModel.home.collectAsStateWithLifecycle()
    val state = home ?: return Loading()
    HomeScreen(
        state = state,
        actions = HomeActions(
            onOpen = navController::open,
            onPlay = viewModel::play,
            onToggleMyList = viewModel::toggleMyList,
            onResume = { viewModel.resume(it.key) },
            onSeeAllMovies = { navController.navigate(Routes.Grid(series = false)) },
            onSeeAllSeries = { navController.navigate(Routes.Grid(series = true)) },
            onSeeAllMyList = { navController.navigate(Routes.Grid(series = false, myList = true, anyType = true)) },
            onSeeAllLive = { navController.openTab(TopLevel.LIVE) },
            onPlayChannel = viewModel::playChannel,
            onPlaylists = { navController.navigate(Routes.Settings) },
            onSettings = { navController.navigate(Routes.Settings) },
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
            onSearch = { navController.openTab(TopLevel.SEARCH) },
        ),
    )
}

@Composable
private fun LiveDestination(viewModel: OndoViewModel) {
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
        ),
    )
}

@Composable
private fun SearchDestination(viewModel: OndoViewModel, navController: NavHostController) {
    val results by viewModel.search.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    SearchScreen(
        state = results.copy(query = query),
        onQuery = viewModel::setQuery,
        onOpen = navController::open,
        onPlayChannel = viewModel::playChannel,
    )
}

@Composable
private fun MovieDestination(viewModel: OndoViewModel, navController: NavHostController, id: String) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(id) { viewModel.loadMovie(id) }
    val movie = state.content.movies.firstOrNull { it.id == id } ?: return Loading()
    MovieDetailScreen(
        state = remember(state, movie) { UiMappers.movieDetail(state, movie) },
        actions = DetailActions(
            onBack = { navController.popBackStack() },
            onPlay = { viewModel.playMovie(id) },
            onPlayFromStart = { viewModel.playMovie(id, fromStart = true) },
            onToggleMyList = { viewModel.toggleMyList(ShowcaseItem.MovieItem(movie)) },
            onOpen = navController::open,
        ),
    )
}

@Composable
private fun SeriesDestination(viewModel: OndoViewModel, navController: NavHostController, id: String) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val errors by viewModel.seriesErrors.collectAsStateWithLifecycle()
    var season by rememberSaveable { mutableStateOf<Int?>(null) }
    LaunchedEffect(id) { viewModel.loadSeries(id) }
    val series = state.content.series.firstOrNull { it.id == id } ?: return Loading()
    val ui = remember(state, series, season, errors) { UiMappers.seriesDetail(state, series, season, errors[id]) }
    SeriesDetailScreen(
        state = ui,
        actions = DetailActions(
            onBack = { navController.popBackStack() },
            onPlay = { ui.next?.let { viewModel.playEpisode(id, it) } },
            onToggleMyList = { viewModel.toggleMyList(ui.item) },
            onOpen = navController::open,
            onSeason = { season = it },
            onPlayEpisode = { viewModel.playEpisode(id, it) },
        ),
    )
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
    val items = remember(state, route, filter) { UiMappers.grid(state, route.series, route.categoryId, route.myList, filter, route.anyType) }
    PosterGridScreen(
        title = title,
        items = items,
        filter = filter,
        onFilter = { filter = it },
        onOpen = navController::open,
        onBack = { navController.popBackStack() },
    )
}

@Composable
private fun SettingsDestination(viewModel: OndoViewModel, navController: NavHostController) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val m3u = stringResource(R.string.kind_m3u)
    val xtream = stringResource(R.string.kind_xtream)
    fun browse(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            // No browser on the device: nothing to open.
        }
    }
    SettingsScreen(
        state = UiMappers.settings(state, BuildConfig.VERSION_NAME) { isXtream -> if (isXtream) xtream else m3u },
        actions = SettingsActions(
            onBack = { navController.popBackStack() },
            onAddPlaylist = { navController.navigate(Routes.AddPlaylist) },
            onActivate = viewModel::activate,
            onRefresh = viewModel::refresh,
            onDelete = viewModel::delete,
            onAppearance = viewModel::setAppearance,
            onShowAdultCategories = viewModel::setShowAdultCategories,
            onPrivacy = { browse(PRIVACY_URL) },
            onSupport = { browse(SUPPORT_URL) },
        ),
    )
}
