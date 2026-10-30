package ani.dantotsu.tv

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ani.dantotsu.R
import ani.dantotsu.connections.TrackerSessions
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.AnilistAnimeViewModel
import ani.dantotsu.connections.anilist.AnilistHomeViewModel
import ani.dantotsu.initActivity
import ani.dantotsu.media.Media
import ani.dantotsu.themes.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.Serializable

/**
 * The TV interface's home: the focused title shown large over its banner, and rows of the user's
 * anime beneath — what they're watching, what they planned, what's airing, what AniList suggests.
 */
class TvHomeActivity : AppCompatActivity(), TvScreen {

    private val home: AnilistHomeViewModel by viewModels()
    private val anime: AnilistAnimeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // TV mode is dark whatever the system says: a bright screen glares across a dark room.
        delegate.localNightMode = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        ThemeManager(this).applyTheme()
        super.onCreate(savedInstanceState)
        initActivity(this)
        setContent {
            TvTheme {
                TvHome(
                    home = home,
                    anime = anime,
                    onOpen = ::openMedia,
                    onSettings = { startActivity(Intent(this, TvSettingsActivity::class.java)) },
                    onExit = if (TvMode.canExit(this)) ({ TvMode.exit(this) }) else null,
                )
            }
        }
        lifecycleScope.launch(Dispatchers.IO) {
            TrackerSessions.await()
            home.initHomePageCached()
            home.initHomePage()
        }
        lifecycleScope.launch(Dispatchers.IO) { anime.loadTrending(1) }
    }

    private fun openMedia(media: Media) {
        startActivity(Intent(this, TvMediaActivity::class.java).putExtra("media", media as Serializable))
    }
}

@Composable
private fun TvHome(
    home: AnilistHomeViewModel,
    anime: AnilistAnimeViewModel,
    onOpen: (Media) -> Unit,
    onSettings: () -> Unit,
    onExit: (() -> Unit)?,
) {
    val continuing by home.getAnimeContinue().observeAsState()
    val planned by home.getAnimePlanned().observeAsState()
    val recommended by home.getRecommendation().observeAsState()
    val trending by anime.getTrending().observeAsState()

    val rows = listOf(
        stringResource(R.string.continue_watching) to continuing.orEmpty(),
        stringResource(R.string.planned_anime) to planned.orEmpty(),
        stringResource(R.string.tv_trending) to trending.orEmpty(),
        stringResource(R.string.recommended) to recommended.orEmpty().filter { it.anime != null },
    ).filter { it.second.isNotEmpty() }

    var focused by remember { mutableStateOf<Media?>(null) }
    val firstCard = remember { FocusRequester() }
    // The first card takes focus once there is one, so the remote has somewhere to start from.
    var focusPlaced by remember { mutableStateOf(false) }
    LaunchedEffect(rows.isNotEmpty()) {
        if (rows.isNotEmpty() && !focusPlaced) {
            runCatching { firstCard.requestFocus() }
            focusPlaced = true
        }
    }
    val shown = focused ?: rows.firstOrNull()?.second?.firstOrNull()

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Backdrop(
            imageUrl = shown?.banner ?: shown?.cover,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.66f),
        )
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                Button(onClick = onSettings, colors = ButtonDefaults.colors()) {
                    Text(stringResource(R.string.settings))
                }
                // Not on a TV, where there's no other interface to leave for.
                if (onExit != null) {
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = onExit, colors = ButtonDefaults.colors()) {
                        Text(stringResource(R.string.tv_exit))
                    }
                }
            }
            Hero(shown, Modifier.padding(start = 48.dp, top = 4.dp, bottom = 20.dp).height(170.dp))
            if (rows.isEmpty()) {
                Text(
                    if (Anilist.token == null) stringResource(R.string.tv_signed_out) else "",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(48.dp),
                )
            }
            // A focused card rests low enough for its row's title and its own growth to show.
            FocusRestingAt(56.dp) { LazyColumn(
                contentPadding = PaddingValues(bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                rows.forEachIndexed { rowIndex, (title, items) ->
                    item(key = title) {
                        Column {
                            Text(
                                title,
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(start = 48.dp, bottom = 12.dp),
                            )
                            // ...and at the row's margin, so the first card comes back to where it began.
                            FocusRestingAt(48.dp) { LazyRow(
                                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(18.dp),
                            ) {
                                items(items, key = { it.id }) { media ->
                                    val first = rowIndex == 0 && media === items.first()
                                    PosterCard(
                                        media = media,
                                        onClick = { onOpen(media) },
                                        onFocused = { focused = media },
                                        subtitle = progressLabel(media),
                                        modifier = if (first) Modifier.focusRequester(firstCard) else Modifier,
                                    )
                                }
                            } }
                        }
                    }
                }
            } }
        }
    }
}

/** The focused title, large: name, what it is, and the start of its synopsis. */
@Composable
private fun Hero(media: Media?, modifier: Modifier = Modifier) {
    Column(modifier.width(520.dp), verticalArrangement = Arrangement.Bottom) {
        if (media == null) return@Column
        Text(
            media.userPreferredName,
            style = MaterialTheme.typography.displaySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            metaLine(media),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            plainDescription(media),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}
