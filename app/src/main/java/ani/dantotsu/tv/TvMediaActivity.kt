package ani.dantotsu.tv

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import ani.dantotsu.R
import ani.dantotsu.initActivity
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsViewModel
import ani.dantotsu.media.MediaNameAdapter
import ani.dantotsu.media.anime.Episode
import ani.dantotsu.others.getSerialized
import ani.dantotsu.parsers.AnimeSources
import ani.dantotsu.parsers.HAnimeSources
import ani.dantotsu.themes.ThemeManager
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * A title on the TV: its banner and details, one button to start or continue, the source the
 * episodes come from, and the episodes themselves. Playing goes through the same server picker and
 * player as the phone, which read this screen's [MediaDetailsViewModel] just as they read the media
 * page's.
 */
class TvMediaActivity : AppCompatActivity(), TvScreen {

    private val model: MediaDetailsViewModel by viewModels()

    /** The source episodes are being shown from, as an index into [ani.dantotsu.parsers.WatchSources]. */
    private val sourceIndex = mutableIntStateOf(0)
    private var episodesRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // TV mode is dark whatever the system says: a bright screen glares across a dark room.
        delegate.localNightMode = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        ThemeManager(this).applyTheme()
        super.onCreate(savedInstanceState)
        initActivity(this)
        val start: Media = intent.getSerialized("media") ?: return finish()
        model.watchSources = if (start.isAdult) HAnimeSources else AnimeSources

        // Details arrive from AniList; the episode search starts once, on the full entry.
        model.getMedia().observe(this) { media ->
            if (media == null) return@observe
            media.selected = model.loadSelected(media)
            sourceIndex.intValue = media.selected!!.sourceIndex
            if (!episodesRequested) {
                episodesRequested = true
                loadEpisodes(media)
            }
        }
        lifecycleScope.launch(Dispatchers.IO) { model.loadMedia(start) }

        setContent {
            TvTheme {
                // The view model's media starts out null (not unset), so the fallback is ours to make.
                val media = model.getMedia().observeAsState().value ?: start
                val loaded by model.getEpisodes().observeAsState()
                val episodes = loaded?.get(sourceIndex.intValue)
                // The player and its server picker look episodes up on the media itself.
                LaunchedEffect(episodes, media) { media.anime?.episodes = episodes }
                TvMedia(
                    media = media,
                    sourceNames = model.watchSources?.names.orEmpty(),
                    sourceIndex = sourceIndex.intValue,
                    episodes = episodes?.values?.sortedBy { it.number.toFloatOrNull() ?: Float.MAX_VALUE },
                    onPlay = { play(media, it) },
                    onSource = { changeSource(media, it) },
                )
            }
        }
    }

    private fun loadEpisodes(media: Media) {
        lifecycleScope.launch(Dispatchers.IO) { model.loadEpisodes(media, sourceIndex.intValue) }
    }

    private fun changeSource(media: Media, index: Int) {
        if (index == sourceIndex.intValue) return
        sourceIndex.intValue = index
        media.selected = (media.selected ?: model.loadSelected(media)).also { it.sourceIndex = index }
        model.saveSelected(media.id, media.selected!!)
        model.saveSelectedSourceName(media.id, model.watchSources?.names?.getOrNull(index))
        loadEpisodes(media)
    }

    private fun play(media: Media, episode: Episode) {
        model.continueMedia = false
        media.selected?.let { model.saveSelected(media.id, it) }
        model.onEpisodeClick(media, episode.number, supportFragmentManager)
    }
}

@Composable
private fun TvMedia(
    media: Media,
    sourceNames: List<String>,
    sourceIndex: Int,
    episodes: List<Episode>?,
    onPlay: (Episode) -> Unit,
    onSource: (Int) -> Unit,
) {
    var pickingSource by remember { mutableStateOf(false) }
    val sourceName = sourceNames.getOrNull(sourceIndex)
    val watched = media.userProgress ?: 0
    // The next one to watch: the first past the user's progress, else the first there is.
    val next = episodes?.firstOrNull { (it.number.toFloatOrNull() ?: 0f) > watched } ?: episodes?.firstOrNull()
    val playButton = remember { FocusRequester() }
    LaunchedEffect(next != null) { if (next != null) runCatching { playButton.requestFocus() } }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Backdrop(media.banner ?: media.cover, Modifier.fillMaxSize())
        FocusRestingAt(60.dp) {
        LazyColumn(
            contentPadding = PaddingValues(start = 48.dp, top = 36.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                Column(Modifier.width(560.dp)) {
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
                    if (media.genres.isNotEmpty()) Text(
                        media.genres.joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        plainDescription(media),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (next != null) Button(
                        onClick = { onPlay(next) },
                        modifier = Modifier.focusRequester(playButton),
                    ) {
                        Text(
                            if (watched > 0) stringResource(R.string.tv_continue_episode, next.number)
                            else stringResource(R.string.tv_watch_episode, next.number)
                        )
                    }
                    if (sourceNames.isNotEmpty()) OutlinedButton(onClick = { pickingSource = true }) {
                        Text(stringResource(R.string.tv_source, sourceName.orEmpty()))
                    }
                }
            }
            item {
                when {
                    sourceNames.isEmpty() -> Status(stringResource(R.string.tv_no_extensions))
                    episodes == null -> Status(stringResource(R.string.tv_finding_episodes, sourceName.orEmpty()))
                    episodes.isEmpty() -> Status(stringResource(R.string.tv_no_episodes, sourceName.orEmpty()))
                    else -> Column {
                        Text(
                            stringResource(R.string.tv_episodes),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                        val rowState = rememberLazyListState()
                        // Opens at the next episode rather than at episode 1 of a long series.
                        LaunchedEffect(episodes.size) {
                            val at = episodes.indexOf(next).coerceAtLeast(0)
                            if (at > 0) rowState.scrollToItem(at)
                        }
                        // A little inset, so the first card's focus outline isn't cut by the row's edge.
                        FocusRestingAt(8.dp) { LazyRow(
                            state = rowState,
                            contentPadding = PaddingValues(start = 8.dp, end = 48.dp, top = 12.dp, bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            items(episodes, key = { it.number }) { episode ->
                                EpisodeCard(
                                    episode = episode,
                                    fallbackImage = media.banner ?: media.cover,
                                    watched = (episode.number.toFloatOrNull() ?: Float.MAX_VALUE) <= watched,
                                    onClick = { onPlay(episode) },
                                )
                            }
                        } }
                    }
                }
            }
        }
        }
    }

    if (pickingSource) SourcePicker(
        names = sourceNames,
        selected = sourceIndex,
        onPick = { pickingSource = false; onSource(it) },
        onDismiss = { pickingSource = false },
    )
}

@Composable
private fun Status(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** An episode: its thumbnail (or the series' banner), number and title; dimmed once watched. */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun EpisodeCard(episode: Episode, fallbackImage: String?, watched: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(200.dp)) {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            shape = CardDefaults.shape(CARD_SHAPE),
            scale = CardDefaults.scale(focusedScale = FOCUSED_SCALE),
            border = CardDefaults.border(
                focusedBorder = Border(BorderStroke(FOCUS_OUTLINE, MaterialTheme.colorScheme.primary), shape = CARD_SHAPE),
            ),
        ) {
            GlideImage(
                model = episode.thumb?.url ?: fallbackImage,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(if (watched) 0.45f else 1f),
            )
        }
        Text(
            stringResource(R.string.tv_episode_n, episode.number),
            style = MaterialTheme.typography.titleSmall,
            color = if (watched) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 10.dp),
        )
        // A title that only repeats the number ("Episode 3") says nothing the line above doesn't.
        episode.title?.takeIf { MediaNameAdapter.removeEpisodeNumberCompletely(it).isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The installed sources, one focusable row each, the current one first in focus. */
@Composable
private fun SourcePicker(names: List<String>, selected: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val current = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = CARD_SHAPE, modifier = Modifier.width(420.dp)) {
            Column(Modifier.padding(24.dp)) {
                Text(
                    stringResource(R.string.tv_pick_source),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                LazyColumn(Modifier.height(360.dp)) {
                    itemsIndexed(names) { index, name ->
                        ListItem(
                            selected = index == selected,
                            onClick = { onPick(index) },
                            headlineContent = { Text(name) },
                            modifier = if (index == selected) Modifier.focusRequester(current) else Modifier,
                        )
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { current.requestFocus() } }
}
