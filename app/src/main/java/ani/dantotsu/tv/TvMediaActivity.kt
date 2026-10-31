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
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import ani.dantotsu.media.deleteFromList
import ani.dantotsu.media.anime.Episode
import ani.dantotsu.others.getSerialized
import ani.dantotsu.parsers.AnimeSources
import ani.dantotsu.parsers.HAnimeSources
import ani.dantotsu.parsers.ShowResponse
import ani.dantotsu.themes.ThemeManager
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    override fun attachBaseContext(newBase: android.content.Context?) {
        super.attachBaseContext(TvMode.attach(this, newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
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
                    sourceNames = sourcesVersion.intValue.let { model.watchSources?.names.orEmpty() },
                    sourceIndex = sourceIndex.intValue,
                    sourceIcon = ::sourceIcon,
                    isDownloads = ::isDownloads,
                    onAddExtensions = { startActivity(android.content.Intent(this, TvExtensionsActivity::class.java)) },
                    onOpenMedia = { related ->
                        startActivity(android.content.Intent(this, TvMediaActivity::class.java).putExtra("media", related as java.io.Serializable))
                    },
                    onSourceSettings = sourceSettingsId(sourceIndex.intValue)?.let { id ->
                        {
                            startActivity(
                                android.content.Intent(this, TvExtensionSettingsActivity::class.java)
                                    .putExtra(TvExtensionSettingsActivity.EXTRA_SOURCE, id)
                            )
                        }
                    },
                    episodes = episodes?.values?.sortedBy { it.number.toFloatOrNull() ?: Float.MAX_VALUE },
                    onPlay = { play(media, it) },
                    onSource = { changeSource(media, it) },
                    matchedName = matchedName.value,
                    searchSource = ::searchSource,
                    onPickMatch = { pickMatch(media, it) },
                    listVersion = listVersion.intValue,
                    onSaveList = { status, progress -> saveList(media, status, progress) },
                    onRemoveFromList = { removeFromList(media) },
                )
            }
        }
    }

    /** The source entry the episodes come from, as the source names it — what "Wrong title?" fixes. */
    private val matchedName = mutableStateOf<String?>(null)

    private fun loadEpisodes(media: Media) {
        lifecycleScope.launch(Dispatchers.IO) {
            model.loadEpisodes(media, sourceIndex.intValue)
            refreshMatchedName(media)
        }
    }

    private suspend fun refreshMatchedName(media: Media) {
        matchedName.value = runCatching {
            model.watchSources?.get(sourceIndex.intValue)?.loadSavedShowResponse(media.id)?.name
        }.getOrNull()
    }

    private suspend fun searchSource(query: String): List<ShowResponse> = withContext(Dispatchers.IO) {
        model.watchSources?.get(sourceIndex.intValue)?.search(query).orEmpty()
    }

    /** Bumped when the user's list entry changes here, so what shows it is drawn again. */
    private val listVersion = mutableIntStateOf(0)

    /**
     * Saves the entry from the TV's list editor — through the same path as the phone's sheet, so
     * AniList and the trackers mirroring it all get it. Score, notes and dates are kept as they were.
     */
    private fun saveList(media: Media, status: String, progress: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            val score = media.userScore.takeIf { it > 0 }
            val started = if (status == "CURRENT" && media.userStartedAt.isEmpty())
                ani.dantotsu.connections.anilist.api.FuzzyDate().getToday() else media.userStartedAt
            val ok = runCatching {
                ani.dantotsu.media.ListEntrySave.save(
                    media, progress, media.userVolume, score, media.userRepeat, media.notes,
                    status, started, media.userCompletedAt, score,
                )
            }.getOrDefault(false)
            if (ok) {
                media.userStatus = status
                media.userProgress = progress
                media.userStartedAt = started
                ani.dantotsu.Refresh.all()
            }
            listVersion.intValue++
            ani.dantotsu.snackString(getString(if (ok) R.string.list_updated else R.string.list_update_failed))
        }
    }

    private fun removeFromList(media: Media) {
        media.deleteFromList(lifecycleScope, onSuccess = {
            media.userStatus = null
            media.userProgress = null
            media.userListId = null
            ani.dantotsu.Refresh.all()
            listVersion.intValue++
            ani.dantotsu.snackString(getString(R.string.deleted_from_list))
        }, onError = {
            ani.dantotsu.snackString(getString(R.string.list_update_failed))
        }, onNotFound = {
            ani.dantotsu.snackString(getString(R.string.list_update_failed))
        })
    }

    private fun isDownloads(index: Int) =
        runCatching { model.watchSources?.get(index) }.getOrNull() is ani.dantotsu.parsers.OfflineAnimeParser

    /** Bumped on coming back to the page: extensions added from the picker's last row show up in it. */
    private val sourcesVersion = androidx.compose.runtime.mutableIntStateOf(0)

    override fun onRestart() {
        super.onRestart()
        sourcesVersion.intValue++
    }

    private fun extensionOf(index: Int) =
        (runCatching { model.watchSources?.get(index) }.getOrNull() as? ani.dantotsu.parsers.DynamicAnimeParser)?.extension

    /** A source's extension icon, for its button and its row in the picker. */
    private fun sourceIcon(index: Int): androidx.compose.ui.graphics.ImageBitmap? =
        runCatching { extensionOf(index)?.icon?.toBitmap(64, 64)?.asImageBitmap() }.getOrNull()

    /**
     * The source whose settings the media page's settings button opens: the one in use (an
     * extension carries one a language), or else its first with settings; none when it has none.
     */
    private fun sourceSettingsId(index: Int): Long? {
        val parser = runCatching { model.watchSources?.get(index) }.getOrNull() as? ani.dantotsu.parsers.DynamicAnimeParser ?: return null
        val sources = parser.extension.sources
        val active = sources.getOrNull(parser.sourceLanguage) as? eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
        return (active ?: sources.filterIsInstance<eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource>().firstOrNull())?.id
    }

    /** The user's pick from "Wrong title?": remembered for this media, and its episodes loaded. */
    private fun pickMatch(media: Media, response: ShowResponse) {
        matchedName.value = response.name
        lifecycleScope.launch(Dispatchers.IO) {
            model.overrideEpisodes(sourceIndex.intValue, response, media.id)
        }
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
    sourceIcon: (Int) -> androidx.compose.ui.graphics.ImageBitmap?,
    isDownloads: (Int) -> Boolean,
    onAddExtensions: () -> Unit,
    onSourceSettings: (() -> Unit)?,
    onOpenMedia: (Media) -> Unit,
    episodes: List<Episode>?,
    onPlay: (Episode) -> Unit,
    onSource: (Int) -> Unit,
    matchedName: String?,
    searchSource: suspend (String) -> List<ShowResponse>,
    onPickMatch: (ShowResponse) -> Unit,
    listVersion: Int,
    onSaveList: (String, Int) -> Unit,
    onRemoveFromList: () -> Unit,
) {
    var editingList by remember { mutableStateOf(false) }
    var pickingSource by remember { mutableStateOf(false) }
    var fixingTitle by remember { mutableStateOf(false) }
    val sourceName = sourceNames.getOrNull(sourceIndex)
    val watched = media.userProgress ?: 0
    // The next one to watch: the first past the user's progress, else the first there is.
    val next = episodes?.firstOrNull { (it.number.toFloatOrNull() ?: 0f) > watched } ?: episodes?.firstOrNull()
    val playButton = remember { FocusRequester() }
    LaunchedEffect(next != null) { if (next != null) runCatching { playButton.requestFocus() } }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Backdrop(media.banner ?: media.cover, Modifier.fillMaxSize())
        // The title and what's under it can't take focus, so focus coming back up into the top section
        // (the buttons beneath them) takes the page all the way to its top: resting the buttons at the
        // usual line left the title above them cut off.
        val pageState = rememberLazyListState()
        val restPx = with(androidx.compose.ui.platform.LocalDensity.current) { 60.dp.toPx() }
        val pageSpec = remember(restPx) {
            @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
            object : androidx.compose.foundation.gestures.BringIntoViewSpec {
                override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                    // In the top sections (the title, then the buttons, items 0 and 1): to the very top.
                    // Item offsets count from the content's start, after the top padding; [offset]
                    // from the list's own edge.
                    val shift = -pageState.layoutInfo.viewportStartOffset
                    val within = pageState.layoutInfo.visibleItemsInfo.firstOrNull {
                        it.index <= 1 && offset >= it.offset + shift && offset + size <= it.offset + shift + it.size
                    }
                    if (within == null) return offset - restPx
                    val first = pageState.layoutInfo.visibleItemsInfo.first()
                    // From the first item showing, back past what's scrolled of it — and, if that's
                    // the buttons, past the title above them too (more than enough; the list stops at its top).
                    return -(pageState.firstVisibleItemScrollOffset + if (first.index > 0) containerSize else 0f)
                }
            }
        }
        @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides pageSpec) {
        LazyColumn(
            state = pageState,
            contentPadding = PaddingValues(start = 48.dp, top = 36.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                Column(Modifier.width(560.dp)) {
                    FittedTitle(media.userPreferredName)
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
            item { Column {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (next != null) Button(
                        onClick = { onPlay(next) },
                        modifier = Modifier.focusRequester(playButton).pointerClick { onPlay(next) },
                    ) {
                        Text(
                            if (watched > 0) stringResource(R.string.tv_continue_episode, next.number)
                            else stringResource(R.string.tv_watch_episode, next.number)
                        )
                    }
                    // Where the title sits on the list, and the way to change it. Read through
                    // listVersion so a save here redraws it.
                    val listLabel = listVersion.let { listStatusLabel(media) } ?: stringResource(R.string.add_list)
                    OutlinedButton(
                        onClick = { editingList = true },
                        modifier = Modifier.pointerClick { editingList = true },
                    ) { Text(listLabel) }
                    // The source in use, by its icon and name, and its settings beside it.
                    if (sourceNames.isNotEmpty()) OutlinedButton(
                        onClick = { pickingSource = true },
                        modifier = Modifier.pointerClick { pickingSource = true },
                    ) {
                        SourceIcon(sourceIndex, sourceNames, sourceIcon, isDownloads, Modifier.padding(end = 8.dp).size(20.dp))
                        Text(stringResource(R.string.tv_source, sourceName.orEmpty()))
                    }
                    if (onSourceSettings != null) androidx.tv.material3.OutlinedIconButton(
                        onClick = onSourceSettings,
                        modifier = Modifier.pointerClick(onClick = onSourceSettings),
                    ) {
                        androidx.tv.material3.Icon(
                            androidx.compose.ui.res.painterResource(R.drawable.ic_round_settings_24),
                            contentDescription = stringResource(R.string.tv_source_settings, sourceName.orEmpty()),
                        )
                    }
                    if (sourceNames.isNotEmpty()) OutlinedButton(
                        onClick = { fixingTitle = true },
                        modifier = Modifier.pointerClick { fixingTitle = true },
                    ) {
                        Text(stringResource(R.string.tv_wrong_title))
                    }
                }
                if (matchedName != null && sourceName != null) Text(
                    stringResource(R.string.tv_matched_as, sourceName, matchedName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            } }
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
            tvMediaInfo(media, onOpenMedia)
        }
        }
    }

    if (editingList) TvListEditor(
        media = media,
        onSave = { status, progress -> editingList = false; onSaveList(status, progress) },
        onRemove = { editingList = false; onRemoveFromList() },
        onDismiss = { editingList = false },
    )

    if (fixingTitle) WrongTitleDialog(
        initialQuery = media.mainName(),
        sourceName = sourceName.orEmpty(),
        search = searchSource,
        onPick = { fixingTitle = false; onPickMatch(it) },
        onDismiss = { fixingTitle = false },
    )

    if (pickingSource) SourcePicker(
        names = sourceNames,
        icon = sourceIcon,
        isDownloads = isDownloads,
        onAddExtensions = { pickingSource = false; onAddExtensions() },
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
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).pointerClick(onClick = onClick),
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
private fun SourcePicker(
    names: List<String>,
    icon: (Int) -> androidx.compose.ui.graphics.ImageBitmap?,
    isDownloads: (Int) -> Boolean,
    onAddExtensions: () -> Unit,
    selected: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val current = remember { FocusRequester() }
    TvDialog(onDismissRequest = onDismiss) {
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
                            leadingContent = { SourceIcon(index, names, icon, isDownloads, Modifier.size(28.dp)) },
                            modifier = (if (index == selected) Modifier.focusRequester(current) else Modifier)
                                .pointerClick { onPick(index) },
                        )
                    }
                    item {
                        ListItem(
                            selected = false,
                            onClick = onAddExtensions,
                            headlineContent = { Text(stringResource(R.string.tv_add_extensions)) },
                            leadingContent = {
                                androidx.tv.material3.Icon(
                                    androidx.compose.ui.res.painterResource(R.drawable.ic_round_add_24),
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                )
                            },
                            modifier = Modifier.pointerClick(onClick = onAddExtensions),
                        )
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { current.requestFocus() } }
}

/** A source's icon: its extension's, or the download icon for the downloaded episodes. */
@Composable
private fun SourceIcon(
    index: Int,
    names: List<String>,
    icon: (Int) -> androidx.compose.ui.graphics.ImageBitmap?,
    isDownloads: (Int) -> Boolean,
    modifier: Modifier,
) {
    val bitmap = remember(index, names) { icon(index) }
    val downloads = remember(index, names) { isDownloads(index) }
    when {
        bitmap != null -> androidx.compose.foundation.Image(bitmap, contentDescription = null, modifier = modifier)
        downloads -> androidx.tv.material3.Icon(
            androidx.compose.ui.res.painterResource(R.drawable.ic_download_24),
            contentDescription = null,
            modifier = modifier,
        )
    }
}
