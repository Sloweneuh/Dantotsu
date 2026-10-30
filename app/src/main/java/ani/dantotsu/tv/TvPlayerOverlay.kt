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
    }

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
    LaunchedEffect(controller.visible) {
        if (controller.visible) runCatching { playButton.requestFocus() }
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
                    ControlButton(
                        if (controller.playing) R.drawable.ic_round_pause_24 else R.drawable.ic_round_play_arrow_24,
                        if (controller.playing) R.string.pause else R.string.tv_play,
                        large = true,
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
            Icon(painterResource(icon), contentDescription = stringResource(label))
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
