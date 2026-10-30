package com.markedoe.ondo.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.markedoe.ondo.R
import com.markedoe.ondo.ui.components.ChannelLogo
import com.markedoe.ondo.ui.components.PosterCard
import com.markedoe.ondo.ui.components.Shelf
import com.markedoe.ondo.ui.components.rememberShelfMetrics
import com.markedoe.ondo.ui.model.ChannelRow
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.tv.focusFrame
import com.markedoe.ondo.ui.tv.rememberLeaveField
import com.markedoe.ondo.ui.tv.rememberTvTextField

/** Release years and awards the search is limited to. One year alone keeps the other end open. */
data class SearchFilters(
    val fromYear: Int? = null,
    val toYear: Int? = null,
    /** A festival or ceremony of the awards file ("cannes", "oscars"…). */
    val awardGroup: String? = null,
    val winnersOnly: Boolean = false,
) {
    val years: IntRange?
        get() {
            if (fromYear == null && toYear == null) return null
            val lower = fromYear ?: Int.MIN_VALUE
            val upper = toYear ?: Int.MAX_VALUE
            return minOf(lower, upper)..maxOf(lower, upper)
        }

    val isActive: Boolean get() = years != null || awardGroup != null
}

data class SearchUiState(
    val query: String = "",
    /** Actors and directors whose name matches (see [com.markedoe.ondo.ui.UiMappers.people]). */
    val people: List<String> = emptyList(),
    val movies: List<ShowcaseItem> = emptyList(),
    val series: List<ShowcaseItem> = emptyList(),
    val channels: List<ChannelRow> = emptyList(),
    val filters: SearchFilters = SearchFilters(),
    /** The filter's choices: release years of the playlist, newest first, and festivals or ceremonies present. */
    val years: List<Int> = emptyList(),
    val awardGroups: List<String> = emptyList(),
    /** The results shown are not yet those of the words typed (or filters set): the search is under way. */
    val searching: Boolean = false,
)

/** Search across movies, series and channels of the current playlist, optionally by release years and awards. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    state: SearchUiState,
    onQuery: (String) -> Unit,
    onOpen: (ShowcaseItem) -> Unit,
    onPlayChannel: (ChannelRow) -> Unit,
    onFilters: (SearchFilters) -> Unit = {},
    onPerson: (String) -> Unit = {},
) {
    val metrics = rememberShelfMetrics()
    val filters = state.filters
    var editingFilters by rememberSaveable { mutableStateOf(false) }
    // Filtered results are a grid inset from the edges; shelves scroll from edge to edge and inset themselves.
    val edge = if (filters.isActive) metrics.gutter else 0.dp
    val inset = metrics.gutter - edge
    val leaveField = rememberLeaveField()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (metrics.compact) 108.dp else 150.dp),
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).testTag("search"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(start = edge, end = edge, top = 12.dp, bottom = 24.dp),
    ) {
        fullWidth {
            Row(Modifier.padding(start = inset, end = (inset - 8.dp).coerceAtLeast(0.dp)), verticalAlignment = Alignment.CenterVertically) {
                val field = rememberTvTextField()
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQuery,
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.search_prompt)) },
                    // Done, as before; on a TV, on to the results.
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (!leaveField()) defaultKeyboardAction(ImeAction.Done) }),
                    readOnly = field.readOnly,
                    modifier = Modifier.weight(1f).then(field.modifier).testTag("searchField"),
                )
                IconButton(
                    onClick = { editingFilters = true },
                    colors = if (filters.isActive) IconButtonDefaults.filledTonalIconButtonColors() else IconButtonDefaults.iconButtonColors(),
                    modifier = Modifier.padding(start = 4.dp).testTag("searchFilters"),
                ) { Icon(Icons.Rounded.FilterList, contentDescription = stringResource(R.string.search_filters)) }
            }
        }
        val nothingYet = state.people.isEmpty() && state.movies.isEmpty() && state.series.isEmpty() && state.channels.isEmpty()
        // Results show as they come; meanwhile a line says the search goes on.
        if (state.searching && !nothingYet) {
            fullWidth {
                Row(Modifier.padding(horizontal = metrics.gutter).testTag("searching"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.searching, state.query), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (filters.isActive) {
            fullWidth { ActiveFilters(filters, onClear = { onFilters(SearchFilters()) }) }
            posterGrid(R.string.movies, state.movies, onOpen)
            posterGrid(R.string.series, state.series, onOpen)
        } else {
            if (state.people.isNotEmpty()) {
                fullWidth { Text(stringResource(R.string.people), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = metrics.gutter)) }
                items(state.people, key = { "person-$it" }, span = { GridItemSpan(maxLineSpan) }) { name ->
                    ListItem(
                        headlineContent = { Text(name) },
                        leadingContent = { Icon(Icons.Rounded.Person, contentDescription = null) },
                        modifier = Modifier.focusFrame(RoundedCornerShape(12.dp), grow = 1f).clickable { onPerson(name) }.padding(horizontal = metrics.gutter - 16.dp).testTag("person-$name"),
                    )
                }
            }
            if (state.movies.isNotEmpty()) {
                fullWidth {
                    Shelf(stringResource(R.string.movies), metrics) {
                        items(state.movies, key = { it.key }) { PosterCard(it, metrics.posterWidth, onClick = { onOpen(it) }) }
                    }
                }
            }
            if (state.series.isNotEmpty()) {
                fullWidth {
                    Shelf(stringResource(R.string.series), metrics) {
                        items(state.series, key = { it.key }) { PosterCard(it, metrics.posterWidth, onClick = { onOpen(it) }) }
                    }
                }
            }
            if (state.channels.isNotEmpty()) {
                fullWidth { Text(stringResource(R.string.channels), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = metrics.gutter)) }
                items(state.channels, key = { it.channel.id }, span = { GridItemSpan(maxLineSpan) }) { row ->
                    ListItem(
                        headlineContent = { Text(row.channel.name) },
                        supportingContent = row.nowTitle?.let { { Text(it) } },
                        leadingContent = {
                            Box(
                                Modifier.size(width = 72.dp, height = 48.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(6.dp),
                                contentAlignment = Alignment.Center,
                            ) { ChannelLogo(url = row.channel.logoUrl, name = row.channel.name) }
                        },
                        modifier = Modifier.focusFrame(RoundedCornerShape(12.dp), grow = 1f).clickable { onPlayChannel(row) }.padding(horizontal = metrics.gutter - 16.dp),
                    )
                }
            }
        }
        val nothing = state.people.isEmpty() && state.movies.isEmpty() && state.series.isEmpty() && state.channels.isEmpty()
        when {
            state.searching && nothing -> fullWidth {
                Row(Modifier.padding(horizontal = metrics.gutter).testTag("searching"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.searching, state.query), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            filters.isActive && nothing -> fullWidth {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.no_filtered_results_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.no_filtered_results), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            state.query.isNotBlank() && nothing -> fullWidth {
                Text(stringResource(R.string.no_results, state.query), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = metrics.gutter))
            }
            state.query.isBlank() && !filters.isActive -> fullWidth {
                Text(stringResource(R.string.search_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = metrics.gutter))
            }
        }
    }
    if (editingFilters) {
        ModalBottomSheet(onDismissRequest = { editingFilters = false }) {
            SearchFiltersSheet(state, onFilters)
        }
    }
}

private fun LazyGridScope.fullWidth(content: @Composable () -> Unit) = item(span = { GridItemSpan(maxLineSpan) }) { content() }

/** Filtered results as a grid: a filter may list hundreds of titles. */
private fun LazyGridScope.posterGrid(title: Int, posters: List<ShowcaseItem>, onOpen: (ShowcaseItem) -> Unit) {
    if (posters.isEmpty()) return
    fullWidth { Text(stringResource(title), style = MaterialTheme.typography.titleLarge) }
    items(posters, key = { it.key }) { item -> PosterCard(item, width = null, onClick = { onOpen(item) }, showsTitle = true) }
}

/** What the search is limited to, and a button to clear it. */
@Composable
private fun ActiveFilters(filters: SearchFilters, onClear: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().testTag("activeFilters"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            filters.years?.let {
                FilterLine(Icons.Rounded.CalendarMonth, yearsText(filters))
            }
            filters.awardGroup?.let { group ->
                val name = awardGroupName(group)
                FilterLine(Icons.Rounded.EmojiEvents, if (filters.winnersOnly) stringResource(R.string.winners_of, name) else name)
            }
        }
        IconButton(onClick = onClear) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.clear_filters)) }
    }
}

@Composable
private fun FilterLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
    }
}

/** The years a search is limited to, in words ("Released since 2020"…). */
@Composable
fun yearsText(filters: SearchFilters): String {
    val from = filters.fromYear
    val to = filters.toYear
    return when {
        from != null && to == null -> stringResource(R.string.released_since, from.toString())
        from == null && to != null -> stringResource(R.string.released_until, to.toString())
        else -> {
            val range = filters.years ?: return ""
            if (range.first == range.last) stringResource(R.string.released_in_year, range.first.toString())
            else stringResource(R.string.released_between, range.first.toString(), range.last.toString())
        }
    }
}

/** A festival or ceremony, as the filter names it. */
@Composable
fun awardGroupName(group: String): String = when (group) {
    "cannes" -> stringResource(R.string.award_group_cannes)
    "venice" -> stringResource(R.string.award_group_venice)
    "berlin" -> stringResource(R.string.award_group_berlin)
    "oscars" -> stringResource(R.string.award_group_oscars)
    "cesars" -> stringResource(R.string.award_group_cesars)
    "baftas" -> stringResource(R.string.award_group_baftas)
    "golden-globes" -> stringResource(R.string.award_group_golden_globes)
    "goyas" -> stringResource(R.string.award_group_goyas)
    "european-film-awards" -> stringResource(R.string.award_group_european_film_awards)
    "san-sebastian" -> stringResource(R.string.award_group_san_sebastian)
    "locarno" -> stringResource(R.string.award_group_locarno)
    else -> group
}

/** From and To over the playlist's release years, then the festival or ceremony and "winners only". */
@Composable
private fun SearchFiltersSheet(state: SearchUiState, onFilters: (SearchFilters) -> Unit) {
    val filters = state.filters
    val anyYear = stringResource(R.string.any_year)
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp).testTag("searchFiltersSheet")) {
        Text(
            stringResource(R.string.search_filters),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        ChoiceItem(
            label = stringResource(R.string.filter_from),
            value = filters.fromYear?.toString() ?: anyYear,
            options = listOf(null to anyYear) + state.years.reversed().map { it to it.toString() },
            selected = filters.fromYear,
            onSelect = { onFilters(filters.copy(fromYear = it)) },
            tag = "filterFrom",
        )
        ChoiceItem(
            label = stringResource(R.string.filter_to),
            value = filters.toYear?.toString() ?: anyYear,
            options = listOf(null to anyYear) + state.years.map { it to it.toString() },
            selected = filters.toYear,
            onSelect = { onFilters(filters.copy(toYear = it)) },
            tag = "filterTo",
        )
        if (state.awardGroups.isNotEmpty()) {
            val anyMovie = stringResource(R.string.any_movie)
            ChoiceItem(
                label = stringResource(R.string.awards),
                value = filters.awardGroup?.let { awardGroupName(it) } ?: anyMovie,
                options = listOf(null to anyMovie) + state.awardGroups.map { it to awardGroupName(it) },
                selected = filters.awardGroup,
                onSelect = { onFilters(filters.copy(awardGroup = it, winnersOnly = filters.winnersOnly && it != null)) },
                tag = "filterAwards",
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.winners_only)) },
                trailingContent = {
                    val label = stringResource(R.string.winners_only)
                    Switch(
                        checked = filters.winnersOnly,
                        onCheckedChange = { onFilters(filters.copy(winnersOnly = it)) },
                        enabled = filters.awardGroup != null,
                        // TalkBack reaches the switch on its own: it says what it keeps.
                        modifier = Modifier.testTag("filterWinners").semantics { contentDescription = label },
                    )
                },
            )
        }
        if (filters.isActive) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { onFilters(SearchFilters()) }, modifier = Modifier.padding(horizontal = 12.dp)) {
                Text(stringResource(R.string.clear_filters))
            }
        }
    }
}

/** A row showing its current choice; tapping it opens the list of choices. */
@Composable
private fun <T> ChoiceItem(label: String, value: String, options: List<Pair<T?, String>>, selected: T?, onSelect: (T?) -> Unit, tag: String) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = { Text(label) },
            trailingContent = { Text(value, color = MaterialTheme.colorScheme.primary) },
            modifier = Modifier.focusFrame(RoundedCornerShape(12.dp), grow = 1f).clickable { open = true }.testTag(tag),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.align(Alignment.TopEnd)) {
            options.forEach { (option, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    leadingIcon = { if (option == selected) Icon(Icons.Rounded.Check, contentDescription = null) },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}
