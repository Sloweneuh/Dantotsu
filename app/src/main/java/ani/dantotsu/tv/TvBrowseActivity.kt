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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.initActivity
import ani.dantotsu.media.Media
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.themes.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Serializable

/**
 * One genre's anime, most popular first, as a grid of covers that open the TV media page. More are
 * fetched as focus nears the end, so the grid goes on as far as the user scrolls.
 */
class TvBrowseActivity : AppCompatActivity(), TvScreen {

    private val genre by lazy { intent.getStringExtra(EXTRA_GENRE).orEmpty() }
    private var results by mutableStateOf<List<Media>?>(null)
    private var page = 0
    private var hasNextPage = true
    private var loading = false

    override fun attachBaseContext(newBase: android.content.Context?) {
        super.attachBaseContext(newBase?.let { ani.dantotsu.util.LanguageHelper.applyLanguageToContext(it) })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // TV mode is dark whatever the system says: a bright screen glares across a dark room.
        delegate.localNightMode = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        ThemeManager(this).applyTheme()
        super.onCreate(savedInstanceState)
        initActivity(this)
        setContent {
            TvTheme {
                TvBrowse(
                    title = genre,
                    results = results,
                    onNearEnd = ::loadMore,
                    onOpen = { media ->
                        startActivity(Intent(this, TvMediaActivity::class.java).putExtra("media", media as Serializable))
                    },
                )
            }
        }
        loadMore()
    }

    private fun loadMore() {
        if (loading || !hasNextPage) return
        loading = true
        lifecycleScope.launch(Dispatchers.IO) {
            val found = runCatching {
                Anilist.query.searchAniManga(
                    "ANIME",
                    page = page + 1,
                    perPage = PAGE_SIZE,
                    sort = Anilist.sortBy[1],
                    genres = mutableListOf(genre),
                    adultOnly = PrefManager.getVal(PrefName.AdultOnly),
                )
            }.getOrNull()
            withContext(Dispatchers.Main) {
                if (found != null) {
                    page = found.page
                    hasNextPage = found.hasNextPage
                    results = results.orEmpty() + found.results.filter { new -> results.orEmpty().none { it.id == new.id } }
                } else if (results == null) {
                    results = emptyList()
                    hasNextPage = false
                }
                loading = false
            }
        }
    }

    companion object {
        const val EXTRA_GENRE = "tv_genre"
        private const val PAGE_SIZE = 40
    }
}

/** Focus this close to the end of what's loaded fetches the next page. */
private const val LOAD_AHEAD = 12

@Composable
private fun TvBrowse(title: String, results: List<Media>?, onNearEnd: () -> Unit, onOpen: (Media) -> Unit) {
    var focused by remember { mutableStateOf<Media?>(null) }
    val firstResult = remember { FocusRequester() }
    var focusPlaced by remember { mutableStateOf(false) }
    LaunchedEffect(results.isNullOrEmpty()) {
        if (!results.isNullOrEmpty() && !focusPlaced) {
            focusPlaced = runCatching { firstResult.requestFocus() }.isSuccess
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Backdrop(focused?.banner ?: focused?.cover, Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().padding(top = 36.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(horizontal = 48.dp),
            )
            when {
                results == null -> Unit
                results.isEmpty() -> Text(
                    stringResource(R.string.tv_genre_none, title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 48.dp, top = 28.dp),
                )
                else -> FocusRestingAt(24.dp) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(112.dp),
                        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 24.dp, bottom = 48.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        itemsIndexed(results, key = { _, media -> media.id }) { index, media ->
                            PosterCard(
                                media = media,
                                onClick = { onOpen(media) },
                                onFocused = {
                                    focused = media
                                    if (index >= results.size - LOAD_AHEAD) onNearEnd()
                                },
                                subtitle = progressLabel(media),
                                modifier = if (index == 0) Modifier.focusRequester(firstResult) else Modifier,
                            )
                        }
                    }
                }
            }
        }
    }
}
