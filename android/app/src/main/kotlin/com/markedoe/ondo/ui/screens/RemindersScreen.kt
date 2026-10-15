package com.markedoe.ondo.ui.screens

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.markedoe.ondo.R
import com.markedoe.ondo.data.Reminder
import java.util.Date

/** The programmes the viewer will be reminded of, soonest first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemindersScreen(reminders: List<Reminder>, onRemove: (Reminder) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val day = DateFormat.getMediumDateFormat(context)
    val time = DateFormat.getTimeFormat(context)
    LazyColumn(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).testTag("remindersList"), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            TopAppBar(
                title = { Text(stringResource(R.string.reminders)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                windowInsets = WindowInsets(0),
            )
        }
        items(reminders, key = { it.key }) { reminder ->
            ListItem(
                headlineContent = { Text(reminder.title, maxLines = 1) },
                supportingContent = { Text("${reminder.channelName} · ${day.format(Date(reminder.start))} · ${time.format(Date(reminder.start))}") },
                trailingContent = {
                    IconButton(onClick = { onRemove(reminder) }, modifier = Modifier.testTag("removeReminder")) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.delete))
                    }
                },
            )
        }
        if (reminders.isEmpty()) {
            item {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.no_reminders), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.no_reminders_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
