package com.markedoe.ondo.ui.screens

import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.markedoe.ondo.R

/** One page of the help: what a feature does and where it is. */
data class HelpTopic(val id: String, @StringRes val title: Int, @StringRes val body: Int)

val helpTopics = listOf(
    HelpTopic("playlists", R.string.help_playlists_title, R.string.help_playlists_body),
    HelpTopic("search", R.string.help_search_title, R.string.help_search_body),
    HelpTopic("categories", R.string.help_categories_title, R.string.help_categories_body),
    HelpTopic("my-list", R.string.help_mylist_title, R.string.help_mylist_body),
    HelpTopic("guide", R.string.help_guide_title, R.string.help_guide_body),
    HelpTopic("playback", R.string.help_playback_title, R.string.help_playback_body),
    HelpTopic("privacy", R.string.help_privacy_title, R.string.help_privacy_body),
)

/** Help: one section per feature (tap to open), then the support website. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(onBack: () -> Unit, onSupport: () -> Unit) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).testTag("help")) {
        TopAppBar(
            title = { Text(stringResource(R.string.help)) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back)) }
            },
            windowInsets = WindowInsets(0),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(helpTopics, key = { it.id }) { topic ->
                val expanded = open == topic.id
                Column(Modifier.fillMaxWidth().animateContentSize()) {
                    ListItem(
                        headlineContent = { Text(stringResource(topic.title)) },
                        trailingContent = { Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null) },
                        modifier = Modifier
                            .clickable(role = Role.Button) { open = if (expanded) null else topic.id }
                            .testTag("help-" + topic.id),
                    )
                    if (expanded) {
                        Text(
                            stringResource(topic.body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        )
                    }
                    HorizontalDivider()
                }
            }
            item {
                Text(
                    stringResource(R.string.disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            item { ListItem(headlineContent = { Text(stringResource(R.string.support)) }, modifier = Modifier.clickable(onClick = onSupport)) }
        }
    }
}
