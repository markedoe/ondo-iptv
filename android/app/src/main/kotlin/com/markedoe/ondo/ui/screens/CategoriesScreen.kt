package com.markedoe.ondo.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.markedoe.ondo.R
import com.markedoe.ondo.core.model.ContentType
import com.markedoe.ondo.ui.CategoriesUiState

class CategoriesActions(
    val onBack: () -> Unit = {},
    val onType: (ContentType) -> Unit = {},
    val onFilter: (String) -> Unit = {},
    val onTag: (String, Boolean) -> Unit = { _, _ -> },
    val onCategory: (String, Boolean) -> Unit = { _, _ -> },
    /** Shows (false) or hides (true) every category listed. */
    val onAll: (Boolean) -> Unit = {},
)

/** Which categories are shown: by language or country tag ("|PT|"), in every playlist, then one by one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(state: CategoriesUiState, type: ContentType, filter: String, actions: CategoriesActions) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).testTag("categories")) {
        TopAppBar(
            title = { Text(stringResource(R.string.categories)) },
            navigationIcon = {
                IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back)) }
            },
            windowInsets = WindowInsets(0),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                OutlinedTextField(
                    value = filter,
                    onValueChange = actions.onFilter,
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.filter_categories)) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (state.tags.isNotEmpty()) {
                item { CategoriesSectionTitle(stringResource(R.string.languages_and_countries)) }
                items(state.tags, key = { "tag-" + it.tag }) { tag ->
                    ListItem(
                        headlineContent = { Text(tag.label) },
                        supportingContent = { Text(pluralStringResource(R.plurals.category_count, tag.count, tag.count)) },
                        trailingContent = {
                            Switch(checked = !tag.hidden, onCheckedChange = { actions.onTag(tag.tag, !it) }, modifier = Modifier.testTag("tag-" + tag.tag))
                        },
                    )
                }
                item {
                    Text(
                        stringResource(R.string.tags_explanation),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            }
            item { CategoriesSectionTitle(stringResource(R.string.categories)) }
            item {
                val options = listOf(ContentType.MOVIE to R.string.movies, ContentType.SERIES to R.string.series, ContentType.LIVE to R.string.live_tv)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    options.forEachIndexed { index, (value, label) ->
                        SegmentedButton(selected = type == value, onClick = { actions.onType(value) }, shape = SegmentedButtonDefaults.itemShape(index, options.size)) {
                            Text(stringResource(label))
                        }
                    }
                }
            }
            if (state.categories.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { actions.onAll(false) }) { Text(stringResource(R.string.show_all)) }
                        TextButton(onClick = { actions.onAll(true) }) { Text(stringResource(R.string.hide_all)) }
                    }
                }
            }
            items(state.categories, key = { it.id }) { category ->
                ListItem(
                    headlineContent = { Text(category.name) },
                    supportingContent = {
                        val count = pluralStringResource(R.plurals.item_count, category.count, category.count)
                        Text(category.hiddenWith?.let { stringResource(R.string.hidden_with, it) + " · " + count } ?: count)
                    },
                    trailingContent = {
                        Switch(
                            checked = !category.hidden,
                            onCheckedChange = { actions.onCategory(category.id, !it) },
                            enabled = category.hiddenWith == null,
                        )
                    },
                )
            }
            if (state.categories.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.nothing_here),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoriesSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}
