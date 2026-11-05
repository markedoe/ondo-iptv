package com.markedoe.ondo.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.markedoe.ondo.R
import com.markedoe.ondo.ui.screens.RowOrderUiState
import com.markedoe.ondo.ui.screens.SelectionActions
import com.markedoe.ondo.ui.screens.SelectionDraft
import com.markedoe.ondo.ui.screens.SelectionUiState
import com.markedoe.ondo.ui.screens.draftSaver
import com.markedoe.ondo.ui.screens.tabName
import com.markedoe.ondo.ui.screens.title
import com.markedoe.ondo.ui.selectionTagName

/**
 * A selection's page on a TV: its name (OK opens the keyboard), the tabs it shows in and its tags as chips the remote
 * goes through (OK chooses one or leaves it), "All tags" as a row like Settings', then Save and Delete. Back closes the
 * page without saving.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TvSelectionScreen(state: SelectionUiState, actions: SelectionActions) {
    val draft = rememberSaveable(state.id, saver = draftSaver(state)) { SelectionDraft(state) }
    val title = draft.title(state)
    val leaveField = rememberLeaveField()
    FocusEntry(Modifier.fillMaxSize().background(TvTokens.Ink)) {
        LazyColumn(
            Modifier.fillMaxSize().testTag("selection"),
            contentPadding = PaddingValues(start = TvTokens.EdgeEnd, top = TvTokens.EdgeTop + 12.dp, end = TvTokens.EdgeEnd, bottom = TvTokens.EdgeTop + 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "title") {
                Text(
                    stringResource(if (state.id == null) R.string.new_selection else R.string.edit_selection),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
                )
            }
            item(key = "hint") {
                Text(
                    stringResource(R.string.selection_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            item(key = "name") {
                val field = rememberTvTextField()
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { draft.name = it },
                    singleLine = true,
                    label = { androidx.compose.material3.Text(stringResource(R.string.selection_name)) },
                    placeholder = { androidx.compose.material3.Text(title) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { leaveField() }),
                    readOnly = field.readOnly,
                    modifier = Modifier.padding(horizontal = 12.dp).widthIn(max = 640.dp).fillMaxWidth().then(field.modifier).testTag("selectionName"),
                )
            }
            item(key = "tabs") {
                ChipGroup(stringResource(R.string.selection_shown_in)) {
                    for (tab in RowOrderUiState.TABS) {
                        TvChip(tabName(tab), tab in draft.tabs, "selectionTab-${RowOrderUiState.tag(tab)}") { draft.toggleTab(tab) }
                    }
                }
            }
            item(key = "all") {
                TvSwitchRow(
                    title = stringResource(R.string.selection_all_tags),
                    subtitle = stringResource(if (draft.all) R.string.selection_all_tags_on else R.string.selection_all_tags_off),
                    checked = draft.all,
                    onChange = { draft.all = it },
                    modifier = Modifier.widthIn(max = 760.dp).testTag("selectionAll"),
                )
            }
            for ((group, tags) in state.groups) {
                item(key = "group-$group") {
                    ChipGroup(stringResource(group)) {
                        for (tag in tags) {
                            TvChip(selectionTagName(tag, state.choices.genreNames), tag in draft.tags, "tag-$tag") { draft.toggle(tag) }
                        }
                    }
                }
            }
            if (state.groups.isEmpty()) {
                item(key = "none") {
                    Text(stringResource(R.string.selection_no_tags), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
                }
            }
            item(key = "buttons") {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TvActionButton(
                        onClick = { actions.onSave(title, draft.tags, draft.all, draft.tabs) },
                        enabled = draft.canSave,
                        prominent = true,
                        modifier = Modifier.testTag("saveSelection"),
                    ) { Text(stringResource(R.string.save), maxLines = 1) }
                    if (state.id != null) {
                        TvActionButton(onClick = actions.onDelete, modifier = Modifier.testTag("deleteSelection")) {
                            Text(stringResource(R.string.delete_selection), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/** A group of chips under its title. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipGroup(title: String, chips: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = TvTokens.Accent, modifier = Modifier.padding(bottom = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { chips() }
    }
}

/** A chip the remote reaches: OK chooses it or leaves it. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvChip(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, modifier = Modifier.testTag(tag)) { Text(label, maxLines = 1) }
}
