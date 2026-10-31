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
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.material.icons.rounded.VolunteerActivism
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import com.markedoe.ondo.R
import com.markedoe.ondo.data.OndoRepository
import com.markedoe.ondo.ui.updatedAgoText
import androidx.compose.runtime.produceState
import com.markedoe.ondo.core.playback.PlaybackBuffer
import com.markedoe.ondo.data.LastCrash
import com.markedoe.ondo.data.SyncDevice
import com.markedoe.ondo.data.Tip
import com.markedoe.ondo.data.TipOutcome
import com.markedoe.ondo.data.TipsState
import com.markedoe.ondo.data.TmdbCheck
import com.markedoe.ondo.ui.components.TmdbTokenSettings
import com.markedoe.ondo.ui.theme.ondoSwitchColors
import com.markedoe.ondo.ui.tv.focusFrame

data class PlaylistSummary(
    val id: String,
    val name: String,
    val detail: String,
    val active: Boolean,
    val xtream: Boolean = false,
    /** When the playlist in use was last fetched (null: another playlist, or not loaded yet). */
    val updatedAt: Long? = null,
)

data class SettingsUiState(
    val playlists: List<PlaylistSummary> = emptyList(),
    val showAdultCategories: Boolean = false,
    val updateAwards: Boolean = true,
    val prepareAhead: Boolean = false,
    /** Buffer settings in seconds, 0 for automatic. */
    val liveBuffer: Int = 0,
    val vodBuffer: Int = 0,
    /** Hours after which the playlist in use is downloaded again; 0: only when asked. */
    val autoRefreshHours: Int = 24,
    val version: String = "",
    val isRefreshing: Boolean = false,
    val hideWatchedMovies: Boolean = false,
    val syncOnLocalNetwork: Boolean = false,
    /** The devices sync found or heard from (only while it is on). */
    val syncDevices: List<SyncDevice> = emptyList(),
    /** The last error that stopped the app, recorded on this device. */
    val lastCrash: LastCrash? = null,
    /** A TMDb token is saved (typed here or arrived by sync). */
    val hasTmdbToken: Boolean = false,
    /** TMDb refused the saved token when it was used. */
    val tmdbRefused: Boolean = false,
    /** The optional tips: Google Play's prices, or why they are not there. */
    val tips: TipsState = TipsState(offers = emptyList()),
)

class SettingsActions(
    val onBack: () -> Unit = {},
    val onAddPlaylist: () -> Unit = {},
    val onActivate: (String) -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onDelete: (String) -> Unit = {},
    val onShowAdultCategories: (Boolean) -> Unit = {},
    val onHideWatchedMovies: (Boolean) -> Unit = {},
    val onSyncOnLocalNetwork: (Boolean) -> Unit = {},
    val onUpdateAwards: (Boolean) -> Unit = {},
    val onPrepareAhead: (Boolean) -> Unit = {},
    val onLiveBuffer: (Int) -> Unit = {},
    val onAutoRefresh: (Int) -> Unit = {},
    val onVodBuffer: (Int) -> Unit = {},
    val onCategories: () -> Unit = {},
    val onAppLanguage: () -> Unit = {},
    val onHelp: () -> Unit = {},
    val onPrivacy: () -> Unit = {},
    val onSupport: () -> Unit = {},
    /** Checks a TMDb token with TMDb, and saves it only when TMDb accepts it. */
    val onCheckTmdbToken: suspend (String) -> TmdbCheck = { TmdbCheck.UNREACHABLE },
    val onRemoveTmdbToken: () -> Unit = {},
    val onOpenTmdbApiPage: () -> Unit = {},
    val onTip: (Tip) -> Unit = {},
)

/** Playlists, player and about (player-only disclaimer, privacy policy). */
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
                    supportingContent = { Text(playlistDetail(playlist)) },
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
            item { AutoRefreshRow(state.autoRefreshHours, actions.onAutoRefresh, Modifier.testTag("autoRefreshRow")) }
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
                            colors = ondoSwitchColors(),
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
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.adult_categories)) },
                    supportingContent = { Text(stringResource(R.string.adult_categories_hint)) },
                    trailingContent = {
                        val label = stringResource(R.string.adult_categories)
                        Switch(
                            checked = state.showAdultCategories,
                            onCheckedChange = actions.onShowAdultCategories,
                            colors = ondoSwitchColors(),
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
                            colors = ondoSwitchColors(),
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
                            colors = ondoSwitchColors(),
                            modifier = Modifier.testTag("awardsSwitch").semantics { contentDescription = label },
                        )
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.prepare_ahead)) },
                    supportingContent = { Text(stringResource(R.string.prepare_ahead_hint)) },
                    trailingContent = {
                        val label = stringResource(R.string.prepare_ahead)
                        Switch(
                            checked = state.prepareAhead,
                            onCheckedChange = actions.onPrepareAhead,
                            colors = ondoSwitchColors(),
                            modifier = Modifier.testTag("prepareAheadSwitch").semantics { contentDescription = label },
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
            item { SectionTitle("TMDb") }
            item(key = "tmdb") {
                TmdbTokenSettings(state.hasTmdbToken, state.tmdbRefused, actions.onCheckTmdbToken, actions.onRemoveTmdbToken, actions.onOpenTmdbApiPage)
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
            item(key = "tipsTitle") { SectionTitle(stringResource(R.string.tips_title)) }
            item(key = "tipsText") { Text(stringResource(R.string.tips_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            when {
                state.tips.loading -> item(key = "tipsLoading") { Text(stringResource(R.string.tips_loading), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp).testTag("tipsLoading")) }
                state.tips.unavailable -> item(key = "tipsUnavailable") { Text(stringResource(R.string.tips_unavailable), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp).testTag("tipsUnavailable")) }
                else -> state.tips.offers.orEmpty().forEach { offer ->
                    item(key = "tip:${offer.tip}") {
                        ListItem(
                            headlineContent = { Text(stringResource(offer.tip.label)) },
                            leadingContent = { Icon(Icons.Rounded.VolunteerActivism, contentDescription = null) },
                            trailingContent = { Text(offer.price, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) },
                            modifier = Modifier.focusFrame(RoundedCornerShape(12.dp), grow = 1f).clickable { actions.onTip(offer.tip) }.testTag("tip-${offer.tip}"),
                        )
                    }
                }
            }
            state.tips.outcome?.let { outcome ->
                item(key = "tipOutcome") { Text(stringResource(outcome.message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp).testTag("tipOutcome")) }
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
            state.lastCrash?.let { crash ->
                item(key = "lastCrash") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("lastCrash")) {
                        Text(lastCrashTitle(crash), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.last_crash_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SelectionContainer {
                            Text(crash.report, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
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

/** How often the playlist in use is downloaded again on its own. */
@Composable
private fun AutoRefreshRow(hours: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = { Text(stringResource(R.string.auto_refresh)) },
            supportingContent = { Text(autoRefreshLabel(hours)) },
            leadingContent = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
            modifier = modifier.focusFrame(RoundedCornerShape(12.dp), grow = 1f).clickable { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, offset = DpOffset(16.dp, 0.dp)) {
            OndoRepository.AUTO_REFRESH_CHOICES.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(autoRefreshLabel(choice)) },
                    onClick = {
                        open = false
                        onChange(choice)
                    },
                    trailingIcon = { if (choice == hours) Icon(Icons.Rounded.Check, contentDescription = null) },
                    modifier = Modifier.semantics { selected = choice == hours }.testTag("autoRefreshChoice-$choice"),
                )
            }
        }
    }
}

@Composable
internal fun autoRefreshLabel(hours: Int): String = when (hours) {
    0 -> stringResource(R.string.auto_refresh_manual)
    24 -> stringResource(R.string.auto_refresh_daily)
    168 -> stringResource(R.string.auto_refresh_weekly)
    else -> stringResource(R.string.auto_refresh_hours, hours)
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
/** "Last crash: <date and time>". */
@Composable
fun lastCrashTitle(crash: LastCrash): String {
    val context = LocalContext.current
    val date = java.util.Date(crash.at)
    val time = android.text.format.DateFormat.getDateFormat(context).format(date) + " " + android.text.format.DateFormat.getTimeFormat(context).format(date)
    return stringResource(R.string.last_crash, time)
}

@Composable
fun syncDetail(device: SyncDevice): String = when {
    device.unreachable -> stringResource(R.string.sync_not_reached)
    device.syncedAt != null -> stringResource(R.string.sync_synced_at, android.text.format.DateFormat.getTimeFormat(LocalContext.current).format(java.util.Date(device.syncedAt)))
    else -> stringResource(R.string.sync_found)
}

/** A tip's name in Settings. */
internal val Tip.label: Int
    get() = when (this) {
        Tip.SMALL -> R.string.tip_small
        Tip.MEDIUM -> R.string.tip_medium
        Tip.LARGE -> R.string.tip_large
    }

/** What happened to the last tip, said under the tips. */
internal val TipOutcome.message: Int
    get() = when (this) {
        TipOutcome.THANKS -> R.string.tips_thanks
        TipOutcome.PENDING -> R.string.tips_pending
        TipOutcome.FAILED -> R.string.tips_failed
    }

/** The playlist's kind and server, and for the one in use when it was last updated ("Updated 3 minutes ago"). */
@Composable
internal fun playlistDetail(playlist: PlaylistSummary): String {
    val updatedAt = playlist.updatedAt ?: return playlist.detail
    // Ticks every half minute: "Updated 1 minute ago" right after Refresh, then grows.
    val now by produceState(System.currentTimeMillis(), updatedAt) {
        while (true) {
            value = System.currentTimeMillis()
            kotlinx.coroutines.delay(30_000)
        }
    }
    return listOf(playlist.detail, updatedAgoText(updatedAt, now)).filter { it.isNotBlank() }.joinToString(" · ")
}
