package ani.dantotsu.settings.quicktiles

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.view.isVisible
import androidx.viewpager2.widget.ViewPager2
import ani.dantotsu.getThemeColor

/**
 * The dot-and-dash page indicator Android uses under its quick settings pages: every page is a
 * dot, the current one stretches into a dash, and the dash slides with the swipe rather than
 * jumping when the page settles.
 *
 * Drawn rather than assembled from views because the interesting part is the in-between state,
 * which means interpolating position and width on every scroll frame.
 */
class QuickTilePageIndicator @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val dotSize = 6f * density
    private val dashWidth = 20f * density
    private val gap = 6f * density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var pageCount = 0
    private var position = 0
    private var positionOffset = 0f

    private val activeColor
        get() = context.getThemeColor(com.google.android.material.R.attr.colorPrimary)
    private val inactiveColor
        get() = context.getThemeColor(com.google.android.material.R.attr.colorOutline)

    private var callback: ViewPager2.OnPageChangeCallback? = null

    /** Follows [pager] until [detach] or another pager is attached. */
    fun attachTo(pager: ViewPager2, pages: Int) {
        detach()
        pageCount = pages
        position = pager.currentItem.coerceIn(0, (pages - 1).coerceAtLeast(0))
        positionOffset = 0f
        // A single page has nothing to indicate, and two identical dots would only be noise.
        isVisible = pages > 1
        requestLayout()
        invalidate()

        callback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(p: Int, offset: Float, offsetPixels: Int) {
                position = p
                positionOffset = offset
                invalidate()
            }
        }.also { pager.registerOnPageChangeCallback(it) }
        pagerRef = pager
    }

    private var pagerRef: ViewPager2? = null

    fun detach() {
        callback?.let { pagerRef?.unregisterOnPageChangeCallback(it) }
        callback = null
        pagerRef = null
    }

    override fun onDetachedFromWindow() {
        detach()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // One dash plus a dot for every other page, with the gaps between them.
        val width = if (pageCount <= 0) 0f else
            dashWidth + (pageCount - 1) * dotSize + (pageCount - 1) * gap
        setMeasuredDimension(
            resolveSize(width.toInt() + paddingStart + paddingEnd, widthMeasureSpec),
            resolveSize((dotSize + paddingTop + paddingBottom).toInt(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (pageCount <= 1) return
        val totalWidth = dashWidth + (pageCount - 1) * dotSize + (pageCount - 1) * gap
        var x = (width - totalWidth) / 2f
        val centerY = height / 2f
        val radius = dotSize / 2f

        for (i in 0 until pageCount) {
            // How much of the dash this page currently owns: 1 at rest on it, sliding to 0 as the
            // next page takes over.
            val share = when (i) {
                position -> 1f - positionOffset
                position + 1 -> positionOffset
                else -> 0f
            }
            val itemWidth = dotSize + (dashWidth - dotSize) * share
            paint.color = if (share > 0f) activeColor else inactiveColor
            paint.alpha = if (share > 0f) (255 * (0.4f + 0.6f * share)).toInt() else 110
            rect.set(x, centerY - radius, x + itemWidth, centerY + radius)
            canvas.drawRoundRect(rect, radius, radius, paint)
            x += itemWidth + gap
        }
    }
}
