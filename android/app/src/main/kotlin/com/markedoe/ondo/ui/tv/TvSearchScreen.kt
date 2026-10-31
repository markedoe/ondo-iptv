package com.markedoe.ondo.ui.tv

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.markedoe.ondo.R
import com.markedoe.ondo.ui.model.ChannelRow
import com.markedoe.ondo.ui.model.ShowcaseItem
import com.markedoe.ondo.ui.screens.SearchFilters
import com.markedoe.ondo.ui.screens.SearchUiState
import com.markedoe.ondo.ui.screens.awardGroupName
import com.markedoe.ondo.ui.screens.yearsText
import com.markedoe.ondo.ui.theme.OndoColors

/** Where the page's words and rows start: the whole width is the page's, Search having no drawer beside it. */
private val SearchStart: Dp = TvTokens.EdgeEnd

/**
 * Search on a TV: a page of its own, with no drawer beside it (Back closes it, back to the page that opened it).
 * The words at the top, typed with the TV's keyboard (OK on the field opens it), Filters beside them; then a row each
 * of the movies, series, channels and people found, each title written under its poster.
 */
@Composable
fun TvSearchScreen(
    state: SearchUiState,
    onQuery: (String) -> Unit,
    onOpen: (ShowcaseItem) -> Unit,
    onPlayChannel: (ChannelRow) -> Unit,
    onFilters: (SearchFilters) -> Unit,
    onPerson: (String) -> Unit,
) {
    val filters = state.filters
    var editingFilters by rememberSaveable { mutableStateOf(false) }
    val filtersButton = remember { FocusRequester() }
    // Search opens on its field (ready to type), not on the results of an earlier search.
    val fieldFocus = remember { FocusRequester() }
    var focused by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!focused) {
            withFrameNanos { }
            focused = runCatching { fieldFocus.requestFocus() }.isSuccess
        }
    }
    val leaveField = rememberLeaveField()
    val nothing = state.people.isEmpty() && state.movies.isEmpty() && state.series.isEmpty() && state.channels.isEmpty()
    val titles = listOf(R.string.movies, R.string.series, R.string.channels, R.string.people).map { stringResource(it) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("search"),
        contentPadding = PaddingValues(top = TvTokens.EdgeTop, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item(key = "field") {
            Row(
                Modifier.fillMaxWidth().padding(start = SearchStart, end = TvTokens.EdgeEnd),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Search opens ready to type: the keyboard is up at once.
                val field = rememberTvTextField(openOnFirstFocus = true)
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQuery,
                    singleLine = true,
                    leadingIcon = { androidx.compose.material3.Icon(Icons.Rounded.Search, contentDescription = null) },
                    placeholder = { androidx.compose.material3.Text(stringResource(R.string.search_prompt)) },
                    // Done goes on to the results.
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (!leaveField()) defaultKeyboardAction(ImeAction.Done) }),
                    readOnly = field.readOnly,
                    // A filled grey field, ringed in white when the remote is on it: the look of the rest of the TV.
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = OndoColors.SurfaceHigh,
                        unfocusedContainerColor = OndoColors.Surface,
                        focusedBorderColor = androidx.compose.ui.graphics.Color.White,
                        unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                        cursorColor = androidx.compose.ui.graphics.Color.White,
                    ),
                    modifier = Modifier.weight(1f).focusRequester(fieldFocus).then(field.modifier).focusMemory("search-field", initial = true).testTag("searchField"),
                )
                TvActionButton(
                    onClick = { editingFilters = true },
                    modifier = Modifier.focusRequester(filtersButton).focusMemory("search-filters").testTag("searchFilters"),
                ) {
                    Icon(Icons.Rounded.FilterList, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.search_filters))
                }
            }
        }
        if (filters.isActive) {
            item(key = "activeFilters") { ActiveFilters(filters, onClear = { onFilters(SearchFilters()) }) }
        }
        // Results show as they come; meanwhile a line says the search goes on.
        if (state.searching && !nothing) {
            item(key = "stillSearching") {
                Text(
                    stringResource(R.string.searching, state.query),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = SearchStart, end = TvTokens.EdgeEnd).testTag("searching"),
                )
            }
        }
        posterRow(titles[0], "searchMovies", state.movies, onOpen)
        posterRow(titles[1], "searchSeries", state.series, onOpen)
        resultRow(countedTitle(titles[2], state.channels.size), "searchChannels", state.channels, key = { it.channel.id }) { row, entry ->
            TvChannelCard(row, onClick = { onPlayChannel(row) }, modifier = entry.focusMemory("search-channel:${row.channel.id}"))
        }
        resultRow(countedTitle(titles[3], state.people.size), "searchPeople", state.people, key = { "person-$it" }) { name, entry ->
            TvPersonCard(name, onClick = { onPerson(name) }, photoUrl = state.photoOf(name), modifier = entry.focusMemory("search-person:$name").testTag("person-$name"))
        }
        val message = when {
            state.searching && nothing -> R.string.searching
            filters.isActive && nothing -> R.string.no_filtered_results
            state.query.isNotBlank() && nothing -> R.string.no_results
            state.query.isBlank() && !filters.isActive -> R.string.search_hint
            else -> null
        }
        message?.let { text ->
            item(key = "message") {
                Text(
                    if (text == R.string.no_results || text == R.string.searching) stringResource(text, state.query) else stringResource(text),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = SearchStart, end = TvTokens.EdgeEnd).widthIn(max = 900.dp),
                )
            }
        }
    }
    if (editingFilters) TvSearchFiltersDialog(state, onFilters, onDismiss = { editingFilters = false })
    FocusBackAfter(editingFilters, filtersButton)
}

/** "Movies (12)": a row's title and how many titles it holds. */
private fun countedTitle(title: String, count: Int) = "$title ($count)"

/** A row of posters found, each with its title (and year) beneath. */
private fun LazyListScope.posterRow(title: String, tag: String, posters: List<ShowcaseItem>, onOpen: (ShowcaseItem) -> Unit) {
    resultRow(countedTitle(title, posters.size), tag, posters, key = { it.key }) { item, entry ->
        Column(Modifier.width(TvTokens.PosterWidth), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TvPosterCard(item, onClick = { onOpen(item) }, modifier = entry.focusMemory("search:${item.key}"))
            Text(
                item.title,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("resultTitle"),
            )
            item.year?.let { Text(it.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/**
 * One card of a row of results: [focusMemory] gives the card its place in the page's memory (Back from a title lands on
 * it) and in its row's (the remote enters the row on it again).
 */
private class ResultCard(private val row: ResultRowFocus, private val index: Int) {
    fun focusMemory(key: String): Modifier = Modifier
        .focusRequester(row.requester(index))
        .onFocusChanged { if (it.isFocused) row.last = index }
        .focusMemory(key)
}

/**
 * Where the remote enters a row of results from above or below: the card it was last on in the row, while that one
 * is on screen, else the first card on screen; the first card of all once the row holds other results.
 */
private class ResultRowFocus(var keys: List<Any>) {
    private val requesters = HashMap<Int, FocusRequester>()
    var last = 0
    var hasFocus = false

    fun requester(index: Int): FocusRequester = requesters.getOrPut(index) { FocusRequester() }

    fun enter(state: LazyListState) {
        val visible = state.layoutInfo.visibleItemsInfo.map { it.index }
        val target = last.takeIf { it in visible } ?: visible.firstOrNull() ?: return
        runCatching { requester(target).requestFocus() }
    }
}

/**
 * A row of results under its [title], none when there are no [results]. Whenever its results change (other words, other
 * filters), it starts again from its first one: a row kept where it was would stay scrolled to wherever one of the
 * titles before now is, often far along the row. Not while the remote is in it (the card it is on stays put), and
 * not back from a title (the row is where it was left, and so is the remote).
 */
private fun <T> LazyListScope.resultRow(
    title: String,
    tag: String,
    results: List<T>,
    key: (T) -> Any,
    card: @Composable (T, ResultCard) -> Unit,
) {
    if (results.isEmpty()) return
    item(key = "shelf:$tag") {
        val keys = results.map(key)
        val state = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
        val focus = remember { ResultRowFocus(keys) }
        if (focus.keys != keys) {
            focus.keys = keys
            if (!focus.hasFocus) {
                focus.last = 0
                // Before the new results are laid out: the row does not first follow one of the old ones.
                state.requestScrollToItem(0)
            }
        }
        Column(Modifier.testTag(tag)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = SearchStart, bottom = 12.dp),
            )
            TvRow {
                LazyRow(
                    state = state,
                    contentPadding = PaddingValues(start = SearchStart, end = TvTokens.EdgeEnd, top = 8.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(TvTokens.CardGap),
                    modifier = Modifier
                        .onFocusChanged { focus.hasFocus = it.hasFocus }
                        .focusProperties {
                            onEnter = { if (requestedFocusDirection == FocusDirection.Down || requestedFocusDirection == FocusDirection.Up) focus.enter(state) }
                        }
                        .focusGroup(),
                ) {
                    itemsIndexed(results, key = { _, item -> key(item) }) { index, item -> card(item, ResultCard(focus, index)) }
                }
            }
        }
    }
}

/** What the search is limited to, and a button to clear it. */
@Composable
private fun ActiveFilters(filters: SearchFilters, onClear: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = SearchStart, end = TvTokens.EdgeEnd).testTag("activeFilters"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        filters.years?.let { FilterLine(Icons.Rounded.CalendarMonth, yearsText(filters)) }
        filters.awardGroup?.let { group ->
            val name = awardGroupName(group)
            FilterLine(Icons.Rounded.EmojiEvents, if (filters.winnersOnly) stringResource(R.string.winners_of, name) else name)
        }
        TvActionButton(onClick = onClear, modifier = Modifier.focusMemory("search-clear").testTag("clearFilters")) {
            Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.clear_filters))
        }
    }
}

@Composable
private fun FilterLine(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
    }
}

/**
 * The filters on a TV: From and To over the playlist's release years, then the festival or ceremony and "winners
 * only", each a row of choices the remote moves along; the choice made is lit. The search changes as they change.
 */
@Composable
private fun TvSearchFiltersDialog(state: SearchUiState, onFilters: (SearchFilters) -> Unit, onDismiss: () -> Unit) {
    val filters = state.filters
    val anyYear = stringResource(R.string.any_year)
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            colors = SurfaceDefaults.colors(containerColor = OndoColors.SurfaceHigh, contentColor = androidx.compose.ui.graphics.Color.White),
            modifier = Modifier.widthIn(min = 480.dp, max = 760.dp).heightIn(max = 480.dp).testTag("searchFiltersSheet"),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                item(key = "title") {
                    Text(
                        stringResource(R.string.search_filters),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
                item(key = "from") {
                    ChoiceRow(
                        label = stringResource(R.string.filter_from),
                        options = listOf(null to anyYear) + state.years.reversed().map { it to it.toString() },
                        selected = filters.fromYear,
                        onSelect = { onFilters(filters.copy(fromYear = it)) },
                        tag = "filterFrom",
                        modifier = Modifier.focusRequester(first),
                    )
                }
                item(key = "to") {
                    ChoiceRow(
                        label = stringResource(R.string.filter_to),
                        options = listOf(null to anyYear) + state.years.map { it to it.toString() },
                        selected = filters.toYear,
                        onSelect = { onFilters(filters.copy(toYear = it)) },
                        tag = "filterTo",
                    )
                }
                if (state.awardGroups.isNotEmpty()) {
                    item(key = "awards") {
                        val anyMovie = stringResource(R.string.any_movie)
                        ChoiceRow(
                            label = stringResource(R.string.awards),
                            options = listOf(null to anyMovie) + state.awardGroups.map { it to awardGroupName(it) },
                            selected = filters.awardGroup,
                            onSelect = { onFilters(filters.copy(awardGroup = it, winnersOnly = filters.winnersOnly && it != null)) },
                            tag = "filterAwards",
                        )
                    }
                    if (filters.awardGroup != null) {
                        item(key = "winners") {
                            val label = stringResource(R.string.winners_only)
                            Row(Modifier.padding(horizontal = 24.dp)) {
                                TvChip(
                                    text = label,
                                    selected = filters.winnersOnly,
                                    icon = if (filters.winnersOnly) Icons.Rounded.Check else null,
                                    onClick = { onFilters(filters.copy(winnersOnly = !filters.winnersOnly)) },
                                    modifier = Modifier.semantics { role = Role.Switch }.testTag("filterWinners"),
                                )
                            }
                        }
                    }
                }
                if (filters.isActive) {
                    item(key = "clear") {
                        Row(Modifier.padding(horizontal = 24.dp)) {
                            TvActionButton(onClick = { onFilters(SearchFilters()) }, modifier = Modifier.testTag("clearFiltersInSheet")) {
                                Text(stringResource(R.string.clear_filters))
                            }
                        }
                    }
                }
            }
            FocusOnOpen(first)
        }
    }
}

/** A filter: its name, then its choices in a row, the one in force lit; [modifier] goes to the first choice. */
@Composable
private fun <T> ChoiceRow(label: String, options: List<Pair<T?, String>>, selected: T?, onSelect: (T?) -> Unit, tag: String, modifier: Modifier = Modifier) {
    val current = options.firstOrNull { it.first == selected }?.second.orEmpty()
    Column(Modifier.semantics { stateDescription = current }.testTag(tag), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp))
        TvRow {
            androidx.compose.foundation.lazy.LazyRow(
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(options, key = { _, it -> it.first?.toString() ?: "any" }) { index, (option, text) ->
                    TvChip(
                        text = text,
                        selected = option == selected,
                        onClick = { onSelect(option) },
                        modifier = (if (index == 0) modifier else Modifier).testTag("$tag-${option ?: "any"}"),
                    )
                }
            }
        }
    }
}
