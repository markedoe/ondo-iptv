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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.markedoe.ondo.R
import com.markedoe.ondo.core.library.CategoryStyles
import com.markedoe.ondo.core.model.ContentType
import com.markedoe.ondo.ui.CategoriesUiState
import com.markedoe.ondo.ui.tv.rememberLeaveField

class CategoriesActions(
    val onBack: () -> Unit = {},
    val onType: (ContentType) -> Unit = {},
    val onFilter: (String) -> Unit = {},
    val onTag: (String, Boolean) -> Unit = { _, _ -> },
    /** Hides (true) or shows a style ("manga") or a word of the viewer's, in every playlist. */
    val onWord: (String, Boolean) -> Unit = { _, _ -> },
    val onCategory: (String, Boolean) -> Unit = { _, _ -> },
    /** Shows (false) or hides (true) every category listed. */
    val onAll: (Boolean) -> Unit = {},
)

/** Which categories are shown: by language or country tag ("|PT|") or by style, in every playlist, then one by one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(state: CategoriesUiState, type: ContentType, filter: String, actions: CategoriesActions) {
    var newWord by rememberSaveable { mutableStateOf("") }
    val addWord = {
        CategoryStyles.ruleForWord(newWord)?.let { word ->
            actions.onWord(word, true)
            newWord = ""
        }
    }
    val leaveField = rememberLeaveField()
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
                    // Done, as before; on a TV, on to what follows.
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (!leaveField()) defaultKeyboardAction(ImeAction.Done) }),
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
                            Switch(
                                checked = !tag.hidden,
                                onCheckedChange = { actions.onTag(tag.tag, !it) },
                                // TalkBack reaches the switch on its own: it says what it shows.
                                modifier = Modifier.testTag("tag-" + tag.tag).semantics { contentDescription = tag.label },
                            )
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
            if (state.showsRules) {
                item { CategoriesSectionTitle(stringResource(R.string.styles)) }
                items(state.styles, key = { "style-" + it.style }) { style ->
                    ListItem(
                        headlineContent = { Text(styleName(style.style)) },
                        supportingContent = { Text(pluralStringResource(R.plurals.category_count, style.count, style.count)) },
                        trailingContent = {
                            val name = styleName(style.style)
                            Switch(
                                checked = !style.hidden,
                                onCheckedChange = { actions.onWord(style.style, !it) },
                                modifier = Modifier.testTag("style-" + style.style).semantics { contentDescription = name },
                            )
                        },
                    )
                }
                items(state.words, key = { "word-" + it.word }) { word ->
                    ListItem(
                        headlineContent = { Text(word.word) },
                        supportingContent = { Text(pluralStringResource(R.plurals.category_count, word.count, word.count)) },
                        trailingContent = {
                            TextButton(onClick = { actions.onWord(word.word, false) }, modifier = Modifier.testTag("showWord-" + word.word)) {
                                Text(stringResource(R.string.show))
                            }
                        },
                    )
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newWord,
                            onValueChange = { newWord = it },
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.word_to_hide)) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            // The word is hidden; on a TV, on to the categories.
                            keyboardActions = KeyboardActions(onDone = {
                                addWord()
                                leaveField()
                            }),
                            modifier = Modifier.weight(1f).testTag("wordToHide"),
                        )
                        TextButton(onClick = { addWord() }, enabled = CategoryStyles.ruleForWord(newWord) != null, modifier = Modifier.testTag("addWord")) {
                            Text(stringResource(R.string.add))
                        }
                    }
                }
                item {
                    Text(
                        stringResource(R.string.styles_explanation),
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
                        Text(category.hiddenWith?.let { stringResource(R.string.hidden_with, styleName(it)) + " · " + count } ?: count)
                    },
                    trailingContent = {
                        Switch(
                            checked = !category.hidden,
                            onCheckedChange = { actions.onCategory(category.id, !it) },
                            enabled = category.hiddenWith == null,
                            modifier = Modifier.semantics { contentDescription = category.name },
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

/** A style as the screen names it; a tag ("PT") or a word of the viewer's stays as it is. */
@Composable
fun styleName(rule: String): String = when (rule) {
    "manga" -> stringResource(R.string.style_manga)
    "kids" -> stringResource(R.string.style_kids)
    "animation" -> stringResource(R.string.style_animation)
    "documentary" -> stringResource(R.string.style_documentary)
    "sport" -> stringResource(R.string.style_sport)
    "horror" -> stringResource(R.string.style_horror)
    "music" -> stringResource(R.string.style_music)
    else -> rule
}
