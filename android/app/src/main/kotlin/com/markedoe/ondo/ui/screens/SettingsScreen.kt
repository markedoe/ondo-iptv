package com.markedoe.ondo.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import com.markedoe.ondo.R
import com.markedoe.ondo.core.playback.PlaybackBuffer
import com.markedoe.ondo.data.SyncDevice
import com.markedoe.ondo.ui.theme.Appearance
import com.markedoe.ondo.ui.tv.focusFrame

data class PlaylistSummary(val id: String, val name: String, val detail: String, val active: Boolean, val xtream: Boolean = false)

data class SettingsUiState(
    val playlists: List<PlaylistSummary> = emptyList(),
    val appearance: Appearance = Appearance.DARK,
    val showAdultCategories: Boolean = false,
    val updateAwards: Boolean = true,
    /** Buffer settings in seconds, 0 for automatic. */
    val liveBuffer: Int = 0,
    val vodBuffer: Int = 0,
    val version: String = "",
    val isRefreshing: Boolean = false,
    val hideWatchedMovies: Boolean = false,
    val syncOnLocalNetwork: Boolean = false,
    /** The devices sync found or heard from (only while it is on). */
    val syncDevices: List<SyncDevice> = emptyList(),
)

class SettingsActions(
    val onBack: () -> Unit = {},
    val onAddPlaylist: () -> Unit = {},
    val onActivate: (String) -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onDelete: (String) -> Unit = {},
    val onAppearance: (Appearance) -> Unit = {},
    val onShowAdultCategories: (Boolean) -> Unit = {},
    val onHideWatchedMovies: (Boolean) -> Unit = {},
    val onSyncOnLocalNetwork: (Boolean) -> Unit = {},
    val onUpdateAwards: (Boolean) -> Unit = {},
    val onLiveBuffer: (Int) -> Unit = {},
    val onVodBuffer: (Int) -> Unit = {},
    val onCategories: () -> Unit = {},
    val onAppLanguage: () -> Unit = {},
    val onHelp: () -> Unit = {},
    val onPrivacy: () -> Unit = {},
    val onSupport: () -> Unit = {},
)

/** Playlists, appearance, player and about (player-only disclaimer, privacy policy). */
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
        LazyColumn(Modifier.testTag("settingsList"), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { SectionTitle(stringResource(R.string.playlists)) }
            items(state.playlists, key = { it.id }) { playlist ->
                ListItem(
                    headlineContent = { Text(playlist.name) },
                    supportingContent = { Text(playlist.detail) },
                    leadingContent = { if (playlist.active) Icon(Icons.Rounded.CheckCircle, contentDescription = stringResource(R.string.active), tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = {
                        IconButton(onClick = { deleting = playlist }) { Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.delete)) }
                    },
                    modifier = Modifier.focusFrame(RoundedCornerShape(12.dp), grow = 1f).clickable { actions.onActivate(playlist.id) },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.add_playlist)) },
                    leadingContent = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    modifier = Modifier.focusFrame(RoundedCornerShape(12.dp), grow = 1f).clickable(onClick = actions.onAddPlaylist),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.sync_title)) },
                    supportingContent = { Text(stringResource(R.string.sync_hint)) },
                    leadingContent = { Icon(Icons.Rounded.Sync, contentDescription = null) },
                    trailingContent = {
                        val label = stringResource(R.string.sync_title)
                        Switch(
                            checked = state.syncOnLocalNetwork,
                            onCheckedChange = actions.onSyncOnLocalNetwork,
                            modifier = Modifier.testTag("syncSwitch").semantics { contentDescription = label },
                        )
                    },
                )
            }
            if (state.syncOnLocalNetwork) {
                if (state.syncDevices.isEmpty()) {
                    item { ListItem(headlineContent = { Text(stringResource(R.string.sync_looking)) }, leadingContent = { Icon(Icons.Rounded.Devices, contentDescription = null) }) }
                }
                items(state.syncDevices, key = { "sync:${it.id}" }) { device ->
                    ListItem(
                        headlineContent = { Text(device.name) },
                        supportingContent = { Text(syncDetail(device)) },
                        leadingContent = { Icon(Icons.Rounded.Devices, contentDescription = null) },
                        modifier = Modifier.testTag("syncDevice"),
                    )
                }
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
                        val label = stringResource(R.string.adult_categories)
                        Switch(
                            checked = state.showAdultCategories,
                            onCheckedChange = actions.onShowAdultCategories,
                            // TalkBack reaches the switch on its own: it says what it turns on.
                            modifier = Modifier.testTag("adultSwitch").semantics { contentDescription = label },
                        )
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.hide_watched)) },
                    supportingContent = { Text(stringResource(R.string.hide_watched_hint)) },
                    trailingContent = {
                        val label = stringResource(R.string.hide_watched)
                        Switch(
                            checked = state.hideWatchedMovies,
                            onCheckedChange = actions.onHideWatchedMovies,
                            modifier = Modifier.testTag("hideWatchedSwitch").semantics { contentDescription = label },
                        )
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.update_awards)) },
                    supportingContent = { Text(stringResource(R.string.update_awards_hint)) },
                    trailingContent = {
                        val label = stringResource(R.string.update_awards)
                        Switch(
                            checked = state.updateAwards,
                            onCheckedChange = actions.onUpdateAwards,
                            modifier = Modifier.testTag("awardsSwitch").semantics { contentDescription = label },
                        )
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.languages_and_categories)) },
                    supportingContent = { Text(stringResource(R.string.categories_hint)) },
                    leadingContent = { Icon(Icons.Rounded.FilterList, contentDescription = null) },
                    modifier = Modifier.focusFrame(RoundedCornerShape(12.dp), grow = 1f).clickable(onClick = actions.onCategories).testTag("categoriesRow"),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.app_language)) },
                    supportingContent = { Text(stringResource(R.string.app_language_hint)) },
                    leadingContent = { Icon(Icons.Rounded.Language, contentDescription = null) },
                    modifier = Modifier.focusFrame(RoundedCornerShape(12.dp), grow = 1f).clickable(onClick = actions.onAppLanguage).testTag("appLanguageRow"),
                )
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionTitle(stringResource(R.string.player)) }
            item { BufferRow(stringResource(R.string.buffer_live), state.liveBuffer, actions.onLiveBuffer, Modifier.testTag("liveBufferRow")) }
            item { BufferRow(stringResource(R.string.buffer_vod), state.vodBuffer, actions.onVodBuffer, Modifier.testTag("vodBufferRow")) }
            item {
                Text(
                    stringResource(R.string.buffer_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionTitle(stringResource(R.string.about)) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.help)) },
                    leadingContent = { Icon(Icons.AutoMirrored.Rounded.HelpOutline, contentDescription = null) },
                    modifier = Modifier.focusFrame(RoundedCornerShape(12.dp), grow = 1f).clickable(onClick = actions.onHelp).testTag("helpRow"),
                )
            }
            item { Text(stringResource(R.string.disclaimer), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            item { ListItem(headlineContent = { Text(stringResource(R.string.privacy_policy)) }, modifier = Modifier.focusFrame(RoundedCornerShape(12.dp), grow = 1f).clickable(onClick = actions.onPrivacy)) }
            item { ListItem(headlineContent = { Text(stringResource(R.string.support)) }, modifier = Modifier.focusFrame(RoundedCornerShape(12.dp), grow = 1f).clickable(onClick = actions.onSupport)) }
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

/** A buffer setting: its current length, and the choices (automatic, then seconds) in a menu. */
@Composable
private fun BufferRow(label: String, seconds: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = { Text(label) },
            supportingContent = { Text(bufferLabel(seconds)) },
            leadingContent = { Icon(Icons.Rounded.Speed, contentDescription = null) },
            modifier = modifier.focusFrame(RoundedCornerShape(12.dp), grow = 1f).clickable { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, offset = DpOffset(16.dp, 0.dp)) {
            PlaybackBuffer.CHOICES.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(bufferLabel(choice)) },
                    onClick = {
                        open = false
                        onChange(choice)
                    },
                    trailingIcon = { if (choice == seconds) Icon(Icons.Rounded.Check, contentDescription = null) },
                    modifier = Modifier.semantics { selected = choice == seconds }.testTag("bufferChoice-$choice"),
                )
            }
        }
    }
}

@Composable
internal fun bufferLabel(seconds: Int): String =
    if (seconds > 0) pluralStringResource(R.plurals.buffer_seconds, seconds, seconds) else stringResource(R.string.automatic)

@Composable
private fun SectionTitle(text: String) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }
}

/** Where sync is with a device: when the last session worked, or that it could not be reached. */
@Composable
fun syncDetail(device: SyncDevice): String = when {
    device.unreachable -> stringResource(R.string.sync_not_reached)
    device.syncedAt != null -> stringResource(R.string.sync_synced_at, android.text.format.DateFormat.getTimeFormat(LocalContext.current).format(java.util.Date(device.syncedAt)))
    else -> stringResource(R.string.sync_found)
}
