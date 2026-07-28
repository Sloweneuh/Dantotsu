package ani.dantotsu.media.screenshot

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Typeface
import android.text.TextPaint
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.CanvasOverlay
import io.github.peerless2012.ass.AssRender
import io.github.peerless2012.ass.AssTexType

/**
 * Burns subtitles into an exported clip, one video frame at a time.
 *
 * This is a media3 [CanvasOverlay], so the same object works for both subtitle kinds even though
 * they reach us very differently:
 *
 *  - **ASS/SSA** is re-rendered by libass at the exact timestamp of each frame, which preserves the
 *    original positioning, styling and typesetting. [assRender] must be a renderer dedicated to
 *    this export — sharing the player's would fight it over frame size — with the track already set.
 *  - **Plain text cues** can't be re-rendered (ExoPlayer only surfaces them as they play), so they
 *    are replayed from the [SubtitleCueBuffer] snapshot the player recorded, drawn to approximate
 *    the user's configured subtitle style.
 *
 * The same overlay drives the trim preview, which is why the mapping from a frame to a point on the
 * episode timeline is left to a [TimeSource] — an export and a live player disagree about what a
 * presentation timestamp means.
 */
@OptIn(UnstableApi::class)
class ClipSubtitleOverlay(
    private val time: TimeSource,
    private val videoWidth: Int,
    private val videoHeight: Int,
    private val assRender: AssRender?,
    private val cues: List<SubtitleCueBuffer.Cue>,
    private val textStyle: TextStyle?,
) : CanvasOverlay(true) {

    /** Maps a frame's presentation timestamp onto the episode's own timeline, in milliseconds. */
    fun interface TimeSource {
        fun mediaTimeMs(presentationTimeUs: Long): Long
    }

    /**
     * How the player was drawing text cues on screen, so the burned-in copy matches.
     *
     * Sizes are in the pixels they were measured in — screen pixels, since that's where the player
     * drew them — and [referenceHeight] is the height they were measured against. An exported frame
     * is almost never that size, so everything is rescaled by the ratio between the two.
     */
    data class TextStyle(
        val typeface: Typeface?,
        val textColor: Int,
        val outlineColor: Int,
        val backgroundColor: Int,
        val textSizePx: Float,
        val strokeWidthPx: Float,
        val alpha: Float,
        val bottomMarginPx: Float,
        val outlined: Boolean,
        val referenceHeight: Int,
    ) {
        /** Rescales the style onto a frame [factor]x the height it was measured against. */
        fun scaleTo(factor: Float) = copy(
            textSizePx = textSizePx * factor,
            strokeWidthPx = strokeWidthPx * factor,
            bottomMarginPx = bottomMarginPx * factor,
        )
    }

    /** Tints the alpha masks libass hands back; SRC_OVER so overlapping glyphs blend correctly. */
    private val assPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
    }

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var scaledStyle: TextStyle? = null

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        assRender?.apply {
            // Storage size is the resolution the script was authored against; frame size is what
            // we're rendering onto. Giving libass both lets it scale the typesetting for us. When
            // no source size was supplied the frame *is* the video (nothing has rescaled it yet),
            // so the two coincide.
            val storageWidth = if (videoWidth > 0) videoWidth else videoSize.width
            val storageHeight = if (videoHeight > 0) videoHeight else videoSize.height
            setStorageSize(storageWidth, storageHeight)
            setFrameSize(videoSize.width, videoSize.height)
        }
        scaledStyle = textStyle?.let {
            if (it.referenceHeight > 0) {
                it.scaleTo(videoSize.height.toFloat() / it.referenceHeight)
            } else it
        }
    }

    override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
        val mediaTimeMs = time.mediaTimeMs(presentationTimeUs)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        if (assRender != null) drawAss(canvas, mediaTimeMs) else drawTextCues(canvas, mediaTimeMs)
    }

    private fun drawAss(canvas: Canvas, mediaTimeMs: Long) {
        // Rendered synchronously: an export has no frame deadline to miss, and skipping a frame
        // here would bake a subtitle drop into the file.
        val frame = runCatching {
            assRender?.renderFrame(mediaTimeMs, AssTexType.BITMAP_ALPHA)
        }.getOrNull() ?: return

        frame.images?.forEach { image ->
            val bitmap = image.bitmap ?: return@forEach
            // libass packs the colour as RGBA with an *inverted* alpha byte.
            val r = image.color shr 24 and 0xFF
            val g = image.color shr 16 and 0xFF
            val b = image.color shr 8 and 0xFF
            val a = (0xFF - image.color) and 0xFF
            assPaint.color = (a shl 24) or (r shl 16) or (g shl 8) or b
            canvas.drawBitmap(bitmap, image.x.toFloat(), image.y.toFloat(), assPaint)
        }
    }

    private fun drawTextCues(canvas: Canvas, mediaTimeMs: Long) {
        val style = scaledStyle ?: return
        if (style.alpha <= 0f) return
        val active = cues.filter { mediaTimeMs >= it.startMs && mediaTimeMs < it.endMs }
        if (active.isEmpty()) return

        val lines = active.flatMap { it.text.split('\n') }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return

        textPaint.apply {
            typeface = style.typeface
            textSize = style.textSizePx
            textAlign = Paint.Align.CENTER
        }
        val alpha = (style.alpha.coerceIn(0f, 1f) * 255).toInt()
        val lineHeight = textPaint.fontSpacing
        val centerX = canvas.width / 2f
        // Stack upwards from the bottom margin so the last line sits where a single line would.
        var baseline = canvas.height - style.bottomMarginPx - textPaint.descent() -
            lineHeight * (lines.size - 1)

        lines.forEach { line ->
            if (Color.alpha(style.backgroundColor) > 0) {
                backgroundPaint.color = style.backgroundColor
                backgroundPaint.alpha =
                    (Color.alpha(style.backgroundColor) * style.alpha).toInt().coerceIn(0, 255)
                val half = textPaint.measureText(line) / 2f
                canvas.drawRect(
                    centerX - half, baseline + textPaint.ascent(),
                    centerX + half, baseline + textPaint.descent(), backgroundPaint
                )
            }
            if (style.outlined && style.strokeWidthPx > 0f) {
                textPaint.style = Paint.Style.STROKE
                textPaint.strokeWidth = style.strokeWidthPx
                textPaint.color = style.outlineColor
                textPaint.alpha = alpha
                canvas.drawText(line, centerX, baseline, textPaint)
            }
            textPaint.style = Paint.Style.FILL
            textPaint.color = style.textColor
            textPaint.alpha = alpha
            canvas.drawText(line, centerX, baseline, textPaint)
            baseline += lineHeight
        }
    }

    companion object {
        /**
         * For an export. Transformer rebases a clipped item's timestamps to start near zero, so the
         * first frame that arrives is taken as [clipStartMs] and the rest follow from it.
         */
        fun rebasedFrom(clipStartMs: Long) = object : TimeSource {
            private var firstPresentationTimeUs = C.TIME_UNSET

            override fun mediaTimeMs(presentationTimeUs: Long): Long {
                if (firstPresentationTimeUs == C.TIME_UNSET) {
                    firstPresentationTimeUs = presentationTimeUs
                }
                return clipStartMs + (presentationTimeUs - firstPresentationTimeUs) / 1000
            }
        }

        /**
         * For a live player, which already knows where it is. [position] is read per frame rather
         * than trusting presentation timestamps, so seeking and looping stay in sync.
         */
        fun followingPlayer(offsetMs: Long, position: () -> Long) =
            TimeSource { offsetMs + position() }
    }
}
