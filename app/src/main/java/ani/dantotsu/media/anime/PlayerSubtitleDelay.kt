package ani.dantotsu.media.anime

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory

/**
 * Shifts the subtitles against the picture: [delayMs] later (negative: earlier). The player's text
 * renderers are asked for the cues at the playback position minus the delay, so the subtitles are
 * still the player's own, styled and placed as ever. Read on every render, so a change shows at once.
 *
 * Earlier than the picture needs the cues ahead of the playback position: a subtitle file loaded
 * on its own is read whole up front, but subtitles inside the stream only as far as it has buffered.
 * Subtitles libass draws itself are timed by it and aren't shifted.
 */
@OptIn(UnstableApi::class)
class PlayerSubtitleDelay {
    @Volatile
    var delayMs = 0L

    fun wrap(factory: RenderersFactory) = RenderersFactory { handler, video, audio, text, metadata ->
        factory.createRenderers(handler, video, audio, text, metadata).map { renderer ->
            if (renderer.trackType == C.TRACK_TYPE_TEXT) Delayed(renderer) else renderer
        }.toTypedArray()
    }

    private inner class Delayed(renderer: Renderer) : ForwardingRenderer(renderer) {
        override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
            super.render((positionUs - delayMs * 1000).coerceAtLeast(0), elapsedRealtimeUs)
        }
    }
}
