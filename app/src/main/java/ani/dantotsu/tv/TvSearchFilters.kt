package ani.dantotsu.tv

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.RadioButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import java.util.Calendar

/**
 * What a TV search narrows AniList's anime down to, beyond the title typed: AniList's own search
 * fields, as [ani.dantotsu.connections.anilist.AnilistQueries.searchAniManga] takes them. A genre or
 * tag maps to true when it's wanted, false when it's left out. [sort] null is AniList's order: best
 * match for a title, else most popular.
 */
data class TvSearchFilters(
    val sort: String? = null,
    val format: String? = null,
    val status: String? = null,
    val season: String? = null,
    val year: Int? = null,
    val onList: Boolean? = null,
    val genres: Map<String, Boolean> = emptyMap(),
    val tags: Map<String, Boolean> = emptyMap(),
) {
    /** How many are set, for the button that opens them. */
    val count: Int
        get() = listOfNotNull(sort, format, status, season, year, onList).size + genres.size + tags.size

    val isEmpty: Boolean get() = count == 0
}

private val SORTS = listOf(
    "POPULARITY_DESC" to R.string.tv_filter_sort_popular,
    "SCORE_DESC" to R.string.tv_filter_sort_score,
    "TRENDING_DESC" to R.string.tv_filter_sort_trending,
    "START_DATE_DESC" to R.string.tv_filter_sort_newest,
    "TITLE_ENGLISH" to R.string.tv_filter_sort_title,
)

private val FORMATS = listOf(
    "TV" to R.string.tv_filter_format_tv,
    "TV_SHORT" to R.string.tv_filter_format_tv_short,
    "MOVIE" to R.string.tv_filter_format_movie,
    "SPECIAL" to R.string.tv_filter_format_special,
    "OVA" to R.string.tv_filter_format_ova,
    "ONA" to R.string.tv_filter_format_ona,
    "MUSIC" to R.string.tv_filter_format_music,
)

private val STATUSES = listOf(
    "RELEASING" to R.string.tv_airing_releasing,
    "FINISHED" to R.string.tv_airing_finished,
    "NOT_YET_RELEASED" to R.string.tv_airing_not_yet,
    "CANCELLED" to R.string.tv_airing_cancelled,
    "HIATUS" to R.string.tv_airing_hiatus,
)

private val SEASONS = listOf(
    "WINTER" to R.string.tv_filter_season_winter,
    "SPRING" to R.string.tv_filter_season_spring,
    "SUMMER" to R.string.tv_filter_season_summer,
    "FALL" to R.string.tv_filter_season_fall,
)

/** Next year (for what's announced) back to the earliest years anime has listings for. */
private val YEARS: List<Int> by lazy { (Calendar.getInstance().get(Calendar.YEAR) + 1 downTo 1940).toList() }

private fun <T> labelOf(options: List<Pair<T, Int>>, value: T?): Int? = options.firstOrNull { it.first == value }?.second

/** The filters set, each in a word or two, for the chips under the search field. */
@Composable
fun filterChips(filters: TvSearchFilters): List<String> = buildList {
    labelOf(SORTS, filters.sort)?.let { add(stringResource(it)) }
    labelOf(FORMATS, filters.format)?.let { add(stringResource(it)) }
    labelOf(STATUSES, filters.status)?.let { add(stringResource(it)) }
    val season = labelOf(SEASONS, filters.season)?.let { stringResource(it) }
    when {
        season != null && filters.year != null -> add("$season ${filters.year}")
        season != null -> add(season)
        filters.year != null -> add(filters.year.toString())
    }
    filters.onList?.let { add(stringResource(if (it) R.string.tv_filter_on_list else R.string.tv_filter_not_on_list)) }
    filters.genres.forEach { (name, wanted) -> add(if (wanted) name else stringResource(R.string.tv_filter_without, name)) }
    filters.tags.forEach { (name, wanted) -> add(if (wanted) name else stringResource(R.string.tv_filter_without, name)) }
}

/**
 * The filters, a row each showing what it's set to; picking one opens its choices. Genres and tags
 * open a list where a press takes each from off to wanted to left out, and back; tags, being
 * hundreds, have a field to narrow that list. Every change applies at once, and the search with it.
 */
@Composable
fun SearchFiltersDialog(filters: TvSearchFilters, onChange: (TvSearchFilters) -> Unit, onDismiss: () -> Unit) {
    var picking by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    val any = stringResource(R.string.tv_filter_any)
    TvDialog(onDismissRequest = onDismiss) {
        Surface(shape = CARD_SHAPE, modifier = Modifier.width(560.dp)) {
            Column(Modifier.padding(24.dp)) {
                Text(stringResource(R.string.tv_filters), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 10.dp))
                FocusRestingAt(48.dp) {
                    LazyColumn(Modifier.heightIn(max = 520.dp)) {
                        item { FilterRow(stringResource(R.string.sort_by), labelOf(SORTS, filters.sort)?.let { stringResource(it) } ?: stringResource(R.string.tv_filter_sort_default), Modifier.focusRequester(first)) { picking = "sort" } }
                        item { FilterRow(stringResource(R.string.format), labelOf(FORMATS, filters.format)?.let { stringResource(it) } ?: any) { picking = "format" } }
                        item { FilterRow(stringResource(R.string.tv_filter_status), labelOf(STATUSES, filters.status)?.let { stringResource(it) } ?: any) { picking = "status" } }
                        item { FilterRow(stringResource(R.string.season), labelOf(SEASONS, filters.season)?.let { stringResource(it) } ?: any) { picking = "season" } }
                        item { FilterRow(stringResource(R.string.year), filters.year?.toString() ?: any) { picking = "year" } }
                        item {
                            FilterRow(
                                stringResource(R.string.tv_filter_list),
                                when (filters.onList) { true -> stringResource(R.string.tv_filter_on_list); false -> stringResource(R.string.tv_filter_not_on_list); null -> any },
                            ) { picking = "list" }
                        }
                        item { FilterRow(stringResource(R.string.genres), countLabel(filters.genres) ?: any) { picking = "genres" } }
                        item { FilterRow(stringResource(R.string.tags), countLabel(filters.tags) ?: any) { picking = "tags" } }
                        if (!filters.isEmpty) item {
                            val reset = { onChange(TvSearchFilters()) }
                            ListItem(
                                selected = false,
                                onClick = reset,
                                headlineContent = { Text(stringResource(R.string.tv_filter_reset)) },
                                modifier = Modifier.pointerClick(onClick = reset),
                            )
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        runCatching { first.requestFocus() }
    }

    val close = { picking = null }
    when (picking) {
        "sort" -> OptionDialog(stringResource(R.string.sort_by), listOf<String?>(null) + SORTS.map { it.first }, filters.sort,
            label = { labelOf(SORTS, it)?.let { r -> stringResource(r) } ?: stringResource(R.string.tv_filter_sort_default) },
            onPick = { onChange(filters.copy(sort = it)); close() }, onDismiss = close)
        "format" -> OptionDialog(stringResource(R.string.format), listOf<String?>(null) + FORMATS.map { it.first }, filters.format,
            label = { labelOf(FORMATS, it)?.let { r -> stringResource(r) } ?: any },
            onPick = { onChange(filters.copy(format = it)); close() }, onDismiss = close)
        "status" -> OptionDialog(stringResource(R.string.tv_filter_status), listOf<String?>(null) + STATUSES.map { it.first }, filters.status,
            label = { labelOf(STATUSES, it)?.let { r -> stringResource(r) } ?: any },
            onPick = { onChange(filters.copy(status = it)); close() }, onDismiss = close)
        "season" -> OptionDialog(stringResource(R.string.season), listOf<String?>(null) + SEASONS.map { it.first }, filters.season,
            label = { labelOf(SEASONS, it)?.let { r -> stringResource(r) } ?: any },
            onPick = { onChange(filters.copy(season = it)); close() }, onDismiss = close)
        "year" -> OptionDialog(stringResource(R.string.year), listOf<Int?>(null) + YEARS, filters.year,
            label = { it?.toString() ?: any },
            onPick = { onChange(filters.copy(year = it)); close() }, onDismiss = close)
        "list" -> OptionDialog(stringResource(R.string.tv_filter_list), listOf(null, true, false), filters.onList,
            label = { when (it) { true -> stringResource(R.string.tv_filter_on_list); false -> stringResource(R.string.tv_filter_not_on_list); null -> any } },
            onPick = { onChange(filters.copy(onList = it)); close() }, onDismiss = close)
        // Search never asks for adult titles but with the adult-only setting on: without it, a genre
        // only they have would find nothing.
        "genres" -> TriStateDialog(stringResource(R.string.genres),
            Anilist.genres.orEmpty().filter { it != "Hentai" || ani.dantotsu.settings.saving.PrefManager.getVal<Boolean>(ani.dantotsu.settings.saving.PrefName.AdultOnly) },
            filters.genres, searchable = false,
            onChange = { onChange(filters.copy(genres = it)) }, onDismiss = close)
        "tags" -> TriStateDialog(stringResource(R.string.tags), Anilist.tags?.get(false).orEmpty(), filters.tags, searchable = true,
            onChange = { onChange(filters.copy(tags = it)) }, onDismiss = close)
    }
}

/** "2 wanted · 1 left out", or null when none are set. */
@Composable
private fun countLabel(picked: Map<String, Boolean>): String? {
    if (picked.isEmpty()) return null
    val wanted = picked.count { it.value }
    val left = picked.size - wanted
    return listOfNotNull(
        wanted.takeIf { it > 0 }?.let { stringResource(R.string.tv_filter_wanted, it) },
        left.takeIf { it > 0 }?.let { stringResource(R.string.tv_filter_left_out, it) },
    ).joinToString("  ·  ")
}

/** A filter and what it's set to, as TV settings' rows show a choice. */
@Composable
private fun FilterRow(title: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    ListItem(
        selected = false,
        onClick = onClick,
        headlineContent = { Text(title) },
        trailingContent = {
            Text(value, style = MaterialTheme.typography.bodyLarge, color = LocalContentColor.current.copy(alpha = 0.75f), maxLines = 1)
        },
        modifier = modifier.pointerClick(onClick = onClick),
    )
}

/** One value of several, the current one in focus. */
@Composable
private fun <T> OptionDialog(title: String, options: List<T>, current: T, label: @Composable (T) -> String, onPick: (T) -> Unit, onDismiss: () -> Unit) {
    val selected = remember { FocusRequester() }
    TvDialog(onDismissRequest = onDismiss) {
        Surface(shape = CARD_SHAPE, modifier = Modifier.width(420.dp)) {
            Column(Modifier.padding(24.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 10.dp))
                FocusRestingAt(48.dp) {
                    LazyColumn(Modifier.heightIn(max = 440.dp)) {
                        items(options.size) { i ->
                            val option = options[i]
                            val isCurrent = option == current
                            ListItem(
                                selected = isCurrent,
                                onClick = { onPick(option) },
                                headlineContent = { Text(label(option)) },
                                trailingContent = { RadioButton(selected = isCurrent, onClick = null) },
                                modifier = (if (isCurrent) Modifier.focusRequester(selected) else Modifier).pointerClick { onPick(option) },
                            )
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        runCatching { selected.requestFocus() }
    }
}

/**
 * Genres or tags, each pressed through off, wanted and left out. The ones set come first, so what's
 * chosen is in sight; [searchable] adds a field that narrows the list by name.
 */
@Composable
private fun TriStateDialog(
    title: String,
    names: List<String>,
    picked: Map<String, Boolean>,
    searchable: Boolean,
    onChange: (Map<String, Boolean>) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    // In the order it opened with: rows jumping to the top as they're pressed would lose the focus.
    val order = remember { names.sortedBy { if (it in picked) 0 else 1 } }
    val shown = order.filter { query.isBlank() || it.contains(query.trim(), ignoreCase = true) }
    val first = remember { FocusRequester() }
    TvDialog(onDismissRequest = onDismiss) {
        Surface(shape = CARD_SHAPE, modifier = Modifier.width(520.dp)) {
            Column(Modifier.padding(24.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.tv_filter_tristate_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                )
                if (searchable) TvSearchField(
                    query = query,
                    onQuery = { query = it },
                    hint = stringResource(R.string.tv_filter_find_tag),
                    onSubmit = {},
                    compact = true,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                if (names.isEmpty()) Text(stringResource(R.string.loading), modifier = Modifier.padding(16.dp))
                FocusRestingAt(48.dp) {
                    // A searched list keeps its height as it narrows, so the dialog stays put under the typing.
                    LazyColumn(if (searchable) Modifier.height(420.dp) else Modifier.heightIn(max = 420.dp)) {
                        items(shown, key = { it }) { name ->
                            val state = picked[name]
                            val cycle = {
                                onChange(
                                    when (state) {
                                        null -> picked + (name to true)
                                        true -> picked + (name to false)
                                        false -> picked - name
                                    }
                                )
                            }
                            ListItem(
                                selected = state != null,
                                onClick = cycle,
                                headlineContent = { Text(name) },
                                // The phone filters' own three-state boxes, with the state in words beside.
                                // One height whatever's in it: the word, taller than the box, grew the row as it came.
                                trailingContent = {
                                    androidx.compose.foundation.layout.Row(Modifier.height(24.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        if (state != null) Text(
                                            stringResource(if (state) R.string.tv_filter_include else R.string.tv_filter_exclude),
                                            style = MaterialTheme.typography.labelLarge,
                                            modifier = Modifier.padding(end = 10.dp),
                                        )
                                        androidx.tv.material3.Icon(
                                            androidx.compose.ui.res.painterResource(
                                                when (state) {
                                                    true -> R.drawable.ic_round_check_box_24
                                                    false -> R.drawable.ic_round_disabled_by_default_24
                                                    null -> R.drawable.ic_round_check_box_outline_blank_24
                                                }
                                            ),
                                            contentDescription = null,
                                            modifier = androidx.compose.ui.Modifier.size(22.dp),
                                        )
                                    }
                                },
                                modifier = (if (name == shown.firstOrNull()) Modifier.focusRequester(first) else Modifier)
                                    .pointerClick(onClick = cycle),
                            )
                        }
                    }
                }
            }
        }
    }
    // On the first one, not the tag field: there the TV's keyboard came up before anything was asked of it.
    LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        runCatching { first.requestFocus() }
    }
}
