package ani.dantotsu.others

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.drawable.toBitmap
import ani.dantotsu.R
import ani.dantotsu.getThemeColor
import ani.dantotsu.util.animationsEnabled
import kotlin.math.floor
import kotlin.math.sin

/**
 * A quiet animated backdrop: one icon tiled in a staggered grid that slowly drifts diagonally,
 * each copy turning gently with its own phase. Tinted from the theme at low opacity so it sits
 * behind content without competing with it.
 *
 * It only animates while actually visible (paused behind other screens, with the screen off, or
 * when hidden) and draws a still pattern when the app's layout animations are off. Frames are
 * capped at ~30fps: the drift is slow enough that more would only cost battery.
 *
 * Attributes: `app:patternIcon` (drawable), `app:patternSize` and `app:patternSpacing`
 * (dimensions), `app:patternOpacity` (0..1), `app:patternSpeed` (dp per second), and
 * `app:patternAnchor`: a view (found in this view's ancestors) the pattern belongs to. The pattern
 * only shows while the anchor is shown — for empty-state messages that come and go — and if the
 * anchor is an ImageView without an explicit `patternIcon`, its current image is the tiled icon,
 * so a logo set from code (a tracker's, an error icon) carries into the pattern.
 */
class PatternBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private var iconSize = 28f * density
    private var spacing = 76f * density
    private var speed = 10f * density
    private var opacity = 0.08f
    private var iconRes = R.drawable.ic_round_explore_24
    private var tile: Bitmap? = null
    private var tileSource: Any? = null
    private var explicitIcon = false
    private var anchorId = NO_ID
    private val layoutListener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
        // The anchor came or went (an empty state), or its image changed (a logo set from code).
        // GONE rather than just blank, so the pattern takes no room in its parent either.
        val wanted = if (anchor()?.visibility == VISIBLE) VISIBLE else GONE
        if (visibility != wanted) visibility = wanted
        invalidate()
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private var running = false
    private val startTime = SystemClock.uptimeMillis()
    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            invalidate()
            postOnAnimationDelayed(this, FRAME_MS)
        }
    }

    init {
        context.obtainStyledAttributes(attrs, R.styleable.PatternBackgroundView).apply {
            explicitIcon = hasValue(R.styleable.PatternBackgroundView_patternIcon)
            iconRes = getResourceId(R.styleable.PatternBackgroundView_patternIcon, iconRes)
            anchorId = getResourceId(R.styleable.PatternBackgroundView_patternAnchor, NO_ID)
            iconSize = getDimension(R.styleable.PatternBackgroundView_patternSize, iconSize)
            spacing = getDimension(R.styleable.PatternBackgroundView_patternSpacing, spacing)
            opacity = getFloat(R.styleable.PatternBackgroundView_patternOpacity, opacity)
            speed = getDimension(R.styleable.PatternBackgroundView_patternSpeed, speed)
            recycle()
        }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        // Anchored patterns wait for the anchor's first layout before showing.
        if (anchorId != NO_ID) visibility = GONE
    }

    /** Swaps the tiled icon (e.g. per discovery source). */
    fun setPatternIcon(res: Int) {
        explicitIcon = true
        if (res == iconRes) return
        iconRes = res
        tile = null
        invalidate()
    }

    /** The anchor, looked up through this view's ancestors (it's usually a sibling). */
    private fun anchor(): View? {
        if (anchorId == NO_ID) return null
        var p = parent
        while (p is View) {
            p.findViewById<View>(anchorId)?.let { return it }
            p = p.parent
        }
        return null
    }

    private fun tile(): Bitmap? {
        val anchorImage = if (explicitIcon) null else (anchor() as? ImageView)?.drawable
        val source: Any = anchorImage ?: iconRes
        if (tile != null && source == tileSource) return tile
        val drawable = (anchorImage?.constantState?.newDrawable(resources)
            ?: ContextCompat.getDrawable(context, iconRes))?.mutate() ?: return null
        DrawableCompat.setTint(drawable, context.getThemeColor(androidx.appcompat.R.attr.colorPrimary))
        tileSource = source
        return drawable.toBitmap(iconSize.toInt(), iconSize.toInt()).also { tile = it }
    }

    /**
     * A backdrop never sizes its container: zero unless told an exact size. Inside a
     * wrap_content FrameLayout that's measured by its real content, and the FrameLayout then
     * re-measures this (match_parent) to fill it.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        fun size(spec: Int) =
            if (MeasureSpec.getMode(spec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(spec) else 0
        setMeasuredDimension(size(widthMeasureSpec), size(heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val bitmap = tile() ?: return
        paint.alpha = (opacity * 255).toInt()
        val seconds = if (animationsEnabled) (SystemClock.uptimeMillis() - startTime) / 1000f else 0f
        // The staggered grid repeats every cell across and every two cells down, so the drift is
        // wrapped to that period and the grid stays seamless. Whole cells wrapped away are
        // counted, so each icon keeps its own rotation phase as it travels.
        val travel = seconds * speed
        val offX = travel % spacing
        val offY = travel % (spacing * 2)
        val wrappedCols = floor(travel / spacing).toInt()
        val wrappedRows = floor(travel / (spacing * 2)).toInt() * 2
        val half = iconSize / 2
        val rows = (height / spacing).toInt() + 2
        val cols = (width / spacing).toInt() + 2
        for (r in -2..rows) {
            // Every other row sits half a cell over: a staggered grid reads less mechanical.
            val stagger = if (Math.floorMod(r, 2) == 0) 0f else spacing / 2
            val py = r * spacing + offY - half
            for (c in -2..cols) {
                val px = c * spacing + stagger + offX - half
                val angle = if (animationsEnabled) {
                    sin(seconds * 0.6f + (r - wrappedRows) * 0.9f + (c - wrappedCols) * 1.3f) * 18f
                } else 0f
                canvas.save()
                canvas.rotate(angle, px + half, py + half)
                canvas.drawBitmap(bitmap, px, py, paint)
                canvas.restore()
            }
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        setRunning(isVisible && isAttachedToWindow)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (anchorId != NO_ID) viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
    }

    override fun onDetachedFromWindow() {
        setRunning(false)
        viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        super.onDetachedFromWindow()
    }

    private fun setRunning(run: Boolean) {
        val shouldRun = run && animationsEnabled
        if (shouldRun == running) return
        running = shouldRun
        removeCallbacks(frame)
        if (running) postOnAnimation(frame)
    }

    private companion object {
        const val FRAME_MS = 32L
    }
}
