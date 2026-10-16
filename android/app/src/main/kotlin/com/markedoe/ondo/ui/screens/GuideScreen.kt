package com.markedoe.ondo.ui.screens

import android.Manifest
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.markedoe.ondo.R
import com.markedoe.ondo.core.model.Channel
import com.markedoe.ondo.core.model.EpgProgram
import com.markedoe.ondo.core.model.MediaCategory
import com.markedoe.ondo.data.ReminderNotifications
import com.markedoe.ondo.ui.components.ChannelLogo
import com.markedoe.ondo.ui.components.rememberShelfMetrics
import java.util.Date
import kotlin.math.roundToInt

/** A channel and its programmes in the guide's time window. */
data class GuideRow(val channel: Channel, val programs: List<EpgProgram>)

data class GuideUiState(
    val rows: List<GuideRow> = emptyList(),
    /** Channels of the selection, before the row limit. */
    val total: Int = 0,
    val start: Long = 0,
    val end: Long = 0,
    /** The time the state was made for (the line of now). */
    val now: Long = 0,
    val hasGuide: Boolean = false,
    val isLoading: Boolean = false,
    val categories: List<MediaCategory> = emptyList(),
    val selectedCategoryId: String? = null,
    val showingFavorites: Boolean = false,
    /** Keys of the programmes with a reminder (`channelId|start`). */
    val reminders: Set<String> = emptySet(),
)

class GuideActions(
    val onBack: () -> Unit = {},
    /** null: every channel. */
    val onCategory: (String?) -> Unit = {},
    val onFavorites: () -> Unit = {},
    val onPlay: (Channel) -> Unit = {},
    val onToggleReminder: (Channel, EpgProgram) -> Unit = { _, _ -> },
    val onReminders: () -> Unit = {},
)

private const val HOUR = 3_600_000L

/** Channels down, time across, a line for now: the TV guide of the channels chosen on Live TV. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideScreen(state: GuideUiState, actions: GuideActions) {
    val now = state.now
    val metrics = rememberShelfMetrics()
    val hourWidth = if (metrics.compact) 220.dp else 300.dp
    val channelWidth = if (metrics.compact) 92.dp else 168.dp
    val pxPerHour = with(LocalDensity.current) { hourWidth.toPx() }
    val scroll = rememberScrollState()
    var selected by remember { mutableStateOf<Pair<Channel, EpgProgram>?>(null) }
    // One formatter for every time label (hundreds of programmes), not one per label.
    val context = LocalContext.current
    val timeFormat = remember(context) { DateFormat.getTimeFormat(context) }
    // Opens half an hour before now.
    LaunchedEffect(state.start) {
        if (state.start > 0) scroll.scrollTo(((now - state.start - HOUR / 2).coerceAtLeast(0) * pxPerHour / HOUR).roundToInt())
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).testTag("guide")) {
        TopAppBar(
            title = { Text(stringResource(R.string.tv_guide), maxLines = 1) },
            navigationIcon = {
                IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back)) }
            },
            actions = {
                CategoryMenu(state, actions)
                IconButton(onClick = actions.onReminders, modifier = Modifier.testTag("reminders")) {
                    Icon(Icons.Rounded.Notifications, contentDescription = stringResource(R.string.reminders))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            windowInsets = WindowInsets(0),
        )
        if (!state.hasGuide && !state.isLoading) {
            Column(Modifier.padding(horizontal = metrics.gutter, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.no_tv_guide), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.no_tv_guide_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (state.rows.isNotEmpty()) {
            Row {
                Spacer(Modifier.width(channelWidth))
                TimeHeader(state, hourWidth, scroll, timeFormat)
            }
            HorizontalDivider()
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(state.rows, key = { it.channel.id }) { row ->
                Row(Modifier.height(64.dp)) {
                    ChannelCell(row.channel, channelWidth) { actions.onPlay(row.channel) }
                    Programmes(row, state, now, hourWidth, scroll, timeFormat) { selected = row.channel to it }
                }
                HorizontalDivider()
            }
            if (state.total > state.rows.size) {
                item {
                    Text(
                        pluralStringResource(R.plurals.guide_row_limit, state.total, state.rows.size, state.total),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = metrics.gutter, vertical = 12.dp),
                    )
                }
            }
        }
    }
    selected?.let { (channel, program) ->
        ProgramSheet(
            channel = channel,
            program = program,
            now = now,
            timeFormat = timeFormat,
            hasReminder = "${channel.id}|${program.start}" in state.reminders,
            onPlay = { selected = null; actions.onPlay(channel) },
            onToggleReminder = { actions.onToggleReminder(channel, program) },
            onDismiss = { selected = null },
        )
    }
}

/** My List, every channel, or one category: the same choice as on Live TV. */
@Composable
private fun CategoryMenu(state: GuideUiState, actions: GuideActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("guideCategory")) {
            Icon(Icons.Rounded.FilterList, contentDescription = stringResource(R.string.categories))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            @Composable
            fun option(text: String, selected: Boolean, onClick: () -> Unit) = DropdownMenuItem(
                text = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { if (selected) Icon(Icons.Rounded.Check, contentDescription = null) },
                onClick = { open = false; onClick() },
            )
            option(stringResource(R.string.my_list), state.showingFavorites) { if (!state.showingFavorites) actions.onFavorites() }
            option(stringResource(R.string.all_channels), !state.showingFavorites && state.selectedCategoryId == null) { actions.onCategory(null) }
            state.categories.forEach { category ->
                option(category.name, !state.showingFavorites && state.selectedCategoryId == category.id) { actions.onCategory(category.id) }
            }
        }
    }
}

@Composable
private fun TimeHeader(state: GuideUiState, hourWidth: androidx.compose.ui.unit.Dp, scroll: ScrollState, format: java.text.DateFormat) {
    val slots = ((state.end - state.start) / (HOUR / 2)).toInt()
    Row(Modifier.horizontalScroll(scroll).height(32.dp)) {
        for (slot in 0 until slots) {
            Text(
                format.format(Date(state.start + slot * HOUR / 2)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(hourWidth / 2).padding(start = 6.dp, top = 8.dp),
            )
        }
    }
}

@Composable
private fun ChannelCell(channel: Channel, width: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Row(
        Modifier.width(width).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow).clickable(onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(width = 40.dp, height = 28.dp), contentAlignment = Alignment.Center) { ChannelLogo(url = channel.logoUrl, name = channel.name) }
        if (width > 120.dp) Text(channel.name, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Programmes(
    row: GuideRow,
    state: GuideUiState,
    now: Long,
    hourWidth: androidx.compose.ui.unit.Dp,
    scroll: ScrollState,
    timeFormat: java.text.DateFormat,
    onSelect: (EpgProgram) -> Unit,
) {
    val totalWidth = hourWidth * ((state.end - state.start).toFloat() / HOUR)
    val pxPerHour = with(LocalDensity.current) { hourWidth.toPx() }
    fun px(time: Long) = ((time - state.start) * pxPerHour / HOUR).roundToInt()
    Box(Modifier.horizontalScroll(scroll)) {
        Box(Modifier.width(totalWidth).fillMaxHeight()) {
            if (row.programs.isEmpty()) {
                Text(
                    stringResource(R.string.no_information),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
                )
            }
            row.programs.forEach { program ->
                val start = maxOf(program.start, state.start)
                val end = minOf(program.end, state.end)
                val airing = program.start <= now && now < program.end
                val width = with(LocalDensity.current) { (px(end) - px(start)).toDp() } - 2.dp
                Column(
                    Modifier
                        .offset { IntOffset(px(start), 0) }
                        .width(width.coerceAtLeast(2.dp))
                        .fillMaxHeight()
                        .padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (airing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onSelect(program) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if ("${row.channel.id}|${program.start}" in state.reminders) {
                            Icon(Icons.Rounded.NotificationsActive, contentDescription = null, modifier = Modifier.size(12.dp).padding(end = 2.dp))
                        }
                        Text(program.title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(timeRange(program, timeFormat), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            if (now in state.start until state.end) {
                Box(Modifier.offset { IntOffset(px(now), 0) }.width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.error))
            }
        }
    }
}

private fun timeRange(program: EpgProgram, format: java.text.DateFormat): String =
    format.format(Date(program.start)) + " – " + format.format(Date(program.end))

/** A programme: what, when, on which channel; watch it if it is on, or be reminded before it starts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProgramSheet(
    channel: Channel,
    program: EpgProgram,
    now: Long,
    timeFormat: java.text.DateFormat,
    hasReminder: Boolean,
    onPlay: () -> Unit,
    onToggleReminder: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var blocked by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onToggleReminder() else blocked = true
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 24.dp).testTag("programSheet"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(program.title, style = MaterialTheme.typography.titleLarge)
            listOfNotNull(program.subtitle, program.episode).takeIf { it.isNotEmpty() }?.let {
                Text(it.joinToString(" · "), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val day = DateFormat.getMediumDateFormat(context).format(Date(program.start))
            Text("${channel.name} · $day · ${timeRange(program, timeFormat)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            program.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                if (program.start <= now && now < program.end) {
                    Button(onClick = onPlay) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Text(stringResource(R.string.watch_live), modifier = Modifier.padding(start = 6.dp))
                    }
                }
                if (program.start > now) {
                    FilledTonalButton(
                        onClick = {
                            val needsPermission = !hasReminder && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !ReminderNotifications.allowed(context)
                            if (needsPermission) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else onToggleReminder()
                        },
                        modifier = Modifier.testTag("reminderButton"),
                    ) {
                        Icon(if (hasReminder) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsNone, contentDescription = null)
                        Text(stringResource(if (hasReminder) R.string.reminder_set else R.string.remind_me), modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
            if (blocked) Text(stringResource(R.string.notifications_off), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
