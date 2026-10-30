package ani.dantotsu.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import ani.dantotsu.R
import ani.dantotsu.parsers.ShowResponse
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import kotlinx.coroutines.delay

private const val SEARCH_DELAY_MS = 450L

/**
 * "Wrong title?" for the TV: search the current source for the right entry — starting from the
 * title itself, as the phone does — and pick it; the episodes then come from that entry.
 */
@Composable
fun WrongTitleDialog(
    initialQuery: String,
    sourceName: String,
    search: suspend (String) -> List<ShowResponse>,
    onPick: (ShowResponse) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<List<ShowResponse>?>(null) }
    var searchedFor by remember { mutableStateOf("") }
    val field = remember { FocusRequester() }
    val firstResult = remember { FocusRequester() }

    var first by remember { mutableStateOf(true) }
    LaunchedEffect(query) {
        val text = query.trim()
        if (text.isEmpty()) return@LaunchedEffect
        // The first search, of the title itself, needn't wait for typing to stop.
        if (!first) delay(SEARCH_DELAY_MS)
        first = false
        results = null
        results = runCatching { search(text) }.getOrNull().orEmpty()
        searchedFor = text
    }
    // Straight to the results once there are some, so picking needs no typing at all.
    var focusedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(results) {
        if (!focusedOnce && !results.isNullOrEmpty()) {
            focusedOnce = runCatching { firstResult.requestFocus() }.isSuccess
        }
        if (!focusedOnce && results?.isEmpty() == true) runCatching { field.requestFocus() }
    }

    TvDialog(onDismissRequest = onDismiss, fullWidth = true) {
        Surface(shape = CARD_SHAPE, modifier = Modifier.fillMaxSize().padding(36.dp)) {
            Column(Modifier.padding(28.dp)) {
                Text(
                    stringResource(R.string.tv_wrong_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 14.dp),
                )
                TvSearchField(
                    query = query,
                    onQuery = { query = it },
                    hint = stringResource(R.string.tv_wrong_title_hint, sourceName),
                    onSubmit = { runCatching { firstResult.requestFocus() } },
                    modifier = Modifier.fillMaxWidth(0.6f).focusRequester(field),
                )
                val shown = results
                when {
                    shown == null -> Status(stringResource(R.string.tv_wrong_title_searching, sourceName))
                    shown.isEmpty() -> Status(stringResource(R.string.tv_wrong_title_none, sourceName, searchedFor))
                    else -> FocusRestingAt(16.dp) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(112.dp),
                            contentPadding = PaddingValues(top = 20.dp, bottom = 12.dp, start = 4.dp, end = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            items(shown) { response ->
                                ResultCard(
                                    response = response,
                                    onClick = { onPick(response) },
                                    modifier = if (response === shown.first()) Modifier.focusRequester(firstResult) else Modifier,
                                )
                            }
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
        modifier = Modifier.padding(top = 20.dp),
    )
}

/** One of the source's entries: its cover and name. */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun ResultCard(response: ShowResponse, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).pointerClick(onClick = onClick),
            shape = CardDefaults.shape(CARD_SHAPE),
            scale = CardDefaults.scale(focusedScale = FOCUSED_SCALE),
            border = CardDefaults.border(
                focusedBorder = Border(BorderStroke(FOCUS_OUTLINE, MaterialTheme.colorScheme.primary), shape = CARD_SHAPE),
            ),
        ) {
            GlideImage(
                model = response.coverUrl.url,
                contentDescription = response.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            response.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
