package com.markedoe.ondo.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.markedoe.ondo.R
import com.markedoe.ondo.data.SyncDevice
import com.markedoe.ondo.ui.tv.focusFrame
import com.markedoe.ondo.ui.tv.rememberNextField
import com.markedoe.ondo.ui.tv.rememberTvTextField
import kotlin.math.PI
import kotlin.math.sin

/** The Ondo mark: white ring and grey wave (same geometry and colours as the app icon). */
@Composable
fun OndoLogo(size: Dp = 96.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        drawCircle(Color.White, radius = 0.586f * s / 2, style = Stroke(width = 0.115f * s))
        val path = Path()
        val width = 0.707f * s
        val height = 0.14f * s
        for (step in 0..120) {
            val t = step / 120f
            val x = center.x - width / 2 + width * t
            val y = center.y + height / 2 * sin(2 * PI * 1.5 * t).toFloat()
            if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Color(0xFF8E8E93), style = Stroke(width = 0.0625f * s, cap = StrokeCap.Round))
    }
}

/**
 * First launch: what Ondo is, the player-only disclaimer, add a playlist or try the demo, or turn sync on to get the
 * playlists of the devices nearby where Ondo is open (typing a login with a TV remote is the slow way).
 */
@Composable
fun WelcomeScreen(
    onAddPlaylist: () -> Unit,
    onTryDemo: () -> Unit,
    isLoading: Boolean,
    syncOn: Boolean = false,
    syncDevices: List<SyncDevice> = emptyList(),
    onSync: ((Boolean) -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp)
            .testTag("welcome"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        OndoLogo(104.dp)
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.welcome_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp).widthIn(max = 480.dp),
        )
        Spacer(Modifier.height(28.dp))
        Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.widthIn(max = 480.dp)) {
            Feature(Icons.Rounded.Movie, R.string.feature_vod_title, R.string.feature_vod_text)
            Feature(Icons.AutoMirrored.Rounded.PlaylistAdd, R.string.feature_sources_title, R.string.feature_sources_text)
            Feature(Icons.Rounded.LiveTv, R.string.feature_live_title, R.string.feature_live_text)
            Feature(Icons.Rounded.PlayCircle, R.string.feature_player_title, R.string.feature_player_text)
        }
        Spacer(Modifier.height(28.dp))
        Button(onClick = onAddPlaylist, modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().testTag("addPlaylist")) {
            Text(stringResource(R.string.add_playlist))
        }
        OutlinedButton(onClick = onTryDemo, enabled = !isLoading, modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(top = 8.dp).testTag("demoButton")) {
            if (isLoading) ButtonProgress() else Text(stringResource(R.string.try_demo))
        }
        if (onSync != null) {
            Spacer(Modifier.height(20.dp))
            WelcomeSync(syncOn, syncDevices, onSync)
        }
        Text(
            stringResource(R.string.disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 24.dp).widthIn(max = 480.dp),
        )
    }
}

/** Sync on this network from the welcome screen: the switch, what it does, and the devices found while it is on. */
@Composable
private fun WelcomeSync(on: Boolean, devices: List<SyncDevice>, onChange: (Boolean) -> Unit) {
    Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .focusFrame(RoundedCornerShape(12.dp), grow = 1f)
                .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
                .padding(vertical = 8.dp, horizontal = 4.dp)
                .testTag("welcomeSync"),
        ) {
            Icon(Icons.Rounded.Sync, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(stringResource(R.string.sync_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.sync_summary), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = on, onCheckedChange = null)
        }
        Text(stringResource(R.string.sync_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (on) {
            if (devices.isEmpty()) WelcomeDevice(stringResource(R.string.sync_looking), null)
            devices.forEach { WelcomeDevice(it.name, syncDetail(it)) }
        }
    }
}

@Composable
private fun WelcomeDevice(name: String, detail: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp).testTag("syncDevice")) {
        Icon(Icons.Rounded.Devices, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.padding(start = 14.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** The spinner that replaces a button's title while it works; TalkBack reads "Loading…". */
@Composable
private fun ButtonProgress() {
    val label = stringResource(R.string.loading)
    CircularProgressIndicator(Modifier.size(18.dp).semantics { contentDescription = label }, strokeWidth = 2.dp)
}

@Composable
private fun Feature(icon: ImageVector, title: Int, text: Int) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
        Column(Modifier.padding(start = 14.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** What the user typed to add a playlist. */
sealed interface PlaylistForm {
    val name: String
    data class Xtream(override val name: String, val server: String, val username: String, val password: String) : PlaylistForm
    data class M3u(override val name: String, val url: String, val epgUrl: String) : PlaylistForm
}

/** A playlist file chosen with the system picker, copied into the app's storage. */
data class PickedFile(val url: String, val name: String)

/** Add a playlist: Xtream Codes login, or M3U link or file (+ optional guide). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPlaylistScreen(
    onSave: (PlaylistForm) -> Unit,
    onBack: () -> Unit,
    isLoading: Boolean,
    error: String?,
    onPickFile: (() -> Unit)? = null,
    pickedFile: PickedFile? = null,
) {
    var kind by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf("") }
    var server by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    // Not saved with the screen's state: the system can write that state to disk.
    var password by remember { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var epg by rememberSaveable { mutableStateOf("") }
    val valid = if (kind == 0) server.isNotBlank() && username.isNotBlank() && password.isNotBlank() else url.isNotBlank()
    LaunchedEffect(pickedFile) {
        pickedFile ?: return@LaunchedEffect
        kind = 1
        url = pickedFile.url
        if (name.isBlank()) name = pickedFile.name
    }

    // The keyboard's action key goes on to the next field, then to Add; on a TV with the keyboard still open from one
    // field to the next (it opens with OK, see rememberTvTextField).
    val next = KeyboardOptions(imeAction = ImeAction.Next)
    val onNext = rememberNextField()
    val fields = List(6) { rememberTvTextField() }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).testTag("addPlaylistScreen")) {
        TopAppBar(
            title = { Text(stringResource(R.string.add_playlist)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back)) } },
            windowInsets = WindowInsets(0),
        )
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp).widthIn(max = 560.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(R.string.xtream_codes, R.string.m3u_link).forEachIndexed { index, label ->
                    SegmentedButton(selected = kind == index, onClick = { kind = index }, shape = SegmentedButtonDefaults.itemShape(index, 2)) {
                        Text(stringResource(label))
                    }
                }
            }
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true, keyboardActions = onNext, readOnly = fields[0].readOnly, keyboardOptions = next, modifier = Modifier.fillMaxWidth().then(fields[0].modifier))
            if (kind == 0) {
                OutlinedTextField(server, { server = it }, label = { Text(stringResource(R.string.server)) }, placeholder = { Text("http://provider.example:8080") }, singleLine = true, keyboardActions = onNext, readOnly = fields[1].readOnly, keyboardOptions = next.copy(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth().then(fields[1].modifier).testTag("serverField"))
                OutlinedTextField(username, { username = it }, label = { Text(stringResource(R.string.username)) }, singleLine = true, keyboardActions = onNext, readOnly = fields[2].readOnly, keyboardOptions = next, modifier = Modifier.fillMaxWidth().then(fields[2].modifier).testTag("usernameField"))
                OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.password)) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardActions = onNext, readOnly = fields[3].readOnly, keyboardOptions = next.copy(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth().then(fields[3].modifier).testTag("passwordField"))
                Text(stringResource(R.string.xtream_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.playlist_url)) }, singleLine = true, keyboardActions = onNext, readOnly = fields[4].readOnly, keyboardOptions = next.copy(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth().then(fields[4].modifier).testTag("urlField"))
                if (onPickFile != null) {
                    OutlinedButton(onClick = onPickFile, modifier = Modifier.testTag("pickFile")) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.choose_file))
                    }
                }
                OutlinedTextField(epg, { epg = it }, label = { Text(stringResource(R.string.guide_url_optional)) }, singleLine = true, keyboardActions = onNext, readOnly = fields[5].readOnly, keyboardOptions = next.copy(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth().then(fields[5].modifier))
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Button(
                onClick = {
                    if (isLoading) return@Button
                    onSave(
                        if (kind == 0) PlaylistForm.Xtream(name.trim(), server.trim(), username.trim(), password)
                        else PlaylistForm.M3u(name.trim(), url.trim(), epg.trim()),
                    )
                },
                // Not disabled while it checks: the remote's focus stays on it (a disabled button loses it).
                enabled = valid,
                modifier = Modifier.fillMaxWidth().testTag("savePlaylist"),
            ) {
                if (isLoading) ButtonProgress() else Text(stringResource(R.string.add))
            }
            Text(stringResource(R.string.disclaimer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
