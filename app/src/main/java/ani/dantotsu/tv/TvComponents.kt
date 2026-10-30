package ani.dantotsu.tv

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ani.dantotsu.R
import ani.dantotsu.media.Media
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage

/** How far a focused card grows, and the outline it gets: what the eye follows across the room. */
internal const val FOCUSED_SCALE = 1.08f
internal val FOCUS_OUTLINE = 3.dp
internal val CARD_SHAPE = RoundedCornerShape(12.dp)

/**
 * The screen-filling banner behind a TV screen, fading into the background towards the bottom and
 * the start edge so text laid over it stays readable. Cross-fades as the focused title changes.
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun Backdrop(imageUrl: String?, modifier: Modifier = Modifier) {
    val background = MaterialTheme.colorScheme.background
    Box(modifier.fillMaxSize()) {
        Crossfade(targetState = imageUrl, animationSpec = tween(450), label = "backdrop") { url ->
            if (url != null) GlideImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to background, 0.55f to background.copy(alpha = 0.55f), 1f to Color.Transparent)
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.Transparent, 0.5f to background.copy(alpha = 0.4f), 1f to background)
            )
        )
    }
}

/** A cover, outlined and grown when focused, its title underneath. */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun PosterCard(
    media: Media,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    width: Dp = 112.dp,
) {
    Column(modifier.width(width)) {
        Card(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .onFocusChanged { if (it.isFocused) onFocused() },
            shape = CardDefaults.shape(CARD_SHAPE),
            scale = CardDefaults.scale(focusedScale = FOCUSED_SCALE),
            border = CardDefaults.border(
                focusedBorder = Border(BorderStroke(FOCUS_OUTLINE, MaterialTheme.colorScheme.primary), shape = CARD_SHAPE),
            ),
        ) {
            GlideImage(
                model = media.cover,
                contentDescription = media.userPreferredName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            media.userPreferredName,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp),
        )
        if (subtitle != null) Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
    }
}

/** "TV · 2023 · 28 episodes · 91%": what a title is, in one line. */
@Composable
fun metaLine(media: Media): String = listOfNotNull(
    media.format?.replace('_', ' '),
    media.startDate?.year?.toString(),
    media.anime?.totalEpisodes?.let { pluralStringResource(R.plurals.tv_episode_count, it, it) },
    media.meanScore?.let { "$it%" },
).joinToString("  ·  ")

/**
 * AniList descriptions carry HTML; the TV shows plain text, as one paragraph — a blank line left in
 * would end the clipped text on an empty line, its "…" alone.
 */
fun plainDescription(media: Media): String =
    media.description?.let {
        HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().replace(Regex("\\s+"), " ").trim()
    }.orEmpty()

/** Where the user is in a title: "Ep 5 / 12", or nothing before they've started. */
@Composable
fun progressLabel(media: Media): String? {
    val progress = media.userProgress?.takeIf { it > 0 } ?: return null
    val total = media.anime?.totalEpisodes ?: media.anime?.nextAiringEpisode
    return if (total != null) stringResource(R.string.tv_progress_of, progress, total)
    else stringResource(R.string.tv_progress, progress)
}

/**
 * Where a focused item comes to rest when the list scrolls to it: [leading] from the start of the
 * list, every time. Left to itself a lazy list only scrolls just far enough to show the item — so
 * coming back to the first card left it flush against the screen edge with the row's margin scrolled
 * away, and moving up a row put the card's top (and the row's title) under the edge.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@androidx.compose.runtime.Composable
fun FocusRestingAt(leading: Dp, content: @androidx.compose.runtime.Composable () -> Unit) {
    val leadingPx = with(androidx.compose.ui.platform.LocalDensity.current) { leading.toPx() }
    val spec = androidx.compose.runtime.remember(leadingPx) {
        object : androidx.compose.foundation.gestures.BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                offset - leadingPx
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides spec,
        content = content,
    )
}
