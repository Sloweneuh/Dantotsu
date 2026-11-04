package ani.dantotsu.tv

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ani.dantotsu.R
import ani.dantotsu.media.Media

/** A downloaded title on the offline home, with how many of its episodes are saved. */
internal class OfflineTitle(val media: Media, val episodes: Int)

/**
 * TV mode's home while offline (no connection, or offline mode switched on): the downloaded anime,
 * which play without the internet, in place of the AniList rows, which can't load. Their details
 * are the ones saved with the download.
 *
 * [onGoOnline] is there while a connection is: offline mode chosen by hand is left by hand.
 */
@Composable
internal fun TvOfflineHome(
    titles: List<OfflineTitle>?,
    onOpen: (Media) -> Unit,
    onGoOnline: (() -> Unit)?,
    onDownloads: () -> Unit,
    onSettings: () -> Unit,
    onExit: (() -> Unit)?,
) {
    var shown by remember { mutableStateOf<Media?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(titles?.isNotEmpty()) {
        if (titles.isNullOrEmpty()) return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }
        runCatching { first.requestFocus() }
    }

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
                // What this screen is, where the online home has nothing in this corner.
                Row(
                    Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant, androidx.compose.foundation.shape.RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_round_signal_wifi_off_outline_24),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        stringResource(R.string.tv_offline_pill),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (onGoOnline != null) {
                    TopButton(R.drawable.ic_round_wifi_24, stringResource(R.string.go_online), onGoOnline, highlight = true)
                    Spacer(Modifier.width(12.dp))
                }
                TopButton(R.drawable.ic_round_download_24, stringResource(R.string.tv_downloads), onDownloads)
                Spacer(Modifier.width(12.dp))
                TopButton(R.drawable.ic_round_settings_24, stringResource(R.string.settings), onSettings)
                if (onExit != null) {
                    Spacer(Modifier.width(12.dp))
                    TopButton(R.drawable.ic_round_logout_24, stringResource(R.string.tv_exit), onExit)
                }
            }
            Hero(shown, Modifier.padding(start = 48.dp, top = 4.dp, bottom = 20.dp).height(170.dp))
            when {
                titles == null -> Unit
                titles.isEmpty() -> Text(
                    stringResource(R.string.tv_offline_empty),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(48.dp),
                )
                else -> {
                    Text(
                        stringResource(R.string.tv_offline_row),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 48.dp, bottom = 12.dp),
                    )
                    FocusRestingAt(48.dp) {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            itemsIndexed(titles, key = { _, title -> title.media.id }) { index, title ->
                                PosterCard(
                                    media = title.media,
                                    onClick = { onOpen(title.media) },
                                    onFocused = { shown = title.media },
                                    subtitle = pluralStringResource(R.plurals.tv_episode_count, title.episodes, title.episodes),
                                    modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
