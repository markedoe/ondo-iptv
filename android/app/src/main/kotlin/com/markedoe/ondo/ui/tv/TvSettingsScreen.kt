package com.markedoe.ondo.ui.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.markedoe.ondo.R
import com.markedoe.ondo.core.playback.PlaybackBuffer
import com.markedoe.ondo.data.LastCrash
import com.markedoe.ondo.data.TmdbCheck
import com.markedoe.ondo.ui.components.tmdbStatus
import com.markedoe.ondo.ui.components.tmdbStatusColor
import com.markedoe.ondo.ui.screens.PlaylistSummary
import com.markedoe.ondo.ui.screens.SettingsActions
import com.markedoe.ondo.ui.screens.SettingsUiState
import com.markedoe.ondo.ui.screens.bufferLabel
import com.markedoe.ondo.ui.screens.lastCrashTitle
import com.markedoe.ondo.ui.screens.syncDetail
import com.markedoe.ondo.ui.theme.OndoColors
import kotlinx.coroutines.launch

/** The parts of the TV's Settings, listed on the left. */
enum class TvSettingsSection(val label: Int, val icon: ImageVector, val tag: String) {
    PLAYLISTS(R.string.playlists, Icons.Rounded.Tv, "settingsPlaylists"),
    SYNC(R.string.sync_section, Icons.Rounded.Sync, "settingsSync"),
    PLAYER(R.string.player, Icons.Rounded.Speed, "settingsPlayer"),
    CATALOGUE(R.string.settings_catalogue, Icons.Rounded.VideoLibrary, "settingsCatalogue"),
    ABOUT(R.string.about, Icons.Rounded.Info, "settingsAbout"),
}

/**
 * Settings on a TV, as the TV's own settings: the parts on the left, the one the remote is on shown on the right.
 * Each playlist is a card with its buttons in reach of the remote: use it (or refresh it, the one in use) and
 * delete it, after a confirmation that starts on Cancel. No back button: Back closes the page. Each part and each row
 * keeps its own place ([focusMemory]): back from a page a row opened, the remote is on that row again.
 */
@Composable
fun TvSettingsScreen(state: SettingsUiState, actions: SettingsActions, canChooseAppLanguage: Boolean = true) {
    var section by rememberSaveable { mutableStateOf(TvSettingsSection.PLAYLISTS) }
    var deleting by remember { mutableStateOf<PlaylistSummary?>(null) }
    var choosingBuffer by remember { mutableStateOf<BufferChoice?>(null) }
    val requesters = remember { TvSettingsSection.entries.associateWith { FocusRequester() } }
    // Where the focus goes back once a dialog closes: the button that opened it, or Add a Playlist after a deletion.
    val returns = remember { HashMap<String, FocusRequester>() }
    fun returnTo(key: String) = returns.getOrPut(key) { FocusRequester() }
    var focusBack by remember { mutableStateOf("") }
    // From the left, the remote enters the right side on its first item (then on the one it left); not while the page
    // gives the focus back to the row that opened another page ([FocusMemory.restore]): that row takes it.
    val paneFirst = remember(section) { FocusRequester() }
    val restoring = LocalFocusMemory.current?.restore != null
    FocusBackAfter(open = deleting != null || choosingBuffer != null, requester = returnTo(focusBack))
    Row(
        Modifier
            .fillMaxSize()
            .background(TvTokens.Ink)
            .padding(start = TvTokens.EdgeEnd, top = TvTokens.EdgeTop, end = TvTokens.EdgeEnd, bottom = TvTokens.EdgeTop)
            .testTag("settings"),
    ) {
        Column(
            Modifier
                .width(250.dp)
                .fillMaxHeight()
                // Back from the right side, the remote lands on the part shown, not on the one beside it.
                .focusProperties {
                    onEnter = { if (requestedFocusDirection != FocusDirection.Enter) runCatching { requesters.getValue(section).requestFocus() } }
                }
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stringResource(R.string.settings),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 24.dp),
            )
            TvSettingsSection.entries.forEach { item ->
                SectionItem(
                    item = item,
                    selected = item == section,
                    onFocus = { section = item },
                    modifier = Modifier
                        .focusRequester(requesters.getValue(item))
                        .focusMemory(item.tag, initial = item == TvSettingsSection.PLAYLISTS),
                )
            }
        }
        Box(Modifier.padding(horizontal = 24.dp).width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.1f)))
        LazyColumn(
            Modifier.weight(1f).fillMaxHeight().focusRestorer(if (restoring) FocusRequester.Default else paneFirst).testTag("settingsPane"),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (section) {
                TvSettingsSection.PLAYLISTS -> playlists(state, actions, paneFirst, ::returnTo, onDelete = { deleting = it; focusBack = "delete:${it.id}" })
                TvSettingsSection.SYNC -> sync(state, actions, paneFirst)
                TvSettingsSection.PLAYER -> player(state, actions, paneFirst, ::returnTo, onChoose = { choosingBuffer = it; focusBack = it.key })
                TvSettingsSection.CATALOGUE -> catalogue(state, actions, paneFirst, canChooseAppLanguage)
                TvSettingsSection.ABOUT -> about(state, actions, paneFirst)
            }
        }
    }
    deleting?.let { playlist ->
        TvConfirmDialog(
            title = stringResource(R.string.delete_playlist_title, playlist.name),
            text = stringResource(R.string.delete_playlist_text),
            confirm = stringResource(R.string.delete),
            onConfirm = {
                actions.onDelete(playlist.id)
                focusBack = "add"
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
    choosingBuffer?.let { choice ->
        TvChoiceDialog(
            title = choice.title,
            options = PlaybackBuffer.CHOICES,
            selected = choice.seconds,
            label = { bufferLabel(it) },
            onPick = {
                choice.onChange(it)
                choosingBuffer = null
            },
            onDismiss = { choosingBuffer = null },
        )
    }
}

private class BufferChoice(val key: String, val title: String, val seconds: Int, val onChange: (Int) -> Unit)

/** A part of Settings: shown on the right as soon as the remote is on it. */
@Composable
private fun SectionItem(item: TvSettingsSection, selected: Boolean, onFocus: () -> Unit, modifier: Modifier = Modifier) {
    ListItem(
        selected = selected,
        onClick = onFocus,
        headlineContent = { Text(stringResource(item.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Icon(item.icon, contentDescription = null, modifier = Modifier.size(22.dp)) },
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocus() }.testTag(item.tag),
        colors = tvRowColors(),
        shape = ListItemDefaults.shape(RoundedCornerShape(14.dp)),
        scale = ListItemDefaults.scale(focusedScale = 1.03f),
    )
}

/** A row of a TV page: white when the remote is on it. */
@Composable
fun tvRowColors() = ListItemDefaults.colors(
    containerColor = Color.Transparent,
    contentColor = Color.White.copy(alpha = 0.85f),
    focusedContainerColor = Color.White,
    focusedContentColor = TvTokens.Ink,
    selectedContainerColor = Color.White.copy(alpha = 0.12f),
    selectedContentColor = Color.White,
)

private fun LazyListScope.title(text: Int, hint: Int? = null) {
    item(key = "title:$text") {
        Column(Modifier.padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(text), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            hint?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

private fun LazyListScope.playlists(state: SettingsUiState, actions: SettingsActions, first: FocusRequester, returnTo: (String) -> FocusRequester, onDelete: (PlaylistSummary) -> Unit) {
    title(R.string.playlists)
    items(state.playlists, key = { "playlist:${it.id}" }) { playlist ->
        val firstButton = if (playlist == state.playlists.first()) first else null
        PlaylistCard(playlist, state.isRefreshing, actions, firstButton, deleteRequester = returnTo("delete:${playlist.id}"), onDelete = { onDelete(playlist) })
    }
    item(key = "addPlaylist") {
        SettingRow(
            title = stringResource(R.string.add_playlist),
            icon = Icons.Rounded.Add,
            onClick = actions.onAddPlaylist,
            modifier = Modifier
                .focusRequester(returnTo("add"))
                .then(if (state.playlists.isEmpty()) Modifier.focusRequester(first) else Modifier)
                .focusMemory("settings-add")
                .testTag("addPlaylistRow"),
        )
    }
}

/** A playlist: its kind, name and source, the one in use marked, with its buttons beneath. */
@Composable
private fun PlaylistCard(playlist: PlaylistSummary, refreshing: Boolean, actions: SettingsActions, firstButton: FocusRequester?, deleteRequester: FocusRequester, onDelete: () -> Unit) {
    val first = if (firstButton != null) Modifier.focusRequester(firstButton) else Modifier
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (playlist.active) Color.White.copy(alpha = 0.1f) else OndoColors.Surface, RoundedCornerShape(20.dp))
            .padding(horizontal = 24.dp, vertical = 20.dp)
            .testTag("playlist-${playlist.name}"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(
                Modifier.size(56.dp).background(if (playlist.xtream) Color(0xFF2F4A6B) else Color(0xFF4A3560), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (playlist.xtream) "XT" else "M3U", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(playlist.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (playlist.active) {
                        Text(
                            stringResource(R.string.active),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = TvTokens.Ink,
                            modifier = Modifier.background(TvTokens.Accent, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                Text(playlist.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            // One button, Use then Refresh once the playlist is in use: the remote stays on it.
            TvActionButton(
                onClick = { if (playlist.active) actions.onRefresh() else actions.onActivate(playlist.id) },
                enabled = !(playlist.active && refreshing),
                modifier = first.focusMemory("use:${playlist.id}").testTag(if (playlist.active) "refresh-${playlist.name}" else "use-${playlist.name}"),
            ) {
                Icon(if (playlist.active) Icons.Rounded.Refresh else Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (playlist.active) R.string.refresh else R.string.use_playlist), maxLines = 1)
            }
            val deleteLabel = stringResource(R.string.delete)
            Button(
                onClick = onDelete,
                modifier = Modifier
                    .focusRequester(deleteRequester)
                    .focusMemory("delete:${playlist.id}")
                    .semantics { contentDescription = "$deleteLabel ${playlist.name}" }
                    .testTag("delete-${playlist.name}"),
                scale = ButtonDefaults.scale(focusedScale = 1.06f),
                colors = ButtonDefaults.colors(
                    containerColor = Color.White.copy(alpha = 0.16f),
                    contentColor = Color.White,
                    focusedContainerColor = Color.White,
                    focusedContentColor = DangerRed,
                ),
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(deleteLabel, maxLines = 1)
            }
        }
    }
}

private val DangerRed = Color(0xFFB3261E)

private fun LazyListScope.player(state: SettingsUiState, actions: SettingsActions, first: FocusRequester, returnTo: (String) -> FocusRequester, onChoose: (BufferChoice) -> Unit) {
    title(R.string.player, hint = R.string.buffer_hint)
    item(key = "liveBuffer") {
        val title = stringResource(R.string.buffer_live)
        SettingRow(title, Icons.Rounded.Speed, subtitle = bufferLabel(state.liveBuffer), modifier = Modifier.focusRequester(first).focusRequester(returnTo("liveBuffer")).focusMemory("settings-liveBuffer").testTag("liveBufferRow"), onClick = { onChoose(BufferChoice("liveBuffer", title, state.liveBuffer, actions.onLiveBuffer)) })
    }
    item(key = "vodBuffer") {
        val title = stringResource(R.string.buffer_vod)
        SettingRow(title, Icons.Rounded.Speed, subtitle = bufferLabel(state.vodBuffer), modifier = Modifier.focusRequester(returnTo("vodBuffer")).focusMemory("settings-vodBuffer").testTag("vodBufferRow"), onClick = { onChoose(BufferChoice("vodBuffer", title, state.vodBuffer, actions.onVodBuffer)) })
    }
}

private fun LazyListScope.catalogue(state: SettingsUiState, actions: SettingsActions, first: FocusRequester, canChooseAppLanguage: Boolean) {
    title(R.string.settings_catalogue)
    item(key = "categories") {
        SettingRow(stringResource(R.string.languages_and_categories), Icons.Rounded.FilterList, subtitle = stringResource(R.string.categories_hint), onClick = actions.onCategories, modifier = Modifier.focusRequester(first).focusMemory("settings-categories").testTag("categoriesRow"))
    }
    item(key = "adult") {
        SwitchRow(stringResource(R.string.adult_categories), stringResource(R.string.adult_categories_hint), Icons.Rounded.Lock, state.showAdultCategories, actions.onShowAdultCategories, Modifier.focusMemory("settings-adult").testTag("adultSwitch"))
    }
    item(key = "hideWatched") {
        SwitchRow(stringResource(R.string.hide_watched), stringResource(R.string.hide_watched_hint), Icons.Rounded.VisibilityOff, state.hideWatchedMovies, actions.onHideWatchedMovies, Modifier.focusMemory("settings-hideWatched").testTag("hideWatchedSwitch"))
    }
    item(key = "awards") {
        SwitchRow(stringResource(R.string.update_awards), stringResource(R.string.update_awards_hint), Icons.Rounded.Refresh, state.updateAwards, actions.onUpdateAwards, Modifier.focusMemory("settings-awards").testTag("awardsSwitch"))
    }
    if (canChooseAppLanguage) {
        item(key = "language") {
            SettingRow(stringResource(R.string.app_language), Icons.Rounded.Language, subtitle = stringResource(R.string.app_language_hint), onClick = actions.onAppLanguage, modifier = Modifier.focusMemory("settings-language").testTag("appLanguageRow"))
        }
    }
    item(key = "tmdbTitle") {
        Text("TMDb", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
    }
    item(key = "tmdb") { TvTmdbToken(state, actions) }
}

/**
 * The viewer's own TMDb token on a TV: typed with the remote's keyboard (or arrived by sync), checked with TMDb before
 * it is saved; Remove takes it away, from the other devices too.
 */
@Composable
private fun TvTmdbToken(state: SettingsUiState, actions: SettingsActions) {
    var token by rememberSaveable { mutableStateOf("") }
    var check by remember { mutableStateOf<TmdbCheck?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun submit() {
        if (token.isBlank() || checking) return
        checking = true
        scope.launch {
            val answer = actions.onCheckTmdbToken(token)
            check = answer
            checking = false
            if (answer == TmdbCheck.ACCEPTED) token = ""
        }
    }
    val leaveField = rememberLeaveField()
    Column(Modifier.widthIn(max = 900.dp).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val field = rememberTvTextField()
        OutlinedTextField(
            value = token,
            onValueChange = {
                token = it
                check = null
            },
            label = { androidx.compose.material3.Text(stringResource(R.string.tmdb_token_label)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            // Done checks the token and goes on to the buttons below; the keyboard closes either way.
            keyboardActions = KeyboardActions(onDone = {
                submit()
                if (!leaveField()) defaultKeyboardAction(ImeAction.Done)
            }),
            readOnly = field.readOnly,
            modifier = Modifier.fillMaxWidth().then(field.modifier).focusMemory("settings-tmdb").testTag("tmdbTokenField"),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvActionButton(onClick = ::submit, enabled = token.isNotBlank() && !checking, modifier = Modifier.testTag("tmdbCheck")) {
                Text(stringResource(R.string.tmdb_check_and_save))
            }
            if (state.hasTmdbToken) {
                TvActionButton(
                    onClick = {
                        token = ""
                        check = null
                        actions.onRemoveTmdbToken()
                    },
                    modifier = Modifier.testTag("tmdbRemove"),
                ) { Text(stringResource(R.string.remove)) }
            }
        }
        if (checking) Text(stringResource(R.string.tmdb_checking), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        tmdbStatus(check, state.hasTmdbToken, state.tmdbRefused)?.let { (text, good) ->
            Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = tmdbStatusColor(good), modifier = Modifier.testTag("tmdbStatus"))
        }
        Text(stringResource(R.string.tmdb_help), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Sync on the local network: the switch, what it does, and the devices it found while it is on. */
private fun LazyListScope.sync(state: SettingsUiState, actions: SettingsActions, first: FocusRequester) {
    title(R.string.sync_section)
    item(key = "sync") {
        SwitchRow(stringResource(R.string.sync_title), stringResource(R.string.sync_summary), Icons.Rounded.Sync, state.syncOnLocalNetwork, actions.onSyncOnLocalNetwork, Modifier.focusRequester(first).focusMemory("settings-sync").testTag("syncSwitch"))
    }
    item(key = "syncHint") {
        Text(stringResource(R.string.sync_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(max = 640.dp).padding(vertical = 8.dp))
    }
    if (!state.syncOnLocalNetwork) return
    if (state.syncDevices.isEmpty()) {
        item(key = "syncLooking") { DeviceRow(stringResource(R.string.sync_looking), null) }
    }
    items(state.syncDevices, key = { "sync:${it.id}" }) { device -> DeviceRow(device.name, syncDetail(device)) }
}

/** A device sync found: shown, not something the remote goes to. */
@Composable
private fun DeviceRow(name: String, detail: String?) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("syncDevice"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(Icons.Rounded.Devices, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

private fun LazyListScope.about(state: SettingsUiState, actions: SettingsActions, first: FocusRequester) {
    title(R.string.about)
    item(key = "help") { SettingRow(stringResource(R.string.help), Icons.AutoMirrored.Rounded.HelpOutline, onClick = actions.onHelp, modifier = Modifier.focusRequester(first).focusMemory("settings-help").testTag("helpRow")) }
    item(key = "privacy") { SettingRow(stringResource(R.string.privacy_policy), Icons.Rounded.Lock, onClick = actions.onPrivacy, modifier = Modifier.focusMemory("settings-privacy").testTag("privacyRow")) }
    item(key = "support") { SettingRow(stringResource(R.string.support), Icons.Rounded.SupportAgent, onClick = actions.onSupport, modifier = Modifier.focusMemory("settings-support")) }
    item(key = "disclaimer") {
        Text(stringResource(R.string.disclaimer), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(max = 640.dp).padding(top = 8.dp))
    }
    item(key = "version") {
        Text(stringResource(R.string.version) + "  " + state.version, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    state.lastCrash?.let { crash -> item(key = "lastCrash") { CrashCard(crash) } }
}

/** The last error that stopped the app: the remote can reach it, so that it scrolls into view for a photo. */
@Composable
private fun CrashCard(crash: LastCrash) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(14.dp),
        colors = SurfaceDefaults.colors(containerColor = Color.White.copy(alpha = 0.06f), contentColor = Color.White),
        border = Border(BorderStroke(2.dp, if (focused) Color.White else Color.Transparent)),
        modifier = Modifier.widthIn(max = 900.dp).padding(top = 8.dp).onFocusChanged { focused = it.isFocused }.focusMemory("settings-lastCrash").focusable().testTag("lastCrash"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(lastCrashTitle(crash), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.last_crash_hint), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
            // The error and where it happened: the first lines, which fit on the screen.
            Text(crash.report.lineSequence().take(22).joinToString("\n"), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = Color.White.copy(alpha = 0.85f))
        }
    }
}

/** A setting the remote opens: its title, what it is set to, an icon. */
@Composable
private fun SettingRow(title: String, icon: ImageVector, modifier: Modifier = Modifier, subtitle: String? = null, onClick: () -> Unit) {
    ListItem(
        selected = false,
        onClick = onClick,
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = subtitle?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        leadingContent = { Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp)) },
        modifier = modifier,
        colors = tvRowColors(),
        shape = ListItemDefaults.shape(RoundedCornerShape(14.dp)),
        scale = ListItemDefaults.scale(focusedScale = 1.02f),
    )
}

/** An on / off setting: OK on the row turns it over. */
@Composable
private fun SwitchRow(title: String, subtitle: String, icon: ImageVector, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) =
    TvSwitchRow(title, subtitle, checked, onChange, modifier, icon)

/**
 * An on / off row, the whole row in the remote's reach: OK on it turns it over, and the focused row turns white. A row
 * that cannot be turned over ([enabled] false, its reason in [subtitle]) still takes the focus, to be read.
 */
@Composable
fun TvSwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    ListItem(
        selected = false,
        onClick = { if (enabled) onChange(!checked) },
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = subtitle?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        leadingContent = icon?.let { { Icon(it, contentDescription = null, modifier = Modifier.size(24.dp)) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        modifier = modifier.semantics { this.selected = checked },
        colors = tvRowColors(),
        shape = ListItemDefaults.shape(RoundedCornerShape(14.dp)),
        scale = ListItemDefaults.scale(focusedScale = 1.02f),
    )
}

/** A question before something that cannot be undone: the remote starts on Cancel, the OK that opened it held or not. */
@Composable
fun TvConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cancel = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            colors = SurfaceDefaults.colors(containerColor = OndoColors.SurfaceHigh, contentColor = Color.White),
            border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))),
            modifier = Modifier.widthIn(max = 560.dp).newOkPressesOnly().testTag("confirmDialog"),
        ) {
            Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(text, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.75f))
                Spacer(Modifier.height(4.dp))
                FocusOnOpen(cancel)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    TvActionButton(onClick = onDismiss, modifier = Modifier.focusRequester(cancel).testTag("confirmCancel")) {
                        Text(stringResource(R.string.cancel), maxLines = 1)
                    }
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.testTag("confirmOk"),
                        scale = ButtonDefaults.scale(focusedScale = 1.06f),
                        colors = ButtonDefaults.colors(
                            containerColor = Color.White.copy(alpha = 0.16f),
                            contentColor = Color.White,
                            focusedContainerColor = Color.White,
                            focusedContentColor = DangerRed,
                        ),
                        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
                    ) {
                        Text(confirm, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** A choice among a few values, the current one checked and focused first. */
@Composable
fun <T> TvChoiceDialog(title: String, options: List<T>, selected: T, label: @Composable (T) -> String, onPick: (T) -> Unit, onDismiss: () -> Unit) {
    val current = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            colors = SurfaceDefaults.colors(containerColor = OndoColors.SurfaceHigh, contentColor = Color.White),
            modifier = Modifier.widthIn(min = 360.dp, max = 480.dp).newOkPressesOnly().testTag("choiceDialog"),
        ) {
            Column(Modifier.padding(vertical = 24.dp, horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp, bottom = 12.dp))
                FocusOnOpen(current)
                options.forEach { option ->
                    val isSelected = option == selected
                    ListItem(
                        selected = isSelected,
                        onClick = { onPick(option) },
                        headlineContent = { Text(label(option)) },
                        trailingContent = { if (isSelected) Icon(Icons.Rounded.Check, contentDescription = null) },
                        modifier = Modifier
                            .then(if (isSelected) Modifier.focusRequester(current) else Modifier)
                            .semantics { this.selected = isSelected }
                            .testTag("choice-$option"),
                        colors = tvRowColors(),
                        shape = ListItemDefaults.shape(RoundedCornerShape(12.dp)),
                    )
                }
            }
        }
    }
}
