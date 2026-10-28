package com.markedoe.ondo.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
import com.markedoe.ondo.core.library.CategoryStyles
import com.markedoe.ondo.core.model.ContentType
import com.markedoe.ondo.ui.CategoriesUiState
import com.markedoe.ondo.ui.screens.CategoriesActions
import com.markedoe.ondo.ui.screens.styleName

/**
 * Languages and categories on a TV, as the TV's Settings: rows the remote goes down one by one, each turned over with
 * OK (the focused row white, its switch the same as in Settings), within the margins a TV may crop. The two text fields
 * take the remote's arrows only once OK opens their keyboard ([rememberTvTextField]). No back button: Back closes the page.
 */
@Composable
fun TvCategoriesScreen(state: CategoriesUiState, type: ContentType, filter: String, actions: CategoriesActions) {
    var newWord by rememberSaveable { mutableStateOf("") }
    val addWord = {
        CategoryStyles.ruleForWord(newWord)?.let { word ->
            actions.onWord(word, true)
            newWord = ""
        }
    }
    val leaveField = rememberLeaveField()
    FocusEntry(Modifier.fillMaxSize().background(TvTokens.Ink)) {
        LazyColumn(
            Modifier.fillMaxSize().testTag("categories"),
            contentPadding = PaddingValues(start = TvTokens.EdgeEnd, top = TvTokens.EdgeTop + 12.dp, end = TvTokens.EdgeEnd, bottom = TvTokens.EdgeTop + 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "title") {
                Text(
                    stringResource(R.string.categories),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
                )
            }
            item(key = "filter") {
                val field = rememberTvTextField()
                OutlinedTextField(
                    value = filter,
                    onValueChange = actions.onFilter,
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    placeholder = { androidx.compose.material3.Text(stringResource(R.string.filter_categories)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (!leaveField()) defaultKeyboardAction(ImeAction.Done) }),
                    readOnly = field.readOnly,
                    modifier = Modifier.padding(horizontal = 12.dp).widthIn(max = 640.dp).fillMaxWidth().then(field.modifier).testTag("categoriesFilter"),
                )
            }
            if (state.tags.isNotEmpty()) {
                sectionTitle("tagsTitle", R.string.languages_and_countries)
                items(state.tags, key = { "tag-" + it.tag }) { tag ->
                    TvSwitchRow(
                        title = tag.label,
                        subtitle = pluralStringResource(R.plurals.category_count, tag.count, tag.count),
                        checked = !tag.hidden,
                        onChange = { actions.onTag(tag.tag, !it) },
                        modifier = Modifier.testTag("tag-" + tag.tag),
                    )
                }
                hint("tagsHint", R.string.tags_explanation)
            }
            if (state.showsRules) {
                sectionTitle("stylesTitle", R.string.styles)
                items(state.styles, key = { "style-" + it.style }) { style ->
                    TvSwitchRow(
                        title = styleName(style.style),
                        subtitle = pluralStringResource(R.plurals.category_count, style.count, style.count),
                        checked = !style.hidden,
                        onChange = { actions.onWord(style.style, !it) },
                        modifier = Modifier.testTag("style-" + style.style),
                    )
                }
                items(state.words, key = { "word-" + it.word }) { word ->
                    ListItem(
                        selected = false,
                        onClick = { actions.onWord(word.word, false) },
                        headlineContent = { Text(word.word, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(pluralStringResource(R.plurals.category_count, word.count, word.count)) },
                        trailingContent = { Text(stringResource(R.string.show), fontWeight = FontWeight.Medium) },
                        modifier = Modifier.testTag("showWord-" + word.word),
                        colors = tvRowColors(),
                        shape = ListItemDefaults.shape(RoundedCornerShape(14.dp)),
                        scale = ListItemDefaults.scale(focusedScale = 1.02f),
                    )
                }
                item(key = "newWord") {
                    val wordField = remember { FocusRequester() }
                    Row(
                        // From the rows above or below, the remote lands on the field, not on Add beside it.
                        Modifier.padding(horizontal = 12.dp).entersOn(wordField),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        val field = rememberTvTextField()
                        OutlinedTextField(
                            value = newWord,
                            onValueChange = { newWord = it },
                            singleLine = true,
                            placeholder = { androidx.compose.material3.Text(stringResource(R.string.word_to_hide)) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            readOnly = field.readOnly,
                            // The word is hidden, and the remote goes on below.
                            keyboardActions = KeyboardActions(onDone = {
                                addWord()
                                leaveField()
                            }),
                            modifier = Modifier.widthIn(max = 480.dp).focusRequester(wordField).then(field.modifier).testTag("wordToHide"),
                        )
                        TvActionButton(onClick = { addWord() }, enabled = CategoryStyles.ruleForWord(newWord) != null, modifier = Modifier.testTag("addWord")) {
                            Text(stringResource(R.string.add), maxLines = 1)
                        }
                    }
                }
                hint("stylesHint", R.string.styles_explanation)
            }
            sectionTitle("categoriesTitle", R.string.categories)
            item(key = "types") {
                val chosen = remember { FocusRequester() }
                // The remote enters the row on the kind shown.
                Row(Modifier.padding(horizontal = 12.dp).entersOn(chosen), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(ContentType.MOVIE to R.string.movies, ContentType.SERIES to R.string.series, ContentType.LIVE to R.string.live_tv).forEach { (value, label) ->
                        TvChoiceButton(
                            selected = type == value,
                            onClick = { actions.onType(value) },
                            label = stringResource(label),
                            modifier = (if (type == value) Modifier.focusRequester(chosen) else Modifier).testTag("type-" + value.name.lowercase()),
                        )
                    }
                }
            }
            if (state.categories.isNotEmpty()) {
                item(key = "all") {
                    val first = remember { FocusRequester() }
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp).entersOn(first), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvActionButton(onClick = { actions.onAll(false) }, modifier = Modifier.focusRequester(first).testTag("showAll")) { Text(stringResource(R.string.show_all), maxLines = 1) }
                        TvActionButton(onClick = { actions.onAll(true) }, modifier = Modifier.testTag("hideAll")) { Text(stringResource(R.string.hide_all), maxLines = 1) }
                    }
                }
            }
            items(state.categories, key = { "category-" + it.id }) { category ->
                val count = pluralStringResource(R.plurals.item_count, category.count, category.count)
                TvSwitchRow(
                    title = category.name,
                    subtitle = category.hiddenWith?.let { stringResource(R.string.hidden_with, styleName(it)) + " · " + count } ?: count,
                    checked = !category.hidden,
                    onChange = { actions.onCategory(category.id, !it) },
                    // Hidden with its language or style: shown again from there.
                    enabled = category.hiddenWith == null,
                    modifier = Modifier.testTag("category-" + category.id),
                )
            }
            if (state.categories.isEmpty()) {
                item(key = "empty") {
                    Text(stringResource(R.string.nothing_here), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
                }
            }
        }
    }
}

/**
 * A row of a field and buttons, or of buttons, that the remote enters from above or below on [first], rather than on
 * whichever is nearest to where it comes from.
 */
private fun Modifier.entersOn(first: FocusRequester): Modifier = this
    .focusProperties {
        onEnter = { if (requestedFocusDirection == FocusDirection.Down || requestedFocusDirection == FocusDirection.Up) runCatching { first.requestFocus() } }
    }
    .focusGroup()

private fun LazyListScope.sectionTitle(key: String, text: Int) = item(key = key) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = TvTokens.Accent,
        modifier = Modifier.padding(start = 12.dp, top = 20.dp, bottom = 4.dp),
    )
}

private fun LazyListScope.hint(key: String, text: Int) = item(key = key) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/** One of a few choices side by side: the chosen one ticked and lighter, the focused one white. */
@Composable
private fun TvChoiceButton(selected: Boolean, onClick: () -> Unit, label: String, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.semantics { this.selected = selected },
        scale = ButtonDefaults.scale(focusedScale = 1.06f),
        colors = ButtonDefaults.colors(
            containerColor = Color.White.copy(alpha = if (selected) 0.28f else 0.10f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = TvTokens.Ink,
        ),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
    ) {
        if (selected) Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(20.dp))
        Text(label, maxLines = 1)
    }
}
