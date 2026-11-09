package com.markedoe.ondo.ui.tv

import com.markedoe.ondo.ui.components.myListIcon
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.LibraryAddCheck
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.OndemandVideo
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.tv.material3.Glow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.markedoe.ondo.R
import com.markedoe.ondo.ui.trailerLabel
import com.markedoe.ondo.core.TextNormalizer
import com.markedoe.ondo.core.library.People
import com.markedoe.ondo.core.model.Episode
import com.markedoe.ondo.core.playback.MediaFormat
import com.markedoe.ondo.ui.AwardRow
import com.markedoe.ondo.ui.UiMappers
import com.markedoe.ondo.ui.timeLeftText
import com.markedoe.ondo.ui.components.Artwork
import com.markedoe.ondo.ui.components.Backdrop
import com.markedoe.ondo.ui.components.BlurredPoster
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.screens.BrowseKind
import com.markedoe.ondo.ui.screens.CollectionChoice
import com.markedoe.ondo.ui.screens.CollectionNameDialog
import com.markedoe.ondo.ui.screens.DetailActions
import com.markedoe.ondo.ui.screens.canMarkSeason
import com.markedoe.ondo.ui.screens.canMarkSeries
import com.markedoe.ondo.ui.screens.isSeasonWatched
import com.markedoe.ondo.ui.screens.MovieDetailUiState
import com.markedoe.ondo.ui.screens.SeriesDetailUiState
import com.markedoe.ondo.ui.screens.VersionChip
import com.markedoe.ondo.ui.screens.WatchProgress
import com.markedoe.ondo.ui.screens.durationText
import com.markedoe.ondo.ui.screens.myListState
import com.markedoe.ondo.ui.screens.versionLabel
import com.markedoe.ondo.ui.theme.OndoColors

/** Where the text and rows of a page without the drawer start. */
private val PageStart: Dp = TvTokens.ContentStart

/**
 * A movie on a TV: its backdrop across the screen, the title, facts, plot and buttons over it with Play focused,
 * then rows to go further (genres and year, cast, awards, similar titles). No back button: the remote's Back closes it.
 */
@Composable
fun TvMovieDetailScreen(state: MovieDetailUiState, actions: DetailActions) {
    val movie = state.item.movie
    val details = state.details
    val facts = listOfNotNull(
        movie.year?.toString() ?: details?.releaseDate?.take(4),
        firstGenre(details?.genre),
        details?.durationSeconds?.takeIf { it > 0 }?.let { durationText(it, LocalConfiguration.current.locales[0]) },
        UiMappers.ratingFact(details?.rating ?: movie.rating, state.votes, LocalConfiguration.current.locales[0]),
    )
    val resumable = state.progress?.let { !it.finished && it.fraction > 0f } == true
    var picking by rememberSaveable { mutableStateOf(false) }
    val collectionButton = remember { FocusRequester() }
    TvDetailPage {
        item(key = "header") {
            TvDetailHeader(
                title = movie.name,
                logoUrl = state.logoUrl,
                posterUrl = details?.posterUrl ?: movie.posterUrl,
                backdropUrl = state.backdropUrl,
                onBackdropFailed = actions.onBackdropFailed,
                award = state.awards.firstOrNull(),
                facts = facts,
                badges = listOfNotNull(MediaFormat.label((state.version ?: movie).streamUrl)),
                plot = details?.plot ?: movie.plot,
                director = details?.director,
                progress = state.progress.takeIf { resumable },
                watched = state.watched,
            ) {
                TvActionButton(onClick = actions.onPlay, prominent = true, modifier = Modifier.focusMemory("detail-play", initial = true).testTag("detailPlay")) {
                    ButtonLabel(Icons.Rounded.PlayArrow, playLabel(resumable))
                }
                if (resumable) {
                    TvActionButton(onClick = actions.onPlayFromStart, modifier = Modifier.focusMemory("detail-fromStart").testTag("detailFromStart")) {
                        ButtonLabel(Icons.Rounded.Replay, stringResource(R.string.from_start))
                    }
                }
                WatchedButton(state.watched) { actions.onSetWatched(!state.watched) }
                MyListButton(state.inMyList, actions.onToggleMyList)
                CollectionButton(state.collections, collectionButton) { picking = true }
                state.trailer?.let { TrailerButton(it, actions) }
            }
        }
        versionsShelf(state.versions, (state.version ?: movie).id, actions)
        exploreShelf(details?.genre, movie.year ?: details?.releaseDate?.take(4)?.toIntOrNull(), actions)
        peopleShelf(details?.director, details?.cast, state.faces, actions)
        awardsShelf(state.awards, actions)
        similarShelf(state.similar, actions)
    }
    if (picking) TvCollectionDialog(state.collections, actions.onToggleCollection, actions.onNewCollection, onDismiss = { picking = false })
    FocusBackAfter(picking, collectionButton)
}

/**
 * A series on a TV: as a movie, with the episode to play next on the first button, then the seasons and a row of
 * the season's episodes.
 */
@Composable
fun TvSeriesDetailScreen(state: SeriesDetailUiState, actions: DetailActions) {
    val series = state.item.series
    val details = state.details
    val seasonCount = state.seasons.size
    val facts = listOfNotNull(
        series.year?.toString(),
        firstGenre(details?.genre ?: series.genre),
        seasonCount.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.seasons, it, it) },
        UiMappers.ratingFact(details?.rating ?: series.rating, state.votes, LocalConfiguration.current.locales[0]),
    )
    val season = state.seasons.firstOrNull { it.number == state.selectedSeason } ?: state.seasons.firstOrNull()
    val resumable = state.nextProgress?.let { !it.finished && it.fraction > 0f } == true
    val episodesTitle = stringResource(R.string.episodes)
    var picking by rememberSaveable { mutableStateOf(false) }
    val collectionButton = remember { FocusRequester() }
    TvDetailPage {
        item(key = "header") {
            TvDetailHeader(
                title = series.name,
                logoUrl = state.logoUrl,
                posterUrl = details?.posterUrl ?: series.coverUrl,
                backdropUrl = state.backdropUrl,
                onBackdropFailed = actions.onBackdropFailed,
                award = state.awards.firstOrNull(),
                facts = facts,
                badges = listOfNotNull(MediaFormat.label(state.seasons.flatMap { s -> s.episodes.map { it.streamUrl } })),
                plot = details?.plot ?: series.plot,
                director = details?.director ?: series.director,
                progress = state.nextProgress.takeIf { resumable },
                watched = state.watched,
            ) {
                state.next?.let { next ->
                    TvActionButton(onClick = actions.onPlay, prominent = true, modifier = Modifier.focusMemory("detail-play", initial = true).testTag("detailPlay")) {
                        ButtonLabel(Icons.Rounded.PlayArrow, stringResource(if (resumable) R.string.resume_episode else R.string.play_episode, next.code))
                    }
                }
                // Its episodes known: all of them watched, or all unwatched; a long series season by season.
                if (state.canMarkSeries()) WatchedButton(state.watched) { actions.onSetWatched(!state.watched) }
                MyListButton(state.inMyList, actions.onToggleMyList)
                CollectionButton(state.collections, collectionButton) { picking = true }
                state.trailer?.let { TrailerButton(it, actions) }
            }
        }
        state.error?.let { error ->
            item(key = "error") {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = PageStart))
            }
        }
        if (state.seasons.size > 1) {
            item(key = "seasons") {
                TvRow {
                    LazyRow(
                        contentPadding = PaddingValues(start = PageStart, end = TvTokens.EdgeEnd, top = 4.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.testTag("seasons"),
                    ) {
                        items(state.seasons, key = { it.number }) { item ->
                            TvChip(
                                text = stringResource(R.string.season_number, item.number),
                                selected = item.number == season?.number,
                                onClick = { actions.onSeason(item.number) },
                                modifier = Modifier.focusMemory("season:${item.number}").testTag("season-${item.number}"),
                            )
                        }
                        // The season shown, as a whole: at the end of the seasons, Right reaches it.
                        season?.takeIf { state.canMarkSeason(it) }?.let { current ->
                            item(key = "seasonWatched") {
                                val watched = state.isSeasonWatched(current)
                                TvChip(
                                    text = stringResource(if (watched) R.string.mark_season_unwatched else R.string.mark_season_watched),
                                    onClick = { actions.onSetSeasonWatched(current.number, !watched) },
                                    icon = if (watched) Icons.Rounded.CheckCircle else Icons.Rounded.CheckCircleOutline,
                                    modifier = Modifier.focusMemory("seasonWatched").testTag("seasonWatched"),
                                )
                            }
                        }
                    }
                }
            }
        }
        season?.takeIf { it.episodes.isNotEmpty() }?.let { current ->
            tvShelf(episodesTitle, "episodes", start = PageStart) {
                items(current.episodes, key = { it.id }) { episode ->
                    TvEpisodeCard(
                        episode,
                        state.episodeProgress[episode.id],
                        onClick = { actions.onPlayEpisode(episode) },
                        onWatched = { actions.onSetEpisodeWatched(episode, it) },
                        modifier = Modifier.focusMemory("episode:${episode.id}"),
                    )
                }
            }
        }
        exploreShelf(details?.genre ?: series.genre, series.year, actions)
        peopleShelf(details?.director ?: series.director, details?.cast ?: series.cast, state.faces, actions)
        awardsShelf(state.awards, actions)
        similarShelf(state.similar, actions)
    }
    if (picking) TvCollectionDialog(state.collections, actions.onToggleCollection, actions.onNewCollection, onDismiss = { picking = false })
    FocusBackAfter(picking, collectionButton)
}

@Composable
private fun TvDetailPage(content: LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(TvTokens.Ink).testTag("detail"),
        contentPadding = PaddingValues(bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** The least height of a page's title: one line of its name, or its logo at that height. */
private val DETAIL_TITLE_SLOT = 64.dp

/** The first screen of a page: the artwork behind, faded towards the text on the left and the rows below. */
@Composable
private fun TvDetailHeader(
    /** The provider's name, its tags left out on screen ([TvTitleOrLogo]). */
    title: String,
    logoUrl: String?,
    posterUrl: String?,
    backdropUrl: String?,
    onBackdropFailed: (String) -> Unit,
    award: AwardRow?,
    facts: List<String>,
    badges: List<String>,
    plot: String?,
    director: String?,
    progress: WatchProgress?,
    watched: Boolean,
    buttons: @Composable FlowRowScope.() -> Unit,
) {
    val ink = TvTokens.Ink
    Box(Modifier.fillMaxWidth().heightIn(min = 280.dp).bringIntoViewAsAWhole().testTag("detailHeader")) {
        TvArtwork(backdropUrl, posterUrl, title, Modifier.matchParentSize(), onBackdropFailed = onBackdropFailed)
        Box(Modifier.matchParentSize().background(startToEndGradient(0f to ink, 0.35f to ink.copy(alpha = 0.9f), 0.7f to ink.copy(alpha = 0.3f), 1f to Color.Transparent)))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to ink)))
        Column(
            Modifier.padding(start = PageStart, top = 32.dp, end = TvTokens.EdgeEnd, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            award?.let { AwardLine(it) }
            // TMDb's logo of the title, else its name without the provider's tags; the slot keeps the name's height.
            TvTitleOrLogo(
                title,
                logoUrl,
                MaterialTheme.typography.displayMedium,
                Modifier.widthIn(max = 640.dp).fillMaxWidth().heightIn(min = DETAIL_TITLE_SLOT),
                maxLines = 2,
                fillsSlot = true,
                textTag = "detailTitle",
                logoTag = "detailLogo",
            )
            TvFacts(facts, badges)
            if (watched) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("detailWatchedState")) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = TvTokens.Accent, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.watched), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
            }
            plot?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.78f), maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 560.dp))
            }
            People.names(director).takeIf { it.isNotEmpty() }?.let { names ->
                Text(
                    stringResource(R.string.director) + "  " + names.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 560.dp),
                )
            }
            // Four buttons in a long language go on two lines rather than squeezing the last one.
            FlowRow(
                Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                itemVerticalAlignment = Alignment.Top,
                verticalArrangement = Arrangement.spacedBy(14.dp),
                content = buttons,
            )
            progress?.let { ProgressLine(it) }
        }
    }
}

/**
 * Where a TV's backdrops are cropped: towards their top, where the faces are. A picture wider than its frame is high
 * (a header, the carousel) loses its bottom rather than the eyes of the people in it.
 */
val TvArtworkAlignment: Alignment = BiasAlignment(horizontalBias = 0f, verticalBias = -0.6f)

/** The part of its picture a TV's artwork keeps when cropped ([TvArtwork]), for the tests. */
val ArtworkAlignment = SemanticsPropertyKey<Alignment>("ArtworkAlignment")

/**
 * The picture behind a title: its backdrop across the width, cropped as [alignment] says, else its poster on the
 * right over a blurred copy (an enlarged poster would look blurry on a TV).
 */
@Composable
internal fun TvArtwork(
    backdropUrl: String?,
    posterUrl: String?,
    title: String,
    modifier: Modifier = Modifier,
    alignment: Alignment = TvArtworkAlignment,
    onBackdropFailed: (String) -> Unit = {},
) {
    Box(modifier.semantics { this[ArtworkAlignment] = alignment }.testTag("tvArtwork")) {
        if (!backdropUrl.isNullOrBlank()) {
            Backdrop(url = backdropUrl, posterUrl = posterUrl, title = title, modifier = Modifier.fillMaxSize(), alignment = alignment, onFailed = onBackdropFailed)
        } else {
            BlurredPoster(url = posterUrl, title = title, modifier = Modifier.fillMaxSize().alpha(0.55f))
            Artwork(
                url = posterUrl,
                title = title,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = TvTokens.EdgeEnd + 24.dp, top = 12.dp, bottom = 12.dp)
                    .fillMaxHeight(0.82f)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(14.dp)),
            )
        }
    }
}

/** The movie's first award above its title, in gold when it won. */
@Composable
private fun AwardLine(award: AwardRow) {
    val gold = Color(0xFFE6B422)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(if (award.won) Icons.Rounded.EmojiEvents else Icons.Rounded.WorkspacePremium, contentDescription = null, tint = gold, modifier = Modifier.size(20.dp))
        Text(
            award.name + " · " + award.year,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = gold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** How far the viewer is: a white bar and the minutes left. */
@Composable
private fun ProgressLine(progress: WatchProgress) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
        TvProgress(progress.fraction, Modifier.width(220.dp).clip(RoundedCornerShape(2.dp)))
        Text(
            timeLeftText(progress.minutesLeft),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.7f),
        )
    }
}

/** The first of a title's genres ("Drama, Mystery" → "Drama"), for its facts line; all of them are below. */
private fun firstGenre(genre: String?): String? = genre?.split(',', '/', '|')?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }

@Composable
private fun RowScope.ButtonLabel(icon: ImageVector, text: String) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
    Spacer(Modifier.width(8.dp))
    Text(text, maxLines = 1)
}

/**
 * "Mark as watched" / "Mark as unwatched": the same state as watching it to the end (out of Continue watching), or
 * played from its start again. After Play on the remote's path; its focus is remembered whichever its label.
 */
@Composable
private fun WatchedButton(watched: Boolean, onClick: () -> Unit) {
    val state = stringResource(if (watched) R.string.watched else R.string.not_watched)
    TvActionButton(onClick = onClick, modifier = Modifier.focusMemory("detail-watched").semantics { stateDescription = state }.testTag("detailWatched")) {
        ButtonLabel(if (watched) Icons.Rounded.CheckCircle else Icons.Rounded.CheckCircleOutline, stringResource(if (watched) R.string.mark_unwatched else R.string.mark_watched))
    }
}

/** The provider's trailer, in the YouTube app. */
@Composable
private fun TrailerButton(youTubeId: String, actions: DetailActions) {
    TvCircleAction(Icons.Rounded.OndemandVideo, trailerLabel(), onClick = { actions.onTrailer(youTubeId) }, modifier = Modifier.focusMemory("detail-trailer").testTag("detailTrailer"))
}

@Composable
private fun MyListButton(inMyList: Boolean, onClick: () -> Unit) {
    val state = myListState(inMyList)
    TvCircleAction(
        myListIcon(inMyList),
        stringResource(R.string.my_list),
        onClick = onClick,
        modifier = Modifier.focusMemory("detail-myList").semantics { stateDescription = state }.testTag("detailMyList"),
    )
}

/** Opens the collections; marked when one of them holds the title. */
@Composable
private fun CollectionButton(collections: List<CollectionChoice>, requester: FocusRequester, onClick: () -> Unit) {
    val held = collections.any { it.contains }
    TvCircleAction(
        if (held) Icons.Rounded.LibraryAddCheck else Icons.Rounded.LibraryAdd,
        stringResource(R.string.collection),
        onClick = onClick,
        modifier = Modifier.focusRequester(requester).focusMemory("detail-collection").testTag("detailCollection"),
    )
}

/**
 * The collections, ticked when they hold the title, and New collection: OK ticks or unticks one. The remote starts
 * on the first line.
 */
@Composable
internal fun TvCollectionDialog(
    choices: List<CollectionChoice>,
    onToggle: (String) -> Unit,
    onNew: (String) -> Unit,
    onDismiss: () -> Unit,
    /** The name a new collection is offered (the person's, on a person's page); else "Collection N". */
    suggestedName: String? = null,
) {
    var naming by rememberSaveable { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val colors = ListItemDefaults.colors(
        containerColor = Color.Transparent,
        contentColor = Color.White,
        focusedContainerColor = Color.White,
        focusedContentColor = TvTokens.Ink,
    )
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            colors = SurfaceDefaults.colors(containerColor = OndoColors.SurfaceHigh, contentColor = Color.White),
            modifier = Modifier.widthIn(min = 360.dp, max = 520.dp).testTag("collectionPicker"),
        ) {
            Column(Modifier.padding(vertical = 24.dp, horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.add_to_collection),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 12.dp, bottom = 12.dp),
                )
                FocusOnOpen(first)
                choices.forEachIndexed { index, choice ->
                    ListItem(
                        selected = false,
                        onClick = { onToggle(choice.id) },
                        headlineContent = { Text(choice.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = { Icon(if (choice.contains) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank, contentDescription = null) },
                        modifier = Modifier
                            .then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                            .semantics {
                                role = Role.Checkbox
                                toggleableState = ToggleableState(choice.contains)
                            }
                            .testTag("pick-${choice.name}"),
                        colors = colors,
                        shape = ListItemDefaults.shape(RoundedCornerShape(12.dp)),
                    )
                }
                ListItem(
                    selected = false,
                    onClick = { naming = true },
                    headlineContent = { Text(stringResource(R.string.new_collection)) },
                    leadingContent = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    modifier = Modifier.then(if (choices.isEmpty()) Modifier.focusRequester(first) else Modifier).testTag("pickNewCollection"),
                    colors = colors,
                    shape = ListItemDefaults.shape(RoundedCornerShape(12.dp)),
                )
            }
        }
    }
    if (naming) {
        CollectionNameDialog(
            title = stringResource(R.string.new_collection),
            initial = suggestedName ?: stringResource(R.string.collection_default_name, choices.size + 1),
            confirm = stringResource(R.string.create),
            onConfirm = {
                naming = false
                onNew(it)
            },
            onDismiss = { naming = false },
        )
    }
}

/**
 * A small pill: a season, a version, a genre, a filter, an award. The selected one is white; the one with the remote's
 * focus has a white ring around it and grows ([tvFocusRing]), selected or not: the two never look alike.
 */
@Composable
fun TvChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, icon: ImageVector? = null, iconTint: Color? = null) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val container = if (selected) TvTokens.Accent else Color.White.copy(alpha = 0.1f)
    val content = if (selected) TvTokens.Ink else Color.White
    Button(
        onClick = onClick,
        modifier = (if (selected) modifier.semantics { this.selected = true } else modifier).tvFocusRing(focused, chipScale(focused)),
        // The ring and its scale are drawn around the chip ([tvFocusRing]); the chip keeps its own colors.
        scale = ButtonDefaults.scale(focusedScale = 1f),
        glow = ButtonDefaults.glow(focusedGlow = Glow(elevationColor = Color.Black.copy(alpha = 0.8f), elevation = 10.dp)),
        colors = ButtonDefaults.colors(
            containerColor = container,
            contentColor = content,
            focusedContainerColor = if (selected) container else Color.White.copy(alpha = 0.2f),
            focusedContentColor = content,
            pressedContainerColor = if (selected) container else Color.White.copy(alpha = 0.3f),
            pressedContentColor = content,
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
        interactionSource = interactions,
    ) {
        icon?.let {
            Icon(it, contentDescription = null, tint = iconTint ?: androidx.tv.material3.LocalContentColor.current, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

/** An episode: its picture (16:9) with the progress, then its number, title, length and plot. */
@Composable
private fun TvEpisodeCard(episode: Episode, progress: WatchProgress?, onClick: () -> Unit, onWatched: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val watched = stringResource(R.string.watched)
    val isWatched = progress?.finished == true
    val context = LocalContext.current
    Column(modifier.width(TvTokens.EpisodeCardWidth), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Card(
            onClick = onClick,
            // OK held marks the episode watched, or unwatched again, as OK held on a poster keeps it in My List.
            onLongClick = {
                onWatched(!isWatched)
                Toast.makeText(context, if (isWatched) R.string.marked_unwatched else R.string.marked_watched, Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .semantics { contentDescription = "${episode.code} ${episode.title}" + if (isWatched) ", $watched" else "" }
                .testTag("episodeCard-${episode.id}"),
            shape = CardDefaults.shape(TvTokens.CardShape),
            scale = CardDefaults.scale(focusedScale = 1.08f),
            border = CardDefaults.border(focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(3.dp, Color.White), shape = TvTokens.CardShape)),
        ) {
            Box(Modifier.fillMaxSize()) {
                Artwork(url = episode.posterUrl, title = episode.code, modifier = Modifier.fillMaxSize())
                if (progress?.finished == true) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = TvTokens.Accent,
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(22.dp),
                    )
                }
                if (progress != null && !progress.finished && progress.fraction > 0f) {
                    TvProgress(progress.fraction, Modifier.align(Alignment.BottomCenter).fillMaxWidth())
                }
            }
        }
        Text("${episode.number}. ${episode.title}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        episode.durationSeconds?.takeIf { it > 0 }?.let {
            Text(durationText(it, LocalConfiguration.current.locales[0]), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        episode.plot?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The director first, then the cast, with TMDb's photos when the viewer has a token ([faces]): each opens the titles
 * of the playlist with that person.
 */
private fun LazyListScope.peopleShelf(director: String?, cast: String?, faces: Map<String, String>, actions: DetailActions) {
    val directors = People.names(director)
    val actors = People.names(cast).filterNot { it in directors }
    if (directors.isEmpty() && actors.isEmpty()) return
    item(key = "people") {
        val directorLabel = stringResource(R.string.director)
        Column(Modifier.testTag("people")) {
            Text(
                stringResource(R.string.cast),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = PageStart, bottom = 6.dp),
            )
            TvRow {
                LazyRow(
                    contentPadding = PaddingValues(start = PageStart, end = TvTokens.EdgeEnd, top = 4.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(TvTokens.CardGap),
                ) {
                    items(directors, key = { "director:$it" }) { name ->
                        TvPersonCard(name, onClick = { actions.onPerson(name) }, role = directorLabel, photoUrl = faces[TextNormalizer.normalize(name)], modifier = Modifier.focusMemory("person:$name"))
                    }
                    items(actors, key = { "actor:$it" }) { name ->
                        TvPersonCard(name, onClick = { actions.onPerson(name) }, photoUrl = faces[TextNormalizer.normalize(name)], modifier = Modifier.focusMemory("person:$name"))
                    }
                }
            }
        }
    }
}

/** Awards and festival selections: each opens the other titles of the playlist that won it or competed for it. */
private fun LazyListScope.awardsShelf(awards: List<AwardRow>, actions: DetailActions) {
    if (awards.isEmpty()) return
    item(key = "awards") {
        val gold = Color(0xFFE6B422)
        Column(Modifier.testTag("awards")) {
            Text(
                stringResource(R.string.awards),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = PageStart, bottom = 12.dp),
            )
            TvRow {
                LazyRow(
                    contentPadding = PaddingValues(start = PageStart, end = TvTokens.EdgeEnd, top = 4.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(awards, key = { "${it.awardId}:${it.year}:${it.name}" }) { award ->
                        val status = when {
                            award.won -> R.string.award_winner
                            award.festival -> R.string.award_in_competition
                            else -> R.string.award_nominated
                        }
                        TvChip(
                            text = award.name + " · " + stringResource(status, award.year.toString()),
                            onClick = { actions.onBrowse(BrowseKind.AWARD, award.awardId) },
                            icon = if (award.won) Icons.Rounded.EmojiEvents else Icons.Rounded.WorkspacePremium,
                            iconTint = if (award.won) gold else null,
                            modifier = Modifier.focusMemory("award:${award.awardId}:${award.year}").testTag("award-${award.awardId}"),
                        )
                    }
                }
            }
        }
    }
}

/** The genres and the year: each opens the titles of the playlist sharing it. */
/** A film's versions (4K DV, HD, VOSTFR…) as chips the remote moves through: the one picked is selected and plays. */
private fun LazyListScope.versionsShelf(versions: List<VersionChip>, selected: String, actions: DetailActions) {
    if (versions.size < 2) return
    item(key = "versions") {
        TvRow {
            LazyRow(
                contentPadding = PaddingValues(start = PageStart, end = TvTokens.EdgeEnd, top = 4.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.testTag("versions"),
            ) {
                itemsIndexed(versions, key = { index, chip -> "version:$index:${chip.id}" }) { _, chip ->
                    TvChip(
                        text = versionLabel(chip),
                        selected = chip.id == selected,
                        onClick = { actions.onVersion(chip.id) },
                        modifier = Modifier.focusMemory("version:${chip.id}").testTag("version-${chip.id}"),
                    )
                }
            }
        }
    }
}

private fun LazyListScope.exploreShelf(genre: String?, year: Int?, actions: DetailActions) {
    val genres = UiMappers.genres(genre)
    if (genres.isEmpty() && year == null) return
    item(key = "explore") {
        val genreLabel = stringResource(R.string.genre_label)
        val yearLabel = stringResource(R.string.year_label)
        TvRow {
            LazyRow(
                contentPadding = PaddingValues(start = PageStart, end = TvTokens.EdgeEnd, top = 4.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.testTag("explore"),
            ) {
                items(genres, key = { "genre:$it" }) { value ->
                    TvChip(
                        text = value,
                        icon = Icons.Rounded.LocalOffer,
                        onClick = { actions.onBrowse(BrowseKind.GENRE, value) },
                        modifier = Modifier.focusMemory("genre:$value").semantics { contentDescription = "$genreLabel $value" },
                    )
                }
                year?.let { value ->
                    item(key = "year") {
                        TvChip(
                            text = value.toString(),
                            icon = Icons.Rounded.CalendarMonth,
                            onClick = { actions.onBrowse(BrowseKind.YEAR, value.toString()) },
                            modifier = Modifier.focusMemory("year").semantics { contentDescription = "$yearLabel $value" },
                        )
                    }
                }
            }
        }
    }
}

private fun LazyListScope.similarShelf(similar: List<ShowcaseItem>, actions: DetailActions) {
    if (similar.isEmpty()) return
    item(key = "similar") {
        Column(Modifier.testTag("similar")) {
            Text(
                stringResource(R.string.more_like_this),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = PageStart, bottom = 12.dp),
            )
            TvRow {
                LazyRow(
                    contentPadding = PaddingValues(start = PageStart, end = TvTokens.EdgeEnd, top = 8.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(TvTokens.CardGap),
                ) {
                    items(similar, key = { it.key }) { item ->
                        TvPosterCard(item, onClick = { actions.onOpen(item) }, modifier = Modifier.focusMemory("similar:${item.key}"))
                    }
                }
            }
        }
    }
}
