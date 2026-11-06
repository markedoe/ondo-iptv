package com.markedoe.ondo.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.markedoe.ondo.R
import com.markedoe.ondo.core.library.RowOrder
import com.markedoe.ondo.ui.screens.SelectionsActions
import com.markedoe.ondo.ui.screens.SelectionsUiState
import com.markedoe.ondo.ui.screens.selectionTabsSummary
import com.markedoe.ondo.ui.screens.selectionTagsSummary
import com.markedoe.ondo.ui.selectionTitle

/**
 * Settings → My selections on a TV: New selection first, then each selection as a row (OK opens it to change it; Right
 * reaches its Delete button). Up and Down go through them; Back returns to Settings.
 */
@Composable
fun TvSelectionsScreen(state: SelectionsUiState, actions: SelectionsActions) {
    FocusEntry(Modifier.fillMaxSize().background(TvTokens.Ink)) {
        LazyColumn(
            Modifier.fillMaxSize().testTag("selections"),
            contentPadding = PaddingValues(start = TvTokens.EdgeEnd, top = TvTokens.EdgeTop + 12.dp, end = TvTokens.EdgeEnd, bottom = TvTokens.EdgeTop + 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "title") {
                Text(
                    stringResource(R.string.my_selections),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
                )
            }
            item(key = "hint") {
                Text(
                    stringResource(R.string.my_selections_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).widthIn(max = 900.dp),
                )
            }
            item(key = "new") {
                Button(
                    onClick = actions.onNew,
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp).focusMemory("selections-new", initial = true).testTag("newSelection"),
                    scale = ButtonDefaults.scale(focusedScale = 1.06f),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(22.dp))
                    Text(stringResource(R.string.new_selection), modifier = Modifier.padding(start = 8.dp))
                }
            }
            if (state.selections.isEmpty()) {
                item(key = "none") {
                    Text(
                        stringResource(R.string.my_selections_none),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp).testTag("selectionsNone"),
                    )
                }
            }
            items(state.selections, key = { it.id }) { selection ->
                val tab = selection.tabs.firstOrNull() ?: RowOrder.HOME
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ListItem(
                        selected = false,
                        onClick = { actions.onEdit(selection.id, tab) },
                        headlineContent = { Text(selectionTitle(selection.name, selection.tags, state.genreNames), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Column {
                                Text(selectionTagsSummary(selection, state.genreNames), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(selectionTabsSummary(selection), color = TvTokens.Accent, maxLines = 1)
                            }
                        },
                        leadingContent = { Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(24.dp)) },
                        colors = tvRowColors(),
                        shape = ListItemDefaults.shape(RoundedCornerShape(12.dp)),
                        scale = ListItemDefaults.scale(focusedScale = 1.02f),
                        modifier = Modifier.weight(1f).focusMemory("selections-${selection.id}").testTag("selection-${selection.id}"),
                    )
                    Button(
                        onClick = { actions.onDelete(selection.id) },
                        modifier = Modifier.padding(start = 12.dp).focusMemory("selections-delete-${selection.id}").testTag("deleteSelection-${selection.id}"),
                        scale = ButtonDefaults.scale(focusedScale = 1.06f),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.delete_selection), modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
    }
}
