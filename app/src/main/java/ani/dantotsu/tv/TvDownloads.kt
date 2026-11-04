package ani.dantotsu.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import ani.dantotsu.R

/** Where an episode stands with downloads, as its card and its actions show it. */
sealed interface EpisodeDownload {
    data object None : EpisodeDownload
    data object Queued : EpisodeDownload
    data class Running(val percent: Int) : EpisodeDownload
    data object Done : EpisodeDownload
}

/** What has to be set up before a first download: somewhere to keep them, then the add-on that makes them. */
enum class DownloadSetup { STORAGE, ADDON }

/**
 * Holding OK on an episode: play it, and download it, stop its download or delete it, whichever
 * applies. The first action has the focus.
 */
@Composable
fun EpisodeActions(
    episode: String,
    state: EpisodeDownload,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    TvDialog(onDismissRequest = onDismiss) {
        Surface(shape = CARD_SHAPE, modifier = Modifier.width(420.dp)) {
            Column(Modifier.padding(24.dp)) {
                Text(
                    stringResource(R.string.tv_episode_n, episode),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                Action(R.drawable.ic_round_play_arrow_24, stringResource(R.string.tv_play), onPlay, Modifier.focusRequester(first))
                when (state) {
                    EpisodeDownload.None -> Action(R.drawable.ic_round_download_24, stringResource(R.string.download), onDownload)
                    EpisodeDownload.Queued, is EpisodeDownload.Running ->
                        Action(R.drawable.ic_round_close_24, stringResource(R.string.tv_stop_download), onCancel)
                    EpisodeDownload.Done -> Action(R.drawable.ic_round_delete_24, stringResource(R.string.tv_delete_download), onDelete)
                }
            }
        }
    }
    // A frame on: the dialog's window isn't there to take focus at first.
    LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        runCatching { first.requestFocus() }
    }
}

@Composable
private fun Action(icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ListItem(
        selected = false,
        onClick = onClick,
        headlineContent = { Text(label) },
        leadingContent = { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp)) },
        modifier = modifier.pointerClick(onClick = onClick),
    )
}

/**
 * Asked on a first download. For [DownloadSetup.STORAGE]: keep downloads on this device, in the
 * app's own storage (all a TV without a folder picker can do), or in a folder picked through the
 * system's picker where there is one. For [DownloadSetup.ADDON]: install the add-on downloads need.
 */
@Composable
fun DownloadSetupDialog(
    setup: DownloadSetup,
    canPickFolder: Boolean,
    onAppStorage: () -> Unit,
    onPickFolder: () -> Unit,
    onInstallAddon: () -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    TvDialog(onDismissRequest = onDismiss) {
        Surface(shape = CARD_SHAPE, modifier = Modifier.width(520.dp)) {
            Column(Modifier.padding(24.dp)) {
                Text(
                    stringResource(if (setup == DownloadSetup.STORAGE) R.string.tv_download_where else R.string.download_addon_not_installed),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    stringResource(if (setup == DownloadSetup.STORAGE) R.string.tv_download_where_desc else R.string.tv_download_addon_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                )
                if (setup == DownloadSetup.STORAGE) {
                    Action(R.drawable.ic_round_tv_outline_24, stringResource(R.string.tv_download_app_storage), onAppStorage, Modifier.focusRequester(first))
                    if (canPickFolder) Action(R.drawable.ic_round_folder_outline_24, stringResource(R.string.tv_download_pick_folder), onPickFolder)
                } else {
                    Action(R.drawable.ic_round_download_24, stringResource(R.string.tv_download_install_addon), onInstallAddon, Modifier.focusRequester(first))
                }
                Action(R.drawable.ic_round_close_24, stringResource(R.string.cancel), onDismiss)
            }
        }
    }
    // A frame on: the dialog's window isn't there to take focus at first.
    LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        runCatching { first.requestFocus() }
    }
}

/**
 * The media page's Download button: the episodes not watched yet, all of them, or a range picked
 * with two steppers, each leaving out what's downloaded or downloading already ([skip]). Each
 * choice says how many that is, and one that comes to none can't be picked.
 */
@Composable
fun BatchDownloadDialog(
    episodes: List<String>,
    watched: Int,
    skip: (String) -> Boolean,
    onDownload: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val wanted = remember(episodes) { episodes.filterNot(skip) }
    val unwatched = remember(wanted, watched) { wanted.filter { (it.toFloatOrNull() ?: Float.MAX_VALUE) > watched } }
    // The range runs over every episode, so its ends read as the episode numbers themselves.
    var from by remember { androidx.compose.runtime.mutableIntStateOf(episodes.indexOfFirst { it in unwatched }.coerceAtLeast(0)) }
    var to by remember { androidx.compose.runtime.mutableIntStateOf(episodes.lastIndex) }
    val ranged = episodes.subList(minOf(from, to), maxOf(from, to) + 1).filter { it in wanted }
    val first = remember { FocusRequester() }
    TvDialog(onDismissRequest = onDismiss) {
        Surface(shape = CARD_SHAPE, modifier = Modifier.width(520.dp)) {
            Column(Modifier.padding(24.dp)) {
                Text(
                    stringResource(R.string.tv_download_episodes),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                BatchChoice(stringResource(R.string.tv_download_unwatched), unwatched, onDownload, Modifier.focusRequester(first))
                BatchChoice(stringResource(R.string.tv_download_all), wanted, onDownload)
                Text(
                    stringResource(R.string.tv_download_range),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                )
                // One above the other: left and right are theirs, so up and down are the way between them.
                Stepper(stringResource(R.string.tv_download_from), episodes[from], { from = (from + it).coerceIn(0, episodes.lastIndex) })
                Stepper(stringResource(R.string.tv_download_to), episodes[to], { to = (to + it).coerceIn(0, episodes.lastIndex) })
                BatchChoice(stringResource(R.string.tv_download_this_range), ranged, onDownload)
                Action(R.drawable.ic_round_close_24, stringResource(R.string.cancel), onDismiss)
            }
        }
    }
    LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        runCatching { first.requestFocus() }
    }
}

/** A batch: what it is, how many episodes that makes, and downloading them; nothing to pick at none. */
@Composable
private fun BatchChoice(label: String, numbers: List<String>, onDownload: (List<String>) -> Unit, modifier: Modifier = Modifier) {
    val pick = { if (numbers.isNotEmpty()) onDownload(numbers) }
    ListItem(
        selected = false,
        enabled = numbers.isNotEmpty(),
        onClick = pick,
        headlineContent = { Text(label) },
        supportingContent = {
            Text(androidx.compose.ui.res.pluralStringResource(R.plurals.tv_episode_count, numbers.size, numbers.size))
        },
        leadingContent = { Icon(painterResource(R.drawable.ic_round_download_24), contentDescription = null, modifier = Modifier.size(24.dp)) },
        modifier = modifier.pointerClick(onClick = pick),
    )
}

/** An end of the range: left and right step it back and forth an episode at a time. */
@Composable
private fun Stepper(label: String, value: String, onStep: (Int) -> Unit, modifier: Modifier = Modifier) {
    ListItem(
        selected = false,
        onClick = {},
        headlineContent = { Text(stringResource(R.string.tv_episode_n, value)) },
        overlineContent = { Text(label) },
        leadingContent = { Icon(painterResource(R.drawable.ic_round_arrow_back_ios_new_24), contentDescription = null, modifier = Modifier.size(16.dp)) },
        trailingContent = { Icon(painterResource(R.drawable.ic_round_arrow_forward_ios_24), contentDescription = null, modifier = Modifier.size(16.dp)) },
        modifier = modifier.onPreviewKeyEvent { event ->
            val step = when (event.key) {
                androidx.compose.ui.input.key.Key.DirectionLeft -> -1
                androidx.compose.ui.input.key.Key.DirectionRight -> 1
                else -> return@onPreviewKeyEvent false
            }
            if (event.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) onStep(step)
            true
        },
    )
}

/** On an episode's thumbnail: a tick once downloaded, the download icon while queued, a bar as it runs. */
@Composable
fun BoxScope.EpisodeDownloadMarks(state: EpisodeDownload) {
    when (state) {
        EpisodeDownload.None -> Unit
        EpisodeDownload.Done, EpisodeDownload.Queued -> Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(28.dp)
                .background(if (state == EpisodeDownload.Done) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.6f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(if (state == EpisodeDownload.Done) R.drawable.ic_round_check_24 else R.drawable.ic_round_download_24),
                contentDescription = stringResource(if (state == EpisodeDownload.Done) R.string.tv_downloaded else R.string.tv_download_queued),
                tint = if (state == EpisodeDownload.Done) MaterialTheme.colorScheme.onPrimary else Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
        is EpisodeDownload.Running -> {
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) {
                Text("${state.percent}%", style = MaterialTheme.typography.labelMedium, color = Color.White)
            }
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(5.dp)
                    .background(Color.Black.copy(alpha = 0.5f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(state.percent.coerceIn(0, 100) / 100f)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}
