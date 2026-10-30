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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
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
                .onFocusChanged { if (it.isFocused) onFocused() }
                .pointerClick(onClick = onClick),
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

/**
 * A title shown whole: from the large size down, the first that fits it in two lines — the
 * smallest gets a third. Long names were cut off with "…", and nowhere showed the rest of them.
 * Nothing is drawn until the size is settled, so a long one doesn't flash big first.
 */
@Composable
fun FittedTitle(text: String, modifier: Modifier = Modifier) {
    val typography = MaterialTheme.typography
    // Smaller, but the same bold face as the large size: the headline styles are regular weight.
    val large = typography.displaySmall
    val sizes = listOf(large, typography.headlineLarge, typography.headlineMedium, typography.headlineSmall)
        .map { it.copy(fontWeight = large.fontWeight, fontFamily = large.fontFamily) }
    var size by androidx.compose.runtime.remember(text) { androidx.compose.runtime.mutableIntStateOf(0) }
    var settled by androidx.compose.runtime.remember(text) { androidx.compose.runtime.mutableStateOf(false) }
    val last = size == sizes.lastIndex
    Text(
        text,
        style = sizes[size],
        maxLines = if (last) 3 else 2,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (it.hasVisualOverflow && !last) size++ else settled = true },
        modifier = modifier.drawWithContent { if (settled) drawContent() },
    )
}

/** "TV · 2023 · 28 episodes · 91%": what a title is, in one line. */
@Composable
fun metaLine(media: Media): String = listOfNotNull(
    media.format?.replace('_', ' '),
    media.startDate?.year?.toString(),
    airingStatus(media),
    media.anime?.totalEpisodes?.let { pluralStringResource(R.plurals.tv_episode_count, it, it) },
    media.meanScore?.let { "$it%" },
).joinToString("  ·  ")

/** Whether the title is airing, done, or still to come — AniList's status, in words. */
@Composable
fun airingStatus(media: Media): String? {
    val status = media.status?.takeIf { it.isNotBlank() } ?: return null
    // Media keeps AniList's status already put into words (MediaStatus.toString(): "NOT YET
    // RELEASED", or the translation), so each is matched in its key and its stored forms.
    val context = androidx.compose.ui.platform.LocalContext.current
    fun matches(key: String, stored: Int) =
        status == key || status == key.replace('_', ' ') || status == context.getString(stored)
    return when {
        matches("RELEASING", R.string.status_releasing) -> stringResource(R.string.tv_airing_releasing)
        matches("FINISHED", R.string.status_finished) -> stringResource(R.string.tv_airing_finished)
        matches("NOT_YET_RELEASED", R.string.status_not_yet_released) -> stringResource(R.string.tv_airing_not_yet)
        matches("CANCELLED", R.string.status_cancelled) -> stringResource(R.string.tv_airing_cancelled)
        matches("HIATUS", R.string.status_hiatus) -> stringResource(R.string.tv_airing_hiatus)
        else -> status.lowercase().replaceFirstChar { it.titlecase() }
    }
}

/** "Watching · Ep 5 / 12": the user's list status and progress, or null when it isn't on their list. */
@Composable
fun listStatusLabel(media: Media): String? {
    val status = when (media.userStatus) {
        "CURRENT" -> stringResource(R.string.tv_list_current)
        "PLANNING" -> stringResource(R.string.tv_list_planning)
        "COMPLETED" -> stringResource(R.string.tv_list_completed)
        "DROPPED" -> stringResource(R.string.tv_list_dropped)
        "PAUSED" -> stringResource(R.string.tv_list_paused)
        "REPEATING" -> stringResource(R.string.tv_list_repeating)
        else -> return null
    }
    val progress = if (media.userStatus == "COMPLETED") null else progressLabel(media)
    return listOfNotNull(status, progress).joinToString("  ·  ")
}

/**
 * Where the title sits on the user's list — "Watching · Ep 5 / 12" — as a pill in the accent colour;
 * nothing for a title that isn't on it.
 */
@Composable
fun ListStatusPill(media: Media, modifier: Modifier = Modifier) {
    val label = listStatusLabel(media) ?: return
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 5.dp),
    )
}

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
                // Focus the mouse gave stays where it is: the row moving would take the item from under it.
                if (TvPointer.drivesFocus) 0f else offset - leadingPx
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides spec,
        content = content,
    )
}

/**
 * Fades what a scrolling list cuts off at its edges — [horizontal] wide at the start and end, [vertical]
 * tall at the top and bottom ([top] apart, for a list whose top has nothing above it yet) — rather than slicing it straight across.
 */
fun Modifier.fadingEdges(horizontal: Dp = 0.dp, vertical: Dp = 0.dp, top: Dp = vertical): Modifier =
    graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val h = horizontal.toPx()
            val v = vertical.toPx()
            val t = top.toPx()
            val clear = Color.Transparent
            if (h > 0f) drawRect(
                Brush.horizontalGradient(0f to clear, h / size.width to Color.Black, 1f - h / size.width to Color.Black, 1f to clear),
                blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
            )
            if (v > 0f || t > 0f) drawRect(
                Brush.verticalGradient(
                    0f to (if (t > 0f) clear else Color.Black), t / size.height to Color.Black,
                    1f - v / size.height to Color.Black, 1f to (if (v > 0f) clear else Color.Black),
                ),
                blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
            )
        }

/**
 * A list the mouse wheel and a swipe step through, one item of focus at a time — the wheel's notch
 * or a swipe's length moves focus to the next row (or card, sideways), and the list snaps to it the
 * way the D-pad has it, instead of scrolling freely past rows with the focus left behind.
 * [onNoFocus] runs when there's no focus to move yet; [onMoved] after focus has moved, for a list
 * that scrolls itself to it. A list of rows passes [stepRow] to make the up and down steps itself:
 * a touch puts Android in touch mode, which drops focus, so there's no focus to move from.
 */
fun Modifier.stepsFocus(
    onNoFocus: () -> Unit = {},
    onMoved: (androidx.compose.ui.focus.FocusDirection) -> Unit = {},
    stepRow: ((down: Boolean) -> Unit)? = null,
): Modifier = composed {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val step = with(androidx.compose.ui.platform.LocalDensity.current) { SWIPE_STEP.toPx() }
    val minSwipe = with(androidx.compose.ui.platform.LocalDensity.current) { MIN_SWIPE.toPx() }
    var lastStep by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    fun move(direction: androidx.compose.ui.focus.FocusDirection) {
        // Moved by the remote's rules: the list scrolls to the new focus, the pointer doesn't hold it.
        TvPointer.active = false
        TvPointer.steppedAt = System.currentTimeMillis()
        val vertical = direction == androidx.compose.ui.focus.FocusDirection.Down ||
            direction == androidx.compose.ui.focus.FocusDirection.Up
        if (vertical && stepRow != null) stepRow(direction == androidx.compose.ui.focus.FocusDirection.Down)
        else if (focusManager.moveFocus(direction)) onMoved(direction) else onNoFocus()
    }
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                if (event.type != androidx.compose.ui.input.pointer.PointerEventType.Scroll) continue
                val delta = event.changes.fold(androidx.compose.ui.geometry.Offset.Zero) { sum, it -> sum + it.scrollDelta }
                event.changes.forEach { it.consume() }
                // A trackpad sends a burst of small deltas for one flick: one step per burst.
                val now = System.currentTimeMillis()
                if (now - lastStep < WHEEL_STEP_MS || delta == androidx.compose.ui.geometry.Offset.Zero) continue
                lastStep = now
                move(
                    if (kotlin.math.abs(delta.y) >= kotlin.math.abs(delta.x)) {
                        if (delta.y > 0) androidx.compose.ui.focus.FocusDirection.Down else androidx.compose.ui.focus.FocusDirection.Up
                    } else {
                        if (delta.x > 0) androidx.compose.ui.focus.FocusDirection.Right else androidx.compose.ui.focus.FocusDirection.Left
                    }
                )
            }
        }
    }.pointerInput(Unit) {
        // Windows' Android subsystem sends a mouse wheel notch as a short drag (about 100px), not as
        // a scroll: any swipe that gets going is one row at least, and a long one steps on further.
        var dragged = 0f
        var stepped = false
        detectVerticalDragGestures(
            onDragStart = { dragged = 0f; stepped = false },
            onDragEnd = {
                if (!stepped && kotlin.math.abs(dragged) >= minSwipe) {
                    move(if (dragged < 0) androidx.compose.ui.focus.FocusDirection.Down else androidx.compose.ui.focus.FocusDirection.Up)
                }
            },
        ) { change, amount ->
            change.consume()
            dragged += amount
            // Dragging up brings the next row, as a finger moves a list.
            while (dragged <= -step) { dragged += step; stepped = true; move(androidx.compose.ui.focus.FocusDirection.Down) }
            while (dragged >= step) { dragged -= step; stepped = true; move(androidx.compose.ui.focus.FocusDirection.Up) }
        }
    }
}

private const val WHEEL_STEP_MS = 140L
private val SWIPE_STEP = 120.dp
private val MIN_SWIPE = 16.dp

/**
 * Whether the mouse is what the user is using: set when the pointer really moves, cleared by any key.
 * Kept apart from hover itself — when a list scrolls under a still pointer, the item arriving under
 * it is "hovered" too, and mustn't take focus from the remote that just scrolled it there.
 */
internal object TvPointer {
    @Volatile
    var active = false

    /** When the wheel last stepped focus: the hover and the resting rules leave that move alone a moment. */
    @Volatile
    var steppedAt = 0L

    /** Whether focus is the pointer's to move: it's in use, and hasn't just been stepped by the wheel. */
    val drivesFocus: Boolean
        get() = active && System.currentTimeMillis() - steppedAt > STEP_SETTLE_MS

    private const val STEP_SETTLE_MS = 600L
}

/**
 * Tracks [TvPointer] for everything inside: on the root of each TV window (screen or dialog).
 */
internal fun Modifier.tvInputTracking(): Modifier = composed {
    var last by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    onPreviewKeyEvent { TvPointer.active = false; false }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    // Seen before the items below, so they know whether this event is the mouse moving.
                    val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    val change = event.changes.firstOrNull() ?: continue
                    val at = change.position
                    // Pointing only: a pointer moving with nothing pressed, which a finger never does (a
                    // touch means to tap or swipe). Not the reported pointer type — Windows' Android
                    // subsystem doesn't always say a mouse is one.
                    if (!change.pressed && at != last) TvPointer.active = true
                    last = at
                }
            }
        }
}

/**
 * TV mode's buttons, cards and rows (androidx.tv surfaces) answer the D-pad and a keyboard's Enter
 * only: a mouse click or a tap on them did nothing, which left a desktop window or a tablet with
 * a pointer that couldn't press anything. This takes that click, without adding a focus stop.
 *
 * Pointing at one gives it focus, so it shows the focused look and there is only ever one — a hover
 * look of its own sat next to the remote's focus elsewhere, two items lit at once. [hoverFocus] is
 * off for what has nothing to focus (a dialog's backdrop).
 */
fun Modifier.pointerClick(hoverFocus: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val current by androidx.compose.runtime.rememberUpdatedState(onClick)
    val tap = Modifier.pointerInput(Unit) { detectTapGestures { current() } }
    if (!hoverFocus) return@composed tap
    val requester = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    var focused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    onFocusChanged { focused = it.hasFocus }
        .focusRequester(requester)
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val type = event.type
                    if ((type == androidx.compose.ui.input.pointer.PointerEventType.Enter ||
                            type == androidx.compose.ui.input.pointer.PointerEventType.Move) &&
                        TvPointer.drivesFocus && !focused &&
                        event.changes.firstOrNull()?.pressed == false
                    ) runCatching { requester.requestFocus() }
                }
            }
        }
        .then(tap)
}
