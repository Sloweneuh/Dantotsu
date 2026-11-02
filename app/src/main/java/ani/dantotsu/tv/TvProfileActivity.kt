package ani.dantotsu.tv

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.RadioButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.api.Query
import ani.dantotsu.initActivity
import ani.dantotsu.media.Media
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.themes.ThemeManager
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.io.Serializable
import java.util.Locale

/**
 * The signed-in user on the TV: their banner, avatar and name, how much they've watched, then their
 * whole anime list — a tab a list (Watching, Completed, their own lists, All, their favourites),
 * its titles as a grid of covers that open the TV media page, in the order they pick.
 */
class TvProfileActivity : AppCompatActivity(), TvScreen {

    private val profile = mutableStateOf<Query.UserProfile?>(null)
    private val lists = mutableStateOf<Map<String, List<Media>>?>(null)

    override fun attachBaseContext(newBase: android.content.Context?) {
        super.attachBaseContext(TvMode.attach(this, newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager(this).applyTheme()
        super.onCreate(savedInstanceState)
        initActivity(this)
        val userId = Anilist.userid ?: return finish()
        setContent {
            TvTheme {
                TvProfile(
                    profile = profile.value,
                    lists = lists.value,
                    onOpen = { media ->
                        startActivity(Intent(this, TvMediaActivity::class.java).putExtra("media", media as Serializable))
                    },
                )
            }
        }
        load(userId, withProfile = true)
    }

    /** Back from a title, whose entry may have moved list or been favourited: both afresh. */
    override fun onRestart() {
        super.onRestart()
        Anilist.userid?.let { load(it, withProfile = true) }
    }

    private fun load(userId: Int, withProfile: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            val user = if (withProfile) async {
                runCatching { Anilist.query.getUserProfile(userId)?.data?.user }.getOrNull()
            } else null
            val loaded = runCatching { Anilist.query.getMediaLists(anime = true, userId = userId) }.getOrNull()
            lists.value = loaded.orEmpty()
            user?.await()?.let { profile.value = it }
        }
    }
}

/** How a list can be ordered: [PrefName.AnimeListSortOrder]'s values, as the phone's list sorts by them. */
private val SORTS = listOf(
    "updatedAt_desc" to R.string.tv_sort_updated,
    "title_asc" to R.string.tv_sort_title,
    "score_desc" to R.string.tv_sort_score,
    "release_desc" to R.string.tv_sort_release,
)

private fun sorted(list: List<Media>, sort: String?): List<Media> = when (sort) {
    "score", "score_desc" -> list.sortedWith(compareByDescending<Media> { it.userScore }.thenByDescending { it.meanScore })
    "score_asc" -> list.sortedWith(compareBy<Media> { it.userScore }.thenBy { it.meanScore })
    "title", "title_asc" -> list.sortedBy { it.userPreferredName.lowercase(Locale.ROOT) }
    "title_desc" -> list.sortedByDescending { it.userPreferredName.lowercase(Locale.ROOT) }
    "updatedAt", "updatedAt_desc" -> list.sortedByDescending { it.userUpdatedAt }
    "updatedAt_asc" -> list.sortedBy { it.userUpdatedAt }
    "release", "release_desc" -> list.sortedWith(compareByDescending { it.startDate })
    "release_asc" -> list.sortedWith(compareBy { it.startDate })
    else -> list
}

@OptIn(ExperimentalGlideComposeApi::class, androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
private fun TvProfile(profile: Query.UserProfile?, lists: Map<String, List<Media>>?, onOpen: (Media) -> Unit) {
    // Favourites come as ids and covers only: those on the list are full entries to open, and
    // they're a tab of their own beside the lists.
    val all = lists?.get("All").orEmpty()
    val favourites = remember(profile, all) {
        profile?.favourites?.anime?.nodes.orEmpty().mapNotNull { fav -> all.firstOrNull { it.id == fav.id } }
    }
    val tabs = remember(lists, favourites) {
        lists.orEmpty().let { if (favourites.isEmpty()) it else it + (FAVOURITES to favourites) }
    }
    // Watching first, as the phone's list opens on; else AniList's first list.
    var tab by remember { mutableStateOf<String?>(null) }
    val names = tabs.keys.toList()
    val current = tab?.takeIf { it in names } ?: names.firstOrNull { it == "Watching" } ?: names.firstOrNull()
    var sort by remember { mutableStateOf(PrefManager.getVal<String?>(PrefName.AnimeListSortOrder)) }
    var sorting by remember { mutableStateOf(false) }
    val shown = remember(tabs, current, sort) { sorted(tabs[current].orEmpty(), sort) }
    var focused by remember { mutableStateOf<Media?>(null) }
    val currentTab = remember { FocusRequester() }
    var focusPlaced by remember { mutableStateOf(false) }
    LaunchedEffect(current != null) {
        if (current != null && !focusPlaced) {
            androidx.compose.runtime.withFrameNanos { }
            focusPlaced = runCatching { currentTab.requestFocus() }.isSuccess
        }
    }
    val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    // A new list starts at its top.
    LaunchedEffect(current, sort) { grid.scrollToItem(0) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Backdrop(focused?.banner ?: focused?.cover ?: profile?.bannerImage ?: Anilist.bg, Modifier.fillMaxSize())
        // The user and the tabs stay put; only the titles scroll under them, so the profile is
        // never scrolled away out of reach.
        Column(Modifier.fillMaxSize().padding(top = 32.dp)) {
            Box(Modifier.padding(horizontal = 48.dp)) { Header(profile) }
            when {
                lists == null -> Note(stringResource(R.string.loading))
                names.isEmpty() -> Note(stringResource(R.string.tv_list_empty))
                else -> LazyRow(
                    contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 20.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(names, key = { it }) { name ->
                        val pick = { tab = name; focused = null }
                        androidx.tv.material3.FilterChip(
                            selected = name == current,
                            onClick = pick,
                            modifier = (if (name == current) Modifier.focusRequester(currentTab) else Modifier)
                                .pointerClick(onClick = pick),
                        ) { Text("${listName(name)}  ${tabs[name]?.size ?: 0}") }
                    }
                    item(key = "sort") {
                        androidx.tv.material3.FilterChip(
                            selected = false,
                            onClick = { sorting = true },
                            modifier = Modifier.pointerClick { sorting = true },
                        ) {
                            Text(
                                stringResource(R.string.sort) + " · " +
                                    stringResource(SORTS.firstOrNull { it.first == sort }?.second ?: R.string.tv_sort_default)
                            )
                        }
                    }
                }
            }
            FocusRestingAt(24.dp) {
                LazyVerticalGrid(
                    state = grid,
                    columns = GridCells.Adaptive(112.dp),
                    contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 20.dp, bottom = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    modifier = Modifier.fadingEdges(vertical = 16.dp),
                ) {
                    itemsIndexed(shown, key = { _, media -> "l" + media.id }) { _, media ->
                        PosterCard(
                            media = media,
                            onClick = { onOpen(media) },
                            onFocused = { focused = media },
                            subtitle = progressLabel(media),
                        )
                    }
                }
            }
        }
    }

    if (sorting) SortDialog(sort, onPick = {
        sorting = false
        sort = it
        PrefManager.setVal(PrefName.AnimeListSortOrder, it)
    }, onDismiss = { sorting = false })
}

/** The favourites' tab, keyed apart from any list the user might have named "Favourites". */
private const val FAVOURITES = "\u0000favourites"

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 48.dp, top = 24.dp),
    )
}

/** AniList's own list names in the app's words; the user's custom lists as they named them. */
@Composable
private fun listName(name: String): String = when (name) {
    FAVOURITES -> stringResource(R.string.fav_anime)
    "Watching" -> stringResource(R.string.tv_list_current)
    "Completed" -> stringResource(R.string.tv_list_completed)
    "Paused" -> stringResource(R.string.tv_list_paused)
    "Dropped" -> stringResource(R.string.tv_list_dropped)
    "Planning" -> stringResource(R.string.tv_list_planning)
    "Rewatching" -> stringResource(R.string.tv_list_repeating)
    "All" -> stringResource(R.string.tv_list_all)
    else -> name
}

/** The avatar, name and what's been watched: titles, episodes, days, and the mean score given. */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun Header(profile: Query.UserProfile?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        GlideImage(
            model = profile?.avatar?.large ?: Anilist.avatar,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(88.dp).clip(CircleShape),
        )
        Column(Modifier.padding(start = 28.dp)) {
            Text(profile?.name ?: Anilist.username.orEmpty(), style = MaterialTheme.typography.headlineLarge)
            val stats = profile?.statistics?.anime
            if (stats != null) Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat(stats.count.toString(), stringResource(R.string.tv_stat_anime))
                Stat(stats.episodesWatched.toString(), stringResource(R.string.episodes_watched))
                Stat(String.format(Locale.getDefault(), "%.1f", stats.minutesWatched / 1440f), stringResource(R.string.days_watched))
                if (stats.meanScore > 0) Stat(String.format(Locale.getDefault(), "%.1f", stats.meanScore), stringResource(R.string.mean_score))
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(
        Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f), RoundedCornerShape(14.dp))
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SortDialog(current: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val selected = remember { FocusRequester() }
    TvDialog(onDismissRequest = onDismiss) {
        Surface(shape = CARD_SHAPE, modifier = Modifier.width(420.dp)) {
            Column(Modifier.padding(24.dp)) {
                Text(stringResource(R.string.sort_by), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 10.dp))
                LazyColumn {
                    items(SORTS, key = { it.first }) { (key, label) ->
                        val isCurrent = key == current || (current == null && key == SORTS.first().first)
                        ListItem(
                            selected = isCurrent,
                            onClick = { onPick(key) },
                            headlineContent = { Text(stringResource(label)) },
                            trailingContent = { RadioButton(selected = isCurrent, onClick = null) },
                            modifier = (if (isCurrent) Modifier.focusRequester(selected) else Modifier).pointerClick { onPick(key) },
                        )
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
