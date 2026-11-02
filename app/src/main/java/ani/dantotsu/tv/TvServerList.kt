package ani.dantotsu.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import ani.dantotsu.R
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.drawscope.rotate

/** Downloading: a kind of track to take along ([title]), each of [options] ticked or not, then [onDone]. */
class TvTrackChoice(val title: String, val options: List<String>, val onDone: (List<String>) -> Unit)

/**
 * The server sheet for a remote: its title, then either the remembered server being picked on its
 * own (with Cancel), or a spinner until the first streams arrive and a row each as they do — the
 * first taking focus as soon as there is one — with the "make default" switch beneath. As tall as
 * what it holds: the phone sheet ran the full height of the screen, mostly empty while loading.
 *
 * Downloading ([downloading]), the same list without the switch, then the [tracks] to take along,
 * then the server the download is [starting] on.
 */
@Composable
fun <T> TvServerSheet(
    downloading: Boolean = false,
    episode: String? = null,
    episodeCount: Int = 1,
    tracks: TvTrackChoice? = null,
    starting: String? = null,
    rows: List<T>,
    loading: Boolean,
    autoServer: String?,
    onCancelAuto: () -> Unit,
    makeDefault: Boolean,
    onMakeDefault: (Boolean) -> Unit,
    name: (T) -> String,
    details: (T) -> String,
    onPick: (T) -> Unit,
) {
    val first = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(rows.isNotEmpty(), autoServer) {
        if ((rows.isNotEmpty() || autoServer != null) && !focused) {
            androidx.compose.runtime.withFrameNanos { }
            focused = runCatching { first.requestFocus() }.isSuccess
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp)) {
        Text(
            when {
                downloading && episodeCount > 1 ->
                    androidx.compose.ui.res.pluralStringResource(R.plurals.tv_download_n_episodes, episodeCount, episodeCount)
                downloading && episode != null -> stringResource(R.string.tv_download_episode, episode)
                autoServer != null -> stringResource(R.string.auto_select_server)
                else -> stringResource(R.string.server_selector)
            },
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 12.dp, bottom = 12.dp),
        )
        if (starting != null) {
            Row(Modifier.padding(start = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Spinner()
                Text(
                    stringResource(R.string.tv_download_starting, starting),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
            return@Column
        }
        if (tracks != null) {
            // Keyed on the choice, so the second kind starts with nothing ticked and the focus back on top.
            androidx.compose.runtime.key(tracks) { TrackChecklist(tracks) }
            return@Column
        }
        if (autoServer != null) {
            Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Spinner()
                Text(autoServer, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp))
            }
            Button(
                onClick = onCancelAuto,
                modifier = Modifier.padding(start = 12.dp, top = 18.dp).focusRequester(first).pointerClick(onClick = onCancelAuto),
            ) { Text(stringResource(R.string.cancel)) }
            return@Column
        }
        if (rows.isEmpty() && loading) {
            Box(Modifier.fillMaxWidth().padding(vertical = 36.dp), contentAlignment = Alignment.Center) { Spinner() }
        }
        FocusRestingAt(24.dp) {
            LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(rows) { i, row ->
                    val pick = { onPick(row) }
                    ListItem(
                        selected = false,
                        onClick = pick,
                        headlineContent = { Text(name(row)) },
                        supportingContent = details(row).takeIf { it.isNotBlank() }?.let { { Text(it) } },
                        modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier).pointerClick(onClick = pick),
                    )
                }
                // More may still be on their way once the first have come.
                if (rows.isNotEmpty() && loading) item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) { Spinner(24) }
                }
            }
        }
        if (downloading) return@Column
        val flip = { onMakeDefault(!makeDefault) }
        ListItem(
            selected = false,
            onClick = flip,
            headlineContent = { Text(stringResource(R.string.make_default)) },
            supportingContent = { Text(stringResource(R.string.tv_make_default_desc)) },
            trailingContent = { Switch(checked = makeDefault, onCheckedChange = null) },
            modifier = Modifier.padding(top = 8.dp).pointerClick(onClick = flip),
        )
    }
}

/** One kind of track, a switch row each, and Download (with those ticked) or Skip (with none). */
@Composable
private fun TrackChecklist(choice: TvTrackChoice) {
    val picked = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    Text(
        choice.title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
    )
    FocusRestingAt(24.dp) {
        LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(choice.options) { i, option ->
                val flip = { if (option in picked) picked.remove(option) else picked.add(option); Unit }
                ListItem(
                    selected = false,
                    onClick = flip,
                    headlineContent = { Text(option) },
                    trailingContent = { Switch(checked = option in picked, onCheckedChange = null) },
                    modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier).pointerClick(onClick = flip),
                )
            }
        }
    }
    Row(Modifier.padding(start = 12.dp, top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val download = { choice.onDone(picked.toList()) }
        val skip = { choice.onDone(emptyList()) }
        Button(onClick = download, modifier = Modifier.pointerClick(onClick = download)) { Text(stringResource(R.string.download)) }
        androidx.tv.material3.OutlinedButton(onClick = skip, modifier = Modifier.pointerClick(onClick = skip)) {
            Text(stringResource(R.string.skip))
        }
    }
}

/** The app's loading ring, in TV mode's accent colour. */
@Composable
private fun Spinner(sizeDp: Int = 32) {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "spinner")
    val angle by transition.animateFloat(
        0f, 360f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.LinearEasing)),
        label = "spin",
    )
    val color = MaterialTheme.colorScheme.primary
    androidx.compose.foundation.Canvas(Modifier.size(sizeDp.dp)) {
        rotate(angle) {
            drawArc(
                color, 0f, 270f, useCenter = false,
                style = androidx.compose.ui.graphics.drawscope.Stroke(4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        }
    }
}
