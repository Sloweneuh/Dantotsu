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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import ani.dantotsu.R
import ani.dantotsu.download.DownloadItem
import ani.dantotsu.download.DownloadState
import ani.dantotsu.download.DownloadTracker
import ani.dantotsu.download.DownloadedType
import ani.dantotsu.download.DownloadsManager
import ani.dantotsu.download.OfflineMediaLoader
import ani.dantotsu.download.manage.DownloadChild
import ani.dantotsu.download.manage.DownloadManageLoader
import ani.dantotsu.download.manage.DownloadMediaGroup
import ani.dantotsu.formatBytes
import ani.dantotsu.initActivity
import ani.dantotsu.media.MediaType
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.Logger
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * TV mode's downloads, opened from settings: what's downloading now, then each downloaded title
 * with its episodes and the room they take — to open, or to delete a title or an episode of it.
 * The phone's own screen, through the same loader.
 */
class TvDownloadsActivity : AppCompatActivity(), TvScreen {

    private val downloads by lazy { Injekt.get<DownloadsManager>() }
    private val groups = mutableStateOf<List<DownloadMediaGroup>?>(null)
    private var loadToken = 0

    override fun attachBaseContext(newBase: android.content.Context?) {
        super.attachBaseContext(TvMode.attach(this, newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager(this).applyTheme()
        super.onCreate(savedInstanceState)
        initActivity(this)
        setContent {
            // Its own list shows the downloads under way: no pill to repeat them.
            TvTheme(downloads = false) {
                val tracked by DownloadTracker.items.collectAsState()
                val active = tracked.filter { it.type == MediaType.ANIME }
                // One leaving the queue finished (or stopped): the list below may have grown.
                LaunchedEffect(active.map { it.id }) { reload() }
                TvDownloads(
                    active = active,
                    groups = groups.value,
                    onStop = { DownloadTracker.cancel(this, it) },
                    onOpen = ::open,
                    onDeleteTitle = { group ->
                        downloads.removeMedia(group.titleName, group.type)
                        reload()
                    },
                    onDeleteEpisode = { child ->
                        downloads.removeDownload(
                            DownloadedType(child.titleName, child.chapterName, child.type, scanlator = child.scanlator)
                        ) { reload() }
                    },
                )
            }
        }
    }

    override fun onRestart() {
        super.onRestart()
        reload()
    }

    /** The titles first, then their sizes: measuring them walks every folder, and the list needn't wait for it. */
    private fun reload() {
        val token = ++loadToken
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { DownloadManageLoader.load(this@TvDownloadsActivity) }
                    .onFailure { Logger.log("TvDownloads: couldn't load downloads: ${it.message}") }
                    .getOrDefault(emptyList())
                    .filter { it.type == MediaType.ANIME }
            }
            if (token != loadToken) return@launch
            // Sizes kept from the last pass until the new ones are in, rather than blanked meanwhile.
            val before = groups.value.orEmpty().associateBy { it.titleName }
            groups.value = loaded.map { it.copy(sizeBytes = before[it.titleName]?.sizeBytes) }
            val sized = withContext(Dispatchers.IO) {
                runCatching { DownloadManageLoader.loadSizes(this@TvDownloadsActivity, loaded).first }.getOrNull()
            }
            if (token == loadToken && sized != null) groups.value = sized
        }
    }

    /** A title's page from its own saved details (no network needed), on its downloaded episodes. */
    private fun open(group: DownloadMediaGroup) {
        lifecycleScope.launch {
            val media = withContext(Dispatchers.IO) {
                OfflineMediaLoader.loadMedia(this@TvDownloadsActivity, group.type, group.titleName)
            } ?: run { ani.dantotsu.snackString(getString(R.string.error)); return@launch }
            startActivity(
                Intent(this@TvDownloadsActivity, TvMediaActivity::class.java)
                    .putExtra("media", media as java.io.Serializable)
                    .putExtra(TvMediaActivity.EXTRA_DOWNLOADS, true)
            )
        }
    }
}

@Composable
private fun TvDownloads(
    active: List<DownloadItem>,
    groups: List<DownloadMediaGroup>?,
    onStop: (DownloadItem) -> Unit,
    onOpen: (DownloadMediaGroup) -> Unit,
    onDeleteTitle: (DownloadMediaGroup) -> Unit,
    onDeleteEpisode: (DownloadChild) -> Unit,
) {
    var stopping by remember { mutableStateOf<DownloadItem?>(null) }
    var opened by remember { mutableStateOf<DownloadMediaGroup?>(null) }
    val first = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val hasRows = active.isNotEmpty() || !groups.isNullOrEmpty()
    LaunchedEffect(hasRows) {
        if (hasRows && !focused) {
            androidx.compose.runtime.withFrameNanos { }
            focused = runCatching { first.requestFocus() }.isSuccess
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 36.dp)) {
            Text(stringResource(R.string.tv_downloads), style = MaterialTheme.typography.displaySmall)
            if (groups != null) {
                val episodes = groups.sumOf { it.children.size }
                val size = groups.mapNotNull { it.sizeBytes }.takeIf { it.size == groups.size }?.sum()
                Text(
                    listOfNotNull(
                        pluralStringResource(R.plurals.tv_episode_count, episodes, episodes),
                        size?.let { formatBytes(it) },
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            FocusRestingAt(120.dp) {
                LazyColumn(
                    contentPadding = PaddingValues(top = 24.dp, bottom = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (active.isNotEmpty()) {
                        item { SectionTitle(R.string.tv_downloading) }
                        items(active, key = { "a" + it.id }) { item ->
                            ActiveRow(item, if (item == active.first()) Modifier.focusRequester(first) else Modifier) { stopping = item }
                        }
                    }
                    item { SectionTitle(R.string.tv_downloaded) }
                    when {
                        groups == null -> item { Note(stringResource(R.string.loading)) }
                        groups.isEmpty() -> item { Note(stringResource(R.string.tv_downloads_empty)) }
                        else -> items(groups, key = { "g" + it.titleName }) { group ->
                            val modifier = if (active.isEmpty() && group == groups.first()) Modifier.focusRequester(first) else Modifier
                            TitleRow(group, modifier) { opened = group }
                        }
                    }
                }
            }
        }
    }

    stopping?.let { item ->
        TvConfirm(
            title = item.title,
            message = stringResource(R.string.tv_stop_download_confirm, item.label),
            confirm = stringResource(R.string.tv_stop_download),
            onConfirm = { stopping = null; onStop(item) },
            onDismiss = { stopping = null },
        )
    }
    // Looked up afresh, so a deletion inside it shows there at once.
    opened?.let { picked ->
        val group = groups?.firstOrNull { it.titleName == picked.titleName }
        if (group == null) opened = null
        else TitleDialog(
            group = group,
            onOpen = { opened = null; onOpen(group) },
            onDeleteTitle = { opened = null; onDeleteTitle(group) },
            onDeleteEpisode = onDeleteEpisode,
            onDismiss = { opened = null },
        )
    }
}

@Composable
private fun SectionTitle(title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp, start = 16.dp),
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** A download under way: its title and episode, how far it's got, and a bar for it. */
@Composable
private fun ActiveRow(item: DownloadItem, modifier: Modifier, onClick: () -> Unit) {
    val running = item.state == DownloadState.DOWNLOADING
    ListItem(
        selected = false,
        onClick = onClick,
        headlineContent = { Text(item.title, maxLines = 1) },
        supportingContent = {
            Column {
                Text(
                    stringResource(R.string.tv_episode_n, item.label) + "  ·  " + when (item.state) {
                        DownloadState.DOWNLOADING -> "${item.percent}%"
                        DownloadState.QUEUED -> stringResource(R.string.tv_download_queued)
                    }
                )
                if (running) Box(
                    Modifier.padding(top = 6.dp).fillMaxWidth().height(4.dp)
                        .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(2.dp))
                ) {
                    Box(
                        Modifier.fillMaxWidth(item.percent.coerceIn(0, 100) / 100f).fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                    )
                }
            }
        },
        leadingContent = { Icon(painterResource(R.drawable.ic_round_download_24), contentDescription = null, modifier = Modifier.size(28.dp)) },
        modifier = modifier.pointerClick(onClick = onClick),
    )
}

/** A downloaded title: its cover, name, and how many episodes it has here and their size. */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun TitleRow(group: DownloadMediaGroup, modifier: Modifier, onClick: () -> Unit) {
    ListItem(
        selected = false,
        onClick = onClick,
        headlineContent = { Text(group.title, maxLines = 1) },
        supportingContent = { Text(countAndSize(group.children.size, group.sizeBytes)) },
        leadingContent = {
            GlideImage(
                model = group.coverUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.width(44.dp).height(62.dp).clip(RoundedCornerShape(6.dp)),
            )
        },
        modifier = modifier.pointerClick(onClick = onClick),
    )
}

@Composable
private fun countAndSize(count: Int, size: Long?): String =
    listOfNotNull(pluralStringResource(R.plurals.tv_episode_count, count, count), size?.let { formatBytes(it) })
        .joinToString("  ·  ")

/**
 * A title's downloads: open it on them, delete them all, or one episode at a time — each deletion
 * asked about first, as there's no getting it back without downloading again.
 */
@Composable
private fun TitleDialog(
    group: DownloadMediaGroup,
    onOpen: () -> Unit,
    onDeleteTitle: () -> Unit,
    onDeleteEpisode: (DownloadChild) -> Unit,
    onDismiss: () -> Unit,
) {
    var deletingAll by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<DownloadChild?>(null) }
    val first = remember { FocusRequester() }
    TvDialog(onDismissRequest = onDismiss) {
        Surface(shape = CARD_SHAPE, modifier = Modifier.width(560.dp)) {
            Column(Modifier.padding(24.dp)) {
                Text(group.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2)
                Text(
                    countAndSize(group.children.size, group.sizeBytes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                DialogAction(R.drawable.ic_round_play_arrow_24, stringResource(R.string.tv_downloads_open), onOpen, Modifier.focusRequester(first))
                DialogAction(R.drawable.ic_round_delete_24, stringResource(R.string.tv_downloads_delete_all), { deletingAll = true })
                FocusRestingAt(48.dp) {
                    LazyColumn(Modifier.heightIn(max = 300.dp).padding(top = 8.dp)) {
                        items(group.children, key = { it.chapterName }) { child ->
                            val delete = { deleting = child }
                            ListItem(
                                selected = false,
                                onClick = delete,
                                headlineContent = { Text(stringResource(R.string.tv_episode_n, child.chapterName)) },
                                supportingContent = listOfNotNull(child.episodeTitle, child.sizeBytes?.let { formatBytes(it) })
                                    .takeIf { it.isNotEmpty() }?.let { parts -> { Text(parts.joinToString("  ·  "), maxLines = 1) } },
                                trailingContent = {
                                    Icon(painterResource(R.drawable.ic_round_delete_24), contentDescription = stringResource(R.string.delete), modifier = Modifier.size(22.dp))
                                },
                                modifier = Modifier.pointerClick(onClick = delete),
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
    if (deletingAll) TvConfirm(
        title = stringResource(R.string.tv_downloads_delete_all),
        message = stringResource(R.string.are_you_sure_delete_item, group.title),
        confirm = stringResource(R.string.delete),
        onConfirm = { deletingAll = false; onDeleteTitle() },
        onDismiss = { deletingAll = false },
    )
    deleting?.let { child ->
        TvConfirm(
            title = stringResource(R.string.tv_delete_download),
            message = stringResource(R.string.are_you_sure_delete_item, stringResource(R.string.tv_episode_n, child.chapterName)),
            confirm = stringResource(R.string.delete),
            onConfirm = { deleting = null; onDeleteEpisode(child) },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun DialogAction(icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ListItem(
        selected = false,
        onClick = onClick,
        headlineContent = { Text(label) },
        leadingContent = { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp)) },
        modifier = modifier.pointerClick(onClick = onClick),
    )
}

/** Asks before something that can't be undone; the safe answer has the focus. */
@Composable
fun TvConfirm(title: String, message: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cancel = remember { FocusRequester() }
    TvDialog(onDismissRequest = onDismiss) {
        Surface(shape = CARD_SHAPE, modifier = Modifier.width(480.dp)) {
            Column(Modifier.padding(24.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                )
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.tv.material3.Button(onClick = onConfirm, modifier = Modifier.pointerClick(onClick = onConfirm)) { Text(confirm) }
                    androidx.tv.material3.OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.focusRequester(cancel).pointerClick(onClick = onDismiss),
                    ) { Text(stringResource(R.string.cancel)) }
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        runCatching { cancel.requestFocus() }
    }
}
