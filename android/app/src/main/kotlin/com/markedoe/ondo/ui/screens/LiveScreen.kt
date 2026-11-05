package com.markedoe.ondo.ui.screens

import com.markedoe.ondo.ui.tv.tvTopBarSpace
import com.markedoe.ondo.ui.tv.rememberTabListState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CalendarViewDay
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import com.markedoe.ondo.ui.tv.isTelevision
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.markedoe.ondo.R
import com.markedoe.ondo.core.model.MediaCategory
import com.markedoe.ondo.ui.components.ChannelLogo
import com.markedoe.ondo.ui.components.ProgressBar
import com.markedoe.ondo.ui.model.ChannelRow
import com.markedoe.ondo.ui.tv.LocalHeldOk
import com.markedoe.ondo.ui.tv.TvChip
import com.markedoe.ondo.ui.tv.TvRow
import com.markedoe.ondo.ui.tv.focusFrame
import com.markedoe.ondo.ui.tv.focusMemory

data class LiveUiState(
    val categories: List<MediaCategory> = emptyList(),
    /** `null` = all channels, "favorites" handled by [showingFavorites]. */
    val selectedCategoryId: String? = null,
    val showingFavorites: Boolean = false,
    val channels: List<ChannelRow> = emptyList(),
    val favoriteIds: Set<String> = emptySet(),
    /** My List has channels that show (hidden categories and languages left out): Live TV opens on them. */
    val hasFavorites: Boolean = false,
)

class LiveActions(
    val onCategory: (String?) -> Unit = {},
    val onFavorites: () -> Unit = {},
    val onPlay: (ChannelRow) -> Unit = {},
    val onToggleFavorite: (ChannelRow) -> Unit = {},
    val onCategories: () -> Unit = {},
    val onSearch: () -> Unit = {},
    val onGuide: () -> Unit = {},
)

/**
 * Live TV: the choice of channels at the top (My List first, where the tab opens while it has channels, then all of
 * them and the categories), and the channels with what is on now (long press, or OK held on a remote: My List).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LiveScreen(state: LiveUiState, actions: LiveActions) {
    val heldOk = LocalHeldOk.current
    val television = isTelevision()
    // On a TV, the channel the remote is on is shown large beside the list: what is on, how far, what comes next.
    var focusedId by remember { mutableStateOf<String?>(null) }
    // The choice shown at the top, where Up from the channels goes.
    val selectedChoice = remember { FocusRequester() }
    // While the remote is on the choices, the channels that a new choice brings leave it there.
    var onChoices by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
    // On a TV the list takes the left of the screen, the channel the remote is on the right; the chips above both.
    val listWidth = if (television) 0.56f else 1f
    LazyColumn(
        state = rememberTabListState(),
        modifier = Modifier.fillMaxSize().testTag("live"),
        // A TV page's rows keep a 24 dp gutter (tvSafeArea): the channels' frame starts 8 dp further, its text at 24.
        contentPadding = PaddingValues(start = if (television) 8.dp else 0.dp, top = tvTopBarSpace(), bottom = 24.dp),
    ) {
        if (!television) item {
            TopAppBar(
                title = { Text(stringResource(R.string.live_tv), style = MaterialTheme.typography.headlineSmall) },
                actions = {
                    IconButton(onClick = actions.onSearch) { Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.search)) }
                    IconButton(onClick = actions.onGuide, modifier = Modifier.testTag("openGuide")) {
                        Icon(Icons.Rounded.CalendarViewDay, contentDescription = stringResource(R.string.tv_guide))
                    }
                    IconButton(onClick = actions.onCategories, modifier = Modifier.testTag("categoryFilter")) {
                        Icon(Icons.Rounded.FilterList, contentDescription = stringResource(R.string.categories))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        item {
            TvRow {
                LazyRow(
                    // On a TV, room for the focused choice, grown, and its ring.
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = if (television) 6.dp else 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .testTag("liveChips")
                        .onFocusChanged { onChoices = it.hasFocus }
                        // Up from the channels reaches the choice shown, not the chip that happens to lie above.
                        .focusProperties {
                            onEnter = {
                                if (requestedFocusDirection == FocusDirection.Up) runCatching { selectedChoice.requestFocus() }
                            }
                        }
                        .focusGroup(),
                ) {
                    // On a TV the top bar holds Search; the guide and the categories come first here.
                    if (television) {
                        item {
                            CategoryPill(
                                stringResource(R.string.tv_guide),
                                onClick = actions.onGuide,
                                leadingIcon = { Icon(Icons.Rounded.CalendarViewDay, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                modifier = Modifier.testTag("openGuide"),
                            )
                        }
                        item {
                            CategoryPill(
                                stringResource(R.string.categories),
                                onClick = actions.onCategories,
                                leadingIcon = { Icon(Icons.Rounded.FilterList, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                modifier = Modifier.testTag("categoryFilter"),
                            )
                        }
                    }
                    // The choices: My List first, then all channels and the categories.
                    item(key = "choice:favorites") {
                        LiveChoice(
                            stringResource(R.string.my_list),
                            selected = state.showingFavorites,
                            television = television,
                            onClick = actions.onFavorites,
                            icon = Icons.Rounded.Bookmarks,
                            modifier = Modifier.testTag("liveMyList").then(choiceFocus(state.showingFavorites, selectedChoice)),
                        )
                    }
                    item(key = "choice:all") {
                        val selected = state.selectedCategoryId == null && !state.showingFavorites
                        LiveChoice(
                            stringResource(R.string.all),
                            selected = selected,
                            television = television,
                            onClick = { actions.onCategory(null) },
                            modifier = Modifier.testTag("liveAll").then(choiceFocus(selected, selectedChoice)),
                        )
                    }
                    items(state.categories, key = { it.id }) { category ->
                        val selected = !state.showingFavorites && state.selectedCategoryId == category.id
                        LiveChoice(
                            category.name,
                            selected = selected,
                            television = television,
                            onClick = { actions.onCategory(category.id) },
                            modifier = choiceFocus(selected, selectedChoice),
                        )
                    }
                }
            }
        }
        // My List has no channel yet: how to add one, under the choices (once there are some, the tab opens on them).
        if (!state.hasFavorites && !state.showingFavorites && state.channels.isNotEmpty()) {
            item(key = "myListHint") {
                Text(
                    stringResource(if (television) R.string.live_hint_my_list_tv else R.string.live_hint_my_list),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(listWidth).padding(start = 16.dp, end = 16.dp, top = 8.dp).testTag("liveMyListHint"),
                )
            }
        }
        itemsIndexed(state.channels, key = { _, it -> it.channel.id }) { index, row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth(listWidth)
                    // A remote: the frame shows the channel; holding OK toggles My List, as a long press does.
                    .focusFrame(RoundedCornerShape(12.dp), grow = 1.02f)
                    .focusMemory("channel:${row.channel.id}", initial = index == 0 && !onChoices)
                    .onFocusChanged { if (it.isFocused) focusedId = row.channel.id }
                    .clip(RoundedCornerShape(12.dp))
                    // TalkBack offers the long press under this name ("double-tap and hold to …").
                    .combinedClickable(
                        onClick = { actions.onPlay(row) },
                        onLongClickLabel = stringResource(R.string.toggle_favorite_action),
                        onLongClick = {
                            // Shown in My List, the channel leaves it: the rest of OK held is for no other channel.
                            if (state.showingFavorites) heldOk?.arm()
                            actions.onToggleFavorite(row)
                        },
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    row.channel.number?.toString() ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(32.dp),
                )
                Box(
                    Modifier.size(width = 72.dp, height = 48.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ChannelLogo(url = row.channel.logoUrl, name = row.channel.name)
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(row.channel.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (row.channel.id in state.favoriteIds) {
                            Icon(Icons.Rounded.Bookmarks, contentDescription = stringResource(R.string.favorite), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp).size(14.dp))
                        }
                    }
                    row.nowTitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    row.nowProgress?.let { Box(Modifier.padding(top = 4.dp).width(140.dp)) { ProgressBar(it) } }
                    row.nextTitle?.let { Text(stringResource(R.string.next_program, it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
        if (state.channels.isEmpty()) {
            item { Text(stringResource(if (state.showingFavorites) R.string.no_favorite_channels else R.string.nothing_here), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth(listWidth).padding(16.dp)) }
        }
    }
    if (television) {
        val shown = state.channels.firstOrNull { it.channel.id == focusedId } ?: state.channels.firstOrNull()
        shown?.let {
            FocusedChannel(
                it,
                Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxWidth(0.44f)
                    .padding(start = 24.dp, top = tvTopBarSpace() + 64.dp, end = 8.dp),
            )
        }
    }
    }
}

/**
 * The way a choice takes the focus: the choice shown through [selectedChoice] (where Up from the channels goes), the
 * others through their own. Always a requester, only its target changing: a modifier added or taken away as the choice
 * changes would rebuild the chip's focus, and the remote on it would lose it.
 */
@Composable
private fun choiceFocus(selected: Boolean, selectedChoice: FocusRequester): Modifier {
    val own = remember { FocusRequester() }
    return Modifier.focusRequester(if (selected) selectedChoice else own)
}

/**
 * One choice of channels at the top of Live TV. On a phone, a filter chip. On a TV, the TV's chip: the choice shown is
 * white with a check mark, the one the remote is on grows inside a ring (both at once: the white chip ringed).
 */
@Composable
private fun LiveChoice(
    text: String,
    selected: Boolean,
    television: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    if (!television) {
        FilterChip(
            selected = selected,
            onClick = onClick,
            label = { Text(text, maxLines = 1) },
            leadingIcon = icon?.let { { Icon(it, contentDescription = null, modifier = Modifier.size(18.dp)) } },
            modifier = modifier,
        )
        return
    }
    // The TV's chips: the shown one white with a check mark, the one the remote is on ringed and grown (TvChip).
    TvChip(text, onClick = onClick, modifier = modifier, selected = selected, icon = if (selected) Icons.Rounded.Check else icon)
}

/** The channel the remote is on, large: its logo, name, what is on now and how far it is, what comes next. */
@Composable
private fun FocusedChannel(row: ChannelRow, modifier: Modifier = Modifier) {
    Column(modifier.testTag("focusedChannel"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(36.dp),
            contentAlignment = Alignment.Center,
        ) {
            ChannelLogo(url = row.channel.logoUrl, name = row.channel.name)
        }
        Text(
            listOfNotNull(row.channel.number?.toString(), row.channel.name).joinToString(" · "),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        row.nowTitle?.let {
            Text(it, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        row.nowProgress?.let { Box(Modifier.width(220.dp)) { ProgressBar(it) } }
        row.nextTitle?.let {
            Text(stringResource(R.string.next_program, it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
