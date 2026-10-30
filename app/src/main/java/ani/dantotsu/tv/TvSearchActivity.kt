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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import kotlinx.coroutines.withContext
import java.io.Serializable

/**
 * Searching AniList from the couch: one field the TV's keyboard types into, results as covers that
 * open the TV media page. The search runs a moment after typing stops, not on every letter.
 */
class TvSearchActivity : AppCompatActivity(), TvScreen {

    override fun attachBaseContext(newBase: android.content.Context?) {
        super.attachBaseContext(TvMode.attach(this, newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager(this).applyTheme()
        super.onCreate(savedInstanceState)
        initActivity(this)
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

@Composable
private fun TvSearch(onOpen: (Media) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Media>?>(null) }
    var searchedFor by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf<Media?>(null) }
    val field = remember { FocusRequester() }
    val firstResult = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
    LaunchedEffect(query) {
        val text = query.trim()
        if (text.length < MIN_QUERY) {
            results = null
            return@LaunchedEffect
        }
        delay(SEARCH_DELAY_MS)
        val found = withContext(Dispatchers.IO) {
            runCatching {
                Anilist.query.searchAniManga(
                    "ANIME",
                    search = text,
                    perPage = 40,
                    adultOnly = PrefManager.getVal(PrefName.AdultOnly),
                )?.results
            }.getOrNull()
        }
        searchedFor = text
        results = found.orEmpty()
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Backdrop(focused?.banner ?: focused?.cover, Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().padding(top = 36.dp)) {
            TvSearchField(
                query = query,
                onQuery = { query = it },
                hint = stringResource(R.string.tv_search_hint),
                // Searching is already under way; the keyboard's search key takes the user to it —
                // the first result, not whichever happens to sit below the middle of the field.
                onSubmit = {
                    if (runCatching { firstResult.requestFocus() }.isFailure) focusManager.moveFocus(FocusDirection.Down)
                },
                modifier = Modifier
                    .padding(horizontal = 48.dp)
                    .width(560.dp)
                    .focusRequester(field),
            )

            val shown = results
            when {
                shown == null -> Status(stringResource(R.string.tv_search_start))
                shown.isEmpty() -> Status(stringResource(R.string.tv_search_none, searchedFor))
                else -> FocusRestingAt(24.dp) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(112.dp),
                        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 24.dp, bottom = 48.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        items(shown, key = { it.id }) { media ->
                            PosterCard(
                                media = media,
                                onClick = { onOpen(media) },
                                onFocused = { focused = media },
                                modifier = if (media === shown.first()) Modifier.focusRequester(firstResult) else Modifier,
                            )
                        }
                    }
                }
            }
        }
    }
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
