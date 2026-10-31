package ani.dantotsu.tv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ani.dantotsu.R
import androidx.compose.animation.graphics.res.animatedVectorResource
import kotlinx.coroutines.delay

/**
 * What TV mode's player overlay shows, kept up to date by the player; and what it can ask of it.
 * The player keeps its own logic — the overlay only draws it for a remote, and passes the buttons on.
 */
class TvPlayerController(private val actions: Actions) {

    interface Actions {
        fun playPause()
        fun seekBy(deltaMs: Long)
        fun seekTo(positionMs: Long)
        fun previous()
        fun next()
        fun audio()
        fun subtitles()
        fun server()
        fun skip()
        fun playNext()
        fun cancelNext()
    }

    /** "Skip opening" while there's a segment to skip; null otherwise. */
    var skipLabel by mutableStateOf<String?>(null)

    /** The next episode on offer: its name, and the countdown when one is running. */
    class UpNext(val label: String, val countdown: String?, val thumb: String? = null)
    var upNext by mutableStateOf<UpNext?>(null)

    var visible by mutableStateOf(true)
        private set
    var title by mutableStateOf("")
    var episodeLine by mutableStateOf("")
    var playing by mutableStateOf(false)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var buffered by mutableFloatStateOf(0f)
    var hasPrevious by mutableStateOf(false)
    var hasNext by mutableStateOf(false)
    var hasAudio by mutableStateOf(false)
    var hasSubtitles by mutableStateOf(false)

    /** Bumped on every key, so the auto-hide waits for the user to stop pressing. */
    var lastInput by mutableLongStateOf(0L)
        private set

    fun show() {
        visible = true
        poke()
    }

    fun hide() {
        visible = false
    }

    fun poke() {
        lastInput = System.currentTimeMillis()
    }

    internal fun act(block: Actions.() -> Unit) {
        poke()
        actions.block()
    }

    /** A list to pick one of — an audio track, a subtitle — drawn over the player for a remote. */
    class Choice(val title: String, val options: List<Option>)
    class Option(val label: String, val selected: Boolean, val pick: () -> Unit)

    var choice by mutableStateOf<Choice?>(null)

    fun choose(choice: Choice) {
        this.choice = choice
        poke()
    }
}

private const val HIDE_AFTER_MS = 5_000L
/** The player's seek step (the same one the D-pad uses with the controls hidden), in milliseconds. */
private val seekStepMs: Long
    get() = ani.dantotsu.settings.saving.PrefManager.getVal<Int>(ani.dantotsu.settings.saving.PrefName.SeekTime) * 1000L

/**
 * The player's controls for a TV: the title over a gradient at the top; at the bottom a timeline the
 * D-pad scrubs and one row of large buttons, the focused one named beneath. Out of the way on its own
 * after a few seconds of playing; up for as long as the episode is paused.
 */
@Composable
fun TvPlayerOverlay(controller: TvPlayerController) {
    val playButton = remember { FocusRequester() }

    // Hidden again once the user stops pressing — unless paused, when there's nothing to watch.
    LaunchedEffect(controller.visible, controller.lastInput, controller.playing) {
        if (controller.visible && controller.playing) {
            delay(HIDE_AFTER_MS)
            controller.hide()
        }
    }
    val promptFocus = remember { FocusRequester() }
    val prompting = controller.upNext != null || controller.skipLabel != null
    LaunchedEffect(controller.visible) {
        if (controller.visible) runCatching { (if (prompting) promptFocus else playButton).requestFocus() }
    }

    controller.choice?.let { choice -> ChoiceDialog(choice) { controller.choice = null } }

    // Skip and up next stay up whether or not the controls are, above them when they are: OK
    // presses them while the controls are hidden (see ExoplayerView.remoteKey), a mouse clicks them.
    val raise by androidx.compose.animation.core.animateDpAsState(
        // Clear of the timeline and its times, with room to grow when focused.
        if (controller.visible) 200.dp else 40.dp, label = "promptLift",
    )
    Box(Modifier.fillMaxSize().padding(end = 48.dp, bottom = raise), contentAlignment = Alignment.BottomEnd) {
        val next = controller.upNext
        val skip = controller.skipLabel
        when {
            next != null -> UpNextCard(next, controller, promptFocus)
            skip != null -> SkipPill(skip, promptFocus) { controller.act { skip() } }
        }
    }

    AnimatedVisibility(visible = controller.visible, enter = fadeIn(), exit = fadeOut()) {
        // A mouse click on the picture itself, not a button, puts the controls away.
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { controller.hide() } }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
                    .padding(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 48.dp)
                    .align(Alignment.TopStart)
            ) {
                Text(
                    controller.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    controller.episodeLine,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomStart)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                    .padding(start = 48.dp, end = 48.dp, top = 56.dp, bottom = 24.dp)
            ) {
                Timeline(controller)
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    ControlButton(R.drawable.ic_round_skip_previous_24, R.string.tv_previous_episode, controller.hasPrevious) {
                        controller.act { previous() }
                    }
                    ControlButton(R.drawable.ic_round_replay_10_24, R.string.tv_rewind_10) {
                        controller.act { seekBy(-seekStepMs) }
                    }
                    // The phone player's animated icon: one morphing into the other, at one size
                    // (the separate play and pause icons were drawn at different sizes).
                    @OptIn(androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi::class)
                    val playPause = androidx.compose.animation.graphics.vector.AnimatedImageVector
                        .animatedVectorResource(R.drawable.anim_play_to_pause)
                    @OptIn(androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi::class)
                    val playPausePainter = androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter(
                        playPause, atEnd = controller.playing,
                    )
                    ControlButton(
                        R.drawable.ic_round_play_arrow_24,
                        if (controller.playing) R.string.pause else R.string.tv_play,
                        large = true,
                        painter = playPausePainter,
                        modifier = Modifier.focusRequester(playButton),
                    ) { controller.act { playPause() } }
                    ControlButton(R.drawable.ic_round_forward_10_24, R.string.tv_forward_10) {
                        controller.act { seekBy(seekStepMs) }
                    }
                    ControlButton(R.drawable.ic_round_skip_next_24, R.string.tv_next_episode, controller.hasNext) {
                        controller.act { next() }
                    }
                    Spacer(Modifier.weight(1f))
                    ControlButton(R.drawable.ic_round_audiotrack_24, R.string.audio_tracks, controller.hasAudio) {
                        controller.act { audio() }
                    }
                    ControlButton(R.drawable.ic_round_subtitles_24, R.string.subtitles, controller.hasSubtitles) {
                        controller.act { subtitles() }
                    }
                    ControlButton(R.drawable.ic_round_dns_24, R.string.tv_server) {
                        controller.act { server() }
                    }
                }
            }
        }
    }
}

/** Elapsed / total, and a bar the D-pad scrubs while it has focus. */
@Composable
private fun Timeline(controller: TvPlayerController) {
    var focused by remember { mutableStateOf(false) }
    val fraction = if (controller.duration > 0) (controller.position.toFloat() / controller.duration).coerceIn(0f, 1f) else 0f
    val primary = MaterialTheme.colorScheme.primary
    var width by remember { mutableFloatStateOf(1f) }
    fun seekAt(x: Float) {
        if (controller.duration > 0) controller.act {
            seekTo((x / width).coerceIn(0f, 1f).times(controller.duration).toLong())
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            // A mouse seeks by clicking the bar, or dragging along it.
            .pointerInput(Unit) { detectTapGestures { seekAt(it.x) } }
            .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> seekAt(change.position.x) } }
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionRight -> true.also { controller.act { seekBy(seekStepMs) } }
                    Key.DirectionLeft -> true.also { controller.act { seekBy(-seekStepMs) } }
                    else -> false
                }
            }
            .focusable()
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (focused) 10.dp else 6.dp)
                .background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(50))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(controller.buffered.coerceIn(0f, 1f))
                    .height(if (focused) 10.dp else 6.dp)
                    .background(Color.White.copy(alpha = 0.35f), RoundedCornerShape(50))
            )
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(if (focused) 10.dp else 6.dp)
                    .background(primary, RoundedCornerShape(50))
                    .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(50)) else Modifier)
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(formatTime(controller.position), style = MaterialTheme.typography.labelMedium, color = Color.White)
            Spacer(Modifier.weight(1f))
            Text(formatTime(controller.duration), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
        }
    }
}

/** A round icon button, grown and outlined when focused, its name shown beneath only then. */
@Composable
private fun ControlButton(
    icon: Int,
    label: Int,
    enabled: Boolean = true,
    large: Boolean = false,
    painter: androidx.compose.ui.graphics.painter.Painter? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier.width(if (large) 84.dp else 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier
                .size(if (large) 60.dp else 48.dp)
                .onFocusChanged { focused = it.isFocused }
                .then(if (enabled) Modifier.pointerClick(onClick = onClick) else Modifier),
            scale = IconButtonDefaults.scale(focusedScale = 1.12f),
            colors = IconButtonDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.12f),
                contentColor = Color.White,
                focusedContainerColor = MaterialTheme.colorScheme.primary,
                focusedContentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Icon(
                painter ?: painterResource(icon),
                contentDescription = stringResource(label),
                modifier = Modifier.size(if (painter != null) 34.dp else 24.dp),
            )
        }
        Text(
            if (focused) stringResource(label) else "",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * The player's audio and subtitle choices for a remote, in place of the phone's bottom sheets: the
 * current one focused, a press picks it. The sheets were the phone's, small and in its light theme.
 */
@Composable
private fun ChoiceDialog(choice: TvPlayerController.Choice, onDismiss: () -> Unit) {
    val current = remember { FocusRequester() }
    LaunchedEffect(choice) {
        androidx.compose.runtime.withFrameNanos { }
        runCatching { current.requestFocus() }
    }
    TvDialog(onDismissRequest = onDismiss) {
        androidx.tv.material3.Surface(shape = CARD_SHAPE, modifier = Modifier.width(460.dp)) {
            Column(Modifier.padding(24.dp)) {
                Text(
                    choice.title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                val focusAt = choice.options.indexOfFirst { it.selected }.coerceAtLeast(0)
                FocusRestingAt(48.dp) {
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(choice.options.size) { i ->
                            val option = choice.options[i]
                            val pick = {
                                onDismiss()
                                option.pick()
                            }
                            androidx.tv.material3.ListItem(
                                selected = option.selected,
                                onClick = pick,
                                headlineContent = { Text(option.label) },
                                trailingContent = { androidx.tv.material3.RadioButton(selected = option.selected, onClick = null) },
                                modifier = (if (i == focusAt) Modifier.focusRequester(current) else Modifier).pointerClick(onClick = pick),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A button over the picture, focused the way the overlay's own controls are: the accent colour and
 * the same growth. Unfocused, a dark fill rather than theirs — these float over the picture itself,
 * where the controls sit on a gradient.
 */
@Composable
private fun PromptButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: Int? = null) {
    androidx.tv.material3.Button(
        onClick = onClick,
        modifier = modifier.pointerClick(onClick = onClick),
        scale = androidx.tv.material3.ButtonDefaults.scale(focusedScale = 1.12f),
        colors = androidx.tv.material3.ButtonDefaults.colors(
            containerColor = Color.Black.copy(alpha = 0.6f),
            contentColor = Color.White,
            focusedContainerColor = MaterialTheme.colorScheme.primary,
            focusedContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall)
        if (icon != null) Icon(
            painterResource(icon),
            contentDescription = null,
            modifier = Modifier.padding(start = 8.dp).size(20.dp),
        )
    }
}

/** "Skip Opening ⏩", as the phone's reads: OK presses it with the controls hidden; with them up, it has the focus first. */
@Composable
private fun SkipPill(label: String, focus: FocusRequester, onSkip: () -> Unit) {
    PromptButton(label, onSkip, Modifier.focusRequester(focus), icon = R.drawable.ic_round_fast_forward_24)
}

/**
 * The next episode over its thumbnail, its countdown, and its two buttons: Play now (OK) and
 * Cancel (back, with the controls hidden; a button of its own with them up).
 */
@OptIn(com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi::class)
@Composable
private fun UpNextCard(next: TvPlayerController.UpNext, controller: TvPlayerController, focus: FocusRequester) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .width(380.dp)
            .clip(shape)
            .background(Color.Black, shape)
            .border(1.dp, Color.White.copy(alpha = 0.15f), shape)
    ) {
        if (next.thumb != null) {
            com.bumptech.glide.integration.compose.GlideImage(
                model = next.thumb,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
            // Darker towards the text, so it reads over any picture.
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.85f)))
                )
            )
        }
        Column(Modifier.padding(20.dp)) {
            Text(
                next.countdown ?: stringResource(R.string.up_next),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                next.label,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                PromptButton(stringResource(R.string.play_now), { controller.act { playNext() } }, Modifier.focusRequester(focus))
                Spacer(Modifier.width(12.dp))
                PromptButton(
                    stringResource(if (controller.visible) R.string.cancel else R.string.tv_back_cancels),
                    { controller.act { cancelNext() } },
                )
            }
        }
    }
}
