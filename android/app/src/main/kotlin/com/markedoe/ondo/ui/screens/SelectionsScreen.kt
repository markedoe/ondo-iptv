package com.markedoe.ondo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.markedoe.ondo.R
import com.markedoe.ondo.core.library.RowOrder
import com.markedoe.ondo.core.library.Selections
import com.markedoe.ondo.core.sync.SyncSelection
import com.markedoe.ondo.ui.selectionTagName
import com.markedoe.ondo.ui.selectionTitle
import com.markedoe.ondo.ui.tv.focusFrame

/** Settings → My selections: the open profile's selections, and the names of the genres their tags hold. */
data class SelectionsUiState(
    val selections: List<SyncSelection>,
    val genreNames: Map<String, String> = emptyMap(),
)

class SelectionsActions(
    val onBack: () -> Unit = {},
    /** Opens a new selection's page. */
    val onNew: () -> Unit = {},
    /** Opens a selection's page to change it, first shown in [tab]. */
    val onEdit: (id: String, tab: String) -> Unit = { _, _ -> },
    val onDelete: (id: String) -> Unit = {},
)

/** A selection's tags by name ("Drama, Cannes, 1990s"), those of a kind this version does not know left out. */
@Composable
fun selectionTagsSummary(selection: SyncSelection, genreNames: Map<String, String>): String {
    val names = selection.tags.filter(Selections::isKnown).map { selectionTagName(it, genreNames) }
    val tags = names.joinToString(", ")
    return if (selection.matchesAll && names.size > 1) stringResource(R.string.selections_all_of, tags) else tags
}

/** The tabs a selection shows in ("Home · Movies"). */
@Composable
fun selectionTabsSummary(selection: SyncSelection): String =
    RowOrderUiState.TABS.filter { it in selection.tabs }.map { tabName(it) }.joinToString(" · ")

/**
 * Settings → My selections on a phone or a tablet: each selection with its tags and tabs (tap to change it, the bin to
 * delete it), and New selection. The rows themselves are placed with Settings → Row order.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionsScreen(state: SelectionsUiState, actions: SelectionsActions) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing).testTag("selections")) {
        TopAppBar(
            title = { Text(stringResource(R.string.my_selections)) },
            navigationIcon = {
                IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back)) }
            },
            actions = {
                TextButton(onClick = actions.onNew, modifier = Modifier.testTag("newSelection")) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Text(stringResource(R.string.new_selection))
                }
            },
            windowInsets = WindowInsets(0),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.testTag("selectionsList")) {
            item(key = "hint") {
                Text(
                    stringResource(R.string.my_selections_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (state.selections.isEmpty()) {
                item(key = "none") {
                    Text(
                        stringResource(R.string.my_selections_none),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp).testTag("selectionsNone"),
                    )
                }
            }
            items(state.selections, key = { it.id }) { selection ->
                val tab = selection.tabs.firstOrNull() ?: RowOrder.HOME
                ListItem(
                    headlineContent = { Text(selectionTitle(selection.name, selection.tags, state.genreNames)) },
                    supportingContent = {
                        Column {
                            Text(selectionTagsSummary(selection, state.genreNames), maxLines = 3)
                            Text(selectionTabsSummary(selection), color = MaterialTheme.colorScheme.primary)
                        }
                    },
                    leadingContent = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                    trailingContent = {
                        IconButton(onClick = { actions.onDelete(selection.id) }, modifier = Modifier.testTag("deleteSelection-${selection.id}")) {
                            Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.delete_selection))
                        }
                    },
                    modifier = Modifier
                        .focusFrame(RoundedCornerShape(12.dp), grow = 1f)
                        .clickable(role = Role.Button) { actions.onEdit(selection.id, tab) }
                        .testTag("selection-${selection.id}"),
                )
                HorizontalDivider()
            }
        }
    }
}
