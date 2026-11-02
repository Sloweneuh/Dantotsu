package ani.dantotsu.tv

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.initActivity
import ani.dantotsu.media.Media
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.themes.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.withContext
import java.io.Serializable

/**
 * Searching AniList from the couch: one field the TV's keyboard types into, and filters beside it
 * (sort, format, status, season, year, the user's list, genres and tags), results as covers that
 * open the TV media page. The search runs a moment after typing stops, not on every letter; filters
 * alone browse, with no title typed. More results load as focus nears the end of what's there.
 */
class TvSearchActivity : AppCompatActivity(), TvScreen {

    override fun attachBaseContext(newBase: android.content.Context?) {
        super.attachBaseContext(TvMode.attach(this, newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager(this).applyTheme()
        super.onCreate(savedInstanceState)
        initActivity(this)
        // The genre and tag filters list them; the home loads them too, but may not have yet.
        if (Anilist.genres == null || Anilist.tags == null) lifecycleScope.launch(Dispatchers.IO) {
            runCatching { Anilist.query.getGenresAndTags() }
        }
        setContent {
            TvTheme {
                TvSearch(onOpen = { media ->
                    startActivity(Intent(this, TvMediaActivity::class.java).putExtra("media", media as Serializable))
                })
            }
        }
    }
}

private const val SEARCH_DELAY_MS = 450L

/** AniList finds nothing for a single letter; searching starts from two. */
private const val MIN_QUERY = 2

/** Focus this close to the end of what's loaded fetches the next page. */
private const val LOAD_AHEAD = 12

/** A page of results for [text] (blank: none) under [filters]. */
private suspend fun searchPage(text: String, filters: TvSearchFilters, page: Int) = withContext(Dispatchers.IO) {
    runCatching {
        Anilist.query.searchAniManga(
            "ANIME",
            page = page,
            perPage = 40,
            search = text.ifBlank { null },
            // AniList's best match for a title; with none, the most popular first.
            sort = filters.sort ?: if (text.isBlank()) "POPULARITY_DESC" else null,
            format = filters.format,
            status = filters.status,
            season = filters.season,
            seasonYear = filters.year,
            onList = filters.onList,
            genres = filters.genres.filterValues { it }.keys.toMutableList(),
            excludedGenres = filters.genres.filterValues { !it }.keys.toMutableList(),
            tags = filters.tags.filterValues { it }.keys.toMutableList(),
            excludedTags = filters.tags.filterValues { !it }.keys.toMutableList(),
            adultOnly = PrefManager.getVal(PrefName.AdultOnly),
        )
    }.getOrNull()
}

@Composable
private fun TvSearch(onOpen: (Media) -> Unit) {
    var query by remember { mutableStateOf("") }
    var filters by remember { mutableStateOf(TvSearchFilters()) }
    var filtering by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Media>?>(null) }
    var searchedFor by remember { mutableStateOf("") }
    var page by remember { mutableStateOf(1) }
    var hasNextPage by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf<Media?>(null) }
    val field = remember { FocusRequester() }
    val firstResult = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
    LaunchedEffect(query, filters) {
        val text = query.trim()
        if (text.length < MIN_QUERY && filters.isEmpty) {
            results = null
            return@LaunchedEffect
        }
        delay(SEARCH_DELAY_MS)
        val found = searchPage(text, filters, 1)
        searchedFor = text
        page = 1
        hasNextPage = found?.hasNextPage == true
        results = found?.results.orEmpty()
    }
    val loadMore = {
        if (hasNextPage && !loadingMore) {
            loadingMore = true
            val text = searchedFor
            val asked = filters
            scope.launch {
                val found = searchPage(text, asked, page + 1)
                // Only if the search is still the one this page is of.
                if (found != null && asked == filters && text == searchedFor) {
                    page = found.page
                    hasNextPage = found.hasNextPage
                    results = results.orEmpty() + found.results.filter { new -> results.orEmpty().none { it.id == new.id } }
                }
                loadingMore = false
            }
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Backdrop(focused?.banner ?: focused?.cover, Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().padding(top = 36.dp)) {
            Row(Modifier.padding(horizontal = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                TvSearchField(
                    query = query,
                    onQuery = { query = it },
                    hint = stringResource(R.string.tv_search_hint),
                    // Searching is already under way; the keyboard's search key takes the user to it —
                    // the first result, not whichever happens to sit below the middle of the field.
                    onSubmit = {
                        if (runCatching { firstResult.requestFocus() }.isFailure) focusManager.moveFocus(FocusDirection.Down)
                    },
                    // The size of the Filters button beside it.
                    compact = true,
                    modifier = Modifier.width(560.dp).focusRequester(field),
                )
                androidx.compose.foundation.layout.Spacer(Modifier.width(16.dp))
                val open = { filtering = true }
                OutlinedButton(onClick = open, modifier = Modifier.pointerClick(onClick = open)) {
                    androidx.tv.material3.Icon(
                        androidx.compose.ui.res.painterResource(R.drawable.ic_round_filter_alt_24),
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp).size(20.dp),
                    )
                    Text(
                        if (filters.isEmpty) stringResource(R.string.tv_filters)
                        else stringResource(R.string.tv_filters) + " · " + filters.count
                    )
                }
            }
            // What the results are narrowed to, at a glance.
            val chips = filterChips(filters)
            if (chips.isNotEmpty()) androidx.compose.foundation.lazy.LazyRow(
                contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(chips.size) { i ->
                    Text(
                        chips[i],
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                }
            }

            val shown = results
            when {
                shown == null -> Status(stringResource(R.string.tv_search_start))
                shown.isEmpty() -> Status(
                    if (searchedFor.isBlank()) stringResource(R.string.tv_search_none_filtered)
                    else stringResource(R.string.tv_search_none, searchedFor)
                )
                else -> FocusRestingAt(24.dp) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(112.dp),
                        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 24.dp, bottom = 48.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        itemsIndexed(shown, key = { _, media -> media.id }) { index, media ->
                            PosterCard(
                                media = media,
                                onClick = { onOpen(media) },
                                onFocused = {
                                    focused = media
                                    if (index >= shown.size - LOAD_AHEAD) loadMore()
                                },
                                modifier = if (media === shown.first()) Modifier.focusRequester(firstResult) else Modifier,
                            )
                        }
                    }
                }
            }
        }
    }

    if (filtering) SearchFiltersDialog(filters, onChange = { filters = it }, onDismiss = { filtering = false })
}

@Composable
private fun Status(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 48.dp, top = 28.dp),
    )
}
