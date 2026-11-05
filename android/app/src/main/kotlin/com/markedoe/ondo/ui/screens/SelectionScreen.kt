package com.markedoe.ondo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.markedoe.ondo.R
import com.markedoe.ondo.core.library.Selections
import com.markedoe.ondo.ui.SelectionChoices
import com.markedoe.ondo.ui.selectionTagName
import com.markedoe.ondo.ui.selectionTitle
import com.markedoe.ondo.ui.theme.ondoSwitchColors

/**
 * A selection as its page shows it (Settings → Row order → New selection): [id] null for a new one; its name, tags,
 * match mode and tabs; and every tag the playlist's titles offer ([choices]).
 */
data class SelectionUiState(
    val id: String?,
    val name: String,
    val tags: List<String>,
    val all: Boolean,
    val tabs: List<String>,
    val choices: SelectionChoices,
) {
    /**
     * The groups of tags offered, each with its title: the genres, awards, decades and styles of the playlist, and after
     * them the tags of the selection the playlist no longer offers (a category hidden, another playlist), so that they
     * can be taken out.
     */
    val groups: List<Pair<Int, List<String>>>
        get() = listOf(
            R.string.selection_genres to (Selections.GENRE to choices.genres.map { it.first }),
            R.string.selection_awards to (Selections.AWARD to choices.awardGroups.map(Selections::award)),
            R.string.selection_decades to (Selections.DECADE to choices.decades.map(Selections::decade)),
            R.string.selection_styles to (Selections.STYLE to choices.styles.map(Selections::style)),
        ).map { (title, offered) ->
            val (kind, list) = offered
            title to list + tags.filter { it.startsWith(kind) && Selections.isKnown(it) && it !in list }
        }.filter { it.second.isNotEmpty() }
}

class SelectionActions(
    val onBack: () -> Unit = {},
    /** Saves it: its name ("" when none was typed: the tags' names show), tags, whether a title needs all of them, and tabs. */
    val onSave: (name: String, tags: List<String>, all: Boolean, tabs: List<String>) -> Unit = { _, _, _, _ -> },
    val onDelete: () -> Unit = {},
)

/** What the viewer chose so far on a selection's page. */
internal class SelectionDraft(state: SelectionUiState) {
    var name by mutableStateOf(state.name)
    var tags by mutableStateOf(state.tags)
    var all by mutableStateOf(state.all)
    var tabs by mutableStateOf(state.tabs)

    fun toggle(tag: String) {
        tags = if (tag in tags) tags - tag else tags + tag
    }

    fun toggleTab(tab: String) {
        tabs = if (tab in tabs) tabs - tab else tabs + tab
    }

    val canSave: Boolean get() = tags.isNotEmpty() && tabs.isNotEmpty()
}

/** The selection's name: the one typed, else its tags' names. */
@Composable
internal fun SelectionDraft.title(state: SelectionUiState): String = selectionTitle(name.trim(), tags, state.choices.genreNames)

/**
 * A selection's page on a phone or a tablet: its name (its tags' names until one is typed), the tabs it shows in, whether
 * a title needs any tag or all of them, then every tag the playlist offers as chips, by group; Save, and Delete for one
 * already made.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SelectionScreen(state: SelectionUiState, actions: SelectionActions) {
    val draft = rememberSaveable(state.id, saver = draftSaver(state)) { SelectionDraft(state) }
    val title = draft.title(state)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing).testTag("selection")) {
        TopAppBar(
            title = { Text(stringResource(if (state.id == null) R.string.new_selection else R.string.edit_selection)) },
            navigationIcon = {
                IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back)) }
            },
            actions = {
                TextButton(
                    // The name typed, or none: the tags' names then, in each viewer's language.
                    onClick = { actions.onSave(draft.name.trim(), draft.tags, draft.all, draft.tabs) },
                    enabled = draft.canSave,
                    modifier = Modifier.testTag("saveSelection"),
                ) { Text(stringResource(R.string.save)) }
            },
            windowInsets = WindowInsets(0),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.testTag("selectionList")) {
            item(key = "hint") {
                Text(
                    stringResource(R.string.selection_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item(key = "name") {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { draft.name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.selection_name)) },
                    placeholder = { Text(title) },
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().testTag("selectionName"),
                )
            }
            item(key = "tabs") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(stringResource(R.string.selection_shown_in), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (tab in RowOrderUiState.TABS) {
                            FilterChip(
                                selected = tab in draft.tabs,
                                onClick = { draft.toggleTab(tab) },
                                label = { Text(tabName(tab)) },
                                modifier = Modifier.testTag("selectionTab-${RowOrderUiState.tag(tab)}"),
                            )
                        }
                    }
                }
            }
            item(key = "all") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.selection_all_tags)) },
                    supportingContent = { Text(stringResource(if (draft.all) R.string.selection_all_tags_on else R.string.selection_all_tags_off)) },
                    trailingContent = {
                        Switch(checked = draft.all, onCheckedChange = { draft.all = it }, colors = ondoSwitchColors(), modifier = Modifier.testTag("selectionAll"))
                    },
                )
            }
            tagGroups(state, draft)
            if (state.groups.isEmpty()) {
                item(key = "none") {
                    Text(
                        stringResource(R.string.selection_no_tags),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            if (state.id != null) {
                item(key = "delete") {
                    OutlinedButton(onClick = actions.onDelete, modifier = Modifier.padding(16.dp).testTag("deleteSelection")) {
                        Text(stringResource(R.string.delete_selection))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
private fun LazyListScope.tagGroups(state: SelectionUiState, draft: SelectionDraft) {
    for ((title, tags) in state.groups) {
        item(key = "group-$title") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (tag in tags) {
                        FilterChip(
                            selected = tag in draft.tags,
                            onClick = { draft.toggle(tag) },
                            label = { Text(selectionTagName(tag, state.choices.genreNames)) },
                            modifier = Modifier.testTag("tag-$tag"),
                        )
                    }
                }
            }
        }
    }
}

/** Keeps what was chosen across a rotation. */
internal fun draftSaver(state: SelectionUiState) = androidx.compose.runtime.saveable.Saver<SelectionDraft, List<Any>>(
    save = { listOf(it.name, ArrayList(it.tags), it.all, ArrayList(it.tabs)) },
    restore = { saved ->
        @Suppress("UNCHECKED_CAST")
        SelectionDraft(state).apply {
            name = saved[0] as String
            tags = saved[1] as List<String>
            all = saved[2] as Boolean
            tabs = saved[3] as List<String>
        }
    },
)
