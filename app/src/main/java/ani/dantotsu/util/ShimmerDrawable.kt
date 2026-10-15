package ani.dantotsu.util

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import androidx.core.graphics.ColorUtils
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.transition.DrawableCrossFadeFactory

/**
 * A soft diagonal shine sweeping across an image that's still loading, over whatever sits behind
 * it (a cover's grey card). It keeps itself going by redrawing, and stops on its own once the
 * loaded image has faded in over it and it is no longer drawn; see [shineCrossFade].
 */
class ShimmerDrawable(private val highlight: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shift = Matrix()
    // Starts a little into the first sweep, with the band already coming in over the left edge:
    // most images land within a few hundred ms, so a shine that has to enter first barely shows.
    private val started = SystemClock.uptimeMillis() - (SWEEP_MS * 0.3f).toLong()
    private var band = 0f

    override fun onBoundsChange(bounds: Rect) {
        band = bounds.width() * 0.75f
        paint.shader = LinearGradient(
            0f, 0f, band, band * 0.5f,
            intArrayOf(Color.TRANSPARENT, highlight, Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun draw(canvas: Canvas) {
        val area = bounds
        if (area.isEmpty) return
        val progress = ((SystemClock.uptimeMillis() - started) % CYCLE_MS).toFloat() / SWEEP_MS
        if (progress <= 1f) {
            // From fully off the left edge to fully past the bottom-right corner. The gradient runs
            // along (band, band / 2), so a point sits at (x - shift) + (y - top) / 2 along it and
            // the band is 1.25 × band long on that axis: it clears the top-left corner once the
            // shift is that far left of it, and the bottom-right only once the shift has gone
            // half the height beyond the right edge.
            val from = area.left - band * 1.25f
            val to = area.right + area.height() * 0.5f
            shift.setTranslate(from + (to - from) * progress, area.top.toFloat())
            paint.shader.setLocalMatrix(shift)
            canvas.drawRect(area, paint)
        }
        invalidateSelf()
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        private const val SWEEP_MS = 650L
        private const val CYCLE_MS = 950L

        /** A shine for [context]'s theme, or null when layout animations are off. */
        fun loading(context: Context): Drawable? {
            if (!animationsEnabled) return null
            val night = context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            return ShimmerDrawable(ColorUtils.setAlphaComponent(Color.WHITE, if (night) 28 else 110))
        }
    }
}

/**
 * Glide's cross-fade with the outgoing drawable faded out too. Its default keeps drawing the
 * placeholder under the image for good, which would leave a [ShimmerDrawable] redrawing forever.
 */
val shineCrossFade: DrawableTransitionOptions = DrawableTransitionOptions.withCrossFade(
    DrawableCrossFadeFactory.Builder().setCrossFadeEnabled(true).build()
)
