package com.markedoe.ondo.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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

data class LiveUiState(
    val categories: List<MediaCategory> = emptyList(),
    /** `null` = all channels, "favorites" handled by [showingFavorites]. */
    val selectedCategoryId: String? = null,
    val showingFavorites: Boolean = false,
    val channels: List<ChannelRow> = emptyList(),
    val favoriteIds: Set<String> = emptySet(),
)

class LiveActions(
    val onCategory: (String?) -> Unit = {},
    val onFavorites: () -> Unit = {},
    val onPlay: (ChannelRow) -> Unit = {},
    val onToggleFavorite: (ChannelRow) -> Unit = {},
)

/** Live TV: category chips and channels with what is on now (long press: favourite). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LiveScreen(state: LiveUiState, actions: LiveActions) {
    LazyColumn(modifier = Modifier.fillMaxSize().testTag("live"), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            TopAppBar(
                title = { Text(stringResource(R.string.live_tv), style = MaterialTheme.typography.headlineSmall) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(selected = state.selectedCategoryId == null && !state.showingFavorites, onClick = { actions.onCategory(null) }, label = { Text(stringResource(R.string.all)) })
                }
                item {
                    FilterChip(
                        selected = state.showingFavorites,
                        onClick = actions.onFavorites,
                        label = { Text(stringResource(R.string.favorites)) },
                        leadingIcon = { Icon(Icons.Rounded.Star, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }
                items(state.categories, key = { it.id }) { category ->
                    FilterChip(selected = state.selectedCategoryId == category.id, onClick = { actions.onCategory(category.id) }, label = { Text(category.name, maxLines = 1) })
                }
            }
        }
        items(state.channels, key = { it.channel.id }) { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = { actions.onPlay(row) }, onLongClick = { actions.onToggleFavorite(row) })
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
                            Icon(Icons.Rounded.Star, contentDescription = stringResource(R.string.favorite), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp).size(14.dp))
                        }
                    }
                    row.nowTitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    row.nowProgress?.let { Box(Modifier.padding(top = 4.dp).width(140.dp)) { ProgressBar(it) } }
                    row.nextTitle?.let { Text(stringResource(R.string.next_program, it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
        if (state.channels.isEmpty()) {
            item { Text(stringResource(if (state.showingFavorites) R.string.no_favorite_channels else R.string.nothing_here), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
        }
    }
}
