package com.markedoe.ondo.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.markedoe.ondo.R
import com.markedoe.ondo.ui.theme.Appearance

data class PlaylistSummary(val id: String, val name: String, val detail: String, val active: Boolean)

data class SettingsUiState(
    val playlists: List<PlaylistSummary> = emptyList(),
    val appearance: Appearance = Appearance.DARK,
    val showAdultCategories: Boolean = false,
    val version: String = "",
    val isRefreshing: Boolean = false,
)

class SettingsActions(
    val onBack: () -> Unit = {},
    val onAddPlaylist: () -> Unit = {},
    val onActivate: (String) -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onDelete: (String) -> Unit = {},
    val onAppearance: (Appearance) -> Unit = {},
    val onShowAdultCategories: (Boolean) -> Unit = {},
    val onPrivacy: () -> Unit = {},
    val onSupport: () -> Unit = {},
)

/** Playlists, appearance and about (player-only disclaimer, privacy policy). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: SettingsUiState, actions: SettingsActions) {
    var deleting by remember { mutableStateOf<PlaylistSummary?>(null) }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).testTag("settings")) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings)) },
            navigationIcon = { IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back)) } },
            actions = { IconButton(onClick = actions.onRefresh, enabled = !state.isRefreshing) { Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.refresh)) } },
            windowInsets = WindowInsets(0),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item { SectionTitle(stringResource(R.string.playlists)) }
            items(state.playlists, key = { it.id }) { playlist ->
                ListItem(
                    headlineContent = { Text(playlist.name) },
                    supportingContent = { Text(playlist.detail) },
                    leadingContent = { if (playlist.active) Icon(Icons.Rounded.CheckCircle, contentDescription = stringResource(R.string.active), tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = {
                        IconButton(onClick = { deleting = playlist }) { Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.delete)) }
                    },
                    modifier = Modifier.clickable { actions.onActivate(playlist.id) },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.add_playlist)) },
                    leadingContent = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    modifier = Modifier.clickable(onClick = actions.onAddPlaylist),
                )
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionTitle(stringResource(R.string.appearance)) }
            item {
                val options = listOf(Appearance.SYSTEM to R.string.appearance_system, Appearance.DARK to R.string.appearance_dark, Appearance.LIGHT to R.string.appearance_light)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    options.forEachIndexed { index, (value, label) ->
                        SegmentedButton(selected = state.appearance == value, onClick = { actions.onAppearance(value) }, shape = SegmentedButtonDefaults.itemShape(index, options.size)) {
                            Text(stringResource(label))
                        }
                    }
                }
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.adult_categories)) },
                    supportingContent = { Text(stringResource(R.string.adult_categories_hint)) },
                    trailingContent = {
                        Switch(
                            checked = state.showAdultCategories,
                            onCheckedChange = actions.onShowAdultCategories,
                            modifier = Modifier.testTag("adultSwitch"),
                        )
                    },
                )
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionTitle(stringResource(R.string.about)) }
            item { Text(stringResource(R.string.disclaimer), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            item { ListItem(headlineContent = { Text(stringResource(R.string.privacy_policy)) }, modifier = Modifier.clickable(onClick = actions.onPrivacy)) }
            item { ListItem(headlineContent = { Text(stringResource(R.string.support)) }, modifier = Modifier.clickable(onClick = actions.onSupport)) }
            item { ListItem(headlineContent = { Text(stringResource(R.string.version)) }, trailingContent = { Text(state.version) }) }
        }
    }
    deleting?.let { playlist ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_playlist_title, playlist.name)) },
            text = { Text(stringResource(R.string.delete_playlist_text)) },
            confirmButton = { TextButton(onClick = { actions.onDelete(playlist.id); deleting = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }
}
