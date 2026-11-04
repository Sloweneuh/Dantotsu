package ani.dantotsu.tv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ani.dantotsu.R
import ani.dantotsu.download.DownloadState
import ani.dantotsu.download.DownloadTracker
import ani.dantotsu.formatEta

/**
 * Downloads under way, in the rate limit pill's style and corner: a ring filling as the whole queue
 * does, how many episodes are left, and the one downloading now — its title, episode and time to
 * go. Like that pill it asks for nothing and never takes focus; the downloads screen has the rest.
 */
@Composable
fun TvDownloadIndicator(modifier: Modifier = Modifier) {
    val items by DownloadTracker.items.collectAsState()
    // Read along with the items, which are what it changes with.
    val overall = remember(items) { DownloadTracker.overallPercent() / 100f }
    val current = items.firstOrNull { it.state == DownloadState.DOWNLOADING }
    val left = items.count { it.state != DownloadState.FAILED }

    AnimatedVisibility(
        visible = left > 0,
        modifier = modifier,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
    ) {
        val ring = MaterialTheme.colorScheme.primary
        val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
        Row(
            Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f), RoundedCornerShape(50))
                .padding(start = 14.dp, end = 22.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(30.dp)) {
                    val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                    drawArc(track, 0f, 360f, useCenter = false, style = stroke)
                    drawArc(ring, -90f, 360f * overall, useCenter = false, style = stroke)
                }
                Icon(painterResource(R.drawable.ic_round_download_24), contentDescription = null, modifier = Modifier.size(14.dp))
            }
            Column(Modifier.widthIn(max = 320.dp)) {
                Text(
                    pluralStringResource(R.plurals.tv_downloading_count, left, left),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    if (current == null) stringResource(R.string.tv_download_queued)
                    else listOfNotNull(
                        current.title,
                        stringResource(R.string.tv_episode_n, current.label),
                        "${current.percent}%",
                        current.etaMs.takeIf { it > 0 }?.let { formatEta(it) },
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
