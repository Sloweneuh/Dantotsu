package ani.dantotsu.others

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.OverScroller
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlin.math.abs

/**
 * The ChipGroup of a titled chip row, which keeps a chip longer than the row whole once the row
 * is expanded.
 *
 * Wrapping, FlowLayout measures each chip against the row's width, so a long synonym would come
 * out ellipsized - and Chip refuses multi-line text. Here the chips keep their natural width, so
 * such a chip gets a line of its own and runs past the row's edge, and dragging it sideways
 * scrolls (and flings) that chip alone, the way the collapsed row scrolls - the rest of the block
 * stays where it is.
 */
class ChipFlowGroup @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.chipGroupStyle,
) : ChipGroup(context, attrs, defStyleAttr) {

    private val touchSlop: Int
    private val minFlingVelocity: Int
    private val maxFlingVelocity: Int
    private val fadePaint = Paint().apply {
        shader = LinearGradient(0f, 0f, 1f, 0f, 0xFF000000.toInt(), 0, Shader.TileMode.CLAMP)
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    private val fadeMatrix = Matrix()

    init {
        val config = ViewConfiguration.get(context)
        touchSlop = config.scaledTouchSlop
        minFlingVelocity = config.scaledMinimumFlingVelocity
        maxFlingVelocity = config.scaledMaximumFlingVelocity
    }

    override fun measureChild(child: View, parentWidthMeasureSpec: Int, parentHeightMeasureSpec: Int) {
        if (isSingleLine) return super.measureChild(child, parentWidthMeasureSpec, parentHeightMeasureSpec)
        child.measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            ViewGroup.getChildMeasureSpec(parentHeightMeasureSpec, paddingTop + paddingBottom, child.layoutParams.height)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // A chip scrolled further than a new layout (a rotation, the row collapsing) allows.
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.translationX = child.translationX.coerceIn(-maxScroll(child).toFloat(), 0f)
        }
    }

    /**
     * Fades a long chip at the row's edges, as its [ChipScrollView] fades the collapsed row: same
     * place (the group's edges, where the chip is clipped), same length, and the same strength -
     * how much is left to scroll that way, over the fade's length. Drawn on the chip alone, since a
     * fade across the whole block would eat the ends of the chips on the other lines.
     */
    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        val max = maxScroll(child)
        val length = (parent as? View)?.horizontalFadingEdgeLength ?: 0
        if (max <= 0 || length <= 0) return super.drawChild(canvas, child, drawingTime)
        val scrolled = -child.translationX
        val leftStrength = (scrolled / length).coerceIn(0f, 1f)
        val rightStrength = ((max - scrolled) / length).coerceIn(0f, 1f)
        val drawLeft = leftStrength * length > 1f
        val drawRight = rightStrength * length > 1f
        if (!drawLeft && !drawRight) return super.drawChild(canvas, child, drawingTime)

        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val top = child.top.toFloat()
        val bottom = child.bottom.toFloat()
        val layer = canvas.saveLayer(left, top, right, bottom, null)
        val drawn = super.drawChild(canvas, child, drawingTime)
        if (drawLeft) {
            fadeMatrix.setScale(length * leftStrength, 1f)
            fadeMatrix.postTranslate(left, 0f)
            fadePaint.shader.setLocalMatrix(fadeMatrix)
            canvas.drawRect(left, top, left + length, bottom, fadePaint)
        }
        if (drawRight) {
            fadeMatrix.setScale(-length * rightStrength, 1f)
            fadeMatrix.postTranslate(right, 0f)
            fadePaint.shader.setLocalMatrix(fadeMatrix)
            canvas.drawRect(right - length, top, right, bottom, fadePaint)
        }
        canvas.restoreToCount(layer)
        return drawn
    }

    override fun onViewAdded(child: View?) {
        super.onViewAdded(child)
        (child as? Chip)?.let(::makeScrollable)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeScrollable(chip: Chip) {
        var downX = 0f
        var downY = 0f
        var startTranslation = 0f
        var dragging = false
        var velocity: VelocityTracker? = null
        // The scroller HorizontalScrollView flings with, so this chip glides like the collapsed row.
        val scroller = OverScroller(context)
        val flingStep = object : Runnable {
            override fun run() {
                if (!scroller.computeScrollOffset()) return
                scrollChip(chip, -scroller.currX.toFloat())
                chip.postOnAnimation(this)
            }
        }

        // Velocity in screen coordinates: the chip moves under the finger, so its own drift.
        fun track(event: MotionEvent) {
            val screen = MotionEvent.obtain(event)
            screen.setLocation(event.rawX, event.rawY)
            velocity?.addMovement(screen)
            screen.recycle()
        }

        chip.setOnTouchListener { v, event ->
            val max = maxScroll(v)
            if (max <= 0 && !dragging) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    scroller.forceFinished(true)
                    downX = event.rawX
                    downY = event.rawY
                    startTranslation = v.translationX
                    dragging = false
                    velocity?.recycle()
                    velocity = VelocityTracker.obtain()
                    track(event)
                    // Claimed up front, or the enclosing scroll views take the drag first.
                    v.parent.requestDisallowInterceptTouchEvent(true)
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    track(event)
                    // Raw coordinates: the chip moves under the finger, so its own would drift.
                    var dx = event.rawX - downX
                    if (!dragging) {
                        when {
                            abs(dx) > touchSlop -> {
                                dragging = true
                                // Start from where the drag was recognised, so the chip doesn't jump.
                                downX += if (dx > 0) touchSlop else -touchSlop
                                dx = event.rawX - downX
                                // End the press, so letting go doesn't click and holding doesn't copy.
                                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                                v.onTouchEvent(cancel)
                                cancel.recycle()
                            }
                            // A vertical drag is the page's.
                            abs(event.rawY - downY) > touchSlop ->
                                v.parent.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                    if (dragging) scrollChip(v, (startTranslation + dx).coerceIn(-max.toFloat(), 0f))
                    dragging
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val wasDragging = dragging
                    if (dragging && event.actionMasked == MotionEvent.ACTION_UP) {
                        track(event)
                        val tracker = velocity
                        tracker?.computeCurrentVelocity(1000, maxFlingVelocity.toFloat())
                        val vx = tracker?.xVelocity ?: 0f
                        if (abs(vx) > minFlingVelocity) {
                            scroller.fling(-v.translationX.toInt(), 0, -vx.toInt(), 0, 0, max, 0, 0)
                            v.postOnAnimation(flingStep)
                        }
                    }
                    dragging = false
                    velocity?.recycle()
                    velocity = null
                    wasDragging
                }

                else -> dragging
            }
        }
    }

    /**
     * Moves a long chip. The group is redrawn with it: a translation alone only updates the chip's
     * own render node, which would leave [drawChild]'s fades as they were first drawn.
     */
    private fun scrollChip(chip: View, translation: Float) {
        chip.translationX = translation
        invalidate()
    }

    /** How far a chip runs past the group's end - nothing unless it is wider than its line. */
    private fun maxScroll(child: View): Int {
        if (isSingleLine) return 0
        val endMargin = (child.layoutParams as? MarginLayoutParams)?.rightMargin ?: 0
        return (child.right + endMargin - (width - paddingRight)).coerceAtLeast(0)
    }
}
