package ani.dantotsu.tv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.AnilistRateLimit
import ani.dantotsu.formatEta
import kotlinx.coroutines.delay

/**
 * TV mode's AniList rate limit indicator: the phone's floating pill, sized to be read from across
 * the room — a draining ring, what's waiting and for how long. It ends by itself, so it asks for
 * nothing: it never takes focus, and sits in the corner the rows' content leaves free.
 */
@Composable
fun TvRateLimitIndicator(modifier: Modifier = Modifier) {
    val window by AnilistRateLimit.window.collectAsState()
    var remaining by remember { mutableLongStateOf(0L) }
    var fraction by remember { mutableFloatStateOf(0f) }
    // Nothing comes from AniList to say the wait is over: the countdown is what notices.
    LaunchedEffect(window) {
        while (window != null && AnilistRateLimit.isLimited()) {
            remaining = AnilistRateLimit.remainingMillis()
            fraction = AnilistRateLimit.remainingPercent() / 100f
            delay(250)
        }
        remaining = 0L
        if (window != null) AnilistRateLimit.clear()
    }

    AnimatedVisibility(
        visible = remaining > 0,
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
            Canvas(Modifier.size(30.dp)) {
                val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                drawArc(track, 0f, 360f, useCenter = false, style = stroke)
                drawArc(ring, -90f, 360f * fraction, useCenter = false, style = stroke)
            }
            Column {
                Text(stringResource(R.string.anilist_rate_limited), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.tv_rate_limit_resumes, formatEta(remaining)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
